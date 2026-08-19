package com.localfocuscoach.strict.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class StrictSession {
    private final UUID id;
    private final StrictMode mode;
    private final Instant startedAt;
    private final Instant endsAt;
    private final boolean earlyExitChallenge;
    private SessionStatus status;
    private Instant warningEndsAt;

    public StrictSession(
            UUID id,
            StrictMode mode,
            Instant startedAt,
            Instant endsAt,
            boolean earlyExitChallenge,
            SessionStatus status) {
        this.id = Objects.requireNonNull(id);
        this.mode = Objects.requireNonNull(mode);
        this.startedAt = Objects.requireNonNull(startedAt);
        this.endsAt = endsAt;
        this.earlyExitChallenge = earlyExitChallenge;
        this.status = Objects.requireNonNull(status);
    }

    public UUID id() {
        return id;
    }

    public StrictMode mode() {
        return mode;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public boolean earlyExitChallenge() {
        return earlyExitChallenge;
    }

    public SessionStatus status() {
        return status;
    }

    public Instant warningEndsAt() {
        return warningEndsAt;
    }

    void startRestoreWarning(Instant endsAt) {
        warningEndsAt = endsAt;
    }

    void cancelRestoreWarning() {
        warningEndsAt = null;
    }

    void expire() {
        status = SessionStatus.EXPIRED;
        warningEndsAt = null;
    }
}
