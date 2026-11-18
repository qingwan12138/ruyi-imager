// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        ImageCacheMarkers.write(cache, cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/boot.ext4.zst.part"));
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
        Path complete = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.ext4.zst");
        Path leftover = cache.resolve("artifacts/ruyisdk/board-image/meles/.staging-1.0.0-123/file");
        Path artifact = cache.resolve("artifacts/ruyisdk/board-image/meles/1.0.0/root.ext4");
        write(part, 10);
        ImageCacheMarkers.write(cache, part);
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
        Files.createDirectories(cache.resolve("downloads/ruyisdk/board-image/meles"));
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
        ImageCacheService service = new ImageCacheService(
                new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root.zst"), List.of("1.0.0")));

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

        assertTrue(Files.exists(downloads, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(outsideVersion));
        assertFalse(result.skipped().isEmpty());
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

    /// Separates archive completeness from the presence of temporary files.
    @Test
    public void measuresMultiArchiveCompleteness(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path version = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0");
        write(version.resolve("root.zst"), 10);
        ImageCacheService service = new ImageCacheService(
                new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root.zst", "boot.zst"), List.of("1.0.0")));

        ImageCacheEntry incomplete = service.usage().entries().getFirst();
        assertEquals(ImageCacheEntry.Completeness.INCOMPLETE, incomplete.completeness());
        assertEquals(0, incomplete.partialFiles());

        write(version.resolve("boot.zst"), 10);
        assertEquals(ImageCacheEntry.Completeness.COMPLETE, service.usage().entries().getFirst().completeness());
    }

    /// Treats a download left by an older version as incomplete when the catalog declares it.
    @Test
    public void recognizesLegacyPartialDownloads(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path part = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst.part");
        write(part, 10);
        ImageCacheService service = new ImageCacheService(
                new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root.zst"), List.of("1.0.0")));

        ImageCacheUsage usage = service.usage();
        assertEquals(1, usage.partialFiles());
        assertEquals(10L, usage.partialBytes());

        service.cleanPartials();
        assertFalse(Files.exists(part));
    }

    /// A matching complete archive takes precedence over an obsolete partial marker.
    @Test
    public void protectsDeclaredArchiveWithObsoletePartialMarker(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.part");
        write(archive, 20);
        ImageCacheMarkers.write(cache, archive);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root", "root.part"), List.of("1.0.0")));
        assertEquals(0, service.usage().partialFiles());
        assertEquals(0, service.cleanPartials().removedFiles());
        assertTrue(Files.exists(archive));
    }

    /// A nested filename alone does not make it a legacy download partial.
    @Test
    public void keepsNestedFileWithLegacyPartialName(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path nested = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/backup/disk.img.part");
        write(nested, 10);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("disk.img"), List.of("1.0.0")));
        assertEquals(0, service.usage().partialFiles());
        assertEquals(0, service.cleanPartials().removedFiles());
        assertTrue(Files.exists(nested));
    }

    /// Completeness and partial cleanup use the filesystem's path resolution.
    @Test
    public void protectsCaseAliasedDeclaredArchive(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.part");
        write(archive, 20);
        write(archive.resolveSibling("root"), 10);
        Assumptions.assumeTrue(Files.isRegularFile(archive.resolveSibling("ROOT.PART")),
                "the filesystem is case-sensitive");
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root", "ROOT.PART"), List.of("1.0.0")));
        assertEquals(ImageCacheEntry.Completeness.COMPLETE, service.usage().entries().getFirst().completeness());
        assertEquals(0, service.cleanPartials().removedFiles());
        assertTrue(Files.exists(archive));
    }

    /// Cleaning a version cannot unlink a lock with the same name.
    @Test
    public void preservesOtherVersionLockAtDownloadTarget(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path lock = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.lock");
        write(cache.resolve("artifacts/ruyisdk/board-image/meles/1.0.lock/archive"), 10);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        try (@Nullable CacheLock held = CacheLock.acquire(lock)) {
            assertNotNull(held);
            ImageCacheCleanResult result = service.cleanEntries(service.usage().entries());
            assertTrue(Files.isRegularFile(lock));
            assertFalse(result.skipped().isEmpty());
        }
    }

    /// Keeps a declared archive whose name ends with .part complete.
    @Test
    public void keepsDeclaredArchivesNamedLikePartials(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.part");
        write(archive, 10);
        ImageCacheService service = new ImageCacheService(
                new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root.part"), List.of("1.0.0")));

        ImageCacheUsage usage = service.usage();
        assertEquals(0, usage.partialFiles());
        assertEquals(10L, usage.downloadBytes());

        service.cleanPartials();
        assertTrue(Files.exists(archive));
    }

    /// Leaves unconfirmed legacy staging for explicit removal.
    @Test
    public void preservesLegacyLookingDirectoriesDuringPartialCleanup(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path leftover = cache.resolve("artifacts/ruyisdk/board-image/meles/1.0.0.tmp-123/file");
        write(leftover, 5);
        ImageCacheService service = new ImageCacheService(
                new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root.zst"), List.of("1.0.0")));

        ImageCacheUsage usage = service.usage();
        assertEquals(0, usage.partialFiles());
        assertEquals(5L, usage.artifactBytes());

        assertEquals(0, service.cleanPartials().removedFiles());
        assertEquals(0, service.cleanAll().removedFiles());
        assertTrue(Files.exists(leftover));
        assertFalse(Files.exists(cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0.tmp-123.lock")));
        Files.createDirectories(cache.resolve("downloads/ruyisdk/board-image/meles"));
        assertEquals(1, service.cleanEntries(usage.entries()).removedFiles());
        assertFalse(Files.exists(leftover.getParent()));
    }

    /// Keeps staged markers from creating lock files outside the cache.
    @Test
    public void rejectsMarkerWithLinkedLockAncestor(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path name = cache.resolve("downloads/ruyisdk/board-image/meles");
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.createDirectories(name.getParent());
        try {
            Files.createSymbolicLink(name, outside);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }
        Path partial = name.resolve("1.0.0/root.zst.part");
        ImageCacheMarkers.write(cache, partial);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        assertFalse(service.cleanPartials().skipped().isEmpty());
        assertFalse(Files.exists(outside.resolve("1.0.0.lock")));
        assertTrue(ImageCacheMarkers.exists(cache, partial));
    }

    /// Cleans same-filesystem import staging even without metadata or marker files.
    @Test
    public void cleansPrivateImportStagingWithoutCatalog(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path version = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0");
        Path staged = version.resolve(".import-staging-test/source.tmp");
        write(staged, 10);
        Path empty = Files.createDirectory(version.resolve(".import-staging-empty"));
        Path archive = version.resolve(".import-staging-archive");
        write(archive, 20);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheUsage usage = service.usage();
        assertEquals(2, usage.partialFiles());
        assertEquals(10, usage.partialBytes());
        assertEquals(20, usage.downloadBytes());
        assertEquals(1, service.cleanPartials().removedFiles());
        assertFalse(Files.exists(staged.getParent()));
        assertFalse(Files.exists(empty));
        assertTrue(Files.exists(archive));
    }

    /// Invalid public entries are reported without aborting cleanup of other selections.
    @Test
    public void reportsInvalidPublicCacheEntry(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst");
        write(archive, 10);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        ImageCacheEntry invalid = new ImageCacheEntry("repo", "board-image", "meles", "invalid" + (char) 0,
                "invalid", 0, 0, 0, 0, 0);
        ImageCacheCleanResult result = service.cleanEntries(List.of(service.usage().entries().getFirst(), invalid));
        assertEquals(1, result.removedFiles());
        assertEquals(1, result.skipped().size());
        assertFalse(Files.exists(archive));
    }

    /// Oversized markers are reported while cleanup continues for the cache.
    @Test
    public void reportsOversizedMarkerWithoutReadingAllOfIt(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path relative = Path.of("downloads/ruyisdk/board-image/meles/1.0.0/root.zst.part");
        Path markers = Files.createDirectories(cache.resolve("partials"));
        Path marker = ImageCacheMarkers.markerPath(markers, relative);
        Files.writeString(marker, "x".repeat(200 * 1024));
        Path invalidName = Files.writeString(markers.resolve("not-a-marker.marker"), "x".repeat(200 * 1024));
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        ImageCacheCleanResult result = service.cleanPartials();
        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().getFirst().contains("too large"));
        assertTrue(Files.exists(marker));
        assertTrue(Files.exists(invalidName));
    }

    /// A stable writer lease protects artifacts even after the download name directory disappears.
    @Test
    public void stableLeaseBlocksCleanupWithMissingLegacyParent(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path artifact = cache.resolve("artifacts/ruyisdk/board-image/meles/1.0.0/root.raw");
        write(artifact, 10);
        Path legacy = Path.of("downloads/ruyisdk/board-image/meles/1.0.0.lock");
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        try (@Nullable CacheLock lease = CacheLock.acquire(cache.resolve(CacheLock.versionLeaseName(cache.toRealPath(), legacy)))) {
            assertNotNull(lease);
            assertNull(CacheLock.acquireVersion(cache, cache.resolve(legacy)));
            ImageCacheCleanResult result = service.cleanAll();
            assertEquals(0, result.removedFiles());
            assertTrue(result.skipped().stream().anyMatch(reason -> reason.startsWith(ImageCacheService.SKIPPED_IN_USE)));
            assertTrue(Files.exists(artifact));
            assertFalse(Files.exists(cache.resolve("downloads")));
        }

        Files.createDirectories(cache.resolve(legacy).getParent());
        assertEquals(1, service.cleanAll().removedFiles());
    }

    /// P2:118 只有产物、没有下载树的版本也能被全量清理删除。
    @Test
    public void removesArtifactOnlyVersionWithoutDownloadsTree(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("artifacts/ruyisdk/board-image/meles/1.0.0/root.raw");
        write(archive, 10);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        assertEquals(1, service.usageForCleanup(true).entries().size());
        ImageCacheCleanResult result = service.cleanAll();

        assertEquals(1, result.removedFiles());
        assertEquals(10L, result.removedBytes());
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertFalse(Files.exists(archive));
        assertTrue(Files.isDirectory(cache.resolve("downloads/ruyisdk/board-image/meles")));
    }

    /// P2:118 兼容锁父目录缺失时，过期标记仍可被清理。
    @Test
    public void removesStaleMarkerByCreatingCompatibilityLock(@TempDir Path directory) throws IOException {
        Path cache = Files.createDirectory(directory.resolve("cache"));
        Path partial = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst.part");
        ImageCacheMarkers.write(cache, partial);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheCleanResult result = service.cleanPartials();

        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertFalse(ImageCacheMarkers.exists(cache, partial));
        assertTrue(Files.isRegularFile(cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0.lock")));
    }

    /// P1:106 清理必须读取磁盘上的当前声明，而不是进程内缓存的旧快照。
    @Test
    public void cleanupUsesCurrentDiskDeclarations(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.part");
        write(archive, 10);
        ImageCatalog oldCatalog = catalog("board-image", "meles", List.of("root"), List.of("1.0.0")).listImages();
        ImageCatalog newCatalog = catalog(
                "board-image", "meles", List.of("root", "root.part"), List.of("1.0.0")).listImages();
        AtomicInteger loads = new AtomicInteger();
        ImageCatalogService caching = new ImageCatalogService() {
            /// Snapshot held by this service until it is invalidated.
            private @Nullable ImageCatalog cached;

            /// Loads one snapshot and caches it like the production catalog service.
            @Override
            public ImageCatalog listImages() {
                if (cached == null) {
                    cached = loads.getAndIncrement() == 0 ? oldCatalog : newCatalog;
                }
                return cached;
            }

            /// Discards the cached snapshot.
            @Override
            public void invalidateCache() {
                cached = null;
            }

            /// Reports that this catalog only supports measurement.
            @Override
            public Path downloadImage(ImageEntry image, ProgressReporter reporter) {
                throw new UnsupportedOperationException();
            }
        };
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache), caching);

        assertEquals(1, service.usage().partialFiles());
        ImageCacheCleanResult result = service.cleanPartials();

        assertEquals(2, loads.get());
        assertEquals(0, result.removedFiles());
        assertTrue(Files.exists(archive));
    }

    /// P2:1113 维护索引保留每个仓库自己的归档声明。
    @Test
    public void usesDeclarationsFromEveryRepository(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/repo-b/board-image/meles/1.0.0/root.zst");
        write(archive, 10);
        RuyiDistfile declared = new RuyiDistfile(
                "root.zst", List.of(), null, Map.of(), false, false, null, 0, List.of(), null);
        ImageEntry lowerPriority = new ImageEntry(
                "repo-b", "board-image", "meles", "1.0.0", null, "board-image/meles(1.0.0)",
                "meles", "manufacturer", "board", "variant", "dd-v1",
                Map.of(), List.of(declared), StrategySupport.SUPPORTED);
        ImageCatalogService deduplicated = new ImageCatalogService() {
            /// Returns the cross-repository de-duplicated catalog, which omits `repo-b`.
            @Override
            public ImageCatalog listImages() {
                return new ImageCatalog(List.of());
            }

            /// Returns every repository declaration.
            @Override
            public List<ImageEntry> declaredImages() {
                return List.of(lowerPriority);
            }

            /// Reports that this catalog only supports measurement.
            @Override
            public Path downloadImage(ImageEntry image, ProgressReporter reporter) {
                throw new UnsupportedOperationException();
            }
        };
        ImageCacheService service =
                new ImageCacheService(new AppDirectories(directory.resolve("config"), cache), deduplicated);

        ImageCacheEntry entry = service.usage().entries().getFirst();

        assertEquals("repo-b", entry.repoId());
        assertEquals(ImageCacheEntry.Completeness.COMPLETE, entry.completeness());
    }

    /// P3:159 全量清理预览保留被排除条目的 ATOM 和原因。
    @Test
    public void reportsRetainedLegacyEntriesInBulkPreview(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        write(cache.resolve("artifacts/ruyisdk/board-image/meles/1.0.0.old-1/root.ext4"), 5);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheUsage bulk = service.usageForCleanup(true);

        assertTrue(bulk.entries().isEmpty());
        assertEquals(0L, bulk.totalBytes());
        assertEquals(1, bulk.retained().size());
        assertEquals("board-image/meles(1.0.0.old-1)", bulk.retained().getFirst().entry().atom());
        assertEquals(ImageCacheRetainedEntry.Reason.AMBIGUOUS_LEGACY_DIRECTORY,
                bulk.retained().getFirst().reason());

        ImageCacheUsage full = service.usageForCleanup(false);
        assertEquals(1, full.entries().size());
        assertTrue(full.retained().isEmpty());
    }

    /// P2:494 私有续传硬链接与 `.part` 的占用和释放字节只计一次，删除名称仍各计一次。
    @Test
    public void countsHardLinkedResumeFileBytesOnce(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path part = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst.part");
        write(part, 4096);
        ImageCacheMarkers.write(cache, part);
        Path staging = Files.createDirectory(part.resolveSibling(".download-partials-0"));
        try {
            Files.createLink(staging.resolve("resume"), part);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "hard links are unavailable");
        }
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheUsage usage = service.usage();
        assertEquals(4096L, usage.partialBytes());

        ImageCacheCleanResult result = service.cleanPartials();
        assertEquals(4096L, result.removedBytes());
        assertEquals(2, result.removedFiles());
    }

    /// P2:198 显式清理只处理所选版本的标记，其他版本的标记保持原样。
    @Test
    public void cleanEntriesKeepsMarkersOfOtherVersions(@TempDir Path directory) throws IOException {
        Path cache = Files.createDirectory(directory.resolve("cache"));
        Path selected = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst");
        write(selected, 10);
        Path otherPartial = cache.resolve("downloads/ruyisdk/board-image/meles/2.0.0/root.zst.part");
        ImageCacheMarkers.write(cache, otherPartial);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        ImageCacheEntry entry = entry(service, "board-image/meles(1.0.0)");

        ImageCacheCleanResult result = service.cleanEntries(List.of(entry));

        assertEquals(1, result.removedFiles());
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertFalse(Files.exists(selected));
        assertTrue(ImageCacheMarkers.exists(cache, otherPartial));
    }

    /// Leaves a mismatched marker alone instead of using a different version's lock to remove it.
    @Test
    public void ignoresMarkerWhoseContentsDoNotMatchItsName(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path relative = Path.of("downloads/ruyisdk/board-image/meles/1.0.0/root.zst.part");
        Path markers = Files.createDirectories(cache.resolve("partials"));
        Path marker = ImageCacheMarkers.markerPath(markers, relative);
        Files.writeString(marker, "downloads/ruyisdk/board-image/meles/2.0.0/root.zst.part");
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        service.cleanPartials();
        assertTrue(Files.exists(marker));
        assertFalse(Files.exists(cache.resolve("downloads/ruyisdk/board-image/meles/2.0.0.lock")));
    }

    /// Leaves ambiguous legacy staging alone while the possible owner is using it.
    @Test
    public void protectsUnknownLegacyStagingWithBaseVersionLock(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path staged = cache.resolve("artifacts/ruyisdk/board-image/meles/1.0.0.tmp-123/root.ext4");
        write(staged, 10);
        Path lock = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0.lock");
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        try (@Nullable CacheLock held = CacheLock.acquire(lock)) {
            assertNotNull(held);
            ImageCacheCleanResult result = service.cleanEntries(service.usage().entries());
            assertTrue(Files.exists(staged));
            assertTrue(result.skipped().stream().anyMatch(reason -> reason.startsWith(ImageCacheService.SKIPPED_IN_USE)));
        }
        assertEquals(1, service.cleanEntries(service.usage().entries()).removedFiles());
        assertFalse(Files.exists(staged));
    }

    /// Reports unreadable retained data in usage without failing an otherwise successful cleanup.
    @Test
    public void retainedDirectoryWarningsDoNotFailCleanup(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst");
        write(archive, 10);
        Path retained = Files.createDirectories(cache.resolve("logs"));
        Assumptions.assumeTrue(Files.getFileAttributeView(retained, PosixFileAttributeView.class) != null,
                "POSIX permissions are unavailable");
        @Unmodifiable Set<PosixFilePermission> original = Set.copyOf(Files.getPosixFilePermissions(retained));
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        try {
            Files.setPosixFilePermissions(retained, Set.of());
            Assumptions.assumeTrue(!service.usage().unreadable().isEmpty(), "the test user can still read the directory");
            ImageCacheCleanResult result = service.cleanAll();
            assertEquals(1, result.removedFiles());
            assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        } finally {
            Files.setPosixFilePermissions(retained, original);
        }
    }

    /// Re-reads catalog metadata once when a reused service starts another cleanup.
    @Test
    public void cleanupUsesCurrentCatalogSnapshot(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.part");
        write(archive, 10);
        ImageCatalog oldCatalog = catalog("board-image", "meles", List.of("root"), List.of("1.0.0")).listImages();
        ImageCatalog newCatalog = catalog(
                "board-image", "meles", List.of("root", "root.part"), List.of("1.0.0")).listImages();
        AtomicInteger reads = new AtomicInteger();
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache),
                catalog(() -> reads.getAndIncrement() == 0 ? oldCatalog : newCatalog));

        assertEquals(1, service.usage().partialFiles());
        assertEquals(0, service.cleanPartials().removedFiles());
        assertEquals(2, reads.get());
        assertTrue(Files.exists(archive));
    }

    /// Keeps a formerly declared version when its own download lock is present.
    @Test
    public void keepsRetiredVersionsNamedLikeLeftovers(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path artifact = cache.resolve("artifacts/ruyisdk/board-image/meles/1.0.0.old-1/root.ext4");
        write(artifact, 5);
        Path lock = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0.old-1.lock");
        write(lock, 0);
        ImageCacheService service = new ImageCacheService(
                new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root.zst"), List.of("1.0.0")));

        assertEquals(0, service.usage().partialFiles());
        service.cleanPartials();
        assertTrue(Files.exists(artifact));
    }

    /// Keeps a declared version whose name contains a staging marker.
    @Test
    public void keepsDeclaredVersionsNamedLikeLeftovers(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path artifact = cache.resolve("artifacts/ruyisdk/board-image/meles/1.0.0.old-1/root.ext4");
        write(artifact, 5);
        ImageCacheService service = new ImageCacheService(
                new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root.zst"), List.of("1.0.0", "1.0.0.old-1")));

        ImageCacheUsage usage = service.usage();
        assertEquals(1, usage.entries().size());
        assertEquals("board-image/meles(1.0.0.old-1)", usage.entries().getFirst().atom());
        assertEquals(0, usage.partialFiles());
    }

    /// Recognizes and removes import staging files left by older releases.
    @Test
    public void recognizesLegacyImportStagingFiles(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path staging = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/.import-boot.tmp");
        write(staging, 10);
        ImageCacheService service = new ImageCacheService(
                new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root.zst"), List.of("1.0.0")));

        assertEquals(1, service.usage().partialFiles());
        service.cleanPartials();
        assertFalse(Files.exists(staging));
    }

    /// Keeps a declared archive whose name looks like import staging.
    @Test
    public void keepsDeclaredArchivesNamedLikeImportStaging(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/.import-boot.tmp");
        write(archive, 10);
        ImageCacheService service = new ImageCacheService(
                new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of(".import-boot.tmp"), List.of("1.0.0")));

        assertEquals(0, service.usage().partialFiles());
        service.cleanPartials();
        assertTrue(Files.exists(archive));
    }

    /// Does not remove import-like files when the catalog cannot confirm their identity.
    @Test
    public void keepsUnconfirmedImportLikeFilesWithoutCatalog(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path file = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/.import-boot.tmp");
        write(file, 10);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        assertEquals(0, service.usage().partialFiles());
        service.cleanPartials();
        assertTrue(Files.exists(file));
    }

    /// Removes markers belonging to versions removed by a cleanup.
    @Test
    public void removesMarkersWhenVersionsAreCleaned(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path part = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst.part");
        write(part, 10);
        ImageCacheMarkers.write(cache, part);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        service.cleanAll();

        assertFalse(ImageCacheMarkers.exists(cache, part));
    }

    /// Keeps the marker when the partial file could not be removed.
    @Test
    public void keepsMarkersWhenPartialDeletionFails(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path part = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst.part");
        write(part, 10);
        ImageCacheMarkers.write(cache, part);
        try {
            Files.setAttribute(part, "dos:readonly", true);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "read-only attributes are unavailable");
        }
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        service.cleanPartials();

        if (!Files.exists(part)) {
            Assumptions.assumeTrue(false, "read-only files can be deleted on this platform");
        }
        assertTrue(ImageCacheMarkers.exists(cache, part));

        Files.setAttribute(part, "dos:readonly", false);
        service.cleanPartials();
        assertFalse(ImageCacheMarkers.exists(cache, part));
    }

    /// Keeps the marker of a download whose version is still locked by another operation.
    @Test
    public void keepsMarkersOfDownloadsInUse(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path part = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst.part");
        write(part, 10);
        ImageCacheMarkers.write(cache, part);
        Path lockPath = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0.lock");
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        try (FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
             @Nullable FileLock lock = channel.lock()) {
            ImageCacheCleanResult result = service.cleanPartials();

            assertFalse(result.skipped().isEmpty());
            assertTrue(Files.exists(part));
            assertTrue(ImageCacheMarkers.exists(cache, part));
        }
    }

    /// Reports and removes local-import staging files left behind by an interrupted import.
    @Test
    public void reportsAndRemovesImportStaging(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path staging = cache.resolve("imports/ruyisdk/board-image/meles/1.0.0/import-abc.tmp");
        write(staging, 40);
        Files.createDirectories(cache.resolve("downloads/ruyisdk/board-image/meles"));
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheUsage usage = service.usage();
        assertEquals(40L, usage.partialBytes());
        assertEquals(1, usage.partialFiles());
        assertEquals(1, usage.entries().size());
        assertEquals("board-image/meles(1.0.0)", usage.entries().getFirst().atom());

        ImageCacheCleanResult result = service.cleanPartials();
        assertEquals(40L, result.removedBytes());
        assertFalse(Files.exists(staging.getParent()));
    }

    /// Removes local-import staging files when the whole cache is cleaned.
    @Test
    public void removesImportStagingWithAllCleanup(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path staging = cache.resolve("imports/ruyisdk/board-image/meles/1.0.0/import-abc.tmp");
        write(staging, 40);
        Files.createDirectories(cache.resolve("downloads/ruyisdk/board-image/meles"));
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        service.cleanAll();

        assertFalse(Files.exists(staging.getParent()));
        assertEquals(0L, service.usage().totalBytes());
    }

    /// Keeps local-import staging files while their version lock is held by an import.
    @Test
    public void keepsImportStagingWhileVersionIsLocked(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path staging = cache.resolve("imports/ruyisdk/board-image/meles/1.0.0/import-abc.tmp");
        write(staging, 40);
        Path lockPath = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0.lock");
        Files.createDirectories(lockPath.getParent());
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        try (FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
             @Nullable FileLock lock = channel.lock()) {
            ImageCacheCleanResult result = service.cleanPartials();

            assertFalse(result.skipped().isEmpty());
            assertTrue(Files.exists(staging));
        }

        service.cleanPartials();
        assertFalse(Files.exists(staging));
    }

    /// Never counts or removes import staging reached through a symbolic link outside the cache.
    @Test
    public void ignoresImportStagingBehindSymbolicLink(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path outside = directory.resolve("outside");
        Path outsideFile = outside.resolve("board-image/meles/1.0.0/import-abc.tmp");
        write(outsideFile, 40);
        Path imports = cache.resolve("imports");
        Files.createDirectories(imports);
        try {
            Files.createSymbolicLink(imports.resolve("ruyisdk"), outside);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        assertEquals(0L, service.usage().partialBytes());
        service.cleanPartials();
        assertTrue(Files.exists(outsideFile));
    }

    /// Keeps a declared version whose name looks like a leftover when another version is cleaned.
    @Test
    public void keepsOtherDeclaredVersionsNamedLikeLeftovers(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path other = cache.resolve("artifacts/ruyisdk/board-image/meles/1.0.0.old-1/root.ext4");
        Path first = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst");
        write(other, 5);
        write(first, 10);
        ImageCacheService service = new ImageCacheService(
                new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root.zst"), List.of("1.0.0", "1.0.0.old-1")));
        ImageCacheEntry entry = service.usage().entries().stream()
                .filter(candidate -> candidate.atom().equals("board-image/meles(1.0.0)"))
                .findFirst()
                .orElseThrow();

        service.cleanEntries(List.of(entry));

        assertFalse(Files.exists(first));
        assertTrue(Files.exists(other));
    }

    /// Keeps a declared archive whose name is also the suffix of another declared archive.
    @Test
    public void keepsDeclaredArchivesThatLookLikePartials(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.part");
        write(archive, 10);
        ImageCacheService service = new ImageCacheService(
                new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root", "root.part"), List.of("1.0.0")));

        assertEquals(0, service.usage().partialFiles());
        service.cleanPartials();
        assertTrue(Files.exists(archive));
    }

    /// Never writes or reads markers through a linked marker directory.
    @Test
    public void ignoresMarkerDirectoryBehindSymbolicLink(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path partial = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst.part");
        Files.createDirectories(partial.getParent());
        Files.write(partial, new byte[10]);
        Path outside = directory.resolve("outside");
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(cache.resolve("partials"), outside);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }

        ImageCacheMarkers.write(cache, partial);
        new ImageCacheService(new AppDirectories(directory.resolve("config"), cache)).cleanPartials();

        assertFalse(ImageCacheMarkers.exists(cache, partial));
        try (var children = Files.list(outside)) {
            assertTrue(children.findAny().isEmpty());
        }
    }

    /// Removes a linked marker as a link and never follows it.
    @Test
    public void neverFollowsSymbolicMarkerFiles(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path partial = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst.part");
        Files.createDirectories(partial.getParent());
        Files.write(partial, new byte[10]);
        Path markerDirectory = cache.resolve("partials");
        Files.createDirectories(markerDirectory);
        Path target = directory.resolve("marker-target.txt");
        Files.writeString(target, "original");
        Path marker = ImageCacheMarkers.markerPath(markerDirectory, cache.relativize(partial));
        try {
            Files.createSymbolicLink(marker, target);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }

        assertFalse(ImageCacheMarkers.exists(cache, partial));
        ImageCacheMarkers.delete(cache, partial);

        assertFalse(Files.exists(marker, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(target));
        assertEquals("original", Files.readString(target));
    }

    /// Lists and cleans versions below a linked cache root.
    @Test
    public void listsCachesBelowLinkedCacheRoot(@TempDir Path directory) throws IOException {
        Path real = directory.resolve("real-cache");
        Path file = real.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst");
        write(file, 10);
        Path cache = directory.resolve("cache");
        try {
            Files.createSymbolicLink(cache, real);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheUsage usage = service.usage();
        assertEquals(1, usage.entries().size());
        assertEquals(10L, usage.downloadBytes());

        service.cleanEntries(usage.entries());
        assertFalse(Files.exists(file));
    }

    /// Keeps a stale marker while the owning version is locked, then removes it later.
    @Test
    public void keepsStaleMarkersWhileVersionIsLocked(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path partial = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst.part");
        write(partial, 10);
        ImageCacheMarkers.write(cache, partial);
        Files.delete(partial);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        Path lockPath = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0.lock");

        try (@Nullable CacheLock lock = CacheLock.acquire(lockPath)) {
            assertNotNull(lock);
            service.cleanPartials();
            assertTrue(ImageCacheMarkers.exists(cache, partial));
        }

        service.cleanPartials();
        assertFalse(ImageCacheMarkers.exists(cache, partial));
    }

    /// Creates a catalog with one image available in the requested versions.
    ///
    /// @param category image category.
    /// @param name image name.
    /// @param distfiles declared distfile names.
    /// @param versions declared versions.
    /// @return catalog service.
    private static ImageCatalogService catalog(
            String category,
            String name,
            @Unmodifiable List<String> distfiles,
            @Unmodifiable List<String> versions) {
        ArrayList<RuyiDistfile> files = new ArrayList<>();
        for (String distfile : distfiles) {
            files.add(new RuyiDistfile(distfile, List.of(), null, Map.of(), false, false, null, 0, List.of(), null));
        }
        ArrayList<ImageEntry> entries = new ArrayList<>();
        for (String version : versions) {
            entries.add(new ImageEntry(
                    "ruyisdk", category, name, version, null,
                    category + "/" + name + "(" + version + ")",
                    name, "manufacturer", "board", "variant", "dd-v1",
                    Map.of(), files, StrategySupport.SUPPORTED));
        }
        ImageCatalog snapshot = new ImageCatalog(List.copyOf(entries));
        return catalog(() -> snapshot);
    }

    /// Creates a test catalog service with a supplied snapshot.
    private static ImageCatalogService catalog(Supplier<ImageCatalog> snapshot) {
        return new ImageCatalogService() {
            /// Returns the current test snapshot.
            @Override
            public ImageCatalog listImages() {
                return snapshot.get();
            }

            /// Reports that this catalog only supports measurement.
            ///
            /// @param image image to download.
            /// @param reporter progress reporter.
            /// @return never returns.
            @Override
            public Path downloadImage(ImageEntry image, ProgressReporter reporter) {
                throw new UnsupportedOperationException();
            }
        };
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
