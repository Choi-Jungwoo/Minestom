package net.minestom.server.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.Registry;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

final class BedrockMappings {
    private static final String RUNTIME_PALETTE_FILE = "block_palette.26_30.nbt";
    private static final List<String> NBT_FILES = List.of(
            "block_shapes.nbt",
            "blocks.nbt",
            "collisions.nbt",
            "item_components.nbt");
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
    private static final List<String> REQUIRED_FILES;

    static {
        final List<String> files = new ArrayList<>(List.of("LICENSE", "README.md"));
        files.addAll(JSON_REQUIREMENTS.keySet());
        files.addAll(NBT_FILES);
        REQUIRED_FILES = List.copyOf(files);
    }

    static final Release SUPPORTED_RELEASE = new Release(
            BedrockCompatibility.JAVA_VERSION,
            BedrockCompatibility.BEDROCK_MAPPING_VERSION,
            BedrockCompatibility.MAPPING_SHA256,
            BedrockCompatibility.RUNTIME_PALETTE_SHA256);

    private final Release release;
    private final RegistryCoverage coverage;
    private final List<BlockDefinition> blockDefinitions;
    private final Map<String, ItemDefinition> itemDefinitionsByJavaKey;

    private BedrockMappings(
            Release release,
            RegistryCoverage coverage,
            List<BlockDefinition> blockDefinitions,
            List<ItemDefinition> itemDefinitions) {
        this.release = release;
        this.coverage = coverage;
        this.blockDefinitions = List.copyOf(blockDefinitions);
        this.itemDefinitionsByJavaKey = itemDefinitions.stream()
                .collect(Collectors.toUnmodifiableMap(
                        ItemDefinition::getIdentifier,
                        definition -> definition));
    }

    static BedrockMappings testing(Registries registries) {
        final List<BlockDefinition> blockDefinitions = new ArrayList<>(Block.statesCount());
        for (int stateId = 0; stateId < Block.statesCount(); stateId++) {
            final Block block = Objects.requireNonNull(
                    Block.fromStateId(stateId), "Missing Minestom block state " + stateId);
            blockDefinitions.add(new SimpleBlockDefinition(
                    block.key().asString(), stateId, NbtMap.EMPTY));
        }
        return new BedrockMappings(new Release(
                BedrockCompatibility.JAVA_VERSION,
                BedrockCompatibility.BEDROCK_MAPPING_VERSION,
                "0000000000000000000000000000000000000000000000000000000000000000",
                "0000000000000000000000000000000000000000000000000000000000000000"),
                new RegistryCoverage(
                        registryKeys(registries.material()),
                        registryKeys(registries.biome()),
                        Block.statesCount(),
                        List.of(),
                        Map.of("minecraft:plains", 1)),
                blockDefinitions,
                testingItemDefinitions(registries));
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
        final Path runtimePalette = root.resolve(RUNTIME_PALETTE_FILE);
        if (!runtimePalette.normalize().startsWith(root)
                || !Files.isRegularFile(runtimePalette)
                || Files.isSymbolicLink(runtimePalette)
                || Files.size(runtimePalette) == 0) {
            throw new IllegalArgumentException(
                    "Bedrock mappings are missing " + RUNTIME_PALETTE_FILE);
        }

        final String actualChecksum = sha256(root, REQUIRED_FILES);
        if (!release.sha256().equals(actualChecksum)) {
            throw new IllegalArgumentException(
                    "Bedrock mapping checksum mismatch: expected " + release.sha256()
                            + ", got " + actualChecksum);
        }
        final String actualRuntimePaletteChecksum = sha256(runtimePalette);
        if (!release.runtimePaletteSha256().equals(actualRuntimePaletteChecksum)) {
            throw new IllegalArgumentException(
                    "Bedrock runtime palette checksum mismatch: expected "
                            + release.runtimePaletteSha256()
                            + ", got " + actualRuntimePaletteChecksum);
        }
        validateReleaseIdentity(root, release);
        final RegistryCoverage coverage = validateRegistryCoverage(root);
        final List<BlockDefinition> blockDefinitions =
                loadBlockDefinitions(root, coverage.blockMappings());
        return new BedrockMappings(
                release,
                coverage,
                blockDefinitions,
                loadItemDefinitions(root));
    }

    private static List<ItemDefinition> testingItemDefinitions(Registries registries) {
        final List<String> keys = registryKeys(registries.material()).stream()
                .sorted()
                .toList();
        final List<ItemDefinition> definitions = new ArrayList<>(keys.size());
        definitions.add(new SimpleItemDefinition("minecraft:air", 0, true));
        int runtimeId = 1;
        for (String key : keys) {
            if (key.equals("minecraft:air")) continue;
            definitions.add(new SimpleItemDefinition(key, runtimeId++, true));
        }
        return definitions;
    }

    private static List<ItemDefinition> loadItemDefinitions(Path root) throws IOException {
        final JsonElement json;
        try (var reader = Files.newBufferedReader(root.resolve("item_data_components.json"))) {
            json = JsonParser.parseReader(reader);
        }
        final List<ItemDefinition> definitions = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray()) {
            final JsonObject item = element.getAsJsonObject();
            definitions.add(new SimpleItemDefinition(
                    item.get("key").getAsString(),
                    item.get("id").getAsInt(),
                    true));
        }
        definitions.sort(Comparator.comparingInt(ItemDefinition::getRuntimeId));
        return definitions;
    }

    static String sha256(Path directory, List<String> files) throws IOException {
        final MessageDigest digest = sha256Digest();
        for (String file : files.stream().sorted().toList()) {
            digest.update(file.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            updateDigest(digest, directory.resolve(file));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(Path file) throws IOException {
        final MessageDigest digest = sha256Digest();
        updateDigest(digest, file);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void updateDigest(MessageDigest digest, Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            final byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, length);
            }
        }
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
        for (Map.Entry<String, JsonRequirement> requirement : JSON_REQUIREMENTS.entrySet()) {
            final String file = requirement.getKey();
            final JsonElement json;
            try (var reader = Files.newBufferedReader(root.resolve(file))) {
                json = JsonParser.parseReader(reader);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Bedrock mapping JSON is invalid: " + file, exception);
            }
            requirement.getValue().validate(file, json);
            jsonMappings.put(file, json);
        }

        final JsonObject biomes = jsonMappings.get("biomes.json").getAsJsonObject();
        final Set<String> biomeKeys = new HashSet<>();
        final Map<String, Integer> biomeIds = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : biomes.entrySet()) {
            if (!entry.getValue().isJsonObject()
                    || !isNumber(entry.getValue().getAsJsonObject().get("bedrock_id"))) {
                throw invalidRegistryEntry("biomes.json", entry.getKey());
            }
            biomeKeys.add(entry.getKey());
            biomeIds.put(
                    entry.getKey(),
                    entry.getValue().getAsJsonObject().get("bedrock_id").getAsInt());
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

        final List<NbtMap> blockMappings = requireCompoundList(
                nbtMappings.get("blocks.nbt"), "bedrock_mappings", "blocks.nbt");
        final int blockStateCount = blockMappings.size();
        validateShapeRegistry(nbtMappings.get("block_shapes.nbt"), "block_shapes.nbt", blockStateCount);
        validateShapeRegistry(nbtMappings.get("collisions.nbt"), "collisions.nbt", blockStateCount);
        final NbtMap itemComponents = nbtMappings.get("item_components.nbt");
        if (!itemComponents.containsKey("minecraft:air")
                || itemComponents.values().stream().anyMatch(value -> !(value instanceof NbtMap))) {
            throw new IllegalArgumentException(
                    "Bedrock item_components.nbt does not cover structured item components");
        }
        return new RegistryCoverage(
                itemKeys, biomeKeys, blockStateCount, blockMappings, biomeIds);
    }

    private static List<BlockDefinition> loadBlockDefinitions(
            Path root, List<NbtMap> javaMappings) throws IOException {
        final List<NbtMap> runtimeStates;
        try (var input = Files.newInputStream(root.resolve(RUNTIME_PALETTE_FILE));
             var gzip = new GZIPInputStream(input);
             var reader = NbtUtils.createReaderLE(gzip, true, true)) {
            final Object rootTag = reader.readTag();
            if (!(rootTag instanceof NbtMap palette)) {
                throw new IllegalArgumentException(
                        "Bedrock runtime palette has an invalid root");
            }
            runtimeStates = palette.getList("blocks", NbtType.COMPOUND);
        }
        if (runtimeStates.isEmpty()) {
            throw new IllegalArgumentException("Bedrock runtime palette is empty");
        }

        final Map<NbtMap, Integer> runtimeIds = new HashMap<>(runtimeStates.size());
        for (int runtimeId = 0; runtimeId < runtimeStates.size(); runtimeId++) {
            final NbtMap state = normalizeRuntimeState(runtimeStates.get(runtimeId));
            if (runtimeIds.put(state, runtimeId) != null) {
                throw new IllegalArgumentException(
                        "Bedrock runtime palette contains duplicate block states");
            }
        }

        final List<BlockDefinition> definitions = new ArrayList<>(javaMappings.size());
        for (int stateId = 0; stateId < javaMappings.size(); stateId++) {
            final Block block = Block.fromStateId(stateId);
            if (block == null) {
                throw new IllegalArgumentException(
                        "Bedrock mappings contain an unknown Minestom block state " + stateId);
            }
            final NbtMap mapping = javaMappings.get(stateId);
            String identifier = mapping.getString(
                    "bedrock_identifier", block.key().asString());
            if (identifier.indexOf(':') < 0) identifier = "minecraft:" + identifier;
            final NbtMap state = NbtMap.builder()
                    .putString("name", identifier)
                    .putCompound("states", mapping.getCompound("state"))
                    .build();
            final Integer runtimeId = runtimeIds.get(state);
            if (runtimeId == null) {
                throw new IllegalArgumentException(
                        "Bedrock runtime palette has no mapping for Minestom block state "
                                + stateId + ": " + state);
            }
            definitions.add(new SimpleBlockDefinition(
                    identifier, runtimeId, state.getCompound("states")));
        }
        return definitions;
    }

    private static NbtMap normalizeRuntimeState(NbtMap state) {
        return NbtMap.builder()
                .putString("name", state.getString("name"))
                .putCompound("states", state.getCompound("states"))
                .build();
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

    private static List<NbtMap> requireCompoundList(
            NbtMap root, String key, String file) {
        final List<NbtMap> list = root.getList(key, NbtType.COMPOUND);
        if (list.isEmpty()) {
            throw new IllegalArgumentException(
                    "Bedrock mapping " + file + " is missing non-empty " + key);
        }
        return List.copyOf(list);
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
        return coverage.blockStateCount();
    }

    BlockDefinition blockDefinition(int javaStateId) {
        if (javaStateId < 0 || javaStateId >= blockDefinitions.size()) {
            throw new IllegalArgumentException(
                    "No Bedrock mapping for Minestom block state " + javaStateId);
        }
        return blockDefinitions.get(javaStateId);
    }

    ItemDefinition itemDefinition(String javaKey) {
        final ItemDefinition definition = itemDefinitionsByJavaKey.get(javaKey);
        if (definition == null) {
            throw new IllegalArgumentException(
                    "No Bedrock mapping for Minestom item " + javaKey);
        }
        return definition;
    }

    int biomeId(int javaBiomeId, Registry<?> biomeRegistry) {
        final var key = biomeRegistry.getKey(javaBiomeId);
        if (key == null) {
            throw new IllegalArgumentException(
                    "Unknown Minestom biome id " + javaBiomeId);
        }
        final Integer bedrockId = coverage.biomeIds().get(key.key().asString());
        if (bedrockId == null) {
            throw new IllegalArgumentException(
                    "No Bedrock mapping for Minestom biome " + key.key().asString());
        }
        return bedrockId;
    }

    int defaultBiomeId() {
        return Objects.requireNonNull(
                coverage.biomeIds().get("minecraft:plains"),
                "Bedrock mappings are missing the plains biome");
    }

    int itemCount() {
        return coverage.itemKeys().size();
    }

    int biomeCount() {
        return coverage.biomeKeys().size();
    }

    void requireRegistryCompatibility(Registries registries) {
        requireRegistryCoverage(
                "item", coverage.itemKeys(), registryKeys(registries.material()));
        requireRegistryCoverage(
                "biome", coverage.biomeKeys(), registryKeys(registries.biome()));
        if (coverage.blockStateCount() < Block.statesCount()) {
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

    record Release(
            String javaVersion,
            String bedrockVersion,
            String sha256,
            String runtimePaletteSha256) {
        Release {
            Objects.requireNonNull(javaVersion, "javaVersion");
            Objects.requireNonNull(bedrockVersion, "bedrockVersion");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(runtimePaletteSha256, "runtimePaletteSha256");
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters");
            }
            if (!runtimePaletteSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "runtimePaletteSha256 must be 64 lowercase hexadecimal characters");
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
            Set<String> itemKeys,
            Set<String> biomeKeys,
            int blockStateCount,
            List<NbtMap> blockMappings,
            Map<String, Integer> biomeIds) {
        private RegistryCoverage {
            itemKeys = Set.copyOf(itemKeys);
            biomeKeys = Set.copyOf(biomeKeys);
            blockMappings = List.copyOf(blockMappings);
            biomeIds = Map.copyOf(biomeIds);
        }
    }
}
