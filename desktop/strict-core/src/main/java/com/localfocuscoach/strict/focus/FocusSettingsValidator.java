package com.localfocuscoach.strict.focus;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates user-proposed focus settings and detects Strict Mode weakening changes. */
public final class FocusSettingsValidator {
    private static final Set<String> SETTINGS_FIELDS = Set.of("enabled", "rules");
    private static final Set<String> RULE_FIELDS = Set.of(
            "enabled", "doomscrollBudgetMinutes", "warningScore", "gracePeriodSeconds", "interventions");

    public FocusSettings parse(Map<String, Object> value) {
        if (!hasExactFields(value, SETTINGS_FIELDS) || !(value.get("enabled") instanceof Boolean enabled)) {
            throw new IllegalArgumentException("Focus settings have invalid fields");
        }

        var rulesValue = value.get("rules");
        if (!(rulesValue instanceof Map<?, ?> rawRules)) {
            throw new IllegalArgumentException("Focus settings rules must be an object");
        }

        var rules = new EnumMap<FocusSite, FocusRule>(FocusSite.class);
        for (var entry : rawRules.entrySet()) {
            if (!(entry.getKey() instanceof String siteValue)) {
                throw new IllegalArgumentException("Focus settings include an unsupported site");
            }
            var site = FocusSite.fromPayloadValue(siteValue);
            if (rules.put(site, parseRule(entry.getValue())) != null) {
                throw new IllegalArgumentException("Focus settings include duplicate sites");
            }
        }
        return new FocusSettings(0, enabled, rules);
    }

    /**
     * Strict Mode allows only protection enablement, decreased numeric limits, and an unchanged
     * intervention sequence. Any other difference can make future enforcement less strict.
     */
    public boolean isWeakening(FocusSettings current, FocusSettings candidate) {
        if (current.enabled() && !candidate.enabled()) {
            return true;
        }
        for (var site : FocusSite.values()) {
            var existing = current.rules().get(site);
            var proposed = candidate.rules().get(site);
            if ((existing.enabled() && !proposed.enabled())
                    || proposed.doomscrollBudgetMinutes() > existing.doomscrollBudgetMinutes()
                    || proposed.warningScore() > existing.warningScore()
                    || proposed.gracePeriodSeconds() > existing.gracePeriodSeconds()
                    || !proposed.interventions().equals(existing.interventions())) {
                return true;
            }
        }
        return false;
    }

    private FocusRule parseRule(Object value) {
        if (!(value instanceof Map<?, ?> rawRule) || !hasExactFields(rawRule, RULE_FIELDS)) {
            throw new IllegalArgumentException("Focus rule has invalid fields");
        }
        if (!(rawRule.get("enabled") instanceof Boolean enabled)) {
            throw new IllegalArgumentException("Focus rule enabled must be boolean");
        }
        var budget = integer(rawRule.get("doomscrollBudgetMinutes"), "Doomscroll budget");
        var warningScore = integer(rawRule.get("warningScore"), "Warning score");
        var gracePeriodSeconds = integer(rawRule.get("gracePeriodSeconds"), "Grace period");
        var interventions = interventions(rawRule.get("interventions"));
        if (enabled && interventions.isEmpty()) {
            throw new IllegalArgumentException("Enabled focus rules need an intervention");
        }
        return new FocusRule(enabled, budget, warningScore, gracePeriodSeconds, interventions);
    }

    private List<FocusIntervention> interventions(Object value) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("Focus interventions must be an array");
        }
        var parsed = values.stream()
                .map(item -> {
                    if (!(item instanceof String intervention)) {
                        throw new IllegalArgumentException("Focus intervention must be a string");
                    }
                    return FocusIntervention.fromPayloadValue(intervention);
                })
                .toList();
        if (new HashSet<>(parsed).size() != parsed.size()) {
            throw new IllegalArgumentException("Focus interventions must be unique");
        }
        return parsed;
    }

    private int integer(Object value, String field) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        var number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " is out of range");
        }
        return (int) number;
    }

    private boolean hasExactFields(Map<?, ?> value, Set<String> fields) {
        return value != null && value.keySet().equals(fields);
    }
}
