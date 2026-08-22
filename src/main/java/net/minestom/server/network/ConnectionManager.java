package net.minestom.server.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.listener.preplay.LoginListener;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import net.minestom.server.network.packet.server.configuration.FinishConfigurationPacket;
import net.minestom.server.network.packet.server.configuration.ResetChatPacket;
import net.minestom.server.network.packet.server.configuration.SelectKnownPacksPacket;
import net.minestom.server.network.packet.server.configuration.UpdateEnabledFeaturesPacket;
import net.minestom.server.network.packet.server.login.LoginSuccessPacket;
import net.minestom.server.network.packet.server.play.StartConfigurationPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.network.player.PlayerSocketConnection;
import net.minestom.server.network.plugin.LoginPluginMessageProcessor;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.StaticProtocolObject;
import net.minestom.server.utils.StringUtils;
import net.minestom.server.utils.collection.ConcurrentMessageQueues;
import org.jctools.queues.MessagePassingQueue;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Manages the connected clients.
 */
public final class ConnectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionManager.class);

    private static final Component TIMEOUT_TEXT = Component.text("Timeout", NamedTextColor.RED);
    private static final Component ADMISSION_FAILED_TEXT = Component.text("Error during login!", NamedTextColor.RED);
    private static final Component SHUTDOWN_TEXT = Component.text("Server shutting down");

    private final CachedPacket cachedTagsPacket =
            new CachedPacket(() -> Registries.tagsPacket(MinecraftServer.getRegistries()));

    // All players once their Player object has been instantiated.
    private final Map<PlayerConnection, Player> connectionPlayerMap = new ConcurrentHashMap<>();
    // Connections currently moving through the protocol-neutral admission lifecycle.
    private final Map<PlayerConnection, AdmissionState> playerAdmissions = new ConcurrentHashMap<>();
    // Final profiles reserved between pre-login and Player creation.
    private final Set<AdmissionIdentity> admissionIdentities = new HashSet<>();
    private volatile boolean acceptingAdmissions = true;
    // Players waiting to be spawned (post configuration state)
    private final MessagePassingQueue<Player> playWaitingPlayers = ConcurrentMessageQueues.mpscUnboundedArrayQueue(64);
    // Players waiting to be (re) configured
    private final MessagePassingQueue<Player> configWaitingPlayers = ConcurrentMessageQueues.mpscUnboundedArrayQueue(64);
    // Players in configuration state
    private final Set<Player> configurationPlayers = new CopyOnWriteArraySet<>();
    // Players in play state
    private final Set<Player> playPlayers = new CopyOnWriteArraySet<>();

    // The players who need keep alive ticks. This was added because we may not send a keep alive in
    // the time after sending finish configuration but before receiving configuration end (to swap to play).
    // I(mattw) could not come up with a better way to express this besides completely splitting client/server
    // states. Perhaps there will be an improvement in the future.
    private final Set<Player> keepAlivePlayers = new CopyOnWriteArraySet<>();

    private final Set<Player> unmodifiableConfigurationPlayers = Collections.unmodifiableSet(configurationPlayers);
    private final Set<Player> unmodifiablePlayPlayers = Collections.unmodifiableSet(playPlayers);

    // The player provider to have your own Player implementation
    private volatile PlayerProvider playerProvider = Player::new;

    /**
     * Gets the number of "online" players, e.g. for the query response.
     *
     * <p>Only includes players in the play state, not players in configuration.</p>
     */
    public int getOnlinePlayerCount() {
        return playPlayers.size();
    }

    /**
     * Returns an unmodifiable set containing the players currently in the play state.
     */
    @SuppressWarnings("PreferredInterfaceType") // wider type kept for binary compatibility until the next breaking release
    public Collection<Player> getOnlinePlayers() {
        return unmodifiablePlayPlayers;
    }

    /**
     * Returns an unmodifiable set containing the players currently in the configuration state.
     */
    @SuppressWarnings("PreferredInterfaceType") // wider type kept for binary compatibility until the next breaking release
    public Collection<Player> getConfigPlayers() {
        return unmodifiableConfigurationPlayers;
    }

    /**
     * Gets the {@link Player} linked to a {@link PlayerConnection}.
     *
     * <p>The player will be returned whether they are in the play or config state,
     * so be sure to check before sending packets to them.</p>
     *
     * @param connection the player connection
     * @return the player linked to the connection
     */
    public @Nullable Player getPlayer(PlayerConnection connection) {
        return connectionPlayerMap.get(connection);
    }

    /**
     * Gets the first player in the play state which validates {@link String#equalsIgnoreCase(String)}.
     * <p>
     * This can cause issue if two or more players have the same username.
     *
     * @param username the player username (case-insensitive)
     * @return the first player who validate the username condition, null if none was found
     */
    public @Nullable Player getOnlinePlayerByUsername(String username) {
        for (Player player : getOnlinePlayers()) {
            if (player.getUsername().equalsIgnoreCase(username))
                return player;
        }
        return null;
    }

    /**
     * Gets the first player in the play state which validates {@link UUID#equals(Object)}.
     * <p>
     * This can cause issue if two or more players have the same UUID.
     *
     * @param uuid the player UUID
     * @return the first player who validate the UUID condition, null if none was found
     */
    public @Nullable Player getOnlinePlayerByUuid(UUID uuid) {
        for (Player player : getOnlinePlayers()) {
            if (player.getUuid().equals(uuid))
                return player;
        }
        return null;
    }

    /**
     * Finds the closest player in the play state matching a given username.
     *
     * @param username the player username (can be partial)
     * @return the closest match, null if no players are online
     */
    public @Nullable Player findOnlinePlayer(String username) {
        Player exact = getOnlinePlayerByUsername(username);
        if (exact != null) return exact;
        final String username1 = username.toLowerCase(Locale.ROOT);

        Function<Player, Double> distanceFunction = player -> {
            final String username2 = player.getUsername().toLowerCase(Locale.ROOT);
            return StringUtils.jaroWinklerScore(username1, username2);
        };
        return getOnlinePlayers().stream()
                .max(Comparator.comparingDouble(distanceFunction::apply))
                .filter(player -> distanceFunction.apply(player) > 0)
                .orElse(null);
    }

    /**
     * Changes the {@link Player} provider, to change which object to link to him.
     *
     * @param playerProvider the new {@link PlayerProvider}, can be set to null to apply the default provider
     */
    public void setPlayerProvider(@Nullable PlayerProvider playerProvider) {
        this.playerProvider = playerProvider != null ? playerProvider : Player::new;
    }

    @ApiStatus.Internal
    public synchronized Player createPlayer(PlayerConnection connection, GameProfile gameProfile) {
        assert ServerFlag.INSIDE_TEST || Thread.currentThread().isVirtual();
        final Player player = Objects.requireNonNull(
                playerProvider.createPlayer(connection, gameProfile), "PlayerProvider returned null");
        this.connectionPlayerMap.put(connection, player);
        return player;
    }

    /**
     * Admits a candidate connection through the shared player lifecycle.
     *
     * <p>Only one admission may be active for a connection. The final profile is accepted by the
     * adapter before the player provider is invoked, and protocol preparation runs only after the
     * created player has been registered. Any failure rolls back the player registration.
     *
     * @param connection  the candidate connection
     * @param gameProfile the candidate profile
     * @param admission   the protocol adapter
     * @return a future completed with the admitted player
     */
    @ApiStatus.Experimental
    public CompletableFuture<Player> admitPlayer(PlayerConnection connection, GameProfile gameProfile,
                                                  PlayerAdmission admission) {
        return admitPlayer(connection, gameProfile, admission,
                ServerFlag.PLAYER_ADMISSION_TIMEOUT, TimeUnit.MILLISECONDS);
    }

    CompletableFuture<Player> admitPlayer(PlayerConnection connection, GameProfile gameProfile,
                                           PlayerAdmission admission, long timeout, TimeUnit unit) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(gameProfile, "gameProfile");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(unit, "unit");
        if (timeout <= 0) throw new IllegalArgumentException("timeout must be positive");
        if (!acceptingAdmissions) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Player admissions are shutting down"));
        }
        if (connectionPlayerMap.containsKey(connection)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Connection already has an admitted player"));
        }

        final AdmissionState state = new AdmissionState(connection);
        if (playerAdmissions.putIfAbsent(connection, state) != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Connection admission is already in progress"));
        }
        var _ = state.operation.whenComplete((player, throwable) -> {
            state.cancelPhase();
            if (throwable != null) {
                final Throwable rollbackFailure = rollbackAdmission(connection);
                if (rollbackFailure != null && rollbackFailure != throwable) {
                    throwable.addSuppressed(rollbackFailure);
                }
            }
            playerAdmissions.remove(connection, state);
            if (throwable == null) {
                state.result.complete(player);
            } else {
                state.result.completeExceptionally(throwable);
            }
        });
        if (!acceptingAdmissions) {
            state.operation.cancel(true);
            return state.result;
        }
        Thread.startVirtualThread(() -> runAdmission(
                state, gameProfile, admission, timeout, unit));
        return state.result;
    }

    private void runAdmission(AdmissionState state, GameProfile candidateProfile,
                              PlayerAdmission admission, long timeout, TimeUnit unit) {
        try {
            if (state.operation.isDone()) return;
            final GameProfile finalProfile = finalGameProfile(state.connection, candidateProfile);
            if (finalProfile == null) {
                throw new CancellationException("Connection closed during pre-login");
            }

            final Player player;
            final AdmissionIdentity identity = reserveAdmissionIdentity(finalProfile);
            try {
                awaitAdmissionPhase(state, admission.accept(finalProfile), timeout, unit);
                ensureAdmissionActive(state);
                player = createPlayer(state.connection, finalProfile);
            } finally {
                releaseAdmissionIdentity(identity);
            }
            if (state.operation.isDone()) {
                rollbackAdmission(state.connection);
                return;
            }

            awaitAdmissionPhase(state, admission.prepare(player), timeout, unit);
            ensureAdmissionActive(state);
            transitionConfigToPlay(player);
            state.operation.complete(player);
        } catch (Throwable throwable) {
            if (throwable instanceof InterruptedException) Thread.currentThread().interrupt();
            state.operation.completeExceptionally(throwable);
        }
    }

    private synchronized AdmissionIdentity reserveAdmissionIdentity(GameProfile gameProfile) {
        final AdmissionIdentity identity = AdmissionIdentity.from(gameProfile);
        for (Player existing : connectionPlayerMap.values()) {
            if (identity.matches(existing)) throw duplicateIdentity();
        }
        for (AdmissionIdentity reserved : admissionIdentities) {
            if (identity.conflictsWith(reserved)) throw duplicateIdentity();
        }
        admissionIdentities.add(identity);
        return identity;
    }

    private synchronized void releaseAdmissionIdentity(AdmissionIdentity identity) {
        admissionIdentities.remove(identity);
    }

    private static IllegalArgumentException duplicateIdentity() {
        return new IllegalArgumentException("A player with this name or UUID is already connected");
    }

    private static void awaitAdmissionPhase(AdmissionState state, CompletableFuture<Void> phase,
                                            long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(phase, "PlayerAdmission returned null");
        if (!state.phase.compareAndSet(null, phase)) {
            throw new IllegalStateException("Another admission phase is already active");
        }
        try {
            if (state.operation.isDone()) {
                phase.cancel(true);
                throw new CancellationException("Player admission was cancelled");
            }
            phase.get(timeout, unit);
        } catch (InterruptedException | TimeoutException exception) {
            phase.cancel(true);
            throw exception;
        } finally {
            state.phase.compareAndSet(phase, null);
        }
    }

    private static void ensureAdmissionActive(AdmissionState state) {
        if (state.operation.isDone() || !state.connection.isOnline()) {
            throw new CancellationException("Connection closed during player admission");
        }
    }

    private @Nullable Throwable rollbackAdmission(PlayerConnection connection) {
        Throwable failure = null;
        try {
            if (connection.isOnline()) connection.kick(ADMISSION_FAILED_TEXT);
        } catch (Throwable throwable) {
            failure = throwable;
            try {
                connection.disconnect();
            } catch (Throwable disconnectFailure) {
                failure = combineFailures(failure, disconnectFailure);
            }
        }
        try {
            removePlayer(connection);
        } catch (Throwable cleanupFailure) {
            failure = combineFailures(failure, cleanupFailure);
        }
        try {
            connection.setPlayer(null);
        } catch (Throwable cleanupFailure) {
            failure = combineFailures(failure, cleanupFailure);
        }
        return failure;
    }

    private static Throwable combineFailures(@Nullable Throwable first, Throwable second) {
        if (first == null) return second;
        if (first != second) first.addSuppressed(second);
        return first;
    }

    @ApiStatus.Internal
    public void cancelPlayerAdmission(PlayerConnection connection) {
        final AdmissionState state = playerAdmissions.get(connection);
        if (state != null) state.operation.cancel(true);
    }

    public void sendRegistryTags(Player player) {
        player.sendPacket(cachedTagsPacket);
    }

    // This is a somewhat weird implementation where connectionmanager owns the caching of tags.
    // There should be no registry->connectionmanager communication.
    @ApiStatus.Internal
    public void invalidateTags() {
        this.cachedTagsPacket.invalidate();
    }

    public GameProfile transitionLoginToConfig(PlayerConnection connection, GameProfile gameProfile) {
        assert ServerFlag.INSIDE_TEST || Thread.currentThread().isVirtual();
        // Compression
        if (connection instanceof PlayerSocketConnection socketConnection) {
            final int threshold = MinecraftServer.getCompressionThreshold();
            if (threshold > 0) socketConnection.startCompression();
        }
        final GameProfile finalProfile = finalGameProfile(connection, gameProfile);
        if (finalProfile == null) return gameProfile;
        gameProfile = finalProfile;
        // Publish the final profile before the client could possibly respond
        if (connection instanceof PlayerSocketConnection socketConnection) {
            socketConnection.UNSAFE_setProfile(gameProfile);
        }
        // Send login success packet (and switch to configuration phase)
        connection.sendPacket(new LoginSuccessPacket(gameProfile, new UUID(0L, 0L)));
        return gameProfile;
    }

    private static @Nullable GameProfile finalGameProfile(PlayerConnection connection,
                                                           GameProfile gameProfile) {
        // Call pre login event
        LoginPluginMessageProcessor pluginMessageProcessor = connection.loginPluginMessageProcessor();
        AsyncPlayerPreLoginEvent asyncPlayerPreLoginEvent = new AsyncPlayerPreLoginEvent(connection, gameProfile, pluginMessageProcessor);
        EventDispatcher.call(asyncPlayerPreLoginEvent);
        if (!connection.isOnline()) return null; // Player has been kicked
        // Change UUID/Username based on the event
        gameProfile = asyncPlayerPreLoginEvent.getGameProfile();
        // Wait for pending login plugin messages
        try {
            pluginMessageProcessor.awaitReplies(ServerFlag.LOGIN_PLUGIN_MESSAGE_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            connection.kick(LoginListener.INVALID_PROXY_RESPONSE);
            throw new RuntimeException("Error getting replies for login plugin messages", t);
        }
        return gameProfile;
    }

    @ApiStatus.Internal
    public void transitionPlayToConfig(Player player) {
        configWaitingPlayers.relaxedOffer(player);
    }

    /**
     * Return value exposed for testing
     */
    @ApiStatus.Internal
    public void doConfiguration(Player player, boolean isFirstConfig) {
        assert ServerFlag.INSIDE_TEST || Thread.currentThread().isVirtual();
        if (!player.isOnline()) return;
        if (isFirstConfig) {
            configurationPlayers.add(player);
            keepAlivePlayers.add(player);
            if (!player.isOnline()) {
                configurationPlayers.remove(player);
                keepAlivePlayers.remove(player);
                return;
            }
        }
        player.sendPacket(PluginMessagePacket.brandPacket(MinecraftServer.getBrandName()));
        // Request known packs immediately, but don't wait for the response until required (sending registry data).
        final var knownPacksFuture = player.getPlayerConnection().requestKnownPacks(List.of(SelectKnownPacksPacket.MINECRAFT_CORE));

        var event = new AsyncPlayerConfigurationEvent(player, isFirstConfig);
        EventDispatcher.call(event);
        if (!player.isOnline()) return; // Player was kicked during config.

        // send player features that were enabled or disabled during async config event
        player.sendPacket(new UpdateEnabledFeaturesPacket(event.getFeatureFlags().stream().map(StaticProtocolObject::name).toList()));

        final Instance spawningInstance = event.getSpawningInstance();
        Objects.requireNonNull(spawningInstance, "You need to specify a spawning instance in the AsyncPlayerConfigurationEvent");

        if (event.willClearChat()) player.sendPacket(new ResetChatPacket());

        // Registry data (if it should be sent)
        if (event.willSendRegistryData()) {
            List<SelectKnownPacksPacket.Entry> knownPacks;
            try {
                knownPacks = knownPacksFuture.get(ServerFlag.KNOWN_PACKS_RESPONSE_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | TimeoutException _) {
                LOGGER.warn("Player {} failed to respond to known packs query", player.getUsername());
                player.getPlayerConnection().disconnect();
                return;
            } catch (ExecutionException e) {
                throw new RuntimeException("Error receiving known packs", e);
            }
            boolean excludeVanilla = knownPacks.contains(SelectKnownPacksPacket.MINECRAFT_CORE);

            Registries registries = MinecraftServer.getRegistries();
            player.sendPackets(Registries.registryDataPackets(registries, excludeVanilla));
            // TODO: TEST_ENVIRONMENT, TEST_INSTANCE

            sendRegistryTags(player);
        }

        // Wait for pending resource packs if any
        final var packFuture = player.getResourcePackFuture();
        if (packFuture != null) {
            try {
                packFuture.get();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException exception) {
                throw new RuntimeException("Error receiving resource pack response", exception);
            }
        }

        keepAlivePlayers.remove(player);
        player.setPendingOptions(spawningInstance, event.isHardcore());
        player.sendPacket(new FinishConfigurationPacket());
    }

    @ApiStatus.Internal
    public void transitionConfigToPlay(Player player) {
        this.playWaitingPlayers.relaxedOffer(player);
    }

    /**
     * Removes a {@link Player} from the players list.
     * <p>
     * Used during disconnection, you shouldn't have to do it manually.
     *
     * @param connection the player connection
     * @see PlayerConnection#disconnect() to properly disconnect a player
     */
    @ApiStatus.Internal
    public synchronized void removePlayer(PlayerConnection connection) {
        final Player player = this.connectionPlayerMap.remove(connection);
        if (player == null) return;
        this.configurationPlayers.remove(player);
        this.playPlayers.remove(player);
        this.keepAlivePlayers.remove(player);
    }

    /**
     * Shutdowns the connection manager by kicking all the currently connected players.
     */
    public synchronized void shutdown() {
        acceptingAdmissions = false;
        for (final AdmissionState state : List.copyOf(playerAdmissions.values())) {
            try {
                disconnectOnShutdown(state.connection);
            } finally {
                state.operation.cancel(true);
            }
        }
        for (final PlayerConnection configPlayer : connectionPlayerMap.keySet())
            disconnectOnShutdown(configPlayer);
        this.configurationPlayers.clear();
        for (final Player playPlayer : playPlayers)
            disconnectOnShutdown(playPlayer.getPlayerConnection());
        this.playPlayers.clear();

        this.keepAlivePlayers.clear();
        this.connectionPlayerMap.clear();
    }

    private static void disconnectOnShutdown(PlayerConnection connection) {
        if (!connection.isOnline()) return;
        try {
            connection.kick(SHUTDOWN_TEXT);
        } catch (Throwable throwable) {
            LOGGER.error("Failed to kick connection during shutdown", throwable);
            try {
                connection.disconnect();
            } catch (Throwable disconnectFailure) {
                if (throwable != disconnectFailure) throwable.addSuppressed(disconnectFailure);
                LOGGER.error("Failed to disconnect connection during shutdown", disconnectFailure);
            }
        }
    }

    public void tick(long tickStart) {
        // Let waiting players into their instances
        updateWaitingPlayers();

        // Send keep alive packets
        handleKeepAlive(keepAlivePlayers, tickStart);

        // Interpret packets for configuration players
        configurationPlayers.forEach(Player::interpretPacketQueue);
    }

    /**
     * Connects waiting players.
     */
    @ApiStatus.Internal
    public void updateWaitingPlayers() {
        this.configWaitingPlayers.drain(player -> {
            // In case the method was called multiple times, the player disconnected, etc. just ignore it.
            if (!playPlayers.remove(player)) return;

            configurationPlayers.add(player);
            player.remove(false);
            player.sendPacket(new StartConfigurationPacket());
        });
        this.playWaitingPlayers.drain(player -> {
            if (!player.isOnline()) return; // Player disconnected while in queued to join
            configurationPlayers.remove(player);
            playPlayers.add(player);
            keepAlivePlayers.add(player);

            // This fixes a bug with Geyser. They do not reply to keep alive during config, meaning that
            // `Player#didAnswerKeepAlive()` will always be false when entering the play state, so a new keep
            // alive will never be sent and they will disconnect themselves or we will kick them for not replying.
            player.refreshAnswerKeepAlive(true);

            // Spawn the player at Player#getRespawnPoint
            CompletableFuture<Void> spawnFuture = player.UNSAFE_init();

            // Required to get the exact moment the player spawns
            if (ServerFlag.INSIDE_TEST) spawnFuture.join();
        });
    }

    /**
     * Updates keep alive by checking the last keep alive packet and send a new one if needed.
     *
     * @param tickStart the time of the update in nanoseconds, forwarded to the packet
     */
    private static void handleKeepAlive(Collection<Player> playerGroup, long tickStart) {
        final KeepAlivePacket keepAlivePacket = new KeepAlivePacket(tickStart);
        for (Player player : playerGroup) {
            final long lastKeepAlive = tickStart - player.getLastKeepAlive();
            if (lastKeepAlive > TimeUnit.MILLISECONDS.toNanos(ServerFlag.KEEP_ALIVE_DELAY) && player.didAnswerKeepAlive()) {
                player.refreshKeepAlive(tickStart);
                player.sendPacket(keepAlivePacket);
            } else if (lastKeepAlive >= TimeUnit.MILLISECONDS.toNanos(ServerFlag.KEEP_ALIVE_KICK)) {
                player.kick(TIMEOUT_TEXT);
            }
        }
    }

    private static final class AdmissionState {
        private final PlayerConnection connection;
        private final CompletableFuture<Player> operation = new CompletableFuture<>();
        private final CompletableFuture<Player> result = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (!operation.cancel(mayInterruptIfRunning)) return false;
                super.cancel(mayInterruptIfRunning);
                return true;
            }
        };
        private final AtomicReference<CompletableFuture<Void>> phase = new AtomicReference<>();

        private AdmissionState(PlayerConnection connection) {
            this.connection = connection;
        }

        private void cancelPhase() {
            final CompletableFuture<Void> current = phase.getAndSet(null);
            if (current != null) current.cancel(true);
        }
    }

    private record AdmissionIdentity(String username, UUID uuid) {
        private static AdmissionIdentity from(GameProfile profile) {
            return new AdmissionIdentity(profile.name().toLowerCase(Locale.ROOT), profile.uuid());
        }

        private boolean matches(Player player) {
            return username.equals(player.getUsername().toLowerCase(Locale.ROOT))
                    || uuid.equals(player.getUuid());
        }

        private boolean conflictsWith(AdmissionIdentity identity) {
            return username.equals(identity.username) || uuid.equals(identity.uuid);
        }
    }

}
