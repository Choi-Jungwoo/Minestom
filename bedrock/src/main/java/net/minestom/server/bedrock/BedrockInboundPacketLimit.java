package net.minestom.server.bedrock;

import net.minestom.server.MinecraftServer;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class BedrockInboundPacketLimit {
    private final int maximumPackets;
    private final LongSupplier clock;
    private long window = Long.MIN_VALUE;
    private int packets;

    BedrockInboundPacketLimit(int maximumPackets) {
        this(maximumPackets, System::nanoTime);
    }

    BedrockInboundPacketLimit(int maximumPackets, LongSupplier clock) {
        this.maximumPackets = maximumPackets;
        this.clock = clock;
    }

    boolean accept() {
        final long currentWindow = clock.getAsLong()
                / TimeUnit.MILLISECONDS.toNanos(MinecraftServer.TICK_MS);
        if (currentWindow != window) {
            window = currentWindow;
            packets = 0;
        }
        return ++packets <= maximumPackets;
    }
}
