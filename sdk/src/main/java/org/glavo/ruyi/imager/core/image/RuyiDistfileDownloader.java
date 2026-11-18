// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressUnit;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.NetworkDefaults;
import org.glavo.ruyi.imager.core.SdkMessages;
import org.glavo.ruyi.imager.logging.LogRedactor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.glavo.ruyi.imager.core.image.DistfileIntegrity.hasSupportedChecksum;
import static org.glavo.ruyi.imager.core.image.DistfileIntegrity.verify;

/// Downloads and verifies Ruyi distfiles.
@NotNullByDefault
public final class RuyiDistfileDownloader {
    /// Prefix of directories reserved for resume data.
    static final String DOWNLOAD_PARTIAL_DIRECTORY_PREFIX = ".download-partials-";

    /// Name of a staging slot immediately below a version directory.
    private static final Pattern STAGING_DIRECTORY_NAME =
            Pattern.compile(Pattern.quote(DOWNLOAD_PARTIAL_DIRECTORY_PREFIX) + "[0-9]+");

    /// Logger for distfile download operations.
    private static final Logger LOGGER = LoggerFactory.getLogger(RuyiDistfileDownloader.class);

    /// HTTP client used for distfile downloads.
    private final HttpClient httpClient;

    /// Maximum wait for each demanded response-body chunk, excluding local writes and progress reporting.
    private final Duration bodyTimeout;

    /// Creates a downloader with a default HTTP client.
    public RuyiDistfileDownloader() {
        this(defaultHttpClient());
    }

    /// Creates a downloader with a provided HTTP client.
    ///
    /// @param httpClient HTTP client.
    public RuyiDistfileDownloader(HttpClient httpClient) {
        this(httpClient, Duration.ofSeconds(60));
    }

    /// Creates a downloader with an explicit response-body wait limit.
    ///
    /// @param httpClient HTTP client owned by the caller.
    /// @param bodyTimeout positive maximum wait for each body chunk.
    /// @throws IllegalArgumentException when the wait limit is not positive.
    RuyiDistfileDownloader(HttpClient httpClient, Duration bodyTimeout) {
        if (bodyTimeout.isNegative() || bodyTimeout.isZero()) {
            throw new IllegalArgumentException("Body timeout must be positive.");
        }
        this.httpClient = httpClient;
        this.bodyTimeout = bodyTimeout;
    }

    /// Creates the default HTTP client.
    ///
    /// @return default HTTP client.
    private static HttpClient defaultHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL);
        @Nullable ProxySelector proxySelector = NetworkDefaults.proxySelector();
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        return builder.build();
    }

    /// Downloads a distfile into the target directory.
    ///
    /// A target in the conventional downloads cache hierarchy is protected by a version lease.
    /// Elsewhere a staging directory that collides with the archive name is left in place.
    ///
    /// @param distfile distfile declaration.
    /// @param targetDirectory target directory.
    /// @param reporter progress reporter.
    /// @return verified target path.
    /// @throws IOException when the distfile cannot be downloaded or verified.
    public Path download(RuyiDistfile distfile, Path targetDirectory, ProgressReporter reporter) throws IOException {
        Path absolute = targetDirectory.toAbsolutePath().normalize();
        int treeIndex = absolute.getNameCount() - 5;
        if (treeIndex >= 0 && absolute.getName(treeIndex).toString().equalsIgnoreCase("downloads")) {
            Path volume = absolute.getRoot();
            Path cacheRoot = treeIndex == 0 ? volume : volume.resolve(absolute.subpath(0, treeIndex));
            Path downloadTree = cacheRoot.resolve(absolute.getName(treeIndex));
            Path canonical = cacheRoot.resolve("downloads");
            if (downloadTree.equals(canonical) || (Files.exists(downloadTree) && Files.exists(canonical)
                    && Files.isSameFile(downloadTree, canonical))) {
                Path configured = AppDirectories.defaults().cacheDirectory();
                if (Files.exists(configured) && Files.exists(cacheRoot) && Files.isSameFile(configured, cacheRoot)) {
                    return download(distfile, targetDirectory, cacheRoot, reporter);
                }
                return downloadWithCompatibilityLock(distfile, targetDirectory, absolute, cacheRoot, reporter);
            }
        }
        return download(distfile, targetDirectory, null, List.of(), reporter, false);
    }

    /// Coordinates standalone writers in a conventional cache layout through the legacy lock.
    private Path downloadWithCompatibilityLock(RuyiDistfile distfile, Path targetDirectory, Path absolute,
            Path candidateRoot, ProgressReporter reporter) throws IOException {
        Path lockPath = absolute.resolveSibling(absolute.getFileName() + ".lock");
        Path parent = absolute.getParent();
        if (CacheLock.hasEstablishedVersionLease(candidateRoot, candidateRoot.relativize(lockPath))) {
            return download(distfile, targetDirectory, candidateRoot, reporter);
        }
        if (Files.notExists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(parent) && !Files.isWritable(parent)) {
                // Without an existing lock and with a read-only parent, cleanup cannot lock this version either.
                return download(distfile, targetDirectory, null, List.of(), reporter, false);
            }
            Path target = targetDirectory.resolve(distfile.name());
            if (!Files.isWritable(candidateRoot) && reusableArchive(target, distfile)) {
                // A standalone cache hit needs neither a sibling lock nor any writes to the inferred root.
                return reportCached(target, distfile, reporter);
            }
        }
        try (@Nullable CacheLock compatibility = CacheLock.acquire(lockPath)) {
            if (compatibility == null) {
                throw new IOException(SdkMessages.get("core.local.cacheBusy"));
            }
            return download(distfile, targetDirectory, null, List.of(), reporter, false);
        }
    }

    /// Downloads into an explicit managed cache version under the same lease used by cleanup.
    public Path download(RuyiDistfile distfile, Path targetDirectory, Path cacheRoot, ProgressReporter reporter)
            throws IOException {
        Path configuredRoot = cacheRoot.toAbsolutePath().normalize();
        Path absolute = targetDirectory.toAbsolutePath().normalize();
        if (!absolute.startsWith(configuredRoot)) {
            throw new IOException("Download path is outside the cache root: " + targetDirectory);
        }
        Path relative = configuredRoot.relativize(absolute);
        if (relative.getNameCount() != 5 || !relative.getName(0).toString().equalsIgnoreCase("downloads")) {
            throw new IOException("Expected a managed download version: " + targetDirectory);
        }
        if (!Files.isDirectory(configuredRoot)) {
            Files.createDirectories(configuredRoot);
        }
        Path root = configuredRoot.toRealPath();
        Path version = root.resolve(relative);
        Path lockPath = version.resolveSibling(version.getFileName() + ".lock");
        try (@Nullable CacheLock lock = CacheLock.acquireVersion(root, lockPath)) {
            if (lock == null) {
                throw new IOException(SdkMessages.get("core.local.cacheBusy"));
            }
            try (var directory = CacheTreeRemoval.bindDownloadVersion(root, relative)) {
                directory.verifyIdentity();
            }
            download(distfile, version, root, List.of(), reporter, true);
            return targetDirectory.resolve(distfile.name());
        }
    }

    /// Downloads a distfile and clears any legacy partial identity for the final archive.
    ///
    /// The caller holds the version lock when supplying the cache root.
    ///
    /// @param distfile distfile declaration.
    /// @param targetDirectory target directory.
    /// @param cacheRoot cache root used to store the incomplete-download marker, or null.
    /// @param archives declarations used to reserve names for future archives.
    /// @param reporter progress reporter.
    /// @return verified target path.
    /// @throws IOException when the distfile cannot be downloaded or verified.
    Path download(
            RuyiDistfile distfile,
            Path targetDirectory,
            @Nullable Path cacheRoot,
            List<RuyiDistfile> archives,
            ProgressReporter reporter) throws IOException {
        return download(distfile, targetDirectory, cacheRoot, archives, reporter, true);
    }

    /// Downloads a distfile, allowing staging relocation only while the caller holds the version lock.
    private Path download(
            RuyiDistfile distfile,
            Path targetDirectory,
            @Nullable Path cacheRoot,
            List<RuyiDistfile> archives,
            ProgressReporter reporter,
            boolean canRelocateStaging) throws IOException {
        Files.createDirectories(targetDirectory);
        Path target = targetDirectory.resolve(distfile.name());
        LOGGER.atInfo().log(() -> "Downloading distfile. name="
                + distfile.name()
                + ", target="
                + target
                + ", sources="
                + distfile.sourceUris().size());
        if (reusableArchive(target, distfile)) {
            clearArchiveMarker(cacheRoot, target);
            return reportCached(target, distfile, reporter);
        }

        if (!canRelocateStaging && isStagingDirectory(target, targetDirectory)) {
            throw new FileAlreadyExistsException(target.toString(), null, "Download staging directory may be in use");
        }
        if (distfile.fetchRestricted()) {
            if (canRelocateStaging) {
                relocateStaging(targetDirectory, distfile, archives);
            }
            LOGGER.atWarn().log(() -> "Distfile requires manual download. name=" + distfile.name() + ", target=" + target);
            throw new IOException(manualDownloadMessage(distfile, target));
        }

        if (!hasSupportedChecksum(distfile)) {
            LOGGER.atWarn().log(() -> "Distfile has no supported checksum for automatic download. name="
                    + distfile.name());
            throw new IOException(SdkMessages.get("core.download.noSupportedChecksum", distfile.name()));
        }

        List<URI> sourceUris = distfile.sourceUris();
        if (sourceUris.isEmpty()) {
            LOGGER.atWarn().log(() -> "Distfile has no source URLs. name=" + distfile.name());
            throw new IOException(SdkMessages.get("core.download.noUrls", distfile.name()));
        }

        if (canRelocateStaging) {
            relocateReservedStaging(targetDirectory, distfile, archives);
        }
        Path partial = partialPath(distfile, targetDirectory, archives);
        Path partialDirectory = partial.getParent();
        try {
            Files.createDirectory(partialDirectory);
        } catch (FileAlreadyExistsException _) {
            if (!Files.isDirectory(partialDirectory, LinkOption.NOFOLLOW_LINKS)
                    || !partialDirectory.toRealPath().equals(targetDirectory.toRealPath()
                    .resolve(partialDirectory.getFileName()))) {
                throw new IOException("Unsafe download staging directory: " + partialDirectory);
            }
        }
        boolean newPartial = Files.notExists(partial, LinkOption.NOFOLLOW_LINKS);

        try {
            if (newPartial) {
                @Nullable Path legacy = legacyPartialPath(distfile, targetDirectory, archives);
                if (legacy != null) {
                    recoverLegacyPartial(archives.isEmpty() ? null : cacheRoot, legacy, partial);
                }
            }
            transferFromSources(distfile, sourceUris, partial, reporter);
        } catch (IOException | RuntimeException | Error failure) {
            cleanupPartial(partial, newPartial);
            throw failure;
        }
        publishVerifiedPartial(partial, target, cacheRoot, canRelocateStaging);
        LOGGER.atInfo().log(() -> "Distfile download completed. name=" + distfile.name() + ", target=" + target);
        return target;
    }

    /// Tests whether a verified local archive fulfills the declaration without a transfer.
    private static boolean reusableArchive(Path target, RuyiDistfile distfile) throws IOException {
        return Files.isRegularFile(target) && (distfile.fetchRestricted() || hasSupportedChecksum(distfile))
                && verify(target, distfile);
    }

    /// Reports a verified local cache hit and returns its path.
    private static Path reportCached(Path target, RuyiDistfile distfile, ProgressReporter reporter) {
        LOGGER.atInfo().log(() -> "Using cached distfile. name=" + distfile.name() + ", path=" + target);
        reporter.report(new ProgressEvent(
                "download",
                SdkMessages.get("core.download.cached", distfile.name()),
                bytesOrNull(target),
                distfile.sizeBytes(),
                ProgressUnit.BYTES));
        return target;
    }

    /// Migrates a marked exclusive partial without copying. Other sibling files retain their identity.
    private static void recoverLegacyPartial(@Nullable Path cacheRoot, Path legacy, Path partial) throws IOException {
        if (cacheRoot != null && ImageCacheMarkers.exists(cacheRoot, legacy) && CacheTreeRemoval.linkCount(legacy) == 1L) {
            try {
                Files.move(legacy, partial, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(legacy, partial);
            }
            ImageCacheMarkers.delete(cacheRoot, legacy);
        } else {
            try (var input = FileChannel.open(legacy, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(Channels.newInputStream(input), partial);
            }
        }
    }

    /// Downloads and verifies a partial, trying another source only when transfer or verification fails.
    private void transferFromSources(RuyiDistfile distfile, List<URI> sourceUris, Path partial,
                                     ProgressReporter reporter) throws IOException {
        @Nullable IOException failure = null;
        for (URI sourceUri : sourceUris) {
            try {
                downloadFromUri(distfile, sourceUri, partial, reporter);
                return;
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    var interruption = new InterruptedIOException(SdkMessages.get("core.download.interrupted", distfile.name()));
                    interruption.initCause(e);
                    throw interruption;
                }
                LOGGER.warn("Distfile source failed. name="
                        + distfile.name()
                        + ", uri="
                        + LogRedactor.redactUri(sourceUri), e);
                failure = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Distfile download interrupted. name=" + distfile.name(), e);
                throw new IOException(SdkMessages.get("core.download.interrupted", distfile.name()), e);
            }
        }
        throw new IOException(SdkMessages.get("core.download.failed", distfile.name()), failure);
    }

    /// Selects an isolated resume path beneath the download version.
    ///
    /// @param distfile distfile declaration.
    /// @param targetDirectory directory of the image version.
    /// @param archives declarations for the version.
    /// @return resume file path.
    /// @throws IOException when a candidate directory cannot be inspected.
    static Path partialPath(RuyiDistfile distfile, Path targetDirectory, List<RuyiDistfile> archives) throws IOException {
        String partialName = distfile.name() + ".part";
        try (var directories = Files.newDirectoryStream(targetDirectory, DOWNLOAD_PARTIAL_DIRECTORY_PREFIX + "*")) {
            for (Path directory : directories) {
                if (isStagingDirectory(directory, targetDirectory)) {
                    Path existing = directory.resolve(partialName);
                    if (!Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
                        return existing;
                    }
                }
            }
        } catch (NoSuchFileException _) {
            // The version directory has not been created yet.
        }
        return stagingSlot(distfile, targetDirectory, archives, false).resolve(partialName);
    }

    /// Finds an eligible legacy sibling that can be copied without changing its original name or data.
    static @Nullable Path legacyPartialPath(RuyiDistfile distfile, Path targetDirectory, List<RuyiDistfile> archives)
            throws IOException {
        String partialName = distfile.name() + ".part";
        if (archives.stream().anyMatch(archive -> archive.name().equalsIgnoreCase(partialName))) {
            return null;
        }
        Path legacy = targetDirectory.resolve(partialName);
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(legacy, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            @Nullable Long expectedSize = distfile.sizeBytes();
            if (attributes.isRegularFile() && attributes.size() > 0L
                    && (expectedSize == null || attributes.size() <= expectedSize)) {
                return legacy;
            }
        } catch (NoSuchFileException _) {
            // There is no legacy resume data.
        }
        return null;
    }

    /// Makes a newly declared archive name available while retaining the old slot's entire contents.
    private static void relocateReservedStaging(Path targetDirectory, RuyiDistfile distfile,
                                               List<RuyiDistfile> archives) throws IOException {
        relocateStaging(targetDirectory, distfile, archives);
        for (RuyiDistfile archive : archives) {
            if (!archive.name().equals(distfile.name())) {
                relocateStaging(targetDirectory, archive, archives);
            }
        }
    }

    /// Frees the archive's name by moving an existing staging directory to a vacant slot in the version.
    /// The catalog caller holds the version lock through relocation and archive publication.
    static void relocateStaging(Path targetDirectory, RuyiDistfile distfile, List<RuyiDistfile> archives)
            throws IOException {
        Path candidate = targetDirectory.resolve(distfile.name());
        if (isStagingDirectory(candidate, targetDirectory)) {
            Path available = stagingSlot(distfile, targetDirectory, archives, true);
            Files.move(candidate, available);
        }
    }

    /// Identifies the numeric download staging namespace shared by download, cleanup, and flash.
    static boolean isDownloadPartialDirectoryName(String name) {
        return STAGING_DIRECTORY_NAME.matcher(name).matches();
    }

    /// Checks that a numeric staging slot is a physical child of the version directory.
    private static boolean isStagingDirectory(Path directory, Path targetDirectory) throws IOException {
        String name = directory.getFileName().toString();
        String slot = name.toLowerCase(Locale.ROOT);
        if (!isDownloadPartialDirectoryName(slot) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path actual = directory.toRealPath();
        @Nullable Path actualParent = actual.getParent();
        if (actualParent == null || !actual.getFileName().toString().equalsIgnoreCase(slot)
                || !Files.isSameFile(actualParent, targetDirectory)) {
            return false;
        }

        // Some providers keep the requested spelling in toRealPath(), even on a case-insensitive volume.
        try (var entries = Files.newDirectoryStream(targetDirectory, slot)) {
            for (Path entry : entries) {
                if (entry.getFileName().toString().equals(slot) && Files.isSameFile(entry, directory)) {
                    return true;
                }
            }
        }
        return false;
    }

    /// Finds the first safe unreserved staging slot, optionally requiring it to be vacant.
    private static Path stagingSlot(RuyiDistfile distfile, Path targetDirectory, List<RuyiDistfile> archives,
                                    boolean vacant) throws IOException {
        for (int slot = 0; ; slot++) {
            String directoryName = DOWNLOAD_PARTIAL_DIRECTORY_PREFIX + slot;
            if (distfile.name().equalsIgnoreCase(directoryName)
                    || archives.stream().anyMatch(archive -> archive.name().equalsIgnoreCase(directoryName))) {
                continue;
            }
            Path candidate = targetDirectory.resolve(directoryName);
            try {
                Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!vacant && isStagingDirectory(candidate, targetDirectory)) {
                    return candidate;
                }
            } catch (NoSuchFileException _) {
                return candidate;
            }
        }
    }

    /// Preserves resumable bytes and discards any empty file first created by this download.
    private static void cleanupPartial(Path partial, boolean newPartial) {
        try {
            if (newPartial && Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS) && Files.size(partial) == 0) {
                Files.deleteIfExists(partial);
            }
        } catch (IOException failure) {
            LOGGER.warn("Failed to clean up the download partial: " + partial, failure);
        }
    }

    /// Invalidates a legacy partial identity before the final archive is exposed.
    private static void clearArchiveMarker(@Nullable Path cacheRoot, Path target) throws IOException {
        if (cacheRoot != null) {
            ImageCacheMarkers.clearArchiveMarker(cacheRoot, target);
        }
    }

    /// Downloads one distfile from one source URI.
    ///
    /// @param distfile distfile declaration.
    /// @param sourceUri source URI.
    /// @param partial partial output path.
    /// @param reporter progress reporter.
    /// @throws IOException when the download fails.
    /// @throws InterruptedException when the HTTP request is interrupted.
    private void downloadFromUri(
            RuyiDistfile distfile,
            URI sourceUri,
            Path partial,
            ProgressReporter reporter) throws IOException, InterruptedException {
        @Nullable String scheme = sourceUri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            LOGGER.atWarn().log(() -> "Unsupported distfile URI scheme. uri=" + LogRedactor.redactUri(sourceUri));
            throw new IOException(SdkMessages.get("core.download.unsupportedScheme", sourceUri));
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            long existingBytes = preparePartial(distfile, partial);
            Long expectedSize = distfile.sizeBytes();

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(sourceUri)
                    .timeout(Duration.ofMinutes(10))
                    .GET();
            if (existingBytes > 0L) {
                requestBuilder.header("Range", "bytes=" + existingBytes + "-");
            }

            long resumeBytes = existingBytes;
            LOGGER.atInfo().log(() -> "Requesting distfile source. name="
                    + distfile.name()
                    + ", uri="
                    + LogRedactor.redactUri(sourceUri)
                    + ", resumeBytes="
                    + resumeBytes);
            reporter.report(ProgressEvent.indeterminate(
                    "download",
                    SdkMessages.get("core.download.connecting", distfile.name())));
            try (DistfileResponseBody body = new DistfileResponseBody()) {
                HttpResponse<DistfileResponseBody> response = httpClient.send(requestBuilder.build(), _ -> body);
                int statusCode = response.statusCode();
                LOGGER.atInfo().log(() -> "Distfile source responded. name="
                        + distfile.name()
                        + ", uri="
                        + LogRedactor.redactUri(sourceUri)
                        + ", status="
                        + statusCode);
                if (existingBytes > 0L && statusCode == 416) {
                    if (verify(partial, distfile)) {
                        return;
                    }
                    LOGGER.atInfo().log(() -> "Discarding invalid complete partial download. name=" + distfile.name());
                    Files.deleteIfExists(partial);
                    continue;
                }
                if (statusCode != 200 && statusCode != 206) {
                    LOGGER.atWarn().log(() -> "Unexpected distfile source status. name="
                            + distfile.name()
                            + ", uri="
                            + LogRedactor.redactUri(sourceUri)
                            + ", status="
                            + statusCode);
                    throw new IOException(SdkMessages.get("core.download.unexpectedStatus", statusCode, sourceUri));
                }
                if (existingBytes > 0L && statusCode == 200) {
                    LOGGER.atInfo().log(() -> "Source ignored range request; restarting partial download. name=" + distfile.name());
                    existingBytes = 0L;
                }
                long resumedBytes = existingBytes;
                reporter.report(new ProgressEvent(
                        "download",
                        resumedBytes > 0L && expectedSize != null && expectedSize > 0L
                                ? SdkMessages.get(
                                        "core.download.resuming",
                                        distfile.name(),
                                        Math.min(100L, resumedBytes * 100L / expectedSize))
                                : SdkMessages.get("core.download.downloading", distfile.name()),
                        resumedBytes,
                        expectedSize,
                        ProgressUnit.BYTES));

                writeResponse(response.body(), partial, distfile, reporter, existingBytes);
                if (Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS) && verify(partial, distfile)) {
                    return;
                }
                LOGGER.atWarn().log(() -> "Downloaded distfile verification failed. name="
                        + distfile.name() + ", partial=" + partial);
                if (existingBytes == 0L) {
                    break;
                }
                Files.deleteIfExists(partial);
            }
        }

        throw new IOException(SdkMessages.get("core.download.verifyFailed", distfile.name()));
    }

    /// Prepares an existing partial download for a resumed request.
    ///
    /// @param distfile distfile declaration.
    /// @param partial partial output path.
    /// @return existing bytes that may be resumed.
    /// @throws IOException when the partial file cannot be inspected or deleted.
    private static long preparePartial(RuyiDistfile distfile, Path partial) throws IOException {
        long existingBytes;
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(partial, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IOException("Expected a regular download partial: " + partial);
            }
            existingBytes = attributes.size();
        } catch (NoSuchFileException _) {
            return 0L;
        }
        Long expectedSize = distfile.sizeBytes();
        if (expectedSize != null && existingBytes >= expectedSize) {
            if (existingBytes == expectedSize && verify(partial, distfile)) {
                return existingBytes;
            }

            long partialBytes = existingBytes;
            LOGGER.atInfo().log(() -> "Discarding invalid partial download. name="
                    + distfile.name()
                    + ", partial="
                    + partial
                    + ", partialBytes="
                    + partialBytes
                    + ", expectedBytes="
                    + expectedSize);
            Files.deleteIfExists(partial);
            return 0L;
        }

        return existingBytes;
    }

    /// Writes an HTTP response body into the partial file.
    ///
    /// @param body response body.
    /// @param partial partial output path.
    /// @param distfile distfile declaration.
    /// @param reporter progress reporter.
    /// @param initialBytes bytes already present in the partial file.
    /// @throws IOException when the response fails, a body wait times out, or the file cannot be written.
    /// @throws InterruptedException when cancellation is requested while writing.
    private void writeResponse(
            DistfileResponseBody body,
            Path partial,
            RuyiDistfile distfile,
            ProgressReporter reporter,
            long initialBytes) throws IOException, InterruptedException {
        try (PartialOutput partialOutput = openPartial(partial, initialBytes)) {
            FileChannel output = partialOutput.channel();
            long currentBytes = initialBytes;
            @Nullable Long expectedSize = distfile.sizeBytes();
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                List<ByteBuffer> buffers = body.next(bodyTimeout);
                if (buffers.isEmpty()) {
                    break;
                }
                for (ByteBuffer buffer : buffers) {
                    int read = buffer.remaining();
                    if (expectedSize != null
                            && (currentBytes > expectedSize || read > expectedSize - currentBytes)) {
                        throw new IOException(SdkMessages.get(
                                "core.download.sizeExceeded",
                                distfile.name(),
                                expectedSize));
                    }
                    while (buffer.hasRemaining()) {
                        output.write(buffer);
                    }
                    currentBytes += read;
                    reporter.report(new ProgressEvent(
                            "download",
                            SdkMessages.get("core.download.downloading", distfile.name()),
                            currentBytes,
                            distfile.sizeBytes(),
                            ProgressUnit.BYTES));
                }
                body.requestNext();
            }
        }
    }

    /// Opens a new partial or resumes through a private name. Shared partials are detached before writing.
    ///
    /// A file with one link can be appended in place. A shared file is detached before writing.
    ///
    /// @param partial canonical resume filename.
    /// @param initialBytes number of bytes retained from a previous attempt.
    /// @return open output positioned after the retained bytes.
    /// @throws IOException when the partial cannot be opened safely.
    private static PartialOutput openPartial(Path partial, long initialBytes) throws IOException {
        Path target = partial.toAbsolutePath();
        if (initialBytes == 0L) {
            try {
                return new PartialOutput(FileChannel.open(target, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), null);
            } catch (FileAlreadyExistsException _) {
                // Restart an existing partial through the detached path.
            }
        }
        @Nullable Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Download partial has no parent: " + partial);
        }
        if (initialBytes > 0L) {
            @Nullable Path leftover = findResumeLink(target);
            if (leftover != null) {
                return openResumeLink(leftover, initialBytes, partial);
            }
            long links = CacheTreeRemoval.linkCount(target);
            if (links == 1L) {
                return openInPlace(target, initialBytes, partial);
            }
        }
        Path directory = Files.createTempDirectory(parent, DOWNLOAD_PARTIAL_DIRECTORY_PREFIX);
        Path staged = directory.resolve("resume");
        @Nullable FileChannel output = null;
        boolean retainWorkingLink = false;
        try {
            if (initialBytes > 0L && linkUnsharedPartial(target, staged)) {
                output = FileChannel.open(staged, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                if (output.size() != initialBytes) {
                    throw new IOException("Download partial changed before it could be resumed: " + partial);
                }
                output.position(initialBytes);
                retainWorkingLink = true;
                return new PartialOutput(output, staged);
            }

            output = FileChannel.open(staged, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            if (initialBytes > 0L) {
                try (var input = FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    long copied = 0L;
                    while (copied < initialBytes) {
                        long count = output.transferFrom(input, copied, initialBytes - copied);
                        if (count == 0L) {
                            throw new IOException("Download partial changed before it could be resumed: " + partial);
                        }
                        copied += count;
                    }
                }
            }
            output.position(initialBytes);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            PartialOutput result = new PartialOutput(output, null);
            output = null;
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        } finally {
            if (!retainWorkingLink) {
                removeWorkingLink(staged);
            }
        }
    }

    /// Opens an exclusive partial in place so a resume never copies the retained prefix.
    ///
    /// @param target existing partial file.
    /// @param initialBytes bytes retained from the previous attempt.
    /// @param partial canonical partial path used in diagnostics.
    /// @return channel positioned after the retained bytes.
    /// @throws IOException when the file changed or cannot be opened.
    private static PartialOutput openInPlace(Path target, long initialBytes, Path partial) throws IOException {
        FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            if (channel.size() != initialBytes) {
                throw new IOException("Download partial changed before it could be resumed: " + partial);
            }
            channel.position(initialBytes);
            return new PartialOutput(channel, null);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /// Finds a leftover private resume link that still names the partial file.
    ///
    /// @param partial existing partial file.
    /// @return leftover resume link, or null when there is none.
    /// @throws IOException when the parent directory cannot be listed.
    private static @Nullable Path findResumeLink(Path partial) throws IOException {
        @Nullable Path parent = partial.getParent();
        if (parent == null) {
            return null;
        }
        try (var directories = Files.newDirectoryStream(parent, DOWNLOAD_PARTIAL_DIRECTORY_PREFIX + "*")) {
            for (Path directory : directories) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Path candidate = directory.resolve("resume");
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                        && Files.isSameFile(candidate, partial)
                        && CacheTreeRemoval.hasExactlyTwoLinks(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /// Reopens a private resume link left by an interrupted download.
    ///
    /// @param link leftover private resume link.
    /// @param initialBytes bytes retained from the previous attempt.
    /// @param partial canonical partial path used in diagnostics.
    /// @return channel positioned after the retained bytes.
    /// @throws IOException when the file changed or cannot be opened.
    private static PartialOutput openResumeLink(Path link, long initialBytes, Path partial) throws IOException {
        FileChannel channel = FileChannel.open(link, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            if (channel.size() != initialBytes) {
                throw new IOException("Download partial changed before it could be resumed: " + partial);
            }
            channel.position(initialBytes);
            return new PartialOutput(channel, link);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /// Creates a private name for an unshared partial if the file system can verify its link count.
    private static boolean linkUnsharedPartial(Path partial, Path staged) throws IOException {
        try {
            if (Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS)) {
                Files.createLink(staged, partial);
                if (Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)
                        && Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS)
                        && Files.isSameFile(staged, partial) && CacheTreeRemoval.hasExactlyTwoLinks(staged)) {
                    return true;
                }
            }
        } catch (IOException | UnsupportedOperationException _) {
            // Use an exclusive copy when a private link or its identity cannot be verified.
        }
        Files.deleteIfExists(staged);
        return false;
    }

    /// Removes the private resume name and its working directory.
    private static void removeWorkingLink(Path staged) {
        Path directory = staged.getParent();
        try {
            Files.deleteIfExists(staged);
            Files.deleteIfExists(directory);
        } catch (IOException failure) {
            LOGGER.warn("Failed to remove download working directory: " + directory, failure);
        }
    }

    /// Holds the partial channel and any private name retained until it closes.
    ///
    /// @param channel output channel for resume data.
    /// @param workingLink optional hard link retained while the channel is open.
    @NotNullByDefault
    private record PartialOutput(FileChannel channel, @Nullable Path workingLink) implements AutoCloseable {
        /// Closes the channel before removing a retained private name.
        @Override
        public void close() throws IOException {
            try {
                channel.close();
            } finally {
                if (workingLink != null) {
                    removeWorkingLink(workingLink);
                }
            }
        }
    }

    /// Publishes a partial download after successful verification.
    ///
    /// @param partial partial path.
    /// @param target final target path.
    /// @param cacheRoot physical cache root for a version-locked download.
    /// @param canRelocateStaging whether the caller holds the version lock.
    /// @throws IOException when marker clearance or the move fails.
    private static void publishVerifiedPartial(Path partial, Path target, @Nullable Path cacheRoot,
                                               boolean canRelocateStaging) throws IOException {
        clearArchiveMarker(cacheRoot, target);
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            String targetName = target.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!canRelocateStaging && isDownloadPartialDirectoryName(targetName)) {
                // A non-atomic replacement can remove an empty staging directory created after selection.
                throw e;
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Creates a manual download error message.
    ///
    /// @param distfile distfile declaration.
    /// @param target expected target path.
    /// @return manual download message.
    private static String manualDownloadMessage(RuyiDistfile distfile, Path target) {
        @Nullable RuyiFetchRestriction restriction = distfile.fetchRestriction();
        if (restriction == null) {
            return SdkMessages.get("core.download.manual", distfile.name());
        }

        String instructions = restriction.render(target, Locale.getDefault());
        if (instructions.isBlank()) {
            return SdkMessages.get("core.download.manual", distfile.name());
        }
        return SdkMessages.get("core.download.manualWithInstructions", distfile.name(), instructions);
    }

    /// Reads file size if the file exists.
    ///
    /// @param path file path.
    /// @return size in bytes, or null when the file cannot be read.
    private static @Nullable Long bytesOrNull(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return null;
        }
    }
}
