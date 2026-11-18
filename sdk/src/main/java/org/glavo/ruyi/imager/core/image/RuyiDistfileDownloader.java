// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

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
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.glavo.ruyi.imager.core.image.DistfileIntegrity.hasSupportedChecksum;
import static org.glavo.ruyi.imager.core.image.DistfileIntegrity.verify;

/// Downloads and verifies Ruyi distfiles.
@NotNullByDefault
public final class RuyiDistfileDownloader {
    /// Prefix of reserved resume directories used when a sibling archive occupies the legacy partial name.
    static final String DOWNLOAD_PARTIAL_DIRECTORY_PREFIX = ".download-partials-";

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
    /// @param distfile distfile declaration.
    /// @param targetDirectory target directory.
    /// @param reporter progress reporter.
    /// @return verified target path.
    /// @throws IOException when the distfile cannot be downloaded or verified.
    public Path download(RuyiDistfile distfile, Path targetDirectory, ProgressReporter reporter) throws IOException {
        return download(distfile, targetDirectory, null, List.of(), reporter);
    }

    /// Downloads a distfile and records an incomplete-download marker when requested.
    ///
    /// The caller holds the version lock when supplying the cache root.
    ///
    /// @param distfile distfile declaration.
    /// @param targetDirectory target directory.
    /// @param cacheRoot cache root used to store the incomplete-download marker, or null.
    /// @param archives declarations whose final paths must never be opened as resume files.
    /// @param reporter progress reporter.
    /// @return verified target path.
    /// @throws IOException when the distfile cannot be downloaded or verified.
    Path download(
            RuyiDistfile distfile,
            Path targetDirectory,
            @Nullable Path cacheRoot,
            List<RuyiDistfile> archives,
            ProgressReporter reporter) throws IOException {
        Files.createDirectories(targetDirectory);
        Path target = targetDirectory.resolve(distfile.name());
        LOGGER.atInfo().log(() -> "Downloading distfile. name="
                + distfile.name()
                + ", target="
                + target
                + ", sources="
                + distfile.sourceUris().size());
        boolean hasSupportedChecksum = hasSupportedChecksum(distfile);
        if (Files.isRegularFile(target)
                && verify(target, distfile)
                && (distfile.fetchRestricted() || hasSupportedChecksum)) {
            LOGGER.atInfo().log(() -> "Using cached distfile. name=" + distfile.name() + ", path=" + target);
            reporter.report(new ProgressEvent(
                    "download",
                    SdkMessages.get("core.download.cached", distfile.name()),
                    bytesOrNull(target),
                    distfile.sizeBytes(),
                    ProgressUnit.BYTES));
            return target;
        }

        if (distfile.fetchRestricted()) {
            LOGGER.atWarn().log(() -> "Distfile requires manual download. name=" + distfile.name() + ", target=" + target);
            throw new IOException(manualDownloadMessage(distfile, target));
        }

        if (!hasSupportedChecksum) {
            LOGGER.atWarn().log(() -> "Distfile has no supported checksum for automatic download. name="
                    + distfile.name());
            throw new IOException(SdkMessages.get("core.download.noSupportedChecksum", distfile.name()));
        }

        List<URI> sourceUris = distfile.sourceUris();
        if (sourceUris.isEmpty()) {
            LOGGER.atWarn().log(() -> "Distfile has no source URLs. name=" + distfile.name());
            throw new IOException(SdkMessages.get("core.download.noUrls", distfile.name()));
        }

        Path partial = partialPath(distfile, targetDirectory, archives);
        @Nullable Path partialDirectory = partial.getParent();
        boolean reservedPartial = partialDirectory != null && !partialDirectory.equals(targetDirectory);
        if (reservedPartial) {
            Files.createDirectories(partialDirectory);
        }
        boolean newPartial = Files.notExists(partial, LinkOption.NOFOLLOW_LINKS);
        @Nullable Path markerRoot = reservedPartial ? null : cacheRoot;
        if (markerRoot != null) {
            try {
                ImageCacheMarkers.write(markerRoot, partial);
            } catch (IOException markerFailure) {
                LOGGER.warn("Failed to write the download marker. partial=" + partial, markerFailure);
            }
        }

        try {
            IOException failure = null;
            for (URI sourceUri : sourceUris) {
                try {
                    downloadFromUri(distfile, sourceUri, partial, reporter);
                    moveVerifiedPartial(partial, target, distfile);
                    LOGGER.atInfo().log(() -> "Distfile download completed. name=" + distfile.name() + ", target=" + target);
                    return target;
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
        } finally {
            cleanupPartial(partial, newPartial, markerRoot);
            if (reservedPartial) {
                try {
                    Files.deleteIfExists(partialDirectory);
                } catch (DirectoryNotEmptyException _) {
                    // Other resume data can share the same reserved directory.
                } catch (IOException failure) {
                    LOGGER.warn("Failed to clean up the download staging directory: " + partialDirectory, failure);
                }
            }
        }
    }

    /// Selects a resume path that does not occupy another declared archive's final name.
    ///
    /// @param distfile distfile declaration.
    /// @param targetDirectory directory of the image version.
    /// @param archives declarations for the version.
    /// @return resume file path.
    /// @throws IOException when a candidate directory cannot be inspected.
    static Path partialPath(RuyiDistfile distfile, Path targetDirectory, List<RuyiDistfile> archives) throws IOException {
        String partialName = distfile.name() + ".part";
        if (!isDeclaredName(partialName, archives)) {
            return targetDirectory.resolve(partialName);
        }

        for (int slot = 0; ; slot++) {
            String directoryName = DOWNLOAD_PARTIAL_DIRECTORY_PREFIX + slot;
            if (isDeclaredName(directoryName, archives)) {
                continue;
            }
            Path candidate = targetDirectory.resolve(directoryName);
            try {
                BasicFileAttributes attributes =
                        Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory()
                        && candidate.toRealPath().equals(targetDirectory.toRealPath().resolve(directoryName))) {
                    return candidate.resolve(partialName);
                }
            } catch (NoSuchFileException _) {
                return candidate.resolve(partialName);
            }
        }
    }

    /// Checks whether the name might resolve to a declared archive on the actual file system.
    ///
    /// P2:258 只比较大小写无法识别 NFC/NFD 等价拼写；按规范化等价规则折叠后，任何可能解析到
    /// 正式归档名的续传名都会改用隔离目录，避免续传或丢弃分片时处理到完整归档。
    ///
    /// @param name candidate filename.
    /// @param archives declarations for the version.
    /// @return whether the name matches a declaration.
    private static boolean isDeclaredName(String name, List<RuyiDistfile> archives) {
        String folded = foldName(name);
        return archives.stream().anyMatch(archive -> foldName(archive.name()).equals(folded));
    }

    /// Folds one file name to the characters that file systems compare as equal.
    ///
    /// @param name file name.
    /// @return decomposed, lower-case form used only for collision checks.
    private static String foldName(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
    }

    /// Preserves resumable bytes and discards any empty file first created by this download.
    ///
    /// @param partial resume file path.
    /// @param newPartial whether the resume file was absent before downloading.
    /// @param cacheRoot cache root with a marker for the resume file, or null.
    private static void cleanupPartial(Path partial, boolean newPartial, @Nullable Path cacheRoot) {
        try {
            if (newPartial && Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS) && Files.size(partial) == 0) {
                Files.deleteIfExists(partial);
            }
            if (cacheRoot != null && Files.notExists(partial, LinkOption.NOFOLLOW_LINKS)) {
                ImageCacheMarkers.delete(cacheRoot, partial);
            }
        } catch (IOException failure) {
            LOGGER.warn("Failed to clean up the download partial or its marker. partial=" + partial, failure);
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
                return;
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
    /// P2:503 续传不再把已下载前缀整段复制到同卷临时文件：
    /// 复用上次异常退出留下的私有续传链接，独占文件则直接原位追加，
    /// 因此 8 GiB 归档下载到 7 GiB 续传时无需额外的 7 GiB 空间。
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
                // P2:503 文件没有其他目录项：直接原位追加，既不需要复制前缀，也不会改动其他名称的内容。
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

    /// Moves a verified partial download into its final path.
    ///
    /// @param partial partial path.
    /// @param target final target path.
    /// @param distfile distfile declaration.
    /// @throws IOException when verification or move fails.
    private static void moveVerifiedPartial(Path partial, Path target, RuyiDistfile distfile) throws IOException {
        if (!Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS) || !verify(partial, distfile)) {
            LOGGER.atWarn().log(() -> "Downloaded distfile verification failed. name="
                    + distfile.name()
                    + ", partial="
                    + partial);
            throw new IOException(SdkMessages.get("core.download.verifyFailed", distfile.name()));
        }

        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
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
