package com.localfocuscoach.strict.core;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class TypingChallengeService {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int TARGET_LENGTH = 500;

    private final SecureRandom random = new SecureRandom();
    private TypingChallenge activeChallenge;

    public TypingChallenge create(Instant now) {
        Objects.requireNonNull(now);

        var target = new StringBuilder(TARGET_LENGTH);
        for (int index = 0; index < TARGET_LENGTH; index++) {
            target.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        activeChallenge = new TypingChallenge(UUID.randomUUID(), target.toString(), now);
        return activeChallenge;
    }

    public boolean matches(TypingChallenge challenge, String candidate) {
        Objects.requireNonNull(challenge);

        var matches = challenge.target().equals(candidate);
        if (matches && challenge.equals(activeChallenge)) {
            activeChallenge = null;
        }
        return matches;
    }
}
