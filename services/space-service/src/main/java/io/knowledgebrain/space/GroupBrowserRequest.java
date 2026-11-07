package io.knowledgebrain.space;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
final class GroupBrowserRequest {
 static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Group membership request");}
 static UUID id(String value){if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))throw bad();return UUID.fromString(value);}
 static String token(HttpServletRequest r){if(!r.isSecure())throw bad();var tokens=new ArrayList<String>();for(var header:Collections.list(r.getHeaders("Cookie")))for(var part:header.split(";")){var pair=part.trim().split("=",2);if(pair.length==2&&pair[0].equals("__Host-brain_guest"))tokens.add(pair[1]);}if(tokens.size()!=1||!tokens.getFirst().matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Browser session required");return tokens.getFirst();}
 static void query(HttpServletRequest r,Set<String> allowed){if(r.getQueryString()!=null&&r.getQueryString().length()>256)throw bad();for(var e:r.getParameterMap().entrySet())if(!allowed.contains(e.getKey())||e.getValue().length!=1)throw bad();}
 static void read(HttpServletRequest r,Set<String> allowed)throws IOException{query(r,allowed);if(r.getInputStream().read()!=-1)throw bad();}
 static int limit(HttpServletRequest r,int max,int fallback){var value=r.getParameter("limit");if(value==null)return fallback;if(!value.matches("[1-9][0-9]{0,2}")||Integer.parseInt(value)>max)throw bad();return Integer.parseInt(value);}
 static UUID after(HttpServletRequest r){return r.getParameter("after")==null?null:id(r.getParameter("after"));}
 static long before(HttpServletRequest r){return r.getParameter("before")==null?Long.MAX_VALUE:number(r.getParameter("before"));}
 static long number(String value){try{if(!value.matches("[1-9][0-9]{0,18}"))throw bad();return Long.parseLong(value);}catch(NumberFormatException e){throw bad();}}
 static long match(HttpServletRequest r,String prefix){var values=Collections.list(r.getHeaders("If-Match"));if(values.size()!=1)throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"Current Group version required");var value=values.getFirst();if(!value.matches("\""+prefix+"[1-9][0-9]{0,18}\""))throw bad();return number(value.substring(prefix.length()+1,value.length()-1));}
 static String key(HttpServletRequest r){var keys=Collections.list(r.getHeaders("Idempotency-Key"));if(keys.size()!=1||!keys.getFirst().matches("[0-9a-f]{32}"))throw bad();return keys.getFirst();}
 static JsonNode write(HttpServletRequest r,String origin,Set<String> fields)throws IOException{
  query(r,Set.of());if(Collections.list(r.getHeaders("Origin")).size()!=1||!origin.equals(r.getHeader("Origin"))||r.getHeader("Sec-Fetch-Site")!=null&&!r.getHeader("Sec-Fetch-Site").equals("same-origin"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Same-origin request required");
  var bytes=r.getInputStream().readNBytes(2049);if(bytes.length>2048)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Request too large");
  if(r.getMethod().equals("DELETE")){if(bytes.length!=0)throw bad();return null;}
  if(!MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(Optional.ofNullable(r.getContentType()).orElse("").split(";",2)[0].trim()))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"JSON required");
  try{var node=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(bytes);if(node==null||!node.isObject()||node.size()!=fields.size()||fields.stream().anyMatch(k->!node.has(k)||!node.get(k).isString()))throw bad();return node;}catch(tools.jackson.core.JacksonException e){throw bad();}
 }
 static String parent(HttpServletRequest r){var stored=r.getAttribute("brain.group.traceparent");if(stored instanceof String parent)return parent;var value=r.getHeader("traceparent");if(value==null||!value.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")||value.substring(3,35).equals("0".repeat(32))||value.substring(36,52).equals("0".repeat(16)))value="00-"+UUID.randomUUID().toString().replace("-","")+"-"+UUID.randomUUID().toString().replace("-","").substring(0,16)+"-01";r.setAttribute("brain.group.traceparent",value);return value;}
}
