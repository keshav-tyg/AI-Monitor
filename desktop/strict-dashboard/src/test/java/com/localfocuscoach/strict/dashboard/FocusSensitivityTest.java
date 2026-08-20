package com.localfocuscoach.strict.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class FocusSensitivityTest {
    @Test
    void exposesLevelsInGentlestToStrictestOrder() {
        assertEquals(List.of(FocusSensitivity.MILD, FocusSensitivity.MEDIUM,
                FocusSensitivity.AGGRESSIVE),
                List.of(FocusSensitivity.values()));
        assertEquals(10, FocusSensitivity.MILD.warningScore());
        assertEquals(5, FocusSensitivity.MEDIUM.warningScore());
        assertEquals(1, FocusSensitivity.AGGRESSIVE.warningScore());
    }

    @Test
    void mapsLegacyScoresWithoutNormalizingTheirStoredValues() {
        assertEquals(FocusSensitivity.AGGRESSIVE, FocusSensitivity.forStoredScore(2));
        assertEquals(FocusSensitivity.MEDIUM, FocusSensitivity.forStoredScore(6));
        assertEquals(FocusSensitivity.MILD, FocusSensitivity.forStoredScore(10));
    }
}
