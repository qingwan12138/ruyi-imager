// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/// Cross-process cache lock that also coordinates holders inside one JVM.
///
/// Opening a second channel to a file that this process already locked and then closing it can
/// release the process-wide POSIX lock. This class therefore tracks held lock paths in memory and
/// refuses a second acquisition before opening another channel to the lock file.
@NotNullByDefault
public final class CacheLock implements AutoCloseable {
    /// Lock paths currently held by this JVM.
    private static final Set<Path> HELD = new HashSet<>();

    /// POSIX channels that must stay open until retried for the same inode or until JVM exit.
    /// Other Java channels can own a process lock without appearing in HELD. Closing a conflicting
    /// channel, even from a background task, could silently release that lock.
    private static final Map<Object, List<FileChannel>> DEFERRED = new HashMap<>();

    /// Whether the deferred-channel worker is running. Guarded by HELD.
    private static boolean reclaimWorkerRunning;

    /// Physical lock path.
    private final Path lockPath;

    /// Open lock file channel.
    private final FileChannel channel;

    /// Held file lock.
    private final FileLock lock;

    /// Whether this guard has been closed, protected by the HELD monitor.
    private boolean closed;

    /// Stable lease paired with a version's compatibility lock.
    private @Nullable CacheLock rootLease;

    /// Creates a held lock.
    ///
    /// @param lockPath physical lock path.
    /// @param channel open lock file channel.
    /// @param lock held file lock.
    private CacheLock(Path lockPath, FileChannel channel, FileLock lock) {
        this.lockPath = lockPath;
        this.channel = channel;
        this.lock = lock;
    }

    /// Acquires one cache lock without blocking.
    ///
    /// @param lockPath lock file path.
    /// @return acquired lock, or null when this JVM or another process already holds it.
    /// @throws IOException when the lock file cannot be opened.
    public static @Nullable CacheLock acquire(Path lockPath) throws IOException {
        Path absolute = lockPath.toAbsolutePath().normalize();
        @Nullable Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Cache lock has no parent: " + lockPath);
        }
        Files.createDirectories(parent);
        Path key = parent.toRealPath().resolve(absolute.getFileName());
        return acquireBound(key, () -> lockIdentity(key), () -> FileChannel.open(
                key, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS));
    }

    /// Acquires both the stable lease and the legacy lock for a managed cache version.
    ///
    /// @param cacheRoot configured cache root, which may be an alias.
    /// @param legacyLock version lock in the configured download tree.
    /// @return held version lock, or null when either lock is busy.
    /// @throws IOException when the cache or a lock cannot be opened.
    public static @Nullable CacheLock acquireVersion(Path cacheRoot, Path legacyLock) throws IOException {
        Path configured = cacheRoot.toAbsolutePath().normalize();
        Path absoluteLock = legacyLock.toAbsolutePath().normalize();
        if (!absoluteLock.startsWith(configured)) {
            throw new IOException("Cache version lock is outside the configured cache: " + legacyLock);
        }
        Path relative = configured.relativize(absoluteLock);
        if (!Files.isDirectory(configured)) {
            Files.createDirectories(configured);
        }
        return CacheTreeRemoval.acquireVersion(configured.toRealPath(), relative);
    }

    /// Transfers the stable lease to a newly acquired compatibility lock.
    void attachRootLease(CacheLock lease) {
        rootLease = lease;
    }

    /// Recognizes an existing version lease without requiring writes or a directory listing.
    static boolean hasEstablishedVersionLease(Path configuredRoot, Path relative) throws IOException {
        Path root;
        Path canonical;
        try {
            root = configuredRoot.toRealPath();
            canonical = canonicalVersionLock(root, relative);
        } catch (IOException _) {
            // Standalone output can traverse aliases that the managed layout does not permit.
            return false;
        }
        for (boolean foldCase : new boolean[]{false, true}) {
            for (boolean normalizeUnicode : new boolean[]{false, true}) {
                Path lease = root.resolve(versionLeaseName(canonical, foldCase, normalizeUnicode));
                if (Files.isRegularFile(lease, LinkOption.NOFOLLOW_LINKS)) {
                    return true;
                }
            }
        }
        return false;
    }

    /// Resolves file-system aliases before deriving the stable lease for an existing or future version.
    static Path versionLeaseName(Path root, Path relative) throws IOException {
        Path canonical = canonicalVersionLock(root, relative);
        CacheTreeRemoval.NameEquivalence equivalence = CacheTreeRemoval.nameEquivalence(root);
        return versionLeaseName(canonical, equivalence.caseInsensitive(), equivalence.normalizationInsensitive());
    }

    /// Resolves the stable lease's canonical lock path without creating an entry.
    static Path canonicalVersionLock(Path root, Path relative) throws IOException {
        validateVersionLockPath(relative);
        String lockName = relative.getFileName().toString();
        Path version = root.resolve(relative).resolveSibling(lockName.substring(0, lockName.length() - ".lock".length()));
        Path realVersion = canonicalCachePath(version);
        if (!realVersion.startsWith(root) || realVersion.getNameCount() != version.getNameCount()) {
            throw new IOException("Cache version resolves outside its expected hierarchy: " + version);
        }
        Path canonicalLock = realVersion.resolveSibling(realVersion.getFileName() + ".lock");
        return root.relativize(canonicalLock);
    }

    /// Canonicalizes existing components and retains the spelling of a component not yet created.
    ///
    /// @param path cache path to canonicalize.
    /// @return physical path of an existing component or a physical parent plus one name.
    /// @throws IOException when an existing component cannot be resolved.
    private static Path canonicalCachePath(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return path.toRealPath();
        }
        @Nullable Path parent = path.getParent();
        return parent == null ? path : canonicalCachePath(parent).resolve(path.getFileName());
    }

    /// Validates the shape of a relative version lock path.
    private static void validateVersionLockPath(Path relative) throws IOException {
        if (relative.isAbsolute() || !relative.equals(relative.normalize())
                || relative.getNameCount() != 5 || !relative.getName(0).toString().equalsIgnoreCase("downloads")
                || relative.getFileName().toString().length() <= ".lock".length()
                || !relative.getFileName().toString().endsWith(".lock")) {
            throw new IOException("Invalid cache version lock path: " + relative);
        }
    }

    /// Maps the version to a root lease using the observed filename equivalences.
    static Path versionLeaseName(Path relative, boolean foldCase, boolean normalizeUnicode) throws IOException {
        validateVersionLockPath(relative);
        String portable = relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
        String cased = foldCase ? portable.toLowerCase(Locale.ROOT) : portable;
        String logicalName = normalizeUnicode ? Normalizer.normalize(cased, Normalizer.Form.NFC) : cased;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(logicalName.getBytes(StandardCharsets.UTF_8));
            return relative.getFileSystem().getPath(".version-lock-" + HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /// Reads the identity of an existing regular lock file.
    private static @Nullable Object lockIdentity(Path key) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    key, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IOException("Expected a regular cache lock file: " + key);
            }
            return attributes.fileKey();
        } catch (NoSuchFileException _) {
            return null;
        }
    }

    /// Reads an entry identity through the same binding used to open it.
    @NotNullByDefault
    @FunctionalInterface
    interface IdentityReader {
        /// Reads the existing lock file identity, or null when unavailable.
        @Nullable Object read() throws IOException;
    }

    /// Opens a channel for a prevalidated entry, possibly relative to a held directory handle.
    @NotNullByDefault
    @FunctionalInterface
    interface ChannelOpener {
        /// Opens the lock channel.
        FileChannel open() throws IOException;
    }

    /// Acquires a lock without reopening a path that the caller has already bound.
    static @Nullable CacheLock acquireBound(Path key, IdentityReader identityReader, ChannelOpener opener)
            throws IOException {
        synchronized (HELD) {
            reclaimDeferred();
            if (isHeld(key)) {
                return null;
            }
            @Nullable Object identity = identityReader.read();
            // When an identity is unavailable, keep the previous channel but do not reuse it by spelling alone.
            Object deferredKey = identity == null ? key : identity;
            if (DEFERRED.containsKey(deferredKey) || DEFERRED.containsKey(key)) {
                return null;
            }
            FileChannel channel = opener.open();
            try {
                @Nullable FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    return null;
                }
                HELD.add(key);
                return new CacheLock(key, channel, lock);
            } catch (OverlappingFileLockException _) {
                if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                    channel.close();
                } else {
                    // Closing this channel would release the other Java channel's process-wide POSIX lock.
                    // The competing channel may have created a file that did not exist at the first lookup.
                    Object observed = deferredKey;
                    if (identity == null) {
                        try {
                            @Nullable Object createdIdentity = identityReader.read();
                            if (createdIdentity != null) {
                                observed = createdIdentity;
                            }
                        } catch (IOException _) {
                            // Keep the channel by path and fail closed when the inode cannot be identified.
                        }
                    }
                    DEFERRED.computeIfAbsent(observed, _ -> new ArrayList<>()).add(channel);
                    startReclaimWorker();
                }
                return null;
            } catch (IOException exception) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                throw exception;
            }
        }
    }

    /// Checks paths and filesystem identities before opening another channel.
    ///
    /// @param key candidate lock path with a resolved parent.
    /// @return whether this JVM already holds that lock.
    /// @throws IOException when an existing lock cannot be identified.
    private static boolean isHeld(Path key) throws IOException {
        if (HELD.contains(key)) {
            return true;
        }
        if (Files.notExists(key, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        for (Path held : HELD) {
            try {
                if (Files.isSameFile(key, held)) {
                    return true;
                }
            } catch (NoSuchFileException _) {
                // A missing path cannot alias an existing lock file.
            }
        }
        return false;
    }

    /// Returns the locked file, used by callers that only need the guard.
    ///
    /// @return held file lock.
    public FileLock fileLock() {
        return lock;
    }

    /// Returns the resolved physical lock path.
    ///
    /// @return lock path with a resolved parent directory.
    Path lockPath() {
        return lockPath;
    }

    /// Returns the number of channels parked for a currently unretryable in-JVM conflict.
    ///
    /// @return parked channel count.
    static int deferredCount() {
        synchronized (HELD) {
            return DEFERRED.values().stream().mapToInt(List::size).sum();
        }
    }

    /// Releases the lock and closes the lock file.
    ///
    /// @throws IOException when the lock file cannot be closed.
    @Override
    public void close() throws IOException {
        synchronized (HELD) {
            if (closed) {
                return;
            }
            closed = true;
            boolean released = false;
            try (@Nullable CacheLock lease = rootLease) {
                try {
                    channel.close();
                    released = true;
                } finally {
                    HELD.remove(lockPath);
                }
            } finally {
                if (released) {
                    reclaimDeferred();
                }
            }
        }
    }

    /// Rechecks parked channels even when the caller never retries the lock.
    private static void startReclaimWorker() {
        if (reclaimWorkerRunning) {
            return;
        }
        reclaimWorkerRunning = true;
        Thread.ofPlatform().daemon().name("ruyi-imager-cache-lock-reaper").start(() -> {
            try {
                while (true) {
                    Thread.sleep(1_000L);
                    synchronized (HELD) {
                        reclaimDeferred();
                        if (DEFERRED.isEmpty()) {
                            return;
                        }
                    }
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                synchronized (HELD) {
                    reclaimWorkerRunning = false;
                    if (!DEFERRED.isEmpty()) {
                        startReclaimWorker();
                    }
                }
            }
        });
    }

    /// Closes parked channels after obtaining a JVM lock on the same file.
    private static void reclaimDeferred() {
        Iterator<List<FileChannel>> groups = DEFERRED.values().iterator();
        while (groups.hasNext()) {
            List<FileChannel> group = groups.next();
            Iterator<FileChannel> channels = group.iterator();
            while (channels.hasNext()) {
                FileChannel parked = channels.next();
                try {
                    if (parked.tryLock() != null) {
                        channels.remove();
                        parked.close();
                    }
                } catch (OverlappingFileLockException | IOException _) {
                    // A live owner or an inaccessible channel must not be disturbed.
                }
            }
            if (group.isEmpty()) {
                groups.remove();
            }
        }
    }
}
