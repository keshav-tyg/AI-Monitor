package com.localfocuscoach.strict.dashboard;

import com.localfocuscoach.strict.focus.FocusIntervention;
import com.localfocuscoach.strict.focus.FocusRule;
import com.localfocuscoach.strict.focus.FocusSettings;
import com.localfocuscoach.strict.focus.FocusSettingsPayload;
import com.localfocuscoach.strict.focus.FocusSite;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class FocusRulesView extends BorderPane {
    private final ServiceClient client;
    private final Runnable showStrictMode;
    private final CheckBox protectionEnabled = new CheckBox("Protection enabled");
    private final Label feedback = new Label("Loading Focus Rules…");
    private final Label chromeSyncStatus = new Label();
    private final Button save = new Button("Save Focus Rules");
    private final EnumMap<FocusSite, RuleControls> rules = new EnumMap<>(FocusSite.class);
    private boolean disposed;
    private boolean saveInFlight;
    private long draftGeneration;
    private long responseGeneration;

    public FocusRulesView(ServiceClient client, Runnable showStrictMode) {
        this.client = Objects.requireNonNull(client);
        this.showStrictMode = Objects.requireNonNull(showStrictMode);
        setStyle("-fx-background-color: #f7f7f4;");
        render();
        refresh();
    }

    public void refresh() {
        if (disposed || saveInFlight) {
            return;
        }
        var generation = ++responseGeneration;
        client.getFocusSettingsAsync((response, failure) -> {
            if (disposed || generation != responseGeneration) {
                return;
            }
            if (failure != null || response == null || !"service.focusSettings".equals(response.type())) {
                feedback.setText("Could not load Focus Rules");
                return;
            }
            try {
                renderSnapshot(response.payload());
            } catch (IllegalArgumentException exception) {
                feedback.setText("Could not load Focus Rules");
            }
        });
    }

    public void dispose() {
        disposed = true;
        responseGeneration++;
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
        var heading = new VBox(8, new HBox(12, title, strictMode), description);
        heading.setPadding(new Insets(28, 32, 14, 32));
        setTop(heading);

        protectionEnabled.setId("focusProtectionEnabled");
        var cards = new VBox(16, protectionEnabled);
        cards.setPadding(new Insets(12, 32, 12, 32));
        for (var site : FocusSite.values()) {
            var controls = createRuleControls(site);
            rules.put(site, controls);
            cards.getChildren().add(controls.card());
        }
        setCenter(cards);

        chromeSyncStatus.setId("chromeSyncStatus");
        feedback.setId("focusSettingsFeedback");
        feedback.setWrapText(true);
        feedback.setStyle("-fx-text-fill: #8a331f;");
        save.setId("saveFocusRules");
        save.setDefaultButton(true);
        save.setOnAction(event -> save());
        var footer = new VBox(8, chromeSyncStatus, feedback, save);
        footer.setPadding(new Insets(14, 32, 28, 32));
        setBottom(footer);
        trackDraftChanges();
    }

    private RuleControls createRuleControls(FocusSite site) {
        var metadata = SiteMetadata.forSite(site);
        var enabled = new CheckBox("Enable this rule");
        enabled.setId(metadata.prefix() + "Enabled");
        var budget = numberField(metadata.prefix() + "Budget", "1–60");
        var warningScore = numberField(metadata.prefix() + "WarningScore", "1–50");
        var gracePeriod = numberField(metadata.prefix() + "GracePeriod", "0–600");

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
        var card = new VBox(
                10,
                title,
                enabled,
                labelled("Doomscroll session budget (minutes)", budget),
                labelled("Warning score", warningScore),
                labelled("Grace period (seconds)", gracePeriod),
                interventionList,
                blockDuration);
        card.getStyleClass().add("focusSiteRule");
        card.setId(metadata.prefix() + "Rule");
        card.setPadding(new Insets(18));
        card.setStyle(
                "-fx-background-color: white; -fx-border-color: #d8d8d2; -fx-border-radius: 10; -fx-background-radius: 10;");
        return new RuleControls(card, enabled, budget, warningScore, gracePeriod, interventions);
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
        var settings = snapshot.settings();
        protectionEnabled.setSelected(settings.enabled());
        for (var site : FocusSite.values()) {
            var rule = settings.rules().get(site);
            var controls = rules.get(site);
            controls.enabled().setSelected(rule.enabled());
            controls.budget().setText(Integer.toString(rule.doomscrollBudgetMinutes()));
            controls.warningScore().setText(Integer.toString(rule.warningScore()));
            controls.gracePeriod().setText(Integer.toString(rule.gracePeriodSeconds()));
            for (var intervention : FocusIntervention.values()) {
                controls.interventions()
                        .get(intervention)
                        .setSelected(rule.interventions().contains(intervention));
            }
        }
        renderChromeSyncStatus(snapshot);
        feedback.setText("");
    }

    private void renderChromeSyncStatus(FocusSettingsSnapshot snapshot) {
        chromeSyncStatus.setText(snapshot.chromeRevision() == snapshot.settings().revision()
                ? "Synced with Chrome"
                : "Waiting for Chrome");
    }

    private void save() {
        if (disposed || saveInFlight) {
            return;
        }
        var proposedRules = new EnumMap<FocusSite, FocusRule>(FocusSite.class);
        for (var site : FocusSite.values()) {
            var controls = rules.get(site);
            var budget = integer(controls.budget(), 1, 60);
            if (budget == null) {
                feedback.setText("Doomscroll session budget must be 1 to 60 minutes");
                return;
            }
            var warningScore = integer(controls.warningScore(), 1, 50);
            if (warningScore == null) {
                feedback.setText("Warning score must be 1 to 50");
                return;
            }
            var gracePeriod = integer(controls.gracePeriod(), 0, 600);
            if (gracePeriod == null) {
                feedback.setText("Grace period must be 0 to 600 seconds");
                return;
            }
            var interventions = new ArrayList<FocusIntervention>();
            for (var intervention : FocusIntervention.values()) {
                if (controls.interventions().get(intervention).isSelected()) {
                    interventions.add(intervention);
                }
            }
            if (controls.enabled().isSelected() && interventions.isEmpty()) {
                feedback.setText("An enabled rule needs at least one intervention");
                return;
            }
            proposedRules.put(
                    site,
                    new FocusRule(
                            controls.enabled().isSelected(),
                            budget,
                            warningScore,
                            gracePeriod,
                            interventions));
        }

        var proposed = new FocusSettings(0, protectionEnabled.isSelected(), proposedRules);
        var payload = new LinkedHashMap<>(FocusSettingsPayload.toPayload(proposed));
        payload.remove("revision");
        var submittedDraftGeneration = draftGeneration;
        saveInFlight = true;
        setFormDisabled(true);
        feedback.setText("Saving Focus Rules…");
        var generation = ++responseGeneration;
        client.saveFocusSettingsAsync(payload, (response, failure) -> {
            if (disposed || generation != responseGeneration) {
                return;
            }
            saveInFlight = false;
            setFormDisabled(false);
            if (failure != null || response == null) {
                feedback.setText("Could not save Focus Rules");
                return;
            }
            if ("error.focusSettingsWeakening".equals(response.type())) {
                feedback.setText(
                        "Strict Mode is active, so settings cannot be made less protective.");
                return;
            }
            if (!"service.focusSettings".equals(response.type())) {
                feedback.setText("Could not save Focus Rules");
                return;
            }
            try {
                var snapshot = parseSnapshot(response.payload());
                if (draftGeneration == submittedDraftGeneration) {
                    renderSnapshot(snapshot);
                } else {
                    renderChromeSyncStatus(snapshot);
                }
                feedback.setText("Focus Rules saved");
            } catch (IllegalArgumentException exception) {
                feedback.setText("Could not save Focus Rules");
            }
        });
    }

    private void trackDraftChanges() {
        protectionEnabled.selectedProperty().addListener(
                (observable, previous, current) -> draftGeneration++);
        for (var controls : rules.values()) {
            controls.enabled().selectedProperty().addListener(
                    (observable, previous, current) -> draftGeneration++);
            controls.budget().textProperty().addListener(
                    (observable, previous, current) -> draftGeneration++);
            controls.warningScore().textProperty().addListener(
                    (observable, previous, current) -> draftGeneration++);
            controls.gracePeriod().textProperty().addListener(
                    (observable, previous, current) -> draftGeneration++);
            for (var intervention : controls.interventions().values()) {
                intervention.selectedProperty().addListener(
                        (observable, previous, current) -> draftGeneration++);
            }
        }
    }

    private void setFormDisabled(boolean disabled) {
        protectionEnabled.setDisable(disabled);
        save.setDisable(disabled);
        for (var controls : rules.values()) {
            controls.enabled().setDisable(disabled);
            controls.budget().setDisable(disabled);
            controls.warningScore().setDisable(disabled);
            controls.gracePeriod().setDisable(disabled);
            for (var intervention : controls.interventions().values()) {
                intervention.setDisable(disabled);
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
        label.setMinWidth(250);
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

    private record RuleControls(
            VBox card,
            CheckBox enabled,
            TextField budget,
            TextField warningScore,
            TextField gracePeriod,
            EnumMap<FocusIntervention, CheckBox> interventions) {}

    private record FocusSettingsSnapshot(FocusSettings settings, long chromeRevision) {}

    private record SiteMetadata(String label, String prefix) {
        private static SiteMetadata forSite(FocusSite site) {
            return switch (site) {
                case INSTAGRAM_REELS -> new SiteMetadata("Instagram Reels", "instagramReels");
                case X_TIMELINE -> new SiteMetadata("X timeline", "xTimeline");
                case YOUTUBE_SHORTS -> new SiteMetadata("YouTube Shorts", "youtubeShorts");
            };
        }
    }
}
