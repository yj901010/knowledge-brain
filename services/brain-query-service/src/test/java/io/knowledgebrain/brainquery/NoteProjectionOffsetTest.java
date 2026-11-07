package io.knowledgebrain.brainquery;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class NoteProjectionOffsetTest {
    @Test void newGroupStartsAtRetainedBeginningAndExistingGroupReplaysItsCommit() {
        assertThat(NoteProjectionConsumer.initialOffset(null,0,100)).isEqualTo(0);
        assertThat(NoteProjectionConsumer.initialOffset(15L,10,100)).isEqualTo(15);
    }
    @Test void expiredOrTruncatedOffsetsRequireCanonicalRebuildInsteadOfSkipping() {
        assertThatThrownBy(()->NoteProjectionConsumer.initialOffset(null,10,100)).isInstanceOf(NoteProjectionConsumer.RebuildRequired.class);
        assertThatThrownBy(()->NoteProjectionConsumer.initialOffset(9L,10,100)).isInstanceOf(NoteProjectionConsumer.RebuildRequired.class);
        assertThatThrownBy(()->NoteProjectionConsumer.initialOffset(101L,10,100)).isInstanceOf(NoteProjectionConsumer.RebuildRequired.class);
    }
}
