// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/// Tests stable identification of incomplete downloads.
@NotNullByDefault
class ImageCacheMarkersTest {
    /// Cache-relative path of the incomplete download in each fixture.
    private static final String PARTIAL = "downloads/repo/board-image/meles/1.0.0/root.zst.part";

    /// Reports a binding failure when the marker directory is occupied by a file.
    @Test
    void reportsAnUnsafeMarkerDirectory(@TempDir Path cache) throws IOException {
        Files.writeString(cache.resolve(ImageCacheMarkers.DIRECTORY), "occupied");
        assertThrows(IOException.class,
                () -> ImageCacheMarkers.write(cache, cache.resolve(PARTIAL)));
    }

    /// Invalidates an old absolute marker even when its cache alias is gone.
    @Test
    void clearsLegacyArchiveMarkerFromFormerCacheAlias(@TempDir Path directory) throws IOException {
        Path cache = Files.createDirectory(directory.resolve("cache"));
        Path archive = cache.resolve(PARTIAL);
        Files.createDirectories(archive.getParent());
        Path markers = Files.createDirectory(cache.resolve(ImageCacheMarkers.DIRECTORY));
        Path formerPath = directory.resolve("former-cache").resolve(PARTIAL);
        Path marker = Files.writeString(ImageCacheMarkers.legacyMarkerPath(markers, formerPath), formerPath.toString());

        ImageCacheMarkers.clearArchiveMarker(cache, archive);

        assertFalse(Files.exists(marker));
    }

    /// Retires a differently spelled marker only when the spelling aliases the archive.
    @Test
    void distinguishesIndependentArchiveNames(@TempDir Path cache) throws IOException {
        Path version = Files.createDirectories(cache.resolve("downloads/repo/board-image/meles/1.0.0"));
        Path marked = Files.writeString(version.resolve("A\u03a3-1.part"), "resume bytes");
        Path archive = version.resolve("A\u03c3-1.part");
        boolean aliases = Files.exists(archive);
        ImageCacheMarkers.write(cache, marked);

        ImageCacheMarkers.clearArchiveMarker(cache, archive);

        assertEquals(!aliases, ImageCacheMarkers.exists(cache, marked));
    }

    /// The marker identifies the same file after the cache directory moves.
    @Test
    void markerSurvivesCacheRelocation(@TempDir Path directory) throws IOException {
        Path cache = Files.createDirectory(directory.resolve("cache"));
        Path partial = cache.resolve(PARTIAL);
        ImageCacheMarkers.write(cache, partial);

        Path relocated = Files.move(cache, directory.resolve("relocated"));
        Path movedPartial = relocated.resolve(PARTIAL);
        assertTrue(ImageCacheMarkers.exists(relocated, movedPartial));
        Path marker = ImageCacheMarkers.markerPath(relocated.resolve("partials"), Path.of(PARTIAL));
        assertEquals(PARTIAL, Files.readString(marker));
        assertEquals(movedPartial, ImageCacheMarkers.resolvePartial(relocated, Files.readString(marker)));

        ImageCacheMarkers.delete(relocated, movedPartial);
        assertFalse(ImageCacheMarkers.exists(relocated, movedPartial));
    }

    /// Updating a marker cannot truncate a different hard link to its previous inode.
    @Test
    void markerPublicationDoesNotMutateOtherHardLinks(@TempDir Path directory) throws IOException {
        Path cache = Files.createDirectory(directory.resolve("cache"));
        Path partial = cache.resolve(PARTIAL);
        Path markers = Files.createDirectory(cache.resolve("partials"));
        Path previous = Files.writeString(directory.resolve("preserved"), "preserved");
        Path marker = ImageCacheMarkers.markerPath(markers, cache.relativize(partial));
        try {
            Files.createLink(marker, previous);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "hard links are unavailable");
        }

        ImageCacheMarkers.write(cache, partial);

        assertEquals("preserved", Files.readString(previous));
        assertEquals(PARTIAL, Files.readString(marker));
    }

    /// A marker written via a cache alias is also visible through the physical root.
    @Test
    void markerSurvivesCacheAliasChange(@TempDir Path directory) throws IOException {
        Path cache = Files.createDirectory(directory.resolve("cache"));
        Path alias = directory.resolve("alias");
        try {
            Files.createSymbolicLink(alias, cache);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "cache directory aliases are unavailable");
        }

        ImageCacheMarkers.write(alias, alias.resolve(PARTIAL));
        assertTrue(ImageCacheMarkers.exists(cache, cache.resolve(PARTIAL)));
        ImageCacheMarkers.delete(cache, cache.resolve(PARTIAL));
        assertFalse(ImageCacheMarkers.exists(alias, alias.resolve(PARTIAL)));
    }

    /// Absolute marker keys from older releases remain readable at the original location.
    @Test
    void recognizesLegacyAbsoluteMarker(@TempDir Path directory) throws Exception {
        Path cache = directory.resolve("cache");
        Path partial = cache.resolve(PARTIAL);
        Path markers = Files.createDirectories(cache.resolve("partials"));
        String stored = partial.toAbsolutePath().normalize().toString();
        String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(stored.getBytes(StandardCharsets.UTF_8)));
        Path marker = Files.writeString(markers.resolve(key + ".marker"), stored);

        assertTrue(ImageCacheMarkers.exists(cache, partial));
        assertEquals(partial, ImageCacheMarkers.resolvePartial(cache, stored));
        ImageCacheMarkers.delete(cache, partial);
        assertFalse(Files.exists(marker));
    }

    /// Marker contents must identify a download inside the configured cache.
    @Test
    void rejectsPathsOutsideDownloads(@TempDir Path directory) throws IOException {
        Path cache = Files.createDirectory(directory.resolve("cache"));
        assertNull(ImageCacheMarkers.resolvePartial(cache, "../" + PARTIAL));
        assertNull(ImageCacheMarkers.resolvePartial(cache, directory.resolve(PARTIAL).toString()));
        assertNull(ImageCacheMarkers.resolvePartial(cache, "logs/repo/board-image/meles/1.0.0/root.zst.part"));
    }

    /// Empty and mismatched markers do not identify a partial.
    @Test
    void requiresMatchingMarkerContent(@TempDir Path directory) throws IOException {
        Path cache = Files.createDirectory(directory.resolve("cache"));
        Path markers = Files.createDirectories(cache.resolve("partials"));
        Path partial = cache.resolve(PARTIAL);
        Files.createDirectories(partial.getParent());
        Files.write(partial, new byte[16]);
        Path marker = ImageCacheMarkers.markerPath(markers, cache.relativize(partial));

        Files.writeString(marker, "");
        assertFalse(ImageCacheMarkers.exists(cache, partial));

        Files.writeString(marker, "downloads/repo/board-image/meles/2.0.0/root.zst.part");
        assertFalse(ImageCacheMarkers.exists(cache, partial));

        Files.writeString(marker, PARTIAL);
        assertTrue(ImageCacheMarkers.exists(cache, partial));
    }

    /// A linked marker directory is rejected before writing.
    @Test
    void neverWritesThroughLinkedMarkerDirectory(@TempDir Path directory) throws IOException {
        Path cache = Files.createDirectory(directory.resolve("cache"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        try {
            Files.createSymbolicLink(cache.resolve("partials"), outside);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }

        assertThrows(IOException.class, () -> ImageCacheMarkers.write(cache, cache.resolve(PARTIAL)));

        try (var children = Files.list(outside)) {
            assertTrue(children.findAny().isEmpty());
        }
        assertFalse(ImageCacheMarkers.exists(cache, cache.resolve(PARTIAL)));
    }
}
