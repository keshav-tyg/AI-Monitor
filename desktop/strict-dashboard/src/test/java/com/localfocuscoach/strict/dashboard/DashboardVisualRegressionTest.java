package com.localfocuscoach.strict.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localfocuscoach.strict.protocol.ProtocolMessage;
import java.util.List;
import java.util.Map;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
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

                assertNotNull(protection);
                assertEquals(2, cards.getColumnConstraints().size());
                assertTrue(protection.getWidth() >= cards.getWidth() - 2.0);
                assertEquals(instagram.getWidth(), youtube.getWidth(), 1.0);
                assertTrue(youtube.localToScene(youtube.getBoundsInLocal()).getMinX()
                        > instagram.localToScene(instagram.getBoundsInLocal()).getMinX());
                assertTrue(status.isVisible());
                assertNotNull(status.getScene());
                assertTrue(status.localToScene(status.getBoundsInLocal()).getMinY() > 700.0);
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

    private static void assertColorNear(Color expected, Color actual) {
        double tolerance = 0.02;
        assertTrue(Math.abs(expected.getRed() - actual.getRed()) <= tolerance, actual.toString());
        assertTrue(Math.abs(expected.getGreen() - actual.getGreen()) <= tolerance, actual.toString());
        assertTrue(Math.abs(expected.getBlue() - actual.getBlue()) <= tolerance, actual.toString());
    }

    private static ProtocolMessage focusSettingsResponse() {
        var rule = Map.<String, Object>of(
                "enabled", false,
                "doomscrollBudgetMinutes", 5,
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
                                "enabled", false,
                                "rules", Map.of(
                                        "instagram-reels", rule,
                                        "x-timeline", rule,
                                        "youtube-shorts", rule)),
                        "chromeAppliedRevision", 1L));
    }
}
