package com.localfocuscoach.strict.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localfocuscoach.strict.protocol.ProtocolMessage;
import java.util.List;
import java.util.Map;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

class DashboardAppTest {
    private static final String SECRET = "dashboard-test-secret";

    @Test
    void rendersTheReferenceMacOsShellWithoutAStage() {
        var client = client(false);
        var result = FxTestSupport.call(() -> {
            var view = new DashboardApp.DashboardView(client);
            var scene = new Scene(view, 1100, 760);
            view.applyCss();
            view.layout();
            return new Object[] {view, scene};
        });
        var dashboard = (DashboardApp.DashboardView) result[0];
        var scene = (Scene) result[1];
        try {
            FxTestSupport.call(() -> {
                assertEquals(1100.0, scene.getWidth(), 0.1);
                assertEquals(760.0, scene.getHeight(), 0.1);
                var titleBar = (Region) dashboard.lookup("#macosTitleBar");
                assertNotNull(titleBar);
                assertEquals(40.0, titleBar.prefHeight(-1), 0.1);
                var sidebar = (Region) dashboard.lookup("#dashboardSidebar");
                assertNotNull(sidebar);
                assertEquals(208.0, sidebar.prefWidth(-1), 0.1);
                assertNotNull(dashboard.lookup("#dashboardContentViewport"));
                assertNotNull(dashboard.lookup("#dashboardPrivacy"));
                return null;
            });
        } finally {
            dispose(dashboard, client);
        }
    }

    @Test
    void keepsThePageFooterOutsideTheScrollableContentViewport() {
        var client = client(false);
        var dashboard = FxTestSupport.call(() -> {
            var view = new DashboardApp.DashboardView(client);
            new Scene(view, 1100, 760);
            view.applyCss();
            view.layout();
            return view;
        });
        try {
            FxTestSupport.waitFor(
                    () -> dashboard.lookup("#focusSaveStatus") != null,
                    "Focus Rules status footer");
            FxTestSupport.call(() -> {
                var scroll = dashboard.lookup("#dashboardContentViewport");
                var status = dashboard.lookup("#focusSaveStatus");
                assertFalse(isDescendantOf(status, scroll));
                assertNotNull(dashboard.lookup("#dashboardPinnedFooter"));
                return null;
            });
        } finally {
            dispose(dashboard, client);
        }
    }

    @Test
    void appliesAnExplicitReadableLightThemeRegardlessOfTheMacAppearance() {
        var client = client(false);
        var dashboard = FxTestSupport.call(() -> {
            var view = new DashboardApp.DashboardView(client);
            new Scene(view, 1100, 760);
            view.applyCss();
            return view;
        });
        try {
            FxTestSupport.call(() -> {
                assertEquals(Color.web("#1c1c1e"), ((Label) dashboard.lookup(".label")).getTextFill());
                var privacy = (Label) dashboard.lookup("#dashboardPrivacy");
                assertEquals(
                        "No browsing history or personal data leaves your device.",
                        privacy.getText());
                assertEquals(Color.web("#6e6e73"), privacy.getTextFill());
                return null;
            });
        } finally {
            dispose(dashboard, client);
        }
    }

    @Test
    void landsOnFocusRulesAndNavigatesClearlyBetweenBothDashboardSections() {
        var client = client(false);
        var dashboard = FxTestSupport.call(() -> {
            var view = new DashboardApp.DashboardView(client);
            new Scene(view, 1100, 760);
            view.applyCss();
            return view;
        });
        try {
            FxTestSupport.waitFor(
                    () -> dashboard.lookup("#focusProtectionEnabled") != null,
                    "default Focus Rules screen");

            FxTestSupport.call(() -> {
                assertNotNull(dashboard.lookup("#dashboardSidebar"));
                assertNotNull(dashboard.lookup("#dashboardBrand"));
                assertNotNull(dashboard.lookup("#dashboardPrivacy"));
                assertNotNull(dashboard.lookup("#focusRulesNavigation"));
                assertNotNull(dashboard.lookup("#strictModeNavigation"));
                assertTrue(((Button) dashboard.lookup("#focusRulesNavigation"))
                        .getStyleClass().contains("activeNavigation"));
                assertFalse(((Button) dashboard.lookup("#strictModeNavigation")).isDisable());
                ((Button) dashboard.lookup("#strictModeNavigation")).fire();
                return null;
            });
            FxTestSupport.waitFor(
                    () -> dashboard.lookup("#durationMinutes") != null,
                    "Strict Mode screen");
            assertNull(FxTestSupport.call(() -> dashboard.lookup("#focusProtectionEnabled")));
            FxTestSupport.call(() -> {
                var focusRules = (Button) dashboard.lookup("#focusRulesNavigation");
                var strictMode = (Button) dashboard.lookup("#strictModeNavigation");
                assertTrue(strictMode.getStyleClass().contains("activeNavigation"));
                assertFalse(focusRules.getStyleClass().contains("activeNavigation"));
                assertFalse(focusRules.isDisable());
                assertFalse(strictMode.isDisable());
                return null;
            });

            FxTestSupport.call(() -> {
                ((Button) dashboard.lookup("#focusRulesNavigation")).fire();
                return null;
            });
            FxTestSupport.waitFor(
                    () -> dashboard.lookup("#focusProtectionEnabled") != null,
                    "Focus Rules navigation return");
        } finally {
            dispose(dashboard, client);
        }
    }

    @Test
    void unlockChallengeReturnsToStrictModeWithoutChangingTheDefaultLanding() {
        var client = client(true);
        var dashboard = FxTestSupport.call(() -> {
            var view = new DashboardApp.DashboardView(client);
            new Scene(view, 1100, 760);
            view.applyCss();
            return view;
        });
        try {
            FxTestSupport.call(() -> {
                ((Button) dashboard.lookup("#strictModeNavigation")).fire();
                return null;
            });
            FxTestSupport.waitFor(
                    () -> dashboard.lookup("#unlockSession") != null,
                    "active Strict Mode screen");
            FxTestSupport.call(() -> {
                ((Button) dashboard.lookup("#unlockSession")).fire();
                return null;
            });
            FxTestSupport.waitFor(
                    () -> dashboard.lookup("#challengeTarget") != null,
                    "unlock challenge screen");
            FxTestSupport.call(() -> {
                ((Button) dashboard.lookup("#backToDashboard")).fire();
                return null;
            });
            FxTestSupport.waitFor(
                    () -> dashboard.lookup("#unlockSession") != null,
                    "Strict Mode return after challenge");
            assertNull(FxTestSupport.call(() -> dashboard.lookup("#challengeTarget")));
        } finally {
            dispose(dashboard, client);
        }
    }

    private static ServiceClient client(boolean active) {
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
                                    "startedAt", "2026-08-19T12:00:00Z",
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
                            "target", "abc",
                            "createdAt", "2026-08-19T12:00:00Z"));
            default -> throw new AssertionError("Unexpected dashboard request " + request.type());
        });
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

    private static boolean isDescendantOf(Node node, Node ancestor) {
        for (var parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static void dispose(DashboardApp.DashboardView dashboard, ServiceClient client) {
        FxTestSupport.call(() -> {
            dashboard.dispose();
            return null;
        });
        client.close();
    }
}
