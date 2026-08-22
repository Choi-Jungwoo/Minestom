package net.minestom.server.bedrock;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BedrockInboundPacketLimitTest {
    @Test
    void boundsEveryPacketInEachTickWindow() {
        final AtomicLong clock = new AtomicLong();
        final BedrockInboundPacketLimit limit =
                new BedrockInboundPacketLimit(2, clock::get);

        assertTrue(limit.accept());
        assertTrue(limit.accept());
        assertFalse(limit.accept());

        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));

        assertTrue(limit.accept());
    }
}
