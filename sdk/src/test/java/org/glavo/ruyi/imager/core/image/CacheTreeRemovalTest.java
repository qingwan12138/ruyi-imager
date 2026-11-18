// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests deletion while keeping cache directory bindings.
@NotNullByDefault
public final class CacheTreeRemovalTest {
    /// Keeps marker writes, publication and deletion in the opened directory after a rename.
    @Test
    public void markerMutationsUseTheBoundDirectory(@TempDir Path directory) throws IOException {
        Path root = directory.toRealPath();
        Path relative = Path.of("partials");
        Path original = Files.createDirectory(root.resolve(relative));
        Path moved = root.resolve("moved");
        Path staged = Path.of("staging");
        Path marker = Path.of("marker");
        try (var bound = CacheTreeRemoval.bindDirectory(root, relative, false)) {
            Files.move(original, moved);
            Files.createDirectory(original);
            Files.writeString(original.resolve(marker), "replacement");
            bound.writeNewFile(staged, new byte[]{42});
            bound.moveFile(staged, marker);
            assertEquals(42, Files.readAllBytes(moved.resolve(marker))[0]);
            assertEquals("replacement", Files.readString(original.resolve(marker)));
            bound.deleteFile(marker);
            assertFalse(Files.exists(moved.resolve(marker)));
            assertEquals("replacement", Files.readString(original.resolve(marker)));
        }
    }

    /// Rejects Windows names that could resolve to a different directory in legacy path APIs.
    @Test
    public void rejectsAmbiguousWindowsPathComponents(@TempDir Path directory) throws IOException {
        Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        Path root = directory.toRealPath();
        Path actual = Files.createDirectory(root.resolve("artifacts"));
        Path archive = Files.writeString(actual.resolve("archive"), "keep");
        ArrayList<String> skipped = new ArrayList<>();

        CacheTreeRemoval.remove(root, Path.of("artifacts.", "archive"), false,
                (_, _) -> {
                    throw new AssertionError("The ordinary-name entry was deleted");
                }, skipped);

        assertEquals("keep", Files.readString(archive));
        assertTrue(skipped.stream().anyMatch(reason -> reason.contains("Ambiguous Windows cache entry name")));
    }

    /// The lock uses the directory that was verified, even when replaced by another real directory.
    @Test
    public void rejectsReplacementOfVerifiedLockParent(@TempDir Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("cache")).toRealPath();
        Path relative = Path.of("downloads", "version.lock");
        Path owner = Files.createDirectory(root.resolve("downloads"));
        Object identity = CacheTreeRemoval.lockParentIdentity(root, relative);
        Files.move(owner, root.resolve("previous"));
        Files.createDirectory(owner);

        assertThrows(IOException.class, () -> CacheTreeRemoval.withLock(root, relative, identity,
                () -> {
                    throw new AssertionError("cleanup ran under the replacement lock");
                }));
        assertFalse(Files.exists(root.resolve(relative)));
    }

    /// A cache can be removed without listing an ancestor outside it.
    @Test
    public void worksWithSearchOnlyCacheAncestor(@TempDir Path directory) throws IOException {
        Path ancestor = Files.createDirectory(directory.resolve("ancestor"));
        Assumptions.assumeTrue(Files.getFileAttributeView(ancestor, PosixFileAttributeView.class) != null,
                "POSIX permissions are unavailable");
        Path root = Files.createDirectory(ancestor.resolve("cache")).toRealPath();
        Path entry = Files.writeString(root.resolve("entry"), "cache");
        Set<PosixFilePermission> previous = Files.getPosixFilePermissions(ancestor);
        ArrayList<String> skipped = new ArrayList<>();
        try {
            Files.setPosixFilePermissions(ancestor, Set.of(PosixFilePermission.OWNER_EXECUTE));
            Assumptions.assumeFalse(Files.isReadable(ancestor), "the test process can list restricted directories");
            CacheTreeRemoval.remove(root, Path.of("entry"), false, (_, _) -> { }, skipped);
            assertFalse(Files.exists(entry));
            assertTrue(skipped.isEmpty(), skipped.toString());
        } finally {
            Files.setPosixFilePermissions(ancestor, previous);
        }
    }

    /// Replacing an ancestor during cleanup cannot redirect the remaining deletes.
    @Test
    public void keepsDeletesBoundToTheOpenedDirectory(@TempDir Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("cache")).toRealPath();
        Path parent = root.resolve("artifacts");
        Path version = Files.createDirectories(parent.resolve("version"));
        Files.writeString(version.resolve("first"), "cache");
        Files.writeString(version.resolve("second"), "cache");
        Path outside = Files.createDirectories(directory.resolve("outside/version"));
        Path outsideFirst = Files.writeString(outside.resolve("first"), "outside");
        Path outsideSecond = Files.writeString(outside.resolve("second"), "outside");
        Path detached = root.resolve("detached");
        ArrayList<String> skipped = new ArrayList<>();
        AtomicInteger removed = new AtomicInteger();

        CacheTreeRemoval.remove(root, root.relativize(version), true, (_, _) -> {
            if (removed.incrementAndGet() != 1) {
                return;
            }
            try {
                Files.move(parent, detached);
            } catch (FileSystemException _) {
                // Windows pins the ancestor against renames until cleanup finishes.
                return;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            try {
                Files.createSymbolicLink(parent, outside.getParent());
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }, skipped);

        assertEquals(2, removed.get());
        assertTrue(skipped.isEmpty(), skipped.toString());
        assertEquals("outside", Files.readString(outsideFirst));
        assertEquals("outside", Files.readString(outsideSecond));
        assertFalse(Files.exists(detached.resolve("version")));
    }

    /// P2:118 只有产物的版本也能取得兼容锁：缺失的锁父目录在缓存根下安全建立。
    @Test
    public void createsMissingLegacyLockParent(@TempDir Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("cache")).toRealPath();
        Path artifact = Files.createDirectories(root.resolve("artifacts/repo/board-image/meles/1.0.0"));
        Path archive = Files.writeString(artifact.resolve("root.raw"), "image");
        Path legacy = Path.of("downloads/repo/board-image/meles/1.0.0.lock");
        AtomicInteger ran = new AtomicInteger();

        boolean locked = CacheTreeRemoval.withVersionLock(root, legacy, _ -> ran.incrementAndGet());

        assertTrue(locked);
        assertEquals(1, ran.get());
        assertTrue(Files.isRegularFile(root.resolve(legacy)));
        assertEquals("image", Files.readString(archive));
    }

    /// P1:339 删除绑定到取得兼容锁时打开的父目录，父目录被替换后不会删到新目录。
    @Test
    public void keepsVersionDeleteBoundToTheLockParent(@TempDir Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("cache")).toRealPath();
        Path parent = Files.createDirectories(root.resolve("downloads/repo/board-image/meles"));
        Path version = Files.createDirectories(parent.resolve("1.0.0"));
        Path cached = Files.writeString(version.resolve("root.zst"), "cached");
        Path legacy = Path.of("downloads/repo/board-image/meles/1.0.0.lock");
        Path detached = root.resolve("detached");
        Path replacementVersion = root.resolve("downloads/repo/board-image/meles/1.0.0");

        boolean ran = CacheTreeRemoval.withVersionLock(root, legacy, bound -> {
            assertEquals(parent, bound.path());
            try {
                Files.move(parent, detached);
            } catch (IOException _) {
                Assumptions.assumeTrue(false, "the platform pins the lock parent against renames");
            }
            Files.writeString(Files.createDirectories(replacementVersion).resolve("root.zst"), "downloading");
            bound.remove(Path.of("1.0.0"), CacheTreeRemoval.Target.DIRECTORY, (_, _) -> { }, new ArrayList<>());
        });

        assertTrue(ran);
        assertFalse(Files.exists(detached.resolve("1.0.0")));
        assertFalse(Files.exists(cached));
        assertEquals("downloading", Files.readString(replacementVersion.resolve("root.zst")));
    }

    /// P2:175 卷根也可以作为缓存目录：根租约的身份必须可用。
    @Test
    public void identifiesVolumeRootLockParent() throws IOException {
        @Nullable Path volume = Path.of(System.getProperty("user.dir")).toAbsolutePath().getRoot();
        assertNotNull(volume);
        Object identity = CacheTreeRemoval.lockParentIdentity(volume, Path.of("cache-probe.lock"));
        assertNotNull(identity);
    }
}
