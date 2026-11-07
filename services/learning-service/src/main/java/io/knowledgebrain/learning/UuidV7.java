package io.knowledgebrain.learning;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

final class UuidV7 {
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    UuidV7(Clock clock) { this.clock = clock; }
    UUID next() {
        long millis = clock.millis();
        if (millis < 0 || millis > 0xffffffffffffL) throw new IllegalStateException("UUIDv7 timestamp outside range");
        long most = (millis << 16) | 0x7000L | (random.nextInt(4096));
        long least = (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(most, least);
    }
}
