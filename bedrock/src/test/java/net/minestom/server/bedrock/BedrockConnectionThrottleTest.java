package net.minestom.server.bedrock;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BedrockConnectionThrottleTest {
    @Test
    void boundsAttemptsPerSourceAndKeepsTheWindowAfterClose() throws Exception {
        final BedrockConnectionThrottle throttle =
                new BedrockConnectionThrottle(2, 32, new BedrockDiagnostics());
        final InetSocketAddress first =
                new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 19132);
        final InetSocketAddress firstRetry =
                new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 19133);
        final InetSocketAddress firstRejected =
                new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 19134);
        final InetSocketAddress second =
                new InetSocketAddress(InetAddress.getByName("192.0.2.2"), 19132);

        assertTrue(throttle.accept(first));
        throttle.closed(first);
        assertTrue(throttle.accept(firstRetry));
        throttle.closed(firstRetry);
        assertFalse(throttle.accept(firstRejected));
        assertTrue(throttle.accept(second));
    }

    @Test
    void releasesConnectionCapacityWhenAChildCloses() throws Exception {
        final BedrockConnectionThrottle throttle =
                new BedrockConnectionThrottle(20, 1, new BedrockDiagnostics());
        final InetSocketAddress first =
                new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 19132);
        final InetSocketAddress second =
                new InetSocketAddress(InetAddress.getByName("192.0.2.2"), 19132);

        assertTrue(throttle.accept(first));
        assertFalse(throttle.accept(second));

        throttle.closed(first);

        assertTrue(throttle.accept(second));
    }
}
