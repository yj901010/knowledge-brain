package io.knowledgebrain.media;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
final class MediaInput {
 record Upload(String filename,String mimeType,long byteSize,String sha256){}
 static final long MAX_FILE=10485760,SPACE_QUOTA=104857600;
 static Upload parse(byte[] body){
  if(body.length>2048)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Upload metadata exceeds 2 KiB");
  try{var node=JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(body);if(!node.isObject() || node.size()!=4 || !node.path("filename").isString() || !node.path("mimeType").isString() || (!node.path("byteSize").isIntegralNumber() || !node.path("byteSize").canConvertToLong()) || !node.path("sha256").isString())throw bad();var result=new Upload(node.path("filename").asString(),node.path("mimeType").asString(),node.path("byteSize").asLong(),node.path("sha256").asString());return validate(result);}catch(ResponseStatusException e){throw e;}catch(RuntimeException e){throw bad();}
 }
 static Upload validate(Upload value){
  String name=Normalizer.normalize(value.filename(),Normalizer.Form.NFC).strip();if(!unicode(name)||name.isEmpty() || name.codePointCount(0,name.length())>200 || name.codePoints().anyMatch(Character::isISOControl) || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals(".."))throw bad();
  if(!Set.of("application/octet-stream","application/pdf","image/png","image/jpeg","image/gif","image/webp").contains(value.mimeType()) || value.byteSize()<1 || value.byteSize()>MAX_FILE || !value.sha256().matches("[0-9a-f]{64}"))throw bad();return new Upload(name,value.mimeType(),value.byteSize(),value.sha256());
 }
 private static boolean unicode(String s){for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c==0)return false;if(Character.isHighSurrogate(c)){if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
 private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid filename, MIME, size or SHA-256");}
}
