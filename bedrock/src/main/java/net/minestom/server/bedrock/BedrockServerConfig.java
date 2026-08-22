package net.minestom.server.bedrock;

import net.minestom.server.instance.Instance;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 启动原生 Bedrock 监听器所需的不可变配置。
 *
 * @param address          UDP 绑定地址
 * @param spawningInstance 分配给已准入 Bedrock 玩家的实例
 * @param mappingsSource   操作者提供的精确版本映射源目录
 * @param rakNetGuid       RakNet 服务器 GUID
 * @param limits           监听器与协议资源上限
 * @param versionPolicy    接受与支持的 Bedrock 协议策略
 * @param advertisement    Bedrock 专用服务器列表字段
 */
public record BedrockServerConfig(
        InetSocketAddress address,
        Instance spawningInstance,
        Path mappingsSource,
        long rakNetGuid,
        BedrockServerLimits limits,
        BedrockVersionPolicy versionPolicy,
        BedrockAdvertisement advertisement) {

    public BedrockServerConfig {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(spawningInstance, "spawningInstance");
        mappingsSource = Objects.requireNonNull(
                mappingsSource, "mappingsSource").toAbsolutePath().normalize();
        if (rakNetGuid == 0) throw new IllegalArgumentException("rakNetGuid must not be zero");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(versionPolicy, "versionPolicy");
        Objects.requireNonNull(advertisement, "advertisement");
    }

    /**
     * 使用安全的生产默认策略创建配置。
     *
     * @param address          UDP 绑定地址
     * @param spawningInstance 玩家出生实例
     * @param mappingsSource   映射源目录
     */
    public BedrockServerConfig(
            InetSocketAddress address,
            Instance spawningInstance,
            Path mappingsSource) {
        this(
                address,
                spawningInstance,
                mappingsSource,
                newRakNetGuid(),
                BedrockServerLimits.defaults(),
                BedrockVersionPolicy.defaults(),
                BedrockAdvertisement.defaults());
    }

    private static long newRakNetGuid() {
        long guid;
        do {
            guid = ThreadLocalRandom.current().nextLong();
        } while (guid == 0);
        return guid;
    }
}
