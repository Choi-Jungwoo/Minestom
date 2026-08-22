package net.minestom.server.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.Registry;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    private static final Map<String, JsonRequirement> JSON_REQUIREMENTS = Map.of(
            "additional_offhand_items.json", new JsonRequirement(JsonRoot.ARRAY, true),
            "biomes.json", new JsonRequirement(JsonRoot.OBJECT, false),
            "effects.json", new JsonRequirement(JsonRoot.OBJECT, false),
            "interactions.json", new JsonRequirement(JsonRoot.OBJECT, false),
            "item_data_components.json", new JsonRequirement(JsonRoot.ARRAY, false),
            "items.json", new JsonRequirement(JsonRoot.OBJECT, false),
            "particles.json", new JsonRequirement(JsonRoot.OBJECT, false),
            "resolvable_item_data_components.json", new JsonRequirement(JsonRoot.OBJECT, false),
            "sounds.json", new JsonRequirement(JsonRoot.OBJECT, false),
            "util.json", new JsonRequirement(JsonRoot.OBJECT, false));
    static final Release SUPPORTED_RELEASE = new Release(
            BedrockCompatibility.JAVA_VERSION,
            BedrockCompatibility.BEDROCK_MAPPING_VERSION,
            BedrockCompatibility.MAPPING_SHA256);

    private final Release release;
    private final Set<String> itemKeys;
    private final Set<String> biomeKeys;
    private final int blockStateCount;

    private BedrockMappings(
            Release release, Set<String> itemKeys, Set<String> biomeKeys, int blockStateCount) {
        this.release = release;
        this.itemKeys = Set.copyOf(itemKeys);
        this.biomeKeys = Set.copyOf(biomeKeys);
        this.blockStateCount = blockStateCount;
    }

    static BedrockMappings testing(Registries registries) {
        return new BedrockMappings(new Release(
                BedrockCompatibility.JAVA_VERSION,
                BedrockCompatibility.BEDROCK_MAPPING_VERSION,
                "0000000000000000000000000000000000000000000000000000000000000000"),
                registryKeys(registries.material()),
                registryKeys(registries.biome()),
                registries.blocks().size());
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
        final RegistryCoverage coverage = validateRegistryCoverage(root);
        return new BedrockMappings(
                release, coverage.itemKeys(), coverage.biomeKeys(), coverage.blockStateCount());
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

    private static RegistryCoverage validateRegistryCoverage(Path root) throws IOException {
        final Map<String, JsonElement> jsonMappings = new HashMap<>();
        for (String file : JSON_FILES) {
            final JsonElement json;
            try (var reader = Files.newBufferedReader(root.resolve(file))) {
                json = JsonParser.parseReader(reader);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Bedrock mapping JSON is invalid: " + file, exception);
            }
            JSON_REQUIREMENTS.get(file).validate(file, json);
            jsonMappings.put(file, json);
        }

        final JsonObject biomes = jsonMappings.get("biomes.json").getAsJsonObject();
        final Set<String> biomeKeys = new HashSet<>();
        for (Map.Entry<String, JsonElement> entry : biomes.entrySet()) {
            if (!entry.getValue().isJsonObject()
                    || !isNumber(entry.getValue().getAsJsonObject().get("bedrock_id"))) {
                throw invalidRegistryEntry("biomes.json", entry.getKey());
            }
            biomeKeys.add(entry.getKey());
        }
        if (!biomeKeys.contains("minecraft:plains")) {
            throw new IllegalArgumentException("Bedrock mappings are missing the plains biome");
        }

        final JsonObject items = jsonMappings.get("items.json").getAsJsonObject();
        final Set<String> itemKeys = new HashSet<>();
        for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
            if (!entry.getValue().isJsonObject()
                    || !isString(entry.getValue().getAsJsonObject().get("bedrock_identifier"))) {
                throw invalidRegistryEntry("items.json", entry.getKey());
            }
            itemKeys.add(entry.getKey());
        }
        if (!itemKeys.contains("minecraft:air")) {
            throw new IllegalArgumentException("Bedrock mappings are missing the air item");
        }

        final Set<String> componentKeys = new HashSet<>();
        for (JsonElement element : jsonMappings.get("item_data_components.json").getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(
                        "Bedrock mapping item_data_components.json contains a non-object entry");
            }
            final JsonObject component = element.getAsJsonObject();
            if (!isNumber(component.get("id"))
                    || !isString(component.get("key"))
                    || component.get("components") == null
                    || !component.get("components").isJsonObject()) {
                throw new IllegalArgumentException(
                        "Bedrock mapping item_data_components.json contains an invalid entry");
            }
            componentKeys.add(component.get("key").getAsString());
        }
        if (!componentKeys.equals(itemKeys)) {
            throw new IllegalArgumentException(
                    "Bedrock item mappings and data components do not cover the same registry");
        }

        final Map<String, NbtMap> nbtMappings = new HashMap<>();
        for (String file : NBT_FILES) {
            final Object rootTag;
            try (var input = Files.newInputStream(root.resolve(file));
                 var reader = NbtUtils.createGZIPReader(input)) {
                rootTag = reader.readTag();
            }
            if (!(rootTag instanceof NbtMap nbt) || nbt.isEmpty()) {
                throw new IllegalArgumentException("Bedrock mapping NBT has an invalid root: " + file);
            }
            nbtMappings.put(file, nbt);
        }

        final int blockStateCount = requireNbtList(
                nbtMappings.get("blocks.nbt"), "bedrock_mappings", "blocks.nbt").size();
        validateShapeRegistry(nbtMappings.get("block_shapes.nbt"), "block_shapes.nbt", blockStateCount);
        validateShapeRegistry(nbtMappings.get("collisions.nbt"), "collisions.nbt", blockStateCount);
        final NbtMap itemComponents = nbtMappings.get("item_components.nbt");
        if (!itemComponents.containsKey("minecraft:air")
                || itemComponents.values().stream().anyMatch(value -> !(value instanceof NbtMap))) {
            throw new IllegalArgumentException(
                    "Bedrock item_components.nbt does not cover structured item components");
        }
        return new RegistryCoverage(itemKeys, biomeKeys, blockStateCount);
    }

    private static void validateShapeRegistry(
            NbtMap root, String file, int blockStateCount) {
        final Object indices = root.get("indices");
        if (!(indices instanceof int[] indexArray) || indexArray.length != blockStateCount) {
            throw new IllegalArgumentException(
                    "Bedrock mapping " + file + " does not cover every block state");
        }
        requireNbtList(root, "shapes", file);
    }

    private static NbtList<?> requireNbtList(NbtMap root, String key, String file) {
        final Object value = root.get(key);
        if (!(value instanceof NbtList<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(
                    "Bedrock mapping " + file + " is missing non-empty " + key);
        }
        return list;
    }

    private static boolean isNumber(JsonElement element) {
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isNumber();
    }

    private static boolean isString(JsonElement element) {
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString();
    }

    private static IllegalArgumentException invalidRegistryEntry(String file, String key) {
        return new IllegalArgumentException(
                "Bedrock mapping " + file + " contains an invalid entry: " + key);
    }

    private static Set<String> registryKeys(Registry<?> registry) {
        return registry.keys().stream()
                .map(key -> key.key().asString())
                .collect(Collectors.toSet());
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

    int blockStateCount() {
        return blockStateCount;
    }

    int itemCount() {
        return itemKeys.size();
    }

    int biomeCount() {
        return biomeKeys.size();
    }

    void requireRegistryCompatibility(Registries registries) {
        requireRegistryCoverage("item", itemKeys, registryKeys(registries.material()));
        requireRegistryCoverage("biome", biomeKeys, registryKeys(registries.biome()));
        if (blockStateCount < registries.blocks().size()) {
            throw new IllegalArgumentException(
                    "Bedrock block mappings do not cover the Minestom block registry");
        }
    }

    private static void requireRegistryCoverage(
            String registry, Set<String> mappings, Set<String> required) {
        if (!mappings.containsAll(required)) {
            final String missing = required.stream()
                    .filter(key -> !mappings.contains(key))
                    .sorted()
                    .findFirst()
                    .orElseThrow();
            throw new IllegalArgumentException(
                    "Bedrock mappings do not cover the Minestom " + registry
                            + " registry: " + missing);
        }
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

    private enum JsonRoot {
        OBJECT,
        ARRAY
    }

    private record JsonRequirement(JsonRoot root, boolean emptyAllowed) {
        private void validate(String file, JsonElement json) {
            final boolean correctRoot = switch (root) {
                case OBJECT -> json.isJsonObject();
                case ARRAY -> json.isJsonArray();
            };
            final int size = switch (root) {
                case OBJECT -> correctRoot ? json.getAsJsonObject().size() : 0;
                case ARRAY -> correctRoot ? json.getAsJsonArray().size() : 0;
            };
            if (!correctRoot || (!emptyAllowed && size == 0)) {
                throw new IllegalArgumentException(
                        "Bedrock mapping JSON has incomplete registry coverage: " + file);
            }
        }
    }

    private record RegistryCoverage(
            Set<String> itemKeys, Set<String> biomeKeys, int blockStateCount) {
    }
}
