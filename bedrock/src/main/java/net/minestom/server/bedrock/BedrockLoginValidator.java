package net.minestom.server.bedrock;

import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.jose4j.json.JsonUtil;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwx.HeaderParameterNames;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

final class BedrockLoginValidator {
    private static final long CLOCK_SKEW_SECONDS = 60;

    private BedrockLoginValidator() {
    }

    static VerifiedLogin validate(LoginPacket login) throws Exception {
        final AuthType authType = login.getAuthPayload().getAuthType();
        if (authType != AuthType.SELF_SIGNED && authType != AuthType.GUEST) {
            throw new IllegalArgumentException("Only offline login is supported");
        }
        if (!(login.getAuthPayload() instanceof CertificateChainPayload chainPayload)
                || chainPayload.getChain().size() != 1) {
            throw new IllegalArgumentException("Offline login requires one identity certificate");
        }

        final JsonWebSignature identityJwt = verifiedJwt(chainPayload.getChain().getFirst());
        final Map<String, Object> identityClaims = JsonUtil.parseJson(identityJwt.getUnverifiedPayload());
        validateTimes(identityClaims);
        final String identityPublicKey = requiredString(identityClaims, "identityPublicKey");
        final PublicKey clientKey = EncryptionUtils.parseKey(identityPublicKey);
        if (!clientKey.equals(identityJwt.getKey())) {
            throw new IllegalArgumentException("Identity certificate changed its public key");
        }

        final Map<String, Object> extraData = requiredMap(identityClaims, "extraData");
        final String name = requiredString(extraData, "displayName");
        if (name.length() > 16) throw new IllegalArgumentException("Bedrock name is too long");
        UUID.fromString(requiredString(extraData, "identity"));
        requiredStringAllowEmpty(extraData, "XUID");

        final JsonWebSignature clientJwt = verifiedJwt(login.getClientJwt(), clientKey);
        final Map<String, Object> clientData = JsonUtil.parseJson(clientJwt.getUnverifiedPayload());
        validateTimes(clientData);
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
        return new VerifiedLogin(clientKey);
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

    record VerifiedLogin(PublicKey clientKey) {
    }
}
