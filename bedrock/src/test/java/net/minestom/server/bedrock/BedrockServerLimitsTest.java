package net.minestom.server.bedrock;

import net.minestom.server.MinecraftServer;
import org.cloudburstmc.netty.channel.raknet.RakConstants;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BedrockServerLimitsTest {
    @Test
    void configOwnsListenerCompatibilityAndAdvertisementPolicy() {
        final var address =
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 19132);
        final var process = MinecraftServer.updateProcess();
        try {
            final var instance = process.instance().createInstanceContainer();
            final var mappingsSource = Path.of("run/bedrock-mappings");
            final var limits = BedrockServerLimits.defaults();
            final var versions =
                    new BedrockVersionPolicy(1001, List.of(1001, 2169), Set.of(1001));
            final var advertisement =
                    new BedrockAdvertisement("MCPE", "Configured", "Creative", true);

            final var config = new BedrockServerConfig(
                    address,
                    instance,
                    mappingsSource,
                    42,
                    limits,
                    versions,
                    advertisement);

            assertEquals(address, config.address());
            assertSame(instance, config.spawningInstance());
            assertEquals(mappingsSource.toAbsolutePath(), config.mappingsSource());
            assertEquals(42, config.rakNetGuid());
            assertSame(limits, config.limits());
            assertSame(versions, config.versionPolicy());
            assertSame(advertisement, config.advertisement());
        } finally {
            process.stop();
        }
    }

    @Test
    void defaultsBoundEveryRequiredNetworkResource() {
        final BedrockServerLimits limits = BedrockServerLimits.defaults();

        assertTrue(limits.maxConnections() > 0);
        assertTrue(limits.maxConnectionAttemptsPerSecond() > 0);
        assertTrue(limits.maxMtu() > 0);
        assertTrue(limits.maxPacketBytes() > 0);
        assertTrue(limits.maxCompressedBatchBytes() > 0);
        assertTrue(limits.maxDecompressedBatchBytes() > 0);
        assertTrue(limits.maxPacketsPerTick() > 0);
        assertTrue(limits.maxJwtBytes() > 0);
        assertTrue(limits.maxCapeBytes() > 0);
        assertTrue(limits.maxGeometryBytes() > 0);
    }

    @Test
    void rejectsPromotingAnExperimentalCodecToSupported() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BedrockVersionPolicy(
                        2169,
                        List.of(1001, 2169),
                        Set.of(1001, 2169)));
    }

    @Test
    void rejectsNonPositiveAndInconsistentLimits() {
        final BedrockServerLimits defaults = BedrockServerLimits.defaults();

        assertThrows(IllegalArgumentException.class, () -> new BedrockServerLimits(
                0,
                defaults.maxConnectionAttemptsPerSecond(),
                defaults.maxMtu(),
                defaults.maxPacketBytes(),
                defaults.maxCompressedBatchBytes(),
                defaults.maxDecompressedBatchBytes(),
                defaults.maxPacketsPerTick(),
                defaults.maxJwtBytes()));
        assertThrows(IllegalArgumentException.class, () -> new BedrockServerLimits(
                defaults.maxConnections(),
                defaults.maxConnectionAttemptsPerSecond(),
                defaults.maxMtu(),
                defaults.maxPacketBytes(),
                defaults.maxCompressedBatchBytes(),
                defaults.maxPacketBytes() - 1,
                defaults.maxPacketsPerTick(),
                defaults.maxJwtBytes()));
        assertThrows(IllegalArgumentException.class, () -> new BedrockServerLimits(
                defaults.maxConnections(),
                defaults.maxConnectionAttemptsPerSecond(),
                RakConstants.MINIMUM_MTU_SIZE - 1,
                defaults.maxPacketBytes(),
                defaults.maxCompressedBatchBytes(),
                defaults.maxDecompressedBatchBytes(),
                defaults.maxPacketsPerTick(),
                defaults.maxJwtBytes()));
        assertThrows(IllegalArgumentException.class, () -> new BedrockServerLimits(
                defaults.maxConnections(),
                defaults.maxConnectionAttemptsPerSecond(),
                RakConstants.MAXIMUM_MTU_SIZE + 1,
                defaults.maxPacketBytes(),
                defaults.maxCompressedBatchBytes(),
                defaults.maxDecompressedBatchBytes(),
                defaults.maxPacketsPerTick(),
                defaults.maxJwtBytes()));
    }
}
