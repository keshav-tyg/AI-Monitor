package com.localfocuscoach.strict.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localfocuscoach.strict.core.SessionStatus;
import com.localfocuscoach.strict.protocol.ProtocolMessage;
import com.localfocuscoach.strict.store.SqliteStrictSessionRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StrictModeServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private static final String SECRET = "test-install-secret";

    private StrictModeService service;

    @AfterEach
    void closeService() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void rejectsWrongSecretWithoutChangingSession(@TempDir Path directory) {
        var repository = repository(directory);
        service = new StrictModeService(SECRET, repository, new FakeChromeController(true));

        var response = service.handle(startMessage("wrong"), NOW);

        assertEquals("error.unauthorized", response.type());
        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void relayReconnectCancelsExistingWarning(@TempDir Path directory) {
        var repository = repository(directory);
        service = new StrictModeService(SECRET, repository, new FakeChromeController(true));
        service.handle(startMessage(SECRET), NOW);

        service.handle(message(SECRET, "relay.disconnected"), NOW);
        service.handle(message(SECRET, "relay.connected"), NOW.plusSeconds(10));

        var restored = repository.loadActive().orElseThrow();
        assertEquals(SessionStatus.ACTIVE, restored.status());
        assertNull(restored.warningEndsAt());
    }

    @Test
    void rejectsUnknownProtocolVersionWithoutChangingSession(@TempDir Path directory) {
        var repository = repository(directory);
        service = new StrictModeService(SECRET, repository, new FakeChromeController(true));
        var request = new ProtocolMessage(2, SECRET, "dashboard.start", startMessage(SECRET).payload());

        var response = service.handle(request, NOW);

        assertEquals("error.unsupportedVersion", response.type());
        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void rejectsUnknownMessageTypeWithoutChangingSession(@TempDir Path directory) {
        var repository = repository(directory);
        service = new StrictModeService(SECRET, repository, new FakeChromeController(true));

        var response = service.handle(message(SECRET, "dashboard.openUrl"), NOW);

        assertEquals("error.unknownType", response.type());
        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void serviceRestartRestoresAndExpiresATimedSession(@TempDir Path directory) {
        var repository = repository(directory);
        var firstService = new StrictModeService(SECRET, repository, new FakeChromeController(false));
        firstService.handle(startMessage(SECRET), NOW);
        firstService.close();
        var clock = new MutableClock(NOW);
        var scheduler = new CapturingScheduler();

        service = new StrictModeService(
                SECRET, repository, new FakeChromeController(false), clock, scheduler);
        clock.advance(Duration.ofSeconds(300));
        scheduler.runScheduledCheck();

        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void serviceRestartChecksAnIndefiniteSessionForMissingRelay(@TempDir Path directory) {
        var repository = repository(directory);
        var firstService = new StrictModeService(SECRET, repository, new FakeChromeController(false));
        firstService.handle(
                new ProtocolMessage(
                        1,
                        SECRET,
                        "dashboard.start",
                        Map.of("mode", "INDEFINITE", "earlyExitChallenge", true)),
                NOW);
        firstService.close();

        service = new StrictModeService(
                SECRET,
                repository,
                new FakeChromeController(true),
                Clock.fixed(NOW.plusSeconds(5), ZoneId.of("UTC")),
                new CapturingScheduler());

        assertEquals(NOW.plusSeconds(35), repository.loadActive().orElseThrow().warningEndsAt());
    }

    @Test
    void disconnectedRelayWithChromeAbsentDoesNotCreateWarning(@TempDir Path directory) {
        var repository = repository(directory);
        service = new StrictModeService(SECRET, repository, new FakeChromeController(false));
        service.handle(startMessage(SECRET), NOW);

        service.handle(message(SECRET, "relay.disconnected"), NOW.plusSeconds(1));

        assertNull(repository.loadActive().orElseThrow().warningEndsAt());
    }

    @Test
    void uncertainChromeStateDoesNotCreateWarning(@TempDir Path directory) {
        var repository = repository(directory);
        service = new StrictModeService(SECRET, repository, new ThrowingChromeController());

        service.handle(startMessage(SECRET), NOW);

        assertNull(repository.loadActive().orElseThrow().warningEndsAt());
    }

    @Test
    void indefiniteUnlockRequiresTheActiveChallengeExactCandidate(@TempDir Path directory) {
        var repository = repository(directory);
        service = new StrictModeService(SECRET, repository, new FakeChromeController(false));
        service.handle(
                new ProtocolMessage(
                        1,
                        SECRET,
                        "dashboard.start",
                        Map.of("mode", "INDEFINITE", "earlyExitChallenge", true)),
                NOW);

        var challengeResponse = service.handle(message(SECRET, "dashboard.beginUnlock"), NOW);
        var target = (String) challengeResponse.payload().get("target");
        assertEquals("service.challenge", challengeResponse.type());
        assertNotNull(target);
        assertEquals(500, target.length());

        var failed = service.handle(
                new ProtocolMessage(
                        1, SECRET, "dashboard.submitUnlock", Map.of("candidate", target + "x")),
                NOW.plusSeconds(1));
        assertEquals(false, failed.payload().get("unlocked"));
        assertTrue(repository.loadActive().isPresent());

        var succeeded = service.handle(
                new ProtocolMessage(
                        1, SECRET, "dashboard.submitUnlock", Map.of("candidate", target)),
                NOW.plusSeconds(2));
        assertEquals(true, succeeded.payload().get("unlocked"));
        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void rejectsUnexpectedStartDataWithoutChangingSession(@TempDir Path directory) {
        var repository = repository(directory);
        service = new StrictModeService(SECRET, repository, new FakeChromeController(false));
        var payload = new java.util.HashMap<>(startMessage(SECRET).payload());
        payload.put("url", "https://example.invalid/private");

        var response = service.handle(
                new ProtocolMessage(1, SECRET, "dashboard.start", payload), NOW);

        assertEquals("error.invalidRequest", response.type());
        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void rejectsUnexpectedUnlockDataWithoutChangingSession(@TempDir Path directory) {
        var repository = repository(directory);
        service = new StrictModeService(SECRET, repository, new FakeChromeController(false));
        service.handle(startMessage(SECRET), NOW);

        var response = service.handle(
                new ProtocolMessage(
                        1,
                        SECRET,
                        "dashboard.submitUnlock",
                        Map.of("candidate", "typed", "url", "https://example.invalid/private")),
                NOW.plusSeconds(1));

        assertEquals("error.invalidRequest", response.type());
        assertTrue(repository.loadActive().isPresent());
    }

    @Test
    void relayMessageAtTimedExpiryReportsNoActiveSession(@TempDir Path directory) {
        var repository = repository(directory);
        service = new StrictModeService(SECRET, repository, new FakeChromeController(false));
        service.handle(startMessage(SECRET), NOW);

        var response = service.handle(
                message(SECRET, "relay.heartbeat"), NOW.plusSeconds(300));

        assertEquals(false, response.payload().get("active"));
        assertTrue(repository.loadActive().isEmpty());
    }

    private SqliteStrictSessionRepository repository(Path directory) {
        return new SqliteStrictSessionRepository(directory.resolve("strict-mode.sqlite"));
    }

    private ProtocolMessage startMessage(String secret) {
        return new ProtocolMessage(
                1,
                secret,
                "dashboard.start",
                Map.of(
                        "mode", "TIMED",
                        "endsAt", NOW.plusSeconds(300).toString(),
                        "earlyExitChallenge", false));
    }

    private ProtocolMessage message(String secret, String type) {
        return new ProtocolMessage(1, secret, type, Map.of());
    }

    private static final class FakeChromeController implements ChromeController {
        private final boolean running;

        private FakeChromeController(boolean running) {
            this.running = running;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public QuitResult requestGracefulQuit() {
            return QuitResult.REQUESTED;
        }
    }

    private static final class ThrowingChromeController implements ChromeController {
        @Override
        public boolean isRunning() {
            throw new IllegalStateException("process state unavailable");
        }

        @Override
        public QuitResult requestGracefulQuit() {
            return QuitResult.FAILED;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class CapturingScheduler extends ScheduledThreadPoolExecutor {
        private Runnable scheduledCheck;

        private CapturingScheduler() {
            super(1);
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            scheduledCheck = command;
            return super.scheduleAtFixedRate(() -> {}, 1, 1, TimeUnit.DAYS);
        }

        private void runScheduledCheck() {
            assertNotNull(scheduledCheck);
            scheduledCheck.run();
        }
    }
}
