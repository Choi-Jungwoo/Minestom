package net.minestom.server.bedrock;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minestom.server.ServerProcess;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.server.ServerListPingEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.PlayerAdmission;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.ping.ServerListPingType;
import net.minestom.server.ping.Status;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakPing;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerOfflineHandler;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Owns the process-level Bedrock UDP listener.
 */
public final class BedrockServer {
    private static final System.Logger LOGGER = System.getLogger(BedrockServer.class.getName());
    private static final long SHUTDOWN_DRAIN_MILLIS = 100;
    private static final int MAX_CONCURRENT_PINGS = 64;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final String ACCEPTED_PROTOCOLS = BedrockProtocol.acceptedVersionsDescription();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final IdentityHashMap<ServerProcess, BedrockServer> INSTANCES = new IdentityHashMap<>();

    private final ServerProcess process;
    private final GlobalEventHandler eventHandler;
    private final BedrockServerConfig config;
    private final @Nullable BedrockMappings preloadedMappings;
    private final Set<BedrockServerSession> sessions = ConcurrentHashMap.newKeySet();

    private @Nullable Channel channel;
    private @Nullable EventLoopGroup parentGroup;
    private @Nullable EventLoopGroup childGroup;
    private volatile @Nullable ExecutorService pingExecutor;
    private volatile @Nullable BedrockMappings mappings;

    private BedrockServer(
            ServerProcess process,
            BedrockServerConfig config,
            @Nullable BedrockMappings preloadedMappings) {
        this.process = process;
        this.eventHandler = process.eventHandler();
        this.config = config;
        this.preloadedMappings = preloadedMappings;
    }

    /**
     * Returns the single Bedrock server owned by a Minestom process.
     *
     * @param process the owning process
     * @param address the UDP bind address
     * @return the process Bedrock server
     */
    public static synchronized BedrockServer create(ServerProcess process, SocketAddress address) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(address, "address");
        if (!(address instanceof InetSocketAddress inetAddress)) {
            throw new IllegalArgumentException("Bedrock requires an internet socket address");
        }
        final String mappingsDirectory = System.getProperty("minestom.bedrock.mappings");
        if (mappingsDirectory == null || mappingsDirectory.isBlank()) {
            throw new IllegalStateException(
                    "Set minestom.bedrock.mappings to the exact operator-provided mappings directory");
        }
        return create(process, new BedrockServerConfig(
                inetAddress,
                process.instance().createInstanceContainer(),
                Path.of(mappingsDirectory)));
    }

    /**
     * Returns the single configured Bedrock server owned by a Minestom process.
     *
     * @param process the owning process
     * @param config  immutable listener, instance, and mappings settings
     * @return the process Bedrock server
     */
    public static synchronized BedrockServer create(
            ServerProcess process, BedrockServerConfig config) {
        return create(process, config, null);
    }

    static synchronized BedrockServer createForTesting(
            ServerProcess process, InetSocketAddress address) {
        final BedrockServer existing = INSTANCES.get(process);
        if (existing != null && existing.config.address().equals(address)) return existing;
        return create(
                process,
                new BedrockServerConfig(
                        address, process.instance().createInstanceContainer(), Path.of(".")),
                BedrockMappings.testing(process));
    }

    private static BedrockServer create(
            ServerProcess process,
            BedrockServerConfig config,
            @Nullable BedrockMappings preloadedMappings) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(config, "config");
        final BedrockServer existing = INSTANCES.get(process);
        if (existing != null) {
            if (!existing.config.equals(config)) {
                throw new IllegalStateException("A Bedrock server already exists for this process");
            }
            return existing;
        }

        final BedrockServer server = new BedrockServer(process, config, preloadedMappings);
        INSTANCES.put(process, server);
        process.scheduler().buildShutdownTask(() -> {
            server.stop();
            synchronized (BedrockServer.class) {
                INSTANCES.remove(process, server);
            }
        });
        return server;
    }

    /**
     * Starts the UDP listener. Calling this while it is already started has no effect.
     */
    public synchronized void start() {
        if (isStarted()) return;

        final BedrockMappings mappings;
        try {
            mappings = preloadedMappings != null
                    ? preloadedMappings
                    : BedrockMappings.load(config.mappingsDirectory());
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load supported Bedrock mappings", exception);
        }
        mappings.requireRegistryCompatibility(process);
        final ExecutorService pingExecutor = createPingExecutor();
        final EventLoopGroup parentGroup =
                new MultiThreadIoEventLoopGroup(
                        1, new DefaultThreadFactory("minestom-bedrock-parent", true), NioIoHandler.newFactory());
        final EventLoopGroup childGroup =
                new MultiThreadIoEventLoopGroup(
                        0, new DefaultThreadFactory("minestom-bedrock-child", true), NioIoHandler.newFactory());
        this.pingExecutor = pingExecutor;
        try {
            final Channel channel = new ServerBootstrap()
                    .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                    .group(parentGroup, childGroup)
                    .option(RakChannelOption.RAK_HANDLE_PING, true)
                    .childHandler(new Initializer(this))
                    .bind(config.address())
                    .syncUninterruptibly()
                    .channel();
            try {
                channel.pipeline().addAfter(
                        RakServerOfflineHandler.NAME, DiscoveryHandler.NAME, new DiscoveryHandler(this));
            } catch (RuntimeException exception) {
                channel.close().syncUninterruptibly();
                throw exception;
            }
            this.parentGroup = parentGroup;
            this.childGroup = childGroup;
            this.channel = channel;
            this.mappings = mappings;
        } catch (RuntimeException exception) {
            this.pingExecutor = null;
            shutdown(pingExecutor);
            shutdown(parentGroup);
            shutdown(childGroup);
            this.mappings = null;
            throw exception;
        }
    }

    /**
     * Stops the UDP listener and releases its event-loop threads and sessions.
     * Calling this while it is stopped has no effect.
     */
    public synchronized void stop() {
        final Channel channel = this.channel;
        if (channel == null) return;

        final EventLoopGroup childGroup = this.childGroup;
        final EventLoopGroup parentGroup = this.parentGroup;
        final ExecutorService pingExecutor = this.pingExecutor;
        this.channel = null;
        this.childGroup = null;
        this.parentGroup = null;
        this.pingExecutor = null;
        this.mappings = null;

        @Nullable RuntimeException failure = null;
        if (pingExecutor != null) {
            failure = cleanup(failure, () -> shutdown(pingExecutor));
        }
        failure = disconnectSessions(failure);
        sessions.clear();
        failure = cleanup(failure, () -> channel.close().syncUninterruptibly());
        failure = cleanup(failure, () -> shutdown(childGroup));
        failure = cleanup(failure, () -> shutdown(parentGroup));
        if (failure != null) throw failure;
    }

    private @Nullable RuntimeException disconnectSessions(@Nullable RuntimeException failure) {
        final List<Channel> peerChannels = new ArrayList<>();
        final List<Future<?>> drainFutures = new ArrayList<>();
        for (BedrockServerSession session : Set.copyOf(sessions)) {
            final Channel peerChannel = session.getPeer().getChannel();
            if (!peerChannel.isActive()) continue;
            peerChannels.add(peerChannel);
            failure = cleanup(failure, () -> session.disconnect("Server shutting down"));
            try {
                drainFutures.add(peerChannel.eventLoop()
                        .schedule(peerChannel::flush, SHUTDOWN_DRAIN_MILLIS, TimeUnit.MILLISECONDS));
            } catch (RuntimeException exception) {
                failure = addFailure(failure, exception);
            }
        }
        for (Future<?> drainFuture : drainFutures) {
            failure = cleanup(failure, drainFuture::syncUninterruptibly);
        }
        for (Channel peerChannel : peerChannels) {
            failure = cleanup(failure, () -> peerChannel.close().syncUninterruptibly());
        }
        return failure;
    }

    private static @Nullable RuntimeException cleanup(
            @Nullable RuntimeException failure, Runnable cleanup) {
        try {
            cleanup.run();
            return failure;
        } catch (RuntimeException exception) {
            return addFailure(failure, exception);
        }
    }

    private static RuntimeException addFailure(
            @Nullable RuntimeException failure, RuntimeException exception) {
        if (failure == null) return exception;
        failure.addSuppressed(exception);
        return failure;
    }

    /**
     * Returns whether the UDP listener is active.
     *
     * @return {@code true} when started
     */
    public synchronized boolean isStarted() {
        return channel != null && channel.isActive();
    }

    /**
     * Returns the configured UDP bind address. A port of zero remains zero here.
     *
     * @return the configured address
     */
    public InetSocketAddress configuredAddress() {
        return config.address();
    }

    Instance spawningInstance() {
        return config.spawningInstance();
    }

    /**
     * Returns the actual UDP bind address, including an ephemeral port selected at startup.
     *
     * @return the bound address
     * @throws IllegalStateException if the server is stopped
     */
    public synchronized InetSocketAddress boundAddress() {
        if (!isStarted()) throw new IllegalStateException("Bedrock server is not started");
        return (InetSocketAddress) channel.localAddress();
    }

    private static ExecutorService createPingExecutor() {
        return new ThreadPoolExecutor(
                0,
                MAX_CONCURRENT_PINGS,
                30,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                Thread.ofVirtual().name("minestom-bedrock-ping-", 0).factory(),
                new ThreadPoolExecutor.DiscardPolicy());
    }

    private static void shutdown(@Nullable EventLoopGroup group) {
        if (group != null) {
            group.shutdownGracefully(0, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static void shutdown(ExecutorService executor) {
        executor.shutdownNow();
        boolean interrupted = false;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_TIMEOUT_SECONDS);
        while (!executor.isTerminated()) {
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException _) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class Initializer extends BedrockServerInitializer {
        private final BedrockServer server;

        private Initializer(BedrockServer server) {
            this.server = server;
        }

        @Override
        protected void initSession(BedrockServerSession session) {
            server.sessions.add(session);
            session.setCodec(Objects.requireNonNull(
                    BedrockProtocol.codec(BedrockCompatibility.PRIMARY_PROTOCOL)));
            session.setPacketHandler(new HandshakeHandler(server, session));
        }
    }

    private static final class HandshakeHandler implements BedrockPacketHandler {
        private final BedrockServer server;
        private final BedrockServerSession session;
        private HandshakePhase phase = HandshakePhase.INITIAL;
        private @Nullable GameProfile candidateProfile;
        private @Nullable BedrockConnection connection;

        private HandshakeHandler(BedrockServer server, BedrockServerSession session) {
            this.server = server;
            this.session = session;
        }

        @Override
        public PacketSignal handle(RequestNetworkSettingsPacket request) {
            if (phase != HandshakePhase.INITIAL) {
                session.disconnect("Network settings were already requested");
                return PacketSignal.HANDLED;
            }

            final int protocolVersion = request.getProtocolVersion();
            final BedrockCodec codec = BedrockProtocol.codec(protocolVersion);
            if (codec == null) {
                session.disconnect("Unsupported Bedrock protocol " + protocolVersion +
                        "; expected one of " + ACCEPTED_PROTOCOLS);
                return PacketSignal.HANDLED;
            }

            session.setCodec(codec);
            final NetworkSettingsPacket response = new NetworkSettingsPacket();
            response.setCompressionAlgorithm(PacketCompressionAlgorithm.ZLIB);
            response.setCompressionThreshold(512);
            session.sendPacketImmediately(response);
            session.setCompression(PacketCompressionAlgorithm.ZLIB);
            phase = HandshakePhase.NETWORK_SETTINGS;
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(LoginPacket login) {
            if (phase != HandshakePhase.NETWORK_SETTINGS
                    || login.getProtocolVersion() != session.getCodec().getProtocolVersion()) {
                session.disconnect("Invalid Bedrock login sequence");
                return PacketSignal.HANDLED;
            }

            try {
                final BedrockLoginValidator.VerifiedLogin verified = BedrockLoginValidator.validate(login);
                final UUID uuid = BedrockConnection.offlineUuid(verified.name());
                candidateProfile = new GameProfile(uuid, verified.name());
                final Channel peerChannel = session.getPeer().getChannel();
                connection = new BedrockConnection(
                        session,
                        (InetSocketAddress) peerChannel.remoteAddress(),
                        (InetSocketAddress) peerChannel.localAddress(),
                        Objects.requireNonNull(server.mappings, "Bedrock mappings were not loaded"),
                        server.process,
                        verified.skin());
                final KeyPair serverKeyPair = EncryptionUtils.createKeyPair();
                final byte[] token = EncryptionUtils.generateRandomToken();
                final ServerToClientHandshakePacket handshake = new ServerToClientHandshakePacket();
                handshake.setJwt(EncryptionUtils.createHandshakeJwt(serverKeyPair, token));
                session.sendPacketImmediately(handshake);
                session.enableEncryption(EncryptionUtils.getSecretKey(
                        serverKeyPair.getPrivate(), verified.clientKey(), token));
                phase = HandshakePhase.LOGIN;
            } catch (Exception _) {
                session.disconnect("Invalid Bedrock login");
            }
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ClientToServerHandshakePacket packet) {
            if (phase != HandshakePhase.LOGIN) {
                session.disconnect("Invalid Bedrock encryption handshake");
                return PacketSignal.HANDLED;
            }
            phase = HandshakePhase.ENCRYPTED;

            final PlayStatusPacket status = new PlayStatusPacket();
            status.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
            session.sendPacket(status);

            final ResourcePacksInfoPacket packsInfo = new ResourcePacksInfoPacket();
            packsInfo.setForcedToAccept(false);
            packsInfo.setHasAddonPacks(false);
            packsInfo.setScriptingEnabled(false);
            packsInfo.setVibrantVisualsForceDisabled(true);
            packsInfo.setWorldTemplateId(new UUID(0, 0));
            packsInfo.setWorldTemplateVersion("");
            session.sendPacket(packsInfo);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ResourcePackClientResponsePacket response) {
            if (phase != HandshakePhase.ENCRYPTED && phase != HandshakePhase.PACK_STACK) {
                session.disconnect("Invalid Bedrock resource-pack handshake");
                return PacketSignal.HANDLED;
            }

            switch (response.getStatus()) {
                case HAVE_ALL_PACKS -> {
                    if (phase != HandshakePhase.ENCRYPTED) {
                        session.disconnect("Invalid Bedrock resource-pack handshake");
                        return PacketSignal.HANDLED;
                    }
                    final ResourcePackStackPacket stack = new ResourcePackStackPacket();
                    stack.setForcedToAccept(false);
                    stack.setGameVersion(session.getCodec().getMinecraftVersion());
                    stack.setExperimentsPreviouslyToggled(false);
                    stack.setHasEditorPacks(false);
                    session.sendPacket(stack);
                    phase = HandshakePhase.PACK_STACK;
                }
                case COMPLETED -> {
                    if (phase != HandshakePhase.PACK_STACK) {
                        session.disconnect("Invalid Bedrock resource-pack handshake");
                        return PacketSignal.HANDLED;
                    }
                    phase = HandshakePhase.ADMITTED;
                    admitPlayer();
                }
                case SEND_PACKS -> {
                    if (phase != HandshakePhase.ENCRYPTED
                            || !response.getPackIds().isEmpty()) {
                        session.disconnect("This server has no Bedrock resource packs");
                    }
                }
                default -> session.disconnect("Bedrock resource-pack handshake was refused");
            }
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(PlayerAuthInputPacket packet) {
            final BedrockConnection connection = this.connection;
            if (phase != HandshakePhase.ADMITTED || connection == null) {
                session.disconnect("Bedrock movement received before player admission");
                return PacketSignal.HANDLED;
            }
            connection.handle(packet);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(PlayerActionPacket packet) {
            final BedrockConnection connection = this.connection;
            if (phase != HandshakePhase.ADMITTED || connection == null) {
                session.disconnect("Bedrock player action received before player admission");
                return PacketSignal.HANDLED;
            }
            if (packet.getAction() == PlayerActionType.DIMENSION_CHANGE_SUCCESS) {
                connection.handleDimensionChangeSuccess();
            }
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(TextPacket packet) {
            final BedrockConnection connection = this.connection;
            if (phase != HandshakePhase.ADMITTED || connection == null) {
                session.disconnect("Bedrock chat received before player admission");
                return PacketSignal.HANDLED;
            }
            connection.handle(packet);
            return PacketSignal.HANDLED;
        }

        @Override
        public void onDisconnect(CharSequence reason) {
            server.sessions.remove(session);
            final BedrockConnection connection = this.connection;
            if (connection != null) connection.peerDisconnected();
        }

        private void admitPlayer() {
            final GameProfile candidate = Objects.requireNonNull(this.candidateProfile);
            final BedrockConnection connection = Objects.requireNonNull(this.connection);
            final PlayerAdmission admission = new PlayerAdmission() {
                @Override
                public boolean requiresUniqueIdentity() {
                    return true;
                }

                @Override
                public CompletableFuture<Void> accept(GameProfile gameProfile) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletableFuture<Void> prepare(Player player) {
                    final BedrockMappings mappings = Objects.requireNonNull(
                            server.mappings, "Bedrock mappings were not loaded");
                    player.setPendingOptions(server.config.spawningInstance(), false);
                    connection.setClientState(ConnectionState.PLAY);
                    connection.setServerState(ConnectionState.PLAY);
                    connection.initializeInstance(server.config.spawningInstance());
                    connection.sendBedrockPacket(BedrockStartGame.create(
                            player,
                            server.config.spawningInstance(),
                            server.process,
                            mappings,
                            connection.getProtocolVersion()));
                    return CompletableFuture.completedFuture(null);
                }
            };
            var _ = server.process.connection()
                    .admitPlayer(connection, candidate, admission)
                    .exceptionally(throwable -> {
                        LOGGER.log(System.Logger.Level.ERROR, "Bedrock player admission failed", throwable);
                        return null;
                    });
        }
    }

    private enum HandshakePhase {
        INITIAL,
        NETWORK_SETTINGS,
        LOGIN,
        ENCRYPTED,
        PACK_STACK,
        ADMITTED
    }

    @ChannelHandler.Sharable
    private static final class DiscoveryHandler extends SimpleChannelInboundHandler<RakPing> {
        private static final String NAME = "minestom-bedrock-discovery";

        private final BedrockServer server;

        private DiscoveryHandler(BedrockServer server) {
            this.server = server;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, RakPing ping) {
            server.submitPing(context, ping);
        }
    }

    private void submitPing(ChannelHandlerContext context, RakPing ping) {
        final ExecutorService pingExecutor = this.pingExecutor;
        if (pingExecutor != null) {
            pingExecutor.execute(() -> respondToPing(context, ping));
        }
    }

    private void respondToPing(ChannelHandlerContext context, RakPing ping) {
        final ServerListPingEvent event = new ServerListPingEvent(ServerListPingType.BEDROCK);
        eventHandler.call(event);
        if (event.isCancelled()) return;

        final Status status = event.getStatus();
        final Status.PlayerInfo playerInfo = status.playerInfo();
        final int port = ((InetSocketAddress) context.channel().localAddress()).getPort();
        final long serverId = context.channel().config().getOption(RakChannelOption.RAK_GUID);
        final BedrockPong pong = new BedrockPong()
                .edition("MCPE")
                .motd(LEGACY.serialize(status.description()))
                .protocolVersion(1001)
                .version("1.26.30")
                .playerCount(playerInfo == null ? 0 : playerInfo.onlinePlayers())
                .maximumPlayerCount(playerInfo == null ? 1 : playerInfo.maxPlayers())
                .serverId(serverId)
                .subMotd("Minestom")
                .gameType("Survival")
                .nintendoLimited(false)
                .ipv4Port(port)
                .ipv6Port(port);
        context.writeAndFlush(ping.reply(serverId, pong.toByteBuf()))
                .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }
}
