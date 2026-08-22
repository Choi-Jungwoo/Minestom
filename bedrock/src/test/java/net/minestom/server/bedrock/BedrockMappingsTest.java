package net.minestom.server.bedrock;

import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BedrockMappingsTest {
    @TempDir
    Path directory;

    @Test
    void loadsAnExactVersionedMappingBundle() throws IOException {
        writeCompleteBundle();
        var release = releaseForCurrentContents();

        var mappings = BedrockMappings.load(directory, release);

        assertEquals("26.2", mappings.javaVersion());
        assertEquals("1.26.30.5", mappings.bedrockVersion());
        assertEquals(release.sha256(), mappings.sha256());
        assertEquals(1, mappings.blockStateCount());
        assertEquals(1, mappings.itemCount());
        assertEquals(1, mappings.biomeCount());
        assertEquals(0, mappings.blockDefinition(0).getRuntimeId());
        assertEquals(
                "minecraft:air",
                assertInstanceOf(
                        SimpleBlockDefinition.class,
                        mappings.blockDefinition(0)).getIdentifier());
    }

    @Test
    void preparesAValidatedMappingDirectoryWithoutChangingItsIdentity() throws IOException {
        writeCompleteBundle();
        var release = releaseForCurrentContents();
        var destination = directory.resolve("prepared");

        BedrockMaintenanceTool.prepare(directory, destination, release);

        var mappings = BedrockMappings.load(destination, release);
        assertEquals(release.javaVersion(), mappings.javaVersion());
        assertEquals(release.bedrockVersion(), mappings.bedrockVersion());
        assertEquals(release.sha256(), mappings.sha256());
        assertEquals(1, mappings.blockStateCount());
        assertEquals(1, mappings.itemCount());
        assertEquals(1, mappings.biomeCount());
    }

    @Test
    void rejectsAChangedMappingBundle() throws IOException {
        writeCompleteBundle();
        var release = releaseForCurrentContents();
        Files.writeString(directory.resolve("items.json"), """
                {"minecraft:air":{"bedrock_identifier":"minecraft:changed"}}
                """);

        assertThrows(IllegalArgumentException.class, () -> BedrockMappings.load(directory, release));
    }

    @Test
    void rejectsAReleaseWithoutCriticalRegistryCoverage() throws IOException {
        writeCompleteBundle();
        Files.writeString(directory.resolve("biomes.json"), "{}");
        var release = releaseForCurrentContents();

        assertThrows(IllegalArgumentException.class, () -> BedrockMappings.load(directory, release));
    }

    @Test
    void rejectsMalformedCriticalNbtEvenWhenTheChecksumMatches() throws IOException {
        writeCompleteBundle();
        Files.write(directory.resolve("blocks.nbt"), new byte[]{1, 2, 3});
        var release = releaseForCurrentContents();

        assertThrows(IOException.class, () -> BedrockMappings.load(directory, release));
    }

    @Test
    void rejectsAnEmptyRequiredRegistryEvenWhenTheChecksumMatches() throws IOException {
        writeCompleteBundle();
        Files.writeString(directory.resolve("effects.json"), "{}");
        var release = releaseForCurrentContents();

        assertThrows(IllegalArgumentException.class, () -> BedrockMappings.load(directory, release));
    }

    @Test
    void rejectsMismatchedBlockRegistryCoverage() throws IOException {
        writeCompleteBundle();
        writeNbt("collisions.nbt", NbtMap.fromMap(Map.of(
                "indices", new int[]{0, 0},
                "shapes", new NbtList<>(NbtType.INT, 0))));
        var release = releaseForCurrentContents();

        assertThrows(IllegalArgumentException.class, () -> BedrockMappings.load(directory, release));
    }

    @Test
    void rejectsMissingBedrockRuntimeBlockState() throws IOException {
        writeCompleteBundle();
        writeRuntimePalette(NbtMap.fromMap(Map.of(
                "name", "minecraft:stone",
                "states", NbtMap.EMPTY)));
        var release = releaseForCurrentContents();

        assertThrows(IllegalArgumentException.class, () -> BedrockMappings.load(directory, release));
    }

    private void writeCompleteBundle() throws IOException {
        Files.writeString(directory.resolve("additional_offhand_items.json"), "[]");
        Files.writeString(directory.resolve("effects.json"), "{\"entry\":{}}");
        Files.writeString(directory.resolve("interactions.json"), "{\"entry\":[]}");
        Files.writeString(directory.resolve("item_data_components.json"),
                "[{\"id\":0,\"key\":\"minecraft:air\",\"components\":{}}]");
        Files.writeString(directory.resolve("particles.json"), "{\"entry\":{}}");
        Files.writeString(directory.resolve("resolvable_item_data_components.json"),
                "{\"value\":[]}");
        Files.writeString(directory.resolve("sounds.json"), "{\"entry\":{}}");
        Files.writeString(directory.resolve("util.json"), "{\"entry\":[]}");
        writeNbt("blocks.nbt", NbtMap.fromMap(Map.of(
                "bedrock_mappings", new NbtList<>(NbtType.COMPOUND, NbtMap.EMPTY))));
        writeRuntimePalette(NbtMap.fromMap(Map.of(
                "name", "minecraft:air",
                "states", NbtMap.EMPTY)));
        for (String file : List.of("block_shapes.nbt", "collisions.nbt")) {
            writeNbt(file, NbtMap.fromMap(Map.of(
                    "indices", new int[]{0},
                    "shapes", new NbtList<>(NbtType.INT, 0))));
        }
        writeNbt("item_components.nbt", NbtMap.fromMap(Map.of(
                "minecraft:air", NbtMap.EMPTY)));
        Files.writeString(directory.resolve("README.md"), """
                Generated for Minecraft: Java Edition 26.2 and Minecraft: Bedrock Edition 1.26.30.5.
                """);
        Files.writeString(directory.resolve("LICENSE"), "test fixture");
        Files.writeString(directory.resolve("biomes.json"), """
                {"minecraft:plains":{"bedrock_id":1}}
                """);
        Files.writeString(directory.resolve("items.json"), """
                {"minecraft:air":{"bedrock_identifier":"minecraft:air"}}
                """);
    }

    private void writeNbt(String file, NbtMap root) throws IOException {
        try (var output = Files.newOutputStream(directory.resolve(file));
             var writer = NbtUtils.createGZIPWriter(output)) {
            writer.writeTag(root);
        }
    }

    private void writeRuntimePalette(NbtMap... states) throws IOException {
        try (var output = Files.newOutputStream(directory.resolve("block_palette.26_30.nbt"));
             var gzip = new GZIPOutputStream(output);
             var writer = NbtUtils.createWriter(gzip)) {
            writer.writeTag(NbtMap.fromMap(Map.of(
                    "blocks", new NbtList<>(NbtType.COMPOUND, states))));
        }
    }

    private BedrockMappings.Release releaseForCurrentContents() throws IOException {
        return new BedrockMappings.Release(
                "26.2",
                "1.26.30.5",
                BedrockMappings.sha256(directory, List.of(
                        "LICENSE",
                        "README.md",
                        "additional_offhand_items.json",
                        "biomes.json",
                        "block_shapes.nbt",
                        "blocks.nbt",
                        "collisions.nbt",
                        "effects.json",
                        "interactions.json",
                        "item_components.nbt",
                        "item_data_components.json",
                        "items.json",
                        "particles.json",
                        "resolvable_item_data_components.json",
                        "sounds.json",
                        "util.json")),
                BedrockMappings.sha256(directory.resolve("block_palette.26_30.nbt")));
    }
}
