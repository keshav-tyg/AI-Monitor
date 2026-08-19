package com.localfocuscoach.strict.focus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

/** The complete immutable focus-settings document. Revision zero is unsaved. */
public record FocusSettings(long revision, boolean enabled, Map<FocusSite, FocusRule> rules) {
    public FocusSettings {
        if (revision < 0 || rules == null || !rules.keySet().equals(EnumSet.allOf(FocusSite.class))) {
            throw new IllegalArgumentException("Focus settings are incomplete");
        }
        var copiedRules = new EnumMap<FocusSite, FocusRule>(FocusSite.class);
        copiedRules.putAll(rules);
        if (copiedRules.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Focus settings are incomplete");
        }
        rules = Collections.unmodifiableMap(copiedRules);
    }

    public FocusSettings withRevision(long value) {
        return new FocusSettings(value, enabled, rules);
    }
}
