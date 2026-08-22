package net.minestom.server.bedrock;

import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.player.PlayerConnection;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A native Bedrock transport connection attached to an ordinary Minestom player.
 *
 * <p>Cloudburst is deliberately kept behind package-private implementation seams.
 */
public final class BedrockConnection extends PlayerConnection {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final BedrockServerSession session;
    private final InetSocketAddress remoteAddress;
    private final InetSocketAddress serverAddress;
    private final AtomicBoolean disconnected = new AtomicBoolean();

    BedrockConnection(
            BedrockServerSession session,
            InetSocketAddress remoteAddress,
            InetSocketAddress serverAddress) {
        this.session = Objects.requireNonNull(session, "session");
        this.remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress");
        this.serverAddress = Objects.requireNonNull(serverAddress, "serverAddress");
    }

    static UUID offlineUuid(String name) {
        Objects.requireNonNull(name, "name");
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void sendPacket(SendablePacket packet) {
        // Java protocol packets are never decoded back into domain data. Bedrock translations
        // are added explicitly at structured seams as their feature slices are implemented.
    }

    void sendBedrockPacket(BedrockPacket packet) {
        if (disconnected.get()) return;
        final ByteBuf validationBuffer = session.getPeer().getChannel().alloc().buffer();
        try {
            session.getCodec().tryEncode(
                    session.getCodec().createHelper(), validationBuffer, packet);
        } finally {
            validationBuffer.release();
        }
        session.sendPacket(packet);
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public int getProtocolVersion() {
        return session.getCodec().getProtocolVersion();
    }

    @Override
    public String getServerAddress() {
        return serverAddress.getHostString();
    }

    @Override
    public int getServerPort() {
        return serverAddress.getPort();
    }

    @Override
    public void kick(Component component) {
        disconnectOnce(() -> {
            final DisconnectPacket packet = new DisconnectPacket();
            packet.setKickMessage(LEGACY.serialize(component));
            session.sendPacketImmediately(packet);
            session.disconnect(packet.getKickMessage());
        });
    }

    @Override
    public void disconnect() {
        disconnectOnce(() -> session.disconnect("Disconnected"));
    }

    void peerDisconnected() {
        disconnectOnce(() -> {
        });
    }

    private void disconnectOnce(Runnable notifyPeer) {
        if (!disconnected.compareAndSet(false, true)) return;
        notifyPeer.run();
        super.disconnect();
    }
}
