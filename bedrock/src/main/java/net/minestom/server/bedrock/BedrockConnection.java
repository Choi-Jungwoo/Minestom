package net.minestom.server.bedrock;

import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minestom.server.ServerProcess;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.client.play.ClientChatMessagePacket;
import net.minestom.server.network.packet.client.play.ClientCommandChatPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionAndRotationPacket;
import net.minestom.server.network.packet.client.play.ClientTeleportConfirmPacket;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket;
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import net.minestom.server.network.packet.server.play.EntityPositionAndRotationPacket;
import net.minestom.server.network.packet.server.play.EntityPositionPacket;
import net.minestom.server.network.packet.server.play.MultiBlockChangePacket;
import net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket;
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import net.minestom.server.network.packet.server.play.UnloadChunkPacket;
import net.minestom.server.network.packet.server.play.UpdateViewPositionPacket;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.utils.position.PositionUtils;
import net.minestom.server.world.DimensionType;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.data.BuildPlatform;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission;
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.skin.SerializedSkin;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobArmorEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkChunkPublisherUpdatePacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A native Bedrock transport connection attached to an ordinary Minestom player.
 *
 * <p>Cloudburst is deliberately kept behind package-private implementation seams.
 */
public final class BedrockConnection extends PlayerConnection {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Component UNSUPPORTED_INSTANCE_DATA =
            Component.text("Unsupported Bedrock instance data");
    private static final Component UNSUPPORTED_MOVEMENT =
            Component.text("Unsupported Bedrock movement capability");
    private static final float TELEPORT_CONFIRM_TOLERANCE = 0.1f;
    private static final int TELEPORT_RESEND_INPUTS = 20;
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
    private final BedrockSkin displaySkin;
    private final Map<Integer, UUID> visiblePlayerUuids = new ConcurrentHashMap<>();
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private final AtomicInteger pendingDimensionChanges = new AtomicInteger();
    private final AtomicReference<PendingTeleport> pendingTeleport = new AtomicReference<>();
    private volatile long lastClientTick = -1;
    private volatile @Nullable UUID instanceId;
    private volatile int dimensionId;

    BedrockConnection(
            BedrockServerSession session,
            InetSocketAddress remoteAddress,
            InetSocketAddress serverAddress,
            BedrockMappings mappings,
            ServerProcess process,
            BedrockSkin displaySkin) {
        this.session = Objects.requireNonNull(session, "session");
        this.remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress");
        this.serverAddress = Objects.requireNonNull(serverAddress, "serverAddress");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.process = Objects.requireNonNull(process, "process");
        this.displaySkin = Objects.requireNonNull(displaySkin, "displaySkin");
    }

    static UUID offlineUuid(String name) {
        Objects.requireNonNull(name, "name");
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void sendPacket(SendablePacket packet) {
        if (disconnected.get()) return;
        if (packet instanceof CachedPacket cached) {
            sendPacket(cached.packet(ConnectionState.PLAY));
            return;
        }
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
            } else if (packet instanceof SystemChatPacket chat) {
                sendSystemChat(chat);
            } else if (packet instanceof PlayerInfoUpdatePacket playerInfo) {
                sendPlayerInfo(playerInfo);
            } else if (packet instanceof PlayerInfoRemovePacket playerInfo) {
                sendPlayerInfoRemoval(playerInfo);
            } else if (packet instanceof SpawnEntityPacket spawn
                    && spawn.type() == EntityType.PLAYER) {
                sendPlayerSpawn(spawn);
            } else if (packet instanceof EntityPositionPacket position) {
                sendPlayerPosition(position.entityId());
            } else if (packet instanceof EntityPositionAndRotationPacket position) {
                sendPlayerPosition(position.entityId());
            } else if (packet instanceof EntityMetaDataPacket metadata) {
                sendPlayerMetadata(metadata.entityId());
            } else if (packet instanceof DestroyEntitiesPacket removals) {
                sendPlayerRemovals(removals);
            } else if (packet instanceof EntityEquipmentPacket equipment) {
                sendPlayerEquipment(equipment);
            }
        } catch (RuntimeException exception) {
            failInstanceData(exception);
        }
    }

    @Override
    public void sendChunk(Chunk chunk) {
        if (disconnected.get()) return;
        try {
            switchInstance(chunk.getInstance());
            sendBedrockPacket(BedrockChunkCodec.encodeChunk(
                    session.getPeer().getChannel().alloc(),
                    chunk,
                    mappings,
                    dimensionId));
        } catch (RuntimeException exception) {
            failInstanceData(exception);
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

    void initializeInstance(Instance instance) {
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
        if (handlePendingTeleport(player, packet)) return;

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

    void handle(TextPacket packet) {
        if (packet.getType() != TextPacket.Type.CHAT) return;
        final Player player = getPlayer();
        final String message = packet.getMessage();
        if (player == null || message == null) return;
        if (message.length() > 256) {
            session.disconnect("Bedrock chat message is too long");
            return;
        }
        if (message.startsWith("/")) {
            player.addPacketToQueue(new ClientCommandChatPacket(message.substring(1)));
        } else {
            player.addPacketToQueue(new ClientChatMessagePacket(
                    message,
                    System.currentTimeMillis(),
                    0,
                    null,
                    0,
                    new BitSet(20),
                    (byte) 0));
        }
    }

    void handleDimensionChangeSuccess() {
        final int previous = pendingDimensionChanges.getAndUpdate(
                current -> Math.max(0, current - 1));
        if (previous != 1) return;
        final PlayStatusPacket status = new PlayStatusPacket();
        status.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
        sendBedrockPacket(status);
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
        switchInstance(player.getInstance());
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
        sendBedrockPacket(BedrockChunkCodec.encodeEmptyChunk(
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
        if (packet.teleportId() >= 0) {
            pendingTeleport.set(new PendingTeleport(
                    packet.teleportId(),
                    move,
                    0));
        }
        sendBedrockPacket(move);
    }

    @SuppressWarnings("deprecation")
    private void sendPlayerInfo(PlayerInfoUpdatePacket packet) {
        if (!packet.actions().contains(PlayerInfoUpdatePacket.Action.ADD_PLAYER)) return;
        final PlayerListPacket list = new PlayerListPacket();
        list.setAction(PlayerListPacket.Action.ADD);
        for (PlayerInfoUpdatePacket.Entry player : packet.entries()) {
            final Player visiblePlayer = findPlayer(player.uuid());
            final PlayerListPacket.Entry entry = new PlayerListPacket.Entry(player.uuid());
            entry.setAction(PlayerListPacket.Action.ADD);
            entry.setEntityId(visiblePlayer == null ? 0 : visiblePlayer.getEntityId());
            entry.setName(player.username());
            entry.setXuid("");
            entry.setPlatformChatId("");
            entry.setBuildPlatform(BuildPlatform.UNKNOWN);
            entry.setSkin(displaySkin(visiblePlayer, player.uuid()));
            entry.setColor(new Color(0, true));
            list.getEntries().add(entry);
        }
        if (!list.getEntries().isEmpty()) sendBedrockPacket(list);
    }

    private void sendSystemChat(SystemChatPacket packet) {
        final TextPacket text = new TextPacket();
        text.setType(packet.overlay() ? TextPacket.Type.TIP : TextPacket.Type.SYSTEM);
        text.setSourceName("");
        text.setMessage(LEGACY.serialize(packet.message()));
        text.setNeedsTranslation(false);
        text.setXuid("");
        text.setPlatformChatId("");
        text.setFilteredMessage("");
        sendBedrockPacket(text);
    }

    @SuppressWarnings("deprecation")
    private void sendPlayerInfoRemoval(PlayerInfoRemovePacket packet) {
        final PlayerListPacket list = new PlayerListPacket();
        list.setAction(PlayerListPacket.Action.REMOVE);
        for (UUID uuid : packet.uuids()) {
            final PlayerListPacket.Entry entry = new PlayerListPacket.Entry(uuid);
            entry.setAction(PlayerListPacket.Action.REMOVE);
            list.getEntries().add(entry);
        }
        if (!list.getEntries().isEmpty()) sendBedrockPacket(list);
    }

    private void sendPlayerSpawn(SpawnEntityPacket packet) {
        final Player player = findPlayer(packet.uuid());
        if (player == null) return;
        visiblePlayerUuids.put(packet.entityId(), packet.uuid());
        final Pos position = packet.position();
        final AddPlayerPacket add = new AddPlayerPacket();
        add.setUuid(packet.uuid());
        add.setUsername(player.getUsername());
        add.setUniqueEntityId(packet.entityId());
        add.setRuntimeEntityId(packet.entityId());
        add.setPlatformChatId("");
        add.setPosition(Vector3f.from(
                position.x(),
                position.y() + BedrockStartGame.PLAYER_EYE_HEIGHT,
                position.z()));
        add.setMotion(Vector3f.from(
                packet.velocity().x(),
                packet.velocity().y(),
                packet.velocity().z()));
        add.setRotation(Vector3f.from(
                position.pitch(),
                position.yaw(),
                packet.headRot()));
        add.setHand(ItemData.AIR);
        add.setGameType(GameType.SURVIVAL);
        add.setMetadata(playerMetadata(player));
        add.setPlayerPermission(PlayerPermission.MEMBER);
        add.setCommandPermission(CommandPermission.ANY);
        add.setDeviceId("");
        add.setBuildPlatform(BuildPlatform.UNKNOWN);
        sendBedrockPacket(add);
    }

    private void sendPlayerRemovals(DestroyEntitiesPacket packet) {
        for (int entityId : packet.entityIds()) {
            if (visiblePlayerUuids.remove(entityId) == null) continue;
            final RemoveEntityPacket removal = new RemoveEntityPacket();
            removal.setUniqueEntityId(entityId);
            sendBedrockPacket(removal);
        }
    }

    private void sendPlayerEquipment(EntityEquipmentPacket packet) {
        final Player player = findPlayer(packet.entityId());
        if (player == null) return;
        for (Map.Entry<EquipmentSlot, ItemStack> equipment : packet.equipments().entrySet()) {
            if (equipment.getKey().isHand()) {
                sendHandEquipment(packet.entityId(), equipment.getKey(), equipment.getValue());
            }
        }
        if (packet.equipments().keySet().stream().anyMatch(EquipmentSlot::isArmor)) {
            sendArmorEquipment(player);
        }
    }

    private void sendHandEquipment(int entityId, EquipmentSlot slot, ItemStack stack) {
        final MobEquipmentPacket equipment = new MobEquipmentPacket();
        equipment.setRuntimeEntityId(entityId);
        equipment.setItem(itemData(stack));
        equipment.setInventorySlot(0);
        equipment.setHotbarSlot(0);
        equipment.setContainerId(
                slot == EquipmentSlot.OFF_HAND ? ContainerId.OFFHAND : ContainerId.INVENTORY);
        sendBedrockPacket(equipment);
    }

    private void sendArmorEquipment(Player player) {
        final MobArmorEquipmentPacket equipment = new MobArmorEquipmentPacket();
        equipment.setRuntimeEntityId(player.getEntityId());
        equipment.setHelmet(itemData(player.getEquipment(EquipmentSlot.HELMET)));
        equipment.setChestplate(itemData(player.getEquipment(EquipmentSlot.CHESTPLATE)));
        equipment.setLeggings(itemData(player.getEquipment(EquipmentSlot.LEGGINGS)));
        equipment.setBoots(itemData(player.getEquipment(EquipmentSlot.BOOTS)));
        equipment.setBody(itemData(player.getEquipment(EquipmentSlot.BODY)));
        sendBedrockPacket(equipment);
    }

    private ItemData itemData(ItemStack stack) {
        if (stack.isAir()) return ItemData.AIR;
        return ItemData.builder()
                .definition(mappings.itemDefinition(stack.material().key().asString()))
                .count(stack.amount())
                .build();
    }

    private void sendPlayerPosition(int entityId) {
        final Player player = findPlayer(entityId);
        if (player == null) return;
        final Pos position = player.getPosition();
        final MovePlayerPacket move = new MovePlayerPacket();
        move.setRuntimeEntityId(entityId);
        move.setPosition(Vector3f.from(
                position.x(),
                position.y() + BedrockStartGame.PLAYER_EYE_HEIGHT,
                position.z()));
        move.setRotation(Vector3f.from(
                position.pitch(),
                position.yaw(),
                position.yaw()));
        move.setMode(MovePlayerPacket.Mode.NORMAL);
        move.setOnGround(player.isOnGround());
        move.setRidingRuntimeEntityId(0);
        move.setTick(Math.max(0, lastClientTick));
        sendBedrockPacket(move);
    }

    private void sendPlayerMetadata(int entityId) {
        final Player player = findPlayer(entityId);
        if (player == null) return;
        final SetEntityDataPacket data = new SetEntityDataPacket();
        data.setRuntimeEntityId(entityId);
        data.setMetadata(playerMetadata(player));
        data.setTick(Math.max(0, lastClientTick));
        sendBedrockPacket(data);
    }

    private static EntityDataMap playerMetadata(Player player) {
        final EntityDataMap metadata = new EntityDataMap();
        final Component customName = player.get(DataComponents.CUSTOM_NAME);
        metadata.putType(
                EntityDataTypes.NAME,
                customName == null ? player.getUsername() : LEGACY.serialize(customName));
        metadata.putType(
                EntityDataTypes.NAMETAG_ALWAYS_SHOW,
                player.isCustomNameVisible() ? (byte) 1 : (byte) 0);
        metadata.setFlag(EntityFlag.ON_FIRE, player.isOnFire());
        metadata.setFlag(EntityFlag.SNEAKING, player.isSneaking());
        metadata.setFlag(EntityFlag.SPRINTING, player.isSprinting());
        metadata.setFlag(EntityFlag.INVISIBLE, player.isInvisible());
        metadata.setFlag(EntityFlag.CAN_SHOW_NAME, true);
        metadata.setFlag(EntityFlag.ALWAYS_SHOW_NAME, player.isCustomNameVisible());
        metadata.setFlag(EntityFlag.HAS_GRAVITY, !player.hasNoGravity());
        return metadata;
    }

    private @Nullable Player findPlayer(UUID uuid) {
        return process.connection().getOnlinePlayers().stream()
                .filter(player -> player.getUuid().equals(uuid))
                .findFirst()
                .orElse(null);
    }

    private @Nullable Player findPlayer(int entityId) {
        final Player player = getPlayer();
        final Instance instance = player == null ? null : player.getInstance();
        if (instance == null) return null;
        return instance.getEntityById(entityId) instanceof Player found ? found : null;
    }

    private static SerializedSkin displaySkin(@Nullable Player player, UUID uuid) {
        if (player != null && player.getPlayerConnection() instanceof BedrockConnection connection) {
            return connection.displaySkin.serialized();
        }
        return BedrockSkin.generated(uuid).serialized();
    }

    private void switchInstance(Instance instance) {
        final UUID targetInstanceId = instance.getUuid();
        if (targetInstanceId.equals(instanceId)) return;
        final int targetDimension = BedrockStartGame.dimensionId(instance, process);
        if (targetDimension == dimensionId) {
            pendingDimensionChanges.set(2);
            sendDimension(targetDimension == 0 ? 1 : 0);
        } else {
            pendingDimensionChanges.set(1);
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

    private boolean handlePendingTeleport(Player player, PlayerAuthInputPacket packet) {
        final PendingTeleport pending = pendingTeleport.get();
        if (pending == null) return false;
        if (pending.canConfirm(packet.getPosition())) {
            if (pendingTeleport.compareAndSet(pending, null)) {
                player.addPacketToQueue(new ClientTeleportConfirmPacket(pending.teleportId()));
            }
            return true;
        }

        final int unconfirmedInputs = pending.unconfirmedInputs() + 1;
        final boolean shouldResend = unconfirmedInputs >= TELEPORT_RESEND_INPUTS;
        final PendingTeleport updated = new PendingTeleport(
                pending.teleportId(),
                pending.packet(),
                shouldResend ? 0 : unconfirmedInputs);
        if (pendingTeleport.compareAndSet(pending, updated) && shouldResend) {
            sendBedrockPacket(pending.packet().clone());
        }
        return true;
    }

    private record PendingTeleport(
            int teleportId,
            MovePlayerPacket packet,
            int unconfirmedInputs
    ) {
        private boolean canConfirm(Vector3f actual) {
            final Vector3f position = packet.getPosition();
            return Math.abs(position.getX() - actual.getX()) < TELEPORT_CONFIRM_TOLERANCE
                    && Math.abs(position.getY() - actual.getY()) < TELEPORT_CONFIRM_TOLERANCE
                    && Math.abs(position.getZ() - actual.getZ()) < TELEPORT_CONFIRM_TOLERANCE;
        }
    }

    private static void releasePayload(BedrockPacket packet) {
        if (packet instanceof LevelChunkPacket chunk && chunk.getData().refCnt() > 0) {
            chunk.getData().release();
        }
    }

    private void failInstanceData(RuntimeException exception) {
        process.exception().handleException(exception);
        kick(UNSUPPORTED_INSTANCE_DATA);
    }

    private void disconnectOnce(Runnable notifyPeer) {
        if (!disconnected.compareAndSet(false, true)) return;
        notifyPeer.run();
        super.disconnect();
    }
}
