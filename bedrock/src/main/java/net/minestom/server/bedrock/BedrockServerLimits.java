package net.minestom.server.bedrock;

import org.cloudburstmc.netty.channel.raknet.RakConstants;

/**
 * Hard resource limits applied by a {@link BedrockServer}.
 *
 * @param maxConnections                 total concurrent RakNet connections
 * @param maxConnectionAttemptsPerSecond accepted RakNet handshakes per source address per second
 * @param maxMtu                         maximum negotiated RakNet MTU
 * @param maxPacketBytes                 maximum uncompressed Bedrock packet size
 * @param maxCompressedBatchBytes        maximum compressed Bedrock batch size
 * @param maxDecompressedBatchBytes      maximum decompressed Bedrock batch size
 * @param maxPacketsPerTick              maximum packets processed per connection per tick window
 * @param maxJwtBytes                    maximum combined login JWT size
 */
public record BedrockServerLimits(
        int maxConnections,
        int maxConnectionAttemptsPerSecond,
        int maxMtu,
        int maxPacketBytes,
        int maxCompressedBatchBytes,
        int maxDecompressedBatchBytes,
        int maxPacketsPerTick,
        int maxJwtBytes) {
    private static final BedrockServerLimits DEFAULTS = new BedrockServerLimits(
            1_024,
            20,
            RakConstants.MAXIMUM_MTU_SIZE,
            1_048_576,
            2_097_152,
            8_388_608,
            256,
            1_048_576);

    public BedrockServerLimits {
        requirePositive(maxConnections, "maxConnections");
        requirePositive(maxConnectionAttemptsPerSecond, "maxConnectionAttemptsPerSecond");
        requirePositive(maxMtu, "maxMtu");
        requirePositive(maxPacketBytes, "maxPacketBytes");
        requirePositive(maxCompressedBatchBytes, "maxCompressedBatchBytes");
        requirePositive(maxDecompressedBatchBytes, "maxDecompressedBatchBytes");
        requirePositive(maxPacketsPerTick, "maxPacketsPerTick");
        requirePositive(maxJwtBytes, "maxJwtBytes");
        if (maxMtu < RakConstants.MINIMUM_MTU_SIZE
                || maxMtu > RakConstants.MAXIMUM_MTU_SIZE) {
            throw new IllegalArgumentException(
                    "maxMtu must be between "
                            + RakConstants.MINIMUM_MTU_SIZE
                            + " and "
                            + RakConstants.MAXIMUM_MTU_SIZE);
        }
        if (maxDecompressedBatchBytes < maxPacketBytes) {
            throw new IllegalArgumentException(
                    "maxDecompressedBatchBytes must be at least maxPacketBytes");
        }
    }

    /**
     * Returns the production defaults.
     *
     * @return immutable default limits
     */
    public static BedrockServerLimits defaults() {
        return DEFAULTS;
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
