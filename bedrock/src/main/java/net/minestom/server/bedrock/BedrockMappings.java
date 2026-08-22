package net.minestom.server.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class BedrockMappings {
    private static final List<String> REQUIRED_FILES = List.of(
            "LICENSE",
            "README.md",
            "additional_offhand_items.json",
            "biomes.json",
            "block_shapes.nbt",
            "blocks.nbt",
            "collisions.nbt",
            "effects.json",
            "interactions.json",
            "item_components.nbt",
            "item_data_components.json",
            "items.json",
            "particles.json",
            "resolvable_item_data_components.json",
            "sounds.json",
            "util.json");
    private static final List<String> JSON_FILES =
            REQUIRED_FILES.stream().filter(file -> file.endsWith(".json")).toList();
    private static final List<String> NBT_FILES =
            REQUIRED_FILES.stream().filter(file -> file.endsWith(".nbt")).toList();
    static final Release SUPPORTED_RELEASE = new Release(
            BedrockCompatibility.JAVA_VERSION,
            BedrockCompatibility.BEDROCK_MAPPING_VERSION,
            BedrockCompatibility.MAPPING_SHA256);

    private final Release release;
    private final int registryEntryCount;

    private BedrockMappings(Release release, int registryEntryCount) {
        this.release = release;
        this.registryEntryCount = registryEntryCount;
    }

    static BedrockMappings testing() {
        return new BedrockMappings(new Release(
                BedrockCompatibility.JAVA_VERSION,
                BedrockCompatibility.BEDROCK_MAPPING_VERSION,
                "0000000000000000000000000000000000000000000000000000000000000000"), 1);
    }

    static BedrockMappings load(Path directory) throws IOException {
        return load(directory, SUPPORTED_RELEASE);
    }

    static BedrockMappings load(Path directory, Release release) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(release, "release");
        final Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Bedrock mappings directory does not exist: " + root);
        }
        for (String file : REQUIRED_FILES) {
            final Path path = root.resolve(file);
            if (!path.normalize().startsWith(root) || !Files.isRegularFile(path)
                    || Files.isSymbolicLink(path) || Files.size(path) == 0) {
                throw new IllegalArgumentException("Bedrock mappings are missing " + file);
            }
        }

        final String actualChecksum = sha256(root, REQUIRED_FILES);
        if (!release.sha256().equals(actualChecksum)) {
            throw new IllegalArgumentException(
                    "Bedrock mapping checksum mismatch: expected " + release.sha256()
                            + ", got " + actualChecksum);
        }
        validateReleaseIdentity(root, release);
        final int registryEntryCount = validateRegistryCoverage(root);
        return new BedrockMappings(release, registryEntryCount);
    }

    static String sha256(Path directory, List<String> files) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
        for (String file : files.stream().sorted().toList()) {
            digest.update(file.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (InputStream input = Files.newInputStream(directory.resolve(file))) {
                final byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, length);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void validateReleaseIdentity(Path root, Release release) throws IOException {
        final String readme = Files.readString(root.resolve("README.md"));
        if (!readme.contains("Java Edition " + release.javaVersion())
                || !readme.contains("Bedrock Edition " + release.bedrockVersion())) {
            throw new IllegalArgumentException("Bedrock mapping README has the wrong release identity");
        }
    }

    private static int validateRegistryCoverage(Path root) throws IOException {
        final Map<String, JsonElement> jsonMappings = new HashMap<>();
        int registryEntryCount = 0;
        for (String file : JSON_FILES) {
            final JsonElement json;
            try (var reader = Files.newBufferedReader(root.resolve(file))) {
                json = JsonParser.parseReader(reader);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Bedrock mapping JSON is invalid: " + file, exception);
            }
            if (json.isJsonObject()) registryEntryCount += json.getAsJsonObject().size();
            else if (json.isJsonArray()) registryEntryCount += json.getAsJsonArray().size();
            jsonMappings.put(file, json);
        }

        final JsonElement biomes = jsonMappings.get("biomes.json");
        final JsonElement items = jsonMappings.get("items.json");
        if (!biomes.isJsonObject() || !biomes.getAsJsonObject().has("minecraft:plains")) {
            throw new IllegalArgumentException("Bedrock mappings are missing the plains biome");
        }
        if (!items.isJsonObject() || !items.getAsJsonObject().has("minecraft:air")) {
            throw new IllegalArgumentException("Bedrock mappings are missing the air item");
        }
        for (String file : NBT_FILES) {
            final Object rootTag;
            try (var input = Files.newInputStream(root.resolve(file));
                 var reader = NbtUtils.createGZIPReader(input)) {
                rootTag = reader.readTag();
            }
            final int size = switch (rootTag) {
                case Map<?, ?> map -> map.size();
                case Collection<?> collection -> collection.size();
                case null, default -> 0;
            };
            if (size == 0) {
                throw new IllegalArgumentException("Bedrock mapping NBT is empty: " + file);
            }
            registryEntryCount += size;
        }
        if (registryEntryCount == 0) {
            throw new IllegalArgumentException("Bedrock mappings contain no registry entries");
        }
        return registryEntryCount;
    }

    String javaVersion() {
        return release.javaVersion();
    }

    String bedrockVersion() {
        return release.bedrockVersion();
    }

    String sha256() {
        return release.sha256();
    }

    int registryEntryCount() {
        return registryEntryCount;
    }

    void requireAcceptedProtocol(int protocolVersion) {
        if (!BedrockCompatibility.ACCEPTED_PROTOCOLS.contains(protocolVersion)) {
            throw new IllegalArgumentException(
                    "Mappings " + bedrockVersion()
                            + " cannot be used with Bedrock protocol " + protocolVersion);
        }
    }

    record Release(String javaVersion, String bedrockVersion, String sha256) {
        Release {
            Objects.requireNonNull(javaVersion, "javaVersion");
            Objects.requireNonNull(bedrockVersion, "bedrockVersion");
            Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters");
            }
        }
    }
}
