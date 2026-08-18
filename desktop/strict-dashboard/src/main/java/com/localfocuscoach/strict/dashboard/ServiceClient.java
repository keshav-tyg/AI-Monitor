package com.localfocuscoach.strict.dashboard;

import com.localfocuscoach.strict.protocol.ProtocolCodec;
import com.localfocuscoach.strict.protocol.ProtocolMessage;
import com.localfocuscoach.strict.service.InstallSecret;
import com.localfocuscoach.strict.service.UnixSocketServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class ServiceClient {
    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_FRAME_BYTES = 64 * 1024;

    private final String secret;
    private final Function<ProtocolMessage, ProtocolMessage> exchange;

    public ServiceClient() {
        this(UnixSocketServer.defaultSocketPath(), InstallSecret.loadOrCreateDefault());
    }

    public ServiceClient(Path socketPath, String secret) {
        this(secret, request -> exchange(socketPath, request));
    }

    ServiceClient(String secret, Function<ProtocolMessage, ProtocolMessage> exchange) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Install secret must not be blank");
        }
        this.secret = secret;
        this.exchange = Objects.requireNonNull(exchange);
    }

    public ProtocolMessage request(ProtocolMessage request) {
        Objects.requireNonNull(request);
        var response = Objects.requireNonNull(exchange.apply(request));
        if (response.version() != PROTOCOL_VERSION || !secret.equals(response.secret())) {
            throw new IllegalStateException("Strict Mode service returned an invalid response");
        }
        return response;
    }

    public ProtocolMessage request(String type, Map<String, Object> payload) {
        return request(new ProtocolMessage(PROTOCOL_VERSION, secret, type, payload));
    }

    private static ProtocolMessage exchange(Path socketPath, ProtocolMessage request) {
        Objects.requireNonNull(socketPath);
        var codec = new ProtocolCodec();
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            var output = Channels.newOutputStream(channel);
            output.write(codec.encode(request).getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.flush();
            var frame = readFrame(Channels.newInputStream(channel));
            return codec.decode(frame);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Strict Mode service is unavailable", exception);
        }
    }

    private static String readFrame(java.io.InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (true) {
            var next = input.read();
            if (next == -1) {
                throw new IOException("Strict Mode service closed without a complete response");
            }
            if (next == '\n') {
                return bytes.toString(StandardCharsets.UTF_8);
            }
            if (bytes.size() >= MAX_FRAME_BYTES) {
                throw new IOException("Strict Mode service response is too large");
            }
            bytes.write(next);
        }
    }
}
