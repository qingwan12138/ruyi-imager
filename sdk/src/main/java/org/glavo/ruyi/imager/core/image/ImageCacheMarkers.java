// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/// Stores marker files that tell in-progress downloads apart from complete archives.
///
/// Markers live in a dedicated directory outside the download tree. Their names and contents use
/// cache-relative paths so they stay valid if the cache moves or is accessed through another alias.
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
    /// @return bound marker directory.
    /// @throws IOException when the directory cannot be safely bound.
    private static Bound bind(Path cacheRoot, boolean create) throws IOException {
        Path root = cacheRoot.toRealPath();
        return new Bound(root, CacheTreeRemoval.bindDirectory(root, Path.of(DIRECTORY), create));
    }

    /// Writes the marker of an in-progress download.
    ///
    /// Creation and publication use the bound marker directory.
    ///
    /// @param cacheRoot cache root directory.
    /// @param partialFile partial download file.
    /// @throws IOException when the marker cannot be written.
    static void write(Path cacheRoot, Path partialFile) throws IOException {
        try (Bound bound = bind(cacheRoot, true)) {
            @Nullable Path relative =
                    relativePartial(cacheRoot.toAbsolutePath().normalize(), bound.root(), partialFile);
            if (relative == null) {
                return;
            }
            Path marker = Path.of(markerName(relative));
            try {
                if (!bound.directory().isRegularFile(marker)) {
                    throw new IOException("Expected a regular download marker: " + bound.directory().path().resolve(marker));
                }
            } catch (NoSuchFileException _) {
                // The name is available for a new marker.
            }
            Path staged = Path.of(".marker-" + UUID.randomUUID() + ".tmp");
            byte[] contents = storedPath(relative).getBytes(StandardCharsets.UTF_8);
            boolean created = false;
            try {
                bound.directory().writeNewFile(staged, contents);
                created = true;
                bound.directory().verifyIdentity();
                bound.directory().moveFile(staged, marker);
                created = false;
            } finally {
                if (created) {
                    deleteIfExists(bound.directory(), staged);
                }
            }
        }
    }

    /// Returns whether an in-progress marker agrees with its contents.
    static boolean exists(Path cacheRoot, Path partialFile) {
        try (Bound bound = bind(cacheRoot, false)) {
            Path configuredRoot = cacheRoot.toAbsolutePath().normalize();
            @Nullable Path relative = relativePartial(configuredRoot, bound.root(), partialFile);
            if (relative == null) {
                return false;
            }
            return markerMatches(bound, configuredRoot, Path.of(markerName(relative)))
                    || markerMatches(bound, configuredRoot, Path.of(legacyMarkerName(configuredRoot.resolve(relative))))
                    || (!configuredRoot.equals(bound.root()) && markerMatches(bound, configuredRoot,
                    Path.of(legacyMarkerName(bound.root().resolve(relative)))));
        } catch (IOException _) {
            return false;
        }
    }

    /// Checks that a marker's name and contents identify the same partial in the bound directory.
    private static boolean markerMatches(Bound bound, Path cacheRoot, Path marker) {
        try {
            @Nullable String stored = readStoredPath(bound.directory(), marker);
            if (stored == null || stored.isEmpty()) {
                return false;
            }
            @Nullable Path resolved = resolvePartial(cacheRoot, bound.root(), stored);
            return resolved != null && identifiesMarker(marker, cacheRoot, resolved, stored);
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }

    /// Reads a regular marker in the bound directory, returning null for oversized contents.
    static @Nullable String readStoredPath(CacheTreeRemoval.Directory directory, Path marker) throws IOException {
        byte[] bytes = directory.readFile(marker, MAX_MARKER_BYTES + 1);
        return bytes.length > MAX_MARKER_BYTES ? null : new String(bytes, StandardCharsets.UTF_8).trim();
    }

    /// Removes the marker of a partial file.
    ///
    /// Deletes through the bound marker directory.
    ///
    /// @param cacheRoot cache root directory.
    /// @param partialFile partial download file.
    /// @throws IOException when the marker exists but cannot be removed.
    static void delete(Path cacheRoot, Path partialFile) throws IOException {
        try (Bound bound = bind(cacheRoot, false)) {
            Path configuredRoot = cacheRoot.toAbsolutePath().normalize();
            Path physicalRoot = bound.root();
            @Nullable Path relative = relativePartial(configuredRoot, physicalRoot, partialFile);
            if (relative == null) {
                return;
            }
            deleteKnownNames(bound, configuredRoot, relative);
        } catch (NoSuchFileException _) {
            // No marker directory has been created.
        }
    }

    /// Removes any legacy partial identity before exposing a complete archive.
    ///
    /// The caller holds the version lock through marker deletion and archive publication.
    static void clearArchiveMarker(Path cacheRoot, Path archive) throws IOException {
        String name = archive.getFileName().toString();
        if (!name.regionMatches(true, name.length() - ".part".length(), ".part", 0, ".part".length())
                && !(name.regionMatches(true, 0, ".import-", 0, ".import-".length())
                && name.regionMatches(true, name.length() - ".tmp".length(), ".tmp", 0, ".tmp".length()))) {
            return;
        }
        Bound available;
        try {
            available = bind(cacheRoot, false);
        } catch (NoSuchFileException _) {
            return;
        }
        try (Bound bound = available) {
            Path configuredRoot = cacheRoot.toAbsolutePath().normalize();
            @Nullable Path relative = relativePartial(configuredRoot, bound.root(), archive);
            if (relative == null) {
                return;
            }
            deleteKnownNames(bound, configuredRoot, relative);
            Path finalPath = bound.root().resolve(relative);
            Path finalParent = finalPath.getParent().toRealPath();
            Path canonicalFinal = finalParent.resolve(finalPath.getFileName());
            @Nullable Path existingFinal = realPathIfPresent(canonicalFinal);
            @Nullable Path canonicalRelative = relativePartial(bound.root(), bound.root(),
                    existingFinal == null ? canonicalFinal : existingFinal);
            if (canonicalRelative != null && !relative.toString().equals(canonicalRelative.toString())) {
                deleteKnownNames(bound, configuredRoot, canonicalRelative);
            }
            @Nullable CacheTreeRemoval.NameEquivalence equivalence = null;
            for (Path marker : bound.directory().names()) {
                if (!isMarkerName(marker)) {
                    continue;
                }
                @Nullable String stored;
                try {
                    if (!bound.directory().isRegularFile(marker)) {
                        continue;
                    }
                    stored = readStoredPath(bound.directory(), marker);
                } catch (IOException _) {
                    // Unknown hashes require readable contents to establish ownership.
                    // Cleanup only trusts exact marker keys, which were removed above.
                    continue;
                }
                if (stored == null || stored.isEmpty()) {
                    continue;
                }
                @Nullable Path markedRelative = archiveMarkerRelative(marker, configuredRoot, bound.root(), stored);
                if (markedRelative == null) {
                    continue;
                }
                Path markedPath = bound.root().resolve(markedRelative);
                try {
                    if (!markedPath.getParent().toRealPath().equals(finalParent)) {
                        continue;
                    }
                } catch (IOException _) {
                    // Alias discovery cannot establish that this marker belongs to the archive.
                    continue;
                }
                @Nullable Path existingMarked;
                try {
                    existingMarked = realPathIfPresent(finalParent.resolve(markedPath.getFileName()));
                } catch (IOException _) {
                    continue;
                }
                if (existingMarked != null || existingFinal != null) {
                    if (existingMarked != null && existingFinal != null
                            && existingMarked.toString().equals(existingFinal.toString())) {
                        deleteIfExists(bound.directory(), marker);
                    }
                    continue;
                }
                if (equivalence == null) {
                    equivalence = CacheTreeRemoval.nameEquivalence(finalParent);
                }
                if (sameArchiveName(markedPath.getFileName().toString(), finalPath.getFileName().toString(), equivalence)) {
                    deleteIfExists(bound.directory(), marker);
                }
            }
            bound.directory().verifyIdentity();
        }
    }

    /// Resolves the spelling of an existing entry without following its final component.
    private static @Nullable Path realPathIfPresent(Path path) throws IOException {
        try {
            return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException _) {
            return null;
        }
    }

    /// Recognizes a matching absolute marker even when an older cache alias no longer resolves.
    private static @Nullable Path archiveMarkerRelative(Path marker, Path configuredRoot, Path physicalRoot, String stored) {
        @Nullable Path resolved = resolvePartial(configuredRoot, physicalRoot, stored);
        if (resolved != null && identifiesMarker(marker, configuredRoot, resolved, stored)) {
            return configuredRoot.relativize(resolved);
        }
        try {
            Path absolute = marker.getFileSystem().getPath(stored).normalize();
            int count = absolute.getNameCount();
            if (absolute.isAbsolute() && count >= 6 && marker.toString().equals(legacyMarkerName(absolute))) {
                Path relative = absolute.subpath(count - 6, count);
                return isDownloadPath(relative) ? relative : null;
            }
        } catch (InvalidPathException _) {
            // This marker does not contain a usable path.
        }
        return null;
    }

    /// Checks alternate spellings using the archive directory's observed equivalences.
    private static boolean sameArchiveName(String marked, String published, CacheTreeRemoval.NameEquivalence equivalence) {
        if (equivalence.normalizationInsensitive()) {
            marked = Normalizer.normalize(marked, Normalizer.Form.NFD);
            published = Normalizer.normalize(published, Normalizer.Form.NFD);
        }
        return equivalence.caseInsensitive() ? marked.equalsIgnoreCase(published) : marked.equals(published);
    }

    /// Deletes the relative marker and the known absolute marker names.
    private static void deleteKnownNames(Bound bound, Path configuredRoot, Path relative) throws IOException {
        deleteIfExists(bound.directory(), Path.of(markerName(relative)));
        deleteIfExists(bound.directory(), Path.of(legacyMarkerName(configuredRoot.resolve(relative))));
        if (!configuredRoot.equals(bound.root())) {
            deleteIfExists(bound.directory(), Path.of(legacyMarkerName(bound.root().resolve(relative))));
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
        try {
            return resolvePartial(cacheRoot, cacheRoot.toRealPath(), stored);
        } catch (IOException _) {
            return resolvePartial(cacheRoot, cacheRoot.toAbsolutePath().normalize(), stored);
        }
    }

    /// Resolves a marker using the already bound physical cache root.
    static @Nullable Path resolvePartial(Path cacheRoot, Path physicalRoot, String stored) {
        Path configuredRoot = cacheRoot.toAbsolutePath().normalize();
        try {
            Path path = cacheRoot.getFileSystem().getPath(stored).normalize();
            if (!path.isAbsolute()) {
                return isDownloadPath(path) ? configuredRoot.resolve(path) : null;
            }
            @Nullable Path relative = relativePartial(configuredRoot, physicalRoot, path);
            return relative == null ? null : configuredRoot.resolve(relative);
        } catch (InvalidPathException _) {
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
    static Path legacyMarkerPath(Path directory, Path absolutePartial) {
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
