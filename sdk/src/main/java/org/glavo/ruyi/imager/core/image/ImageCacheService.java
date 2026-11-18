// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /// Skip reason prefix used when a version is locked by another operation.
    public static final String SKIPPED_IN_USE = "Cache version is in use: ";

    /// Suffix of the per-version lock file kept next to the download directory.
    private static final String LOCK_SUFFIX = ".lock";

    /// Prefix of a local-import staging file.
    private static final String IMPORT_PREFIX = ".import-";

    /// Suffix of an incomplete download.
    private static final String ARCHIVE_PART_SUFFIX = ".part";

    /// Suffix of a local-import staging file.
    private static final String IMPORT_SUFFIX = ".tmp";

    /// Cache root holding the download and artifact trees.
    private final Path cacheDirectory;

    /// Catalog used to recognize downloads left by older versions, or null when unavailable.
    private final @Nullable ImageCatalogService images;

    /// Physical cache root captured for one operation, or null if unavailable.
    private final @Nullable Path realCacheRoot;

    /// Creates a cache service for the given application directories.
    ///
    /// @param directories application filesystem directories.
    public ImageCacheService(AppDirectories directories) {
        this(directories, null);
    }

    /// Creates a cache service that can recognize downloads left by older versions.
    ///
    /// @param directories application filesystem directories.
    /// @param images catalog service used to resolve declared distfile and version names.
    public ImageCacheService(AppDirectories directories, @Nullable ImageCatalogService images) {
        this(directories.cacheDirectory(), images, null);
    }

    /// Creates a view using the cache root captured for the operation.
    private ImageCacheService(Path cacheDirectory, @Nullable ImageCatalogService images, @Nullable Path realCacheRoot) {
        this.cacheDirectory = cacheDirectory;
        this.images = images;
        this.realCacheRoot = realCacheRoot;
    }

    /// Resolves the configured cache alias once for a scan or cleanup.
    private ImageCacheService operation() {
        try {
            return new ImageCacheService(cacheDirectory, images, cacheDirectory.toRealPath());
        } catch (IOException _) {
            return new ImageCacheService(cacheDirectory, images, null);
        }
    }

    /// Returns catalog names, or null when the catalog is unavailable.
    ///
    /// P1:106 清理必须基于磁盘上的当前声明：生产实现会直接返回进程内的 `cachedCatalog`，
    /// 另一个进程同步清单并下载完整归档后，旧快照会把这个归档当作遗留分片删除。
    /// 因此每次清理都先失效内存快照，再读取每仓库的当前声明。
    ///
    /// @return catalog index, or null.
    private @Nullable CatalogIndex catalogIndex() {
        @Nullable ImageCatalogService catalog = images;
        if (catalog != null) {
            try {
                catalog.invalidateCache();
                return new CatalogIndex(catalog.declaredImages());
            } catch (IOException | RuntimeException _) {
                return null;
            }
        }
        return null;
    }

    /// Measures the current cache contents.
    ///
    /// @return cache usage, newest image version first.
    public ImageCacheUsage usage() {
        return operation().usage(catalogIndex(), true);
    }

    /// Measures images and unreadable paths within the cleanup scope.
    ///
    /// @return usage of the download, artifact, and import trees.
    public ImageCacheUsage usageForCleanup() {
        return usageForCleanup(false);
    }

    /// Measures cleanup targets, optionally applying the restrictions of bulk cleanup.
    ///
    /// @param bulk whether to omit versions that can only be removed by explicit selection.
    /// @return usage of the proposed cleanup scope.
    public ImageCacheUsage usageForCleanup(boolean bulk) {
        return operation().usage(catalogIndex(), false, bulk);
    }

    /// Measures one catalog snapshot, optionally including retained repositories and logs.
    private ImageCacheUsage usage(@Nullable CatalogIndex index, boolean includeRetainedTrees) {
        return usage(index, includeRetainedTrees, false);
    }

    /// Measures cache trees using one catalog snapshot and the requested cleanup scope.
    private ImageCacheUsage usage(@Nullable CatalogIndex index, boolean includeRetainedTrees, boolean bulk) {
        ArrayList<String> unreadable = new ArrayList<>();
        Map<String, EntryBuilder> builders = new LinkedHashMap<>();
        scanDownloads(builders, unreadable, index);
        scanArtifacts(builders, unreadable, index);
        scanImports(builders, unreadable);

        long downloadBytes = 0L;
        long artifactBytes = 0L;
        long partialBytes = 0L;
        int partialFiles = 0;
        ArrayList<ImageCacheEntry> entries = new ArrayList<>();
        ArrayList<ImageCacheRetainedEntry> retained = new ArrayList<>();
        for (EntryBuilder builder : builders.values()) {
            if (builder.isEmpty()) {
                continue;
            }
            ImageCacheEntry entry = builder.build(index);
            if (bulk && isUnconfirmedLegacyVersion(entry, index)) {
                // P3:159 全量清理会跳过只能显式选择的条目；预览必须保留其 ATOM 与原因，
                // 否则用户无法知道应该显式清理哪个版本，而不是把条目直接扣除。
                retained.add(new ImageCacheRetainedEntry(
                        entry, ImageCacheRetainedEntry.Reason.AMBIGUOUS_LEGACY_DIRECTORY));
                continue;
            }
            downloadBytes += builder.downloadBytes;
            artifactBytes += builder.artifactBytes;
            partialBytes += builder.partialBytes;
            partialFiles += builder.partialFiles;
            entries.add(entry);
        }
        entries.sort(Comparator.comparingLong(ImageCacheEntry::lastModifiedMillis).reversed());
        return new ImageCacheUsage(
                List.copyOf(entries),
                downloadBytes,
                artifactBytes,
                partialBytes,
                partialFiles,
                includeRetainedTrees ? sizeOf(cacheDirectory.resolve("repos"), unreadable) : 0L,
                includeRetainedTrees ? sizeOf(cacheDirectory.resolve("logs"), unreadable) : 0L,
                List.copyOf(unreadable),
                List.copyOf(retained));
    }

    /// Removes the given image versions from the cache.
    ///
    /// Versions locked by a running download or import are skipped.
    ///
    /// @param entries entries to remove.
    /// @return removal result.
    public ImageCacheCleanResult cleanEntries(@Unmodifiable List<ImageCacheEntry> entries) {
        return operation().cleanEntriesFromSnapshot(entries);
    }

    /// Runs the cleanup using the physical root captured for this operation.
    private ImageCacheCleanResult cleanEntriesFromSnapshot(@Unmodifiable List<ImageCacheEntry> entries) {
        Accumulator accumulator = new Accumulator();
        @Nullable CatalogIndex index = catalogIndex();
        @Unmodifiable List<ImageCacheEntry> selected = distinct(entries);
        for (ImageCacheEntry entry : selected) {
            removeEntry(entry, index, accumulator);
        }
        // P2:198 显式清理只清理所选版本的标记：其他版本的过期标记或已消失的兼容锁父目录
        // 不应让本次清理失败，也不应顺带删除其他版本的标记。
        purgeMarkers(accumulator, selected);
        return accumulator.build();
    }

    /// Removes incomplete downloads and leftover materialization directories.
    ///
    /// @return removal result.
    public ImageCacheCleanResult cleanPartials() {
        return operation().cleanPartialsFromSnapshot();
    }

    /// Runs the cleanup using the physical root captured for this operation.
    private ImageCacheCleanResult cleanPartialsFromSnapshot() {
        Accumulator accumulator = new Accumulator();
        @Nullable CatalogIndex index = catalogIndex();
        ImageCacheUsage usage = usage(index, false);
        for (ImageCacheEntry entry : usage.entries()) {
            if (entry.partialFiles() > 0) {
                removeEntryPartials(entry, index, accumulator);
            }
        }
        accumulator.skipped.addAll(usage.unreadable());
        purgeMarkers(accumulator, null);
        return accumulator.build();
    }

    /// Removes every cached image version, keeping metadata repositories and logs.
    ///
    /// @return removal result.
    public ImageCacheCleanResult cleanAll() {
        return operation().cleanAllFromSnapshot();
    }

    /// Runs the cleanup using the physical root captured for this operation.
    private ImageCacheCleanResult cleanAllFromSnapshot() {
        Accumulator accumulator = new Accumulator();
        @Nullable CatalogIndex index = catalogIndex();
        ImageCacheUsage usage = usage(index, false);
        for (ImageCacheEntry entry : usage.entries()) {
            if (isUnconfirmedLegacyVersion(entry, index)) {
                accumulator.skip("Ambiguous legacy cache directory; select the version explicitly: " + entry.atom());
            } else {
                removeEntry(entry, index, accumulator);
            }
        }
        accumulator.skipped.addAll(usage.unreadable());
        purgeMarkers(accumulator, null);
        return accumulator.build();
    }

    /// Maximum bytes accepted for a stored cache path, including UTF-8 and legacy absolute paths.
    private static final int MAX_MARKER_BYTES = 128 * 1024;

    /// Removes markers whose partial file is gone while the owning version lock is not held.
    ///
    /// A marker whose partial still exists belongs to a running download and is kept, and a marker
    /// whose version is locked is kept as well because that download may recreate the partial.
    ///
    /// @param accumulator removal accumulator.
    /// @param selectedVersions versions removed by this operation, or null to consider every version.
    private void purgeMarkers(Accumulator accumulator, @Nullable List<ImageCacheEntry> selectedVersions) {
        @Nullable Path directory = ImageCacheMarkers.directory(cacheDirectory, false);
        if (directory == null) {
            return;
        }
        if (realCacheRoot == null || !directory.equals(realCacheRoot.resolve(ImageCacheMarkers.DIRECTORY))) {
            accumulator.skip("Cache root changed during cleanup: " + directory);
            return;
        }
        @Nullable Set<String> selectedKeys = null;
        if (selectedVersions != null) {
            selectedKeys = new LinkedHashSet<>();
            for (ImageCacheEntry entry : selectedVersions) {
                selectedKeys.add(entry.repoId() + '/' + entry.category() + '/' + entry.name() + '/' + entry.version());
            }
            if (selectedKeys.isEmpty()) {
                return;
            }
        }
        @Unmodifiable List<Path> markers;
        try (var stream = Files.list(directory)) {
            markers = stream.toList();
        } catch (IOException | UncheckedIOException _) {
            accumulator.skipped.add(directory.toString());
            return;
        }
        Path downloadsRoot = cacheDirectory.resolve("downloads").toAbsolutePath().normalize();
        for (Path marker : markers) {
            if (!ImageCacheMarkers.isMarkerName(marker)
                    || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            String stored;
            try (var input = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAX_MARKER_BYTES + 1);
                if (bytes.length > MAX_MARKER_BYTES) {
                    accumulator.skip("Cache marker is too large: " + marker);
                    continue;
                }
                stored = new String(bytes, StandardCharsets.UTF_8).trim();
            } catch (IOException _) {
                accumulator.skipped.add(marker.toString());
                continue;
            }
            @Nullable Path partial = ImageCacheMarkers.resolvePartial(cacheDirectory, stored);
            if (partial == null || !ImageCacheMarkers.identifiesMarker(marker, cacheDirectory, partial, stored)) {
                continue;
            }
            @Nullable Path versionDirectory = partial.getParent();
            if (versionDirectory == null || !partial.startsWith(downloadsRoot)
                    || versionDirectory.toAbsolutePath().normalize().getNameCount()
                    != downloadsRoot.getNameCount() + 4) {
                continue;
            }
            Path relative = downloadsRoot.relativize(versionDirectory);
            if (selectedKeys != null && !selectedKeys.contains(versionKey(relative))) {
                // P2:198 显式清理不处理未选中版本的标记，即使该标记已经过期或无法取得锁。
                continue;
            }
            if (!isSafeVersionParent(downloadsRoot, relative.getName(0).toString(),
                    relative.getName(1).toString(), relative.getName(2).toString())
                    || (!Files.notExists(versionDirectory, LinkOption.NOFOLLOW_LINKS)
                    && !isRealDirectory(versionDirectory))) {
                accumulator.skip("Unsafe cache marker path: " + marker);
                continue;
            }
            Path physicalVersion = realCacheRoot.resolve("downloads").resolve(relative);
            Path lockPath = physicalVersion.resolveSibling(physicalVersion.getFileName() + LOCK_SUFFIX);
            try {
                CacheTreeRemoval.withVersionLock(realCacheRoot, realCacheRoot.relativize(lockPath), () -> {
                    if (Files.notExists(physicalVersion.resolve(partial.getFileName()), LinkOption.NOFOLLOW_LINKS)) {
                        removePath(marker, false, (_, _) -> { }, accumulator.skipped);
                    }
                });
            } catch (IOException exception) {
                accumulator.skip("Cache lock failed for marker " + marker + ": " + exception.getMessage());
            }
        }
    }

    /// Returns the cache key of one version directory relative to the download tree.
    ///
    /// @param relative `repo/category/name/version` path.
    /// @return slash-separated key.
    private static String versionKey(Path relative) {
        StringBuilder builder = new StringBuilder();
        for (Path component : relative) {
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(component);
        }
        return builder.toString();
    }

    /// Removes one cached image version while holding its lock.
    ///
    /// @param entry entry to remove.
    /// @param index current catalog snapshot.
    /// @param accumulator removal accumulator.
    private void removeEntry(ImageCacheEntry entry, @Nullable CatalogIndex index, Accumulator accumulator) {
        if (!isSafeEntry(entry)) {
            accumulator.skip("Unsafe cache entry: " + entry.atom());
            return;
        }
        if (!isSafeVersionTarget(cacheDirectory.resolve("artifacts"), entry)
                || !isSafeVersionTarget(cacheDirectory.resolve("imports"), entry)) {
            accumulator.skip("Unsafe cache path: " + entry.atom());
            return;
        }
        CacheTreeRemoval.VersionAction removeVersion = locked -> {
            // P1:339 下载版本目录相对取得兼容锁时打开并校验过的父目录删除；
            // 父目录被重命名替换后不能按名称重新查找 `downloads` 删除别的版本。
            locked.remove(Path.of(entry.version()), CacheTreeRemoval.Target.DIRECTORY,
                    accumulator::removedFile, accumulator.skipped);
            removeVersionDirectory(versionDirectory(cacheDirectory.resolve("artifacts"), entry), accumulator);
            removeVersionDirectory(versionDirectory(cacheDirectory.resolve("imports"), entry), accumulator);
            removeLeftoversOf(entry, versionDirectory(cacheDirectory.resolve("artifacts"), entry), index, accumulator);
        };
        @Nullable String possibleBase = legacyBase(entry.version());
        @Nullable Set<String> declared = index == null ? null : index.versions(entry.repoId(), entry.category(), entry.name());
        if (possibleBase != null && (declared == null || !declared.contains(entry.version()))) {
            // The catalog may no longer identify this as a leftover owned by an older materializer.
            withLock(entry, possibleBase, accumulator, removeVersion);
        } else {
            withLock(entry, entry.version(), accumulator, removeVersion);
        }
    }

    /// Removes incomplete files of one cached image version while holding its lock.
    ///
    /// @param entry entry whose partial files are removed.
    /// @param index current catalog snapshot.
    /// @param accumulator removal accumulator.
    private void removeEntryPartials(ImageCacheEntry entry, @Nullable CatalogIndex index, Accumulator accumulator) {
        if (!isSafeEntry(entry)) {
            return;
        }
        if (!isSafeVersionTarget(cacheDirectory.resolve("artifacts"), entry)
                || !isSafeVersionTarget(cacheDirectory.resolve("imports"), entry)) {
            accumulator.skip("Unsafe cache path: " + entry.atom());
            return;
        }
        withLock(entry, entry.version(), accumulator, locked -> {
            removePartialsInside(locked, entry, index, accumulator);
            removeLeftoversOf(entry, versionDirectory(cacheDirectory.resolve("artifacts"), entry), index, accumulator);
            removeVersionDirectory(versionDirectory(cacheDirectory.resolve("imports"), entry), accumulator);
        });
    }

    /// Returns whether a legacy-looking artifact has no evidence of being an independent version.
    private boolean isUnconfirmedLegacyVersion(ImageCacheEntry entry, @Nullable CatalogIndex index) {
        if (legacyBase(entry.version()) == null) {
            return false;
        }
        @Nullable Set<String> versions = index == null
                ? null : index.versions(entry.repoId(), entry.category(), entry.name());
        return (versions == null || !versions.contains(entry.version()))
                && !isRealDirectory(versionDirectory(cacheDirectory.resolve("downloads"), entry));
    }

    /// Acquires the per-version lock and runs the given action.
    ///
    /// @param entry entry whose lock is acquired.
    /// @param accumulator removal accumulator.
    /// @param version version whose lock is acquired.
    /// @param action action run while the lock is held with the bound lock parent.
    private void withLock(ImageCacheEntry entry, String version, Accumulator accumulator,
            CacheTreeRemoval.VersionAction action) {
        @Nullable Path root = realCacheRoot;
        if (root == null) {
            if (!Files.notExists(cacheDirectory, LinkOption.NOFOLLOW_LINKS)) {
                accumulator.skip("Cannot identify cache root: " + cacheDirectory);
            }
            return;
        }
        Path lock = lockPath(entry, version);
        try {
            if (!CacheTreeRemoval.withVersionLock(root, root.relativize(lock), action)) {
                accumulator.skip(SKIPPED_IN_USE + entry.atom());
            }
        } catch (IOException exception) {
            accumulator.skip("Cache lock failed for " + entry.atom() + ": " + exception.getMessage());
        }
    }

    /// Returns the lock path of one cached image version.
    ///
    /// @param entry cache entry.
    /// @param version version whose lock path is requested.
    /// @return lock path.
    private Path lockPath(ImageCacheEntry entry, String version) {
        Path root = realCacheRoot == null ? cacheDirectory : realCacheRoot;
        return root.resolve("downloads")
                .resolve(entry.repoId())
                .resolve(entry.category())
                .resolve(entry.name())
                .resolve(version + LOCK_SUFFIX);
    }

    /// Removes incomplete files inside one version directory.
    ///
    /// P1:339 版本目录相对取得兼容锁时绑定的父目录打开，列举与删除都使用同一个句柄。
    ///
    /// @param parent bound lock parent directory.
    /// @param entry cache entry owning the directory.
    /// @param index current catalog snapshot.
    /// @param accumulator removal accumulator.
    private void removePartialsInside(
            CacheTreeRemoval.Directory parent,
            ImageCacheEntry entry,
            @Nullable CatalogIndex index,
            Accumulator accumulator) {
        Path versionPath = parent.path().resolve(entry.version());
        try (CacheTreeRemoval.Directory version = parent.open(Path.of(entry.version()))) {
            Path directory = version.path();
            for (Path name : version.names()) {
                // 只有真实目录才是下载暂存目录；同名普通文件属于正式归档，不能当作暂存目录删除。
                if (isDownloadStagingDirectory(name) && isDirectory(directory.resolve(name), accumulator.skipped)) {
                    version.remove(name, CacheTreeRemoval.Target.TREE,
                            accumulator::removedFile, accumulator.skipped);
                }
            }
            @Nullable Map<String, RuyiDistfile> declared = index == null
                    ? null : index.distfiles(entry.repoId(), entry.category(), entry.name(), entry.version());
            for (Path name : version.names()) {
                Path file = directory.resolve(name);
                if (!isIncompleteFile(directory, file, declared)) {
                    continue;
                }
                version.remove(name, CacheTreeRemoval.Target.FILE,
                        accumulator::removedFile, accumulator.skipped);
            }
        } catch (NoSuchFileException _) {
            // The download directory is already gone.
        } catch (IOException exception) {
            accumulator.skipped.add(versionPath + ": " + exception.getMessage());
        }
    }

    /// Removes leftover directories belonging to one artifact version directory.
    ///
    /// @param entry cache entry owning the leftovers.
    /// @param artifactVersion artifact version directory.
    /// @param index current catalog snapshot.
    /// @param accumulator removal accumulator.
    private void removeLeftoversOf(
            ImageCacheEntry entry, Path artifactVersion, @Nullable CatalogIndex index, Accumulator accumulator) {
        @Nullable Path nameDirectory = artifactVersion.getParent();
        if (nameDirectory == null) {
            return;
        }
        @Nullable Set<String> declared = index == null
                ? null
                : index.versions(entry.repoId(), entry.category(), entry.name());
        String version = artifactVersion.getFileName().toString();
        for (Path child : listChildren(nameDirectory, accumulator.skipped)) {
            if (version.equals(leftoverVersion(child, declared)) && isRealDirectory(child)) {
                removePath(child, accumulator);
            }
        }
    }

    /// Scans the download tree and merges entries into the given builders.
    ///
    /// @param builders accumulated entries.
    /// @param unreadable paths that could not be read.
    /// @param index catalog index used to recognize downloads left by older versions.
    private void scanDownloads(
            Map<String, EntryBuilder> builders,
            List<String> unreadable,
            @Nullable CatalogIndex index) {
        Path root = cacheDirectory.resolve("downloads");
        for (Path repo : listDirectories(root, unreadable)) {
            for (Path category : listDirectories(repo, unreadable)) {
                for (Path name : listDirectories(category, unreadable)) {
                    for (Path child : listChildren(name, unreadable)) {
                        if (!isDirectory(child, unreadable)) {
                            continue;
                        }
                        String version = child.getFileName().toString();
                        @Nullable EntryBuilder builder = builder(builders, repo, category, name, version);
                        if (builder == null) {
                            continue;
                        }
                        Set<Path> stagingDirectories = new LinkedHashSet<>();
                        for (Path staging : listDirectories(child, unreadable)) {
                            if (isDownloadStagingDirectory(staging)) {
                                stagingDirectories.add(staging.getFileName());
                                builder.present = true;
                                builder.partialFiles++;
                                builder.touch(staging);
                            }
                        }
                        @Nullable Map<String, RuyiDistfile> declared = index == null
                                ? null
                                : index.distfiles(builder.repoId, builder.category, builder.name, version);
                        builder.cachedDistfiles = countCachedArchives(child, declared, unreadable);
                        // P2:494 私有续传硬链接与 `.part` 指向同一个文件；这里按文件身份只计一次占用字节，
                        // 否则 4096 字节的续传分片会报告为 8192 字节。
                        Set<Object> countedFiles = new LinkedHashSet<>();
                        for (Path file : listFiles(child, unreadable)) {
                            builder.present = true;
                            BasicFileAttributes attributes;
                            try {
                                attributes = Files.readAttributes(
                                        file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            } catch (IOException _) {
                                unreadable.add(file.toString());
                                continue;
                            }
                            long size = attributes.size();
                            @Nullable Object identity = CacheTreeRemoval.fileIdentity(file, attributes);
                            boolean alreadyCounted = identity != null && !countedFiles.add(identity);
                            Path relative = child.relativize(file);
                            if (relative.getNameCount() > 1 && stagingDirectories.contains(relative.getName(0))) {
                                if (!alreadyCounted) {
                                    builder.partialBytes += size;
                                }
                                builder.touch(attributes);
                                continue;
                            }
                            if (relative.getNameCount() == 1 && isIncompleteFile(child, file, declared)) {
                                builder.partialFiles++;
                                if (!alreadyCounted) {
                                    builder.partialBytes += size;
                                }
                            } else if (!alreadyCounted) {
                                builder.downloadBytes += size;
                            }
                            builder.touch(attributes);
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
    /// @param index catalog index used to recognize leftovers and declared versions.
    private void scanArtifacts(
            Map<String, EntryBuilder> builders,
            List<String> unreadable,
            @Nullable CatalogIndex index) {
        Path root = cacheDirectory.resolve("artifacts");
        for (Path repo : listDirectories(root, unreadable)) {
            for (Path category : listDirectories(repo, unreadable)) {
                for (Path name : listDirectories(category, unreadable)) {
                    for (Path child : listChildren(name, unreadable)) {
                        if (!isDirectory(child, unreadable)) {
                            continue;
                        }
                        String fileName = child.getFileName().toString();
                        @Nullable String leftoverVersion = leftoverVersion(
                                child,
                                index == null
                                        ? null
                                        : index.versions(
                                                repo.getFileName().toString(),
                                                category.getFileName().toString(),
                                                name.getFileName().toString()));
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
                        @Unmodifiable List<Path> files = listFiles(child, unreadable);
                        if (!files.isEmpty()) {
                            builder.present = true;
                            for (Path file : files) {
                                @Nullable BasicFileAttributes attributes = readAttributes(file, unreadable);
                                if (attributes != null) {
                                    builder.artifactBytes += attributes.size();
                                    builder.touch(attributes);
                                }
                            }
                            builder.touch(child);
                        }
                    }
                }
            }
        }
    }

    /// Scans the local-import staging tree and merges entries into the given builders.
    ///
    /// Every file below the staging tree belongs to an in-progress or interrupted import, so it is
    /// reported as incomplete rather than as a cached archive.
    ///
    /// @param builders accumulated entries.
    /// @param unreadable paths that could not be read.
    private void scanImports(Map<String, EntryBuilder> builders, List<String> unreadable) {
        Path root = cacheDirectory.resolve("imports");
        for (Path repo : listDirectories(root, unreadable)) {
            for (Path category : listDirectories(repo, unreadable)) {
                for (Path name : listDirectories(category, unreadable)) {
                    for (Path child : listChildren(name, unreadable)) {
                        if (!isDirectory(child, unreadable)) {
                            continue;
                        }
                        String version = child.getFileName().toString();
                        @Nullable EntryBuilder builder = builder(builders, repo, category, name, version);
                        if (builder == null) {
                            continue;
                        }
                        for (Path file : listFiles(child, unreadable)) {
                            long size = sizeOfFile(file, unreadable);
                            builder.present = true;
                            builder.partialBytes += size;
                            builder.partialFiles++;
                            builder.touch(file);
                        }
                    }
                }
            }
        }
    }

    /// Returns the image version encoded in a leftover directory name.
    ///
    /// Only the current dot-prefixed namespace identifies staging without guessing its origin.
    /// Legacy-looking names can belong to retired versions and require explicit selection.
    ///
    /// @param directory candidate artifact directory.
    /// @param declaredVersions versions declared for the image, or null when unknown.
    /// @return image version, or null when the directory is not a leftover.
    private static @Nullable String leftoverVersion(Path directory, @Nullable Set<String> declaredVersions) {
        String name = directory.getFileName().toString();
        if (declaredVersions != null && declaredVersions.contains(name)) {
            return null;
        }
        String rest;
        if (name.startsWith(RuyiImageMaterializer.STAGING_PREFIX)) {
            rest = name.substring(RuyiImageMaterializer.STAGING_PREFIX.length());
        } else if (name.startsWith(RuyiImageMaterializer.BACKUP_PREFIX)) {
            rest = name.substring(RuyiImageMaterializer.BACKUP_PREFIX.length());
        } else {
            return null;
        }
        int separator = rest.lastIndexOf('-');
        return separator <= 0 ? rest : rest.substring(0, separator);
    }

    /// Returns whether a real download child directory is reserved for staging.
    private static boolean isDownloadStagingDirectory(Path directory) {
        String name = directory.getFileName().toString();
        if (name.startsWith(RuyiImageCatalogService.IMPORT_STAGING_DIRECTORY_PREFIX)) {
            return true;
        }
        String prefix = RuyiDistfileDownloader.DOWNLOAD_PARTIAL_DIRECTORY_PREFIX;
        if (!name.startsWith(prefix)) {
            return false;
        }
        String counter = name.substring(prefix.length());
        return !counter.isEmpty() && counter.chars().allMatch(ch -> ch >= '0' && ch <= '9');
    }

    /// Returns the possible owner encoded in a legacy staging or backup directory name.
    private static @Nullable String legacyBase(String name) {
        if (RuyiImageMaterializer.isWorkingArtifactDirectory(name)) {
            return null;
        }
        int marker = Math.max(name.lastIndexOf(".tmp-"), name.lastIndexOf(".old-"));
        return marker <= 0 ? null : name.substring(0, marker);
    }

    /// Counts required archives through their declared filesystem paths.
    private static int countCachedArchives(
            Path directory, @Nullable Map<String, RuyiDistfile> declared, List<String> unreadable) {
        int count = 0;
        if (declared != null) {
            for (RuyiDistfile archive : declared.values()) {
                @Nullable BasicFileAttributes attributes = readAttributes(directory.resolve(archive.name()), unreadable);
                @Nullable Long size = archive.sizeBytes();
                if (attributes != null && attributes.isRegularFile() && (size == null || attributes.size() == size)) {
                    count++;
                }
            }
        }
        return count;
    }

    /// Identifies an incomplete direct download without treating reserved final paths as temporary.
    private boolean isIncompleteFile(Path directory, Path file, @Nullable Map<String, RuyiDistfile> declared) {
        String name = file.getFileName().toString();
        boolean oldImport = name.startsWith(IMPORT_PREFIX) && name.endsWith(IMPORT_SUFFIX);
        boolean download = name.length() >= ARCHIVE_PART_SUFFIX.length()
                && name.regionMatches(true, name.length() - ARCHIVE_PART_SUFFIX.length(),
                        ARCHIVE_PART_SUFFIX, 0, ARCHIVE_PART_SUFFIX.length());
        if ((!oldImport && !download) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (declared != null) {
            for (String finalName : declared.keySet()) {
                if (sameArchivePath(file, directory.resolve(finalName))) {
                    return false;
                }
            }
        }
        if (ImageCacheMarkers.exists(cacheDirectory, file)) {
            return true;
        }
        if (declared == null) {
            return false;
        }
        if (oldImport) {
            return true;
        }
        for (String finalName : declared.keySet()) {
            String legacyName = finalName + ARCHIVE_PART_SUFFIX;
            if (legacyName.equalsIgnoreCase(name) && sameArchivePath(file, directory.resolve(legacyName))) {
                return true;
            }
        }
        return false;
    }

    /// Checks two archive names using the semantics of the actual filesystem.
    private static boolean sameArchivePath(Path file, Path declaredPath) {
        if (file.getFileName().toString().equals(declaredPath.getFileName().toString())) {
            return true;
        }
        try {
            return Files.isRegularFile(declaredPath, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(file, declaredPath);
        } catch (IOException _) {
            return false;
        }
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
    private boolean isSafeEntry(ImageCacheEntry entry) {
        try {
            for (String component : List.of(entry.repoId(), entry.category(), entry.name(), entry.version())) {
                if (!isSafeComponent(component)) {
                    return false;
                }
                Path path = cacheDirectory.getFileSystem().getPath(component);
                if (path.getRoot() != null || path.getNameCount() != 1
                        || !path.getFileName().toString().equals(component)) {
                    return false;
                }
            }
            return true;
        } catch (InvalidPathException _) {
            return false;
        }
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
    private boolean isSafeVersionTarget(Path root, ImageCacheEntry entry) {
        return isSafeVersionParent(root, entry.repoId(), entry.category(), entry.name());
    }

    /// Checks the physical ancestors through the directory that holds the version lock.
    ///
    /// @param root cache subtree root.
    /// @param repo repository path component.
    /// @param category image category path component.
    /// @param name image name path component.
    /// @return whether existing ancestors are directories inside the cache.
    private boolean isSafeVersionParent(Path root, String repo, String category, String name) {
        Path repository = root.resolve(repo);
        Path categoryDirectory = repository.resolve(category);
        for (Path path : List.of(root, repository, categoryDirectory, categoryDirectory.resolve(name))) {
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            if (!isRealDirectory(path)) {
                return false;
            }
        }
        return true;
    }

    /// Returns whether a path is a real directory rather than a symbolic link.
    ///
    /// @param path inspected path.
    /// @return whether the path is a real directory.
    private boolean isRealDirectory(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return isPhysicalPath(path);
    }

    /// Returns whether a path resolves to itself, so links and junctions are rejected.
    ///
    /// @param path inspected path.
    /// @return whether the path is physical.
    private boolean isPhysicalPath(Path path) {
        return isPhysicalPath(path, null);
    }

    /// Checks physical path identity and optionally records filesystem lookup failures.
    private boolean isPhysicalPath(Path path, @Nullable List<String> unreadable) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            Path configuredRoot = cacheDirectory.toAbsolutePath().normalize();
            if (!normalized.startsWith(configuredRoot)) {
                return false;
            }
            @Nullable Path realRoot = realCacheRoot;
            if (realRoot == null) {
                return path.toRealPath().equals(normalized);
            }
            return path.toRealPath().equals(realRoot.resolve(configuredRoot.relativize(normalized)).normalize());
        } catch (NoSuchFileException _) {
            return false;
        } catch (IOException _) {
            if (unreadable != null) {
                unreadable.add(path.toString());
            }
            return false;
        }
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
    private boolean isDirectory(Path path, List<String> unreadable) {
        @Nullable BasicFileAttributes attributes = readAttributes(path, unreadable);
        return attributes != null && attributes.isDirectory() && isPhysicalPath(path, unreadable);
    }

    /// Lists the directories below one directory.
    ///
    /// @param directory directory to list.
    /// @param unreadable paths that could not be read.
    /// @return child directories.
    private @Unmodifiable List<Path> listDirectories(Path directory, List<String> unreadable) {
        ArrayList<Path> directories = new ArrayList<>();
        for (Path child : listChildren(directory, unreadable)) {
            if (isDirectory(child, unreadable)) {
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
    private @Unmodifiable List<Path> listChildren(Path directory, List<String> unreadable) {
        try {
            if (!Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
                return List.of();
            }
            try (var stream = Files.list(directory)) {
                return stream.sorted().toList();
            }
        } catch (NoSuchFileException _) {
            return List.of();
        } catch (IOException | UncheckedIOException _) {
            unreadable.add(directory.toString());
            return List.of();
        }
    }

    /// Lists the regular files below one directory.
    ///
    /// @param directory directory to walk.
    /// @param unreadable paths that could not be read.
    /// @return regular files.
    private @Unmodifiable List<Path> listFiles(Path directory, List<String> unreadable) {
        ArrayList<Path> files = new ArrayList<>();
        walk(directory, files, unreadable);
        return List.copyOf(files);
    }

    /// Collects files below a root without following symbolic links.
    ///
    /// @param root root to walk.
    /// @param files collected regular files and symbolic links.
    /// @param unreadable paths that could not be read.
    private void walk(
            Path root,
            List<Path> files,
            List<String> unreadable) {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
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

                /// Keeps measurement within real cache directories.
                ///
                /// @param directory visited directory.
                /// @param attributes directory attributes.
                /// @return continuation result.
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!isPhysicalPath(directory, unreadable)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
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
        @Nullable BasicFileAttributes attributes = readAttributes(file, unreadable);
        return attributes == null ? 0L : attributes.size();
    }

    /// Reads cache attributes without following links and distinguishes missing from unreadable.
    private static @Nullable BasicFileAttributes readAttributes(Path file, List<String> unreadable) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException _) {
            return null;
        } catch (IOException _) {
            unreadable.add(file.toString());
            return null;
        }
    }

    /// Returns the total size of one file or directory tree.
    ///
    /// @param path file or directory.
    /// @param unreadable paths that could not be read.
    /// @return total size in bytes.
    private long sizeOf(Path path, List<String> unreadable) {
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
    private long sizeOfDirectory(Path directory, List<String> unreadable) {
        long bytes = 0L;
        for (Path file : listFiles(directory, unreadable)) {
            bytes += sizeOfFile(file, unreadable);
        }
        return bytes;
    }

    /// Removes a cache tree and counts the files successfully deleted.
    private void removePath(Path path, Accumulator accumulator) {
        removePath(path, true, accumulator::removedFile, accumulator.skipped);
    }

    /// Removes only a version directory, keeping files that collide with the version name.
    private void removeVersionDirectory(Path path, Accumulator accumulator) {
        removePath(path, CacheTreeRemoval.Target.DIRECTORY, accumulator::removedFile, accumulator.skipped);
    }

    /// Removes a cache tree or file selected by the scan.
    private void removePath(Path path, boolean recursive, CacheTreeRemoval.RemovedFile removedFile,
            List<String> skipped) {
        removePath(path, recursive ? CacheTreeRemoval.Target.TREE : CacheTreeRemoval.Target.FILE, removedFile, skipped);
    }

    /// Removes a cache path from its bound parent, without resolving mutable ancestors again.
    private void removePath(Path path, CacheTreeRemoval.Target target, CacheTreeRemoval.RemovedFile removedFile,
            List<String> skipped) {
        @Nullable Path root = realCacheRoot;
        Path configured = cacheDirectory.toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        if (root == null) {
            if (!Files.notExists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                skipped.add(path.toString());
            }
            return;
        }
        Path prefix = absolute.startsWith(configured) ? configured : root;
        if (!absolute.startsWith(prefix)) {
            skipped.add(path.toString());
            return;
        }
        CacheTreeRemoval.remove(root, prefix.relativize(absolute), target, removedFile, skipped);
    }

    /// Names declared by the catalog, used to recognize cache files written by older releases.
    @NotNullByDefault
    private static final class CatalogIndex {
        /// Declared versions keyed by `repo/category/name`.
        private final Map<String, Set<String>> versions;

        /// Declared distfiles keyed by `repo/category/name(version)`.
        private final Map<String, Map<String, RuyiDistfile>> distfiles;

        /// Builds the index from one catalog snapshot.
        ///
        /// P2:1113 索引按仓库保留归档声明；生产 `listImages()` 已跨仓库按 ATOM 去重，
        /// 因此调用方传入未去重的每仓库声明，避免低优先级仓库的缓存显示为 `UNKNOWN`。
        ///
        /// @param images every declared image, without cross-repository de-duplication.
        private CatalogIndex(@Unmodifiable List<ImageEntry> images) {
            Map<String, Set<String>> versionMap = new LinkedHashMap<>();
            Map<String, Map<String, RuyiDistfile>> distfileMap = new LinkedHashMap<>();
            for (ImageEntry image : images) {
                versionMap.computeIfAbsent(
                                image.repoId() + "/" + image.category() + "/" + image.name(),
                                _ -> new LinkedHashSet<>())
                        .add(image.version());
                Map<String, RuyiDistfile> files = distfileMap.computeIfAbsent(
                        image.repoId() + "/" + image.category() + "/" + image.name() + "(" + image.version() + ")",
                        _ -> new LinkedHashMap<>());
                for (RuyiDistfile distfile : image.distfiles()) {
                    files.put(distfile.name(), distfile);
                }
            }
            this.versions = versionMap;
            this.distfiles = distfileMap;
        }

        /// Returns the declared versions of one cached image, or null when the repository is unknown.
        ///
        /// @param repoId repository identifier.
        /// @param category image category.
        /// @param name image name.
        /// @return declared versions, or null when no image from that repository is known.
        private @Nullable Set<String> versions(String repoId, String category, String name) {
            return versions.get(repoId + "/" + category + "/" + name);
        }

        /// Returns the declared distfiles of one cached image version, or null when unknown.
        ///
        /// @param repoId repository identifier.
        /// @param category image category.
        /// @param name image name.
        /// @param version image version.
        /// @return declared distfiles by name, or null when the repository or version is unknown.
        private @Nullable Map<String, RuyiDistfile> distfiles(String repoId, String category, String name, String version) {
            return distfiles.get(repoId + "/" + category + "/" + name + "(" + version + ")");
        }
    }
    /// Mutable accumulator for one cleanup operation.
    @NotNullByDefault
    private static final class Accumulator {
        /// Removed bytes.
        private long removedBytes;

        /// Removed files.
        private int removedFiles;

        /// Identities of files whose bytes were already counted.
        ///
        /// P2:494 私有续传硬链接与 `.part` 指向同一个文件时，删除名称仍各计一次，
        /// 但占用与释放的字节必须按文件身份只计一次。
        private final Set<Object> countedFiles = new LinkedHashSet<>();

        /// Paths that could not be removed or read.
        private final List<String> skipped = new ArrayList<>();

        /// Records a successfully deleted entry and its size.
        ///
        /// @param size size of the deleted entry.
        /// @param identity file identity, or null when unavailable.
        private void removedFile(long size, @Nullable Object identity) {
            removedFiles++;
            if (identity == null || countedFiles.add(identity)) {
                removedBytes += size;
            }
        }

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

        /// Number of required archives found at their expected paths and sizes.
        private int cachedDistfiles;

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
                touch(Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
            } catch (IOException _) {
                // A missing timestamp keeps the previous one.
            }
        }

        /// Records the modification time already obtained by the scan.
        private void touch(BasicFileAttributes attributes) {
            lastModifiedMillis = Math.max(lastModifiedMillis, attributes.lastModifiedTime().toMillis());
        }

        /// Returns whether the entry holds no cached bytes.
        ///
        /// @return whether the entry is empty.
        private boolean isEmpty() {
            return !present;
        }

        /// Builds the immutable entry.
        ///
        /// @param index catalog metadata when available.
        /// @return cache entry.
        private ImageCacheEntry build(@Nullable CatalogIndex index) {
            @Nullable Map<String, RuyiDistfile> declared = index == null
                    ? null
                    : index.distfiles(repoId, category, name, version);
            ImageCacheEntry.Completeness completeness = ImageCacheEntry.Completeness.UNKNOWN;
            if (declared != null && !declared.isEmpty()) {
                completeness = cachedDistfiles == declared.size()
                        ? ImageCacheEntry.Completeness.COMPLETE
                        : ImageCacheEntry.Completeness.INCOMPLETE;
            }
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
                    lastModifiedMillis,
                    completeness);
        }
    }
}
