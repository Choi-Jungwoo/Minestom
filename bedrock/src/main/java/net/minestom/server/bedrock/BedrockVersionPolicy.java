package net.minestom.server.bedrock;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bedrock 线路协议的接受与支持策略。
 *
 * <p>“接受”仅表示可以解码并按实验能力尽力连接；“支持”还要求匹配的映射与自动化兼容测试。
 *
 * @param primaryProtocol   服务器列表广告使用的主要受支持协议
 * @param acceptedProtocols 可实验连接的协议，顺序用于诊断信息
 * @param supportedProtocols 已通过承诺功能兼容套件的协议
 */
public record BedrockVersionPolicy(
        int primaryProtocol,
        List<Integer> acceptedProtocols,
        Set<Integer> supportedProtocols) {

    public BedrockVersionPolicy {
        acceptedProtocols = List.copyOf(
                Objects.requireNonNull(acceptedProtocols, "acceptedProtocols"));
        supportedProtocols = Set.copyOf(
                Objects.requireNonNull(supportedProtocols, "supportedProtocols"));
        if (acceptedProtocols.isEmpty()) {
            throw new IllegalArgumentException("acceptedProtocols must not be empty");
        }
        if (new HashSet<>(acceptedProtocols).size() != acceptedProtocols.size()) {
            throw new IllegalArgumentException("acceptedProtocols must not contain duplicates");
        }
        for (int protocol : acceptedProtocols) {
            if (BedrockProtocol.codec(protocol) == null) {
                throw new IllegalArgumentException("No Bedrock codec for protocol " + protocol);
            }
        }
        if (!acceptedProtocols.containsAll(supportedProtocols)) {
            throw new IllegalArgumentException(
                    "supportedProtocols must be a subset of acceptedProtocols");
        }
        if (!BedrockCompatibility.SUPPORTED_PROTOCOLS.containsAll(supportedProtocols)) {
            throw new IllegalArgumentException(
                    "supportedProtocols must not exceed the verified build compatibility");
        }
        if (!supportedProtocols.contains(primaryProtocol)) {
            throw new IllegalArgumentException(
                    "primaryProtocol must be a supported protocol");
        }
    }

    /**
     * 返回构建元数据声明的默认兼容策略。
     *
     * @return 不可变默认策略
     */
    public static BedrockVersionPolicy defaults() {
        return new BedrockVersionPolicy(
                BedrockCompatibility.PRIMARY_PROTOCOL,
                BedrockCompatibility.ACCEPTED_PROTOCOLS,
                BedrockCompatibility.SUPPORTED_PROTOCOLS);
    }

    boolean accepts(int protocol) {
        return acceptedProtocols.contains(protocol);
    }

    String acceptedProtocolsDescription() {
        return acceptedProtocols.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
    }
}
