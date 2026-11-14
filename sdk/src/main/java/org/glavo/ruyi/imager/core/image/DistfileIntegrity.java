// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressUnit;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.SdkMessages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/// Shared size and digest verification for downloaded and imported files.
@NotNullByDefault
final class DistfileIntegrity {
    /// Prevents construction.
    private DistfileIntegrity() {
    }

    /// Returns whether a declaration has a supported digest.
    static boolean hasSupportedChecksum(RuyiDistfile file) {
        return checksum(file, "sha256") != null || checksum(file, "sha512") != null;
    }

    /// Returns a nonblank checksum, or null when absent.
    private static @Nullable String checksum(RuyiDistfile file, String algorithm) {
        @Nullable String value = file.checksums().get(algorithm);
        return value == null || value.isBlank() ? null : value;
    }

    /// Checks all declared supported digests and the optional size.
    static boolean matches(Fingerprint actual, RuyiDistfile file) {
        @Nullable String sha256 = checksum(file, "sha256");
        @Nullable String sha512 = checksum(file, "sha512");
        return (file.sizeBytes() == null || file.sizeBytes() == actual.size())
                && (sha256 == null || sha256.equalsIgnoreCase(actual.sha256()))
                && (sha512 == null || sha512.equalsIgnoreCase(actual.sha512()));
    }

    /// Checks a regular file without requiring a digest for legacy restricted downloads.
    static boolean verify(Path path, RuyiDistfile file) throws IOException {
        if (!Files.isRegularFile(path)
                || (file.sizeBytes() != null && Files.size(path) != file.sizeBytes())) {
            return false;
        }
        if (!hasSupportedChecksum(file)) {
            return true;
        }
        return matches(read(path, OutputStream.nullOutputStream(), _ -> { }, List.of(file)), file);
    }

    /// Reads required digests in one cancellable pass, optionally copying into a caller-owned stream.
    /// The initial file size bounds the read even if the source grows during the operation.
    static Fingerprint read(Path path, OutputStream output, ProgressReporter reporter, List<RuyiDistfile> declarations) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException(SdkMessages.get("core.flash.localImageMissing", path));
        }
        long size = Files.size(path);
        @Nullable MessageDigest sha256 = declarations.stream().anyMatch(file -> checksum(file, "sha256") != null)
                ? digest("SHA-256") : null;
        @Nullable MessageDigest sha512 = declarations.stream().anyMatch(file -> checksum(file, "sha512") != null)
                ? digest("SHA-512") : null;
        long count = 0;
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[128 * 1024];
            while (true) {
                checkInterrupted();
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read > size - count) {
                    throw new IOException(SdkMessages.get("core.local.changed", path));
                }
                output.write(buffer, 0, read);
                if (sha256 != null) {
                    sha256.update(buffer, 0, read);
                }
                if (sha512 != null) {
                    sha512.update(buffer, 0, read);
                }
                count += read;
                reporter.report(new ProgressEvent("import", SdkMessages.get("core.local.reading", path.getFileName()), count, size, ProgressUnit.BYTES));
            }
        }
        checkInterrupted();
        if (count != size) {
            throw new IOException(SdkMessages.get("core.local.changed", path));
        }
        return new Fingerprint(count, sha256 == null ? null : HexFormat.of().formatHex(sha256.digest()),
                sha512 == null ? null : HexFormat.of().formatHex(sha512.digest()));
    }

    /// Stops before publishing a cancelled read or import.
    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException(SdkMessages.get("core.local.interrupted"));
        }
    }

    /// Creates a supported digest implementation.
    private static MessageDigest digest(String algorithm) throws IOException {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(SdkMessages.get("core.download.missingDigest", algorithm), e);
        }
    }

    /// Content identity from one read of a file.
    /// @param size observed byte count.
    /// @param sha256 SHA-256 digest, or null when not requested.
    /// @param sha512 SHA-512 digest, or null when not requested.
    @NotNullByDefault
    record Fingerprint(long size, @Nullable String sha256, @Nullable String sha512) {
    }
}
