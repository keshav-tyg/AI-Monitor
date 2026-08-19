package com.localfocuscoach.strict.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StrictStateMachineTest {
    private final StrictStateMachine machine = new StrictStateMachine();
    private final Instant now = Instant.parse("2026-08-18T12:00:00Z");

    @Test
    void warnsOnlyWhenChromeRunsWithoutExtension() {
        var action = machine.advance(activeTimed(), ConnectionHealth.DISCONNECTED, true, now);

        assertEquals(StrictAction.SHOW_RESTORE_WARNING, action);
    }

    @Test
    void expiresTimedSessionWithoutAChallenge() {
        var session = timedEndingAt(now);
        var action = machine.advance(session, ConnectionHealth.HEALTHY, true, now);

        assertEquals(StrictAction.EXPIRE_SESSION, action);
        assertEquals(SessionStatus.EXPIRED, session.status());
    }

    @Test
    void indefiniteSessionRequestsChallengeBeforeUnlock() {
        assertEquals(StrictAction.BEGIN_UNLOCK_CHALLENGE, machine.requestEarlyUnlock(indefinite()));
    }

    @Test
    void reconnectBeforeWarningDeadlineCancelsRestoreWarning() {
        var session = activeTimed();
        machine.advance(session, ConnectionHealth.DISCONNECTED, true, now);

        var action = machine.advance(session, ConnectionHealth.HEALTHY, true, now.plusSeconds(29));

        assertEquals(StrictAction.CANCEL_RESTORE_WARNING, action);
    }

    @Test
    void warningDeadlineExpiryQuitsChrome() {
        var session = activeTimed();
        machine.advance(session, ConnectionHealth.DISCONNECTED, true, now);

        var action = machine.advance(session, ConnectionHealth.DISCONNECTED, true, now.plusSeconds(30));

        assertEquals(StrictAction.QUIT_CHROME, action);
    }

    @Test
    void normalTimedExpiryDoesNotRequestUnlockChallenge() {
        var session = timedEndingAt(now);
        machine.advance(session, ConnectionHealth.HEALTHY, true, now);

        assertEquals(StrictAction.NONE, machine.requestEarlyUnlock(session));
    }

    @Test
    void timedEarlyExitRequestsChallengeOnlyWhenConfigured() {
        var withoutChallenge = activeTimed();
        var withChallenge = new StrictSession(
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                StrictMode.TIMED,
                now.minusSeconds(60),
                now.plusSeconds(60),
                true,
                SessionStatus.ACTIVE);

        assertEquals(StrictAction.NONE, machine.requestEarlyUnlock(withoutChallenge));
        assertEquals(StrictAction.BEGIN_UNLOCK_CHALLENGE, machine.requestEarlyUnlock(withChallenge));
    }

    @Test
    void missingExtensionDoesNotWarnWhenChromeIsNotRunning() {
        var action = machine.advance(activeTimed(), ConnectionHealth.DISCONNECTED, false, now);

        assertEquals(StrictAction.NONE, action);
    }

    @Test
    void reopeningChromeAfterItClosedDuringWarningStartsANewWarning() {
        var session = timedEndingAt(now.plusSeconds(120));
        machine.advance(session, ConnectionHealth.DISCONNECTED, true, now);
        machine.advance(session, ConnectionHealth.DISCONNECTED, false, now.plusSeconds(10));

        var action = machine.advance(session, ConnectionHealth.DISCONNECTED, true, now.plusSeconds(31));

        assertEquals(StrictAction.SHOW_RESTORE_WARNING, action);
    }

    private StrictSession activeTimed() {
        return timedEndingAt(now.plusSeconds(60));
    }

    private StrictSession timedEndingAt(Instant endsAt) {
        return new StrictSession(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                StrictMode.TIMED,
                now.minusSeconds(60),
                endsAt,
                false,
                SessionStatus.ACTIVE);
    }

    private StrictSession indefinite() {
        return new StrictSession(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                StrictMode.INDEFINITE,
                now.minusSeconds(60),
                null,
                false,
                SessionStatus.ACTIVE);
    }
}
