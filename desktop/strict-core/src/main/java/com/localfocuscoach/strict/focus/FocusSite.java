package com.localfocuscoach.strict.focus;

import java.util.Arrays;

/** The feed identifiers supported by both the desktop service and Chrome extension. */
public enum FocusSite {
    INSTAGRAM_REELS("instagram-reels"),
    X_TIMELINE("x-timeline"),
    YOUTUBE_SHORTS("youtube-shorts");

    private final String payloadValue;

    FocusSite(String payloadValue) {
        this.payloadValue = payloadValue;
    }

    public String payloadValue() {
        return payloadValue;
    }

    public static FocusSite fromPayloadValue(String value) {
        return Arrays.stream(values())
                .filter(site -> site.payloadValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported focus site"));
    }
}
