// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// A manufacturer, board, and variant associated with an image.
///
/// @param manufacturer device or image manufacturer name.
/// @param board target board name.
/// @param variant board or image variant name.
@NotNullByDefault
public record ImageTarget(String manufacturer, String board, String variant) {
    /// Returns whether this target matches both supplied filters; null filters match any value.
    ///
    /// @param manufacturerName manufacturer filter, or null.
    /// @param boardName board filter, or null.
    /// @return whether both filters match this same target.
    public boolean matches(@Nullable String manufacturerName, @Nullable String boardName) {
        return (manufacturerName == null || manufacturer.equals(manufacturerName))
                && (boardName == null || board.equals(boardName));
    }
}
