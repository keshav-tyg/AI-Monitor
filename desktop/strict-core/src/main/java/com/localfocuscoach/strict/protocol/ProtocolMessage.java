package com.localfocuscoach.strict.protocol;

import java.util.Map;

public record ProtocolMessage(int version, String secret, String type, Map<String, Object> payload) {
    public ProtocolMessage {
        payload = payload == null ? null : Map.copyOf(payload);
    }
}
