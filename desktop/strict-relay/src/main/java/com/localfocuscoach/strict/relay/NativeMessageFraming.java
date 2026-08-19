package com.localfocuscoach.strict.relay;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

public final class NativeMessageFraming {
    private static final int HEADER_BYTES = Integer.BYTES;
    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;

    private final ObjectMapper objectMapper;

    public NativeMessageFraming() {
        this(new ObjectMapper());
    }

    public NativeMessageFraming(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public Optional<JsonNode> read(InputStream input) throws IOException {
        Objects.requireNonNull(input);
        var header = input.readNBytes(HEADER_BYTES);
        if (header.length == 0) {
            return Optional.empty();
        }
        if (header.length != HEADER_BYTES) {
            throw new EOFException("Native message length prefix is truncated");
        }
        var length = ByteBuffer.wrap(header).order(ByteOrder.nativeOrder()).getInt();
        if (length <= 0 || length > MAX_MESSAGE_BYTES) {
            throw new IOException("Native message length is invalid");
        }
        var payload = input.readNBytes(length);
        if (payload.length != length) {
            throw new EOFException("Native message payload is truncated");
        }
        var json = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload))
                .toString();
        var message = objectMapper
                .reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(json);
        if (message == null) {
            throw new IOException("Native message payload is empty");
        }
        return Optional.of(message);
    }

    public void write(OutputStream output, JsonNode message) throws IOException {
        Objects.requireNonNull(output);
        Objects.requireNonNull(message);
        var payload = objectMapper.writeValueAsBytes(message);
        if (payload.length > MAX_MESSAGE_BYTES) {
            throw new IOException("Native message exceeds maximum size");
        }
        var header = ByteBuffer.allocate(HEADER_BYTES)
                .order(ByteOrder.nativeOrder())
                .putInt(payload.length)
                .array();
        output.write(header);
        output.write(payload);
        output.flush();
    }
}
