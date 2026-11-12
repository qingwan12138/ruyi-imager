// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// A catalog candidate, not an authorization to flash or a guarantee of file completeness.
/// @param image candidate image including its complete component set.
/// @param distfiles declarations matching the selected file's content.
@NotNullByDefault
public record LocalImageMatch(ImageEntry image, @Unmodifiable List<RuyiDistfile> distfiles) {
    /// Copies the matching declarations.
    public LocalImageMatch {
        distfiles = List.copyOf(distfiles);
    }
}
