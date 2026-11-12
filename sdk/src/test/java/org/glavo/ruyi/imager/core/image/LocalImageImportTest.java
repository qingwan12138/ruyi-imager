// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.repo.RuyiRepositoryStore;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises local content matching, offline preparation, and cache isolation without devices.
@NotNullByDefault
final class LocalImageImportTest {
    /// Silent progress reporter.
    private static final ProgressReporter SILENT = _ -> { };

    /// Renaming does not change identity, and shared files preserve all image candidates.
    @Test
    void matchesRenamedFileAndAllCandidates(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("renamed.data"), "payload");
        RuyiDistfile file = file("disk.img", source, true);
        ImageEntry first = image("first", List.of(file));
        ImageEntry second = image("second", List.of(file));
        AtomicInteger reads = new AtomicInteger();
        var result = LocalImageMatcher.match(source, new ImageCatalog(List.of(first, second)), _ -> reads.incrementAndGet());
        assertEquals(List.of(first, second), result.stream().map(LocalImageMatch::image).toList());
        assertEquals(List.of(file), result.getFirst().distfiles());
        assertEquals(1, reads.get());
        Files.writeString(source, "changed");
        assertTrue(LocalImageMatcher.match(source, new ImageCatalog(List.of(first)), SILENT).isEmpty());
    }

    /// Both declared digests must match, including declarations with an unknown size.
    @Test
    void requiresStrongConsistentDigests(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("input"), "data");
        RuyiDistfile original = file("disk.img", source, false);
        RuyiDistfile unknownSize = declaration(original, null, original.checksums());
        RuyiDistfile weak = declaration(original, 4L, Map.of("md5", "unused"));
        RuyiDistfile conflicting = declaration(original, 4L, Map.of(
                "sha256", original.checksums().get("sha256"), "sha512", "0".repeat(128)));
        RuyiDistfile sha512 = declaration(original, null, Map.of("sha512", original.checksums().get("sha512")));
        ImageEntry valid = image("valid", List.of(unknownSize));
        ImageEntry valid512 = image("sha512", List.of(sha512));
        var result = LocalImageMatcher.match(source, new ImageCatalog(List.of(valid,
                image("weak", List.of(weak)), image("conflicting", List.of(conflicting)), valid512)), SILENT);
        assertEquals(List.of(valid, valid512), result.stream().map(LocalImageMatch::image).toList());
    }

    /// A local restricted download is copied and prepared offline without modifying its source.
    @Test
    void preparesRestrictedFileOffline(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("renamed"), "disk content");
        ImageEntry image = image("image", List.of(file("disk.img", source, true)));
        var service = service(root);
        assertTrue(service.prepareLocalImage(image, List.of(source), false, SILENT).isEmpty());
        Path artifact = service.downloadImage(image, SILENT);
        assertEquals("disk content", Files.readString(artifact));
        assertFalse(Files.isSameFile(source, cache(root, image).resolve("disk.img")));
        Files.writeString(source, "modified");
        assertEquals("disk content", Files.readString(artifact));
        assertEquals("disk content", Files.readString(cache(root, image).resolve("disk.img")));
        assertNoStaging(cache(root, image));
    }

    /// Missing components remain explicit until additional local files are supplied.
    @Test
    void requiresAllFilesBeforePreparation(@TempDir Path root) throws Exception {
        Path first = Files.writeString(root.resolve("first"), "first payload");
        Path second = Files.writeString(root.resolve("second"), "second payload");
        RuyiDistfile a = file("a.img", first, true);
        RuyiDistfile b = file("b.img", second, true);
        ImageEntry image = image("combo", List.of(a, b));
        var service = service(root);
        assertEquals(List.of(b), service.prepareLocalImage(image, List.of(first), false, SILENT));
        assertFalse(Files.exists(root.resolve("cache/artifacts/test/board-image/combo/1")));
        assertTrue(service.prepareLocalImage(image, List.of(second), false, SILENT).isEmpty());
        assertEquals("first payload", Files.readString(service.downloadImage(image, SILENT).resolve("a.img")));
    }

    /// Identification does not bypass import-time verification after the source changes.
    @Test
    void rechecksChangedSourceWithoutReplacingCache(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("source"), "original");
        ImageEntry image = image("image", List.of(file("disk.img", source, false)));
        assertEquals(1, LocalImageMatcher.match(source, new ImageCatalog(List.of(image)), SILENT).size());
        Files.writeString(source, "modified");
        assertThrows(IOException.class, () -> service(root).prepareLocalImage(image, List.of(source), false, SILENT));
        assertFalse(Files.exists(cache(root, image).resolve("disk.img")));
        assertNoStaging(cache(root, image));
    }

    /// Cancelling an import removes only its staging file and never publishes partial content.
    @Test
    void cancellationCleansStaging(@TempDir Path root) throws Exception {
        Path source = Files.write(root.resolve("source"), new byte[300_000]);
        ImageEntry image = image("image", List.of(file("disk.img", source, false)));
        try {
            assertThrows(InterruptedIOException.class, () -> service(root).prepareLocalImage(image, List.of(source), false,
                    _ -> Thread.currentThread().interrupt()));
        } finally {
            Thread.interrupted();
        }
        assertEquals(300_000, Files.size(source));
        assertFalse(Files.exists(cache(root, image).resolve("disk.img")));
        assertNoStaging(cache(root, image));
    }

    /// Import does not overwrite conflicting cached content.
    @Test
    void preservesCacheConflict(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("source"), "expected");
        ImageEntry image = image("image", List.of(file("disk.img", source, false)));
        Path directory = Files.createDirectories(cache(root, image));
        Path cached = Files.writeString(directory.resolve("disk.img"), "existing");
        assertThrows(IOException.class, () -> service(root).prepareLocalImage(image, List.of(source), false, SILENT));
        assertEquals("existing", Files.readString(cached));
        assertNoStaging(directory);
    }

    /// Import and download both respect the same cache lock.
    @Test
    void sharesLockWithDownloader(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("source"), "expected");
        ImageEntry image = image("image", List.of(file("disk.img", source, false)));
        Path directory = Files.createDirectories(cache(root, image));
        try (var channel = FileChannel.open(directory.resolveSibling("1.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            var service = service(root);
            assertThrows(IOException.class, () -> service.prepareLocalImage(image, List.of(source), false, SILENT));
            assertThrows(IOException.class, () -> service.downloadImage(image, SILENT));
        }
        assertFalse(Files.exists(directory.resolve("disk.img")));
    }

    /// Empty files cannot establish an image identity or be imported.
    @Test
    void rejectsEmptyImages(@TempDir Path root) throws Exception {
        Path source = Files.createFile(root.resolve("empty"));
        ImageEntry image = image("image", List.of(file("disk.img", source, false)));
        assertTrue(LocalImageMatcher.match(source, new ImageCatalog(List.of(image)), SILENT).isEmpty());
        assertThrows(IOException.class, () -> service(root).prepareLocalImage(image, List.of(source), false, SILENT));
    }

    /// A renamed archive is unpacked using catalog metadata, not its local extension.
    @Test
    void preparesRenamedArchiveAndRejectsEmptyOutput(@TempDir Path root) throws Exception {
        for (String content : List.of("disk content", "")) {
            Path source = root.resolve("renamed.data");
            try (var gzip = new GZIPOutputStream(Files.newOutputStream(source))) {
                gzip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            RuyiDistfile original = file("disk.img.gz", source, true);
            RuyiDistfile compressed = new RuyiDistfile(original.name(), List.of(), original.sizeBytes(), original.checksums(),
                    true, false, null, 0, List.of(), "gz");
            ImageEntry image = new ImageEntry("test", "board-image", content.isEmpty() ? "empty" : "compressed", "1", null,
                    "board-image/compressed", "Compressed", "Vendor", "board", "generic", "dd-v1",
                    Map.of("disk", "disk.img"), List.of(compressed), StrategySupport.SUPPORTED);
            var service = service(root);
            assertEquals(1, LocalImageMatcher.match(source, new ImageCatalog(List.of(image)), SILENT).size());
            if (content.isEmpty()) {
                assertThrows(IOException.class, () -> service.prepareLocalImage(image, List.of(source), false, SILENT));
            } else {
                assertTrue(service.prepareLocalImage(image, List.of(source), false, SILENT).isEmpty());
                assertEquals(content, Files.readString(service.downloadImage(image, SILENT)));
            }
        }
    }

    /// Space limits remain enforced before a prepared image can reach the device workflow.
    @Test
    void rejectsPreparationOverBudget(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("source"), "too large");
        ImageEntry image = image("image", List.of(file("disk.img", source, true)));
        var directories = new AppDirectories(root.resolve("config"), root.resolve("cache"));
        var service = new RuyiImageCatalogService(directories, new RuyiRepositoryStore(directories),
                new RuyiDistfileDownloader(), new RuyiImageMaterializer(2, 2, 10, 0));
        assertThrows(IOException.class, () -> service.prepareLocalImage(image, List.of(source), false, SILENT));
        assertEquals("too large", Files.readString(source));
        assertFalse(Files.exists(root.resolve("cache/artifacts/test/board-image/image/1")));
        assertNoStaging(cache(root, image));
    }

    /// A growing source cannot turn the bounded import into an unbounded cache write.
    @Test
    void rejectsSourceGrowth(@TempDir Path root) throws Exception {
        Path source = Files.write(root.resolve("source"), new byte[300_000]);
        ImageEntry image = image("image", List.of(file("disk.img", source, false)));
        AtomicInteger events = new AtomicInteger();
        assertThrows(IOException.class, () -> service(root).prepareLocalImage(image, List.of(source), false, _ -> {
            if (events.getAndIncrement() == 0) {
                try {
                    Files.write(source, new byte[1], StandardOpenOption.APPEND);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }));
        assertFalse(Files.exists(cache(root, image).resolve("disk.img")));
        assertNoStaging(cache(root, image));
    }

    /// Creates a service with isolated metadata and cache directories.
    private static RuyiImageCatalogService service(Path root) {
        var directories = new AppDirectories(root.resolve("config"), root.resolve("cache"));
        return new RuyiImageCatalogService(directories, new RuyiRepositoryStore(directories));
    }

    /// Resolves a fixture image's download directory.
    private static Path cache(Path root, ImageEntry image) {
        return root.resolve("cache/downloads/test/board-image").resolve(image.name()).resolve("1");
    }

    /// Builds a complete raw multi-partition declaration.
    private static ImageEntry image(String name, List<RuyiDistfile> files) {
        Map<String, String> partitions = new java.util.LinkedHashMap<>();
        files.forEach(file -> partitions.put(file.name(), file.name()));
        return new ImageEntry("test", "board-image", name, "1", null, "board-image/" + name, name,
                "Vendor", "board", "generic", "dd-v1", partitions, files, StrategySupport.SUPPORTED);
    }

    /// Declares digests for fixture bytes without any network source.
    private static RuyiDistfile file(String name, Path source, boolean restricted) throws Exception {
        byte[] bytes = Files.readAllBytes(source);
        return new RuyiDistfile(name, List.of(), (long) bytes.length,
                Map.of("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                        "sha512", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes))),
                restricted, false, null, 0, List.of(), "raw");
    }

    /// Replaces integrity metadata for matching boundary tests.
    private static RuyiDistfile declaration(RuyiDistfile file, @Nullable Long size, Map<String, String> checksums) {
        return new RuyiDistfile(file.name(), List.of(), size, checksums, false, false, null, 0, List.of(), "raw");
    }

    /// Verifies that no abandoned import staging files remain.
    private static void assertNoStaging(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".import-")));
        }
    }
}
