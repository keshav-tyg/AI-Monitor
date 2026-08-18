package com.localfocuscoach.strict.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;

public final class ProtocolCodec {
    private static final Set<String> FRAME_FIELDS = Set.of("version", "secret", "type", "payload");
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProtocolMessage decode(String json) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON protocol frame", exception);
        }
        if (root == null || !root.isObject() || !hasExactFields(root)) {
            throw new IllegalArgumentException("Protocol frame must contain only required fields");
        }
        var version = root.get("version");
        var secret = root.get("secret");
        var type = root.get("type");
        var payload = root.get("payload");
        if (!version.isIntegralNumber()
                || !version.canConvertToInt()
                || !secret.isTextual()
                || !type.isTextual()
                || type.textValue().isBlank()
                || !payload.isObject()) {
            throw new IllegalArgumentException("Protocol frame fields have invalid types");
        }
        try {
            return new ProtocolMessage(
                    version.intValue(),
                    secret.textValue(),
                    type.textValue(),
                    objectMapper.convertValue(payload, PAYLOAD_TYPE));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Protocol payload is invalid", exception);
        }
    }

    public String encode(ProtocolMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to encode protocol frame", exception);
        }
    }

    private boolean hasExactFields(JsonNode root) {
        var names = new java.util.HashSet<String>();
        root.fieldNames().forEachRemaining(names::add);
        return names.equals(FRAME_FIELDS);
    }
}
