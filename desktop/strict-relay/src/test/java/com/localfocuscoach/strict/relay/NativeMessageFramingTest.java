package com.localfocuscoach.strict.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class NativeMessageFramingTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NativeMessageFraming framing = new NativeMessageFraming(objectMapper);

    @Test
    void nativeFrameUsesNativeEndianUtf8ByteLengthPrefix() throws Exception {
        var output = new ByteArrayOutputStream();
        var expected = objectMapper.readTree("{\"type\":\"héartbeat\"}");

        framing.write(output, expected);

        var bytes = output.toByteArray();
        var declaredLength = ByteBuffer.wrap(bytes, 0, Integer.BYTES)
                .order(ByteOrder.nativeOrder())
                .getInt();
        assertEquals(
                "{\"type\":\"héartbeat\"}".getBytes(StandardCharsets.UTF_8).length,
                declaredLength);
        assertEquals(
                expected,
                framing.read(new ByteArrayInputStream(bytes)).orElseThrow());
    }

    @Test
    void cleanEofHasNoMessage() throws Exception {
        assertTrue(framing.read(InputStream.nullInputStream()).isEmpty());
    }

    @Test
    void truncatedPayloadIsRejected() throws Exception {
        var header = ByteBuffer.allocate(Integer.BYTES)
                .order(ByteOrder.nativeOrder())
                .putInt(10)
                .array();

        assertThrows(
                java.io.EOFException.class,
                () -> framing.read(new ByteArrayInputStream(header)));
    }
}
