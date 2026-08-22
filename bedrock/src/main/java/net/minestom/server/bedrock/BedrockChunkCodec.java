package net.minestom.server.bedrock;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.palette.Palette;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BedrockChunkCodec {
    private static final int SUB_CHUNK_VERSION = 9;
    private static final int[] BITS_PER_ENTRY = {0, 1, 2, 3, 4, 5, 6, 8, 16};

    private BedrockChunkCodec() {
    }

    static LevelChunkPacket encodeChunk(
            ByteBufAllocator allocator,
            Chunk chunk,
            BedrockMappings mappings,
            int dimensionId) {
        final ByteBuf data = allocator.buffer();
        try {
            final int subChunkCount = subChunkCount(chunk);
            for (int offset = 0; offset < subChunkCount; offset++) {
                final int sectionY = chunk.getMinSection() + offset;
                writeSubChunk(data, chunk.getSection(sectionY), sectionY, mappings);
            }
            for (int sectionY = chunk.getMinSection();
                 sectionY < chunk.getMaxSection();
                 sectionY++) {
                writeBiomeStorage(
                        data,
                        chunk.getSection(sectionY).biomePalette(),
                        chunk,
                        mappings);
            }
            data.writeByte(0); // Education Edition border blocks.

            return chunkPacket(
                    data,
                    chunk.getChunkX(),
                    chunk.getChunkZ(),
                    subChunkCount,
                    dimensionId);
        } catch (Throwable throwable) {
            data.release();
            throw throwable;
        }
    }

    static LevelChunkPacket encodeEmptyChunk(
            ByteBufAllocator allocator,
            int chunkX,
            int chunkZ,
            int sectionCount,
            int biomeId,
            int dimensionId) {
        final ByteBuf data = allocator.buffer();
        try {
            for (int section = 0; section < sectionCount; section++) {
                writeStorage(data, Palette.BIOME_DIMENSION, _ -> biomeId);
            }
            data.writeByte(0);

            return chunkPacket(data, chunkX, chunkZ, 0, dimensionId);
        } catch (Throwable throwable) {
            data.release();
            throw throwable;
        }
    }

    private static LevelChunkPacket chunkPacket(
            ByteBuf data,
            int chunkX,
            int chunkZ,
            int subChunkCount,
            int dimensionId) {
        final LevelChunkPacket packet = new LevelChunkPacket();
        packet.setChunkX(chunkX);
        packet.setChunkZ(chunkZ);
        packet.setSubChunksLength(subChunkCount);
        packet.setCachingEnabled(false);
        packet.setRequestSubChunks(false);
        packet.setDimension(dimensionId);
        packet.setData(data);
        return packet;
    }

    private static int subChunkCount(Chunk chunk) {
        for (int sectionY = chunk.getMaxSection() - 1;
             sectionY >= chunk.getMinSection();
             sectionY--) {
            if (!chunk.getSection(sectionY).blockPalette().isEmpty()) {
                return sectionY - chunk.getMinSection() + 1;
            }
        }
        return 0;
    }

    private static void writeSubChunk(
            ByteBuf output,
            Section section,
            int sectionY,
            BedrockMappings mappings) {
        output.writeByte(SUB_CHUNK_VERSION);
        output.writeByte(1);
        output.writeByte(sectionY);
        writeStorage(
                output,
                Palette.BLOCK_DIMENSION,
                index -> mappings.blockDefinition(
                        paletteValue(section.blockPalette(), Palette.BLOCK_DIMENSION, index))
                        .getRuntimeId());
    }

    private static void writeBiomeStorage(
            ByteBuf output,
            Palette biomes,
            Chunk chunk,
            BedrockMappings mappings) {
        writeStorage(
                output,
                Palette.BIOME_DIMENSION,
                index -> mappings.biomeId(
                        paletteValue(biomes, Palette.BIOME_DIMENSION, index),
                        chunk.getInstance().registries().biome()));
    }

    private static int paletteValue(Palette palette, int dimension, int index) {
        final int y = index & (dimension - 1);
        final int z = (index / dimension) & (dimension - 1);
        final int x = index / (dimension * dimension);
        return palette.get(x, y, z);
    }

    private static void writeStorage(
            ByteBuf output, int dimension, IntEntrySupplier entries) {
        final int size = dimension * dimension * dimension;
        final Map<Integer, Integer> paletteIndices = new LinkedHashMap<>();
        final List<Integer> palette = new ArrayList<>();
        final int[] indices = new int[size];
        for (int index = 0; index < size; index++) {
            final int runtimeId = entries.get(index);
            final Integer existing = paletteIndices.get(runtimeId);
            final int paletteIndex;
            if (existing == null) {
                paletteIndex = palette.size();
                paletteIndices.put(runtimeId, paletteIndex);
                palette.add(runtimeId);
            } else {
                paletteIndex = existing;
            }
            indices[index] = paletteIndex;
        }

        final int bits = bitsForPalette(palette.size());
        output.writeByte((bits << 1) | 1);
        if (bits != 0) {
            final int entriesPerWord = Integer.SIZE / bits;
            final int mask = (1 << bits) - 1;
            for (int offset = 0; offset < size; offset += entriesPerWord) {
                int word = 0;
                final int limit = Math.min(entriesPerWord, size - offset);
                for (int entry = 0; entry < limit; entry++) {
                    word |= (indices[offset + entry] & mask) << (entry * bits);
                }
                output.writeIntLE(word);
            }
            VarInts.writeInt(output, palette.size());
        }
        palette.forEach(runtimeId -> VarInts.writeInt(output, runtimeId));
    }

    private static int bitsForPalette(int size) {
        final int requiredBits = Integer.SIZE - Integer.numberOfLeadingZeros(size - 1);
        for (int bits : BITS_PER_ENTRY) {
            if (bits >= requiredBits) return bits;
        }
        throw new IllegalArgumentException("Bedrock palette contains too many entries: " + size);
    }

    @FunctionalInterface
    private interface IntEntrySupplier {
        int get(int index);
    }
}
