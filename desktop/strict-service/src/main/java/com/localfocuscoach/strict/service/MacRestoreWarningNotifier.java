package com.localfocuscoach.strict.service;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class MacRestoreWarningNotifier implements RestoreWarningNotifier {
    private static final long MAX_WARNING_SECONDS = 30;
    private static final String DIALOG_PREFIX =
            "display dialog \"Restore the Local Focus Coach Chrome extension within ";
    private static final String DIALOG_SUFFIX =
            " seconds to keep Chrome open.\" with title \"Local Focus Coach Strict Mode\" "
                    + "buttons {\"Dismiss\"} default button \"Dismiss\" giving up after ";
    private static final String DIALOG_END = " with icon caution";

    private final WarningProcessLauncher launcher;
    private final Clock clock;
    private WarningProcess activeWarning;

    public MacRestoreWarningNotifier() {
        this(new ProcessWarningLauncher(), Clock.systemUTC());
    }

    MacRestoreWarningNotifier(WarningProcessLauncher launcher, Clock clock) {
        this.launcher = Objects.requireNonNull(launcher);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public synchronized void show(Instant deadline) {
        Objects.requireNonNull(deadline);
        clear();
        var seconds = remainingSeconds(deadline);
        var script = DIALOG_PREFIX + seconds + DIALOG_SUFFIX + seconds + DIALOG_END;
        try {
            activeWarning = launcher.launch(List.of("osascript", "-e", script));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to display the Strict Mode restore warning", exception);
        }
    }

    @Override
    public synchronized void clear() {
        if (activeWarning == null) {
            return;
        }
        activeWarning.terminate();
        activeWarning = null;
    }

    private long remainingSeconds(Instant deadline) {
        var duration = Duration.between(clock.instant(), deadline);
        var seconds = duration.getSeconds() + (duration.getNano() == 0 ? 0 : 1);
        return Math.max(1, Math.min(MAX_WARNING_SECONDS, seconds));
    }

    @FunctionalInterface
    interface WarningProcessLauncher {
        WarningProcess launch(List<String> command) throws IOException;
    }

    @FunctionalInterface
    interface WarningProcess {
        void terminate();
    }

    private static final class ProcessWarningLauncher implements WarningProcessLauncher {
        @Override
        public WarningProcess launch(List<String> command) throws IOException {
            var process = new ProcessBuilder(command)
                    .redirectInput(Redirect.DISCARD)
                    .redirectOutput(Redirect.DISCARD)
                    .redirectError(Redirect.DISCARD)
                    .start();
            return () -> {
                if (process.isAlive()) {
                    process.destroy();
                }
            };
        }
    }
}
