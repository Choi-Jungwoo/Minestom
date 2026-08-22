package net.minestom.server.bedrock;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

final class BedrockPacketLimit extends MessageToMessageDecoder<ByteBuf> {
    static final String NAME = "minestom-bedrock-packet-limit";

    private final int maximumBytes;

    BedrockPacketLimit(int maximumBytes) {
        this.maximumBytes = maximumBytes;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf message, List<Object> output) {
        if (message.readableBytes() > maximumBytes) {
            throw new BedrockCompression.BedrockLimitException(
                    "Bedrock packet exceeds the configured limit");
        }
        output.add(message.retain());
    }
}
