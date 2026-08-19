package com.localfocuscoach.strict.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

    @Test
    void timedOutProcessInspectionMapsToAnUnknownState() {
        var controller = new MacChromeController(command -> {
            throw new IOException("command timed out");
        });

        assertThrows(IllegalStateException.class, controller::isRunning);
    }

    @Test
    void timedOutGracefulQuitReturnsFailureWithoutEscalation() {
        var controller = new MacChromeController(command -> {
            throw new IOException("command timed out");
        });

        assertEquals(ChromeController.QuitResult.FAILED, controller.requestGracefulQuit());
    }

    @Test
    void processRunnerTerminatesAChildThatExceedsItsDeadline() {
        var process = new TimedProcess(true);
        var runner = new MacChromeController.ProcessCommandRunner(
                command -> process, Duration.ZERO, Duration.ZERO);

        assertThrows(IOException.class, () -> runner.run(List.of("pgrep")));

        assertEquals(1, process.destroyCount);
        assertEquals(0, process.destroyForciblyCount);
    }

    @Test
    void processRunnerForceTerminatesOnlyItsUnresponsiveTimedOutChild() {
        var process = new TimedProcess(false);
        var runner = new MacChromeController.ProcessCommandRunner(
                command -> process, Duration.ZERO, Duration.ZERO);

        assertThrows(IOException.class, () -> runner.run(List.of("osascript")));

        assertEquals(1, process.destroyCount);
        assertEquals(1, process.destroyForciblyCount);
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

    private static final class TimedProcess extends Process {
        private final boolean exitsAfterDestroy;
        private int timedWaitCount;
        private int destroyCount;
        private int destroyForciblyCount;

        private TimedProcess(boolean exitsAfterDestroy) {
            this.exitsAfterDestroy = exitsAfterDestroy;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            throw new AssertionError("unbounded waitFor must never be called");
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            timedWaitCount++;
            return timedWaitCount > 1 && exitsAfterDestroy;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyCount++;
        }

        @Override
        public Process destroyForcibly() {
            destroyForciblyCount++;
            return this;
        }
    }
}
