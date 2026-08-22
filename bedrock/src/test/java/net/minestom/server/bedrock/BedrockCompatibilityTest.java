package net.minestom.server.bedrock;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BedrockCompatibilityTest {
    @Test
    void artifactMetadataRecordsTheResolvedCompatibilityPolicy() throws IOException {
        var properties = new Properties();
        try (var input = BedrockCompatibility.class.getResourceAsStream(
                "/META-INF/minestom-bedrock.properties")) {
            properties.load(input);
        }

        assertEquals(BedrockCompatibility.CLOUDBURST_CONNECTION_VERSION,
                properties.getProperty("cloudburst.connection"));
        assertEquals(BedrockCompatibility.CLOUDBURST_CODEC_VERSION,
                properties.getProperty("cloudburst.codec"));
        assertEquals(BedrockCompatibility.CLOUDBURST_RAKNET_VERSION,
                properties.getProperty("cloudburst.raknet"));
        assertEquals(BedrockCompatibility.NETTY_VERSION, properties.getProperty("netty"));
        assertEquals(BedrockCompatibility.BEDROCK_MAPPING_VERSION,
                properties.getProperty("mappings.version"));
        assertEquals(BedrockCompatibility.MAPPING_SOURCE,
                properties.getProperty("mappings.source"));
        assertEquals(BedrockCompatibility.MAPPING_SOURCE_COMMIT,
                properties.getProperty("mappings.commit"));
        assertEquals(BedrockCompatibility.MAPPING_SHA256,
                properties.getProperty("mappings.sha256"));
        assertEquals(BedrockCompatibility.RUNTIME_PALETTE_SOURCE,
                properties.getProperty("mappings.runtime-palette.source"));
        assertEquals(BedrockCompatibility.RUNTIME_PALETTE_SOURCE_COMMIT,
                properties.getProperty("mappings.runtime-palette.commit"));
        assertEquals(
                "73ef6dadfa16dd52319493c8112ecb13a020989cf04452f76ff4a8a250a29fea",
                BedrockCompatibility.RUNTIME_PALETTE_SHA256);
        assertEquals(BedrockCompatibility.RUNTIME_PALETTE_SHA256,
                properties.getProperty("mappings.runtime-palette.sha256"));
        assertEquals("924,944,975,1001,2168,2169", properties.getProperty("protocols.accepted"));
        assertEquals("1001", properties.getProperty("protocols.supported"));
    }

    @Test
    @Tag("bedrock-acceptance")
    void acceptedProtocolsDoNotOverstateAutomatedSupport() {
        assertEquals(
                List.of(924, 944, 975, 1001, 2168, 2169),
                BedrockCompatibility.ACCEPTED_PROTOCOLS);
        assertEquals(Set.of(1001), BedrockCompatibility.SUPPORTED_PROTOCOLS);
        assertTrue(BedrockCompatibility.ACCEPTED_PROTOCOLS.containsAll(
                BedrockCompatibility.SUPPORTED_PROTOCOLS));
        assertNotEquals(
                Set.copyOf(BedrockCompatibility.ACCEPTED_PROTOCOLS),
                BedrockCompatibility.SUPPORTED_PROTOCOLS);
    }
}
