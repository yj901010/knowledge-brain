package io.knowledgebrain.brainquery;

import org.apache.tinkerpop.shaded.jackson.core.JsonFactory;
import org.apache.tinkerpop.shaded.jackson.core.StreamReadConstraints;
import org.apache.tinkerpop.shaded.jackson.core.exc.StreamConstraintsException;
import org.apache.tinkerpop.shaded.jackson.databind.ObjectMapper;
import org.apache.tinkerpop.shaded.jackson.databind.node.ArrayNode;
import org.apache.tinkerpop.shaded.jackson.databind.node.ObjectNode;
import org.janusgraph.graphdb.database.serialize.StandardSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShadedGraphCompatibilityTest {
    @Test void janusGraphRegisteredJsonTypesKeepTheirSerializationContract() throws Exception {
        var mapper = new ObjectMapper();
        var object = (ObjectNode) mapper.readTree("{\"title\":\"간암 / 한글 🧠\",\"count\":3,\"active\":true,\"value\":null}");
        var array = (ArrayNode) mapper.readTree("[1,\"두 번째\",{\"nested\":false},null]");
        try (var serializer = new StandardSerializer()) {
            var output = serializer.getDataOutput(128);
            output.writeObject(object, ObjectNode.class);
            output.writeObject(array, ArrayNode.class);
            var input = output.getStaticBuffer().asReadBuffer();
            assertEquals(object, serializer.readObject(input, ObjectNode.class));
            assertEquals(array, serializer.readObject(input, ArrayNode.class));
            assertFalse(input.hasRemaining());
        }
    }

    @Test void relocatedParserReallyUsesPatchedClassesAndEnforcesItsConstraints() throws Exception {
        assertEquals("2.18.11", org.apache.tinkerpop.shaded.jackson.core.json.PackageVersion.VERSION.toString());
        assertEquals("2.18.11", org.apache.tinkerpop.shaded.jackson.databind.cfg.PackageVersion.VERSION.toString());
        assertTrue(ObjectMapper.class.getProtectionDomain().getCodeSource().getLocation().toString().contains("gremlin-shaded-patched"));
        var constraints = StreamReadConstraints.builder().maxNestingDepth(32).maxNumberLength(64).build();
        var mapper = new ObjectMapper(JsonFactory.builder().streamReadConstraints(constraints).build());
        assertEquals("정상", mapper.readTree("{\"name\":\"정상\",\"items\":[1,2]}").get("name").asText());
        assertThrows(StreamConstraintsException.class, () -> mapper.readTree("[".repeat(33) + "0" + "]".repeat(33)));
        assertThrows(StreamConstraintsException.class, () -> mapper.readTree("1".repeat(65)));
    }
}
