package net.minestom.server.bedrock;

import net.minestom.server.network.player.PlayerConnection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BedrockConnectionTest {
    @Test
    void isAFinalPlayerConnectionWithoutCloudburstPublicApi() {
        assertEquals(PlayerConnection.class, BedrockConnection.class.getSuperclass());
        assertTrue(Modifier.isFinal(BedrockConnection.class.getModifiers()));
        assertFalse(Arrays.stream(BedrockConnection.class.getMethods())
                .flatMap(method -> Stream.concat(
                        Stream.of(method.getReturnType()),
                        Arrays.stream(method.getParameterTypes())))
                .map(Class::getName)
                .anyMatch(name -> name.startsWith("org.cloudburstmc.")));
    }

    @Test
    void derivesTheConventionalOfflineUuidFromTheName() {
        assertEquals(
                UUID.fromString("0c7651f2-577a-3b92-8ff9-3faa54136489"),
                BedrockConnection.offlineUuid("LoopbackPlayer"));
    }
}
