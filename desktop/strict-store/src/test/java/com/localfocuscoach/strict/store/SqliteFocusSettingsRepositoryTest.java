package com.localfocuscoach.strict.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.localfocuscoach.strict.focus.FocusIntervention;
import com.localfocuscoach.strict.focus.FocusRule;
import com.localfocuscoach.strict.focus.FocusSettings;
import com.localfocuscoach.strict.focus.FocusSite;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteFocusSettingsRepositoryTest {
    @Test
    void saveAssignsAndPersistsMonotonicRevisions(@TempDir Path directory) {
        var repository = repository(directory);

        assertEquals(1L, repository.save(defaultSettings()).revision());
        assertEquals(2L, repository.save(changedSettings()).revision());
        assertEquals(2L, repository.load().orElseThrow().revision());
        assertEquals(changedSettings().withRevision(2), repository.load().orElseThrow());
    }

    @Test
    void firstImportNeverOverwritesDesktopOwnedSettings(@TempDir Path directory) {
        var repository = repository(directory);
        var imported = repository.importIfAbsent(legacy());

        assertEquals(1L, imported.revision());
        assertEquals(imported, repository.importIfAbsent(otherLegacy()));
        assertEquals(imported, repository.load().orElseThrow());
    }

    @Test
    void invalidCandidateLeavesTheLastSavedDocumentUntouched(@TempDir Path directory) {
        var repository = repository(directory);
        repository.save(defaultSettings());

        assertThrows(IllegalArgumentException.class, () -> repository.save(invalidSettings()));

        assertEquals(defaultSettings().withRevision(1), repository.load().orElseThrow());
    }

    @Test
    void migrationKeepsStrictSessionStorageCompatible(@TempDir Path directory) throws Exception {
        repository(directory);
        var strictSessions = new SqliteStrictSessionRepository(database(directory));

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database(directory));
                var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT version FROM schema_migration ORDER BY version")) {
            result.next();
            assertEquals(1, result.getInt("version"));
            result.next();
            assertEquals(2, result.getInt("version"));
        }

        assertEquals(0L, strictSessions.loadActive().stream().count());
    }

    private FocusSettingsRepository repository(Path directory) {
        return new SqliteFocusSettingsRepository(database(directory));
    }

    private Path database(Path directory) {
        return directory.resolve("strict-mode.sqlite");
    }

    private FocusSettings defaultSettings() {
        return settings(5, 10, 60, List.of(FocusIntervention.NOTIFY, FocusIntervention.PAUSE));
    }

    private FocusSettings changedSettings() {
        return settings(4, 10, 60, List.of(FocusIntervention.NOTIFY, FocusIntervention.PAUSE));
    }

    private FocusSettings legacy() {
        return settings(5, 9, 60, List.of(FocusIntervention.NOTIFY, FocusIntervention.CLOSE_TAB));
    }

    private FocusSettings otherLegacy() {
        return settings(6, 11, 60, List.of(FocusIntervention.NOTIFY, FocusIntervention.BLOCK));
    }

    private FocusSettings invalidSettings() {
        return defaultSettings().withRevision(1);
    }

    private FocusSettings settings(
            int doomscrollBudgetMinutes,
            int warningScore,
            int gracePeriodSeconds,
            List<FocusIntervention> interventions) {
        var rule = new FocusRule(
                true, doomscrollBudgetMinutes, warningScore, gracePeriodSeconds, interventions);
        return new FocusSettings(
                0,
                true,
                Map.of(
                        FocusSite.INSTAGRAM_REELS, rule,
                        FocusSite.X_TIMELINE, rule,
                        FocusSite.YOUTUBE_SHORTS, rule));
    }
}
