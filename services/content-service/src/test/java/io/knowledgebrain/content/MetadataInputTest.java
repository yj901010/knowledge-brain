package io.knowledgebrain.content;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class MetadataInputTest {
 static MetadataInput.Edit parse(String value){return MetadataInput.parse(value.getBytes(StandardCharsets.UTF_8));}
 @Test void supportsAllTypesAndPreservesDecimalPrecision(){
 var edit=parse("""
 {"tags":[" study "],"properties":{"text":{"type":"STRING","value":"hello"},"score":{"type":"NUMBER","value":123456789.123456789},"done":{"type":"BOOLEAN","value":true},"date":{"type":"DATE","value":"2028-02-29"},"list":{"type":"STRING_LIST","value":["one","two"]}}}
 """);assertThat(edit.tags()).containsExactly("study");assertThat(edit.properties()).hasSize(5);assertThat(edit.properties().get(1).json()).isEqualTo("123456789.123456789");
 }
 @Test void rejectsInvalidTypesCalendarNullDuplicatesTrailingAndBounds(){
 for(String body:new String[]{"{}","{\"tags\":[],\"properties\":{},\"extra\":1}","{\"tags\":[\"x\",\" x \"],\"properties\":{}}","{\"tags\":[],\"tags\":[],\"properties\":{}}","{\"tags\":[],\"properties\":{}} {}"})assertThatThrownBy(()->parse(body)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 for(String field:new String[]{"{\"type\":\"DATE\",\"value\":\"2026-02-29\"}","{\"type\":\"NUMBER\",\"value\":\"1\"}","{\"type\":\"BOOLEAN\",\"value\":null}","{\"type\":\"STRING_LIST\",\"value\":[1]}","{\"type\":\"NUMBER\",\"value\":1e100}","{\"type\":\"UNKNOWN\",\"value\":1}"})assertThatThrownBy(()->parse("{\"tags\":[],\"properties\":{\"x\":"+field+"}} ")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 assertThatThrownBy(()->MetadataInput.parse(new byte[16385])).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }
}
