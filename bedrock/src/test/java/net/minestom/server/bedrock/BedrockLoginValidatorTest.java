package net.minestom.server.bedrock;

import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BedrockLoginValidatorTest {
    @Test
    void fullCertificateAuthenticationReachesTheAuthenticatedValidator() {
        assertReachesAuthenticatedValidator(
                new CertificateChainPayload(List.of("not-a-jwt"), AuthType.FULL));
    }

    @Test
    void fullTokenAuthenticationReachesTheAuthenticatedValidator() {
        assertReachesAuthenticatedValidator(new TokenPayload("not-a-jwt", AuthType.FULL));
    }

    private static void assertReachesAuthenticatedValidator(AuthPayload authPayload) {
        final var login = new LoginPacket();
        login.setAuthPayload(authPayload);
        login.setClientJwt("not-a-jwt");

        final var exception = assertThrows(
                Exception.class,
                () -> BedrockLoginValidator.validate(login, BedrockServerLimits.defaults()));

        assertNotEquals("Only offline login is supported", exception.getMessage());
    }
}
