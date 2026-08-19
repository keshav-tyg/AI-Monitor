package com.localfocuscoach.strict.focus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FocusSettingsPayloadTest {
    @Test
    void payloadRoundTripPreservesRevisionAndRuleOrder() {
        var original = settings(7L, List.of(
                FocusIntervention.NOTIFY,
                FocusIntervention.PAUSE,
                FocusIntervention.CLOSE_TAB,
                FocusIntervention.BLOCK));

        assertEquals(original, FocusSettingsPayload.fromPayload(FocusSettingsPayload.toPayload(original)));
    }

    @Test
    void rejectsUnknownOrMissingPayloadFields() {
        var withExtra = new LinkedHashMap<String, Object>(FocusSettingsPayload.toPayload(settings(7L, List.of(FocusIntervention.NOTIFY))));
        withExtra.put("extra", true);
        assertThrows(IllegalArgumentException.class, () -> FocusSettingsPayload.fromPayload(withExtra));

        var missingRevision = new LinkedHashMap<>(FocusSettingsPayload.toPayload(settings(7L, List.of(FocusIntervention.NOTIFY))));
        missingRevision.remove("revision");
        assertThrows(IllegalArgumentException.class, () -> FocusSettingsPayload.fromPayload(missingRevision));
    }

    @Test
    void emitsProtocolSiteAndInterventionIdentifiers() {
        var payload = FocusSettingsPayload.toPayload(settings(3L, List.of(FocusIntervention.CLOSE_TAB)));

        assertEquals(3L, payload.get("revision"));
        @SuppressWarnings("unchecked")
        var rules = (Map<String, Object>) payload.get("rules");
        @SuppressWarnings("unchecked")
        var instagram = (Map<String, Object>) rules.get("instagram-reels");
        assertEquals(List.of("close-tab"), instagram.get("interventions"));
    }

    private FocusSettings settings(long revision, List<FocusIntervention> interventions) {
        var rule = new FocusRule(true, 5, 10, 60, interventions);
        return new FocusSettings(
                revision,
                true,
                Map.of(
                        FocusSite.INSTAGRAM_REELS, rule,
                        FocusSite.X_TIMELINE, rule,
                        FocusSite.YOUTUBE_SHORTS, rule));
    }
}
