package com.localfocuscoach.strict.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MacRestoreWarningNotifierTest {
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    @Test
    void warningUsesALocalMacDialogWithoutTargetingChrome() {
        var launcher = new RecordingLauncher();
        var notifier = new MacRestoreWarningNotifier(
                launcher, Clock.fixed(NOW, ZoneId.of("UTC")));

        notifier.show(NOW.plusSeconds(30));

        assertEquals("osascript", launcher.command.get(0));
        assertEquals("-e", launcher.command.get(1));
        assertTrue(launcher.command.get(2).contains("Restore the Local Focus Coach Chrome extension"));
        assertTrue(launcher.command.get(2).contains("giving up after 30"));
        assertTrue(launcher.command.stream().noneMatch(part -> part.contains("kill")));
    }

    @Test
    void clearingTheWarningTerminatesOnlyItsDialogProcess() {
        var launcher = new RecordingLauncher();
        var notifier = new MacRestoreWarningNotifier(
                launcher, Clock.fixed(NOW, ZoneId.of("UTC")));
        notifier.show(NOW.plusSeconds(30));

        notifier.clear();

        assertEquals(1, launcher.warningProcess.terminateCount);
    }

    private static final class RecordingLauncher
            implements MacRestoreWarningNotifier.WarningProcessLauncher {
        private List<String> command = new ArrayList<>();
        private final RecordingWarningProcess warningProcess = new RecordingWarningProcess();

        @Override
        public MacRestoreWarningNotifier.WarningProcess launch(List<String> command) {
            this.command = List.copyOf(command);
            return warningProcess;
        }
    }

    private static final class RecordingWarningProcess
            implements MacRestoreWarningNotifier.WarningProcess {
        private int terminateCount;

        @Override
        public void terminate() {
            terminateCount++;
        }
    }
}
