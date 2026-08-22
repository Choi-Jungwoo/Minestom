package net.minestom.server.bedrock;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BedrockPacketLimitTest {
    @Test
    void rejectsAndReleasesAnOversizedPacket() {
        final EmbeddedChannel channel = new EmbeddedChannel(new BedrockPacketLimit(4));
        final ByteBuf packet = Unpooled.buffer(5).writeZero(5);

        assertThrows(DecoderException.class, () -> channel.writeInbound(packet));
        assertEquals(0, packet.refCnt());
        channel.finishAndReleaseAll();
    }

    @Test
    void forwardsAnAcceptedPacketWithoutCopyingIt() {
        final EmbeddedChannel channel = new EmbeddedChannel(new BedrockPacketLimit(4));
        final ByteBuf packet = Unpooled.buffer(4).writeZero(4);

        channel.writeInbound(packet);

        final ByteBuf forwarded = channel.readInbound();
        assertSame(packet, forwarded);
        forwarded.release();
        channel.finishAndReleaseAll();
    }
}
