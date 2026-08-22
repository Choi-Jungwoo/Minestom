package net.minestom.server.bedrock;

import io.netty.buffer.UnpooledByteBufAllocator;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class BedrockChunkCodecIntegrationTest {
    @Test
    void encodesStructuredChunkSectionsWithoutJavaWireData(Env env) {
        var instance = env.createEmptyInstance();
        int minY = instance.getCachedDimensionType().minY();
        var chunk = instance.loadChunk(2, -3).join();
        instance.setBlock(new Pos(32, minY, -48), Block.STONE);

        var packet = BedrockChunkCodec.encodeChunk(
                UnpooledByteBufAllocator.DEFAULT,
                chunk,
                BedrockMappings.testing(env.process()),
                0);
        try {
            assertEquals(2, packet.getChunkX());
            assertEquals(-3, packet.getChunkZ());
            assertEquals(0, packet.getDimension());
            assertEquals(1, packet.getSubChunksLength());

            var data = packet.getData().duplicate();
            assertEquals(9, data.readUnsignedByte());
            assertEquals(1, data.readUnsignedByte());
            assertEquals(chunk.getMinSection() & 0xFF, data.readUnsignedByte());
            assertEquals(3, data.readUnsignedByte());
            assertEquals(0xfffffffe, data.readIntLE());
            data.skipBytes((128 - 1) * Integer.BYTES);
            assertEquals(2, VarInts.readInt(data));
            assertEquals(Block.STONE.stateId(), VarInts.readInt(data));
            assertEquals(Block.AIR.stateId(), VarInts.readInt(data));
        } finally {
            packet.release();
        }
    }
}
