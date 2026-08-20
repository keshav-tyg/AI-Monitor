package com.localfocuscoach.strict.dashboard;

enum FocusSensitivity {
    MILD(10),
    MEDIUM(5),
    AGGRESSIVE(1);

    private final int warningScore;

    FocusSensitivity(int warningScore) {
        this.warningScore = warningScore;
    }

    int warningScore() {
        return warningScore;
    }

    static FocusSensitivity forStoredScore(int warningScore) {
        if (warningScore < 1 || warningScore > 50) {
            throw new IllegalArgumentException("Warning score must be 1 to 50");
        }
        if (warningScore <= 3) return AGGRESSIVE;
        if (warningScore <= 7) return MEDIUM;
        return MILD;
    }
}
