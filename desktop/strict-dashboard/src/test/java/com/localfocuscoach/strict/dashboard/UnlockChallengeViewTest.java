package com.localfocuscoach.strict.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localfocuscoach.strict.protocol.ProtocolMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import org.junit.jupiter.api.Test;

class UnlockChallengeViewTest {
    private static final String SECRET = "dashboard-test-secret";
    private static final String TARGET = "abc";

    @Test
    void beginFailureOffersRetryAndReturnWithoutRestartingTheApp() {
        var attempts = new AtomicInteger();
        var returned = new AtomicBoolean();
        var view = FxTestSupport.call(() -> new UnlockChallengeView(
                new ServiceClient(SECRET, request -> {
                    if (attempts.incrementAndGet() == 1) {
                        return response("error.unlockUnavailable", Map.of());
                    }
                    return challengeResponse();
                }),
                () -> returned.set(true)));
        FxTestSupport.waitFor(
                () -> "Challenge unavailable".equals(feedback(view)), "challenge failure");

        FxTestSupport.call(() -> {
            var retry = (Button) view.lookup("#retryChallenge");
            var back = (Button) view.lookup("#backToDashboard");
            assertTrue(retry.isVisible());
            assertTrue(back.isVisible());
            retry.fire();
            return null;
        });
        FxTestSupport.waitFor(
                () -> TARGET.equals(((Label) view.lookup("#challengeTarget")).getText()),
                "retried challenge");
        FxTestSupport.call(() -> {
            ((Button) view.lookup("#backToDashboard")).fire();
            return null;
        });

        assertEquals(2, attempts.get());
        assertTrue(returned.get());
    }

    @Test
    void backRemainsAvailableWhenATimedSessionExpiresDuringTheChallenge() {
        var returned = new AtomicBoolean();
        var view = view(new ArrayList<>(), false, returned);
        FxTestSupport.waitFor(
                () -> TARGET.equals(((Label) view.lookup("#challengeTarget")).getText()),
                "challenge target");

        FxTestSupport.call(() -> {
            view.submit(TARGET);
            ((Button) view.lookup("#backToDashboard")).fire();
            return null;
        });

        assertTrue(returned.get());
    }

    @Test
    void beginsChallengeAndRendersAWrappingMonospaceTarget() {
        var requests = new ArrayList<ProtocolMessage>();
        var view = view(requests, false, new AtomicBoolean());

        FxTestSupport.call(() -> {
            var target = (Label) view.lookup("#challengeTarget");
            assertTrue(target.isWrapText());
            assertEquals("Monospaced", target.getFont().getFamily());
            assertEquals(TARGET, target.getText());
            return null;
        });
        assertEquals(List.of("dashboard.beginUnlock"), requestTypes(requests));
    }

    @Test
    void ordinaryTypingAndBackspaceRemainAvailable() {
        var view = view(new ArrayList<>(), false, new AtomicBoolean());

        FxTestSupport.call(() -> {
            var input = (TextArea) view.lookup("#challengeCandidate");
            input.appendText("a");
            input.appendText("b");
            input.deletePreviousChar();
            assertEquals("a", view.currentCandidate());
            return null;
        });
    }

    @Test
    void pasteDropAndContextMenuDoNotAlterChallengeInput() {
        var view = view(new ArrayList<>(), false, new AtomicBoolean());

        FxTestSupport.call(() -> {
            var input = (TextArea) view.lookup("#challengeCandidate");
            var contextReachedControl = new AtomicBoolean();
            var dropReachedControl = new AtomicBoolean();
            input.addEventHandler(
                    ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    event -> contextReachedControl.set(true));
            input.addEventHandler(DragEvent.DRAG_DROPPED, event -> dropReachedControl.set(true));
            view.onPaste();
            view.onDrop("abc");
            input.appendText("abc");
            var contextMenu = new ContextMenuEvent(
                    ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 0, 0, false, null);
            Event.fireEvent(input, contextMenu);
            var drop = new DragEvent(
                    DragEvent.DRAG_DROPPED,
                    null,
                    0,
                    0,
                    0,
                    0,
                    TransferMode.COPY,
                    null,
                    null,
                    null);
            Event.fireEvent(input, drop);
            assertEquals("", view.currentCandidate());
            assertFalse(contextReachedControl.get());
            assertFalse(dropReachedControl.get());
            assertEquals(null, input.getContextMenu());
            return null;
        });
    }

    @Test
    void commonPasteAndClipboardShortcutsAreConsumed() {
        var view = view(new ArrayList<>(), false, new AtomicBoolean());

        FxTestSupport.call(() -> {
            var input = (TextArea) view.lookup("#challengeCandidate");
            for (var code : List.of(KeyCode.C, KeyCode.X, KeyCode.V)) {
                var reachedControl = new AtomicBoolean();
                input.addEventHandler(KeyEvent.KEY_PRESSED, event -> reachedControl.set(true));
                var command = keyPressed(code, false, true);
                Event.fireEvent(input, command);
                assertFalse(
                        reachedControl.get(), "Command-" + code + " reached the control handler");

                reachedControl.set(false);
                var control = keyPressed(code, true, false);
                Event.fireEvent(input, control);
                assertFalse(
                        reachedControl.get(), "Control-" + code + " reached the control handler");
            }
            var insertReachedControl = new AtomicBoolean();
            input.addEventHandler(
                    KeyEvent.KEY_PRESSED, event -> insertReachedControl.set(true));
            var shiftInsert = new KeyEvent(
                    KeyEvent.KEY_PRESSED, "", "", KeyCode.INSERT, true, false, false, false);
            Event.fireEvent(input, shiftInsert);
            assertFalse(insertReachedControl.get());
            return null;
        });
    }

    @Test
    void submitsOnlyAFullCandidateAndNeverExposesMismatchPosition() {
        var requests = new ArrayList<ProtocolMessage>();
        var view = view(requests, false, new AtomicBoolean());

        FxTestSupport.call(() -> {
            view.submit("ab");
            view.submit("abd");
            return null;
        });
        FxTestSupport.waitFor(
                () -> requestTypes(requests).contains("dashboard.submitUnlock"),
                "full candidate submission");
        FxTestSupport.waitFor(
                () -> "Challenge not complete".equals(feedback(view)), "generic failure");

        assertEquals("Challenge not complete", feedback(view));
        assertFalse(feedback(view).contains("position"));
        assertFalse(feedback(view).matches(".*\\d+.*"));
        assertEquals(
                List.of("dashboard.beginUnlock", "dashboard.submitUnlock"),
                requestTypes(requests));
        assertEquals("abd", requests.get(1).payload().get("candidate"));
        assertEquals(1, requests.get(1).payload().size());
    }

    @Test
    void successfulFullCandidateReportsSuccessAndReturnsToDashboard() {
        var completed = new AtomicBoolean();
        var view = view(new ArrayList<>(), true, completed);

        FxTestSupport.call(() -> {
            view.submit(TARGET);
            return null;
        });
        FxTestSupport.waitFor(completed::get, "successful unlock");

        assertEquals("Strict Mode unlocked", feedback(view));
        assertTrue(completed.get());
    }

    @Test
    void submitButtonIsEnabledByFullLengthWithoutRevealingCorrectness() {
        var view = view(new ArrayList<>(), false, new AtomicBoolean());

        FxTestSupport.call(() -> {
            var input = (TextArea) view.lookup("#challengeCandidate");
            var submit = (Button) view.lookup("#submitChallenge");
            input.appendText("a");
            input.appendText("b");
            assertTrue(submit.isDisable());
            input.appendText("d");
            assertFalse(submit.isDisable());
            assertEquals("", ((Label) view.lookup("#challengeFeedback")).getText());
            return null;
        });
    }

    private static UnlockChallengeView view(
            List<ProtocolMessage> requests, boolean unlocks, AtomicBoolean completed) {
        var view = FxTestSupport.call(() -> new UnlockChallengeView(
                new ServiceClient(SECRET, request -> {
                    requests.add(request);
                    if (request.type().equals("dashboard.beginUnlock")) {
                        return response(
                                "service.challenge",
                                Map.of(
                                        "challengeId", "challenge-id",
                                        "target", TARGET,
                                        "createdAt", "2026-08-18T12:00:00Z"));
                    }
                    return response("service.unlockResult", Map.of("unlocked", unlocks));
                }),
                () -> completed.set(true)));
        FxTestSupport.waitFor(
                () -> TARGET.equals(((Label) view.lookup("#challengeTarget")).getText()),
                "challenge target");
        return view;
    }

    private static KeyEvent keyPressed(KeyCode code, boolean control, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, meta);
    }

    private static List<String> requestTypes(List<ProtocolMessage> requests) {
        return requests.stream().map(ProtocolMessage::type).toList();
    }

    private static ProtocolMessage response(String type, Map<String, Object> payload) {
        return new ProtocolMessage(1, SECRET, type, payload);
    }

    private static ProtocolMessage challengeResponse() {
        return response(
                "service.challenge",
                Map.of(
                        "challengeId", "challenge-id",
                        "target", TARGET,
                        "createdAt", "2026-08-18T12:00:00Z"));
    }

    private static String feedback(UnlockChallengeView view) {
        return ((Label) view.lookup("#challengeFeedback")).getText();
    }
}
