package com.localfocuscoach.strict.service;

import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import com.localfocuscoach.strict.protocol.ProtocolCodec;
import com.localfocuscoach.strict.protocol.ProtocolMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UnixSocketServer implements AutoCloseable {
    private static final int MAX_FRAME_BYTES = 64 * 1024;

    private final Path socketPath;
    private final ProtocolCodec codec;
    private final StrictModeService service;
    private final Clock clock;
    private final ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
    private ServerSocketChannel serverChannel;
    private Thread acceptThread;
    private volatile boolean running;

    public UnixSocketServer(
            Path socketPath, ProtocolCodec codec, StrictModeService service, Clock clock) {
        this.socketPath = Objects.requireNonNull(socketPath).toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec);
        this.service = Objects.requireNonNull(service);
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized void start() throws IOException {
        if (running) {
            throw new IllegalStateException("Socket server is already running");
        }
        var runtimeDirectory = socketPath.getParent();
        Files.createDirectories(runtimeDirectory);
        Files.setPosixFilePermissions(
                runtimeDirectory, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
        Files.deleteIfExists(socketPath);
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
            Files.setPosixFilePermissions(socketPath, Set.of(OWNER_READ, OWNER_WRITE));
        } catch (IOException exception) {
            serverChannel.close();
            serverChannel = null;
            throw exception;
        }
        running = true;
        acceptThread = Thread.ofPlatform()
                .name("strict-mode-socket")
                .start(this::acceptLoop);
    }

    public static Path defaultSocketPath() {
        return InstallSecret.defaultAppSupportDirectory()
                .resolve("run")
                .resolve("strict-mode.sock");
    }

    private void acceptLoop() {
        while (running) {
            try {
                var client = serverChannel.accept();
                clients.submit(() -> handleClient(client));
            } catch (IOException exception) {
                if (running) {
                    close();
                }
            }
        }
    }

    private void handleClient(java.nio.channels.SocketChannel client) {
        try (client;
                var input = Channels.newInputStream(client);
                var output = Channels.newOutputStream(client)) {
            while (true) {
                var frame = readFrame(input);
                if (frame == null) {
                    return;
                }
                ProtocolMessage response;
                try {
                    response = service.handle(codec.decode(frame), clock.instant());
                } catch (IllegalArgumentException | NullPointerException exception) {
                    response = new ProtocolMessage(1, "", "error.invalidRequest", java.util.Map.of());
                }
                output.write(codec.encode(response).getBytes(StandardCharsets.UTF_8));
                output.write('\n');
                output.flush();
            }
        } catch (IOException exception) {
            // A local client may disconnect at any point; no service state changes are inferred.
        }
    }

    private String readFrame(java.io.InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (true) {
            var next = input.read();
            if (next == -1) {
                return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8);
            }
            if (next == '\n') {
                return bytes.toString(StandardCharsets.UTF_8);
            }
            if (bytes.size() >= MAX_FRAME_BYTES) {
                throw new IOException("Protocol frame exceeds maximum size");
            }
            bytes.write(next);
        }
    }

    @Override
    public synchronized void close() {
        if (!running && serverChannel == null) {
            clients.shutdownNow();
            return;
        }
        running = false;
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {
                // Closing is best-effort and cannot justify a state mutation.
            }
            serverChannel = null;
        }
        clients.shutdownNow();
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
            // A stale socket is removed safely on the next start.
        }
    }
}
