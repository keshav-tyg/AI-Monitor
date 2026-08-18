package com.localfocuscoach.strict.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localfocuscoach.strict.protocol.ProtocolMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

class StrictModeViewTest {
    private static final String SECRET = "dashboard-test-secret";
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    @Test
    void timedStartRequiresAPositiveWholeMinuteDuration() {
        var starts = new ArrayList<ProtocolMessage>();
        var view = idleView(starts);

        FxTestSupport.call(() -> {
            var duration = (TextField) view.lookup("#durationMinutes");
            var start = (Button) view.lookup("#startSession");
            duration.setText("0");
            start.fire();
            assertEquals("Enter a positive duration in minutes", feedback(view));
            duration.setText("-5");
            start.fire();
            duration.setText("1.5");
            start.fire();
            return null;
        });

        assertTrue(starts.isEmpty());
    }

    @Test
    void timedStartSendsThePerSessionEarlyExitChoice() {
        var starts = new ArrayList<ProtocolMessage>();
        var view = idleView(starts);

        FxTestSupport.call(() -> {
            ((TextField) view.lookup("#durationMinutes")).setText("15");
            ((CheckBox) view.lookup("#earlyExitChallenge")).setSelected(true);
            ((Button) view.lookup("#startSession")).fire();
            return null;
        });

        assertEquals(1, starts.size());
        assertEquals(
                Map.of(
                        "mode", "TIMED",
                        "endsAt", "2026-08-18T12:15:00Z",
                        "earlyExitChallenge", true),
                starts.getFirst().payload());
    }

    @Test
    void idleStatusRefreshPreservesTheCurrentSessionChoices() {
        var view = idleView(new ArrayList<>());

        FxTestSupport.call(() -> {
            ((TextField) view.lookup("#durationMinutes")).setText("25");
            ((CheckBox) view.lookup("#earlyExitChallenge")).setSelected(true);
            view.refresh();
            assertEquals("25", ((TextField) view.lookup("#durationMinutes")).getText());
            assertTrue(((CheckBox) view.lookup("#earlyExitChallenge")).isSelected());
            return null;
        });
    }

    @Test
    void indefiniteModeHidesTheToggleAndAlwaysRequestsAChallenge() {
        var starts = new ArrayList<ProtocolMessage>();
        var view = idleView(starts);

        FxTestSupport.call(() -> {
            ((RadioButton) view.lookup("#indefiniteMode")).fire();
            var earlyExit = (CheckBox) view.lookup("#earlyExitChallenge");
            assertFalse(earlyExit.isVisible());
            assertFalse(earlyExit.isManaged());
            assertFalse(((TextField) view.lookup("#durationMinutes")).isVisible());
            ((Button) view.lookup("#startSession")).fire();
            return null;
        });

        assertEquals(
                Map.of("mode", "INDEFINITE", "earlyExitChallenge", true),
                starts.getFirst().payload());
    }

    @Test
    void warningStateRendersTheRestoreCountdown() {
        var view = FxTestSupport.call(() -> new StrictModeView(
                statusClient(Map.of(
                        "active", true,
                        "sessionId", "session-id",
                        "mode", "INDEFINITE",
                        "startedAt", NOW.minusSeconds(20).toString(),
                        "earlyExitChallenge", true,
                        "status", "ACTIVE",
                        "connectionHealth", "DISCONNECTED",
                        "warningEndsAt", NOW.plusSeconds(25).toString())),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> {}));

        FxTestSupport.call(() -> {
            assertEquals("Restore the Chrome extension", text(view, "#activeTitle"));
            assertEquals("25 seconds remaining", text(view, "#warningCountdown"));
            assertTrue(((Button) view.lookup("#unlockSession")).isVisible());
            return null;
        });
    }

    @Test
    void timedSessionWithoutEarlyExitHasNoUnlockAction() {
        var view = FxTestSupport.call(() -> new StrictModeView(
                statusClient(Map.of(
                        "active", true,
                        "sessionId", "session-id",
                        "mode", "TIMED",
                        "startedAt", NOW.toString(),
                        "endsAt", NOW.plusSeconds(3600).toString(),
                        "earlyExitChallenge", false,
                        "status", "ACTIVE",
                        "connectionHealth", "HEALTHY")),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> {}));

        FxTestSupport.call(() -> {
            assertEquals("Strict Mode is active", text(view, "#activeTitle"));
            assertEquals("1 hour remaining", text(view, "#sessionCountdown"));
            assertFalse(((Button) view.lookup("#unlockSession")).isVisible());
            return null;
        });
    }

    private static StrictModeView idleView(ArrayList<ProtocolMessage> starts) {
        return FxTestSupport.call(() -> new StrictModeView(
                new ServiceClient(SECRET, request -> {
                    if (request.type().equals("dashboard.start")) {
                        starts.add(request);
                        return new ProtocolMessage(
                                1,
                                SECRET,
                                "service.status",
                                Map.of(
                                        "active", true,
                                        "sessionId", "session-id",
                                        "mode", request.payload().get("mode"),
                                        "startedAt", NOW.toString(),
                                        "earlyExitChallenge",
                                                request.payload().get("earlyExitChallenge"),
                                        "status", "ACTIVE",
                                        "connectionHealth", "HEALTHY"));
                    }
                    return new ProtocolMessage(
                            1, SECRET, "service.status", Map.of("active", false));
                }),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> {}));
    }

    private static ServiceClient statusClient(Map<String, Object> payload) {
        return new ServiceClient(
                SECRET, request -> new ProtocolMessage(1, SECRET, "service.status", payload));
    }

    private static String feedback(StrictModeView view) {
        return text(view, "#startFeedback");
    }

    private static String text(StrictModeView view, String id) {
        return ((Label) view.lookup(id)).getText();
    }
}
