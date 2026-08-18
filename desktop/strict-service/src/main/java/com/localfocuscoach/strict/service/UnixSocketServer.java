package com.localfocuscoach.strict.service;

import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import com.localfocuscoach.strict.protocol.ProtocolCodec;
import com.localfocuscoach.strict.protocol.ProtocolMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UnixSocketServer implements AutoCloseable {
    private static final int MAX_FRAME_BYTES = 64 * 1024;

    private final Path socketPath;
    private final Path lockPath;
    private final ProtocolCodec codec;
    private final StrictModeService service;
    private final Clock clock;
    private final ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
    private ServerSocketChannel serverChannel;
    private FileChannel lockChannel;
    private FileLock instanceLock;
    private Object ownedSocketFileKey;
    private Thread acceptThread;
    private volatile boolean running;

    public UnixSocketServer(
            Path socketPath, ProtocolCodec codec, StrictModeService service, Clock clock) {
        this.socketPath = Objects.requireNonNull(socketPath).toAbsolutePath().normalize();
        lockPath = this.socketPath.resolveSibling(this.socketPath.getFileName() + ".lock");
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
        try {
            acquireInstanceLock();
            removeVerifiedStaleSocket();
            serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
            Files.setPosixFilePermissions(socketPath, Set.of(OWNER_READ, OWNER_WRITE));
            ownedSocketFileKey = readSocketIdentity(socketPath);
        } catch (IOException | RuntimeException exception) {
            closeServerChannel();
            deleteOwnedSocket();
            releaseInstanceLock();
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

    private void acquireInstanceLock() throws IOException {
        lockChannel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        Files.setPosixFilePermissions(lockPath, Set.of(OWNER_READ, OWNER_WRITE));
        try {
            instanceLock = lockChannel.tryLock();
        } catch (OverlappingFileLockException exception) {
            throw new IOException("Strict Mode service is already running", exception);
        }
        if (instanceLock == null) {
            throw new IOException("Strict Mode service is already running");
        }
    }

    private void removeVerifiedStaleSocket() throws IOException {
        if (!Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        var identity = readSocketIdentity(socketPath);
        try (var probe = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            probe.connect(UnixDomainSocketAddress.of(socketPath));
            throw new IOException("Strict Mode socket is already accepting connections");
        } catch (ConnectException exception) {
            deleteSocketIfIdentityMatches(identity);
        }
    }

    private Object readSocketIdentity(Path path) throws IOException {
        var attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !isUnixSocket(path)) {
            throw new IOException("Refusing to replace a non-socket path: " + path);
        }
        var identity = attributes.fileKey();
        if (identity == null) {
            throw new IOException("Unable to verify socket ownership: " + path);
        }
        return identity;
    }

    private boolean isUnixSocket(Path path) throws IOException {
        var mode = (Integer) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
        return (mode & 0170000) == 0140000;
    }

    private void deleteSocketIfIdentityMatches(Object expectedIdentity) throws IOException {
        if (!Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        var currentIdentity = readSocketIdentity(socketPath);
        if (!expectedIdentity.equals(currentIdentity)) {
            throw new IOException("Socket path changed during ownership verification");
        }
        Files.delete(socketPath);
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
                if (bytes.size() != 0) {
                    throw new IOException("Protocol frame is not newline terminated");
                }
                return null;
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
        closeServerChannel();
        clients.shutdownNow();
        deleteOwnedSocket();
        releaseInstanceLock();
    }

    private void closeServerChannel() {
        if (serverChannel == null) {
            return;
        }
        try {
            serverChannel.close();
        } catch (IOException ignored) {
            // Closing is best-effort and cannot justify a state mutation.
        }
        serverChannel = null;
    }

    private void deleteOwnedSocket() {
        if (ownedSocketFileKey == null) {
            return;
        }
        try {
            deleteSocketIfIdentityMatches(ownedSocketFileKey);
        } catch (IOException ignored) {
            // Never unlink a path whose identity cannot be proven to be ours.
        }
        ownedSocketFileKey = null;
    }

    private void releaseInstanceLock() {
        if (instanceLock != null) {
            try {
                instanceLock.release();
            } catch (IOException ignored) {
                // Channel close below releases the lock as a fallback.
            }
            instanceLock = null;
        }
        if (lockChannel != null) {
            try {
                lockChannel.close();
            } catch (IOException ignored) {
                // The operating system releases process locks on termination.
            }
            lockChannel = null;
        }
    }
}
