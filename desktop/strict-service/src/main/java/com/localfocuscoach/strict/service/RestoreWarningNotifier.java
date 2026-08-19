package com.localfocuscoach.strict.service;

import java.time.Instant;

public interface RestoreWarningNotifier extends AutoCloseable {
    RestoreWarningNotifier NOOP = new RestoreWarningNotifier() {
        @Override
        public void show(Instant deadline) {}

        @Override
        public void clear() {}
    };

    void show(Instant deadline);

    void clear();

    @Override
    default void close() {
        clear();
    }
}
