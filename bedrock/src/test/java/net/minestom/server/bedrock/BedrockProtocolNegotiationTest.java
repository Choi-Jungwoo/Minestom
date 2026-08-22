package net.minestom.server.bedrock;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerProcess;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2169.Bedrock_v2169;
import org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924;
import org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944;
import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BedrockProtocolNegotiationTest {
    private ServerProcess process;
    private BedrockServer server;

    @BeforeEach
    void startServer() {
        process = MinecraftServer.updateProcess();
        server = BedrockServer.createForTesting(
                process, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
        if (process != null) process.stop();
    }

    @ParameterizedTest
    @MethodSource("bedrock126Codecs")
    @Tag("bedrock-acceptance")
    void selectsTheExactCodecAndNegotiatesCompression(BedrockCodec codec) throws Exception {
        try (var client = new TestClient(server.boundAddress(), codec)) {
            client.requestNetworkSettings(codec.getProtocolVersion());

            assertTrue(client.networkSettings.await(2, TimeUnit.SECONDS));
            assertEquals(PacketCompressionAlgorithm.ZLIB, client.compression);
            assertEquals(codec.getProtocolVersion(), client.session.getCodec().getProtocolVersion());
        }
    }

    private static Stream<Arguments> bedrock126Codecs() {
        return Stream.of(
                Arguments.of(Bedrock_v924.CODEC),
                Arguments.of(Bedrock_v944.CODEC),
                Arguments.of(Bedrock_v975.CODEC),
                Arguments.of(Bedrock_v1001.CODEC),
                Arguments.of(Bedrock_v2168.CODEC),
                Arguments.of(Bedrock_v2169.CODEC));
    }

    @Test
    void rejectsProtocolsOutsideBedrock126() throws Exception {
        try (var client = new TestClient(server.boundAddress(), Bedrock_v1001.CODEC)) {
            client.requestNetworkSettings(685);

            assertTrue(client.disconnected.await(2, TimeUnit.SECONDS));
            assertEquals("Unsupported Bedrock protocol 685; expected one of 924, 944, 975, 1001, 2168, 2169",
                    client.disconnectReason.toString());
        }
    }

    @Test
    void rejectsRepeatedNetworkSettingsRequests() throws Exception {
        try (var client = new TestClient(server.boundAddress(), Bedrock_v1001.CODEC)) {
            client.requestNetworkSettings(1001);
            assertTrue(client.networkSettings.await(2, TimeUnit.SECONDS));

            client.requestNetworkSettings(1001);

            assertTrue(client.disconnected.await(2, TimeUnit.SECONDS));
            assertEquals("Network settings were already requested", client.disconnectReason.toString());
        }
    }

    @Test
    void normalShutdownDisconnectsActiveSessions() throws Exception {
        try (var client = new TestClient(server.boundAddress(), Bedrock_v1001.CODEC)) {
            client.requestNetworkSettings(1001);
            assertTrue(client.networkSettings.await(2, TimeUnit.SECONDS));

            server.stop();

            assertTrue(client.disconnected.await(2, TimeUnit.SECONDS));
        }
    }

    private static final class TestClient implements AutoCloseable {
        private final CountDownLatch networkSettings = new CountDownLatch(1);
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private final EventLoopGroup eventLoopGroup =
                new MultiThreadIoEventLoopGroup(
                        1, new DefaultThreadFactory("bedrock-test-client", true), NioIoHandler.newFactory());

        private final BedrockClientSession session;
        private final Channel channel;
        private volatile PacketCompressionAlgorithm compression;
        private volatile CharSequence disconnectReason = "";

        private TestClient(InetSocketAddress address, BedrockCodec codec) throws Exception {
            var connected = new CountDownLatch(1);
            var sessionHolder = new BedrockClientSession[1];
            channel = new Bootstrap()
                    .group(eventLoopGroup)
                    .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                    .option(RakChannelOption.RAK_PROTOCOL_VERSION, codec.getRaknetProtocolVersion())
                    .handler(new BedrockClientInitializer() {
                        @Override
                        protected void initSession(BedrockClientSession session) {
                            session.setCodec(codec);
                            session.setPacketHandler(new BedrockPacketHandler() {
                                @Override
                                public PacketSignal handle(NetworkSettingsPacket packet) {
                                    compression = packet.getCompressionAlgorithm();
                                    session.setCompression(compression);
                                    networkSettings.countDown();
                                    return PacketSignal.HANDLED;
                                }

                                @Override
                                public PacketSignal handle(DisconnectPacket packet) {
                                    disconnectReason = packet.getKickMessage();
                                    disconnected.countDown();
                                    return PacketSignal.HANDLED;
                                }

                                @Override
                                public void onDisconnect(CharSequence reason) {
                                    disconnectReason = reason;
                                    disconnected.countDown();
                                }
                            });
                            sessionHolder[0] = session;
                            connected.countDown();
                        }
                    })
                    .connect(address)
                    .sync()
                    .channel();
            assertTrue(connected.await(2, TimeUnit.SECONDS));
            session = sessionHolder[0];
        }

        private void requestNetworkSettings(int protocolVersion) {
            var request = new RequestNetworkSettingsPacket();
            request.setProtocolVersion(protocolVersion);
            session.sendPacketImmediately(request);
        }

        @Override
        public void close() {
            channel.close().syncUninterruptibly();
            eventLoopGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
