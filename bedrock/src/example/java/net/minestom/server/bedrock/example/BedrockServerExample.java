package net.minestom.server.bedrock.example;

import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerProcess;
import net.minestom.server.bedrock.BedrockServer;
import net.minestom.server.bedrock.BedrockServerConfig;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Objects;

public final class BedrockServerExample {
    private static final String BIND_ADDRESS = "0.0.0.0";
    private static final int BEDROCK_PORT = 19132;
    private static final int JAVA_PORT = 25565;
    private static final Pos SPAWN_POSITION = new Pos(0.5, 41, 0.5);

    private BedrockServerExample() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "Expected one argument: the verified Bedrock mappings directory");
        }

        final MinecraftServer minecraftServer = MinecraftServer.init(new Auth.Offline());
        final ServerProcess process = Objects.requireNonNull(MinecraftServer.process());
        final InstanceContainer instance = process.instance().createInstanceContainer();
        instance.setGenerator(unit -> {
            unit.modifier().fillHeight(0, 40, Block.DIRT);
            unit.modifier().fillHeight(40, 41, Block.GRASS_BLOCK);
        });

        process.eventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(SPAWN_POSITION);
        });

        final BedrockServer bedrockServer = BedrockServer.create(
                process,
                new BedrockServerConfig(
                        new InetSocketAddress(BIND_ADDRESS, BEDROCK_PORT),
                        instance,
                        Path.of(arguments[0])));
        bedrockServer.start();

        System.out.printf(
                "Bedrock example listening on %s:%d/UDP; connect a mobile client to this host's LAN IP.%n",
                BIND_ADDRESS,
                bedrockServer.boundAddress().getPort());
        minecraftServer.start(BIND_ADDRESS, JAVA_PORT);
    }
}
