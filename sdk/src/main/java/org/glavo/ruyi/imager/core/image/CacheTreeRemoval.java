// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.Unmodifiable;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// Removes cache entries while keeping their real parent directories bound.
@NotNullByDefault
final class CacheTreeRemoval {
    /// Whether this platform exposes Linux mount identities.
    private static final boolean LINUX = System.getProperty("os.name", "").startsWith("Linux");

    /// Whether the cache needs to account for macOS device boundaries.
    private static final boolean MAC = System.getProperty("os.name", "").startsWith("Mac");

    /// Prevents construction.
    private CacheTreeRemoval() {
    }

    /// Determines the expected type of a selected entry.
    @NotNullByDefault
    enum Target {
        /// A single file or link.
        FILE,
        /// A regular file, never a symbolic link or directory.
        REGULAR_FILE,
        /// A version directory, never a regular file or symbolic link.
        DIRECTORY,
        /// A file or arbitrary directory tree.
        TREE
    }

    /// Receives the bytes released by deleting one directory entry.
    @NotNullByDefault
    @FunctionalInterface
    interface RemovedFile {
        /// Records one removed entry.
        ///
        /// @param size size of the removed entry.
        void accept(long size);
    }

    /// Removes a cache entry from a physical root.
    static void remove(Path root, Path relative, boolean recursive, RemovedFile removedFile, BiConsumer<Path, Throwable> skipped) {
        remove(root, relative, recursive ? Target.TREE : Target.FILE, removedFile, skipped);
    }

    /// Removes a cache entry with an expected root type.
    static void remove(Path root, Path relative, Target target, RemovedFile removedFile, BiConsumer<Path, Throwable> skipped) {
        remove(root, relative, target, null, removedFile, skipped);
    }

    /// Removes an entry only from the root captured by the cleanup snapshot.
    static void remove(Path root, Path relative, Target target, @Nullable Object expectedRoot,
            RemovedFile removedFile, BiConsumer<Path, Throwable> skipped) {
        try {
            atParent(root, relative, expectedRoot, (parent, name) -> {
                removeEntry(parent, name, target, removedFile, skipped);
                return true;
            });
        } catch (NoSuchFileException _) {
            // A missing ancestor has no entry to clean.
        } catch (IOException exception) {
            skipped.accept(root.resolve(relative), exception);
        }
    }

    /// Binds one existing real directory below the physical cache root.
    ///
    /// The returned directory keeps its own descriptor (POSIX) or handle (Windows) so callers can
    /// create, move, and delete entries relative to that directory.
    ///
    /// @param root physical cache root.
    /// @param relative directory path relative to the root.
    /// @param create whether a missing directory may be created.
    /// @return bound directory, owned by the caller.
    /// @throws IOException when the directory is missing, is not real, or cannot be opened.
    static Directory bindDirectory(Path root, Path relative, boolean create) throws IOException {
        return bindDirectory(root, relative, create, true);
    }

    /// Binds a directory with the specified Windows delete sharing.
    static Directory bindDirectory(Path root, Path relative, boolean create, boolean shareDelete) throws IOException {
        return bindDirectory(root, relative, create, shareDelete, null);
    }

    /// Binds a directory below the root captured by the cleanup snapshot.
    static Directory bindDirectory(Path root, Path relative, boolean create, boolean shareDelete,
            @Nullable Object expectedRoot) throws IOException {
        return bindDirectory(root, relative, create, shareDelete, expectedRoot, false);
    }

    /// Binds a managed version for writing, allowing a separate top-level downloads mount.
    static Directory bindDownloadVersion(Path root, Path relative) throws IOException {
        if (relative.getNameCount() != 5 || !relative.getName(0).toString().equalsIgnoreCase("downloads")) {
            throw new FileSystemException(relative.toString(), null, "Expected a managed download version");
        }
        return bindDirectory(root, relative, true, true, null, true);
    }

    /// Binds or creates a real directory under the requested mount policy.
    private static Directory bindDirectory(Path root, Path relative, boolean create, boolean shareDelete,
            @Nullable Object expectedRoot, boolean allowMountedDownloads) throws IOException {
        if (create) {
            ensureLockParent(root, relative.resolve(".entry"), expectedRoot, allowMountedDownloads);
        }
        return atParent(root, relative, expectedRoot, allowMountedDownloads, (parent, name) -> {
            Directory bound = parent.open(name, shareDelete);
            boolean accepted = false;
            try {
                bound.verifyIdentity();
                accepted = true;
                return bound;
            } finally {
                if (!accepted) {
                    bound.close();
                }
            }
        });
    }

    /// Creates missing lock ancestors within the identified root and permitted mounts.
    private static void ensureLockParent(Path root, Path relative, @Nullable Object expectedRoot,
            boolean allowMountedDownloads) throws IOException {
        if (root.getFileSystem() != FileSystems.getDefault() || relative.isAbsolute()
                || !relative.equals(relative.normalize()) || relative.startsWith("..")) {
            throw new IOException("Cannot create cache lock parents: " + relative);
        }
        if (File.separatorChar == '\\') {
            Path volume = root.getRoot();
            try (Directory start = new Directory(volume, null, null)) {
                ensureWindowsParents(start, volume.relativize(root.resolve(relative)), 0, root.getNameCount(), expectedRoot);
            }
        } else {
            try (Directory bound = Directory.openRoot(root)) {
                bound.checkRootIdentity(expectedRoot);
                Posix.createParents(bound, relative, allowMountedDownloads);
            }
        }
    }

    /// Keeps each Windows ancestor pinned across creation and descent.
    private static void ensureWindowsParents(Directory parent, Path relative, int level, int rootDepth,
            @Nullable Object expectedRoot) throws IOException {
        if (level == rootDepth) {
            parent.checkRootIdentity(expectedRoot);
        }
        if (level == relative.getNameCount() - 1) {
            return;
        }
        Path name = relative.getName(level);
        Windows.checkComponent(name);
        if (level >= rootDepth) {
            try {
                Files.createDirectory(parent.path().resolve(name));
            } catch (FileAlreadyExistsException _) {
                // Opening without following reparse points verifies the existing entry.
            }
        }
        try (Directory child = parent.open(name)) {
            ensureWindowsParents(child, relative, level + 1, rootDepth, expectedRoot);
        }
    }

    /// Name equivalences observed in the cache root.
    /// @param caseInsensitive whether case-only variants resolve to the same entry.
    /// @param normalizationInsensitive whether Unicode normalization variants resolve to the same entry.
    @NotNullByDefault
    record NameEquivalence(boolean caseInsensitive, boolean normalizationInsensitive) {
    }

    /// Tests case and normalization using a private filename in the lease namespace.
    static NameEquivalence nameEquivalence(Path root) throws IOException {
        return atParent(root, Path.of(".probe"), (parent, _) -> {
            if (parent.stream == null && parent.pin == null) {
                try (Directory pinned = new Directory(root, null, Windows.pin(root, true))) {
                    return probeNames(pinned);
                }
            }
            return probeNames(parent);
        });
    }

    /// Detects alternate names for the same probe entry.
    private static NameEquivalence probeNames(Directory parent) throws IOException {
        String prefix = ".lease-probe-" + UUID.randomUUID();
        boolean caseInsensitive = probeAlternateName(parent, Path.of(prefix + "-A"), Path.of(prefix + "-a"));
        boolean normalizationInsensitive;
        try {
            normalizationInsensitive = probeAlternateName(
                    parent, Path.of(prefix + "-A\u00e9"), Path.of(prefix + "-Ae\u0301"));
        } catch (InvalidPathException _) {
            // A filesystem encoding that cannot represent the probe may still store ASCII paths.
            normalizationInsensitive = false;
        }
        return new NameEquivalence(caseInsensitive, normalizationInsensitive);
    }

    /// Creates one probe and checks whether another spelling finds it.
    private static boolean probeAlternateName(Directory parent, Path probe, Path alternative) throws IOException {
        parent.writeNewFile(probe, new byte[0]);
        try {
            return probeExists(parent, alternative);
        } finally {
            parent.deleteFile(probe);
        }
    }

    /// Checks whether an alternative probe name resolves in the bound directory.
    private static boolean probeExists(Directory parent, Path name) throws IOException {
        try {
            parent.attributes(name);
            return true;
        } catch (NoSuchFileException _) {
            return false;
        }
    }

    /// Publishes a file on the same volume without replacing a file created by another process.
    static void moveWithoutReplacing(Path source, Path target) throws IOException {
        if (File.separatorChar == '\\') {
            Files.move(source, target);
        } else {
            Posix.moveWithoutReplacing(source, target);
        }
    }

    /// Creates POSIX directory entries relative to a descriptor rather than a mutable path.
    @NotNullByDefault
    private static final class Posix {
        /// Encoding used by the default NIO filesystem provider for native paths.
        private static final Charset PATH_ENCODING = Charset.forName(System.getProperty("sun.jnu.encoding"));
        /// Native library lookup for POSIX directory calls.
        private static final Linker LINKER = Linker.nativeLinker();

        /// Opens a descriptor for a directory.
        private static final MethodHandle OPEN = bind("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT),
                Linker.Option.firstVariadicArg(2));

        /// Opens a directory relative to its parent.
        private static final MethodHandle OPEN_AT = bind("openat",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), Linker.Option.firstVariadicArg(3));

        /// Creates a directory relative to its parent.
        private static final MethodHandle MKDIR_AT = bind("mkdirat",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));

        /// Closes a directory descriptor.
        private static final MethodHandle CLOSE = bind("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

        /// Encodes a path as NIO does, rejecting a lossy conversion of raw filesystem bytes.
        private static MemorySegment nativePath(Arena arena, Path path) throws IOException {
            String name = path.toString();
            if (!PATH_ENCODING.newEncoder().canEncode(name) || !path.equals(path.getFileSystem().getPath(name))) {
                throw new FileSystemException(name, null, "Path cannot be represented in the filesystem encoding");
            }
            byte[] bytes = name.getBytes(PATH_ENCODING);
            MemorySegment terminated = arena.allocate(bytes.length + 1L);
            terminated.asSlice(0, bytes.length).copyFrom(MemorySegment.ofArray(bytes));
            return terminated;
        }

        /// Resolves one POSIX function.
        private static MethodHandle bind(String name, FunctionDescriptor descriptor, Linker.Option... options) {
            return LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(), descriptor, options);
        }

        /// Uses the host API that combines rename and the no-replace check.
        private static void moveWithoutReplacing(Path source, Path target) throws IOException {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            boolean mac = os.startsWith("mac");
            String symbol = mac ? "renameatx_np" : "renameat2";
            MemorySegment address = LINKER.defaultLookup().find(symbol)
                    .orElseThrow(() -> new IOException("Exclusive rename is unavailable: " + symbol));
            StructLayout stateLayout = Linker.Option.captureStateLayout();
            VarHandle errno = stateLayout.varHandle(MemoryLayout.PathElement.groupElement("errno"));
            MethodHandle rename = LINKER.downcallHandle(address,
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
                    Linker.Option.captureCallState("errno"));
            int currentDirectory = mac ? -2 : -100;
            int exclusive = mac ? 4 : 1;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(stateLayout);
                int result = (int) rename.invokeExact(state, currentDirectory,
                        nativePath(arena, source.toAbsolutePath()), currentDirectory,
                        nativePath(arena, target.toAbsolutePath()), exclusive);
                if (result != 0) {
                    int error = (int) errno.get(state, 0L);
                    if (error == 17) {
                        throw new FileAlreadyExistsException(target.toString());
                    }
                    throw new FileSystemException(source.toString(), target.toString(), "Exclusive rename failed (errno " + error + ")");
                }
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable failure) {
                throw new IOException("Cannot exclusively publish " + target, failure);
            }
        }

        /// Opens only real directories and prevents descriptor inheritance.
        private static int directoryFlags() throws IOException {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "");
            if (os.startsWith("mac")) {
                return 0x00100000 | 0x00000100 | 0x01000000;
            }
            if (os.startsWith("linux")) {
                if (arch.equals("aarch64") || arch.equals("arm")) {
                    return (1 << 14) | (1 << 15) | (1 << 19);
                }
                if (Set.of("amd64", "x86_64", "x86", "riscv64", "loongarch64").contains(arch)) {
                    return (1 << 16) | (1 << 17) | (1 << 19);
                }
            }
            throw new IOException("Bound cache directory creation is unavailable on " + os + "/" + arch);
        }

        /// Creates missing directories relative to their opened ancestors.
        private static void createParents(Directory root, Path relative, boolean allowMountedDownloads) throws IOException {
            int flags = directoryFlags();
            try (Arena arena = Arena.ofConfined()) {
                if (root.descriptor() >= 0) {
                    createParents(root.descriptor(), relative, 0, flags, root.mount(), allowMountedDownloads, arena);
                    return;
                }
                int descriptor = (int) OPEN.invokeExact(nativePath(arena, root.path()), flags);
                if (descriptor < 0) {
                    throw new IOException("Cannot open cache root: " + root);
                }
                try {
                    if (root.mount() >= 0L && root.mount() != mountId(descriptor)) {
                        throw new FileSystemException(root.path().toString(), null, "Cache root device changed");
                    }
                    root.verifyIdentity();
                    createParents(descriptor, relative, 0, flags, root.mount(), allowMountedDownloads, arena);
                } finally {
                    int ignored = (int) CLOSE.invokeExact(descriptor);
                }
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable failure) {
                throw new IOException("Cannot create bound cache directories under " + root, failure);
            }
        }

        /// Descends through each child using its parent descriptor.
        private static void createParents(int parent, Path relative, int level, int flags, long mount,
                boolean allowMountedDownloads, Arena arena) throws Throwable {
            if (level == relative.getNameCount() - 1) {
                return;
            }
            MemorySegment name = nativePath(arena, relative.getName(level));
            int child = (int) OPEN_AT.invokeExact(parent, name, flags);
            if (child < 0) {
                int ignored = (int) MKDIR_AT.invokeExact(parent, name, 0777);
                child = (int) OPEN_AT.invokeExact(parent, name, flags);
                if (child < 0) {
                    throw new IOException("Cannot create or open cache directory: " + relative.subpath(0, level + 1));
                }
            }
            try {
                long childMount = mount >= 0L ? mountId(child) : -1L;
                if (mount != childMount && !(allowMountedDownloads && level == 0)) {
                    throw new FileSystemException(relative.subpath(0, level + 1).toString(), null,
                            "Crosses a cache mount boundary");
                }
                createParents(child, relative, level + 1, flags, childMount, allowMountedDownloads, arena);
            } finally {
                int ignored = (int) CLOSE.invokeExact(child);
            }
        }

        /// Reads the mount ID on Linux or the filesystem device on macOS.
        private static long mountId(int descriptor) throws Throwable {
            if (MAC) {
                try (Arena arena = Arena.ofConfined()) {
                    return Darwin.device(Darwin.attributes(descriptor, arena));
                }
            }
            for (String line : Files.readAllLines(Path.of("/proc/self/fdinfo", Integer.toString(descriptor)))) {
                if (line.startsWith("mnt_id:")) {
                    try {
                        return Long.parseLong(line.substring("mnt_id:".length()).strip());
                    } catch (NumberFormatException exception) {
                        throw new IOException("Invalid Linux mount identity", exception);
                    }
                }
            }
            throw new IOException("Linux mount identity is unavailable");
        }

        /// Opens a Linux directory from its parent descriptor or by absolute path.
        private static Directory openLinux(Path path, int parent, Path name, long expectedMount) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                int flags = directoryFlags();
                int descriptor = parent < 0
                        ? (int) OPEN.invokeExact(nativePath(arena, name), flags)
                        : (int) OPEN_AT.invokeExact(parent, nativePath(arena, name), flags);
                if (descriptor < 0) {
                    if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw new NoSuchFileException(path.toString());
                    }
                    throw new FileSystemException(path.toString(), null, "Cannot open the real cache directory");
                }
                boolean accepted = false;
                try {
                    long mount = mountId(descriptor);
                    if (expectedMount >= 0L && mount != expectedMount) {
                        throw new FileSystemException(path.toString(), null, "Crosses a cache mount boundary");
                    }
                    // The secure stream opens the same directory whose mount was checked.
                    DirectoryStream<Path> opened = Files.newDirectoryStream(
                            Path.of("/proc/self/fd", Integer.toString(descriptor)));
                    if (!(opened instanceof SecureDirectoryStream<Path> secure)) {
                        opened.close();
                        throw new FileSystemException(path.toString(), null, "Cannot bind the cache directory");
                    }
                    accepted = true;
                    return new Directory(path, secure, null, descriptor, mount);
                } finally {
                    if (!accepted) {
                        closeDescriptor(descriptor);
                    }
                }
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable failure) {
                throw new IOException("Cannot bind cache directory: " + path, failure);
            }
        }

        /// Opens the native descriptor for the directory already opened by the secure stream.
        private static Directory openMac(Path path, SecureDirectoryStream<Path> stream, int parent, Path name,
                long expectedDevice) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                int flags = directoryFlags();
                int descriptor = parent < 0
                        ? (int) OPEN.invokeExact(nativePath(arena, name), flags)
                        : (int) OPEN_AT.invokeExact(parent, nativePath(arena, name), flags);
                if (descriptor < 0) {
                    throw new FileSystemException(path.toString(), null, "Cannot open the real cache directory");
                }
                boolean accepted = false;
                try {
                    MemorySegment attributes = Darwin.attributes(descriptor, arena);
                    long device = Darwin.device(attributes);
                    if (expectedDevice >= 0L && expectedDevice != device) {
                        throw new FileSystemException(path.toString(), null, "Crosses a cache mount boundary");
                    }
                    Directory bound = new Directory(path, stream, null, descriptor, device);
                    // Correlate the descriptor's inode and device with the secure stream's file key.
                    // The descriptor's current path is used only for this comparison and can differ after a rename.
                    Path currentPath = Darwin.path(descriptor, arena);
                    Map<String, Object> named = Files.readAttributes(currentPath, "unix:dev,ino,fileKey",
                            LinkOption.NOFOLLOW_LINKS);
                    @Nullable Object key = bound.fileKey();
                    if (key == null || !key.equals(named.get("fileKey"))
                            || !(named.get("dev") instanceof Number namedDevice)
                            || Integer.toUnsignedLong(namedDevice.intValue()) != device
                            || !(named.get("ino") instanceof Number namedInode)
                            || namedInode.longValue() != attributes.get(JAVA_LONG, 8)) {
                        throw new FileSystemException(path.toString(), null, "Cache directory identity changed");
                    }
                    accepted = true;
                    return bound;
                } finally {
                    if (!accepted) {
                        closeDescriptor(descriptor);
                    }
                }
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable failure) {
                throw new IOException("Cannot bind cache directory: " + path, failure);
            }
        }

        /// Queries bound macOS directories through the 64-bit inode ABI.
        @NotNullByDefault
        private static final class Darwin {
            /// Apple Silicon always uses 64-bit inodes and Intel uses the suffixed symbol.
            private static final MethodHandle FSTAT = bind(System.getProperty("os.arch", "").equals("aarch64")
                            ? "fstat" : "fstat$INODE64",
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));

            /// Queries the current path of an opened descriptor, including after a rename.
            private static final MethodHandle FCNTL = bind("fcntl",
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS), Linker.Option.firstVariadicArg(2));

            /// Reads the device and inode from the 64-bit inode stat structure.
            private static MemorySegment attributes(int descriptor, Arena arena) throws Throwable {
                MemorySegment info = arena.allocate(256, 8);
                if ((int) FSTAT.invokeExact(descriptor, info) != 0) {
                    throw new IOException("Cannot identify cache directory device");
                }
                return info;
            }

            /// Reads the 32-bit st_dev field that begins the macOS stat structure.
            private static long device(MemorySegment attributes) {
                return Integer.toUnsignedLong(attributes.get(JAVA_INT, 0));
            }

            /// Returns the path of the opened directory for identity comparison.
            private static Path path(int descriptor, Arena arena) throws Throwable {
                int getPath = 50;
                MemorySegment buffer = arena.allocate(1024);
                if ((int) FCNTL.invokeExact(descriptor, getPath, buffer) != 0) {
                    throw new IOException("Cannot identify the current path of a cache directory");
                }
                return Path.of(buffer.getString(0L, PATH_ENCODING));
            }
        }

        /// Closes a POSIX directory descriptor.
        private static void closeDescriptor(int descriptor) throws IOException {
            try {
                if ((int) CLOSE.invokeExact(descriptor) != 0) {
                    throw new IOException("Cannot close cache directory descriptor");
                }
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable failure) {
                throw new IOException("Cannot close cache directory descriptor", failure);
            }
        }
    }

    /// Checks whether a scan can reach a directory without crossing a child mount.
    static void checkMountBoundary(Path root, Path path) throws IOException {
        if ((LINUX || MAC) && !root.equals(path)) {
            atParent(root, root.relativize(path), (parent, name) -> {
                try (Directory ignored = parent.open(name)) {
                    return true;
                }
            });
        }
    }

    /// Identity of a POSIX directory and the boundary containing it.
    ///
    /// @param key physical directory key.
    /// @param mount Linux mount ID or macOS device number.
    @NotNullByDefault
    private record MountedIdentity(Object key, long mount) {
    }

    /// Obtains the existing, bound lock parent's identity before attempting to lock it.
    static Object lockParentIdentity(Path root, Path relative) throws IOException {
        return atParent(root, relative, (parent, _) -> parent.identity());
    }

    /// Holds a lock opened relative to the verified parent during an action.
    ///
    /// @param root physical cache root.
    /// @param relative lock path relative to the root.
    /// @param expectedParent identity observed when the lock parent was originally verified.
    /// @param action action to run under the lock.
    /// @return whether the action ran.
    /// @throws IOException when a directory or lock cannot be safely bound.
    static boolean withLock(Path root, Path relative, Object expectedParent, Runnable action) throws IOException {
        return withLock(root, relative, expectedParent, () -> {
            action.run();
            return true;
        });
    }

    /// Returns the identity of one file for byte accounting.
    ///
    /// Recheck the named location because other handles can modify child entries.
    ///
    /// @param path inspected entry.
    /// @param attributes attributes already read for the entry.
    /// @return identity, or null when the file system cannot report one.
    static @Nullable Object fileIdentity(Path path, BasicFileAttributes attributes) {
        @Nullable Object key = attributes.fileKey();
        if (key != null) {
            return key;
        }
        if (path.getFileSystem() != FileSystems.getDefault() || File.separatorChar != '\\') {
            return null;
        }
        return Windows.fileIdentity(path);
    }

    /// Checks whether a regular file has only its original name and the private resume link.
    static boolean hasExactlyTwoLinks(Path path) throws IOException {
        return linkCount(path) == 2L;
    }

    /// Returns the number of directory entries that name one regular file.
    ///
    ///
    /// @param path regular file.
    /// @return link count, or -1 when the file system does not report one.
    /// @throws IOException when the link count cannot be read.
    static long linkCount(Path path) throws IOException {
        if (path.getFileSystem() == FileSystems.getDefault() && File.separatorChar == '\\') {
            return Windows.linkCount(path);
        }
        try {
            if (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) instanceof Number count) {
                return count.longValue();
            }
        } catch (UnsupportedOperationException _) {
            return -1L;
        }
        return -1L;
    }

    /// Guards cleanup with the stable lease and the lock used by older SDKs.
    ///
    /// The action receives the bound compatibility lock parent.
    ///
    /// @param root physical cache root.
    /// @param legacy version lock in the configured download tree.
    /// @param action action to run under the lock with the bound parent directory.
    /// @return whether the action ran.
    /// @throws IOException when a directory or lock cannot be safely bound.
    static boolean withVersionLock(Path root, Path legacy, VersionAction action) throws IOException {
        return withVersionLock(root, legacy, null, action);
    }

    /// Runs cleanup under version locks bound to the snapshot root.
    static boolean withVersionLock(Path root, Path legacy, @Nullable Object expectedRoot, VersionAction action)
            throws IOException {
        return atParent(root, Path.of(".lease"), expectedRoot, (rootDirectory, _) -> {
            Object rootIdentity = rootDirectory.identity();
            VersionLease version = resolveVersionLease(rootDirectory, rootIdentity, legacy, true);
            LockedAction underCompatibilityLock = () ->
                    withVersionLockParent(root, legacy, rootIdentity, version, false, (parent, name) ->
                            withBoundLock(parent, name, version.existingOnly(), locked -> {
                                action.run(locked);
                                return true;
                            }));
            @Nullable Path leaseName = version.name();
            if (leaseName == null) {
                return underCompatibilityLock.run();
            }
            return withBoundLock(rootDirectory, leaseName, version.existingOnly(), _ -> underCompatibilityLock.run());
        });
    }

    /// Returns the canonical key used to order and deduplicate version acquisitions.
    static Path versionLeaseKey(Path root, Path legacy) throws IOException {
        return atParent(root, Path.of(".lease"), (rootDirectory, _) -> {
            VersionLease version = resolveVersionLease(rootDirectory, rootDirectory.identity(), legacy, false);
            @Nullable Path lease = version.name();
            return lease != null ? lease : CacheLock.canonicalVersionLock(root, legacy);
        });
    }

    /// Acquires a version guard for a caller which does not need to keep the directory bound.
    static @Nullable CacheLock acquireVersion(Path root, Path legacy) throws IOException {
        return atParent(root, Path.of(".lease"), (rootDirectory, _) -> {
            Object rootIdentity = rootDirectory.identity();
            VersionLease version = resolveVersionLease(rootDirectory, rootIdentity, legacy, false);
            @Nullable Path leaseName = version.name();
            if (leaseName == null) {
                return withVersionLockParent(root, legacy, rootIdentity, version, true,
                        (parent, name) -> acquireBoundLock(parent, name, true));
            }
            @Nullable CacheLock lease = acquireBoundLock(rootDirectory, leaseName, version.existingOnly());
            if (lease == null) {
                return null;
            }
            boolean transferred = false;
            try {
                @Nullable CacheLock compatibility = withVersionLockParent(root, legacy, rootIdentity, version, true,
                        (parent, name) -> acquireBoundLock(parent, name, version.existingOnly()));
                if (compatibility != null) {
                    compatibility.attachRootLease(lease);
                    transferred = true;
                }
                return compatibility;
            } finally {
                if (!transferred) {
                    lease.close();
                }
            }
        });
    }

    /// Resolves the root lease, reusing an established compatibility lock for cleanup when possible.
    private static VersionLease resolveVersionLease(Directory rootDirectory, Object rootIdentity, Path legacy,
            boolean cleanup) throws IOException {
        Path root = rootDirectory.path();
        rootDirectory.verifyIdentity();
        Path canonical = CacheLock.canonicalVersionLock(root, legacy);
        rootDirectory.verifyIdentity();
        @Nullable Object legacyIdentity = existingLockParent(root, legacy, rootIdentity, !cleanup);
        if (legacyIdentity != null) {
            @Nullable Path lease = existingLease(rootDirectory, canonical);
            if (lease != null || cleanup || !Files.isWritable(root)) {
                // With no established lease, the compatibility lock serializes both old and current writers.
                return new VersionLease(lease, legacyIdentity);
            }
        }
        try {
            NameEquivalence equivalence = rootDirectory.stream() == null && rootDirectory.pin() == null
                    ? nameEquivalence(root) : probeNames(rootDirectory);
            Path lease = CacheLock.versionLeaseName(canonical, equivalence.caseInsensitive(),
                    equivalence.normalizationInsensitive());
            return new VersionLease(lease, null);
        } catch (AccessDeniedException failure) {
            if (legacyIdentity == null) {
                throw failure;
            }
            rootDirectory.verifyIdentity();
            return new VersionLease(existingLease(rootDirectory, canonical), legacyIdentity);
        }
    }

    /// Describes the resolved root lease for a cache version.
    ///
    /// @param name root lease filename, or null when the compatibility lock is sufficient.
    /// @param existingParent verified legacy lock parent when opening locks without creating files.
    @NotNullByDefault
    private record VersionLease(@Nullable Path name, @Nullable Object existingParent) {
        /// Indicates that the lock files must already exist.
        private boolean existingOnly() {
            return existingParent != null;
        }
    }

    /// Finds the bound parent when the legacy lock is already present.
    private static @Nullable Object existingLockParent(Path root, Path legacy, Object expectedRoot,
            boolean allowMountedDownloads) throws IOException {
        try {
            return atParent(root, legacy, expectedRoot, allowMountedDownloads, (parent, name) -> {
                if (!parent.attributes(name).isRegularFile()) {
                    throw new FileSystemException(parent.path().resolve(name).toString(), null,
                            "Expected a regular cache lock");
                }
                return parent.identity();
            });
        } catch (NoSuchFileException _) {
            return null;
        }
    }

    /// Uses persistent lock names to determine equivalences without allocating a probe.
    private static @Nullable Path existingLease(Directory root, Path canonical) throws IOException {
        for (boolean foldCase : new boolean[]{false, true}) {
            for (boolean normalizeUnicode : new boolean[]{false, true}) {
                Path candidate = CacheLock.versionLeaseName(canonical, foldCase, normalizeUnicode);
                @Nullable Object identity = root.lockIdentity(candidate);
                if (identity == null) {
                    if (probeExists(root, candidate)) {
                        throw new IOException("Cannot identify existing cache lease: " + root.path().resolve(candidate));
                    }
                    continue;
                }
                Path alternate = Path.of(candidate.toString().toUpperCase(Locale.ROOT));
                boolean folds = identity.equals(root.lockIdentity(alternate));
                Path unnormalized = CacheLock.versionLeaseName(canonical, folds, false);
                Path normalized = CacheLock.versionLeaseName(canonical, folds, true);
                Path lease = unnormalized.equals(normalized) || !normalizesLockSpelling(root.path(), canonical)
                        ? unnormalized : normalized;
                return root.lockIdentity(lease) != null ? lease : null;
            }
        }
        return null;
    }

    /// Checks whether a stored lock path component also resolves under an NFC spelling.
    private static boolean normalizesLockSpelling(Path root, Path canonical) throws IOException {
        Path parent = root;
        for (Path name : canonical) {
            String normalized = Normalizer.normalize(name.toString(), Normalizer.Form.NFC);
            Path original = parent.resolve(name);
            if (!normalized.equals(name.toString())) {
                try {
                    BasicFileAttributes originalAttributes =
                            Files.readAttributes(original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    Path alternate = parent.resolve(normalized);
                    try {
                        BasicFileAttributes alternateAttributes =
                                Files.readAttributes(alternate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        @Nullable Object originalIdentity = fileIdentity(original, originalAttributes);
                        if (originalIdentity == null) {
                            throw new IOException("Cannot identify cache lock name equivalence: " + original);
                        }
                        return originalIdentity.equals(fileIdentity(alternate, alternateAttributes));
                    } catch (NoSuchFileException _) {
                        return false;
                    }
                } catch (NoSuchFileException _) {
                    // Try another existing component if the canonical lock is not present under this spelling.
                }
            }
            parent = original;
        }
        throw new IOException("Cannot determine cache lock name equivalence: " + root.resolve(canonical));
    }

    /// Opens the compatibility lock parent after acquiring any required root lease.
    private static <T> T withVersionLockParent(Path root, Path legacy, Object rootIdentity, VersionLease version,
            boolean allowMountedDownloads, ParentAction<T> action) throws IOException {
        @Nullable Object existingParent = version.existingParent();
        if (existingParent == null) {
            ensureLockParent(root, legacy, rootIdentity, allowMountedDownloads);
        }
        return atParent(root, legacy, rootIdentity, allowMountedDownloads, (parent, name) -> {
            if (existingParent != null) {
                checkLockParent(parent, existingParent);
            }
            return action.run(parent, name);
        });
    }

    /// Guards cleanup with the stable lease and the lock used by older SDKs.
    ///
    /// @param root physical cache root.
    /// @param legacy version lock in the configured download tree.
    /// @param action action to run under the lock.
    /// @return whether the action ran.
    /// @throws IOException when a directory or lock cannot be safely bound.
    static boolean withVersionLock(Path root, Path legacy, Runnable action) throws IOException {
        return withVersionLock(root, legacy, _ -> action.run());
    }

    /// Action run while a version lock is held and its parent directory remains bound.
    @NotNullByDefault
    @FunctionalInterface
    interface VersionAction {
        /// Runs the operation with the bound lock parent.
        ///
        /// @param parent bound parent directory of the version lock.
        /// @throws IOException when the operation fails.
        void run(Directory parent) throws IOException;
    }

    /// Runs an operation while the specified lock is held.
    @NotNullByDefault
    @FunctionalInterface
    private interface LockedAction {
        /// Returns whether the requested operation ran.
        boolean run() throws IOException;
    }

    /// Acquires a lock in a bound directory and returns the action's status.
    private static boolean withLock(Path root, Path relative, Object expectedParent, LockedAction action)
            throws IOException {
        return withLock(root, relative, expectedParent, _ -> action.run());
    }

    /// Acquires a lock in a bound directory and runs an action with that directory.
    private static boolean withLock(Path root, Path relative, Object expectedParent, LockedDirectoryAction action)
            throws IOException {
        return atParent(root, relative, (parent, name) -> {
            checkLockParent(parent, expectedParent);
            return withBoundLock(parent, name, false, action);
        });
    }

    /// Rejects a replacement of the compatibility lock parent.
    private static void checkLockParent(Directory parent, Object identity) throws IOException {
        if (!identity.equals(parent.identity())) {
            throw new FileSystemException(parent.path().toString(), null, "Cache lock parent was replaced");
        }
    }

    /// Holds one lock whose parent is already bound.
    private static boolean withBoundLock(Directory parent, Path name, boolean existingOnly,
            LockedDirectoryAction action) throws IOException {
        try (@Nullable CacheLock lock = acquireBoundLock(parent, name, existingOnly)) {
            return lock != null && action.run(parent);
        }
    }

    /// Acquires a lock through an already bound parent and verifies the original entry after locking.
    private static @Nullable CacheLock acquireBoundLock(Directory parent, Path name, boolean existingOnly)
            throws IOException {
        parent.verifyIdentity();
        @Nullable Object identity = parent.lockIdentity(name);
        @Nullable CacheLock lock = CacheLock.acquireBound(
                parent.path().resolve(name), () -> parent.lockIdentity(name),
                () -> parent.openLock(name, existingOnly));
        if (lock == null) {
            return null;
        }
        try {
            parent.verifyIdentity();
            if (identity != null && !identity.equals(parent.lockIdentity(name))) {
                throw new FileSystemException(parent.path().resolve(name).toString(), null,
                        "Cache lock identity changed");
            }
            return lock;
        } catch (IOException | RuntimeException failure) {
            try {
                lock.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /// Runs one locked action that needs the verified parent directory.
    @NotNullByDefault
    @FunctionalInterface
    private interface LockedDirectoryAction {
        /// Runs the operation with the bound parent directory.
        boolean run(Directory parent) throws IOException;
    }

    /// Executes an operation on a name with its parent still bound.
    @NotNullByDefault
    @FunctionalInterface
    private interface ParentAction<T> {
        /// Runs one operation inside the bound directory.
        T run(Directory parent, Path name) throws IOException;
    }

    /// Binds the root and descends through real directories without following links.
    private static <T> T atParent(Path root, Path relative, ParentAction<T> action) throws IOException {
        return atParent(root, relative, null, action);
    }

    /// Binds and verifies the actual root used to reach an operation's parent.
    private static <T> T atParent(Path root, Path relative, @Nullable Object expectedRoot, ParentAction<T> action)
            throws IOException {
        return atParent(root, relative, expectedRoot, false, action);
    }

    /// Binds an operation's parent, optionally allowing the top-level downloads directory to be mounted.
    private static <T> T atParent(Path root, Path relative, @Nullable Object expectedRoot,
            boolean allowMountedDownloads, ParentAction<T> action) throws IOException {
        if (relative.isAbsolute() || relative.toString().isEmpty() || relative.startsWith("..")
                || !relative.equals(relative.normalize())) {
            throw new FileSystemException(relative.toString(), null, "Invalid cache-relative path");
        }
        if (root.getFileSystem() == FileSystems.getDefault()
                && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            @Nullable Path volume = root.getRoot();
            if (volume == null) {
                throw new FileSystemException(root.toString(), null, "Expected an absolute cache root");
            }
            try (Directory start = new Directory(volume, null, null)) {
                return atParent(start, volume.relativize(root.resolve(relative)), 0, root.getNameCount(),
                        expectedRoot, false, action);
            }
        }
        try (Directory start = Directory.openRoot(root)) {
            return atParent(start, relative, 0, 0, expectedRoot, allowMountedDownloads, action);
        }
    }

    /// Opens each ancestor in order before running the operation.
    private static <T> T atParent(Directory parent, Path path, int level, int rootDepth,
            @Nullable Object expectedRoot, boolean allowMountedDownloads, ParentAction<T> action) throws IOException {
        if (level == rootDepth) {
            parent.checkRootIdentity(expectedRoot);
        }
        Path name = path.getName(level);
        if (level == path.getNameCount() - 1) {
            return action.run(parent, name);
        }
        try (Directory child = parent.open(name, false, allowMountedDownloads && level == rootDepth)) {
            return atParent(child, path, level + 1, rootDepth, expectedRoot, allowMountedDownloads, action);
        }
    }

    /// Deletes a directory's children before deleting the directory entry itself.
    static void removeEntry(
            Directory parent, Path name, Target target, RemovedFile removedFile, BiConsumer<Path, Throwable> skipped) {
        Path path = parent.path().resolve(name);
        try {
            BasicFileAttributes attributes = parent.attributes(name);
            if (!attributes.isDirectory()) {
                if (target == Target.DIRECTORY) {
                    throw new FileSystemException(path.toString(), null, "Expected a cache version directory");
                }
                if (target == Target.REGULAR_FILE && !attributes.isRegularFile()) {
                    throw new FileSystemException(path.toString(), null, "Expected a regular cache file");
                }
                long released = attributes.isRegularFile() && parent.linkCount(name, attributes) != 1L
                        ? 0L : attributes.size();
                parent.delete(name, false, attributes.isRegularFile());
                removedFile.accept(released);
                return;
            }
            if (target == Target.FILE || target == Target.REGULAR_FILE) {
                throw new FileSystemException(path.toString(), null, "Expected a cache file");
            }
            try (Directory child = parent.open(name);
                 DirectoryStream<Path> contents = child.contents()) {
                for (Path entry : contents) {
                    removeEntry(child, entry.getFileName(), Target.TREE, removedFile, skipped);
                }
            }
            parent.delete(name, true, false);
        } catch (NoSuchFileException _) {
            // Another operation already removed this entry.
        } catch (IOException | DirectoryIteratorException exception) {
            skipped.accept(path, exception);
        }
    }

    /// Holds either a secure directory stream or a Windows handle that prevents renames and writes.
    ///
    /// Entries created, moved, and deleted through this handle stay relative to the directory that
    /// was verified when the handle was opened, so the operations are used for marker publication
    /// and for deleting the version whose lock is held.
    ///
    /// @param path physical directory path used for diagnostics and pinned Windows operations.
    /// @param stream secure directory stream on POSIX filesystems.
    /// @param pin held Windows handle for a non-root directory.
    /// @param descriptor POSIX descriptor used to open child directories, or -1.
    /// @param mount Linux mount ID or macOS device number of the directory, or -1.
    @NotNullByDefault
    record Directory(
            Path path,
            @Nullable SecureDirectoryStream<Path> stream,
            @Nullable MemorySegment pin,
            int descriptor,
            long mount) implements AutoCloseable {

        /// Creates a directory binding before any mount or device checks.
        private Directory(Path path, @Nullable SecureDirectoryStream<Path> stream, @Nullable MemorySegment pin) {
            this(path, stream, pin, -1, -1L);
        }

        /// Verifies that this binding starts at the expected cache root.
        private void checkRootIdentity(@Nullable Object expectedRoot) throws IOException {
            if (expectedRoot != null && !expectedRoot.equals(identity())) {
                throw new FileSystemException(path.toString(), null, "Cache root changed during selection");
            }
        }

        /// Opens the cache root directly so its POSIX ancestors need only grant search access.
        private static Directory openRoot(Path root) throws IOException {
            if (LINUX && root.getFileSystem() == FileSystems.getDefault()) {
                return Posix.openLinux(root, -1, root, -1L);
            }
            BasicFileAttributes expected = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!expected.isDirectory() || expected.fileKey() == null) {
                throw new FileSystemException(root.toString(), null, "Cannot identify the physical cache root");
            }
            DirectoryStream<Path> opened = Files.newDirectoryStream(root);
            boolean accepted = false;
            try {
                if (opened instanceof SecureDirectoryStream<Path> secure) {
                    Directory bound = new Directory(root, secure, null);
                    if (expected.fileKey().equals(bound.fileKey())) {
                        Directory checked = MAC ? Posix.openMac(root, secure, -1, root, -1L) : bound;
                        accepted = true;
                        return checked;
                    }
                }
                throw new FileSystemException(root.toString(), null, "Cannot safely bind the physical cache root");
            } finally {
                if (!accepted) {
                    opened.close();
                }
            }
        }

        /// Opens one real child directory relative to its bound parent.
        Directory open(Path name) throws IOException {
            return open(name, false);
        }

        /// Opens a child with the requested Windows sharing behavior.
        private Directory open(Path name, boolean shareDelete) throws IOException {
            return open(name, shareDelete, false);
        }

        /// Opens a child directory with an optional exception for the downloads mount.
        private Directory open(Path name, boolean shareDelete, boolean allowMount) throws IOException {
            Path child = path.resolve(name);
            long expectedMount = allowMount ? -1L : mount;
            if (LINUX && descriptor >= 0) {
                return Posix.openLinux(child, descriptor, name, expectedMount);
            }
            if (stream != null) {
                SecureDirectoryStream<Path> opened = stream.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                if (!MAC) {
                    return new Directory(child, opened, null);
                }
                boolean accepted = false;
                try {
                    Directory bound = Posix.openMac(child, opened, descriptor, name, expectedMount);
                    accepted = true;
                    return bound;
                } finally {
                    if (!accepted) {
                        opened.close();
                    }
                }
            }
            Windows.checkComponent(name);
            return new Directory(child, null, Windows.pin(child, shareDelete));
        }

        /// Removes one direct child entry with an expected type.
        ///
        /// @param name child name.
        /// @param target expected entry type.
        /// @param removedFile removed entry receiver.
        /// @param skipped unremovable paths.
        void remove(Path name, Target target, RemovedFile removedFile, BiConsumer<Path, Throwable> skipped) {
            removeEntry(this, name, target, removedFile, skipped);
        }

        /// Lists the names of this bound directory's children.
        ///
        /// @return immutable child names.
        /// @throws IOException when the directory cannot be listed.
        @Unmodifiable List<Path> names() throws IOException {
            if (stream == null) {
                verifyIdentity();
            }
            ArrayList<Path> names = new ArrayList<>();
            try (DirectoryStream<Path> contents = contents()) {
                for (Path entry : contents) {
                    names.add(entry.getFileName());
                }
            } catch (DirectoryIteratorException exception) {
                throw exception.getCause();
            }
            if (stream == null) {
                verifyIdentity();
            }
            return List.copyOf(names);
        }

        /// Checks the type of a child through the bound directory.
        boolean isRegularFile(Path name) throws IOException {
            return stream != null ? attributes(name).isRegularFile() : Windows.isRegularFile(requirePin(), name);
        }

        /// Reads at most the specified number of bytes from a regular child through the binding.
        byte[] readFile(Path name, int limit) throws IOException {
            if (stream == null) {
                return Windows.readFile(requirePin(), name, limit);
            }
            if (!attributes(name).isRegularFile()) {
                throw new NoSuchFileException(name.toString());
            }
            try (var channel = stream.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 var input = Channels.newInputStream(channel)) {
                return input.readNBytes(limit);
            }
        }

        /// Creates an exclusive child and writes its complete contents.
        void writeNewFile(Path name, byte[] contents) throws IOException {
            if (stream == null) {
                Windows.writeNewFile(requirePin(), name, contents);
                return;
            }
            try (var channel = stream.newByteChannel(name, Set.of(StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                ByteBuffer buffer = ByteBuffer.wrap(contents);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
        }

        /// Moves a child to another name in the same bound directory.
        void moveFile(Path source, Path target) throws IOException {
            if (stream != null) {
                stream.move(source, stream, target);
            } else {
                Windows.moveFile(requirePin(), source, target);
            }
        }

        /// Deletes a file or link in this bound directory.
        void deleteFile(Path name) throws IOException {
            if (stream != null) {
                stream.deleteFile(name);
            } else {
                Windows.deleteFile(requirePin(), name);
            }
        }

        /// Returns the pinned Windows directory handle.
        private MemorySegment requirePin() throws IOException {
            if (pin == null) {
                throw new IOException("Cache directory is not pinned: " + path);
            }
            return pin;
        }

        /// Returns the identity of an existing lock, rejecting non-regular entries.
        private @Nullable Object lockIdentity(Path name) throws IOException {
            try {
                BasicFileAttributes attributes = attributes(name);
                if (!attributes.isRegularFile()) {
                    throw new FileSystemException(path.resolve(name).toString(), null, "Expected a regular lock file");
                }
                return fileIdentity(path.resolve(name), attributes);
            } catch (NoSuchFileException _) {
                return null;
            }
        }

        /// Opens a lock, optionally requiring an already allocated directory entry.
        private FileChannel openLock(Path name, boolean existingOnly) throws IOException {
            Set<OpenOption> options = existingOnly
                    ? Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                    : Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            if (stream == null) {
                return FileChannel.open(path.resolve(name), options);
            }
            var opened = stream.newByteChannel(name, options);
            if (opened instanceof FileChannel channel) {
                return channel;
            }
            opened.close();
            throw new FileSystemException(path.resolve(name).toString(), null, "File locking is unavailable");
        }

        /// Confirms the bound directory still occupies the named location.
        void verifyIdentity() throws IOException {
            if (stream == null) {
                if (pin == null) {
                    return; // Windows volume roots cannot be renamed or deleted.
                }
                // Recheck the named location because other handles can modify child entries.
                if (!Windows.identity(pin).equals(Windows.probeIdentity(path))) {
                    throw new FileSystemException(path.toString(), null, "Cache directory identity changed");
                }
                return;
            }
            @Nullable Object expected = fileKey();
            BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (expected == null || !expected.equals(current.fileKey()) || !current.isDirectory()
                    || !path.equals(path.toRealPath())) {
                throw new FileSystemException(path.toString(), null, "Cache directory identity changed");
            }
        }

        /// Returns the identity of this directory handle.
        private Object identity() throws IOException {
            @Nullable Object key = pin == null ? fileKey() : Windows.identity(pin);
            if (key == null && stream == null && pin == null) {
                // The volume root has no JDK file key, so query a native handle.
                return Windows.probeIdentity(path);
            }
            if (key == null) {
                throw new FileSystemException(path.toString(), null, "Directory identity is unavailable");
            }
            return mount < 0L ? key : new MountedIdentity(key, mount);
        }

        /// Returns the identity of the open POSIX directory rather than resolving its name.
        private @Nullable Object fileKey() throws IOException {
            if (stream == null) {
                return null;
            }
            @Nullable BasicFileAttributeView view = stream.getFileAttributeView(BasicFileAttributeView.class);
            return view == null ? null : view.readAttributes().fileKey();
        }

        /// Lists children of this bound directory.
        private DirectoryStream<Path> contents() throws IOException {
            if (stream != null) {
                // The caller also owns this Directory. Use an independent descriptor for iteration.
                return stream.newDirectoryStream(path.getFileSystem().getPath("."));
            }
            return Files.newDirectoryStream(path);
        }

        /// Reads the attributes of one entry without following a link.
        BasicFileAttributes attributes(Path name) throws IOException {
            if (stream != null) {
                @Nullable BasicFileAttributeView view =
                        stream.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (view == null) {
                    throw new FileSystemException(path.resolve(name).toString(), null, "File attributes are unavailable");
                }
                return view.readAttributes();
            }
            Windows.checkComponent(name);
            return Files.readAttributes(path.resolve(name), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        /// Reads the number of links for the entry if its named location can still be verified.
        private long linkCount(Path name, BasicFileAttributes expected) {
            try {
                verifyIdentity();
                long links = CacheTreeRemoval.linkCount(path.resolve(name));
                verifyIdentity();
                BasicFileAttributes current = attributes(name);
                @Nullable Object identity = expected.fileKey();
                return identity == null || identity.equals(current.fileKey()) ? links : -1L;
            } catch (IOException _) {
                return -1L;
            }
        }

        /// Deletes the entry from the bound parent using the expected type when available.
        private void delete(Path name, boolean directory, boolean regular) throws IOException {
            if (directory) {
                if (stream != null) {
                    stream.deleteDirectory(name);
                } else if (pin != null) {
                    Windows.deleteDirectory(pin, name);
                } else {
                    Files.delete(path.resolve(name));
                }
            } else if (stream != null || (pin != null && regular)) {
                deleteFile(name);
            } else {
                Files.delete(path.resolve(name));
            }
        }

        /// Releases the directory binding.
        @Override
        public void close() throws IOException {
            if (stream != null) {
                try {
                    stream.close();
                } finally {
                    if (descriptor >= 0) {
                        Posix.closeDescriptor(descriptor);
                    }
                }
            }
            if (pin != null) {
                Windows.close(pin);
            }
        }
    }

    /// Holds Windows directory handles without delete or write sharing while their children are processed.
    @NotNullByDefault
    private static final class Windows {
        /// Native Windows filesystem functions.
        private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

        /// Captured Win32 error state for CreateFileW.
        private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();

        /// Reads GetLastError from the captured state before other native calls can overwrite it.
        private static final VarHandle LAST_ERROR = CALL_STATE.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));

        /// FILE_TRAVERSE, allowing ancestors without listing or attribute-read access.
        private static final int DIRECTORY_ACCESS = 0x20;

        /// FILE_SHARE_READ, excluding write and delete sharing.
        private static final int SHARE_READ = 0x01;

        /// FILE_SHARE_READ, FILE_SHARE_WRITE and FILE_SHARE_DELETE.
        private static final int SHARE_ALL = 0x07;

        /// OPEN_EXISTING disposition.
        private static final int OPEN_EXISTING = 3;

        /// FILE_FLAG_BACKUP_SEMANTICS and FILE_FLAG_OPEN_REPARSE_POINT.
        private static final int DIRECTORY_FLAGS = 0x02000000 | 0x00200000;

        /// FileBasicInfo selector for GetFileInformationByHandleEx.
        private static final int BASIC_INFO = 0;

        /// FileIdInfo selector for GetFileInformationByHandleEx.
        private static final int FILE_ID_INFO = 18;

        /// FILE_ATTRIBUTE_DIRECTORY.
        private static final int DIRECTORY_ATTRIBUTE = 0x10;

        /// FILE_ATTRIBUTE_REPARSE_POINT.
        private static final int REPARSE_ATTRIBUTE = 0x400;

        /// ERROR_FILE_NOT_FOUND and ERROR_PATH_NOT_FOUND indicate a vanished entry.
        private static final int FILE_NOT_FOUND = 2, PATH_NOT_FOUND = 3;

        /// Opens a file or directory with explicit sharing rules.
        private static final MethodHandle CREATE_FILE = Linker.nativeLinker().downcallHandle(
                KERNEL32.find("CreateFileW").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS),
                Linker.Option.captureCallState("GetLastError"));

        /// Reads Win32 attributes from the open handle.
        private static final MethodHandle FILE_INFO = Linker.nativeLinker().downcallHandle(
                KERNEL32.find("GetFileInformationByHandleEx").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));

        /// Reads the legacy BY_HANDLE_FILE_INFORMATION structure, including on FAT volumes.
        private static final MethodHandle LEGACY_FILE_INFO = Linker.nativeLinker().downcallHandle(
                KERNEL32.find("GetFileInformationByHandle").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

        /// Closes an open handle.
        private static final MethodHandle CLOSE_HANDLE = Linker.nativeLinker().downcallHandle(
                KERNEL32.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));

        /// Native NT file APIs support names relative to a directory handle.
        private static final SymbolLookup NTDLL = SymbolLookup.libraryLookup("ntdll", Arena.global());

        /// Opens or creates a child relative to OBJECT_ATTRIBUTES.RootDirectory.
        private static final MethodHandle NT_CREATE_FILE = Linker.nativeLinker().downcallHandle(
                NTDLL.find("NtCreateFile").orElseThrow(), FunctionDescriptor.of(JAVA_INT,
                        ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
                        JAVA_INT, ADDRESS, JAVA_INT));

        /// Updates an opened child's name or deletion disposition.
        private static final MethodHandle NT_SET_INFORMATION = Linker.nativeLinker().downcallHandle(
                NTDLL.find("NtSetInformationFile").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));

        /// Reads the contents of an existing regular file.
        private static final MethodHandle READ_FILE = Linker.nativeLinker().downcallHandle(
                KERNEL32.find("ReadFile").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                Linker.Option.captureCallState("GetLastError"));

        /// Writes the contents of a new marker.
        private static final MethodHandle WRITE_FILE = Linker.nativeLinker().downcallHandle(
                KERNEL32.find("WriteFile").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

        /// Opens a file relative to a pinned directory without following a link.
        private static MemorySegment openChild(MemorySegment parent, Path name, int access, int disposition,
                                               Arena arena) throws IOException {
            return openChild(parent, name, access, disposition, 0x40, arena);
        }

        /// Opens a child with the expected type and without following a link.
        private static MemorySegment openChild(MemorySegment parent, Path name, int access, int disposition,
                                               int typeOption, Arena arena) throws IOException {
            checkComponent(name);
            if (name.getNameCount() != 1 || name.isAbsolute() || !name.equals(name.normalize())) {
                throw new IOException("Invalid cache marker name: " + name);
            }
            byte[] encoded = name.toString().getBytes(StandardCharsets.UTF_16LE);
            MemorySegment unicode = arena.allocate(16, 8);
            unicode.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 0, (short) encoded.length);
            unicode.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 2, (short) encoded.length);
            unicode.set(ADDRESS, 8, arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, encoded));
            MemorySegment attributes = arena.allocate(48, 8);
            attributes.set(JAVA_INT, 0, 48);
            attributes.set(ADDRESS, 8, parent);
            attributes.set(ADDRESS, 16, unicode);
            attributes.set(JAVA_INT, 24, 0x40); // OBJ_CASE_INSENSITIVE
            MemorySegment out = arena.allocate(ADDRESS);
            try {
                int openOptions = 0x00200020 | typeOption;
                int status = (int) NT_CREATE_FILE.invokeExact(out, access | 0x100000, attributes,
                        arena.allocate(16, 8), MemorySegment.NULL, 0x80, SHARE_ALL, disposition,
                        openOptions, MemorySegment.NULL, 0);
                checkStatus(status, name);
                return out.get(ADDRESS, 0);
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable failure) {
                throw new IOException("Cannot open bound cache marker: " + name, failure);
            }
        }

        /// Converts NT file-operation failures into IO exceptions.
        private static void checkStatus(int status, Path name) throws IOException {
            if (status == 0xC0000034 || status == 0xC000003A || status == 0xC000000F) {
                throw new NoSuchFileException(name.toString());
            }
            if (status == 0xC0000035) {
                throw new FileAlreadyExistsException(name.toString());
            }
            if (status < 0) {
                throw new FileSystemException(name.toString(), null, "NTSTATUS 0x" + Integer.toHexString(status));
            }
        }

        /// Recognizes regular files from the relative handle without traversing a reparse point.
        private static boolean isRegularFile(MemorySegment parent, Path name) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment child = openChild(parent, name, 0x80, 1, 0, arena);
                try {
                    return regularHandle(child, name, arena);
                } finally {
                    close(child);
                }
            }
        }

        /// Reads only from a regular file opened relative to the pinned directory.
        private static byte[] readFile(MemorySegment parent, Path name, int limit) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment child = openChild(parent, name, 0x81, 1, 0, arena);
                try {
                    if (!regularHandle(child, name, arena)) {
                        throw new NoSuchFileException(name.toString());
                    }
                    MemorySegment state = arena.allocate(CALL_STATE);
                    MemorySegment buffer = arena.allocate(limit);
                    MemorySegment read = arena.allocate(JAVA_INT);
                    if ((int) READ_FILE.invokeExact(state, child, buffer, limit, read, MemorySegment.NULL) == 0) {
                        int error = (int) LAST_ERROR.get(state, 0L);
                        if (error == 38) { // ERROR_HANDLE_EOF
                            return new byte[0];
                        }
                        throw new IOException("Cannot read bound cache file (Windows error " + error + "): " + name);
                    }
                    return buffer.asSlice(0, read.get(JAVA_INT, 0)).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                } finally {
                    close(child);
                }
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable failure) {
                throw new IOException("Cannot read bound cache file: " + name, failure);
            }
        }

        /// Determines whether an opened Windows file handle is neither a directory nor a reparse point.
        private static boolean regularHandle(MemorySegment child, Path name, Arena arena) throws IOException {
            try {
                return (attributes(child, arena) & (DIRECTORY_ATTRIBUTE | REPARSE_ATTRIBUTE)) == 0;
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable failure) {
                throw new IOException("Cannot read attributes of bound cache file: " + name, failure);
            }
        }

        /// Writes a newly created marker through its relative handle.
        private static void writeNewFile(MemorySegment parent, Path name, byte[] bytes) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment handle = openChild(parent, name, 2, 2, arena);
                try {
                    if (bytes.length == 0) {
                        return;
                    }
                    MemorySegment written = arena.allocate(JAVA_INT);
                    if ((int) WRITE_FILE.invokeExact(handle,
                            arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, bytes), bytes.length,
                            written, MemorySegment.NULL) == 0 || written.get(JAVA_INT, 0) != bytes.length) {
                        throw new IOException("Cannot write bound cache marker: " + name);
                    }
                } finally {
                    close(handle);
                }
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable failure) {
                throw new IOException("Cannot write bound cache marker: " + name, failure);
            }
        }

        /// Publishes a marker by renaming the opened entry within its existing parent.
        private static void moveFile(MemorySegment parent, Path source, Path target) throws IOException {
            checkComponent(target);
            byte[] encoded = target.toString().getBytes(StandardCharsets.UTF_16LE);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment info = arena.allocate(24L + encoded.length, 8);
                info.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 1);
                info.set(JAVA_INT, 16, encoded.length);
                info.asSlice(20, encoded.length).copyFrom(MemorySegment.ofArray(encoded));
                setChildInformation(parent, source, info, 10, false, arena);
            }
        }

        /// Deletes a file relative to the parent handle.
        private static void deleteFile(MemorySegment parent, Path name) throws IOException {
            deleteChild(parent, name, false);
        }

        /// Deletes a directory relative to the parent handle with a directory-only native open.
        private static void deleteDirectory(MemorySegment parent, Path name) throws IOException {
            deleteChild(parent, name, true);
        }

        /// Marks an entry of the expected type for deletion when its handle closes.
        private static void deleteChild(MemorySegment parent, Path name, boolean directory) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment info = arena.allocate(java.lang.foreign.ValueLayout.JAVA_BYTE);
                info.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 1);
                setChildInformation(parent, name, info, 13, directory, arena);
            }
        }

        /// Updates one child opened with DELETE access relative to its directory handle.
        private static void setChildInformation(MemorySegment parent, Path name, MemorySegment info, int kind,
                                                boolean directory, Arena arena) throws IOException {
            MemorySegment handle = openChild(parent, name, 0x10000, 1, directory ? 0x01 : 0x40, arena);
            try {
                int status = (int) NT_SET_INFORMATION.invokeExact(handle, arena.allocate(16, 8), info,
                        (int) info.byteSize(), kind);
                checkStatus(status, name);
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable failure) {
                throw new IOException("Cannot update bound cache marker: " + name, failure);
            } finally {
                close(handle);
            }
        }

        /// Rejects names that Win32 can collapse to a different entry without an extended path.
        private static void checkComponent(Path name) throws IOException {
            String component = name.toString();
            if (component.endsWith(".") || component.endsWith(" ")) {
                throw new FileSystemException(component, null, "Ambiguous Windows cache entry name");
            }
        }

        /// Converts an absolute path to the extended Windows namespace.
        private static String extendedPath(Path path) {
            String name = path.toString();
            return name.startsWith("\\\\")
                    ? "\\\\?\\UNC\\" + name.substring(2)
                    : "\\\\?\\" + name;
        }

        /// Queries the link count without following a reparse point at the final component.
        ///
        /// @param path inspected file.
        /// @return link count, or -1 when the file cannot be opened.
        private static long linkCount(Path path) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(CALL_STATE);
                int readAttributes = 0x80;
                int shareAll = 0x07;
                int openReparsePoint = 0x00200000;
                MemorySegment handle = (MemorySegment) CREATE_FILE.invokeExact(
                        state, arena.allocateFrom(extendedPath(path), StandardCharsets.UTF_16LE),
                        readAttributes, shareAll, MemorySegment.NULL, OPEN_EXISTING, openReparsePoint, MemorySegment.NULL);
                if (handle.address() == -1L) {
                    return -1L;
                }
                try {
                    // FILE_STANDARD_INFO contains two 64-bit sizes followed by the link count.
                    int fileStandardInfo = 1;
                    MemorySegment info = arena.allocate(24, 8);
                    if ((int) FILE_INFO.invokeExact(handle, fileStandardInfo, info, (int) info.byteSize()) == 0) {
                        return -1L;
                    }
                    return Integer.toUnsignedLong(info.get(JAVA_INT, 16));
                } finally {
                    close(handle);
                }
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable exception) {
                throw new IOException("Cannot read file link count: " + path, exception);
            }
        }

        /// Opens a real directory, optionally allowing its entries to be renamed.
        ///
        /// @param path directory path.
        /// @param shareDelete whether renames and deletions may proceed while the handle is held.
        /// @return held directory handle.
        /// @throws IOException when the directory cannot be opened.
        private static MemorySegment pin(Path path, boolean shareDelete) throws IOException {
            String name = path.toString();
            int share = shareDelete ? SHARE_ALL : SHARE_READ;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(CALL_STATE);
                MemorySegment handle = (MemorySegment) CREATE_FILE.invokeExact(
                        state, arena.allocateFrom(extendedPath(path), StandardCharsets.UTF_16LE),
                        DIRECTORY_ACCESS, share, MemorySegment.NULL, OPEN_EXISTING, DIRECTORY_FLAGS, MemorySegment.NULL);
                if (handle.address() == -1L) {
                    int error = (int) LAST_ERROR.get(state, 0L);
                    if (error == FILE_NOT_FOUND || error == PATH_NOT_FOUND) {
                        throw new NoSuchFileException(name);
                    }
                    throw new FileSystemException(name, null, "Cannot pin cache directory (Windows error " + error + ")");
                }
                boolean pinned = false;
                try {
                    if ((attributes(handle, arena) & (DIRECTORY_ATTRIBUTE | REPARSE_ATTRIBUTE))
                            != DIRECTORY_ATTRIBUTE) {
                        throw new FileSystemException(name, null, "Expected a real cache directory");
                    }
                    pinned = true;
                    return handle;
                } finally {
                    if (!pinned) {
                        close(handle);
                    }
                }
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable exception) {
                throw new IOException("Cannot pin cache directory " + name, exception);
            }
        }

        /// Reads the identity of a directory through a fresh handle so a replaced path is detected.
        ///
        /// @param path directory path.
        /// @return hexadecimal volume and file identity.
        /// @throws IOException when the directory cannot be opened or identified.
        private static String probeIdentity(Path path) throws IOException {
            String name = path.toString();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(CALL_STATE);
                MemorySegment handle = (MemorySegment) CREATE_FILE.invokeExact(
                        state, arena.allocateFrom(extendedPath(path), StandardCharsets.UTF_16LE),
                        DIRECTORY_ACCESS, SHARE_ALL, MemorySegment.NULL, OPEN_EXISTING, DIRECTORY_FLAGS,
                        MemorySegment.NULL);
                if (handle.address() == -1L) {
                    int error = (int) LAST_ERROR.get(state, 0L);
                    if (error == FILE_NOT_FOUND || error == PATH_NOT_FOUND) {
                        throw new NoSuchFileException(name);
                    }
                    throw new FileSystemException(name, null,
                            "Cannot inspect cache directory (Windows error " + error + ")");
                }
                try {
                    return identity(handle);
                } finally {
                    close(handle);
                }
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable exception) {
                throw new IOException("Cannot identify cache directory " + name, exception);
            }
        }

        /// Reads the identity of one file through a fresh handle.
        ///
        /// @param path file path.
        /// @return hexadecimal volume and file identity, or null when the file cannot be opened.
        private static @Nullable String fileIdentity(Path path) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(CALL_STATE);
                int readAttributes = 0x80;
                int openReparsePoint = 0x00200000;
                MemorySegment handle = (MemorySegment) CREATE_FILE.invokeExact(
                        state, arena.allocateFrom(extendedPath(path), StandardCharsets.UTF_16LE),
                        readAttributes, SHARE_ALL, MemorySegment.NULL, OPEN_EXISTING, openReparsePoint,
                        MemorySegment.NULL);
                if (handle.address() == -1L) {
                    return null;
                }
                try {
                    return identity(handle);
                } finally {
                    close(handle);
                }
            } catch (Throwable _) {
                // The caller excludes bytes that cannot be attributed to an identified file.
                return null;
            }
        }

        /// Reads DOS attributes without relying on FileAttributeTagInfo, which FAT may reject.
        private static int attributes(MemorySegment handle, Arena arena) throws Throwable {
            // FILE_BASIC_INFO stores the DOS attributes after four LARGE_INTEGER timestamps.
            MemorySegment info = arena.allocate(40, 8);
            if ((int) FILE_INFO.invokeExact(handle, BASIC_INFO, info, (int) info.byteSize()) != 0) {
                return info.get(JAVA_INT, 32);
            }
            return legacyInformation(handle, arena).get(JAVA_INT, 0);
        }

        /// Reads the fixed native layout supported by the legacy Windows file information API.
        private static MemorySegment legacyInformation(MemorySegment handle, Arena arena) throws Throwable {
            MemorySegment info = arena.allocate(52, 4);
            if ((int) LEGACY_FILE_INFO.invokeExact(handle, info) == 0) {
                throw new IOException("Windows cache file information is unavailable");
            }
            return info;
        }

        /// Returns a volume serial and file ID, including on volumes without FileIdInfo.
        private static String identity(MemorySegment handle) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment info = arena.allocate(24, 8);
                if ((int) FILE_INFO.invokeExact(handle, FILE_ID_INFO, info, (int) info.byteSize()) != 0) {
                    return HexFormat.of().formatHex(info.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
                }
                MemorySegment legacy = legacyInformation(handle, arena);
                // BY_HANDLE_FILE_INFORMATION stores the volume serial and the high and low file-index words.
                int volume = legacy.get(JAVA_INT, 28);
                int indexHigh = legacy.get(JAVA_INT, 44);
                int indexLow = legacy.get(JAVA_INT, 48);
                if (indexHigh == 0 && indexLow == 0) {
                    throw new IOException("Windows cache file identity is unavailable");
                }
                HexFormat hex = HexFormat.of();
                return hex.toHexDigits(volume) + hex.toHexDigits(indexHigh) + hex.toHexDigits(indexLow);
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable exception) {
                throw new IOException("Cannot identify a pinned cache directory", exception);
            }
        }

        /// Releases one directory handle.
        private static void close(MemorySegment handle) throws IOException {
            try {
                if ((int) CLOSE_HANDLE.invokeExact(handle) == 0) {
                    throw new IOException("Cannot close cache directory handle");
                }
            } catch (IOException exception) {
                throw exception;
            } catch (Throwable exception) {
                throw new IOException("Cannot close cache directory handle", exception);
            }
        }
    }
}
