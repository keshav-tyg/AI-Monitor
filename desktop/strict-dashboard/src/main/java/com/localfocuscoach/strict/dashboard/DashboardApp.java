package com.localfocuscoach.strict.dashboard;

import java.util.List;
import java.util.Objects;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public final class DashboardApp {
    private static final double MIN_WIDTH = 840;
    private static final double MIN_HEIGHT = 620;

    private DashboardApp() {}

    public static void main(String[] args) {
        javafx.application.Application.launch(DashboardApplication.class, args);
    }

    public static final class DashboardApplication extends javafx.application.Application {
        private ServiceClient client;
        private DashboardView dashboardView;

        @Override
        public void start(Stage primaryStage) {
            try {
                Thread bootstrap = new Thread(
                        FirstRunBootstrap::runIfEligible, "first-run-bootstrap");
                bootstrap.setDaemon(true);
                bootstrap.start();
            } catch (Throwable ignored) {
            }
            client = new ServiceClient();
            primaryStage.initStyle(StageStyle.UNDECORATED);
            dashboardView = new DashboardView(client, primaryStage);
            primaryStage.setTitle("Local Focus Coach");
            primaryStage.setScene(new Scene(dashboardView, 1100, 760));
            primaryStage.setMinWidth(MIN_WIDTH);
            primaryStage.setMinHeight(MIN_HEIGHT);
            primaryStage.show();
        }

        @Override
        public void stop() {
            if (dashboardView != null) {
                dashboardView.dispose();
            }
            if (client != null) {
                client.close();
            }
        }
    }

    static final class DashboardView extends BorderPane {
        private final ServiceClient client;
        private final Button focusRulesNavigation = new Button("Focus Rules");
        private final Button strictModeNavigation = new Button("Strict Mode");
        private final BorderPane contentShell = new BorderPane();
        private final StackPane pinnedFooter = new StackPane();
        private FocusRulesView focusRulesView;
        private StrictModeView strictModeView;
        private UnlockChallengeView unlockChallengeView;
        private final Stage stage;
        private boolean disposed;
        private double dragOffsetX;
        private double dragOffsetY;
        private ResizeDirection activeResize = ResizeDirection.NONE;
        private WindowBounds resizeStart;
        private double resizeStartScreenX;
        private double resizeStartScreenY;

        DashboardView(ServiceClient client) {
            this(client, null);
        }

        DashboardView(ServiceClient client, Stage stage) {
            this.client = Objects.requireNonNull(client);
            this.stage = stage;
            getStyleClass().add("dashboard");
            var stylesheet = Objects.requireNonNull(
                    DashboardApp.class.getResource("dashboard.css"), "Missing dashboard stylesheet");
            getStylesheets().add(stylesheet.toExternalForm());
            configureTitleBar();
            configureWindowResize();
            configureNavigation();
            contentShell.setId("dashboardContentShell");
            contentShell.getStyleClass().add("dashboardContentShell");
            pinnedFooter.setId("dashboardPinnedFooter");
            pinnedFooter.getStyleClass().add("dashboardPinnedFooter");
            pinnedFooter.setManaged(false);
            pinnedFooter.setVisible(false);
            contentShell.setBottom(pinnedFooter);
            setCenter(contentShell);
            showFocusRules();
        }

        void dispose() {
            if (disposed) {
                return;
            }
            disposed = true;
            disposeCurrentView();
        }

        private void configureTitleBar() {
            var red = trafficLight("macosClose", "macosTrafficRed", "Close window", () -> {
                if (stage != null) {
                    stage.close();
                }
            });
            var yellow = trafficLight(
                    "macosMinimize", "macosTrafficYellow", "Minimize window", () -> {
                        if (stage != null) {
                            stage.setIconified(true);
                        }
                    });
            var green = trafficLight("macosZoom", "macosTrafficGreen", "Zoom window", () -> {
                if (stage != null) {
                    stage.setMaximized(!stage.isMaximized());
                }
            });
            var trafficLights = new HBox(8, red, yellow, green);
            trafficLights.setId("macosTrafficLights");
            trafficLights.setAlignment(Pos.CENTER_LEFT);

            var title = new Label("Local Focus Coach");
            title.setId("macosWindowTitle");
            title.getStyleClass().add("macosWindowTitle");

            var titleBar = new StackPane(title, trafficLights);
            titleBar.setId("macosTitleBar");
            titleBar.getStyleClass().add("macosTitleBar");
            titleBar.setMinHeight(40);
            titleBar.setPrefHeight(40);
            titleBar.setMaxHeight(40);
            StackPane.setAlignment(trafficLights, Pos.CENTER_LEFT);
            titleBar.setOnMousePressed(event -> {
                dragOffsetX = event.getSceneX();
                dragOffsetY = event.getSceneY();
            });
            titleBar.setOnMouseDragged(event -> {
                if (stage != null) {
                    stage.setX(event.getScreenX() - dragOffsetX);
                    stage.setY(event.getScreenY() - dragOffsetY);
                }
            });
            setTop(titleBar);
        }

        private static Region trafficLight(
                String id, String styleClass, String accessibleText, Runnable action) {
            var light = new Region();
            light.setId(id);
            light.getStyleClass().addAll("macosTrafficLight", styleClass);
            light.setAccessibleText(accessibleText);
            light.setFocusTraversable(false);
            light.setCursor(Cursor.HAND);
            light.setMinSize(12, 12);
            light.setPrefSize(12, 12);
            light.setMaxSize(12, 12);
            light.setOnMousePressed(MouseEvent::consume);
            light.setOnMouseDragged(MouseEvent::consume);
            light.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    action.run();
                    event.consume();
                }
            });
            return light;
        }

        private void configureWindowResize() {
            if (stage == null) {
                return;
            }
            addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
                if (activeResize == ResizeDirection.NONE) {
                    setCursor(resizeDirectionAt(event.getSceneX(), event.getSceneY()).cursor());
                }
            });
            addEventFilter(MouseEvent.MOUSE_EXITED, event -> {
                if (activeResize == ResizeDirection.NONE) {
                    setCursor(Cursor.DEFAULT);
                }
            });
            addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                if (event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                activeResize = resizeDirectionAt(event.getSceneX(), event.getSceneY());
                if (activeResize == ResizeDirection.NONE) {
                    return;
                }
                resizeStart = new WindowBounds(
                        stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
                resizeStartScreenX = event.getScreenX();
                resizeStartScreenY = event.getScreenY();
                event.consume();
            });
            addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
                if (activeResize == ResizeDirection.NONE || resizeStart == null) {
                    return;
                }
                var bounds = resizedBounds(
                        resizeStart,
                        activeResize,
                        event.getScreenX() - resizeStartScreenX,
                        event.getScreenY() - resizeStartScreenY);
                stage.setX(bounds.x());
                stage.setY(bounds.y());
                stage.setWidth(bounds.width());
                stage.setHeight(bounds.height());
                event.consume();
            });
            addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
                if (activeResize != ResizeDirection.NONE) {
                    activeResize = ResizeDirection.NONE;
                    resizeStart = null;
                    setCursor(resizeDirectionAt(event.getSceneX(), event.getSceneY()).cursor());
                    event.consume();
                }
            });
        }

        private ResizeDirection resizeDirectionAt(double x, double y) {
            var north = y <= 6;
            var south = y >= getHeight() - 6;
            var west = x <= 6;
            var east = x >= getWidth() - 6;
            if (north && west) return ResizeDirection.NORTH_WEST;
            if (north && east) return ResizeDirection.NORTH_EAST;
            if (south && west) return ResizeDirection.SOUTH_WEST;
            if (south && east) return ResizeDirection.SOUTH_EAST;
            if (north) return ResizeDirection.NORTH;
            if (south) return ResizeDirection.SOUTH;
            if (west) return ResizeDirection.WEST;
            if (east) return ResizeDirection.EAST;
            return ResizeDirection.NONE;
        }

        private static WindowBounds resizedBounds(
                WindowBounds start, ResizeDirection direction, double deltaX, double deltaY) {
            var x = start.x();
            var y = start.y();
            var width = start.width();
            var height = start.height();
            if (direction.west()) {
                width = Math.max(MIN_WIDTH, start.width() - deltaX);
                x = start.x() + start.width() - width;
            } else if (direction.east()) {
                width = Math.max(MIN_WIDTH, start.width() + deltaX);
            }
            if (direction.north()) {
                height = Math.max(MIN_HEIGHT, start.height() - deltaY);
                y = start.y() + start.height() - height;
            } else if (direction.south()) {
                height = Math.max(MIN_HEIGHT, start.height() + deltaY);
            }
            return new WindowBounds(x, y, width, height);
        }

        private void configureNavigation() {
            focusRulesNavigation.setId("focusRulesNavigation");
            focusRulesNavigation.setOnAction(event -> showFocusRules());
            focusRulesNavigation.setMaxWidth(Double.MAX_VALUE);
            focusRulesNavigation.setGraphic(DashboardControls.shieldIcon(
                    "focusRulesNavigation", false, Color.web("#52606f")));
            strictModeNavigation.setId("strictModeNavigation");
            strictModeNavigation.setOnAction(event -> showStrictMode());
            strictModeNavigation.setMaxWidth(Double.MAX_VALUE);
            strictModeNavigation.setGraphic(DashboardControls.lockIcon(
                    "strictModeNavigation", Color.web("#52606f")));

            var logo = DashboardControls.shieldIcon("dashboardBrand", true, Color.WHITE);
            logo.setId("dashboardLogo");
            logo.getStyleClass().add("dashboardLogo");
            logo.setMinSize(32, 32);
            logo.setPrefSize(32, 32);
            logo.setMaxSize(32, 32);
            var brandTitle = new Label("Local Focus");
            brandTitle.getStyleClass().add("dashboardBrandTitle");
            var brandSubtitle = new Label("Coach");
            brandSubtitle.getStyleClass().add("dashboardBrandSubtitle");
            var brandCopy = new VBox(1, brandTitle, brandSubtitle);
            var brand = new HBox(10, logo, brandCopy);
            brand.setId("dashboardBrand");
            brand.getStyleClass().add("dashboardBrand");
            brand.setAlignment(Pos.CENTER_LEFT);

            var spacer = new Region();
            VBox.setVgrow(spacer, Priority.ALWAYS);
            var privacyIndicator = new Circle(3, Color.web("#22c55e"));
            privacyIndicator.setId("dashboardPrivacyIndicator");
            var privacyTitle = new Label("Local-only privacy");
            privacyTitle.setId("dashboardPrivacyTitle");
            privacyTitle.getStyleClass().add("dashboardPrivacyTitle");
            var privacy = new Label("No browsing history or personal data leaves your device.");
            privacy.setId("dashboardPrivacy");
            privacy.setWrapText(true);
            privacy.getStyleClass().add("dashboardPrivacy");
            var privacyHeading = new HBox(8, privacyIndicator, privacyTitle);
            privacyHeading.setAlignment(Pos.CENTER_LEFT);
            var privacyPanel = new VBox(4, privacyHeading, privacy);
            privacyPanel.setId("dashboardPrivacyPanel");
            privacyPanel.getStyleClass().add("dashboardPrivacyPanel");

            var navigation = new VBox(8, focusRulesNavigation, strictModeNavigation);
            navigation.getStyleClass().add("dashboardNavigation");
            var sidebar = new VBox(20, brand, navigation, spacer, privacyPanel);
            sidebar.setId("dashboardSidebar");
            sidebar.getStyleClass().add("dashboardSidebar");
            sidebar.setAlignment(Pos.TOP_LEFT);
            sidebar.setPadding(new Insets(16, 12, 16, 12));
            sidebar.setMinWidth(208);
            sidebar.setPrefWidth(208);
            sidebar.setMaxWidth(208);
            setLeft(sidebar);
        }

        private void showFocusRules() {
            if (disposed) {
                return;
            }
            disposeCurrentView();
            focusRulesView = new FocusRulesView(client, this::showStrictMode);
            setDashboardContent(focusRulesView);
            setActiveNavigation(focusRulesNavigation);
        }

        private void showStrictMode() {
            if (disposed) {
                return;
            }
            disposeCurrentView();
            strictModeView = new StrictModeView(client, this::showUnlockChallenge);
            setDashboardContent(strictModeView);
            setActiveNavigation(strictModeNavigation);
        }

        private void showUnlockChallenge() {
            if (disposed) {
                return;
            }
            disposeCurrentView();
            unlockChallengeView = new UnlockChallengeView(client, this::showStrictMode);
            setDashboardContent(unlockChallengeView);
            setActiveNavigation(strictModeNavigation);
        }

        private void setDashboardContent(Parent content) {
            content.setStyle(content.getStyle() + "; -fx-background-color: #f0efe9;");
            pinnedFooter.getChildren().clear();
            if (content instanceof BorderPane page && page.getBottom() != null) {
                var footer = page.getBottom();
                page.setBottom(null);
                pinnedFooter.getChildren().setAll(footer);
                pinnedFooter.setManaged(true);
                pinnedFooter.setVisible(true);
            } else {
                pinnedFooter.setManaged(false);
                pinnedFooter.setVisible(false);
            }
            var scroll = new ScrollPane(content);
            scroll.setId("dashboardContentViewport");
            scroll.setFitToWidth(true);
            scroll.setPannable(true);
            scroll.getStyleClass().add("dashboardContent");
            contentShell.setCenter(scroll);
            if (getScene() != null) {
                applyCss();
            }
        }

        private void setActiveNavigation(Button active) {
            for (var navigation : List.of(focusRulesNavigation, strictModeNavigation)) {
                navigation.getStyleClass().remove("activeNavigation");
                recolorVectorIcon(navigation.getGraphic(), Color.web("#52606f"));
            }
            active.getStyleClass().add("activeNavigation");
            recolorVectorIcon(active.getGraphic(), Color.WHITE);
        }

        private static void recolorVectorIcon(Node node, Color color) {
            if (node instanceof SVGPath path) {
                path.setStroke(color);
            }
            if (node instanceof Parent parent) {
                for (var child : parent.getChildrenUnmodifiable()) {
                    recolorVectorIcon(child, color);
                }
            }
        }

        private void disposeCurrentView() {
            if (focusRulesView != null) {
                focusRulesView.dispose();
                focusRulesView = null;
            }
            if (strictModeView != null) {
                strictModeView.dispose();
                strictModeView = null;
            }
            if (unlockChallengeView != null) {
                unlockChallengeView.dispose();
                unlockChallengeView = null;
            }
        }

        private record WindowBounds(double x, double y, double width, double height) {}

        private enum ResizeDirection {
            NONE(Cursor.DEFAULT, false, false, false, false),
            NORTH(Cursor.N_RESIZE, true, false, false, false),
            SOUTH(Cursor.S_RESIZE, false, true, false, false),
            WEST(Cursor.W_RESIZE, false, false, true, false),
            EAST(Cursor.E_RESIZE, false, false, false, true),
            NORTH_WEST(Cursor.NW_RESIZE, true, false, true, false),
            NORTH_EAST(Cursor.NE_RESIZE, true, false, false, true),
            SOUTH_WEST(Cursor.SW_RESIZE, false, true, true, false),
            SOUTH_EAST(Cursor.SE_RESIZE, false, true, false, true);

            private final Cursor cursor;
            private final boolean north;
            private final boolean south;
            private final boolean west;
            private final boolean east;

            ResizeDirection(
                    Cursor cursor, boolean north, boolean south, boolean west, boolean east) {
                this.cursor = cursor;
                this.north = north;
                this.south = south;
                this.west = west;
                this.east = east;
            }

            private Cursor cursor() {
                return cursor;
            }

            private boolean north() {
                return north;
            }

            private boolean south() {
                return south;
            }

            private boolean west() {
                return west;
            }

            private boolean east() {
                return east;
            }
        }
    }
}
