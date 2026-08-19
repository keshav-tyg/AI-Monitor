package com.localfocuscoach.strict.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localfocuscoach.strict.service.InstallSecret;
import com.localfocuscoach.strict.service.UnixSocketServer;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class NativeMessagingRelay {
    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_SERVICE_FRAME_BYTES = 64 * 1024;
    private static final Pattern EXTENSION_ORIGIN =
            Pattern.compile("chrome-extension://[a-p]{32}/");
    private static final Set<String> EXTENSION_FIELDS = Set.of("version", "type", "payload");
    private static final Set<String> SERVICE_FIELDS =
            Set.of("version", "secret", "type", "payload");
    private static final Set<String> NATIVE_SERVICE_RESPONSE_TYPES =
            Set.of("service.ack", "service.focusSettings");
    private static final Set<String> FOCUS_SETTINGS_FIELDS = Set.of("enabled", "rules");
    private static final Set<String> FOCUS_RULE_FIELDS = Set.of(
            "enabled", "doomscrollBudgetMinutes", "warningScore", "gracePeriodSeconds", "interventions");
    private static final Set<String> FOCUS_SITE_NAMES =
            Set.of("instagram-reels", "x-timeline", "youtube-shorts");
    private static final Set<String> FOCUS_INTERVENTION_NAMES =
            Set.of("notify", "pause", "close-tab", "block");
    private static final List<String> NATIVE_HOST_NAMES = List.of(
            "com.localfocuscoach.strict_mode", "com.localfocuscoach.strict_mode_dev");

    private final String expectedOrigin;
    private final String secret;
    private final NativeMessageFraming framing;
    private final ObjectMapper objectMapper;
    private final ServiceConnectionFactory connectionFactory;

    public NativeMessagingRelay(
            String expectedOrigin,
            String secret,
            NativeMessageFraming framing,
            ObjectMapper objectMapper,
            ServiceConnectionFactory connectionFactory) {
        if (expectedOrigin == null || !EXTENSION_ORIGIN.matcher(expectedOrigin).matches()) {
            throw new IllegalArgumentException("Expected origin must identify one Chrome extension");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Install secret must not be blank");
        }
        this.expectedOrigin = expectedOrigin;
        this.secret = secret;
        this.framing = Objects.requireNonNull(framing);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
    }

    public int run(
            String callerOrigin,
            InputStream nativeInput,
            OutputStream nativeOutput,
            PrintStream diagnostics) {
        Objects.requireNonNull(nativeInput);
        Objects.requireNonNull(nativeOutput);
        Objects.requireNonNull(diagnostics);

        if (!expectedOrigin.equals(callerOrigin)) {
            diagnostics.println("Rejected unauthorized native host origin");
            writeError(nativeOutput, diagnostics, "unauthorizedOrigin");
            return 2;
        }

        try (var service = connectionFactory.open()) {
            return runConnected(service, nativeInput, nativeOutput, diagnostics);
        } catch (IOException exception) {
            diagnostics.println("Strict Mode service connection failed: " + exception.getMessage());
            writeError(nativeOutput, diagnostics, "serviceUnavailable");
            return 1;
        }
    }

    private int runConnected(
            ServiceConnection service,
            InputStream nativeInput,
            OutputStream nativeOutput,
            PrintStream diagnostics)
            throws IOException {
        var connected = false;
        var disconnected = false;
        try {
            var connectedResponse = exchange(service, "relay.connected");
            connected = true;
            writeNativeResponse(nativeOutput, connectedResponse);
            while (true) {
                var message = framing.read(nativeInput);
                if (message.isEmpty()) {
                    disconnected = true;
                    exchange(service, "relay.disconnected");
                    return 0;
                }
                var request = extensionRequest(message.orElseThrow());
                if (request == null) {
                    diagnostics.println("Rejected invalid extension message");
                    writeError(nativeOutput, diagnostics, "invalidMessage");
                    continue;
                }
                switch (request.type()) {
                    case "extension.heartbeat" ->
                            writeNativeResponse(nativeOutput, exchange(service, "relay.heartbeat"));
                    case "extension.focusSettings.sync" -> {
                        try {
                            writeNativeResponse(
                                    nativeOutput,
                                    exchange(service, "relay.focusSettings.sync", request.payload()));
                        } catch (OversizedServiceFrameException exception) {
                            diagnostics.println("Rejected oversized extension settings sync message");
                            writeError(nativeOutput, diagnostics, "invalidMessage");
                        }
                    }
                    case "extension.openDashboard" -> writeNativeResponse(
                            nativeOutput,
                            exchange(service, "relay.focusSettings.openDashboard"));
                    default -> {
                        // extension.hello establishes the native port but needs no service action.
                    }
                }
            }
        } finally {
            if (connected && !disconnected) {
                try {
                    exchange(service, "relay.disconnected");
                } catch (IOException ignored) {
                    diagnostics.println("Unable to report relay disconnect to Strict Mode service");
                }
            }
        }
    }

    private JsonNode exchange(ServiceConnection service, String type) throws IOException {
        return exchange(service, type, objectMapper.createObjectNode());
    }

    private JsonNode exchange(ServiceConnection service, String type, JsonNode payload)
            throws IOException {
        var requestBytes = serviceRequestBytes(type, payload);
        service.output().write(requestBytes);
        service.output().write('\n');
        service.output().flush();
        return readServiceResponse(service.input());
    }

    private byte[] serviceRequestBytes(String type, JsonNode payload) throws IOException {
        var request = objectMapper.createObjectNode()
                .put("version", PROTOCOL_VERSION)
                .put("secret", secret)
                .put("type", type)
                .set("payload", payload.deepCopy());
        var bytes = objectMapper.writeValueAsBytes(request);
        if (bytes.length > MAX_SERVICE_FRAME_BYTES) {
            throw new OversizedServiceFrameException();
        }
        return bytes;
    }

    private JsonNode readServiceResponse(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (true) {
            var next = input.read();
            if (next == -1) {
                throw new EOFException("Strict Mode service closed without a response");
            }
            if (next == '\n') {
                break;
            }
            if (bytes.size() >= MAX_SERVICE_FRAME_BYTES) {
                throw new IOException("Strict Mode service response exceeds maximum size");
            }
            bytes.write(next);
        }
        final JsonNode response;
        try {
            response = objectMapper.readTree(bytes.toByteArray());
        } catch (RuntimeException exception) {
            throw new IOException("Strict Mode service returned invalid JSON", exception);
        }
        if (response == null
                || !response.isObject()
                || !hasExactFields(response, SERVICE_FIELDS)
                || !response.path("version").isIntegralNumber()
                || response.path("version").intValue() != PROTOCOL_VERSION
                || !response.path("secret").isTextual()
                || !secret.equals(response.path("secret").textValue())
                || !response.path("type").isTextual()
                || !NATIVE_SERVICE_RESPONSE_TYPES.contains(response.path("type").textValue())
                || !response.path("payload").isObject()
                || !validNativeServicePayload(
                        response.path("type").textValue(), response.path("payload"))) {
            throw new IOException("Strict Mode service returned an invalid response");
        }
        return response;
    }

    private boolean validNativeServicePayload(String type, JsonNode payload) {
        return switch (type) {
            case "service.ack" -> payload.size() == 0;
            case "service.focusSettings" -> validFocusSettingsResponsePayload(payload);
            default -> false;
        };
    }

    private boolean validFocusSettingsResponsePayload(JsonNode payload) {
        return hasExactFields(payload, Set.of("revision", "settings", "chromeAppliedRevision"))
                && positiveLong(payload.path("revision"))
                && nonNegativeLong(payload.path("chromeAppliedRevision"))
                && validFocusSettingsPayload(payload.path("settings"));
    }

    private boolean validFocusSettingsPayload(JsonNode settings) {
        if (!settings.isObject()
                || !hasExactFields(settings, FOCUS_SETTINGS_FIELDS)
                || !settings.path("enabled").isBoolean()
                || !settings.path("rules").isObject()
                || !hasExactFields(settings.path("rules"), FOCUS_SITE_NAMES)) {
            return false;
        }
        for (var site : FOCUS_SITE_NAMES) {
            if (!validFocusRulePayload(settings.path("rules").path(site))) {
                return false;
            }
        }
        return true;
    }

    private boolean validFocusRulePayload(JsonNode rule) {
        if (!rule.isObject()
                || !hasExactFields(rule, FOCUS_RULE_FIELDS)
                || !rule.path("enabled").isBoolean()
                || !intInRange(rule.path("doomscrollBudgetMinutes"), 1, 60)
                || !intInRange(rule.path("warningScore"), 1, 50)
                || !intInRange(rule.path("gracePeriodSeconds"), 0, 600)
                || !rule.path("interventions").isArray()) {
            return false;
        }
        var interventions = new HashSet<String>();
        for (var intervention : rule.path("interventions")) {
            if (!intervention.isTextual()
                    || !FOCUS_INTERVENTION_NAMES.contains(intervention.textValue())
                    || !interventions.add(intervention.textValue())) {
                return false;
            }
        }
        return !rule.path("enabled").booleanValue() || !interventions.isEmpty();
    }

    private boolean intInRange(JsonNode value, int minimum, int maximum) {
        return value.isIntegralNumber()
                && value.canConvertToInt()
                && value.intValue() >= minimum
                && value.intValue() <= maximum;
    }

    private void writeNativeResponse(OutputStream nativeOutput, JsonNode serviceResponse)
            throws IOException {
        var nativeResponse = objectMapper.createObjectNode()
                .put("version", PROTOCOL_VERSION)
                .put("type", serviceResponse.path("type").textValue())
                .set("payload", serviceResponse.path("payload").deepCopy());
        framing.write(nativeOutput, nativeResponse);
    }

    private RelayRequest extensionRequest(JsonNode message) {
        if (!message.isObject()
                || !hasExactFields(message, EXTENSION_FIELDS)
                || !message.path("version").isIntegralNumber()
                || message.path("version").intValue() != PROTOCOL_VERSION
                || !message.path("type").isTextual()
                || !message.path("payload").isObject()) {
            return null;
        }
        var type = message.path("type").textValue();
        var payload = message.path("payload");
        return switch (type) {
            case "extension.hello", "extension.heartbeat", "extension.openDashboard" ->
                    payload.size() == 0 ? new RelayRequest(type, payload) : null;
            case "extension.focusSettings.sync" -> validFocusSettingsSyncPayload(payload)
                    ? new RelayRequest(type, payload)
                    : null;
            default -> null;
        };
    }

    private boolean validFocusSettingsSyncPayload(JsonNode payload) {
        if (!(hasExactFields(payload, Set.of("appliedRevision"))
                || hasExactFields(payload, Set.of("appliedRevision", "legacySettings")))) {
            return false;
        }
        var appliedRevision = payload.path("appliedRevision");
        return nonNegativeLong(appliedRevision)
                && (!payload.has("legacySettings") || payload.path("legacySettings").isObject());
    }

    private boolean positiveLong(JsonNode value) {
        return nonNegativeLong(value) && value.longValue() > 0;
    }

    private boolean nonNegativeLong(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
    }

    private boolean hasExactFields(JsonNode node, Set<String> expected) {
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private void writeError(OutputStream output, PrintStream diagnostics, String code) {
        ObjectNode error = objectMapper.createObjectNode()
                .put("version", PROTOCOL_VERSION)
                .put("type", "relay.error");
        error.set("payload", objectMapper.createObjectNode().put("code", code));
        try {
            framing.write(output, error);
        } catch (IOException exception) {
            diagnostics.println("Unable to write native error frame: " + exception.getMessage());
        }
    }

    public static Path defaultHostManifestPath() {
        return defaultNativeHostsDirectory().resolve("com.localfocuscoach.strict_mode.json");
    }

    public static Path defaultNativeHostsDirectory() {
        return Path.of(
                System.getProperty("user.home"),
                "Library",
                "Application Support",
                "Google",
                "Chrome",
                "NativeMessagingHosts");
    }

    public static String readInstalledOrigin(Path manifestPath, ObjectMapper objectMapper)
            throws IOException {
        var manifest = objectMapper.readTree(Files.readAllBytes(manifestPath));
        var origins = manifest == null ? null : manifest.get("allowed_origins");
        if (origins == null
                || !origins.isArray()
                || origins.size() != 1
                || !origins.get(0).isTextual()) {
            throw new IOException("Native host manifest must contain exactly one allowed origin");
        }
        var origin = origins.get(0).textValue();
        if (!EXTENSION_ORIGIN.matcher(origin).matches()) {
            throw new IOException("Native host manifest contains an invalid Chrome extension origin");
        }
        return origin;
    }

    public static String readInstalledOriginForCaller(
            Path nativeHostsDirectory, String callerOrigin, ObjectMapper objectMapper)
            throws IOException {
        if (callerOrigin == null || !EXTENSION_ORIGIN.matcher(callerOrigin).matches()) {
            throw new IOException("Chrome provided an invalid extension origin");
        }
        String matchedOrigin = null;
        for (var hostName : NATIVE_HOST_NAMES) {
            var manifestPath = nativeHostsDirectory.resolve(hostName + ".json");
            if (!Files.isRegularFile(manifestPath)) {
                continue;
            }
            var manifest = objectMapper.readTree(Files.readAllBytes(manifestPath));
            if (manifest == null
                    || !manifest.isObject()
                    || !manifest.path("name").isTextual()
                    || !hostName.equals(manifest.path("name").textValue())) {
                throw new IOException("Native host manifest name does not match its configuration");
            }
            var installedOrigin = readInstalledOrigin(manifestPath, objectMapper);
            if (callerOrigin.equals(installedOrigin)) {
                if (matchedOrigin != null) {
                    throw new IOException(
                            "Production and development native hosts share an allowed origin");
                }
                matchedOrigin = installedOrigin;
            }
        }
        if (matchedOrigin == null) {
            throw new IOException("No native host configuration allows the caller origin");
        }
        return matchedOrigin;
    }

    public static void main(String[] args) {
        var objectMapper = new ObjectMapper();
        var framing = new NativeMessageFraming(objectMapper);
        var diagnostics = System.err;
        if (args.length < 1) {
            diagnostics.println("Chrome did not provide a native host caller origin");
            writeStartupError(framing, objectMapper, diagnostics, "missingOrigin");
            return;
        }
        try {
            var relay = new NativeMessagingRelay(
                    readInstalledOriginForCaller(
                            defaultNativeHostsDirectory(), args[0], objectMapper),
                    InstallSecret.loadOrCreateDefault(),
                    framing,
                    objectMapper,
                    new UnixServiceConnectionFactory(UnixSocketServer.defaultSocketPath()));
            relay.run(args[0], System.in, System.out, diagnostics);
        } catch (IOException | RuntimeException exception) {
            diagnostics.println("Unable to start native relay: " + exception.getMessage());
            writeStartupError(framing, objectMapper, diagnostics, "configurationUnavailable");
        }
    }

    private static void writeStartupError(
            NativeMessageFraming framing,
            ObjectMapper objectMapper,
            PrintStream diagnostics,
            String code) {
        ObjectNode error = objectMapper.createObjectNode()
                .put("version", PROTOCOL_VERSION)
                .put("type", "relay.error");
        error.set("payload", objectMapper.createObjectNode().put("code", code));
        try {
            framing.write(System.out, error);
        } catch (IOException exception) {
            diagnostics.println("Unable to write native error frame: " + exception.getMessage());
        }
    }

    @FunctionalInterface
    public interface ServiceConnectionFactory {
        ServiceConnection open() throws IOException;
    }

    public interface ServiceConnection extends AutoCloseable {
        InputStream input();

        OutputStream output();

        @Override
        void close() throws IOException;
    }

    private record RelayRequest(String type, JsonNode payload) {}

    private static final class OversizedServiceFrameException extends IOException {
        private OversizedServiceFrameException() {
            super("Strict Mode service request exceeds maximum size");
        }
    }

    private static final class UnixServiceConnectionFactory implements ServiceConnectionFactory {
        private final Path socketPath;

        private UnixServiceConnectionFactory(Path socketPath) {
            this.socketPath = socketPath;
        }

        @Override
        public ServiceConnection open() throws IOException {
            var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                channel.connect(UnixDomainSocketAddress.of(socketPath));
                return new ChannelServiceConnection(channel);
            } catch (IOException exception) {
                channel.close();
                throw exception;
            }
        }
    }

    private static final class ChannelServiceConnection implements ServiceConnection {
        private final SocketChannel channel;
        private final InputStream input;
        private final OutputStream output;

        private ChannelServiceConnection(SocketChannel channel) {
            this.channel = channel;
            input = Channels.newInputStream(channel);
            output = Channels.newOutputStream(channel);
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
        public void close() throws IOException {
            channel.close();
        }
    }
}
