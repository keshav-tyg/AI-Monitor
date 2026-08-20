package com.localfocuscoach.strict.dashboard;

import com.localfocuscoach.strict.protocol.ProtocolMessage;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

public final class StrictModeView extends BorderPane {
    private static final String STATUS_REQUEST = "dashboard.status";
    private static final String START_REQUEST = "dashboard.start";
    private static final long MAX_DURATION_MINUTES = 365L * 24 * 60;

    private final ServiceClient client;
    private final Clock clock;
    private final Runnable unlockAction;
    private final Timeline refreshTimer;
    private boolean statusRequestInFlight;
    private boolean actionRequestInFlight;
    private boolean disposed;
    private long responseGeneration;

    public StrictModeView(ServiceClient client, Runnable unlockAction) {
        this(client, Clock.systemUTC(), unlockAction);
    }

    StrictModeView(ServiceClient client, Clock clock, Runnable unlockAction) {
        this.client = Objects.requireNonNull(client);
        this.clock = Objects.requireNonNull(clock);
        this.unlockAction = Objects.requireNonNull(unlockAction);
        setStyle("-fx-background-color: #f0efe9;");
        getStyleClass().add("strictModeView");
        refreshTimer = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), event -> refresh()));
        refreshTimer.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((observable, previous, current) -> {
            if (current == null) {
                refreshTimer.stop();
            } else {
                refreshTimer.play();
            }
        });
        refresh();
    }

    public void refresh() {
        if (disposed || statusRequestInFlight || actionRequestInFlight) {
            return;
        }
        statusRequestInFlight = true;
        var generation = responseGeneration;
        client.requestAsync(STATUS_REQUEST, Map.of(), (response, failure) -> {
            statusRequestInFlight = false;
            if (disposed || generation != responseGeneration) {
                return;
            }
            if (failure != null) {
                renderIdle("Strict Mode service is unavailable");
            } else {
                renderStatus(response);
            }
        });
    }

    public void dispose() {
        disposed = true;
        responseGeneration++;
        refreshTimer.stop();
    }

    private void renderStatus(ProtocolMessage response) {
        if (!"service.status".equals(response.type())) {
            renderIdle("Strict Mode service is unavailable");
            return;
        }
        if (!Boolean.TRUE.equals(response.payload().get("active"))) {
            renderIdle("");
            return;
        }
        renderActive(response.payload());
    }

    private void renderIdle(String initialFeedback) {
        if (getCenter() != null && "idleView".equals(getCenter().getId())) {
            ((Label) lookup("#serviceFeedback")).setText(initialFeedback);
            return;
        }
        var title = title("Strict Mode");
        var description = new Label(
                "Keeps your protection active during a chosen period. While active, rules cannot "
                        + "be weakened, deleted, or disabled — only strengthened.");
        description.setWrapText(true);
        description.getStyleClass().add("strictModeDescription");
        var titleRow = new HBox(10, lockTile("strictModeHeader"), title);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        var header = new VBox(6, titleRow, description);
        header.setId("strictModeHeader");

        var timed = new RadioButton("Timed session");
        timed.setId("timedMode");
        var indefinite = new RadioButton("Indefinite");
        indefinite.setId("indefiniteMode");
        var modes = new ToggleGroup();
        timed.setToggleGroup(modes);
        indefinite.setToggleGroup(modes);
        timed.setSelected(true);

        var duration = new TextField("60");
        duration.setId("durationMinutes");
        duration.setManaged(false);
        duration.setVisible(false);

        var durationHours = new TextField("1");
        var durationMinutePart = new TextField("0");
        var hoursStepper = DashboardControls.stepper(
                "durationHours", durationHours, 0, 8_760, "hr");
        var minutesStepper = DashboardControls.stepper(
                "durationMinutePart", durationMinutePart, 0, 59, "min");
        var durationRow = new HBox(20, hoursStepper, minutesStepper, duration);
        durationRow.setId("durationStepper");
        durationRow.getStyleClass().add("strictDurationStepper");
        durationRow.setAlignment(Pos.CENTER_LEFT);
        ChangeListener<String> updateDuration = (observable, previous, current) ->
                updateDurationMinutes(durationHours, durationMinutePart, duration);
        durationHours.textProperty().addListener(updateDuration);
        durationMinutePart.textProperty().addListener(updateDuration);

        var earlyExit = new CheckBox("Require a typing challenge for early exit");
        earlyExit.setId("earlyExitChallenge");
        earlyExit.getStyleClass().add("figmaIntervention");
        var serviceFeedback = new Label(initialFeedback);
        serviceFeedback.setId("serviceFeedback");
        serviceFeedback.setWrapText(true);
        serviceFeedback.getStyleClass().add("errorState");
        var feedback = new Label();
        feedback.setId("startFeedback");
        feedback.setWrapText(true);
        feedback.getStyleClass().add("errorState");

        var start = new Button("Start Strict Mode");
        start.setId("startSession");
        start.getStyleClass().add("strictPrimaryAction");
        start.setMaxWidth(Double.MAX_VALUE);
        start.setDefaultButton(true);
        start.setOnAction(event -> startSession(timed.isSelected(), duration, earlyExit, feedback));

        var timedOption = sessionOption(
                "timedSessionOption",
                timed,
                "Automatically ends after a set time");
        var indefiniteOption = sessionOption(
                "indefiniteSessionOption",
                indefinite,
                "Ends when you complete the unlock challenge");
        var optionRow = new HBox(12, timedOption, indefiniteOption);
        optionRow.setId("sessionTypeOptions");
        HBox.setHgrow(timedOption, Priority.ALWAYS);
        HBox.setHgrow(indefiniteOption, Priority.ALWAYS);
        var divider = new Region();
        divider.setId("durationDivider");
        divider.getStyleClass().add("strictModeDivider");
        divider.setMinHeight(1);
        divider.setPrefHeight(1);
        divider.setMaxHeight(1);
        var durationTitle = new Label("DURATION");
        durationTitle.setId("durationLabel");
        durationTitle.getStyleClass().add("strictSectionLabel");

        var updateMode = (ChangeListener<javafx.scene.control.Toggle>)
                (observable, previous, current) -> {
                    var isTimed = timed.isSelected();
                    setShown(divider, isTimed);
                    setShown(durationTitle, isTimed);
                    setShown(durationRow, isTimed);
                    setShown(earlyExit, isTimed);
                    updateSessionOptionState(timedOption, isTimed);
                    updateSessionOptionState(indefiniteOption, !isTimed);
                };
        modes.selectedToggleProperty().addListener(updateMode);
        updateMode.changed(modes.selectedToggleProperty(), null, timed);

        var sessionTitle = new Label("Session type");
        sessionTitle.getStyleClass().add("strictModeCardTitle");
        var sessionCard = DashboardControls.card(
                "sessionTypeCard",
                sessionTitle,
                optionRow,
                divider,
                durationTitle,
                durationRow);
        sessionCard.getStyleClass().add("strictModeCard");

        var preparationTitle = new Label("Unlock sequence");
        preparationTitle.getStyleClass().add("strictModeCardTitle");
        var preparationCopy = new Label(
                "If this session can end early, Local Focus Coach will generate the real "
                        + "500-character unlock sequence when you begin the challenge. Mistakes "
                        + "won't be revealed as you type.");
        preparationCopy.setWrapText(true);
        preparationCopy.getStyleClass().add("strictCardDescription");
        var sequenceNotice = new Label(
                "Secure 500-character challenge generated on unlock");
        sequenceNotice.setId("unlockPreparationSequence");
        sequenceNotice.setWrapText(true);
        sequenceNotice.setMaxWidth(Double.MAX_VALUE);
        sequenceNotice.getStyleClass().add("figmaSequencePanel");
        var challengeLabel = new Label("EARLY EXIT POLICY");
        challengeLabel.getStyleClass().add("strictSectionLabel");
        var preparationCard = DashboardControls.card(
                "unlockPreparationCard",
                preparationTitle,
                preparationCopy,
                sequenceNotice,
                challengeLabel,
                earlyExit,
                serviceFeedback,
                feedback);
        preparationCard.getStyleClass().add("unlockPreparationCard");

        var safetyIcon = new Label("i");
        safetyIcon.getStyleClass().add("strictSafetyIcon");
        var safetyTitle = new Label("Good to know");
        safetyTitle.getStyleClass().add("strictSafetyTitle");
        var safetyCopy = new Label(
                "You can always make rules stricter during a session. Timed sessions end "
                        + "automatically; indefinite sessions require the secure unlock challenge.");
        safetyCopy.setWrapText(true);
        safetyCopy.getStyleClass().add("strictSafetyCopy");
        var safetyText = new VBox(3, safetyTitle, safetyCopy);
        var safetyCard = new HBox(12, safetyIcon, safetyText);
        safetyCard.setId("strictSafetyCard");
        safetyCard.getStyleClass().add("figmaSafetyCard");
        safetyCard.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(safetyText, Priority.ALWAYS);

        var content = new VBox(16, header, sessionCard, preparationCard, safetyCard, start);
        content.setId("idleView");
        content.setMaxWidth(520);
        content.setAlignment(Pos.CENTER_LEFT);
        setCenter(content);
        BorderPane.setAlignment(content, Pos.TOP_LEFT);
        BorderPane.setMargin(content, new Insets(24, 28, 28, 28));
    }

    private static VBox sessionOption(String id, RadioButton control, String detailText) {
        control.getStyleClass().add("strictSessionChoice");
        var detail = new Label(detailText);
        detail.setWrapText(true);
        detail.getStyleClass().add("strictSessionOptionDetail");
        var option = new VBox(4, control, detail);
        option.setId(id);
        option.getStyleClass().add("strictSessionOption");
        option.setMinWidth(0);
        option.setPrefWidth(0);
        option.setMaxWidth(Double.MAX_VALUE);
        option.setOnMouseClicked(event -> control.setSelected(true));
        return option;
    }

    private static void updateSessionOptionState(VBox option, boolean selected) {
        option.getStyleClass().remove("selectedSessionOption");
        if (selected) {
            option.getStyleClass().add("selectedSessionOption");
        }
    }

    private static void updateDurationMinutes(
            TextField hours, TextField minutePart, TextField totalMinutes) {
        try {
            var parsedHours = Long.parseLong(hours.getText().trim());
            var parsedMinutes = Long.parseLong(minutePart.getText().trim());
            var total = Math.addExact(Math.multiplyExact(parsedHours, 60), parsedMinutes);
            totalMinutes.setText(Long.toString(total));
        } catch (ArithmeticException | NumberFormatException exception) {
            totalMinutes.setText("");
        }
    }

    private void startSession(
            boolean timed, TextField duration, CheckBox earlyExit, Label feedback) {
        var payload = new LinkedHashMap<String, Object>();
        if (timed) {
            final long minutes;
            try {
                minutes = Long.parseLong(duration.getText().trim());
            } catch (NumberFormatException exception) {
                feedback.setText("Enter a positive duration in minutes");
                return;
            }
            if (minutes <= 0) {
                feedback.setText("Enter a positive duration in minutes");
                return;
            }
            if (minutes > MAX_DURATION_MINUTES) {
                feedback.setText("Duration must be 525,600 minutes or less");
                return;
            }
            final Instant endsAt;
            try {
                endsAt = clock.instant().plus(Duration.ofMinutes(minutes));
            } catch (ArithmeticException | DateTimeException exception) {
                feedback.setText("Enter a positive duration in minutes");
                return;
            }
            payload.put("mode", "TIMED");
            payload.put("endsAt", endsAt.toString());
            payload.put("earlyExitChallenge", earlyExit.isSelected());
        } else {
            payload.put("mode", "INDEFINITE");
            payload.put("earlyExitChallenge", true);
        }

        if (actionRequestInFlight) {
            return;
        }
        actionRequestInFlight = true;
        responseGeneration++;
        client.requestAsync(START_REQUEST, payload, (response, failure) -> {
            actionRequestInFlight = false;
            if (disposed) {
                return;
            }
            if (failure != null
                    || response == null
                    || !"service.status".equals(response.type())
                    || !Boolean.TRUE.equals(response.payload().get("active"))) {
                feedback.setText("Unable to start Strict Mode");
                return;
            }
            renderActive(response.payload());
        });
    }

    private void renderActive(Map<String, Object> status) {
        var warningEndsAt = instant(status.get("warningEndsAt"));
        var isWarning = warningEndsAt != null;
        var activeTitle = title(isWarning ? "Restore the Chrome extension" : "Strict Mode is active");
        activeTitle.setId("activeTitle");
        var titleRow = new HBox(10, lockTile("activeSessionHeader"), activeTitle);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        var header = new VBox(8, titleRow);
        header.setId("strictModeHeader");

        var detail = new Label(isWarning
                ? "The extension connection is unavailable. Restore it before the countdown ends."
                : activeDescription(status));
        detail.setWrapText(true);

        var sessionCountdown = new Label(remainingText(instant(status.get("endsAt"))));
        sessionCountdown.setId("sessionCountdown");
        setShown(sessionCountdown, status.get("endsAt") instanceof String);

        var warningCountdown = new Label(isWarning ? remainingText(warningEndsAt) : "");
        warningCountdown.setId("warningCountdown");
        warningCountdown.getStyleClass().addAll("strictModeWarningCountdown", "pendingState");
        setShown(warningCountdown, isWarning);

        var unlock = new Button("Begin unlock challenge");
        unlock.setId("unlockSession");
        var canUnlock = "INDEFINITE".equals(status.get("mode"))
                || Boolean.TRUE.equals(status.get("earlyExitChallenge"));
        setShown(unlock, canUnlock);
        unlock.setOnAction(event -> unlockAction.run());

        var sessionTitle = new Label("Current session");
        sessionTitle.getStyleClass().add("strictModeCardTitle");
        sessionCountdown.getStyleClass().add("activeSessionCountdown");
        unlock.getStyleClass().add("strictSecondaryAction");
        var sessionCard = DashboardControls.card(
                "activeSessionCard", sessionTitle, detail, sessionCountdown, unlock);
        sessionCard.getStyleClass().add("strictModeCard");
        if (!isWarning) {
            sessionCard.getChildren().add(warningCountdown);
        }

        var content = new VBox(20, header);
        if (isWarning) {
            var warningTitle = new Label("Connection warning");
            warningTitle.getStyleClass().add("strictModeWarningTitle");
            var warningCard = new VBox(8, warningTitle, warningCountdown);
            warningCard.setId("strictConnectionWarningCard");
            warningCard.getStyleClass().add("strictModeWarningCard");
            content.getChildren().add(warningCard);
        }
        content.getChildren().add(sessionCard);
        content.setMaxWidth(520);
        content.setAlignment(Pos.CENTER_LEFT);
        setCenter(content);
        BorderPane.setAlignment(content, Pos.TOP_LEFT);
        BorderPane.setMargin(content, new Insets(24, 28, 28, 28));
    }

    private String activeDescription(Map<String, Object> status) {
        if ("INDEFINITE".equals(status.get("mode"))) {
            return "This session continues until you complete the unlock challenge.";
        }
        return "This timed session ends automatically when its countdown reaches zero.";
    }

    private String remainingText(Instant deadline) {
        if (deadline == null) {
            return "";
        }
        var seconds = Math.max(0, Duration.between(clock.instant(), deadline).toSeconds());
        if (seconds != 0 && seconds % 3600 == 0) {
            var hours = seconds / 3600;
            return hours + (hours == 1 ? " hour remaining" : " hours remaining");
        }
        if (seconds >= 60 && seconds % 60 == 0) {
            var minutes = seconds / 60;
            return minutes + (minutes == 1 ? " minute remaining" : " minutes remaining");
        }
        return seconds + (seconds == 1 ? " second remaining" : " seconds remaining");
    }

    private static Instant instant(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static Label title(String text) {
        var label = new Label(text);
        label.getStyleClass().add("strictModeTitle");
        label.setWrapText(true);
        return label;
    }

    private static StackPane lockTile(String idPrefix) {
        var icon = DashboardControls.lockIcon(idPrefix, Color.WHITE);
        var tile = new StackPane(icon);
        tile.getStyleClass().add("strictHeaderLockTile");
        tile.setMinSize(32, 32);
        tile.setPrefSize(32, 32);
        tile.setMaxSize(32, 32);
        return tile;
    }

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
