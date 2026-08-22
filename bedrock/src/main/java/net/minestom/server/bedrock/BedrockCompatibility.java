package net.minestom.server.bedrock;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** 实验性 Bedrock 模块可复现的依赖与协议元数据。 */
public final class BedrockCompatibility {
    private static final Properties PINS = loadPins();

    public static final String JAVA_VERSION = pin("java.version");
    public static final String BEDROCK_MAPPING_VERSION = pin("bedrock.mapping.version");
    public static final String BEDROCK_WIRE_VERSION = pin("bedrock.wire.version");
    public static final int PRIMARY_PROTOCOL = Integer.parseInt(pin("protocols.primary"));
    public static final List<Integer> ACCEPTED_PROTOCOLS = protocolList("protocols.accepted");
    public static final Set<Integer> SUPPORTED_PROTOCOLS =
            Set.copyOf(protocolList("protocols.supported"));
    public static final String MAPPING_SOURCE = pin("mappings.source");
    public static final String MAPPING_SOURCE_COMMIT = pin("mappings.commit");
    public static final String MAPPING_SHA256 = pin("mappings.sha256");
    public static final String RUNTIME_PALETTE_SOURCE =
            pin("mappings.runtime-palette.source");
    public static final String RUNTIME_PALETTE_SOURCE_COMMIT =
            pin("mappings.runtime-palette.commit");
    public static final String RUNTIME_PALETTE_SHA256 =
            pin("mappings.runtime-palette.sha256");
    public static final String CLOUDBURST_CONNECTION_VERSION =
            pin("cloudburst.connection");
    public static final String CLOUDBURST_CODEC_VERSION = pin("cloudburst.codec");
    public static final String CLOUDBURST_RAKNET_VERSION = pin("cloudburst.raknet");
    public static final String NETTY_VERSION = pin("netty");

    static {
        if (!ACCEPTED_PROTOCOLS.equals(BedrockProtocol.availableVersions())) {
            throw new ExceptionInInitializerError(
                    "Accepted Bedrock protocols must match every bundled 1.26.x codec");
        }
    }

    private BedrockCompatibility() {
    }

    private static Properties loadPins() {
        final Properties properties = new Properties();
        try (InputStream input = BedrockCompatibility.class.getResourceAsStream(
                "/META-INF/minestom-bedrock.properties")) {
            if (input == null) throw new IllegalStateException("Missing Bedrock compatibility metadata");
            properties.load(input);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
        return properties;
    }

    private static String pin(String name) {
        final String value = PINS.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new ExceptionInInitializerError("Missing Bedrock compatibility pin: " + name);
        }
        return value;
    }

    private static List<Integer> protocolList(String name) {
        return Arrays.stream(pin(name).split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .toList();
    }
}
