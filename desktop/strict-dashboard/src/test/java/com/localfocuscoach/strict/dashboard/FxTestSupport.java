package com.localfocuscoach.strict.dashboard;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;

final class FxTestSupport {
    private static final long TIMEOUT_SECONDS = 10;

    static {
        var started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        await(started);
        Platform.setImplicitExit(false);
    }

    private FxTestSupport() {}

    static <T> T call(Callable<T> action) {
        if (Platform.isFxApplicationThread()) {
            try {
                return action.call();
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        try {
            return task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    static void waitFor(BooleanSupplier condition, String description) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (call(condition::getAsBoolean)) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("JavaFX toolkit did not start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
