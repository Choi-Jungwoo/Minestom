package net.minestom.server.bedrock;

import io.netty.channel.epoll.Epoll;
import io.netty.channel.kqueue.KQueue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BedrockTransportTest {
    @Test
    void selectsAnAvailableNativeTransportOrFallsBackToNio() {
        final String expected;
        if (Epoll.isAvailable()) {
            expected = "epoll";
        } else if (KQueue.isAvailable()) {
            expected = "kqueue";
        } else {
            expected = "nio";
        }

        assertEquals(expected, BedrockTransport.select().name());
    }
}
