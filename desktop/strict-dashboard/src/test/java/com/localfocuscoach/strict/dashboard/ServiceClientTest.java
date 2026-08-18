package com.localfocuscoach.strict.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceClientTest {
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
}
