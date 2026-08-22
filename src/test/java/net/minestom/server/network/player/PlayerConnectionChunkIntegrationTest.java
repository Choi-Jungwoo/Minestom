package net.minestom.server.network.player;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PlayerConnectionChunkIntegrationTest {

    @Test
    void defaultChunkDeliveryUsesExistingJavaPacket(Env env) {
        final Chunk chunk = env.createEmptyInstance().loadChunk(0, 0).join();
        final RecordingConnection connection = new RecordingConnection();

        connection.sendChunk(chunk);

        assertSame(chunk.getFullDataPacket(), connection.packet);
    }

    @Test
    void playerChunkQueueUsesStructuredConnectionSeam(Env env) {
        final var instance = env.createEmptyInstance();
        final Chunk chunk = instance.loadChunk(0, 0).join();
        final StructuredRecordingConnection connection = new StructuredRecordingConnection();
        final Player player = new Player(connection, new GameProfile(UUID.randomUUID(), "Player"));
        player.setInstance(instance, Pos.ZERO).join();

        player.sendChunk(chunk);
        for (int tick = 0; tick < 100; tick++) player.update(tick);

        assertTrue(connection.chunks.contains(chunk));
    }

    private static class RecordingConnection extends PlayerConnection {
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

    private static final class StructuredRecordingConnection extends RecordingConnection {
        private final List<Chunk> chunks = new ArrayList<>();

        @Override
        public void sendChunk(Chunk chunk) {
            chunks.add(chunk);
        }
    }
}
