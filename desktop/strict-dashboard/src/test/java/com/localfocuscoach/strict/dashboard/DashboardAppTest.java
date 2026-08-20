package com.localfocuscoach.strict.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localfocuscoach.strict.protocol.ProtocolMessage;
import java.util.List;
import java.util.Map;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
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
    void usesVectorShellIconsAndASeparatePrivacyIndicator() {
        var client = client(false);
        var dashboard = FxTestSupport.call(() -> {
            var view = new DashboardApp.DashboardView(client);
            new Scene(view, 1100, 760);
            view.applyCss();
            return view;
        });
        try {
            FxTestSupport.call(() -> {
                assertInstanceOf(SVGPath.class, dashboard.lookup("#dashboardBrandShield"));
                assertInstanceOf(SVGPath.class, dashboard.lookup("#dashboardBrandCheck"));
                assertInstanceOf(SVGPath.class, dashboard.lookup("#focusRulesNavigationShield"));
                assertInstanceOf(SVGPath.class, dashboard.lookup("#strictModeNavigationLockBody"));
                assertInstanceOf(SVGPath.class, dashboard.lookup("#strictModeNavigationLockShackle"));
                assertEquals(
                        Color.WHITE,
                        ((SVGPath) dashboard.lookup("#focusRulesNavigationShield")).getStroke());
                assertEquals(
                        Color.web("#52606f"),
                        ((SVGPath) dashboard.lookup("#strictModeNavigationLockBody")).getStroke());
                var indicator = assertInstanceOf(
                        Circle.class, dashboard.lookup("#dashboardPrivacyIndicator"));
                assertEquals(Color.web("#22c55e"), indicator.getFill());
                var title = (Label) dashboard.lookup("#dashboardPrivacyTitle");
                assertEquals("Local-only privacy", title.getText());
                assertEquals(Color.web("#1c1c1e"), title.getTextFill());
                assertFalse(title.getText().contains("●"));
                ((Button) dashboard.lookup("#strictModeNavigation")).fire();
                return null;
            });
            FxTestSupport.waitFor(
                    () -> dashboard.lookup("#durationMinutes") != null,
                    "Strict Mode for navigation icon state");
            FxTestSupport.call(() -> {
                assertEquals(
                        Color.web("#52606f"),
                        ((SVGPath) dashboard.lookup("#focusRulesNavigationShield")).getStroke());
                assertEquals(
                        Color.WHITE,
                        ((SVGPath) dashboard.lookup("#strictModeNavigationLockBody")).getStroke());
                return null;
            });
        } finally {
            dispose(dashboard, client);
        }
    }

    @Test
    void edgeAndCornerResizeHonorsTheReferenceMinimumSize() {
        var client = client(false);
        FxTestSupport.call(() -> {
            var stage = new Stage();
            stage.initStyle(StageStyle.UNDECORATED);
            var dashboard = new DashboardApp.DashboardView(client, stage);
            stage.setScene(new Scene(dashboard, 900, 700));
            stage.setX(100);
            stage.setY(100);
            stage.setWidth(900);
            stage.setHeight(700);
            dashboard.applyCss();
            dashboard.layout();
            try {
                fireMouse(dashboard, MouseEvent.MOUSE_MOVED, 1, 1, 101, 101, MouseButton.NONE);
                assertEquals(Cursor.NW_RESIZE, dashboard.getCursor());

                fireMouse(dashboard, MouseEvent.MOUSE_PRESSED, 1, 1, 101, 101, MouseButton.PRIMARY);
                fireMouse(dashboard, MouseEvent.MOUSE_DRAGGED, 400, 400, 500, 500, MouseButton.PRIMARY);

                assertEquals(840.0, stage.getWidth(), 0.1);
                assertEquals(620.0, stage.getHeight(), 0.1);
                assertEquals(160.0, stage.getX(), 0.1);
                assertEquals(180.0, stage.getY(), 0.1);
            } finally {
                dashboard.dispose();
                stage.close();
            }
            return null;
        });
        client.close();
    }

    @Test
    void trafficLightsControlTheUndecoratedRuntimeStage() {
        var client = client(false);
        FxTestSupport.call(() -> {
            var stage = new Stage();
            stage.initStyle(StageStyle.UNDECORATED);
            var dashboard = new DashboardApp.DashboardView(client, stage);
            stage.setScene(new Scene(dashboard, 1100, 760));
            stage.show();
            stage.setMaximized(false);
            try {
                assertEquals(StageStyle.UNDECORATED, stage.getStyle());

                fireMouse(
                        dashboard.lookup("#macosZoom"),
                        MouseEvent.MOUSE_CLICKED,
                        6,
                        6,
                        80,
                        20,
                        MouseButton.PRIMARY);
                assertTrue(stage.isMaximized());
                fireMouse(
                        dashboard.lookup("#macosZoom"),
                        MouseEvent.MOUSE_CLICKED,
                        6,
                        6,
                        80,
                        20,
                        MouseButton.PRIMARY);
                assertFalse(stage.isMaximized());

                fireMouse(
                        dashboard.lookup("#macosMinimize"),
                        MouseEvent.MOUSE_CLICKED,
                        6,
                        6,
                        58,
                        20,
                        MouseButton.PRIMARY);
                assertTrue(stage.isIconified());
                stage.setIconified(false);

                fireMouse(
                        dashboard.lookup("#macosClose"),
                        MouseEvent.MOUSE_CLICKED,
                        6,
                        6,
                        34,
                        20,
                        MouseButton.PRIMARY);
                assertFalse(stage.isShowing());
            } finally {
                dashboard.dispose();
                stage.close();
            }
            return null;
        });
        client.close();
    }

    @Test
    void hidesScrollbarChromeWithoutDisablingProgrammaticScrolling() {
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
                    () -> dashboard.lookup("#instagramReelsRule") != null,
                    "scrollable Focus Rules cards");
            FxTestSupport.call(() -> {
                dashboard.applyCss();
                dashboard.layout();
                var viewport = (ScrollPane) dashboard.lookup("#dashboardContentViewport");
                var scrollbars = viewport.lookupAll(".scroll-bar").stream()
                        .map(ScrollBar.class::cast)
                        .toList();
                assertFalse(scrollbars.isEmpty());
                for (var scrollbar : scrollbars) {
                    assertEquals(0.0, scrollbar.getOpacity(), 0.01);
                }
                viewport.setVvalue(1.0);
                assertEquals(1.0, viewport.getVvalue(), 0.01);
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

    private static void fireMouse(
            Node target,
            javafx.event.EventType<MouseEvent> type,
            double x,
            double y,
            double screenX,
            double screenY,
            MouseButton button) {
        target.fireEvent(new MouseEvent(
                type,
                x,
                y,
                screenX,
                screenY,
                button,
                button == MouseButton.NONE ? 0 : 1,
                false,
                false,
                false,
                false,
                button == MouseButton.PRIMARY,
                false,
                false,
                false,
                false,
                false,
                null));
    }

    private static void dispose(DashboardApp.DashboardView dashboard, ServiceClient client) {
        FxTestSupport.call(() -> {
            dashboard.dispose();
            return null;
        });
        client.close();
    }
}
