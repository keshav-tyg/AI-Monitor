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
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

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
            dashboardView = new DashboardView(client);
            primaryStage.setTitle("Local Focus Coach");
            primaryStage.setScene(new Scene(dashboardView, 760, 580));
            primaryStage.setMinWidth(620);
            primaryStage.setMinHeight(500);
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
        private FocusRulesView focusRulesView;
        private StrictModeView strictModeView;
        private UnlockChallengeView unlockChallengeView;
        private boolean disposed;

        DashboardView(ServiceClient client) {
            this.client = Objects.requireNonNull(client);
            getStyleClass().add("dashboard");
            var stylesheet = Objects.requireNonNull(
                    DashboardApp.class.getResource("dashboard.css"), "Missing dashboard stylesheet");
            getStylesheets().add(stylesheet.toExternalForm());
            configureNavigation();
            showFocusRules();
        }

        void dispose() {
            if (disposed) {
                return;
            }
            disposed = true;
            disposeCurrentView();
        }

        private void configureNavigation() {
            focusRulesNavigation.setId("focusRulesNavigation");
            focusRulesNavigation.setOnAction(event -> showFocusRules());
            focusRulesNavigation.setMaxWidth(Double.MAX_VALUE);
            strictModeNavigation.setId("strictModeNavigation");
            strictModeNavigation.setOnAction(event -> showStrictMode());
            strictModeNavigation.setMaxWidth(Double.MAX_VALUE);

            var brandTitle = new Label("LOCAL FOCUS");
            brandTitle.getStyleClass().add("dashboardBrandTitle");
            var brandSubtitle = new Label("Coach dashboard");
            brandSubtitle.getStyleClass().add("dashboardBrandSubtitle");
            var brand = new VBox(4, brandTitle, brandSubtitle);
            brand.setId("dashboardBrand");
            brand.getStyleClass().add("dashboardBrand");

            var spacer = new Region();
            VBox.setVgrow(spacer, Priority.ALWAYS);
            var privacy = new Label("Your focus data stays on this device.");
            privacy.setId("dashboardPrivacy");
            privacy.setWrapText(true);
            privacy.getStyleClass().add("dashboardPrivacy");

            var navigation = new VBox(8, focusRulesNavigation, strictModeNavigation);
            navigation.getStyleClass().add("dashboardNavigation");
            var sidebar = new VBox(24, brand, navigation, spacer, privacy);
            sidebar.setId("dashboardSidebar");
            sidebar.getStyleClass().add("dashboardSidebar");
            sidebar.setAlignment(Pos.TOP_LEFT);
            sidebar.setPadding(new Insets(28, 20, 24, 20));
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
            var scroll = new ScrollPane(content);
            scroll.setFitToWidth(true);
            scroll.setPannable(true);
            scroll.getStyleClass().add("dashboardContent");
            setCenter(scroll);
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
