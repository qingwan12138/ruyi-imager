// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.AppServices;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.SdkMessages;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.flash.FlashRequest;
import org.glavo.ruyi.imager.core.flash.FlashTarget;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests measuring and cleaning the image download cache.
@NotNullByDefault
public final class ImageCacheServiceTest {
    /// Rejects a replacement root during catalog lookup for every public cleanup entry point.
    @Test
    public void cleanupRejectsCacheReplacementBeforeScan(@TempDir Path directory) throws IOException {
        for (String mode : List.of("all", "partials", "entries")) {
            Path base = Files.createDirectory(directory.resolve(mode));
            Path cache = Files.createDirectory(base.resolve("cache"));
            Path replacement = Files.createDirectory(base.resolve("replacement"));
            Path version = Path.of("downloads", "ruyisdk", "board-image", "meles", "1.0");
            Path activePartial = version.resolve("active.part");
            Path stalePartial = version.resolve("stale.part");
            write(cache.resolve(activePartial), 1);
            write(replacement.resolve(activePartial), 2);
            ImageCacheMarkers.write(replacement, replacement.resolve(activePartial));
            write(replacement.resolve(stalePartial), 3);
            ImageCacheMarkers.write(replacement, replacement.resolve(stalePartial));
            Files.delete(replacement.resolve(stalePartial));
            AppDirectories directories = new AppDirectories(base.resolve("config"), cache);
            @Unmodifiable List<ImageCacheEntry> selected = new ImageCacheService(directories).usage().entries();
            assertEquals(1, selected.size());
            ImageCacheService service = new ImageCacheService(directories,
                    catalog(() -> {
                        try {
                            Files.move(cache, base.resolve("previous"));
                            Files.move(replacement, cache);
                        } catch (IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                        return new ImageCatalog(List.of());
                    }));

            ImageCacheCleanResult result = switch (mode) {
                case "all" -> service.cleanAll();
                case "partials" -> service.cleanPartials();
                default -> service.cleanEntries(selected);
            };

            assertEquals(0, result.removedFiles());
            assertEquals(2, Files.size(cache.resolve(activePartial)));
            assertTrue(ImageCacheMarkers.exists(cache, cache.resolve(stalePartial)));
            assertTrue(result.skipped().stream().anyMatch(reason -> reason.contains("Cache root changed")));
        }
    }

    /// Keeps both possible owners of a retired version protected during bulk cleanup.
    @Test
    public void bulkCleanupProtectsEitherLegacyVersionLock(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.tmp-1/root");
        write(archive, 7);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        for (String version : List.of("1.0", "1.0.tmp-1")) {
            Path lockPath = archive.getParent().resolveSibling(version + ".lock");
            try (@Nullable CacheLock lock = CacheLock.acquireVersion(cache, lockPath)) {
                assertNotNull(lock);
                assertEquals(0, service.cleanAll().removedFiles());
                assertTrue(Files.exists(archive));
            }
        }
        assertEquals(1, service.cleanAll().removedFiles());
    }

    /// Removing the staging link leaves final archive data allocated.
    @Test
    public void doesNotCountHardLinkedImportAsReleased(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0/root");
        write(archive, 7);
        Path staging = Files.createDirectory(archive.resolveSibling(".import-staging-0"));
        try {
            Files.createLink(staging.resolve("source"), archive);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.assumeTrue(false, "Hard links unavailable: " + exception);
        }
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        assertEquals(0, service.usage().partialBytes());
        assertEquals(0, service.usageForPartialsCleanup().partialBytes());
        assertEquals(7, service.usage().downloadBytes());
        ImageCacheCleanResult result = service.cleanPartials();
        assertEquals(1, result.removedFiles());
        assertEquals(0, result.removedBytes());
        assertTrue(Files.exists(archive));
    }

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
        Files.setLastModifiedTime(leftover.getParent(), java.nio.file.attribute.FileTime.fromMillis(10000));
        Files.setLastModifiedTime(leftover, java.nio.file.attribute.FileTime.fromMillis(20000));
        Files.createDirectories(cache.resolve("downloads/ruyisdk/board-image/meles"));
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheUsage usage = service.usage();

        assertEquals(1, usage.entries().size());
        ImageCacheEntry entry = usage.entries().getFirst();
        assertEquals("board-image/meles(1.0.0)", entry.atom());
        assertEquals(5L, entry.partialBytes());
        assertEquals(Files.getLastModifiedTime(leftover).toMillis(), entry.lastModifiedMillis());
        assertEquals(1, entry.partialFiles());
        assertTrue(entry.partial());
        service.cleanPartials();
        assertFalse(Files.exists(leftover.getParent()));
    }

    /// Treats local-import staging files as incomplete downloads.
    @Test
    public void treatsImportStagingAsPartial(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path staging = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/.import-staging-abc/source.tmp");
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
        Files.createDirectory(version.resolve(".download-partials-0"));
        assertEquals(ImageCacheEntry.Completeness.COMPLETE, service.usage().entries().getFirst().completeness());
        assertEquals(ImageCacheEntry.Completeness.UNKNOWN,
                service.usageForPartialsCleanup().entries().getFirst().completeness());
    }

    /// A usable archive link supplies a required distfile while usage measures the link itself.
    @Test
    public void measuresLinkedArchiveCompleteness(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst");
        Path target = directory.resolve("original");
        write(target, 1024);
        Files.createDirectories(archive.getParent());
        try {
            Files.createSymbolicLink(archive, target);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root.zst"), List.of("1.0.0")));
        ImageCacheEntry entry = service.usage().entries().getFirst();
        assertEquals(ImageCacheEntry.Completeness.COMPLETE, entry.completeness());
        assertEquals(Files.readAttributes(archive, java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).size(), entry.downloadBytes());
    }

    /// Each direct resume name is a download even when slots are shared or a private resume link remains.
    @Test
    public void countsDownloadsInSharedSlot(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path slot = cache.resolve("downloads/repo/board-image/board/1/.download-partials-0");
        Path first = slot.resolve("first.raw.part");
        write(first, 2);
        write(slot.resolve("second.raw.part"), 3);
        hardLink(slot.resolve(".download-partials-123/resume"), first);
        write(slot.resolve(".download-partials-456/resume"), 17);
        var service = new ImageCacheService(new AppDirectories(directory, cache));
        for (var usage : List.of(service.usage(), service.usageForPartialsCleanup())) {
            assertEquals(22, usage.partialBytes());
            assertEquals(3, usage.partialFiles());
            assertEquals(3, usage.entries().getFirst().partialFiles());
        }
    }

    /// Rejects a marked local source before the requested target can be written.
    @Test
    public void refusesToFlashMarkedDownload(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path file = cache.resolve("downloads/ruyisdk/board-image/meles/1/root.raw.part");
        write(file, 1);
        ImageCacheMarkers.write(cache, file);
        Path target = Files.writeString(directory.resolve("target.raw"), "original");
        var device = new BlockDevice("file", "file", target, 8, true, false, false, false, null, "file");
        var services = AppServices.createDefault(new AppDirectories(directory, cache));
        var request = new FlashRequest(null, file, FlashTarget.blockDevice(device), true);
        IOException failure = assertThrows(IOException.class, () -> services.flash().flash(request, _ -> {}));
        assertEquals(SdkMessages.get("core.flash.localImageStaging", file.toRealPath()), failure.getMessage());
        assertEquals("original", Files.readString(target));
    }

    /// A marked historical sibling may be flashed only if it verifies against its declaration.
    @Test
    public void classifiesLegacyFlashSourceWithCurrentDeclarations(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path file = cache.resolve("downloads/ruyisdk/board-image/meles/1/root.raw.part");
        write(file, 1);
        ImageCacheMarkers.write(cache, file);
        var empty = catalog(() -> new ImageCatalog(List.of()));
        assertTrue(ImageCacheService.isMarkedDownloadPartial(cache, cache.toRealPath(), file.toRealPath(), empty));
        var noChecksum = catalog("board-image", "meles", List.of("root.raw.part"), List.of("1"));
        assertTrue(ImageCacheService.isMarkedDownloadPartial(cache, cache.toRealPath(), file.toRealPath(), noChecksum));
        String digest = "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d";
        var wrongLength = flashArchiveCatalog(2, digest);
        assertTrue(ImageCacheService.isMarkedDownloadPartial(cache, cache.toRealPath(), file.toRealPath(), wrongLength));
        var wrongChecksum = flashArchiveCatalog(1, "0".repeat(64));
        assertTrue(ImageCacheService.isMarkedDownloadPartial(cache, cache.toRealPath(), file.toRealPath(), wrongChecksum));
        var declared = flashArchiveCatalog(1, digest);
        assertFalse(ImageCacheService.isMarkedDownloadPartial(cache, cache.toRealPath(), file.toRealPath(), declared));
        Path sibling = Files.writeString(file.resolveSibling("other.raw.part"), "complete");
        var unused = catalog(() -> { throw new AssertionError("Unmarked files do not need the catalog"); });
        assertFalse(ImageCacheService.isMarkedDownloadPartial(cache, cache.toRealPath(), sibling.toRealPath(), unused));
        Path marker = ImageCacheMarkers.legacyMarkerPath(cache.resolve("partials"), file.toRealPath());
        Files.writeString(marker, "x".repeat(200 * 1024));
        assertFalse(ImageCacheService.isMarkedDownloadPartial(cache, cache.toRealPath(), file.toRealPath(), declared));
        assertThrows(IOException.class,
                () -> ImageCacheService.isMarkedDownloadPartial(cache, cache.toRealPath(), file.toRealPath(), empty));
    }

    /// Declares the historical partial name with a size and checksum.
    private static ImageCatalogService flashArchiveCatalog(long size, String checksum) {
        RuyiDistfile archive = new RuyiDistfile("root.raw.part", List.of(), size, Map.of("sha256", checksum),
                false, false, null, 0, List.of(), null);
        ImageEntry entry = new ImageEntry("ruyisdk", "board-image", "meles", "1", null,
                "board-image/meles(1)", "meles", "manufacturer", "board", "variant", "dd-v1",
                Map.of(), List.of(archive), StrategySupport.SUPPORTED);
        return catalog(() -> new ImageCatalog(List.of(entry)));
    }

    /// POSIX versions with an epoch remain visible and removable.
    @Test
    public void cleansPosixEpochVersion(@TempDir Path directory) throws IOException {
        Assumptions.assumeTrue(directory.getFileSystem().getSeparator().equals("/"));
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1:2.0/root");
        write(archive, 7);
        var service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        assertEquals("1:2.0", service.usage().entries().getFirst().version());
        assertEquals(7, service.cleanAll().removedBytes());
        assertFalse(Files.exists(archive));
    }

    /// Locked entries with the same atom retain their separate repository identities.
    @Test
    public void reportsBothLockedRepositories(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path first = cache.resolve("downloads/first/board-image/meles/1");
        Path second = cache.resolve("downloads/second/board-image/meles/1");
        write(first.resolve("image.raw"), 1);
        write(second.resolve("image.raw"), 1);
        var service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        try (var left = CacheLock.acquireVersion(cache, first.resolveSibling("1.lock"));
             var right = CacheLock.acquireVersion(cache, second.resolveSibling("1.lock"))) {
            assertNotNull(left);
            assertNotNull(right);
            assertEquals(Set.of(ImageCacheService.SKIPPED_IN_USE + "first:board-image/meles(1)",
                    ImageCacheService.SKIPPED_IN_USE + "second:board-image/meles(1)"),
                    Set.copyOf(service.cleanAll().skipped()));
        }
    }

    /// Bulk cleanup includes empty resume slots hidden from the ordinary cache listing.
    @Test
    public void emptyResumeSlotIsVisibleForBulkCleanup(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path slot = Files.createDirectories(cache.resolve(
                "downloads/ruyisdk/board-image/meles/1.0.0/.download-partials-0"));
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        assertTrue(service.usage().entries().isEmpty());
        assertEquals(0, service.usageForPartialsCleanup().partialFiles());
        assertEquals(1, service.usageForCleanup(true).entries().size());
        assertTrue(service.cleanAll().skipped().isEmpty());
        assertFalse(Files.exists(slot.getParent()));
        Files.createDirectories(slot);
        service.cleanPartials();
        assertFalse(Files.exists(slot));
    }

    /// Keeps an unmarked historical archive even when the current catalog declares its base name.
    @Test
    public void keepsUnmarkedHistoricalArchive(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path part = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst.part");
        write(part, 10);
        ImageCacheService service = new ImageCacheService(
                new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root.zst"), List.of("1.0.0")));

        ImageCacheUsage usage = service.usage();
        assertEquals(0, usage.partialFiles());
        assertEquals(10L, usage.downloadBytes());

        service.cleanPartials();
        assertTrue(Files.exists(part));
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

    /// A case-sensitive cache can contain two independent names for the same inode.
    @Test
    public void cleansMarkedHardlinkDistinctFromDeclaredArchive(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path partial = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.part");
        Path archive = partial.resolveSibling("ROOT.PART");
        write(archive, 20);
        Assumptions.assumeFalse(Files.exists(partial), "the filesystem is case-insensitive");
        hardLink(partial, archive);
        ImageCacheMarkers.write(cache, partial);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("ROOT.PART"), List.of("1.0.0")));
        assertEquals(1, service.usage().partialFiles());
        assertEquals(1, service.cleanPartials().removedFiles());
        assertFalse(Files.exists(partial));
        assertTrue(Files.exists(archive));
    }

    /// Matches different spellings of one version across cache trees on case-insensitive storage.
    @Test
    public void groupsFilesystemEquivalentVersions(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path download = cache.resolve("downloads/ruyisdk/board-image/meles/Release/root.zst");
        Path artifact = cache.resolve("artifacts/ruyisdk/board-image/meles/RELEASE/root.img");
        write(download, 10);
        write(artifact, 20);
        Assumptions.assumeTrue(Files.isDirectory(download.getParent().resolveSibling("release")),
                "the filesystem is case-sensitive");
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root.zst"), List.of("release")));
        ImageCacheUsage usage = service.usage();
        assertEquals(1, usage.entries().size());
        assertEquals(ImageCacheEntry.Completeness.COMPLETE, usage.entries().getFirst().completeness());
        assertEquals(30, usage.totalBytes());
        assertEquals(2, service.cleanEntries(usage.entries()).removedFiles());
    }

    /// Keeps two independent case variants in separate groups on case-sensitive storage.
    @Test
    public void keepsDistinctCaseSensitiveVersions(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path first = cache.resolve("downloads/ruyisdk/board-image/meles/Release/root.zst");
        Path second = cache.resolve("artifacts/ruyisdk/board-image/meles/RELEASE/root.img");
        write(first, 10);
        Assumptions.assumeFalse(Files.isDirectory(first.getParent().resolveSibling("release")),
                "the filesystem is case-insensitive");
        write(second, 20);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        ImageCacheUsage usage = service.usage();
        assertEquals(2, usage.entries().size());
        assertEquals(30, usage.totalBytes());
        ImageCacheEntry download = usage.entries().stream().filter(entry -> entry.downloadBytes() > 0).findFirst().orElseThrow();
        assertEquals(1, service.cleanEntries(List.of(download)).removedFiles());
        assertTrue(Files.exists(second));
    }

    /// Unreadable historical markers appear in the preview for their candidate partial.
    @Test
    public void reportsUnreadableHistoricalMarker(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path partial = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst.part");
        write(partial, 10);
        Path markers = Files.createDirectory(cache.resolve("partials"));
        Path physicalPartial = partial.toRealPath();
        Path marker = ImageCacheMarkers.legacyMarkerPath(markers, physicalPartial);
        Files.writeString(marker, "x".repeat(200 * 1024));
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        Path validPartial = partial.resolveSibling("valid.zst.part");
        write(validPartial, 5);
        ImageCacheMarkers.write(cache, validPartial);
        assertTrue(service.usageForPartialsCleanup().unreadable().contains(marker.toString()));
        var scope = service.selectForCleanup();
        assertTrue(scope.mayContainAtom(marker.toString(), "meles"));
        assertTrue(scope.mayContainAtom(marker.toString(), "board-image/meles(1.0.0)"));
        assertFalse(scope.mayContainAtom(marker.toString(), "other"));
        assertFalse(scope.mayContainAtom(marker.toString(), "board-image/other(1.0.0)"));
        ImageCacheCleanResult result = service.cleanPartials();
        assertTrue(result.skipped().stream().anyMatch(reason -> reason.contains(marker.toString())));
        assertTrue(Files.exists(partial));
        assertFalse(Files.exists(validPartial));
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

    /// Reports each malformed marker once when both scan and cleanup encounter it.
    @Test
    public void mergesRepeatedMarkerFailuresInBulkCleanup(@TempDir Path directory) throws IOException {
        for (boolean partialsOnly : List.of(true, false)) {
            Path cache = directory.resolve(Boolean.toString(partialsOnly));
            Path partial = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst.part");
            write(partial, 10);
            Path markerDirectory = Files.createDirectory(cache.resolve("partials"));
            Path marker = ImageCacheMarkers.legacyMarkerPath(markerDirectory, partial.toRealPath());
            Path otherMarker = ImageCacheMarkers.markerPath(
                    markerDirectory, cache.relativize(partial.resolveSibling("other.part")));
            Files.writeString(marker, "x".repeat(200 * 1024));
            Files.writeString(otherMarker, "x".repeat(200 * 1024));
            ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

            ImageCacheCleanResult result = partialsOnly ? service.cleanPartials() : service.cleanAll();

            assertEquals(2, result.skipped().size(), result.toString());
            assertTrue(result.skipped().stream()
                    .anyMatch(reason -> reason.startsWith(marker + ": ") && reason.contains("too large")));
            assertTrue(result.skipped().stream()
                    .anyMatch(reason -> reason.startsWith(otherMarker + ": ") && reason.contains("too large")));
        }
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

    /// Removes an artifact-only version with no downloads tree.
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

    /// Purges a stale marker when the compatibility lock parent is absent.
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

    /// Refreshes cached catalog declarations before cleanup.
    @Test
    public void cleanupUsesCurrentDiskDeclarations(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.part");
        write(archive, 10);
        ImageCacheMarkers.write(cache, archive);
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

    /// Keeps archive declarations distinct even when two display ATOMs have the same spelling.
    @Test
    public void cacheCompletenessUsesSeparatePackageAndVersionFields(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        write(cache.resolve("downloads/ruyisdk/cat/board(1/2/first"), 1);
        write(cache.resolve("downloads/ruyisdk/cat/board/1(2/second"), 1);
        ImageEntry first = catalog("cat", "board(1", List.of("first"), List.of("2")).listImages().images().getFirst();
        ImageEntry second = catalog("cat", "board", List.of("second"), List.of("1(2")).listImages().images().getFirst();
        var service = new ImageCacheService(new AppDirectories(directory, cache),
                catalog(() -> new ImageCatalog(List.of(first, second))));
        var entries = service.usage().entries();
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(entry -> entry.completeness() == ImageCacheEntry.Completeness.COMPLETE));
    }

    /// Isolated resume directories can be removed without reloading repository declarations per version.
    @Test
    public void isolatedPartialsUseOneCatalogScan(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        write(cache.resolve("downloads/ruyisdk/cat/board/1/.download-partials-0/first.part"), 1);
        write(cache.resolve("downloads/ruyisdk/cat/board/2/.download-partials-0/second.part"), 1);
        AtomicInteger loads = new AtomicInteger();
        var service = new ImageCacheService(new AppDirectories(directory, cache), catalog(() -> {
            loads.incrementAndGet();
            return new ImageCatalog(List.of());
        }));
        assertEquals(2, service.cleanPartials().removedFiles());
        assertEquals(1, loads.get());
    }

    /// Keeps each repository’s archive declarations.
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

    /// Reports the atom and retention reason for ambiguous legacy artifacts.
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

    /// Counts the data shared by both names once.
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

    /// Counts hard-linked partial data shared by versions when both names will be removed.
    @Test
    public void countsHardLinkedPartialsAcrossVersions(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path first = cache.resolve("imports/ruyisdk/board-image/meles/1.0/source");
        Path second = cache.resolve("imports/ruyisdk/board-image/meles/2.0/source");
        write(first, 11);
        hardLink(second, first);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheUsage usage = service.usageForPartialsCleanup();
        assertEquals(2, usage.partialFiles());
        assertEquals(11, usage.partialBytes());
        assertEquals(11, service.cleanPartials().removedBytes());
    }

    /// Counts only data whose last name will be removed by bulk cleanup.
    @Test
    public void countsHardLinkedFilesInBulkCleanup(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0/root");
        Path artifact = cache.resolve("artifacts/ruyisdk/board-image/meles/2.0/root");
        write(archive, 11);
        hardLink(artifact, archive);
        hardLink(artifact.resolveSibling("copy"), archive);
        Path retained = cache.resolve("artifacts/ruyisdk/board-image/meles/2.0.old-1/root");
        write(retained, 17);
        hardLink(artifact.resolveSibling("retained"), retained);
        Path external = directory.resolve("original");
        write(external, 23);
        hardLink(archive.resolveSibling("external"), external);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        assertEquals(11, service.usageForCleanup(true).totalBytes());
        assertEquals(11, service.cleanAll().removedBytes());
        assertTrue(Files.exists(retained));
        assertTrue(Files.exists(external));
    }

    /// A record from an older snapshot cannot select a changed entry for deletion.
    @Test
    public void staleSelectionMatchesPreview(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path image = cache.resolve("artifacts/repo/board-image/board/1/root");
        write(image, 16);
        var service = new ImageCacheService(new AppDirectories(directory, cache));
        var previous = service.selectForCleanup().usage().entries();
        write(image, 32);
        var scope = service.selectForCleanup();
        assertEquals(0, scope.releasedBytes(previous));
        assertEquals(0, scope.cleanEntries(previous).removedFiles());
        assertEquals(32, Files.size(image));
        assertEquals(32, scope.cleanEntries(scope.usage().entries()).removedBytes());
    }

    /// Estimates bytes using only the versions chosen from the cleanup snapshot.
    @Test
    public void estimatesReleaseForSelectedVersions(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path first = cache.resolve("artifacts/ruyisdk/board-image/meles/1.0/root");
        Path second = cache.resolve("artifacts/ruyisdk/board-image/meles/2.0/root");
        write(first, 11);
        hardLink(second, first);
        Path independent = first.resolveSibling("independent");
        write(independent, 17);
        hardLink(first.resolveSibling("copy"), independent);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        ImageCacheService.CleanupScope scope = service.selectForCleanup();
        List<ImageCacheEntry> entries = scope.usage().entries();
        ImageCacheEntry firstEntry = entries.stream().filter(entry -> entry.version().equals("1.0")).findFirst().orElseThrow();
        ImageCacheEntry secondEntry = entries.stream().filter(entry -> entry.version().equals("2.0")).findFirst().orElseThrow();

        assertEquals(17, scope.releasedBytes(List.of(firstEntry)));
        assertEquals(0, scope.releasedBytes(List.of(secondEntry)));
        assertEquals(28, scope.releasedBytes(entries));
        assertEquals(28, scope.cleanEntries(entries).removedBytes());
    }

    private static void hardLink(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        try {
            Files.createLink(link, target);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.assumeTrue(false, "Hard links unavailable: " + exception);
        }
    }

    /// Leaves markers for other versions outside targeted cleanup.
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
        ImageCacheMarkers.write(cache, archive);
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

    /// Rechecks the catalog after a scan identifies a marked partial.
    @Test
    public void rechecksArchiveDeclarationWhileLocked(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.part");
        write(archive, 10);
        ImageCacheMarkers.write(cache, archive);
        ImageCatalog oldCatalog = catalog("board-image", "meles", List.of("root"), List.of("1.0.0")).listImages();
        ImageCatalog newCatalog = catalog("board-image", "meles", List.of("root.part"), List.of("1.0.0")).listImages();
        AtomicInteger reads = new AtomicInteger();
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache),
                catalog(() -> {
                    if (reads.getAndIncrement() == 0) {
                        return oldCatalog;
                    }
                    try (@Nullable CacheLock lock = CacheLock.acquireVersion(cache,
                            archive.getParent().resolveSibling("1.0.0.lock"))) {
                        assertNull(lock);
                    } catch (IOException exception) {
                        throw new AssertionError(exception);
                    }
                    return newCatalog;
                }));

        assertEquals(0, service.cleanPartials().removedFiles());
        assertEquals(2, reads.get());
        assertTrue(Files.exists(archive));
    }

    /// Unreadable completed artifacts are outside partial preview and cleanup.
    @Test
    public void partialCleanupDoesNotScanCompletedArtifacts(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path artifact = cache.resolve("artifacts/ruyisdk/board-image/meles/1.0");
        Path staged = cache.resolve("downloads/ruyisdk/board-image/meles/2.0/.import-staging-0/source");
        write(artifact.resolve("root"), 5);
        write(staged, 8);
        Assumptions.assumeTrue(Files.getFileAttributeView(artifact, PosixFileAttributeView.class) != null);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(artifact);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        try {
            Files.setPosixFilePermissions(artifact, Set.of());
            Assumptions.assumeFalse(Files.isReadable(artifact));
            assertTrue(service.usageForPartialsCleanup().unreadable().isEmpty());
            ImageCacheCleanResult result = service.cleanPartials();
            assertTrue(result.skipped().isEmpty(), result.skipped().toString());
            assertFalse(Files.exists(staged));
        } finally {
            Files.setPosixFilePermissions(artifact, original);
        }
    }

    /// An import-only version is removable even if its name resembles legacy staging.
    @Test
    public void cleansImportOnlyVersionWithLegacyLookingName(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path staged = cache.resolve("imports/ruyisdk/board-image/meles/1.0.old-1/source");
        write(staged, 5);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        assertEquals(5, service.usageForCleanup(true).totalBytes());
        assertEquals(1, service.cleanAll().removedFiles());
        assertFalse(Files.exists(staged));
    }

    /// A selected version does not inherit errors from another version's unreadable marker.
    @Test
    public void selectedCleanupIgnoresUnattributableMarkerErrors(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path archive = cache.resolve("downloads/ruyisdk/board-image/meles/1.0/root");
        write(archive, 5);
        Path markers = Files.createDirectories(cache.resolve("partials"));
        Path other = ImageCacheMarkers.markerPath(markers,
                Path.of("downloads/ruyisdk/board-image/meles/2.0/root.part"));
        Files.writeString(other, "x".repeat(200 * 1024));
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        ImageCacheCleanResult result = service.cleanEntries(service.usageForCleanup().entries());
        assertEquals(1, result.removedFiles());
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertTrue(Files.exists(other));
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

    /// Keeps unmarked files whose names resemble old import staging files.
    @Test
    public void keepsUnmarkedImportLikeArchives(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path staging = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/.import-boot.tmp");
        write(staging, 10);
        ImageCacheService service = new ImageCacheService(
                new AppDirectories(directory.resolve("config"), cache),
                catalog("board-image", "meles", List.of("root.zst"), List.of("1.0.0")));

        assertEquals(0, service.usage().partialFiles());
        service.cleanPartials();
        assertTrue(Files.exists(staging));
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
    public void ignoresLinkedMarkerDirectory(@TempDir Path directory) throws Exception {
        Path cache = directory.resolve("cache");
        Path partial = cache.resolve("downloads/ruyisdk/board-image/meles/1.0.0/root.zst.part");
        Files.createDirectories(partial.getParent());
        Files.write(partial, new byte[10]);
        Path outside = directory.resolve("outside");
        Files.createDirectories(outside);
        try {
            createCacheAlias(cache.resolve("partials"), outside);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }

        assertThrows(IOException.class, () -> ImageCacheMarkers.write(cache, partial));
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

    /// Partial cleanup works through a cache root junction or symbolic link.
    @Test
    public void cleansPartialDirectoriesThroughCacheAlias(@TempDir Path directory) throws Exception {
        Path real = directory.resolve("real");
        Path staged = real.resolve("downloads/ruyisdk/board-image/meles/1.0/.import-staging-0/source");
        write(staged, 3);
        Path cache = directory.resolve("cache");
        createCacheAlias(cache, real);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        assertEquals(3, service.usageForPartialsCleanup().partialBytes());
        ImageCacheCleanResult result = service.cleanPartials();
        assertEquals(3, result.removedBytes(), result.skipped().toString());
        assertFalse(Files.exists(staged));
    }

    /// A cache replaced after selection validation cannot receive the selected version's deletion.
    @Test
    public void selectionDetectsRootReplacementDuringCleanupLookup(@TempDir Path directory) throws IOException {
        Path root = directory.resolve("cache");
        Path archived = directory.resolve("previous-cache");
        Path relative = Path.of("artifacts/repo/board-image/board/1/root");
        write(root.resolve(relative), 3);
        AtomicInteger loads = new AtomicInteger();
        var service = new ImageCacheService(new AppDirectories(directory, root), catalog(() -> {
            if (loads.incrementAndGet() == 2) {
                try {
                    Files.move(root, archived);
                    write(root.resolve(relative), 5);
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
            }
            return new ImageCatalog(List.of());
        }));
        var scope = service.selectForCleanup();
        var result = scope.cleanEntries(scope.usage().entries());
        assertEquals(0, result.removedFiles());
        assertFalse(result.skipped().isEmpty());
        assertEquals(3, Files.size(archived.resolve(relative)));
        assertEquals(5, Files.size(root.resolve(relative)));
    }

    /// Selection scans the captured root even if the configured alias changes during catalog loading.
    @Test
    public void selectionScansCapturedCacheRoot(@TempDir Path directory) throws Exception {
        Path first = directory.resolve("a");
        Path second = directory.resolve("b");
        Path initial = first.resolve("downloads/ruyisdk/board-image/meles/1.0/root");
        Path redirected = second.resolve("downloads/ruyisdk/board-image/meles/2.0/root");
        write(initial, 3);
        write(redirected, 5);
        Path cache = directory.resolve("cache");
        createCacheAlias(cache, first);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache),
                catalog(() -> {
                    try {
                        Files.delete(cache);
                        createCacheAlias(cache, second);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                    return new ImageCatalog(List.of());
                }));

        ImageCacheService.CleanupScope scope = service.selectForCleanup();
        assertEquals("1.0", scope.usage().entries().getFirst().version());
        assertEquals(3, scope.usage().downloadBytes());
    }

    /// A cache alias retargeted after selection cannot redirect a deletion.
    @Test
    public void selectedCleanupRejectsRetargetedCacheAlias(@TempDir Path directory) throws Exception {
        Path realA = directory.resolve("a");
        Path realB = directory.resolve("b");
        Path relative = Path.of("downloads/ruyisdk/board-image/meles/1.0/root");
        write(realA.resolve(relative), 3);
        write(realB.resolve(relative), 3);
        Path cache = directory.resolve("cache");
        createCacheAlias(cache, realA);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        ImageCacheService.CleanupScope scope = service.selectForCleanup();
        Files.delete(cache);
        createCacheAlias(cache, realB);

        ImageCacheCleanResult result = scope.cleanEntries(scope.usage().entries());
        assertEquals(0, result.removedFiles());
        assertFalse(result.skipped().isEmpty());
        assertTrue(Files.exists(realA.resolve(relative)));
        assertTrue(Files.exists(realB.resolve(relative)));
    }

    /// Creates a junction on Windows and a symbolic link on POSIX.
    private static void createCacheAlias(Path alias, Path target) throws Exception {
        if (java.io.File.separatorChar == '\\') {
            Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                    alias.toString(), target.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            Assumptions.assumeTrue(process.waitFor() == 0, output);
        } else {
            Files.createSymbolicLink(alias, target);
        }
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
