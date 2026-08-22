package net.minestom.server.bedrock;

import org.cloudburstmc.protocol.bedrock.data.skin.ImageData;
import org.cloudburstmc.protocol.bedrock.data.skin.SerializedSkin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class BedrockSkin {
    private static final int WIDTH = 64;
    private static final int MAX_HEIGHT = 64;
    private static final int MAX_PIXEL_BYTES = WIDTH * MAX_HEIGHT * 4;
    private static final int MAX_ENCODED_PIXEL_BYTES = ((MAX_PIXEL_BYTES + 2) / 3) * 4;
    private static final int MAX_SKIN_ID_LENGTH = 128;

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
        rejectUnsupportedBase64(
                clientData, "CapeData", limits.maxCapeBytes(), "Cape data");
        rejectUnsupportedBase64(
                clientData,
                "SkinGeometryData",
                limits.maxGeometryBytes(),
                "Skin geometry data");
        rejectUnsupportedText(
                clientData,
                "SkinGeometryDataEngineVersion",
                limits.maxGeometryBytes(),
                "Skin geometry engine version");
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
                serialize(skinId, width, height, pixels, armSize),
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
                serialize(skinId, WIDTH, MAX_HEIGHT, pixels, "wide"),
                Source.GENERATED);
    }

    SerializedSkin serialized() {
        return serialized;
    }

    Source source() {
        return source;
    }

    private static SerializedSkin serialize(
            String skinId, int width, int height, byte[] pixels, String armSize) {
        return SerializedSkin.of(
                        skinId,
                        "",
                        ImageData.of(width, height, pixels.clone()),
                        ImageData.EMPTY,
                        "geometry.humanoid.custom",
                        "",
                        false)
                .toBuilder()
                .armSize(armSize)
                .trusted(true)
                .build();
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

    private static void rejectUnsupportedBase64(
            Map<String, Object> values,
            String key,
            int maximumBytes,
            String label) {
        final Object value = values.get(key);
        if (value == null) return;
        if (!(value instanceof String encoded)) {
            throw new IllegalArgumentException(label + " is not a string");
        }
        if (encoded.isEmpty()) return;
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
        throw new IllegalArgumentException(label + " is not supported");
    }

    private static void rejectUnsupportedText(
            Map<String, Object> values,
            String key,
            int maximumBytes,
            String label) {
        final Object value = values.get(key);
        if (value == null) return;
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(label + " is not a string");
        }
        if (text.isEmpty()) return;
        if (text.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException(label + " exceeds the login limit");
        }
        throw new IllegalArgumentException(label + " is not supported");
    }

    enum Source {
        BEDROCK_CLASSIC,
        GENERATED
    }
}
