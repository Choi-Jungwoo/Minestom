package net.minestom.server.bedrock;

import com.google.errorprone.annotations.FormatMethod;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BedrockDiagnosticsTest {
    @Test
    void rateLimitsStructuredDiagnosticsWithoutSensitiveExceptionMessages() {
        final RecordingLogger logger = new RecordingLogger();
        final AtomicLong clock = new AtomicLong();
        final BedrockDiagnostics diagnostics = new BedrockDiagnostics(logger, clock::get, 2);
        final var sensitive =
                new IllegalArgumentException("JWT contained secret-device-identity");

        diagnostics.rejection(1001, "LOGIN", "LoginPacket", sensitive);
        diagnostics.rejection(1001, "LOGIN", "LoginPacket", sensitive);
        diagnostics.rejection(1001, "LOGIN", "LoginPacket", sensitive);

        assertEquals(2, logger.messages.size());
        assertTrue(logger.messages.stream().allMatch(message ->
                message.contains("protocol=1001")
                        && message.contains("state=LOGIN")
                        && message.contains("packet=LoginPacket")
                        && message.contains("cause=IllegalArgumentException")));
        assertFalse(logger.messages.stream().anyMatch(message ->
                message.contains("JWT") || message.contains("secret-device-identity")));
    }

    private static final class RecordingLogger implements System.Logger {
        private final List<String> messages = new ArrayList<>();

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(
                Level level,
                ResourceBundle bundle,
                String message,
                Throwable throwable) {
            messages.add(message);
        }

        @Override
        @FormatMethod
        public void log(
                Level level,
                ResourceBundle bundle,
                String format,
                Object... parameters) {
            messages.add(format.formatted(parameters));
        }
    }
}
