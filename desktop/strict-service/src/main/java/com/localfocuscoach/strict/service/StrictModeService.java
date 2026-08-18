package com.localfocuscoach.strict.service;

import com.localfocuscoach.strict.core.ConnectionHealth;
import com.localfocuscoach.strict.core.SessionStatus;
import com.localfocuscoach.strict.core.StrictAction;
import com.localfocuscoach.strict.core.StrictMode;
import com.localfocuscoach.strict.core.StrictSession;
import com.localfocuscoach.strict.core.StrictStateMachine;
import com.localfocuscoach.strict.core.TypingChallenge;
import com.localfocuscoach.strict.core.TypingChallengeService;
import com.localfocuscoach.strict.protocol.ProtocolMessage;
import com.localfocuscoach.strict.store.StrictSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
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
    private static final Set<String> VALID_TYPES = Set.of(
            "dashboard.start",
            "dashboard.status",
            "dashboard.beginUnlock",
            "dashboard.submitUnlock",
            "relay.connected",
            "relay.heartbeat",
            "relay.disconnected");

    private final String secret;
    private final StrictSessionRepository repository;
    private final ChromeController chromeController;
    private final StrictStateMachine stateMachine = new StrictStateMachine();
    private final TypingChallengeService challengeService = new TypingChallengeService();
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private ConnectionHealth connectionHealth = ConnectionHealth.DISCONNECTED;
    private TypingChallenge activeChallenge;
    private Instant lastQuitWarningDeadline;
    private ScheduledFuture<?> scheduledCheck;
    private boolean closed;

    public StrictModeService(
            String secret, StrictSessionRepository repository, ChromeController chromeController) {
        this(
                secret,
                repository,
                chromeController,
                Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    var thread = new Thread(runnable, "strict-mode-deadline");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    StrictModeService(
            String secret,
            StrictSessionRepository repository,
            ChromeController chromeController,
            Clock clock,
            ScheduledExecutorService scheduler) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Install secret must not be blank");
        }
        this.secret = secret;
        this.repository = Objects.requireNonNull(repository);
        this.chromeController = Objects.requireNonNull(chromeController);
        this.clock = Objects.requireNonNull(clock);
        this.scheduler = Objects.requireNonNull(scheduler);
        var recovered = repository.loadActive().orElse(null);
        if (recovered != null) {
            advanceAndSave(recovered, clock.instant());
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

        var response = switch (message.type()) {
            case "dashboard.start" -> start(message.payload(), now);
            case "dashboard.status" -> status(message.payload(), now);
            case "dashboard.beginUnlock" -> beginUnlock(message.payload(), now);
            case "dashboard.submitUnlock" -> submitUnlock(message.payload());
            case "relay.connected", "relay.heartbeat" ->
                    updateConnection(message.payload(), ConnectionHealth.HEALTHY, now);
            case "relay.disconnected" ->
                    updateConnection(message.payload(), ConnectionHealth.DISCONNECTED, now);
            default -> authenticatedError("error.invalidRequest");
        };
        updateSchedule(repository.loadActive().orElse(null));
        return response;
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
        repository.save(session);
        advanceAndSave(session, now);
        return statusResponse(session);
    }

    private ProtocolMessage status(Map<String, Object> payload, Instant now) {
        if (!payload.isEmpty()) {
            return authenticatedError("error.invalidRequest");
        }
        var active = repository.loadActive();
        if (active.isEmpty()) {
            return authenticatedResponse("service.status", Map.of("active", false));
        }
        var session = active.orElseThrow();
        advanceAndSave(session, now);
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
        }
        var active = repository.loadActive();
        if (active.isEmpty()) {
            return authenticatedResponse("service.ack", Map.of());
        }
        var session = active.orElseThrow();
        advanceAndSave(session, now);
        return session.status() == SessionStatus.ACTIVE
                ? statusResponse(session)
                : authenticatedResponse("service.status", Map.of("active", false));
    }

    private void advanceAndSave(StrictSession session, Instant now) {
        var action = stateMachine.advance(session, connectionHealth, chromeIsRunning(), now);
        repository.save(session);
        if (action == StrictAction.SHOW_RESTORE_WARNING) {
            lastQuitWarningDeadline = null;
        } else if (action == StrictAction.QUIT_CHROME
                && !Objects.equals(lastQuitWarningDeadline, session.warningEndsAt())) {
            lastQuitWarningDeadline = session.warningEndsAt();
            requestGracefulQuit();
        }
    }

    private boolean chromeIsRunning() {
        try {
            return chromeController.isRunning();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void requestGracefulQuit() {
        try {
            chromeController.requestGracefulQuit();
        } catch (RuntimeException exception) {
            // An unavailable controller leaves the session active and never escalates to force kill.
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
        advanceAndSave(session, clock.instant());
        updateSchedule(session.status() == SessionStatus.ACTIVE ? session : null);
    }

    private void updateSchedule(StrictSession session) {
        var needsCheck = session != null
                && session.status() == SessionStatus.ACTIVE
                && (session.warningEndsAt() != null
                        || (session.mode() == StrictMode.TIMED && session.endsAt() != null));
        if (needsCheck && (scheduledCheck == null || scheduledCheck.isCancelled())) {
            scheduledCheck = scheduler.scheduleAtFixedRate(
                    this::scheduledAdvance, 1, 1, TimeUnit.SECONDS);
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
        scheduler.shutdownNow();
    }
}
