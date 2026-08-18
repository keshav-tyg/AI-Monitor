package com.localfocuscoach.strict.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localfocuscoach.strict.core.ConnectionHealth;
import com.localfocuscoach.strict.core.SessionStatus;
import com.localfocuscoach.strict.core.StrictMode;
import com.localfocuscoach.strict.core.StrictSession;
import com.localfocuscoach.strict.core.StrictStateMachine;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteStrictSessionRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00.123456Z");

    @Test
    void activeSessionSurvivesRepositoryReopen(@TempDir Path directory) {
        var first = repository(directory);
        var expected = activeIndefinite();
        first.save(expected);

        var reopened = repository(directory);
        var actual = reopened.loadActive().orElseThrow();

        assertEquals(expected.id(), actual.id());
        assertEquals(StrictMode.INDEFINITE, actual.mode());
        assertEquals(NOW.minusSeconds(60), actual.startedAt());
        assertNull(actual.endsAt());
        assertTrue(actual.earlyExitChallenge());
        assertEquals(SessionStatus.ACTIVE, actual.status());
        assertNull(actual.warningEndsAt());
    }

    @Test
    void warningDeadlineSurvivesRepositoryReopen(@TempDir Path directory) {
        var first = repository(directory);
        var expected = activeTimed("00000000-0000-0000-0000-000000000002", NOW.plusSeconds(90));
        new StrictStateMachine().advance(expected, ConnectionHealth.DISCONNECTED, true, NOW);
        first.save(expected);

        var actual = repository(directory).loadActive().orElseThrow();

        assertEquals(NOW.plusSeconds(30), actual.warningEndsAt());
    }

    @Test
    void clearRemovesOnlyTheNamedSession(@TempDir Path directory) {
        var repo = repository(directory);
        var removed = activeTimed("00000000-0000-0000-0000-000000000003", NOW.plusSeconds(60));
        var retained = activeTimed("00000000-0000-0000-0000-000000000004", NOW.plusSeconds(120));
        repo.save(removed);
        repo.save(retained);

        repo.clear(removed.id());

        assertEquals(retained.id(), repo.loadActive().orElseThrow().id());

        repo.clear(retained.id());

        assertTrue(repo.loadActive().isEmpty());
    }

    @Test
    void expiredSessionIsNotLoadedAsActive(@TempDir Path directory) {
        var repo = repository(directory);
        var expired = activeTimed("00000000-0000-0000-0000-000000000005", NOW);
        new StrictStateMachine().advance(expired, ConnectionHealth.HEALTHY, true, NOW);

        repo.save(expired);

        assertTrue(repo.loadActive().isEmpty());
    }

    @Test
    void auditEventSurvivesRepositoryReopen(@TempDir Path directory) throws Exception {
        var repo = repository(directory);
        var session = activeIndefinite();
        repo.save(session);
        repo.appendAudit(session.id(), "SESSION_STARTED", NOW);

        repository(directory);

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database(directory));
                var statement = connection.prepareStatement(
                        "SELECT event, occurred_at FROM strict_session_audit WHERE session_id = ?")) {
            statement.setString(1, session.id().toString());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("SESSION_STARTED", result.getString("event"));
                assertEquals(NOW.toString(), result.getString("occurred_at"));
            }
        }
    }

    private SqliteStrictSessionRepository repository(Path directory) {
        return new SqliteStrictSessionRepository(database(directory));
    }

    private Path database(Path directory) {
        return directory.resolve("strict-mode.sqlite");
    }

    private StrictSession activeIndefinite() {
        return new StrictSession(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                StrictMode.INDEFINITE,
                NOW.minusSeconds(60),
                null,
                true,
                SessionStatus.ACTIVE);
    }

    private StrictSession activeTimed(String id, Instant endsAt) {
        return new StrictSession(
                UUID.fromString(id),
                StrictMode.TIMED,
                NOW.minusSeconds(60),
                endsAt,
                false,
                SessionStatus.ACTIVE);
    }
}
