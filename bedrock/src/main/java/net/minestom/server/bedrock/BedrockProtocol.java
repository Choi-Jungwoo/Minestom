package net.minestom.server.bedrock;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2169.Bedrock_v2169;
import org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924;
import org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944;
import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

enum BedrockProtocol {
    V924(924, Bedrock_v924.CODEC),
    V944(944, Bedrock_v944.CODEC),
    V975(975, Bedrock_v975.CODEC),
    V1001(1001, Bedrock_v1001.CODEC),
    V2168(2168, Bedrock_v2168.CODEC),
    V2169(2169, Bedrock_v2169.CODEC);

    private final int version;
    private final BedrockCodec codec;

    BedrockProtocol(int version, BedrockCodec codec) {
        this.version = version;
        this.codec = codec;
    }

    static @Nullable BedrockCodec codec(int version) {
        return Arrays.stream(values())
                .filter(protocol -> protocol.version == version)
                .map(protocol -> protocol.codec)
                .findFirst()
                .orElse(null);
    }

    static List<Integer> availableVersions() {
        return Arrays.stream(values()).map(protocol -> protocol.version).toList();
    }
}
