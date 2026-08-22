package net.minestom.server.bedrock;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(BedrockCompatibility.MAPPING_SHA256,
                properties.getProperty("mappings.sha256"));
        assertEquals("924,944,975,1001,2168", properties.getProperty("protocols.accepted"));
        assertEquals("1001", properties.getProperty("protocols.supported"));
    }
}
