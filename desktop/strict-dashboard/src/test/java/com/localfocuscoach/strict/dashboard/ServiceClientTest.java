package com.localfocuscoach.strict.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localfocuscoach.strict.protocol.ProtocolCodec;
import com.localfocuscoach.strict.protocol.ProtocolMessage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceClientTest {
    @Test
    void focusSettingsGetWrapperUsesTheExactAuthenticatedRequest() throws Exception {
        var received = new AtomicReference<ProtocolMessage>();
        var completed = new CountDownLatch(1);
        var client = new ServiceClient("secret", request -> {
            received.set(request);
            return new ProtocolMessage(1, "secret", "service.focusSettings", Map.of());
        });

        FxTestSupport.call(() -> {
            client.getFocusSettingsAsync((response, failure) -> completed.countDown());
            return null;
        });

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals("dashboard.focusSettings.get", received.get().type());
        assertEquals(Map.of(), received.get().payload());
        client.close();
    }

    @Test
    void focusSettingsSaveWrapperNestsTheCompleteSettingsDocument() throws Exception {
        var received = new AtomicReference<ProtocolMessage>();
        var completed = new CountDownLatch(1);
        var client = new ServiceClient("secret", request -> {
            received.set(request);
            return new ProtocolMessage(1, "secret", "service.focusSettings", Map.of());
        });
        var settings = Map.<String, Object>of("enabled", true, "rules", Map.of());

        FxTestSupport.call(() -> {
            client.saveFocusSettingsAsync(settings, (response, failure) -> completed.countDown());
            return null;
        });

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals("dashboard.focusSettings.save", received.get().type());
        assertEquals(Map.of("settings", settings), received.get().payload());
        client.close();
    }

    @Test
    void asynchronousRequestKeepsJavaFxResponsiveAndCompletesOnItsThread()
            throws Exception {
        var exchangeStarted = new CountDownLatch(1);
        var releaseExchange = new CountDownLatch(1);
        var fxRemainedResponsive = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var callbackOnFxThread = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        var client = new ServiceClient("secret", request -> {
            exchangeStarted.countDown();
            try {
                releaseExchange.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new ProtocolMessage(1, "secret", "service.status", Map.of("active", false));
        });

        FxTestSupport.call(() -> {
            client.requestAsync("dashboard.status", Map.of(), (response, exception) -> {
                callbackOnFxThread.set(Platform.isFxApplicationThread());
                failure.set(exception);
                completed.countDown();
            });
            Platform.runLater(fxRemainedResponsive::countDown);
            return null;
        });

        assertTrue(exchangeStarted.await(1, TimeUnit.SECONDS));
        assertTrue(fxRemainedResponsive.await(1, TimeUnit.SECONDS));
        releaseExchange.countDown();
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertTrue(callbackOnFxThread.get());
        assertNull(failure.get());
        client.close();
    }

    @Test
    void publicRequestRejectsAFrameWithoutThisClientsAuthentication() {
        var exchanged = new AtomicBoolean();
        var client = new ServiceClient("secret", request -> {
            exchanged.set(true);
            return new ProtocolMessage(1, "secret", "service.status", Map.of("active", false));
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> client.request(
                        new ProtocolMessage(1, "wrong", "dashboard.status", Map.of())));
        assertFalse(exchanged.get());
    }

    @Test
    void requestExchangesOneNewlineDelimitedProtocolMessage(@TempDir Path directory)
            throws Exception {
        var socket = directory.resolve("dashboard.sock");
        var codec = new ProtocolCodec();
        var received = new ProtocolMessage[1];
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.bind(UnixDomainSocketAddress.of(socket));
            var exchange = executor.submit(() -> {
                try (var channel = server.accept();
                        var reader = new BufferedReader(new java.io.InputStreamReader(
                                Channels.newInputStream(channel), StandardCharsets.UTF_8));
                        var writer = new BufferedWriter(new java.io.OutputStreamWriter(
                                Channels.newOutputStream(channel), StandardCharsets.UTF_8))) {
                    received[0] = codec.decode(reader.readLine());
                    writer.write(codec.encode(new ProtocolMessage(
                            1, "secret", "service.status", Map.of("active", false))));
                    writer.newLine();
                    writer.flush();
                }
                return null;
            });

            var request = new ProtocolMessage(1, "secret", "dashboard.status", Map.of());
            var response = new ServiceClient(socket, "secret").request(request);
            exchange.get();

            assertEquals(request, received[0]);
            assertEquals("service.status", response.type());
            assertEquals(false, response.payload().get("active"));
        }
    }

    @Test
    void stalledServiceReadIsBounded(@TempDir Path directory) throws Exception {
        var socket = directory.resolve("stalled.sock");
        var releaseServer = new CountDownLatch(1);
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.bind(UnixDomainSocketAddress.of(socket));
            var accepted = executor.submit(() -> {
                try (var ignored = server.accept()) {
                    releaseServer.await(2, TimeUnit.SECONDS);
                }
                return null;
            });
            var client = new ServiceClient(
                    socket, "secret", Duration.ofMillis(200), Duration.ofMillis(100));
            var request = new ProtocolMessage(1, "secret", "dashboard.status", Map.of());

            try {
                assertTimeoutPreemptively(
                        Duration.ofSeconds(1),
                        () -> assertThrows(IllegalStateException.class, () -> client.request(request)));
            } finally {
                releaseServer.countDown();
                accepted.get();
            }
        }
    }
}
