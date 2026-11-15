// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests the trailing-window download rate meter.
@NotNullByDefault
public final class DownloadRateMeterTest {
    /// Reports no rate until the retained samples span at least one second.
    @Test
    public void reportsNothingBeforeTheMinimumSpan() {
        DownloadRateMeter meter = new DownloadRateMeter();
        assertEquals(-1.0, meter.bytesPerSecond());
        meter.record(0L, 0L);
        assertEquals(-1.0, meter.bytesPerSecond());
        meter.record(1024L, 500_000_000L);
        assertEquals(-1.0, meter.bytesPerSecond());
    }

    /// Reports the rate measured over the retained samples.
    @Test
    public void reportsWindowedRate() {
        DownloadRateMeter meter = new DownloadRateMeter();
        meter.record(0L, 0L);
        meter.record(1_048_576L, 1_000_000_000L);
        assertEquals(1_048_576.0, meter.bytesPerSecond(), 1.0);
        meter.record(2_097_152L, 2_000_000_000L);
        assertEquals(1_048_576.0, meter.bytesPerSecond(), 1.0);
    }

    /// Drops samples that fall out of the window so a fast prologue does not inflate the rate.
    @Test
    public void forgetsSamplesOutsideTheWindow() {
        DownloadRateMeter meter = new DownloadRateMeter(2_000_000_000L);
        long mib = 1024L * 1024L;
        meter.record(0L, 0L);
        meter.record(30L * mib, 100_000_000L);
        meter.record(30L * mib, 1_000_000_000L);
        meter.record(31L * mib, 3_000_000_000L);
        assertEquals(mib / 2.0, meter.bytesPerSecond(), 1.0);
    }

    /// Computes a rate from samples that arrive far more often than the window allows.
    @Test
    public void computesRateFromHighFrequencyEvents() {
        DownloadRateMeter meter = new DownloadRateMeter();
        long bytesPerEvent = 2048L;
        for (int index = 0; index <= 1000; index++) {
            meter.record(index * bytesPerEvent, index * 2_000_000L);
        }
        assertEquals(bytesPerEvent * 500.0, meter.bytesPerSecond(), 2048.0);
    }

    /// Forgets every sample when reset.
    @Test
    public void resetClearsSamples() {
        DownloadRateMeter meter = new DownloadRateMeter();
        meter.record(0L, 0L);
        meter.record(1_048_576L, 1_000_000_000L);
        meter.reset();
        assertEquals(-1.0, meter.bytesPerSecond());
    }
}
