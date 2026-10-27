// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXComboBox;
import io.github.palexdev.materialfx.controls.MFXProgressBar;
import io.github.palexdev.materialfx.dialogs.MFXGenericDialogBuilder;
import io.github.palexdev.materialfx.enums.FloatMode;
import io.github.palexdev.materialfx.theming.JavaFXThemes;
import io.github.palexdev.materialfx.theming.MaterialFXStylesheets;
import io.github.palexdev.materialfx.theming.UserAgentBuilder;
import javafx.animation.Animation;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.glavo.ruyi.imager.core.AppDirectories;
import org.glavo.ruyi.imager.core.AppServices;
import org.glavo.ruyi.imager.core.OperationResult;
import org.glavo.ruyi.imager.core.ProgressEvent;
import org.glavo.ruyi.imager.core.ProgressReporter;
import org.glavo.ruyi.imager.core.StrategySupport;
import org.glavo.ruyi.imager.core.device.BlockDevice;
import org.glavo.ruyi.imager.core.device.BlockDeviceService;
import org.glavo.ruyi.imager.core.fastboot.FastbootDevice;
import org.glavo.ruyi.imager.core.fastboot.FastbootFlashResult;
import org.glavo.ruyi.imager.core.fastboot.FastbootService;
import org.glavo.ruyi.imager.core.flash.FlashRequest;
import org.glavo.ruyi.imager.core.flash.FlashService;
import org.glavo.ruyi.imager.core.flash.FlashTarget;
import org.glavo.ruyi.imager.core.image.ImageCatalog;
import org.glavo.ruyi.imager.core.image.ImageCatalogService;
import org.glavo.ruyi.imager.core.image.ImageEntry;
import org.glavo.ruyi.imager.core.image.ImageTarget;
import org.glavo.ruyi.imager.core.repo.RepositoryService;
import org.glavo.ruyi.imager.i18n.Messages;
import org.glavo.ruyi.imager.update.BuildInfo;
import org.glavo.ruyi.imager.update.UpdateChannel;
import org.glavo.ruyi.imager.update.UpdateChecker;
import org.glavo.ruyi.imager.update.UpdateSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.TestAbortedException;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES;

/// Smoke tests for JavaFX controls used by the main window selection flows.
@NotNullByDefault
public final class MainWindowJavaFxSmokeTest {
    /// Checks shared-image filtering, counts, search, and selection without changing the chosen board.
    ///
    /// @param directory isolated application state directory.
    /// @throws Exception when the JavaFX action fails.
    @Test
    public void selectsSharedImageForEveryRelatedBoard(@TempDir Path directory) throws Exception {
        runOnJavaFxThread(() -> {
            MainWindow window = new MainWindow(testServices(directory));
            try {
                ImageEntry image = new ImageEntry("test", "image-combo", "shared", "1.0.0", null,
                        "image-combo/shared(1.0.0)", "Shared image",
                        List.of(new ImageTarget("Allwinner", "awol-d1dev", "sd"),
                                new ImageTarget("Sipeed", "sipeed-licheerv", "generic"),
                                new ImageTarget("Sipeed", "sipeed-licheerv", "alternate")),
                        "dd-v1", Map.of("disk", "image.img"), List.of(), StrategySupport.SUPPORTED, List.of());
                var manufacturers = MainWindow.class.getDeclaredMethod("manufacturerOptions", List.class);
                manufacturers.setAccessible(true);
                List<?> options = (List<?>) manufacturers.invoke(null, List.of(image));
                assertEquals(2, options.size());
                for (Object option : options) {
                    var count = option.getClass().getDeclaredMethod("imageCount");
                    count.setAccessible(true);
                    assertEquals(1, count.invoke(option));
                    var boards = option.getClass().getDeclaredMethod("boardCount");
                    boards.setAccessible(true);
                    assertEquals(1, boards.invoke(option));
                }
                var boards = MainWindow.class.getDeclaredMethod("boardOptions", List.class, String.class);
                boards.setAccessible(true);
                List<?> sipeedBoards = (List<?>) boards.invoke(null, List.of(image), "Sipeed");
                assertEquals(1, sipeedBoards.size());
                Object board = sipeedBoards.getFirst();
                var boardName = board.getClass().getDeclaredMethod("name");
                boardName.setAccessible(true);
                assertEquals("sipeed-licheerv", boardName.invoke(board));
                var count = board.getClass().getDeclaredMethod("imageCount");
                count.setAccessible(true);
                assertEquals(1, count.invoke(board));
                var filter = MainWindow.class.getDeclaredMethod("filteredImages", List.class, String.class, String.class);
                filter.setAccessible(true);
                assertEquals(List.of(image), filter.invoke(null, List.of(image), "Sipeed", "sipeed-licheerv"));
                assertEquals(List.of(image), filter.invoke(null, List.of(image), "Allwinner", "awol-d1dev"));
                assertEquals(List.of(), filter.invoke(null, List.of(image), "Allwinner", "sipeed-licheerv"));
                var search = MainWindow.class.getDeclaredMethod("imageMatches", ImageEntry.class, String.class);
                search.setAccessible(true);
                assertEquals(true, search.invoke(null, image, "sipeed-licheerv"));
                assertEquals(true, search.invoke(null, image, "alternate"));
                var stateField = MainWindow.class.getDeclaredField("state");
                stateField.setAccessible(true);
                var constructor = stateField.getType().getDeclaredConstructor(
                        String.class, String.class, ImageEntry.class, Path.class, FlashTarget.class);
                constructor.setAccessible(true);
                stateField.set(window, constructor.newInstance("Sipeed", "sipeed-licheerv", null, null, null));
                var select = MainWindow.class.getDeclaredMethod("selectCatalogImage", ImageEntry.class);
                select.setAccessible(true);
                select.invoke(window, image);
                Object state = stateField.get(window);
                for (String field : List.of("manufacturerName", "boardName", "image")) {
                    var accessor = stateField.getType().getDeclaredMethod(field);
                    accessor.setAccessible(true);
                    assertEquals(switch (field) {
                        case "manufacturerName" -> "Sipeed";
                        case "boardName" -> "sipeed-licheerv";
                        default -> image;
                    }, accessor.invoke(state));
                }
                var variants = MainWindow.class.getDeclaredMethod("imageVariants", ImageEntry.class);
                variants.setAccessible(true);
                assertEquals("alternate, generic", variants.invoke(window, image));
            } finally {
                window.shutdown();
            }
            return null;
        });
    }

    /// Maximum time to wait for one JavaFX action.
    private static final long FX_TIMEOUT_SECONDS = 10L;

    /// Starts the JavaFX toolkit with the application's MaterialFX theme for smoke tests.
    @BeforeAll
    public static void startJavaFx() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "JavaFX smoke tests require a graphics environment.");
        try {
            Platform.startup(() -> {
                UserAgentBuilder.builder()
                        .themes(JavaFXThemes.MODENA)
                        .themes(MaterialFXStylesheets.forAssemble(true))
                        .setDeploy(true)
                        .setResolveAssets(true)
                        .build()
                        .setGlobal();
            });
        } catch (IllegalStateException _) {
        } catch (RuntimeException e) {
            throw new TestAbortedException("JavaFX toolkit is not available.", e);
        }
    }

    /// Constructs the GUI without installers on an unknown architecture regardless of automatic-check preference.
    ///
    /// @param automaticChecks whether automatic checks are enabled.
    /// @param directory isolated application directory without an update manifest.
    /// @throws Exception when preferences or JavaFX execution fail.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @ResourceLock(SYSTEM_PROPERTIES)
    public void opensWithoutUpdatePlatformSupport(boolean automaticChecks, @TempDir Path directory) throws Exception {
        AppServices services = testServices(directory);
        GuiPreferences preferences = new GuiPreferences(services.directories());
        preferences.writeStartupSafetyWarningAccepted();
        preferences.writeSettings(Locale.ENGLISH, automaticChecks, UpdateChannel.STABLE);
        runOnJavaFxThread(() -> {
            @Nullable String original = System.getProperty("os.arch");
            MainWindow window;
            System.setProperty("os.arch", "unsupported-arch");
            try {
                window = new MainWindow(services);
            } finally {
                if (original == null) {
                    System.clearProperty("os.arch");
                } else {
                    System.setProperty("os.arch", original);
                }
            }
            try {
                new Scene(window.root());
                assertNotNull(window.root());
                UpdateChecker checker = assertInstanceOf(UpdateChecker.class, windowField(window, "updateChecker"));
                assertNull(checker.target());
                assertNull(windowField(window, "updatePackageManager"));
                window.showStartupActions();
                assertFalse(windowBusy(window).get());
            } finally {
                window.shutdown();
            }
            return null;
        });
    }

    /// Verifies that the operating-system tree filters images and never treats a category as a selection.
    ///
    /// @throws Exception when JavaFX execution fails.
    @Test
    public void filtersAndSelectsOperatingSystemTree() throws Exception {
        runOnJavaFxThread(() -> {
            Locale originalLocale = Messages.locale();
            Messages.setLocale(Locale.ENGLISH);
            try {
                ImageEntry revyos = image(
                        "revyos-test",
                        "RevyOS image for Test Board",
                        "generic",
                        "dd-v1",
                        Map.of("disk", "revyos.raw"));
                ImageEntry ubuntu = image(
                        "ubuntu-test",
                        "Ubuntu image for Test Board",
                        "generic",
                        "dd-v1",
                        Map.of("disk", "ubuntu.raw"));

                TreeView<MainWindow.OperatingSystemTreeNode> treeView = MainWindow.selectionTreeView();
                Node content = MainWindow.operatingSystemSelectionContent("Choose OS", treeView, List.of(revyos, ubuntu), revyos);
                VBox contentBox = assertInstanceOf(VBox.class, content);
                HBox headerRow = assertInstanceOf(HBox.class, contentBox.getChildren().get(0));
                TextField searchField = assertInstanceOf(
                        TextField.class,
                        headerRow.getChildren().get(headerRow.getChildren().size() - 1));

                assertEquals(revyos, MainWindow.selectedTreeImage(treeView));

                searchField.setText("ubuntu");
                TreeItem<MainWindow.OperatingSystemTreeNode> root = treeView.getRoot();
                assertNotNull(root);
                assertEquals(1, root.getChildren().size());
                assertEquals("Ubuntu", root.getChildren().getFirst().getValue().name());

                // Filtering must not select anything by itself.
                assertNull(treeView.getSelectionModel().getSelectedItem());
                assertNull(MainWindow.selectedTreeImage(treeView));

                // Re-filtering must not bring back the image the dialog opened with.
                searchField.setText("revyos");
                assertNull(treeView.getSelectionModel().getSelectedItem());
                assertNull(MainWindow.selectedTreeImage(treeView));

                // A category is not a choice, so selecting one keeps the dialog unconfirmable.
                searchField.setText("");
                root = treeView.getRoot();
                assertNotNull(root);
                @Nullable TreeItem<MainWindow.OperatingSystemTreeNode> ubuntuCategory = null;
                for (TreeItem<MainWindow.OperatingSystemTreeNode> categoryItem : root.getChildren()) {
                    if ("Ubuntu".equals(categoryItem.getValue().name())) {
                        ubuntuCategory = categoryItem;
                        break;
                    }
                }
                assertNotNull(ubuntuCategory);

                treeView.getSelectionModel().select(ubuntuCategory);
                assertNull(MainWindow.selectedTreeImage(treeView));

                TreeItem<MainWindow.OperatingSystemTreeNode> imageItem = ubuntuCategory.getChildren().getFirst();
                treeView.getSelectionModel().select(imageItem);
                @Nullable ImageEntry selected = MainWindow.selectedTreeImage(treeView);
                assertNotNull(selected);
                assertEquals(ubuntu.atom(), selected.atom());
            } finally {
                Messages.setLocale(originalLocale);
            }
            return null;
        });
    }

    /// Verifies that dialogs map Escape to cancel and Enter to confirm.
    ///
    /// @throws Exception when JavaFX execution fails.
    @Test
    public void handlesDialogKeyboardShortcuts() throws Exception {
        runOnJavaFxThread(() -> {
            Pane content = new Pane();
            AtomicInteger cancels = new AtomicInteger();
            AtomicInteger confirms = new AtomicInteger();
            MainWindow.installDialogKeyHandling(content, cancels::incrementAndGet, confirms::incrementAndGet);

            content.fireEvent(keyPressed(KeyCode.ESCAPE));
            assertEquals(1, cancels.get());
            assertEquals(0, confirms.get());

            content.fireEvent(keyPressed(KeyCode.ENTER));
            assertEquals(1, cancels.get());
            assertEquals(1, confirms.get());

            content.fireEvent(keyPressed(KeyCode.A));
            assertEquals(1, cancels.get());
            assertEquals(1, confirms.get());
            return null;
        });
    }

    /// Verifies that the confirm state follows the current selection of a list and of a tree.
    ///
    /// @throws Exception when JavaFX execution fails.
    @Test
    public void reportsSelectionConfirmState() throws Exception {
        runOnJavaFxThread(() -> {
            ListView<String> listView = new ListView<>();
            listView.setItems(FXCollections.observableArrayList("alpha", "beta"));
            BooleanBinding listConfirm = MainWindow.selectionCanConfirm(listView);
            assertFalse(listConfirm.get());

            listView.getSelectionModel().selectFirst();
            assertTrue(listConfirm.get());

            listView.getSelectionModel().clearSelection();
            assertFalse(listConfirm.get());

            TreeView<MainWindow.OperatingSystemTreeNode> treeView = MainWindow.selectionTreeView();
            BooleanBinding treeConfirm = MainWindow.selectionCanConfirm(treeView);
            assertFalse(treeConfirm.get());

            MainWindow.operatingSystemSelectionContent("Choose OS", treeView, manyImages(), null);
            assertNull(treeView.getSelectionModel().getSelectedItem());
            assertFalse(treeConfirm.get());

            TreeItem<MainWindow.OperatingSystemTreeNode> category = treeView.getRoot().getChildren().getFirst();
            treeView.getSelectionModel().select(category);
            assertFalse(treeConfirm.get());

            treeView.getSelectionModel().select(category.getChildren().getFirst());
            assertTrue(treeConfirm.get());
            return null;
        });
    }

    /// Verifies that the partition dialog cannot be confirmed until every partition has a target.
    ///
    /// @param temporaryDirectory temporary application directory.
    /// @throws Exception when JavaFX execution fails.
    @Test
    public void requiresEveryPartitionTargetBeforeConfirming(@TempDir Path temporaryDirectory) throws Exception {
        runOnJavaFxThread(() -> {
            BlockDevice boot = blockDevice("boot", temporaryDirectory.resolve("boot.raw"));
            BlockDevice root = blockDevice("root", temporaryDirectory.resolve("root.raw"));

            MFXComboBox<BlockDevice> bootSelector = new MFXComboBox<>();
            bootSelector.getItems().setAll(List.of(boot, root));
            MFXComboBox<BlockDevice> rootSelector = new MFXComboBox<>();
            rootSelector.getItems().setAll(List.of(boot, root));

            LinkedHashMap<String, MFXComboBox<BlockDevice>> selectors = new LinkedHashMap<>();
            selectors.put("boot", bootSelector);
            selectors.put("root", rootSelector);

            BooleanBinding ready = MainWindow.partitionSelectorsReady(selectors);
            assertFalse(ready.get());

            bootSelector.setValue(boot);
            assertFalse(ready.get());

            rootSelector.setValue(root);
            assertTrue(ready.get());
            return null;
        });
    }

    /// Creates a key-pressed event for dialog shortcut tests.
    ///
    /// @param code key code.
    /// @return key event.
    private static KeyEvent keyPressed(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    /// Verifies that Enter activates the focused button instead of confirming the dialog.
    ///
    /// JavaFX maps Enter to buttons on some platforms only, so the dialog activates the focused
    /// button itself: pressing Enter on a focused cancel button must cancel rather than run the
    /// primary action, which would start writing to the device from the flash confirmation dialog.
    ///
    /// @throws Exception when JavaFX execution fails.
    @Test
    public void activatesFocusedButtonForEnter() throws Exception {
        runOnJavaFxThread(() -> {
            AtomicInteger buttonActions = new AtomicInteger();
            MFXButton cancelButton = new MFXButton("Cancel");
            cancelButton.setOnAction(_ -> buttonActions.incrementAndGet());
            Pane content = new Pane(cancelButton);
            AtomicInteger cancels = new AtomicInteger();
            AtomicInteger confirms = new AtomicInteger();
            MainWindow.installDialogKeyHandling(content, cancels::incrementAndGet, confirms::incrementAndGet);

            Scene scene = new Scene(content, 240.0, 120.0);
            cancelButton.requestFocus();
            assertSame(cancelButton, scene.getFocusOwner());

            content.fireEvent(keyPressed(KeyCode.ENTER));
            assertEquals(1, buttonActions.get());
            assertEquals(0, confirms.get());
            assertEquals(0, cancels.get());

            TextField searchField = new TextField();
            content.getChildren().add(searchField);
            searchField.requestFocus();
            assertSame(searchField, scene.getFocusOwner());

            content.fireEvent(keyPressed(KeyCode.ENTER));
            assertEquals(1, confirms.get());
            return null;
        });
    }

    /// Verifies that a selection dialog asks for a choice until a confirmable item is selected.
    ///
    /// @throws Exception when JavaFX execution fails.
    @Test
    public void showsSelectionRequiredHintUntilAnItemIsChosen() throws Exception {
        runOnJavaFxThread(() -> {
            Locale originalLocale = Messages.locale();
            Messages.setLocale(Locale.ENGLISH);
            try {
                TreeView<MainWindow.OperatingSystemTreeNode> treeView = MainWindow.selectionTreeView();
                Node content = MainWindow.operatingSystemSelectionContent(
                        "Choose OS",
                        treeView,
                        manyImages(),
                        null);
                VBox contentBox = assertInstanceOf(VBox.class, content);
                assertEquals(3, contentBox.getChildren().size());
                Label hint = assertInstanceOf(Label.class, contentBox.getChildren().get(2));
                assertTrue(hint.isVisible());

                TreeItem<MainWindow.OperatingSystemTreeNode> category = treeView.getRoot().getChildren().getFirst();
                treeView.getSelectionModel().select(category.getChildren().getFirst());
                assertFalse(hint.isVisible());

                treeView.getSelectionModel().clearSelection();
                assertTrue(hint.isVisible());

                HBox headerRow = assertInstanceOf(HBox.class, contentBox.getChildren().get(0));
                TextField searchField = assertInstanceOf(
                        TextField.class,
                        headerRow.getChildren().get(headerRow.getChildren().size() - 1));
                searchField.setText("no-such-image");
                assertFalse(hint.isVisible());
            } finally {
                Messages.setLocale(originalLocale);
            }
            return null;
        });
    }

    /// Verifies that a selection dialog reports an empty state and cannot be confirmed without items.
    ///
    /// @throws Exception when JavaFX execution fails.
    @Test
    public void reportsEmptySelectionStateWhenSearchMatchesNothing() throws Exception {
        runOnJavaFxThread(() -> {
            Locale originalLocale = Messages.locale();
            Messages.setLocale(Locale.ENGLISH);
            try {
                ImageEntry revyos = image(
                        "revyos-test",
                        "RevyOS image for Test Board",
                        "generic",
                        "dd-v1",
                        Map.of("disk", "revyos.raw"));

                TreeView<MainWindow.OperatingSystemTreeNode> treeView = MainWindow.selectionTreeView();
                Node content = MainWindow.operatingSystemSelectionContent(
                        "Choose OS",
                        treeView,
                        List.of(revyos),
                        revyos);
                VBox contentBox = assertInstanceOf(VBox.class, content);

                HBox headerRow = assertInstanceOf(HBox.class, contentBox.getChildren().get(0));
                TextField searchField = assertInstanceOf(
                        TextField.class,
                        headerRow.getChildren().get(headerRow.getChildren().size() - 1));
                StackPane selectionArea = assertInstanceOf(StackPane.class, contentBox.getChildren().get(1));
                assertEquals(2, selectionArea.getChildren().size());
                VBox emptyHint = assertInstanceOf(VBox.class, selectionArea.getChildren().get(1));

                BooleanBinding canConfirm = MainWindow.selectionCanConfirm(treeView);
                assertTrue(canConfirm.get());
                assertFalse(emptyHint.isVisible());

                searchField.setText("no-such-image");

                assertNull(MainWindow.selectedTreeImage(treeView));
                assertFalse(canConfirm.get());
                assertTrue(emptyHint.isVisible());
            } finally {
                Messages.setLocale(originalLocale);
            }
            return null;
        });
    }

    /// Verifies that multi-partition storage selectors produce a partition target map.
    ///
    /// @param temporaryDirectory temporary application directory.
    /// @throws Exception when JavaFX execution fails.
    @Test
    public void mapsPartitionStorageSelectors(@TempDir Path temporaryDirectory) throws Exception {
        runOnJavaFxThread(() -> {
            MainWindow window = new MainWindow(testServices(temporaryDirectory));
            BlockDevice boot = blockDevice("boot", temporaryDirectory.resolve("boot.raw"));
            BlockDevice root = blockDevice("root", temporaryDirectory.resolve("root.raw"));

            LinkedHashMap<String, MFXComboBox<BlockDevice>> selectors = new LinkedHashMap<>();
            selectors.put("boot", selector(List.of(boot, root), boot));
            selectors.put("root", selector(List.of(boot, root), root));

            @Nullable FlashTarget target = window.partitionTargetSelection(selectors);
            assertNotNull(target);
            assertFalse(target.blockDevices().isEmpty());
            assertEquals(boot, target.blockDevices().get("boot"));
            assertEquals(root, target.blockDevices().get("root"));
            return null;
        });
    }

    /// Verifies that the operating-system tree keeps a visible vertical scrollbar.
    ///
    /// @throws Exception when JavaFX execution fails.
    @Test
    public void showsOperatingSystemTreeScrollbar() throws Exception {
        runOnJavaFxThread(() -> {
            TreeView<MainWindow.OperatingSystemTreeNode> treeView = MainWindow.selectionTreeView();
            Node content = MainWindow.operatingSystemSelectionContent("Choose OS", treeView, manyImages(), null);
            Parent parent = assertInstanceOf(Parent.class, content);
            Scene scene = new Scene(parent);
            @Nullable String stylesheet = applicationStylesheet();
            assertNotNull(stylesheet);
            scene.getStylesheets().add(stylesheet);

            parent.applyCss();
            parent.layout();
            treeView.applyCss();
            treeView.layout();

            @Nullable ScrollBar verticalScrollBar = verticalScrollBar(treeView);
            assertNotNull(verticalScrollBar);
            assertTrue(verticalScrollBar.prefWidth(-1.0) > 0.0);
            assertTrue(verticalScrollBar.isVisible());
            return null;
        });
    }

    /// Verifies that MaterialFX selectors center their editor when no floating label is used.
    ///
    /// @throws Exception when JavaFX execution fails.
    @Test
    public void centersMaterialFxSelectorText() throws Exception {
        runOnJavaFxThread(() -> {
            MFXComboBox<String> selector = new MFXComboBox<>();
            selector.setPrefWidth(220.0);
            selector.setText("Simplified Chinese");
            VBox parent = new VBox(selector);
            Scene scene = new Scene(parent);
            @Nullable String stylesheet = applicationStylesheet();
            assertNotNull(stylesheet);
            scene.getStylesheets().add(stylesheet);

            parent.applyCss();
            parent.autosize();
            parent.layout();
            selector.applyCss();
            selector.layout();

            TextField editor = assertInstanceOf(TextField.class, selector.lookup(".text-field"));
            assertEquals(FloatMode.DISABLED, selector.getFloatMode());
            assertEquals(
                    selector.localToScene(selector.getBoundsInLocal()).getCenterY(),
                    editor.localToScene(editor.getBoundsInLocal()).getCenterY(),
                    0.5);
            return null;
        });
    }

    /// Verifies that the brand palette reaches MaterialFX controls without changing destructive action colors.
    ///
    /// @throws Exception when JavaFX execution fails.
    @Test
    public void appliesBrandColorsToControls() throws Exception {
        runOnJavaFxThread(() -> {
            MFXButton action = new MFXButton("Choose image");
            action.getStyleClass().add("step-button");
            MFXButton destructive = new MFXButton("Write image");
            destructive.getStyleClass().add("primary-action-button");
            MFXButton secondary = new MFXButton("Use local image");
            secondary.getStyleClass().add("secondary-action-button");
            MFXProgressBar progress = new MFXProgressBar(0.5);
            MFXComboBox<String> selector = new MFXComboBox<>();
            selector.setText("English");
            VBox parent = new VBox(action, secondary, destructive, progress, selector);
            parent.getStyleClass().add("app-root");
            Scene scene = new Scene(parent, 400, 240);
            @Nullable String stylesheet = applicationStylesheet();
            assertNotNull(stylesheet);
            scene.getStylesheets().add(stylesheet);
            parent.applyCss();
            parent.layout();

            Color primary = Color.web("#0a2c7e");
            LinearGradient background = assertInstanceOf(LinearGradient.class, parent.getBackground().getFills().getFirst().getFill());
            assertEquals(Color.web("#d9e0f3"), background.getStops().getFirst().getColor());
            assertEquals(Color.web("#f5f5f7"), background.getStops().getLast().getColor());
            assertEquals(primary, action.getBackground().getFills().getFirst().getFill());
            assertEquals(Color.WHITE, action.getTextFill());
            assertEquals(Color.web("#f4eedb"), secondary.getBackground().getFills().getFirst().getFill());
            assertEquals(primary, secondary.getTextFill());
            assertEquals(Color.web("#d33f49"), destructive.getBackground().getFills().getFirst().getFill());
            assertEquals(primary, assertInstanceOf(Rectangle.class, progress.lookup(".bar1")).getFill());

            progress.setProgress(-1);
            selector.pseudoClassStateChanged(PseudoClass.getPseudoClass("focus-within"), true);
            parent.applyCss();
            assertEquals(primary, assertInstanceOf(Rectangle.class, progress.lookup(".bar2")).getFill());
            assertEquals(primary, selector.getBorder().getStrokes().getFirst().getTopStroke());
            return null;
        });
    }

    /// Verifies the daily automatic metadata update boundary.
    @Test
    public void schedulesDailyMetadataUpdates() {
        Instant now = Instant.parse("2026-07-14T06:00:00Z");

        assertTrue(MainWindow.metadataUpdateDue(null, now));
        assertFalse(MainWindow.metadataUpdateDue(now.minus(Duration.ofHours(23)), now));
        assertTrue(MainWindow.metadataUpdateDue(now.minus(Duration.ofHours(24)), now));
        assertFalse(MainWindow.metadataUpdateDue(now.plus(Duration.ofHours(1)), now));
    }

    /// Verifies the one-hour startup application update check boundary.
    @Test
    public void schedulesHourlyStartupApplicationUpdateChecks() {
        Instant now = Instant.parse("2026-07-16T06:00:00Z");

        assertTrue(MainWindow.applicationUpdateCheckDue(null, now));
        assertFalse(MainWindow.applicationUpdateCheckDue(now.minus(Duration.ofHours(1)).plusNanos(1), now));
        assertTrue(MainWindow.applicationUpdateCheckDue(now.minus(Duration.ofHours(1)), now));
        assertTrue(MainWindow.applicationUpdateCheckDue(now.minus(Duration.ofHours(2)), now));
        assertFalse(MainWindow.applicationUpdateCheckDue(now.plus(Duration.ofHours(1)), now));
    }

    /// Keeps application update controls outside the scene when disabled without hiding metadata updates.
    ///
    /// @param enabled whether application update entry points are enabled in the fixture build.
    /// @param directory local manifest directory.
    /// @throws Exception when JavaFX execution fails.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void gatesApplicationUpdateSettings(boolean enabled, @TempDir Path directory) throws Exception {
        runOnJavaFxThread(() -> {
            SettingsDialog settings = new SettingsDialog(Locale.ENGLISH,
                    new BuildInfo("1.0.0", enabled), UpdateSource.of(directory.resolve("update.json")),
                    true, UpdateChannel.STABLE, new SimpleBooleanProperty());
            Scene scene = new Scene((Parent) settings.root());
            assertEquals(enabled, settings.applicationUpdateButton().getScene() == scene);
            assertSame(scene, settings.metadataUpdateButton().getScene());
            assertFalse(settings.metadataUpdateButton().isDisabled());
            settings.metadataUpdateStarted();
            settings.metadataUpdateFinished(true, "Metadata updated");
            assertFalse(settings.metadataUpdateButton().isDisabled());
            assertEquals(enabled, settings.applicationUpdateButton().getScene() == scene);
            return null;
        });
    }

    /// Prevents settings operations from replacing an active preparation task and restores controls on completion.
    ///
    /// @param successful whether the controlled preparation task succeeds.
    /// @param directory isolated application directory.
    /// @throws Exception when fixture setup, reflection, or JavaFX execution fails.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void blocksSettingsDuringPreparation(boolean successful, @TempDir Path directory) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> finished = new CompletableFuture<>();
        MainWindow window = runOnJavaFxThread(() -> new MainWindow(testServices(directory)));
        SettingsDialog settings = runOnJavaFxThread(() -> {
            SettingsDialog result = new SettingsDialog(Locale.ENGLISH, new BuildInfo("1.0.0", true),
                    UpdateSource.of(directory.resolve("update.json")), true, UpdateChannel.STABLE, windowBusy(window));
            new Scene((Parent) result.root());
            return result;
        });
        try {
            runOnJavaFxThread(() -> {
                Task<Boolean> task = new Task<>() {
                    /// Waits without touching an installer or disk device.
                    @Override
                    protected Boolean call() throws Exception {
                        if (!release.await(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            throw new IOException("Fixture timed out.");
                        }
                        if (!successful) {
                            throw new IOException("Preparation failed.");
                        }
                        return true;
                    }
                };
                var start = MainWindow.class.getDeclaredMethod("startBackgroundTask", Task.class, Consumer.class, Consumer.class);
                start.setAccessible(true);
                start.invoke(window, task, (Consumer<Boolean>) finished::complete,
                        (Consumer<@Nullable Throwable>) _ -> finished.complete(false));
                Object worker = windowField(window, "currentBackgroundThread");
                settings.applicationUpdateFinished(true, "Update available");
                assertTrue(settings.applicationUpdateButton().isDisabled());
                assertTrue(settings.metadataUpdateButton().isDisabled());
                for (String name : List.of("checkApplicationUpdate", "updateRepository")) {
                    var action = MainWindow.class.getDeclaredMethod(name, SettingsDialog.class);
                    action.setAccessible(true);
                    action.invoke(window, settings);
                    assertSame(worker, windowField(window, "currentBackgroundThread"));
                    assertTrue(windowBusy(window).get());
                }
                return null;
            });
            release.countDown();
            assertEquals(successful, finished.get(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            runOnJavaFxThread(() -> {
                assertFalse(windowBusy(window).get());
                assertFalse(settings.applicationUpdateButton().isDisabled());
                assertFalse(settings.metadataUpdateButton().isDisabled());
                assertNull(windowField(window, "currentBackgroundThread"));
                return null;
            });
        } finally {
            release.countDown();
            runOnJavaFxThread(() -> {
                settings.root().disableProperty().unbind();
                window.shutdown();
                return null;
            });
        }
    }

    /// Keeps startup and timer callbacks inactive even with saved opt-in and a configured update source.
    ///
    /// @param directory isolated application directory.
    /// @throws Exception when preferences or JavaFX execution fail.
    @Test
    @ResourceLock(SYSTEM_PROPERTIES)
    public void disablesApplicationUpdateScheduling(@TempDir Path directory) throws Exception {
        Assumptions.assumeFalse(BuildInfo.current().applicationUpdatesEnabled());
        AppServices services = testServices(directory);
        GuiPreferences preferences = new GuiPreferences(services.directories());
        preferences.writeStartupSafetyWarningAccepted();
        preferences.writeSettings(Locale.ENGLISH, true, UpdateChannel.STABLE);
        Path manifest = directory.resolve("update.json");
        Files.writeString(manifest, "{\"schemaVersion\":1,\"releases\":[]}");
        @Nullable String original = System.getProperty(UpdateChecker.SOURCE_PROPERTY);
        System.setProperty(UpdateChecker.SOURCE_PROPERTY, manifest.toString());
        try {
            runOnJavaFxThread(() -> {
                Platform.setImplicitExit(false);
                MainWindow window = new MainWindow(services);
                Stage owner = new Stage();
                try {
                    owner.setOpacity(0);
                    owner.setScene(new Scene(window.root()));
                    owner.show();
                    window.showStartupActions();
                    Timeline timer = assertInstanceOf(Timeline.class, windowField(window, "applicationUpdateTimer"));
                    assertEquals(Animation.Status.STOPPED, timer.getStatus());
                    timer.getKeyFrames().getFirst().getOnFinished().handle(new ActionEvent());
                    window.showStartupActions();
                    assertEquals(Animation.Status.STOPPED, timer.getStatus());
                    assertFalse(windowBusy(window).get());
                    assertNull(windowField(window, "currentBackgroundThread"));
                    assertNull(windowField(window, "updatePackageManager"));
                    assertNull(preferences.readApplicationUpdateCheckedAt(UpdateChannel.STABLE));
                    assertTrue(preferences.readAutomaticUpdateChecksEnabled());
                } finally {
                    window.shutdown();
                    owner.hide();
                }
                return null;
            });
        } finally {
            if (original == null) {
                System.clearProperty(UpdateChecker.SOURCE_PROPERTY);
            } else {
                System.setProperty(UpdateChecker.SOURCE_PROPERTY, original);
            }
        }
    }

    /// Exercises timer wiring, preference changes, workflow exclusion, silent failures, and shutdown.
    ///
    /// @param directory isolated application directory.
    /// @throws Exception when fixture setup or JavaFX execution fails.
    @Test
    public void checksUpdatesPeriodicallyWithoutOverlappingWork(@TempDir Path directory) throws Exception {
        Assumptions.assumeTrue(BuildInfo.current().applicationUpdatesEnabled());
        AppServices services = testServices(directory);
        GuiPreferences preferences = new GuiPreferences(services.directories());
        preferences.writeStartupSafetyWarningAccepted();
        preferences.writeSettings(Locale.ENGLISH, true, UpdateChannel.STABLE);
        Instant previousCheck = Instant.now().minusSeconds(60);
        preferences.writeApplicationUpdateCheckedAt(UpdateChannel.STABLE, previousCheck);
        Path manifest = services.directories().configDirectory().resolve("update-manifest.json");
        Files.writeString(manifest, "{\"schemaVersion\":1,\"releases\":[]}");
        MainWindow window = runOnJavaFxThread(() -> {
            Platform.setImplicitExit(false);
            MainWindow result = new MainWindow(services);
            Stage owner = new Stage();
            owner.setOpacity(0);
            owner.setScene(new Scene(result.root()));
            owner.show();
            result.showStartupActions();
            return result;
        });
        try {
            Thread worker = runOnJavaFxThread(() -> {
                Timeline timer = assertInstanceOf(Timeline.class, windowField(window, "applicationUpdateTimer"));
                assertEquals(Animation.Status.RUNNING, timer.getStatus());
                assertEquals(Animation.INDEFINITE, timer.getCycleCount());
                assertEquals(javafx.util.Duration.hours(4), timer.getKeyFrames().getFirst().getTime());
                assertFalse(windowBusy(window).get());
                window.showStartupActions();
                assertEquals(previousCheck, preferences.readApplicationUpdateCheckedAt(UpdateChannel.STABLE));

                preferences.writeSettings(Locale.ENGLISH, false, UpdateChannel.STABLE);
                timer.getKeyFrames().getFirst().getOnFinished().handle(new ActionEvent());
                assertFalse(windowBusy(window).get());
                preferences.writeSettings(Locale.ENGLISH, true, UpdateChannel.NIGHTLY);

                Stage dialog = new Stage();
                dialog.initOwner(window.root().getScene().getWindow());
                dialog.setOpacity(0);
                dialog.show();
                try {
                    timer.getKeyFrames().getFirst().getOnFinished().handle(new ActionEvent());
                    assertFalse(windowBusy(window).get());
                } finally {
                    dialog.hide();
                }

                timer.getKeyFrames().getFirst().getOnFinished().handle(new ActionEvent());
                assertTrue(windowBusy(window).get());
                Thread first = assertInstanceOf(Thread.class, windowField(window, "currentBackgroundThread"));
                timer.getKeyFrames().getFirst().getOnFinished().handle(new ActionEvent());
                assertSame(first, windowField(window, "currentBackgroundThread"));
                return first;
            });
            assertTrue(worker.join(Duration.ofSeconds(FX_TIMEOUT_SECONDS)));

            Thread failingWorker = runOnJavaFxThread(() -> {
                assertFalse(windowBusy(window).get());
                assertNotNull(preferences.readApplicationUpdateCheckedAt(UpdateChannel.NIGHTLY));
                assertEquals(previousCheck, preferences.readApplicationUpdateCheckedAt(UpdateChannel.STABLE));
                Files.writeString(manifest, "invalid JSON");
                Timeline timer = assertInstanceOf(Timeline.class, windowField(window, "applicationUpdateTimer"));
                timer.getKeyFrames().getFirst().getOnFinished().handle(new ActionEvent());
                return assertInstanceOf(Thread.class, windowField(window, "currentBackgroundThread"));
            });
            assertTrue(failingWorker.join(Duration.ofSeconds(FX_TIMEOUT_SECONDS)));
            runOnJavaFxThread(() -> {
                assertFalse(windowBusy(window).get());
                assertEquals(1, javafx.stage.Window.getWindows().size());
                Timeline timer = assertInstanceOf(Timeline.class, windowField(window, "applicationUpdateTimer"));
                assertEquals(Animation.Status.RUNNING, timer.getStatus());
                assertTrue(window.requestClose(() -> {}));
                assertEquals(Animation.Status.STOPPED, timer.getStatus());
                timer.getKeyFrames().getFirst().getOnFinished().handle(new ActionEvent());
                window.showStartupActions();
                assertFalse(windowBusy(window).get());
                assertEquals(Animation.Status.STOPPED, timer.getStatus());
                return null;
            });
        } finally {
            runOnJavaFxThread(() -> {
                window.shutdown();
                window.root().getScene().getWindow().hide();
                return null;
            });
        }
    }

    /// Reads the shared background-operation state on the JavaFX thread.
    ///
    /// @param window inspected main window.
    /// @return observable busy state.
    /// @throws ReflectiveOperationException when the field is unavailable.
    private static ReadOnlyBooleanProperty windowBusy(MainWindow window) throws ReflectiveOperationException {
        return assertInstanceOf(ReadOnlyBooleanProperty.class, windowField(window, "busy"));
    }

    /// Reads lifecycle state without exposing mutable test hooks in the window API.
    ///
    /// @param window window inspected on the JavaFX thread.
    /// @param name declared field name.
    /// @return current field value, possibly null.
    /// @throws ReflectiveOperationException when the field is unavailable.
    private static @Nullable Object windowField(MainWindow window, String name) throws ReflectiveOperationException {
        var field = MainWindow.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(window);
    }

    /// Verifies flash progress rows are known before backend progress events arrive.
    @Test
    public void createsInitialFlashProgressStages() {
        ImageEntry ddImage = image(
                "dd-test",
                "DD image for Test Board",
                "generic",
                "dd-v1",
                Map.of("disk", "dd.raw"));
        ImageEntry fastbootImage = image(
                "fastboot-test",
                "Fastboot image for Test Board",
                "generic",
                "fastboot-v1",
                Map.of("root", "root.ext4"));
        BlockDevice block = blockDevice("target", Path.of("target.raw"));
        FastbootDevice fastboot = new FastbootDevice("serial", "serial", "fastboot");

        assertEquals(
                List.of("download", "materialize", "prepare", "flash", "verify"),
                MainWindow.initialPhaseProgressStages(ddImage, FlashTarget.blockDevice(block)));
        assertEquals(
                List.of("prepare", "flash", "verify"),
                MainWindow.initialPhaseProgressStages(null, FlashTarget.blockDevice(block)));
        assertEquals(
                List.of("download", "materialize", "fastboot"),
                MainWindow.initialPhaseProgressStages(fastbootImage, FlashTarget.fastbootDevice(fastboot)));
    }

    /// Keeps a single-line footer stable across progress changes, scrolling, and flash-view transitions.
    ///
    /// @param width available window width.
    /// @param directory isolated application directory.
    /// @throws Exception when JavaFX execution or reflection fails.
    @ParameterizedTest
    @ValueSource(ints = {840, 1180, 1440})
    public void separatesProgressFromLongStatus(int width, @TempDir Path directory) throws Exception {
        runOnJavaFxThread(() -> {
            MainWindow window = new MainWindow(testServices(directory));
            try {
                Scene scene = new Scene(window.root(), width, width == 840 ? 560 : 700);
                scene.getStylesheets().add(applicationStylesheet());
                Label status = assertInstanceOf(Label.class, windowField(window, "statusLabel"));
                MFXProgressBar progress = assertInstanceOf(MFXProgressBar.class, windowField(window, "progressBar"));
                Node settings = assertInstanceOf(Node.class, windowField(window, "settingsButton"));
                window.root().applyCss();
                window.root().layout();
                Node header = window.root().lookup(".app-header");
                Node footer = window.root().lookup(".app-status-bar");
                Node flash = assertInstanceOf(Node.class, windowField(window, "flashButton"));
                Node cancel = assertInstanceOf(Node.class, windowField(window, "cancelFlashButton"));
                Node workflow = window.root().lookup(".workflow-scroll-pane");
                double headerHeight = header.getLayoutBounds().getHeight();
                double statusY = status.localToScene(status.getLayoutBounds()).getMinY();
                var workflowBounds = workflow.localToScene(workflow.getLayoutBounds());
                assertTrue(headerHeight <= 130);
                assertEquals(32, footer.getLayoutBounds().getHeight(), 0.5);
                assertEquals(scene.getHeight(), footer.localToScene(footer.getLayoutBounds()).getMaxY(), 0.5);
                assertTrue(flash.getParent().getParent().isVisible());
                assertFalse(cancel.getParent().getParent().isVisible());
                assertAbove(header, workflow);
                assertAbove(workflow, footer);
                Node localOption = window.root().lookup(".independent-option-row");
                Node firstStep = window.root().lookup(".step-row");
                assertEquals(firstStep.localToScene(firstStep.getLayoutBounds()).getMinY(),
                        localOption.localToScene(localOption.getLayoutBounds()).getMinY(), 0.5);
                Label badge = assertInstanceOf(Label.class, window.root().lookup(".step-badge"));
                assertEquals(Color.WHITE, badge.getTextFill());
                assertEquals(34, badge.getWidth(), 0.5);
                String detail = "Writing https://example.invalid/" + "long-image-directory/".repeat(20) + "root.img";
                status.setText(detail);
                progress.setVisible(true);
                progress.setProgress(0.5);
                window.root().layout();
                assertEquals(statusY, status.localToScene(status.getLayoutBounds()).getMinY(), 0.5);
                var statusBounds = status.localToScene(status.getLayoutBounds());
                var progressBounds = progress.localToScene(progress.getLayoutBounds());
                assertEquals(statusBounds.getCenterY(), progressBounds.getCenterY(), 0.5);
                assertTrue(statusBounds.getMaxX() < progressBounds.getMinX());
                var scrollPane = assertInstanceOf(javafx.scene.control.ScrollPane.class, workflow);
                scrollPane.setVvalue(1);
                window.root().layout();
                assertEquals(statusY, status.localToScene(status.getLayoutBounds()).getMinY(), 0.5);
                scrollPane.setVvalue(0);
                var flashing = MainWindow.class.getDeclaredField("flashInProgress");
                flashing.setAccessible(true);
                var refresh = MainWindow.class.getDeclaredMethod("refreshState");
                refresh.setAccessible(true);
                flashing.setBoolean(window, true);
                refresh.invoke(window);
                assertFalse(flash.getParent().getParent().isVisible());
                assertTrue(cancel.getParent().getParent().isVisible());

                var update = MainWindow.class.getDeclaredMethod("updatePhaseProgress", long.class, ProgressEvent.class);
                update.setAccessible(true);
                long generation = (Long) windowField(window, "phaseProgressGeneration");
                update.invoke(window, generation, new ProgressEvent("flash", detail, 50L, 100L));
                window.root().applyCss();
                window.root().layout();
                assertEquals(headerHeight, header.getLayoutBounds().getHeight(), 0.5);
                assertEquals(180, progress.getWidth(), 0.5);
                assertEquals(detail, status.getTooltip().getText());
                assertEquals(workflowBounds, workflow.localToScene(workflow.getLayoutBounds()));

                Label message = assertInstanceOf(Label.class, window.root().lookup(".phase-progress-message"));
                Node bar = window.root().lookup(".phase-progress-bar");
                Label percent = assertInstanceOf(Label.class,
                        window.root().lookup(".phase-progress-row .phase-progress-percent"));
                assertEquals("50%", percent.getText());
                assertAbove(bar, message);
                assertEquals(status.localToScene(status.getLayoutBounds()).getCenterY(),
                        progress.localToScene(progress.getLayoutBounds()).getCenterY(), 0.5);
                assertAbove(settings, status);
                assertTrue(message.getHeight() > percent.getHeight());
                assertTrue(message.getWidth() <= message.getParent().getLayoutBounds().getWidth());
                double barWidth = bar.getLayoutBounds().getWidth();

                update.invoke(window, generation, new ProgressEvent("flash", "Complete", 100L, 100L));
                window.root().layout();
                assertEquals("100%", percent.getText());
                assertEquals(barWidth, bar.getLayoutBounds().getWidth(), 0.5);
                progress.setProgress(-1);
                Label activityPercent = assertInstanceOf(Label.class, progress.getParent().lookup(".phase-progress-percent"));
                assertEquals("", activityPercent.getText());
                progress.setVisible(false);
                status.setText("Ready");
                flashing.setBoolean(window, false);
                refresh.invoke(window);
                window.root().layout();
                assertTrue(progress.getParent().isManaged());
                assertEquals(headerHeight, header.getLayoutBounds().getHeight(), 0.5);
                assertEquals(statusY, status.localToScene(status.getLayoutBounds()).getMinY(), 0.5);
                assertEquals(workflowBounds, workflow.localToScene(workflow.getLayoutBounds()));
            } finally {
                window.shutdown();
            }
            return null;
        });
    }

    /// Keeps update progress separate from wrapping results and hides it on both success and failure.
    ///
    /// @param applicationUpdate whether to exercise the application rather than metadata update.
    /// @param directory local manifest directory.
    /// @throws Exception when JavaFX execution fails.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void separatesSettingsProgressAndResults(boolean applicationUpdate, @TempDir Path directory) throws Exception {
        runOnJavaFxThread(() -> {
            SettingsDialog settings = new SettingsDialog(Locale.ENGLISH,
                    new BuildInfo("1.0.0", true), UpdateSource.of(directory.resolve("update.json")),
                    true, UpdateChannel.STABLE, new SimpleBooleanProperty());
            VBox root = assertInstanceOf(VBox.class, settings.root());
            Label footer = new Label("Save settings");
            VBox surface = new VBox(root, footer);
            Scene scene = new Scene(surface, 560, 1000);
            scene.getStylesheets().add(applicationStylesheet());
            Node button = applicationUpdate ? settings.applicationUpdateButton() : settings.metadataUpdateButton();
            Parent section = button.getParent().getParent();
            surface.applyCss();
            surface.layout();
            double footerY = footer.localToScene(footer.getLayoutBounds()).getMinY();
            if (applicationUpdate) {
                settings.applicationUpdateStarted();
            } else {
                settings.metadataUpdateStarted();
            }
            surface.applyCss();
            surface.layout();
            assertEquals(footerY, footer.localToScene(footer.getLayoutBounds()).getMinY(), 0.5);
            MFXProgressBar progress = assertInstanceOf(MFXProgressBar.class, section.lookup(".mfx-progress-bar"));
            Label status = assertInstanceOf(Label.class, section.lookup(".settings-update-status"));
            assertTrue(progress.isVisible());
            assertTrue(progress.isManaged());
            assertEquals(100, progress.getWidth(), 0.5);
            assertEquals(-1, progress.getProgress());
            assertAbove(button, status);
            assertAbove(progress, status);
            assertEquals(button.localToScene(button.getLayoutBounds()).getCenterY(),
                    progress.localToScene(progress.getLayoutBounds()).getCenterY(), 0.5);
            assertTrue(button.localToScene(button.getLayoutBounds()).getMaxX()
                    < progress.localToScene(progress.getLayoutBounds()).getMinX());

            String failure = "Failed to update https://example.invalid/" + "long-repository-name/".repeat(30);
            if (applicationUpdate) {
                settings.applicationUpdateFailed(failure);
            } else {
                settings.metadataUpdateFinished(false, failure);
            }
            surface.layout();
            assertFalse(progress.isVisible());
            assertTrue(progress.isManaged());
            assertFalse(status.isVisible());
            assertFalse(status.isManaged());
            Label error = assertInstanceOf(Label.class, section.lookup(".settings-update-error"));
            assertEquals(failure, error.getText());
            assertTrue(error.isVisible());
            assertTrue(error.getHeight() >= error.prefHeight(error.getWidth()) - 0.5);
            assertAbove(error, footer);
            if (applicationUpdate) {
                settings.applicationUpdateStarted();
                settings.applicationUpdateFinished(false, "Up to date");
            } else {
                settings.metadataUpdateStarted();
                settings.metadataUpdateFinished(true, "Metadata updated");
            }
            assertFalse(progress.isVisible());
            assertTrue(status.isVisible());
            assertFalse(error.isVisible());
            assertFalse(error.isManaged());
            assertEquals("", error.getText());
            assertFalse(status.getStyleClass().contains("settings-update-error"));
            assertFalse(button.isDisabled());
            surface.layout();
            assertEquals(footerY, footer.localToScene(footer.getLayoutBounds()).getMinY(), 0.5);
            root.disableProperty().unbind();
            return null;
        });
    }

    /// Checks the settings form inside its actual dialog shell in both supported languages.
    ///
    /// @param languageTag GUI language tag.
    /// @param directory isolated update source directory.
    /// @throws Exception when JavaFX execution fails.
    @ParameterizedTest
    @ValueSource(strings = {"en", "zh-CN"})
    public void alignsCompactSettingsDialog(String languageTag, @TempDir Path directory) throws Exception {
        runOnJavaFxThread(() -> {
            Locale original = Messages.locale();
            Messages.setLocale(Locale.forLanguageTag(languageTag));
            SettingsDialog settings = new SettingsDialog(Messages.locale(), new BuildInfo("1.0.0", false),
                    UpdateSource.of(directory.resolve("update.json")), true, UpdateChannel.STABLE,
                    new SimpleBooleanProperty());
            try {
                MFXButton cancel = new MFXButton(Messages.get("gui.dialog.cancel"));
                cancel.getStyleClass().addAll("dialog-button", "dialog-secondary-button");
                MFXButton save = new MFXButton(Messages.get("gui.settings.save"));
                save.getStyleClass().addAll("dialog-button", "dialog-primary-button");
                var dialog = MFXGenericDialogBuilder.build()
                        .setHeaderText(Messages.get("gui.settings.title"))
                        .setContent(settings.root())
                        .setShowClose(false).setShowMinimize(false).setShowAlwaysOnTop(false)
                        .addStyleClasses("material-dialog", "material-settings-dialog")
                        .addActions(cancel, save).get();
                Scene scene = new Scene(dialog);
                scene.getStylesheets().add(applicationStylesheet());
                dialog.applyCss();
                dialog.autosize();
                dialog.layout();
                Node language = dialog.lookup(".settings-language-selector");
                Node version = dialog.lookup(".settings-value");
                Node update = settings.metadataUpdateButton();
                double controlX = language.localToScene(language.getLayoutBounds()).getMinX();
                assertEquals(controlX, version.localToScene(version.getLayoutBounds()).getMinX(), 0.5);
                assertEquals(controlX, update.localToScene(update.getLayoutBounds()).getMinX(), 0.5);
                Node header = dialog.lookup(".header-label");
                assertTrue(language.localToScene(language.getLayoutBounds()).getMinY()
                        - header.localToScene(header.getLayoutBounds()).getMaxY() >= 12);
                assertTrue(dialog.getWidth() <= 500);
                assertTrue(dialog.getHeight() < 320);
                double initialHeight = dialog.getHeight();
                double initialWidth = dialog.getWidth();
                settings.metadataUpdateStarted();
                dialog.autosize();
                dialog.layout();
                assertEquals(initialHeight, dialog.getHeight(), 0.5);
                settings.metadataUpdateFinished(false,
                        "Repository update failed: https://example.invalid/" + "long-repository-path/".repeat(12));
                dialog.autosize();
                dialog.layout();
                Node error = dialog.lookup(".settings-update-error");
                assertAbove(error, save);
                assertAbove(error, cancel);
                assertEquals(initialWidth, dialog.getWidth(), 0.5);
            } finally {
                settings.root().disableProperty().unbind();
                Messages.setLocale(original);
            }
            return null;
        });
    }

    /// Asserts that two laid-out nodes have disjoint vertical bounds in scene coordinates.
    ///
    /// @param upper node expected above the other node.
    /// @param lower node expected below the other node.
    private static void assertAbove(Node upper, Node lower) {
        assertTrue(upper.localToScene(upper.getLayoutBounds()).getMaxY()
                <= lower.localToScene(lower.getLayoutBounds()).getMinY() + 0.5);
    }

    /// Runs an action on the JavaFX application thread.
    ///
    /// @param action action to run.
    /// @param <T> action result type.
    /// @return action result.
    /// @throws Exception when the action fails.
    private static <T> T runOnJavaFxThread(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return action.call();
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                future.complete(action.call());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return future.get(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        } catch (TimeoutException e) {
            throw new AssertionError("Timed out waiting for JavaFX action.", e);
        }
    }

    /// Creates a block-device selector with a selected value.
    ///
    /// @param devices available devices.
    /// @param selected selected device.
    /// @return configured selector.
    private static MFXComboBox<BlockDevice> selector(
            @Unmodifiable List<BlockDevice> devices,
            BlockDevice selected) {
        MFXComboBox<BlockDevice> selector = new MFXComboBox<>();
        selector.getItems().setAll(devices);
        selector.setValue(selected);
        return selector;
    }

    /// Finds the vertical scrollbar inside a tree view.
    ///
    /// @param treeView tree view.
    /// @return vertical scrollbar, or null when no scrollbar exists.
    private static @Nullable ScrollBar verticalScrollBar(TreeView<?> treeView) {
        Set<Node> scrollBars = treeView.lookupAll(".scroll-bar");
        for (Node node : scrollBars) {
            if (node instanceof ScrollBar scrollBar && scrollBar.getOrientation() == Orientation.VERTICAL) {
                return scrollBar;
            }
        }
        return null;
    }

    /// Returns the application stylesheet for GUI smoke tests.
    ///
    /// @return stylesheet URL, or null when missing.
    private static @Nullable String applicationStylesheet() {
        @Nullable URL stylesheet = MainWindow.class.getResource("/org/glavo/ruyi/imager/gui/application.css");
        return stylesheet == null ? null : stylesheet.toExternalForm();
    }

    /// Creates enough image entries to require scrolling in the operating-system tree.
    ///
    /// @return image list.
    private static @Unmodifiable List<ImageEntry> manyImages() {
        ArrayList<ImageEntry> images = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            images.add(image(
                    "ubuntu-test-" + i,
                    "Ubuntu image " + i + " for Test Board",
                    "generic",
                    "dd-v1",
                    Map.of("disk", "ubuntu-" + i + ".raw")));
        }
        return List.copyOf(images);
    }

    /// Creates an image entry for GUI smoke tests.
    ///
    /// @param name package name.
    /// @param displayName image display name.
    /// @param variant image variant.
    /// @param strategy provision strategy.
    /// @param partitionMap image partition map.
    /// @return image entry.
    private static ImageEntry image(
            String name,
            String displayName,
            String variant,
            String strategy,
            @Unmodifiable Map<String, String> partitionMap) {
        return new ImageEntry(
                "ruyisdk",
                name,
                "test-board",
                "1.0.0",
                null,
                name + "/test-board(1.0.0)",
                displayName,
                "Test Manufacturer",
                "test-board",
                variant,
                strategy,
                partitionMap,
                List.of(),
                StrategySupport.SUPPORTED);
    }

    /// Creates block-device metadata for GUI smoke tests.
    ///
    /// @param id device id.
    /// @param path device path.
    /// @return block-device metadata.
    private static BlockDevice blockDevice(String id, Path path) {
        return new BlockDevice(
                id,
                "Test " + id,
                path,
                1024L,
                true,
                false,
                false,
                false,
                "Test",
                "file");
    }

    /// Creates a minimal service graph for constructing the main window.
    ///
    /// @param directory temporary application directory.
    /// @return test service graph.
    private static AppServices testServices(Path directory) {
        AppDirectories directories = new AppDirectories(directory.resolve("config"), directory.resolve("cache"));
        return new AppServices(
                directories,
                new EmptyRepositoryService(),
                new EmptyImageCatalogService(),
                new EmptyBlockDeviceService(),
                new EmptyFastbootService(),
                new NoOpFlashService());
    }

    /// Repository service that reports a no-op success.
    @NotNullByDefault
    private static final class EmptyRepositoryService implements RepositoryService {
        /// Reports that local metadata is already available.
        ///
        /// @return true.
        @Override
        public boolean hasLocalMetadata() {
            return true;
        }

        /// Reports a successful no-op update.
        ///
        /// @param reporter progress reporter.
        /// @return successful operation result.
        @Override
        public OperationResult update(ProgressReporter reporter) {
            return OperationResult.success("No update.");
        }
    }

    /// Image catalog service with no images.
    @NotNullByDefault
    private static final class EmptyImageCatalogService implements ImageCatalogService {
        /// Lists no images.
        ///
        /// @return empty image catalog.
        @Override
        public ImageCatalog listImages() {
            return new ImageCatalog(List.of());
        }

        /// Refuses image downloads.
        ///
        /// @param image image to download.
        /// @param reporter progress reporter.
        /// @return never returns.
        /// @throws IOException always thrown.
        @Override
        public Path downloadImage(ImageEntry image, ProgressReporter reporter) throws IOException {
            throw new IOException("No images are available.");
        }
    }

    /// Block-device service with no devices.
    @NotNullByDefault
    private static final class EmptyBlockDeviceService implements BlockDeviceService {
        /// Lists no block devices.
        ///
        /// @return empty device list.
        @Override
        public @Unmodifiable List<BlockDevice> listDevices() {
            return List.of();
        }
    }

    /// Fastboot service with no devices.
    @NotNullByDefault
    private static final class EmptyFastbootService implements FastbootService {
        /// Lists no fastboot devices.
        ///
        /// @return empty fastboot device list.
        @Override
        public @Unmodifiable List<FastbootDevice> listDevices() {
            return List.of();
        }

        /// Refuses fastboot flashing.
        ///
        /// @param strategy Ruyi provision strategy.
        /// @param partitions materialized partition images keyed by target partition name.
        /// @param device target fastboot device.
        /// @param reporter progress reporter.
        /// @return never returns.
        /// @throws IOException always thrown.
        @Override
        public FastbootFlashResult flash(
                String strategy,
                @Unmodifiable Map<String, Path> partitions,
                FastbootDevice device,
                ProgressReporter reporter) throws IOException {
            throw new IOException("Fastboot is not available.");
        }
    }

    /// Flash service that does nothing.
    @NotNullByDefault
    private static final class NoOpFlashService implements FlashService {
        /// Reports a successful no-op flash.
        ///
        /// @param request flash request.
        /// @param reporter progress reporter.
        /// @return successful operation result.
        @Override
        public OperationResult flash(FlashRequest request, ProgressReporter reporter) {
            return OperationResult.success("No flash.");
        }
    }
}
