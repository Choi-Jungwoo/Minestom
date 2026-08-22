package net.minestom.server.bedrock;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924;
import org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944;
import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

enum BedrockProtocol {
    V924(924, Support.EXPERIMENTAL, Bedrock_v924.CODEC),
    V944(944, Support.EXPERIMENTAL, Bedrock_v944.CODEC),
    V975(975, Support.EXPERIMENTAL, Bedrock_v975.CODEC),
    V1001(1001, Support.SUPPORTED, Bedrock_v1001.CODEC),
    V2168(2168, Support.EXPERIMENTAL, Bedrock_v2168.CODEC);

    private final int version;
    private final Support support;
    private final BedrockCodec codec;

    BedrockProtocol(int version, Support support, BedrockCodec codec) {
        this.version = version;
        this.support = support;
        this.codec = codec;
    }

    static @Nullable BedrockCodec codec(int version) {
        return Arrays.stream(values())
                .filter(protocol -> protocol.version == version)
                .map(protocol -> protocol.codec)
                .findFirst()
                .orElse(null);
    }

    static List<Integer> acceptedVersions() {
        return Arrays.stream(values()).map(protocol -> protocol.version).toList();
    }

    static Set<Integer> supportedVersions() {
        return Arrays.stream(values())
                .filter(protocol -> protocol.support == Support.SUPPORTED)
                .map(protocol -> protocol.version)
                .collect(Collectors.toUnmodifiableSet());
    }

    static String acceptedVersionsDescription() {
        return acceptedVersions().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
    }

    private enum Support {
        SUPPORTED,
        EXPERIMENTAL
    }
}
