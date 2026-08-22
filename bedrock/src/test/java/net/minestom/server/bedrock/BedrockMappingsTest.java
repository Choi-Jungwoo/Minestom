package net.minestom.server.bedrock;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private void writeCompleteBundle() throws IOException {
        for (String file : List.of(
                "additional_offhand_items.json",
                "effects.json",
                "interactions.json",
                "item_data_components.json",
                "particles.json",
                "resolvable_item_data_components.json",
                "sounds.json",
                "util.json")) {
            Files.writeString(directory.resolve(file), "{}");
        }
        for (String file : List.of(
                "block_shapes.nbt", "blocks.nbt", "collisions.nbt", "item_components.nbt")) {
            try (var output = Files.newOutputStream(directory.resolve(file));
                 var writer = NbtUtils.createGZIPWriter(output)) {
                writer.writeTag(NbtMap.fromMap(Map.of("entry", 1)));
            }
        }
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
                        "util.json")));
    }
}
