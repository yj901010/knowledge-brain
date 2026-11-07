package io.knowledgebrain.brainquery;

import com.datastax.oss.driver.shaded.guava.common.collect.MapMaker;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DriverGuavaCompatibilityTest {
    @Test void driverWeakMapsPreserveLargerMapsAndSerializationIdentities() throws Exception {
        String prefix = "com.datastax.oss.driver.shaded.guava.common.collect.MapMakerInternalMap";
        assertTrue(Class.forName(prefix).getProtectionDomain().getCodeSource().getLocation().toString().contains("java-driver-shaded-guava-patched"));
        assertEquals(5L, ObjectStreamClass.lookup(Class.forName(prefix)).getSerialVersionUID());
        for (String name : List.of("$AbstractSerializationProxy", "$SerializationProxy"))
            assertEquals(3L, ObjectStreamClass.lookup(Class.forName(prefix + name)).getSerialVersionUID());
        for (boolean weakKeys : List.of(true, false)) {
            var builder = new MapMaker().concurrencyLevel(8);
            if (weakKeys) builder.weakKeys(); else builder.weakValues();
            Map<String, String> map = builder.makeMap();
            var expected = new HashMap<String, String>();
            for (int i = 0; i < 600; i++) expected.put("key-" + i, "값-" + i);
            map.putAll(expected); // expected retains all weak referents during serialization.
            var bytes = new ByteArrayOutputStream();
            try (var output = new ObjectOutputStream(bytes)) { output.writeObject(map); }
            try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                var restored = (Map<?, ?>) input.readObject();
                assertEquals(expected, new HashMap<>(restored));
                assertTrue(restored.getClass().getName().startsWith(prefix));
                if (weakKeys) assertNull(restored.get(new String("key-1")));
                else assertEquals("값-1", restored.get("key-1"));
            }
        }
    }
}
