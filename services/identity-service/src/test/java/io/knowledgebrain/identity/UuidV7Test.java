package io.knowledgebrain.identity;
import java.time.*;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class UuidV7Test {
    @Test void encodesTimestampVersionVariantAndHasUniqueRandomBits() {
        long millis=1791280000123L;
        var generator=new UuidV7(Clock.fixed(Instant.ofEpochMilli(millis),ZoneOffset.UTC));
        var ids=new HashSet<java.util.UUID>();
        for(int i=0;i<10000;i++) {
            var id=generator.next();
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
            assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(millis);
            assertThat(ids.add(id)).isTrue();
        }
    }
}
