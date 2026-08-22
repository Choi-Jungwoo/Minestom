package net.minestom.server.bedrock;

import java.util.Objects;

/**
 * Bedrock 服务器列表中没有对应 Minestom {@code Status} 字段的静态广告信息。
 *
 * @param edition          Bedrock 版本标识
 * @param subMotd          次级描述
 * @param gameType         游戏模式描述
 * @param nintendoLimited  是否限制 Nintendo 平台
 */
public record BedrockAdvertisement(
        String edition,
        String subMotd,
        String gameType,
        boolean nintendoLimited) {
    private static final BedrockAdvertisement DEFAULTS =
            new BedrockAdvertisement("MCPE", "Minestom", "Survival", false);

    public BedrockAdvertisement {
        edition = requireText(edition, "edition");
        subMotd = requireText(subMotd, "subMotd");
        gameType = requireText(gameType, "gameType");
    }

    /**
     * 返回生产环境默认广告信息。
     *
     * @return 不可变默认配置
     */
    public static BedrockAdvertisement defaults() {
        return DEFAULTS;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
