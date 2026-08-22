package net.minestom.server.bedrock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class BedrockMaintenanceTool {
    private BedrockMaintenanceTool() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length == 1 && arguments[0].equals("report")) {
            reportCompatibility();
            return;
        }
        if (arguments.length == 2 && arguments[0].equals("verify")) {
            final Path directory = Path.of(arguments[1]);
            final BedrockMappings mappings = BedrockMappings.load(directory);
            System.out.printf(
                    "Verified Bedrock mappings %s for Java %s (%s)%n",
                    mappings.bedrockVersion(),
                    mappings.javaVersion(),
                    mappings.sha256());
            return;
        }
        if (arguments.length == 3 && arguments[0].equals("prepare")) {
            final Path destination = Path.of(arguments[2]);
            prepare(Path.of(arguments[1]), destination, BedrockMappings.SUPPORTED_RELEASE);
            System.out.printf(
                    "Prepared verified Bedrock mappings at %s%n",
                    destination.toAbsolutePath().normalize());
            return;
        }
        throw new IllegalArgumentException(
                "Usage: BedrockMaintenanceTool report | verify <directory>"
                        + " | prepare <source-directory> <destination-directory>");
    }

    private static void reportCompatibility() {
        System.out.printf(
                """
                Pinned Bedrock compatibility:
                  Cloudburst connection: %s
                  Cloudburst codec: %s
                  RakNet: %s
                  Netty: %s
                  mappings: %s @ %s
                  mappings SHA-256: %s
                  runtime palette: %s @ %s
                  runtime palette SHA-256: %s
                  accepted protocols: %s
                  supported protocols: %s
                """,
                BedrockCompatibility.CLOUDBURST_CONNECTION_VERSION,
                BedrockCompatibility.CLOUDBURST_CODEC_VERSION,
                BedrockCompatibility.CLOUDBURST_RAKNET_VERSION,
                BedrockCompatibility.NETTY_VERSION,
                BedrockCompatibility.MAPPING_SOURCE,
                BedrockCompatibility.MAPPING_SOURCE_COMMIT,
                BedrockCompatibility.MAPPING_SHA256,
                BedrockCompatibility.RUNTIME_PALETTE_SOURCE,
                BedrockCompatibility.RUNTIME_PALETTE_SOURCE_COMMIT,
                BedrockCompatibility.RUNTIME_PALETTE_SHA256,
                BedrockCompatibility.ACCEPTED_PROTOCOLS,
                BedrockCompatibility.SUPPORTED_PROTOCOLS);
    }

    static void prepare(
            Path sourceDirectory,
            Path destinationDirectory,
            BedrockMappings.Release release) throws IOException {
        final Path source = sourceDirectory.toAbsolutePath().normalize();
        final Path destination = destinationDirectory.toAbsolutePath().normalize();
        BedrockMappings.load(source, release);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "Bedrock mappings destination already exists: " + destination);
        }

        Files.createDirectory(destination);
        for (String file : BedrockMappings.releaseFiles()) {
            final Path sourceFile = source.resolve(file);
            if (!Files.isRegularFile(sourceFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "Bedrock mappings contain a symbolic link: " + sourceFile);
            }
            Files.copy(sourceFile, destination.resolve(file));
        }
        BedrockMappings.load(destination, release);
    }
}
