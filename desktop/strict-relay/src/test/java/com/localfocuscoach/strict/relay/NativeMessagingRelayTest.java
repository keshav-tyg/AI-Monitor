package com.localfocuscoach.strict.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeMessagingRelayTest {
    private static final String EXPECTED_ORIGIN =
            "chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/";
    private static final String SECRET = "installation-secret";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NativeMessageFraming framing = new NativeMessageFraming(objectMapper);

    @Test
    void mapsPortLifecycleAndHeartbeatToAuthenticatedServiceMessages() throws Exception {
        var chromeInput = nativeInput(
                extensionMessage("extension.hello"), extensionMessage("extension.heartbeat"));
        var chromeOutput = new ByteArrayOutputStream();
        var service = serviceWithResponses(3);
        var relay = relay(() -> service);

        assertEquals(
                0,
                relay.run(
                        EXPECTED_ORIGIN,
                        chromeInput,
                        chromeOutput,
                        new PrintStream(OutputStream.nullOutputStream())));

        assertEquals(
                List.of("relay.connected", "relay.heartbeat", "relay.disconnected"),
                service.requestTypes());
        assertEquals(List.of(SECRET, SECRET, SECRET), service.requestSecrets());
        assertEquals(List.of("service.ack", "service.ack"), nativeOutputTypes(chromeOutput));
    }

    @Test
    void eofReportsExactlyOneDisconnect() throws Exception {
        var chromeOutput = new ByteArrayOutputStream();
        var service = serviceWithResponses(2);
        var relay = relay(() -> service);

        relay.run(
                EXPECTED_ORIGIN,
                InputStream.nullInputStream(),
                chromeOutput,
                new PrintStream(OutputStream.nullOutputStream()));

        assertEquals(List.of("relay.connected", "relay.disconnected"), service.requestTypes());
    }

    @Test
    void stdoutFailureAfterConnectedAckStillReportsExactlyOneDisconnect() throws Exception {
        var service = serviceWithResponses(2);
        var relay = relay(() -> service);

        assertEquals(
                1,
                relay.run(
                        EXPECTED_ORIGIN,
                        InputStream.nullInputStream(),
                        new FailingOutputStream(),
                        new PrintStream(OutputStream.nullOutputStream())));

        assertEquals(List.of("relay.connected", "relay.disconnected"), service.requestTypes());
    }

    @Test
    void wrongOriginReturnsErrorBeforeOpeningServiceSocket() throws Exception {
        var opens = new int[1];
        var chromeOutput = new ByteArrayOutputStream();
        var relay = relay(() -> {
            opens[0]++;
            throw new AssertionError("wrong origin must not open the service socket");
        });

        assertEquals(
                2,
                relay.run(
                        "chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        InputStream.nullInputStream(),
                        chromeOutput,
                        new PrintStream(OutputStream.nullOutputStream())));

        assertEquals(0, opens[0]);
        assertEquals("relay.error", firstNativeOutput(chromeOutput).path("type").textValue());
        assertEquals(
                "unauthorizedOrigin",
                firstNativeOutput(chromeOutput).path("payload").path("code").textValue());
    }

    @Test
    void serviceSocketFailureReturnsValidErrorFrameAndDiagnosticsStayOffStdout()
            throws Exception {
        var chromeOutput = new ByteArrayOutputStream();
        var diagnostics = new ByteArrayOutputStream();
        var relay = relay(() -> {
            throw new IOException("private socket detail");
        });

        assertEquals(
                1,
                relay.run(
                        EXPECTED_ORIGIN,
                        InputStream.nullInputStream(),
                        chromeOutput,
                        new PrintStream(diagnostics, true, StandardCharsets.UTF_8)));

        var frame = firstNativeOutput(chromeOutput);
        assertEquals(1, frame.path("version").intValue());
        assertEquals("relay.error", frame.path("type").textValue());
        assertEquals("serviceUnavailable", frame.path("payload").path("code").textValue());
        assertFalse(frame.has("secret"));
        assertTrue(diagnostics.toString(StandardCharsets.UTF_8).contains("private socket detail"));
        assertFalse(new String(chromeOutput.toByteArray(), StandardCharsets.UTF_8)
                .contains("private socket detail"));
    }

    @Test
    void protocolVersionMismatchIsNotForwardedAsAHeartbeat() throws Exception {
        var invalid = extensionMessage("extension.heartbeat").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).put("version", 2);
        var chromeOutput = new ByteArrayOutputStream();
        var service = serviceWithResponses(2);
        var relay = relay(() -> service);

        relay.run(
                EXPECTED_ORIGIN,
                nativeInput(invalid),
                chromeOutput,
                new PrintStream(OutputStream.nullOutputStream()));

        assertEquals(List.of("relay.connected", "relay.disconnected"), service.requestTypes());
        assertEquals(
                List.of("service.ack", "relay.error"), nativeOutputTypes(chromeOutput));
    }

    @Test
    void syncForwardsOnlyRevisionAndOptionalLegacySettings() throws Exception {
        var legacySettings = objectMapper.readTree("{\"rules\":[]}");
        var chromeOutput = new ByteArrayOutputStream();
        var service = serviceWithResponses(3);
        var relay = relay(() -> service);

        assertEquals(
                0,
                relay.run(
                        EXPECTED_ORIGIN,
                        nativeInput(sync(4L, legacySettings)),
                        chromeOutput,
                        new PrintStream(OutputStream.nullOutputStream())));

        var request = service.requests().get(1);
        assertEquals("relay.focusSettings.sync", request.path("type").textValue());
        assertEquals(4L, request.path("payload").path("appliedRevision").longValue());
        assertEquals(legacySettings, request.path("payload").path("legacySettings"));
        assertEquals(Set.of("appliedRevision", "legacySettings"), fieldNames(request.path("payload")));
    }

    @Test
    void rejectsExtraFieldsAndNeverForwardsPageContent() throws Exception {
        var chromeOutput = new ByteArrayOutputStream();
        var service = serviceWithResponses(2);
        var relay = relay(() -> service);

        assertEquals(
                0,
                relay.run(
                        EXPECTED_ORIGIN,
                        nativeInput(raw("extension.focusSettings.sync", "{\"text\":\"page\"}")),
                        chromeOutput,
                        new PrintStream(OutputStream.nullOutputStream())));

        assertEquals(List.of("relay.connected", "relay.disconnected"), service.requestTypes());
        assertFalse(service.requests().stream().anyMatch(request -> request.toString().contains("page")));
        assertEquals(List.of("service.ack", "relay.error"), nativeOutputTypes(chromeOutput));
    }

    @Test
    void openDashboardForwardsOnlyAnExactEmptyPayload() throws Exception {
        var chromeOutput = new ByteArrayOutputStream();
        var service = serviceWithResponses(3);
        var relay = relay(() -> service);

        assertEquals(
                0,
                relay.run(
                        EXPECTED_ORIGIN,
                        nativeInput(
                                extensionMessage("extension.openDashboard"),
                                raw("extension.openDashboard", "{\"page\":\"content\"}")),
                        chromeOutput,
                        new PrintStream(OutputStream.nullOutputStream())));

        var request = service.requests().get(1);
        assertEquals("relay.focusSettings.openDashboard", request.path("type").textValue());
        assertTrue(request.path("payload").isEmpty());
        assertEquals(
                List.of("relay.connected", "relay.focusSettings.openDashboard", "relay.disconnected"),
                service.requestTypes());
        assertEquals(
                List.of("service.ack", "service.ack", "relay.error"), nativeOutputTypes(chromeOutput));
    }

    @Test
    void forwardsSettingsSnapshotWithoutServiceSecret() throws Exception {
        var chromeOutput = new ByteArrayOutputStream();
        var service = serviceWithResponses(
                serviceResponse("service.ack", objectMapper.createObjectNode()),
                serviceResponse(
                        "service.focusSettings",
                        objectMapper.readTree(
                                "{\"revision\":4,\"settings\":{},\"chromeAppliedRevision\":4}")),
                serviceResponse("service.ack", objectMapper.createObjectNode()));
        var relay = relay(() -> service);

        assertEquals(
                0,
                relay.run(
                        EXPECTED_ORIGIN,
                        nativeInput(sync(4L)),
                        chromeOutput,
                        new PrintStream(OutputStream.nullOutputStream())));

        var output = new ByteArrayInputStream(chromeOutput.toByteArray());
        framing.read(output).orElseThrow();
        var response = framing.read(output).orElseThrow();
        assertEquals("service.focusSettings", response.path("type").textValue());
        assertFalse(response.has("secret"));
    }

    @Test
    void rejectsUnexpectedServiceResponseTypesInsteadOfForwardingThem() throws Exception {
        var chromeOutput = new ByteArrayOutputStream();
        var service = serviceWithResponses(
                serviceResponse("service.ack", objectMapper.createObjectNode()),
                serviceResponse("service.status", objectMapper.createObjectNode()),
                serviceResponse("service.ack", objectMapper.createObjectNode()));
        var relay = relay(() -> service);

        assertEquals(
                1,
                relay.run(
                        EXPECTED_ORIGIN,
                        nativeInput(sync(4L)),
                        chromeOutput,
                        new PrintStream(OutputStream.nullOutputStream())));

        assertEquals(List.of("service.ack", "relay.error"), nativeOutputTypes(chromeOutput));
    }

    @Test
    void installedManifestMustNameExactlyOneChromeExtensionOrigin(@TempDir Path directory)
            throws Exception {
        var manifest = directory.resolve("com.localfocuscoach.strict_mode.json");
        Files.writeString(
                manifest,
                "{\"allowed_origins\":[\"" + EXPECTED_ORIGIN + "\"]}",
                StandardCharsets.UTF_8);

        assertEquals(
                EXPECTED_ORIGIN,
                NativeMessagingRelay.readInstalledOrigin(manifest, objectMapper));
    }

    @Test
    void callerOriginSelectsOnlyItsSeparateDevelopmentHostConfiguration(@TempDir Path directory)
            throws Exception {
        var developmentOrigin = "chrome-extension://bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/";
        Files.writeString(
                directory.resolve("com.localfocuscoach.strict_mode.json"),
                "{\"name\":\"com.localfocuscoach.strict_mode\",\"allowed_origins\":[\""
                        + EXPECTED_ORIGIN
                        + "\"]}",
                StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve("com.localfocuscoach.strict_mode_dev.json"),
                "{\"name\":\"com.localfocuscoach.strict_mode_dev\",\"allowed_origins\":[\""
                        + developmentOrigin
                        + "\"]}",
                StandardCharsets.UTF_8);

        assertEquals(
                developmentOrigin,
                NativeMessagingRelay.readInstalledOriginForCaller(
                        directory, developmentOrigin, objectMapper));
    }

    @Test
    void duplicateProductionAndDevelopmentAllowlistsAreRejected(@TempDir Path directory)
            throws Exception {
        for (var hostName : List.of(
                "com.localfocuscoach.strict_mode", "com.localfocuscoach.strict_mode_dev")) {
            Files.writeString(
                    directory.resolve(hostName + ".json"),
                    "{\"name\":\""
                            + hostName
                            + "\",\"allowed_origins\":[\""
                            + EXPECTED_ORIGIN
                            + "\"]}",
                    StandardCharsets.UTF_8);
        }

        org.junit.jupiter.api.Assertions.assertThrows(
                IOException.class,
                () -> NativeMessagingRelay.readInstalledOriginForCaller(
                        directory, EXPECTED_ORIGIN, objectMapper));
    }

    private NativeMessagingRelay relay(
            NativeMessagingRelay.ServiceConnectionFactory connectionFactory) {
        return new NativeMessagingRelay(
                EXPECTED_ORIGIN, SECRET, framing, objectMapper, connectionFactory);
    }

    private ByteArrayInputStream nativeInput(JsonNode... messages) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (var message : messages) {
            framing.write(bytes, message);
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }

    private JsonNode extensionMessage(String type) {
        return extensionMessage(type, objectMapper.createObjectNode());
    }

    private JsonNode extensionMessage(String type, JsonNode payload) {
        return objectMapper.createObjectNode()
                .put("version", 1)
                .put("type", type)
                .set("payload", payload);
    }

    private JsonNode sync(long appliedRevision) {
        return sync(appliedRevision, null);
    }

    private JsonNode sync(long appliedRevision, JsonNode legacySettings) {
        var payload = objectMapper.createObjectNode().put("appliedRevision", appliedRevision);
        if (legacySettings != null) {
            payload.set("legacySettings", legacySettings);
        }
        return extensionMessage("extension.focusSettings.sync", payload);
    }

    private JsonNode raw(String type, String payload) throws IOException {
        return extensionMessage(type, objectMapper.readTree(payload));
    }

    private FakeServiceConnection serviceWithResponses(int count) throws IOException {
        var responses = new JsonNode[count];
        for (var index = 0; index < count; index++) {
            responses[index] = serviceResponse("service.ack", objectMapper.createObjectNode());
        }
        return serviceWithResponses(responses);
    }

    private FakeServiceConnection serviceWithResponses(JsonNode... messages) throws IOException {
        var responses = new ByteArrayOutputStream();
        for (var message : messages) {
            responses.write(objectMapper.writeValueAsBytes(message));
            responses.write('\n');
        }
        return new FakeServiceConnection(responses.toByteArray());
    }

    private JsonNode serviceResponse(String type, JsonNode payload) {
        return objectMapper.createObjectNode()
                .put("version", 1)
                .put("secret", SECRET)
                .put("type", type)
                .set("payload", payload);
    }

    private Set<String> fieldNames(JsonNode object) {
        var names = new java.util.HashSet<String>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private List<String> nativeOutputTypes(ByteArrayOutputStream output) throws IOException {
        var input = new ByteArrayInputStream(output.toByteArray());
        var types = new ArrayList<String>();
        while (true) {
            var next = framing.read(input);
            if (next.isEmpty()) {
                return types;
            }
            types.add(next.orElseThrow().path("type").textValue());
        }
    }

    private JsonNode firstNativeOutput(ByteArrayOutputStream output) throws IOException {
        return framing.read(new ByteArrayInputStream(output.toByteArray())).orElseThrow();
    }

    private static final class FailingOutputStream extends OutputStream {
        @Override
        public void write(int value) throws IOException {
            throw new IOException("Chrome stdout is unavailable");
        }
    }

    private final class FakeServiceConnection implements NativeMessagingRelay.ServiceConnection {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private FakeServiceConnection(byte[] responses) {
            input = new ByteArrayInputStream(responses);
        }

        @Override
        public InputStream input() {
            return input;
        }

        @Override
        public OutputStream output() {
            return output;
        }

        @Override
        public void close() {}

        private List<JsonNode> requests() throws IOException {
            var requests = new ArrayList<JsonNode>();
            for (var line : output.toString(StandardCharsets.UTF_8).split("\\n")) {
                if (!line.isEmpty()) {
                    requests.add(objectMapper.readTree(line));
                }
            }
            return requests;
        }

        private List<String> requestTypes() throws IOException {
            return requests().stream().map(node -> node.path("type").textValue()).toList();
        }

        private List<String> requestSecrets() throws IOException {
            return requests().stream().map(node -> node.path("secret").textValue()).toList();
        }
    }
}
