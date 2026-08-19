package com.localfocuscoach.strict.service;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Opens only the packaged Local Focus Coach application through the macOS app launcher. */
public final class MacDashboardLauncher implements DashboardLauncher {
    private static final List<String> OPEN_COMMAND =
            List.of("open", "-a", "Local Focus Coach");
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TERMINATION_TIMEOUT = Duration.ofMillis(250);

    private final CommandRunner runner;

    public MacDashboardLauncher() {
        this(new ProcessCommandRunner(
                command -> new ProcessBuilder(command)
                        .redirectInput(Redirect.from(ProcessBuilder.Redirect.DISCARD.file()))
                        .redirectOutput(Redirect.DISCARD)
                        .redirectError(Redirect.DISCARD)
                        .start(),
                COMMAND_TIMEOUT,
                TERMINATION_TIMEOUT));
    }

    public MacDashboardLauncher(CommandRunner runner) {
        this.runner = Objects.requireNonNull(runner);
    }

    @Override
    public void open() {
        try {
            runner.run(OPEN_COMMAND);
        } catch (IOException exception) {
            // A failed app launch does not broaden the request or stop the local service.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    public interface CommandRunner {
        int run(List<String> command) throws IOException, InterruptedException;
    }

    static final class ProcessCommandRunner implements CommandRunner {
        private final ProcessStarter starter;
        private final Duration commandTimeout;
        private final Duration terminationTimeout;

        ProcessCommandRunner(
                ProcessStarter starter, Duration commandTimeout, Duration terminationTimeout) {
            this.starter = Objects.requireNonNull(starter);
            this.commandTimeout = requireNonNegative(commandTimeout, "command timeout");
            this.terminationTimeout = requireNonNegative(terminationTimeout, "termination timeout");
        }

        @Override
        public int run(List<String> command) throws IOException, InterruptedException {
            var process = starter.start(command);
            try {
                if (!process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    terminate(process);
                    throw new IOException("Command timed out");
                }
                return process.exitValue();
            } catch (InterruptedException exception) {
                terminate(process);
                throw exception;
            }
        }

        private void terminate(Process process) throws InterruptedException {
            process.destroy();
            if (!process.waitFor(terminationTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        }

        private static Duration requireNonNegative(Duration duration, String name) {
            Objects.requireNonNull(duration);
            if (duration.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return duration;
        }
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }
}
