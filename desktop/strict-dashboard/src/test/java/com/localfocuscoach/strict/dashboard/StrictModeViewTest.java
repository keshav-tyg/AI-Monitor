package com.localfocuscoach.strict.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localfocuscoach.strict.protocol.ProtocolMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.Scene;
import org.junit.jupiter.api.Test;

class StrictModeViewTest {
    private static final String SECRET = "dashboard-test-secret";
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    @Test
    void statusExchangeNeverRunsOnTheJavaFxThread() throws Exception {
        var exchangeRan = new CountDownLatch(1);
        var exchangeOnFxThread = new AtomicBoolean();
        var client = new ServiceClient(SECRET, request -> {
            exchangeOnFxThread.set(Platform.isFxApplicationThread());
            exchangeRan.countDown();
            return inactiveStatus();
        });

        var view = FxTestSupport.call(() -> new StrictModeView(
                client, Clock.fixed(NOW, ZoneOffset.UTC), () -> {}));

        assertTrue(exchangeRan.await(1, TimeUnit.SECONDS));
        assertFalse(exchangeOnFxThread.get());
        FxTestSupport.call(() -> {
            view.dispose();
            return null;
        });
        client.close();
    }

    @Test
    void statusPollingDoesNotOverlapAnOutstandingRequest() throws Exception {
        var requestCount = new AtomicInteger();
        var secondRequestStarted = new CountDownLatch(1);
        var releaseSecondRequest = new CountDownLatch(1);
        var client = new ServiceClient(SECRET, request -> {
            var count = requestCount.incrementAndGet();
            if (count >= 2) {
                secondRequestStarted.countDown();
                try {
                    releaseSecondRequest.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            return inactiveStatus();
        });
        var view = FxTestSupport.call(() -> new StrictModeView(
                client, Clock.fixed(NOW, ZoneOffset.UTC), () -> {}));
        FxTestSupport.waitFor(
                () -> view.lookup("#durationMinutes") != null, "initial idle dashboard");

        try (var releaser = Executors.newVirtualThreadPerTaskExecutor()) {
            releaser.submit(() -> {
                secondRequestStarted.await(1, TimeUnit.SECONDS);
                releaseSecondRequest.countDown();
                return null;
            });
            FxTestSupport.call(() -> {
                view.refresh();
                view.refresh();
                return null;
            });
        }

        assertTrue(secondRequestStarted.await(1, TimeUnit.SECONDS));
        assertEquals(2, requestCount.get());
        FxTestSupport.call(() -> {
            view.dispose();
            return null;
        });
        client.close();
    }

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
    void idleModeRendersTheStrictModeHeaderAndCard() {
        var view = idleView(new ArrayList<>());
        applyDashboardCss(view);

        FxTestSupport.call(() -> {
            assertNotNull(view.lookup("#strictModeHeader"));
            assertEquals(1, view.lookupAll(".strictModeCard").size());
            assertNotNull(((DropShadow) view.lookup(".strictModeCard").getEffect()));
            return null;
        });
    }

    @Test
    void timedStartRejectsAnImpracticallyLargeDuration() {
        var starts = new ArrayList<ProtocolMessage>();
        var view = idleView(starts);

        FxTestSupport.call(() -> {
            ((TextField) view.lookup("#durationMinutes")).setText("525601");
            ((Button) view.lookup("#startSession")).fire();
            assertEquals("Duration must be 525,600 minutes or less", feedback(view));
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
        FxTestSupport.waitFor(() -> starts.size() == 1, "timed session start");

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
        FxTestSupport.waitFor(() -> starts.size() == 1, "indefinite session start");

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
        FxTestSupport.waitFor(() -> view.lookup("#activeTitle") != null, "warning dashboard");
        applyDashboardCss(view);

        FxTestSupport.call(() -> {
            assertEquals("Restore the Chrome extension", text(view, "#activeTitle"));
            assertEquals("25 seconds remaining", text(view, "#warningCountdown"));
            var warningCountdown = (Label) view.lookup("#warningCountdown");
            assertTrue(warningCountdown.getStyleClass().contains("pendingState"));
            assertEquals(Color.web("#9a6700"), warningCountdown.getTextFill());
            assertTrue(((Button) view.lookup("#unlockSession")).isVisible());
            assertEquals(1, view.lookupAll(".strictModeWarningCard").size());
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
        FxTestSupport.waitFor(() -> view.lookup("#activeTitle") != null, "active dashboard");

        FxTestSupport.call(() -> {
            assertEquals("Strict Mode is active", text(view, "#activeTitle"));
            assertEquals("1 hour remaining", text(view, "#sessionCountdown"));
            var warningCountdown = (Label) view.lookup("#warningCountdown");
            assertNotNull(warningCountdown);
            assertFalse(warningCountdown.isVisible());
            assertFalse(warningCountdown.isManaged());
            assertEquals(0, view.lookupAll(".strictModeWarningCard").size());
            assertFalse(((Button) view.lookup("#unlockSession")).isVisible());
            return null;
        });
    }

    private static StrictModeView idleView(ArrayList<ProtocolMessage> starts) {
        var view = FxTestSupport.call(() -> new StrictModeView(
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
        FxTestSupport.waitFor(
                () -> view.lookup("#durationMinutes") != null, "initial idle dashboard");
        return view;
    }

    private static ServiceClient statusClient(Map<String, Object> payload) {
        return new ServiceClient(
                SECRET, request -> new ProtocolMessage(1, SECRET, "service.status", payload));
    }

    private static ProtocolMessage inactiveStatus() {
        return new ProtocolMessage(1, SECRET, "service.status", Map.of("active", false));
    }

    private static void applyDashboardCss(StrictModeView view) {
        FxTestSupport.call(() -> {
            var root = new StackPane(view);
            root.getStyleClass().add("dashboard");
            root.getStylesheets().add(java.util.Objects.requireNonNull(
                            DashboardApp.class.getResource("dashboard.css"))
                    .toExternalForm());
            new Scene(root, 760, 580);
            root.applyCss();
            return null;
        });
    }

    private static String feedback(StrictModeView view) {
        return text(view, "#startFeedback");
    }

    private static String text(StrictModeView view, String id) {
        return ((Label) view.lookup(id)).getText();
    }
}
