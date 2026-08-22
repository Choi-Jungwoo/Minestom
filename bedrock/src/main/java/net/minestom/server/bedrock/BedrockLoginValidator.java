package net.minestom.server.bedrock;

import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.jose4j.json.JsonUtil;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwx.HeaderParameterNames;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

final class BedrockLoginValidator {
    private static final long CLOCK_SKEW_SECONDS = 60;

    private BedrockLoginValidator() {
    }

    static VerifiedLogin validate(LoginPacket login, BedrockServerLimits limits) throws Exception {
        final AuthPayload authPayload = login.getAuthPayload();
        final String compactClientJwt = login.getClientJwt();
        final long jwtBytes = authPayloadBytes(authPayload) + utf8Length(compactClientJwt);
        if (jwtBytes > limits.maxJwtBytes()) {
            throw new IllegalArgumentException("JWT input exceeds the login limit");
        }

        final AuthType authType = authPayload.getAuthType();
        final Identity identity;
        if (authType == AuthType.FULL) {
            identity = authenticatedIdentity(authPayload);
        } else if (authType == AuthType.SELF_SIGNED || authType == AuthType.GUEST) {
            identity = offlineIdentity(authPayload);
        } else {
            throw new IllegalArgumentException("Unsupported Bedrock authentication type");
        }

        final String name = identity.name();
        final Map<String, Object> clientData =
                clientData(compactClientJwt, identity.clientKey());
        // Client-data JWTs inherit trust from the expiring identity certificate and have no time claims.
        if (!name.equals(requiredString(clientData, "ThirdPartyName"))) {
            throw new IllegalArgumentException("Client data name does not match the identity");
        }
        if (!(clientData.get("DeviceOS") instanceof Number)) {
            throw new IllegalArgumentException("Client data is missing DeviceOS");
        }
        requiredString(clientData, "DeviceId");
        final String gameVersion = requiredString(clientData, "GameVersion");
        if (!gameVersion.startsWith("1.26.")) {
            throw new IllegalArgumentException("Client data is not for Bedrock 1.26");
        }
        final BedrockSkin skin = BedrockSkin.classic(clientData, limits);
        return new VerifiedLogin(identity.clientKey(), name, skin);
    }

    private static Identity authenticatedIdentity(AuthPayload authPayload) throws Exception {
        final ChainValidationResult result = EncryptionUtils.validatePayload(authPayload);
        if (!result.signed()) {
            throw new IllegalArgumentException("Bedrock identity is not signed by Mojang");
        }
        final ChainValidationResult.IdentityClaims claims = result.identityClaims();
        if (claims.extraData == null) {
            throw new IllegalArgumentException("Bedrock identity is missing extra data");
        }
        final String name = claims.extraData.displayName;
        validateName(name);
        return new Identity(claims.parsedIdentityPublicKey(), name);
    }

    private static Identity offlineIdentity(AuthPayload authPayload) throws Exception {
        if (!(authPayload instanceof CertificateChainPayload chainPayload)
                || chainPayload.getChain().size() != 1) {
            throw new IllegalArgumentException("Offline login requires one identity certificate");
        }

        final String compactIdentityJwt = chainPayload.getChain().getFirst();
        final JsonWebSignature identityJwt = verifiedJwt(compactIdentityJwt);
        final Map<String, Object> identityClaims = JsonUtil.parseJson(identityJwt.getUnverifiedPayload());
        validateTimes(identityClaims);
        final String identityPublicKey = requiredString(identityClaims, "identityPublicKey");
        final PublicKey clientKey = EncryptionUtils.parseKey(identityPublicKey);
        if (!clientKey.equals(identityJwt.getKey())) {
            throw new IllegalArgumentException("Identity certificate changed its public key");
        }

        final Map<String, Object> extraData = requiredMap(identityClaims, "extraData");
        final String name = requiredString(extraData, "displayName");
        validateName(name);
        UUID.fromString(requiredString(extraData, "identity"));
        requiredStringAllowEmpty(extraData, "XUID");
        return new Identity(clientKey, name);
    }

    private static Map<String, Object> clientData(String compactClientJwt, PublicKey clientKey)
            throws Exception {
        final JsonWebSignature clientJwt = verifiedJwt(compactClientJwt, clientKey);
        final Map<String, Object> clientData = JsonUtil.parseJson(clientJwt.getUnverifiedPayload());
        return clientData;
    }

    private static long authPayloadBytes(AuthPayload authPayload) {
        if (authPayload instanceof CertificateChainPayload chainPayload) {
            long bytes = 0;
            for (String certificate : chainPayload.getChain()) {
                bytes = Math.addExact(bytes, utf8Length(certificate));
            }
            return bytes;
        }
        if (authPayload instanceof TokenPayload tokenPayload) {
            return utf8Length(tokenPayload.getToken());
        }
        throw new IllegalArgumentException("Unsupported Bedrock authentication payload");
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Bedrock identity has no display name");
        }
        if (name.length() > 16) {
            throw new IllegalArgumentException("Bedrock name is too long");
        }
    }

    private static JsonWebSignature verifiedJwt(String compactJwt) throws Exception {
        final JsonWebSignature jwt = new JsonWebSignature();
        jwt.setCompactSerialization(compactJwt);
        final String encodedKey = jwt.getHeader(HeaderParameterNames.X509_URL);
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalArgumentException("Identity certificate has no public key");
        }
        return verifiedJwt(jwt, EncryptionUtils.parseKey(encodedKey));
    }

    private static JsonWebSignature verifiedJwt(String compactJwt, PublicKey key) throws Exception {
        final JsonWebSignature jwt = new JsonWebSignature();
        jwt.setCompactSerialization(compactJwt);
        return verifiedJwt(jwt, key);
    }

    private static JsonWebSignature verifiedJwt(JsonWebSignature jwt, PublicKey key) throws Exception {
        jwt.setAlgorithmConstraints(new AlgorithmConstraints(
                AlgorithmConstraints.ConstraintType.PERMIT, EncryptionUtils.ALGORITHM_TYPE));
        jwt.setKey(key);
        if (!jwt.verifySignature()) {
            throw new IllegalArgumentException("JWT signature is invalid");
        }
        return jwt;
    }

    private static void validateTimes(Map<String, Object> claims) {
        final long now = Instant.now().getEpochSecond();
        final long issuedAt = requiredNumber(claims, "iat").longValue();
        final long expiresAt = requiredNumber(claims, "exp").longValue();
        if (issuedAt > now + CLOCK_SKEW_SECONDS) {
            throw new IllegalArgumentException("JWT was issued in the future");
        }
        if (expiresAt <= now - CLOCK_SKEW_SECONDS) {
            throw new IllegalArgumentException("JWT has expired");
        }
        if (expiresAt <= issuedAt) {
            throw new IllegalArgumentException("JWT expires before it was issued");
        }
    }

    private static String requiredString(Map<?, ?> values, String key) {
        final Object value = values.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException("JWT is missing " + key);
        }
        return string;
    }

    private static Number requiredNumber(Map<?, ?> values, String key) {
        final Object value = values.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("JWT is missing " + key);
        }
        return number;
    }

    private static String requiredStringAllowEmpty(Map<?, ?> values, String key) {
        final Object value = values.get(key);
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("JWT is missing " + key);
        }
        return string;
    }

    private static Map<String, Object> requiredMap(Map<?, ?> values, String key) {
        final Object value = values.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("JWT is missing " + key);
        }
        @SuppressWarnings("unchecked") final Map<String, Object> result = (Map<String, Object>) map;
        return result;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private record Identity(PublicKey clientKey, String name) {
    }

    record VerifiedLogin(PublicKey clientKey, String name, BedrockSkin skin) {
    }
}
