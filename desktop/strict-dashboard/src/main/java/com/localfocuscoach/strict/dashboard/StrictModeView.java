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
import javafx.scene.layout.VBox;

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
        setStyle("-fx-background-color: #f7f7f4;");
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
        var title = title("Start Strict Mode");
        var description = new Label(
                "Choose a timed commitment or continue until you complete an unlock challenge.");
        description.setWrapText(true);
        var header = new VBox(8, title, description);
        header.setId("strictModeHeader");

        var timed = new RadioButton("Timed");
        timed.setId("timedMode");
        var indefinite = new RadioButton("Indefinite");
        indefinite.setId("indefiniteMode");
        var modes = new ToggleGroup();
        timed.setToggleGroup(modes);
        indefinite.setToggleGroup(modes);
        timed.setSelected(true);

        var duration = new TextField("60");
        duration.setId("durationMinutes");
        duration.setPromptText("Minutes");
        duration.setMaxWidth(120);
        var durationLabel = new Label("minutes");
        var durationRow = new HBox(8, duration, durationLabel);
        durationRow.setAlignment(Pos.CENTER_LEFT);

        var earlyExit = new CheckBox("Require a typing challenge for early exit");
        earlyExit.setId("earlyExitChallenge");
        var serviceFeedback = new Label(initialFeedback);
        serviceFeedback.setId("serviceFeedback");
        serviceFeedback.setWrapText(true);
        serviceFeedback.setStyle("-fx-text-fill: #8a331f;");
        var feedback = new Label();
        feedback.setId("startFeedback");
        feedback.setWrapText(true);
        feedback.setStyle("-fx-text-fill: #8a331f;");

        var start = new Button("Start session");
        start.setId("startSession");
        start.setDefaultButton(true);
        start.setOnAction(event -> startSession(timed.isSelected(), duration, earlyExit, feedback));

        var updateMode = (javafx.beans.value.ChangeListener<javafx.scene.control.Toggle>)
                (observable, previous, current) -> {
                    var isTimed = timed.isSelected();
                    setShown(duration, isTimed);
                    setShown(durationLabel, isTimed);
                    durationRow.setManaged(isTimed);
                    durationRow.setVisible(isTimed);
                    setShown(earlyExit, isTimed);
                };
        modes.selectedToggleProperty().addListener(updateMode);

        var sessionTitle = new Label("Session setup");
        sessionTitle.getStyleClass().add("strictModeCardTitle");
        var card = new VBox(
                14,
                sessionTitle,
                new HBox(18, timed, indefinite),
                durationRow,
                earlyExit,
                serviceFeedback,
                feedback,
                start);
        card.getStyleClass().add("strictModeCard");
        card.setPadding(new Insets(20));
        var content = new VBox(20, header, card);
        content.setId("idleView");
        content.setMaxWidth(520);
        content.setAlignment(Pos.CENTER_LEFT);
        setCenter(content);
        BorderPane.setMargin(content, new Insets(32));
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
        var header = new VBox(8, activeTitle);
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
        warningCountdown.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #a23a25;");
        setShown(warningCountdown, isWarning);

        var unlock = new Button("Begin unlock challenge");
        unlock.setId("unlockSession");
        var canUnlock = "INDEFINITE".equals(status.get("mode"))
                || Boolean.TRUE.equals(status.get("earlyExitChallenge"));
        setShown(unlock, canUnlock);
        unlock.setOnAction(event -> unlockAction.run());

        var sessionTitle = new Label("Current session");
        sessionTitle.getStyleClass().add("strictModeCardTitle");
        var sessionCard = new VBox(14, sessionTitle, detail, sessionCountdown, unlock);
        sessionCard.getStyleClass().add("strictModeCard");
        sessionCard.setPadding(new Insets(20));
        if (!isWarning) {
            sessionCard.getChildren().add(warningCountdown);
        }

        var content = new VBox(20, header);
        if (isWarning) {
            var warningTitle = new Label("Connection warning");
            warningTitle.getStyleClass().add("strictModeWarningTitle");
            var warningCard = new VBox(8, warningTitle, warningCountdown);
            warningCard.getStyleClass().add("strictModeWarningCard");
            warningCard.setPadding(new Insets(16));
            content.getChildren().add(warningCard);
        }
        content.getChildren().add(sessionCard);
        content.setMaxWidth(560);
        content.setAlignment(Pos.CENTER_LEFT);
        setCenter(content);
        BorderPane.setMargin(content, new Insets(32));
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

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
