// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;

/// One cached image version that can be removed from the download cache.
///
/// Byte counts report data released by removing this version alone.
///
/// @param repoId repository identifier the image belongs to.
/// @param category image category, such as `board-image`.
/// @param name image name.
/// @param version image version.
/// @param atom image atom derived from the cache layout.
/// @param artifactBytes bytes of materialized images.
/// @param downloadBytes bytes of completed download archives.
/// @param partialBytes bytes of incomplete downloads and leftover materialization directories.
/// @param partialFiles number of incomplete downloads and leftover materialization directories.
/// @param lastModifiedMillis newest modification time seen among the scanned cache paths.
/// @param completeness whether all catalog-declared archives are present.
@NotNullByDefault
public record ImageCacheEntry(
        String repoId,
        String category,
        String name,
        String version,
        String atom,
        long artifactBytes,
        long downloadBytes,
        long partialBytes,
        int partialFiles,
        long lastModifiedMillis,
        Completeness completeness) {
    /// Creates a cache entry without information about the required archives.
    public ImageCacheEntry(
            String repoId,
            String category,
            String name,
            String version,
            String atom,
            long artifactBytes,
            long downloadBytes,
            long partialBytes,
            int partialFiles,
            long lastModifiedMillis) {
        this(repoId, category, name, version, atom, artifactBytes, downloadBytes, partialBytes, partialFiles,
                lastModifiedMillis, Completeness.UNKNOWN);
    }

    /// Whether the archives required by the catalog are present.
    @NotNullByDefault
    public enum Completeness {
        /// All required archives appear present with their expected sizes, when known.
        COMPLETE,
        /// At least one required archive is missing or its size does not match.
        INCOMPLETE,
        /// The cache entry has no available catalog metadata for its archives.
        UNKNOWN
    }

    /// Returns whether the entry holds an incomplete download or leftover files.
    ///
    /// @return whether the entry holds temporary cache data.
    public boolean partial() {
        return partialFiles > 0;
    }

    /// Returns the total size of the entry.
    ///
    /// @return total bytes.
    public long totalBytes() {
        return artifactBytes + downloadBytes + partialBytes;
    }
}
