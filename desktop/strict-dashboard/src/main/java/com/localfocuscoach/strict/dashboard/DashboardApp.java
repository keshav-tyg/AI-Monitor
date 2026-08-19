package com.localfocuscoach.strict.dashboard;

import javafx.scene.Scene;
import javafx.stage.Stage;

public final class DashboardApp {
    private DashboardApp() {}

    public static void main(String[] args) {
        javafx.application.Application.launch(DashboardApplication.class, args);
    }

    public static final class DashboardApplication extends javafx.application.Application {
        private Stage stage;
        private ServiceClient client;
        private StrictModeView strictModeView;
        private UnlockChallengeView unlockChallengeView;

        @Override
        public void start(Stage primaryStage) {
            stage = primaryStage;
            client = new ServiceClient();
            stage.setTitle("Local Focus Coach — Strict Mode");
            showDashboard();
            stage.setMinWidth(620);
            stage.setMinHeight(500);
            stage.show();
        }

        @Override
        public void stop() {
            if (strictModeView != null) {
                strictModeView.dispose();
            }
            if (unlockChallengeView != null) {
                unlockChallengeView.dispose();
            }
            if (client != null) {
                client.close();
            }
        }

        private void showDashboard() {
            if (strictModeView != null) {
                strictModeView.dispose();
            }
            if (unlockChallengeView != null) {
                unlockChallengeView.dispose();
                unlockChallengeView = null;
            }
            strictModeView = new StrictModeView(client, this::showUnlockChallenge);
            setRoot(strictModeView);
        }

        private void showUnlockChallenge() {
            strictModeView.dispose();
            unlockChallengeView = new UnlockChallengeView(client, this::showDashboard);
            setRoot(unlockChallengeView);
        }

        private void setRoot(javafx.scene.Parent root) {
            if (stage.getScene() == null) {
                stage.setScene(new Scene(root, 760, 580));
            } else {
                stage.getScene().setRoot(root);
            }
        }
    }
}
