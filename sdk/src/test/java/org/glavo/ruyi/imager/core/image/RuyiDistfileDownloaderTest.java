// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.ProgressUnit;
import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.repo.RuyiRepositoryStore;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for Ruyi distfile downloading.
@NotNullByDefault
public final class RuyiDistfileDownloaderTest {
    /// Progress reporter that ignores progress events.
    private static final ProgressReporter NO_PROGRESS = _ -> {
    };

    /// Network consent cannot make an unverified restricted archive usable for local preparation.
    @Test
    public void localPreparationRequiresChecksumWithEitherConsent(@TempDir Path root) throws Exception {
        var directories = new AppDirectories(root.resolve("config"), root.resolve("cache"));
        Path version = Files.createDirectories(directories.cacheDirectory().resolve("downloads/test/board-image/image/1"));
        Files.write(version.resolve("disk.img"), new byte[4]);
        var archive = new RuyiDistfile("disk.img", List.of(), 4L, Map.of(), true, false, null, 0, List.of(), "raw");
        var image = new ImageEntry("test", "board-image", "image", "1", null, "board-image/image", "Image",
                "Vendor", "board", "generic", "dd-v1", Map.of("disk", "disk.img"), List.of(archive), StrategySupport.SUPPORTED);
        var service = new RuyiImageCatalogService(directories, new RuyiRepositoryStore(directories));
        assertEquals(List.of(archive), service.prepareLocalImage(image, List.of(), false, NO_PROGRESS));
        assertThrows(IOException.class, () -> service.prepareLocalImage(image, List.of(), true, NO_PROGRESS));
        assertFalse(Files.exists(directories.cacheDirectory().resolve("artifacts")));
    }

    /// Completing one public request must leave the resume directory available to another request.
    @Test
    public void pendingDownloadKeepsItsStagingDirectory(@TempDir Path directory) throws Exception {
        byte[] content = {1, 2, 3};
        try (TinyHttpServer server = new TinyHttpServer(content);
             HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()) {
            var downloader = new RuyiDistfileDownloader(client);
            var checksums = Map.of("sha256", sha256(content));
            var pending = new RuyiDistfile("first", List.of(server.uri("/first")), 3L, checksums,
                    false, false, null, 0, List.of(), "raw");
            var other = new RuyiDistfile("second", List.of(server.uri("/second")), 3L, checksums,
                    false, false, null, 0, List.of(), "raw");
            var completedOther = new AtomicBoolean();
            downloader.download(pending, directory, _ -> {
                if (completedOther.compareAndSet(false, true)) {
                    assertDoesNotThrow(() -> downloader.download(other, directory, NO_PROGRESS));
                }
            });
            assertArrayEquals(content, Files.readAllBytes(directory.resolve("first")));
            assertArrayEquals(content, Files.readAllBytes(directory.resolve("second")));
        }
    }

    /// Standalone cached and new downloads work when only the target directory is writable.
    @Test
    public void standaloneDownloadNeedsOnlyTargetWriteAccess(@TempDir Path root) throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        Path target = Files.createDirectories(root.resolve("downloads/team/board/image/1"));
        Path parent = target.getParent();
        byte[] bytes = "standalone archive".getBytes(StandardCharsets.UTF_8);
        Files.write(target.resolve("cached"), bytes);
        Set<PosixFilePermission> rootPermissions = Files.getPosixFilePermissions(root);
        Set<PosixFilePermission> parentPermissions = Files.getPosixFilePermissions(parent);
        Set<PosixFilePermission> readOnly = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(root, readOnly);
        try (TinyHttpServer server = new TinyHttpServer(bytes)) {
            Assumptions.assumeFalse(Files.isWritable(root));
            var cached = new RuyiDistfile("cached", List.of(), (long) bytes.length,
                    Map.of("sha256", sha256(bytes)), false, false, null, 0, List.of(), "raw");
            var fresh = new RuyiDistfile("fresh", List.of(server.uri("/fresh")), (long) bytes.length,
                    cached.checksums(), false, false, null, 0, List.of(), "raw");
            var downloader = new RuyiDistfileDownloader();

            assertEquals(target.resolve("cached"), downloader.download(cached, target, NO_PROGRESS));
            assertFalse(Files.exists(parent.resolve("1.lock")));
            Files.setPosixFilePermissions(parent, readOnly);
            Assumptions.assumeFalse(Files.isWritable(parent));
            assertArrayEquals(bytes, Files.readAllBytes(downloader.download(fresh, target, NO_PROGRESS)));
            assertFalse(Files.exists(parent.resolve("1.lock")));
        } finally {
            Files.setPosixFilePermissions(parent, parentPermissions);
            Files.setPosixFilePermissions(root, rootPermissions);
        }
    }

    /// Retains the active partial when cleanup runs during a direct download into the managed layout.
    @Test
    public void protectsLegacyPublicDownloadFromManagedCleanup(@TempDir Path cache) throws Exception {
        byte[] content = "a cached archive".getBytes(StandardCharsets.UTF_8);
        Path version = cache.resolve("downloads/test/board-image/image/1");
        var service = new ImageCacheService(new AppDirectories(cache.resolve("config"), cache));
        AtomicBoolean checked = new AtomicBoolean();
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            var archive = new RuyiDistfile("root", List.of(server.uri("/root")), (long) content.length,
                    Map.of("sha256", sha256(content)), false, false, null, 0, List.of(), "raw");
            Path result = new RuyiDistfileDownloader().download(archive, version, event -> {
                if (event.currentBytes() != null && event.currentBytes() > 0 && checked.compareAndSet(false, true)) {
                    assertEquals(0, service.cleanPartials().removedFiles());
                    assertEquals(0, service.cleanAll().removedFiles());
                    assertTrue(Files.isRegularFile(version.resolve(".download-partials-0/root.part")));
                }
            });
            assertTrue(checked.get());
            assertArrayEquals(content, Files.readAllBytes(result));

            Path otherVersion = version.resolveSibling("2");
            Path otherLock = cache.relativize(version.resolveSibling("2.lock"));
            assertTrue(CacheTreeRemoval.withVersionLock(cache.toRealPath(), otherLock, () ->
                    assertThrows(IOException.class,
                            () -> new RuyiDistfileDownloader().download(archive, otherVersion, NO_PROGRESS))));
            assertEquals(1, server.requests.get());
        }
    }

    /// Moves a marked, exclusive legacy partial into private staging and resumes with the same inode.
    @Test
    public void migratesMarkedExclusiveLegacyPartial(@TempDir Path cache) throws Exception {
        byte[] content = "a cached archive".getBytes(StandardCharsets.UTF_8);
        Path version = Files.createDirectories(cache.resolve("downloads/test/board-image/image/1"));
        Path legacy = Files.write(version.resolve("root.part"), Arrays.copyOf(content, 4));
        Assumptions.assumeTrue(CacheTreeRemoval.linkCount(legacy) == 1L);
        @Nullable Object original = Files.readAttributes(legacy, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        ImageCacheMarkers.write(cache, legacy);
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            var archive = new RuyiDistfile("root", List.of(server.uri("/root")), (long) content.length,
                    Map.of("sha256", sha256(content)), false, false, null, 0, List.of(), "raw");
            var directories = new AppDirectories(cache.resolve("config"), cache);
            var service = new RuyiImageCatalogService(directories, new RuyiRepositoryStore(directories));
            var image = new ImageEntry("test", "board-image", "image", "1", null, "board-image/image", "Image",
                    "Vendor", "board", "generic", "dd-v1", Map.of("disk", "root"),
                    List.of(archive), StrategySupport.SUPPORTED);
            service.downloadImage(image, NO_PROGRESS);
            assertEquals(List.of("bytes=4-"), server.rangeRequests());
            assertFalse(Files.exists(legacy));
            assertFalse(ImageCacheMarkers.exists(cache, legacy));
            Path published = version.resolve("root");
            assertArrayEquals(content, Files.readAllBytes(published));
            if (original != null) {
                assertEquals(original, Files.readAttributes(published, java.nio.file.attribute.BasicFileAttributes.class).fileKey());
            }
        }
    }

    /// Local preparation never fetches missing components without explicit authorization.
    @Test
    public void preparesLocalImageOnlyWithDownloadConsent(@TempDir Path root) throws Exception {
        byte[] localBytes = "local".getBytes(StandardCharsets.UTF_8);
        byte[] remoteBytes = "remote".getBytes(StandardCharsets.UTF_8);
        Path local = Files.write(root.resolve("renamed"), localBytes);
        try (TinyHttpServer server = new TinyHttpServer(remoteBytes)) {
            var directories = new AppDirectories(root.resolve("config"), root.resolve("cache"));
            var service = new RuyiImageCatalogService(directories, new RuyiRepositoryStore(directories));
            var first = new RuyiDistfile("local.img", List.of(), (long) localBytes.length, Map.of("sha256", sha256(localBytes)),
                    true, false, null, 0, List.of(), "raw");
            var second = new RuyiDistfile("remote.img", List.of(server.uri("/remote")), (long) remoteBytes.length,
                    Map.of("sha256", sha256(remoteBytes)), false, false, null, 0, List.of(), "raw");
            var image = new ImageEntry("test", "board-image", "combo", "1", null, "board-image/combo", "Combo",
                    "Vendor", "board", "generic", "dd-v1", Map.of("a", "local.img", "b", "remote.img"),
                    List.of(first, second), StrategySupport.SUPPORTED);
            assertEquals(List.of(second), service.prepareLocalImage(image, List.of(local), false, NO_PROGRESS));
            assertEquals(0, server.requests.get());
            assertTrue(service.prepareLocalImage(image, List.of(), true, NO_PROGRESS).isEmpty());
            assertEquals(1, server.requests.get());
            assertEquals("local", Files.readString(local));
            assertTrue(service.prepareLocalImage(image, List.of(), false, NO_PROGRESS).isEmpty());
            assertEquals(1, server.requests.get());
        }
    }

    /// Keeps a completed archive separate from another archive's resume file.
    @Test
    public void preservesArchiveWithReservedPartialName(@TempDir Path root) throws Exception {
        byte[] downloaded = "remote".getBytes(StandardCharsets.UTF_8);
        byte[] retained = "old".getBytes(StandardCharsets.UTF_8);
        try (TinyHttpServer server = new TinyHttpServer(downloaded)) {
            Path cache = root.resolve("cache");
            Path directory = Files.createDirectories(cache.resolve("downloads/test/board-image/combo/1"));
            Path existing = Files.write(directory.resolve("root.part"), retained);
            ImageCacheMarkers.write(cache, existing);
            Path reservedDirectoryName = Files.write(directory.resolve(".download-partials-0"), retained);
            var first = new RuyiDistfile("root", List.of(server.uri("/root")), (long) downloaded.length,
                    Map.of("sha256", sha256(downloaded)), false, false, null, 0, List.of(), "raw");
            var second = new RuyiDistfile("root.part", List.of(), (long) retained.length,
                    Map.of("sha256", sha256(retained)), true, false, null, 0, List.of(), "raw");
            var third = new RuyiDistfile(".download-partials-0", List.of(), (long) retained.length,
                    Map.of("sha256", sha256(retained)), true, false, null, 0, List.of(), "raw");
            var image = new ImageEntry("test", "board-image", "combo", "1", null, "board-image/combo", "Combo",
                    "Vendor", "board", "generic", "dd-v1", Map.of("disk", "root"),
                    List.of(first, second, third), StrategySupport.SUPPORTED);
            var directories = new AppDirectories(root.resolve("config"), cache);
            var service = new RuyiImageCatalogService(directories, new RuyiRepositoryStore(directories));

            assertTrue(service.prepareLocalImage(image, List.of(), true, NO_PROGRESS).isEmpty());
            assertArrayEquals(downloaded, Files.readAllBytes(directory.resolve("root")));
            assertArrayEquals(retained, Files.readAllBytes(existing));
            assertFalse(ImageCacheMarkers.exists(cache, existing));
            assertArrayEquals(retained, Files.readAllBytes(reservedDirectoryName));
            assertEquals(List.of(), server.rangeRequests());
        }
    }

    /// Protects cached archives added after a caller loaded its image declarations.
    @Test
    public void preservesArchiveWithoutFreshDeclarations(@TempDir Path directory) throws Exception {
        byte[] downloaded = "remote".getBytes(StandardCharsets.UTF_8);
        byte[] retained = "old declaration".getBytes(StandardCharsets.UTF_8);
        try (TinyHttpServer server = new TinyHttpServer(downloaded)) {
            Path existing = Files.write(directory.resolve("root.part"), retained);
            var distfile = new RuyiDistfile("root", List.of(server.uri("/root")), (long) downloaded.length,
                    Map.of("sha256", sha256(downloaded)), false, false, null, 0, List.of(), "raw");

            assertArrayEquals(downloaded, Files.readAllBytes(
                    new RuyiDistfileDownloader().download(distfile, directory, NO_PROGRESS)));
            assertArrayEquals(retained, Files.readAllBytes(existing));
            assertEquals(List.of(), server.rangeRequests());
        }
    }

    /// Copies and resumes a legacy sibling without writing to the original file.
    @Test
    public void resumesLegacySiblingWithoutChangingIt(@TempDir Path directory) throws Exception {
        byte[] content = "complete archive".getBytes(StandardCharsets.UTF_8);
        byte[] prefix = Arrays.copyOf(content, 4);
        Path legacy = Files.write(directory.resolve("root.part"), prefix);
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            var archive = new RuyiDistfile("root", List.of(server.uri("/root")), (long) content.length,
                    Map.of("sha256", sha256(content)), false, false, null, 0, List.of(), "raw");
            Path result = new RuyiDistfileDownloader().download(archive, directory, NO_PROGRESS);
            assertEquals(List.of("bytes=4-"), server.rangeRequests());
            assertArrayEquals(content, Files.readAllBytes(result));
            assertArrayEquals(prefix, Files.readAllBytes(legacy));
        }
    }

    /// A stale legacy prefix gets one clean retry against the same source.
    @Test
    public void retriesStaleLegacyPrefixFromZero(@TempDir Path directory) throws Exception {
        byte[] content = "complete archive".getBytes(StandardCharsets.UTF_8);
        byte[] prefix = "old!".getBytes(StandardCharsets.UTF_8);
        Path legacy = Files.write(directory.resolve("root.part"), prefix);
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            var archive = new RuyiDistfile("root", List.of(server.uri("/root")), (long) content.length,
                    Map.of("sha256", sha256(content)), false, false, null, 0, List.of(), "raw");
            Path result = new RuyiDistfileDownloader().download(archive, directory, NO_PROGRESS);
            assertEquals(2, server.requests.get());
            assertEquals(List.of("bytes=4-"), server.rangeRequests());
            assertArrayEquals(content, Files.readAllBytes(result));
            assertArrayEquals(prefix, Files.readAllBytes(legacy));
        }
    }

    /// A new archive can occupy a staging name while an unrelated download still resumes.
    @Test
    public void recoversPartialsWhenAnArchiveClaimsAStagingSlot(@TempDir Path directory) throws Exception {
        byte[] content = "complete archive".getBytes(StandardCharsets.UTF_8);
        Path original = resumeDirectory(directory);
        String name = original.getFileName().toString();
        Files.write(original.resolve(name + ".part"), Arrays.copyOf(content, 2));
        Files.write(original.resolve("root.part"), Arrays.copyOf(content, 4));
        Files.write(Files.createDirectory(directory.resolve(".download-partials-1")).resolve("other.part"), content);
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            var unavailable = new RuyiDistfile(name, List.of(URI.create("file:///unavailable")),
                    (long) content.length, Map.of("sha256", sha256(content)), false, false, null, 0, List.of(), "raw");
            var colliding = new RuyiDistfile(name, List.of(server.uri("/staging")),
                    (long) content.length, Map.of("sha256", sha256(content)), false, false, null, 0, List.of(), "raw");
            var resumed = new RuyiDistfile("root", List.of(server.uri("/root")), (long) content.length,
                    Map.of("sha256", sha256(content)), false, false, null, 0, List.of(), "raw");
            var archives = List.of(colliding, resumed);
            var downloader = new RuyiDistfileDownloader();
            assertThrows(IOException.class, () -> downloader.download(unavailable, directory, null, archives, NO_PROGRESS));
            assertEquals(original, downloader.download(colliding, directory, null, archives, NO_PROGRESS));
            assertArrayEquals(content, Files.readAllBytes(original));
            assertArrayEquals(content, Files.readAllBytes(downloader.download(resumed, directory, null, archives, NO_PROGRESS)));
            assertEquals(List.of("bytes=2-", "bytes=4-"), server.rangeRequests());
            assertArrayEquals(content, Files.readAllBytes(directory.resolve(".download-partials-1/other.part")));
        }
    }

    /// Clears the manual destination and preserves its resume data during local import.
    @Test
    public void clearsStagingCollisionForManualAndLocalAcquisition(@TempDir Path root) throws Exception {
        byte[] content = "local archive".getBytes(StandardCharsets.UTF_8);
        Path cache = root.resolve("cache");
        Path version = Files.createDirectories(cache.resolve("downloads/test/board-image/image/1"));
        Path archivePath = resumeDirectory(version);
        byte[] prefix = Arrays.copyOf(content, 4);
        Files.write(archivePath.resolve("root.part"), prefix);
        String name = archivePath.getFileName().toString();
        Path source = Files.write(root.resolve("local"), content);
        var archive = new RuyiDistfile(name, List.of(), (long) content.length, Map.of("sha256", sha256(content)),
                true, false, null, 0, List.of(), "raw");
        var image = new ImageEntry("test", "board-image", "image", "1", null, "board-image/image", "Image",
                "Vendor", "board", "generic", "dd-v1", Map.of("disk", name), List.of(archive), StrategySupport.SUPPORTED);
        var directories = new AppDirectories(root.resolve("config"), cache);
        var service = new RuyiImageCatalogService(directories, new RuyiRepositoryStore(directories));

        assertThrows(IOException.class, () -> service.downloadImage(image, NO_PROGRESS));
        assertFalse(Files.exists(archivePath));
        Path relocated = version.resolve(".download-partials-1");
        Files.move(relocated, archivePath);
        assertTrue(service.prepareLocalImage(image, List.of(source), false, NO_PROGRESS).isEmpty());
        assertArrayEquals(content, Files.readAllBytes(archivePath));
        assertArrayEquals(prefix, Files.readAllBytes(relocated.resolve("root.part")));
    }

    /// Preserves an ordinary uppercase directory on case-sensitive filesystems.
    @Test
    public void doesNotRelocateUppercaseOrdinaryDirectory(@TempDir Path directory) throws Exception {
        String name = ".DOWNLOAD-PARTIALS-0";
        Path ordinary = Files.createDirectory(directory.resolve(name));
        Assumptions.assumeFalse(Files.exists(directory.resolve(".download-partials-0")), "filesystem is case-sensitive");
        Path existing = Files.writeString(ordinary.resolve("existing"), "ordinary directory");
        var archive = new RuyiDistfile(name, List.of(URI.create("file:///unavailable")), 1L,
                Map.of("sha256", sha256(new byte[1])), false, false, null, 0, List.of(), "raw");
        assertThrows(IOException.class, () -> new RuyiDistfileDownloader().download(archive, directory, NO_PROGRESS));
        assertEquals("ordinary directory", Files.readString(existing));
    }

    /// Removes an older resume marker before using a complete cache hit or publishing its replacement.
    @Test
    public void clearsFinalPathMarker(@TempDir Path cache) throws Exception {
        byte[] content = "complete archive".getBytes(StandardCharsets.UTF_8);
        try (TinyHttpServer server = new TinyHttpServer(content);
             HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()) {
            Path directory = Files.createDirectories(cache.resolve("downloads/test/board-image/image/1"));
            Path target = directory.resolve("root.part");
            var distfile = new RuyiDistfile("root.part", List.of(server.uri("/root.part")), (long) content.length,
                    Map.of("sha256", sha256(content)), false, false, null, 0, List.of(), "raw");
            var downloader = new RuyiDistfileDownloader(client);

            ImageCacheMarkers.write(cache, target);
            downloader.download(distfile, directory, cache, List.of(distfile), NO_PROGRESS);
            assertFalse(ImageCacheMarkers.exists(cache, target));

            ImageCacheMarkers.write(cache, target);
            downloader.download(distfile, directory, cache, List.of(distfile), NO_PROGRESS);
            assertFalse(ImageCacheMarkers.exists(cache, target));
            assertArrayEquals(content, Files.readAllBytes(target));
            assertEquals(1, server.requests.get());
        }
    }

    /// Publishes and reuses an archive when another archive's unreadable marker is present.
    @Test
    public void publishesWithAnUnreadableUnrelatedMarker(@TempDir Path cache) throws Exception {
        byte[] content = "complete archive".getBytes(StandardCharsets.UTF_8);
        Path directory = Files.createDirectories(cache.resolve("downloads/test/board-image/image/1"));
        Path target = directory.resolve("root.part");
        Path unrelated = directory.resolve("unrelated.part");
        ImageCacheMarkers.write(cache, unrelated);
        Path marker;
        try (var markers = Files.list(cache.resolve(ImageCacheMarkers.DIRECTORY))) {
            marker = markers.findFirst().orElseThrow();
        }
        ImageCacheMarkers.write(cache, target);
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        if (!windows) {
            Assumptions.assumeTrue(Files.getFileStore(marker).supportsFileAttributeView("posix"));
            Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("---------"));
            Assumptions.assumeFalse(Files.isReadable(marker));
        }
        try (var lock = windows ? FileChannel.open(marker, StandardOpenOption.READ,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ) : null;
             TinyHttpServer server = new TinyHttpServer(content)) {
            assertFalse(ImageCacheMarkers.exists(cache, unrelated));
            var distfile = new RuyiDistfile("root.part", List.of(server.uri("/root.part")), (long) content.length,
                    Map.of("sha256", sha256(content)), false, false, null, 0, List.of(), "raw");
            var directories = new AppDirectories(cache.resolve("config"), cache);
            var service = new RuyiImageCatalogService(directories, new RuyiRepositoryStore(directories));
            var image = new ImageEntry("test", "board-image", "image", "1", null, "board-image/image", "Image",
                    "Vendor", "board", "generic", "dd-v1", Map.of("disk", "root.part"),
                    List.of(distfile), StrategySupport.SUPPORTED);
            service.downloadImage(image, NO_PROGRESS);
            service.downloadImage(image, NO_PROGRESS);
        }
        assertArrayEquals(content, Files.readAllBytes(target));
        assertFalse(ImageCacheMarkers.exists(cache, target));
        assertTrue(Files.exists(marker));
        new ImageCacheService(new AppDirectories(cache.resolve("config"), cache)).cleanPartials();
        assertArrayEquals(content, Files.readAllBytes(target));
    }

    /// Keeps a verified partial when final archive publication fails and never contacts a second source.
    @Test
    public void preservesVerifiedPartialOnPublicationFailure(@TempDir Path directory) throws Exception {
        byte[] content = "complete archive".getBytes(StandardCharsets.UTF_8);
        Path target = Files.createDirectory(directory.resolve("root"));
        Files.writeString(target.resolve("existing"), "retained");
        try (TinyHttpServer source = new TinyHttpServer(content);
             TinyHttpServer mirror = new TinyHttpServer(content)) {
            var distfile = new RuyiDistfile("root", List.of(source.uri("/root"), mirror.uri("/no-range")),
                    (long) content.length, Map.of("sha256", sha256(content)), false, false, null, 0, List.of(), "raw");
            var failure = assertThrows(IOException.class,
                    () -> new RuyiDistfileDownloader().download(distfile, directory, NO_PROGRESS));
            assertInstanceOf(java.nio.file.FileSystemException.class, failure);
            assertEquals(1, source.requests.get());
            assertEquals(0, mirror.requests.get());
            assertArrayEquals(content, Files.readAllBytes(directory.resolve(".download-partials-0/root.part")));
            assertEquals("retained", Files.readString(target.resolve("existing")));
        }
    }

    /// Coordinates active staging across public downloader instances that share a version directory.
    @Test
    public void doesNotRelocateAnotherPublicDownloadsActiveStaging(@TempDir Path directory) throws Exception {
        byte[] content = "complete archive".getBytes(StandardCharsets.UTF_8);
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (TinyHttpServer server = new TinyHttpServer(content);
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = new RuyiDistfile("root", List.of(server.uri("/root")), (long) content.length,
                    Map.of("sha256", sha256(content)), false, false, null, 0, List.of(), "raw");
            var second = new RuyiDistfile(".download-partials-0", List.of(server.uri("/reserved")),
                    (long) content.length, Map.of("sha256", sha256(content)), false, false, null, 0, List.of(), "raw");
            var original = new RuyiDistfileDownloader();
            var competitor = new RuyiDistfileDownloader();
            var firstResult = tasks.submit(() -> original.download(first, directory, _ -> {
                connecting.countDown();
                try {
                    resume.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            try {
                assertTrue(connecting.await(5, TimeUnit.SECONDS));
                var secondResult = tasks.submit(() -> assertThrows(java.nio.file.FileAlreadyExistsException.class,
                        () -> competitor.download(second, directory.resolve("."), NO_PROGRESS)));
                assertNotNull(secondResult.get(5, TimeUnit.SECONDS));
                resume.countDown();
                assertArrayEquals(content, Files.readAllBytes(firstResult.get(5, TimeUnit.SECONDS)));
                assertEquals(1, server.requests.get());
                assertTrue(Files.isDirectory(directory.resolve(second.name())));
            } finally {
                resume.countDown();
            }
        }
    }

    /// Isolated resume data requires no marker and leaves no fresh empty partial.
    @Test
    public void attemptsSourceWithoutAMarkerDirectory(@TempDir Path root) throws Exception {
        byte[] payload = "too long".getBytes(StandardCharsets.UTF_8);
        try (TinyHttpServer server = new TinyHttpServer(payload)) {
            Path cache = root.resolve("cache");
            Path directory = Files.createDirectories(cache.resolve("downloads/test/board-image/image/1"));
            Path partial = directory.resolve(RuyiDistfileDownloader.DOWNLOAD_PARTIAL_DIRECTORY_PREFIX + "0")
                    .resolve("root.part");
            Files.writeString(cache.resolve(ImageCacheMarkers.DIRECTORY), "occupied");
            RuyiDistfile distfile = new RuyiDistfile(
                    "root", List.of(server.uri("/root")), 1L, Map.of("sha256", sha256(new byte[1])),
                    false, false, null, 0, List.of(), "raw");

            var directories = new AppDirectories(root.resolve("config"), cache);
            var service = new RuyiImageCatalogService(directories, new RuyiRepositoryStore(directories));
            var image = new ImageEntry("test", "board-image", "image", "1", null, "board-image/image", "Image",
                    "Vendor", "board", "generic", "dd-v1", Map.of("disk", "root"),
                    List.of(distfile), StrategySupport.SUPPORTED);
            assertThrows(IOException.class, () -> service.downloadImage(image, NO_PROGRESS));

            assertEquals(1, server.requests.get());
            assertFalse(Files.exists(partial));
            assertFalse(Files.exists(directory.resolve("root")));
        }
    }

    /// Verifies that a distfile can be downloaded and verified.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the fixture server or filesystem fails.
    @Test
    public void downloadsDistfileAndVerifiesChecksum(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = "hello ruyi\n".getBytes(StandardCharsets.UTF_8);
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            RuyiDistfile distfile = new RuyiDistfile(
                    "image.raw",
                    List.of(server.uri("/image.raw")),
                    (long) content.length,
                    Map.of("sha256", sha256(content)),
                    false,
                    true,
                    null,
                    0,
                    List.of(),
                    "raw");

            Path result = new RuyiDistfileDownloader().download(distfile, temporaryDirectory, NO_PROGRESS);

            assertEquals(temporaryDirectory.resolve("image.raw"), result);
            assertArrayEquals(content, Files.readAllBytes(result));
            assertEquals(List.of(), server.rangeRequests());
        }
    }

    /// Resuming from a hard-linked partial preserves the other name's bytes.
    @Test
    public void resumesWithoutWritingToAnExistingHardLink(@TempDir Path directory) throws Exception {
        byte[] content = new byte[1024];
        Arrays.fill(content, (byte) 7);
        byte[] prefix = Arrays.copyOf(content, 128);
        Path original = Files.write(directory.resolve("preserved.raw"), prefix);
        try {
            Files.createLink(resumeDirectory(directory).resolve("image.raw.part"), original);
            Path staging = Files.createDirectory(resumeDirectory(directory).resolve(
                    RuyiDistfileDownloader.DOWNLOAD_PARTIAL_DIRECTORY_PREFIX + "leftover"));
            Files.createLink(staging.resolve("resume"), original);
        } catch (IOException | UnsupportedOperationException _) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "hard links are unavailable");
        }
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            RuyiDistfile distfile = new RuyiDistfile("image.raw", List.of(server.uri("/image.raw")),
                    (long) content.length, Map.of("sha256", sha256(content)), false, true, null, 0, List.of(), "raw");

            Path result = new RuyiDistfileDownloader().download(distfile, directory, NO_PROGRESS);

            assertArrayEquals(content, Files.readAllBytes(result));
            assertArrayEquals(prefix, Files.readAllBytes(original));
            assertEquals(List.of("bytes=128-"), server.rangeRequests());
        }
    }

    /// Resumes an unshared partial in place on a file system supporting named attributes.
    @Test
    public void reusesAnUnsharedPartial(@TempDir Path directory) throws Exception {
        byte[] content = new byte[1024];
        Arrays.fill(content, (byte) 7);
        Path partial = Files.write(resumeDirectory(directory).resolve("image.raw.part"), Arrays.copyOf(content, 128));
        var attributes = Files.getFileAttributeView(partial, UserDefinedFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(attributes != null, "named attributes are unavailable");
        try {
            attributes.write("resume-test", ByteBuffer.wrap(new byte[]{1}));
        } catch (IOException | UnsupportedOperationException _) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "named attributes are unavailable");
        }

        try (TinyHttpServer server = new TinyHttpServer(content)) {
            RuyiDistfile distfile = new RuyiDistfile("image.raw", List.of(server.uri("/image.raw")),
                    (long) content.length, Map.of("sha256", sha256(content)), false, true, null, 0, List.of(), "raw");

            Path result = new RuyiDistfileDownloader().download(distfile, directory, NO_PROGRESS);

            assertArrayEquals(content, Files.readAllBytes(result));
            assertEquals(List.of("bytes=128-"), server.rangeRequests());
            assertTrue(Files.getFileAttributeView(result, UserDefinedFileAttributeView.class).list().contains("resume-test"));
            assertPublishedWithEmptyResumeDirectory(result);
        }
    }

    /// A completed download leaves only the published archive and the reusable empty slot.
    private static void assertPublishedWithEmptyResumeDirectory(Path result) throws IOException {
        Path directory = result.getParent();
        Path slot = directory.resolve(RuyiDistfileDownloader.DOWNLOAD_PARTIAL_DIRECTORY_PREFIX + "0");
        try (var siblings = Files.list(directory); var entries = Files.list(slot)) {
            assertEquals(Set.of(result, slot), Set.copyOf(siblings.toList()));
            assertTrue(entries.findAny().isEmpty());
        }
    }

    /// Refuses to open a linked partial for writing.
    @Test
    public void leavesSymbolicPartialTargetUnchanged(@TempDir Path directory) throws Exception {
        byte[] content = new byte[1024];
        byte[] prefix = Arrays.copyOf(content, 128);
        Path original = Files.write(directory.resolve("preserved.raw"), prefix);
        try {
            Files.createSymbolicLink(resumeDirectory(directory).resolve("image.raw.part"), original);
        } catch (IOException | UnsupportedOperationException _) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            RuyiDistfile distfile = new RuyiDistfile("image.raw", List.of(server.uri("/image.raw")),
                    (long) content.length, Map.of("sha256", sha256(content)), false, true, null, 0, List.of(), "raw");

            assertThrows(IOException.class, () -> new RuyiDistfileDownloader().download(distfile, directory, NO_PROGRESS));
            assertArrayEquals(prefix, Files.readAllBytes(original));
            assertTrue(server.rangeRequests().isEmpty());
        }
    }

    /// Verifies that an existing partial file is resumed with a Range request.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the fixture server or filesystem fails.
    @Test
    public void resumesPartialDownloadWithRangeRequest(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = new byte[1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }

        Files.write(resumeDirectory(temporaryDirectory).resolve("image.raw.part"), Arrays.copyOf(content, 128));
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            RuyiDistfile distfile = new RuyiDistfile(
                    "image.raw",
                    List.of(server.uri("/image.raw")),
                    (long) content.length,
                    Map.of("sha256", sha256(content)),
                    false,
                    true,
                    null,
                    0,
                    List.of(),
                    "raw");

            List<ProgressEvent> events = new ArrayList<>();
            Path result = new RuyiDistfileDownloader().download(distfile, temporaryDirectory, events::add);

            assertArrayEquals(content, Files.readAllBytes(result));
            assertEquals(List.of("bytes=128-"), server.rangeRequests());
            assertConnectingEvent(events);
            assertTrue(
                    events.stream().anyMatch(event -> event.message().startsWith("Resuming")),
                    events.toString());
        }
    }

    /// Verifies that a source ignoring the range request is reported as a restart, not a resume.
    ///
    /// @param temporaryDirectory directory holding the partial file.
    /// @throws Exception when the fixture server or filesystem fails.
    @Test
    public void reportsRestartWhenSourceIgnoresRange(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = new byte[1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }

        Files.write(resumeDirectory(temporaryDirectory).resolve("image.raw.part"), Arrays.copyOf(content, 128));
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            RuyiDistfile distfile = new RuyiDistfile(
                    "image.raw",
                    List.of(server.uri("/no-range")),
                    (long) content.length,
                    Map.of("sha256", sha256(content)),
                    false,
                    true,
                    null,
                    0,
                    List.of(),
                    "raw");

            List<ProgressEvent> events = new ArrayList<>();
            Path result = new RuyiDistfileDownloader().download(distfile, temporaryDirectory, events::add);

            assertArrayEquals(content, Files.readAllBytes(result));
            assertEquals(List.of("bytes=128-"), server.rangeRequests());
            assertConnectingEvent(events);
            assertTrue(
                    events.stream().noneMatch(event -> event.message().startsWith("Resuming")),
                    events.toString());
            assertTrue(
                    events.stream().anyMatch(event -> event.message().startsWith("Downloading")),
                    events.toString());
        }
    }

    /// Verifies that a corrupt full-size partial file is discarded and downloaded from the beginning.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the fixture server or filesystem fails.
    @Test
    public void restartsInvalidCompletePartialDownload(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = "complete image".getBytes(StandardCharsets.UTF_8);
        Files.write(resumeDirectory(temporaryDirectory).resolve("image.raw.part"), new byte[content.length]);
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            RuyiDistfile distfile = new RuyiDistfile(
                    "image.raw",
                    List.of(server.uri("/image.raw")),
                    (long) content.length,
                    Map.of("sha256", sha256(content)),
                    false,
                    true,
                    null,
                    0,
                    List.of(),
                    "raw");

            Path result = new RuyiDistfileDownloader().download(distfile, temporaryDirectory, NO_PROGRESS);

            assertArrayEquals(content, Files.readAllBytes(result));
            assertEquals(List.of(), server.rangeRequests());
        }
    }

    /// Verifies response data beyond the declared size is rejected before it is written.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when the fixture server or filesystem fails.
    @Test
    public void rejectsResponseLargerThanDeclaredSize(@TempDir Path temporaryDirectory) throws Exception {
        byte[] content = new byte[1024];
        byte[] expected = Arrays.copyOf(content, 8);
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            RuyiDistfile distfile = new RuyiDistfile(
                    "image.raw",
                    List.of(server.uri("/image.raw")),
                    (long) expected.length,
                    Map.of("sha256", sha256(expected)),
                    false,
                    true,
                    null,
                    0,
                    List.of(),
                    "raw");

            IOException exception = assertThrows(IOException.class, () ->
                    new RuyiDistfileDownloader().download(distfile, temporaryDirectory, NO_PROGRESS));

            @Nullable Throwable cause = exception.getCause();
            assertNotNull(cause);
            assertTrue(cause.getMessage().contains("declared size"), exception.toString());
            Path partial = temporaryDirectory.resolve(RuyiDistfileDownloader.DOWNLOAD_PARTIAL_DIRECTORY_PREFIX + "0")
                    .resolve("image.raw.part");
            assertTrue(!Files.exists(partial) || Files.size(partial) <= expected.length);
        }
    }

    /// Times out stalled bodies and resumes from the next source without losing received bytes.
    @Test
    public void fallsBackAfterBodyTimeout(@TempDir Path directory) throws Exception {
        byte[] content = "download through a fallback source".getBytes(StandardCharsets.UTF_8);
        for (boolean partialBody : List.of(false, true)) {
            try (TinyHttpServer server = new TinyHttpServer(content);
                 HttpClient client = HttpClient.newHttpClient()) {
                RuyiDistfile distfile = new RuyiDistfile("image.raw",
                        List.of(server.uri(partialBody ? "/stall-partial" : "/stall-empty"), server.uri("/image.raw")),
                        (long) content.length, Map.of("sha256", sha256(content)), false, true, null, 0, List.of(), "raw");
                Path result = assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                        new RuyiDistfileDownloader(client, Duration.ofMillis(200))
                                .download(distfile, directory, NO_PROGRESS));
                assertArrayEquals(content, Files.readAllBytes(result));
                assertTrue(server.stalledConnectionClosed.await(1, TimeUnit.SECONDS));
                assertEquals(partialBody ? List.of("bytes=4-") : List.of(), server.rangeRequests());
                Files.delete(result);
            }
        }
    }

    /// Interrupts a body wait promptly and does not try another source after cancellation.
    @Test
    public void cancelsStalledBodyWithoutFallback(@TempDir Path directory) throws Exception {
        byte[] content = "download cancellation".getBytes(StandardCharsets.UTF_8);
        try (TinyHttpServer server = new TinyHttpServer(content);
             HttpClient client = HttpClient.newHttpClient()) {
            RuyiDistfile distfile = new RuyiDistfile("image.raw",
                    List.of(server.uri("/stall-partial"), server.uri("/image.raw")),
                    (long) content.length, Map.of("sha256", sha256(content)), false, true, null, 0, List.of(), "raw");
            AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
            CountDownLatch received = new CountDownLatch(1);
            Thread download = Thread.ofVirtual().start(() -> {
                try {
                    new RuyiDistfileDownloader(client).download(distfile, directory, event -> {
                        if (event.currentBytes() != null && event.currentBytes() > 0) {
                            received.countDown();
                        }
                    });
                } catch (Throwable exception) {
                    failure.set(exception);
                }
            });
            try {
                assertTrue(received.await(3, TimeUnit.SECONDS));
                download.interrupt();
                download.join(2000);
                assertFalse(download.isAlive());
                assertTrue(download.isInterrupted());
                IOException exception = assertInstanceOf(IOException.class, failure.get());
                assertInstanceOf(InterruptedException.class, exception.getCause());
                assertTrue(server.stalledConnectionClosed.await(1, TimeUnit.SECONDS));
                assertEquals(List.of(), server.rangeRequests());
                assertFalse(Files.exists(directory.resolve("image.raw")));
            } finally {
                download.interrupt();
                download.join(2000);
            }
        }
    }

    /// Verifies unsafe distfile names are rejected before they can be resolved against cache directories.
    @Test
    public void rejectsUnsafeDistfileName() {
        assertThrows(IllegalArgumentException.class, () -> new RuyiDistfile(
                "../image.raw",
                List.of(URI.create("https://example.invalid/image.raw")),
                null,
                Map.of(),
                false,
                true,
                null,
                0,
                List.of(),
                "raw"));
    }

    /// Verifies that fetch-restricted distfiles report rendered manual instructions.
    ///
    /// @param temporaryDirectory temporary test directory.
    @Test
    public void reportsFetchRestrictionInstructions(@TempDir Path temporaryDirectory) {
        RuyiDistfile distfile = new RuyiDistfile(
                "manual.raw",
                List.of(),
                null,
                Map.of(),
                true,
                true,
                new RuyiFetchRestriction(
                        Map.of("en_US", "Download with {{ method }} to {{ dest_path }}."),
                        Map.of("method", "browser")),
                0,
                List.of(),
                "raw");

        IOException exception = assertThrows(IOException.class, () ->
                new RuyiDistfileDownloader().download(distfile, temporaryDirectory, NO_PROGRESS));

        String message = exception.getMessage();
        assertTrue(message.contains("manual.raw"), message);
        assertTrue(message.contains("browser"), message);
        assertTrue(message.contains(temporaryDirectory.resolve("manual.raw").toAbsolutePath().normalize().toString()), message);
    }

    /// Verifies automatic downloads reject metadata without a supported strong checksum.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws IOException when the cached fixture cannot be written.
    @Test
    public void rejectsAutomaticDownloadWithoutSupportedChecksum(@TempDir Path temporaryDirectory) throws IOException {
        Files.writeString(temporaryDirectory.resolve("image.raw"), "cached");
        RuyiDistfile distfile = new RuyiDistfile(
                "image.raw",
                List.of(URI.create("https://example.invalid/image.raw")),
                6L,
                Map.of("sha1", "0123456789abcdef"),
                false,
                true,
                null,
                0,
                List.of(),
                "raw");

        IOException exception = assertThrows(IOException.class, () ->
                new RuyiDistfileDownloader().download(distfile, temporaryDirectory, NO_PROGRESS));

        assertTrue(exception.getMessage().contains("core.download.noSupportedChecksum"), exception.getMessage());
    }

    /// Verifies manually supplied fetch-restricted files remain usable without a strong checksum.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws IOException when the cached fixture cannot be written or inspected.
    @Test
    public void acceptsManualFetchRestrictedFileWithoutSupportedChecksum(
            @TempDir Path temporaryDirectory) throws IOException {
        Path target = temporaryDirectory.resolve("manual.raw");
        Files.writeString(target, "manual");
        RuyiDistfile distfile = new RuyiDistfile(
                "manual.raw",
                List.of(),
                6L,
                Map.of(),
                true,
                true,
                null,
                0,
                List.of(),
                "raw");

        assertEquals(
                target,
                new RuyiDistfileDownloader().download(distfile, temporaryDirectory, NO_PROGRESS));
    }

    /// Isolates resume data even when declaration spellings are canonically equivalent.
    @Test
    public void isolatesResumeNameFromUnicodeEquivalentArchive(@TempDir Path directory) throws Exception {
        Path version = Files.createDirectories(directory.resolve("version"));
        RuyiDistfile candidate = new RuyiDistfile(
                "cafe\u0301.raw", List.of(), null, Map.of(), false, false, null, 0, List.of(), "raw");
        RuyiDistfile equivalent = new RuyiDistfile(
                "caf\u00e9.raw.part", List.of(), null, Map.of(), false, false, null, 0, List.of(), "raw");
        RuyiDistfile unrelated = new RuyiDistfile(
                "other.raw.part", List.of(), null, Map.of(), false, false, null, 0, List.of(), "raw");

        Path reserved = RuyiDistfileDownloader.partialPath(candidate, version, List.of(equivalent));
        assertEquals(reserved, RuyiDistfileDownloader.partialPath(candidate, version, List.of(unrelated)));
        assertEquals(version.resolve(RuyiDistfileDownloader.DOWNLOAD_PARTIAL_DIRECTORY_PREFIX + "0"),
                reserved.getParent());
        assertEquals("cafe\u0301.raw.part", reserved.getFileName().toString());
    }

    /// Appends to an exclusive partial without changing its file identity.
    @Test
    public void resumesExclusivePartialInPlace(@TempDir Path directory) throws Exception {
        byte[] content = new byte[4096];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) index;
        }
        Path partial = Files.write(resumeDirectory(directory).resolve("image.raw.part"), Arrays.copyOf(content, 1024));
        @Nullable Object identity = Files.readAttributes(partial, java.nio.file.attribute.BasicFileAttributes.class)
                .fileKey();
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            RuyiDistfile distfile = new RuyiDistfile("image.raw", List.of(server.uri("/image.raw")),
                    (long) content.length, Map.of("sha256", sha256(content)), false, true, null, 0, List.of(), "raw");

            Path result = new RuyiDistfileDownloader().download(distfile, directory, NO_PROGRESS);

            assertArrayEquals(content, Files.readAllBytes(result));
            assertEquals(List.of("bytes=1024-"), server.rangeRequests());
            if (identity != null) {
                assertEquals(identity, Files.readAttributes(result, java.nio.file.attribute.BasicFileAttributes.class)
                        .fileKey());
            }
        }
    }

    /// Reuses the private hard link left by a previous interrupted resume.
    @Test
    public void reusesLeftoverResumeLinkWithoutCopyingPrefix(@TempDir Path directory) throws Exception {
        byte[] content = new byte[4096];
        Arrays.fill(content, (byte) 3);
        Path partial = Files.write(resumeDirectory(directory).resolve("image.raw.part"), Arrays.copyOf(content, 1024));
        Path staging = Files.createDirectory(resumeDirectory(directory).resolve(
                RuyiDistfileDownloader.DOWNLOAD_PARTIAL_DIRECTORY_PREFIX + "leftover"));
        try {
            Files.createLink(staging.resolve("resume"), partial);
        } catch (IOException | UnsupportedOperationException _) {
            Assumptions.assumeTrue(false, "hard links are unavailable");
        }
        @Nullable Object identity = Files.readAttributes(partial, java.nio.file.attribute.BasicFileAttributes.class)
                .fileKey();
        try (TinyHttpServer server = new TinyHttpServer(content)) {
            RuyiDistfile distfile = new RuyiDistfile("image.raw", List.of(server.uri("/image.raw")),
                    (long) content.length, Map.of("sha256", sha256(content)), false, true, null, 0, List.of(), "raw");

            Path result = new RuyiDistfileDownloader().download(distfile, directory, NO_PROGRESS);

            assertArrayEquals(content, Files.readAllBytes(result));
            assertEquals(List.of("bytes=1024-"), server.rangeRequests());
            if (identity != null) {
                assertEquals(identity, Files.readAttributes(result, java.nio.file.attribute.BasicFileAttributes.class)
                        .fileKey());
            }
            assertFalse(Files.exists(staging));
            assertPublishedWithEmptyResumeDirectory(result);
        }
    }

    /// Creates the download version's isolated resume directory for a test fixture.
    private static Path resumeDirectory(Path directory) throws IOException {
        return Files.createDirectories(directory.resolve(RuyiDistfileDownloader.DOWNLOAD_PARTIAL_DIRECTORY_PREFIX + "0"));
    }

    /// Computes a SHA-256 digest for bytes.
    ///
    /// @param content bytes to digest.
    /// @return lowercase hexadecimal digest.
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /// Asserts that a download attempt reports a neutral connecting event without byte counters.
    ///
    /// @param events reported progress events.
    private static void assertConnectingEvent(List<ProgressEvent> events) {
        assertTrue(
                events.stream().anyMatch(event -> event.stage().equals("download")
                        && event.message().startsWith("Connecting")
                        && event.unit() == ProgressUnit.NONE),
                events.toString());
    }

    /// Minimal HTTP server that supports GET and byte ranges.
    @NotNullByDefault
    private static final class TinyHttpServer implements AutoCloseable {
        /// Counts HTTP requests to verify offline preparation does not contact a source.
        private final AtomicInteger requests = new AtomicInteger();
        /// Bytes served by the fixture server.
        private final byte[] content;

        /// Server socket bound to loopback.
        private final ServerSocket serverSocket;

        /// Background accept loop.
        private final Thread thread;

        /// Range request headers received by the server.
        private final List<String> rangeRequests = java.util.Collections.synchronizedList(new ArrayList<>());

        /// Signals that a stalled response was cancelled by its client.
        private final CountDownLatch stalledConnectionClosed = new CountDownLatch(1);

        /// Creates and starts the fixture server.
        ///
        /// @param content bytes to serve.
        /// @throws IOException when the server socket cannot be opened.
        private TinyHttpServer(byte[] content) throws IOException {
            this.content = content.clone();
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.thread = new Thread(this::run, "ruyi-imager-test-http");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        /// Returns a URI on this server.
        ///
        /// @param path request path.
        /// @return fixture URI.
        private URI uri(String path) {
            return URI.create("http://" + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort() + path);
        }

        /// Returns received Range request headers.
        ///
        /// @return immutable range header list.
        private List<String> rangeRequests() {
            synchronized (rangeRequests) {
                return List.copyOf(rangeRequests);
            }
        }

        /// Runs the server accept loop.
        private void run() {
            while (!serverSocket.isClosed()) {
                try {
                    handle(serverSocket.accept());
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }

        /// Handles one HTTP connection.
        ///
        /// @param socket client socket.
        /// @throws IOException when the connection cannot be read or written.
        private void handle(Socket socket) throws IOException {
            try (Socket client = socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                 OutputStream output = client.getOutputStream()) {
                client.setSoTimeout(5000);
                @Nullable String requestLine = reader.readLine();
                if (requestLine == null || !requestLine.startsWith("GET ")) {
                    writeResponse(output, 400, 0, 0);
                    return;
                }
                requests.incrementAndGet();

                @Nullable String range = null;
                while (true) {
                    @Nullable String line = reader.readLine();
                    if (line == null || line.isEmpty()) {
                        break;
                    }
                    if (line.regionMatches(true, 0, "Range:", 0, "Range:".length())) {
                        range = line.substring("Range:".length()).strip();
                    }
                }

                if (requestLine.startsWith("GET /stall-")) {
                    output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + content.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    if (requestLine.startsWith("GET /stall-partial")) {
                        output.write(content, 0, 4);
                    }
                    output.flush();
                    if (reader.read() == -1) {
                        stalledConnectionClosed.countDown();
                    }
                    return;
                }

                if (requestLine.startsWith("GET /no-range")) {
                    if (range != null) {
                        rangeRequests.add(range);
                    }
                    writeResponse(output, 200, 0, content.length);
                    return;
                }

                if (range != null) {
                    rangeRequests.add(range);
                    long start = parseRangeStart(range);
                    if (start < 0L || start >= content.length) {
                        writeResponse(output, 416, 0, 0);
                        return;
                    }
                    writeResponse(output, 206, Math.toIntExact(start), content.length - Math.toIntExact(start));
                    return;
                }

                writeResponse(output, 200, 0, content.length);
            }
        }

        /// Writes one HTTP response.
        ///
        /// @param output output stream.
        /// @param status HTTP status.
        /// @param offset content offset.
        /// @param length content length.
        /// @throws IOException when the response cannot be written.
        private void writeResponse(OutputStream output, int status, int offset, int length) throws IOException {
            String reason = switch (status) {
                case 200 -> "OK";
                case 206 -> "Partial Content";
                case 416 -> "Range Not Satisfiable";
                default -> "Bad Request";
            };
            StringBuilder headers = new StringBuilder()
                    .append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                    .append("Content-Length: ").append(length).append("\r\n")
                    .append("Connection: close\r\n");
            if (status == 206) {
                headers.append("Content-Range: bytes ")
                        .append(offset)
                        .append('-')
                        .append(offset + length - 1)
                        .append('/')
                        .append(content.length)
                        .append("\r\n");
            }
            headers.append("\r\n");
            output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
            if (length > 0) {
                output.write(content, offset, length);
            }
            output.flush();
        }

        /// Parses the starting byte of a Range header.
        ///
        /// @param range Range header value.
        /// @return start byte, or -1 when invalid.
        private static long parseRangeStart(String range) {
            if (!range.startsWith("bytes=")) {
                return -1L;
            }
            int end = range.indexOf('-', "bytes=".length());
            if (end < 0) {
                return -1L;
            }
            try {
                return Long.parseLong(range.substring("bytes=".length(), end));
            } catch (NumberFormatException e) {
                return -1L;
            }
        }

        /// Stops the fixture server.
        ///
        /// @throws IOException when the server socket cannot be closed.
        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}
