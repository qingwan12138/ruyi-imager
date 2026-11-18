// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests in-process cache lock coordination.
@NotNullByDefault
public final class CacheLockTest {
    /// Equivalent Unicode path spellings use the same stable lease.
    @Test
    public void normalizesUnicodeLeaseNames() throws IOException {
        Path composed = Path.of("downloads/ruyisdk/board-image/caf\u00e9/1.0.0.lock");
        Path decomposed = Path.of("downloads/ruyisdk/board-image/cafe\u0301/1.0.0.lock");
        assertEquals(CacheLock.versionLeaseName(composed, false, true), CacheLock.versionLeaseName(decomposed, false, true));
    }

    /// Rejects a lock file that is a symbolic link.
    @Test
    public void rejectsLinkedLockFiles(@TempDir Path directory) throws IOException {
        Path target = Files.createFile(directory.resolve("target"));
        Path link = directory.resolve("version.lock");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }

        assertThrows(IOException.class, () -> {
            try (@Nullable CacheLock ignored = CacheLock.acquire(link)) {
                // Opening a lock through the link should fail.
            }
        });
    }

    /// Treats two paths through the same cache directory as one version lock.
    @Test
    public void coordinatesDirectoryAliases(@TempDir Path directory) throws IOException {
        Path cache = Files.createDirectory(directory.resolve("cache"));
        Path alias = directory.resolve("alias");
        try {
            Files.createSymbolicLink(alias, cache);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }

        try (@Nullable CacheLock first = CacheLock.acquire(cache.resolve("version.lock"))) {
            assertNotNull(first);
            assertNull(CacheLock.acquire(alias.resolve("version.lock")));
        }
        try (@Nullable CacheLock next = CacheLock.acquire(alias.resolve("version.lock"))) {
            assertNotNull(next);
        }
    }

    /// Keeps the operating-system lock when acquisition through a hard link is refused.
    @Test
    public void preservesProcessLockAcrossHardLinkContention(@TempDir Path directory) throws Exception {
        Path lockPath = Files.createFile(directory.resolve("version.lock"));
        Path alias = directory.resolve("alias.lock");
        try {
            Files.createLink(alias, lockPath);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "hard links are unavailable");
        }
        try (@Nullable CacheLock first = CacheLock.acquire(lockPath)) {
            assertNotNull(first);
            assertNull(CacheLock.acquire(alias));
            assertExternalLockBlocked(directory, lockPath);
        }
    }

    /// A raw Java owner retains its operating-system lock after a conflicting SDK attempt.
    @Test
    public void preservesRawJavaLockAcrossContention(@TempDir Path directory) throws Exception {
        Path lockPath = Files.createFile(directory.resolve("version.lock"));
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertNull(CacheLock.acquire(lockPath));
            assertNull(CacheLock.acquire(lockPath));
            assertExternalLockBlocked(directory, lockPath);
        }
        try (@Nullable CacheLock next = CacheLock.acquire(lockPath)) {
            assertNotNull(next);
        }
    }

    /// Checks the operating-system lock independently of the JVM that owns it.
    private static void assertExternalLockBlocked(Path directory, Path lockPath) throws Exception {
        Path probe = directory.resolve("LockProbe.java");
        Files.writeString(probe, """
                import java.nio.channels.FileChannel;
                import java.nio.file.Path;
                import java.nio.file.StandardOpenOption;
                class LockProbe {
                    void main(String[] arguments) throws Exception {
                        try (var channel = FileChannel.open(Path.of(arguments[0]), StandardOpenOption.WRITE);
                             var lock = channel.tryLock()) {
                            if (lock != null) {
                                throw new AssertionError("Another process acquired the held version lock");
                            }
                        }
                    }
                }
                """);

        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process child = new ProcessBuilder(java.toString(), probe.toString(), lockPath.toString())
                .redirectErrorStream(true)
                .start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "lock probe timed out");
            assertEquals(0, child.exitValue(), new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            child.destroyForcibly();
        }
    }

    /// Refuses a second lock and frees the registry even if the lease's channel was closed earlier.
    ///
    /// @param directory temporary test directory.
    /// @throws IOException when the lock file cannot be created.
    @Test
    public void refusesSecondLockFromSameJvm(@TempDir Path directory) throws IOException {
        Path lockPath = directory.resolve("version.lock");

        try (@Nullable CacheLock first = CacheLock.acquire(lockPath)) {
            assertNotNull(first);
            assertNull(CacheLock.acquire(lockPath));
            first.fileLock().channel().close();
        }

        try (@Nullable CacheLock third = CacheLock.acquire(lockPath)) {
            assertNotNull(third);
        }
    }

    @Test
    void leaseNameFoldsCaseOnlyOnCaseInsensitiveVolumes(@TempDir Path directory) throws IOException {
        // P3:159 大小写敏感卷上 1.0-RC 与 1.0-rc 必须是两个不同的租约。
        Path upper = Path.of("downloads", "repo", "cat", "name", "1.0-RC.lock");
        Path lower = Path.of("downloads", "repo", "cat", "name", "1.0-rc.lock");
        Files.writeString(directory.resolve("CheckCase"), "");
        boolean folds = Files.exists(directory.resolve("checkcase"));
        if (folds) {
            assertEquals(CacheLock.versionLeaseName(directory.toRealPath(), upper), CacheLock.versionLeaseName(directory.toRealPath(), lower));
        } else {
            assertNotEquals(CacheLock.versionLeaseName(directory.toRealPath(), upper), CacheLock.versionLeaseName(directory.toRealPath(), lower));
        }
    }

    /// P1:138 缺失的层级不能经由非目录分量创建到缓存根之外。
    @Test
    public void rejectsNonDirectoryLockAncestor(@TempDir Path directory) throws IOException {
        Path cache = Files.createDirectory(directory.resolve("cache"));
        Files.createDirectory(cache.resolve("downloads"));
        Path blocker = Files.writeString(cache.resolve("downloads/repo"), "not a directory");

        assertThrows(IOException.class, () -> CacheLock.acquireVersion(
                cache, cache.resolve("downloads/repo/board-image/meles/1.0.0.lock")));

        assertEquals("not a directory", Files.readString(blocker));
        assertFalse(Files.exists(cache.resolve("downloads/repo/board-image")));
    }

    /// P3:244 原持有者释放后，暂存通道在后续取得锁时被回收。
    @Test
    public void reclaimsParkedChannelsAfterContention(@TempDir Path directory) throws IOException {
        // Windows 关闭冲突通道不会释放其他 Java 通道的锁，因此不存在暂存通道。
        boolean posix = !System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        Assumptions.assumeTrue(posix, "POSIX process-wide file locks are required");
        Path lockPath = Files.createFile(directory.resolve("version.lock"));
        Path other = directory.resolve("other.lock");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertNull(CacheLock.acquire(lockPath));
            assertTrue(CacheLock.deferredCount() >= 1);
        }

        // 原持有者已释放且不再重试该 inode：下一次取得锁时回收暂存通道，而不是保留到 JVM 退出。
        try (@Nullable CacheLock next = CacheLock.acquire(other)) {
            assertNotNull(next);
            assertEquals(0, CacheLock.deferredCount());
        }
    }
}
