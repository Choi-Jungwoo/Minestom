package net.minestom.server.network;

import net.minestom.server.entity.Player;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PlayerAdmissionIntegrationTest {

    @Test
    void admitsFinalProfileOnceInOrder(Env env) {
        final ConnectionManager connectionManager = env.process().connection();
        final RecordingConnection connection = new RecordingConnection();
        final GameProfile candidate = profile("Candidate");
        final GameProfile rewritten = profile("Rewritten");
        final List<String> order = new ArrayList<>();
        final AtomicInteger providerCalls = new AtomicInteger();
        final AtomicReference<GameProfile> providerProfile = new AtomicReference<>();

        env.process().eventHandler().addListener(AsyncPlayerPreLoginEvent.class, event -> {
            order.add("pre-login");
            event.setGameProfile(rewritten);
        });
        connectionManager.setPlayerProvider((playerConnection, gameProfile) -> {
            order.add("provider");
            providerCalls.incrementAndGet();
            providerProfile.set(gameProfile);
            return new Player(playerConnection, gameProfile);
        });

        final PlayerAdmission admission = new PlayerAdmission() {
            @Override
            public CompletableFuture<Void> accept(GameProfile gameProfile) {
                order.add("accept");
                assertSame(rewritten, gameProfile);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> prepare(Player player) {
                order.add("prepare");
                assertSame(player, connectionManager.getPlayer(connection));
                return CompletableFuture.completedFuture(null);
            }
        };

        final CompletableFuture<Player> result =
                connectionManager.admitPlayer(connection, candidate, admission);
        final Player player = result.join();

        assertEquals(List.of("pre-login", "accept", "provider", "prepare"), order);
        assertEquals(1, providerCalls.get());
        assertSame(rewritten, providerProfile.get());
        assertEquals(rewritten.uuid(), player.getUuid());
        assertEquals(rewritten.name(), player.getUsername());
        assertSame(player, connection.getPlayer());
        assertSame(player, connectionManager.getPlayer(connection));
        assertFalse(result.cancel(true));
    }

    @Test
    void rejectionDoesNotCreatePlayer(Env env) {
        final ConnectionManager connectionManager = env.process().connection();
        final RecordingConnection connection = new RecordingConnection();
        final AtomicInteger providerCalls = new AtomicInteger();
        final AtomicInteger adapterCalls = new AtomicInteger();

        env.process().eventHandler().addListener(AsyncPlayerPreLoginEvent.class,
                event -> event.getConnection().disconnect());
        connectionManager.setPlayerProvider((playerConnection, gameProfile) -> {
            providerCalls.incrementAndGet();
            return new Player(playerConnection, gameProfile);
        });

        final PlayerAdmission admission = admission(
                _ -> adapterCalls.incrementAndGet(),
                _ -> adapterCalls.incrementAndGet());

        assertThrows(CancellationException.class,
                () -> connectionManager.admitPlayer(connection, profile("Rejected"), admission).join());
        assertEquals(0, providerCalls.get());
        assertEquals(0, adapterCalls.get());
        assertNull(connection.getPlayer());
        assertNull(connectionManager.getPlayer(connection));
    }

    @Test
    void preparationFailureRollsBackCreatedPlayer(Env env) {
        final ConnectionManager connectionManager = env.process().connection();
        final RecordingConnection connection = new RecordingConnection();
        final AtomicInteger providerCalls = new AtomicInteger();

        connectionManager.setPlayerProvider((playerConnection, gameProfile) -> {
            providerCalls.incrementAndGet();
            return new Player(playerConnection, gameProfile);
        });
        final PlayerAdmission admission = new PlayerAdmission() {
            @Override
            public CompletableFuture<Void> accept(GameProfile gameProfile) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> prepare(Player player) {
                return CompletableFuture.failedFuture(new IllegalStateException("preparation failed"));
            }
        };

        assertThrows(CompletionException.class,
                () -> connectionManager.admitPlayer(connection, profile("Rollback"), admission).join());
        assertEquals(1, providerCalls.get());
        assertNull(connection.getPlayer());
        assertNull(connectionManager.getPlayer(connection));
        assertFalse(connection.isOnline());
    }

    @Test
    void rollbackPacketFailureStillCompletesAndCleansUp(Env env) {
        final ConnectionManager connectionManager = env.process().connection();
        final ThrowingConnection connection = new ThrowingConnection();
        connectionManager.setPlayerProvider(Player::new);
        final PlayerAdmission admission = new PlayerAdmission() {
            @Override
            public CompletableFuture<Void> accept(GameProfile gameProfile) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> prepare(Player player) {
                return CompletableFuture.failedFuture(new IllegalStateException("preparation failed"));
            }
        };

        final CompletionException exception = assertThrows(CompletionException.class, () -> connectionManager
                .admitPlayer(connection, profile("RollbackFailure"), admission)
                .orTimeout(5, TimeUnit.SECONDS)
                .join());

        assertNotNull(exception.getCause());
        assertEquals(1, exception.getCause().getSuppressed().length);
        assertFalse(connection.isOnline());
        assertNull(connection.getPlayer());
        assertNull(connectionManager.getPlayer(connection));
    }

    @Test
    void acceptanceTimeoutDoesNotCreatePlayer(Env env) {
        final ConnectionManager connectionManager = env.process().connection();
        final RecordingConnection connection = new RecordingConnection();
        final AtomicInteger providerCalls = new AtomicInteger();
        final CompletableFuture<Void> acceptance = new CompletableFuture<>();

        connectionManager.setPlayerProvider((playerConnection, gameProfile) -> {
            providerCalls.incrementAndGet();
            return new Player(playerConnection, gameProfile);
        });
        final PlayerAdmission admission = new PlayerAdmission() {
            @Override
            public CompletableFuture<Void> accept(GameProfile gameProfile) {
                return acceptance;
            }

            @Override
            public CompletableFuture<Void> prepare(Player player) {
                return CompletableFuture.completedFuture(null);
            }
        };

        assertThrows(CompletionException.class, () -> connectionManager
                .admitPlayer(connection, profile("Timeout"), admission, 1, TimeUnit.MILLISECONDS)
                .join());
        assertEquals(0, providerCalls.get());
        assertTrue(acceptance.isCancelled());
        assertNull(connection.getPlayer());
        assertNull(connectionManager.getPlayer(connection));
        assertFalse(connection.isOnline());
    }

    @Test
    void duplicateAdmissionCannotInvokeProviderTwice(Env env) {
        final ConnectionManager connectionManager = env.process().connection();
        final RecordingConnection connection = new RecordingConnection();
        final CompletableFuture<Void> accepted = new CompletableFuture<>();
        final AtomicInteger providerCalls = new AtomicInteger();

        connectionManager.setPlayerProvider((playerConnection, gameProfile) -> {
            providerCalls.incrementAndGet();
            return new Player(playerConnection, gameProfile);
        });
        final PlayerAdmission admission = new PlayerAdmission() {
            @Override
            public CompletableFuture<Void> accept(GameProfile gameProfile) {
                return accepted;
            }

            @Override
            public CompletableFuture<Void> prepare(Player player) {
                return CompletableFuture.completedFuture(null);
            }
        };

        final CompletableFuture<Player> first =
                connectionManager.admitPlayer(connection, profile("First"), admission);
        final CompletableFuture<Player> duplicate =
                connectionManager.admitPlayer(connection, profile("Duplicate"), admission);

        assertThrows(CompletionException.class, duplicate::join);
        assertTrue(connection.isOnline());
        accepted.complete(null);
        assertSame(first.join(), connectionManager.getPlayer(connection));
        assertEquals(1, providerCalls.get());
    }

    @Test
    void cancellationDuringPreparationRollsBackPlayer(Env env) {
        final ConnectionManager connectionManager = env.process().connection();
        final RecordingConnection connection = new RecordingConnection();
        final CompletableFuture<Player> preparing = new CompletableFuture<>();
        final CompletableFuture<Void> preparation = new CompletableFuture<>();

        connectionManager.setPlayerProvider(Player::new);
        final PlayerAdmission admission = new PlayerAdmission() {
            @Override
            public CompletableFuture<Void> accept(GameProfile gameProfile) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> prepare(Player player) {
                preparing.complete(player);
                return preparation;
            }
        };

        final CompletableFuture<Player> result =
                connectionManager.admitPlayer(connection, profile("Cancelled"), admission);
        final Player player = preparing.join();
        assertSame(player, connectionManager.getPlayer(connection));

        assertTrue(result.cancel(true));

        assertTrue(preparation.isCancelled());
        assertNull(connection.getPlayer());
        assertNull(connectionManager.getPlayer(connection));
        assertFalse(connection.isOnline());
    }

    @Test
    void disconnectDuringPreparationRollsBackPlayer(Env env) {
        final ConnectionManager connectionManager = env.process().connection();
        final RecordingConnection connection = new RecordingConnection();
        final CompletableFuture<Player> preparing = new CompletableFuture<>();
        final CompletableFuture<Void> preparation = new CompletableFuture<>();

        connectionManager.setPlayerProvider(Player::new);
        final PlayerAdmission admission = new PlayerAdmission() {
            @Override
            public CompletableFuture<Void> accept(GameProfile gameProfile) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> prepare(Player player) {
                preparing.complete(player);
                return preparation;
            }
        };

        final CompletableFuture<Player> result =
                connectionManager.admitPlayer(connection, profile("Disconnected"), admission);
        preparing.join();
        connection.disconnect();

        assertThrows(CancellationException.class, result::join);
        assertTrue(preparation.isCancelled());
        assertNull(connection.getPlayer());
        assertNull(connectionManager.getPlayer(connection));
        assertFalse(connection.isOnline());
    }

    @Test
    void shutdownCancelsPendingAdmission(Env env) {
        final ConnectionManager connectionManager = env.process().connection();
        final RecordingConnection connection = new RecordingConnection();
        final CompletableFuture<Void> acceptance = new CompletableFuture<>();
        final CompletableFuture<Void> acceptanceStarted = new CompletableFuture<>();
        final PlayerAdmission admission = new PlayerAdmission() {
            @Override
            public CompletableFuture<Void> accept(GameProfile gameProfile) {
                acceptanceStarted.complete(null);
                return acceptance;
            }

            @Override
            public CompletableFuture<Void> prepare(Player player) {
                return CompletableFuture.completedFuture(null);
            }
        };

        final CompletableFuture<Player> result =
                connectionManager.admitPlayer(connection, profile("Shutdown"), admission);
        acceptanceStarted.orTimeout(5, TimeUnit.SECONDS).join();
        connectionManager.shutdown();

        assertThrows(CancellationException.class, result::join);
        assertTrue(acceptance.isCancelled());
        assertFalse(connection.isOnline());
        assertNull(connection.getPlayer());
        assertNull(connectionManager.getPlayer(connection));

        final AtomicInteger adapterCalls = new AtomicInteger();
        final CompletableFuture<Player> rejected = connectionManager.admitPlayer(
                new RecordingConnection(), profile("AfterShutdown"),
                admission(_ -> adapterCalls.incrementAndGet(), _ -> adapterCalls.incrementAndGet()));
        assertThrows(CompletionException.class, rejected::join);
        assertEquals(0, adapterCalls.get());
    }

    private static PlayerAdmission admission(Consumer<GameProfile> accept,
                                             Consumer<Player> prepare) {
        return new PlayerAdmission() {
            @Override
            public CompletableFuture<Void> accept(GameProfile gameProfile) {
                accept.accept(gameProfile);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> prepare(Player player) {
                prepare.accept(player);
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private static GameProfile profile(String name) {
        return new GameProfile(UUID.randomUUID(), name);
    }

    private static class RecordingConnection extends PlayerConnection {
        @Override
        public void sendPacket(SendablePacket packet) {
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }
    }

    private static final class ThrowingConnection extends RecordingConnection {
        @Override
        public void sendPacket(SendablePacket packet) {
            throw new IllegalStateException("send failed");
        }
    }
}
