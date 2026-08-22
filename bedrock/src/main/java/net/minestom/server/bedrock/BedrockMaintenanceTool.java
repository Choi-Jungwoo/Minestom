package net.minestom.server.bedrock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

final class BedrockMaintenanceTool {
    private static final Set<String> REQUIRED_COMPATIBILITY_PINS = Set.of(
            "java.version",
            "bedrock.mapping.version",
            "bedrock.wire.version",
            "cloudburst.connection",
            "cloudburst.common",
            "cloudburst.codec",
            "cloudburst.raknet",
            "netty",
            "mappings.version",
            "mappings.source",
            "mappings.commit",
            "mappings.sha256",
            "mappings.runtime-palette.source",
            "mappings.runtime-palette.commit",
            "mappings.runtime-palette.sha256",
            "protocols.primary",
            "protocols.accepted",
            "protocols.supported");

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
        if (arguments.length == 3 && arguments[0].equals("update")) {
            final Path source = Path.of(arguments[1]);
            final Path destination = Path.of(arguments[2]);
            updateCompatibilityPins(source, destination);
            System.out.printf(
                    "Installed reviewed Bedrock compatibility pins from %s%n",
                    source.toAbsolutePath().normalize());
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
                        + " | update <source-file> <destination-file>"
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

    static void updateCompatibilityPins(Path sourceFile, Path destinationFile) throws IOException {
        final Path source = sourceFile.toAbsolutePath().normalize();
        final Path destination = destinationFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "Bedrock compatibility manifest is not a regular file: " + source);
        }

        final Properties pins = new Properties();
        try (var input = Files.newInputStream(source)) {
            pins.load(input);
        }
        if (!pins.stringPropertyNames().containsAll(REQUIRED_COMPATIBILITY_PINS)) {
            throw new IllegalArgumentException(
                    "Compatibility manifest must contain: "
                            + REQUIRED_COMPATIBILITY_PINS.stream().sorted().toList());
        }
        for (String name : Set.of(
                "cloudburst.connection",
                "cloudburst.common",
                "cloudburst.codec",
                "cloudburst.raknet",
                "netty")) {
            final String version = pins.getProperty(name);
            if (version.toLowerCase(Locale.ROOT).contains("snapshot")
                    || version.equalsIgnoreCase("latest")) {
                throw new IllegalArgumentException(
                        name + " must be pinned to an immutable version");
            }
        }
        for (Map.Entry<String, Integer> pin : Map.of(
                "mappings.commit", 40,
                "mappings.runtime-palette.commit", 40,
                "mappings.sha256", 64,
                "mappings.runtime-palette.sha256", 64).entrySet()) {
            final String value = pins.getProperty(pin.getKey());
            if (value.length() != pin.getValue()
                    || value.chars().anyMatch(character ->
                    !Character.isDigit(character) && (character < 'a' || character > 'f'))) {
                throw new IllegalArgumentException(
                        pin.getKey() + " must be a lowercase hexadecimal pin");
            }
        }
        if (!source.equals(destination)) {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
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
