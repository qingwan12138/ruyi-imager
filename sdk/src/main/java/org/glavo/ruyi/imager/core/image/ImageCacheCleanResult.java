// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;

/// Result of removing entries from the image download cache.
///
/// Files that could not be removed, for example because they are still in use, are reported in
/// [#skipped()] instead of failing the whole operation.
///
/// @param removedBytes bytes that were removed.
/// @param removedFiles files that were removed.
/// @param skipped human-readable descriptions of the paths that could not be removed.
@NotNullByDefault
public record ImageCacheCleanResult(
        long removedBytes,
        int removedFiles,
        @Unmodifiable List<String> skipped) {
    /// Creates a removal result that copies its skipped paths.
    public ImageCacheCleanResult {
        skipped = List.copyOf(skipped);
    }

    /// Creates an empty result.
    ///
    /// @return result without removed or skipped paths.
    public static ImageCacheCleanResult empty() {
        return new ImageCacheCleanResult(0L, 0, List.of());
    }

    /// Merges two results into one.
    ///
    /// @param first first result.
    /// @param second second result.
    /// @return combined result.
    public static ImageCacheCleanResult merge(ImageCacheCleanResult first, ImageCacheCleanResult second) {
        if (second.removedBytes() == 0L && second.removedFiles() == 0 && second.skipped().isEmpty()) {
            return first;
        }
        ArrayList<String> skipped = new ArrayList<>(first.skipped());
        skipped.addAll(second.skipped());
        return new ImageCacheCleanResult(
                first.removedBytes() + second.removedBytes(),
                first.removedFiles() + second.removedFiles(),
                List.copyOf(skipped));
    }
}
