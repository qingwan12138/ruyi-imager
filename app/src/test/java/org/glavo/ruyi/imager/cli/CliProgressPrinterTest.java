// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.cli;

import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressUnit;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests the throttled CLI progress printer.
@NotNullByDefault
public final class CliProgressPrinterTest {
    /// Prints one line per whole percentage step instead of one line per received chunk.
    @Test
    public void throttlesByteProgressToPercentageSteps() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CliProgressPrinter printer = new CliProgressPrinter(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        for (int percent = 1; percent <= 100; percent++) {
            printer.report(new ProgressEvent("download", "Downloading image", (long) percent, 100L, ProgressUnit.BYTES));
        }
        List<String> lines = buffer.toString(StandardCharsets.UTF_8).lines().toList();
        // The first event of a file always prints, then every fifth percentage point and 100%.
        assertEquals(21, lines.size());
        assertEquals("download: Downloading image 1% (1 B / 100 B)", lines.getFirst());
        assertEquals("download: Downloading image 100% (100 B / 100 B)", lines.getLast());
    }

    /// Prints a line immediately when the reported distfile changes.
    @Test
    public void printsNewDistfileImmediately() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CliProgressPrinter printer = new CliProgressPrinter(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        printer.report(new ProgressEvent("download", "Downloading first", 1L, 100L, ProgressUnit.BYTES));
        printer.report(new ProgressEvent("download", "Downloading first", 2L, 100L, ProgressUnit.BYTES));
        printer.report(new ProgressEvent("download", "Downloading second", 1L, 100L, ProgressUnit.BYTES));
        printer.report(new ProgressEvent("download", "Downloading second", 2L, 100L, ProgressUnit.BYTES));
        List<String> lines = buffer.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(2, lines.size());
        assertEquals("download: Downloading first 1% (1 B / 100 B)", lines.getFirst());
        assertEquals("download: Downloading second 1% (1 B / 100 B)", lines.getLast());
    }

    /// Prints events without counters as plain lines.
    @Test
    public void printsIndeterminateEvents() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CliProgressPrinter printer = new CliProgressPrinter(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        printer.report(new ProgressEvent("materialize", "Preparing image", null, null));
        List<String> lines = buffer.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(1, lines.size());
        assertEquals("materialize: Preparing image", lines.getFirst());
    }

    /// Restarts throttling when a retry moves the counter backwards.
    @Test
    public void restartsThrottlingAfterProgressRegression() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CliProgressPrinter printer = new CliProgressPrinter(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        printer.report(new ProgressEvent("download", "Downloading image", 100L, 100L, ProgressUnit.BYTES));
        printer.report(new ProgressEvent("download", "Downloading image", 0L, 100L, ProgressUnit.BYTES));
        List<String> lines = buffer.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(2, lines.size());
        assertEquals("download: Downloading image 100% (100 B / 100 B)", lines.getFirst());
        assertEquals("download: Downloading image 0% (0 B / 100 B)", lines.getLast());
    }

    /// Throttles byte progress whose total size is unknown.
    @Test
    public void throttlesByteProgressWithUnknownTotal() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long[] now = {0L};
        CliProgressPrinter printer = new CliProgressPrinter(
                new PrintStream(buffer, true, StandardCharsets.UTF_8), () -> now[0]);
        long mib = 1024L * 1024L;
        // Ten events one MiB apart within a second: the first prints, then every four MiB.
        for (int index = 0; index < 10; index++) {
            now[0] = index * 100_000_000L;
            printer.report(new ProgressEvent("download", "Downloading image", index * mib, null, ProgressUnit.BYTES));
        }
        assertEquals(3, buffer.toString(StandardCharsets.UTF_8).lines().count());
        // After one second another line is printed even without a large byte jump.
        now[0] = 2_000_000_000L;
        printer.report(new ProgressEvent("download", "Downloading image", 10 * mib, null, ProgressUnit.BYTES));
        assertEquals(4, buffer.toString(StandardCharsets.UTF_8).lines().count());
        // A large byte jump prints immediately.
        now[0] = 2_100_000_000L;
        printer.report(new ProgressEvent("download", "Downloading image", 20 * mib, null, ProgressUnit.BYTES));
        assertEquals(5, buffer.toString(StandardCharsets.UTF_8).lines().count());
    }

    /// A new distfile resets unknown-total throttling and prints immediately.
    @Test
    public void resetsUnknownTotalThrottlingForNewDistfile() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long[] now = {0L};
        CliProgressPrinter printer = new CliProgressPrinter(
                new PrintStream(buffer, true, StandardCharsets.UTF_8), () -> now[0]);
        printer.report(new ProgressEvent("download", "Downloading first", 1024L, null, ProgressUnit.BYTES));
        printer.report(new ProgressEvent("download", "Downloading first", 2048L, null, ProgressUnit.BYTES));
        printer.report(new ProgressEvent("download", "Downloading second", 1024L, null, ProgressUnit.BYTES));
        assertEquals(2, buffer.toString(StandardCharsets.UTF_8).lines().count());
    }

    /// Completion events are printed even while byte progress is throttled.
    @Test
    public void printsCompletionEventsWhileThrottling() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long[] now = {0L};
        CliProgressPrinter printer = new CliProgressPrinter(
                new PrintStream(buffer, true, StandardCharsets.UTF_8), () -> now[0]);
        printer.report(new ProgressEvent("download", "Downloading image", 1024L, null, ProgressUnit.BYTES));
        printer.report(new ProgressEvent("download", "Downloaded image", null, null));
        assertEquals(2, buffer.toString(StandardCharsets.UTF_8).lines().count());
    }

    /// Prints step counters as percentages without formatting them as byte sizes.
    @Test
    public void printsStepProgressWithoutByteSizes() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CliProgressPrinter printer = new CliProgressPrinter(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        printer.report(new ProgressEvent("fastboot", "Sending root", 500L, 1000L, ProgressUnit.STEPS));
        List<String> lines = buffer.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(1, lines.size());
        assertEquals("fastboot: Sending root 50%", lines.getFirst());
    }

    /// Prints completion sentinels without percentages or byte sizes.
    @Test
    public void printsCompletionSentinelWithoutCounters() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CliProgressPrinter printer = new CliProgressPrinter(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        printer.report(new ProgressEvent("prepare", "Target device prepared", 1L, 1L, ProgressUnit.NONE));
        List<String> lines = buffer.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(1, lines.size());
        assertEquals("prepare: Target device prepared", lines.getFirst());
    }
}
