// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.SdkMessages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/// Holds the distinct managed versions that supply or receive files during one operation.
@NotNullByDefault
final class CacheInputLeases implements AutoCloseable {
    /// Resolved sources in caller order, to avoid following a different alias after taking leases.
    private final @Unmodifiable List<Path> inputs;

    /// Leases in acquisition order.
    private final List<CacheLock> locks = new ArrayList<>();

    /// Creates the guard for an already resolved input list.
    private CacheInputLeases(@Unmodifiable List<Path> inputs) {
        this.inputs = inputs;
    }

    /// Locks the target and every source version in stable lease-key order.
    static CacheInputLeases acquire(Path root, Path target, List<Path> inputs) throws IOException {
        List<Path> resolved = new ArrayList<>(inputs.size());
        Set<Path> versions = new LinkedHashSet<>();
        versions.add(root.relativize(target));
        for (Path path : inputs) {
            Path source = path.toRealPath();
            resolved.add(source);
            @Nullable Path version = sourceVersion(root, source);
            if (version != null) {
                versions.add(version);
            }
        }

        var leases = new CacheInputLeases(List.copyOf(resolved));
        try {
            if (versions.size() == 1) {
                leases.acquireVersion(root, versions.iterator().next());
            } else {
                var ordered = new TreeMap<Path, Path>();
                for (Path version : versions) {
                    ordered.putIfAbsent(CacheTreeRemoval.versionLeaseKey(root, version), version);
                }
                for (Path version : ordered.values()) {
                    leases.acquireVersion(root, version);
                }
            }
            return leases;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                leases.close();
            } catch (IOException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    /// Returns the compatibility lock for a source in one of the managed trees.
    private static @Nullable Path sourceVersion(Path root, Path source) throws IOException {
        @Nullable Path managed = ImageCacheService.resolveCacheTreeSource(root, source);
        if (managed == null) {
            return null;
        }
        Path relative = root.relativize(managed);
        if (relative.getNameCount() < 6) {
            return null;
        }
        Path packagePath = relative.subpath(1, 4);
        String version = relative.getName(4).toString();
        if (relative.getName(0).toString().equals("artifacts") && RuyiImageMaterializer.isWorkingArtifactDirectory(version)) {
            int prefix = version.startsWith(RuyiImageMaterializer.STAGING_PREFIX)
                    ? RuyiImageMaterializer.STAGING_PREFIX.length() : RuyiImageMaterializer.BACKUP_PREFIX.length();
            int suffix = version.lastIndexOf('-');
            if (suffix > prefix) {
                version = version.substring(prefix, suffix);
            }
        }
        return root.getFileSystem().getPath("downloads").resolve(packagePath).resolve(version + ".lock");
    }

    /// Acquires one version without accepting a lease already held by another operation.
    private void acquireVersion(Path root, Path version) throws IOException {
        @Nullable CacheLock lock = CacheTreeRemoval.acquireVersion(root, version);
        if (lock == null) {
            throw new IOException(SdkMessages.get("core.local.cacheBusy"));
        }
        locks.add(lock);
    }

    /// Returns the paths corresponding to the original inputs in the same order.
    @Unmodifiable List<Path> inputs() {
        return inputs;
    }

    /// Releases all acquired leases, even when closing an earlier lease fails.
    @Override
    public void close() throws IOException {
        @Nullable IOException failure = null;
        for (CacheLock lease : locks.reversed()) {
            try {
                lease.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
