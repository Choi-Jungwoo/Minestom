package net.minestom.server.network;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.listener.preplay.LoginListener;
import net.minestom.server.network.packet.client.configuration.ClientFinishConfigurationPacket;
import net.minestom.server.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import net.minestom.server.network.packet.client.login.ClientLoginAcknowledgedPacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.configuration.FinishConfigurationPacket;
import net.minestom.server.network.packet.server.configuration.SelectKnownPacksPacket;
import net.minestom.server.network.packet.server.login.LoginSuccessPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerSocketConnection;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class ConnectionManagerIntegrationTest {

    private GameProfile[] profiles;

    @BeforeEach
    public void setup(Env env) {
        profiles = new GameProfile[]{
                new GameProfile(UUID.randomUUID(), "Minestom"),
                new GameProfile(UUID.randomUUID(), "Notch")};
    }

    @Test
    public void testPartialFind(Env env) {
        Instance instance = env.createEmptyInstance();
        Player minestomPlayer = env.createConnection(profiles[0]).connect(instance, Pos.ZERO);
        ConnectionManager connectionManager = env.process().connection();

        assertEquals(minestomPlayer, connectionManager.findOnlinePlayer("Mine"));
        assertNull(connectionManager.findOnlinePlayer("No"));

        Player notchPlayer = env.createConnection(profiles[1]).connect(instance, Pos.ZERO);

        assertEquals(minestomPlayer, connectionManager.findOnlinePlayer("Mine"));
        assertEquals(notchPlayer, connectionManager.findOnlinePlayer("No"));
        assertNull(connectionManager.findOnlinePlayer("leo"));
    }

    @Test
    public void profileIsPublishedBeforeLoginSuccess(Env env) throws IOException {
        final GameProfile profile = profiles[0];

        try (SocketChannel channel = SocketChannel.open()) {
            final var connection = new ProfileCapturingConnection(channel);
            connection.setClientState(ConnectionState.LOGIN);

            final CompletableFuture<GameProfile> future = new CompletableFuture<>();
            Thread.startVirtualThread(() -> {
                try {
                    future.complete(env.process().connection().transitionLoginToConfig(connection, profiles[0]));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            final GameProfile result = future.join();

            assertSame(profile, result);
            assertSame(profile, connection.profileWhenLoginSuccessSent.join());
            assertSame(profile, connection.gameProfile());
        }
    }

    @Test
    public void javaLoginUsesFinalPreLoginProfileOnce(Env env) throws IOException {
        final ConnectionManager connectionManager = env.process().connection();
        final Instance instance = env.createEmptyInstance();
        final GameProfile candidate = profiles[0];
        final GameProfile rewritten = new GameProfile(UUID.randomUUID(), "Rewritten");
        final AtomicInteger providerCalls = new AtomicInteger();
        final AtomicReference<GameProfile> providerProfile = new AtomicReference<>();
        final List<String> order = new CopyOnWriteArrayList<>();

        env.process().eventHandler().addListener(AsyncPlayerPreLoginEvent.class, event -> {
            order.add("pre-login");
            event.setGameProfile(rewritten);
        });
        env.process().eventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            order.add("configuration");
            event.setSpawningInstance(instance);
        });
        connectionManager.setPlayerProvider((connection, profile) -> {
            order.add("provider");
            providerCalls.incrementAndGet();
            providerProfile.set(profile);
            return new Player(connection, profile);
        });

        try (SocketChannel channel = SocketChannel.open()) {
            final var connection = new AutoRespondingConnection(channel, order);
            connection.setClientState(ConnectionState.LOGIN);

            LoginListener.loginStartListener(
                    new ClientLoginStartPacket(candidate.name(), candidate.uuid()), connection);

            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (connectionManager.getOnlinePlayerCount() == 0 && System.nanoTime() < deadline) {
                connectionManager.updateWaitingPlayers();
                Thread.onSpinWait();
            }

            assertEquals(1, connectionManager.getOnlinePlayerCount());
            assertEquals(1, providerCalls.get());
            assertSame(rewritten, providerProfile.get());
            assertSame(rewritten, connection.loginProfile.join());
            assertEquals(List.of(
                    "pre-login", "login-success", "provider",
                    "configuration", "finish-configuration"), order);
        }
    }

    @Test
    public void javaDisconnectDuringPreparationRollsBackPlayer(Env env) throws IOException {
        final ConnectionManager connectionManager = env.process().connection();
        final Instance instance = env.createEmptyInstance();
        final GameProfile profile = profiles[0];
        final AtomicInteger providerCalls = new AtomicInteger();

        env.process().eventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().getPlayerConnection().disconnect();
        });
        connectionManager.setPlayerProvider((connection, gameProfile) -> {
            providerCalls.incrementAndGet();
            return new Player(connection, gameProfile);
        });

        try (SocketChannel channel = SocketChannel.open()) {
            final var connection = new AutoRespondingConnection(
                    channel, new CopyOnWriteArrayList<>());
            connection.setClientState(ConnectionState.LOGIN);

            LoginListener.loginStartListener(
                    new ClientLoginStartPacket(profile.name(), profile.uuid()), connection);

            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((connection.isOnline() || connection.getPlayer() != null)
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }

            assertFalse(connection.isOnline());
            assertEquals(1, providerCalls.get());
            assertNull(connection.getPlayer());
            assertNull(connectionManager.getPlayer(connection));
            assertTrue(connectionManager.getConfigPlayers().isEmpty());
            assertEquals(0, connectionManager.getOnlinePlayerCount());
        }
    }

    private static final class ProfileCapturingConnection extends PlayerSocketConnection {
        private final CompletableFuture<GameProfile> profileWhenLoginSuccessSent = new CompletableFuture<>();

        private ProfileCapturingConnection(SocketChannel channel) {
            super(channel, new InetSocketAddress("localhost", 25565),
                    Thread.currentThread(), Thread.currentThread());
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            if (packet instanceof LoginSuccessPacket) {
                // Model the socket reader handling an immediate acknowledgement before the login
                // thread resumes from sendPacket.
                Thread.startVirtualThread(() -> profileWhenLoginSuccessSent.complete(gameProfile()));
                profileWhenLoginSuccessSent.join();
            }
        }
    }

    private static final class AutoRespondingConnection extends PlayerSocketConnection {
        private final CompletableFuture<GameProfile> loginProfile = new CompletableFuture<>();
        private final List<String> order;

        private AutoRespondingConnection(SocketChannel channel, List<String> order) {
            super(channel, new InetSocketAddress("localhost", 25565),
                    Thread.currentThread(), Thread.currentThread());
            this.order = order;
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            switch (packet) {
                case LoginSuccessPacket loginSuccess -> {
                    order.add("login-success");
                    loginProfile.complete(loginSuccess.gameProfile());
                    Thread.startVirtualThread(() -> LoginListener.loginAckListener(
                            new ClientLoginAcknowledgedPacket(), this));
                }
                case SelectKnownPacksPacket _ -> Thread.startVirtualThread(() ->
                        LoginListener.selectKnownPacks(
                                new ClientSelectKnownPacksPacket(List.of()), getPlayer()));
                case FinishConfigurationPacket _ -> {
                    order.add("finish-configuration");
                    Thread.startVirtualThread(() -> LoginListener.finishConfigListener(
                            new ClientFinishConfigurationPacket(), getPlayer()));
                }
                default -> {
                }
            }
        }
    }

}
