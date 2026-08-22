package net.minestom.server.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BedrockServerLimitsTest {
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
    }
}
