package net.minestom.server.bedrock;

import io.netty.channel.epoll.Epoll;
import io.netty.channel.kqueue.KQueue;
import net.minestom.server.MinecraftServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BedrockTransportTest {
    @Test
    void selectsAnAvailableNativeTransportOrFallsBackToNio() {
        final String expected;
        if (Epoll.isAvailable()) {
            expected = "epoll";
        } else if (KQueue.isAvailable()) {
            expected = "kqueue";
        } else {
            expected = "nio";
        }

        assertEquals(expected, BedrockTransport.select().name());
    }

    @Test
    void everyAvailableTransportBindsARealRakNetListener() {
        final Set<String> expected = new HashSet<>(Set.of("nio"));
        if (Epoll.isAvailable()) expected.add("epoll");
        if (KQueue.isAvailable()) expected.add("kqueue");
        assertEquals(
                expected,
                BedrockTransport.available().stream()
                        .map(BedrockTransport::name)
                        .collect(Collectors.toSet()));

        for (BedrockTransport transport : BedrockTransport.available()) {
            final var process = MinecraftServer.updateProcess();
            final var server = BedrockServer.createForTesting(
                    process,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    transport);
            try {
                server.start();
                assertTrue(server.boundAddress().getPort() > 0, transport.name());
            } finally {
                server.stop();
                process.stop();
            }
        }
    }
}
