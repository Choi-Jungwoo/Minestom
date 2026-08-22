package net.minestom.server.bedrock;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import org.cloudburstmc.protocol.bedrock.data.CompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.BatchCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.ZlibCompression;
import org.cloudburstmc.protocol.common.util.Zlib;

import java.util.zip.DataFormatException;

final class BedrockCompression implements CompressionStrategy {
    private final BatchCompression zlib;
    private final BatchCompression none;

    BedrockCompression(BedrockServerLimits limits) {
        this.zlib = new LimitedZlibCompression(limits);
        this.none = new LimitedNoopCompression(limits);
    }

    @Override
    public BatchCompression getCompression(BedrockBatchWrapper wrapper) {
        return zlib;
    }

    @Override
    public BatchCompression getCompression(CompressionAlgorithm algorithm) {
        if (algorithm == PacketCompressionAlgorithm.ZLIB) return zlib;
        if (algorithm == PacketCompressionAlgorithm.NONE) return none;
        throw new BedrockLimitException("Unnegotiated Bedrock compression algorithm");
    }

    @Override
    public BatchCompression getDefaultCompression() {
        return zlib;
    }

    private static final class LimitedZlibCompression implements BatchCompression {
        private final ZlibCompression delegate = new ZlibCompression(Zlib.RAW);
        private final int maximumCompressedBytes;
        private final int maximumDecompressedBytes;

        private LimitedZlibCompression(BedrockServerLimits limits) {
            this.maximumCompressedBytes = limits.maxCompressedBatchBytes();
            this.maximumDecompressedBytes = limits.maxDecompressedBatchBytes();
        }

        @Override
        public ByteBuf encode(ChannelHandlerContext context, ByteBuf message) throws Exception {
            return delegate.encode(context, message);
        }

        @Override
        public ByteBuf decode(ChannelHandlerContext context, ByteBuf message) throws Exception {
            requireSize(message, maximumCompressedBytes, "Compressed Bedrock batch");
            try {
                return Zlib.RAW.inflate(message, maximumDecompressedBytes);
            } catch (DataFormatException exception) {
                if ("Inflated data exceeds maximum size".equals(exception.getMessage())) {
                    throw new BedrockLimitException(
                            "Decompressed Bedrock batch exceeds the configured limit",
                            exception);
                }
                throw exception;
            }
        }

        @Override
        public CompressionAlgorithm getAlgorithm() {
            return PacketCompressionAlgorithm.ZLIB;
        }

        @Override
        public void setLevel(int level) {
            delegate.setLevel(level);
        }

        @Override
        public int getLevel() {
            return delegate.getLevel();
        }
    }

    private static final class LimitedNoopCompression implements BatchCompression {
        private final NoopCompression delegate = new NoopCompression();
        private final int maximumCompressedBytes;
        private final int maximumDecompressedBytes;

        private LimitedNoopCompression(BedrockServerLimits limits) {
            this.maximumCompressedBytes = limits.maxCompressedBatchBytes();
            this.maximumDecompressedBytes = limits.maxDecompressedBatchBytes();
        }

        @Override
        public ByteBuf encode(ChannelHandlerContext context, ByteBuf message) throws Exception {
            return delegate.encode(context, message);
        }

        @Override
        public ByteBuf decode(ChannelHandlerContext context, ByteBuf message) {
            requireSize(message, maximumCompressedBytes, "Uncompressed Bedrock batch");
            requireSize(message, maximumDecompressedBytes, "Uncompressed Bedrock batch");
            return message.retainedSlice();
        }

        @Override
        public CompressionAlgorithm getAlgorithm() {
            return PacketCompressionAlgorithm.NONE;
        }

        @Override
        public void setLevel(int level) {
            delegate.setLevel(level);
        }

        @Override
        public int getLevel() {
            return delegate.getLevel();
        }
    }

    private static void requireSize(ByteBuf buffer, int maximumBytes, String description) {
        if (buffer.readableBytes() > maximumBytes) {
            throw new BedrockLimitException(description + " exceeds the configured limit");
        }
    }

    static final class BedrockLimitException extends DecoderException {
        private static final long serialVersionUID = 1L;

        BedrockLimitException(String message) {
            super(message);
        }

        BedrockLimitException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
