package com.localfocuscoach.strict.focus;

import java.util.Arrays;

/** The supported intervention sequence values, in their protocol spelling. */
public enum FocusIntervention {
    NOTIFY("notify"),
    PAUSE("pause"),
    CLOSE_TAB("close-tab"),
    BLOCK("block");

    private final String payloadValue;

    FocusIntervention(String payloadValue) {
        this.payloadValue = payloadValue;
    }

    public String payloadValue() {
        return payloadValue;
    }

    public static FocusIntervention fromPayloadValue(String value) {
        return Arrays.stream(values())
                .filter(intervention -> intervention.payloadValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported focus intervention"));
    }
}
