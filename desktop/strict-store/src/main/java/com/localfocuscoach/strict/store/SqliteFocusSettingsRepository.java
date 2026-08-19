package com.localfocuscoach.strict.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localfocuscoach.strict.focus.FocusSettings;
import com.localfocuscoach.strict.focus.FocusSettingsPayload;
import com.localfocuscoach.strict.focus.FocusSettingsValidator;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** SQLite-backed storage for the revisioned desktop-owned focus-settings document. */
public final class SqliteFocusSettingsRepository implements FocusSettingsRepository {
    private static final SqliteMigrationSupport.Migration[] MIGRATIONS = {
        new SqliteMigrationSupport.Migration(1, "strict_session", "/db/migration/V1__strict_session.sql"),
        new SqliteMigrationSupport.Migration(2, "focus_settings", "/db/migration/V2__focus_settings.sql")
    };
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final String jdbcUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FocusSettingsValidator validator = new FocusSettingsValidator();

    public SqliteFocusSettingsRepository(Path databasePath) {
        Objects.requireNonNull(databasePath);
        jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().normalize();
        migrate();
    }

    @Override
    public Optional<FocusSettings> load() {
        try (var connection = openConnection()) {
            return load(connection);
        } catch (SQLException exception) {
            throw storageFailure("load focus settings", exception);
        }
    }

    @Override
    public FocusSettings save(FocusSettings candidate) {
        var validated = validateUnsaved(candidate);
        try (var connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                var revision = nextRevision(load(connection));
                var saved = validated.withRevision(revision);
                upsert(connection, saved, false);
                connection.commit();
                return saved;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("save focus settings", exception);
        }
    }

    @Override
    public FocusSettings importIfAbsent(FocusSettings legacy) {
        var validated = validateUnsaved(legacy);
        try (var connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                var current = load(connection);
                if (current.isPresent()) {
                    connection.commit();
                    return current.get();
                }
                var imported = validated.withRevision(1);
                upsert(connection, imported, true);
                connection.commit();
                return imported;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("import focus settings", exception);
        }
    }

    private FocusSettings validateUnsaved(FocusSettings candidate) {
        if (candidate == null || candidate.revision() != 0) {
            throw new IllegalArgumentException("Focus settings candidates must be unsaved");
        }
        var payload = FocusSettingsPayload.toPayload(candidate);
        var settingsPayload = new LinkedHashMap<String, Object>();
        settingsPayload.put("enabled", payload.get("enabled"));
        settingsPayload.put("rules", payload.get("rules"));
        return validator.parse(settingsPayload);
    }

    private Optional<FocusSettings> load(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                        "SELECT revision, settings_json FROM focus_settings WHERE singleton = 1");
                var result = statement.executeQuery()) {
            return result.next() ? Optional.of(readSettings(result)) : Optional.empty();
        }
    }

    private FocusSettings readSettings(ResultSet result) throws SQLException {
        var revision = result.getLong("revision");
        try {
            var settings = FocusSettingsPayload.fromPayload(
                    objectMapper.readValue(result.getString("settings_json"), PAYLOAD_TYPE));
            if (settings.revision() != revision) {
                throw new SQLException("Stored focus-settings revision is inconsistent");
            }
            return settings;
        } catch (IOException | IllegalArgumentException exception) {
            throw new SQLException("Stored focus settings are invalid", exception);
        }
    }

    private long nextRevision(Optional<FocusSettings> current) throws SQLException {
        if (current.isPresent() && current.get().revision() == Long.MAX_VALUE) {
            throw new SQLException("Focus-settings revision limit reached");
        }
        return current.map(settings -> settings.revision() + 1).orElse(1L);
    }

    private void upsert(Connection connection, FocusSettings settings, boolean importedFromExtension)
            throws SQLException {
        var sql = """
                INSERT INTO focus_settings (
                    singleton, revision, settings_json, imported_from_extension, updated_at
                ) VALUES (1, ?, ?, ?, ?)
                ON CONFLICT(singleton) DO UPDATE SET
                    revision = excluded.revision,
                    settings_json = excluded.settings_json,
                    updated_at = excluded.updated_at
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, settings.revision());
            statement.setString(2, serialize(settings));
            statement.setInt(3, importedFromExtension ? 1 : 0);
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private String serialize(FocusSettings settings) throws SQLException {
        try {
            return objectMapper.writeValueAsString(FocusSettingsPayload.toPayload(settings));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to serialize focus settings", exception);
        }
    }

    private void migrate() {
        try (var connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                SqliteMigrationSupport.applyPending(connection, SqliteFocusSettingsRepository.class, MIGRATIONS);
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
