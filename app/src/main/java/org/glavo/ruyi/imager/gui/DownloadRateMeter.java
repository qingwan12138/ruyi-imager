// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayDeque;

/// Estimates the recent transfer rate of a download from byte-counter samples.
///
/// Using the average rate since the start of the whole image makes the remaining time jump around:
/// a small fast distfile raises the average, and a large slow one then drags it down, so the
/// estimate keeps growing. This meter only looks at a short trailing window, so the reported rate
/// follows the current transfer instead of the history of the whole download.
@NotNullByDefault
final class DownloadRateMeter {
    /// Default length of the trailing window used for the rate.
    private static final long DEFAULT_WINDOW_NANOS = 5_000_000_000L;

    /// Minimum span needed before a rate is reported.
    private static final long MINIMUM_SPAN_NANOS = 1_000_000_000L;

    /// Default interval between retained samples.
    private static final long DEFAULT_BUCKET_NANOS = 200_000_000L;

    /// Length of the trailing window used for the rate.
    private final long windowNanos;

    /// Interval between retained samples.
    private final long bucketNanos;

    /// Samples of elapsed nanoseconds and byte counters, oldest first.
    private final ArrayDeque<long[]> samples = new ArrayDeque<>();

    /// Creates a meter with the default window and sample interval.
    DownloadRateMeter() {
        this(DEFAULT_WINDOW_NANOS, DEFAULT_BUCKET_NANOS);
    }

    /// Creates a meter with an explicit window and the default sample interval.
    ///
    /// @param windowNanos positive trailing window length in nanoseconds.
    DownloadRateMeter(long windowNanos) {
        this(windowNanos, DEFAULT_BUCKET_NANOS);
    }

    /// Creates a meter with an explicit window and sample interval.
    ///
    /// @param windowNanos positive trailing window length in nanoseconds.
    /// @param bucketNanos positive interval between retained samples in nanoseconds.
    DownloadRateMeter(long windowNanos, long bucketNanos) {
        this.windowNanos = windowNanos;
        this.bucketNanos = bucketNanos;
    }

    /// Clears all samples.
    void reset() {
        samples.clear();
    }

    /// Records one byte-counter observation, keeping at most one sample per interval.
    ///
    /// Downloads report progress for every received chunk, so retaining every observation would
    /// let the sample budget cover far less than the window and break the minimum measurement span.
    /// Observations inside the current interval are dropped, which keeps every retained pair of
    /// timestamp and byte counter consistent.
    ///
    /// @param bytes cumulative downloaded bytes.
    /// @param nanos observation timestamp from `System.nanoTime()`.
    void record(long bytes, long nanos) {
        long[] last = samples.peekLast();
        if (last == null || nanos - last[0] >= bucketNanos) {
            samples.addLast(new long[]{nanos, bytes});
        }
        while (samples.size() > 2 && nanos - samples.getFirst()[0] > windowNanos) {
            samples.removeFirst();
        }
    }

    /// Returns the recent transfer rate.
    ///
    /// @return bytes per second, or a negative value when the rate cannot be computed yet.
    double bytesPerSecond() {
        if (samples.size() < 2) {
            return -1.0;
        }
        long[] first = samples.getFirst();
        long[] last = samples.getLast();
        long elapsedNanos = last[0] - first[0];
        if (elapsedNanos < MINIMUM_SPAN_NANOS) {
            return -1.0;
        }
        long bytes = last[1] - first[1];
        if (bytes <= 0L) {
            return -1.0;
        }
        return bytes * 1_000_000_000.0 / elapsedNanos;
    }
}
