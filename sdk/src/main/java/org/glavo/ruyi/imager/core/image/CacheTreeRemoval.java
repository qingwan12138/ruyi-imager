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
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Unmodifiable;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/// Removes cache entries while keeping their real parent directories bound.
@NotNullByDefault
final class CacheTreeRemoval {
    /// Prevents construction.
    private CacheTreeRemoval() {
    }

    /// Determines the expected type of a selected entry.
    @NotNullByDefault
    enum Target {
        /// A single file or link.
        FILE,
        /// A version directory, never a regular file or symbolic link.
        DIRECTORY,
        /// A file or arbitrary directory tree.
        TREE
    }

    /// Receives one removed cache entry together with its file identity.
    ///
    /// P2:494 同一次清理可能通过多个目录项（例如 `.part` 与其私有续传硬链接）删除同一个文件，
    /// 因此同时提供文件身份，让调用方按身份去重计算占用与释放的字节，而删除名称仍各自计数。
    @FunctionalInterface
    interface RemovedFile {
        /// Records one removed entry.
        ///
        /// @param size size of the removed entry.
        /// @param identity identity of the removed file, or null when unavailable.
        void accept(long size, @Nullable Object identity);
    }

    /// Removes a cache entry from a physical root.
    static void remove(Path root, Path relative, boolean recursive, RemovedFile removedFile, List<String> skipped) {
        remove(root, relative, recursive ? Target.TREE : Target.FILE, removedFile, skipped);
    }

    /// Removes a cache entry with an expected root type.
    static void remove(Path root, Path relative, Target target, RemovedFile removedFile, List<String> skipped) {
        try {
            atParent(root, relative, (parent, name) -> {
                removeEntry(parent, name, target, removedFile, skipped);
                return true;
            });
        } catch (NoSuchFileException _) {
            // A missing ancestor has no entry to clean.
        } catch (IOException exception) {
            skipped.add(root.resolve(relative) + ": " + exception.getMessage());
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
        if (create) {
            ensureLockParent(root, relative.resolve(".entry"));
        }
        return atParent(root, relative, (parent, name) -> {
            Directory bound = parent.open(name, true);
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

    /// Creates the missing parents of a version lock below the physical cache root.
    ///
    /// P2:118 只有产物、下载树已迁移或已被手动清除时兼容锁父目录不存在。若直接报告失败，
    /// 这种迁移后的布局就无法显式清理；这里在已验证的缓存根下逐级建立真实目录后再绑定。
    ///
    /// @param root physical cache root.
    /// @param relative lock path relative to the root.
    /// @throws IOException when a level is not a real directory.
    static void ensureLockParent(Path root, Path relative) throws IOException {
        if (root.getFileSystem() != FileSystems.getDefault() || relative.isAbsolute()
                || !relative.equals(relative.normalize()) || relative.startsWith("..")) {
            throw new IOException("Cannot create cache lock parents: " + relative);
        }
        if (File.separatorChar == '\\') {
            Path volume = root.getRoot();
            try (Directory start = new Directory(volume, null, null)) {
                ensureWindowsParents(start, volume.relativize(root.resolve(relative)), 0, root.getNameCount());
            }
        } else {
            Posix.createParents(root, relative);
        }
    }

    /// Keeps each Windows ancestor pinned across creation and descent.
    private static void ensureWindowsParents(Directory parent, Path relative, int level, int rootDepth)
            throws IOException {
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
            ensureWindowsParents(child, relative, level + 1, rootDepth);
        }
    }

    /// Name equivalences observed in the cache root.
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
        Path probe = Path.of(prefix + "-Aé");
        parent.writeNewFile(probe, new byte[]{0});
        try {
            return new NameEquivalence(probeExists(parent, Path.of(prefix + "-aé")),
                    probeExists(parent, Path.of(prefix + "-Ae\u0301")));
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

    /// Opens a lock through its bound parent and returns it to the caller.
    static @Nullable CacheLock acquireLock(Path root, Path relative) throws IOException {
        return atParent(root, relative, (parent, name) -> {
            parent.verifyIdentity();
            return CacheLock.acquireBound(parent.path().resolve(name),
                    () -> parent.lockIdentity(name), () -> parent.openLock(name));
        });
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
    private static final class Posix {
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
                        arena.allocateFrom(source.toAbsolutePath().toString()), currentDirectory,
                        arena.allocateFrom(target.toAbsolutePath().toString()), exclusive);
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
        private static void createParents(Path root, Path relative) throws IOException {
            int flags = directoryFlags();
            try (Arena arena = Arena.ofConfined()) {
                int descriptor = (int) OPEN.invokeExact(arena.allocateFrom(root.toString()), flags);
                if (descriptor < 0) {
                    throw new IOException("Cannot open cache root: " + root);
                }
                try {
                    createParents(descriptor, relative, 0, flags, arena);
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
        private static void createParents(int parent, Path relative, int level, int flags, Arena arena)
                throws Throwable {
            if (level == relative.getNameCount() - 1) {
                return;
            }
            MemorySegment name = arena.allocateFrom(relative.getName(level).toString());
            int child = (int) OPEN_AT.invokeExact(parent, name, flags);
            if (child < 0) {
                int ignored = (int) MKDIR_AT.invokeExact(parent, name, 0777);
                child = (int) OPEN_AT.invokeExact(parent, name, flags);
                if (child < 0) {
                    throw new IOException("Cannot create or open cache directory: " + relative.subpath(0, level + 1));
                }
            }
            try {
                createParents(child, relative, level + 1, flags, arena);
            } finally {
                int ignored = (int) CLOSE.invokeExact(child);
            }
        }
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
    /// P2:494 Windows 的 `BasicFileAttributes` 不提供文件键，因此改用文件句柄读取
    /// FILE_ID_INFO；这样私有续传硬链接与 `.part` 仍然得到同一个身份。
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
    /// P2:503 续传需要区分"独占文件"与"与其他目录项共享内容"的文件：独占时可以原位追加，
    /// 不必复制整个已下载前缀；只有真正共享时才需要保留旧内容的副本。
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
    /// P1:339 回调取得的是取得兼容锁时打开并校验过的父目录句柄，删除该版本的下载目录必须相对它执行，
    /// 不能在父目录被重命名替换后重新按名称从缓存根查找 `downloads`。
    ///
    /// @param root physical cache root.
    /// @param legacy version lock in the configured download tree.
    /// @param action action to run under the lock with the bound parent directory.
    /// @return whether the action ran.
    /// @throws IOException when a directory or lock cannot be safely bound.
    static boolean withVersionLock(Path root, Path legacy, VersionAction action) throws IOException {
        Path lease = CacheLock.versionLeaseName(root, legacy);
        return withLock(root, lease, lockParentIdentity(root, lease), _ -> {
            Object parentIdentity;
            try {
                parentIdentity = lockParentIdentity(root, legacy);
            } catch (NoSuchFileException _) {
                ensureLockParent(root, legacy);
                parentIdentity = lockParentIdentity(root, legacy);
            }
            return withLock(root, legacy, parentIdentity, parent -> {
                action.run(parent);
                return true;
            });
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
        return withLock(root, relative, expectedParent, _ -> {
            action.run();
            return true;
        });
    }

    /// Acquires a lock in a bound directory and runs an action with that directory.
    private static boolean withLock(Path root, Path relative, Object expectedParent, LockedDirectoryAction action)
            throws IOException {
        return atParent(root, relative, (parent, name) -> {
            if (!expectedParent.equals(parent.identity())) {
                throw new FileSystemException(parent.path().toString(), null, "Cache lock parent was replaced");
            }
            parent.verifyIdentity();
            @Nullable Object identity = parent.lockIdentity(name);
            try (@Nullable CacheLock lock = CacheLock.acquireBound(
                    parent.path().resolve(name), () -> parent.lockIdentity(name), () -> parent.openLock(name))) {
                if (lock == null) {
                    return false;
                }
                parent.verifyIdentity();
                if (identity != null && !identity.equals(parent.lockIdentity(name))) {
                    throw new FileSystemException(parent.path().resolve(name).toString(), null,
                            "Cache lock identity changed");
                }
                return action.run(parent);
            }
        });
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
                return atParent(start, volume.relativize(root.resolve(relative)), 0, action);
            }
        }
        try (Directory start = Directory.openRoot(root)) {
            return atParent(start, relative, 0, action);
        }
    }

    /// Opens each ancestor in order before running the operation.
    private static <T> T atParent(Directory parent, Path path, int level, ParentAction<T> action) throws IOException {
        Path name = path.getName(level);
        if (level == path.getNameCount() - 1) {
            return action.run(parent, name);
        }
        try (Directory child = parent.open(name)) {
            return atParent(child, path, level + 1, action);
        }
    }

    /// Deletes a directory's children before deleting the directory entry itself.
    static void removeEntry(
            Directory parent, Path name, Target target, RemovedFile removedFile, List<String> skipped) {
        Path path = parent.path().resolve(name);
        try {
            BasicFileAttributes attributes = parent.attributes(name);
            if (!attributes.isDirectory()) {
                if (target == Target.DIRECTORY) {
                    throw new FileSystemException(path.toString(), null, "Expected a cache version directory");
                }
                // P2:494 身份必须在删除名称之前读取，否则 Windows 上的句柄身份已经不可用。
                @Nullable Object identity = fileIdentity(path, attributes);
                parent.delete(name, false);
                removedFile.accept(attributes.size(), identity);
                return;
            }
            if (target == Target.FILE) {
                throw new FileSystemException(path.toString(), null, "Expected a cache file");
            }
            try (Directory child = parent.open(name);
                 DirectoryStream<Path> contents = child.contents()) {
                for (Path entry : contents) {
                    removeEntry(child, entry.getFileName(), Target.TREE, removedFile, skipped);
                }
            }
            parent.delete(name, true);
        } catch (NoSuchFileException _) {
            // Another operation already removed this entry.
        } catch (IOException | DirectoryIteratorException exception) {
            skipped.add(path + ": " + exception.getMessage());
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
    @NotNullByDefault
    record Directory(
            Path path,
            @Nullable SecureDirectoryStream<Path> stream,
            @Nullable MemorySegment pin) implements AutoCloseable {

        /// Opens the cache root directly so its POSIX ancestors need only grant search access.
        private static Directory openRoot(Path root) throws IOException {
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
                        accepted = true;
                        return bound;
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
            Path child = path.resolve(name);
            if (stream != null) {
                return new Directory(child, stream.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS), null);
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
        void remove(Path name, Target target, RemovedFile removedFile, List<String> skipped) {
            removeEntry(this, name, target, removedFile, skipped);
        }

        /// Lists the names of this bound directory's children.
        ///
        /// @return immutable child names.
        /// @throws IOException when the directory cannot be listed.
        @Unmodifiable List<Path> names() throws IOException {
            ArrayList<Path> names = new ArrayList<>();
            try (DirectoryStream<Path> contents = contents()) {
                for (Path entry : contents) {
                    names.add(entry.getFileName());
                }
            }
            return List.copyOf(names);
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
                return attributes.fileKey();
            } catch (NoSuchFileException _) {
                return null;
            }
        }

        /// Opens one regular lock entry through the bound parent.
        private FileChannel openLock(Path name) throws IOException {
            if (stream == null) {
                return FileChannel.open(path.resolve(name), StandardOpenOption.CREATE,
                        StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            }
            var opened = stream.newByteChannel(name, Set.of(StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
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
                // Windows 句柄无法约束子项的创建与重命名，因此按句柄身份复验路径仍然指向同一目录。
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
                // P2:175 Windows 卷根既不能重命名也不能删除；JDK 对卷根不返回文件键，
                // 改为通过一个新句柄读取目录身份，避免根租约直接位于卷根（如 `D:\`）时清理全部失败。
                return Windows.probeIdentity(path);
            }
            if (key == null) {
                throw new FileSystemException(path.toString(), null, "Directory identity is unavailable");
            }
            return key;
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
        private BasicFileAttributes attributes(Path name) throws IOException {
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

        /// Deletes the entry from the bound parent.
        private void delete(Path name, boolean directory) throws IOException {
            if (stream == null) {
                Files.delete(path.resolve(name));
            } else if (directory) {
                stream.deleteDirectory(name);
            } else {
                stream.deleteFile(name);
            }
        }

        /// Releases the directory binding.
        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
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

        /// FileAttributeTagInfo selector for GetFileInformationByHandleEx.
        private static final int ATTRIBUTE_TAG_INFO = 9;

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

        /// Writes the contents of a new marker.
        private static final MethodHandle WRITE_FILE = Linker.nativeLinker().downcallHandle(
                KERNEL32.find("WriteFile").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

        /// Opens a single file name relative to a pinned directory without following a link.
        private static MemorySegment openChild(MemorySegment parent, Path name, int access, int disposition,
                                               Arena arena) throws IOException {
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
                int status = (int) NT_CREATE_FILE.invokeExact(out, access | 0x100000, attributes,
                        arena.allocate(16, 8), MemorySegment.NULL, 0x80, SHARE_ALL, disposition,
                        0x00200060, MemorySegment.NULL, 0);
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

        /// Writes a newly created marker through its relative handle.
        private static void writeNewFile(MemorySegment parent, Path name, byte[] bytes) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment handle = openChild(parent, name, 2, 2, arena);
                try {
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
                setChildInformation(parent, source, info, 10, arena);
            }
        }

        /// Marks the opened entry for deletion when its handle closes.
        private static void deleteFile(MemorySegment parent, Path name) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment info = arena.allocate(java.lang.foreign.ValueLayout.JAVA_BYTE);
                info.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 1);
                setChildInformation(parent, name, info, 13, arena);
            }
        }

        /// Updates one child opened with DELETE access relative to its directory handle.
        private static void setChildInformation(MemorySegment parent, Path name, MemorySegment info, int kind,
                                                Arena arena) throws IOException {
            MemorySegment handle = openChild(parent, name, 0x10000, 1, arena);
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
                    MemorySegment info = arena.allocate(2 * JAVA_INT.byteSize(), JAVA_INT.byteAlignment());
                    // FileAttributeTagInfo begins with the DOS file-attribute mask.
                    int status = (int) FILE_INFO.invokeExact(handle, ATTRIBUTE_TAG_INFO, info, (int) info.byteSize());
                    if (status == 0 || (info.get(JAVA_INT, 0) & (DIRECTORY_ATTRIBUTE | REPARSE_ATTRIBUTE))
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
                // 无法识别的文件按旧行为逐名称计数。
                return null;
            }
        }

        /// Returns the volume serial and 128-bit identity of a pinned Windows directory.
        private static String identity(MemorySegment handle) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment info = arena.allocate(24, 8);
                if ((int) FILE_INFO.invokeExact(handle, FILE_ID_INFO, info, (int) info.byteSize()) == 0) {
                    throw new IOException("Windows cache directory identity is unavailable");
                }
                return HexFormat.of().formatHex(info.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
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
