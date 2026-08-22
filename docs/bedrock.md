# 实验性原生 Bedrock 适配器

`bedrock` 模块通过 UDP/RakNet 直接接收 Bedrock 客户端，并把它们准入为普通
Minestom `Player`。该模块是可选的：只使用 Java 协议的应用不会引入 Cloudburst、
RakNet、Netty 原生传输或映射依赖。

由于固定的 Cloudburst 版本仍需从 OpenCollab 仓库解析，本模块保持实验状态且不发布到
Maven Central。Bedrock 监听器不会随 Java 监听器自动启动。

## 启动监听器

以下示例适用于包含 `implementation(project(":bedrock"))` 的源码构建：

```java
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerProcess;
import net.minestom.server.bedrock.BedrockServer;
import net.minestom.server.bedrock.BedrockServerConfig;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Objects;

public final class BedrockExample {
    public static void main(String[] arguments) {
        MinecraftServer minecraftServer = MinecraftServer.init(new Auth.Offline());
        ServerProcess process = Objects.requireNonNull(MinecraftServer.process());
        InstanceContainer instance = process.instance().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));

        var config = new BedrockServerConfig(
                new InetSocketAddress("0.0.0.0", 19132),
                instance,
                Path.of("run/bedrock-mappings"));
        BedrockServer bedrockServer = BedrockServer.create(process, config);
        bedrockServer.start();

        minecraftServer.start("0.0.0.0", 25565);
    }
}
```

UDP 可以与 Java TCP 监听器使用相同的数字端口。`stop()` 可重复调用，进程关闭时也会
停止监听器和所属会话。每个 `ServerProcess` 只能拥有一个 `BedrockServer`。

三参数 `BedrockServerConfig` 使用安全默认值。完整构造器还可以显式配置：

- RakNet GUID；
- `BedrockServerLimits` 网络、JWT、cape 与 geometry 资源上限；
- `BedrockVersionPolicy` 接受/支持版本策略；
- `BedrockAdvertisement` 的 Bedrock 专用服务器列表字段；
- 精确版本的操作者映射源目录。

动态描述与玩家人数仍由现有 `ServerListPingEvent`/`Status` 控制。

## 兼容策略

- **支持版本：**协议 1001 / Bedrock 线路版本 1.26.30，使用 Bedrock 1.26.30.5
  到 Java 26.2 的映射，并通过自动化兼容套件。
- **实验性接受版本：**协议 924、944、975、2168 和 2169。Cloudburst 能按对应
  schema 解码这些线路格式，但基线映射与承诺功能套件并不保证其玩法兼容性。
- 不属于当前 Cloudburst Bedrock 1.26.x codec 的协议会在玩家准入前被拒绝。
- 服务器列表始终广告主要支持协议，不会广告某个实验性客户端选择的 codec。

“接受版本”只表示可以解码并尽力连接；“支持版本”还必须有匹配映射，并通过承诺功能的
自动化兼容套件。运行 `mise run bedrock-compatibility` 可查看所有不可变依赖、映射和
协议固定值。其唯一维护源是
`gradle/bedrock-compatibility.properties`，构建会把同一文件写入产物
`META-INF/minestom-bedrock.properties`。

## 功能支持矩阵

| 能力 | 状态 | 边界 |
| --- | --- | --- |
| UDP 发现与协议选择 | 支持 | 发现广告协议 1001 |
| 离线自签名或访客登录 | 支持 | 不证明客户端拥有在线 Microsoft 账户 |
| 加密、压缩、空资源包握手 | 支持 | 不支持自定义资源包 |
| StartGame 与初始 Instance 加载 | 支持 | 需要精确校验过的映射 |
| 区块、卸载、方块更新、基础移动 | 支持 | 拒绝高级移动能力 |
| 位置纠正、传送、Instance 切换 | 支持 | 不支持完整死亡/重生流程 |
| 多玩家可见性与基础装备 | 支持 | 不支持非玩家 Entity |
| Bedrock 经典皮肤与生成的 Java 回退皮肤 | 支持 | 拒绝 Persona、cape 与 geometry 输入 |
| 普通聊天与斜杠命令 | 支持 | 不保证签名聊天，不发送命令树/补全 |
| 延迟、踢出、断开、转服、关闭 | 支持 | 稳定客户端原因会隐藏内部故障 |
| 物品栏事务、容器、交互 | 不支持 | 不支持合成、方块使用或实体交互 |
| Xbox/Microsoft 在线认证 | 不支持 | 仅提供离线 Bedrock 身份 |

通过自动化套件不表示支持非玩家实体、命令树补全、Persona 皮肤或在线账户认证。
官方客户端手工测试有帮助，但不是发布门禁。

出站包的内部翻译结果分为三类：已翻译、明确忽略的非关键包、缺失关键翻译。只有明确
列入非关键白名单的包会被忽略；未知结构化包或 Java 原始缓冲包会使用稳定原因断开，
并把脱敏后的协议、状态和包类型交给现有异常管理器与限速诊断。

## 映射来源与门禁

受支持映射包包含面向 Java 26.2 / Bedrock 1.26.30.5 的 Geyser mappings，以及对应的
Geyser `block_palette.26_30.nbt`。准确来源、提交和 SHA-256 均记录在
`gradle/bedrock-compatibility.properties`。

映射由持续维护的
[`GeyserMC/mappings-generator`](https://github.com/GeyserMC/mappings-generator)
生成；其上游流程使用 `runMappings`/`runDatagen`，并要求人工检查警告和不完整映射。

在项目明确记录 Mojang/Microsoft 数据再分发许可之前，映射不会随模块打包。每位操作者
都必须取得固定输入并提供已校验目录；不要提交、发布或镜像准备后的目录。

一种可复现的准备流程如下：

```shell
pins=gradle/bedrock-compatibility.properties
mappings_source=$(sed -n 's/^mappings.source=//p' "$pins")
mappings_commit=$(sed -n 's/^mappings.commit=//p' "$pins")
palette_source=$(sed -n 's/^mappings.runtime-palette.source=//p' "$pins")
palette_commit=$(sed -n 's/^mappings.runtime-palette.commit=//p' "$pins")

git clone "$mappings_source" /tmp/minestom-mappings
git -C /tmp/minestom-mappings checkout --detach "$mappings_commit"
curl --fail --location \
  "$palette_source/raw/$palette_commit/core/src/main/resources/bedrock/block_palette.26_30.nbt" \
  --output /tmp/minestom-mappings/block_palette.26_30.nbt
mise run bedrock-mappings-verify /tmp/minestom-mappings
mise run bedrock-mappings-prepare \
  /tmp/minestom-mappings run/bedrock-mappings
```

准备任务会拒绝已存在的目标，只复制必要的常规文件，不跟随符号链接，并再次校验目标。

### 更新固定值

1. 在独立文件中选择固定的 Geyser mappings、Geyser runtime palette、Cloudburst、
   RakNet 与 Netty 版本；不得使用 `latest` 或浮动 snapshot。
2. 生成或取得映射输出，检查生成器警告、许可、registry 覆盖和对应 codec。
3. 计算聚合映射与 palette 校验和，更新该审查文件中的全部属性。
4. 运行 `mise run bedrock-update <reviewed-properties>`；该单一任务会校验不可变版本、
   提交和校验和，再安装兼容清单。
5. 运行 `mise run bedrock-mappings-verify`、`mise run bedrock-acceptance` 和
   `mise run check`。
6. 只有匹配映射和完整自动化验收全部通过后，才能把 codec 标为支持；否则只能标为接受。
7. 嵌入或再分发新映射包前，必须取得并记录再分发许可。

## 验证

- `mise run check`：运行所有模块测试、格式/静态检查和带标签的真实 UDP 验收套件。
- `mise run bedrock-acceptance`：验证发现、精确 codec 协商、离线登录、StartGame/
  资源包、Instance、移动/传送、多人、聊天/命令、转服、断开以及接受/支持门禁。
- `mise run bedrock-check`：运行完整 Bedrock 模块校验。
- `mise run bedrock-mappings-verify <directory>`：校验发布身份、两个校验和、必要常规
  文件、NBT/JSON 结构和关键 registry 覆盖。
- `mise run bedrock-compatibility`：报告构建实际使用的全部兼容固定值。

## 运维

### 端口与生命周期

- 在主机与网络防火墙中开放配置的 UDP 端口（通常为 19132）；只有 TCP 规则不够。
- 创建出生 `Instance` 后显式启动监听器。
- 通过正常 Minestom 进程生命周期停止。关闭会停止准入、断开会话、移除玩家、关闭 UDP
  channel，并终止所属 event-loop 线程。
- 绑定失败通常表示另一个 UDP 监听器占用了地址，或进程没有相应权限。

### 容量与速率限制

`BedrockServerLimits.defaults()` 允许 1,024 个并发连接，以及每个源地址每秒 20 次连接
尝试。它还限制 RakNet MTU、未压缩包（1 MiB）、压缩批次（2 MiB）、解压批次
（8 MiB）、每个连接每 tick 入站包数（256）和登录 JWT 总量（1 MiB）。经典皮肤宽度
限制为 64 像素，高度为 32 或 64 像素；cape 与 geometry 分别有独立解码字节上限。
首个版本不实现这两类外观数据，因此任何非空输入即使未超限也会被拒绝。

资源受限时，把应用专用的 `BedrockServerLimits` 放入完整 `BedrockServerConfig`。
速率限制是安全边界，但不能替代网络层 DDoS 防护。

### 日志与隐私

协议诊断会限速，并包含协议、Bedrock 状态、包类型和服务端根因。客户端可见故障只使用
稳定原因，不暴露内部细节。

离线登录会验证 JWT 结构、签名、有效期和客户端数据完整性，但不证明 Microsoft 账户
所有权。准入后会丢弃原始 JWT、证书链、XUID、设备标识和其他不必要的身份材料，也不得
把它们加入日志。只有有界的派生 profile 与经典皮肤数据可以保留。

### 原生传输

NIO 始终是回退实现。只有 Netty 报告匹配的原生运行时可用时，Linux 才选择 epoll，
macOS/BSD 才选择 kqueue。需要原生传输的应用必须添加与模块固定 Netty 版本匹配的
native artifact，并核对部署架构 classifier。`mise run bedrock-check` 会验证当前可用
传输与类链接，并让 NIO 及当前平台可用的 epoll/kqueue 分别实际绑定 RakNet 监听器。

## 故障排查

| 现象 | 检查项 |
| --- | --- |
| 服务器未出现在发现列表 | UDP 防火墙/NAT、绑定地址、广告端口和监听状态 |
| 启动报告映射缺失或变化 | 使用兼容清单中的精确版本；先运行 `bedrock-mappings-verify` |
| 实验客户端可以解码但在 Instance 加载时断开 | 其 codec 仅被接受而未受支持，不得推断映射兼容 |
| 登录被拒绝 | 名称长度、JWT 有效期/签名、经典皮肤边界、Persona 标志和 JWT 上限 |
| 移动导致断开 | 不受支持的移动能力或每 tick 包限制 |
| 原生传输回退到 NIO | 匹配 artifact/classifier 缺失或不可用；NIO 仍受支持 |
| 关闭后端口仍占用 | 确认所属 `ServerProcess` 已停止，且没有第二个进程占用 UDP 端口 |
| 日志不含原始客户端原因 | 这是预期隐私行为；使用结构化服务端诊断字段 |
