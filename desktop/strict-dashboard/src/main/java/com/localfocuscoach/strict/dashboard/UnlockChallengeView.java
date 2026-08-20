package com.localfocuscoach.strict.dashboard;

import java.util.Map;
import java.util.Objects;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

public final class UnlockChallengeView extends BorderPane {
    private static final String BEGIN_REQUEST = "dashboard.beginUnlock";
    private static final String SUBMIT_REQUEST = "dashboard.submitUnlock";
    private static final String FAILURE_MESSAGE = "Challenge not complete";

    private final ServiceClient client;
    private final Runnable returnToDashboard;
    private final ChallengeTextArea candidate = new ChallengeTextArea();
    private final Label feedback = new Label();
    private final Button submit = new Button("Unlock Strict Mode");
    private final Button retry = new Button("Retry challenge");
    private final Button back = new Button("Back to dashboard");
    private final Label targetLabel = new Label();
    private String target = "";
    private boolean requestInFlight;
    private boolean disposed;
    private long responseGeneration;

    public UnlockChallengeView(ServiceClient client, Runnable returnToDashboard) {
        this.client = Objects.requireNonNull(client);
        this.returnToDashboard = Objects.requireNonNull(returnToDashboard);
        setStyle("-fx-background-color: #f7f7f4;");
        getStyleClass().add("unlockChallengeView");
        configureCandidate();
        render();
        beginChallenge();
    }

    public String currentCandidate() {
        return candidate.getText();
    }

    public void onPaste() {
        candidate.paste();
    }

    public void onDrop(String ignoredText) {
        // Drag-and-drop input is deliberately ignored.
    }

    public void submit(String fullCandidate) {
        if (target.isEmpty() || fullCandidate == null || fullCandidate.length() != target.length()) {
            showResult(false);
            return;
        }
        if (disposed || requestInFlight) {
            return;
        }
        requestInFlight = true;
        candidate.setDisable(true);
        submit.setDisable(true);
        var generation = ++responseGeneration;
        client.requestAsync(
                SUBMIT_REQUEST, Map.of("candidate", fullCandidate), (response, failure) -> {
            if (disposed || generation != responseGeneration) {
                return;
            }
            requestInFlight = false;
            candidate.setDisable(false);
            submit.setDisable(candidate.getLength() != target.length());
            var unlocked = failure == null
                    && response != null
                    && "service.unlockResult".equals(response.type())
                    && Boolean.TRUE.equals(response.payload().get("unlocked"));
            showResult(unlocked);
        });
    }

    private void beginChallenge() {
        if (disposed || requestInFlight) {
            return;
        }
        requestInFlight = true;
        target = "";
        targetLabel.setText("");
        candidate.clear();
        candidate.setDisable(true);
        submit.setDisable(true);
        setShown(retry, false);
        feedback.setText("Loading challenge…");
        var generation = ++responseGeneration;
        client.requestAsync(BEGIN_REQUEST, Map.of(), (response, failure) -> {
            if (disposed || generation != responseGeneration) {
                return;
            }
            requestInFlight = false;
            var value = response == null ? null : response.payload().get("target");
            if (failure != null
                    || response == null
                    || !"service.challenge".equals(response.type())
                    || !(value instanceof String text)) {
                showUnavailable();
                return;
            }
            target = text;
            targetLabel.setText(target);
            candidate.setDisable(false);
            feedback.setText("");
            submit.setDisable(target.isEmpty() || candidate.getLength() != target.length());
            candidate.requestFocus();
        });
    }

    private void render() {
        var title = new Label("Unlock challenge");
        title.getStyleClass().add("unlockChallengeTitle");
        var instructions = new Label(
                "Type the complete target exactly. You can correct mistakes with Backspace, but clipboard and drag-and-drop input are disabled.");
        instructions.setWrapText(true);
        var header = new VBox(8, title, instructions);
        header.setId("unlockHeader");

        targetLabel.setId("challengeTarget");
        targetLabel.setFont(Font.font("Monospaced", 14));
        targetLabel.setWrapText(true);
        targetLabel.setMaxWidth(Double.MAX_VALUE);
        targetLabel.getStyleClass().add("unlockChallengeTarget");

        candidate.setId("challengeCandidate");
        candidate.setPromptText("Type the full challenge here");
        candidate.setWrapText(true);
        candidate.setPrefRowCount(8);

        submit.setId("submitChallenge");
        submit.setDisable(true);
        submit.setDefaultButton(true);
        submit.setOnAction(event -> submit(candidate.getText()));

        retry.setId("retryChallenge");
        setShown(retry, false);
        retry.setOnAction(event -> beginChallenge());

        back.setId("backToDashboard");
        back.setOnAction(event -> returnToDashboard());

        feedback.setId("challengeFeedback");
        feedback.setWrapText(true);
        feedback.setStyle("-fx-text-fill: #8a331f;");

        var actions = new HBox(10, submit, retry, back);
        var card = new VBox(14, targetLabel, candidate, feedback, actions);
        card.getStyleClass().add("unlockChallengeCard");
        card.setPadding(new Insets(20));
        var content = new VBox(20, header, card);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setMaxWidth(680);
        setCenter(content);
        BorderPane.setMargin(content, new Insets(32));
    }

    private void configureCandidate() {
        candidate.setContextMenu(null);
        candidate.setTextFormatter(new TextFormatter<String>(change -> {
            if (change.isAdded() && change.getText().length() > 1) {
                return null;
            }
            return change;
        }));
        candidate.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> event.consume());
        candidate.addEventFilter(DragEvent.ANY, event -> event.consume());
        candidate.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (isClipboardShortcut(event)) {
                event.consume();
            }
        });
        candidate.textProperty().addListener((observable, previous, current) -> {
            submit.setDisable(target.isEmpty() || current.length() != target.length());
            feedback.setText("");
        });
    }

    private static boolean isClipboardShortcut(KeyEvent event) {
        var clipboardKey = event.getCode() == KeyCode.C
                || event.getCode() == KeyCode.X
                || event.getCode() == KeyCode.V
                || event.getCode() == KeyCode.COPY
                || event.getCode() == KeyCode.CUT
                || event.getCode() == KeyCode.PASTE;
        return (clipboardKey && (event.isShortcutDown() || event.isControlDown() || event.isMetaDown()))
                || (event.getCode() == KeyCode.INSERT && event.isShiftDown());
    }

    private void showResult(boolean unlocked) {
        var message = unlocked ? "Strict Mode unlocked" : FAILURE_MESSAGE;
        feedback.setText(message);
        if (unlocked) {
            returnToDashboard();
        }
    }

    private void showUnavailable() {
        target = "";
        targetLabel.setText("");
        candidate.setDisable(true);
        feedback.setText("Challenge unavailable");
        submit.setDisable(true);
        setShown(retry, true);
    }

    public void dispose() {
        disposed = true;
        responseGeneration++;
    }

    private void returnToDashboard() {
        if (disposed) {
            return;
        }
        dispose();
        returnToDashboard.run();
    }

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private static final class ChallengeTextArea extends TextArea {
        @Override
        public void copy() {
            // Clipboard operations are intentionally disabled for the challenge.
        }

        @Override
        public void cut() {
            // Clipboard operations are intentionally disabled for the challenge.
        }

        @Override
        public void paste() {
            // Clipboard operations are intentionally disabled for the challenge.
        }
    }
}
