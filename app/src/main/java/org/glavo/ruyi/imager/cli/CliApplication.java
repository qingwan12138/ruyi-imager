// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.glavo.ruyi.imager.core.AppServices;
import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.ProvisionStrategies;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootDevice;
import org.glavo.ruyi.imager.core.flash.FlashRequest;
import org.glavo.ruyi.imager.core.flash.FlashTarget;
import org.glavo.ruyi.imager.core.image.ImageCatalog;
import org.glavo.ruyi.imager.core.image.ImageCacheCleanResult;
import org.glavo.ruyi.imager.core.image.ImageCacheEntry;
import org.glavo.ruyi.imager.core.image.ImageCacheRetainedEntry;
import org.glavo.ruyi.imager.core.image.ImageCacheService;
import org.glavo.ruyi.imager.core.image.ImageCacheUsage;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.glavo.ruyi.imager.i18n.Messages;
import org.glavo.ruyi.imager.logging.LogRedactor;
import org.glavo.ruyi.imager.logging.LoggingProgressReporter;
import org.glavo.ruyi.imager.logging.RuyiLogging;
import org.glavo.ruyi.imager.update.BuildInfo;
import org.glavo.ruyi.imager.update.PreparedUpdate;
import org.glavo.ruyi.imager.update.UpdateChannel;
import org.glavo.ruyi.imager.update.UpdateCheckResult;
import org.glavo.ruyi.imager.update.UpdateChecker;
import org.glavo.ruyi.imager.update.UpdateInstaller;
import org.glavo.ruyi.imager.update.UpdatePackageManager;
import org.glavo.ruyi.imager.update.UpdateSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Picocli command tree for scriptable Ruyi Imager operations.
@Command(
        name = "ruyi-imager-cli",
        description = "Flash Ruyi images from a CLI or GUI.")
@NotNullByDefault
public final class CliApplication implements Callable<Integer> {
    /// Logger for CLI command execution.
    private static final Logger LOGGER = LoggerFactory.getLogger(CliApplication.class);

    /// Picocli command specification injected at runtime.
    @Spec
    private @Nullable CommandSpec spec;

    /// Whether usage help should be displayed.
    @Option(
            names = {"-h", "--help"},
            usageHelp = true,
            scope = ScopeType.INHERIT,
            description = "Show this help message and exit.",
            descriptionKey = "cli.option.help")
    private boolean usageHelp;

    /// Whether version help should be displayed.
    @Option(
            names = {"-V", "--version"},
            versionHelp = true,
            description = "Print version information and exit.",
            descriptionKey = "cli.option.version")
    private boolean versionHelp;

    /// Configured log level inherited by subcommands.
    @Option(
            names = "--log-level",
            scope = ScopeType.INHERIT,
            paramLabel = "LEVEL",
            description = "Set log level.",
            descriptionKey = "cli.option.logLevel")
    private @Nullable String logLevel;

    /// Whether debug logging is enabled.
    @Option(
            names = "--verbose",
            scope = ScopeType.INHERIT,
            description = "Enable debug logging.",
            descriptionKey = "cli.option.verbose")
    private boolean verbose;

    /// Configured log file inherited by subcommands.
    @Option(
            names = "--log-file",
            scope = ScopeType.INHERIT,
            paramLabel = "PATH",
            description = "Write logs to this file.",
            descriptionKey = "cli.option.logFile")
    private @Nullable Path logFile;

    /// Creates the command root.
    private CliApplication() {
    }

    /// Executes CLI arguments.
    ///
    /// @param services shared application services.
    /// @param args command-line arguments.
    /// @return process exit code.
    public static int run(AppServices services, String @Unmodifiable [] args) {
        RuyiLogging.configure(
                services.directories(),
                RuyiLogging.cliLevel(optionValue(args, "--log-level"), hasVerboseFlag(args)),
                logFileOptionValue(args));
        LOGGER.atInfo().log(() -> "CLI command started. args=" + LogRedactor.redactCommand(List.of(args)));
        try {
            CliApplication root = new CliApplication();
            CommandLine commandLine = new CommandLine(root);
            commandLine.getCommandSpec().version(BuildInfo.current().version());
            commandLine.addSubcommand("repo", repoCommand(services));
            commandLine.addSubcommand("image", imageCommand(services));
            commandLine.addSubcommand("device", deviceCommand(services));
            commandLine.addSubcommand("flash", new FlashCommand(services));
            if (BuildInfo.current().applicationUpdatesEnabled()) {
                commandLine.addSubcommand("check-update", new CheckUpdateCommand(services));
            }
            localizeCommands(commandLine);
            configureOutput(commandLine);
            commandLine.setExecutionStrategy(CliApplication::executeCommand);
            int exitCode = commandLine.execute(args);
            LOGGER.atInfo().log(() -> "CLI command finished. exitCode=" + exitCode);
            return exitCode;
        } finally {
            RuyiLogging.shutdown();
        }
    }

    /// Rejects unmatched arguments before handling help or executing the selected command.
    ///
    /// @param parseResult parsed command hierarchy.
    /// @return command exit code, including zero for an explicit help request.
    /// @throws CommandLine.UnmatchedArgumentException when any command has unrecognized arguments.
    /// @throws CommandLine.ExecutionException when command execution fails.
    private static int executeCommand(CommandLine.ParseResult parseResult) {
        for (@Nullable CommandLine.ParseResult current = parseResult; current != null; current = current.subcommand()) {
            if (!current.unmatched().isEmpty()) {
                throw new CommandLine.UnmatchedArgumentException(current.commandSpec().commandLine(), current.unmatched());
            }
        }
        return new CommandLine.RunLast().execute(parseResult);
    }

    /// Aligns Picocli usage output with the charset used by the standard streams.
    ///
    /// Picocli resolves the `sun.stdout.encoding` property, which current JDKs no longer provide, and
    /// falls back to the default charset; the application itself writes through `System.out`, whose
    /// charset follows the console. Routing usage output through the stream charset keeps a single
    /// encoding, so help text and command messages stay readable on legacy Windows code pages.
    ///
    /// @param commandLine root command line.
    private static void configureOutput(CommandLine commandLine) {
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, System.out.charset()), true);
        PrintWriter err = new PrintWriter(new OutputStreamWriter(System.err, System.err.charset()), true);
        applyWriters(commandLine, out, err);
    }

    /// Applies output writers to a command line and every nested subcommand.
    ///
    /// @param commandLine command line.
    /// @param out standard output writer.
    /// @param err standard error writer.
    private static void applyWriters(CommandLine commandLine, PrintWriter out, PrintWriter err) {
        commandLine.setOut(out);
        commandLine.setErr(err);
        for (CommandLine subcommand : commandLine.getSubcommands().values()) {
            applyWriters(subcommand, out, err);
        }
    }

    /// Prints root usage to standard error when no CLI subcommand was selected.
    ///
    /// @return the usage-error exit code.
    @Override
    public Integer call() {
        return printMissingSubcommandUsage(spec);
    }

    /// Builds the repository command group.
    ///
    /// @param services shared application services.
    /// @return configured command line.
    private static CommandLine repoCommand(AppServices services) {
        CommandLine commandLine = new CommandLine(new RepoCommand());
        commandLine.addSubcommand("update", new RepoUpdateCommand(services));
        return commandLine;
    }

    /// Builds the image command group.
    ///
    /// @param services shared application services.
    /// @return configured command line.
    private static CommandLine imageCommand(AppServices services) {
        CommandLine commandLine = new CommandLine(new ImageCommand());
        commandLine.addSubcommand("list", new ImageListCommand(services));
        commandLine.addSubcommand("download", new ImageDownloadCommand(services));
        commandLine.addSubcommand("cache", cacheCommand(services));
        return commandLine;
    }

    /// Builds the cache command group.
    ///
    /// @param services shared application services.
    /// @return configured command line.
    private static CommandLine cacheCommand(AppServices services) {
        CommandLine commandLine = new CommandLine(new ImageCacheCommand());
        commandLine.addSubcommand("list", new ImageCacheListCommand(services));
        commandLine.addSubcommand("clean", new ImageCacheCleanCommand(services));
        return commandLine;
    }

    /// Builds the device command group.
    ///
    /// @param services shared application services.
    /// @return configured command line.
    private static CommandLine deviceCommand(AppServices services) {
        CommandLine commandLine = new CommandLine(new DeviceCommand());
        commandLine.addSubcommand("list", new DeviceListCommand(services));
        return commandLine;
    }

    /// Prints usage to standard error for a command missing a subcommand.
    ///
    /// @param spec command specification.
    /// @return the usage-error exit code.
    private static int printMissingSubcommandUsage(@Nullable CommandSpec spec) {
        if (spec != null) {
            CommandLine commandLine = spec.commandLine();
            commandLine.usage(commandLine.getErr());
        }
        return CommandLine.ExitCode.USAGE;
    }

    /// Checks a local or HTTPS update manifest for a newer compatible application build.
    @Command(
            name = "check-update",
            description = "Check, verify, and launch an application update.")
    @NotNullByDefault
    private static final class CheckUpdateCommand implements Callable<Integer> {
        /// Shared application services.
        private final AppServices services;

        /// Optional local path or HTTPS update manifest URL.
        @Option(
                names = "--source",
                paramLabel = "SOURCE",
                description = "Read the update manifest from a local JSON file or HTTPS URL.",
                descriptionKey = "cli.option.updateSource")
        private @Nullable String source;

        /// Selected update channel.
        @Option(
                names = "--channel",
                paramLabel = "CHANNEL",
                converter = UpdateChannelConverter.class,
                description = "Select the update channel.",
                descriptionKey = "cli.option.updateChannel")
        private @Nullable UpdateChannel channel;

        /// Whether the installer package should be retrieved and verified.
        @Option(
                names = "--prepare",
                description = "Download and verify the installer package.",
                descriptionKey = "cli.option.updatePrepare")
        private boolean prepare;

        /// Whether the verified installer should be launched.
        @Option(
                names = "--install",
                description = "Download, verify, and launch the installer package.",
                descriptionKey = "cli.option.updateInstall")
        private boolean install;

        /// Creates the update check command.
        ///
        /// @param services shared application services.
        private CheckUpdateCommand(AppServices services) {
            this.services = services;
        }

        /// Checks the update manifest and prints the comparison result.
        ///
        /// @return process exit code.
        @Override
        public Integer call() {
            try {
                UpdateSource updateSource = source == null
                        ? UpdateChecker.configuredSource(services.directories())
                        : UpdateSource.parse(source);
                UpdateChecker checker = UpdateChecker.createConfigured(updateSource);
                UpdateChannel selectedChannel = channel == null
                        ? BuildInfo.current().inferredChannel()
                        : channel;
                UpdateCheckResult result = checker.check(selectedChannel);
                if (result.status() == UpdateCheckResult.Status.NO_COMPATIBLE_UPDATE) {
                    System.out.println(Messages.get("cli.update.noCompatibleUpdate",
                            Objects.requireNonNull(result.available()).version()));
                    return prepare || install ? 1 : 0;
                }
                if (result.status() == UpdateCheckResult.Status.UPDATE_AVAILABLE) {
                    System.out.println(Messages.get(
                            "cli.update.available",
                            Objects.requireNonNull(result.available()).version(),
                            result.current().version()));
                } else {
                    System.out.println(Messages.get("cli.update.upToDate", result.current().version()));
                    return 0;
                }
                if (!prepare && !install) {
                    return 0;
                }

                UpdatePackageManager packageManager =
                        UpdatePackageManager.createDefault(services.directories(), checker);
                PreparedUpdate prepared = packageManager.prepare(Objects.requireNonNull(result.available()), _ -> {
                });
                System.out.println(Messages.get("cli.update.prepared", prepared.packageFile()));
                if (install) {
                    UpdateInstaller.launch(prepared);
                    System.out.println(Messages.get("cli.update.installerStarted", prepared.packageFile()));
                }
                return 0;
            } catch (IOException | IllegalStateException | IllegalArgumentException exception) {
                return failException(exception, false);
            }
        }
    }

    /// Converts case-insensitive update channel tokens for Picocli.
    @NotNullByDefault
    private static final class UpdateChannelConverter implements ITypeConverter<UpdateChannel> {
        /// Converts one command-line channel token.
        ///
        /// @param value channel token.
        /// @return parsed update channel.
        @Override
        public UpdateChannel convert(String value) {
            return UpdateChannel.parse(value);
        }
    }

    /// Creates a progress reporter for the selected output mode.
    ///
    /// @param json whether progress should be emitted as JSON events.
    /// @return progress reporter.
    private static ProgressReporter progressReporter(boolean json) {
        ProgressReporter reporter;
        if (json) {
            reporter = event -> JsonOutput.print(progressEventMap(event));
        } else {
            CliProgressPrinter printer = new CliProgressPrinter(System.err);
            reporter = printer::report;
        }

        return LoggingProgressReporter.wrap(reporter, LOGGER);
    }

    /// Creates a JSON progress event object.
    ///
    /// @param event progress event.
    /// @return JSON-ready event map.
    private static Map<String, Object> progressEventMap(ProgressEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "progress");
        map.put("stage", event.stage());
        map.put("message", event.message());
        map.put("currentBytes", event.currentBytes());
        map.put("totalBytes", event.totalBytes());
        map.put("unit", event.unit().name().toLowerCase(Locale.ROOT));
        @Nullable Integer itemIndex = event.itemIndex();
        if (itemIndex != null) {
            map.put("itemIndex", itemIndex);
        }
        @Nullable Integer itemCount = event.itemCount();
        if (itemCount != null) {
            map.put("itemCount", itemCount);
        }
        return map;
    }

    /// Prints a command failure in the selected output mode.
    ///
    /// @param message error message.
    /// @param json whether JSON output is enabled.
    /// @return software error exit code.
    private static int fail(String message, boolean json) {
        @Nullable String logFile = RuyiLogging.currentLogFileText();
        if (json) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("type", "error");
            output.put("message", message);
            if (logFile != null) {
                output.put("logFile", logFile);
            }
            JsonOutput.print(output);
        } else {
            System.err.println(Messages.get("cli.error.prefix", message));
            if (logFile != null) {
                System.err.println("Log file: " + logFile);
            }
        }
        return CommandLine.ExitCode.SOFTWARE;
    }

    /// Logs and prints a command failure.
    ///
    /// @param exception failure exception.
    /// @param json whether JSON output is enabled.
    /// @return software error exit code.
    private static int failException(Exception exception, boolean json) {
        LOGGER.error("CLI command failed.", exception);
        return fail(exceptionMessage(exception), json);
    }

    /// Returns a localized fallback for an exception without a message.
    ///
    /// @param exception failure exception.
    /// @return failure message.
    private static String exceptionMessage(Exception exception) {
        @Nullable String message = exception.getMessage();
        return message == null ? Messages.get("cli.error.unknownFailure") : message;
    }

    /// Localizes command descriptions after the command model is assembled.
    ///
    /// @param commandLine root command line.
    private static void localizeCommands(CommandLine commandLine) {
        commandLine.setResourceBundle(Messages.bundle());
        commandLine.setAdjustLineBreaksForWideCJKCharacters(true);
        setDescription(commandLine, "cli.root.description");
        CommandLine repo = commandLine.getSubcommands().get("repo");
        setDescription(repo, "cli.repo.description");
        setDescription(repo.getSubcommands().get("update"), "cli.repo.update.description");
        CommandLine image = commandLine.getSubcommands().get("image");
        setDescription(image, "cli.image.description");
        setDescription(image.getSubcommands().get("list"), "cli.image.list.description");
        setDescription(image.getSubcommands().get("download"), "cli.image.download.description");
        CommandLine cache = image.getSubcommands().get("cache");
        setDescription(cache, "cli.image.cache.description");
        setDescription(cache.getSubcommands().get("list"), "cli.image.cache.list.description");
        setDescription(cache.getSubcommands().get("clean"), "cli.image.cache.clean.description");
        CommandLine device = commandLine.getSubcommands().get("device");
        setDescription(device, "cli.device.description");
        setDescription(device.getSubcommands().get("list"), "cli.device.list.description");
        setDescription(commandLine.getSubcommands().get("flash"), "cli.flash.description");
        @Nullable CommandLine checkUpdate = commandLine.getSubcommands().get("check-update");
        if (checkUpdate != null) {
            setDescription(checkUpdate, "cli.update.description");
        }
    }

    /// Sets one command description from the active message bundle.
    ///
    /// @param commandLine command line to update.
    /// @param key message key.
    private static void setDescription(CommandLine commandLine, String key) {
        commandLine.setResourceBundle(Messages.bundle());
        commandLine.setAdjustLineBreaksForWideCJKCharacters(true);
        commandLine.getCommandSpec().usageMessage().description(Messages.get(key));
    }

    /// Prints an operation result in the selected output mode.
    ///
    /// @param result operation result.
    /// @param json whether JSON output is enabled.
    /// @return process exit code.
    private static int finish(OperationResult result, boolean json) {
        @Nullable String logFile = RuyiLogging.currentLogFileText();
        if (!result.success()) {
            LOGGER.atWarn().log(() -> "CLI operation failed. message=" + result.message());
        }
        if (json) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("type", result.success() ? "complete" : "error");
            output.put("success", result.success());
            output.put("message", result.message());
            if (!result.success() && logFile != null) {
                output.put("logFile", logFile);
            }
            JsonOutput.print(output);
        } else {
            PrintStream stream = result.success() ? System.out : System.err;
            stream.println(result.message());
            if (!result.success() && logFile != null) {
                stream.println("Log file: " + logFile);
            }
        }

        return result.success() ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
    }

    /// Returns whether verbose logging was requested.
    ///
    /// @param args command-line arguments.
    /// @return whether the verbose flag is present.
    private static boolean hasVerboseFlag(String @Unmodifiable [] args) {
        for (String arg : args) {
            if ("--verbose".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /// Returns an option value from CLI arguments without consuming it.
    ///
    /// @param args command-line arguments.
    /// @param name option name.
    /// @return option value, or null when absent.
    private static @Nullable String optionValue(String @Unmodifiable [] args, String name) {
        String prefix = name + "=";
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
            if (name.equals(arg) && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                return args[i + 1];
            }
        }
        return null;
    }

    /// Returns the configured log file path without consuming its argument.
    ///
    /// @param args command-line arguments.
    /// @return log file path, or null when absent.
    private static @Nullable Path logFileOptionValue(String @Unmodifiable [] args) {
        @Nullable String value = optionValue(args, "--log-file");
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    /// Command group for repository operations.
    @Command(name = "repo", description = "Manage Ruyi metadata repositories.")
    @NotNullByDefault
    private static final class RepoCommand implements Callable<Integer> {
        /// Picocli command specification injected at runtime.
        @Spec
        private @Nullable CommandSpec spec;

        /// Prints repository command usage to standard error.
        ///
        /// @return the usage-error exit code.
        @Override
        public Integer call() {
            return printMissingSubcommandUsage(spec);
        }
    }

    /// Updates local Ruyi metadata repositories.
    @Command(name = "update", description = "Prepare or refresh local Ruyi metadata.")
    @NotNullByDefault
    private static final class RepoUpdateCommand implements Callable<Integer> {
        /// Services used by the command.
        private final AppServices services;

        /// Whether command output should be JSON.
        @Option(
                names = "--json",
                description = "Emit newline-delimited JSON events.",
                descriptionKey = "cli.option.json.events")
        private boolean json;

        /// Creates the repository update command.
        ///
        /// @param services shared application services.
        private RepoUpdateCommand(AppServices services) {
            this.services = services;
        }

        /// Runs repository update.
        ///
        /// @return process exit code.
        @Override
        public Integer call() {
            try {
                OperationResult result = services.repository().update(progressReporter(json));
                return finish(result, json);
            } catch (IOException | RuntimeException e) {
                return failException(e, json);
            }
        }
    }

    /// Command group for image operations.
    @Command(name = "image", description = "List and download Ruyi images.")
    @NotNullByDefault
    private static final class ImageCommand implements Callable<Integer> {
        /// Picocli command specification injected at runtime.
        @Spec
        private @Nullable CommandSpec spec;

        /// Prints image command usage to standard error.
        ///
        /// @return the usage-error exit code.
        @Override
        public Integer call() {
            return printMissingSubcommandUsage(spec);
        }
    }

    /// Lists known Ruyi images.
    @Command(name = "list", description = "List images from the local Ruyi metadata cache.")
    @NotNullByDefault
    private static final class ImageListCommand implements Callable<Integer> {
        /// Services used by the command.
        private final AppServices services;

        /// Whether command output should be JSON.
        @Option(
                names = "--json",
                description = "Emit a JSON object.",
                descriptionKey = "cli.option.json.object")
        private boolean json;

        /// Creates the image list command.
        ///
        /// @param services shared application services.
        private ImageListCommand(AppServices services) {
            this.services = services;
        }

        /// Lists images.
        ///
        /// @return process exit code.
        @Override
        public Integer call() {
            try {
                ImageCatalog catalog = services.images().listImages();
                if (json) {
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("type", "image-list");
                    output.put("images", catalog.images());
                    JsonOutput.print(output);
                    return CommandLine.ExitCode.OK;
                }

                if (catalog.images().isEmpty()) {
                    System.out.println(Messages.get("cli.image.none"));
                    return CommandLine.ExitCode.OK;
                }

                for (ImageEntry image : catalog.images()) {
                    System.out.printf("%s\t%s\t%s%n", image.atom(), image.displayName(), image.strategy());
                }
                return CommandLine.ExitCode.OK;
            } catch (IOException | RuntimeException e) {
                return failException(e, json);
            }
        }
    }

    /// Downloads a Ruyi image.
    @Command(name = "download", description = "Download one image by atom name.")
    @NotNullByDefault
    private static final class ImageDownloadCommand implements Callable<Integer> {
        /// Services used by the command.
        private final AppServices services;

        /// Whether command output should be JSON.
        @Option(
                names = "--json",
                description = "Emit newline-delimited JSON events.",
                descriptionKey = "cli.option.json.events")
        private boolean json;

        /// Image atom to download.
        @Parameters(
                index = "0",
                paramLabel = "ATOM",
                description = "Image atom name.",
                descriptionKey = "cli.parameter.atom")
        private @Nullable String atom;

        /// Creates the image download command.
        ///
        /// @param services shared application services.
        private ImageDownloadCommand(AppServices services) {
            this.services = services;
        }

        /// Downloads an image.
        ///
        /// @return process exit code.
        @Override
        public Integer call() {
            String requestedAtom = atom;
            if (requestedAtom == null) {
                return fail(Messages.get("cli.error.missingImageAtom"), json);
            }

            try {
                ImageEntry image = services.images().findImage(requestedAtom);
                if (image == null) {
                    return fail(Messages.get("cli.error.unknownImageAtom", requestedAtom), json);
                }

                Path imagePath = services.images().downloadImage(image, progressReporter(json));
                if (json) {
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("type", "complete");
                    output.put("success", true);
                    output.put("path", imagePath.toString());
                    JsonOutput.print(output);
                } else {
                    System.out.println(imagePath);
                }
                return CommandLine.ExitCode.OK;
            } catch (IOException | RuntimeException e) {
                return failException(e, json);
            }
        }
    }

    /// Command group for download cache operations.
    @Command(name = "cache", description = "Inspect and clean the image download cache.")
    @NotNullByDefault
    private static final class ImageCacheCommand implements Callable<Integer> {
        /// Picocli command specification injected at runtime.
        @Spec
        private @Nullable CommandSpec spec;

        /// Prints cache command usage to standard error.
        ///
        /// @return the usage-error exit code.
        @Override
        public Integer call() {
            return printMissingSubcommandUsage(spec);
        }
    }

    /// Lists cached image versions and their sizes.
    @Command(name = "list", description = "List cached images and their sizes.")
    @NotNullByDefault
    private static final class ImageCacheListCommand implements Callable<Integer> {
        /// Services used by the command.
        private final AppServices services;

        /// Whether command output should be JSON.
        @Option(
                names = "--json",
                description = "Emit a JSON object.",
                descriptionKey = "cli.option.json.object")
        private boolean json;

        /// Creates the cache list command.
        ///
        /// @param services shared application services.
        private ImageCacheListCommand(AppServices services) {
            this.services = services;
        }

        /// Lists cached images.
        ///
        /// @return process exit code.
        @Override
        public Integer call() {
            try {
                ImageCacheUsage usage = new ImageCacheService(services.directories(), services.images()).usage();
                if (json) {
                    Map<String, Object> output = cacheUsageMap(usage);
                    output.put("type", "cache-list");
                    JsonOutput.print(output);
                    return CommandLine.ExitCode.OK;
                }
                System.out.println(Messages.get(
                        "cli.cache.summary",
                        CliProgressPrinter.sizeLabel(usage.totalBytes()),
                        CliProgressPrinter.sizeLabel(usage.downloadBytes()),
                        CliProgressPrinter.sizeLabel(usage.artifactBytes()),
                        CliProgressPrinter.sizeLabel(usage.partialBytes())));
                System.out.println(Messages.get(
                        "cli.cache.retained",
                        CliProgressPrinter.sizeLabel(usage.repositoryBytes()),
                        CliProgressPrinter.sizeLabel(usage.logBytes())));
                for (ImageCacheEntry entry : usage.entries()) {
                    String state = switch (entry.completeness()) {
                        case COMPLETE -> "cli.cache.state.cached";
                        case INCOMPLETE -> "cli.cache.state.partial";
                        case UNKNOWN -> "cli.cache.state.unknown";
                    };
                    System.out.println(Messages.get(
                            "cli.cache.entry",
                            entry.atom(),
                            CliProgressPrinter.sizeLabel(entry.totalBytes()),
                            Messages.get(state)));
                }
                if (!usage.unreadable().isEmpty()) {
                    System.out.println(Messages.get("cli.cache.unreadable", usage.unreadable().size()));
                }
                return CommandLine.ExitCode.OK;
            } catch (RuntimeException e) {
                return failException(e, json);
            }
        }
    }

    /// Removes cached images or leftover files.
    @Command(name = "clean", description = "Remove cached images or leftover files.")
    @NotNullByDefault
    private static final class ImageCacheCleanCommand implements Callable<Integer> {
        /// Services used by the command.
        private final AppServices services;

        /// Image atoms to remove.
        @Parameters(
                index = "0..*",
                paramLabel = "ATOM",
                description = "Image atoms to remove.",
                descriptionKey = "cli.option.atom")
        private @Nullable List<String> atoms;

        /// Whether every cached image should be removed.
        @Option(
                names = "--all",
                description = "Remove every cached image.",
                descriptionKey = "cli.option.cacheAll")
        private boolean all;

        /// Whether incomplete downloads and leftover files should be removed.
        @Option(
                names = "--partials",
                description = "Remove incomplete downloads and leftover files.",
                descriptionKey = "cli.option.cachePartials")
        private boolean partials;

        /// Whether the removal was explicitly confirmed.
        @Option(
                names = "--yes",
                description = "Confirm cache deletion.",
                descriptionKey = "cli.option.cacheYes")
        private boolean yes;

        /// Whether command output should be JSON.
        @Option(
                names = "--json",
                description = "Emit a JSON object.",
                descriptionKey = "cli.option.json.object")
        private boolean json;

        /// Creates the cache clean command.
        ///
        /// @param services shared application services.
        private ImageCacheCleanCommand(AppServices services) {
            this.services = services;
        }

        /// Removes cached images.
        ///
        /// @return process exit code.
        @Override
        public Integer call() {
            @Unmodifiable List<String> requested = atoms == null ? List.of() : List.copyOf(atoms);
            if ((all && partials) || (!requested.isEmpty() && (all || partials))) {
                fail(Messages.get("cli.cache.clean.conflictingScopes"), json);
                return CommandLine.ExitCode.USAGE;
            }
            if (!all && !partials && requested.isEmpty()) {
                fail(Messages.get("cli.cache.clean.requiresTarget"), json);
                return CommandLine.ExitCode.USAGE;
            }
            try {
                ImageCacheService service = new ImageCacheService(services.directories(), services.images());
                if (yes && (all || partials)) {
                    return printResult(all ? service.cleanAll() : service.cleanPartials());
                }

                ImageCacheUsage usage = service.usageForCleanup(all);
                LinkedHashSet<ImageCacheEntry> selected = new LinkedHashSet<>();
                LinkedHashSet<String> unreadable = new LinkedHashSet<>();
                Path cacheRoot = services.directories().cacheDirectory().toAbsolutePath().normalize();
                for (String atom : requested) {
                    @Unmodifiable List<ImageCacheEntry> matches = findEntries(usage, atom);
                    @Unmodifiable List<String> affectedPaths = usage.unreadable().stream()
                            .filter(path -> mayContainAtom(cacheRoot, path, atom))
                            .distinct()
                            .toList();
                    if (matches.isEmpty()) {
                        if (!affectedPaths.isEmpty()) {
                            return fail(Messages.get(
                                    "cli.cache.unreadableAtom", atom, String.join(", ", affectedPaths)), json);
                        }
                        fail(Messages.get("cli.cache.unknownAtom", atom), json);
                        return CommandLine.ExitCode.USAGE;
                    }
                    if (!affectedPaths.isEmpty() && !atom.contains("/")) {
                        // P2:917 简写名（如 meles）可能被不可读目录隐藏了另一个版本，唯一性无法确认；
                        // 必须在任何删除之前退出并要求和完整 ATOM，而不是先删掉可见版本再报告失败。
                        return fail(Messages.get(
                                "cli.cache.unreadableAtom", atom, String.join(", ", affectedPaths)), json);
                    }
                    if (matches.stream().map(ImageCacheEntry::atom).distinct().count() > 1) {
                        fail(Messages.get("cli.cache.ambiguousAtom", atom), json);
                        return CommandLine.ExitCode.USAGE;
                    }
                    selected.addAll(matches);
                    unreadable.addAll(affectedPaths);
                }
                if (!yes) {
                    long plannedBytes;
                    if (all || partials) {
                        plannedBytes = all ? usage.totalBytes() : usage.partialBytes();
                        unreadable.addAll(usage.unreadable());
                    } else {
                        plannedBytes = selected.stream().mapToLong(ImageCacheEntry::totalBytes).sum();
                    }
                    printPreview(plannedBytes, List.copyOf(unreadable), usage.retained());
                    return CommandLine.ExitCode.USAGE;
                }

                ImageCacheCleanResult result = service.cleanEntries(List.copyOf(selected));
                if (unreadable.isEmpty()) {
                    return printResult(result);
                }
                unreadable.removeAll(result.skipped());
                ArrayList<String> skipped = new ArrayList<>(result.skipped());
                skipped.addAll(unreadable);
                return printResult(new ImageCacheCleanResult(
                        result.removedBytes(), result.removedFiles(), List.copyOf(skipped)));
            } catch (RuntimeException e) {
                return failException(e, json);
            }
        }

        /// Prints the proposed cleanup, including paths that could not be measured.
        ///
        /// P3:159 全量清理会跳过只能显式选择的版本；预览必须列出这些条目的 ATOM 和原因，
        /// 否则用户确认后只看到失败数量，无法知道应显式清理哪个版本。
        private void printPreview(
                long plannedBytes,
                @Unmodifiable List<String> unreadable,
                @Unmodifiable List<ImageCacheRetainedEntry> retained) {
            if (json) {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("type", "cache-clean-preview");
                output.put("removedBytes", plannedBytes);
                output.put("requiresConfirmation", true);
                if (!unreadable.isEmpty()) {
                    output.put("unreadable", unreadable);
                }
                if (!retained.isEmpty()) {
                    ArrayList<Map<String, Object>> entries = new ArrayList<>();
                    for (ImageCacheRetainedEntry item : retained) {
                        Map<String, Object> retainedEntry = new LinkedHashMap<>();
                        retainedEntry.put("atom", item.entry().atom());
                        retainedEntry.put("reason", item.reason().name().toLowerCase(Locale.ROOT));
                        entries.add(retainedEntry);
                    }
                    output.put("retained", entries);
                }
                JsonOutput.print(output);
            } else {
                System.out.println(Messages.get(
                        "cli.cache.clean.confirm",
                        CliProgressPrinter.sizeLabel(plannedBytes)));
                for (ImageCacheRetainedEntry item : retained) {
                    System.out.println(Messages.get(
                            "cli.cache.clean.retainedAtom", item.entry().atom(), retainedReason(item.reason())));
                }
                if (!unreadable.isEmpty()) {
                    System.out.println(Messages.get("cli.cache.unreadable", unreadable.size()));
                }
            }
        }

        /// Returns the localized explanation of one retention reason.
        ///
        /// @param reason retention reason.
        /// @return localized reason.
        private static String retainedReason(ImageCacheRetainedEntry.Reason reason) {
            return switch (reason) {
                case AMBIGUOUS_LEGACY_DIRECTORY -> Messages.get("cli.cache.retained.ambiguousLegacy");
            };
        }

        /// Prints cleanup results and returns a failing exit code when cleanup encountered errors.
        private int printResult(ImageCacheCleanResult result) {
            long inUse = result.skipped().stream()
                    .filter(reason -> reason.startsWith(ImageCacheService.SKIPPED_IN_USE))
                    .count();
            long failed = result.skipped().size() - inUse;
            if (json) {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("type", "cache-clean");
                output.put("removedBytes", result.removedBytes());
                output.put("removedFiles", result.removedFiles());
                output.put("skipped", result.skipped());
                JsonOutput.print(output);
            } else {
                System.out.println(Messages.get(
                        "cli.cache.clean.done",
                        CliProgressPrinter.sizeLabel(result.removedBytes()),
                        result.removedFiles()));
                if (inUse > 0) {
                    System.out.println(Messages.get("cli.cache.clean.skipped", inUse));
                }
                if (failed > 0) {
                    System.out.println(Messages.get("cli.cache.clean.failed", failed));
                }
            }
            return failed > 0 ? CommandLine.ExitCode.SOFTWARE : CommandLine.ExitCode.OK;
        }

        /// Reports whether an unreadable cache tree path could contain a requested image.
        private static boolean mayContainAtom(Path cacheRoot, String path, String atom) {
            Path absolute = cacheRoot.getFileSystem().getPath(path).toAbsolutePath().normalize();
            if (!absolute.startsWith(cacheRoot)) {
                return false;
            }
            Path relative = cacheRoot.relativize(absolute);
            int count = relative.getNameCount();
            if (count < 3) {
                return true;
            }
            if (!atom.contains("/")) {
                return count == 3 || relative.getName(3).toString().equals(atom);
            }
            String category = relative.getName(2).toString() + "/";
            if (count == 3) {
                return atom.startsWith(category);
            }
            String atomPrefix = category + relative.getName(3) + "(";
            if (!atom.startsWith(atomPrefix) || !atom.endsWith(")")) {
                return false;
            }
            if (count == 4) {
                return true;
            }
            String version = atom.substring(atomPrefix.length(), atom.length() - 1);
            String pathVersion = relative.getName(4).toString();
            if (pathVersion.equals(version)) {
                return true;
            }
            if (relative.startsWith("artifacts")) {
                return pathVersion.startsWith(".staging-" + version + "-")
                        || pathVersion.startsWith(".old-" + version + "-")
                        || pathVersion.startsWith(version + ".tmp-")
                        || pathVersion.startsWith(version + ".old-");
            }
            return false;
        }
    }

    /// Finds every cached image entry matching an atom.
    ///
    /// The same image may be cached under more than one repository, so every match is returned.
    ///
    /// @param usage cache usage to search.
    /// @param atom image atom or image name.
    /// @return matching entries, possibly empty.
    private static @Unmodifiable List<ImageCacheEntry> findEntries(ImageCacheUsage usage, String atom) {
        ArrayList<ImageCacheEntry> matches = new ArrayList<>();
        for (ImageCacheEntry entry : usage.entries()) {
            if (entry.atom().equals(atom) || entry.name().equals(atom)) {
                matches.add(entry);
            }
        }
        return List.copyOf(matches);
    }

    /// Builds the JSON view of cache usage.
    ///
    /// @param usage cache usage.
    /// @return JSON-ready map.
    private static Map<String, Object> cacheUsageMap(ImageCacheUsage usage) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("downloadBytes", usage.downloadBytes());
        output.put("artifactBytes", usage.artifactBytes());
        output.put("partialBytes", usage.partialBytes());
        output.put("partialFiles", usage.partialFiles());
        output.put("repositoryBytes", usage.repositoryBytes());
        output.put("logBytes", usage.logBytes());
        output.put("unreadable", usage.unreadable());
        ArrayList<Map<String, Object>> entries = new ArrayList<>();
        for (ImageCacheEntry entry : usage.entries()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("atom", entry.atom());
            item.put("repoId", entry.repoId());
            item.put("category", entry.category());
            item.put("name", entry.name());
            item.put("version", entry.version());
            item.put("artifactBytes", entry.artifactBytes());
            item.put("downloadBytes", entry.downloadBytes());
            item.put("partialBytes", entry.partialBytes());
            item.put("partialFiles", entry.partialFiles());
            item.put("completeness", entry.completeness().name().toLowerCase(Locale.ROOT));
            item.put("lastModifiedMillis", entry.lastModifiedMillis());
            entries.add(item);
        }
        output.put("entries", entries);
        return output;
    }

    /// Command group for block device operations.
    @Command(name = "device", description = "Inspect writable target devices.")
    @NotNullByDefault
    private static final class DeviceCommand implements Callable<Integer> {
        /// Picocli command specification injected at runtime.
        @Spec
        private @Nullable CommandSpec spec;

        /// Prints device command usage to standard error.
        ///
        /// @return the usage-error exit code.
        @Override
        public Integer call() {
            return printMissingSubcommandUsage(spec);
        }
    }

    /// Lists writable target devices.
    @Command(name = "list", description = "List candidate target devices.")
    @NotNullByDefault
    private static final class DeviceListCommand implements Callable<Integer> {
        /// Services used by the command.
        private final AppServices services;

        /// Whether command output should be JSON.
        @Option(
                names = "--json",
                description = "Emit a JSON object.",
                descriptionKey = "cli.option.json.object")
        private boolean json;

        /// Whether to list fastboot devices instead of block devices.
        @Option(
                names = "--fastboot",
                description = "List fastboot devices.",
                descriptionKey = "cli.option.fastboot")
        private boolean fastbootDevices;

        /// Creates the device list command.
        ///
        /// @param services shared application services.
        private DeviceListCommand(AppServices services) {
            this.services = services;
        }

        /// Lists block devices.
        ///
        /// @return process exit code.
        @Override
        public Integer call() {
            try {
                if (fastbootDevices) {
                    return listFastbootDevices();
                }

                List<BlockDevice> devices = services.devices().listDevices();
                if (json) {
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("type", "device-list");
                    output.put("devices", deviceOutput(devices));
                    JsonOutput.print(output);
                } else if (devices.isEmpty()) {
                    System.out.println(Messages.get("cli.device.none"));
                } else {
                    for (BlockDevice device : devices) {
                        String mountPoints = deviceMountPointsText(device);
                        if (mountPoints.isEmpty()) {
                            System.out.printf("%s\t%s\t%s%n", device.id(), device.displayName(), devicePathText(device));
                        } else {
                            System.out.printf(
                                    "%s\t%s\t%s\t%s%n",
                                    device.id(),
                                    device.displayName(),
                                    devicePathText(device),
                                    mountPoints);
                        }
                    }
                }
                return CommandLine.ExitCode.OK;
            } catch (IOException | RuntimeException e) {
                return failException(e, json);
            }
        }

        /// Lists fastboot devices.
        ///
        /// @return process exit code.
        private Integer listFastbootDevices() {
            try {
                List<FastbootDevice> devices = services.fastboot().listDevices();
                if (json) {
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("type", "fastboot-device-list");
                    output.put("devices", fastbootDeviceOutput(devices));
                    JsonOutput.print(output);
                } else if (devices.isEmpty()) {
                    System.out.println(Messages.get("cli.device.none"));
                } else {
                    for (FastbootDevice device : devices) {
                        System.out.printf("%s\t%s\t%s%n", device.id(), device.serial(), device.state());
                    }
                }
                return CommandLine.ExitCode.OK;
            } catch (IOException | RuntimeException e) {
                return failException(e, json);
            }
        }
    }

    /// Converts block devices to stable JSON output maps.
    ///
    /// @param devices devices to serialize.
    /// @return immutable JSON-ready device maps.
    private static @Unmodifiable List<@Unmodifiable Map<String, @Nullable Object>> deviceOutput(
            List<BlockDevice> devices) {
        ArrayList<@Unmodifiable Map<String, @Nullable Object>> output = new ArrayList<>(devices.size());
        for (BlockDevice device : devices) {
            Map<String, @Nullable Object> map = new LinkedHashMap<>();
            map.put("id", device.id());
            map.put("displayName", device.displayName());
            map.put("path", devicePathText(device));
            map.put("sizeBytes", device.sizeBytes());
            map.put("removable", device.removable());
            map.put("system", device.system());
            map.put("mounted", device.mounted());
            map.put("mountPoints", device.mountPoints());
            map.put("readOnly", device.readOnly());
            map.put("model", device.model());
            map.put("busType", device.busType());
            output.add(Collections.unmodifiableMap(map));
        }
        return List.copyOf(output);
    }

    /// Converts fastboot devices to stable JSON output maps.
    ///
    /// @param devices devices to serialize.
    /// @return immutable JSON-ready device maps.
    private static @Unmodifiable List<@Unmodifiable Map<String, @Nullable Object>> fastbootDeviceOutput(
            List<FastbootDevice> devices) {
        ArrayList<@Unmodifiable Map<String, @Nullable Object>> output = new ArrayList<>(devices.size());
        for (FastbootDevice device : devices) {
            Map<String, @Nullable Object> map = new LinkedHashMap<>();
            map.put("id", device.id());
            map.put("displayName", device.displayName());
            map.put("serial", device.serial());
            map.put("state", device.state());
            output.add(Collections.unmodifiableMap(map));
        }
        return List.copyOf(output);
    }

    /// Converts a device target path to CLI text.
    ///
    /// @param device block device.
    /// @return printable target path.
    private static String devicePathText(BlockDevice device) {
        String text = device.path().toString();
        if (text.startsWith("\\\\.\\PHYSICALDRIVE") && text.endsWith("\\")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    /// Formats device mount points for CLI output.
    ///
    /// @param device block device.
    /// @return mount point text, or empty string when none are known.
    private static String deviceMountPointsText(BlockDevice device) {
        if (device.mountPoints().isEmpty()) {
            return "";
        }
        return String.join(", ", device.mountPoints());
    }

    /// Flashes an image to a target device.
    @Command(name = "flash", description = "Flash an image to a target device.")
    @NotNullByDefault
    private static final class FlashCommand implements Callable<Integer> {
        /// Services used by the command.
        private final AppServices services;

        /// Image atom selected from Ruyi metadata.
        @Option(
                names = "--atom",
                paramLabel = "ATOM",
                description = "Image atom name.",
                descriptionKey = "cli.option.atom")
        private @Nullable String atom;

        /// Local image path to flash.
        @Option(
                names = "--local-image",
                paramLabel = "PATH",
                description = "Local image path.",
                descriptionKey = "cli.option.localImage")
        private @Nullable Path localImage;

        /// Target block device identifier.
        @Option(
                names = "--device",
                paramLabel = "ID",
                description = "Target device id.",
                descriptionKey = "cli.option.device")
        private @Nullable String deviceId;

        /// Partition-specific target block device mappings.
        @Option(
                names = "--partition-device",
                paramLabel = "PARTITION=ID",
                description = "Partition target mapping.",
                descriptionKey = "cli.option.partitionDevice")
        private List<String> partitionDeviceSpecs = new ArrayList<>();

        /// Whether the destructive operation was explicitly confirmed.
        @Option(
                names = "--yes",
                description = "Confirm destructive writing.",
                descriptionKey = "cli.option.yes")
        private boolean yes;

        /// Whether write verification should be skipped.
        @Option(
                names = "--skip-verify",
                description = "Skip post-write verification.",
                descriptionKey = "cli.option.skipVerify")
        private boolean skipVerify;

        /// Whether command output should be JSON.
        @Option(
                names = "--json",
                description = "Emit newline-delimited JSON events.",
                descriptionKey = "cli.option.json.events")
        private boolean json;

        /// Creates the flash command.
        ///
        /// @param services shared application services.
        private FlashCommand(AppServices services) {
            this.services = services;
        }

        /// Runs the flash command.
        ///
        /// @return process exit code.
        @Override
        public Integer call() {
            if (!yes) {
                return fail(Messages.get("cli.error.refusingWithoutYes"), json);
            }

            boolean hasAtom = atom != null;
            boolean hasLocalImage = localImage != null;
            if (hasAtom == hasLocalImage) {
                return fail(Messages.get("cli.error.chooseOneImageSource"), json);
            }

            String requestedDeviceId = deviceId;
            try {
                @Unmodifiable Map<String, String> requestedPartitionDevices =
                        parsePartitionDeviceSpecs(partitionDeviceSpecs);
                @Nullable ImageEntry image = null;
                @Nullable Path requestedLocalImage = localImage;
                if (atom != null) {
                    image = services.images().findImage(atom);
                    if (image == null) {
                        return fail(Messages.get("cli.error.unknownImageAtom", atom), json);
                    }
                } else if (requestedLocalImage != null && !Files.isRegularFile(requestedLocalImage)) {
                    return fail(Messages.get("cli.error.localImageMissing", requestedLocalImage), json);
                }

                FlashTarget target;
                if (!requestedPartitionDevices.isEmpty()) {
                    if (requestedDeviceId != null) {
                        return fail(Messages.get("cli.error.deviceConflictsWithPartitionDevice"), json);
                    }
                    if (image == null || !ProvisionStrategies.isDD(image.strategy())) {
                        return fail(Messages.get("cli.error.partitionDeviceRequiresAtom"), json);
                    }
                    target = FlashTarget.blockDevices(resolvePartitionDevices(requestedPartitionDevices));
                } else if (image != null && ProvisionStrategies.isDD(image.strategy()) && image.partitionMap().size() > 1) {
                    return fail(
                            Messages.get("cli.error.missingPartitionTargets", String.join(", ", image.partitionMap().keySet())),
                            json);
                } else if (image != null && ProvisionStrategies.isFastboot(image.strategy())) {
                    if (requestedDeviceId == null) {
                        return fail(Messages.get("cli.error.missingTargetDevice"), json);
                    }
                    @Nullable FastbootDevice fastbootDevice = services.fastboot().findDevice(requestedDeviceId);
                    if (fastbootDevice == null) {
                        return fail(Messages.get("cli.error.unknownTargetDevice", requestedDeviceId), json);
                    }
                    target = FlashTarget.fastbootDevice(fastbootDevice);
                } else {
                    if (requestedDeviceId == null) {
                        return fail(Messages.get("cli.error.missingTargetDevice"), json);
                    }
                    @Nullable BlockDevice blockDevice = services.devices().findDevice(requestedDeviceId);
                    if (blockDevice == null) {
                        return fail(Messages.get("cli.error.unknownTargetDevice", requestedDeviceId), json);
                    }
                    target = FlashTarget.blockDevice(blockDevice);
                }

                OperationResult result = services.flash().flash(
                        new FlashRequest(image, requestedLocalImage, target, !skipVerify),
                        progressReporter(json));
                return finish(result, json);
            } catch (IOException | RuntimeException e) {
                return failException(e, json);
            }
        }

        /// Parses partition target mapping options.
        ///
        /// @param specs raw option values.
        /// @return immutable partition target id map.
        private static @Unmodifiable Map<String, String> parsePartitionDeviceSpecs(List<String> specs) {
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            for (String spec : specs) {
                int delimiterIndex = spec.indexOf('=');
                if (delimiterIndex <= 0 || delimiterIndex == spec.length() - 1) {
                    throw new IllegalArgumentException(Messages.get("cli.error.invalidPartitionDevice", spec));
                }

                String partition = spec.substring(0, delimiterIndex).strip();
                String device = spec.substring(delimiterIndex + 1).strip();
                if (partition.isEmpty() || device.isEmpty()) {
                    throw new IllegalArgumentException(Messages.get("cli.error.invalidPartitionDevice", spec));
                }
                if (result.put(partition, device) != null) {
                    throw new IllegalArgumentException(Messages.get("cli.error.duplicatePartitionDevice", partition));
                }
            }
            return Collections.unmodifiableMap(result);
        }

        /// Resolves partition target device ids to block devices.
        ///
        /// @param partitionDeviceIds partition target device ids.
        /// @return immutable partition target device map.
        /// @throws IOException when device enumeration fails.
        private @Unmodifiable Map<String, BlockDevice> resolvePartitionDevices(
                @Unmodifiable Map<String, String> partitionDeviceIds) throws IOException {
            @Unmodifiable List<BlockDevice> devices = services.devices().listDevices();
            LinkedHashMap<String, BlockDevice> result = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : partitionDeviceIds.entrySet()) {
                @Nullable BlockDevice device = findBlockDevice(devices, entry.getValue());
                if (device == null) {
                    throw new IllegalArgumentException(Messages.get("cli.error.unknownTargetDevice", entry.getValue()));
                }
                result.put(entry.getKey(), device);
            }
            return Collections.unmodifiableMap(result);
        }

        /// Finds a block device by id in an already enumerated device list.
        ///
        /// @param devices device list.
        /// @param id target device id.
        /// @return matching device, or null when not found.
        private static @Nullable BlockDevice findBlockDevice(@Unmodifiable List<BlockDevice> devices, String id) {
            for (BlockDevice device : devices) {
                if (device.id().equals(id)) {
                    return device;
                }
            }
            return null;
        }
    }

    /// JSON writer used by command output paths.
    @NotNullByDefault
    static final class JsonOutput {
        /// Shared JSON mapper.
        private static final ObjectMapper MAPPER = new ObjectMapper();

        /// Guards the standard output while one complete JSON line is written.
        private static final Object LOCK = new Object();

        /// Prevents construction of the JSON utility.
        private JsonOutput() {
        }

        /// Writes one JSON object followed by a line separator.
        ///
        /// The text is encoded as UTF-8 while it is written, so machine-readable output does not
        /// depend on the console code page of the host and no full-size byte copy is allocated. The
        /// complete line is written under a lock because progress events are reported from several
        /// threads, for example the ones draining fastboot or helper output.
        ///
        /// @param value value to write.
        static void print(Object value) {
            try {
                writeLine(MAPPER.writeValueAsString(value));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /// Writes one complete JSON line to the standard output.
        ///
        /// @param json JSON text.
        /// @throws IOException when the text cannot be written.
        private static void writeLine(String json) throws IOException {
            synchronized (LOCK) {
                OutputStreamWriter writer = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
                writer.write(json);
                writer.write(System.lineSeparator());
                writer.flush();
            }
        }
    }
}
