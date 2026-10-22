// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.StrategySupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests for image entry construction.
@NotNullByDefault
public final class ImageEntryTest {
    /// Verifies that an entry without declared boards falls back to its primary board.
    @Test
    public void derivesPrimaryBoardWhenBoardsAreEmpty() {
        ImageEntry entry = new ImageEntry(
                "ruyisdk",
                "board-image",
                "demo-image",
                "1.0.0",
                null,
                "board-image/demo-image(1.0.0)",
                "Demo image",
                "Demo Manufacturer",
                "demo-board",
                "generic",
                "dd-v1",
                Map.of("disk", "demo.img"),
                List.of(),
                StrategySupport.SUPPORTED,
                List.of(),
                List.of());

        assertEquals(List.of(new ImageBoard("Demo Manufacturer", "demo-board", "generic")), entry.boards());
    }
}
