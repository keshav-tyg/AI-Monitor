package com.localfocuscoach.strict.dashboard;

import java.util.Objects;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
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
            setStyle("-fx-background-color: #f7f7f4;");
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
            strictModeNavigation.setId("strictModeNavigation");
            strictModeNavigation.setOnAction(event -> showStrictMode());
            var navigation = new HBox(10, focusRulesNavigation, strictModeNavigation);
            navigation.getStyleClass().add("dashboardNavigation");
            navigation.setAlignment(Pos.CENTER_LEFT);
            navigation.setPadding(new Insets(12, 20, 0, 20));
            setTop(navigation);
        }

        private void showFocusRules() {
            if (disposed) {
                return;
            }
            disposeCurrentView();
            focusRulesView = new FocusRulesView(client, this::showStrictMode);
            var scroll = new ScrollPane(focusRulesView);
            scroll.setFitToWidth(true);
            scroll.setPannable(true);
            scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
            setDashboardContent(scroll);
            focusRulesNavigation.setDisable(true);
            strictModeNavigation.setDisable(false);
        }

        private void showStrictMode() {
            if (disposed) {
                return;
            }
            disposeCurrentView();
            strictModeView = new StrictModeView(client, this::showUnlockChallenge);
            setDashboardContent(strictModeView);
            focusRulesNavigation.setDisable(false);
            strictModeNavigation.setDisable(true);
        }

        private void showUnlockChallenge() {
            if (disposed) {
                return;
            }
            disposeCurrentView();
            unlockChallengeView = new UnlockChallengeView(client, this::showStrictMode);
            setDashboardContent(unlockChallengeView);
            focusRulesNavigation.setDisable(false);
            strictModeNavigation.setDisable(false);
        }

        private void setDashboardContent(Parent content) {
            setCenter(content);
            if (getScene() != null) {
                applyCss();
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
    }
}
