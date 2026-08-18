package com.localfocuscoach.strict.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TypingChallenge(UUID id, String target, Instant createdAt) {
    public TypingChallenge {
        Objects.requireNonNull(id);
        Objects.requireNonNull(target);
        Objects.requireNonNull(createdAt);
    }
}
