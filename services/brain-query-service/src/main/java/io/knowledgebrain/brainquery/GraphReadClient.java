package io.knowledgebrain.brainquery;
import java.util.UUID;
@FunctionalInterface interface GraphReadClient {
 NoteGraphStore.View read(UUID authorizedSpace,int limit);
 final class Unavailable extends RuntimeException { Unavailable(){super("Graph unavailable");} }
}
