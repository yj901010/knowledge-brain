package io.knowledgebrain.collaboration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
final class GroupInput {
 record Settings(String title,String description){}
 static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Group request");}
 static UUID id(String value){if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))throw bad();return UUID.fromString(value);}
 static JsonNode json(byte[] bytes){try{return JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(bytes);}catch(RuntimeException e){throw bad();}}
 static Settings settings(byte[] bytes){var root=json(bytes);if(root==null||!root.isObject()||root.size()!=2||!root.has("title")||!root.has("description")||!root.get("title").isString()||!root.get("description").isString())throw bad();
  var title=root.get("title").asString();var description=root.get("description").asString();
  if(title.isBlank()||title.codePointCount(0,title.length())>200||title.codePoints().anyMatch(Character::isISOControl)||description.getBytes(StandardCharsets.UTF_8).length>32768||!unicode(title)||!unicode(description))throw bad();return new Settings(title,description);
 }
 static boolean unicode(String s){for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c==0)return false;if(Character.isHighSurrogate(c)){if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
}
