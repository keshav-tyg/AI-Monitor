package com.localfocuscoach.strict.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MacChromeControllerTest {
    @Test
    void runningCheckTargetsTheExactGoogleChromeProcessName() {
        var runner = new CapturingRunner(0);
        var controller = new MacChromeController(runner);

        assertTrue(controller.isRunning());
        assertEquals(List.of(List.of("pgrep", "-x", "Google Chrome")), runner.commands);
    }

    @Test
    void quitRequestTargetsGoogleChromeOnly() {
        var runner = new CapturingRunner(0);
        var controller = new MacChromeController(runner);

        assertEquals(ChromeController.QuitResult.REQUESTED, controller.requestGracefulQuit());
        assertEquals(
                List.of(List.of(
                        "osascript", "-e", "tell application \"Google Chrome\" to quit")),
                runner.commands);
    }

    @Test
    void absentChromeIsReportedWithoutTryingAnotherCommand() {
        var runner = new CapturingRunner(1);
        var controller = new MacChromeController(runner);

        assertFalse(controller.isRunning());
        assertEquals(List.of(List.of("pgrep", "-x", "Google Chrome")), runner.commands);
    }

    @Test
    void failedAppleScriptReturnsFailureWithoutRetryOrEscalation() {
        var runner = new CapturingRunner(1);
        var controller = new MacChromeController(runner);

        assertEquals(ChromeController.QuitResult.FAILED, controller.requestGracefulQuit());
        assertEquals(
                List.of(List.of(
                        "osascript", "-e", "tell application \"Google Chrome\" to quit")),
                runner.commands);
    }

    private static final class CapturingRunner implements MacChromeController.CommandRunner {
        private final int exitCode;
        private final List<List<String>> commands = new ArrayList<>();

        private CapturingRunner(int exitCode) {
            this.exitCode = exitCode;
        }

        @Override
        public int run(List<String> command) {
            commands.add(List.copyOf(command));
            return exitCode;
        }
    }
}
