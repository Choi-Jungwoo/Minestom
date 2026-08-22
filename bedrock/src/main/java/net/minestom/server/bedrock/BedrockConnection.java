package net.minestom.server.bedrock;

import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minestom.server.ServerProcess;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionAndRotationPacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.MultiBlockChangePacket;
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket;
import net.minestom.server.network.packet.server.play.UnloadChunkPacket;
import net.minestom.server.network.packet.server.play.UpdateViewPositionPacket;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.utils.position.PositionUtils;
import net.minestom.server.world.DimensionType;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkChunkPublisherUpdatePacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A native Bedrock transport connection attached to an ordinary Minestom player.
 *
 * <p>Cloudburst is deliberately kept behind package-private implementation seams.
 */
public final class BedrockConnection extends PlayerConnection {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Component UNSUPPORTED_WORLD =
            Component.text("Unsupported Bedrock world data");
    private static final Component UNSUPPORTED_MOVEMENT =
            Component.text("Unsupported Bedrock movement capability");
    private static final Set<PlayerAuthInputData> UNSUPPORTED_MOVEMENT_INPUTS = EnumSet.of(
            PlayerAuthInputData.ASCEND,
            PlayerAuthInputData.DESCEND,
            PlayerAuthInputData.START_GLIDING,
            PlayerAuthInputData.STOP_GLIDING,
            PlayerAuthInputData.START_SWIMMING,
            PlayerAuthInputData.STOP_SWIMMING,
            PlayerAuthInputData.START_CRAWLING,
            PlayerAuthInputData.STOP_CRAWLING,
            PlayerAuthInputData.START_SPIN_ATTACK,
            PlayerAuthInputData.STOP_SPIN_ATTACK,
            PlayerAuthInputData.START_FLYING,
            PlayerAuthInputData.STOP_FLYING,
            PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE);

    private final BedrockServerSession session;
    private final InetSocketAddress remoteAddress;
    private final InetSocketAddress serverAddress;
    private final BedrockMappings mappings;
    private final ServerProcess process;
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private volatile long lastClientTick = -1;
    private volatile @Nullable UUID instanceId;
    private volatile int dimensionId;

    BedrockConnection(
            BedrockServerSession session,
            InetSocketAddress remoteAddress,
            InetSocketAddress serverAddress,
            BedrockMappings mappings,
            ServerProcess process) {
        this.session = Objects.requireNonNull(session, "session");
        this.remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress");
        this.serverAddress = Objects.requireNonNull(serverAddress, "serverAddress");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.process = Objects.requireNonNull(process, "process");
    }

    static UUID offlineUuid(String name) {
        Objects.requireNonNull(name, "name");
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void sendPacket(SendablePacket packet) {
        if (disconnected.get()) return;
        try {
            if (packet instanceof BlockChangePacket change) {
                sendBlockUpdate(
                        change.blockPosition().blockX(),
                        change.blockPosition().blockY(),
                        change.blockPosition().blockZ(),
                        change.blockStateId());
            } else if (packet instanceof MultiBlockChangePacket changes) {
                sendBlockUpdates(changes);
            } else if (packet instanceof UnloadChunkPacket unload) {
                sendEmptyChunk(unload.chunkX(), unload.chunkZ());
            } else if (packet instanceof UpdateViewPositionPacket view) {
                sendChunkPublisherUpdate(view);
            } else if (packet instanceof PlayerPositionAndLookPacket position) {
                sendPosition(position);
            }
        } catch (RuntimeException exception) {
            failWorld(exception);
        }
    }

    @Override
    public void sendChunk(Chunk chunk) {
        if (disconnected.get()) return;
        try {
            switchWorld(chunk.getInstance());
            sendBedrockPacket(BedrockWorldCodec.encodeChunk(
                    session.getPeer().getChannel().alloc(),
                    chunk,
                    mappings,
                    dimensionId));
        } catch (RuntimeException exception) {
            failWorld(exception);
        }
    }

    @Override
    public boolean requiresChunkBatchAcknowledgement() {
        return false;
    }

    void sendBedrockPacket(BedrockPacket packet) {
        if (disconnected.get()) {
            releasePayload(packet);
            return;
        }
        final ByteBuf validationBuffer = session.getPeer().getChannel().alloc().buffer();
        boolean submitted = false;
        try {
            session.getCodec().tryEncode(
                    session.getCodec().createHelper(), validationBuffer, packet);
            session.sendPacket(packet);
            submitted = true;
        } finally {
            validationBuffer.release();
            if (!submitted) releasePayload(packet);
        }
    }

    void initializeWorld(Instance instance) {
        instanceId = instance.getUuid();
        dimensionId = BedrockStartGame.dimensionId(instance, process);
    }

    void handle(PlayerAuthInputPacket packet) {
        final Player player = getPlayer();
        if (player == null || player.getInstance() == null || disconnected.get()) return;
        if (packet.getPlayMode() != ClientPlayMode.NORMAL
                || packet.getPredictedVehicle() != 0
                || packet.getInputData().stream().anyMatch(UNSUPPORTED_MOVEMENT_INPUTS::contains)) {
            kick(UNSUPPORTED_MOVEMENT);
            return;
        }
        final long tick = packet.getTick();
        if (tick <= lastClientTick) return;
        lastClientTick = tick;

        final Vector3f position = packet.getPosition();
        final Vector3f rotation = packet.getRotation();
        final Pos feetPosition = new Pos(
                position.getX(),
                position.getY() - BedrockStartGame.PLAYER_EYE_HEIGHT,
                position.getZ(),
                rotation.getY(),
                rotation.getX());
        player.addPacketToQueue(new ClientPlayerPositionAndRotationPacket(
                feetPosition,
                packet.getInputData().contains(PlayerAuthInputData.VERTICAL_COLLISION),
                packet.getInputData().contains(PlayerAuthInputData.HORIZONTAL_COLLISION)));
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

    private void sendChunkPublisherUpdate(UpdateViewPositionPacket view) {
        final Player player = getPlayer();
        if (player == null || player.getInstance() == null) return;
        switchWorld(player.getInstance());
        final NetworkChunkPublisherUpdatePacket update = new NetworkChunkPublisherUpdatePacket();
        update.setPosition(Vector3i.from(
                view.chunkX() * Chunk.CHUNK_SIZE_X + Chunk.CHUNK_SIZE_X / 2,
                player.getPosition().blockY(),
                view.chunkZ() * Chunk.CHUNK_SIZE_Z + Chunk.CHUNK_SIZE_Z / 2));
        update.setRadius(player.getInstance().viewDistance() * Chunk.CHUNK_SIZE_X);
        sendBedrockPacket(update);
    }

    private void sendBlockUpdates(MultiBlockChangePacket changes) {
        final long sectionPosition = changes.chunkSectionPosition();
        final int chunkX = (int) (sectionPosition >> 42);
        final int chunkZ = (int) (sectionPosition << 22 >> 42);
        final int sectionY = (int) (sectionPosition << 44 >> 44);
        for (long encoded : changes.blocks()) {
            final int index = (int) (encoded & 0xFFF);
            sendBlockUpdate(
                    chunkX * Chunk.CHUNK_SIZE_X + CoordConversion.sectionBlockIndexGetX(index),
                    sectionY * Chunk.CHUNK_SECTION_SIZE + CoordConversion.sectionBlockIndexGetY(index),
                    chunkZ * Chunk.CHUNK_SIZE_Z + CoordConversion.sectionBlockIndexGetZ(index),
                    (int) (encoded >>> 12));
        }
    }

    private void sendBlockUpdate(int x, int y, int z, int blockStateId) {
        final UpdateBlockPacket update = new UpdateBlockPacket();
        update.setBlockPosition(Vector3i.from(x, y, z));
        update.setDefinition(mappings.blockDefinition(blockStateId));
        update.setDataLayer(0);
        update.getFlags().addAll(UpdateBlockPacket.FLAG_ALL);
        sendBedrockPacket(update);
    }

    private void sendEmptyChunk(int chunkX, int chunkZ) {
        final Player player = getPlayer();
        final Instance instance = player == null ? null : player.getInstance();
        if (instance == null) return;
        final DimensionType dimensionType = Objects.requireNonNull(
                process.dimensionType().get(instance.getDimensionType()),
                "Instance dimension type is not registered");
        sendBedrockPacket(BedrockWorldCodec.encodeEmptyChunk(
                session.getPeer().getChannel().alloc(),
                chunkX,
                chunkZ,
                dimensionType.height() / Chunk.CHUNK_SECTION_SIZE,
                mappings.defaultBiomeId(),
                dimensionId));
    }

    private void sendPosition(PlayerPositionAndLookPacket packet) {
        final Player player = getPlayer();
        if (player == null) return;
        final Pos position = PositionUtils.getPositionWithRelativeFlags(
                player.getPosition(),
                new Pos(
                        packet.position().x(),
                        packet.position().y(),
                        packet.position().z(),
                        packet.yaw(),
                        packet.pitch()),
                packet.flags());
        final MovePlayerPacket move = new MovePlayerPacket();
        move.setRuntimeEntityId(player.getEntityId());
        move.setPosition(Vector3f.from(
                position.x(),
                position.y() + BedrockStartGame.PLAYER_EYE_HEIGHT,
                position.z()));
        move.setRotation(Vector3f.from(position.pitch(), position.yaw(), position.yaw()));
        move.setMode(MovePlayerPacket.Mode.TELEPORT);
        move.setOnGround(player.isOnGround());
        move.setRidingRuntimeEntityId(0);
        move.setTeleportationCause(MovePlayerPacket.TeleportationCause.UNKNOWN);
        move.setEntityType(0);
        move.setTick(Math.max(0, lastClientTick));
        sendBedrockPacket(move);
        player.refreshReceivedTeleportId(packet.teleportId());
    }

    private void switchWorld(Instance instance) {
        final UUID targetInstanceId = instance.getUuid();
        if (targetInstanceId.equals(instanceId)) return;
        final int targetDimension = BedrockStartGame.dimensionId(instance, process);
        if (targetDimension == dimensionId) {
            sendDimension(targetDimension == 0 ? 1 : 0);
        }
        sendDimension(targetDimension);
        instanceId = targetInstanceId;
        dimensionId = targetDimension;
    }

    private void sendDimension(int targetDimension) {
        final Player player = getPlayer();
        if (player == null) return;
        final Pos position = player.getPosition();
        final ChangeDimensionPacket change = new ChangeDimensionPacket();
        change.setDimension(targetDimension);
        change.setPosition(Vector3f.from(
                position.x(),
                position.y() + BedrockStartGame.PLAYER_EYE_HEIGHT,
                position.z()));
        change.setRespawn(true);
        change.setLoadingScreenId(null);
        sendBedrockPacket(change);

        final PlayerActionPacket complete = new PlayerActionPacket();
        complete.setRuntimeEntityId(player.getEntityId());
        complete.setAction(PlayerActionType.DIMENSION_CHANGE_SUCCESS);
        complete.setBlockPosition(Vector3i.ZERO);
        complete.setResultPosition(Vector3i.ZERO);
        complete.setFace(0);
        sendBedrockPacket(complete);
    }

    private static void releasePayload(BedrockPacket packet) {
        if (packet instanceof LevelChunkPacket chunk && chunk.getData().refCnt() > 0) {
            chunk.getData().release();
        }
    }

    private void failWorld(RuntimeException exception) {
        process.exception().handleException(exception);
        kick(UNSUPPORTED_WORLD);
    }

    private void disconnectOnce(Runnable notifyPeer) {
        if (!disconnected.compareAndSet(false, true)) return;
        notifyPeer.run();
        super.disconnect();
    }
}
