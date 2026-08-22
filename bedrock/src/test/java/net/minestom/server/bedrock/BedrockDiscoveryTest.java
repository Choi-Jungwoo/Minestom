package net.minestom.server.bedrock;

import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerProcess;
import net.minestom.server.event.server.ServerListPingEvent;
import net.minestom.server.ping.ServerListPingType;
import net.minestom.server.ping.Status;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BedrockDiscoveryTest {
    private static final byte[] RAKNET_MAGIC = {
            0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78
    };

    private ServerProcess process;
    private BedrockServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
        if (process != null) process.stop();
    }

    @Test
    void udpDiscoveryUsesStatusAndBedrockPingEvent() throws Exception {
        process = MinecraftServer.updateProcess();
        var eventCalled = new CountDownLatch(1);
        process.eventHandler().addListener(ServerListPingEvent.class, event -> {
            assertEquals(ServerListPingType.BEDROCK, event.getPingType());
            event.setStatus(Status.builder()
                    .description(Component.text("Bedrock loopback"))
                    .playerInfo(3, 20)
                    .build());
            eventCalled.countDown();
        });
        server = BedrockServer.createForTesting(
                process, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.start();

        BedrockPong pong = discover(server.boundAddress());

        assertTrue(eventCalled.await(2, TimeUnit.SECONDS));
        assertEquals("MCPE", pong.edition());
        assertEquals("Bedrock loopback", pong.motd());
        assertEquals(1001, pong.protocolVersion());
        assertEquals("1.26.30", pong.version());
        assertEquals(3, pong.playerCount());
        assertEquals(20, pong.maximumPlayerCount());
        assertEquals(server.boundAddress().getPort(), pong.ipv4Port());
    }

    @Test
    void shutdownInterruptsInFlightPingEvents() throws Exception {
        process = MinecraftServer.updateProcess();
        var eventStarted = new CountDownLatch(1);
        var eventInterrupted = new CountDownLatch(1);
        var eventFinished = new CountDownLatch(1);
        var releaseEvent = new CountDownLatch(1);
        process.eventHandler().addListener(ServerListPingEvent.class, event -> {
            if (event.getPingType() != ServerListPingType.BEDROCK) return;
            eventStarted.countDown();
            try {
                releaseEvent.await();
            } catch (InterruptedException _) {
                eventInterrupted.countDown();
                Thread.currentThread().interrupt();
            } finally {
                eventFinished.countDown();
            }
        });
        server = BedrockServer.createForTesting(
                process, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.start();

        try {
            sendDiscovery(server.boundAddress());
            assertTrue(eventStarted.await(2, TimeUnit.SECONDS));

            server.stop();

            assertTrue(eventInterrupted.await(2, TimeUnit.SECONDS));
            assertTrue(eventFinished.await(2, TimeUnit.SECONDS));
        } finally {
            releaseEvent.countDown();
        }
    }

    @Test
    void malformedUdpFloodDoesNotPreventSubsequentDiscovery() throws Exception {
        process = MinecraftServer.updateProcess();
        server = BedrockServer.createForTesting(
                process, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.start();

        try (var socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            for (int index = 0; index < 256; index++) {
                final byte[] malformed = new byte[index % 2 == 0 ? 64 : 2_048];
                malformed[0] = (byte) index;
                socket.send(new DatagramPacket(
                        malformed, malformed.length, server.boundAddress()));
            }
        }

        assertEquals(1001, discover(server.boundAddress()).protocolVersion());
    }

    private static BedrockPong discover(InetSocketAddress address) throws Exception {
        try (var socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            socket.setSoTimeout(2_000);
            socket.send(discoveryPacket(address));

            byte[] responseBytes = new byte[2_048];
            var response = new DatagramPacket(responseBytes, responseBytes.length);
            socket.receive(response);
            ByteBuffer buffer = ByteBuffer.wrap(response.getData(), 0, response.getLength());
            assertEquals(0x1c, Byte.toUnsignedInt(buffer.get()));
            buffer.getLong();
            buffer.getLong();
            buffer.position(buffer.position() + RAKNET_MAGIC.length);
            int payloadLength = Short.toUnsignedInt(buffer.getShort());
            byte[] payload = new byte[payloadLength];
            buffer.get(payload);
            return BedrockPong.fromRakNet(Unpooled.wrappedBuffer(payload));
        }
    }

    private static void sendDiscovery(InetSocketAddress address) throws Exception {
        try (var socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            socket.send(discoveryPacket(address));
        }
    }

    private static DatagramPacket discoveryPacket(InetSocketAddress address) {
        ByteBuffer request = ByteBuffer.allocate(33);
        request.put((byte) 0x01);
        request.putLong(System.currentTimeMillis());
        request.put(RAKNET_MAGIC);
        request.putLong(1L);
        return new DatagramPacket(request.array(), request.position(), address);
    }
}
