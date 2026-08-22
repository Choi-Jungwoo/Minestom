package net.minestom.server.bedrock;

import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BedrockServerLifecycleTest {
    @TempDir
    Path mappingsDirectory;

    private ServerProcess process;
    private BedrockServer server;
    private boolean processStopped;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
        if (process != null && !processStopped) process.stop();
    }

    @Test
    void startAndStopAreIdempotentAndReleaseThePort() throws Exception {
        process = MinecraftServer.updateProcess();
        server = BedrockServer.createForTesting(
                process, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));

        assertSame(server, BedrockServer.createForTesting(process, server.configuredAddress()));
        assertFalse(server.isStarted());

        assertDoesNotThrow(server::start);
        assertDoesNotThrow(server::start);
        assertTrue(server.isStarted());
        int port = server.boundAddress().getPort();

        assertDoesNotThrow(server::stop);
        assertDoesNotThrow(server::stop);
        assertFalse(server.isStarted());

        try (var socket = new DatagramSocket(port, InetAddress.getLoopbackAddress())) {
            assertTrue(socket.isBound());
        }

        server.start();
        int restartedPort = server.boundAddress().getPort();
        server.stop();
        try (var socket = new DatagramSocket(restartedPort, InetAddress.getLoopbackAddress())) {
            assertTrue(socket.isBound());
        }
    }

    @Test
    void processShutdownReleasesThePortAndEventLoopThreads() throws Exception {
        Set<Long> existingThreads = bedrockEventLoopThreads();
        process = MinecraftServer.updateProcess();
        server = BedrockServer.createForTesting(
                process, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.start();
        int port = server.boundAddress().getPort();
        assertTrue(bedrockEventLoopThreads().size() > existingThreads.size());

        process.stop();
        processStopped = true;

        assertFalse(server.isStarted());
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (!bedrockEventLoopThreads().equals(existingThreads)) {
                Thread.sleep(10);
            }
        });
        assertEquals(existingThreads, bedrockEventLoopThreads());
        try (var socket = new DatagramSocket(port, InetAddress.getLoopbackAddress())) {
            assertTrue(socket.isBound());
        }
    }

    @Test
    void invalidOperatorMappingsPreventStartupBeforeBindingUdp() {
        process = MinecraftServer.updateProcess();
        var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        var instance = process.instance().createInstanceContainer();
        server = BedrockServer.create(
                process, new BedrockServerConfig(address, instance, mappingsDirectory));

        assertThrows(IllegalStateException.class, server::start);
        assertFalse(server.isStarted());
    }

    @Test
    void additiveLimitsOverloadKeepsProcessSingletonConfigurationStable() {
        process = MinecraftServer.updateProcess();
        final var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        final var config = new BedrockServerConfig(
                address,
                process.instance().createInstanceContainer(),
                mappingsDirectory);
        final BedrockServerLimits limits = new BedrockServerLimits(
                8,
                4,
                1_200,
                16_384,
                32_768,
                65_536,
                32,
                16_384);

        server = BedrockServer.create(process, config, limits);

        assertSame(server, BedrockServer.create(process, config, limits));
        assertThrows(
                IllegalStateException.class,
                () -> BedrockServer.create(process, config, BedrockServerLimits.defaults()));
    }

    private static Set<Long> bedrockEventLoopThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith("minestom-bedrock-"))
                .map(Thread::threadId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
