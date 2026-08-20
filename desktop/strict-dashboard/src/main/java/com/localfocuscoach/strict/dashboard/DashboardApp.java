package com.localfocuscoach.strict.dashboard;

import java.util.List;
import java.util.Objects;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public final class DashboardApp {
    private DashboardApp() {}

    public static void main(String[] args) {
        javafx.application.Application.launch(DashboardApplication.class, args);
    }

    public static final class DashboardApplication extends javafx.application.Application {
        private ServiceClient client;
        private DashboardView dashboardView;

        @Override
        public void start(Stage primaryStage) {
            client = new ServiceClient();
            primaryStage.initStyle(StageStyle.UNDECORATED);
            dashboardView = new DashboardView(client, primaryStage);
            primaryStage.setTitle("Local Focus Coach");
            primaryStage.setScene(new Scene(dashboardView, 1100, 760));
            primaryStage.setMinWidth(840);
            primaryStage.setMinHeight(620);
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
            var red = trafficLight("macosClose", "macosTrafficRed");
            var yellow = trafficLight("macosMinimize", "macosTrafficYellow");
            var green = trafficLight("macosZoom", "macosTrafficGreen");
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

        private static Region trafficLight(String id, String styleClass) {
            var light = new Region();
            light.setId(id);
            light.getStyleClass().addAll("macosTrafficLight", styleClass);
            light.setMinSize(12, 12);
            light.setPrefSize(12, 12);
            light.setMaxSize(12, 12);
            return light;
        }

        private void configureNavigation() {
            focusRulesNavigation.setId("focusRulesNavigation");
            focusRulesNavigation.setOnAction(event -> showFocusRules());
            focusRulesNavigation.setMaxWidth(Double.MAX_VALUE);
            strictModeNavigation.setId("strictModeNavigation");
            strictModeNavigation.setOnAction(event -> showStrictMode());
            strictModeNavigation.setMaxWidth(Double.MAX_VALUE);

            var logo = new Label("⌾");
            logo.setId("dashboardLogo");
            logo.getStyleClass().add("dashboardLogo");
            logo.setAlignment(Pos.CENTER);
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
            var privacyTitle = new Label("●  Local-only privacy");
            privacyTitle.getStyleClass().add("dashboardPrivacyTitle");
            var privacy = new Label("No browsing history or personal data leaves your device.");
            privacy.setId("dashboardPrivacy");
            privacy.setWrapText(true);
            privacy.getStyleClass().add("dashboardPrivacy");
            var privacyPanel = new VBox(4, privacyTitle, privacy);
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
            }
            active.getStyleClass().add("activeNavigation");
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
    }
}
