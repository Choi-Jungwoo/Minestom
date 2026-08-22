package net.minestom.server.bedrock;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class BedrockCompressionTest {
    @Test
    void rejectsCompressedAndUncompressedBatchesBeforeInflation() {
        final BedrockServerLimits limits = new BedrockServerLimits(
                8,
                4,
                1_200,
                4,
                4,
                4,
                8,
                4);
        final BedrockCompression compression = new BedrockCompression(limits);
        final ByteBuf compressed = Unpooled.buffer(5).writeZero(5);
        final ByteBuf uncompressed = Unpooled.buffer(5).writeZero(5);
        try {
            assertThrows(
                    BedrockCompression.BedrockLimitException.class,
                    () -> compression.getDefaultCompression().decode(null, compressed));
            assertThrows(
                    BedrockCompression.BedrockLimitException.class,
                    () -> compression.getCompression(PacketCompressionAlgorithm.NONE)
                            .decode(null, uncompressed));
        } finally {
            compressed.release();
            uncompressed.release();
        }
    }
}
