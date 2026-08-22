package net.minestom.server.bedrock;

import java.util.List;
import java.util.Set;

/**
 * Reproducible dependency and protocol metadata for the experimental Bedrock module.
 */
public final class BedrockCompatibility {
    public static final String JAVA_VERSION = "26.2";
    public static final String BEDROCK_MAPPING_VERSION = "1.26.30.5";
    public static final String BEDROCK_WIRE_VERSION = "1.26.30";
    public static final int PRIMARY_PROTOCOL = 1001;
    public static final List<Integer> ACCEPTED_PROTOCOLS = BedrockProtocol.acceptedVersions();
    public static final Set<Integer> SUPPORTED_PROTOCOLS = BedrockProtocol.supportedVersions();
    public static final String MAPPING_SHA256 =
            "92c1d3bc5b12705d20290363857a19ea202a88bdc5f79f560ead8b345442cbd0";
    public static final String CLOUDBURST_CONNECTION_VERSION =
            "3.0.0.Beta13-20260814.192942-15";
    public static final String CLOUDBURST_CODEC_VERSION =
            "3.0.0.Beta13-20260820.124150-16";
    public static final String CLOUDBURST_RAKNET_VERSION =
            "1.1.0.CR1-20260820.174333-6";
    public static final String NETTY_VERSION = "4.2.17.Final";

    private BedrockCompatibility() {
    }
}
