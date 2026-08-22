package net.minestom.server.bedrock;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.LongSupplier;

final class BedrockDiagnostics {
    private static final int MAXIMUM_KEYS = 256;
    private static final int DEFAULT_EVENTS_PER_MINUTE = 10;

    private final System.Logger logger;
    private final LongSupplier clock;
    private final int maximumEventsPerMinute;
    private final LinkedHashMap<Key, Window> windows = new LinkedHashMap<>();

    BedrockDiagnostics() {
        this(
                System.getLogger(BedrockServer.class.getName()),
                System::nanoTime,
                DEFAULT_EVENTS_PER_MINUTE);
    }

    BedrockDiagnostics(
            System.Logger logger,
            LongSupplier clock,
            int maximumEventsPerMinute) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumEventsPerMinute <= 0) {
            throw new IllegalArgumentException("maximumEventsPerMinute must be positive");
        }
        this.maximumEventsPerMinute = maximumEventsPerMinute;
    }

    void rejection(
            int protocol,
            String state,
            String packetType,
            Throwable cause) {
        rejection(protocol, state, packetType, cause.getClass().getSimpleName());
    }

    synchronized void rejection(
            int protocol,
            String state,
            String packetType,
            String causeType) {
        final Key key = new Key(protocol, state, packetType, causeType);
        final long minute = clock.getAsLong() / 60_000_000_000L;
        Window window = windows.get(key);
        if (window == null || window.minute() != minute) {
            if (window == null && windows.size() >= MAXIMUM_KEYS) removeOldest();
            window = new Window(minute, 0);
        }
        final int count = window.count() + 1;
        windows.put(key, new Window(minute, count));
        if (count > maximumEventsPerMinute) return;

        logger.log(
                System.Logger.Level.WARNING,
                "Bedrock rejection protocol=" + protocol
                        + " state=" + state
                        + " packet=" + packetType
                        + " cause=" + causeType);
    }

    private void removeOldest() {
        final var iterator = windows.entrySet().iterator();
        if (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private record Key(int protocol, String state, String packetType, String causeType) {
    }

    private record Window(long minute, int count) {
    }
}
