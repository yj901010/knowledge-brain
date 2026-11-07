package io.knowledgebrain.content;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
final class MetadataInput {
    record Field(String name,String type,String json) {}
    record Edit(List<String> tags,List<Field> properties) {}
    static String name(String value) {
        if(value==null)throw bad();String name=WikiLinks.key(value);
        if(name.isEmpty() || name.codePointCount(0,name.length())>64 || name.codePoints().anyMatch(Character::isISOControl))throw bad();return name;
    }
    static Edit parse(byte[] body) {
        try {
            if(body.length>16384)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Metadata JSON exceeds 16 KiB");
            var node=JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(body);if(!node.isObject() || node.size()!=2 || !node.path("tags").isArray() || !node.path("properties").isObject())throw bad();
            var tags=new ArrayList<String>();var seen=new HashSet<String>();for(var tag:node.path("tags")) {if(!tag.isString())throw bad();String n=name(tag.asString());if(!seen.add(n))throw bad();tags.add(n);}if(tags.size()>32 || node.path("properties").size()>32)throw bad();
            var fields=new ArrayList<Field>();seen.clear();for(var entry:node.path("properties").properties()) {
                String key=name(entry.getKey());if(!seen.add(key))throw bad();var field=entry.getValue();if(!field.isObject() || field.size()!=2 || !field.path("type").isString() || !field.has("value"))throw bad();
                String type=field.path("type").asString();var value=field.path("value");String json=value.toString();
                switch(type) {
                    case "STRING" -> {if(!value.isString() || !validString(value.asString(),1000))throw bad();}
                    case "NUMBER" -> {if(!value.isNumber())throw bad();var decimal=new BigDecimal(json);if(decimal.precision()>38 || Math.abs((long)decimal.scale())>18)throw bad();json=decimal.stripTrailingZeros().toPlainString();}
                    case "BOOLEAN" -> {if(!value.isBoolean())throw bad();}
                    case "DATE" -> {if(!value.isString() || !value.asString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw bad();LocalDate.parse(value.asString());}
                    case "STRING_LIST" -> {if(!value.isArray() || value.size()>20)throw bad();for(var item:value)if(!item.isString() || !validString(item.asString(),200))throw bad();}
                    default -> throw bad();
                }
                fields.add(new Field(key,type,json));
            }return new Edit(List.copyOf(tags),List.copyOf(fields));
        }catch(ResponseStatusException error) {throw error;}catch(RuntimeException error) {throw bad();}
    }
    private static boolean validString(String value,int max) {return value.indexOf(0)<0 && value.codePointCount(0,value.length())<=max;}
    private static ResponseStatusException bad() {return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid tags or typed properties");}
}
