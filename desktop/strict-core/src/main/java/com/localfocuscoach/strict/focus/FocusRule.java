package com.localfocuscoach.strict.focus;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** An immutable focus rule for one supported feed. */
public record FocusRule(
        boolean enabled,
        int doomscrollBudgetMinutes,
        int warningScore,
        int gracePeriodSeconds,
        List<FocusIntervention> interventions) {
    public FocusRule {
        if (doomscrollBudgetMinutes < 1 || doomscrollBudgetMinutes > 60) {
            throw new IllegalArgumentException("Doomscroll budget must be 1 to 60 minutes");
        }
        if (warningScore < 1 || warningScore > 50) {
            throw new IllegalArgumentException("Warning score must be 1 to 50");
        }
        if (gracePeriodSeconds < 0 || gracePeriodSeconds > 600) {
            throw new IllegalArgumentException("Grace period must be 0 to 600 seconds");
        }
        interventions = List.copyOf(Objects.requireNonNull(interventions, "interventions"));
        if (enabled && interventions.isEmpty()) {
            throw new IllegalArgumentException("Enabled focus rules need an intervention");
        }
        if (new HashSet<>(interventions).size() != interventions.size()) {
            throw new IllegalArgumentException("Focus interventions must be unique");
        }
    }
}
