package com.localfocuscoach.strict.dashboard;

import com.localfocuscoach.strict.protocol.ProtocolCodec;
import com.localfocuscoach.strict.protocol.ProtocolMessage;
import com.localfocuscoach.strict.service.InstallSecret;
import com.localfocuscoach.strict.service.UnixSocketServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javafx.application.Platform;

public final class ServiceClient implements AutoCloseable {
    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_FRAME_BYTES = 64 * 1024;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_IO_TIMEOUT = Duration.ofSeconds(2);

    private final String secret;
    private final Function<ProtocolMessage, ProtocolMessage> exchange;
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();

    public ServiceClient() {
        this(UnixSocketServer.defaultSocketPath(), InstallSecret.loadOrCreateDefault());
    }

    public ServiceClient(Path socketPath, String secret) {
        this(socketPath, secret, DEFAULT_CONNECT_TIMEOUT, DEFAULT_IO_TIMEOUT);
    }

    ServiceClient(
            Path socketPath, String secret, Duration connectTimeout, Duration ioTimeout) {
        this(secret, socketExchange(socketPath, connectTimeout, ioTimeout));
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
        if (request.version() != PROTOCOL_VERSION || !secret.equals(request.secret())) {
            throw new IllegalArgumentException("Request is not authenticated for this client");
        }
        var response = Objects.requireNonNull(exchange.apply(request));
        if (response.version() != PROTOCOL_VERSION || !secret.equals(response.secret())) {
            throw new IllegalStateException("Strict Mode service returned an invalid response");
        }
        return response;
    }

    public ProtocolMessage request(String type, Map<String, Object> payload) {
        return request(new ProtocolMessage(PROTOCOL_VERSION, secret, type, payload));
    }

    public void requestAsync(
            String type,
            Map<String, Object> payload,
            BiConsumer<ProtocolMessage, RuntimeException> completion) {
        Objects.requireNonNull(completion);
        var request = new ProtocolMessage(PROTOCOL_VERSION, secret, type, payload);
        requests.execute(() -> {
            ProtocolMessage response = null;
            RuntimeException failure = null;
            try {
                response = request(request);
            } catch (RuntimeException exception) {
                failure = exception;
            }
            var completedResponse = response;
            var completedFailure = failure;
            Platform.runLater(() -> completion.accept(completedResponse, completedFailure));
        });
    }

    public void getFocusSettingsAsync(
            BiConsumer<ProtocolMessage, RuntimeException> completion) {
        requestAsync("dashboard.focusSettings.get", Map.of(), completion);
    }

    public void saveFocusSettingsAsync(
            Map<String, Object> settings,
            BiConsumer<ProtocolMessage, RuntimeException> completion) {
        requestAsync(
                "dashboard.focusSettings.save",
                Map.of("settings", Objects.requireNonNull(settings)),
                completion);
    }

    @Override
    public void close() {
        requests.shutdownNow();
    }

    private static Function<ProtocolMessage, ProtocolMessage> socketExchange(
            Path socketPath, Duration connectTimeout, Duration ioTimeout) {
        Objects.requireNonNull(socketPath);
        requirePositive(connectTimeout, "Connect timeout");
        requirePositive(ioTimeout, "I/O timeout");
        return request -> exchange(socketPath, connectTimeout, ioTimeout, request);
    }

    private static ProtocolMessage exchange(
            Path socketPath,
            Duration connectTimeout,
            Duration ioTimeout,
            ProtocolMessage request) {
        var codec = new ProtocolCodec();
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                var selector = Selector.open()) {
            channel.configureBlocking(false);
            connect(channel, selector, socketPath, connectTimeout);
            var encoded = codec.encode(request) + '\n';
            writeFrame(
                    channel,
                    selector,
                    ByteBuffer.wrap(encoded.getBytes(StandardCharsets.UTF_8)),
                    ioTimeout);
            var frame = readFrame(channel, selector, ioTimeout);
            return codec.decode(frame);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Strict Mode service is unavailable", exception);
        }
    }

    private static void connect(
            SocketChannel channel,
            Selector selector,
            Path socketPath,
            Duration timeout)
            throws IOException {
        if (channel.connect(UnixDomainSocketAddress.of(socketPath))) {
            return;
        }
        var deadline = deadline(timeout);
        while (!channel.finishConnect()) {
            awaitReady(channel, selector, SelectionKey.OP_CONNECT, deadline, "connect");
        }
    }

    private static void writeFrame(
            SocketChannel channel, Selector selector, ByteBuffer frame, Duration timeout)
            throws IOException {
        var deadline = deadline(timeout);
        while (frame.hasRemaining()) {
            if (channel.write(frame) == 0) {
                awaitReady(channel, selector, SelectionKey.OP_WRITE, deadline, "write");
            }
        }
    }

    private static String readFrame(SocketChannel channel, Selector selector, Duration timeout)
            throws IOException {
        var bytes = new ByteArrayOutputStream();
        var buffer = ByteBuffer.allocate(1024);
        var deadline = deadline(timeout);
        while (true) {
            var count = channel.read(buffer);
            if (count == -1) {
                throw new IOException("Strict Mode service closed without a complete response");
            }
            if (count == 0) {
                awaitReady(channel, selector, SelectionKey.OP_READ, deadline, "read");
                continue;
            }
            buffer.flip();
            while (buffer.hasRemaining()) {
                var next = buffer.get();
                if (next == '\n') {
                    return bytes.toString(StandardCharsets.UTF_8);
                }
                if (bytes.size() >= MAX_FRAME_BYTES) {
                    throw new IOException("Strict Mode service response is too large");
                }
                bytes.write(next);
            }
            buffer.clear();
        }
    }

    private static void awaitReady(
            SocketChannel channel,
            Selector selector,
            int operation,
            long deadline,
            String phase)
            throws IOException {
        var key = channel.keyFor(selector);
        if (key == null) {
            channel.register(selector, operation);
        } else {
            key.interestOps(operation);
        }
        while (true) {
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new SocketTimeoutException("Strict Mode service " + phase + " timed out");
            }
            var waitMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
            if (selector.select(waitMillis) != 0) {
                selector.selectedKeys().clear();
                return;
            }
        }
    }

    private static long deadline(Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
    }

    private static void requirePositive(Duration timeout, String label) {
        Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }
}
