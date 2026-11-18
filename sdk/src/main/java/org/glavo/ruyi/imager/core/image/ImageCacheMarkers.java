// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/// Stores marker files that tell in-progress downloads apart from complete archives.
///
/// Markers live in a dedicated directory outside the download tree. Their names and contents use
/// cache-relative paths so they stay valid if the cache moves or is accessed through another alias.
///
/// P1:92 标记的创建、发布和删除都相对同一个已校验并绑定的 `partials` 目录执行，因此父目录被替换时
/// 这些操作不会落到缓存之外。
@NotNullByDefault
final class ImageCacheMarkers {
    /// Directory below the cache root that stores download markers.
    static final String DIRECTORY = "partials";

    /// Suffix of one marker file.
    private static final String SUFFIX = ".marker";

    /// Name of a published marker file.
    private static final Pattern MARKER_NAME = Pattern.compile("[0-9a-f]{64}\\.marker");

    /// Maximum accepted size of a stored marker path.
    private static final int MAX_MARKER_BYTES = 128 * 1024;

    /// Prevents instantiation.
    private ImageCacheMarkers() {
    }

    /// Returns the physical marker directory, optionally creating it.
    ///
    /// The configured cache root may be a symbolic link alias, but the marker directory itself must
    /// resolve to the same physical path so markers never land outside the cache.
    ///
    /// @param cacheRoot configured cache root.
    /// @param create whether the directory may be created.
    /// @return marker directory, or null when the directory is unsafe or unreadable.
    static @Nullable Path directory(Path cacheRoot, boolean create) {
        try {
            Path root = cacheRoot.toRealPath();
            Path directory = root.resolve(DIRECTORY);
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    return null;
                }
                return directory.toRealPath().equals(directory) ? directory : null;
            }
            if (!create) {
                return null;
            }
            Files.createDirectories(directory);
            return directory;
        } catch (IOException _) {
            return null;
        }
    }

    /// A marker directory held open together with its physical cache root.
    ///
    /// @param root physical cache root.
    /// @param directory bound marker directory.
    @NotNullByDefault
    private record Bound(Path root, CacheTreeRemoval.Directory directory) implements AutoCloseable {
        /// Releases the bound marker directory.
        @Override
        public void close() throws IOException {
            directory.close();
        }
    }

    /// Binds the marker directory without re-resolving its ancestors for each operation.
    ///
    /// @param cacheRoot configured cache root.
    /// @param create whether the directory may be created.
    /// @return bound marker directory, or null when it is unsafe or unavailable.
    private static @Nullable Bound bind(Path cacheRoot, boolean create) {
        try {
            Path root = cacheRoot.toRealPath();
            return new Bound(root, CacheTreeRemoval.bindDirectory(root, Path.of(DIRECTORY), create));
        } catch (IOException _) {
            return null;
        }
    }

    /// Writes the marker of an in-progress download.
    ///
    /// Creation and publication use the bound marker directory.
    ///
    /// @param cacheRoot cache root directory.
    /// @param partialFile partial download file.
    /// @throws IOException when the marker cannot be written.
    static void write(Path cacheRoot, Path partialFile) throws IOException {
        try (@Nullable Bound bound = bind(cacheRoot, true)) {
            if (bound == null) {
                return;
            }
            @Nullable Path relative =
                    relativePartial(cacheRoot.toAbsolutePath().normalize(), bound.root(), partialFile);
            if (relative == null) {
                return;
            }
            Path marker = bound.directory().path().resolve(markerName(relative));
            if (Files.isSymbolicLink(marker)) {
                return;
            }
            Path staged = Path.of(".marker-" + UUID.randomUUID() + ".tmp");
            byte[] contents = storedPath(relative).getBytes(StandardCharsets.UTF_8);
            boolean created = false;
            try {
                bound.directory().writeNewFile(staged, contents);
                created = true;
                bound.directory().moveFile(staged, Path.of(markerName(relative)));
                created = false;
            } finally {
                if (created) {
                    deleteIfExists(bound.directory(), staged);
                }
            }
        }
    }

    /// Returns whether an in-progress download marker exists.
    ///
    /// P2:121 名称必须与内容一致：空标记、内容错配或内容不是下载分片的标记都不算存在，
    /// 否则完整的 `.part` 归档可能被当作分片删除。
    ///
    /// @param cacheRoot cache root directory.
    /// @param partialFile partial download file.
    /// @return whether the partial file has a marker.
    static boolean exists(Path cacheRoot, Path partialFile) {
        @Nullable Path directory = directory(cacheRoot, false);
        if (directory == null) {
            return false;
        }
        Path configuredRoot = cacheRoot.toAbsolutePath().normalize();
        Path physicalRoot = directory.resolve("..").normalize();
        @Nullable Path relative = relativePartial(configuredRoot, physicalRoot, partialFile);
        if (relative == null) {
            return false;
        }
        return markerMatches(directory, cacheRoot, markerPath(directory, relative))
                || markerMatches(directory, cacheRoot, legacyMarkerPath(directory, configuredRoot.resolve(relative)))
                || (!configuredRoot.equals(physicalRoot)
                && markerMatches(directory, cacheRoot, legacyMarkerPath(directory, physicalRoot.resolve(relative))));
    }

    /// Checks that one marker file's name and contents identify a partial download.
    ///
    /// @param directory physical marker directory.
    /// @param cacheRoot configured cache root.
    /// @param marker candidate marker path.
    /// @return whether the marker is present and self-consistent.
    private static boolean markerMatches(Path directory, Path cacheRoot, Path marker) {
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            @Nullable String stored = readStoredPath(marker);
            if (stored == null || stored.isEmpty()) {
                return false;
            }
            @Nullable Path resolved = resolvePartial(cacheRoot, stored);
            return resolved != null && identifiesMarker(marker, cacheRoot, resolved, stored);
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }

    /// Reads stored marker contents without following links or accepting oversized files.
    ///
    /// @param marker marker file.
    /// @return trimmed contents, or null when the marker is oversized.
    /// @throws IOException when the marker cannot be read.
    static @Nullable String readStoredPath(Path marker) throws IOException {
        try (var input = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_MARKER_BYTES + 1);
            if (bytes.length > MAX_MARKER_BYTES) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8).trim();
        }
    }

    /// Removes the marker of a partial file.
    ///
    /// Deletes through the bound marker directory.
    ///
    /// @param cacheRoot cache root directory.
    /// @param partialFile partial download file.
    /// @throws IOException when the marker exists but cannot be removed.
    static void delete(Path cacheRoot, Path partialFile) throws IOException {
        try (@Nullable Bound bound = bind(cacheRoot, false)) {
            if (bound == null) {
                return;
            }
            Path configuredRoot = cacheRoot.toAbsolutePath().normalize();
            Path physicalRoot = bound.root();
            @Nullable Path relative = relativePartial(configuredRoot, physicalRoot, partialFile);
            if (relative == null) {
                return;
            }
            deleteIfExists(bound.directory(), Path.of(markerName(relative)));
            deleteIfExists(bound.directory(), Path.of(legacyMarkerName(configuredRoot.resolve(relative))));
            if (!configuredRoot.equals(physicalRoot)) {
                deleteIfExists(bound.directory(), Path.of(legacyMarkerName(physicalRoot.resolve(relative))));
            }
        }
    }

    /// Deletes one marker name from the bound directory, ignoring a missing entry.
    ///
    /// @param directory bound marker directory.
    /// @param name marker name.
    /// @throws IOException when the entry cannot be deleted.
    private static void deleteIfExists(CacheTreeRemoval.Directory directory, Path name) throws IOException {
        try {
            directory.deleteFile(name);
        } catch (NoSuchFileException _) {
            // The marker was already removed.
        }
    }

    /// Resolves the stored marker contents against the current cache root.
    ///
    /// Older absolute markers are supported when their stored path is under the configured or
    /// physical cache root.
    ///
    /// @param cacheRoot cache root directory.
    /// @param stored marker contents.
    /// @return absolute partial path under the configured cache root, or null for invalid contents.
    static @Nullable Path resolvePartial(Path cacheRoot, String stored) {
        Path configuredRoot = cacheRoot.toAbsolutePath().normalize();
        try {
            Path path = cacheRoot.getFileSystem().getPath(stored).normalize();
            if (!path.isAbsolute()) {
                return isDownloadPath(path) ? configuredRoot.resolve(path) : null;
            }
            @Nullable Path relative = relativePartial(configuredRoot, configuredRoot, path);
            if (relative == null) {
                relative = relativePartial(configuredRoot, configuredRoot.toRealPath(), path);
            }
            return relative == null ? null : configuredRoot.resolve(relative);
        } catch (InvalidPathException | IOException _) {
            return null;
        }
    }

    /// Returns whether an entry has a published marker name.
    static boolean isMarkerName(Path path) {
        return MARKER_NAME.matcher(path.getFileName().toString()).matches();
    }

    /// Checks that a marker's name and contents identify the same partial.
    ///
    /// @param marker enumerated marker file.
    /// @param cacheRoot configured cache root.
    /// @param partial resolved partial path under the configured cache root.
    /// @param stored original marker contents.
    /// @return whether the name is the relative or legacy absolute marker key for the partial.
    static boolean identifiesMarker(Path marker, Path cacheRoot, Path partial, String stored) {
        Path relative = cacheRoot.toAbsolutePath().normalize().relativize(partial);
        String name = marker.getFileName().toString();
        if (name.equals(hash(storedPath(relative)) + SUFFIX)) {
            return true;
        }
        Path storedPath = cacheRoot.getFileSystem().getPath(stored);
        return storedPath.isAbsolute() && name.equals(hash(storedPath.normalize().toString()) + SUFFIX);
    }

    /// Locates a partial relative to the configured cache root or its physical equivalent.
    ///
    /// @param cacheRoot configured cache root.
    /// @param physicalRoot physical cache root.
    /// @param partialFile partial download file.
    /// @return cache-relative partial path, or null when the path is outside the download tree.
    private static @Nullable Path relativePartial(Path cacheRoot, Path physicalRoot, Path partialFile) {
        Path configuredRoot = cacheRoot.toAbsolutePath().normalize();
        Path absolute = partialFile.toAbsolutePath().normalize();
        Path relative;
        if (absolute.startsWith(configuredRoot)) {
            relative = configuredRoot.relativize(absolute);
        } else if (absolute.startsWith(physicalRoot)) {
            relative = physicalRoot.relativize(absolute);
        } else {
            return null;
        }
        return isDownloadPath(relative) ? relative : null;
    }

    /// Checks that a path identifies a file directly inside a download version.
    ///
    /// @param relative normalized candidate path.
    /// @return whether it has the download layout.
    private static boolean isDownloadPath(Path relative) {
        return !relative.isAbsolute() && relative.getNameCount() == 6 && relative.startsWith("downloads");
    }

    /// Returns the marker path of a cache-relative partial path.
    ///
    /// @param directory physical marker directory.
    /// @param relativePartial cache-relative partial download path.
    /// @return marker path.
    static Path markerPath(Path directory, Path relativePartial) {
        return directory.resolve(markerName(relativePartial));
    }

    /// Returns the hash name of the current-format marker of a cache-relative partial path.
    ///
    /// @param relativePartial cache-relative partial download path.
    /// @return marker file name.
    private static String markerName(Path relativePartial) {
        return hash(storedPath(relativePartial)) + SUFFIX;
    }

    /// Returns the former absolute-path marker name.
    ///
    /// @param directory physical marker directory.
    /// @param absolutePartial normalized absolute partial download path.
    /// @return marker path.
    private static Path legacyMarkerPath(Path directory, Path absolutePartial) {
        return directory.resolve(legacyMarkerName(absolutePartial));
    }

    /// Returns the former absolute-path marker file name.
    ///
    /// @param absolutePartial normalized absolute partial download path.
    /// @return marker file name.
    private static String legacyMarkerName(Path absolutePartial) {
        return hash(absolutePartial.toString()) + SUFFIX;
    }

    /// Serializes a cache-relative path with a stable directory separator.
    ///
    /// @param relative cache-relative partial download path.
    /// @return marker contents.
    private static String storedPath(Path relative) {
        return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
    }

    /// Hashes a marker key to keep archive names out of the marker directory namespace.
    ///
    /// @param key normalized partial path.
    /// @return hexadecimal SHA-256 digest.
    private static String hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
