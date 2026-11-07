package io.knowledgebrain.brainquery;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_SEARCH_ORIGIN", matches=".+")
class NoteSearchStorageIntegrationTest {
    private UUID id() {
        var random = UUID.randomUUID();
        return new UUID((random.getMostSignificantBits() & ~0xf000L) | 0x7000L, random.getLeastSignificantBits());
    }
    private NoteSearchClient client(String role) throws Exception {
        return new NoteSearchClient(System.getenv("BRAIN_TEST_SEARCH_ORIGIN"), Path.of("/search-"+role));
    }
    private NoteSearchProjection.Revision revision(UUID space, UUID note, long version, boolean retired, String title) {
        return new NoteSearchProjection.Revision(id(), space, note, version, retired ? "RETIRED" : "ACTIVE",
                retired ? "" : title, retired ? "" : "test body");
    }
    @Test void realTlsIndexingDuplicatesOldEventsAndConflicts() throws Exception {
        var writer = client("projector"); var space = id(); var note = id();
        var original = revision(space, note, 3, false, "VersionFixture");
        assertThat(writer.apply(original)).isEqualTo(NoteSearchClient.Applied.INDEXED);
        assertThat(writer.apply(original)).isEqualTo(NoteSearchClient.Applied.DUPLICATE);
        assertThat(writer.apply(revision(space, note, 2, false, "OldFixture"))).isEqualTo(NoteSearchClient.Applied.SUPERSEDED);
        assertThatThrownBy(() -> writer.apply(revision(space, note, 3, false, "ConflictingFixture")))
                .isInstanceOf(NoteSearchClient.Conflict.class);
        assertThatThrownBy(() -> writer.apply(revision(id(), note, 3, false, "CrossSpaceFixture")))
                .isInstanceOf(NoteSearchClient.Conflict.class);
    }
    @Test void realSearchFiltersSpacesAndRetiredTombstonesSurviveLateUpdates() throws Exception {
        var writer = client("projector"); var reader = client("reader");
        var space = id(); var otherSpace = id(); var note = id();
        writer.apply(revision(space, note, 1, false, "ScopeFixture"));
        writer.apply(revision(otherSpace, id(), 1, false, "ScopeFixture"));
        awaitTotal(reader, space, "ScopeFixture", 1);
        assertThat(reader.search(space, "ScopeFixture", 0, 20).path("hits").size()).isEqualTo(1);
        assertThat(reader.search(id(), "ScopeFixture", 0, 20).path("total").path("value").asLong()).isZero();
        writer.apply(revision(space, note, 2, true, ""));
        assertThat(writer.apply(revision(space, note, 1, false, "ScopeFixture"))).isEqualTo(NoteSearchClient.Applied.SUPERSEDED);
        awaitTotal(reader, space, "ScopeFixture", 0);
    }
    @Test void certificatesHaveDistinctLeastPrivilegeRoles() throws Exception {
        var reader = client("reader"); var writer = client("projector");
        assertThatThrownBy(() -> reader.apply(revision(id(), id(), 1, false, "ForbiddenWrite")))
                .isInstanceOf(NoteSearchClient.Unavailable.class);
        assertThatThrownBy(() -> writer.search(id(), "ForbiddenRead", 0, 20))
                .isInstanceOf(NoteSearchClient.Unavailable.class);
    }
    @Test void insecureAndAmbiguousOriginsAreRejectedBeforeTransport() {
        for (String origin : new String[] {"http://example.test", "https://user:pass@example.test", "https://example.test/path", "https://example.test?q=x"})
            assertThatThrownBy(() -> new NoteSearchClient(origin, Path.of("/absent")))
                    .isInstanceOf(IllegalArgumentException.class);
    }
    private void awaitTotal(NoteSearchClient reader, UUID space, String text, long expected) throws Exception {
        for (int i=0; i<30; i++) {
            if (reader.search(space, text, 0, 20).path("total").path("value").asLong() == expected) return;
            Thread.sleep(200);
        }
        fail("Search refresh did not reach expected total");
    }
}
