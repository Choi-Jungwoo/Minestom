package net.minestom.server.bedrock;

import org.cloudburstmc.netty.channel.raknet.config.RakServerThrottle;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class BedrockConnectionThrottle implements RakServerThrottle {
    private final int maximumAttempts;
    private final int maximumConnections;
    private final int maximumSources;
    private final BedrockDiagnostics diagnostics;
    private final LinkedHashMap<InetAddress, AttemptWindow> attempts = new LinkedHashMap<>();
    private final Set<InetSocketAddress> activeConnections = new HashSet<>();

    BedrockConnectionThrottle(
            int maximumAttempts,
            int maximumConnections,
            BedrockDiagnostics diagnostics) {
        this.maximumAttempts = maximumAttempts;
        this.maximumConnections = maximumConnections;
        this.maximumSources = (int) Math.min(
                Integer.MAX_VALUE, Math.max(64L, (long) maximumConnections * 4));
        this.diagnostics = diagnostics;
    }

    @Override
    public synchronized boolean accept(InetSocketAddress address) {
        final long second = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime());
        final InetAddress source = address.getAddress();
        AttemptWindow window = attempts.get(source);
        if (window == null || window.second() != second) {
            if (window == null && attempts.size() >= maximumSources) removeOldest();
            window = new AttemptWindow(second, 0);
        }
        final int count = window.count() + 1;
        attempts.put(source, new AttemptWindow(second, count));
        if (count > maximumAttempts) {
            diagnostics.rejection(0, "RAKNET", "ConnectionRequest", "RateLimit");
            return false;
        }
        if (activeConnections.size() >= maximumConnections) {
            diagnostics.rejection(0, "RAKNET", "ConnectionRequest", "CapacityLimit");
            return false;
        }

        return activeConnections.add(address);
    }

    @Override
    public synchronized void closed(InetSocketAddress address) {
        activeConnections.remove(address);
    }

    private void removeOldest() {
        final var iterator = attempts.entrySet().iterator();
        if (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private record AttemptWindow(long second, int count) {
    }
}
