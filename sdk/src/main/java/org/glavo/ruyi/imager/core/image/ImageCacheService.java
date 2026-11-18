// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/// Inspects and cleans the on-disk image download cache.
///
/// The cache keeps downloaded archives under `downloads` and materialized images under `artifacts`;
/// neither is ever pruned by the download path itself. This service reports what is stored and
/// removes either explicitly selected image versions, leftover partial files, or both roots.
/// Metadata repositories and logs live outside these roots and are only measured.
///
/// Cleanup takes the same per-version lock that downloads and local imports use, so removing a
/// version that another process is writing fails instead of corrupting it. Symbolic links are never
/// followed, and paths that cannot be listed or removed are reported through the result instead of
/// being silently ignored.
@NotNullByDefault
public final class ImageCacheService {
    /// Suffix of the per-version lock file kept next to the download directory.
    private static final String LOCK_SUFFIX = ".lock";

    /// Prefix of a materialization staging directory.
    private static final String STAGING_PREFIX = ".staging-";

    /// Prefix of a materialization backup directory.
    private static final String BACKUP_PREFIX = ".old-";

    /// Suffix of the marker file that identifies an in-progress download.
    private static final String PARTIAL_MARKER_SUFFIX = ".ruyi-partial";

    /// Prefix of a local-import staging file.
    private static final String IMPORT_PREFIX = ".import-";

    /// Suffix of an incomplete download.
    private static final String ARCHIVE_PART_SUFFIX = ".part";

    /// Suffix of a local-import staging file.
    private static final String IMPORT_SUFFIX = ".tmp";

    /// Cache root holding the download and artifact trees.
    private final Path cacheDirectory;

    /// Creates a cache service for the given application directories.
    ///
    /// @param directories application filesystem directories.
    public ImageCacheService(AppDirectories directories) {
        this.cacheDirectory = directories.cacheDirectory();
    }

    /// Measures the current cache contents.
    ///
    /// @return cache usage, newest image version first.
    public ImageCacheUsage usage() {
        ArrayList<String> unreadable = new ArrayList<>();
        Map<String, EntryBuilder> builders = new LinkedHashMap<>();
        scanDownloads(builders, unreadable);
        scanArtifacts(builders, unreadable);

        long downloadBytes = 0L;
        long artifactBytes = 0L;
        long partialBytes = 0L;
        int partialFiles = 0;
        ArrayList<ImageCacheEntry> entries = new ArrayList<>();
        for (EntryBuilder builder : builders.values()) {
            if (builder.isEmpty()) {
                continue;
            }
            downloadBytes += builder.downloadBytes;
            artifactBytes += builder.artifactBytes;
            partialBytes += builder.partialBytes;
            partialFiles += builder.partialFiles;
            entries.add(builder.build());
        }
        entries.sort(Comparator.comparingLong(ImageCacheEntry::lastModifiedMillis).reversed());
        return new ImageCacheUsage(
                List.copyOf(entries),
                downloadBytes,
                artifactBytes,
                partialBytes,
                partialFiles,
                sizeOf(cacheDirectory.resolve("repos"), unreadable),
                sizeOf(cacheDirectory.resolve("logs"), unreadable),
                List.copyOf(unreadable));
    }

    /// Removes the given image versions from the cache.
    ///
    /// Versions locked by a running download or import are skipped.
    ///
    /// @param entries entries to remove.
    /// @return removal result.
    public ImageCacheCleanResult cleanEntries(@Unmodifiable List<ImageCacheEntry> entries) {
        Accumulator accumulator = new Accumulator();
        for (ImageCacheEntry entry : distinct(entries)) {
            removeEntry(entry, accumulator);
        }
        return accumulator.build();
    }

    /// Removes incomplete downloads and leftover materialization directories.
    ///
    /// @return removal result.
    public ImageCacheCleanResult cleanPartials() {
        Accumulator accumulator = new Accumulator();
        ImageCacheUsage usage = usage();
        for (ImageCacheEntry entry : usage.entries()) {
            if (entry.partialFiles() > 0) {
                removeEntryPartials(entry, accumulator);
            }
        }
        accumulator.skipped.addAll(usage.unreadable());
        removeOrphanLeftovers(accumulator);
        return accumulator.build();
    }

    /// Removes every cached image version, keeping metadata repositories and logs.
    ///
    /// @return removal result.
    public ImageCacheCleanResult cleanAll() {
        Accumulator accumulator = new Accumulator();
        ImageCacheUsage usage = usage();
        for (ImageCacheEntry entry : usage.entries()) {
            removeEntry(entry, accumulator);
        }
        accumulator.skipped.addAll(usage.unreadable());
        removeOrphanLeftovers(accumulator);
        return accumulator.build();
    }

    /// Removes leftover materialization directories whose image version has no cache entry.
    ///
    /// @param accumulator removal accumulator.
    private void removeOrphanLeftovers(Accumulator accumulator) {
        Path root = cacheDirectory.resolve("artifacts");
        for (Path repo : listDirectories(root, accumulator.skipped)) {
            for (Path category : listDirectories(repo, accumulator.skipped)) {
                for (Path name : listDirectories(category, accumulator.skipped)) {
                    for (Path child : listChildren(name, accumulator.skipped)) {
                        if (isDirectory(child) && leftoverVersion(child.getFileName().toString()) != null) {
                            removePath(child, accumulator);
                        }
                    }
                }
            }
        }
    }

    /// Removes one cached image version while holding its lock.
    ///
    /// @param entry entry to remove.
    /// @param accumulator removal accumulator.
    private void removeEntry(ImageCacheEntry entry, Accumulator accumulator) {
        if (!isSafeEntry(entry)) {
            accumulator.skip("Unsafe cache entry: " + entry.atom());
            return;
        }
        if (!isSafeVersionTarget(cacheDirectory.resolve("downloads"), entry)
                || !isSafeVersionTarget(cacheDirectory.resolve("artifacts"), entry)) {
            accumulator.skip("Unsafe cache path: " + entry.atom());
            return;
        }
        withLock(entry, accumulator, () -> {
            removePath(versionDirectory(cacheDirectory.resolve("downloads"), entry), accumulator);
            removePath(versionDirectory(cacheDirectory.resolve("artifacts"), entry), accumulator);
            removeLeftoversOf(versionDirectory(cacheDirectory.resolve("artifacts"), entry), accumulator);
        });
    }

    /// Removes incomplete files of one cached image version while holding its lock.
    ///
    /// @param entry entry whose partial files are removed.
    /// @param accumulator removal accumulator.
    private void removeEntryPartials(ImageCacheEntry entry, Accumulator accumulator) {
        if (!isSafeEntry(entry)) {
            return;
        }
        if (!isSafeVersionTarget(cacheDirectory.resolve("downloads"), entry)
                || !isSafeVersionTarget(cacheDirectory.resolve("artifacts"), entry)) {
            accumulator.skip("Unsafe cache path: " + entry.atom());
            return;
        }
        withLock(entry, accumulator, () -> {
            removePartialsInside(versionDirectory(cacheDirectory.resolve("downloads"), entry), accumulator);
            removeLeftoversOf(versionDirectory(cacheDirectory.resolve("artifacts"), entry), accumulator);
        });
    }

    /// Acquires the per-version lock and runs the given action.
    ///
    /// @param entry entry whose lock is acquired.
    /// @param accumulator removal accumulator.
    /// @param action action run while the lock is held.
    /// @return whether the action ran.
    private boolean withLock(ImageCacheEntry entry, Accumulator accumulator, Runnable action) {
        Path lockPath = lockPath(entry);
        try {
            Files.createDirectories(lockPath.getParent());
        } catch (IOException _) {
            accumulator.skip(lockPath.toString());
            return false;
        }
        try (FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
             @Nullable FileLock lock = channel.tryLock()) {
            if (lock == null) {
                accumulator.skip("Cache version is in use: " + entry.atom());
                return false;
            }
            action.run();
            return true;
        } catch (IOException | OverlappingFileLockException _) {
            accumulator.skip("Cache version is in use: " + entry.atom());
            return false;
        }
    }

    /// Returns the lock path of one cached image version.
    ///
    /// @param entry cache entry.
    /// @return lock path.
    private Path lockPath(ImageCacheEntry entry) {
        return cacheDirectory.resolve("downloads")
                .resolve(entry.repoId())
                .resolve(entry.category())
                .resolve(entry.name())
                .resolve(entry.version() + LOCK_SUFFIX);
    }

    /// Removes incomplete files inside one version directory.
    ///
    /// @param directory version directory.
    /// @param accumulator removal accumulator.
    private static void removePartialsInside(Path directory, Accumulator accumulator) {
        for (Path file : listFiles(directory, accumulator.skipped)) {
            if (isIncompleteFile(file)) {
                removePath(file, accumulator);
                removePath(file.resolveSibling(file.getFileName() + PARTIAL_MARKER_SUFFIX), accumulator);
            }
        }
    }

    /// Removes leftover directories belonging to one artifact version directory.
    ///
    /// @param artifactVersion artifact version directory.
    /// @param accumulator removal accumulator.
    private static void removeLeftoversOf(Path artifactVersion, Accumulator accumulator) {
        @Nullable Path nameDirectory = artifactVersion.getParent();
        if (nameDirectory == null) {
            return;
        }
        String version = artifactVersion.getFileName().toString();
        for (Path child : listChildren(nameDirectory, accumulator.skipped)) {
            String fileName = child.getFileName().toString();
            if (fileName.startsWith(STAGING_PREFIX + version + "-")
                    || fileName.startsWith(BACKUP_PREFIX + version + "-")) {
                removePath(child, accumulator);
            }
        }
    }

    /// Scans the download tree and merges entries into the given builders.
    ///
    /// @param builders accumulated entries.
    /// @param unreadable paths that could not be read.
    private void scanDownloads(Map<String, EntryBuilder> builders, List<String> unreadable) {
        Path root = cacheDirectory.resolve("downloads");
        for (Path repo : listDirectories(root, unreadable)) {
            for (Path category : listDirectories(repo, unreadable)) {
                for (Path name : listDirectories(category, unreadable)) {
                    for (Path child : listChildren(name, unreadable)) {
                        if (!isDirectory(child)) {
                            continue;
                        }
                        @Nullable EntryBuilder builder = builder(builders, repo, category, name, child.getFileName().toString());
                        if (builder == null) {
                            continue;
                        }
                        for (Path file : listFiles(child, unreadable)) {
                            String fileName = file.getFileName().toString();
                            if (isMarkerName(fileName)) {
                                continue;
                            }
                            long size = sizeOfFile(file, unreadable);
                            builder.present = true;
                            if (isIncompleteFile(file)) {
                                builder.partialBytes += size;
                                builder.partialFiles++;
                            } else {
                                builder.downloadBytes += size;
                            }
                            builder.touch(file);
                        }
                    }
                }
            }
        }
    }

    /// Scans the artifact tree and merges entries into the given builders.
    ///
    /// Leftover materialization directories are attributed to their image version.
    ///
    /// @param builders accumulated entries.
    /// @param unreadable paths that could not be read.
    private void scanArtifacts(Map<String, EntryBuilder> builders, List<String> unreadable) {
        Path root = cacheDirectory.resolve("artifacts");
        for (Path repo : listDirectories(root, unreadable)) {
            for (Path category : listDirectories(repo, unreadable)) {
                for (Path name : listDirectories(category, unreadable)) {
                    for (Path child : listChildren(name, unreadable)) {
                        if (!isDirectory(child)) {
                            continue;
                        }
                        String fileName = child.getFileName().toString();
                        @Nullable String leftoverVersion = leftoverVersion(fileName);
                        if (leftoverVersion != null) {
                            @Nullable EntryBuilder leftoverBuilder =
                                    builder(builders, repo, category, name, leftoverVersion);
                            if (leftoverBuilder != null) {
                                leftoverBuilder.partialBytes += sizeOfDirectory(child, unreadable);
                                leftoverBuilder.partialFiles++;
                                leftoverBuilder.present = true;
                                leftoverBuilder.touch(child);
                            }
                            continue;
                        }
                        @Nullable EntryBuilder builder = builder(builders, repo, category, name, fileName);
                        if (builder == null) {
                            continue;
                        }
                        List<Path> files = listFiles(child, unreadable);
                        if (!files.isEmpty()) {
                            builder.present = true;
                            for (Path file : files) {
                                builder.artifactBytes += sizeOfFile(file, unreadable);
                            }
                            builder.touch(child);
                        }
                    }
                }
            }
        }
    }

    /// Returns the image version encoded in a leftover directory name.
    ///
    /// @param name directory name.
    /// @return image version, or null when the directory is not a leftover.
    private static @Nullable String leftoverVersion(String name) {
        String rest;
        if (name.startsWith(STAGING_PREFIX)) {
            rest = name.substring(STAGING_PREFIX.length());
        } else if (name.startsWith(BACKUP_PREFIX)) {
            rest = name.substring(BACKUP_PREFIX.length());
        } else {
            return null;
        }
        int separator = rest.lastIndexOf('-');
        return separator <= 0 ? rest : rest.substring(0, separator);
    }

    /// Returns whether a file name marks an incomplete cache file.
    ///
    /// @param name file name.
    /// @return whether the file is incomplete.
    private static boolean isPartialName(String name) {
        return name.endsWith(ARCHIVE_PART_SUFFIX)
                || (name.startsWith(IMPORT_PREFIX) && name.endsWith(IMPORT_SUFFIX));
    }

    /// Returns whether a file is an in-progress download.
    ///
    /// A completed archive may legitimately end with `.part`, so the marker sidecar written by the
    /// downloader decides instead of the file name alone.
    ///
    /// @param file cached file.
    /// @return whether the file is incomplete.
    private static boolean isIncompleteFile(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(ARCHIVE_PART_SUFFIX)) {
            return name.startsWith(IMPORT_PREFIX) && name.endsWith(IMPORT_SUFFIX);
        }
        return Files.exists(file.resolveSibling(name + PARTIAL_MARKER_SUFFIX), LinkOption.NOFOLLOW_LINKS);
    }

    /// Returns whether a file is an internal marker.
    ///
    /// @param name file name.
    /// @return whether the file only marks cache state.
    private static boolean isMarkerName(String name) {
        return name.endsWith(PARTIAL_MARKER_SUFFIX);
    }

    /// Returns the builder for one image version.
    ///
    /// @param builders accumulated entries.
    /// @param repo repository directory.
    /// @param category category directory.
    /// @param name image name directory.
    /// @param version version name.
    /// @return entry builder, or null when a path component is unsafe.
    private static @Nullable EntryBuilder builder(
            Map<String, EntryBuilder> builders,
            Path repo,
            Path category,
            Path name,
            String version) {
        String repoId = repo.getFileName().toString();
        String categoryName = category.getFileName().toString();
        String imageName = name.getFileName().toString();
        if (!isSafeComponent(repoId) || !isSafeComponent(categoryName)
                || !isSafeComponent(imageName) || !isSafeComponent(version)) {
            return null;
        }
        String key = repoId + '/' + categoryName + '/' + imageName + '/' + version;
        return builders.computeIfAbsent(
                key,
                _ -> new EntryBuilder(repoId, categoryName, imageName, version));
    }

    /// Returns whether a cache entry only contains safe path components.
    ///
    /// @param entry cache entry.
    /// @return whether the entry can be resolved below the cache root.
    private static boolean isSafeEntry(ImageCacheEntry entry) {
        return isSafeComponent(entry.repoId())
                && isSafeComponent(entry.category())
                && isSafeComponent(entry.name())
                && isSafeComponent(entry.version());
    }

    /// Returns whether a cache version path only runs through real directories.
    ///
    /// The version may be absent, in which case there is nothing to remove. When it exists, every
    /// ancestor below the tree root must be a real directory, so deletion can never follow a
    /// symbolic link that points outside the cache.
    ///
    /// @param root tree root.
    /// @param entry cache entry.
    /// @return whether the target can be removed safely.
    private static boolean isSafeVersionTarget(Path root, ImageCacheEntry entry) {
        Path repo = root.resolve(entry.repoId());
        Path category = repo.resolve(entry.category());
        Path name = category.resolve(entry.name());
        if (!Files.exists(name.resolve(entry.version()), LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        return isRealDirectory(root)
                && isRealDirectory(repo)
                && isRealDirectory(category)
                && isRealDirectory(name);
    }

    /// Returns whether a path is a real directory rather than a symbolic link.
    ///
    /// @param path inspected path.
    /// @return whether the path is a real directory.
    private static boolean isRealDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    /// Returns whether a value is a single, non-special path component.
    ///
    /// @param value path component.
    /// @return whether the component is safe to resolve.
    private static boolean isSafeComponent(String value) {
        return !value.isEmpty()
                && !value.equals(".")
                && !value.equals("..")
                && value.indexOf('/') < 0
                && value.indexOf('\\') < 0
                && value.indexOf(':') < 0;
    }

    /// Removes duplicate entries while keeping their order.
    ///
    /// @param entries entries to deduplicate.
    /// @return distinct entries.
    private static @Unmodifiable List<ImageCacheEntry> distinct(@Unmodifiable List<ImageCacheEntry> entries) {
        return List.copyOf(new LinkedHashSet<>(entries));
    }

    /// Resolves the directory of one cached image version below a tree root.
    ///
    /// @param root tree root.
    /// @param entry cache entry.
    /// @return version directory.
    private static Path versionDirectory(Path root, ImageCacheEntry entry) {
        return root.resolve(entry.repoId())
                .resolve(entry.category())
                .resolve(entry.name())
                .resolve(entry.version());
    }

    /// Returns whether a path is a directory without following symbolic links.
    ///
    /// @param path inspected path.
    /// @return whether the path is a real directory.
    private static boolean isDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    /// Lists the directories below one directory.
    ///
    /// @param directory directory to list.
    /// @param unreadable paths that could not be read.
    /// @return child directories.
    private static @Unmodifiable List<Path> listDirectories(Path directory, List<String> unreadable) {
        ArrayList<Path> directories = new ArrayList<>();
        for (Path child : listChildren(directory, unreadable)) {
            if (isDirectory(child)) {
                directories.add(child);
            }
        }
        return List.copyOf(directories);
    }

    /// Lists the children of one directory, recording unreadable directories.
    ///
    /// @param directory directory to list.
    /// @param unreadable paths that could not be read.
    /// @return child paths.
    private static @Unmodifiable List<Path> listChildren(Path directory, List<String> unreadable) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream.sorted().toList();
        } catch (IOException _) {
            unreadable.add(directory.toString());
            return List.of();
        }
    }

    /// Lists the regular files below one directory.
    ///
    /// @param directory directory to walk.
    /// @param unreadable paths that could not be read.
    /// @return regular files.
    private static @Unmodifiable List<Path> listFiles(Path directory, List<String> unreadable) {
        ArrayList<Path> files = new ArrayList<>();
        walk(directory, files, new ArrayList<>(), unreadable);
        return List.copyOf(files);
    }

    /// Collects files and directories below a root without following symbolic links.
    ///
    /// @param root root to walk.
    /// @param files collected regular files and symbolic links.
    /// @param directories collected directories.
    /// @param unreadable paths that could not be read.
    private static void walk(
            Path root,
            List<Path> files,
            List<Path> directories,
            List<String> unreadable) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                /// Collects one visited file or symbolic link.
                ///
                /// @param file visited path.
                /// @param attributes file attributes.
                /// @return continuation result.
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    files.add(file);
                    return FileVisitResult.CONTINUE;
                }

                /// Collects one visited directory.
                ///
                /// @param directory visited directory.
                /// @param attributes directory attributes.
                /// @return continuation result.
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    directories.add(directory);
                    return FileVisitResult.CONTINUE;
                }

                /// Records one unreadable path.
                ///
                /// @param file failed path.
                /// @param exception failure.
                /// @return continuation result.
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    unreadable.add(file.toString());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException _) {
            unreadable.add(root.toString());
        }
    }

    /// Returns the size of one file, recording unreadable paths.
    ///
    /// @param file file to measure.
    /// @param unreadable paths that could not be read.
    /// @return file size, or zero when unreadable.
    private static long sizeOfFile(Path file, List<String> unreadable) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
        } catch (IOException _) {
            unreadable.add(file.toString());
            return 0L;
        }
    }

    /// Returns the total size of one file or directory tree.
    ///
    /// @param path file or directory.
    /// @param unreadable paths that could not be read.
    /// @return total size in bytes.
    private static long sizeOf(Path path, List<String> unreadable) {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return sizeOfFile(path, unreadable);
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return 0L;
        }
        return sizeOfDirectory(path, unreadable);
    }

    /// Returns the total size of one directory tree.
    ///
    /// @param directory directory to measure.
    /// @param unreadable paths that could not be read.
    /// @return total size in bytes.
    private static long sizeOfDirectory(Path directory, List<String> unreadable) {
        long bytes = 0L;
        for (Path file : listFiles(directory, unreadable)) {
            bytes += sizeOfFile(file, unreadable);
        }
        return bytes;
    }

    /// Removes one file or directory tree, reporting unremovable paths instead of failing.
    ///
    /// Symbolic links are removed themselves and never followed.
    ///
    /// @param path file or directory to remove.
    /// @param accumulator removal accumulator.
    private static void removePath(Path path, Accumulator accumulator) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        ArrayList<Path> files = new ArrayList<>();
        ArrayList<Path> directories = new ArrayList<>();
        walk(path, files, directories, accumulator.skipped);
        for (Path file : files) {
            removeSingleFile(file, accumulator);
        }
        directories.sort(Comparator.comparingInt((Path directory) -> directory.getNameCount()).reversed());
        for (Path directory : directories) {
            try {
                Files.deleteIfExists(directory);
            } catch (IOException _) {
                accumulator.skipped.add(directory.toString());
            }
        }
    }

    /// Removes one file and records it only when it was really deleted.
    ///
    /// @param file file to remove.
    /// @param accumulator removal accumulator.
    private static void removeSingleFile(Path file, Accumulator accumulator) {
        long size = 0L;
        boolean measured = false;
        try {
            size = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
            measured = true;
        } catch (IOException _) {
            // A file whose attributes cannot be read is still removed, just not counted by size.
        }
        try {
            if (Files.deleteIfExists(file)) {
                if (measured) {
                    accumulator.removedBytes += size;
                }
                accumulator.removedFiles++;
            }
        } catch (IOException _) {
            accumulator.skipped.add(file.toString());
        }
    }

    /// Mutable accumulator for one cleanup operation.
    @NotNullByDefault
    private static final class Accumulator {
        /// Removed bytes.
        private long removedBytes;

        /// Removed files.
        private int removedFiles;

        /// Paths that could not be removed or read.
        private final List<String> skipped = new ArrayList<>();

        /// Records one skipped path.
        ///
        /// @param reason human-readable reason.
        private void skip(String reason) {
            skipped.add(reason);
        }

        /// Builds the immutable result.
        ///
        /// @return removal result.
        private ImageCacheCleanResult build() {
            return new ImageCacheCleanResult(removedBytes, removedFiles, List.copyOf(skipped));
        }
    }

    /// Mutable accumulator for one cached image version.
    @NotNullByDefault
    private static final class EntryBuilder {
        /// Repository identifier.
        private final String repoId;

        /// Image category.
        private final String category;

        /// Image name.
        private final String name;

        /// Image version.
        private final String version;

        /// Bytes of completed download archives.
        private long downloadBytes;

        /// Bytes of materialized images.
        private long artifactBytes;

        /// Bytes of incomplete downloads and leftover directories.
        private long partialBytes;

        /// Number of incomplete downloads and leftover directories.
        private int partialFiles;

        /// Whether any cached file or leftover directory was found.
        private boolean present;

        /// Newest modification time seen while scanning.
        private long lastModifiedMillis;

        /// Creates an accumulator for one image version.
        ///
        /// @param repoId repository identifier.
        /// @param category image category.
        /// @param name image name.
        /// @param version image version.
        private EntryBuilder(String repoId, String category, String name, String version) {
            this.repoId = repoId;
            this.category = category;
            this.name = name;
            this.version = version;
        }

        /// Records the modification time of one path.
        ///
        /// @param path inspected path.
        private void touch(Path path) {
            try {
                long modified = Files.getLastModifiedTime(path).toMillis();
                if (modified > lastModifiedMillis) {
                    lastModifiedMillis = modified;
                }
            } catch (IOException _) {
                // A missing timestamp keeps the previous one.
            }
        }

        /// Returns whether the entry holds no cached bytes.
        ///
        /// @return whether the entry is empty.
        private boolean isEmpty() {
            return !present;
        }

        /// Builds the immutable entry.
        ///
        /// @return cache entry.
        private ImageCacheEntry build() {
            return new ImageCacheEntry(
                    repoId,
                    category,
                    name,
                    version,
                    category + '/' + name + '(' + version + ')',
                    artifactBytes,
                    downloadBytes,
                    partialBytes,
                    partialFiles,
                    lastModifiedMillis);
        }
    }
}
