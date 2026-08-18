package com.localfocuscoach.strict.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypingChallengeServiceTest {
    private final TypingChallengeService service = new TypingChallengeService();
    private final Instant now = Instant.parse("2026-08-18T12:00:00Z");

    @Test
    void createsExactlyFiveHundredAsciiLetters() {
        var challenge = service.create(now);

        assertEquals(500, challenge.target().length());
        assertTrue(challenge.target().matches("[A-Za-z]{500}"));
    }

    @Test
    void requiresAnExactFullMatch() {
        var challenge = new TypingChallenge(UUID.randomUUID(), "AbC", now);

        assertTrue(service.matches(challenge, "AbC"));
        assertFalse(service.matches(challenge, "Abc"));
        assertFalse(service.matches(challenge, "AbC "));
    }
}
