package com.localfocuscoach.strict.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localfocuscoach.strict.core.SessionStatus;
import com.localfocuscoach.strict.core.StrictSession;
import com.localfocuscoach.strict.focus.FocusSettings;
import com.localfocuscoach.strict.focus.FocusSettingsPayload;
import com.localfocuscoach.strict.focus.FocusSettingsValidator;
import com.localfocuscoach.strict.focus.FocusSite;
import com.localfocuscoach.strict.protocol.ProtocolMessage;
import com.localfocuscoach.strict.store.SqliteFocusSettingsRepository;
import com.localfocuscoach.strict.store.SqliteStrictSessionRepository;
import com.localfocuscoach.strict.store.StrictSessionRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
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
        service = createService(directory, repository, new FakeChromeController(true));

        var response = service.handle(startMessage("wrong"), NOW);

        assertEquals("error.unauthorized", response.type());
        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void relayReconnectCancelsExistingWarning(@TempDir Path directory) {
        var repository = repository(directory);
        service = createService(directory, repository, new FakeChromeController(true));
        service.handle(startMessage(SECRET), NOW);

        service.handle(message(SECRET, "relay.disconnected"), NOW);
        service.handle(message(SECRET, "relay.connected"), NOW.plusSeconds(10));

        var restored = repository.loadActive().orElseThrow();
        assertEquals(SessionStatus.ACTIVE, restored.status());
        assertNull(restored.warningEndsAt());
    }

    @Test
    void disconnectedRelayShowsAServiceOwnedRestoreWarning(@TempDir Path directory) {
        var repository = repository(directory);
        var notifier = new RecordingRestoreWarningNotifier();
        service = createService(
                directory,
                repository,
                new FakeChromeController(true),
                Clock.fixed(NOW, ZoneId.of("UTC")),
                new CapturingScheduler(),
                notifier);

        service.handle(startMessage(SECRET), NOW);

        assertEquals(1, notifier.showCount);
        assertEquals(NOW.plusSeconds(30), notifier.lastDeadline);
    }

    @Test
    void relayReconnectClearsTheServiceOwnedRestoreWarning(@TempDir Path directory) {
        var repository = repository(directory);
        var notifier = new RecordingRestoreWarningNotifier();
        service = createService(
                directory,
                repository,
                new FakeChromeController(true),
                Clock.fixed(NOW, ZoneId.of("UTC")),
                new CapturingScheduler(),
                notifier);
        service.handle(startMessage(SECRET), NOW);

        service.handle(message(SECRET, "relay.connected"), NOW.plusSeconds(10));

        assertEquals(1, notifier.clearCount);
    }

    @Test
    void closingChromeClearsTheServiceOwnedRestoreWarning(@TempDir Path directory) {
        var repository = repository(directory);
        var notifier = new RecordingRestoreWarningNotifier();
        var chrome = new MutableChromeController();
        var clock = new MutableClock(NOW);
        var scheduler = new CapturingScheduler();
        service = createService(
                directory, repository, chrome, clock, scheduler, notifier);
        service.handle(startMessage(SECRET), NOW);

        chrome.running = false;
        clock.advance(Duration.ofSeconds(1));
        scheduler.runScheduledCheck();

        assertEquals(1, notifier.clearCount);
        assertNull(repository.loadActive().orElseThrow().warningEndsAt());
    }

    @Test
    void rejectsUnknownProtocolVersionWithoutChangingSession(@TempDir Path directory) {
        var repository = repository(directory);
        service = createService(directory, repository, new FakeChromeController(true));
        var request = new ProtocolMessage(2, SECRET, "dashboard.start", startMessage(SECRET).payload());

        var response = service.handle(request, NOW);

        assertEquals("error.unsupportedVersion", response.type());
        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void rejectsUnknownMessageTypeWithoutChangingSession(@TempDir Path directory) {
        var repository = repository(directory);
        service = createService(directory, repository, new FakeChromeController(true));

        var response = service.handle(message(SECRET, "dashboard.openUrl"), NOW);

        assertEquals("error.unknownType", response.type());
        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void dashboardSavePersistsSettingsAndReturnsTheNewRevision(@TempDir Path directory) {
        var settingsRepository = settingsRepository(directory);
        service = new StrictModeService(
                SECRET,
                repository(directory),
                settingsRepository,
                new FakeChromeController(false),
                () -> {});

        var response = service.handle(
                message(
                        SECRET,
                        "dashboard.focusSettings.save",
                        Map.of("settings", settingsPayload(5))),
                NOW);

        assertEquals("service.focusSettings", response.type());
        assertEquals(1L, response.payload().get("revision"));
        assertEquals(settingsPayload(5), response.payload().get("settings"));
        assertEquals(0L, response.payload().get("chromeAppliedRevision"));
        assertEquals(1L, settingsRepository.load().orElseThrow().revision());
    }

    @Test
    void dashboardGetShowsEditableDefaultsWithoutConsumingLaterLegacyImport(
            @TempDir Path directory) {
        var settingsRepository = settingsRepository(directory);
        service = new StrictModeService(
                SECRET,
                repository(directory),
                settingsRepository,
                new FakeChromeController(false),
                () -> {});

        var response = service.handle(
                message(SECRET, "dashboard.focusSettings.get"), NOW);

        assertEquals("service.focusSettings", response.type());
        assertEquals(0L, response.payload().get("revision"));
        assertEquals(defaultSettingsPayload(), response.payload().get("settings"));
        assertTrue(settingsRepository.load().isEmpty());

        var imported = sync(0L, settingsPayload(4));

        assertEquals("service.focusSettings", imported.type());
        assertEquals(1L, imported.payload().get("revision"));
        assertEquals(settingsPayload(4), imported.payload().get("settings"));
        assertEquals(4, savedBudget(settingsRepository.load().orElseThrow()));
    }

    @Test
    void invalidDashboardSaveLeavesThePriorRevisionUntouched(@TempDir Path directory) {
        var settingsRepository = settingsRepository(directory);
        settingsRepository.save(new FocusSettingsValidator().parse(settingsPayload(5)));
        service = new StrictModeService(
                SECRET,
                repository(directory),
                settingsRepository,
                new FakeChromeController(false),
                () -> {});
        var invalid = new java.util.LinkedHashMap<>(settingsPayload(4));
        invalid.put("page", "private content");

        var response = service.handle(
                message(
                        SECRET,
                        "dashboard.focusSettings.save",
                        Map.of("settings", invalid)),
                NOW);

        assertEquals("error.invalidRequest", response.type());
        assertEquals(1L, settingsRepository.load().orElseThrow().revision());
        assertEquals(
                settingsPayload(5),
                settingsWithoutRevision(settingsRepository.load().orElseThrow()));
    }

    @Test
    void activeStrictModeRejectsAWeakerBudgetButAcceptsASmallerBudget(@TempDir Path directory) {
        var settingsRepository = settingsRepository(directory);
        settingsRepository.save(new FocusSettingsValidator().parse(settingsPayload(5)));
        var sessionRepository = new ControllableRepository(repository(directory));
        service = new StrictModeService(
                SECRET,
                sessionRepository,
                settingsRepository,
                new FakeChromeController(false),
                () -> {});
        service.handle(
                message(
                        SECRET,
                        "dashboard.start",
                        Map.of("mode", "INDEFINITE", "earlyExitChallenge", true)),
                NOW);

        var weaker = service.handle(
                message(
                        SECRET,
                        "dashboard.focusSettings.save",
                        Map.of("settings", settingsPayload(6))),
                NOW.plusSeconds(1));

        assertEquals(1, sessionRepository.saveCount);

        var stronger = service.handle(
                message(
                        SECRET,
                        "dashboard.focusSettings.save",
                        Map.of("settings", settingsPayload(4))),
                NOW.plusSeconds(2));

        assertEquals("error.focusSettingsWeakening", weaker.type());
        assertEquals("service.focusSettings", stronger.type());
        assertEquals(2L, stronger.payload().get("revision"));
        assertEquals(4, savedBudget(settingsRepository.load().orElseThrow()));
    }

    @Test
    void expiredStrictModeDoesNotBlockAWeakerSave(@TempDir Path directory) {
        var settingsRepository = settingsRepository(directory);
        settingsRepository.save(new FocusSettingsValidator().parse(settingsPayload(5)));
        service = new StrictModeService(
                SECRET,
                repository(directory),
                settingsRepository,
                new FakeChromeController(false),
                () -> {});
        service.handle(startMessage(SECRET), NOW);

        var response = service.handle(
                message(
                        SECRET,
                        "dashboard.focusSettings.save",
                        Map.of("settings", settingsPayload(6))),
                NOW.plusSeconds(300));

        assertEquals("service.focusSettings", response.type());
        assertEquals(2L, response.payload().get("revision"));
    }

    @Test
    void syncImportsLegacyOnlyOnceAndTracksHighestAppliedRevision(@TempDir Path directory) {
        var settingsRepository = settingsRepository(directory);
        service = new StrictModeService(
                SECRET,
                repository(directory),
                settingsRepository,
                new FakeChromeController(false),
                () -> {});

        var imported = sync(0L, settingsPayload(5));
        var existing = sync(1L, settingsPayload(6));
        var staleAcknowledgement = sync(0L, null);

        assertEquals("service.focusSettings", imported.type());
        assertEquals(1L, imported.payload().get("revision"));
        assertEquals(0L, imported.payload().get("chromeAppliedRevision"));
        assertEquals(1L, existing.payload().get("revision"));
        assertEquals(settingsPayload(5), existing.payload().get("settings"));
        assertEquals(1L, existing.payload().get("chromeAppliedRevision"));
        assertEquals(1L, staleAcknowledgement.payload().get("chromeAppliedRevision"));
        assertEquals(5, savedBudget(settingsRepository.load().orElseThrow()));
    }

    @Test
    void syncWithoutLegacyInitializesAndPersistsTheSafeDefaults(@TempDir Path directory) {
        var settingsRepository = settingsRepository(directory);
        service = new StrictModeService(
                SECRET,
                repository(directory),
                settingsRepository,
                new FakeChromeController(false),
                () -> {});

        var response = sync(0L, null);

        assertEquals("service.focusSettings", response.type());
        assertEquals(1L, response.payload().get("revision"));
        assertEquals(defaultSettingsPayload(), response.payload().get("settings"));
        assertEquals(1L, settingsRepository.load().orElseThrow().revision());
    }

    @Test
    void relayDisconnectResetsTheAppliedRevision(@TempDir Path directory) {
        var settingsRepository = settingsRepository(directory);
        settingsRepository.save(new FocusSettingsValidator().parse(settingsPayload(5)));
        service = new StrictModeService(
                SECRET,
                repository(directory),
                settingsRepository,
                new FakeChromeController(false),
                () -> {});
        sync(7L, null);

        service.handle(message(SECRET, "relay.disconnected"), NOW);
        var response = service.handle(
                message(SECRET, "dashboard.focusSettings.get"), NOW.plusSeconds(1));

        assertEquals("service.focusSettings", response.type());
        assertEquals(0L, response.payload().get("chromeAppliedRevision"));
    }

    @Test
    void malformedSyncDoesNotImportSettingsOrAdvanceTheAppliedRevision(@TempDir Path directory) {
        var settingsRepository = settingsRepository(directory);
        service = new StrictModeService(
                SECRET,
                repository(directory),
                settingsRepository,
                new FakeChromeController(false),
                () -> {});

        var response = service.handle(
                message(
                        SECRET,
                        "relay.focusSettings.sync",
                        Map.of(
                                "appliedRevision", -1L,
                                "legacySettings", settingsPayload(5))),
                NOW);

        assertEquals("error.invalidRequest", response.type());
        assertTrue(settingsRepository.load().isEmpty());
    }

    @Test
    void onlyAuthenticatedExactOpenDashboardRequestsInvokeTheLauncher(@TempDir Path directory) {
        var launcher = new RecordingDashboardLauncher();
        service = new StrictModeService(
                SECRET,
                repository(directory),
                settingsRepository(directory),
                new FakeChromeController(false),
                launcher);

        var unauthorized = service.handle(
                message("wrong", "relay.focusSettings.openDashboard"), NOW);
        var malformed = service.handle(
                message(
                        SECRET,
                        "relay.focusSettings.openDashboard",
                        Map.of("page", "https://example.invalid/private")),
                NOW);
        var accepted = service.handle(
                message(SECRET, "relay.focusSettings.openDashboard"), NOW);

        assertEquals("error.unauthorized", unauthorized.type());
        assertEquals("error.invalidRequest", malformed.type());
        assertEquals("service.ack", accepted.type());
        assertTrue(accepted.payload().isEmpty());
        assertEquals(1, launcher.openCalls);
    }

    @Test
    void macDashboardLauncherUsesTheCanonicalInstalledApplicationPath(@TempDir Path directory)
            throws Exception {
        var installedApp = directory.resolve("installed/Local Focus Coach.app");
        Files.createDirectories(installedApp);
        var linkedApp = directory.resolve("registered.app");
        Files.createSymbolicLink(linkedApp, installedApp);
        var runner = new CapturingDashboardCommandRunner(0);
        var launcher = new MacDashboardLauncher(linkedApp, runner);

        launcher.open();

        assertEquals(
                List.of(List.of("open", installedApp.toRealPath().toString())),
                runner.commands);
    }

    @Test
    void macDashboardLauncherTerminatesOnlyItsTimedOutChild(@TempDir Path directory)
            throws Exception {
        var installedApp = directory.resolve("Local Focus Coach.app");
        Files.createDirectories(installedApp);
        var process = new TimedDashboardProcess(false);
        var runner = new MacDashboardLauncher.ProcessCommandRunner(
                command -> process, Duration.ZERO, Duration.ZERO);
        var launcher = new MacDashboardLauncher(installedApp, runner);

        assertDoesNotThrow(launcher::open);

        assertEquals(1, process.destroyCount);
        assertEquals(1, process.destroyForciblyCount);
    }

    @Test
    void serviceRestartRestoresAndExpiresATimedSession(@TempDir Path directory) {
        var repository = repository(directory);
        var firstService = createService(directory, repository, new FakeChromeController(false));
        firstService.handle(startMessage(SECRET), NOW);
        firstService.close();
        var clock = new MutableClock(NOW);
        var scheduler = new CapturingScheduler();

        service = createService(
                directory, repository, new FakeChromeController(false), clock, scheduler);
        clock.advance(Duration.ofSeconds(300));
        scheduler.runScheduledCheck();

        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void serviceRestartChecksAnIndefiniteSessionForMissingRelay(@TempDir Path directory) {
        var repository = repository(directory);
        var firstService = createService(directory, repository, new FakeChromeController(false));
        firstService.handle(
                new ProtocolMessage(
                        1,
                        SECRET,
                        "dashboard.start",
                        Map.of("mode", "INDEFINITE", "earlyExitChallenge", true)),
                NOW);
        firstService.close();

        service = createService(
                directory,
                repository,
                new FakeChromeController(true),
                Clock.fixed(NOW.plusSeconds(5), ZoneId.of("UTC")),
                new CapturingScheduler());

        assertEquals(NOW.plusSeconds(35), repository.loadActive().orElseThrow().warningEndsAt());
    }

    @Test
    void disconnectedRelayWithChromeAbsentDoesNotCreateWarning(@TempDir Path directory) {
        var repository = repository(directory);
        service = createService(directory, repository, new FakeChromeController(false));
        service.handle(startMessage(SECRET), NOW);

        service.handle(message(SECRET, "relay.disconnected"), NOW.plusSeconds(1));

        assertNull(repository.loadActive().orElseThrow().warningEndsAt());
    }

    @Test
    void closedChromeReopeningWithoutTheRelayStartsAFreshWarning(@TempDir Path directory) {
        var repository = repository(directory);
        var chrome = new MutableChromeController();
        chrome.running = false;
        var clock = new MutableClock(NOW);
        var scheduler = new CapturingScheduler();
        service = createService(directory, repository, chrome, clock, scheduler);
        service.handle(
                new ProtocolMessage(
                        1,
                        SECRET,
                        "dashboard.start",
                        Map.of("mode", "INDEFINITE", "earlyExitChallenge", true)),
                NOW);

        chrome.running = true;
        clock.advance(Duration.ofSeconds(1));
        scheduler.runScheduledCheck();

        assertEquals(NOW.plusSeconds(31), repository.loadActive().orElseThrow().warningEndsAt());
    }

    @Test
    void uncertainChromeStateDoesNotCreateWarning(@TempDir Path directory) {
        var repository = repository(directory);
        service = createService(directory, repository, new ThrowingChromeController());

        service.handle(startMessage(SECRET), NOW);

        assertNull(repository.loadActive().orElseThrow().warningEndsAt());
    }

    @Test
    void indefiniteUnlockRequiresTheActiveChallengeExactCandidate(@TempDir Path directory) {
        var repository = repository(directory);
        service = createService(directory, repository, new FakeChromeController(false));
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
        service = createService(directory, repository, new FakeChromeController(false));
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
        service = createService(directory, repository, new FakeChromeController(false));
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
        service = createService(directory, repository, new FakeChromeController(false));
        service.handle(startMessage(SECRET), NOW);

        var response = service.handle(
                message(SECRET, "relay.heartbeat"), NOW.plusSeconds(300));

        assertEquals(false, response.payload().get("active"));
        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void dashboardStartPersistsTheNewSessionExactlyOnce(@TempDir Path directory) {
        var repository = new ControllableRepository(repository(directory));
        service = createService(directory, repository, new FakeChromeController(false));

        service.handle(startMessage(SECRET), NOW);

        assertEquals(1, repository.saveCount);
        assertTrue(repository.loadActive().isPresent());
    }

    @Test
    void transientProcessUncertaintyPreservesWarningThenDefersQuit(@TempDir Path directory) {
        var repository = repository(directory);
        var chrome = new MutableChromeController();
        var clock = new MutableClock(NOW);
        var scheduler = new CapturingScheduler();
        service = createService(directory, repository, chrome, clock, scheduler);
        service.handle(startMessage(SECRET), NOW);
        var deadline = repository.loadActive().orElseThrow().warningEndsAt();

        chrome.throwOnQuery = true;
        service.handle(message(SECRET, "dashboard.status"), NOW.plusSeconds(10));

        assertEquals(deadline, repository.loadActive().orElseThrow().warningEndsAt());
        assertEquals(0, chrome.quitRequests);

        chrome.throwOnQuery = false;
        service.handle(message(SECRET, "dashboard.status"), deadline);

        assertEquals(1, chrome.quitRequests);
        assertTrue(repository.loadActive().isPresent());
    }

    @Test
    void transientProcessUncertaintyKeepsIndefiniteEnforcementScheduled(@TempDir Path directory) {
        var repository = repository(directory);
        var chrome = new MutableChromeController();
        chrome.throwOnQuery = true;
        var clock = new MutableClock(NOW);
        var scheduler = new CapturingScheduler();
        service = createService(directory, repository, chrome, clock, scheduler);
        service.handle(
                new ProtocolMessage(
                        1,
                        SECRET,
                        "dashboard.start",
                        Map.of("mode", "INDEFINITE", "earlyExitChallenge", true)),
                NOW);

        chrome.throwOnQuery = false;
        clock.advance(Duration.ofSeconds(1));
        scheduler.runScheduledCheck();

        assertEquals(NOW.plusSeconds(31), repository.loadActive().orElseThrow().warningEndsAt());
    }

    @Test
    void schedulerSurvivesATransientRepositoryFailure(@TempDir Path directory) {
        var repository = new ControllableRepository(repository(directory));
        var clock = new MutableClock(NOW);
        var scheduler = new CapturingScheduler();
        service = createService(
                directory, repository, new FakeChromeController(false), clock, scheduler);
        service.handle(startMessage(SECRET), NOW);
        repository.failNextLoad = true;
        clock.advance(Duration.ofSeconds(300));

        assertDoesNotThrow(scheduler::runScheduledCheck);
        scheduler.runScheduledCheck();

        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void completedDeadlineFutureIsRecreated(@TempDir Path directory) {
        var repository = repository(directory);
        var scheduler = new CompletedFutureScheduler();
        service = createService(
                directory,
                repository,
                new FakeChromeController(false),
                Clock.fixed(NOW, ZoneId.of("UTC")),
                scheduler);

        service.handle(startMessage(SECRET), NOW);
        service.handle(message(SECRET, "dashboard.status"), NOW.plusSeconds(1));

        assertEquals(2, scheduler.scheduleCount);
    }

    @Test
    void schedulerRejectionDoesNotLeaveANewSessionActive(@TempDir Path directory) {
        var repository = repository(directory);
        service = createService(
                directory,
                repository,
                new FakeChromeController(false),
                Clock.fixed(NOW, ZoneId.of("UTC")),
                new RejectingScheduler());

        org.junit.jupiter.api.Assertions.assertThrows(
                RejectedExecutionException.class,
                () -> service.handle(startMessage(SECRET), NOW));

        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void warningExpiryRequestsExactlyOneGracefulQuit(@TempDir Path directory) {
        var repository = repository(directory);
        var chrome = new MutableChromeController();
        var clock = new MutableClock(NOW);
        var scheduler = new CapturingScheduler();
        service = createService(directory, repository, chrome, clock, scheduler);
        service.handle(startMessage(SECRET), NOW);

        clock.advance(Duration.ofSeconds(30));
        scheduler.runScheduledCheck();
        scheduler.runScheduledCheck();

        assertEquals(1, chrome.quitRequests);
        assertTrue(repository.loadActive().isPresent());
    }

    @Test
    void failedGracefulQuitLeavesTheSessionActive(@TempDir Path directory) {
        var repository = repository(directory);
        var chrome = new MutableChromeController();
        chrome.quitResult = ChromeController.QuitResult.FAILED;
        var clock = new MutableClock(NOW);
        var scheduler = new CapturingScheduler();
        service = createService(directory, repository, chrome, clock, scheduler);
        service.handle(startMessage(SECRET), NOW);

        clock.advance(Duration.ofSeconds(30));
        scheduler.runScheduledCheck();

        assertEquals(1, chrome.quitRequests);
        assertTrue(repository.loadActive().isPresent());
    }

    @Test
    void throwingGracefulQuitLeavesTheSessionActive(@TempDir Path directory) {
        var repository = repository(directory);
        var chrome = new MutableChromeController();
        chrome.throwOnQuit = true;
        var clock = new MutableClock(NOW);
        var scheduler = new CapturingScheduler();
        service = createService(directory, repository, chrome, clock, scheduler);
        service.handle(startMessage(SECRET), NOW);

        clock.advance(Duration.ofSeconds(30));
        assertDoesNotThrow(scheduler::runScheduledCheck);

        assertEquals(1, chrome.quitRequests);
        assertTrue(repository.loadActive().isPresent());
    }

    private SqliteStrictSessionRepository repository(Path directory) {
        return new SqliteStrictSessionRepository(directory.resolve("strict-mode.sqlite"));
    }

    private SqliteFocusSettingsRepository settingsRepository(Path directory) {
        return new SqliteFocusSettingsRepository(directory.resolve("strict-mode.sqlite"));
    }

    private StrictModeService createService(
            Path directory,
            StrictSessionRepository repository,
            ChromeController chromeController) {
        return new StrictModeService(
                SECRET,
                repository,
                settingsRepository(directory),
                chromeController,
                () -> {});
    }

    private StrictModeService createService(
            Path directory,
            StrictSessionRepository repository,
            ChromeController chromeController,
            Clock clock,
            java.util.concurrent.ScheduledExecutorService scheduler) {
        return new StrictModeService(
                SECRET,
                repository,
                settingsRepository(directory),
                chromeController,
                () -> {},
                clock,
                scheduler);
    }

    private StrictModeService createService(
            Path directory,
            StrictSessionRepository repository,
            ChromeController chromeController,
            Clock clock,
            java.util.concurrent.ScheduledExecutorService scheduler,
            RestoreWarningNotifier notifier) {
        return new StrictModeService(
                SECRET,
                repository,
                settingsRepository(directory),
                chromeController,
                () -> {},
                clock,
                scheduler,
                notifier);
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

    private ProtocolMessage message(String secret, String type, Map<String, Object> payload) {
        return new ProtocolMessage(1, secret, type, payload);
    }

    private ProtocolMessage sync(long appliedRevision, Map<String, Object> legacySettings) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("appliedRevision", appliedRevision);
        if (legacySettings != null) {
            payload.put("legacySettings", legacySettings);
        }
        return service.handle(message(SECRET, "relay.focusSettings.sync", payload), NOW);
    }

    private Map<String, Object> settingsPayload(int budget) {
        var rule = Map.<String, Object>of(
                "enabled", true,
                "doomscrollBudgetMinutes", budget,
                "warningScore", 10,
                "gracePeriodSeconds", 60,
                "interventions", List.of("notify", "pause", "close-tab"));
        return Map.of(
                "enabled", true,
                "rules", Map.of(
                        "instagram-reels", rule,
                        "x-timeline", rule,
                        "youtube-shorts", rule));
    }

    private Map<String, Object> defaultSettingsPayload() {
        var rule = Map.<String, Object>of(
                "enabled", false,
                "doomscrollBudgetMinutes", 5,
                "warningScore", 10,
                "gracePeriodSeconds", 60,
                "interventions", List.of("notify", "pause", "close-tab", "block"));
        return Map.of(
                "enabled", false,
                "rules", Map.of(
                        "instagram-reels", rule,
                        "x-timeline", rule,
                        "youtube-shorts", rule));
    }

    private Map<String, Object> settingsWithoutRevision(FocusSettings settings) {
        var payload = new java.util.LinkedHashMap<>(FocusSettingsPayload.toPayload(settings));
        payload.remove("revision");
        return payload;
    }

    private int savedBudget(FocusSettings settings) {
        return settings.rules()
                .get(FocusSite.INSTAGRAM_REELS)
                .doomscrollBudgetMinutes();
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

    private static final class MutableChromeController implements ChromeController {
        private boolean running = true;
        private boolean throwOnQuery;
        private boolean throwOnQuit;
        private int quitRequests;
        private QuitResult quitResult = QuitResult.REQUESTED;

        @Override
        public boolean isRunning() {
            if (throwOnQuery) {
                throw new IllegalStateException("process state unavailable");
            }
            return running;
        }

        @Override
        public QuitResult requestGracefulQuit() {
            quitRequests++;
            if (throwOnQuit) {
                throw new IllegalStateException("quit unavailable");
            }
            return quitResult;
        }
    }

    private static final class RecordingRestoreWarningNotifier
            implements RestoreWarningNotifier {
        private int showCount;
        private int clearCount;
        private Instant lastDeadline;

        @Override
        public void show(Instant deadline) {
            showCount++;
            lastDeadline = deadline;
        }

        @Override
        public void clear() {
            clearCount++;
        }
    }

    private static final class RecordingDashboardLauncher implements DashboardLauncher {
        private int openCalls;

        @Override
        public void open() {
            openCalls++;
        }
    }

    private static final class CapturingDashboardCommandRunner
            implements MacDashboardLauncher.CommandRunner {
        private final int exitCode;
        private final List<List<String>> commands = new ArrayList<>();

        private CapturingDashboardCommandRunner(int exitCode) {
            this.exitCode = exitCode;
        }

        @Override
        public int run(List<String> command) {
            commands.add(List.copyOf(command));
            return exitCode;
        }
    }

    private static final class TimedDashboardProcess extends Process {
        private final boolean exitsAfterDestroy;
        private int timedWaitCount;
        private int destroyCount;
        private int destroyForciblyCount;

        private TimedDashboardProcess(boolean exitsAfterDestroy) {
            this.exitsAfterDestroy = exitsAfterDestroy;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            throw new AssertionError("unbounded waitFor must never be called");
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            timedWaitCount++;
            return timedWaitCount > 1 && exitsAfterDestroy;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyCount++;
        }

        @Override
        public Process destroyForcibly() {
            destroyForciblyCount++;
            return this;
        }
    }

    private static final class ControllableRepository implements StrictSessionRepository {
        private final StrictSessionRepository delegate;
        private boolean failNextLoad;
        private int saveCount;

        private ControllableRepository(StrictSessionRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<StrictSession> loadActive() {
            if (failNextLoad) {
                failNextLoad = false;
                throw new IllegalStateException("temporary database failure");
            }
            return delegate.loadActive();
        }

        @Override
        public void save(StrictSession session) {
            saveCount++;
            delegate.save(session);
        }

        @Override
        public void clear(UUID sessionId) {
            delegate.clear(sessionId);
        }

        @Override
        public void appendAudit(UUID sessionId, String event, Instant at) {
            delegate.appendAudit(sessionId, event, at);
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

    private static final class CompletedFutureScheduler extends ScheduledThreadPoolExecutor {
        private int scheduleCount;

        private CompletedFutureScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            scheduleCount++;
            return new CompletedScheduledFuture();
        }
    }

    private static final class RejectingScheduler extends ScheduledThreadPoolExecutor {
        private RejectingScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new RejectedExecutionException("scheduler unavailable");
        }
    }

    private static final class CompletedScheduledFuture implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
