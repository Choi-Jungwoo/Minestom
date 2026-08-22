package net.minestom.server.bedrock;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.concurrent.DefaultThreadFactory;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerProcess;
import net.minestom.server.command.builder.Command;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.event.player.PlayerChatEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.PlayerAdmission;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.packet.TransferPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;
import org.jose4j.json.JsonUtil;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwx.HeaderParameterNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BedrockLoginHandshakeTest {
    private static final byte[] CLASSIC_SKIN = classicSkin(64, 64);
    private static final Object LEAK_DETECTOR_LOCK = new Object();

    private ServerProcess process;
    private BedrockServer server;

    @BeforeEach
    void startServer() {
        process = MinecraftServer.updateProcess();
        process.dispatcher().start();
        server = BedrockServer.createForTesting(
                process, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
        if (process != null) process.stop();
    }

    @Test
    void validOfflineLoginCompletesEncryptedEmptyResourcePackHandshake() throws Exception {
        try (var client = new LoginClient(
                server.boundAddress(), "LoopbackPlayer", AuthType.SELF_SIGNED, Credentials.VALID)) {
            client.begin();

            assertTrue(client.completed.await(3, TimeUnit.SECONDS), () -> client.stage);
            assertEquals(PlayStatusPacket.Status.LOGIN_SUCCESS, client.playStatus);
            assertTrue(client.resourcePacksInfoEmpty);
            assertTrue(client.resourcePackStackEmpty);
            assertTrue(client.startGameReceived, () -> client.stage);

            Player player = awaitPlayer();
            assertInstanceOf(BedrockConnection.class, player.getPlayerConnection());
            assertEquals(
                    UUID.fromString("0c7651f2-577a-3b92-8ff9-3faa54136489"),
                    player.getUuid());
            assertSame(server.spawningInstance(), player.getInstance());
        }
    }

    @Test
    void validGuestLoginCompletesTheSameHandshake() throws Exception {
        try (var client =
                     new LoginClient(server.boundAddress(), "GuestPlayer", AuthType.GUEST, Credentials.VALID)) {
            client.begin();

            assertTrue(client.completed.await(3, TimeUnit.SECONDS), () -> client.stage);
            assertEquals(PlayStatusPacket.Status.LOGIN_SUCCESS, client.playStatus);
        }
    }

    @Test
    void preLoginEventReplacesTheFinalGameProfile() throws Exception {
        UUID replacementUuid = UUID.fromString("11111111-2222-3333-8444-555555555555");
        process.eventHandler().addListener(AsyncPlayerPreLoginEvent.class, event ->
                event.setGameProfile(new GameProfile(replacementUuid, "Replacement")));
        try (var client = new LoginClient(
                server.boundAddress(), "Original", AuthType.SELF_SIGNED, Credentials.VALID)) {
            client.begin();

            assertTrue(client.completed.await(3, TimeUnit.SECONDS), () -> client.stage);
            Player player = awaitPlayer();
            assertEquals(replacementUuid, player.getUuid());
            assertEquals("Replacement", player.getUsername());
        }
    }

    @Test
    void rejectsASecondLiveConnectionWithTheSameOfflineIdentity() throws Exception {
        try (var first = new LoginClient(
                server.boundAddress(), "Duplicate", AuthType.SELF_SIGNED, Credentials.VALID);
             var second = new LoginClient(
                     server.boundAddress(), "Duplicate", AuthType.SELF_SIGNED, Credentials.VALID)) {
            first.begin();
            assertTrue(first.completed.await(3, TimeUnit.SECONDS), () -> first.stage);

            second.begin();
            assertTrue(second.completed.await(3, TimeUnit.SECONDS), () -> second.stage);
            assertEquals("disconnected: §cError during login!", second.stage);
        }
    }

    @Test
    void rejectsExpiredIdentityCertificateWithoutLeakingTheCause() throws Exception {
        assertLoginRejected(Credentials.EXPIRED_IDENTITY);
    }

    @Test
    void rejectsClientDataSignedByAnotherKeyWithoutLeakingTheCause() throws Exception {
        assertLoginRejected(Credentials.INVALID_CLIENT_SIGNATURE);
    }

    @Test
    void rejectsClientDataThatDoesNotMatchTheIdentityWithoutLeakingTheCause() throws Exception {
        assertLoginRejected(Credentials.MISMATCHED_CLIENT_DATA);
    }

    @Test
    void rejectsPersonaSkin() throws Exception {
        assertLoginRejected(Credentials.PERSONA_SKIN);
    }

    @Test
    void rejectsOversizedSkin() throws Exception {
        assertLoginRejected(Credentials.OVERSIZED_SKIN);
    }

    @Test
    void loopbackPlayerUsesAuthoritativeInstanceChunksAndMovement() throws Exception {
        var first = server.spawningInstance();
        first.viewDistance(1);
        first.loadChunk(0, 0).join();
        first.setBlock(0, 0, 0, Block.STONE);
        process.eventHandler().addListener(PlayerMoveEvent.class, event -> {
            if (event.getNewPosition().x() == 18) event.setCancelled(true);
        });

        try (var client = new LoginClient(
                server.boundAddress(), "WorldPlayer", AuthType.SELF_SIGNED, Credentials.VALID)) {
            client.begin();
            assertTrue(client.completed.await(3, TimeUnit.SECONDS), () -> client.stage);
            Player player = awaitPlayer();

            assertTrue(tickUntil(() -> client.chunks.stream()
                    .anyMatch(chunk -> chunk.x() == 0 && chunk.z() == 0
                            && chunk.subChunks() > 0)));
            client.move(player.getPosition(), 1, PlayerAuthInputData.HANDLE_TELEPORT);
            assertTrue(tickUntil(() ->
                    player.getLastSentTeleportId() == player.getLastReceivedTeleportId()));

            client.move(new Pos(17, 0, 0), 2);
            assertTrue(tickUntil(() -> player.getPosition().x() == 17));
            assertTrue(tickUntil(() -> client.chunks.stream()
                    .anyMatch(chunk -> chunk.subChunks() == 0)));
            client.moves.clear();

            first.setBlock(17, 0, 0, Block.DIRT);
            UpdateBlockPacket update = client.blockUpdates.poll(3, TimeUnit.SECONDS);
            assertNotNull(update);
            assertEquals(Block.DIRT.stateId(), update.getDefinition().getRuntimeId());

            client.move(new Pos(18, 0, 0), 3);
            assertTrue(tickUntil(() -> !client.moves.isEmpty()));
            MovePlayerPacket correction = awaitMove(client);
            assertEquals(MovePlayerPacket.Mode.TELEPORT, correction.getMode());
            assertEquals(17, correction.getPosition().getX());
            assertEquals(17, player.getPosition().x());
            client.moves.clear();

            player.teleport(new Pos(20, 0, 0)).join();
            MovePlayerPacket teleport = awaitMove(client);
            assertEquals(20, teleport.getPosition().getX());
            client.move(new Pos(19, 0, 0), 4);
            tick();
            assertEquals(20, player.getPosition().x());
            assertNotEquals(player.getLastSentTeleportId(), player.getLastReceivedTeleportId());
            client.move(new Pos(20, 0, 0), 5, PlayerAuthInputData.HANDLE_TELEPORT);
            assertTrue(tickUntil(() ->
                    player.getLastSentTeleportId() == player.getLastReceivedTeleportId()));
            client.move(new Pos(21, 0, 0), 6);
            assertTrue(tickUntil(() -> player.getPosition().x() == 21));

            var second = process.instance().createInstanceContainer();
            second.viewDistance(1);
            second.loadChunk(0, 0).join();
            second.setBlock(0, 0, 0, Block.GOLD_BLOCK);
            client.chunks.clear();
            client.dimensionChanges.clear();
            player.setInstance(second, new Pos(0, 0, 0)).join();
            assertTrue(tickUntil(() -> player.getInstance() == second));
            assertTrue(
                    tickUntil(() -> client.dimensionChanges.size() >= 2),
                    () -> "dimension changes: " + client.dimensionChanges.size());
            assertTrue(tickUntil(() ->
                    client.playStatus == PlayStatusPacket.Status.PLAYER_SPAWN));
            assertTrue(
                    tickUntil(() -> client.chunks.stream().anyMatch(chunk ->
                            chunk.x() == 0 && chunk.z() == 0 && chunk.subChunks() > 0)),
                    () -> "stage: " + client.stage + ", chunks: " + client.chunks);

            client.move(new Pos(0, 0, 0), 7, PlayerAuthInputData.START_FLYING);
            assertTrue(tickUntil(() -> client.stage.contains(
                    "Unsupported Bedrock movement capability")));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void bedrockPlayersSeeOneAnother() throws Exception {
        try (var observer = new LoginClient(
                server.boundAddress(), "Observer", AuthType.SELF_SIGNED, Credentials.VALID);
             var visible = new LoginClient(
                     server.boundAddress(), "Visible", AuthType.SELF_SIGNED, Credentials.VALID)) {
            observer.begin();
            assertTrue(observer.completed.await(3, TimeUnit.SECONDS), () -> observer.stage);
            awaitPlayer("Observer");

            visible.begin();
            assertTrue(visible.completed.await(3, TimeUnit.SECONDS), () -> visible.stage);
            Player visiblePlayer = awaitPlayer("Visible");

            assertTrue(tickUntil(() -> observer.playerLists.stream()
                    .filter(packet -> packet.getAction() == PlayerListPacket.Action.ADD)
                    .flatMap(packet -> packet.getEntries().stream())
                    .anyMatch(entry -> entry.getUuid().equals(visiblePlayer.getUuid()))));
            assertTrue(tickUntil(() -> observer.addedPlayers.stream()
                    .anyMatch(packet ->
                            packet.getRuntimeEntityId() == visiblePlayer.getEntityId()
                                    && packet.getUuid().equals(visiblePlayer.getUuid()))));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void bedrockClassicSkinPropagatesToObservers() throws Exception {
        try (var observer = new LoginClient(
                server.boundAddress(), "Observer", AuthType.SELF_SIGNED, Credentials.VALID);
             var visible = new LoginClient(
                     server.boundAddress(), "Visible", AuthType.SELF_SIGNED, Credentials.VALID)) {
            observer.begin();
            assertTrue(observer.completed.await(3, TimeUnit.SECONDS), () -> observer.stage);
            awaitPlayer("Observer");

            visible.begin();
            assertTrue(visible.completed.await(3, TimeUnit.SECONDS), () -> visible.stage);
            Player visiblePlayer = awaitPlayer("Visible");

            assertTrue(tickUntil(() -> observer.playerLists.stream()
                    .filter(packet -> packet.getAction() == PlayerListPacket.Action.ADD)
                    .flatMap(packet -> packet.getEntries().stream())
                    .filter(entry -> entry.getUuid().equals(visiblePlayer.getUuid()))
                    .map(PlayerListPacket.Entry::getSkin)
                    .anyMatch(skin -> skin.getSkinId().equals("test-classic")
                            && skin.getSkinData().getWidth() == 64
                            && skin.getSkinData().getHeight() == 64
                            && Arrays.equals(
                                    skin.getSkinData().getImage(), CLASSIC_SKIN))));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void javaPlayerUsesGeneratedWideSkinWithRecordedSource() throws Exception {
        try (var observer = new LoginClient(
                server.boundAddress(), "Observer", AuthType.SELF_SIGNED, Credentials.VALID)) {
            observer.begin();
            assertTrue(observer.completed.await(3, TimeUnit.SECONDS), () -> observer.stage);
            awaitPlayer("Observer");

            final UUID javaUuid = UUID.randomUUID();
            final var admission = process.connection().admitPlayer(
                    new RecordingJavaConnection(),
                    new GameProfile(javaUuid, "JavaPlayer"),
                    new PlayerAdmission() {
                        @Override
                        public CompletableFuture<Void> accept(GameProfile gameProfile) {
                            return CompletableFuture.completedFuture(null);
                        }

                        @Override
                        public CompletableFuture<Void> prepare(Player player) {
                            player.setPendingOptions(server.spawningInstance(), false);
                            return CompletableFuture.completedFuture(null);
                        }
                    });
            assertTrue(tickUntil(admission::isDone));
            final Player javaPlayer = admission.join();

            assertTrue(tickUntil(() -> observer.playerLists.stream()
                    .filter(packet -> packet.getAction() == PlayerListPacket.Action.ADD)
                    .flatMap(packet -> packet.getEntries().stream())
                    .filter(entry -> entry.getUuid().equals(javaUuid))
                    .map(PlayerListPacket.Entry::getSkin)
                    .anyMatch(skin -> skin.getSkinId().startsWith("minestom-generated:")
                            && skin.getSkinData().getWidth() == 64
                            && skin.getSkinData().getHeight() == 64
                            && skin.getArmSize().equals("wide")
                            && hasMultipleColors(skin.getSkinData().getImage()))));
            javaPlayer.remove();
        }
    }

    @Test
    void bedrockObserverSeesPlayerMovement() throws Exception {
        try (var observer = new LoginClient(
                server.boundAddress(), "Observer", AuthType.SELF_SIGNED, Credentials.VALID);
             var mover = new LoginClient(
                     server.boundAddress(), "Mover", AuthType.SELF_SIGNED, Credentials.VALID)) {
            observer.begin();
            assertTrue(observer.completed.await(3, TimeUnit.SECONDS), () -> observer.stage);
            awaitPlayer("Observer");

            mover.begin();
            assertTrue(mover.completed.await(3, TimeUnit.SECONDS), () -> mover.stage);
            Player moverPlayer = awaitPlayer("Mover");
            mover.move(moverPlayer.getPosition(), 1, PlayerAuthInputData.HANDLE_TELEPORT);
            assertTrue(tickUntil(() ->
                    moverPlayer.getLastSentTeleportId() == moverPlayer.getLastReceivedTeleportId()));
            observer.moves.clear();

            mover.move(new Pos(5, 0, 0), 2);

            assertTrue(tickUntil(() -> observer.moves.stream()
                    .anyMatch(packet -> packet.getRuntimeEntityId() == moverPlayer.getEntityId()
                            && packet.getPosition().getX() == 5)));
        }
    }

    @Test
    void bedrockObserverSeesBasicPlayerMetadata() throws Exception {
        try (var observer = new LoginClient(
                server.boundAddress(), "Observer", AuthType.SELF_SIGNED, Credentials.VALID);
             var visible = new LoginClient(
                     server.boundAddress(), "Visible", AuthType.SELF_SIGNED, Credentials.VALID)) {
            observer.begin();
            assertTrue(observer.completed.await(3, TimeUnit.SECONDS), () -> observer.stage);
            awaitPlayer("Observer");

            visible.begin();
            assertTrue(visible.completed.await(3, TimeUnit.SECONDS), () -> visible.stage);
            Player visiblePlayer = awaitPlayer("Visible");
            observer.entityData.clear();

            visiblePlayer.set(DataComponents.CUSTOM_NAME, Component.text("Display Name"));
            visiblePlayer.setCustomNameVisible(true);

            assertTrue(tickUntil(() -> observer.entityData.stream()
                    .filter(packet -> packet.getRuntimeEntityId() == visiblePlayer.getEntityId())
                    .map(packet -> packet.getMetadata().get(EntityDataTypes.NAME))
                    .anyMatch(name -> name != null && name.toString().equals("Display Name"))));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void bedrockObserverSeesPlayerRemoval() throws Exception {
        try (var observer = new LoginClient(
                server.boundAddress(), "Observer", AuthType.SELF_SIGNED, Credentials.VALID);
             var visible = new LoginClient(
                     server.boundAddress(), "Visible", AuthType.SELF_SIGNED, Credentials.VALID)) {
            observer.begin();
            assertTrue(observer.completed.await(3, TimeUnit.SECONDS), () -> observer.stage);
            awaitPlayer("Observer");

            visible.begin();
            assertTrue(visible.completed.await(3, TimeUnit.SECONDS), () -> visible.stage);
            Player visiblePlayer = awaitPlayer("Visible");
            assertTrue(tickUntil(() -> observer.addedPlayers.stream()
                    .anyMatch(packet -> packet.getRuntimeEntityId() == visiblePlayer.getEntityId())));
            observer.removedEntities.clear();
            observer.playerLists.clear();

            visiblePlayer.remove();

            assertTrue(tickUntil(() -> observer.removedEntities.stream()
                    .anyMatch(packet -> packet.getUniqueEntityId() == visiblePlayer.getEntityId())));
            assertTrue(tickUntil(() -> observer.playerLists.stream()
                    .filter(packet -> packet.getAction() == PlayerListPacket.Action.REMOVE)
                    .flatMap(packet -> packet.getEntries().stream())
                    .anyMatch(entry -> entry.getUuid().equals(visiblePlayer.getUuid()))));
        }
    }

    @Test
    void bedrockObserverSeesPlayerEquipment() throws Exception {
        try (var observer = new LoginClient(
                server.boundAddress(), "Observer", AuthType.SELF_SIGNED, Credentials.VALID);
             var visible = new LoginClient(
                     server.boundAddress(), "Visible", AuthType.SELF_SIGNED, Credentials.VALID)) {
            observer.begin();
            assertTrue(observer.completed.await(3, TimeUnit.SECONDS), () -> observer.stage);
            awaitPlayer("Observer");

            visible.begin();
            assertTrue(visible.completed.await(3, TimeUnit.SECONDS), () -> visible.stage);
            Player visiblePlayer = awaitPlayer("Visible");
            assertTrue(tickUntil(() -> observer.addedPlayers.stream()
                    .anyMatch(packet -> packet.getRuntimeEntityId() == visiblePlayer.getEntityId())));
            observer.equipment.clear();

            visiblePlayer.setEquipment(
                    EquipmentSlot.MAIN_HAND,
                    ItemStack.of(Material.STONE));

            assertTrue(tickUntil(() -> observer.equipment.stream()
                    .anyMatch(packet -> packet.getRuntimeEntityId() == visiblePlayer.getEntityId()
                            && packet.getItem() != null
                            && packet.getItem().getDefinition() != null
                            && packet.getItem().getDefinition().getIdentifier()
                            .equals("minecraft:stone"))), () -> observer.equipment.toString());
        }
    }

    @Test
    void bedrockObserverDoesNotReceiveNonPlayerEntities() throws Exception {
        try (var observer = new LoginClient(
                server.boundAddress(), "Observer", AuthType.SELF_SIGNED, Credentials.VALID)) {
            observer.begin();
            assertTrue(observer.completed.await(3, TimeUnit.SECONDS), () -> observer.stage);
            awaitPlayer("Observer");
            observer.addedEntities.clear();
            observer.addedPlayers.clear();

            final Entity zombie = new Entity(EntityType.ZOMBIE);
            zombie.setInstance(server.spawningInstance(), Pos.ZERO).join();
            for (int index = 0; index < 20; index++) {
                tick();
                Thread.sleep(10);
            }

            assertTrue(observer.addedEntities.stream()
                    .noneMatch(packet -> packet.getRuntimeEntityId() == zombie.getEntityId()));
            assertTrue(observer.addedPlayers.stream()
                    .noneMatch(packet -> packet.getRuntimeEntityId() == zombie.getEntityId()));
            zombie.remove();
        }
    }

    @Test
    void bedrockChatAndCommandsUseExistingMinestomSystems() throws Exception {
        final AtomicReference<PlayerChatEvent> receivedEvent = new AtomicReference<>();
        final AtomicReference<Player> commandPlayer = new AtomicReference<>();
        process.eventHandler().addListener(PlayerChatEvent.class, event -> {
            receivedEvent.set(event);
            event.setFormattedMessage(Component.text("event:" + event.getRawMessage()));
        });
        final Command command = new Command("bedrocktest");
        command.setDefaultExecutor((sender, _) -> {
            commandPlayer.set((Player) sender);
            sender.sendMessage(Component.text("command-ok"));
        });
        process.command().register(command);
        try (var observer = new LoginClient(
                server.boundAddress(), "Observer", AuthType.SELF_SIGNED, Credentials.VALID);
             var client = new LoginClient(
                     server.boundAddress(), "Chatter", AuthType.SELF_SIGNED, Credentials.VALID)) {
            observer.begin();
            assertTrue(observer.completed.await(3, TimeUnit.SECONDS), () -> observer.stage);
            awaitPlayer("Observer");

            client.begin();
            assertTrue(client.completed.await(3, TimeUnit.SECONDS), () -> client.stage);
            final Player player = awaitPlayer("Chatter");
            client.texts.clear();
            observer.texts.clear();

            client.sendText("hello");

            assertTrue(tickUntil(() -> receivedEvent.get() != null));
            assertSame(player, receivedEvent.get().getPlayer());
            assertEquals("hello", receivedEvent.get().getRawMessage());
            assertTrue(tickUntil(() -> observer.texts.stream()
                    .anyMatch(packet -> packet.getMessage().equals("event:hello"))));
            client.texts.clear();

            client.sendCommand("/bedrocktest");

            assertTrue(tickUntil(() -> commandPlayer.get() != null));
            assertSame(player, commandPlayer.get());
            assertTrue(tickUntil(() -> client.texts.stream()
                    .anyMatch(packet -> packet.getMessage().equals("command-ok"))));
        }
    }

    @Test
    void bedrockKeepAliveUpdatesTheAuthoritativePlayerLatency() throws Exception {
        try (var client = new LoginClient(
                server.boundAddress(), "Latency", AuthType.SELF_SIGNED, Credentials.VALID)) {
            client.begin();
            assertTrue(client.completed.await(3, TimeUnit.SECONDS), () -> client.stage);
            final Player player = awaitPlayer();

            tick();

            final NetworkStackLatencyPacket latency = client.latencies.poll(3, TimeUnit.SECONDS);
            assertNotNull(latency);
            assertEquals(player.getLastKeepAlive(), latency.getTimestamp());
            assertTrue(tickUntil(player::didAnswerKeepAlive));
            assertTrue(player.getLatency() >= 0);
        }
    }

    @Test
    void unansweredKeepAliveUsesCoreTimeoutCleanup() throws Exception {
        try (var client = new LoginClient(
                server.boundAddress(), "TimedOut", AuthType.SELF_SIGNED, Credentials.VALID)) {
            client.begin();
            assertTrue(client.completed.await(3, TimeUnit.SECONDS), () -> client.stage);
            final Player player = awaitPlayer();

            player.refreshKeepAlive(System.nanoTime() - TimeUnit.SECONDS.toNanos(16));
            tick();

            assertEquals("§cTimeout", client.disconnects.poll(3, TimeUnit.SECONDS));
            assertTrue(tickUntil(() -> process.connection().getOnlinePlayerCount() == 0));
        }
    }

    @Test
    void kickAndRepeatedDisconnectCleanUpOnceWithTheClientReason() throws Exception {
        final AtomicInteger disconnectEvents = new AtomicInteger();
        process.eventHandler().addListener(PlayerDisconnectEvent.class, _ -> disconnectEvents.incrementAndGet());
        try (var client = new LoginClient(
                server.boundAddress(), "Kicked", AuthType.SELF_SIGNED, Credentials.VALID)) {
            client.begin();
            assertTrue(client.completed.await(3, TimeUnit.SECONDS), () -> client.stage);
            final Player player = awaitPlayer();
            final PlayerConnection connection = player.getPlayerConnection();

            player.kick(Component.text("Maintenance"));

            assertEquals("Maintenance", client.disconnects.poll(3, TimeUnit.SECONDS));
            assertTrue(tickUntil(() -> process.connection().getOnlinePlayerCount() == 0));
            tick();
            connection.disconnect();
            connection.disconnect();
            assertEquals(1, disconnectEvents.get());
        }
    }

    @Test
    void normalDisconnectUsesAStableClientReason() throws Exception {
        try (var client = new LoginClient(
                server.boundAddress(), "Disconnected", AuthType.SELF_SIGNED, Credentials.VALID)) {
            client.begin();
            assertTrue(client.completed.await(3, TimeUnit.SECONDS), () -> client.stage);
            final Player player = awaitPlayer();

            player.getPlayerConnection().disconnect();

            assertEquals("Disconnected", client.disconnects.poll(3, TimeUnit.SECONDS));
            assertTrue(tickUntil(() -> process.connection().getOnlinePlayerCount() == 0));
        }
    }

    @Test
    void serverShutdownDisconnectsAndCleansAnAdmittedPlayerOnce() throws Exception {
        final AtomicInteger disconnectEvents = new AtomicInteger();
        process.eventHandler().addListener(PlayerDisconnectEvent.class, _ -> disconnectEvents.incrementAndGet());
        try (var client = new LoginClient(
                server.boundAddress(), "Shutdown", AuthType.SELF_SIGNED, Credentials.VALID)) {
            client.begin();
            assertTrue(client.completed.await(3, TimeUnit.SECONDS), () -> client.stage);
            final Player player = awaitPlayer();
            final PlayerConnection connection = player.getPlayerConnection();

            server.stop();

            assertEquals("Server shutting down", client.disconnects.poll(3, TimeUnit.SECONDS));
            assertTrue(tickUntil(() -> process.connection().getOnlinePlayerCount() == 0));
            tick();
            server.stop();
            connection.disconnect();
            assertEquals(1, disconnectEvents.get());
        }
    }

    @Test
    void transferUsesTheNativeBedrockAddressAndPort() throws Exception {
        try (var client = new LoginClient(
                server.boundAddress(), "Transfer", AuthType.SELF_SIGNED, Credentials.VALID)) {
            client.begin();
            assertTrue(client.completed.await(3, TimeUnit.SECONDS), () -> client.stage);
            final Player player = awaitPlayer();

            player.getPlayerConnection().transfer("target.example", 19133);

            final TransferPacket transfer = client.transfers.poll(3, TimeUnit.SECONDS);
            assertNotNull(transfer);
            assertEquals("target.example", transfer.getAddress());
            assertEquals(19133, transfer.getPort());
        }
    }

    @Test
    void configuredJwtLimitRejectsTheLoginWithAStableReason() throws Exception {
        restartServer(new BedrockServerLimits(
                32,
                20,
                1400,
                1_048_576,
                2_097_152,
                8_388_608,
                256,
                1_024));
        assertLoginRejected(Credentials.VALID);
    }

    @Test
    void configuredTotalConnectionLimitRejectsASecondRakNetSession() throws Exception {
        restartServer(new BedrockServerLimits(
                1,
                20,
                1400,
                1_048_576,
                2_097_152,
                8_388_608,
                256,
                1_048_576));
        try (var first = new LoginClient(
                server.boundAddress(), "Capacity", AuthType.SELF_SIGNED, Credentials.VALID)) {
            assertTrue(first.session.isConnected());
            assertRakNetConnectionRejected(server.boundAddress());
        }
    }

    @Test
    void configuredPerTickLimitRejectsExcessInboundWork() throws Exception {
        restartServer(new BedrockServerLimits(
                32,
                20,
                1400,
                1_048_576,
                2_097_152,
                8_388_608,
                16,
                1_048_576));
        try (var client = new LoginClient(
                server.boundAddress(), "InboundLimit", AuthType.SELF_SIGNED, Credentials.VALID)) {
            client.begin();
            assertTrue(client.completed.await(3, TimeUnit.SECONDS), () -> client.stage);
            awaitPlayer();

            for (int tick = 1; tick <= 17; tick++) {
                client.move(new Pos(0, 42, 0), tick);
            }

            assertEquals(
                    "Bedrock inbound packet limit exceeded",
                    client.disconnects.poll(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void configuredBatchLimitRejectsExpansionBeforePacketHandling() throws Exception {
        restartServer(new BedrockServerLimits(
                32,
                20,
                1400,
                32_768,
                32_768,
                32_768,
                256,
                1_048_576));
        try (var client = new LoginClient(
                server.boundAddress(), "BatchLimit", AuthType.SELF_SIGNED, Credentials.VALID)) {
            client.begin();
            assertTrue(client.completed.await(3, TimeUnit.SECONDS), () -> client.stage);
            awaitPlayer();

            for (int index = 0; index < 500; index++) {
                client.sendText("x".repeat(200));
            }

            assertEquals(
                    "Bedrock packet exceeds configured limits",
                    client.disconnects.poll(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void longSessionChurnRunsWithParanoidLeakDetection() throws Exception {
        synchronized (LEAK_DETECTOR_LOCK) {
            final ResourceLeakDetector.Level previous = ResourceLeakDetector.getLevel();
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
            try {
                for (int index = 0; index < 8; index++) {
                    final LoginClient interrupted = new LoginClient(
                            server.boundAddress(),
                            "HandshakeInterrupted" + index,
                            AuthType.SELF_SIGNED,
                            Credentials.VALID);
                    // Closing before RequestNetworkSettings simulates interruption during handshake.
                    interrupted.close();
                }

                for (int index = 0; index < 8; index++) {
                    final LoginClient interrupted = new LoginClient(
                            server.boundAddress(),
                            "LoginInterrupted" + index,
                            AuthType.SELF_SIGNED,
                            Credentials.VALID);
                    interrupted.sendLogin = false;
                    interrupted.begin();
                    assertTrue(awaitCondition(() ->
                            interrupted.stage.equals("received network settings")));
                    interrupted.close();
                }

                for (int index = 0; index < 16; index++) {
                    try (var admitted = new LoginClient(
                            server.boundAddress(),
                            "Churn" + index,
                            AuthType.SELF_SIGNED,
                            Credentials.VALID)) {
                        admitted.begin();
                        assertTrue(admitted.completed.await(3, TimeUnit.SECONDS), () -> admitted.stage);
                        awaitPlayer();
                        for (int tick = 1; tick <= 32; tick++) {
                            admitted.move(new Pos(0, 42, 0), tick);
                            tick();
                        }
                        assertEquals(1, process.connection().getOnlinePlayerCount());
                    }
                    assertTrue(tickUntil(() -> process.connection().getOnlinePlayerCount() == 0));
                }
            } finally {
                ResourceLeakDetector.setLevel(previous);
            }
        }
    }

    @Test
    void concurrentClientAndServerCloseDoesNotDuplicateCleanup() throws Exception {
        final AtomicInteger disconnectEvents = new AtomicInteger();
        process.eventHandler().addListener(
                PlayerDisconnectEvent.class, _ -> disconnectEvents.incrementAndGet());
        try (var client = new LoginClient(
                server.boundAddress(), "ConcurrentClose", AuthType.SELF_SIGNED, Credentials.VALID)) {
            client.begin();
            assertTrue(client.completed.await(3, TimeUnit.SECONDS), () -> client.stage);
            awaitPlayer();

            final CompletableFuture<Void> clientClose =
                    CompletableFuture.runAsync(client::close);
            final CompletableFuture<Void> serverClose =
                    CompletableFuture.runAsync(server::stop);
            assertDoesNotThrow(() -> CompletableFuture.allOf(clientClose, serverClose).join());

            assertTrue(tickUntil(() -> process.connection().getOnlinePlayerCount() == 0));
            tick();
            assertEquals(1, disconnectEvents.get());
        }
    }

    private void restartServer(BedrockServerLimits limits) {
        server.stop();
        process.stop();
        process = MinecraftServer.updateProcess();
        process.dispatcher().start();
        server = BedrockServer.createForTesting(
                process,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                limits);
        server.start();
    }

    private void assertLoginRejected(Credentials credentials) throws Exception {
        try (var client = new LoginClient(
                server.boundAddress(), "LoopbackPlayer", AuthType.SELF_SIGNED, credentials)) {
            client.begin();

            assertTrue(client.completed.await(3, TimeUnit.SECONDS), () -> client.stage);
            assertEquals("disconnected: Invalid Bedrock login", client.stage);
        }
    }

    private Player awaitPlayer() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (process.connection().getOnlinePlayers().isEmpty()
                && System.nanoTime() < deadline) {
            process.connection().updateWaitingPlayers();
            Thread.sleep(10);
        }
        assertEquals(1, process.connection().getOnlinePlayerCount());
        return process.connection().getOnlinePlayers().iterator().next();
    }

    private Player awaitPlayer(String name) throws InterruptedException {
        assertTrue(tickUntil(() -> process.connection().getOnlinePlayers().stream()
                .anyMatch(player -> player.getUsername().equals(name))));
        return process.connection().getOnlinePlayers().stream()
                .filter(player -> player.getUsername().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private boolean tickUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            tick();
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static boolean awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static void assertRakNetConnectionRejected(InetSocketAddress address) {
        final EventLoopGroup group = new MultiThreadIoEventLoopGroup(
                1,
                new DefaultThreadFactory("bedrock-capacity-client", true),
                NioIoHandler.newFactory());
        ChannelFuture connection = null;
        try {
            connection = new Bootstrap()
                    .group(group)
                    .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                    .option(
                            RakChannelOption.RAK_PROTOCOL_VERSION,
                            Bedrock_v1001.CODEC.getRaknetProtocolVersion())
                    .option(RakChannelOption.RAK_CONNECT_TIMEOUT, 2_000L)
                    .option(RakChannelOption.RAK_MAX_CONNECTION_ATTEMPTS, 2)
                    .option(RakChannelOption.RAK_TIME_BETWEEN_SEND_CONNECTION_ATTEMPTS_MS, 50)
                    .handler(new ChannelInboundHandlerAdapter())
                    .connect(address);
            final ChannelFuture rejected = connection;
            assertThrows(Exception.class, rejected::sync);
        } finally {
            if (connection != null) connection.channel().close().syncUninterruptibly();
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private void tick() {
        process.connection().updateWaitingPlayers();
        process.ticker().tick(System.nanoTime());
    }

    private static byte[] classicSkin(int width, int height) {
        final byte[] pixels = new byte[width * height * 4];
        for (int index = 0; index < pixels.length; index += 4) {
            pixels[index] = (byte) 0x7D;
            pixels[index + 1] = (byte) 0x32;
            pixels[index + 2] = (byte) 0xA8;
            pixels[index + 3] = (byte) 0xFF;
        }
        return pixels;
    }

    private static boolean hasMultipleColors(byte[] pixels) {
        for (int index = 4; index < pixels.length; index += 4) {
            if (pixels[index] != pixels[0]
                    || pixels[index + 1] != pixels[1]
                    || pixels[index + 2] != pixels[2]
                    || pixels[index + 3] != pixels[3]) {
                return true;
            }
        }
        return false;
    }

    private static MovePlayerPacket awaitMove(LoginClient client) throws InterruptedException {
        MovePlayerPacket packet = client.moves.poll(3, TimeUnit.SECONDS);
        assertTrue(packet != null);
        return packet;
    }

    private enum Credentials {
        VALID,
        EXPIRED_IDENTITY,
        INVALID_CLIENT_SIGNATURE,
        MISMATCHED_CLIENT_DATA,
        PERSONA_SKIN,
        OVERSIZED_SKIN
    }

    private static final class RecordingJavaConnection extends PlayerConnection {
        @Override
        public void sendPacket(SendablePacket packet) {
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
        }
    }

    private static final class LoginClient implements AutoCloseable {
        private final CountDownLatch completed = new CountDownLatch(1);
        private final EventLoopGroup eventLoopGroup =
                new MultiThreadIoEventLoopGroup(
                        1, new DefaultThreadFactory("bedrock-login-client", true), NioIoHandler.newFactory());
        private final KeyPair identityKey = EncryptionUtils.createKeyPair();
        private final String identityJwt;
        private final String clientJwt;
        private final String name;

        private final BedrockClientSession session;
        private final Channel channel;
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean sendLogin = true;
        private volatile String stage = "connected";
        private volatile PlayStatusPacket.Status playStatus;
        private volatile boolean resourcePacksInfoEmpty;
        private volatile boolean resourcePackStackEmpty;
        private volatile boolean startGameReceived;
        private final BlockingQueue<ChunkSnapshot> chunks = new LinkedBlockingQueue<>();
        private final BlockingQueue<UpdateBlockPacket> blockUpdates = new LinkedBlockingQueue<>();
        private final BlockingQueue<MovePlayerPacket> moves = new LinkedBlockingQueue<>();
        private final BlockingQueue<ChangeDimensionPacket> dimensionChanges = new LinkedBlockingQueue<>();
        private final BlockingQueue<PlayerListPacket> playerLists = new LinkedBlockingQueue<>();
        private final BlockingQueue<AddEntityPacket> addedEntities = new LinkedBlockingQueue<>();
        private final BlockingQueue<AddPlayerPacket> addedPlayers = new LinkedBlockingQueue<>();
        private final BlockingQueue<SetEntityDataPacket> entityData = new LinkedBlockingQueue<>();
        private final BlockingQueue<RemoveEntityPacket> removedEntities = new LinkedBlockingQueue<>();
        private final BlockingQueue<MobEquipmentPacket> equipment = new LinkedBlockingQueue<>();
        private final BlockingQueue<TextPacket> texts = new LinkedBlockingQueue<>();
        private final BlockingQueue<NetworkStackLatencyPacket> latencies = new LinkedBlockingQueue<>();
        private final BlockingQueue<TransferPacket> transfers = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> disconnects = new LinkedBlockingQueue<>();

        private LoginClient(
                InetSocketAddress address, String name, AuthType authType, Credentials credentials)
                throws Exception {
            this.name = name;
            String publicKey = Base64.getEncoder().encodeToString(identityKey.getPublic().getEncoded());
            JwtClaims identityClaims = new JwtClaims();
            identityClaims.setIssuedAtToNow();
            identityClaims.setExpirationTimeMinutesInTheFuture(
                    credentials == Credentials.EXPIRED_IDENTITY ? -5 : 5);
            identityClaims.setClaim("identityPublicKey", publicKey);
            identityClaims.setClaim("extraData", Map.of(
                    "displayName", name,
                    "identity", UUID.randomUUID().toString(),
                    "XUID", ""));
            identityJwt = sign(identityClaims, identityKey);

            JwtClaims clientClaims = new JwtClaims();
            clientClaims.setClaim(
                    "ThirdPartyName",
                    credentials == Credentials.MISMATCHED_CLIENT_DATA ? "AnotherPlayer" : name);
            clientClaims.setClaim("DeviceOS", 7);
            clientClaims.setClaim("DeviceId", UUID.randomUUID().toString());
            clientClaims.setClaim("GameVersion", "1.26.30");
            final int skinSize = credentials == Credentials.OVERSIZED_SKIN ? 128 : 64;
            clientClaims.setClaim("SkinId", "test-classic");
            clientClaims.setClaim("SkinImageWidth", skinSize);
            clientClaims.setClaim("SkinImageHeight", skinSize);
            clientClaims.setClaim(
                    "SkinData",
                    Base64.getEncoder().encodeToString(
                            skinSize == 64 ? CLASSIC_SKIN : classicSkin(skinSize, skinSize)));
            clientClaims.setClaim("PersonaSkin", credentials == Credentials.PERSONA_SKIN);
            clientClaims.setClaim("ArmSize", "wide");
            clientJwt = sign(
                    clientClaims,
                    credentials == Credentials.INVALID_CLIENT_SIGNATURE
                            ? EncryptionUtils.createKeyPair()
                            : identityKey);

            var connected = new CountDownLatch(1);
            var sessionHolder = new BedrockClientSession[1];
            channel = new Bootstrap()
                    .group(eventLoopGroup)
                    .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                    .option(RakChannelOption.RAK_PROTOCOL_VERSION, Bedrock_v1001.CODEC.getRaknetProtocolVersion())
                    .handler(new BedrockClientInitializer() {
                        @Override
                        protected void initSession(BedrockClientSession session) {
                            session.setCodec(Bedrock_v1001.CODEC);
                            session.getPeer()
                                    .getChannel()
                                    .pipeline()
                                    .get(BedrockPacketCodec.class)
                                    .getHelper()
                                    .setBlockDefinitions(blockDefinitions());
                            session.getPeer()
                                    .getChannel()
                                    .pipeline()
                                    .get(BedrockPacketCodec.class)
                                    .getHelper()
                                    .setItemDefinitions(itemDefinitions());
                            session.setPacketHandler(new LoginHandler(session, authType));
                            sessionHolder[0] = session;
                            connected.countDown();
                        }
                    })
                    .connect(address)
                    .sync()
                    .channel();
            assertTrue(connected.await(2, TimeUnit.SECONDS));
            session = sessionHolder[0];
        }

        private static SimpleDefinitionRegistry<BlockDefinition> blockDefinitions() {
            final List<BlockDefinition> definitions = new ArrayList<>();
            for (int stateId = 0; stateId < Block.statesCount(); stateId++) {
                final Block block = Block.fromStateId(stateId);
                assert block != null;
                definitions.add(new SimpleBlockDefinition(
                        block.key().asString(), stateId, NbtMap.EMPTY));
            }
            return SimpleDefinitionRegistry.<BlockDefinition>builder()
                    .addAll(definitions)
                    .build();
        }

        private static SimpleDefinitionRegistry<ItemDefinition> itemDefinitions() {
            final List<String> keys = Material.values().stream()
                    .map(material -> material.key().asString())
                    .sorted()
                    .toList();
            final List<ItemDefinition> definitions = new ArrayList<>(keys.size());
            definitions.add(new SimpleItemDefinition("minecraft:air", 0, true));
            int runtimeId = 1;
            for (String key : keys) {
                if (!key.equals("minecraft:air")) {
                    definitions.add(new SimpleItemDefinition(key, runtimeId++, true));
                }
            }
            return SimpleDefinitionRegistry.<ItemDefinition>builder()
                    .addAll(definitions)
                    .build();
        }

        private void begin() {
            var request = new RequestNetworkSettingsPacket();
            request.setProtocolVersion(1001);
            session.sendPacketImmediately(request);
        }

        private void move(Pos position, long tick) {
            move(position, tick, new PlayerAuthInputData[0]);
        }

        private void move(
                Pos position, long tick, PlayerAuthInputData... additionalInputData) {
            var packet = new PlayerAuthInputPacket();
            packet.setRotation(Vector3f.from(position.pitch(), position.yaw(), position.yaw()));
            packet.setPosition(Vector3f.from(
                    position.x(), position.y() + 1.62, position.z()));
            packet.setMotion(Vector2f.ZERO);
            packet.setInputMode(InputMode.MOUSE);
            packet.setPlayMode(ClientPlayMode.NORMAL);
            packet.setTick(tick);
            packet.setDelta(Vector3f.ZERO);
            packet.setInputInteractionModel(InputInteractionModel.CLASSIC);
            packet.setInteractRotation(Vector2f.ZERO);
            packet.setAnalogMoveVector(Vector2f.ZERO);
            packet.setVehicleRotation(Vector2f.ZERO);
            packet.setCameraOrientation(Vector3f.ZERO);
            packet.setRawMoveVector(Vector2f.ZERO);
            packet.getInputData().add(PlayerAuthInputData.VERTICAL_COLLISION);
            packet.getInputData().addAll(List.of(additionalInputData));
            session.sendPacket(packet);
        }

        private void sendText(String message) {
            final TextPacket text = new TextPacket();
            text.setType(TextPacket.Type.CHAT);
            text.setSourceName(name);
            text.setMessage(message);
            text.setNeedsTranslation(false);
            text.setXuid("");
            text.setPlatformChatId("");
            text.setFilteredMessage("");
            session.sendPacket(text);
        }

        private void sendCommand(String command) {
            final CommandRequestPacket request = new CommandRequestPacket();
            request.setCommand(command);
            request.setCommandOriginData(new CommandOriginData(
                    CommandOriginType.PLAYER, UUID.randomUUID(), "", 0));
            request.setInternal(false);
            request.setVersion(48);
            session.sendPacket(request);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (session.isConnected()) {
                session.disconnect("Test client closed");
            }
            channel.close().syncUninterruptibly();
            eventLoopGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }

        private final class LoginHandler implements BedrockPacketHandler {
            private final BedrockClientSession session;
            private final AuthType authType;

            private LoginHandler(BedrockClientSession session, AuthType authType) {
                this.session = session;
                this.authType = authType;
            }

            @Override
            public PacketSignal handle(NetworkSettingsPacket packet) {
                stage = "received network settings";
                session.setCompression(packet.getCompressionAlgorithm());
                if (!sendLogin) return PacketSignal.HANDLED;
                var login = new LoginPacket();
                login.setProtocolVersion(1001);
                login.setAuthPayload(new CertificateChainPayload(List.of(identityJwt), authType));
                login.setClientJwt(clientJwt);
                session.sendPacketImmediately(login);
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(ServerToClientHandshakePacket packet) {
                stage = "received server handshake";
                try {
                    var handshake = new JsonWebSignature();
                    handshake.setCompactSerialization(packet.getJwt());
                    var serverKey = EncryptionUtils.parseKey(handshake.getHeader(HeaderParameterNames.X509_URL));
                    handshake.setKey(serverKey);
                    assertTrue(handshake.verifySignature());
                    Map<String, Object> claims = JsonUtil.parseJson(handshake.getUnverifiedPayload());
                    byte[] token = Base64.getDecoder().decode((String) claims.get("salt"));
                    session.enableEncryption(
                            EncryptionUtils.getSecretKey(identityKey.getPrivate(), serverKey, token));
                    session.sendPacketImmediately(new ClientToServerHandshakePacket());
                    return PacketSignal.HANDLED;
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }

            @Override
            public PacketSignal handle(PlayStatusPacket packet) {
                stage = "received play status";
                playStatus = packet.getStatus();
                return PacketSignal.HANDLED;
            }

            @Override
            @SuppressWarnings("deprecation")
            public PacketSignal handle(ResourcePacksInfoPacket packet) {
                stage = "received resource-packs info";
                resourcePacksInfoEmpty =
                        packet.getBehaviorPackInfos().isEmpty() && packet.getResourcePackInfos().isEmpty();
                var response = new ResourcePackClientResponsePacket();
                response.setStatus(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
                session.sendPacket(response);
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(ResourcePackStackPacket packet) {
                stage = "received resource-pack stack";
                resourcePackStackEmpty =
                        packet.getBehaviorPacks().isEmpty() && packet.getResourcePacks().isEmpty();
                var response = new ResourcePackClientResponsePacket();
                response.setStatus(ResourcePackClientResponsePacket.Status.COMPLETED);
                session.sendPacket(response);
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(StartGamePacket packet) {
                stage = "received start game";
                final String expectedServerEngine =
                        "Minestom/" + BedrockCompatibility.JAVA_VERSION
                                + " BedrockMappings/" + BedrockCompatibility.BEDROCK_MAPPING_VERSION;
                startGameReceived = packet.getUniqueEntityId() > 0
                        && packet.getRuntimeEntityId() == packet.getUniqueEntityId()
                        && packet.getLevelName().equals("Minestom")
                        && packet.getDimensionId() == 0
                        && !packet.getLevelId().isBlank()
                        && packet.getVanillaVersion().equals(BedrockCompatibility.BEDROCK_WIRE_VERSION)
                        && packet.getServerEngine().equals(expectedServerEngine);
                completed.countDown();
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(LevelChunkPacket packet) {
                chunks.add(new ChunkSnapshot(
                        packet.getChunkX(),
                        packet.getChunkZ(),
                        packet.getSubChunksLength()));
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(UpdateBlockPacket packet) {
                blockUpdates.add(packet.clone());
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(MovePlayerPacket packet) {
                moves.add(packet.clone());
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(ChangeDimensionPacket packet) {
                dimensionChanges.add(packet.clone());
                var complete = new PlayerActionPacket();
                complete.setRuntimeEntityId(0);
                complete.setAction(PlayerActionType.DIMENSION_CHANGE_SUCCESS);
                complete.setBlockPosition(Vector3i.ZERO);
                complete.setResultPosition(Vector3i.ZERO);
                complete.setFace(0);
                session.sendPacket(complete);
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(PlayerListPacket packet) {
                playerLists.add(packet.clone());
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(AddEntityPacket packet) {
                addedEntities.add(packet.clone());
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(AddPlayerPacket packet) {
                addedPlayers.add(packet.clone());
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(SetEntityDataPacket packet) {
                entityData.add(packet.clone());
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(RemoveEntityPacket packet) {
                removedEntities.add(packet.clone());
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(MobEquipmentPacket packet) {
                equipment.add(packet.clone());
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(TextPacket packet) {
                texts.add(packet.clone());
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(NetworkStackLatencyPacket packet) {
                latencies.add(packet.clone());
                final NetworkStackLatencyPacket response = packet.clone();
                response.setFromServer(false);
                session.sendPacketImmediately(response);
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(TransferPacket packet) {
                transfers.add(packet.clone());
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(DisconnectPacket packet) {
                disconnects.add(packet.getKickMessage());
                stage = "disconnected: " + packet.getKickMessage();
                completed.countDown();
                return PacketSignal.HANDLED;
            }

            @Override
            public void onDisconnect(CharSequence reason) {
                stage = "disconnected: " + reason;
                completed.countDown();
            }

        }
    }

    private record ChunkSnapshot(int x, int z, int subChunks) {
    }

    private static String sign(JwtClaims claims, KeyPair keyPair) throws Exception {
        var signature = new JsonWebSignature();
        signature.setAlgorithmHeaderValue(EncryptionUtils.ALGORITHM_TYPE);
        signature.setHeader(
                HeaderParameterNames.X509_URL,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        signature.setKey(keyPair.getPrivate());
        signature.setPayload(claims.toJson());
        return signature.getCompactSerialization();
    }
}
