// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.ProgressReporter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Identifies original distfiles by content without extracting them or contacting a server.
@NotNullByDefault
public final class LocalImageMatcher {
    /// Prevents construction.
    private LocalImageMatcher() {
    }

    /// Returns every matching catalog entry; filenames never establish identity.
    /// @param source regular, nonempty local file.
    /// @param catalog fixed catalog snapshot.
    /// @param reporter cancellable read progress.
    /// @return candidates in catalog order, including incomplete combinations.
    /// @throws IOException when the file cannot be read or changes size.
    public static @Unmodifiable List<LocalImageMatch> match(
            Path source, ImageCatalog catalog, ProgressReporter reporter) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("Not a regular local image file: " + source);
        }
        DistfileIntegrity.checkInterrupted();
        long size = Files.size(source);
        @Unmodifiable List<ImageEntry> candidates = catalog.images().stream()
                .filter(image -> image.distfiles().stream().anyMatch(file ->
                        DistfileIntegrity.hasSupportedChecksum(file)
                                && (file.sizeBytes() == null || file.sizeBytes() == size)))
                .toList();
        if (size == 0 || candidates.isEmpty()) {
            return List.of();
        }
        var fingerprint = DistfileIntegrity.read(source, OutputStream.nullOutputStream(), reporter,
                candidates.stream().flatMap(image -> image.distfiles().stream()).toList());
        List<LocalImageMatch> matches = new ArrayList<>();
        for (ImageEntry image : candidates) {
            @Unmodifiable List<RuyiDistfile> files = image.distfiles().stream()
                    .filter(DistfileIntegrity::hasSupportedChecksum)
                    .filter(file -> DistfileIntegrity.matches(fingerprint, file)).toList();
            if (!files.isEmpty()) {
                matches.add(new LocalImageMatch(image, files));
            }
        }
        return List.copyOf(matches);
    }
}
