// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.cli;

import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressUnit;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/// Prints human-readable CLI progress with whole-percentage throttling.
///
/// Downloads report progress for every received chunk, which produces hundreds of identical lines
/// for a large image. This printer keeps one line per 5% step and one line whenever the reported
/// file changes, so long downloads stay readable without hiding the progress. Byte counters of a
/// distfile without a declared size have no percentage, so they are throttled by elapsed time and
/// received bytes instead. Counter units are preserved: byte counters are formatted as sizes, step
/// counters keep their percentage only, and stages that report a completion sentinel print their
/// message alone.
@NotNullByDefault
final class CliProgressPrinter {
    /// Size units used by the progress line.
    private static final List<String> SIZE_UNITS = List.of("B", "KiB", "MiB", "GiB", "TiB");

    /// Percentage gap between printed progress lines.
    private static final int PERCENT_STEP = 5;

    /// Minimum interval between printed lines when the total size is unknown.
    private static final long UNKNOWN_TOTAL_INTERVAL_NANOS = 1_000_000_000L;

    /// Minimum received bytes between printed lines when the total size is unknown.
    private static final long UNKNOWN_TOTAL_BYTE_STEP = 4L * 1024L * 1024L;

    /// Output stream receiving the progress lines.
    private final PrintStream err;

    /// Clock used to throttle progress with an unknown total size.
    private final LongSupplier nanoClock;

    /// Message of the last reported file, or null before the first event.
    private @Nullable String lastMessage;

    /// Percentage of the last printed line, or a negative value before the first line.
    private int lastPercent = -1;

    /// Bytes reported by the last unknown-total line, or a negative value when none was printed.
    private long lastUnknownBytes = -1L;

    /// Timestamp of the last unknown-total line.
    private long lastUnknownNanos;

    /// Creates a printer for the given stream.
    ///
    /// @param err stream receiving human-readable progress lines.
    CliProgressPrinter(PrintStream err) {
        this(err, System::nanoTime);
    }

    /// Creates a printer for the given stream and clock.
    ///
    /// @param err stream receiving human-readable progress lines.
    /// @param nanoClock clock used to throttle progress with an unknown total size.
    CliProgressPrinter(PrintStream err, LongSupplier nanoClock) {
        this.err = err;
        this.nanoClock = nanoClock;
    }

    /// Prints one progress event.
    ///
    /// @param event progress event to print.
    void report(ProgressEvent event) {
        @Nullable Long currentBytes = event.currentBytes();
        @Nullable Long totalBytes = event.totalBytes();
        String message = event.message();
        String stage = event.stage();
        boolean newMessage = !message.equals(lastMessage);
        if (newMessage) {
            lastMessage = message;
            lastPercent = -1;
            lastUnknownBytes = -1L;
        }
        if (currentBytes == null || totalBytes == null || totalBytes <= 0L) {
            if (event.unit() == ProgressUnit.BYTES && currentBytes != null) {
                reportUnknownTotal(stage, message, currentBytes, newMessage);
            } else {
                err.printf("%s: %s%n", stage, message);
            }
            return;
        }
        int percent = (int) Math.min(100L, Math.max(0L, currentBytes * 100L / totalBytes));
        if (percent < lastPercent) {
            // A retry or a restarted download moved the counter backwards, so throttling restarts.
            lastPercent = percent - PERCENT_STEP;
        }
        if (!newMessage && percent < lastPercent + PERCENT_STEP && percent != 100) {
            return;
        }
        lastPercent = percent;
        switch (event.unit()) {
            case BYTES -> err.printf(
                    "%s: %s %d%% (%s / %s)%n",
                    stage,
                    message,
                    percent,
                    sizeLabel(currentBytes),
                    sizeLabel(totalBytes));
            case STEPS -> err.printf("%s: %s %d%%%n", stage, message, percent);
            default -> err.printf("%s: %s%n", stage, message);
        }
    }

    /// Prints one byte-counter line whose total size is unknown.
    ///
    /// @param stage stable stage identifier.
    /// @param message human-readable progress message.
    /// @param currentBytes received bytes.
    /// @param newMessage whether the message changed since the last event.
    private void reportUnknownTotal(String stage, String message, long currentBytes, boolean newMessage) {
        long now = nanoClock.getAsLong();
        if (!newMessage
                && lastUnknownBytes >= 0L
                && currentBytes >= lastUnknownBytes
                && currentBytes - lastUnknownBytes < UNKNOWN_TOTAL_BYTE_STEP
                && now - lastUnknownNanos < UNKNOWN_TOTAL_INTERVAL_NANOS) {
            return;
        }
        lastUnknownBytes = currentBytes;
        lastUnknownNanos = now;
        err.printf("%s: %s (%s)%n", stage, message, sizeLabel(currentBytes));
    }

    /// Formats a byte count for the progress line.
    ///
    /// @param bytes byte count.
    /// @return human-readable size.
    private static String sizeLabel(long bytes) {
        double value = bytes;
        int unitIndex = 0;
        while (value >= 1024.0 && unitIndex < SIZE_UNITS.size() - 1) {
            value /= 1024.0;
            unitIndex++;
        }
        if (unitIndex == 0) {
            return String.format(Locale.ROOT, "%d %s", bytes, SIZE_UNITS.get(unitIndex));
        }
        return String.format(Locale.ROOT, "%.1f %s", value, SIZE_UNITS.get(unitIndex));
    }
}
