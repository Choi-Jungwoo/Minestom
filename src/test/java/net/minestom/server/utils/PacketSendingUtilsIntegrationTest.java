package net.minestom.server.utils;

import net.minestom.server.ServerFlag;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.network.player.PlayerSocketConnection;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.UUID;

import static net.kyori.adventure.text.Component.text;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

@EnvTest
public class PacketSendingUtilsIntegrationTest {

    @Test
    void groupedDeliveryKeepsStructuredPacketForNonSocketConnection(Env env) {
        final RecordingConnection connection = new RecordingConnection();
        final Player player = new Player(connection, profile());
        final SystemChatPacket packet = new SystemChatPacket(text("structured"), false);

        PacketSendingUtils.sendGroupedPacket(List.of(player), packet);

        assertSame(packet, connection.packet);
    }

    @Test
    void groupedDeliveryRetainsSocketCache(Env env) throws IOException {
        try (SocketChannel channel = SocketChannel.open()) {
            final RecordingSocketConnection connection = new RecordingSocketConnection(channel);
            final Player player = new Player(connection, profile());
            final SystemChatPacket packet = new SystemChatPacket(text("cached"), false);

            PacketSendingUtils.sendGroupedPacket(List.of(player), packet);

            if (ServerFlag.GROUPED_PACKET) {
                assertInstanceOf(CachedPacket.class, connection.packet);
            } else {
                assertSame(packet, connection.packet);
            }
        }
    }

    private static GameProfile profile() {
        return new GameProfile(UUID.randomUUID(), "Player");
    }

    private static final class RecordingConnection extends PlayerConnection {
        private SendablePacket packet;

        @Override
        public void sendPacket(SendablePacket packet) {
            this.packet = packet;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }
    }

    private static final class RecordingSocketConnection extends PlayerSocketConnection {
        private SendablePacket packet;

        private RecordingSocketConnection(SocketChannel channel) {
            super(channel, new InetSocketAddress("localhost", 25565),
                    Thread.currentThread(), Thread.currentThread());
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            this.packet = packet;
        }
    }
}
