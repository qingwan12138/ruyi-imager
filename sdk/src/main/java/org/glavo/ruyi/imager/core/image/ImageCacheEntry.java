// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;

/// One cached image version that can be removed from the download cache.
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
        long lastModifiedMillis) {
    /// Returns whether the entry holds an incomplete download or leftover files.
    ///
    /// @return whether the entry is incomplete.
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
