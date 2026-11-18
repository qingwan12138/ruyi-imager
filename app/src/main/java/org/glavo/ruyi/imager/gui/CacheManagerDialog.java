// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import io.github.palexdev.materialfx.controls.MFXButton;
import javafx.beans.property.SimpleLongProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.glavo.ruyi.imager.core.image.ImageCacheEntry;
import org.glavo.ruyi.imager.core.image.ImageCacheUsage;
import org.glavo.ruyi.imager.i18n.Messages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Provides the selectable cache contents shown in the cache manager dialog.
///
/// The tree groups cached image versions by category and image name. Every node is a check box item,
/// so selecting a category selects all of its versions, and the footer tracks how much the current
/// selection would remove.
@NotNullByDefault
final class CacheManagerDialog {
    /// Cache tree root.
    private final VBox root;

    /// Tree of cached image versions grouped by category and image name.
    private final TreeView<CacheNode> tree = new TreeView<>();

    /// Placeholder shown when nothing is cached.
    private final Label emptyStateLabel = new Label(Messages.get("gui.cache.emptyState"));

    /// Summary of the cache size and the current selection.
    private final Label summaryLabel = new Label();

    /// Check box that selects the leftover partial files.
    private final CheckBox partialsCheckBox = new CheckBox();

    /// Button that selects every entry.
    private final Button selectAllButton = new MFXButton(Messages.get("gui.cache.selectAll"));

    /// Button that inverts the selection.
    private final Button invertButton = new MFXButton(Messages.get("gui.cache.invertSelection"));

    /// Button that cleans the selection.
    private final Button cleanButton = new MFXButton(Messages.get("gui.cache.cleanSelected"));

    /// Button that closes the dialog.
    private final Button closeButton = new MFXButton(Messages.get("gui.dialog.cancel"));

    /// Leaves of the tree, in display order.
    private final List<CheckBoxTreeItem<CacheNode>> leaves = new ArrayList<>();

    /// Cache usage shown by this dialog.
    private final ImageCacheUsage usage;

    /// Size of the current selection.
    private final SimpleLongProperty selectedBytes = new SimpleLongProperty();

    /// Creates the cache manager content.
    ///
    /// @param usage measured cache usage.
    CacheManagerDialog(ImageCacheUsage usage) {
        this.usage = usage;
        this.tree.setShowRoot(false);
        this.tree.setMinHeight(320);
        this.tree.setPrefHeight(360);
        this.tree.getStyleClass().add("cache-tree-view");
        this.tree.setRoot(buildTree());
        this.tree.setCellFactory(CheckBoxTreeCell.forTreeView());

        for (Button button : List.of(selectAllButton, invertButton, cleanButton, closeButton)) {
            button.getStyleClass().add("settings-action-button");
        }
        selectAllButton.setOnAction(_ -> selectAll(true));
        invertButton.setOnAction(_ -> invertSelection());

        partialsCheckBox.getStyleClass().add("settings-checkbox");
        partialsCheckBox.setText(Messages.get(
                "gui.cache.partials",
                usage.partialFiles(),
                MainWindow.sizeLabel(usage.partialBytes())));
        partialsCheckBox.setSelected(false);
        partialsCheckBox.setVisible(usage.partialFiles() > 0);
        partialsCheckBox.setManaged(usage.partialFiles() > 0);
        partialsCheckBox.selectedProperty().addListener((_, _, _) -> updateSummary());

        HBox header = new HBox(12, summaryLabel, spacer(), selectAllButton, invertButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("cache-header");
        HBox actions = new HBox(12, closeButton, cleanButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("cache-actions");
        emptyStateLabel.getStyleClass().add("cache-empty");
        boolean empty = usage.entries().isEmpty() && usage.partialFiles() == 0;
        emptyStateLabel.setVisible(empty);
        emptyStateLabel.setManaged(empty);
        tree.setVisible(!empty);
        tree.setManaged(!empty);
        root = new VBox(12, header, emptyStateLabel, tree, partialsCheckBox, actions);
        root.getStyleClass().add("cache-content");
        updateSummary();
    }

    /// Returns the dialog content.
    ///
    /// @return dialog content node.
    Node root() {
        return root;
    }

    /// Returns the button that closes the dialog.
    ///
    /// @return close button.
    Button closeButton() {
        return closeButton;
    }

    /// Returns the button that cleans the selection.
    ///
    /// @return clean button.
    Button cleanButton() {
        return cleanButton;
    }

    /// Returns the cached versions selected in the tree.
    ///
    /// @return selected entries.
    List<ImageCacheEntry> selectedEntries() {
        ArrayList<ImageCacheEntry> entries = new ArrayList<>();
        for (CheckBoxTreeItem<CacheNode> leaf : leaves) {
            if (leaf.isSelected()) {
                entries.add(leaf.getValue().entry());
            }
        }
        return List.copyOf(entries);
    }

    /// Returns whether leftover partial files are selected.
    ///
    /// @return whether partial files are selected.
    boolean cleansPartials() {
        return partialsCheckBox.isVisible() && partialsCheckBox.isSelected();
    }

    /// Returns the number of bytes the current selection would remove.
    ///
    /// @return selected size in bytes.
    long selectedBytes() {
        return selectedBytes.get();
    }

    /// Builds the cache tree.
    ///
    /// @return tree root.
    private TreeItem<CacheNode> buildTree() {
        CheckBoxTreeItem<CacheNode> rootItem =
                new CheckBoxTreeItem<>(new CacheNode(null, null, null, null, ""));
        Map<String, List<ImageCacheEntry>> byCategory = new LinkedHashMap<>();
        for (ImageCacheEntry entry : usage.entries()) {
            byCategory.computeIfAbsent(entry.category(), _ -> new ArrayList<>()).add(entry);
        }
        for (Map.Entry<String, List<ImageCacheEntry>> categoryEntry : byCategory.entrySet()) {
            String category = categoryEntry.getKey();
            List<ImageCacheEntry> categoryEntries = categoryEntry.getValue();
            long categoryBytes = categoryEntries.stream().mapToLong(ImageCacheEntry::totalBytes).sum();
            CheckBoxTreeItem<CacheNode> categoryItem = new CheckBoxTreeItem<>(
                    new CacheNode(category, null, null, null, categoryLabel(category, categoryBytes)),
                    null,
                    false);
            categoryItem.setExpanded(true);

            Map<String, List<ImageCacheEntry>> byName = new LinkedHashMap<>();
            for (ImageCacheEntry entry : categoryEntries) {
                byName.computeIfAbsent(entry.name(), _ -> new ArrayList<>()).add(entry);
            }
            for (Map.Entry<String, List<ImageCacheEntry>> nameEntry : byName.entrySet()) {
                List<ImageCacheEntry> versions = nameEntry.getValue();
                versions.sort(Comparator.comparingLong(ImageCacheEntry::lastModifiedMillis).reversed());
                long nameBytes = versions.stream().mapToLong(ImageCacheEntry::totalBytes).sum();
                String nameLabel = Messages.get(
                        "gui.cache.name",
                        nameEntry.getKey(),
                        MainWindow.sizeLabel(nameBytes));
                CheckBoxTreeItem<CacheNode> nameItem = new CheckBoxTreeItem<>(
                        new CacheNode(category, nameEntry.getKey(), null, null, nameLabel));
                nameItem.setExpanded(true);
                for (ImageCacheEntry entry : versions) {
                    String leafLabel = Messages.get(
                            "gui.cache.version",
                            entry.version(),
                            MainWindow.sizeLabel(entry.totalBytes()),
                            Messages.get(entry.partial()
                                    ? "gui.cache.state.partial"
                                    : "gui.cache.state.cached"));
                    CheckBoxTreeItem<CacheNode> leaf = new CheckBoxTreeItem<>(
                            new CacheNode(category, entry.name(), entry.version(), entry, leafLabel));
                    leaf.selectedProperty().addListener((_, _, _) -> updateSummary());
                    nameItem.getChildren().add(leaf);
                    leaves.add(leaf);
                }
                nameItem.selectedProperty().addListener((_, _, _) -> updateSummary());
                categoryItem.getChildren().add(nameItem);
            }
            categoryItem.selectedProperty().addListener((_, _, _) -> updateSummary());
            rootItem.getChildren().add(categoryItem);
        }
        return rootItem;
    }
    /// Returns the localized label of one cache category.
    ///
    /// @param category category identifier.
    /// @param bytes category size in bytes.
    /// @return display text.
    private static String categoryLabel(String category, long bytes) {
        String name = switch (category) {
            case "image-combo" -> Messages.get("gui.cache.category.imageCombo");
            case "board-image" -> Messages.get("gui.cache.category.boardImage");
            default -> category;
        };
        return Messages.get("gui.cache.categoryLabel", name, MainWindow.sizeLabel(bytes));
    }

    /// Selects or clears every leaf.
    ///
    /// @param selected whether leaves are selected.
    void selectAll(boolean selected) {
        for (CheckBoxTreeItem<CacheNode> leaf : leaves) {
            leaf.setSelected(selected);
        }
        updateSummary();
    }

    /// Inverts the selection of every leaf.
    void invertSelection() {
        for (CheckBoxTreeItem<CacheNode> leaf : leaves) {
            leaf.setSelected(!leaf.isSelected());
        }
        updateSummary();
    }

    /// Recomputes the summary and the clean button state.
    private void updateSummary() {
        long entryBytes = 0L;
        long entryPartialBytes = 0L;
        for (CheckBoxTreeItem<CacheNode> leaf : leaves) {
            if (leaf.isSelected()) {
                ImageCacheEntry entry = leaf.getValue().entry();
                entryBytes += entry.totalBytes();
                entryPartialBytes += entry.partialBytes();
            }
        }
        long partialBytes = cleansPartials()
                ? Math.max(0L, usage.partialBytes() - entryPartialBytes)
                : 0L;
        long total = entryBytes + partialBytes;
        selectedBytes.set(total);
        summaryLabel.setText(Messages.get(
                "gui.cache.summary",
                MainWindow.sizeLabel(usage.totalBytes()),
                MainWindow.sizeLabel(total)));
        cleanButton.setText(total <= 0L
                ? Messages.get("gui.cache.cleanSelected")
                : Messages.get("gui.cache.cleanSelectedBytes", MainWindow.sizeLabel(total)));
        cleanButton.setDisable(total <= 0L);
    }

    /// Creates a horizontal spacer.
    ///
    /// @return spacer node.
    private static Node spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /// One node of the cache tree.
    ///
    /// @param category image category, or null for the tree root.
    /// @param name image name, or null for a category node.
    /// @param version image version, or null for a category or name node.
    /// @param entry cached image version, or null for a category or name node.
    record CacheNode(
            @Nullable String category,
            @Nullable String name,
            @Nullable String version,
            @Nullable ImageCacheEntry entry,
            String label) {
        /// Returns the display text shown by the tree.
        ///
        /// @return display text.
        @Override
        public String toString() {
            return label;
        }
    }
}
