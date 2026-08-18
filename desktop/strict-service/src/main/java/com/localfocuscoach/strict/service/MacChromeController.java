package com.localfocuscoach.strict.service;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.util.List;
import java.util.Objects;

public final class MacChromeController implements ChromeController {
    private static final List<String> RUNNING_COMMAND = List.of("pgrep", "-x", "Google Chrome");
    private static final List<String> QUIT_COMMAND = List.of(
            "osascript", "-e", "tell application \"Google Chrome\" to quit");

    private final CommandRunner runner;

    public MacChromeController() {
        this(new ProcessCommandRunner());
    }

    public MacChromeController(CommandRunner runner) {
        this.runner = Objects.requireNonNull(runner);
    }

    @Override
    public boolean isRunning() {
        final int exitCode;
        try {
            exitCode = runner.run(RUNNING_COMMAND);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect Google Chrome", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Google Chrome inspection was interrupted", exception);
        }
        if (exitCode == 0) {
            return true;
        }
        if (exitCode == 1) {
            return false;
        }
        throw new IllegalStateException("Unable to inspect Google Chrome");
    }

    @Override
    public QuitResult requestGracefulQuit() {
        try {
            return runner.run(QUIT_COMMAND) == 0 ? QuitResult.REQUESTED : QuitResult.FAILED;
        } catch (IOException exception) {
            return QuitResult.FAILED;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return QuitResult.FAILED;
        }
    }

    @FunctionalInterface
    public interface CommandRunner {
        int run(List<String> command) throws IOException, InterruptedException;
    }

    private static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public int run(List<String> command) throws IOException, InterruptedException {
            var process = new ProcessBuilder(command)
                    .redirectInput(Redirect.from(ProcessBuilder.Redirect.DISCARD.file()))
                    .redirectOutput(Redirect.DISCARD)
                    .redirectError(Redirect.DISCARD)
                    .start();
            return process.waitFor();
        }
    }
}
