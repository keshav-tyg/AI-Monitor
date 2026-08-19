package com.localfocuscoach.strict.focus;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class FocusRuleTest {
    @Test
    void rejectsAnEnabledRuleWithoutInterventions() {
        assertThrows(IllegalArgumentException.class, () -> new FocusRule(true, 5, 10, 60, List.of()));
    }
}
