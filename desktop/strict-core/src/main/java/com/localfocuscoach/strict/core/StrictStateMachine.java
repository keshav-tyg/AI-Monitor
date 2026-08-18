package com.localfocuscoach.strict.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class StrictStateMachine {
    private static final Duration RESTORE_WARNING_DURATION = Duration.ofSeconds(30);

    public StrictAction advance(
            StrictSession session, ConnectionHealth health, boolean chromeRunning, Instant now) {
        Objects.requireNonNull(session);
        Objects.requireNonNull(health);
        Objects.requireNonNull(now);

        if (session.status() != SessionStatus.ACTIVE) {
            return StrictAction.NONE;
        }
        if (session.mode() == StrictMode.TIMED && !now.isBefore(session.endsAt())) {
            session.expire();
            return StrictAction.EXPIRE_SESSION;
        }
        if (!chromeRunning) {
            session.cancelRestoreWarning();
            return StrictAction.NONE;
        }
        if (health == ConnectionHealth.HEALTHY) {
            if (session.warningEndsAt() != null) {
                session.cancelRestoreWarning();
                return StrictAction.CANCEL_RESTORE_WARNING;
            }
            return StrictAction.NONE;
        }
        if (session.warningEndsAt() == null) {
            session.startRestoreWarning(now.plus(RESTORE_WARNING_DURATION));
            return StrictAction.SHOW_RESTORE_WARNING;
        }
        return now.isBefore(session.warningEndsAt()) ? StrictAction.NONE : StrictAction.QUIT_CHROME;
    }

    public StrictAction requestEarlyUnlock(StrictSession session) {
        Objects.requireNonNull(session);

        if (session.status() != SessionStatus.ACTIVE) {
            return StrictAction.NONE;
        }
        if (session.mode() == StrictMode.INDEFINITE || session.earlyExitChallenge()) {
            return StrictAction.BEGIN_UNLOCK_CHALLENGE;
        }
        return StrictAction.NONE;
    }
}
