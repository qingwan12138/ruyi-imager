// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Result of removing entries from the image download cache.
///
/// Files that could not be removed, for example because they are still in use, are reported in
/// [#skipped()] instead of failing the whole operation.
///
/// @param removedBytes bytes released by unlinking the last file name.
/// @param removedFiles files that were removed.
/// @param skipped human-readable descriptions of the paths that could not be removed.
/// @param retained entries preserved by the cleanup policy.
/// @param legacyResumeCandidates ambiguous old downloads retained by partial-only cleanup.
@NotNullByDefault
public record ImageCacheCleanResult(
        long removedBytes,
        int removedFiles,
        @Unmodifiable List<String> skipped,
        @Unmodifiable List<ImageCacheRetainedEntry> retained,
        @Unmodifiable List<ImageCacheUsage.LegacyResumeCandidate> legacyResumeCandidates) {
    /// Creates a result without legacy resume candidates.
    public ImageCacheCleanResult(long removedBytes, int removedFiles, @Unmodifiable List<String> skipped,
            @Unmodifiable List<ImageCacheRetainedEntry> retained) {
        this(removedBytes, removedFiles, skipped, retained, List.of());
    }

    /// Creates a removal result with immutable path and entry lists.
    public ImageCacheCleanResult {
        skipped = List.copyOf(skipped);
        retained = List.copyOf(retained);
        legacyResumeCandidates = List.copyOf(legacyResumeCandidates);
    }

    /// Creates a removal result without policy-retained entries.
    public ImageCacheCleanResult(long removedBytes, int removedFiles, @Unmodifiable List<String> skipped) {
        this(removedBytes, removedFiles, skipped, List.of());
    }

    /// Creates an empty result.
    ///
    /// @return result without removed or skipped paths.
    public static ImageCacheCleanResult empty() {
        return new ImageCacheCleanResult(0L, 0, List.of());
    }
}
