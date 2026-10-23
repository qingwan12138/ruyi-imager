// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.core.image;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.repo.RuyiRepositoryStore;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Tests for Ruyi image catalog parsing.
@NotNullByDefault
public final class RuyiImageCatalogServiceTest {
    /// Verifies that provisionable manifests are listed as image entries.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void listsProvisionableImagesFromConfiguredLocalRepo(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepository(repoDirectory);

        RuyiRepositoryStore repositoryStore = new RuyiRepositoryStore(
                new AppDirectories(configDirectory, cacheDirectory));
        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                repositoryStore);

        ImageCatalog catalog = service.listImages();

        assertEquals(1, catalog.images().size());
        ImageEntry image = catalog.images().getFirst();
        assertEquals("ruyisdk", image.repoId());
        assertEquals("board-image", image.category());
        assertEquals("revyos-milkv-meles", image.name());
        assertEquals("1.2.3", image.version());
        assertEquals("revyos-meles", image.slug());
        assertEquals("board-image/revyos-milkv-meles(1.2.3)", image.atom());
        assertEquals("RevyOS image for Milk-V Meles", image.displayName());
        assertEquals(List.of(new ImageTarget("Milk-V", "milkv-meles", "generic")), image.targets());
        assertEquals("dd-v1", image.strategy());
        assertEquals(StrategySupport.SUPPORTED, image.support());
        assertEquals("image.raw", image.partitionMap().get("disk"));

        RuyiDistfile distfile = image.distfiles().getFirst();
        assertEquals("image.raw", distfile.name());
        assertEquals(1024L, distfile.sizeBytes());
        assertEquals("0123456789abcdef", distfile.checksums().get("sha256"));
        assertEquals(List.of(URI.create("https://dist.example/dist/image.raw")), distfile.sourceUris());
        assertEquals(List.of("images"), distfile.prefixesToUnpack());
    }

    /// Verifies image metadata is cached until explicitly invalidated.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void cachesImageCatalogUntilInvalidated(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepositoryConfig(repoDirectory);
        writeImageManifest(repoDirectory, "1.2.3", "Original image", "original-image", "image.raw");

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));

        ImageCatalog firstCatalog = service.listImages();
        writeImageManifest(repoDirectory, "1.2.3", "Updated image", "updated-image", "image.raw");
        ImageCatalog cachedCatalog = service.listImages();

        assertEquals(firstCatalog, cachedCatalog);
        assertEquals("Original image", cachedCatalog.images().getFirst().displayName());

        service.invalidateCache();
        ImageCatalog refreshedCatalog = service.listImages();

        assertEquals("Updated image", refreshedCatalog.images().getFirst().displayName());
    }

    /// Verifies fetch_restriction declarations are rendered from repository messages.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void parsesFetchRestrictionInstructions(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepositoryConfig(repoDirectory);
        writeFetchRestrictedImageManifest(repoDirectory);

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));

        RuyiDistfile distfile = service.listImages().images().getFirst().distfiles().getFirst();

        assertTrue(distfile.fetchRestricted());
        @Nullable RuyiFetchRestriction fetchRestriction = distfile.fetchRestriction();
        assertNotNull(fetchRestriction);
        String instructions = fetchRestriction.render(Path.of("downloads").resolve("manual.raw"), Locale.ENGLISH);
        assertTrue(instructions.contains("manual.raw"), instructions);
        assertTrue(instructions.contains("manual"), instructions);
    }

    /// Checks message-file I/O per catalog rebuild without relying on timing thresholds.
    ///
    /// @param directory isolated test directory.
    /// @throws Exception when fixture metadata or the recording cannot be read or written.
    @Test
    public void readsRepositoryMessagesOncePerCatalogLoad(@TempDir Path directory) throws Exception {
        Path config = Files.createDirectories(directory.resolve("config"));
        Path repo = directory.resolve("repo");
        writeConfig(config, repo);
        writeRepositoryConfig(repo);
        writeFetchRestrictedImageManifest(repo);
        Path packageDirectory = repo.resolve("packages/board-image/manual-milkv-meles");
        Files.copy(packageDirectory.resolve("1.2.3.toml"), packageDirectory.resolve("1.2.4.toml"));
        AppDirectories directories = new AppDirectories(config, directory.resolve("cache"));
        RuyiImageCatalogService service = new RuyiImageCatalogService(directories, new RuyiRepositoryStore(directories));
        Path recording = directory.resolve("catalog.jfr");
        Path messages = repo.resolve("messages.toml").toAbsolutePath();
        long messageBytes = Files.size(messages);
        try (Recording events = new Recording()) {
            events.enable("jdk.FileRead").withThreshold(Duration.ZERO).withoutStackTrace();
            events.start();
            ImageCatalog catalog = service.listImages();
            assertEquals(2, catalog.images().size());
            assertSame(catalog, service.listImages());
            service.invalidateCache();
            assertEquals(2, service.listImages().images().size());
            events.stop();
            events.dump(recording);
        }
        long bytesRead = RecordingFile.readAllEvents(recording).stream()
                .filter(event -> event.getEventType().getName().equals("jdk.FileRead"))
                .filter(event -> Path.of(event.getString("path")).toAbsolutePath().equals(messages))
                .mapToLong(event -> event.getLong("bytesRead"))
                .sum();
        assertEquals(2 * messageBytes, bytesRead, "Each catalog rebuild must read the message file only once");
    }

    /// Refreshes repository message snapshots only when the catalog is invalidated.
    ///
    /// @param initialState initial message-file state.
    /// @param directory isolated test directory.
    /// @throws Exception when fixture metadata cannot be written or parsed.
    @ParameterizedTest
    @ValueSource(strings = {"valid", "missing", "malformed", "unsupported"})
    public void refreshesRepositoryMessagesWithCatalog(String initialState, @TempDir Path directory) throws Exception {
        Path config = Files.createDirectories(directory.resolve("config"));
        Path repo = directory.resolve("repo");
        writeConfig(config, repo);
        writeRepositoryConfig(repo);
        writeFetchRestrictedImageManifest(repo);
        Path packageDirectory = repo.resolve("packages/board-image/manual-milkv-meles");
        Files.copy(packageDirectory.resolve("1.2.3.toml"), packageDirectory.resolve("1.2.4.toml"));
        Path messages = repo.resolve("messages.toml");
        switch (initialState) {
            case "missing" -> Files.delete(messages);
            case "malformed" -> Files.writeString(messages, "invalid = [");
            case "unsupported" -> Files.writeString(messages, "ruyi-repo-messages = 'v2'");
            default -> { }
        }
        AppDirectories directories = new AppDirectories(config, directory.resolve("cache"));
        RuyiImageCatalogService service = new RuyiImageCatalogService(directories, new RuyiRepositoryStore(directories));
        ImageCatalog first = service.listImages();
        assertEquals(2, first.images().size());
        for (ImageEntry image : first.images()) {
            RuyiDistfile distfile = image.distfiles().getFirst();
            assertTrue(distfile.fetchRestricted());
            if (initialState.equals("valid")) {
                assertNotNull(distfile.fetchRestriction());
                assertEquals("Fetch this image manually to {{ dest_path }}.",
                        distfile.fetchRestriction().templates().get("en_US"));
            } else {
                assertNull(distfile.fetchRestriction());
            }
        }
        Files.writeString(messages, """
                ruyi-repo-messages = "v1"
                ["manual/image"]
                en_US = "Updated instructions for {{ dest_path }}"
                """);
        assertSame(first, service.listImages());
        service.invalidateCache();
        ImageCatalog refreshed = service.listImages();
        assertEquals(2, refreshed.images().size());
        for (ImageEntry image : refreshed.images()) {
            @Nullable RuyiFetchRestriction restriction = image.distfiles().getFirst().fetchRestriction();
            assertNotNull(restriction);
            assertEquals("Updated instructions for {{ dest_path }}", restriction.templates().get("en_US"));
            assertTrue(restriction.render(Path.of("manual.raw"), Locale.ENGLISH).contains("manual.raw"));
        }
        if (initialState.equals("valid")) {
            assertEquals("Fetch this image manually to {{ dest_path }}.",
                    first.images().getFirst().distfiles().getFirst().fetchRestriction().templates().get("en_US"));
        }
    }

    /// Keeps identical message identifiers scoped to their own repository.
    ///
    /// @param directory isolated test directory.
    /// @throws Exception when fixture metadata cannot be written or parsed.
    @Test
    public void isolatesRepositoryMessageTemplates(@TempDir Path directory) throws Exception {
        Path config = Files.createDirectories(directory.resolve("config"));
        for (String id : List.of("ruyisdk", "overlay")) {
            Path repo = directory.resolve(id);
            writeRepositoryConfig(repo);
            writeFetchRestrictedImageManifest(repo);
            Files.move(repo.resolve("packages/board-image/manual-milkv-meles"),
                    repo.resolve("packages/board-image/" + id + "-milkv-meles"));
            Files.writeString(repo.resolve("messages.toml"), """
                    ruyi-repo-messages = "v1"
                    ["manual/image"]
                    en_US = "%s instructions"
                    """.formatted(id));
        }
        Files.writeString(config.resolve("config.toml"), """
                [repo]
                local = "%s"
                [[repos]]
                id = "overlay"
                local = "%s"
                """.formatted(directory.resolve("ruyisdk").toString().replace('\\', '/'),
                directory.resolve("overlay").toString().replace('\\', '/')));
        AppDirectories directories = new AppDirectories(config, directory.resolve("cache"));
        RuyiImageCatalogService service = new RuyiImageCatalogService(directories, new RuyiRepositoryStore(directories));
        ImageCatalog catalog = service.listImages();
        assertEquals(2, catalog.images().size());
        for (ImageEntry image : catalog.images()) {
            @Nullable RuyiFetchRestriction restriction = image.distfiles().getFirst().fetchRestriction();
            assertNotNull(restriction);
            assertEquals(image.repoId() + " instructions", restriction.templates().get("en_US"));
        }
    }

    /// Verifies Ruyi atom matching and latest stable version selection.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void resolvesRuyiAtomsAgainstLatestStableVersion(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepositoryConfig(repoDirectory);
        writeImageManifest(repoDirectory, "1.0.0", "Older RevyOS image", "revyos-meles-old", "old.raw");
        writeImageManifest(repoDirectory, "1.1.0", "Stable RevyOS image", "revyos-meles-stable", "stable.raw");
        writeImageManifest(repoDirectory, "1.2.0-rc.1", "Release candidate RevyOS image", "revyos-meles-rc", "rc.raw");

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));

        assertEquals("1.1.0", service.findImage("board-image/revyos-milkv-meles").version());
        assertEquals("1.1.0", service.findImage("revyos-milkv-meles").version());
        assertEquals("1.1.0", service.findImage("name:board-image/revyos-milkv-meles").version());
        assertEquals("1.0.0", service.findImage("board-image/revyos-milkv-meles(1.0.0)").version());
        assertEquals("1.0.0", service.findImage("board-image/revyos-milkv-meles(>=1.0.0,<1.1.0)").version());
        assertEquals("1.1.0", service.findImage("slug:revyos-meles-stable").version());
        assertNull(service.findImage("slug:missing"));
    }

    /// Verifies known fastboot provision strategies are exposed as supported.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void classifiesFastbootImagesAsSupported(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepositoryConfig(repoDirectory);
        writeImageManifest(
                repoDirectory,
                "1.2.3",
                "Fastboot image for Milk-V Meles",
                "fastboot-meles",
                "image.raw",
                "fastboot-v1");

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));

        ImageEntry image = service.listImages().images().getFirst();

        assertEquals("fastboot-v1", image.strategy());
        assertEquals(StrategySupport.SUPPORTED, image.support());
    }

    /// Verifies combo strategies are preserved and mixed DD/fastboot combinations are unsupported.
    ///
    /// @param systemStrategy strategy of the system image component.
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @ParameterizedTest
    @ValueSource(strings = {"fastboot-v1", "dd-v1", "unknown-v1"})
    public void listsImageCombosWithComponentStrategies(
            String systemStrategy, @TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepositoryConfig(repoDirectory);
        writeDeviceEntity(repoDirectory, "sipeed-lpi4a");

        Path systemPackage = repoDirectory.resolve("packages").resolve("board-image").resolve("revyos-sipeed-lpi4a");
        Files.createDirectories(systemPackage);
        Files.writeString(
                systemPackage.resolve("0.20251226.0.toml"),
                """
                        format = "v1"
                        kind = ["blob", "provisionable"]

                        [metadata]
                        desc = "RevyOS image for Sipeed LicheePi 4A"
                        vendor = { name = "PLCT", eula = "" }

                        [[distfiles]]
                        name = "boot.ext4"
                        size = 1024

                        [[distfiles]]
                        name = "root.ext4"
                        size = 2048

                        [provisionable]
                        strategy = "%s"

                        [provisionable.partition_map]
                        boot = "boot.ext4"
                        root = "root.ext4"
                        """.formatted(systemStrategy));

        Path ubootPackage = repoDirectory.resolve("packages").resolve("board-image").resolve("uboot-revyos-sipeed-lpi4a-8g");
        Files.createDirectories(ubootPackage);
        Files.writeString(
                ubootPackage.resolve("0.20251226.0.toml"),
                """
                        format = "v1"
                        kind = ["blob", "provisionable"]

                        [metadata]
                        desc = "U-Boot image for Sipeed LicheePi 4A"
                        vendor = { name = "PLCT", eula = "" }

                        [[distfiles]]
                        name = "u-boot-with-spl.bin"
                        size = 512

                        [provisionable]
                        strategy = "fastboot-v1(lpi4a-uboot)"

                        [provisionable.partition_map]
                        uboot = "u-boot-with-spl.bin"
                        """);

        Path comboDirectory = repoDirectory.resolve("entities").resolve("image-combo");
        Files.createDirectories(comboDirectory);
        Files.writeString(
                comboDirectory.resolve("revyos-sipeed-lpi4a@8g.toml"),
                """
                        ruyi-entity = "v0"

                        related = [
                          "device-variant:sipeed-lpi4a@8g",
                        ]

                        [image-combo]
                        display_name = "RevyOS for Sipeed LicheePi 4A (8G RAM)"
                        package_atoms = [
                          "board-image/revyos-sipeed-lpi4a",
                          "board-image/uboot-revyos-sipeed-lpi4a-8g"
                        ]
                        """);

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));

        @Nullable ImageEntry combo = service.findImage("image-combo/revyos-sipeed-lpi4a@8g");

        assertNotNull(combo);
        assertEquals("image-combo", combo.category());
        assertEquals("revyos-sipeed-lpi4a@8g", combo.name());
        assertEquals("0.20251226.0", combo.version());
        assertEquals("image-combo/revyos-sipeed-lpi4a@8g(0.20251226.0)", combo.atom());
        assertEquals("RevyOS for Sipeed LicheePi 4A (8G RAM)", combo.displayName());
        assertEquals(List.of(new ImageTarget("Sipeed", "sipeed-lpi4a", "8g")), combo.targets());
        assertEquals("fastboot-v1(lpi4a-uboot)", combo.strategy());
        assertEquals(systemStrategy.equals("fastboot-v1") ? StrategySupport.SUPPORTED : StrategySupport.UNKNOWN,
                combo.support());
        assertEquals(List.of("uboot", "boot", "root"), List.copyOf(combo.partitionMap().keySet()));
        assertEquals(2, combo.components().size());
        assertEquals("fastboot-v1(lpi4a-uboot)", combo.components().get(0).strategy());
        assertEquals(List.of("uboot"), List.copyOf(combo.components().get(0).partitionMap().keySet()));
        assertEquals(systemStrategy, combo.components().get(1).strategy());
        assertEquals(List.of("boot", "root"), List.copyOf(combo.components().get(1).partitionMap().keySet()));
        assertEquals(
                List.of("u-boot-with-spl.bin", "boot.ext4", "root.ext4"),
                combo.distfiles().stream().map(RuyiDistfile::name).toList());
    }

    /// Verifies image combos reject same-name distfiles with different declarations.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void skipsImageComboWithConflictingDistfileNames(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepositoryConfig(repoDirectory);

        Path firstPackage = repoDirectory.resolve("packages").resolve("board-image").resolve("first-fastboot-image");
        Files.createDirectories(firstPackage);
        Files.writeString(
                firstPackage.resolve("1.0.0.toml"),
                """
                        format = "v1"
                        kind = ["blob", "provisionable"]

                        [metadata]
                        desc = "First fastboot image"
                        vendor = { name = "PLCT", eula = "" }

                        [[distfiles]]
                        name = "payload.img"
                        size = 1024

                        [provisionable]
                        strategy = "fastboot-v1"

                        [provisionable.partition_map]
                        boot = "payload.img"
                        """);

        Path secondPackage = repoDirectory.resolve("packages").resolve("board-image").resolve("second-fastboot-image");
        Files.createDirectories(secondPackage);
        Files.writeString(
                secondPackage.resolve("1.0.0.toml"),
                """
                        format = "v1"
                        kind = ["blob", "provisionable"]

                        [metadata]
                        desc = "Second fastboot image"
                        vendor = { name = "PLCT", eula = "" }

                        [[distfiles]]
                        name = "payload.img"
                        size = 2048

                        [provisionable]
                        strategy = "fastboot-v1"

                        [provisionable.partition_map]
                        root = "payload.img"
                        """);

        Path comboDirectory = repoDirectory.resolve("entities").resolve("image-combo");
        Files.createDirectories(comboDirectory);
        Files.writeString(
                comboDirectory.resolve("conflicting-fastboot.toml"),
                """
                        ruyi-entity = "v0"

                        [image-combo]
                        display_name = "Conflicting fastboot image"
                        package_atoms = [
                          "board-image/first-fastboot-image",
                          "board-image/second-fastboot-image"
                        ]
                        """);

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));

        assertNull(service.findImage("image-combo/conflicting-fastboot"));
    }

    /// Verifies SpacemiT Bianbu provision strategies are exposed as supported.
    ///
    /// @param strategy SpacemiT provision strategy.
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @ParameterizedTest
    @ValueSource(strings = {"spacemit-k1-v1", "spacemit-k3-v1"})
    public void classifiesSpacemitImagesAsSupported(String strategy, @TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepositoryConfig(repoDirectory);
        writeImageManifest(
                repoDirectory,
                "1.2.3",
                "Bianbu image for SpacemiT",
                "bianbu",
                "image.zip",
                strategy);

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));

        ImageEntry image = service.listImages().images().getFirst();

        assertEquals(strategy, image.strategy());
        assertEquals(StrategySupport.SUPPORTED, image.support());
    }

    /// Verifies board-image manufacturer and variant are derived from the device id.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void derivesBoardManufacturerAndVariantFromDeviceId(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepositoryConfig(repoDirectory);
        writeImageManifest(
                repoDirectory,
                "uboot-revyos-milkv-meles-4g",
                "1.2.3",
                "U-Boot image for Milk-V Meles",
                "uboot-meles-4g",
                "uboot.bin",
                "fastboot-v1",
                "PLCT");

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));

        @Nullable ImageEntry image = service.findImage("uboot-revyos-milkv-meles-4g");

        assertNotNull(image);
        assertEquals(List.of(new ImageTarget("Milk-V", "milkv-meles", "4g")), image.targets());
    }

    /// Verifies board-image board names use device entities before suffix heuristics.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void derivesBoardFromDeviceEntityBeforePackageSuffix(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepositoryConfig(repoDirectory);
        writeDeviceEntity(repoDirectory, "sipeed-lpi4a");
        writeImageManifest(
                repoDirectory,
                "revyos-sipeed-lpi4a-headless",
                "1.2.3",
                "Headless RevyOS image for Sipeed LicheePi 4A",
                "revyos-lpi4a-headless",
                "image.raw",
                "dd-v1",
                "PLCT");

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));

        @Nullable ImageEntry image = service.findImage("revyos-sipeed-lpi4a-headless");

        assertNotNull(image);
        assertEquals(List.of(new ImageTarget("Sipeed", "sipeed-lpi4a", "headless")), image.targets());
    }


    /// Verifies lightweight distfile cache status inspection.
    ///
    /// @param temporaryDirectory temporary test directory.
    /// @throws Exception when test fixture files cannot be created or read.
    @Test
    public void reportsImageCacheStatus(@TempDir Path temporaryDirectory) throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        Path repoDirectory = temporaryDirectory.resolve("repo");
        Files.createDirectories(configDirectory);
        writeConfig(configDirectory, repoDirectory);
        writeRepository(repoDirectory);

        RuyiImageCatalogService service = new RuyiImageCatalogService(
                new AppDirectories(configDirectory, cacheDirectory),
                new RuyiRepositoryStore(new AppDirectories(configDirectory, cacheDirectory)));
        ImageEntry image = service.listImages().images().getFirst();

        ImageCacheStatus emptyStatus = service.cacheStatus(image);
        assertEquals(ImageCacheStatus.State.EMPTY, emptyStatus.state());
        assertEquals(0, emptyStatus.cachedDistfiles());
        assertEquals(1, emptyStatus.totalDistfiles());
        assertEquals(0L, emptyStatus.cachedBytes());
        assertEquals(1024L, emptyStatus.totalBytes());

        Path downloadDirectory = cacheDirectory
                .resolve("downloads")
                .resolve("ruyisdk")
                .resolve("board-image")
                .resolve("revyos-milkv-meles")
                .resolve("1.2.3");
        Files.createDirectories(downloadDirectory);
        Files.write(downloadDirectory.resolve("image.raw.part"), new byte[512]);

        ImageCacheStatus partialStatus = service.cacheStatus(image);
        assertEquals(ImageCacheStatus.State.PARTIAL, partialStatus.state());
        assertEquals(0, partialStatus.cachedDistfiles());
        assertEquals(512L, partialStatus.cachedBytes());

        Files.write(downloadDirectory.resolve("image.raw"), new byte[1024]);

        ImageCacheStatus completeStatus = service.cacheStatus(image);
        assertEquals(ImageCacheStatus.State.COMPLETE, completeStatus.state());
        assertEquals(1, completeStatus.cachedDistfiles());
        assertEquals(1024L, completeStatus.cachedBytes());
    }

    /// Retains every related board and variant without duplicating combo identities.
    ///
    /// @param reverse whether to reverse the relation order.
    /// @param directory isolated test directory.
    /// @throws Exception when fixture metadata cannot be written or parsed.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void retainsAllComboTargets(boolean reverse, @TempDir Path directory) throws Exception {
        Path config = Files.createDirectories(directory.resolve("config"));
        Path repo = directory.resolve("repo");
        writeConfig(config, repo);
        writeRepositoryConfig(repo);
        writeDeviceEntity(repo, "awol-d1dev");
        writeDeviceEntity(repo, "sipeed-licheerv");
        List<String> related = new ArrayList<>(List.of(
                "device-variant:awol-d1dev@generic", "device-variant:sipeed-licheerv@generic",
                "device:sipeed-licheerv", "device-variant:sipeed-licheerv@alternate",
                "device-variant:sipeed-licheerv@generic", "cpu:allwinner-d1",
                "device-variant:invalid@", "device:"));
        if (reverse) {
            Collections.reverse(related);
        }
        Path combos = Files.createDirectories(repo.resolve("entities/image-combo"));
        for (String edition : List.of("base", "xfce")) {
            String name = "oerv-awol-d1-" + edition;
            writeImageManifest(repo, name, "1.0.0", "openEuler D1", name, edition + ".img", "dd-v1", "PLCT");
            Files.writeString(combos.resolve(name + ".toml"), """
                    related = ["%s"]
                    [image-combo]
                    display_name = "openEuler D1"
                    package_atoms = ["board-image/%s"]
                    """.formatted(String.join("\", \"", related), name));
        }
        AppDirectories directories = new AppDirectories(config, directory.resolve("cache"));
        RuyiImageCatalogService service = new RuyiImageCatalogService(directories, new RuyiRepositoryStore(directories));
        ImageCatalog catalog = service.listImages();
        assertEquals(4, catalog.images().size());
        for (String edition : List.of("base", "xfce")) {
            String atom = "image-combo/oerv-awol-d1-" + edition;
            @Nullable ImageEntry combo = service.findImage(atom);
            assertNotNull(combo);
            assertEquals(3, combo.targets().size());
            assertEquals(Set.of(new ImageTarget("Allwinner", "awol-d1dev", "generic"),
                    new ImageTarget("Sipeed", "sipeed-licheerv", "generic"),
                    new ImageTarget("Sipeed", "sipeed-licheerv", "alternate")), Set.copyOf(combo.targets()));
            assertEquals(1, catalog.images().stream().filter(image -> image.atom().equals(combo.atom())).count());
            assertSame(combo, service.findImage(combo.atom()));
            @Nullable ImageEntry source = service.findImage("board-image/oerv-awol-d1-" + edition);
            assertNotNull(source);
            assertEquals(source.partitionMap(), combo.partitionMap());
            assertEquals(source.components(), combo.components());
            assertEquals(source.distfiles(), combo.distfiles());
        }
    }

    /// Preserves package-derived targets when a combo has no usable device relations.
    ///
    /// @param relation TOML declaration for absent, empty, or unrelated associations.
    /// @param directory isolated test directory.
    /// @throws Exception when fixture metadata cannot be written or parsed.
    @ParameterizedTest
    @ValueSource(strings = {"", "related = []", "related = ['cpu:allwinner-d1', 'device-variant:invalid@', 'device:']"})
    public void fallsBackWhenComboHasNoTargets(String relation, @TempDir Path directory) throws Exception {
        Path config = Files.createDirectories(directory.resolve("config"));
        Path repo = directory.resolve("repo");
        writeConfig(config, repo);
        writeRepository(repo);
        Path combos = Files.createDirectories(repo.resolve("entities/image-combo"));
        Files.writeString(combos.resolve("meles.toml"), relation + """

                [image-combo]
                package_atoms = ["board-image/revyos-milkv-meles"]
                """);
        AppDirectories directories = new AppDirectories(config, directory.resolve("cache"));
        RuyiImageCatalogService service = new RuyiImageCatalogService(directories, new RuyiRepositoryStore(directories));
        @Nullable ImageEntry combo = service.findImage("image-combo/meles");
        assertNotNull(combo);
        assertEquals(List.of(new ImageTarget("Milk-V", "milkv-meles", "generic")), combo.targets());
    }

    /// Writes the application config fixture.
    ///
    /// @param configDirectory config directory.
    /// @param repoDirectory local repository directory.
    /// @throws Exception when the config cannot be written.
    private static void writeConfig(Path configDirectory, Path repoDirectory) throws Exception {
        String repoPath = repoDirectory.toString().replace('\\', '/');
        Files.writeString(configDirectory.resolve("config.toml"), """
                [repo]
                local = "%s"
                """.formatted(repoPath));
    }

    /// Writes a minimal Ruyi repository fixture.
    ///
    /// @param repoDirectory repository directory.
    /// @throws Exception when fixture files cannot be written.
    private static void writeRepository(Path repoDirectory) throws Exception {
        writeRepositoryConfig(repoDirectory);
        writeImageManifest(repoDirectory, "1.2.3", "RevyOS image for Milk-V Meles", "revyos-meles", "image.raw");
    }

    /// Writes repository config.
    ///
    /// @param repoDirectory repository directory.
    /// @throws Exception when fixture files cannot be written.
    private static void writeRepositoryConfig(Path repoDirectory) throws Exception {
        Files.createDirectories(repoDirectory.resolve("packages").resolve("board-image").resolve("revyos-milkv-meles"));
        Files.writeString(repoDirectory.resolve("config.toml"), """
                ruyi-repo = "v1"

                [[mirrors]]
                id = "ruyi-dist"
                urls = ["https://dist.example/dist/"]
                """);
        Files.writeString(repoDirectory.resolve("messages.toml"), """
                ruyi-repo-messages = "v1"

                ["manual/image"]
                en_US = "Fetch this image manually to {{ dest_path }}."
                """);
    }

    /// Writes one device entity fixture.
    ///
    /// @param repoDirectory repository directory.
    /// @param deviceId device id.
    /// @throws Exception when fixture files cannot be written.
    private static void writeDeviceEntity(Path repoDirectory, String deviceId) throws Exception {
        Path deviceDirectory = repoDirectory.resolve("entities").resolve("device");
        Files.createDirectories(deviceDirectory);
        Files.writeString(
                deviceDirectory.resolve(deviceId + ".toml"),
                """
                        ruyi-entity = "v0"

                        [device]
                        id = "%s"
                        display_name = "%s"
                        """.formatted(deviceId, deviceId));
    }

    /// Writes a fetch-restricted image manifest fixture.
    ///
    /// @param repoDirectory repository directory.
    /// @throws Exception when fixture files cannot be written.
    private static void writeFetchRestrictedImageManifest(Path repoDirectory) throws Exception {
        Path packageDirectory = repoDirectory.resolve("packages").resolve("board-image").resolve("manual-milkv-meles");
        Files.createDirectories(packageDirectory);
        Files.writeString(
                packageDirectory.resolve("1.2.3.toml"),
                """
                        format = "v1"
                        kind = ["blob", "provisionable"]

                        [metadata]
                        desc = "Manual image for Milk-V Meles"
                        slug = "manual-meles"
                        vendor = { name = "PLCT", eula = "" }

                        [[distfiles]]
                        name = "manual.raw"
                        size = 1024
                        restrict = ["fetch"]

                        [distfiles.fetch_restriction]
                        msgid = "manual/image"

                        [distfiles.checksums]
                        sha256 = "0123456789abcdef"

                        [blob]
                        distfiles = ["manual.raw"]

                        [provisionable]
                        strategy = "dd-v1"

                        [provisionable.partition_map]
                        disk = "manual.raw"
                        """);
    }

    /// Writes one image manifest fixture.
    ///
    /// @param repoDirectory repository directory.
    /// @param version package version.
    /// @param description package description.
    /// @param slug package slug.
    /// @param distfileName distfile name.
    /// @throws Exception when fixture files cannot be written.
    private static void writeImageManifest(
            Path repoDirectory,
            String version,
            String description,
            String slug,
            String distfileName) throws Exception {
        writeImageManifest(repoDirectory, version, description, slug, distfileName, "dd-v1");
    }

    /// Writes one image manifest fixture.
    ///
    /// @param repoDirectory repository directory.
    /// @param version package version.
    /// @param description package description.
    /// @param slug package slug.
    /// @param distfileName distfile name.
    /// @param strategy provision strategy.
    /// @throws Exception when fixture files cannot be written.
    private static void writeImageManifest(
            Path repoDirectory,
            String version,
            String description,
            String slug,
            String distfileName,
            String strategy) throws Exception {
        writeImageManifest(
                repoDirectory,
                "revyos-milkv-meles",
                version,
                description,
                slug,
                distfileName,
                strategy,
                "FreeBSD");
    }

    /// Writes one image manifest fixture.
    ///
    /// @param repoDirectory repository directory.
    /// @param packageName package name.
    /// @param version package version.
    /// @param description package description.
    /// @param slug package slug.
    /// @param distfileName distfile name.
    /// @param strategy provision strategy.
    /// @param packageVendor package vendor name.
    /// @throws Exception when fixture files cannot be written.
    private static void writeImageManifest(
            Path repoDirectory,
            String packageName,
            String version,
            String description,
            String slug,
            String distfileName,
            String strategy,
            String packageVendor) throws Exception {
        Path packageDirectory = repoDirectory.resolve("packages").resolve("board-image").resolve(packageName);
        Files.createDirectories(packageDirectory);
        Files.writeString(
                packageDirectory.resolve(version + ".toml"),
                """
                        format = "v1"
                        kind = ["blob", "provisionable"]

                        [metadata]
                        desc = "%s"
                        slug = "%s"
                        vendor = { name = "%s", eula = "" }

                        [[distfiles]]
                        name = "%s"
                        size = 1024
                        prefixes_to_unpack = ["images"]

                        [distfiles.checksums]
                        sha256 = "0123456789abcdef"

                        [blob]
                        distfiles = ["%s"]

                        [provisionable]
                        strategy = "%s"

                        [provisionable.partition_map]
                        disk = "%s"
                        """.formatted(
                        description,
                        slug,
                        packageVendor,
                        distfileName,
                        distfileName,
                        strategy,
                        distfileName));
    }
}
