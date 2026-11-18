// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests measuring and cleaning the image download cache.
@NotNullByDefault
public final class ImageCacheServiceTest {
    /// Reports entries, totals, repository and log size of a populated cache.
    @Test
    public void reportsEntriesAndTotals(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        write(cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.ext4.zst"), 100);
        write(cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/boot.ext4.zst.part"), 10);
        write(cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/boot.ext4.zst.part.ruyi-partial"), 0);
        write(cache.resolve("artifacts/ruyisdk/board-image/meles/1.0.0/root.ext4"), 200);
        write(cache.resolve("artifacts/ruyisdk/board-image/meles/.old-1.0.0-123/file"), 5);
        write(cache.resolve("downloads/ruyisdk/image-combo/meles@4g/2.0.0/root.zst"), 50);
        write(cache.resolve("artifacts/ruyisdk/image-combo/meles@4g/2.0.0/root.ext4"), 300);
        write(cache.resolve("repos/ruyisdk/metadata"), 7);
        write(cache.resolve("logs/ruyi.log"), 3);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheUsage usage = service.usage();

        assertEquals(2, usage.entries().size());
        assertEquals(150L, usage.downloadBytes());
        assertEquals(500L, usage.artifactBytes());
        assertEquals(15L, usage.partialBytes());
        assertEquals(2, usage.partialFiles());
        assertEquals(7L, usage.repositoryBytes());
        assertEquals(3L, usage.logBytes());
        assertEquals(665L, usage.totalBytes());
        assertTrue(usage.unreadable().isEmpty(), usage.unreadable().toString());
        ImageCacheEntry boardImage = usage.entries().stream()
                .filter(entry -> entry.atom().equals("board-image/meles(1.0.0)"))
                .findFirst()
                .orElseThrow();
        assertEquals(100L, boardImage.downloadBytes());
        assertEquals(200L, boardImage.artifactBytes());
        assertEquals(15L, boardImage.partialBytes());
        assertEquals(2, boardImage.partialFiles());
        assertTrue(boardImage.partial());
        assertEquals(315L, boardImage.totalBytes());
    }

    /// Removes only the selected image version and leaves everything else in place.
    @Test
    public void cleanEntriesRemovesOnlySelectedVersions(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path firstDownloads = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.ext4.zst");
        Path firstArtifacts = cache.resolve("artifacts/ruyisdk/board-image/meles/1.0.0/root.ext4");
        Path secondDownloads = cache.resolve("downloads/ruyisdk/image-combo/meles@4g/2.0.0/root.zst");
        write(firstDownloads, 100);
        write(firstArtifacts, 200);
        write(secondDownloads, 50);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        ImageCacheEntry entry = entry(service, "board-image/meles(1.0.0)");

        ImageCacheCleanResult result = service.cleanEntries(List.of(entry));

        assertEquals(300L, result.removedBytes());
        assertEquals(2, result.removedFiles());
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertFalse(Files.exists(firstDownloads));
        assertFalse(Files.exists(firstArtifacts));
        assertTrue(Files.exists(secondDownloads));
    }

    /// Removes incomplete downloads and leftover directories only.
    @Test
    public void cleanPartialsRemovesOnlyLeftovers(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path part = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/boot.ext4.zst.part");
        Path partMarker = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/boot.ext4.zst.part.ruyi-partial");
        Path complete = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.ext4.zst");
        Path leftover = cache.resolve("artifacts/ruyisdk/board-image/meles/.staging-1.0.0-123/file");
        Path artifact = cache.resolve("artifacts/ruyisdk/board-image/meles/1.0.0/root.ext4");
        write(part, 10);
        write(partMarker, 0);
        write(complete, 100);
        write(leftover, 5);
        write(artifact, 200);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheCleanResult result = service.cleanPartials();

        assertEquals(15L, result.removedBytes());
        assertFalse(Files.exists(part));
        assertFalse(Files.exists(leftover.getParent()));
        assertTrue(Files.exists(complete));
        assertTrue(Files.exists(artifact));
    }

    /// Removes every cached image version but keeps metadata repositories and logs.
    @Test
    public void cleanAllKeepsRepositoriesAndLogs(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path downloads = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.ext4.zst");
        Path artifacts = cache.resolve("artifacts/ruyisdk/board-image/meles/1.0.0/root.ext4");
        Path repository = cache.resolve("repos/ruyisdk/metadata");
        Path log = cache.resolve("logs/ruyi.log");
        write(downloads, 100);
        write(artifacts, 200);
        write(repository, 7);
        write(log, 3);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheCleanResult result = service.cleanAll();

        assertEquals(300L, result.removedBytes());
        assertFalse(Files.exists(downloads));
        assertFalse(Files.exists(artifacts));
        assertTrue(Files.exists(repository));
        assertTrue(Files.exists(log));
        assertEquals(0L, service.usage().totalBytes());
    }

    /// Reports an empty cache when nothing has been downloaded yet.
    @Test
    public void reportsEmptyUsageForMissingCache(@TempDir Path directory) {
        ImageCacheService service = new ImageCacheService(
                new AppDirectories(directory.resolve("config"), directory.resolve("cache")));

        ImageCacheUsage usage = service.usage();

        assertTrue(usage.entries().isEmpty());
        assertEquals(0L, usage.totalBytes());
        assertEquals(0L, usage.repositoryBytes());
        assertEquals(0L, usage.logBytes());
    }

    /// Ignores empty version directories left behind by interrupted operations.
    @Test
    public void ignoresEmptyVersionDirectories(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Files.createDirectories(cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0"));
        write(cache.resolve("downloads/ruyisdk/board-image/meles/2.0.0/root.zst"), 10);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheUsage usage = service.usage();

        assertEquals(1, usage.entries().size());
        assertEquals("board-image/meles(2.0.0)", usage.entries().getFirst().atom());
    }

    /// Skips a version whose cache lock is held by another operation.
    @Test
    public void skipsEntriesWhoseLockIsHeld(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path cached = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst");
        write(cached, 100);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        ImageCacheEntry entry = entry(service, "board-image/meles(1.0.0)");
        Path lockPath = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0.lock");

        try (FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
             @Nullable FileLock lock = channel.lock()) {
            ImageCacheCleanResult result = service.cleanEntries(List.of(entry));

            assertTrue(Files.exists(cached));
            assertFalse(result.skipped().isEmpty());
            assertEquals(0L, result.removedBytes());
        }
    }

    /// Attributes leftover materialization directories to their image version.
    @Test
    public void attributesLeftoversToEntries(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path leftover = cache.resolve("artifacts/ruyisdk/board-image/meles/.staging-1.0.0-123/file");
        write(leftover, 5);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheUsage usage = service.usage();

        assertEquals(1, usage.entries().size());
        ImageCacheEntry entry = usage.entries().getFirst();
        assertEquals("board-image/meles(1.0.0)", entry.atom());
        assertEquals(5L, entry.partialBytes());
        assertEquals(1, entry.partialFiles());
        assertTrue(entry.partial());
        service.cleanPartials();
        assertFalse(Files.exists(leftover.getParent()));
    }

    /// Treats local-import staging files as incomplete downloads.
    @Test
    public void treatsImportStagingAsPartial(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path staging = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/.import-abc.tmp");
        Path complete = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst");
        write(staging, 7);
        write(complete, 100);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheUsage usage = service.usage();
        assertEquals(7L, usage.partialBytes());
        assertEquals(1, usage.partialFiles());

        service.cleanPartials();

        assertFalse(Files.exists(staging));
        assertTrue(Files.exists(complete));
    }

    /// Removes matching entries from every repository that caches the same image version.
    @Test
    public void removesMatchingEntriesFromEveryRepository(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path first = cache.resolve("downloads/repo-a/board-image/meles/1.0.0/root.zst");
        Path second = cache.resolve("downloads/repo-b/board-image/meles/1.0.0/root.zst");
        write(first, 10);
        write(second, 20);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheCleanResult result = service.cleanEntries(service.usage().entries());

        assertEquals(30L, result.removedBytes());
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
    }

    /// Lists and removes versions that only contain zero-byte files.
    @Test
    public void listsZeroByteEntries(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path empty = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst");
        write(empty, 0);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheUsage usage = service.usage();

        assertEquals(1, usage.entries().size());
        assertEquals(0L, usage.downloadBytes());
        ImageCacheCleanResult result = service.cleanEntries(usage.entries());
        assertEquals(1, result.removedFiles());
        assertFalse(Files.exists(empty));
    }

    /// Copies the provided lists so later mutations do not change a snapshot.
    @Test
    public void copiesProvidedLists(@TempDir Path directory) {
        ImageCacheEntry entry = new ImageCacheEntry(
                "ruyisdk", "board-image", "meles", "1.0.0", "board-image/meles(1.0.0)",
                1L, 2L, 0L, 0, 3L);
        ArrayList<ImageCacheEntry> entries = new ArrayList<>(List.of(entry));
        ArrayList<String> unreadable = new ArrayList<>(List.of("missing"));
        ImageCacheUsage usage = new ImageCacheUsage(entries, 2L, 1L, 0L, 0, 0L, 0L, unreadable);

        entries.clear();
        unreadable.clear();

        assertEquals(1, usage.entries().size());
        assertEquals(1, usage.unreadable().size());
        ArrayList<String> skipped = new ArrayList<>(List.of("busy"));
        ImageCacheCleanResult result = new ImageCacheCleanResult(1L, 1, skipped);
        skipped.clear();
        assertEquals(1, result.skipped().size());
    }

    /// Accounts a symbolic link by its own size and removes the link, not its target.
    @Test
    public void accountsSymbolicLinksByLinkSize(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path target = directory.resolve("target.bin");
        Files.write(target, new byte[1000]);
        Path link = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheUsage usage = service.usage();
        assertEquals(1, usage.entries().size());
        assertTrue(usage.entries().getFirst().downloadBytes() < 1000L);
        service.cleanEntries(usage.entries());

        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(target));
    }

    /// Removes dangling symbolic links instead of reporting an unreadable file.
    @Test
    public void removesDanglingSymbolicLinks(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path link = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, directory.resolve("missing.bin"));
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheCleanResult result = service.cleanEntries(service.usage().entries());

        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
    }

    /// Keeps an archive whose declared name ends with .part as a complete file.
    @Test
    public void keepsArchivesNamedLikePartials(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.part");
        write(archive, 100);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheUsage usage = service.usage();
        assertEquals(0, usage.partialFiles());
        assertEquals(100L, usage.downloadBytes());

        service.cleanPartials();
        assertTrue(Files.exists(archive));

        ImageCacheCleanResult result = service.cleanEntries(usage.entries());
        assertFalse(Files.exists(archive));
        assertEquals(100L, result.removedBytes());
    }
    /// Never removes artifacts reached through a symbolic link outside the cache.
    @Test
    public void skipsArtifactsReachedThroughSymbolicLinks(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path downloads = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst");
        write(downloads, 10);
        Path outside = directory.resolve("outside");
        Path outsideVersion = outside.resolve("1.0.0/root.ext4");
        write(outsideVersion, 20);
        Path nameDirectory = cache.resolve("artifacts/ruyisdk/board-image/meles");
        Files.createDirectories(nameDirectory.getParent());
        try {
            Files.createSymbolicLink(nameDirectory, outside);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheCleanResult result = service.cleanEntries(service.usage().entries());

        assertFalse(Files.exists(downloads, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(outsideVersion));
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
    }

    /// Returns the entry with the given atom.
    ///
    /// @param service cache service.
    /// @param atom image atom.
    /// @return matching entry.
    private static ImageCacheEntry entry(ImageCacheService service, String atom) {
        return service.usage().entries().stream()
                .filter(candidate -> candidate.atom().equals(atom))
                .findFirst()
                .orElseThrow();
    }

    /// Writes a file of the requested size, creating parent directories.
    ///
    /// @param file file to write.
    /// @param size file size in bytes.
    /// @throws IOException when the file cannot be written.
    private static void write(Path file, int size) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[size]);
    }
}
