package com.localfocuscoach.strict.focus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit conversion between a focus-settings record and its protocol payload. */
public final class FocusSettingsPayload {
    private static final Set<String> PAYLOAD_FIELDS = Set.of("revision", "enabled", "rules");
    private static final FocusSettingsValidator VALIDATOR = new FocusSettingsValidator();

    private FocusSettingsPayload() {}

    public static Map<String, Object> toPayload(FocusSettings settings) {
        var rules = new LinkedHashMap<String, Object>();
        for (var site : FocusSite.values()) {
            var rule = settings.rules().get(site);
            var rulePayload = new LinkedHashMap<String, Object>();
            rulePayload.put("enabled", rule.enabled());
            rulePayload.put("doomscrollBudgetMinutes", rule.doomscrollBudgetMinutes());
            rulePayload.put("warningScore", rule.warningScore());
            rulePayload.put("gracePeriodSeconds", rule.gracePeriodSeconds());
            rulePayload.put(
                    "interventions",
                    rule.interventions().stream().map(FocusIntervention::payloadValue).toList());
            rules.put(site.payloadValue(), rulePayload);
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("revision", settings.revision());
        payload.put("enabled", settings.enabled());
        payload.put("rules", rules);
        return Collections.unmodifiableMap(payload);
    }

    public static FocusSettings fromPayload(Map<String, Object> payload) {
        if (payload == null || !payload.keySet().equals(PAYLOAD_FIELDS)) {
            throw new IllegalArgumentException("Focus settings payload has invalid fields");
        }
        var revision = revision(payload.get("revision"));
        var settingsPayload = new LinkedHashMap<String, Object>();
        settingsPayload.put("enabled", payload.get("enabled"));
        settingsPayload.put("rules", payload.get("rules"));
        return VALIDATOR.parse(settingsPayload).withRevision(revision);
    }

    private static long revision(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException("Focus settings revision must be an integer");
        }
        var revision = ((Number) value).longValue();
        if (revision < 0) {
            throw new IllegalArgumentException("Focus settings revision must not be negative");
        }
        return revision;
    }
}
