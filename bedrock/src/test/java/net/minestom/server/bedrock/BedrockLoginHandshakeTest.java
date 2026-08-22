package net.minestom.server.bedrock;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerProcess;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.player.GameProfile;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
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
import java.security.KeyPair;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BedrockLoginHandshakeTest {
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
    void loopbackPlayerUsesAuthoritativeInstanceWorldAndMovement() throws Exception {
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

            client.move(new Pos(17, 0, 0), 1);
            assertTrue(tickUntil(() -> player.getPosition().x() == 17));
            assertTrue(tickUntil(() -> client.chunks.stream()
                    .anyMatch(chunk -> chunk.subChunks() == 0)));
            client.moves.clear();

            first.setBlock(17, 0, 0, Block.DIRT);
            UpdateBlockPacket update = client.blockUpdates.poll(3, TimeUnit.SECONDS);
            assertNotNull(update);
            assertEquals(Block.DIRT.stateId(), update.getDefinition().getRuntimeId());

            client.move(new Pos(18, 0, 0), 2);
            assertTrue(tickUntil(() -> !client.moves.isEmpty()));
            MovePlayerPacket correction = awaitMove(client);
            assertEquals(MovePlayerPacket.Mode.TELEPORT, correction.getMode());
            assertEquals(17, correction.getPosition().getX());
            assertEquals(17, player.getPosition().x());
            client.moves.clear();

            player.teleport(new Pos(20, 0, 0)).join();
            MovePlayerPacket teleport = awaitMove(client);
            assertEquals(20, teleport.getPosition().getX());
            client.move(new Pos(20, 0, 0), 3);
            tick();
            assertEquals(20, player.getPosition().x());

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
            assertTrue(
                    tickUntil(() -> client.chunks.stream().anyMatch(chunk ->
                            chunk.x() == 0 && chunk.z() == 0 && chunk.subChunks() > 0)),
                    () -> "stage: " + client.stage + ", chunks: " + client.chunks);

            client.move(new Pos(0, 0, 0), 4, PlayerAuthInputData.START_FLYING);
            assertTrue(tickUntil(() -> client.stage.contains(
                    "Unsupported Bedrock movement capability")));
        }
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

    private boolean tickUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            tick();
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private void tick() {
        process.connection().updateWaitingPlayers();
        process.ticker().tick(System.nanoTime());
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
        MISMATCHED_CLIENT_DATA
    }

    private static final class LoginClient implements AutoCloseable {
        private final CountDownLatch completed = new CountDownLatch(1);
        private final EventLoopGroup eventLoopGroup =
                new MultiThreadIoEventLoopGroup(
                        1, new DefaultThreadFactory("bedrock-login-client", true), NioIoHandler.newFactory());
        private final KeyPair identityKey = EncryptionUtils.createKeyPair();
        private final String identityJwt;
        private final String clientJwt;

        private final BedrockClientSession session;
        private final Channel channel;
        private volatile String stage = "connected";
        private volatile PlayStatusPacket.Status playStatus;
        private volatile boolean resourcePacksInfoEmpty;
        private volatile boolean resourcePackStackEmpty;
        private volatile boolean startGameReceived;
        private final BlockingQueue<ChunkSnapshot> chunks = new LinkedBlockingQueue<>();
        private final BlockingQueue<UpdateBlockPacket> blockUpdates = new LinkedBlockingQueue<>();
        private final BlockingQueue<MovePlayerPacket> moves = new LinkedBlockingQueue<>();
        private final BlockingQueue<ChangeDimensionPacket> dimensionChanges = new LinkedBlockingQueue<>();

        private LoginClient(
                InetSocketAddress address, String name, AuthType authType, Credentials credentials)
                throws Exception {
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

        @Override
        public void close() {
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
                return PacketSignal.HANDLED;
            }

            @Override
            public PacketSignal handle(DisconnectPacket packet) {
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
