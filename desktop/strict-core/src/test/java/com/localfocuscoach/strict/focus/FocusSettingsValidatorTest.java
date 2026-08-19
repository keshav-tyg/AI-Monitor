package com.localfocuscoach.strict.focus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FocusSettingsValidatorTest {
    private final FocusSettingsValidator validator = new FocusSettingsValidator();

    @Test
    void acceptsExactlyThreeSupportedRulesAndConfiguredRanges() {
        var parsed = validator.parse(Map.of("enabled", true, "rules", validRules()));

        assertEquals(0L, parsed.revision());
        assertEquals(5, parsed.rules().get(FocusSite.INSTAGRAM_REELS).doomscrollBudgetMinutes());
        assertEquals(
                List.of(FocusIntervention.NOTIFY, FocusIntervention.PAUSE),
                parsed.rules().get(FocusSite.X_TIMELINE).interventions());
    }

    @Test
    void rejectsUnknownSitesAndOutOfRangeNumbers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.parse(Map.of("enabled", true, "rules", Map.of("tiktok", Map.of()))));
        assertThrows(IllegalArgumentException.class, () -> validator.parse(settingsWith("warningScore", 51)));
        assertThrows(IllegalArgumentException.class, () -> validator.parse(settingsWith("doomscrollBudgetMinutes", 0)));
        assertThrows(IllegalArgumentException.class, () -> validator.parse(settingsWith("gracePeriodSeconds", 601)));
    }

    @Test
    void rejectsUnknownAndMissingObjectFields() {
        var rule = new LinkedHashMap<String, Object>(validRule());
        rule.put("extra", true);
        assertThrows(IllegalArgumentException.class, () -> validator.parse(settingsWithRule(rule)));

        var incompleteRule = new LinkedHashMap<String, Object>(validRule());
        incompleteRule.remove("interventions");
        assertThrows(IllegalArgumentException.class, () -> validator.parse(settingsWithRule(incompleteRule)));

        assertThrows(
                IllegalArgumentException.class,
                () -> validator.parse(Map.of("enabled", true, "rules", validRules(), "revision", 1)));
    }

    @Test
    void rejectsNonIntegralNumbersDuplicateInterventionsAndUnknownInterventions() {
        assertThrows(IllegalArgumentException.class, () -> validator.parse(settingsWith("warningScore", 10.0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.parse(settingsWith("interventions", List.of("notify", "notify"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.parse(settingsWith("interventions", List.of("notify", "redirect"))));
    }

    @Test
    void identifiesOnlyDefinedWeakeningChangesDuringStrictMode() {
        assertTrue(validator.isWeakening(enabledSettings(5, 10, 60), enabledSettings(6, 10, 60)));
        assertTrue(validator.isWeakening(enabledSettings(5, 10, 60), enabledSettings(5, 11, 60)));
        assertTrue(validator.isWeakening(enabledSettings(5, 10, 60), enabledSettings(5, 10, 61)));
        assertTrue(validator.isWeakening(enabledSettings(5, 10, 60), disabledSettings()));
        assertTrue(validator.isWeakening(enabledSettings(5, 10, 60), reorderedInterventions()));
        assertFalse(validator.isWeakening(enabledSettings(5, 10, 60), enabledSettings(4, 9, 30)));
    }

    @Test
    void acceptsAllConfiguredBoundaryValues() {
        assertDoesNotThrow(() -> validator.parse(settingsWith("doomscrollBudgetMinutes", 60)));
        assertDoesNotThrow(() -> validator.parse(settingsWith("warningScore", 1)));
        assertDoesNotThrow(() -> validator.parse(settingsWith("gracePeriodSeconds", 0)));
    }

    private Map<String, Object> validRules() {
        var rules = new LinkedHashMap<String, Object>();
        rules.put("instagram-reels", validRule());
        rules.put("x-timeline", Map.of(
                "enabled", true,
                "doomscrollBudgetMinutes", 6,
                "warningScore", 11,
                "gracePeriodSeconds", 61,
                "interventions", List.of("notify", "pause")));
        rules.put("youtube-shorts", validRule());
        return rules;
    }

    private Map<String, Object> validRule() {
        return Map.of(
                "enabled", true,
                "doomscrollBudgetMinutes", 5,
                "warningScore", 10,
                "gracePeriodSeconds", 60,
                "interventions", List.of("notify", "pause", "close-tab", "block"));
    }

    private Map<String, Object> settingsWith(String field, Object value) {
        var rule = new LinkedHashMap<String, Object>(validRule());
        rule.put(field, value);
        return settingsWithRule(rule);
    }

    private Map<String, Object> settingsWithRule(Map<String, Object> rule) {
        var rules = new LinkedHashMap<String, Object>();
        rules.put("instagram-reels", rule);
        rules.put("x-timeline", validRule());
        rules.put("youtube-shorts", validRule());
        return Map.of("enabled", true, "rules", rules);
    }

    private FocusSettings enabledSettings(int budget, int warningScore, int gracePeriodSeconds) {
        var rule = new FocusRule(
                true,
                budget,
                warningScore,
                gracePeriodSeconds,
                List.of(FocusIntervention.NOTIFY, FocusIntervention.PAUSE, FocusIntervention.CLOSE_TAB));
        return settings(true, rule);
    }

    private FocusSettings disabledSettings() {
        var rule = new FocusRule(
                true,
                5,
                10,
                60,
                List.of(FocusIntervention.NOTIFY, FocusIntervention.PAUSE, FocusIntervention.CLOSE_TAB));
        return settings(false, rule);
    }

    private FocusSettings reorderedInterventions() {
        var rule = new FocusRule(
                true,
                5,
                10,
                60,
                List.of(FocusIntervention.PAUSE, FocusIntervention.NOTIFY, FocusIntervention.CLOSE_TAB));
        return settings(true, rule);
    }

    private FocusSettings settings(boolean enabled, FocusRule rule) {
        return new FocusSettings(
                0,
                enabled,
                Map.of(
                        FocusSite.INSTAGRAM_REELS, rule,
                        FocusSite.X_TIMELINE, rule,
                        FocusSite.YOUTUBE_SHORTS, rule));
    }
}
