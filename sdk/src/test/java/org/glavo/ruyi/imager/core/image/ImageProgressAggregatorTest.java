// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.ProgressUnit;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests whole-image progress aggregation across distfiles.
@NotNullByDefault
public final class ImageProgressAggregatorTest {
    /// Shifts per-distfile byte counters by the bytes of already completed distfiles.
    @Test
    public void shiftsCountersToWholeImageProgress() {
        List<ProgressEvent> events = new ArrayList<>();
        ProgressReporter aggregated = ImageProgressAggregator.wrap(events::add, 100L, 1000L, 2, 3);
        aggregated.report(new ProgressEvent("download", "Downloading first", 20L, 200L, ProgressUnit.BYTES));
        aggregated.report(new ProgressEvent("download", "Downloading first", 200L, 200L, ProgressUnit.BYTES));
        assertEquals(
                List.of(
                        new ProgressEvent("download", "Downloading first", 120L, 1000L, 2, 3, ProgressUnit.BYTES),
                        new ProgressEvent("download", "Downloading first", 300L, 1000L, 2, 3, ProgressUnit.BYTES)),
                events);
    }

    /// Forwards events whose byte counters cannot be aggregated.
    @Test
    public void forwardsEventsWithoutByteCounters() {
        List<ProgressEvent> events = new ArrayList<>();
        ProgressEvent cached = new ProgressEvent("download", "Using cached file", null, null);
        ImageProgressAggregator.wrap(events::add, 100L, -1L, 1, 2).report(cached);
        ProgressEvent preparing = new ProgressEvent("materialize", "Preparing image", null, null);
        ImageProgressAggregator.wrap(events::add, 100L, 1000L, 1, 2).report(preparing);
        assertEquals(
                List.of(
                        new ProgressEvent("download", "Using cached file", null, null, 1, 2, ProgressUnit.NONE),
                        new ProgressEvent("materialize", "Preparing image", null, null, 1, 2, ProgressUnit.NONE)),
                events);
    }

    /// Sums declared distfile sizes and reports unknown sizes with a marker value.
    @Test
    public void sumsDeclaredDistfileSizes() {
        assertEquals(300L, ImageProgressAggregator.declaredBytes(List.of(distfile(100L), distfile(200L))));
        assertEquals(-1L, ImageProgressAggregator.declaredBytes(List.of(distfile(100L), distfile(null))));
    }

    /// Creates one distfile declaration with the given declared size.
    ///
    /// @param sizeBytes declared size, or null when absent.
    /// @return distfile declaration.
    private static RuyiDistfile distfile(@Nullable Long sizeBytes) {
        return new RuyiDistfile(
                "file",
                List.of(URI.create("https://example.com/file")),
                sizeBytes,
                Map.of(),
                false,
                false,
                null,
                0,
                List.of(),
                null);
    }
}
