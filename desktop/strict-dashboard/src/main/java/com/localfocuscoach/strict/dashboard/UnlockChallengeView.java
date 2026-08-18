package com.localfocuscoach.strict.dashboard;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
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
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

public final class UnlockChallengeView extends BorderPane {
    private static final String BEGIN_REQUEST = "dashboard.beginUnlock";
    private static final String SUBMIT_REQUEST = "dashboard.submitUnlock";
    private static final String FAILURE_MESSAGE = "Challenge not complete";

    private final ServiceClient client;
    private final Consumer<Boolean> completion;
    private final ChallengeTextArea candidate = new ChallengeTextArea();
    private final Label feedback = new Label();
    private final Button submit = new Button("Unlock Strict Mode");
    private String target = "";

    public UnlockChallengeView(ServiceClient client, Consumer<Boolean> completion) {
        this.client = Objects.requireNonNull(client);
        this.completion = Objects.requireNonNull(completion);
        setStyle("-fx-background-color: #f7f7f4;");
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

    public SubmissionResult submit(String fullCandidate) {
        if (target.isEmpty() || fullCandidate == null || fullCandidate.length() != target.length()) {
            return showResult(false);
        }
        try {
            var response = client.request(SUBMIT_REQUEST, Map.of("candidate", fullCandidate));
            var unlocked = "service.unlockResult".equals(response.type())
                    && Boolean.TRUE.equals(response.payload().get("unlocked"));
            return showResult(unlocked);
        } catch (RuntimeException exception) {
            return showResult(false);
        }
    }

    private void beginChallenge() {
        try {
            var response = client.request(BEGIN_REQUEST, Map.of());
            var value = response.payload().get("target");
            if (!"service.challenge".equals(response.type()) || !(value instanceof String text)) {
                showUnavailable();
                return;
            }
            target = text;
            ((Label) lookup("#challengeTarget")).setText(target);
            submit.setDisable(target.isEmpty() || candidate.getLength() != target.length());
            candidate.requestFocus();
        } catch (RuntimeException exception) {
            showUnavailable();
        }
    }

    private void render() {
        var title = new Label("Unlock challenge");
        title.setStyle("-fx-font-size: 28px; -fx-font-weight: bold;");
        var instructions = new Label(
                "Type the complete target exactly. You can correct mistakes with Backspace, but clipboard and drag-and-drop input are disabled.");
        instructions.setWrapText(true);

        var targetLabel = new Label();
        targetLabel.setId("challengeTarget");
        targetLabel.setFont(Font.font("Monospaced", 14));
        targetLabel.setWrapText(true);
        targetLabel.setMaxWidth(Double.MAX_VALUE);
        targetLabel.setStyle("-fx-padding: 14; -fx-background-color: #ecece7;");

        candidate.setId("challengeCandidate");
        candidate.setPromptText("Type the full challenge here");
        candidate.setWrapText(true);
        candidate.setPrefRowCount(8);

        submit.setId("submitChallenge");
        submit.setDisable(true);
        submit.setDefaultButton(true);
        submit.setOnAction(event -> submit(candidate.getText()));

        feedback.setId("challengeFeedback");
        feedback.setWrapText(true);
        feedback.setStyle("-fx-text-fill: #8a331f;");

        var content = new VBox(14, title, instructions, targetLabel, candidate, feedback, submit);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setMaxWidth(680);
        setCenter(content);
        BorderPane.setMargin(content, new Insets(40));
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

    private SubmissionResult showResult(boolean unlocked) {
        var message = unlocked ? "Strict Mode unlocked" : FAILURE_MESSAGE;
        feedback.setText(message);
        if (unlocked) {
            completion.accept(true);
        }
        return new SubmissionResult(unlocked, message);
    }

    private void showUnavailable() {
        target = "";
        feedback.setText("Challenge unavailable");
        submit.setDisable(true);
    }

    public record SubmissionResult(boolean unlocked, String message) {}

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
