// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;

/// One board a catalog image can be provisioned for.
///
/// @param manufacturer manufacturer owning the board, or [#UNKNOWN_MANUFACTURER] when the metadata does not identify one.
/// @param board board (device) id.
/// @param variant board variant, or `generic` when the metadata does not declare one.
@NotNullByDefault
public record ImageBoard(String manufacturer, String board, String variant) {
    /// Manufacturer value used when a board's device id does not map to a known manufacturer.
    ///
    /// The value is not a display name; it identifies the group of boards whose manufacturer the
    /// metadata does not state, so those boards stay selectable instead of being listed under the
    /// manufacturer of the image that declares them.
    public static final String UNKNOWN_MANUFACTURER = "unknown";
}
