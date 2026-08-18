package com.localfocuscoach.strict.service;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localfocuscoach.strict.protocol.ProtocolCodec;
import com.localfocuscoach.strict.store.SqliteStrictSessionRepository;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.time.Clock;
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
