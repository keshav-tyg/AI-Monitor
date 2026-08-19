package com.localfocuscoach.strict.service;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localfocuscoach.strict.protocol.ProtocolCodec;
import com.localfocuscoach.strict.store.SqliteStrictSessionRepository;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnixSocketServerTest {
    private static final String SECRET = "socket-test-secret";
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    private UnixSocketServer server;
    private StrictModeService service;

    @AfterEach
    void closeResources() {
        if (server != null) {
            server.close();
        }
        if (service != null) {
            service.close();
        }
    }

    @Test
    void malformedJsonProducesAnErrorFrameWithoutDatabaseWrite(@TempDir Path directory)
            throws Exception {
        var repository = new SqliteStrictSessionRepository(directory.resolve("strict-mode.sqlite"));
        service = new StrictModeService(SECRET, repository, new AbsentChromeController());
        var codec = new ProtocolCodec();
        var socket = directory.resolve("run").resolve("strict-mode.sock");
        server = new UnixSocketServer(socket, codec, service, Clock.fixed(NOW, ZoneOffset.UTC));
        server.start();

        var response = codec.decode(request(socket, "{not-json}"));

        assertEquals("error.invalidRequest", response.type());
        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void missingSecretProducesAnErrorFrameWithoutDatabaseWrite(@TempDir Path directory)
            throws Exception {
        var repository = new SqliteStrictSessionRepository(directory.resolve("strict-mode.sqlite"));
        service = new StrictModeService(SECRET, repository, new AbsentChromeController());
        var codec = new ProtocolCodec();
        var socket = directory.resolve("run").resolve("strict-mode.sock");
        server = new UnixSocketServer(socket, codec, service, Clock.fixed(NOW, ZoneOffset.UTC));
        server.start();

        var response = codec.decode(request(
                socket,
                "{\"version\":1,\"type\":\"dashboard.status\",\"payload\":{}}"));

        assertEquals("error.invalidRequest", response.type());
        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void installSecretIsStableAndOwnerOnly(@TempDir Path directory) throws Exception {
        var appSupport = directory.resolve("Application Support").resolve("Local Focus Coach");

        var first = InstallSecret.loadOrCreate(appSupport);
        var second = InstallSecret.loadOrCreate(appSupport);

        assertFalse(first.isBlank());
        assertEquals(first, second);
        assertEquals(
                Set.of(OWNER_READ, OWNER_WRITE),
                Files.getPosixFilePermissions(appSupport.resolve("install-secret")));
        assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(appSupport));
    }

    @Test
    void secondServerCannotReplaceAnActiveSocket(@TempDir Path directory) throws Exception {
        var repository = new SqliteStrictSessionRepository(directory.resolve("strict-mode.sqlite"));
        service = new StrictModeService(SECRET, repository, new AbsentChromeController());
        var codec = new ProtocolCodec();
        var socket = directory.resolve("run").resolve("strict-mode.sock");
        server = new UnixSocketServer(socket, codec, service, Clock.fixed(NOW, ZoneOffset.UTC));
        server.start();
        var otherService = new StrictModeService(
                SECRET,
                new SqliteStrictSessionRepository(directory.resolve("other.sqlite")),
                new AbsentChromeController());
        var otherServer = new UnixSocketServer(
                socket, codec, otherService, Clock.fixed(NOW, ZoneOffset.UTC));

        try {
            assertThrows(java.io.IOException.class, otherServer::start);
            otherServer.close();
            assertTrue(Files.exists(socket));
            assertEquals(
                    "service.status",
                    codec.decode(request(
                                    socket,
                                    codec.encode(new com.localfocuscoach.strict.protocol.ProtocolMessage(
                                            1, SECRET, "dashboard.status", java.util.Map.of()))))
                            .type());
        } finally {
            otherServer.close();
            otherService.close();
        }
    }

    @Test
    void refusesToReplaceARegularFileAtTheSocketPath(@TempDir Path directory) throws Exception {
        var repository = new SqliteStrictSessionRepository(directory.resolve("strict-mode.sqlite"));
        service = new StrictModeService(SECRET, repository, new AbsentChromeController());
        var socket = directory.resolve("run").resolve("strict-mode.sock");
        Files.createDirectories(socket.getParent());
        Files.writeString(socket, "preserve-me");
        server = new UnixSocketServer(
                socket, new ProtocolCodec(), service, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(java.io.IOException.class, server::start);
        assertEquals("preserve-me", Files.readString(socket));
    }

    @Test
    void refusesToReplaceASymlinkAtTheSocketPath(@TempDir Path directory) throws Exception {
        var repository = new SqliteStrictSessionRepository(directory.resolve("strict-mode.sqlite"));
        service = new StrictModeService(SECRET, repository, new AbsentChromeController());
        var socket = directory.resolve("run").resolve("strict-mode.sock");
        Files.createDirectories(socket.getParent());
        var target = directory.resolve("keep-target");
        Files.writeString(target, "preserve-me");
        Files.createSymbolicLink(socket, target);
        server = new UnixSocketServer(
                socket, new ProtocolCodec(), service, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(java.io.IOException.class, server::start);
        assertTrue(Files.isSymbolicLink(socket));
        assertEquals("preserve-me", Files.readString(target));
    }

    @Test
    void rejectsAnUnterminatedEofFrameWithoutDatabaseWrite(@TempDir Path directory)
            throws Exception {
        var repository = new SqliteStrictSessionRepository(directory.resolve("strict-mode.sqlite"));
        service = new StrictModeService(SECRET, repository, new AbsentChromeController());
        var codec = new ProtocolCodec();
        var socket = directory.resolve("run").resolve("strict-mode.sock");
        server = new UnixSocketServer(socket, codec, service, Clock.fixed(NOW, ZoneOffset.UTC));
        server.start();
        var start = codec.encode(new com.localfocuscoach.strict.protocol.ProtocolMessage(
                1,
                SECRET,
                "dashboard.start",
                java.util.Map.of(
                        "mode", "TIMED",
                        "endsAt", NOW.plusSeconds(300).toString(),
                        "earlyExitChallenge", false)));

        assertNull(unterminatedRequest(socket, start));
        assertTrue(repository.loadActive().isEmpty());
    }

    @Test
    void closeDoesNotDeleteAReplacementAtTheSocketPath(@TempDir Path directory) throws Exception {
        var repository = new SqliteStrictSessionRepository(directory.resolve("strict-mode.sqlite"));
        service = new StrictModeService(SECRET, repository, new AbsentChromeController());
        var socket = directory.resolve("run").resolve("strict-mode.sock");
        server = new UnixSocketServer(
                socket, new ProtocolCodec(), service, Clock.fixed(NOW, ZoneOffset.UTC));
        server.start();
        Files.delete(socket);
        Files.writeString(socket, "replacement");

        server.close();

        assertEquals("replacement", Files.readString(socket));
    }

    @Test
    void replacesOnlyAVerifiedStaleSocket(@TempDir Path directory) throws Exception {
        var repository = new SqliteStrictSessionRepository(directory.resolve("strict-mode.sqlite"));
        service = new StrictModeService(SECRET, repository, new AbsentChromeController());
        var codec = new ProtocolCodec();
        var socket = directory.resolve("run").resolve("strict-mode.sock");
        Files.createDirectories(socket.getParent());
        try (var stale = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            stale.bind(UnixDomainSocketAddress.of(socket));
        }
        assertTrue(Files.exists(socket));
        server = new UnixSocketServer(socket, codec, service, Clock.fixed(NOW, ZoneOffset.UTC));

        server.start();

        assertEquals(
                "service.status",
                codec.decode(request(
                                socket,
                                codec.encode(new com.localfocuscoach.strict.protocol.ProtocolMessage(
                                        1, SECRET, "dashboard.status", java.util.Map.of()))))
                        .type());
    }

    @Test
    void awaitTerminationReturnsAfterUnexpectedAcceptLoopShutdown(@TempDir Path directory)
            throws Exception {
        var repository = new SqliteStrictSessionRepository(directory.resolve("strict-mode.sqlite"));
        service = new StrictModeService(SECRET, repository, new AbsentChromeController());
        var socket = directory.resolve("run").resolve("strict-mode.sock");
        server = new UnixSocketServer(
                socket, new ProtocolCodec(), service, Clock.fixed(NOW, ZoneOffset.UTC));
        server.start();
        var acceptThreadField = UnixSocketServer.class.getDeclaredField("acceptThread");
        acceptThreadField.setAccessible(true);
        var acceptThread = (Thread) acceptThreadField.get(server);

        acceptThread.interrupt();

        assertTimeoutPreemptively(Duration.ofSeconds(2), server::awaitTermination);
        assertFalse(Files.exists(socket));
    }

    private String request(Path socket, String frame) throws Exception {
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            var writer = new BufferedWriter(
                    Channels.newWriter(channel, StandardCharsets.UTF_8));
            var reader = new BufferedReader(
                    Channels.newReader(channel, StandardCharsets.UTF_8));
            writer.write(frame);
            writer.newLine();
            writer.flush();
            return reader.readLine();
        }
    }

    private String unterminatedRequest(Path socket, String frame) throws Exception {
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            var writer = new BufferedWriter(
                    Channels.newWriter(channel, StandardCharsets.UTF_8));
            var reader = new BufferedReader(
                    Channels.newReader(channel, StandardCharsets.UTF_8));
            writer.write(frame);
            writer.flush();
            channel.shutdownOutput();
            return reader.readLine();
        }
    }

    private static final class AbsentChromeController implements ChromeController {
        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public QuitResult requestGracefulQuit() {
            return QuitResult.FAILED;
        }
    }
}
