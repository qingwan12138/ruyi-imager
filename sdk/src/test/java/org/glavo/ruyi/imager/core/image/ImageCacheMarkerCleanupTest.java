// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Tests recognition and cleanup of download markers.
@NotNullByDefault
class ImageCacheMarkerCleanupTest {
    /// Relative host directory for the fixture version.
    private static final String VERSION = "downloads/repo/board-image/Board/1.0.0";

    /// The marker can record a different spelling of the actual version directory.
    @Test
    void recognizesMarkerThroughDirectoryCaseAlias(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path version = Files.createDirectories(cache.resolve(VERSION));
        Path partial = Files.write(version.resolve("root.zst.part"), new byte[7]);
        Path alias = cache.resolve(VERSION.replace("/Board/", "/board/")).resolve(partial.getFileName());
        Assumptions.assumeTrue(Files.exists(alias) && Files.isSameFile(partial, alias));
        ImageCacheMarkers.write(cache, alias);

        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        ImageCacheUsage usage = service.usage();
        assertEquals(0, usage.downloadBytes());
        assertEquals(7, usage.partialBytes());
        ImageCacheCleanResult result = service.cleanPartials();
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertFalse(Files.exists(partial));
        assertFalse(ImageCacheMarkers.exists(cache, alias));
    }

    /// The selection keeps an aliased marker associated with its version after the version is deleted.
    @Test
    void removesAliasedMarkersForSelectedVersion(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path version = Files.createDirectories(cache.resolve(VERSION));
        Path alias = cache.resolve(VERSION.replace("/Board/", "/board/"));
        Assumptions.assumeTrue(Files.exists(alias) && Files.isSameFile(version, alias));
        Files.write(version.resolve("root.zst.part"), new byte[7]);
        Path active = alias.resolve("root.zst.part");
        Path stale = alias.resolve("stale.zst.part");
        ImageCacheMarkers.write(cache, active);
        ImageCacheMarkers.write(cache, stale);
        Path marker = ImageCacheMarkers.markerPath(cache.resolve("partials"), cache.relativize(stale));
        long bytes = Files.size(marker);
        var service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        var scope = service.selectForCleanup();
        var entries = scope.usage().entries();

        assertEquals(1, entries.size());
        assertEquals(7 + bytes, scope.releasedBytes(entries));
        ImageCacheCleanResult result = scope.cleanEntries(entries);
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertEquals(7 + bytes, result.removedBytes());
        assertEquals(2L, result.removedFiles());
        assertFalse(Files.exists(version));
        assertFalse(ImageCacheMarkers.exists(cache, active));
        assertFalse(ImageCacheMarkers.exists(cache, stale));
    }

    /// Different names on a case-sensitive filesystem remain separate even if they are hardlinks.
    @Test
    void doesNotApplyMarkerToCaseDistinctHardlink(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path version = Files.createDirectories(cache.resolve(VERSION));
        Path marked = Files.write(version.resolve("root.zst.part"), new byte[7]);
        Path other = version.resolve("Root.zst.part");
        Assumptions.assumeTrue(Files.notExists(other));
        try {
            Files.createLink(other, marked);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "hardlinks are unavailable");
        }
        ImageCacheMarkers.write(cache, marked);

        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));
        ImageCacheCleanResult result = service.cleanPartials();
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertFalse(Files.exists(marked));
        assertTrue(Files.exists(other));
    }

    /// A standalone marker contributes to both preview and cleanup totals.
    @Test
    void countsStandaloneMarker(@TempDir Path directory) throws IOException {
        Path cache = Files.createDirectory(directory.resolve("cache"));
        Path partial = cache.resolve(VERSION).resolve("root.zst.part");
        ImageCacheMarkers.write(cache, partial);
        Path marker = ImageCacheMarkers.markerPath(cache.resolve("partials"), cache.relativize(partial));
        long bytes = Files.size(marker);
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        assertEquals(bytes, service.usageForPartialsCleanup().partialBytes());
        assertEquals(bytes, service.usageForCleanup(true).totalBytes());
        ImageCacheCleanResult result = service.cleanPartials();
        assertEquals(bytes, result.removedBytes());
        assertEquals(1, result.removedFiles());
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertFalse(Files.exists(marker));
    }

    /// A lock held by the stale marker's version is reported even without an image entry.
    @Test
    void reportsStandaloneMarkerLockContention(@TempDir Path directory) throws IOException {
        Path cache = directory.resolve("cache");
        Path version = Files.createDirectories(cache.resolve(VERSION));
        Path partial = version.resolve("root.zst.part");
        ImageCacheMarkers.write(cache, partial);
        Path lock = version.resolveSibling("1.0.0.lock");
        ImageCacheService service = new ImageCacheService(new AppDirectories(directory.resolve("config"), cache));

        assertTrue(CacheTreeRemoval.withVersionLock(cache.toRealPath(), cache.relativize(lock), _ -> {
            ImageCacheCleanResult result = service.cleanPartials();
            assertEquals(0, result.removedFiles());
            assertEquals(List.of(ImageCacheService.SKIPPED_IN_USE + "repo:board-image/Board(1.0.0)"), result.skipped());
        }));
        assertTrue(ImageCacheMarkers.exists(cache, partial));
    }
}
