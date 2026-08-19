package com.localfocuscoach.strict.store;

import com.localfocuscoach.strict.core.StrictSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface StrictSessionRepository {
    Optional<StrictSession> loadActive();

    void save(StrictSession session);

    void clear(UUID sessionId);

    void appendAudit(UUID sessionId, String event, Instant at);
}
