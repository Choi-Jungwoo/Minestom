package net.minestom.server.bedrock;

import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BedrockLoginValidatorTest {
    @Test
    void fullAuthenticationIsRejectedBeforeJwtParsing() {
        final var login = new LoginPacket();
        login.setAuthPayload(
                new CertificateChainPayload(List.of("not-a-jwt"), AuthType.FULL));
        login.setClientJwt("not-a-jwt");

        final var exception = assertThrows(
                IllegalArgumentException.class,
                () -> BedrockLoginValidator.validate(login, BedrockServerLimits.defaults()));

        assertEquals("Only offline login is supported", exception.getMessage());
    }
}
