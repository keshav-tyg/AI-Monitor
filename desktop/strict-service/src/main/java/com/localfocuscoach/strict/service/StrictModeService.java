package com.localfocuscoach.strict.service;

import com.localfocuscoach.strict.core.ConnectionHealth;
import com.localfocuscoach.strict.core.SessionStatus;
import com.localfocuscoach.strict.core.StrictAction;
import com.localfocuscoach.strict.core.StrictMode;
import com.localfocuscoach.strict.core.StrictSession;
import com.localfocuscoach.strict.core.StrictStateMachine;
import com.localfocuscoach.strict.core.TypingChallenge;
import com.localfocuscoach.strict.core.TypingChallengeService;
import com.localfocuscoach.strict.focus.FocusIntervention;
import com.localfocuscoach.strict.focus.FocusRule;
import com.localfocuscoach.strict.focus.FocusSettings;
import com.localfocuscoach.strict.focus.FocusSettingsPayload;
import com.localfocuscoach.strict.focus.FocusSettingsValidator;
import com.localfocuscoach.strict.focus.FocusSite;
import com.localfocuscoach.strict.protocol.ProtocolMessage;
import com.localfocuscoach.strict.store.FocusSettingsRepository;
import com.localfocuscoach.strict.store.StrictSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class StrictModeService implements AutoCloseable {
    private static final int PROTOCOL_VERSION = 1;
    private static final FocusSettings DEFAULT_FOCUS_SETTINGS = defaultFocusSettings();
    private static final Set<String> VALID_TYPES = Set.of(
            "dashboard.start",
            "dashboard.status",
            "dashboard.beginUnlock",
            "dashboard.submitUnlock",
            "dashboard.focusSettings.get",
            "dashboard.focusSettings.save",
            "relay.connected",
            "relay.heartbeat",
            "relay.disconnected",
            "relay.focusSettings.sync",
            "relay.focusSettings.openDashboard");

    private final String secret;
    private final StrictSessionRepository repository;
    private final FocusSettingsRepository focusSettingsRepository;
    private final ChromeController chromeController;
    private final DashboardLauncher dashboardLauncher;
    private final StrictStateMachine stateMachine = new StrictStateMachine();
    private final TypingChallengeService challengeService = new TypingChallengeService();
    private final FocusSettingsValidator focusSettingsValidator = new FocusSettingsValidator();
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final RestoreWarningNotifier restoreWarningNotifier;
    private ConnectionHealth connectionHealth = ConnectionHealth.DISCONNECTED;
    private TypingChallenge activeChallenge;
    private Instant lastQuitWarningDeadline;
    private Instant notifiedWarningDeadline;
    private ScheduledFuture<?> scheduledCheck;
    private long chromeAppliedRevision;
    private boolean chromeStateUncertain;
    private boolean closed;

    public StrictModeService(
            String secret,
            StrictSessionRepository repository,
            FocusSettingsRepository focusSettingsRepository,
            ChromeController chromeController,
            DashboardLauncher dashboardLauncher) {
        this(
                secret,
                repository,
                focusSettingsRepository,
                chromeController,
                dashboardLauncher,
                Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    var thread = new Thread(runnable, "strict-mode-deadline");
                    thread.setDaemon(true);
                    return thread;
                }),
                RestoreWarningNotifier.NOOP);
    }

    public StrictModeService(
            String secret,
            StrictSessionRepository repository,
            FocusSettingsRepository focusSettingsRepository,
            ChromeController chromeController,
            DashboardLauncher dashboardLauncher,
            RestoreWarningNotifier restoreWarningNotifier) {
        this(
                secret,
                repository,
                focusSettingsRepository,
                chromeController,
                dashboardLauncher,
                Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    var thread = new Thread(runnable, "strict-mode-deadline");
                    thread.setDaemon(true);
                    return thread;
                }),
                restoreWarningNotifier);
    }

    StrictModeService(
            String secret,
            StrictSessionRepository repository,
            FocusSettingsRepository focusSettingsRepository,
            ChromeController chromeController,
            DashboardLauncher dashboardLauncher,
            Clock clock,
            ScheduledExecutorService scheduler) {
        this(
                secret,
                repository,
                focusSettingsRepository,
                chromeController,
                dashboardLauncher,
                clock,
                scheduler,
                RestoreWarningNotifier.NOOP);
    }

    StrictModeService(
            String secret,
            StrictSessionRepository repository,
            FocusSettingsRepository focusSettingsRepository,
            ChromeController chromeController,
            DashboardLauncher dashboardLauncher,
            Clock clock,
            ScheduledExecutorService scheduler,
            RestoreWarningNotifier restoreWarningNotifier) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Install secret must not be blank");
        }
        this.secret = secret;
        this.repository = Objects.requireNonNull(repository);
        this.focusSettingsRepository = Objects.requireNonNull(focusSettingsRepository);
        this.chromeController = Objects.requireNonNull(chromeController);
        this.dashboardLauncher = Objects.requireNonNull(dashboardLauncher);
        this.clock = Objects.requireNonNull(clock);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.restoreWarningNotifier = Objects.requireNonNull(restoreWarningNotifier);
        var recovered = repository.loadActive().orElse(null);
        if (recovered != null) {
            advancePersistAndAct(recovered, clock.instant());
        }
        updateSchedule(recovered != null && recovered.status() == SessionStatus.ACTIVE ? recovered : null);
    }

    public synchronized ProtocolMessage handle(ProtocolMessage message, Instant now) {
        Objects.requireNonNull(message);
        Objects.requireNonNull(now);

        if (message.version() != PROTOCOL_VERSION) {
            return unauthenticatedError("error.unsupportedVersion");
        }
        if (!secret.equals(message.secret())) {
            return unauthenticatedError("error.unauthorized");
        }
        if (!VALID_TYPES.contains(message.type())) {
            return unauthenticatedError("error.unknownType");
        }
        if (message.payload() == null) {
            return authenticatedError("error.invalidRequest");
        }

        return switch (message.type()) {
            case "dashboard.start" -> start(message.payload(), now);
            case "dashboard.status" -> status(message.payload(), now);
            case "dashboard.beginUnlock" -> beginUnlock(message.payload(), now);
            case "dashboard.submitUnlock" -> submitUnlock(message.payload());
            case "dashboard.focusSettings.get" -> focusSettings(message.payload());
            case "dashboard.focusSettings.save" -> saveFocusSettings(message.payload(), now);
            case "relay.connected", "relay.heartbeat" ->
                    updateConnection(message.payload(), ConnectionHealth.HEALTHY, now);
            case "relay.disconnected" ->
                    updateConnection(message.payload(), ConnectionHealth.DISCONNECTED, now);
            case "relay.focusSettings.sync" -> syncFocusSettings(message.payload());
            case "relay.focusSettings.openDashboard" -> openDashboard(message.payload());
            default -> authenticatedError("error.invalidRequest");
        };
    }

    private ProtocolMessage focusSettings(Map<String, Object> payload) {
        if (!payload.isEmpty()) {
            return authenticatedError("error.invalidRequest");
        }
        var settings = focusSettingsRepository
                .load()
                .orElseGet(() -> focusSettingsRepository.save(DEFAULT_FOCUS_SETTINGS));
        return focusSettingsResponse(settings);
    }

    private ProtocolMessage saveFocusSettings(Map<String, Object> payload, Instant now) {
        if (!payload.keySet().equals(Set.of("settings"))) {
            return authenticatedError("error.invalidRequest");
        }
        final FocusSettings candidate;
        try {
            candidate = focusSettingsValidator.parse(stringMap(payload.get("settings")));
        } catch (IllegalArgumentException exception) {
            return authenticatedError("error.invalidRequest");
        }

        var active = repository.loadActive().orElse(null);
        var current = focusSettingsRepository.load();
        var strictModeActive = active != null
                && (active.mode() != StrictMode.TIMED || now.isBefore(active.endsAt()));
        if (strictModeActive
                && current.isPresent()
                && focusSettingsValidator.isWeakening(current.orElseThrow(), candidate)) {
            return authenticatedError("error.focusSettingsWeakening");
        }
        if (active != null && !strictModeActive) {
            advancePersistAndAct(active, now);
            updateSchedule(null);
        }
        return focusSettingsResponse(focusSettingsRepository.save(candidate));
    }

    private ProtocolMessage syncFocusSettings(Map<String, Object> payload) {
        if (!(payload.keySet().equals(Set.of("appliedRevision"))
                || payload.keySet().equals(Set.of("appliedRevision", "legacySettings")))) {
            return authenticatedError("error.invalidRequest");
        }
        final long appliedRevision;
        final FocusSettings legacySettings;
        try {
            appliedRevision = nonNegativeLong(payload.get("appliedRevision"));
            legacySettings = payload.containsKey("legacySettings")
                    ? focusSettingsValidator.parse(stringMap(payload.get("legacySettings")))
                    : null;
        } catch (IllegalArgumentException exception) {
            return authenticatedError("error.invalidRequest");
        }

        var current = focusSettingsRepository.load();
        if (current.isEmpty()) {
            current = java.util.Optional.of(legacySettings == null
                    ? focusSettingsRepository.save(DEFAULT_FOCUS_SETTINGS)
                    : focusSettingsRepository.importIfAbsent(legacySettings));
        }
        chromeAppliedRevision = Math.max(chromeAppliedRevision, appliedRevision);
        return focusSettingsResponse(current.orElseThrow());
    }

    private ProtocolMessage openDashboard(Map<String, Object> payload) {
        if (!payload.isEmpty()) {
            return authenticatedError("error.invalidRequest");
        }
        try {
            dashboardLauncher.open();
        } catch (RuntimeException exception) {
            // A launcher failure must not stop the authenticated local service.
        }
        return authenticatedResponse("service.ack", Map.of());
    }

    private ProtocolMessage start(Map<String, Object> payload, Instant now) {
        var modeValue = payload.get("mode");
        var earlyExitValue = payload.get("earlyExitChallenge");
        if (!(modeValue instanceof String modeText)
                || !(earlyExitValue instanceof Boolean earlyExitChallenge)) {
            return authenticatedError("error.invalidRequest");
        }

        final StrictMode mode;
        try {
            mode = StrictMode.valueOf(modeText);
        } catch (IllegalArgumentException exception) {
            return authenticatedError("error.invalidRequest");
        }

        final Instant endsAt;
        if (mode == StrictMode.TIMED) {
            if (!payload.keySet().equals(Set.of("mode", "endsAt", "earlyExitChallenge"))
                    || !(payload.get("endsAt") instanceof String endsAtText)) {
                return authenticatedError("error.invalidRequest");
            }
            try {
                endsAt = Instant.parse(endsAtText);
            } catch (DateTimeParseException exception) {
                return authenticatedError("error.invalidRequest");
            }
            if (!endsAt.isAfter(now)) {
                return authenticatedError("error.invalidRequest");
            }
        } else {
            if (!payload.keySet().equals(Set.of("mode", "earlyExitChallenge"))) {
                return authenticatedError("error.invalidRequest");
            }
            endsAt = null;
            earlyExitChallenge = true;
        }

        activeChallenge = null;
        var session = new StrictSession(
                UUID.randomUUID(), mode, now, endsAt, earlyExitChallenge, SessionStatus.ACTIVE);
        advancePersistAndAct(session, now);
        try {
            updateSchedule(session);
        } catch (RuntimeException exception) {
            repository.clear(session.id());
            throw exception;
        }
        return statusResponse(session);
    }

    private ProtocolMessage status(Map<String, Object> payload, Instant now) {
        if (!payload.isEmpty()) {
            return authenticatedError("error.invalidRequest");
        }
        var active = repository.loadActive();
        if (active.isEmpty()) {
            updateSchedule(null);
            return authenticatedResponse("service.status", Map.of("active", false));
        }
        var session = active.orElseThrow();
        advancePersistAndAct(session, now);
        updateSchedule(session.status() == SessionStatus.ACTIVE ? session : null);
        return session.status() == SessionStatus.ACTIVE
                ? statusResponse(session)
                : authenticatedResponse("service.status", Map.of("active", false));
    }

    private ProtocolMessage beginUnlock(Map<String, Object> payload, Instant now) {
        if (!payload.isEmpty()) {
            return authenticatedError("error.invalidRequest");
        }
        var active = repository.loadActive();
        if (active.isEmpty()
                || stateMachine.requestEarlyUnlock(active.orElseThrow())
                        != StrictAction.BEGIN_UNLOCK_CHALLENGE) {
            return authenticatedError("error.unlockUnavailable");
        }
        activeChallenge = challengeService.create(now);
        return authenticatedResponse(
                "service.challenge",
                Map.of(
                        "challengeId", activeChallenge.id().toString(),
                        "target", activeChallenge.target(),
                        "createdAt", activeChallenge.createdAt().toString()));
    }

    private ProtocolMessage submitUnlock(Map<String, Object> payload) {
        if (!payload.keySet().equals(Set.of("candidate"))
                || !(payload.get("candidate") instanceof String candidate)) {
            return authenticatedError("error.invalidRequest");
        }
        if (activeChallenge == null) {
            return authenticatedResponse("service.unlockResult", Map.of("unlocked", false));
        }
        var active = repository.loadActive();
        if (active.isEmpty() || !challengeService.matches(activeChallenge, candidate)) {
            return authenticatedResponse("service.unlockResult", Map.of("unlocked", false));
        }
        repository.clear(active.orElseThrow().id());
        activeChallenge = null;
        lastQuitWarningDeadline = null;
        clearRestoreWarning();
        updateSchedule(null);
        return authenticatedResponse("service.unlockResult", Map.of("unlocked", true));
    }

    private ProtocolMessage updateConnection(
            Map<String, Object> payload, ConnectionHealth health, Instant now) {
        if (!payload.isEmpty()) {
            return authenticatedError("error.invalidRequest");
        }
        connectionHealth = health;
        if (health == ConnectionHealth.HEALTHY) {
            lastQuitWarningDeadline = null;
        } else {
            chromeAppliedRevision = 0;
        }
        var active = repository.loadActive();
        if (active.isEmpty()) {
            updateSchedule(null);
            return authenticatedResponse("service.ack", Map.of());
        }
        var session = active.orElseThrow();
        advancePersistAndAct(session, now);
        updateSchedule(session.status() == SessionStatus.ACTIVE ? session : null);
        return session.status() == SessionStatus.ACTIVE
                ? statusResponse(session)
                : authenticatedResponse("service.status", Map.of("active", false));
    }

    private void advancePersistAndAct(StrictSession session, Instant now) {
        var action = advanceInMemory(session, now);
        repository.save(session);
        performAction(session, action);
    }

    private StrictAction advanceInMemory(StrictSession session, Instant now) {
        if (connectionHealth == ConnectionHealth.HEALTHY) {
            chromeStateUncertain = false;
            return stateMachine.advance(session, connectionHealth, true, now);
        }
        var chromeState = chromeProcessState();
        if (chromeState == ChromeProcessState.UNKNOWN) {
            chromeStateUncertain = true;
            if (session.mode() == StrictMode.TIMED && !now.isBefore(session.endsAt())) {
                return stateMachine.advance(session, connectionHealth, false, now);
            }
            return StrictAction.NONE;
        }
        chromeStateUncertain = false;
        return stateMachine.advance(
                session, connectionHealth, chromeState == ChromeProcessState.RUNNING, now);
    }

    private void performAction(StrictSession session, StrictAction action) {
        syncRestoreWarning(session.warningEndsAt());
        if (action == StrictAction.SHOW_RESTORE_WARNING) {
            lastQuitWarningDeadline = null;
        } else if (action == StrictAction.QUIT_CHROME
                && !Objects.equals(lastQuitWarningDeadline, session.warningEndsAt())) {
            lastQuitWarningDeadline = session.warningEndsAt();
            requestGracefulQuit();
        }
    }

    private void syncRestoreWarning(Instant deadline) {
        if (Objects.equals(notifiedWarningDeadline, deadline)) {
            return;
        }
        if (deadline == null) {
            clearRestoreWarning();
            return;
        }
        try {
            restoreWarningNotifier.show(deadline);
            notifiedWarningDeadline = deadline;
        } catch (RuntimeException exception) {
            // Warning delivery failure must not stop the service or skip later retries.
        }
    }

    private void clearRestoreWarning() {
        if (notifiedWarningDeadline == null) {
            return;
        }
        try {
            restoreWarningNotifier.clear();
            notifiedWarningDeadline = null;
        } catch (RuntimeException exception) {
            // Retain the deadline so a later state transition can retry cleanup.
        }
    }

    private ChromeProcessState chromeProcessState() {
        try {
            return chromeController.isRunning()
                    ? ChromeProcessState.RUNNING
                    : ChromeProcessState.NOT_RUNNING;
        } catch (RuntimeException exception) {
            return ChromeProcessState.UNKNOWN;
        }
    }

    private void requestGracefulQuit() {
        try {
            chromeController.requestGracefulQuit();
        } catch (RuntimeException exception) {
            // An unavailable controller leaves the session active and never escalates to force kill.
        }
    }

    private void safeScheduledAdvance() {
        try {
            scheduledAdvance();
        } catch (RuntimeException exception) {
            // Fixed-rate tasks stop after an uncaught failure; retain the next deadline check.
        }
    }

    private synchronized void scheduledAdvance() {
        if (closed) {
            return;
        }
        var active = repository.loadActive();
        if (active.isEmpty()) {
            updateSchedule(null);
            return;
        }
        var session = active.orElseThrow();
        advancePersistAndAct(session, clock.instant());
        updateSchedule(session.status() == SessionStatus.ACTIVE ? session : null);
    }

    private void updateSchedule(StrictSession session) {
        var needsCheck = session != null
                && session.status() == SessionStatus.ACTIVE
                && (session.warningEndsAt() != null
                        || (session.mode() == StrictMode.TIMED && session.endsAt() != null)
                        || chromeStateUncertain
                        || connectionHealth == ConnectionHealth.DISCONNECTED);
        if (needsCheck
                && (scheduledCheck == null
                        || scheduledCheck.isCancelled()
                        || scheduledCheck.isDone())) {
            scheduledCheck = scheduler.scheduleAtFixedRate(
                    this::safeScheduledAdvance, 1, 1, TimeUnit.SECONDS);
        } else if (!needsCheck && scheduledCheck != null) {
            scheduledCheck.cancel(false);
            scheduledCheck = null;
        }
    }

    private ProtocolMessage statusResponse(StrictSession session) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("active", true);
        payload.put("sessionId", session.id().toString());
        payload.put("mode", session.mode().name());
        payload.put("startedAt", session.startedAt().toString());
        payload.put("earlyExitChallenge", session.earlyExitChallenge());
        payload.put("status", session.status().name());
        payload.put("connectionHealth", connectionHealth.name());
        if (session.endsAt() != null) {
            payload.put("endsAt", session.endsAt().toString());
        }
        if (session.warningEndsAt() != null) {
            payload.put("warningEndsAt", session.warningEndsAt().toString());
        }
        return authenticatedResponse("service.status", payload);
    }

    private ProtocolMessage focusSettingsResponse(FocusSettings settings) {
        var settingsPayload = new LinkedHashMap<>(FocusSettingsPayload.toPayload(settings));
        settingsPayload.remove("revision");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("revision", settings.revision());
        payload.put("settings", settingsPayload);
        payload.put("chromeAppliedRevision", chromeAppliedRevision);
        return authenticatedResponse("service.focusSettings", payload);
    }

    private Map<String, Object> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Expected an object");
        }
        var result = new LinkedHashMap<String, Object>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Expected string object keys");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private long nonNegativeLong(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException("Expected an integer revision");
        }
        var revision = ((Number) value).longValue();
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
        return revision;
    }

    private static FocusSettings defaultFocusSettings() {
        var rule = new FocusRule(
                false,
                5,
                10,
                60,
                List.of(
                        FocusIntervention.NOTIFY,
                        FocusIntervention.PAUSE,
                        FocusIntervention.CLOSE_TAB,
                        FocusIntervention.BLOCK));
        return new FocusSettings(
                0,
                false,
                Map.of(
                        FocusSite.INSTAGRAM_REELS, rule,
                        FocusSite.X_TIMELINE, rule,
                        FocusSite.YOUTUBE_SHORTS, rule));
    }

    private ProtocolMessage authenticatedError(String type) {
        return authenticatedResponse(type, Map.of());
    }

    private ProtocolMessage unauthenticatedError(String type) {
        return new ProtocolMessage(PROTOCOL_VERSION, "", type, Map.of());
    }

    private ProtocolMessage authenticatedResponse(String type, Map<String, Object> payload) {
        return new ProtocolMessage(PROTOCOL_VERSION, secret, type, payload);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (scheduledCheck != null) {
            scheduledCheck.cancel(false);
            scheduledCheck = null;
        }
        clearRestoreWarning();
        try {
            restoreWarningNotifier.close();
        } catch (RuntimeException exception) {
            // Shutdown remains best effort and never escalates enforcement.
        }
        scheduler.shutdownNow();
    }

    private enum ChromeProcessState {
        RUNNING,
        NOT_RUNNING,
        UNKNOWN
    }
}
