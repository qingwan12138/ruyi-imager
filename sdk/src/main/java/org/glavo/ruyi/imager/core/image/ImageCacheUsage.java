// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Current size and contents of the image download cache.
///
/// @param entries one entry per cached image version, newest modification first.
/// @param downloadBytes bytes of completed download archives.
/// @param artifactBytes bytes of materialized images.
/// @param partialBytes bytes of incomplete downloads and leftover directories.
/// @param partialFiles number of incomplete downloads and leftover directories.
/// @param repositoryBytes bytes of the metadata repositories.
/// @param logBytes bytes of the application logs.
/// @param unreadable paths that could not be read while measuring.
/// @param retained entries that a bulk cleanup leaves for explicit selection.
@NotNullByDefault
public record ImageCacheUsage(
        @Unmodifiable List<ImageCacheEntry> entries,
        long downloadBytes,
        long artifactBytes,
        long partialBytes,
        int partialFiles,
        long repositoryBytes,
        long logBytes,
        @Unmodifiable List<String> unreadable,
        @Unmodifiable List<ImageCacheRetainedEntry> retained) {
    /// Creates a usage snapshot that copies its lists.
    public ImageCacheUsage {
        entries = List.copyOf(entries);
        unreadable = List.copyOf(unreadable);
        retained = List.copyOf(retained);
    }

    /// Creates a usage snapshot without entries retained by a bulk cleanup.
    ///
    /// @param entries one entry per cached image version.
    /// @param downloadBytes bytes of completed download archives.
    /// @param artifactBytes bytes of materialized images.
    /// @param partialBytes bytes of incomplete downloads and leftover directories.
    /// @param partialFiles number of incomplete downloads and leftover directories.
    /// @param repositoryBytes bytes of the metadata repositories.
    /// @param logBytes bytes of the application logs.
    /// @param unreadable paths that could not be read while measuring.
    public ImageCacheUsage(
            @Unmodifiable List<ImageCacheEntry> entries,
            long downloadBytes,
            long artifactBytes,
            long partialBytes,
            int partialFiles,
            long repositoryBytes,
            long logBytes,
            @Unmodifiable List<String> unreadable) {
        this(entries, downloadBytes, artifactBytes, partialBytes, partialFiles, repositoryBytes, logBytes,
                unreadable, List.of());
    }

    /// Returns the total size of the image download cache, excluding metadata repositories and logs.
    ///
    /// @return total bytes.
    public long totalBytes() {
        return downloadBytes + artifactBytes + partialBytes;
    }
}
