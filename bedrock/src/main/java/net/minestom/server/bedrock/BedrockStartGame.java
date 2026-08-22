package net.minestom.server.bedrock;

import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.world.DimensionType;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.ChatRestrictionLevel;
import org.cloudburstmc.protocol.bedrock.data.GamePublishSetting;
import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission;
import org.cloudburstmc.protocol.bedrock.data.SpawnBiomeType;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.common.util.OptionalBoolean;

import java.util.Objects;
import java.util.UUID;

final class BedrockStartGame {
    private BedrockStartGame() {
    }

    static StartGamePacket create(
            Player player, Instance instance, BedrockMappings mappings, int protocolVersion) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(mappings, "mappings");
        mappings.requireAcceptedProtocol(protocolVersion);
        if (mappings.registryEntryCount() == 0) {
            throw new IllegalStateException("Bedrock mappings have no validated registry entries");
        }

        final var position = player.getRespawnPoint();
        final long entityId = player.getEntityId();
        final StartGamePacket packet = new StartGamePacket();
        packet.setUniqueEntityId(entityId);
        packet.setRuntimeEntityId(entityId);
        packet.setPlayerGameType(GameType.SURVIVAL);
        packet.setPlayerPosition(Vector3f.from(position.x(), position.y(), position.z()));
        packet.setRotation(Vector2f.from(position.yaw(), position.pitch()));
        packet.setSeed(0);
        packet.setSpawnBiomeType(SpawnBiomeType.DEFAULT);
        packet.setCustomBiomeName("");
        packet.setDimensionId(dimensionId(instance));
        packet.setGeneratorId(1);
        packet.setLevelGameType(GameType.SURVIVAL);
        packet.setDifficulty(1);
        packet.setDefaultSpawn(Vector3i.from(
                position.blockX(), position.blockY(), position.blockZ()));
        packet.setAchievementsDisabled(true);
        packet.setEducationProductionId("");
        packet.setMultiplayerGame(true);
        packet.setBroadcastingToLan(false);
        packet.setXblBroadcastMode(GamePublishSetting.NO_MULTI_PLAY);
        packet.setPlatformBroadcastMode(GamePublishSetting.NO_MULTI_PLAY);
        packet.setCommandsEnabled(true);
        packet.setDefaultPlayerPermission(PlayerPermission.MEMBER);
        packet.setServerChunkTickRange(instance.viewDistance());
        packet.setVanillaVersion(BedrockCompatibility.BEDROCK_WIRE_VERSION);
        packet.setForceExperimentalGameplay(OptionalBoolean.empty());
        packet.setChatRestrictionLevel(ChatRestrictionLevel.NONE);
        packet.setLevelId(instance.getUuid().toString());
        packet.setLevelName("Minestom");
        packet.setPremiumWorldTemplateId("");
        packet.setCurrentTick(instance.getWorldAge());
        packet.setMultiplayerCorrelationId("");
        packet.setInventoriesServerAuthoritative(true);
        packet.setServerEngine("Minestom/" + mappings.javaVersion()
                + " BedrockMappings/" + mappings.bedrockVersion());
        packet.setPlayerPropertyData(NbtMap.EMPTY);
        packet.setWorldTemplateId(new UUID(0, 0));
        packet.setBlockRegistryChecksum(0);
        packet.setServerId("");
        packet.setWorldId(instance.getUuid().toString());
        packet.setScenarioId("");
        packet.setOwnerId("");
        return packet;
    }

    private static int dimensionId(Instance instance) {
        final DimensionType dimensionType = instance.getCachedDimensionType();
        if (dimensionType.cardinalLight() == DimensionType.CardinalLight.NETHER) return 1;
        if (dimensionType.skybox() == DimensionType.Skybox.END) return 2;
        return 0;
    }
}
