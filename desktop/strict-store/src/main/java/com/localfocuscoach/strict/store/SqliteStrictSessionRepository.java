package com.localfocuscoach.strict.store;

import com.localfocuscoach.strict.core.ConnectionHealth;
import com.localfocuscoach.strict.core.SessionStatus;
import com.localfocuscoach.strict.core.StrictAction;
import com.localfocuscoach.strict.core.StrictMode;
import com.localfocuscoach.strict.core.StrictSession;
import com.localfocuscoach.strict.core.StrictStateMachine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SqliteStrictSessionRepository implements StrictSessionRepository {
    private static final String MIGRATION_RESOURCE = "/db/migration/V1__strict_session.sql";

    private final String jdbcUrl;

    public SqliteStrictSessionRepository(Path databasePath) {
        Objects.requireNonNull(databasePath);
        jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().normalize();
        migrate();
    }

    @Override
    public Optional<StrictSession> loadActive() {
        var sql = """
                SELECT session_id, mode, started_at, ends_at, early_exit_challenge, status,
                       warning_ends_at
                FROM strict_session
                WHERE status = ?
                ORDER BY updated_at DESC, rowid DESC
                LIMIT 1
                """;
        try (var connection = openConnection();
                var statement = connection.prepareStatement(sql)) {
            statement.setString(1, SessionStatus.ACTIVE.name());
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(readSession(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw storageFailure("load active session", exception);
        }
    }

    @Override
    public void save(StrictSession session) {
        Objects.requireNonNull(session);
        var sql = """
                INSERT INTO strict_session (
                    session_id, mode, started_at, ends_at, early_exit_challenge, status,
                    warning_ends_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    mode = excluded.mode,
                    started_at = excluded.started_at,
                    ends_at = excluded.ends_at,
                    early_exit_challenge = excluded.early_exit_challenge,
                    status = excluded.status,
                    warning_ends_at = excluded.warning_ends_at,
                    updated_at = excluded.updated_at
                """;
        var auditedAt = Instant.now().toString();
        try (var connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (session.status() == SessionStatus.ACTIVE) {
                    deleteOtherActiveSessions(connection, session.id());
                }
                try (var statement = connection.prepareStatement(sql)) {
                    statement.setString(1, session.id().toString());
                    statement.setString(2, session.mode().name());
                    statement.setString(3, session.startedAt().toString());
                    setInstant(statement, 4, session.endsAt());
                    statement.setInt(5, session.earlyExitChallenge() ? 1 : 0);
                    statement.setString(6, session.status().name());
                    setInstant(statement, 7, session.warningEndsAt());
                    statement.setString(8, auditedAt);
                    statement.setString(9, auditedAt);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("save session", exception);
        }
    }

    private void deleteOtherActiveSessions(Connection connection, UUID retainedSessionId)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "DELETE FROM strict_session WHERE status = ? AND session_id <> ?")) {
            statement.setString(1, SessionStatus.ACTIVE.name());
            statement.setString(2, retainedSessionId.toString());
            statement.executeUpdate();
        }
    }

    @Override
    public void clear(UUID sessionId) {
        Objects.requireNonNull(sessionId);
        try (var connection = openConnection();
                var statement = connection.prepareStatement(
                        "DELETE FROM strict_session WHERE session_id = ?")) {
            statement.setString(1, sessionId.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw storageFailure("clear session", exception);
        }
    }

    @Override
    public void appendAudit(UUID sessionId, String event, Instant at) {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(event);
        Objects.requireNonNull(at);
        try (var connection = openConnection();
                var statement = connection.prepareStatement(
                        "INSERT INTO strict_session_audit (session_id, event, occurred_at) VALUES (?, ?, ?)")) {
            statement.setString(1, sessionId.toString());
            statement.setString(2, event);
            statement.setString(3, at.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw storageFailure("append session audit", exception);
        }
    }

    private void migrate() {
        try (var connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                createMigrationHistory(connection);
                if (!migrationApplied(connection, 1)) {
                    applyMigration(connection, MIGRATION_RESOURCE);
                    recordMigration(connection, 1, "strict_session");
                }
                connection.commit();
            } catch (IOException | SQLException exception) {
                rollback(connection, exception);
                throw storageFailure("migrate database", exception);
            }
        } catch (SQLException exception) {
            throw storageFailure("migrate database", exception);
        }
    }

    private Connection openConnection() throws SQLException {
        var connection = DriverManager.getConnection(jdbcUrl);
        try {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
            return connection;
        } catch (SQLException exception) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    private void createMigrationHistory(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_migration (
                        version INTEGER PRIMARY KEY,
                        description TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                    )
                    """);
        }
    }

    private boolean migrationApplied(Connection connection, int version) throws SQLException {
        try (var statement = connection.prepareStatement(
                        "SELECT 1 FROM schema_migration WHERE version = ?")) {
            statement.setInt(1, version);
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void applyMigration(Connection connection, String resource) throws IOException, SQLException {
        var stream = SqliteStrictSessionRepository.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException("Missing migration resource: " + resource);
        }
        String migration;
        try (stream) {
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var statement = connection.createStatement()) {
            for (var command : migration.split(";")) {
                if (!command.isBlank()) {
                    statement.executeUpdate(command);
                }
            }
        }
    }

    private void recordMigration(Connection connection, int version, String description)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                        "INSERT INTO schema_migration (version, description, applied_at) VALUES (?, ?, ?)")) {
            statement.setInt(1, version);
            statement.setString(2, description);
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private StrictSession readSession(ResultSet result) throws SQLException {
        var session = new StrictSession(
                UUID.fromString(result.getString("session_id")),
                StrictMode.valueOf(result.getString("mode")),
                Instant.parse(result.getString("started_at")),
                readInstant(result, "ends_at"),
                result.getInt("early_exit_challenge") != 0,
                SessionStatus.valueOf(result.getString("status")));
        var warningEndsAt = readInstant(result, "warning_ends_at");
        if (warningEndsAt != null) {
            var action = new StrictStateMachine().advance(
                    session,
                    ConnectionHealth.DISCONNECTED,
                    true,
                    warningEndsAt.minusSeconds(30));
            if (action != StrictAction.SHOW_RESTORE_WARNING) {
                throw new SQLException("Stored warning deadline is inconsistent with the session");
            }
        }
        return session;
    }

    private Instant readInstant(ResultSet result, String column) throws SQLException {
        var value = result.getString(column);
        return value == null ? null : Instant.parse(value);
    }

    private void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.toString());
        }
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private IllegalStateException storageFailure(String operation, Exception cause) {
        return new IllegalStateException("Failed to " + operation, cause);
    }
}
