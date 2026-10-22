// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.gui;

import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXComboBox;
import io.github.palexdev.materialfx.controls.MFXProgressBar;
import javafx.application.Platform;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.glavo.ruyi.imager.i18n.Messages;
import org.glavo.ruyi.imager.update.BuildInfo;
import org.glavo.ruyi.imager.update.UpdateChannel;
import org.glavo.ruyi.imager.update.UpdateSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Locale;

/// Provides the editable controls shown in the GUI settings dialog.
@NotNullByDefault
final class SettingsDialog {
    /// Language choices available in the GUI.
    private static final @Unmodifiable List<LanguageOption> LANGUAGE_OPTIONS = List.of(
            new LanguageOption("gui.language.english", Locale.ENGLISH),
            new LanguageOption("gui.language.simplifiedChinese", Locale.SIMPLIFIED_CHINESE));

    /// Root settings content.
    private final VBox root;

    /// Language selector.
    private final MFXComboBox<LanguageOption> languageSelector;

    /// Automatic application update check option.
    private final CheckBox automaticUpdateChecks;

    /// Application update channel selector.
    private final MFXComboBox<UpdateChannel> updateChannelSelector;

    /// Application update check button.
    private final Button applicationUpdateButton;

    /// Application update status.
    private final Label applicationUpdateStatus;

    /// Failure details for the most recent application update check.
    private final Label applicationUpdateError = createStatusLabel();

    /// Indeterminate progress shown while checking application updates.
    private final MFXProgressBar applicationUpdateProgress = createProgressBar();

    /// Manual metadata update button.
    private final Button metadataUpdateButton;

    /// Manual metadata update status.
    private final Label metadataUpdateStatus;

    /// Failure details for the most recent metadata update.
    private final Label metadataUpdateError = createStatusLabel();

    /// Indeterminate progress shown while updating repository metadata.
    private final MFXProgressBar metadataUpdateProgress = createProgressBar();

    /// Creates settings controls with current values.
    ///
    /// @param locale       selected GUI locale.
    /// @param buildInfo    running application build.
    /// @param updateSource local or HTTPS update manifest location.
    /// @param automaticUpdateChecks whether startup and periodic update checks are enabled.
    /// @param updateChannel         selected update channel.
    /// @param busy shared background-operation state; the caller must unbind the root's disable property on disposal.
    SettingsDialog(
            Locale locale,
            BuildInfo buildInfo,
            UpdateSource updateSource,
            boolean automaticUpdateChecks,
            UpdateChannel updateChannel,
            ObservableBooleanValue busy) {
        this.languageSelector = createLanguageSelector(locale);
        this.automaticUpdateChecks = new CheckBox(Messages.get("gui.settings.automaticUpdateChecks"));
        this.automaticUpdateChecks.setSelected(automaticUpdateChecks);
        this.automaticUpdateChecks.getStyleClass().add("settings-checkbox");
        this.updateChannelSelector = createUpdateChannelSelector(updateChannel);
        this.applicationUpdateButton = new MFXButton(Messages.get("gui.settings.checkForUpdates"));
        this.applicationUpdateButton.getStyleClass().add("settings-action-button");
        this.applicationUpdateStatus = createStatusLabel();
        this.metadataUpdateButton = new MFXButton(Messages.get("gui.settings.updateMetadata"));
        this.metadataUpdateButton.getStyleClass().add("settings-action-button");
        this.metadataUpdateStatus = createStatusLabel();

        GridPane generalGrid = createSettingsGrid();
        generalGrid.add(settingsLabel("gui.language"), 0, 0);
        generalGrid.add(languageSelector, 1, 0);
        generalGrid.add(settingsLabel("gui.settings.version"), 0, 1);
        Label versionValue = new Label(buildInfo.version());
        versionValue.getStyleClass().add("settings-value");
        versionValue.setMinWidth(0);
        versionValue.setWrapText(true);
        generalGrid.add(versionValue, 1, 1);
        Label metadataLabel = settingsLabel("gui.settings.metadata");
        GridPane.setValignment(metadataLabel, VPos.TOP);
        GridPane.setMargin(metadataLabel, new Insets(8, 0, 0, 0));
        generalGrid.add(metadataLabel, 0, 2);
        generalGrid.add(createOperationContent(metadataUpdateButton, metadataUpdateProgress,
                metadataUpdateStatus, metadataUpdateError), 1, 2);

        Label applicationUpdateTitle = new Label(Messages.get("gui.settings.applicationUpdates"));
        applicationUpdateTitle.getStyleClass().add("settings-section-title");
        Label updateSourceLabel = new Label(Messages.get("gui.settings.updateSource", updateSource));
        updateSourceLabel.setWrapText(true);
        updateSourceLabel.getStyleClass().add("settings-source");
        GridPane updateGrid = createSettingsGrid();
        updateGrid.add(settingsLabel("gui.settings.updateChannel"), 0, 0);
        updateGrid.add(updateChannelSelector, 1, 0);
        updateGrid.add(this.automaticUpdateChecks, 1, 1);
        updateGrid.add(createOperationContent(applicationUpdateButton, applicationUpdateProgress,
                applicationUpdateStatus, applicationUpdateError), 1, 2);
        VBox applicationUpdateSection = new VBox(
                applicationUpdateTitle,
                updateSourceLabel,
                updateGrid);
        applicationUpdateSection.getStyleClass().add("settings-section");

        this.root = new VBox(generalGrid);
        if (buildInfo.applicationUpdatesEnabled()) {
            this.root.getChildren().add(applicationUpdateSection);
        }
        this.root.getStyleClass().add("settings-content");
        this.root.disableProperty().bind(busy);
    }

    /// Returns the settings content node.
    ///
    /// @return settings content node.
    Node root() {
        return root;
    }

    /// Returns the selected GUI locale.
    ///
    /// @return selected locale.
    Locale selectedLocale() {
        return languageSelector.getValue().locale();
    }

    /// Returns whether startup application update checks are enabled.
    ///
    /// @return whether automatic update checks are enabled.
    boolean automaticUpdateChecksEnabled() {
        return automaticUpdateChecks.isSelected();
    }

    /// Returns the selected application update channel.
    ///
    /// @return selected update channel.
    UpdateChannel selectedUpdateChannel() {
        return updateChannelSelector.getValue();
    }

    /// Returns the manual metadata update button.
    ///
    /// @return metadata update button.
    Button metadataUpdateButton() {
        return metadataUpdateButton;
    }

    /// Returns the application update check button.
    ///
    /// @return application update check button.
    Button applicationUpdateButton() {
        return applicationUpdateButton;
    }

    /// Marks the application update check as active.
    void applicationUpdateStarted() {
        setOperationButtonsDisabled(true);
        applicationUpdateProgress.setVisible(true);
        hideStatus(applicationUpdateError);
        setStatus(
                applicationUpdateStatus,
                Messages.get("gui.progress.checkingForUpdates"),
                "settings-update-active");
    }

    /// Shows the result of an application update check.
    ///
    /// @param updateAvailable whether a newer build is available.
    /// @param message         update check result message.
    void applicationUpdateFinished(boolean updateAvailable, String message) {
        setOperationButtonsDisabled(false);
        applicationUpdateProgress.setVisible(false);
        hideStatus(applicationUpdateError);
        setStatus(
                applicationUpdateStatus,
                message,
                updateAvailable ? "settings-update-available" : "settings-update-success");
    }

    /// Shows an application update check failure.
    ///
    /// @param message failure message.
    void applicationUpdateFailed(String message) {
        setOperationButtonsDisabled(false);
        applicationUpdateProgress.setVisible(false);
        hideStatus(applicationUpdateStatus);
        setStatus(applicationUpdateError, message, "settings-update-error");
    }

    /// Marks the manual metadata update as active.
    void metadataUpdateStarted() {
        setOperationButtonsDisabled(true);
        metadataUpdateProgress.setVisible(true);
        hideStatus(metadataUpdateError);
        setStatus(metadataUpdateStatus, Messages.get("gui.progress.updatingMetadata"), "settings-update-active");
    }

    /// Shows the result of a manual metadata update.
    ///
    /// @param successful whether the update succeeded.
    /// @param message    update result message.
    void metadataUpdateFinished(boolean successful, String message) {
        setOperationButtonsDisabled(false);
        metadataUpdateProgress.setVisible(false);
        if (successful) {
            hideStatus(metadataUpdateError);
            setStatus(metadataUpdateStatus, message, "settings-update-success");
        } else {
            hideStatus(metadataUpdateStatus);
            setStatus(metadataUpdateError, message, "settings-update-error");
        }
    }

    /// Creates a settings row label.
    ///
    /// @param messageKey localized message key.
    /// @return styled settings label.
    private static Label settingsLabel(String messageKey) {
        Label label = new Label(Messages.get(messageKey));
        label.getStyleClass().add("settings-label");
        return label;
    }

    /// Creates a form grid with aligned labels and a resizable control column.
    ///
    /// @return two-column settings grid.
    private static GridPane createSettingsGrid() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("settings-grid");
        grid.setAlignment(Pos.CENTER_LEFT);
        ColumnConstraints labelColumn = new ColumnConstraints(90);
        ColumnConstraints valueColumn = new ColumnConstraints();
        valueColumn.setMinWidth(0);
        valueColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, valueColumn);
        return grid;
    }

    /// Groups an operation button and progress indicator above a reserved status line.
    ///
    /// @param button operation action.
    /// @param progress progress indicator whose space remains reserved while hidden.
    /// @param status normal operation status.
    /// @param error failure details shown instead of the normal status.
    /// @return compact operation controls and feedback.
    private static VBox createOperationContent(Button button, MFXProgressBar progress, Label status, Label error) {
        button.setMinWidth(Region.USE_PREF_SIZE);
        HBox actions = new HBox(12, button, progress);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(6, actions, createStatusArea(status, error));
        content.setMinWidth(0);
        content.getStyleClass().add("settings-operation");
        return content;
    }

    /// Creates an initially hidden operation status label.
    ///
    /// @return operation status label.
    private static Label createStatusLabel() {
        Label status = new Label();
        status.setWrapText(true);
        status.setMinWidth(0);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.setVisible(false);
        status.setManaged(false);
        status.getStyleClass().add("settings-update-status");
        return status;
    }

    /// Creates a compact progress bar whose layout space is retained while hidden.
    ///
    /// @return initially hidden indeterminate progress bar.
    private static MFXProgressBar createProgressBar() {
        MFXProgressBar progress = new MFXProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        progress.setMinWidth(80);
        progress.setPrefWidth(100);
        progress.setMaxWidth(100);
        progress.setVisible(false);
        return progress;
    }

    /// Reserves one status line while allowing longer results and errors to wrap.
    ///
    /// @param status normal operation status.
    /// @param error failure details, shown instead of the normal status.
    /// @return shared status area.
    private static StackPane createStatusArea(Label status, Label error) {
        StackPane area = new StackPane(status, error);
        area.setAlignment(Pos.CENTER_LEFT);
        area.setMinHeight(24);
        return area;
    }

    /// Clears a status label and removes its layout space.
    ///
    /// @param status label to clear and hide.
    private static void hideStatus(Label status) {
        status.setText("");
        status.setVisible(false);
        status.setManaged(false);
    }

    /// Enables or disables settings operations together.
    ///
    /// @param disabled whether operations are disabled.
    private void setOperationButtonsDisabled(boolean disabled) {
        applicationUpdateButton.setDisable(disabled);
        metadataUpdateButton.setDisable(disabled);
        automaticUpdateChecks.setDisable(disabled);
        updateChannelSelector.setDisable(disabled);
    }

    /// Updates a visible operation status.
    ///
    /// @param status     status label.
    /// @param message    status text.
    /// @param styleClass status style class.
    private static void setStatus(Label status, String message, String styleClass) {
        status.setText(message);
        status.getStyleClass().removeAll(
                "settings-update-active",
                "settings-update-available",
                "settings-update-success",
                "settings-update-error");
        status.getStyleClass().add(styleClass);
        status.setVisible(true);
        status.setManaged(true);
        requestContainingStageResize(status);
    }

    /// Resizes the containing stage after dynamic content has changed its preferred size.
    ///
    /// @param node changed dialog content.
    private static void requestContainingStageResize(Node node) {
        Platform.runLater(() -> {
            @Nullable Scene scene = node.getScene();
            if (scene != null && scene.getWindow() instanceof Stage stage && stage.isShowing()) {
                stage.sizeToScene();
            }
        });
    }

    /// Creates the language selector.
    ///
    /// @param locale initially selected locale.
    /// @return language selector.
    private static MFXComboBox<LanguageOption> createLanguageSelector(Locale locale) {
        MFXComboBox<LanguageOption> selector = new MFXComboBox<>(
                FXCollections.observableArrayList(LANGUAGE_OPTIONS));
        selector.setAllowEdit(false);
        selector.setRowsCount(LANGUAGE_OPTIONS.size());
        selector.setPrefWidth(220);
        selector.setConverter(new StringConverter<>() {
            /// Converts a language option to localized display text.
            ///
            /// @param option language option.
            /// @return localized display text.
            @Override
            public String toString(@Nullable LanguageOption option) {
                return languageLabel(option);
            }

            /// Converts localized display text back to a language option.
            ///
            /// @param text localized display text.
            /// @return matching language option, or null when no option matches.
            @Override
            public @Nullable LanguageOption fromString(@Nullable String text) {
                if (text == null) {
                    return null;
                }
                for (LanguageOption option : LANGUAGE_OPTIONS) {
                    if (languageLabel(option).equals(text)) {
                        return option;
                    }
                }
                return null;
            }
        });
        selector.getStyleClass().add("settings-language-selector");

        LanguageOption selected = languageOption(locale);
        selector.selectItem(selected);
        selector.setText(languageLabel(selected));
        return selector;
    }

    /// Creates the update channel selector.
    ///
    /// @param selected initially selected channel.
    /// @return channel selector.
    private static MFXComboBox<UpdateChannel> createUpdateChannelSelector(UpdateChannel selected) {
        MFXComboBox<UpdateChannel> selector = new MFXComboBox<>(
                FXCollections.observableArrayList(UpdateChannel.values()));
        selector.setAllowEdit(false);
        selector.setRowsCount(UpdateChannel.values().length);
        selector.setPrefWidth(220);
        selector.setConverter(new StringConverter<>() {
            /// Converts a channel to localized display text.
            ///
            /// @param channel update channel.
            /// @return localized display text.
            @Override
            public String toString(@Nullable UpdateChannel channel) {
                return channel == null ? "" : updateChannelLabel(channel);
            }

            /// Converts localized display text back to a channel.
            ///
            /// @param text localized display text.
            /// @return matching channel, or null.
            @Override
            public @Nullable UpdateChannel fromString(@Nullable String text) {
                if (text == null) {
                    return null;
                }
                for (UpdateChannel channel : UpdateChannel.values()) {
                    if (updateChannelLabel(channel).equals(text)) {
                        return channel;
                    }
                }
                return null;
            }
        });
        selector.getStyleClass().add("settings-language-selector");
        selector.selectItem(selected);
        selector.setText(updateChannelLabel(selected));
        return selector;
    }

    /// Returns the localized channel label.
    ///
    /// @param channel update channel.
    /// @return localized label.
    private static String updateChannelLabel(UpdateChannel channel) {
        return Messages.get("gui.settings.updateChannel." + channel.token());
    }

    /// Selects the supported language option for a locale.
    ///
    /// @param locale selected locale.
    /// @return matching language option.
    private static LanguageOption languageOption(Locale locale) {
        if (Locale.SIMPLIFIED_CHINESE.getLanguage().equals(locale.getLanguage())) {
            return LANGUAGE_OPTIONS.get(1);
        }
        return LANGUAGE_OPTIONS.getFirst();
    }

    /// Returns the localized display label for a language option.
    ///
    /// @param option language option.
    /// @return localized language label.
    private static String languageLabel(@Nullable LanguageOption option) {
        return option == null ? "" : Messages.get(option.labelKey());
    }

    /// Supported GUI language option.
    ///
    /// @param labelKey message key for the language label.
    /// @param locale   locale selected by this option.
    @NotNullByDefault
    private record LanguageOption(String labelKey, Locale locale) {
    }
}
