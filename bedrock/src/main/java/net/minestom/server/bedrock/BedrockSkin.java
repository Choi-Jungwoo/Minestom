package net.minestom.server.bedrock;

import org.cloudburstmc.protocol.bedrock.data.skin.ImageData;
import org.cloudburstmc.protocol.bedrock.data.skin.SerializedSkin;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class BedrockSkin {
    private static final int WIDTH = 64;
    private static final int MAX_HEIGHT = 64;
    private static final int CAPE_WIDTH = 64;
    private static final int CAPE_HEIGHT = 32;
    private static final int CAPE_PIXEL_BYTES = CAPE_WIDTH * CAPE_HEIGHT * 4;
    private static final int MAX_PIXEL_BYTES = WIDTH * MAX_HEIGHT * 4;
    private static final int MAX_ENCODED_PIXEL_BYTES = ((MAX_PIXEL_BYTES + 2) / 3) * 4;
    private static final int MAX_SKIN_ID_LENGTH = 128;
    private static final String CLASSIC_RESOURCE_PATCH =
            "{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}";

    private final SerializedSkin serialized;
    private final Source source;

    private BedrockSkin(SerializedSkin serialized, Source source) {
        this.serialized = Objects.requireNonNull(serialized, "serialized");
        this.source = Objects.requireNonNull(source, "source");
    }

    static BedrockSkin classic(
            Map<String, Object> clientData,
            BedrockServerLimits limits) {
        Objects.requireNonNull(limits, "limits");
        if (requiredBoolean(clientData, "PersonaSkin")) {
            throw new IllegalArgumentException("Persona skins are not supported");
        }
        final Cape cape = cape(clientData, limits.maxCapeBytes());
        final Geometry geometry = geometry(clientData, limits.maxGeometryBytes());
        final int width = requiredDimension(clientData, "SkinImageWidth");
        final int height = requiredDimension(clientData, "SkinImageHeight");
        if (width != WIDTH || (height != 32 && height != MAX_HEIGHT)) {
            throw new IllegalArgumentException("Skin dimensions exceed the classic skin limit");
        }

        final String encodedPixels = requiredString(clientData, "SkinData");
        if (encodedPixels.length() > MAX_ENCODED_PIXEL_BYTES) {
            throw new IllegalArgumentException("Skin data exceeds the classic skin limit");
        }
        final byte[] pixels;
        try {
            pixels = Base64.getDecoder().decode(encodedPixels);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Skin data is not valid base64", exception);
        }
        if (pixels.length != width * height * 4) {
            throw new IllegalArgumentException("Skin data does not match its dimensions");
        }

        final String skinId = requiredString(clientData, "SkinId");
        if (skinId.length() > MAX_SKIN_ID_LENGTH) {
            throw new IllegalArgumentException("Skin id is too long");
        }
        final String armSize = requiredString(clientData, "ArmSize");
        if (!armSize.equals("wide") && !armSize.equals("slim")) {
            throw new IllegalArgumentException("Classic skin has an invalid arm size");
        }
        return new BedrockSkin(
                serialize(skinId, width, height, pixels, armSize, cape, geometry),
                Source.BEDROCK_CLASSIC);
    }

    static BedrockSkin generated(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        final byte[] pixels = new byte[MAX_PIXEL_BYTES];
        final long seed = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        final byte red = (byte) (0x40 | (seed & 0x3F));
        final byte green = (byte) (0x60 | ((seed >>> 8) & 0x3F));
        final byte blue = (byte) (0x80 | ((seed >>> 16) & 0x3F));
        for (int index = 0; index < pixels.length; index += 4) {
            final int pixel = index / 4;
            final boolean accent = ((pixel % WIDTH) / 8 + (pixel / WIDTH) / 8) % 2 == 0;
            pixels[index] = accent ? red : (byte) (red ^ 0x2A);
            pixels[index + 1] = accent ? green : (byte) (green ^ 0x25);
            pixels[index + 2] = accent ? blue : (byte) (blue ^ 0x1F);
            pixels[index + 3] = (byte) 0xFF;
        }
        final String skinId = "minestom-generated:" + uuid;
        return new BedrockSkin(
                serialize(
                        skinId,
                        WIDTH,
                        MAX_HEIGHT,
                        pixels,
                        "wide",
                        Cape.EMPTY,
                        Geometry.DEFAULT),
                Source.GENERATED);
    }

    SerializedSkin serialized() {
        return serialized;
    }

    Source source() {
        return source;
    }

    private static SerializedSkin serialize(
            String skinId,
            int width,
            int height,
            byte[] pixels,
            String armSize,
            Cape cape,
            Geometry geometry) {
        final SerializedSkin skin = SerializedSkin.of(
                        skinId,
                        "",
                        ImageData.of(width, height, pixels.clone()),
                        cape.image(),
                        geometry.data(),
                        geometry.resourcePatch(),
                        false)
                .toBuilder()
                .armSize(armSize)
                .capeId(cape.id())
                .capeOnClassic(cape.onClassic())
                .geometryDataEngineVersion(geometry.engineVersion())
                .trusted(true)
                .build();
        if (skin.isValid()) return skin;
        return skin.toBuilder()
                .skinResourcePatch(CLASSIC_RESOURCE_PATCH)
                .build();
    }

    private static Cape cape(Map<String, Object> values, int maximumBytes) {
        final Object value = values.get("CapeData");
        if (value == null) return Cape.EMPTY;
        if (!(value instanceof String encoded)) {
            throw new IllegalArgumentException("Cape data is not a string");
        }
        if (encoded.isEmpty()) return Cape.EMPTY;

        final int allowedBytes = Math.min(maximumBytes, CAPE_PIXEL_BYTES);
        final long maximumEncodedBytes = ((long) allowedBytes + 2) / 3 * 4;
        if (encoded.length() > maximumEncodedBytes) {
            throw new IllegalArgumentException("Cape data exceeds the login limit");
        }
        final byte[] pixels;
        try {
            pixels = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Cape data is not valid base64", exception);
        }
        if (pixels.length > allowedBytes) {
            throw new IllegalArgumentException("Cape data exceeds the login limit");
        }

        final int width = requiredDimension(values, "CapeImageWidth");
        final int height = requiredDimension(values, "CapeImageHeight");
        if (width != CAPE_WIDTH
                || height != CAPE_HEIGHT
                || pixels.length != CAPE_PIXEL_BYTES) {
            throw new IllegalArgumentException("Cape data does not match classic dimensions");
        }
        final String id = optionalString(values, "CapeId", MAX_SKIN_ID_LENGTH);
        final boolean onClassic = optionalBoolean(values, "CapeOnClassic");
        return new Cape(
                ImageData.of(width, height, pixels.clone()),
                id,
                onClassic);
    }

    private static Geometry geometry(Map<String, Object> values, int maximumBytes) {
        return new Geometry(
                optionalBase64Text(
                        values,
                        "SkinGeometryData",
                        maximumBytes,
                        "Skin geometry data",
                        Geometry.DEFAULT.data()),
                optionalText(
                        values,
                        "SkinGeometryDataEngineVersion",
                        maximumBytes,
                        "Skin geometry engine version",
                        Geometry.DEFAULT.engineVersion()),
                optionalBase64Text(
                        values,
                        "SkinResourcePatch",
                        maximumBytes,
                        "Skin resource patch",
                        Geometry.DEFAULT.resourcePatch()));
    }

    private static int requiredDimension(Map<String, Object> values, String key) {
        final Object value = values.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Client data is missing " + key);
        }
        final int result = number.intValue();
        if (result <= 0 || number.longValue() != result) {
            throw new IllegalArgumentException("Client data has invalid " + key);
        }
        return result;
    }

    private static boolean requiredBoolean(Map<String, Object> values, String key) {
        final Object value = values.get(key);
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException("Client data is missing " + key);
        }
        return result;
    }

    private static String requiredString(Map<String, Object> values, String key) {
        final Object value = values.get(key);
        if (!(value instanceof String result) || result.isBlank()) {
            throw new IllegalArgumentException("Client data is missing " + key);
        }
        return result;
    }

    private static String optionalString(
            Map<String, Object> values,
            String key,
            int maximumLength) {
        final Object value = values.get(key);
        if (value == null) return "";
        if (!(value instanceof String result)) {
            throw new IllegalArgumentException("Client data has invalid " + key);
        }
        if (result.length() > maximumLength) {
            throw new IllegalArgumentException("Client data has overlong " + key);
        }
        return result;
    }

    private static boolean optionalBoolean(Map<String, Object> values, String key) {
        final Object value = values.get(key);
        if (value == null) return false;
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException("Client data has invalid " + key);
        }
        return result;
    }

    private static String optionalBase64Text(
            Map<String, Object> values,
            String key,
            int maximumBytes,
            String label,
            String defaultValue) {
        final Object value = values.get(key);
        if (value == null) return defaultValue;
        if (!(value instanceof String encoded)) {
            throw new IllegalArgumentException(label + " is not a string");
        }
        if (encoded.isEmpty()) return defaultValue;
        final long maximumEncodedBytes = ((long) maximumBytes + 2) / 3 * 4;
        if (encoded.length() > maximumEncodedBytes) {
            throw new IllegalArgumentException(label + " exceeds the login limit");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(label + " is not valid base64", exception);
        }
        if (decoded.length > maximumBytes) {
            throw new IllegalArgumentException(label + " exceeds the login limit");
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(label + " is not valid UTF-8", exception);
        }
    }

    private static String optionalText(
            Map<String, Object> values,
            String key,
            int maximumBytes,
            String label,
            String defaultValue) {
        final Object value = values.get(key);
        if (value == null) return defaultValue;
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(label + " is not a string");
        }
        if (text.isEmpty()) return defaultValue;
        if (text.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException(label + " exceeds the login limit");
        }
        return text;
    }

    enum Source {
        BEDROCK_CLASSIC,
        GENERATED
    }

    private record Cape(ImageData image, String id, boolean onClassic) {
        private static final Cape EMPTY = new Cape(ImageData.EMPTY, "", false);
    }

    private record Geometry(String data, String engineVersion, String resourcePatch) {
        private static final Geometry DEFAULT =
                new Geometry("", "0.0.0", CLASSIC_RESOURCE_PATCH);
    }
}
