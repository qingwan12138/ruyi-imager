// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.AppDirectories;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/// Inspects and cleans the on-disk image download cache.
///
/// The cache keeps downloaded archives under `downloads` and materialized images under `artifacts`;
/// neither is ever pruned by the download path itself. This service reports what is stored and
/// removes either explicitly selected image versions, leftover partial files, or both roots.
/// Metadata repositories and logs live outside these roots and are only measured.
///
/// Cleanup takes the same per-version lock that downloads and local imports use, so removing a
/// version that another process is writing fails instead of corrupting it. Measurement and cleanup
/// treat symbolic links as entries. Completeness checks may follow a declared archive link.
/// Paths that cannot be listed or removed are reported through the result instead of
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

    /// Configured cache alias, used for verification and historical marker keys.
    private final Path configuredCacheDirectory;

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
        this.configuredCacheDirectory = cacheDirectory;
        this.cacheDirectory = realCacheRoot == null ? cacheDirectory : realCacheRoot;
        this.images = images;
        this.realCacheRoot = realCacheRoot;
    }

    /// Resolves the configured cache alias once for a scan or cleanup.
    private ImageCacheService operation() {
        try {
            return new ImageCacheService(configuredCacheDirectory, images, configuredCacheDirectory.toRealPath());
        } catch (IOException _) {
            return new ImageCacheService(configuredCacheDirectory, images, null);
        }
    }

    /// Returns catalog names, or null when the catalog is unavailable.
    ///
    /// Invalidates the process-local snapshot so cleanup reads current on-disk declarations.
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
        return operation().usage(catalogIndex(), false, bulk, false);
    }

    /// Measures only paths that partial cleanup can affect.
    ///
    /// @return usage of the partial cleanup scope.
    public ImageCacheUsage usageForPartialsCleanup() {
        return operation().usage(catalogIndex(), false, false, true);
    }

    /// Measures one catalog snapshot, optionally including retained repositories and logs.
    private ImageCacheUsage usage(@Nullable CatalogIndex index, boolean includeRetainedTrees) {
        return usage(index, includeRetainedTrees, false, false);
    }

    /// Measures cache trees using one catalog snapshot and the requested cleanup scope.
    private ImageCacheUsage usage(@Nullable CatalogIndex index, boolean includeRetainedTrees, boolean bulk, boolean partialsOnly) {
        return usage(index, includeRetainedTrees, bulk, partialsOnly, null, null);
    }

    /// Measures shared release groups for targeted previews when requested.
    private ImageCacheUsage usage(@Nullable CatalogIndex index, boolean includeRetainedTrees, boolean bulk,
            boolean partialsOnly, @Nullable List<SharedRelease> sharedReleases,
            @Nullable Map<String, Path> markerOwners) {
        ArrayList<String> unreadable = new ArrayList<>();
        Map<String, List<EntryBuilder>> builders = new LinkedHashMap<>();
        Map<Object, FileLinks> links = new LinkedHashMap<>();
        List<ImageCacheUsage.LegacyResumeCandidate> candidates = new ArrayList<>();
        scanDownloads(builders, links, unreadable, index, bulk, partialsOnly, markerOwners, candidates);
        scanArtifacts(builders, links, unreadable, index, partialsOnly);
        scanImports(builders, links, unreadable);
        for (FileLinks group : links.values()) {
            if (group.complete() && group.owners.size() == 1) {
                group.owners.iterator().next().addBytes(group.kind, group.size);
            }
        }

        long downloadBytes = 0L;
        long artifactBytes = 0L;
        long partialBytes = 0L;
        int partialFiles = 0;
        ArrayList<ImageCacheEntry> entries = new ArrayList<>();
        ArrayList<ImageCacheRetainedEntry> retained = new ArrayList<>();
        Map<EntryBuilder, ImageCacheEntry> included = new LinkedHashMap<>();
        for (EntryBuilder builder : builders.values().stream().flatMap(List::stream).toList()) {
            if (builder.isEmpty()) {
                continue;
            }
            ImageCacheEntry entry = builder.build(partialsOnly ? null : index, this);
            if (bulk && isUnconfirmedLegacyVersion(entry, index)) {
                retained.add(new ImageCacheRetainedEntry(
                        entry, ImageCacheRetainedEntry.Reason.AMBIGUOUS_LEGACY_DIRECTORY));
                continue;
            }
            downloadBytes += builder.downloadBytes;
            artifactBytes += builder.artifactBytes;
            partialBytes += builder.partialBytes;
            partialFiles += builder.partialFiles;
            entries.add(entry);
            included.put(builder, entry);
        }
        for (FileLinks group : links.values()) {
            if (!group.complete() || group.owners.size() < 2 || !included.keySet().containsAll(group.owners)) {
                continue;
            }
            switch (group.kind) {
                case DOWNLOAD -> downloadBytes += group.size;
                case ARTIFACT -> artifactBytes += group.size;
                case PARTIAL -> partialBytes += group.size;
            }
            if (sharedReleases != null) {
                Set<ImageCacheEntry> owners = new LinkedHashSet<>();
                for (EntryBuilder owner : group.owners) {
                    owners.add(included.get(owner));
                }
                sharedReleases.add(new SharedRelease(Set.copyOf(owners), group.size));
            }
        }
        if (bulk || partialsOnly) {
            Map<String, Long> staleMarkers = staleMarkerSizes(null, unreadable);
            partialFiles += staleMarkers.size();
            for (long size : staleMarkers.values()) {
                partialBytes += size;
            }
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
                List.copyOf(retained), candidates);
    }

    /// Captures the same physical cache for both entry selection and cleanup.
    ///
    /// @return the bound selection scope.
    public CleanupScope selectForCleanup() {
        ImageCacheService operation = operation();
        @Nullable Object rootIdentity = null;
        if (operation.realCacheRoot != null) {
            try {
                rootIdentity = CacheTreeRemoval.lockParentIdentity(operation.realCacheRoot, Path.of(".selection"));
            } catch (IOException _) {
                // The selection can be displayed, but must not be used for deletion.
            }
        }
        @Nullable CatalogIndex index = operation.catalogIndex();
        List<SharedRelease> sharedReleases = new ArrayList<>();
        Map<String, Path> markerOwners = new LinkedHashMap<>();
        ImageCacheUsage usage = operation.usage(index, false, false, false, sharedReleases, markerOwners);
        @Unmodifiable Map<String, TargetedMarker> markers = rootIdentity == null
                ? Map.of() : operation.targetedMarkers(rootIdentity, usage.entries());
        return new CleanupScope(operation, rootIdentity, index, usage, List.copyOf(sharedReleases),
                Map.copyOf(markerOwners), markers);
    }

    /// A cache usage snapshot and the physical root it was taken from.
    @NotNullByDefault
    public static final class CleanupScope {
        /// Cache operation with its captured root.
        private final ImageCacheService operation;
        /// Identity of the physical root that supplied the selection.
        private final @Nullable Object rootIdentity;
        /// Catalog used to interpret paths in the usage snapshot.
        private final @Nullable CatalogIndex index;
        /// Usage snapshot used to select entries.
        private final ImageCacheUsage usage;
        /// Data released only when several versions are selected together.
        private final @Unmodifiable List<SharedRelease> sharedReleases;

        /// Version directory owning each unreadable historical marker.
        private final @Unmodifiable Map<String, Path> markerOwners;

        /// Marker membership captured while the observed version directories still existed.
        private final @Unmodifiable Map<String, TargetedMarker> markers;

        /// Creates a scope for targeted cleanup.
        private CleanupScope(ImageCacheService operation, @Nullable Object rootIdentity,
                @Nullable CatalogIndex index, ImageCacheUsage usage, @Unmodifiable List<SharedRelease> sharedReleases,
                @Unmodifiable Map<String, Path> markerOwners, @Unmodifiable Map<String, TargetedMarker> markers) {
            this.operation = operation;
            this.rootIdentity = rootIdentity;
            this.index = index;
            this.usage = usage;
            this.sharedReleases = sharedReleases;
            this.markerOwners = markerOwners;
            this.markers = markers;
        }

        /// Returns the cache usage associated with this selection.
        public ImageCacheUsage usage() {
            return usage;
        }

        /// Estimates the bytes released by removing the selected entries from this snapshot.
        public long releasedBytes(@Unmodifiable List<ImageCacheEntry> entries) {
            Set<ImageCacheEntry> selected = selectedEntries(entries);
            long bytes = 0L;
            for (ImageCacheEntry entry : selected) {
                bytes += entry.totalBytes();
            }
            for (SharedRelease shared : sharedReleases) {
                if (selected.containsAll(shared.owners())) {
                    bytes += shared.size();
                }
            }
            for (TargetedMarker marker : markers.values()) {
                @Nullable Long size = marker.standaloneBytes();
                if (size != null && selected.contains(marker.owner())) {
                    bytes += size;
                }
            }
            return bytes;
        }

        /// Intersects caller-supplied entries with this snapshot for both preview and execution.
        private Set<ImageCacheEntry> selectedEntries(@Unmodifiable List<ImageCacheEntry> entries) {
            Set<ImageCacheEntry> selected = new LinkedHashSet<>(usage.entries());
            selected.retainAll(entries);
            return selected;
        }

        /// Returns whether selecting this entry requires an explicit versioned ATOM.
        public boolean requiresFullAtom(ImageCacheEntry entry) {
            return operation.isUnconfirmedLegacyVersion(entry, index);
        }

        /// Checks whether a path that failed during the scan could belong to the requested image.
        public boolean mayContainAtom(String path, String atom) {
            Path root = operation.cacheDirectory.toAbsolutePath().normalize();
            Path absolute = markerOwners.getOrDefault(path, root.getFileSystem().getPath(path)).toAbsolutePath().normalize();
            if (!absolute.startsWith(root)) {
                return false;
            }
            Path relative = root.relativize(absolute);
            int count = relative.getNameCount();
            if (count < 3) {
                return true;
            }
            if (!atom.contains("/")) {
                return count == 3 || relative.getName(3).toString().equals(atom);
            }
            String category = relative.getName(2).toString();
            if (count == 3) {
                return atom.startsWith(category + "/");
            }
            String name = relative.getName(3).toString();
            String displayPrefix = category + "/" + name + "(";
            if (!atom.startsWith(displayPrefix) || !atom.endsWith(")")) {
                return false;
            }
            if (count == 4) {
                return true;
            }
            String version = relative.getName(4).toString();
            if (relative.startsWith("artifacts")) {
                @Nullable Set<String> declared = index == null ? null
                        : index.versions(relative.getName(1).toString(), category, name);
                @Nullable String owner = leftoverVersion(relative.getName(4), declared);
                if (owner != null) {
                    version = owner;
                }
            }
            return atom.equals(displayPrefix + version + ")");
        }

        /// Removes entries from the physical root that supplied the selection.
        public ImageCacheCleanResult cleanEntries(@Unmodifiable List<ImageCacheEntry> entries) {
            try {
                if (rootIdentity == null || operation.realCacheRoot == null
                        || !operation.realCacheRoot.equals(operation.configuredCacheDirectory.toRealPath())
                        || !rootIdentity.equals(CacheTreeRemoval.lockParentIdentity(
                        operation.realCacheRoot, Path.of(".selection")))) {
                    return new ImageCacheCleanResult(0, 0, List.of("Cache root changed during selection: "
                            + operation.configuredCacheDirectory));
                }
            } catch (IOException exception) {
                return new ImageCacheCleanResult(0, 0, List.of("Cannot verify cache root: " + exception.getMessage()));
            }
            Set<ImageCacheEntry> selected = selectedEntries(entries);
            @Unmodifiable List<String> unreadable = usage.unreadable().stream()
                    .filter(path -> selected.stream().anyMatch(entry -> mayContainAtom(path, entry.atom())))
                    .toList();
            Map<String, TargetedMarker> selectedMarkers = new LinkedHashMap<>();
            markers.forEach((name, marker) -> {
                if (selected.contains(marker.owner())) {
                    selectedMarkers.put(name, marker);
                }
            });
            return operation.cleanEntriesFromSnapshot(List.copyOf(selected), rootIdentity, unreadable,
                    Map.copyOf(selectedMarkers));
        }
    }

    /// Removes the given image versions from the cache.
    ///
    /// Versions locked by a running download or import are skipped.
    ///
    /// @param entries entries to remove.
    /// @return removal result.
    public ImageCacheCleanResult cleanEntries(@Unmodifiable List<ImageCacheEntry> entries) {
        if (entries.isEmpty()) {
            return ImageCacheCleanResult.empty();
        }
        ImageCacheService operation = operation();
        return operation.withCleanupRoot(root ->
                operation.cleanEntriesFromSnapshot(entries, root, List.of(), operation.targetedMarkers(root, entries)));
    }

    /// Runs the cleanup using the physical root captured for this operation.
    private ImageCacheCleanResult cleanEntriesFromSnapshot(@Unmodifiable List<ImageCacheEntry> entries,
            Object expectedRoot, @Unmodifiable List<String> unreadable,
            @Unmodifiable Map<String, TargetedMarker> markers) {
        Accumulator accumulator = new Accumulator(expectedRoot);
        @Nullable CatalogIndex index = catalogIndex();
        @Unmodifiable List<ImageCacheEntry> selected = distinct(entries);
        for (ImageCacheEntry entry : selected) {
            removeEntry(entry, index, accumulator);
        }
        if (!selected.isEmpty()) {
            purgeMarkers(accumulator, markers, Set.of());
        }
        return accumulator.build(unreadable);
    }

    /// Removes incomplete downloads and leftover materialization directories.
    ///
    /// @return removal result.
    public ImageCacheCleanResult cleanPartials() {
        ImageCacheService operation = operation();
        return operation.withCleanupRoot(operation::cleanPartialsFromSnapshot);
    }

    /// Runs the cleanup using the physical root captured for this operation.
    private ImageCacheCleanResult cleanPartialsFromSnapshot(Object expectedRoot) {
        Accumulator accumulator = new Accumulator(expectedRoot);
        @Nullable CatalogIndex index = catalogIndex();
        ImageCacheUsage usage = usage(index, false, false, true);
        Set<String> standaloneMarkers = staleMarkerSizes(expectedRoot, new ArrayList<>()).keySet();
        for (ImageCacheEntry entry : usage.entries()) {
            removeEntryPartials(entry, index, accumulator);
        }
        purgeMarkers(accumulator, null, standaloneMarkers);
        return accumulator.build(usage.unreadable(), usage.legacyResumeCandidates());
    }

    /// Removes every cached image version, keeping metadata repositories and logs.
    ///
    /// @return removal result.
    public ImageCacheCleanResult cleanAll() {
        ImageCacheService operation = operation();
        return operation.withCleanupRoot(operation::cleanAllFromSnapshot);
    }

    /// Captures the physical root before loading declarations or measuring the cache.
    private ImageCacheCleanResult withCleanupRoot(Function<Object, ImageCacheCleanResult> cleanup) {
        @Nullable Path root = realCacheRoot;
        if (root == null) {
            return Files.notExists(configuredCacheDirectory, LinkOption.NOFOLLOW_LINKS)
                    ? ImageCacheCleanResult.empty()
                    : new ImageCacheCleanResult(0, 0, List.of("Cannot identify cache root: " + configuredCacheDirectory));
        }
        Object expectedRoot;
        try {
            expectedRoot = CacheTreeRemoval.lockParentIdentity(root, Path.of(".selection"));
            if (!root.equals(configuredCacheDirectory.toRealPath())) {
                return new ImageCacheCleanResult(0, 0, List.of("Cache root changed: " + configuredCacheDirectory));
            }
        } catch (NoSuchFileException _) {
            if (Files.notExists(configuredCacheDirectory, LinkOption.NOFOLLOW_LINKS)) {
                return ImageCacheCleanResult.empty();
            }
            return new ImageCacheCleanResult(0, 0, List.of("Cache root changed: " + configuredCacheDirectory));
        } catch (IOException exception) {
            return new ImageCacheCleanResult(0, 0, List.of("Cannot verify cache root: " + exception.getMessage()));
        }
        return cleanup.apply(expectedRoot);
    }

    /// Runs the cleanup using the physical root captured for this operation.
    private ImageCacheCleanResult cleanAllFromSnapshot(Object expectedRoot) {
        Accumulator accumulator = new Accumulator(expectedRoot);
        @Nullable CatalogIndex index = catalogIndex();
        ImageCacheUsage usage = usage(index, false, true, false);
        accumulator.retained.addAll(usage.retained());
        Set<String> standaloneMarkers = staleMarkerSizes(expectedRoot, new ArrayList<>()).keySet();
        for (ImageCacheEntry entry : usage.entries()) {
            removeEntry(entry, index, accumulator);
        }
        purgeMarkers(accumulator, null, standaloneMarkers);
        return accumulator.build(usage.unreadable());
    }

    /// Measures validated markers whose download file was already absent before cleanup.
    private Map<String, Long> staleMarkerSizes(@Nullable Object expectedRoot, List<String> unreadable) {
        Map<String, Long> sizes = new LinkedHashMap<>();
        @Nullable Path root = realCacheRoot;
        if (root == null) {
            return sizes;
        }
        try (CacheTreeRemoval.Directory markers = CacheTreeRemoval.bindDirectory(root,
                Path.of(ImageCacheMarkers.DIRECTORY), false, false, expectedRoot)) {
            for (Path marker : markers.names()) {
                if (!ImageCacheMarkers.isMarkerName(marker)) {
                    continue;
                }
                try {
                    @Nullable Path partial = readMarker(markers, marker);
                    if (partial == null || !isMissingPartial(root, partial, expectedRoot)) {
                        continue;
                    }
                    sizes.put(marker.toString(), markerBytes(markers, marker));
                } catch (NoSuchFileException _) {
                    // Another operation removed the entry during the scan.
                } catch (IOException exception) {
                    unreadable.add(markers.path().resolve(marker).toString());
                }
            }
        } catch (NoSuchFileException _) {
            // There is no marker directory.
        } catch (IOException | DirectoryIteratorException exception) {
            unreadable.add(root.resolve(ImageCacheMarkers.DIRECTORY).toString());
        }
        return sizes;
    }

    /// Checks for an absent partial without creating any version directories or locks.
    private static boolean isMissingPartial(Path root, Path partial, @Nullable Object expectedRoot) throws IOException {
        try (CacheTreeRemoval.Directory version = CacheTreeRemoval.bindDirectory(root,
                root.relativize(partial.getParent()), false, false, expectedRoot)) {
            version.attributes(partial.getFileName());
            return false;
        } catch (NoSuchFileException _) {
            return true;
        }
    }

    /// An authorized marker from a targeted selection.
    ///
    /// @param owner selected version that owns the marker.
    /// @param partial path stored by the marker when selected.
    /// @param standaloneBytes reclaimable bytes, or null when it still has its partial file.
    @NotNullByDefault
    private record TargetedMarker(ImageCacheEntry owner, Path partial, @Nullable Long standaloneBytes) {
    }

    /// Matches marker contents against the version directories before they can be removed.
    private @Unmodifiable Map<String, TargetedMarker> targetedMarkers(Object expectedRoot,
            @Unmodifiable List<ImageCacheEntry> versions) {
        @Nullable Path root = realCacheRoot;
        if (root == null || versions.isEmpty()) {
            return Map.of();
        }
        Path downloads = root.resolve("downloads");
        Map<String, List<ImageCacheEntry>> bySpelling = new LinkedHashMap<>();
        for (ImageCacheEntry entry : versions) {
            if (isSafeEntry(entry)) {
                String key = versionKey(downloads.relativize(versionDirectory(downloads, entry)));
                bySpelling.computeIfAbsent(aliasKey(key), _ -> new ArrayList<>()).add(entry);
            }
        }
        Map<String, TargetedMarker> selected = new LinkedHashMap<>();
        try (CacheTreeRemoval.Directory markers = CacheTreeRemoval.bindDirectory(root,
                Path.of(ImageCacheMarkers.DIRECTORY), false, false, expectedRoot)) {
            for (Path marker : markers.names()) {
                if (!ImageCacheMarkers.isMarkerName(marker)) {
                    continue;
                }
                try {
                    @Nullable Path partial = readMarker(markers, marker);
                    if (partial == null) {
                        continue;
                    }
                    Path directory = partial.getParent();
                    String spelling = versionKey(downloads.relativize(directory));
                    for (ImageCacheEntry entry : bySpelling.getOrDefault(aliasKey(spelling), List.of())) {
                        Path version = versionDirectory(downloads, entry);
                        if (!version.toString().equals(directory.toString()) && !sameDirectory(version, directory)) {
                            continue;
                        }
                        @Nullable Long bytes = null;
                        if (isMissingPartial(root, partial, expectedRoot)) {
                            bytes = markerBytes(markers, marker);
                        }
                        selected.put(marker.toString(), new TargetedMarker(entry, partial, bytes));
                        break;
                    }
                } catch (IOException _) {
                    // Diagnostics for the known marker keys are retained from the version scan.
                }
            }
        } catch (IOException | DirectoryIteratorException _) {
            // Do not infer marker ownership from an unreadable directory.
        }
        return Map.copyOf(selected);
    }

    /// Measures the data released by removing a marker name.
    private static long markerBytes(CacheTreeRemoval.Directory markers, Path marker) throws IOException {
        long size = markers.attributes(marker).size();
        return CacheTreeRemoval.linkCount(markers.path().resolve(marker)) == 1L ? size : 0L;
    }

    /// Removes markers whose partial file is gone while the owning version lock is not held.
    ///
    /// A marker whose partial still exists belongs to a running download and is kept, and a marker
    /// whose version is locked is kept as well because that download may recreate the partial.
    ///
    /// @param accumulator removal accumulator.
    /// @param selectedMarkers markers belonging to the targeted versions, or null for bulk cleanup.
    /// @param standaloneMarkers published marker names whose download file was absent at the start.
    private void purgeMarkers(Accumulator accumulator, @Nullable Map<String, TargetedMarker> selectedMarkers,
            Set<String> standaloneMarkers) {
        @Nullable Path root = realCacheRoot;
        if (root == null) {
            return;
        }
        try (CacheTreeRemoval.Directory markers = CacheTreeRemoval.bindDirectory(root,
                Path.of(ImageCacheMarkers.DIRECTORY), false, false, accumulator.expectedRoot)) {
            for (Path marker : markers.names()) {
                if (!ImageCacheMarkers.isMarkerName(marker)) {
                    continue;
                }
                @Nullable TargetedMarker selected = selectedMarkers == null ? null : selectedMarkers.get(marker.toString());
                if (selectedMarkers != null && selected == null) {
                    continue;
                }
                @Nullable Path partial;
                try {
                    partial = readMarker(markers, marker);
                } catch (IOException exception) {
                    accumulator.skip(markers.path().resolve(marker), exception);
                    continue;
                }
                if (partial == null || selected != null && !selected.partial().toString().equals(partial.toString())) {
                    continue;
                }
                Path versionDirectory = partial.getParent();
                Path lock = versionDirectory.resolveSibling(versionDirectory.getFileName() + LOCK_SUFFIX);
                boolean standalone = selected == null
                        ? standaloneMarkers.contains(marker.toString()) : selected.standaloneBytes() != null;
                try {
                    boolean acquired = CacheTreeRemoval.withVersionLock(root, root.relativize(lock),
                            accumulator.expectedRoot, parent -> {
                        @Nullable Path current = readMarker(markers, marker);
                        if (current == null || !partial.toString().equals(current.toString())) {
                            return;
                        }
                        try (CacheTreeRemoval.Directory version = parent.open(versionDirectory.getFileName())) {
                            try {
                                version.attributes(partial.getFileName());
                                return;
                            } catch (NoSuchFileException _) {
                                // The partial itself is gone.
                            }
                        } catch (NoSuchFileException _) {
                            // The whole version is gone.
                        }
                        markers.remove(marker, CacheTreeRemoval.Target.REGULAR_FILE,
                                bytes -> {
                                    if (standalone) {
                                        accumulator.removedFile(bytes);
                                    }
                                }, accumulator::skip);
                    });
                    if (!acquired && standalone) {
                        Path relative = root.resolve("downloads").relativize(versionDirectory);
                        accumulator.skip(SKIPPED_IN_USE + relative.getName(0) + ':' + relative.getName(1)
                                + '/' + relative.getName(2) + '(' + relative.getName(3) + ')');
                    }
                } catch (IOException exception) {
                    accumulator.skip(markers.path().resolve(marker), exception);
                }
            }
        } catch (NoSuchFileException _) {
            // There is no marker directory.
        } catch (IOException | java.nio.file.DirectoryIteratorException exception) {
            accumulator.skip(root.resolve(ImageCacheMarkers.DIRECTORY), exception);
        }
    }

    /// Reads and validates one marker from a bound marker directory.
    private @Nullable Path readMarker(CacheTreeRemoval.Directory markers, Path name) throws IOException {
        @Nullable Path root = realCacheRoot;
        if (root == null) {
            return null;
        }
        try {
            if (!markers.isRegularFile(name)) {
                return null;
            }
            @Nullable String stored = ImageCacheMarkers.readStoredPath(markers, name);
            if (stored == null) {
                throw new IOException("Cache marker is too large");
            }
            @Nullable Path resolved = ImageCacheMarkers.resolvePartial(configuredCacheDirectory, root, stored);
            if (resolved == null) {
                return null;
            }
            resolved = root.resolve(configuredCacheDirectory.toAbsolutePath().normalize().relativize(resolved));
            return ImageCacheMarkers.identifiesMarker(name, root, resolved, stored) ? resolved : null;
        } catch (NoSuchFileException _) {
            return null;
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
        if (hasAmbiguousVersionAlias(entry, accumulator)) {
            return;
        }
        CacheTreeRemoval.VersionAction removeVersion = locked -> {
            // Remove the download version relative to the bound compatibility lock parent.
            locked.remove(Path.of(entry.version()), CacheTreeRemoval.Target.DIRECTORY,
                    accumulator::removedFile, accumulator::skip);
            removeVersionDirectory(versionDirectory(cacheDirectory.resolve("artifacts"), entry), accumulator);
            removeVersionDirectory(versionDirectory(cacheDirectory.resolve("imports"), entry), accumulator);
            removeLeftoversOf(entry, versionDirectory(cacheDirectory.resolve("artifacts"), entry), () -> index, accumulator);
        };
        @Nullable String possibleBase = legacyBase(entry.version());
        if (possibleBase == null) {
            withLock(entry, entry.version(), accumulator, removeVersion);
        } else {
            // The base is a strict prefix, so all operations acquire these locks in the same order.
            withLock(entry, possibleBase, accumulator, baseParent ->
                    withLock(entry, entry.version(), accumulator, versionParent -> {
                        baseParent.verifyIdentity();
                        versionParent.verifyIdentity();
                        removeVersion.run(versionParent);
                    }));
        }
    }

    /// Removes incomplete files of one cached image version while holding its lock.
    ///
    /// @param entry entry whose partial files are removed.
    /// @param index catalog snapshot used to locate the package for a fresh lookup.
    /// @param accumulator removal accumulator.
    private void removeEntryPartials(ImageCacheEntry entry, @Nullable CatalogIndex index, Accumulator accumulator) {
        if (!isSafeEntry(entry) || hasAmbiguousVersionAlias(entry, accumulator)) {
            return;
        }
        withLock(entry, entry.version(), accumulator, locked -> {
            var current = new LockedDeclarations(entry, index);
            removePartialsInside(locked, entry, current, accumulator);
            removeLeftoversOf(entry, versionDirectory(cacheDirectory.resolve("artifacts"), entry), current::get, accumulator);
            removeVersionDirectory(versionDirectory(cacheDirectory.resolve("imports"), entry), accumulator);
        });
    }

    /// Supplies declarations only when a filename requires a catalog decision.
    @NotNullByDefault
    @FunctionalInterface
    private interface Declarations {
        /// Returns the applicable snapshot, if a catalog is configured.
        @Nullable CatalogIndex get() throws IOException;
    }

    /// Lazily rechecks one package once while its version is locked.
    @NotNullByDefault
    private final class LockedDeclarations implements Declarations {
        /// Version being cleaned.
        private final ImageCacheEntry entry;
        /// Snapshot that identifies the package's catalog spelling.
        private final @Nullable CatalogIndex snapshot;
        /// Fresh declarations, populated on first use.
        private @Nullable CatalogIndex index;

        /// Binds the lookup to the selected package.
        private LockedDeclarations(ImageCacheEntry entry, @Nullable CatalogIndex snapshot) {
            this.entry = entry;
            this.snapshot = snapshot;
        }

        @Override
        public @Nullable CatalogIndex get() throws IOException {
            if (index == null && images != null) {
                try {
                    Path packageKey = Path.of(entry.repoId(), entry.category(), entry.name());
                    @Unmodifiable List<Path> packageKeys = snapshot == null ? List.of(packageKey)
                            : snapshot.packageKeys(packageKey, ImageCacheService.this);
                    List<ImageEntry> current = new ArrayList<>();
                    for (Path key : packageKeys) {
                        current.addAll(images.freshPackageDeclarations(key.getName(0).toString(),
                                key.getName(1).toString(), key.getName(2).toString()));
                    }
                    index = new CatalogIndex(List.copyOf(current));
                } catch (RuntimeException failure) {
                    throw new IOException("Cannot read current catalog declarations for " + entry.atom(), failure);
                }
            }
            return index;
        }
    }

    /// Returns whether a legacy-looking artifact has no evidence of being an independent version.
    private boolean isUnconfirmedLegacyVersion(ImageCacheEntry entry, @Nullable CatalogIndex index) {
        if (legacyBase(entry.version()) == null
                || !isRealDirectory(versionDirectory(cacheDirectory.resolve("artifacts"), entry))) {
            return false;
        }
        return (index == null || index.distfiles(versionDirectory(cacheDirectory.resolve("artifacts"), entry), this) == null)
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
            if (!CacheTreeRemoval.withVersionLock(root, root.relativize(lock), accumulator.expectedRoot, action)) {
                accumulator.skip(SKIPPED_IN_USE + entry.repoId() + ":" + entry.atom());
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
    /// The version directory is bound for both scanning and removal.
    ///
    /// @param parent bound lock parent directory.
    /// @param entry cache entry owning the directory.
    /// @param declarations catalog declarations used to identify protected names.
    /// @param accumulator removal accumulator.
    private void removePartialsInside(
            CacheTreeRemoval.Directory parent,
            ImageCacheEntry entry,
            Declarations declarations,
            Accumulator accumulator) {
        Path versionPath = parent.path().resolve(entry.version());
        try (CacheTreeRemoval.Directory version = parent.open(Path.of(entry.version()))) {
            MarkerAliases markerAliases = new MarkerAliases();
            for (Path name : version.names()) {
                if (!isDownloadStagingDirectory(name) && !isLegacyPartialName(name)) {
                    continue;
                }
                try {
                    BasicFileAttributes attributes = version.attributes(name);
                    if (attributes.isDirectory() && isDownloadStagingDirectory(name)) {
                        version.remove(name, CacheTreeRemoval.Target.DIRECTORY,
                                accumulator::removedFile, accumulator::skip);
                    } else if (attributes.isRegularFile() && isMarkedPartial(version, name, declarations, markerAliases)) {
                        version.remove(name, CacheTreeRemoval.Target.REGULAR_FILE,
                                accumulator::removedFile, accumulator::skip);
                    }
                } catch (NoSuchFileException _) {
                    // The entry is already gone.
                } catch (IOException exception) {
                    accumulator.skip(versionPath.resolve(name), exception);
                }
            }
        } catch (NoSuchFileException _) {
            // The download directory is already gone.
        } catch (IOException exception) {
            accumulator.skip(versionPath, exception);
        }
    }

    /// Removes leftover directories belonging to one artifact version directory.
    ///
    /// @param entry cache entry owning the leftovers.
    /// @param artifactVersion artifact version directory.
    /// @param declarations catalog declarations used to identify protected names.
    /// @param accumulator removal accumulator.
    private void removeLeftoversOf(
            ImageCacheEntry entry, Path artifactVersion, Declarations declarations, Accumulator accumulator) {
        @Nullable Path root = realCacheRoot;
        @Nullable Path nameDirectory = artifactVersion.getParent();
        if (root == null || nameDirectory == null) {
            return;
        }
        Path relative = cacheDirectory.toAbsolutePath().normalize().relativize(nameDirectory.toAbsolutePath().normalize());
        try (CacheTreeRemoval.Directory directory = CacheTreeRemoval.bindDirectory(root, relative, false, false, accumulator.expectedRoot)) {
            for (Path name : directory.names()) {
                @Nullable String owner = leftoverVersion(name, null);
                if (owner == null) {
                    continue;
                }
                Path path = nameDirectory.resolve(name);
                if (!entry.version().equals(owner)) {
                    Path alias = nameDirectory.resolve(leftoverAlias(name.toString(), owner, entry.version()));
                    if (!sameDirectory(path, alias)) {
                        continue;
                    }
                }
                try {
                    @Nullable CatalogIndex index = declarations.get();
                    if (directory.attributes(name).isDirectory()
                            && (index == null || index.distfiles(path, this) == null)) {
                        directory.remove(name, CacheTreeRemoval.Target.DIRECTORY,
                                accumulator::removedFile, accumulator::skip);
                    }
                } catch (NoSuchFileException _) {
                    // The staging directory has already moved.
                } catch (IOException exception) {
                    accumulator.skip(path, exception);
                }
            }
        } catch (NoSuchFileException _) {
            // There are no artifacts for this version.
        } catch (IOException exception) {
            accumulator.skip(nameDirectory, exception);
        }
    }

    /// Scans the download tree and merges entries into the given builders.
    ///
    /// @param builders accumulated entries.
    /// @param links hardlink groups encountered during the scan.
    /// @param unreadable paths that could not be read.
    /// @param index catalog index used to recognize downloads left by older versions.
    private void scanDownloads(
            Map<String, List<EntryBuilder>> builders,
            Map<Object, FileLinks> links,
            List<String> unreadable,
            @Nullable CatalogIndex index, boolean bulk, boolean partialsOnly, @Nullable Map<String, Path> markerOwners,
            List<ImageCacheUsage.LegacyResumeCandidate> candidates) {
        Path root = cacheDirectory.resolve("downloads");
        MarkerAliases markerAliases = new MarkerAliases();
        for (Path repo : listDirectories(root, unreadable)) {
            for (Path category : listDirectories(repo, unreadable)) {
                for (Path name : listDirectories(category, unreadable)) {
                    for (Path child : listChildren(name, unreadable)) {
                        if (!isDirectory(child, unreadable)) {
                            continue;
                        }
                        @Nullable EntryBuilder builder = builder(builders, child, child);
                        if (builder == null) {
                            continue;
                        }
                        Set<Path> stagingDirectories = new LinkedHashSet<>();
                        List<Path> children = listChildren(child, unreadable);
                        for (Path staging : children) {
                            if (isDownloadStagingDirectory(staging) && isDirectory(staging, unreadable)) {
                                stagingDirectories.add(staging.getFileName());
                                boolean resumeSlot = RuyiDistfileDownloader.isDownloadPartialDirectoryName(staging.getFileName().toString());
                                if (!resumeSlot) {
                                    builder.partialFiles++;
                                }
                                if (bulk || partialsOnly || !resumeSlot) {
                                    builder.present = true;
                                    builder.touch(staging);
                                }
                            }
                        }
                        @Nullable Map<String, RuyiDistfile> declared = index == null
                                ? null
                                : index.distfiles(child, this);
                        if (!partialsOnly) {
                            builder.cachedDistfiles = countCachedArchives(child, declared, unreadable);
                        }
                        List<Path> files = new ArrayList<>();
                        if (partialsOnly) {
                            for (Path direct : children) {
                                if (stagingDirectories.contains(direct.getFileName())) {
                                    files.addAll(listFiles(direct, unreadable));
                                } else if (isLegacyPartialName(direct)) {
                                    files.add(direct);
                                }
                            }
                        } else {
                            files.addAll(listFiles(child, unreadable));
                        }
                        Map<Path, BasicFileAttributes> downloads = new LinkedHashMap<>();
                        Map<Path, BasicFileAttributes> resumeFiles = new LinkedHashMap<>();
                        for (Path file : files) {
                            @Nullable BasicFileAttributes attributes = readAttributes(file, unreadable);
                            if (attributes == null) {
                                continue;
                            }
                            Path relative = child.relativize(file);
                            boolean staged = relative.getNameCount() > 1 && stagingDirectories.contains(relative.getName(0));
                            boolean marked = relative.getNameCount() == 1 && isIncompleteFile(
                                    file, attributes, declared, unreadable, markerOwners, builder, markerAliases, candidates);
                            if (staged || marked) {
                                accountFile(builder, FileKind.PARTIAL, file, attributes, links, unreadable);
                                if (marked) {
                                    builder.partialFiles++;
                                } else if (attributes.isRegularFile() && RuyiDistfileDownloader
                                        .isDownloadPartialDirectoryName(relative.getName(0).toString())) {
                                    if (relative.getNameCount() == 2 && file.getFileName().toString().endsWith(ARCHIVE_PART_SUFFIX)) {
                                        downloads.put(file, attributes);
                                    } else if (relative.getNameCount() == 3 && file.getFileName().toString().equals("resume")
                                            && RuyiDistfileDownloader.isDownloadPartialDirectoryName(relative.getName(1).toString())) {
                                        resumeFiles.put(file, attributes);
                                    }
                                }
                            } else if (!partialsOnly) {
                                accountFile(builder, FileKind.DOWNLOAD, file, attributes, links, unreadable);
                            } else {
                                continue;
                            }
                            builder.present = true;
                            builder.touch(attributes);
                        }
                        builder.partialFiles += downloads.size();
                        if (!resumeFiles.isEmpty()) {
                            Set<Object> identities = new LinkedHashSet<>();
                            downloads.forEach((file, attributes) -> {
                                @Nullable Object key = CacheTreeRemoval.fileIdentity(file, attributes);
                                if (key != null) {
                                    identities.add(key);
                                }
                            });
                            resumeFiles.forEach((file, attributes) -> {
                                @Nullable Object key = CacheTreeRemoval.fileIdentity(file, attributes);
                                if (key == null || identities.add(key)) {
                                    builder.partialFiles++;
                                }
                            });
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
    /// @param links hardlink groups encountered during the scan.
    /// @param unreadable paths that could not be read.
    /// @param index catalog index used to recognize leftovers and declared versions.
    private void scanArtifacts(
            Map<String, List<EntryBuilder>> builders,
            Map<Object, FileLinks> links,
            List<String> unreadable,
            @Nullable CatalogIndex index, boolean partialsOnly) {
        Path root = cacheDirectory.resolve("artifacts");
        for (Path repo : listDirectories(root, unreadable)) {
            for (Path category : listDirectories(repo, unreadable)) {
                for (Path name : listDirectories(category, unreadable)) {
                    for (Path child : listChildren(name, unreadable)) {
                        if (partialsOnly && leftoverVersion(child, null) == null) {
                            continue;
                        }
                        if (!isDirectory(child, unreadable)) {
                            continue;
                        }
                        @Nullable String leftoverVersion = index != null && index.distfiles(child, this) != null
                                ? null : leftoverVersion(child, null);
                        if (leftoverVersion != null) {
                            if (!isSafeComponent(leftoverVersion)) {
                                continue;
                            }
                            @Nullable EntryBuilder leftoverBuilder =
                                    builder(builders, name.resolve(leftoverVersion), child);
                            if (leftoverBuilder != null) {
                                for (Path file : listFiles(child, unreadable)) {
                                    @Nullable BasicFileAttributes attributes = readAttributes(file, unreadable);
                                    if (attributes != null) {
                                        accountFile(leftoverBuilder, FileKind.PARTIAL, file, attributes, links, unreadable);
                                        leftoverBuilder.touch(attributes);
                                    }
                                }
                                leftoverBuilder.partialFiles++;
                                leftoverBuilder.present = true;
                                leftoverBuilder.touch(child);
                            }
                            continue;
                        }
                        if (partialsOnly) {
                            continue;
                        }
                        @Nullable EntryBuilder builder = builder(builders, child, child);
                        if (builder == null) {
                            continue;
                        }
                        @Unmodifiable List<Path> files = listFiles(child, unreadable);
                        if (!files.isEmpty()) {
                            builder.present = true;
                            for (Path file : files) {
                                @Nullable BasicFileAttributes attributes = readAttributes(file, unreadable);
                                if (attributes != null) {
                                    accountFile(builder, FileKind.ARTIFACT, file, attributes, links, unreadable);
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
    /// @param links hardlink groups encountered during the scan.
    /// @param unreadable paths that could not be read.
    private void scanImports(Map<String, List<EntryBuilder>> builders, Map<Object, FileLinks> links, List<String> unreadable) {
        Path root = cacheDirectory.resolve("imports");
        for (Path repo : listDirectories(root, unreadable)) {
            for (Path category : listDirectories(repo, unreadable)) {
                for (Path name : listDirectories(category, unreadable)) {
                    for (Path child : listChildren(name, unreadable)) {
                        if (!isDirectory(child, unreadable)) {
                            continue;
                        }
                        @Nullable EntryBuilder builder = builder(builders, child, child);
                        if (builder == null) {
                            continue;
                        }
                        for (Path file : listFiles(child, unreadable)) {
                            @Nullable BasicFileAttributes attributes = readAttributes(file, unreadable);
                            if (attributes != null) {
                                builder.present = true;
                                accountFile(builder, FileKind.PARTIAL, file, attributes, links, unreadable);
                                builder.partialFiles++;
                                builder.touch(attributes);
                            }
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

    /// Substitutes the owning version within a staging or backup directory name.
    private static String leftoverAlias(String name, String owner, String replacement) {
        int start = name.startsWith(RuyiImageMaterializer.STAGING_PREFIX)
                ? RuyiImageMaterializer.STAGING_PREFIX.length() : RuyiImageMaterializer.BACKUP_PREFIX.length();
        return name.substring(0, start) + replacement + name.substring(start + owner.length());
    }

    /// Returns whether a real download child directory is reserved for staging.
    public static boolean isDownloadStagingDirectory(Path directory) {
        String name = directory.getFileName().toString();
        if (name.startsWith(RuyiImageCatalogService.IMPORT_STAGING_DIRECTORY_PREFIX)) {
            return true;
        }
        return RuyiDistfileDownloader.isDownloadPartialDirectoryName(name);
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
                @Nullable BasicFileAttributes attributes = readAttributes(directory.resolve(archive.name()), unreadable, true);
                @Nullable Long size = archive.sizeBytes();
                if (attributes != null && attributes.isRegularFile() && (size == null || attributes.size() == size)) {
                    count++;
                }
            }
        }
        return count;
    }

    /// Recognizes an incomplete direct download only when its published marker identifies it.
    private boolean isIncompleteFile(Path file, BasicFileAttributes attributes,
            @Nullable Map<String, RuyiDistfile> declared, List<String> unreadable,
            @Nullable Map<String, Path> markerOwners, EntryBuilder owner, MarkerAliases markerAliases,
            List<ImageCacheUsage.LegacyResumeCandidate> candidates) {
        if (!isLegacyPartialName(file) || !attributes.isRegularFile()) {
            return false;
        }
        try {
            if (isDeclaredArchive(file, declared)) {
                return false;
            }
            int errors = unreadable.size();
            boolean marked = hasPartialMarker(file, marker -> {
                String path = marker.toString();
                unreadable.add(path);
                if (markerOwners != null) {
                    markerOwners.put(path, owner.origin);
                }
            }, markerAliases);
            if (!marked && errors == unreadable.size() && declared != null
                    && isLegacyResumeCandidate(file, declared)) {
                String atom = owner.category + '/' + owner.name + '(' + owner.version + ')';
                candidates.add(new ImageCacheUsage.LegacyResumeCandidate(file.toString(), owner.repoId, atom));
            }
            return marked;
        } catch (IOException exception) {
            unreadable.add(file.toString());
            return false;
        }
    }

    /// Matches the legacy sibling that the downloader would consider for resuming this archive.
    private boolean isLegacyResumeCandidate(Path file, Map<String, RuyiDistfile> declared) throws IOException {
        String key = aliasKey(file.getFileName().toString());
        for (RuyiDistfile archive : declared.values()) {
            if (!archive.fetchRestricted() && DistfileIntegrity.hasSupportedChecksum(archive)
                    && aliasKey(archive.name() + ARCHIVE_PART_SUFFIX).equals(key)) {
                @Nullable Path legacy = RuyiDistfileDownloader.legacyPartialPath(
                        archive, file.getParent(), List.copyOf(declared.values()));
                if (legacy != null && samePartialEntry(file, legacy)) {
                    return true;
                }
            }
        }
        return false;
    }

    /// Rebases a source in a physical cache tree to that tree's standard spelling.
    ///
    /// @param physicalRoot known real cache root.
    /// @param source real path of the source.
    /// @return source under the standard downloads, artifacts, or imports tree, or null when outside.
    /// @throws IOException when a possible alias cannot be verified.
    public static @Nullable Path resolveCacheTreeSource(Path physicalRoot, Path source) throws IOException {
        int depth = physicalRoot.getNameCount();
        @Nullable Path sourceRoot = source.getRoot();
        if (source.getFileSystem() != physicalRoot.getFileSystem() || sourceRoot == null || source.getNameCount() <= depth + 1
                || !sourceRoot.equals(physicalRoot.getRoot())) {
            return null;
        }
        Path observedRoot = depth == 0 ? sourceRoot : sourceRoot.resolve(source.subpath(0, depth));
        if (!observedRoot.toString().equals(physicalRoot.toString())
                && !isSamePhysicalDirectory(observedRoot, physicalRoot)) {
            return null;
        }
        Path observedTree = observedRoot.resolve(source.getName(depth));
        for (String tree : List.of("downloads", "artifacts", "imports")) {
            Path canonical = physicalRoot.resolve(tree);
            if (tree.equals(source.getName(depth).toString()) || isSamePhysicalDirectory(observedTree, canonical)) {
                return canonical.resolve(source.subpath(depth + 1, source.getNameCount()));
            }
        }
        return null;
    }

    /// Checks a local flash source for a historical partial while the caller holds its version lock.
    ///
    /// @param configuredRoot configured cache path, used by historical marker keys.
    /// @param physicalRoot physical cache root protecting the source.
    /// @param file physical source path.
    /// @param catalog current image declarations.
    /// @return whether the marked source is an unfinished download.
    public static boolean isMarkedDownloadPartial(Path configuredRoot, Path physicalRoot, Path file,
            ImageCatalogService catalog) throws IOException {
        Path downloads = physicalRoot.resolve("downloads");
        if (!file.startsWith(downloads) || downloads.relativize(file).getNameCount() != 5 || !isLegacyPartialName(file)) {
            return false;
        }
        var service = new ImageCacheService(configuredRoot, catalog, physicalRoot);
        if (Files.notExists(physicalRoot.resolve(ImageCacheMarkers.DIRECTORY), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        @Nullable IOException markerFailure = null;
        try {
            if (!service.hasPartialMarker(file, null, service.new MarkerAliases())) {
                return false;
            }
        } catch (IOException exception) {
            markerFailure = exception;
        }
        @Nullable CatalogIndex index = service.catalogIndex();
        @Nullable RuyiDistfile archive = index == null ? null : declaredArchive(file, index.distfiles(file.getParent(), service));
        if (archive != null && DistfileIntegrity.hasSupportedChecksum(archive)
                && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && DistfileIntegrity.verify(file, archive)) {
            return false;
        }
        if (markerFailure != null) {
            throw markerFailure;
        }
        return true;
    }

    /// Checks archive declarations and marker contents against the bound download and marker directories.
    private boolean isMarkedPartial(CacheTreeRemoval.Directory directory, Path name, Declarations declarations,
            MarkerAliases markerAliases)
            throws IOException {
        Path file = directory.path().resolve(name);
        if (!isLegacyPartialName(name) || !hasPartialMarker(file, null, markerAliases)) {
            return false;
        }
        @Nullable CatalogIndex index = declarations.get();
        directory.verifyIdentity();
        @Nullable Map<String, RuyiDistfile> declared = index == null ? null : index.distfiles(directory.path(), this);
        boolean declaredArchive = isDeclaredArchive(file, declared);
        directory.verifyIdentity();
        return !declaredArchive;
    }

    /// Checks whether the archive declaration addresses the same directory entry as the candidate.
    private static boolean isDeclaredArchive(Path file, @Nullable Map<String, RuyiDistfile> declared)
            throws IOException {
        return declaredArchive(file, declared) != null;
    }

    /// Finds the declaration that addresses the actual archive directory entry.
    private static @Nullable RuyiDistfile declaredArchive(Path file, @Nullable Map<String, RuyiDistfile> declared)
            throws IOException {
        if (declared == null) {
            return null;
        }
        @Nullable RuyiDistfile exact = declared.get(file.getFileName().toString());
        if (exact != null) {
            return exact;
        }
        for (String name : declared.keySet()) {
            Path declaredPath = file.resolveSibling(name);
            try {
                if (!Files.readAttributes(declaredPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                        .isRegularFile() || !Files.isSameFile(file, declaredPath)) {
                    continue;
                }
                // Distinct hardlink names appear as distinct directory entries.
                try (var entries = Files.newDirectoryStream(file.getParent())) {
                    boolean distinctName = false;
                    for (Path entry : entries) {
                        if (entry.getFileName().toString().equals(name)) {
                            distinctName = true;
                            break;
                        }
                    }
                    if (!distinctName) {
                        return declared.get(name);
                    }
                } catch (DirectoryIteratorException exception) {
                    throw exception.getCause();
                }
            } catch (NoSuchFileException _) {
                // The archive is not cached.
            }
        }
        return null;
    }

    /// Looks up current and historical marker names under the captured physical cache root.
    private boolean hasPartialMarker(Path physicalPartial, @Nullable Consumer<Path> reportUnreadable,
            MarkerAliases markerAliases) throws IOException {
        @Nullable Path root = realCacheRoot;
        if (root == null) {
            return false;
        }
        Path relative = root.relativize(physicalPartial);
        try (CacheTreeRemoval.Directory markers = CacheTreeRemoval.bindDirectory(root,
                Path.of(ImageCacheMarkers.DIRECTORY), false, false)) {
            Path configuredPartial = configuredCacheDirectory.toAbsolutePath().normalize().resolve(relative);
            Path markerDirectory = markers.path();
            List<Path> names = new ArrayList<>();
            names.add(ImageCacheMarkers.markerPath(markerDirectory, relative).getFileName());
            names.add(ImageCacheMarkers.legacyMarkerPath(markerDirectory, physicalPartial).getFileName());
            if (!configuredPartial.equals(physicalPartial)) {
                names.add(ImageCacheMarkers.legacyMarkerPath(markerDirectory, configuredPartial).getFileName());
            }
            boolean matched = false;
            boolean failed = false;
            for (Path name : names) {
                try {
                    matched |= samePartialEntry(physicalPartial, readMarker(markers, name));
                } catch (IOException exception) {
                    if (reportUnreadable == null) {
                        throw exception;
                    }
                    reportUnreadable.accept(markerDirectory.resolve(name));
                    failed = true;
                }
            }
            if (failed || matched) {
                return matched && !failed;
            }
            for (Path name : markerAliases.candidates(markers, physicalPartial)) {
                if (names.contains(name)) {
                    continue;
                }
                try {
                    if (samePartialEntry(physicalPartial, readMarker(markers, name))) {
                        return true;
                    }
                } catch (IOException _) {
                    // An unverifiable alternate key cannot be attributed to this partial.
                }
            }
            return false;
        } catch (NoSuchFileException _) {
            return false;
        }
    }

    /// Confirms that a marker names this file entry rather than another hardlink to it.
    private boolean samePartialEntry(Path partial, @Nullable Path marked) throws IOException {
        if (marked == null) {
            return false;
        }
        if (partial.toString().equals(marked.toString())) {
            return true;
        }
        try {
            if (!Files.readAttributes(marked, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()
                    || !sameDirectory(partial.getParent(), marked.getParent()) || !Files.isSameFile(partial, marked)) {
                return false;
            }
            String actualName = partial.getFileName().toString();
            String recordedName = marked.getFileName().toString();
            if (actualName.equals(recordedName)) {
                return true;
            }
            String key = aliasKey(actualName);
            boolean found = false;
            try (var entries = Files.newDirectoryStream(partial.getParent())) {
                for (Path entry : entries) {
                    if (aliasKey(entry.getFileName().toString()).equals(key)
                            && Files.isSameFile(partial, entry) && Files.isSameFile(marked, entry)) {
                        if (found) {
                            return false;
                        }
                        found = true;
                    }
                }
            } catch (DirectoryIteratorException exception) {
                throw exception.getCause();
            }
            return found;
        } catch (NoSuchFileException _) {
            return false;
        }
    }

    /// Lazily indexes marker keys by possible spelling aliases during one scan.
    @NotNullByDefault
    private final class MarkerAliases {
        /// Marker names grouped by their contents after Unicode normalization and case folding.
        private @Nullable Map<String, List<Path>> bySpelling;

        /// Finds candidate aliases, which must be revalidated against the file entry before use.
        private List<Path> candidates(CacheTreeRemoval.Directory markers, Path partial) throws IOException {
            @Nullable Map<String, List<Path>> index = bySpelling;
            if (index == null) {
                index = new LinkedHashMap<>();
                for (Path name : markers.names()) {
                    if (!ImageCacheMarkers.isMarkerName(name)) {
                        continue;
                    }
                    try {
                        @Nullable Path marked = readMarker(markers, name);
                        if (marked != null) {
                            index.computeIfAbsent(aliasKey(marked.toString()), _ -> new ArrayList<>()).add(name);
                        }
                    } catch (IOException _) {
                        // Lookup by the known direct keys reports their errors separately.
                    }
                }
                bySpelling = index;
            }
            return index.getOrDefault(aliasKey(partial.toString()), List.of());
        }
    }

    /// Returns whether a filename was historically used for a download or import partial.
    private static boolean isLegacyPartialName(Path file) {
        String name = file.getFileName().toString();
        return (name.startsWith(IMPORT_PREFIX) && name.endsWith(IMPORT_SUFFIX))
                || name.regionMatches(true, name.length() - ARCHIVE_PART_SUFFIX.length(),
                        ARCHIVE_PART_SUFFIX, 0, ARCHIVE_PART_SUFFIX.length());
    }

    /// Returns the builder for the same version in any of the cache trees.
    private @Nullable EntryBuilder builder(Map<String, List<EntryBuilder>> builders, Path path, Path evidence) {
        Path relative = relativeVersion(path);
        for (Path component : relative) {
            if (!isSafeComponent(component.toString())) {
                return null;
            }
        }
        String key = versionKey(relative);
        List<EntryBuilder> candidates = builders.computeIfAbsent(aliasKey(key), _ -> new ArrayList<>());
        for (EntryBuilder candidate : candidates) {
            Path origin = candidate.origin;
            Path candidateRelative = relativeVersion(origin);
            if (key.equals(versionKey(candidateRelative))
                    || sameDirectory(candidate.evidence, projectVersion(relative, origin, candidate.evidence))
                    && sameDirectory(evidence, projectVersion(candidateRelative, path, evidence))) {
                return candidate;
            }
        }
        EntryBuilder created = new EntryBuilder(relative.getName(0).toString(), relative.getName(1).toString(),
                relative.getName(2).toString(), relative.getName(3).toString(), path, evidence);
        candidates.add(created);
        return created;
    }

    /// Projects an alternate version's spelling onto the observed directory layout.
    private Path projectVersion(Path relative, Path version, Path evidence) {
        Path path = treeRoot(version).resolve(relative);
        if (evidence.toString().equals(version.toString())) {
            return path;
        }
        return path.resolveSibling(leftoverAlias(evidence.getFileName().toString(),
                version.getFileName().toString(), relative.getFileName().toString()));
    }

    /// Locates a cached version relative to its download, artifact, or import tree.
    private Path relativeVersion(Path path) {
        return treeRoot(path).relativize(path);
    }

    /// Returns the enclosing cache tree for a version directory.
    private Path treeRoot(Path path) {
        return path.getParent().getParent().getParent().getParent();
    }

    /// Groups possible aliases for lookup before confirming them against the filesystem.
    private static String aliasKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .codePoints()
                .map(Character::toUpperCase)
                .map(Character::toLowerCase)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /// Checks directory aliases against the filesystem even if the provider equates their names.
    private boolean sameDirectory(Path first, Path second) {
        try {
            return isSamePhysicalDirectory(first, second);
        } catch (IOException _) {
            return false;
        }
    }

    /// Tests directory identity while distinguishing separately spelled physical paths.
    private static boolean isSamePhysicalDirectory(Path first, Path second) throws IOException {
        try {
            BasicFileAttributes left = Files.readAttributes(first, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes right = Files.readAttributes(second, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!left.isDirectory() || !right.isDirectory()) {
                return false;
            }
            if (left.fileKey() != null && right.fileKey() != null) {
                return left.fileKey().equals(right.fileKey());
            }
            // Windows Path.equals can equate distinct names in a case-sensitive directory.
            return Files.isSameFile(first, second)
                    && first.toRealPath().toString().equals(second.toRealPath().toString());
        } catch (NoSuchFileException _) {
            return false;
        }
    }

    /// Keeps a version when the same spelling resolves differently across the cache trees.
    private boolean hasAmbiguousVersionAlias(ImageCacheEntry entry, Accumulator accumulator) {
        List<Path> roots = List.of(cacheDirectory.resolve("downloads"), cacheDirectory.resolve("artifacts"),
                cacheDirectory.resolve("imports"));
        Path relative = cacheDirectory.getFileSystem().getPath(entry.repoId(), entry.category(), entry.name(), entry.version());
        for (Path root : roots) {
            Path path = root.resolve(relative);
            try {
                Path resolved = path.toRealPath();
                if (!resolved.startsWith(root)) {
                    continue;
                }
                Path canonical = root.relativize(resolved);
                if (versionKey(relative).equals(versionKey(canonical))
                        || !aliasKey(versionKey(relative)).equals(aliasKey(versionKey(canonical)))) {
                    continue;
                }
                for (Path otherRoot : roots) {
                    Path other = otherRoot.resolve(relative);
                    if (!otherRoot.equals(root) && Files.isDirectory(other, LinkOption.NOFOLLOW_LINKS)
                            && !sameDirectory(other, otherRoot.resolve(canonical))) {
                        accumulator.skip("Ambiguous cache version spelling: " + path);
                        return true;
                    }
                }
            } catch (NoSuchFileException _) {
                // This tree has no entry for the selected version.
            } catch (IOException exception) {
                accumulator.skip(path, exception);
                return true;
            }
        }
        return false;
    }

    /// Returns whether a cache entry only contains safe path components.
    ///
    /// @param entry cache entry.
    /// @return whether the entry can be resolved below the cache root.
    private boolean isSafeEntry(ImageCacheEntry entry) {
        return List.of(entry.repoId(), entry.category(), entry.name(), entry.version()).stream()
                .allMatch(this::isSafeComponent);
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
            if (!path.toRealPath().equals(realRoot.resolve(configuredRoot.relativize(normalized)).normalize())) {
                return false;
            }
            CacheTreeRemoval.checkMountBoundary(realRoot, normalized);
            return true;
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
    private boolean isSafeComponent(String value) {
        return isSafeComponent(cacheDirectory, value);
    }

    /// Checks a single cache name using the cache root's file system.
    ///
    /// @param root cache location whose file system parses the name.
    /// @param value candidate name.
    /// @return whether the name is a single relative component other than dot or dot-dot.
    public static boolean isSafeComponent(Path root, String value) {
        if (value.isEmpty() || value.equals(".") || value.equals("..")) {
            return false;
        }
        try {
            Path path = root.getFileSystem().getPath(value);
            return path.getRoot() == null && path.getNameCount() == 1 && path.getFileName().toString().equals(value);
        } catch (InvalidPathException _) {
            return false;
        }
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
        ArrayList<Path> children = new ArrayList<>();
        try {
            if (!isDirectory(directory, unreadable)) {
                return List.of();
            }
            try (var stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    children.add(child);
                }
            }
        } catch (NoSuchFileException _) {
            // The directory disappeared.
        } catch (IOException | DirectoryIteratorException _) {
            unreadable.add(directory.toString());
        }
        children.sort(Comparator.naturalOrder());
        return List.copyOf(children);
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

                /// Records iteration failures and continues visiting other directories.
                @Override
                public FileVisitResult postVisitDirectory(Path directory, @Nullable IOException exception) {
                    if (exception != null) {
                        unreadable.add(directory.toString());
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
        return readAttributes(file, unreadable, false);
    }

    /// Reads attributes, optionally following the final archive link for completeness checks.
    private static @Nullable BasicFileAttributes readAttributes(Path file, List<String> unreadable, boolean follow) {
        try {
            return follow ? Files.readAttributes(file, BasicFileAttributes.class)
                    : Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
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

    /// Removes only a version directory, keeping files that collide with the version name.
    private void removeVersionDirectory(Path path, Accumulator accumulator) {
        @Nullable Path root = realCacheRoot;
        if (root != null) {
            CacheTreeRemoval.remove(root, root.relativize(path), CacheTreeRemoval.Target.DIRECTORY, accumulator.expectedRoot,
                    accumulator::removedFile, accumulator::skip);
        }
    }

    /// Names declared by the catalog, used to recognize cache files written by older releases.
    @NotNullByDefault
    private static final class CatalogIndex {
        /// Declared versions keyed by `repo/category/name`.
        private final Map<String, Set<String>> versions;

        /// Identifies one version independently of how its ATOM is displayed.
        @NotNullByDefault
        private record VersionKey(String repoId, String category, String name, String version) {
            /// Returns the relative version path using the index's component separator.
            private String path() {
                return repoId + '/' + category + '/' + name + '/' + version;
            }
        }

        /// Declared distfiles keyed by repository, category, package and version.
        private final Map<VersionKey, Map<String, RuyiDistfile>> distfiles;

        /// Possible alias spellings of declared packages.
        private final Map<String, List<String>> packageAliases = new LinkedHashMap<>();

        /// Possible alias spellings of declared versions.
        private final Map<String, List<VersionKey>> versionAliases = new LinkedHashMap<>();

        /// Builds the index from one catalog snapshot.
        ///
        /// The index includes declarations from repositories hidden by catalog priority.
        ///
        /// @param images every declared image, without cross-repository de-duplication.
        private CatalogIndex(@Unmodifiable List<ImageEntry> images) {
            Map<String, Set<String>> versionMap = new LinkedHashMap<>();
            Map<VersionKey, Map<String, RuyiDistfile>> distfileMap = new LinkedHashMap<>();
            for (ImageEntry image : images) {
                versionMap.computeIfAbsent(
                                image.repoId() + "/" + image.category() + "/" + image.name(),
                                _ -> new LinkedHashSet<>())
                        .add(image.version());
                Map<String, RuyiDistfile> files = distfileMap.computeIfAbsent(
                        new VersionKey(image.repoId(), image.category(), image.name(), image.version()),
                        _ -> new LinkedHashMap<>());
                for (RuyiDistfile distfile : image.distfiles()) {
                    files.put(distfile.name(), distfile);
                }
            }
            this.versions = versionMap;
            this.distfiles = distfileMap;
            for (String key : versionMap.keySet()) {
                packageAliases.computeIfAbsent(aliasKey(key), _ -> new ArrayList<>()).add(key);
            }
            for (VersionKey key : distfileMap.keySet()) {
                versionAliases.computeIfAbsent(aliasKey(key.path()), _ -> new ArrayList<>()).add(key);
            }
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

        /// Finds catalog package spellings for a fresh read after acquiring the version lock.
        private @Unmodifiable List<Path> packageKeys(Path relative, ImageCacheService service) {
            String actual = versionKey(relative);
            List<Path> keys = new ArrayList<>();
            for (String key : packageAliases.getOrDefault(aliasKey(actual), List.of())) {
                Path candidate = relative.getFileSystem().getPath(key);
                if (key.equals(actual)) {
                    keys.add(candidate);
                    continue;
                }
                for (String tree : List.of("downloads", "artifacts", "imports")) {
                    Path root = service.cacheDirectory.resolve(tree);
                    if (service.sameDirectory(root.resolve(relative), root.resolve(candidate))) {
                        keys.add(candidate);
                        break;
                    }
                }
            }
            return keys.isEmpty() ? List.of(relative) : List.copyOf(keys);
        }

        /// Returns exact and filesystem-equivalent declarations for the observed version path.
        private @Nullable Map<String, RuyiDistfile> distfiles(Path directory, ImageCacheService service) {
            Path relative = service.relativeVersion(directory);
            VersionKey exact = new VersionKey(relative.getName(0).toString(), relative.getName(1).toString(),
                    relative.getName(2).toString(), relative.getName(3).toString());
            @Nullable Map<String, RuyiDistfile> declared = distfiles.get(exact);
            @Nullable Map<String, RuyiDistfile> aliases = null;
            for (VersionKey key : versionAliases.getOrDefault(aliasKey(exact.path()), List.of())) {
                if (!exact.equals(key) && service.sameDirectory(directory, service.treeRoot(directory).resolve(key.path()))) {
                    if (aliases == null) {
                        aliases = new LinkedHashMap<>();
                    }
                    aliases.putAll(distfiles.get(key));
                }
            }
            if (aliases == null) {
                return declared;
            }
            if (declared != null) {
                aliases.putAll(declared);
            }
            return aliases;
        }

    }

    /// Mutable accumulator for one cleanup operation.
    @NotNullByDefault
    private static final class Accumulator {
        /// Snapshot root identity which every destructive binding must match.
        private final Object expectedRoot;

        /// Creates an accumulator bound to a selection root.
        private Accumulator(Object expectedRoot) {
            this.expectedRoot = expectedRoot;
        }

        /// Removed bytes.
        private long removedBytes;

        /// Removed files.
        private int removedFiles;

        /// Cleanup errors and skips that are associated with an entry or the whole operation.
        private final Set<String> skipped = new LinkedHashSet<>();

        /// Failed paths with the detailed cleanup reason when one is available.
        private final Map<String, @Nullable String> pathFailures = new LinkedHashMap<>();

        /// Entries retained by policy.
        private final List<ImageCacheRetainedEntry> retained = new ArrayList<>();

        /// Records one deleted name and the data released by unlinking it.
        private void removedFile(long size) {
            removedFiles++;
            removedBytes += size;
        }

        /// Records one skipped path.
        ///
        /// @param reason human-readable reason.
        private void skip(String reason) {
            skipped.add(reason);
        }

        /// Records a detailed failure for one filesystem path.
        private void skip(Path path, Throwable failure) {
            @Nullable String message = failure.getMessage();
            String reason = message == null ? failure.getClass().getSimpleName() : message;
            pathFailures.putIfAbsent(path.toString(), reason);
        }

        /// Builds the result, keeping a detailed cleanup failure over a bare scan path.
        private ImageCacheCleanResult build(@Unmodifiable List<String> unreadable) {
            return build(unreadable, List.of());
        }

        /// Includes ambiguous resume candidates observed during partial-only cleanup.
        private ImageCacheCleanResult build(@Unmodifiable List<String> unreadable,
                @Unmodifiable List<ImageCacheUsage.LegacyResumeCandidate> candidates) {
            for (String path : unreadable) {
                pathFailures.putIfAbsent(path, null);
            }
            ArrayList<String> diagnostics = new ArrayList<>(skipped);
            pathFailures.forEach((path, reason) -> diagnostics.add(reason == null ? path : path + ": " + reason));
            return new ImageCacheCleanResult(removedBytes, removedFiles, List.copyOf(diagnostics), List.copyOf(retained), candidates);
        }
    }

    /// Records bytes for one name or tracks it until every hardlink is accounted for.
    private static void accountFile(EntryBuilder owner, FileKind kind, Path path, BasicFileAttributes attributes,
            Map<Object, FileLinks> groups, List<String> unreadable) {
        if (attributes.isDirectory()) {
            return;
        }
        if (!attributes.isRegularFile()) {
            owner.addBytes(kind, attributes.size());
            return;
        }
        try {
            long links = CacheTreeRemoval.linkCount(path);
            if (links == 1L) {
                owner.addBytes(kind, attributes.size());
            } else if (links > 1L) {
                @Nullable Object identity = CacheTreeRemoval.fileIdentity(path, attributes);
                if (identity == null) {
                    unreadable.add(path.toString());
                } else {
                    groups.computeIfAbsent(identity, _ -> new FileLinks(attributes.size(), links, kind))
                            .add(owner, kind, attributes.size(), links);
                }
            } else {
                unreadable.add(path.toString());
            }
        } catch (NoSuchFileException _) {
            // The file disappeared during the scan.
        } catch (IOException _) {
            unreadable.add(path.toString());
        }
    }

    /// Chooses an ordinary file category before partials when names span categories.
    @NotNullByDefault
    private enum FileKind {
        /// Published archive.
        DOWNLOAD,
        /// Materialized image.
        ARTIFACT,
        /// Incomplete data.
        PARTIAL
    }

    /// Data released only if all owning entries are selected.
    /// @param owners entries containing links to this file.
    /// @param size file size in bytes.
    @NotNullByDefault
    private record SharedRelease(@Unmodifiable Set<ImageCacheEntry> owners, long size) {
    }

    /// Names and total links for one file.
    @NotNullByDefault
    private static final class FileLinks {
        /// Size when the first link was observed.
        private final long size;
        /// Total link count at the first observation.
        private final long links;
        /// Category to which the group's bytes are attributed.
        private FileKind kind;
        /// Versions contributing links to the measured scope.
        private final Set<EntryBuilder> owners = new LinkedHashSet<>();
        /// Number of names seen in this scope.
        private long names;
        /// Whether link count or size changed during the scan.
        private boolean changed;

        /// Captures the first observation of a multiply linked file.
        private FileLinks(long size, long links, FileKind kind) {
            this.size = size;
            this.links = links;
            this.kind = kind;
        }

        /// Includes another observed name in the group.
        private void add(EntryBuilder owner, FileKind kind, long size, long links) {
            owners.add(owner);
            names++;
            changed |= this.size != size || this.links != links;
            if (kind.compareTo(this.kind) < 0) {
                this.kind = kind;
            }
        }

        /// Returns whether the measured names account for every link.
        private boolean complete() {
            return !changed && names == links;
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

        /// Path used to resolve filesystem-equivalent declarations.
        private final Path origin;

        /// Existing directory establishing the version's spelling, possibly a leftover.
        private final Path evidence;

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
        /// @param origin path that first supplied the version.
        /// @param evidence existing directory supplying the spelling.
        private EntryBuilder(String repoId, String category, String name, String version, Path origin, Path evidence) {
            this.repoId = repoId;
            this.category = category;
            this.name = name;
            this.version = version;
            this.origin = origin;
            this.evidence = evidence;
        }

        /// Adds data released by deleting this version.
        private void addBytes(FileKind kind, long size) {
            switch (kind) {
                case DOWNLOAD -> downloadBytes += size;
                case ARTIFACT -> artifactBytes += size;
                case PARTIAL -> partialBytes += size;
            }
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
        /// @param service service used to resolve filesystem paths.
        /// @return cache entry.
        private ImageCacheEntry build(@Nullable CatalogIndex index, ImageCacheService service) {
            @Nullable Map<String, RuyiDistfile> declared = index == null
                    ? null
                    : index.distfiles(origin, service);
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
