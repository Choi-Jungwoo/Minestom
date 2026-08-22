package net.minestom.server.bedrock;

import net.minestom.server.instance.Instance;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable settings required to start the native Bedrock listener.
 *
 * @param address           UDP bind address
 * @param spawningInstance  instance assigned to admitted Bedrock players
 * @param mappingsDirectory operator-provided, exact versioned mappings bundle
 * @param limits            hard listener and protocol resource limits
 */
public record BedrockServerConfig(
        InetSocketAddress address,
        Instance spawningInstance,
        Path mappingsDirectory,
        BedrockServerLimits limits) {

    public BedrockServerConfig(
            InetSocketAddress address,
            Instance spawningInstance,
            Path mappingsDirectory) {
        this(address, spawningInstance, mappingsDirectory, BedrockServerLimits.defaults());
    }

    public BedrockServerConfig {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(spawningInstance, "spawningInstance");
        mappingsDirectory = Objects.requireNonNull(
                mappingsDirectory, "mappingsDirectory").toAbsolutePath().normalize();
        Objects.requireNonNull(limits, "limits");
    }
}
