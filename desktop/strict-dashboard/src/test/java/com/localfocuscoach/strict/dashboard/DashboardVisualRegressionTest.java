package com.localfocuscoach.strict.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localfocuscoach.strict.protocol.ProtocolMessage;
import java.util.List;
import java.util.Map;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

class DashboardVisualRegressionTest {
    private static final String SECRET = "visual-test-secret";

    @Test
    void referenceSnapshotUsesTheFigmaFrameAndWarmSurfaceTokens() {
        var client = new ServiceClient(SECRET, request -> focusSettingsResponse());
        var result = FxTestSupport.call(() -> {
            var dashboard = new DashboardApp.DashboardView(client);
            var scene = new Scene(dashboard, 1100, 760);
            dashboard.applyCss();
            dashboard.layout();
            WritableImage snapshot = scene.snapshot(null);
            return new Object[] {dashboard, snapshot};
        });
        var dashboard = (DashboardApp.DashboardView) result[0];
        var snapshot = (WritableImage) result[1];
        try {
            assertEquals(1100.0, snapshot.getWidth(), 0.1);
            assertEquals(760.0, snapshot.getHeight(), 0.1);
            var titleBar = (Region) dashboard.lookup("#macosTitleBar");
            var sidebar = (Region) dashboard.lookup("#dashboardSidebar");
            assertBounds(titleBar, 0, 0, 1100, 40, 1.0);
            assertBounds(sidebar, 0, 40, 208, 720, 1.0);
            assertEquals("Local Focus Coach", label(dashboard, "#macosWindowTitle").getText());
            assertEquals("Focus Rules", ((Button) dashboard.lookup("#focusRulesNavigation")).getText());
            assertEquals("Strict Mode", ((Button) dashboard.lookup("#strictModeNavigation")).getText());
            assertTrue(((Button) dashboard.lookup("#focusRulesNavigation"))
                    .getStyleClass().contains("activeNavigation"));
            assertNotNull(dashboard.lookup("#macosClose").getOnMouseClicked());
            assertNotNull(dashboard.lookup("#macosMinimize").getOnMouseClicked());
            assertNotNull(dashboard.lookup("#macosZoom").getOnMouseClicked());
            assertColorNear(Color.web("#e8e7e1"), snapshot.getPixelReader().getColor(800, 20));
            assertColorNear(Color.web("#e8e7e1"), snapshot.getPixelReader().getColor(100, 200));
            assertColorNear(Color.web("#f0efe9"), snapshot.getPixelReader().getColor(1000, 50));
        } finally {
            FxTestSupport.call(() -> {
                dashboard.dispose();
                return null;
            });
            client.close();
        }
    }

    @Test
    void focusRulesMatchesTheReferenceCardGridAndPinnedStatusGeometry() {
        var client = new ServiceClient(SECRET, request -> focusSettingsResponse());
        var dashboard = FxTestSupport.call(() -> {
            var created = new DashboardApp.DashboardView(client);
            new Scene(created, 1100, 760);
            created.applyCss();
            created.layout();
            return created;
        });
        try {
            FxTestSupport.waitFor(
                    () -> dashboard.lookup("#instagramReelsBadge") != null,
                    "Figma Focus Rules hierarchy");
            FxTestSupport.call(() -> {
                dashboard.applyCss();
                dashboard.layout();
                var protection = (Region) dashboard.lookup("#focusProtectionCard");
                var cards = (GridPane) dashboard.lookup("#focusRulesCards");
                var instagram = (Region) dashboard.lookup("#instagramReelsRule");
                var youtube = (Region) dashboard.lookup("#youtubeShortsRule");
                var status = (Region) dashboard.lookup("#focusRulesStatusBar");
                var title = label(dashboard, ".focusRulesPageTitle");
                var viewport = dashboard.lookup("#dashboardContentViewport .viewport");
                var instagramEnabled = (CheckBox) dashboard.lookup("#instagramReelsEnabled");
                var instagramMild =
                        (RadioButton) dashboard.lookup("#instagramReelsSensitivityMild");

                assertNotNull(protection);
                assertEquals(
                        28.0,
                        title.localToScene(0, 0).getX()
                                - viewport.localToScene(0, 0).getX(),
                        1.0);
                assertEquals("Focus Rules", label(dashboard, ".focusRulesPageTitle").getText());
                assertEquals(
                        "Choose how Local Focus Coach responds to sustained passive use.",
                        label(dashboard, ".focusRulesPageDescription").getText());
                assertEquals("Instagram Reels", label(instagram, ".focusSiteTitle").getText());
                assertEquals("instagram.com/reels", label(instagram, "#instagramReelsRoute").getText());
                assertEquals("YouTube Shorts", label(youtube, ".focusSiteTitle").getText());
                assertTrue(instagramEnabled.isSelected());
                assertTrue(instagramMild.isSelected());
                assertEquals("10", ((javafx.scene.control.TextField)
                        dashboard.lookup("#instagramReelsBudget")).getText());
                assertEquals(2, cards.getColumnConstraints().size());
                assertTrue(protection.getWidth() >= cards.getWidth() - 2.0);
                assertEquals(instagram.getWidth(), youtube.getWidth(), 1.0);
                assertTrue(youtube.localToScene(youtube.getBoundsInLocal()).getMinX()
                        > instagram.localToScene(instagram.getBoundsInLocal()).getMinX());
                assertTrue(status.isVisible());
                assertNotNull(status.getScene());
                assertTrue(status.localToScene(status.getBoundsInLocal()).getMinY() > 700.0);
                assertColorNear(
                        Color.web("#ffffff"),
                        (Color) protection.getBackground().getFills().getFirst().getFill());
                return null;
            });
        } finally {
            FxTestSupport.call(() -> {
                dashboard.dispose();
                return null;
            });
            client.close();
        }
    }

    @Test
    void strictModeMatchesTheReferenceSelectionPreparationAndSafetyGeometry() {
        var client = new ServiceClient(SECRET, request -> {
            if ("dashboard.status".equals(request.type())) {
                return new ProtocolMessage(
                        1, SECRET, "service.status", Map.of("active", false));
            }
            return focusSettingsResponse();
        });
        var dashboard = FxTestSupport.call(() -> {
            var created = new DashboardApp.DashboardView(client);
            new Scene(created, 1100, 760);
            created.applyCss();
            created.layout();
            ((Button) created.lookup("#strictModeNavigation")).fire();
            return created;
        });
        try {
            FxTestSupport.waitFor(
                    () -> dashboard.lookup("#strictSafetyCard") != null,
                    "Figma Strict Mode hierarchy");
            FxTestSupport.call(() -> {
                dashboard.applyCss();
                dashboard.layout();
                var session = (Region) dashboard.lookup("#sessionTypeCard");
                var options = (HBox) dashboard.lookup("#sessionTypeOptions");
                var timed = (Region) dashboard.lookup("#timedSessionOption");
                var indefinite = (Region) dashboard.lookup("#indefiniteSessionOption");
                var preparation = (Region) dashboard.lookup("#unlockPreparationCard");
                var safety = (Region) dashboard.lookup("#strictSafetyCard");
                var start = (Button) dashboard.lookup("#startSession");
                var header = (Region) dashboard.lookup("#strictModeHeader");
                var contentViewport = (ScrollPane) dashboard.lookup("#dashboardContentViewport");
                var viewport = contentViewport.lookup(".viewport");

                assertNotNull(session);
                assertEquals(
                        28.0,
                        header.localToScene(0, 0).getX()
                                - viewport.localToScene(0, 0).getX(),
                        1.0);
                assertEquals(2, options.getChildren().size());
                assertEquals("Strict Mode", label(dashboard, ".strictModeTitle").getText());
                assertEquals("Timed session", ((RadioButton) dashboard.lookup("#timedMode")).getText());
                assertEquals("Indefinite", ((RadioButton) dashboard.lookup("#indefiniteMode")).getText());
                assertTrue(timed.getStyleClass().contains("selectedSessionOption"));
                assertEquals(timed.getWidth(), indefinite.getWidth(), 1.0);
                assertTrue(preparation.getWidth() >= session.getWidth() - 2.0);
                assertTrue(safety.getStyleClass().contains("figmaSafetyCard"));
                assertTrue(start.getWidth() >= safety.getWidth() - 2.0);
                assertColorNear(
                        Color.web("#ecfdf3"),
                        (Color) timed.getBackground().getFills().getFirst().getFill());
                assertColorNear(
                        Color.web("#fffbeb"),
                        (Color) safety.getBackground().getFills().getFirst().getFill());
                assertColorNear(
                        Color.web("#111827"),
                        (Color) start.getBackground().getFills().getFirst().getFill());
                assertEquals("Start Strict Mode", start.getText());
                assertEquals(
                        "Secure 500-character challenge generated on unlock",
                        label(dashboard, "#unlockPreparationSequence").getText());
                var snapshot = dashboard.getScene().snapshot(null);
                assertEquals(1100.0, snapshot.getWidth(), 0.1);
                assertEquals(760.0, snapshot.getHeight(), 0.1);
                return null;
            });
        } finally {
            FxTestSupport.call(() -> {
                dashboard.dispose();
                return null;
            });
            client.close();
        }
    }

    @Test
    void activeStrictModeMatchesTheFigmaCardSystemAndTruthfulState() {
        var client = dashboardClient(true, "z".repeat(500));
        var dashboard = dashboard(client);
        try {
            navigateToStrictMode(dashboard, "#activeSessionCard");
            FxTestSupport.call(() -> {
                dashboard.applyCss();
                dashboard.layout();
                var header = (Region) dashboard.lookup("#strictModeHeader");
                var card = (Region) dashboard.lookup("#activeSessionCard");
                var unlock = (Button) dashboard.lookup("#unlockSession");
                var viewport = dashboard.lookup("#dashboardContentViewport .viewport");
                assertEquals("Strict Mode is active", label(dashboard, "#activeTitle").getText());
                assertEquals(
                        28.0,
                        header.localToScene(0, 0).getX()
                                - viewport.localToScene(0, 0).getX(),
                        1.0);
                assertEquals("Begin unlock challenge", unlock.getText());
                assertTrue(unlock.isVisible());
                assertTrue(card.getStyleClass().contains("figmaCard"));
                assertColorNear(
                        Color.WHITE,
                        (Color) card.getBackground().getFills().getFirst().getFill());
                assertFalse(((Label) dashboard.lookup("#warningCountdown")).isVisible());
                return null;
            });
        } finally {
            dispose(dashboard, client);
        }
    }

    @Test
    void unlockMatchesTheFigmaSequenceCardWhileDisplayingTheRealChallenge() {
        var target = "z".repeat(500);
        var client = dashboardClient(true, target);
        var dashboard = dashboard(client);
        try {
            navigateToStrictMode(dashboard, "#activeSessionCard");
            FxTestSupport.call(() -> {
                ((Button) dashboard.lookup("#unlockSession")).fire();
                return null;
            });
            FxTestSupport.waitFor(
                    () -> dashboard.lookup("#challengeTarget") != null
                            && target.equals(label(dashboard, "#challengeTarget").getText()),
                    "real 500-character unlock target");
            FxTestSupport.call(() -> {
                dashboard.applyCss();
                dashboard.layout();
                var header = (Region) dashboard.lookup("#unlockHeader");
                var card = (Region) dashboard.lookup("#unlockChallengeCard");
                var targetLabel = label(dashboard, "#challengeTarget");
                var candidate = (TextArea) dashboard.lookup("#challengeCandidate");
                var submit = (Button) dashboard.lookup("#submitChallenge");
                var viewport = dashboard.lookup("#dashboardContentViewport .viewport");
                assertEquals("Unlock challenge", label(dashboard, ".unlockChallengeTitle").getText());
                assertEquals(
                        28.0,
                        header.localToScene(0, 0).getX()
                                - viewport.localToScene(0, 0).getX(),
                        1.0);
                assertEquals(500, targetLabel.getText().length());
                assertTrue(targetLabel.isWrapText());
                assertTrue(targetLabel.getStyleClass().contains("figmaSequencePanel"));
                assertEquals("Type the sequence above…", candidate.getPromptText());
                assertTrue(submit.isDisable());
                assertTrue(card.getStyleClass().contains("figmaCard"));
                assertColorNear(
                        Color.web("#f9fafb"),
                        (Color) targetLabel.getBackground().getFills().getFirst().getFill());
                return null;
            });
        } finally {
            dispose(dashboard, client);
        }
    }

    private static DashboardApp.DashboardView dashboard(ServiceClient client) {
        return FxTestSupport.call(() -> {
            var created = new DashboardApp.DashboardView(client);
            new Scene(created, 1100, 760);
            created.applyCss();
            created.layout();
            return created;
        });
    }

    private static void navigateToStrictMode(
            DashboardApp.DashboardView dashboard, String expectedSelector) {
        FxTestSupport.call(() -> {
            ((Button) dashboard.lookup("#strictModeNavigation")).fire();
            return null;
        });
        FxTestSupport.waitFor(
                () -> dashboard.lookup(expectedSelector) != null,
                "Strict Mode visual state " + expectedSelector);
    }

    private static ServiceClient dashboardClient(boolean active, String target) {
        return new ServiceClient(SECRET, request -> switch (request.type()) {
            case "dashboard.focusSettings.get" -> focusSettingsResponse();
            case "dashboard.status" -> new ProtocolMessage(
                    1,
                    SECRET,
                    "service.status",
                    active
                            ? Map.of(
                                    "active", true,
                                    "sessionId", "session-id",
                                    "mode", "INDEFINITE",
                                    "startedAt", "2026-08-20T12:00:00Z",
                                    "earlyExitChallenge", true,
                                    "status", "ACTIVE",
                                    "connectionHealth", "HEALTHY")
                            : Map.of("active", false));
            case "dashboard.beginUnlock" -> new ProtocolMessage(
                    1,
                    SECRET,
                    "service.challenge",
                    Map.of(
                            "challengeId", "challenge-id",
                            "target", target,
                            "createdAt", "2026-08-20T12:00:00Z"));
            default -> throw new AssertionError("Unexpected dashboard request " + request.type());
        });
    }

    private static Label label(javafx.scene.Parent root, String selector) {
        return (Label) root.lookup(selector);
    }

    private static void assertBounds(
            Region node,
            double expectedX,
            double expectedY,
            double expectedWidth,
            double expectedHeight,
            double tolerance) {
        var bounds = node.localToScene(node.getBoundsInLocal());
        assertEquals(expectedX, bounds.getMinX(), tolerance);
        assertEquals(expectedY, bounds.getMinY(), tolerance);
        assertEquals(expectedWidth, bounds.getWidth(), tolerance);
        assertEquals(expectedHeight, bounds.getHeight(), tolerance);
    }

    private static void dispose(
            DashboardApp.DashboardView dashboard, ServiceClient client) {
        FxTestSupport.call(() -> {
            dashboard.dispose();
            return null;
        });
        client.close();
    }

    private static void assertColorNear(Color expected, Color actual) {
        double tolerance = 0.02;
        assertTrue(Math.abs(expected.getRed() - actual.getRed()) <= tolerance, actual.toString());
        assertTrue(Math.abs(expected.getGreen() - actual.getGreen()) <= tolerance, actual.toString());
        assertTrue(Math.abs(expected.getBlue() - actual.getBlue()) <= tolerance, actual.toString());
    }

    private static ProtocolMessage focusSettingsResponse() {
        var rule = Map.<String, Object>of(
                "enabled", true,
                "doomscrollBudgetMinutes", 10,
                "warningScore", 10,
                "gracePeriodSeconds", 60,
                "interventions", List.of("notify", "pause", "close-tab", "block"));
        return new ProtocolMessage(
                1,
                SECRET,
                "service.focusSettings",
                Map.of(
                        "revision", 1L,
                        "settings", Map.of(
                                "enabled", true,
                                "rules", Map.of(
                                        "instagram-reels", rule,
                                        "x-timeline", rule,
                                        "youtube-shorts", rule)),
                        "chromeAppliedRevision", 1L));
    }
}
