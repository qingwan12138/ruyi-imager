// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.AppDirectories;
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
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
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
        Files.writeString(original.resolve(marker), "original");
        try (var bound = CacheTreeRemoval.bindDirectory(root, relative, false)) {
            Files.move(original, moved);
            Files.createDirectory(original);
            Files.writeString(original.resolve(marker), "replacement");
            assertTrue(bound.isRegularFile(marker));
            assertEquals("original", new String(bound.readFile(marker, 64)));
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
                _ -> {
                    throw new AssertionError("The ordinary-name entry was deleted");
                }, recordSkipped(skipped));

        assertEquals("keep", Files.readString(archive));
        assertTrue(skipped.stream().anyMatch(reason -> reason.contains("Ambiguous Windows cache entry name")));
    }

    /// Treats a mounted cache root as the boundary and rejects mount changes below it.
    @Test
    public void linuxMountBoundaryAllowsTheRootMount() throws IOException {
        Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Linux"));
        Path proc = Path.of("/proc").toRealPath();
        Path process = proc.resolve("self").toRealPath();
        CacheTreeRemoval.checkMountBoundary(proc, process);
        assertThrows(FileSystemException.class, () -> CacheTreeRemoval.checkMountBoundary(proc.getRoot(), proc));
    }

    /// Rejects a different mounted device while allowing the cache root to be on one.
    @Test
    public void macMountBoundaryAllowsTheRootDevice() throws IOException {
        Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Mac"));
        Path dev = Path.of("/dev").toRealPath();
        Path root = dev.getRoot();
        Assumptions.assumeFalse(Files.getAttribute(root, "unix:dev").equals(Files.getAttribute(dev, "unix:dev")));
        CacheTreeRemoval.checkMountBoundary(dev, dev);
        assertThrows(FileSystemException.class, () -> CacheTreeRemoval.checkMountBoundary(root, dev));
    }

    /// Leaves content behind when a cache descendant is a bind mount of the same device.
    @Test
    public void rejectsSameDeviceBindMount(@TempDir Path directory) throws IOException, InterruptedException {
        Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Linux"));
        Path root = Files.createDirectory(directory.resolve("cache")).toRealPath();
        Path relative = Path.of("downloads", "repo", "category", "name", "version");
        Path version = Files.createDirectories(root.resolve(relative));
        Path target = Files.createDirectory(version.resolve("mounted"));
        Path source = Files.createDirectory(directory.resolve("source"));
        Path archive = Files.writeString(source.resolve("archive"), "mounted");
        mountBind(source, target);
        try {
            assertEquals(Files.getAttribute(source, "unix:dev"), Files.getAttribute(target, "unix:dev"));
            assertThrows(FileSystemException.class, () -> CacheTreeRemoval.checkMountBoundary(root, target));
            var service = new ImageCacheService(new AppDirectories(directory.resolve("config"), root));
            ImageCacheUsage usage = service.usage();
            assertEquals(0, usage.downloadBytes());
            assertTrue(usage.unreadable().contains(target.toString()));
            Path slot = Files.createDirectory(version.resolve(".download-partials-0"));
            Path partial = Files.writeString(slot.resolve("image.raw.part"), "partial");
            assertTrue(service.usageForPartialsCleanup().unreadable().isEmpty());
            assertTrue(service.cleanPartials().skipped().isEmpty());
            assertFalse(Files.exists(partial));
            assertEquals("mounted", Files.readString(archive));
            ArrayList<String> skipped = new ArrayList<>();
            CacheTreeRemoval.remove(root, relative, true, _ -> {}, recordSkipped(skipped));
            assertEquals("mounted", Files.readString(archive));
            assertTrue(skipped.stream().anyMatch(reason -> reason.contains("Crosses a cache mount boundary")));
        } finally {
            unmount(target);
        }
    }

    /// Uses a separately mounted downloads tree for normal operations while cleanup skips it.
    @Test
    public void normalVersionLocksAllowMountedDownloads(@TempDir Path directory)
            throws IOException, InterruptedException {
        Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Linux"));
        Path root = Files.createDirectory(directory.resolve("cache")).toRealPath();
        Path source = Files.createDirectory(directory.resolve("storage"));
        Path downloads = Files.createDirectory(root.resolve("downloads"));
        Path legacy = Path.of("downloads", "repo", "category", "name", "Version.lock");
        mountBind(source, downloads);
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                try (var lock = CacheLock.acquireVersion(root, root.resolve(legacy))) {
                    assertNotNull(lock);
                    assertTrue(Files.isRegularFile(source.resolve(legacy.subpath(1, legacy.getNameCount()))));
                }
            }
            Path version = Files.createDirectory(root.resolve(legacy).resolveSibling("Version"));
            Path archive = Files.writeString(version.resolve("source.raw"), "mounted archive");
            var distfile = new RuyiDistfile("source.raw", List.of(), Files.size(archive), Map.of(),
                    true, false, null, 0, List.of(), "raw");
            assertEquals(archive, new RuyiDistfileDownloader().download(distfile, version, root, _ -> {}));

            assertThrows(FileSystemException.class, () -> CacheTreeRemoval.withVersionLock(root, legacy,
                    () -> { throw new AssertionError("Cleanup crossed the downloads mount"); }));
        } finally {
            unmount(downloads);
        }
    }

    /// Acquires an existing legacy lock without creating a root lease or probe during cleanup.
    @Test
    public void legacyVersionCleanupUsesNoNewDirectoryEntry(@TempDir Path directory) throws IOException {
        Path root = directory.toRealPath();
        Path legacy = Path.of("downloads", "repo", "category", "name", "Version.lock");
        try (var lock = CacheLock.acquire(root.resolve(legacy))) {
            assertNotNull(lock);
        }
        Path version = Files.createDirectory(root.resolve(legacy).resolveSibling("Version"));
        Path archive = Files.writeString(version.resolve("image.raw"), "legacy cache");
        var service = new ImageCacheService(new AppDirectories(root.resolve("config"), root));
        FileTime stamp = FileTime.from(java.time.Instant.parse("2001-01-01T00:00:00Z"));
        Files.setLastModifiedTime(root, stamp);
        FileTime rootModified = Files.getLastModifiedTime(root);
        @Nullable Set<PosixFilePermission> permissions = null;
        if (Files.getFileAttributeView(root, PosixFileAttributeView.class) != null) {
            permissions = Files.getPosixFilePermissions(root);
            Files.setPosixFilePermissions(root, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        }
        try {
            try (var lock = CacheLock.acquire(root.resolve(legacy))) {
                assertNotNull(lock);
                assertEquals(0, service.cleanAll().removedFiles());
                assertTrue(Files.exists(archive));
            }
            ImageCacheCleanResult result = service.cleanAll();
            assertEquals(1, result.removedFiles(), result.skipped().toString());
            assertFalse(Files.exists(archive));
            assertEquals(rootModified, Files.getLastModifiedTime(root));
            try (var paths = Files.list(root)) {
                assertEquals(List.of(root.resolve("downloads")), paths.toList());
            }
        } finally {
            if (permissions != null) {
                Files.setPosixFilePermissions(root, permissions);
            }
        }
    }

    /// Bind mounts a test directory when the Linux test runner has mount permissions.
    private static void mountBind(Path source, Path target) throws IOException, InterruptedException {
        Process mount;
        try {
            mount = new ProcessBuilder("mount", "--bind", source.toString(), target.toString())
                    .redirectErrorStream(true).start();
        } catch (IOException exception) {
            Assumptions.assumeTrue(false, "mount is unavailable: " + exception);
            return;
        }
        String diagnostic = new String(mount.getInputStream().readAllBytes());
        Assumptions.assumeTrue(mount.waitFor() == 0, diagnostic);
    }

    /// Releases a mount created for a test fixture.
    private static void unmount(Path target) throws IOException, InterruptedException {
        Process unmount = new ProcessBuilder("umount", target.toString()).redirectErrorStream(true).start();
        String diagnostic = new String(unmount.getInputStream().readAllBytes());
        assertEquals(0, unmount.waitFor(), diagnostic);
    }

    /// A writer can use a legacy lock when the cache root cannot allocate a root lease.
    @Test
    public void writerReusesLegacyLockUnderReadOnlyRoot(@TempDir Path directory) throws IOException {
        Path root = directory.toRealPath();
        Assumptions.assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        Path legacy = root.resolve("downloads/repo/category/name/Version.lock");
        try (var lock = CacheLock.acquire(legacy)) {
            assertNotNull(lock);
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(root);
        Files.setPosixFilePermissions(root, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            Assumptions.assumeFalse(Files.isWritable(root));
            try (var lock = CacheLock.acquireVersion(root, legacy)) {
                assertNotNull(lock);
                assertFalse(CacheTreeRemoval.withVersionLock(root, root.relativize(legacy),
                        () -> { throw new AssertionError("Cleanup ran while the writer held its lock"); }));
            }
        } finally {
            Files.setPosixFilePermissions(root, permissions);
        }
    }

    /// Exclusive acquisition requires only write permission on the compatibility lock.
    @Test
    public void versionLockAcceptsWriteOnlyCompatibilityFile(@TempDir Path directory) throws IOException {
        Path root = directory.toRealPath();
        Assumptions.assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        Path legacy = root.resolve("downloads/repo/category/name/Version.lock");
        try (var lock = CacheLock.acquireVersion(root, legacy)) {
            assertNotNull(lock);
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(legacy);
        Files.setPosixFilePermissions(legacy, Set.of(PosixFilePermission.OWNER_WRITE));
        try {
            Assumptions.assumeFalse(Files.isReadable(legacy));
            try (var lock = CacheLock.acquireVersion(root, legacy)) {
                assertNotNull(lock);
            }
            assertTrue(CacheTreeRemoval.withVersionLock(root, root.relativize(legacy), () -> {}));
        } finally {
            Files.setPosixFilePermissions(legacy, permissions);
        }
    }

    /// Acquires established locks when the root cannot allocate a probe.
    @Test
    public void existingVersionLocksNeedNoProbe(@TempDir Path directory) throws IOException {
        Path root = directory.toRealPath();
        Assumptions.assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        Path legacy = Path.of("downloads", "repo", "category", "name", "Version.lock");
        try (var lock = CacheLock.acquireVersion(root, root.resolve(legacy))) {
            assertNotNull(lock);
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(root);
        Files.setPosixFilePermissions(root, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            Assumptions.assumeFalse(Files.isWritable(root));
            try (var lock = CacheLock.acquireVersion(root, root.resolve(legacy))) {
                assertNotNull(lock);
                assertFalse(CacheTreeRemoval.withVersionLock(root, legacy,
                        () -> { throw new AssertionError("A held lock allowed cleanup"); }));
            }
            AtomicInteger ran = new AtomicInteger();
            assertTrue(CacheTreeRemoval.withVersionLock(root, legacy, ran::incrementAndGet));
            assertEquals(1, ran.get());
        } finally {
            Files.setPosixFilePermissions(root, permissions);
        }
    }

    /// Rejects a replacement cache root when binding the actual deletion.
    @Test
    public void rejectsReplacementRootWhenDeleting(@TempDir Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("cache")).toRealPath();
        Object identity = CacheTreeRemoval.lockParentIdentity(root, Path.of(".selection"));
        Files.move(root, directory.resolve("previous"));
        Path replacement = Files.createDirectories(root.resolve("version"));
        Path payload = Files.writeString(replacement.resolve("payload"), "replacement");
        ArrayList<String> skipped = new ArrayList<>();

        CacheTreeRemoval.remove(root, Path.of("version"), CacheTreeRemoval.Target.DIRECTORY,
                identity, _ -> {}, recordSkipped(skipped));

        assertEquals("replacement", Files.readString(payload));
        assertTrue(skipped.stream().anyMatch(reason -> reason.contains("Cache root changed")));
        assertThrows(IOException.class,
                () -> CacheTreeRemoval.bindDirectory(root, Path.of("version"), false, false, identity));
        Path lock = Path.of("downloads", "repo", "category", "name", "version.lock");
        assertThrows(IOException.class, () -> CacheTreeRemoval.withVersionLock(root, lock, identity,
                _ -> { throw new AssertionError("The replacement root was locked"); }));
        assertFalse(Files.exists(root.resolve("downloads")));
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
            CacheTreeRemoval.remove(root, Path.of("entry"), false, _ -> { }, recordSkipped(skipped));
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

        CacheTreeRemoval.remove(root, root.relativize(version), true, _ -> {
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
        }, recordSkipped(skipped));

        assertEquals(2, removed.get());
        assertTrue(skipped.isEmpty(), skipped.toString());
        assertEquals("outside", Files.readString(outsideFirst));
        assertEquals("outside", Files.readString(outsideSecond));
        assertFalse(Files.exists(detached.resolve("version")));
    }

    /// Creates the compatibility lock parent for an artifact-only version.
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

    /// Keeps version cleanup bound to the compatibility lock parent.
    @Test
    public void keepsVersionDeleteBoundToTheLockParent(@TempDir Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("cache")).toRealPath();
        Path parent = Files.createDirectories(root.resolve("downloads/repo/board-image/meles"));
        Path version = Files.createDirectories(parent.resolve("1.0.0"));
        Files.writeString(version.resolve("root.zst"), "cached");
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
            bound.remove(Path.of("1.0.0"), CacheTreeRemoval.Target.DIRECTORY, _ -> { }, (path, failure) -> {
                throw new AssertionError("Could not remove " + path, failure);
            });
        });

        assertTrue(ran);
        assertFalse(Files.exists(detached.resolve("1.0.0")));
        assertEquals("downloading", Files.readString(replacementVersion.resolve("root.zst")));
    }

    /// Records removal diagnostics while retaining their original text for assertions.
    private static BiConsumer<Path, Throwable> recordSkipped(List<String> skipped) {
        return (path, failure) -> skipped.add(path + ": " + failure.getMessage());
    }

    /// A C locale supports ASCII version locks without requiring Unicode filename probes.
    @Test
    public void acquiresVersionLockInAsciiLocale(@TempDir Path directory) throws Exception {
        runLocaleProbe(directory, "C", "US-ASCII", "ascii");
    }

    /// Native path calls use the same non-UTF-8 encoding as NIO.
    @Test
    public void bindsAndRenamesPathsInLatinOneLocale(@TempDir Path directory) throws Exception {
        runLocaleProbe(directory, "en_US.ISO-8859-1", "ISO-8859-1", "\u00e9");
    }

    /// Runs the directory binding and rename checks in a separate JVM with a chosen locale.
    private static void runLocaleProbe(Path directory, String locale, String encoding, String component) throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Linux"));
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
        Path sdkClasses = Path.of(CacheTreeRemoval.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path testClasses = Path.of(LocaleProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ProcessBuilder builder = new ProcessBuilder(javaBinary.toString(), "--enable-native-access=ALL-UNNAMED",
                "-cp", sdkClasses + java.io.File.pathSeparator + testClasses,
                LocaleProbe.class.getName(), directory.toString(), encoding, Integer.toString(component.charAt(0)));
        builder.environment().put("LC_ALL", locale);
        Process process = builder.redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "Locale probe JVM did not exit");
        String output = new String(process.getInputStream().readAllBytes());
        Assumptions.assumeTrue(process.exitValue() != 77, "Requested locale is unavailable: " + output);
        assertEquals(0, process.exitValue(), output);
    }

    /// Checks native cache operations in an isolated JVM so the NIO encoding follows LC_ALL.
    @NotNullByDefault
    public static final class LocaleProbe {
        /// Acquires a version lock, creates cache directories, and tests exclusive rename.
        public static void main(String[] args) throws IOException {
            var jnuEncoding = java.nio.charset.Charset.forName(System.getProperty("sun.jnu.encoding"));
            if (!jnuEncoding.equals(java.nio.charset.Charset.forName(args[1]))) {
                System.out.println(jnuEncoding);
                System.exit(77);
            }
            String component = Character.toString(Integer.parseInt(args[2]));
            Path root = Path.of(args[0]).resolve(component + "-cache");
            Path legacy = root.resolve("downloads").resolve(component).resolve("category/name/1.0.lock");
            try (@Nullable CacheLock lock = CacheLock.acquireVersion(root, legacy)) {
                if (lock == null || !Files.isRegularFile(legacy)) {
                    throw new AssertionError("Version lock was not created");
                }
                Path source = Files.writeString(root.resolve(component + "-temp"), "data");
                Path target = root.resolve(component + "-archive");
                CacheTreeRemoval.moveWithoutReplacing(source, target);
                if (!Files.readString(target).equals("data")) {
                    throw new AssertionError("Archive was not published");
                }
            }
        }
    }

    /// Identifies the lock parent even when the cache is a volume root.
    @Test
    public void identifiesVolumeRootLockParent() throws IOException {
        @Nullable Path volume = Path.of(System.getProperty("user.dir")).toAbsolutePath().getRoot();
        assertNotNull(volume);
        Object identity = CacheTreeRemoval.lockParentIdentity(volume, Path.of("cache-probe.lock"));
        assertNotNull(identity);
    }
}
