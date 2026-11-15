// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Progress event emitted by long-running services.
///
/// @param stage stable stage identifier.
/// @param message human-readable progress message.
/// @param currentBytes current byte count when available.
/// @param totalBytes total byte count when available.
/// @param itemIndex one-based index of the item being processed, or null when the event is not tied to one item.
/// @param itemCount number of items in the operation, or null when the event is not tied to a known item count.
/// @param unit unit of the counters, or `NONE` when they carry no useful magnitude.
@NotNullByDefault
public record ProgressEvent(
        String stage,
        String message,
        @Nullable Long currentBytes,
        @Nullable Long totalBytes,
        @Nullable Integer itemIndex,
        @Nullable Integer itemCount,
        ProgressUnit unit) {
    /// Creates a progress event without item information and without a counter unit.
    ///
    /// @param stage stable stage identifier.
    /// @param message human-readable progress message.
    /// @param currentBytes current byte count when available.
    /// @param totalBytes total byte count when available.
    public ProgressEvent(String stage, String message, @Nullable Long currentBytes, @Nullable Long totalBytes) {
        this(stage, message, currentBytes, totalBytes, null, null, ProgressUnit.NONE);
    }

    /// Creates a progress event with a counter unit but without item information.
    ///
    /// @param stage stable stage identifier.
    /// @param message human-readable progress message.
    /// @param currentBytes current counter when available.
    /// @param totalBytes total counter when available.
    /// @param unit unit of the counters.
    public ProgressEvent(
            String stage,
            String message,
            @Nullable Long currentBytes,
            @Nullable Long totalBytes,
            ProgressUnit unit) {
        this(stage, message, currentBytes, totalBytes, null, null, unit);
    }

    /// Creates an indeterminate progress event.
    ///
    /// @param stage stable stage identifier.
    /// @param message human-readable progress message.
    /// @return progress event without byte counters.
    public static ProgressEvent indeterminate(String stage, String message) {
        return new ProgressEvent(stage, message, null, null);
    }
}
