package com.localfocuscoach.strict.dashboard;

import com.localfocuscoach.strict.focus.FocusIntervention;
import com.localfocuscoach.strict.focus.FocusRule;
import com.localfocuscoach.strict.focus.FocusSettings;
import com.localfocuscoach.strict.focus.FocusSettingsPayload;
import com.localfocuscoach.strict.focus.FocusSite;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

public final class FocusRulesView extends BorderPane {
    private static final Duration CHROME_SYNC_POLL_INTERVAL = Duration.seconds(1);
    private static final Duration AUTO_SAVE_DELAY = Duration.millis(700);
    private static final double TWO_COLUMN_GRID_MIN_WIDTH = 720;
    private static final List<String> STATUS_STYLE_CLASSES =
            List.of("successState", "pendingState", "errorState");

    private final ServiceClient client;
    private final Runnable showStrictMode;
    private final PauseTransition chromeSyncPoll;
    private final PauseTransition autoSaveDebounce;
    private final CheckBox protectionEnabled = new CheckBox("Protection enabled");
    private final Label feedback = new Label("Loading Focus Rules…");
    private final Label chromeSyncStatus = new Label();
    private final Label saveStatus = new Label();
    private final EnumMap<FocusSite, RuleControls> rules = new EnumMap<>(FocusSite.class);
    private boolean disposed;
    private boolean chromeSyncPending;
    private boolean refreshInFlight;
    private boolean saveInFlight;
    private boolean renderingSnapshot;
    private boolean saveQueuedAfterInFlight;
    private int ruleCardColumns;
    private long draftGeneration;
    private long responseGeneration;

    public FocusRulesView(ServiceClient client, Runnable showStrictMode) {
        this(client, showStrictMode, CHROME_SYNC_POLL_INTERVAL, AUTO_SAVE_DELAY);
    }

    FocusRulesView(ServiceClient client, Runnable showStrictMode, Duration chromeSyncPollInterval) {
        this(client, showStrictMode, chromeSyncPollInterval, AUTO_SAVE_DELAY);
    }

    FocusRulesView(
            ServiceClient client,
            Runnable showStrictMode,
            Duration chromeSyncPollInterval,
            Duration autoSaveDelay) {
        this.client = Objects.requireNonNull(client);
        this.showStrictMode = Objects.requireNonNull(showStrictMode);
        Objects.requireNonNull(chromeSyncPollInterval);
        Objects.requireNonNull(autoSaveDelay);
        if (chromeSyncPollInterval.lessThanOrEqualTo(Duration.ZERO)) {
            throw new IllegalArgumentException("Chrome sync poll interval must be positive");
        }
        if (autoSaveDelay.lessThanOrEqualTo(Duration.ZERO)) {
            throw new IllegalArgumentException("Auto-save delay must be positive");
        }
        chromeSyncPoll = new PauseTransition(chromeSyncPollInterval);
        chromeSyncPoll.setOnFinished(event -> pollChromeSyncStatus());
        autoSaveDebounce = new PauseTransition(autoSaveDelay);
        autoSaveDebounce.setOnFinished(event -> saveChangedDraft());
        setStyle("-fx-background-color: #f7f7f4;");
        render();
        refresh();
    }

    public void refresh() {
        requestSnapshot(false);
    }

    private void pollChromeSyncStatus() {
        requestSnapshot(true);
    }

    private void requestSnapshot(boolean statusOnly) {
        if (disposed || refreshInFlight || saveInFlight) {
            if (statusOnly && !disposed) {
                scheduleChromeSyncPoll();
            }
            return;
        }
        refreshInFlight = true;
        var generation = ++responseGeneration;
        var requestedDraftGeneration = draftGeneration;
        client.getFocusSettingsAsync((response, failure) -> {
            if (disposed) {
                return;
            }
            refreshInFlight = false;
            if (generation != responseGeneration) {
                return;
            }
            if (failure != null || response == null || !"service.focusSettings".equals(response.type())) {
                if (statusOnly) {
                    scheduleChromeSyncPoll();
                } else {
                    feedback.setText("Could not load Focus Rules");
                }
                return;
            }
            try {
                var snapshot = parseSnapshot(response.payload());
                if (statusOnly) {
                    renderChromeSyncStatus(snapshot);
                } else if (draftGeneration == requestedDraftGeneration) {
                    renderSnapshot(snapshot);
                } else {
                    renderChromeSyncStatus(snapshot);
                }
            } catch (IllegalArgumentException exception) {
                if (statusOnly) {
                    scheduleChromeSyncPoll();
                } else {
                    feedback.setText("Could not load Focus Rules");
                }
            }
        });
    }

    public void dispose() {
        disposed = true;
        responseGeneration++;
        chromeSyncPoll.stop();
        autoSaveDebounce.stop();
    }

    private void render() {
        var title = new Label("Focus Rules");
        title.setStyle("-fx-font-size: 28px; -fx-font-weight: bold;");
        var description = new Label(
                "Choose how Local Focus Coach responds to sustained passive use on each supported feed.");
        description.setWrapText(true);
        var strictMode = new Button("Strict Mode");
        strictMode.setId("showStrictMode");
        strictMode.setOnAction(event -> {
            if (!disposed) {
                showStrictMode.run();
            }
        });
        var strictModeDescription = new Label(
                "Strict Mode prevents settings from being weakened while a locked session is active.");
        strictModeDescription.setId("focusRulesStrictModeDescription");
        strictModeDescription.setWrapText(true);
        strictModeDescription.getStyleClass().add("focusRulesStrictModeDescription");
        var strictModeContainer = new HBox(12, strictModeDescription, strictMode);
        strictModeContainer.setId("focusRulesStrictMode");
        strictModeContainer.getStyleClass().add("focusRulesStrictMode");
        strictModeContainer.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(strictModeDescription, Priority.ALWAYS);
        var headingRow = new HBox(18, title, strictModeContainer);
        headingRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(strictModeContainer, Priority.ALWAYS);
        var heading = new VBox(8, headingRow, description);
        heading.setId("focusRulesHeader");
        heading.setPadding(new Insets(28, 32, 14, 32));
        setTop(heading);

        protectionEnabled.setId("focusProtectionEnabled");
        protectionEnabled.getStyleClass().add("protectionControl");
        var cards = new GridPane();
        cards.setId("focusRulesCards");
        cards.setHgap(16);
        cards.setVgap(16);
        for (var site : FocusSite.values()) {
            var controls = createRuleControls(site);
            rules.put(site, controls);
        }
        updateRuleCardLayout(cards, 1);
        cards.widthProperty().addListener(
                (observable, previous, current) -> updateRuleCardLayout(cards, current.doubleValue()));
        var content = new VBox(16, protectionEnabled, cards);
        content.setPadding(new Insets(12, 32, 12, 32));
        setCenter(content);

        chromeSyncStatus.setId("chromeSyncStatus");
        feedback.setId("focusSettingsFeedback");
        feedback.setWrapText(true);
        feedback.getStyleClass().add("errorState");
        saveStatus.setId("focusSaveStatus");
        var footer = new VBox(8, saveStatus, chromeSyncStatus, feedback);
        footer.setPadding(new Insets(14, 32, 28, 32));
        setBottom(footer);
        trackDraftChanges();
    }

    private RuleControls createRuleControls(FocusSite site) {
        var metadata = SiteMetadata.forSite(site);
        var enabled = new CheckBox("Enable this rule");
        enabled.setId(metadata.prefix() + "Enabled");
        enabled.getStyleClass().add("protectionControl");
        var budget = numberField(metadata.prefix() + "Budget", "1–60");
        var gracePeriod = numberField(metadata.prefix() + "GracePeriod", "0–600");
        var sensitivityGroup = new ToggleGroup();
        var sensitivityButtons = new EnumMap<FocusSensitivity, RadioButton>(FocusSensitivity.class);
        var sensitivityList = new VBox(7, new Label("Focus sensitivity"));
        for (var sensitivity : FocusSensitivity.values()) {
            var button = new RadioButton(sensitivityLabel(sensitivity));
            button.setId(metadata.prefix() + "Sensitivity" + sensitivitySuffix(sensitivity));
            button.setToggleGroup(sensitivityGroup);
            button.setUserData(sensitivity);
            button.getStyleClass().add("focusSelectionControl");
            sensitivityButtons.put(sensitivity, button);
            sensitivityList.getChildren().add(button);
        }

        var interventions = new EnumMap<FocusIntervention, CheckBox>(FocusIntervention.class);
        var interventionList = new VBox(7);
        interventionList.getChildren().add(new Label("Interventions, in order"));
        for (var intervention : FocusIntervention.values()) {
            var box = new CheckBox(interventionLabel(intervention));
            box.setId(metadata.prefix() + interventionSuffix(intervention));
            interventions.put(intervention, box);
            interventionList.getChildren().add(box);
        }

        var blockDuration = new Label("Block until tomorrow");
        blockDuration.setId(metadata.prefix() + "BlockDuration");
        var title = new Label(metadata.label());
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        var route = new Label(metadata.routeLabel());
        route.setId(metadata.prefix() + "Route");
        route.getStyleClass().add("focusRulesRoute");
        var cardHeader = new VBox(3, title, route);
        var card = new VBox(
                10,
                cardHeader,
                enabled,
                labelled("Doomscroll session budget (minutes)", budget),
                sensitivityList,
                labelled("Grace period (seconds)", gracePeriod),
                interventionList,
                blockDuration);
        card.getStyleClass().add("focusSiteRule");
        card.getStyleClass().add("focusRulesCard");
        card.setId(metadata.prefix() + "Rule");
        card.setPadding(new Insets(18));
        card.setMaxWidth(Double.MAX_VALUE);
        return new RuleControls(
                card, enabled, budget, gracePeriod, interventions, sensitivityGroup, sensitivityButtons);
    }

    private void updateRuleCardLayout(GridPane cards, double width) {
        var columns = width >= TWO_COLUMN_GRID_MIN_WIDTH ? 2 : 1;
        if (columns == ruleCardColumns) {
            return;
        }
        ruleCardColumns = columns;
        cards.getChildren().clear();
        cards.getColumnConstraints().clear();
        for (var column = 0; column < columns; column++) {
            var constraint = new ColumnConstraints();
            constraint.setPercentWidth(100.0 / columns);
            constraint.setHgrow(Priority.ALWAYS);
            constraint.setFillWidth(true);
            cards.getColumnConstraints().add(constraint);
        }
        var index = 0;
        for (var site : FocusSite.values()) {
            var card = rules.get(site).card();
            cards.add(card, index % columns, index / columns);
            GridPane.setHgrow(card, Priority.ALWAYS);
            index++;
        }
    }

    private void renderSnapshot(Map<String, Object> payload) {
        renderSnapshot(parseSnapshot(payload));
    }

    private FocusSettingsSnapshot parseSnapshot(Map<String, Object> payload) {
        if (!payload.keySet().equals(Set.of("revision", "settings", "chromeAppliedRevision"))
                || !(payload.get("settings") instanceof Map<?, ?> rawSettings)) {
            throw new IllegalArgumentException("Invalid Focus Rules response");
        }
        var settingsPayload = stringMap(rawSettings);
        var completePayload = new LinkedHashMap<>(settingsPayload);
        completePayload.put("revision", payload.get("revision"));
        var settings = FocusSettingsPayload.fromPayload(completePayload);
        var chromeRevision = nonNegativeLong(payload.get("chromeAppliedRevision"));
        return new FocusSettingsSnapshot(settings, chromeRevision);
    }

    private void renderSnapshot(FocusSettingsSnapshot snapshot) {
        renderingSnapshot = true;
        try {
            var settings = snapshot.settings();
            protectionEnabled.setSelected(settings.enabled());
            for (var site : FocusSite.values()) {
                var rule = settings.rules().get(site);
                var controls = rules.get(site);
                controls.enabled().setSelected(rule.enabled());
                controls.budget().setText(Integer.toString(rule.doomscrollBudgetMinutes()));
                controls.renderSensitivity(rule.warningScore());
                controls.gracePeriod().setText(Integer.toString(rule.gracePeriodSeconds()));
                for (var intervention : FocusIntervention.values()) {
                    controls.interventions()
                            .get(intervention)
                            .setSelected(rule.interventions().contains(intervention));
                }
            }
        } finally {
            renderingSnapshot = false;
        }
        renderChromeSyncStatus(snapshot);
        feedback.setText("");
    }

    private void renderChromeSyncStatus(FocusSettingsSnapshot snapshot) {
        var synced = snapshot.settings().revision() > 0
                && snapshot.chromeRevision() == snapshot.settings().revision();
        chromeSyncPending = !synced;
        setStatus(
                chromeSyncStatus,
                synced ? "Synced with Chrome" : "Waiting for Chrome",
                synced ? "successState" : "pendingState");
        if (synced) {
            chromeSyncPoll.stop();
        } else {
            scheduleChromeSyncPoll();
        }
    }

    private void scheduleChromeSyncPoll() {
        if (!disposed && chromeSyncPending) {
            chromeSyncPoll.playFromStart();
        }
    }

    private void changedDraft() {
        if (renderingSnapshot || disposed) {
            return;
        }
        draftGeneration++;
        setStatus(saveStatus, "", null);
        autoSaveDebounce.playFromStart();
    }

    private void saveChangedDraft() {
        if (disposed) {
            return;
        }
        if (saveInFlight) {
            saveQueuedAfterInFlight = true;
            return;
        }
        var proposedRules = new EnumMap<FocusSite, FocusRule>(FocusSite.class);
        for (var site : FocusSite.values()) {
            var controls = rules.get(site);
            var budget = integer(controls.budget(), 1, 60);
            if (budget == null) {
                showValidationError("Doomscroll session budget must be 1 to 60 minutes");
                return;
            }
            var gracePeriod = integer(controls.gracePeriod(), 0, 600);
            if (gracePeriod == null) {
                showValidationError("Grace period must be 0 to 600 seconds");
                return;
            }
            var interventions = new ArrayList<FocusIntervention>();
            for (var intervention : FocusIntervention.values()) {
                if (controls.interventions().get(intervention).isSelected()) {
                    interventions.add(intervention);
                }
            }
            if (controls.enabled().isSelected() && interventions.isEmpty()) {
                showValidationError("An enabled rule needs at least one intervention");
                return;
            }
            proposedRules.put(
                    site,
                    new FocusRule(
                            controls.enabled().isSelected(),
                            budget,
                            controls.warningScoreForSave(),
                            gracePeriod,
                            interventions));
        }

        var proposed = new FocusSettings(0, protectionEnabled.isSelected(), proposedRules);
        var payload = new LinkedHashMap<>(FocusSettingsPayload.toPayload(proposed));
        payload.remove("revision");
        var submittedDraftGeneration = draftGeneration;
        saveInFlight = true;
        chromeSyncPoll.stop();
        setStatus(saveStatus, "Saving changes…", "pendingState");
        feedback.setText("");
        var generation = ++responseGeneration;
        client.saveFocusSettingsAsync(payload, (response, failure) -> {
            if (disposed || generation != responseGeneration) {
                return;
            }
            saveInFlight = false;
            if (failure != null || response == null) {
                feedback.setText("Could not save Focus Rules");
                setStatus(saveStatus, "Could not save Focus Rules", "errorState");
                scheduleChromeSyncPoll();
            } else if ("error.focusSettingsWeakening".equals(response.type())) {
                feedback.setText(
                        "Strict Mode is active, so settings cannot be made less protective.");
                setStatus(
                        saveStatus,
                        "Strict Mode is active, so settings cannot be made less protective.",
                        "errorState");
                scheduleChromeSyncPoll();
            } else if (!"service.focusSettings".equals(response.type())) {
                feedback.setText("Could not save Focus Rules");
                setStatus(saveStatus, "Could not save Focus Rules", "errorState");
                scheduleChromeSyncPoll();
            } else {
                try {
                    var snapshot = parseSnapshot(response.payload());
                    if (draftGeneration == submittedDraftGeneration) {
                        renderSnapshot(snapshot);
                    } else {
                        renderChromeSyncStatus(snapshot);
                    }
                    setStatus(saveStatus, "Saved", "successState");
                } catch (IllegalArgumentException exception) {
                    feedback.setText("Could not save Focus Rules");
                    setStatus(saveStatus, "Could not save Focus Rules", "errorState");
                    scheduleChromeSyncPoll();
                }
            }
            if (saveQueuedAfterInFlight) {
                saveQueuedAfterInFlight = false;
                autoSaveDebounce.stop();
                saveChangedDraft();
            }
        });
    }

    private void showValidationError(String message) {
        setStatus(saveStatus, "", null);
        feedback.setText(message);
    }

    private static void setStatus(Label label, String text, String styleClass) {
        label.setText(text);
        label.getStyleClass().removeAll(STATUS_STYLE_CLASSES);
        if (styleClass != null) {
            label.getStyleClass().add(styleClass);
        }
    }

    private void trackDraftChanges() {
        protectionEnabled.selectedProperty().addListener(
                (observable, previous, current) -> changedDraft());
        for (var controls : rules.values()) {
            controls.enabled().selectedProperty().addListener(
                    (observable, previous, current) -> changedDraft());
            controls.budget().textProperty().addListener(
                    (observable, previous, current) -> changedDraft());
            for (var sensitivity : controls.sensitivityButtons().values()) {
                sensitivity.selectedProperty().addListener(
                        (observable, previous, current) -> changedDraft());
            }
            controls.gracePeriod().textProperty().addListener(
                    (observable, previous, current) -> changedDraft());
            for (var intervention : controls.interventions().values()) {
                intervention.selectedProperty().addListener(
                        (observable, previous, current) -> changedDraft());
            }
        }
    }

    private static Integer integer(TextField field, int minimum, int maximum) {
        try {
            var value = Integer.parseInt(field.getText().trim());
            return value >= minimum && value <= maximum ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static TextField numberField(String id, String prompt) {
        var field = new TextField();
        field.setId(id);
        field.setPromptText(prompt);
        field.setMaxWidth(110);
        return field;
    }

    private static HBox labelled(String text, TextField field) {
        var label = new Label(text);
        label.setMinWidth(0);
        label.setWrapText(true);
        var row = new HBox(12, label, field);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(label, Priority.ALWAYS);
        return row;
    }

    private static String interventionLabel(FocusIntervention intervention) {
        return switch (intervention) {
            case NOTIFY -> "Notify me";
            case PAUSE -> "Show a pause screen";
            case CLOSE_TAB -> "Close the tab";
            case BLOCK -> "Block until tomorrow";
        };
    }

    private static String sensitivityLabel(FocusSensitivity sensitivity) {
        return switch (sensitivity) {
            case MILD -> "Mild — Intervene after more sustained passive scrolling.";
            case MEDIUM -> "Medium — A balanced reminder.";
            case AGGRESSIVE -> "Aggressive — Intervene quickly after passive scrolling begins.";
        };
    }

    private static String sensitivitySuffix(FocusSensitivity sensitivity) {
        return switch (sensitivity) {
            case MILD -> "Mild";
            case MEDIUM -> "Medium";
            case AGGRESSIVE -> "Aggressive";
        };
    }

    private static String interventionSuffix(FocusIntervention intervention) {
        return switch (intervention) {
            case NOTIFY -> "Notify";
            case PAUSE -> "Pause";
            case CLOSE_TAB -> "CloseTab";
            case BLOCK -> "Block";
        };
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Focus Rules keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static long nonNegativeLong(Object value) {
        if (!(value instanceof Byte
                        || value instanceof Short
                        || value instanceof Integer
                        || value instanceof Long)
                || ((Number) value).longValue() < 0) {
            throw new IllegalArgumentException("Focus Rules revision must not be negative");
        }
        return ((Number) value).longValue();
    }

    private static final class RuleControls {
        private final VBox card;
        private final CheckBox enabled;
        private final TextField budget;
        private final TextField gracePeriod;
        private final EnumMap<FocusIntervention, CheckBox> interventions;
        private final ToggleGroup sensitivityGroup;
        private final EnumMap<FocusSensitivity, RadioButton> sensitivityButtons;
        private int loadedWarningScore;
        private boolean sensitivityChanged;

        private RuleControls(
                VBox card,
                CheckBox enabled,
                TextField budget,
                TextField gracePeriod,
                EnumMap<FocusIntervention, CheckBox> interventions,
                ToggleGroup sensitivityGroup,
                EnumMap<FocusSensitivity, RadioButton> sensitivityButtons) {
            this.card = card;
            this.enabled = enabled;
            this.budget = budget;
            this.gracePeriod = gracePeriod;
            this.interventions = interventions;
            this.sensitivityGroup = sensitivityGroup;
            this.sensitivityButtons = sensitivityButtons;
            renderSensitivity(FocusSensitivity.MILD.warningScore());
            for (var button : sensitivityButtons.values()) {
                button.setOnAction(event -> sensitivityChanged = true);
            }
        }

        private VBox card() {
            return card;
        }

        private CheckBox enabled() {
            return enabled;
        }

        private TextField budget() {
            return budget;
        }

        private TextField gracePeriod() {
            return gracePeriod;
        }

        private EnumMap<FocusIntervention, CheckBox> interventions() {
            return interventions;
        }

        private EnumMap<FocusSensitivity, RadioButton> sensitivityButtons() {
            return sensitivityButtons;
        }

        private void renderSensitivity(int warningScore) {
            sensitivityButtons.get(FocusSensitivity.forStoredScore(warningScore)).setSelected(true);
            loadedWarningScore = warningScore;
            sensitivityChanged = false;
        }

        private int warningScoreForSave() {
            return sensitivityChanged
                    ? selectedSensitivity().warningScore()
                    : loadedWarningScore;
        }

        private FocusSensitivity selectedSensitivity() {
            var selected = sensitivityGroup.getSelectedToggle();
            if (selected == null) {
                throw new IllegalStateException("Focus sensitivity must be selected");
            }
            return (FocusSensitivity) selected.getUserData();
        }
    }

    private record FocusSettingsSnapshot(FocusSettings settings, long chromeRevision) {}

    private record SiteMetadata(String label, String routeLabel, String prefix) {
        private static SiteMetadata forSite(FocusSite site) {
            return switch (site) {
                case INSTAGRAM_REELS ->
                    new SiteMetadata("Instagram Reels", "instagram.com/reels", "instagramReels");
                case X_TIMELINE -> new SiteMetadata("X timeline", "x.com/home", "xTimeline");
                case YOUTUBE_SHORTS ->
                    new SiteMetadata("YouTube Shorts", "youtube.com/shorts", "youtubeShorts");
            };
        }
    }
}
