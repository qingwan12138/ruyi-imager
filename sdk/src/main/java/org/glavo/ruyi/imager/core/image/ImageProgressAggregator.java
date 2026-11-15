// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Aggregates per-distfile download progress into whole-image progress.
///
/// A multi-file image downloads its distfiles one after another, and the downloader reports byte
/// counters relative to the file it is currently fetching. This class shifts those counters by the
/// number of bytes already completed and reports them against the declared size of the whole image,
/// so progress never moves backwards when the next distfile starts.
@NotNullByDefault
public final class ImageProgressAggregator {
    /// Prevents construction of the aggregation helper.
    private ImageProgressAggregator() {
    }

    /// Wraps a reporter so that per-distfile byte counters become whole-image counters.
    ///
    /// Events without byte counters, and events of an image with unknown total size, keep their
    /// per-distfile counters because an aggregate percentage cannot be computed for them. Every
    /// event is annotated with the index and count of the distfile it belongs to, so presentation
    /// layers do not have to infer the current file from display text.
    ///
    /// @param downstream reporter receiving aggregated events.
    /// @param completedBytes bytes of already completed distfiles.
    /// @param totalBytes declared size of the whole image, or a non-positive value when unknown.
    /// @param itemIndex one-based index of the distfile being reported.
    /// @param itemCount number of distfiles in the image.
    /// @return reporter for one distfile.
    public static ProgressReporter wrap(
            ProgressReporter downstream,
            long completedBytes,
            long totalBytes,
            int itemIndex,
            int itemCount) {
        return event -> {
            @Nullable Long currentBytes = event.currentBytes();
            @Nullable Long fileTotalBytes = event.totalBytes();
            if (currentBytes == null || fileTotalBytes == null || totalBytes <= 0L) {
                downstream.report(new ProgressEvent(
                        event.stage(),
                        event.message(),
                        currentBytes,
                        fileTotalBytes,
                        itemIndex,
                        itemCount,
                        event.unit()));
                return;
            }
            downstream.report(new ProgressEvent(
                    event.stage(),
                    event.message(),
                    completedBytes + currentBytes,
                    totalBytes,
                    itemIndex,
                    itemCount,
                    event.unit()));
        };
    }

    /// Sums the declared distfile sizes of an image.
    ///
    /// @param image image to inspect.
    /// @return summed size, or a non-positive value when any distfile size is unknown.
    public static long declaredBytes(ImageEntry image) {
        return declaredBytes(image.distfiles());
    }

    /// Sums the declared sizes of the given distfiles.
    ///
    /// @param distfiles distfiles to inspect.
    /// @return summed size, or a non-positive value when any distfile size is unknown.
    public static long declaredBytes(@Unmodifiable List<RuyiDistfile> distfiles) {
        long total = 0L;
        for (RuyiDistfile distfile : distfiles) {
            @Nullable Long size = distfile.sizeBytes();
            if (size == null || size <= 0L) {
                return -1L;
            }
            total += size;
        }
        return total;
    }
}
