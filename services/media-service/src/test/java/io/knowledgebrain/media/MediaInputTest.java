package io.knowledgebrain.media;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import org.springframework.web.server.ResponseStatusException;
class MediaInputTest {
 @Test void boundedMetadataRejectsTraversalUnsafeMimeAndOverflow(){
  var good=new MediaInput.Upload("한글.pdf","application/pdf",1,"a".repeat(64));assertThat(MediaInput.validate(good)).isEqualTo(good);
  for(String bad:new String[]{"../a","a/b","a\\b","\n",".",".."})assertThatThrownBy(()->MediaInput.validate(new MediaInput.Upload(bad,"application/pdf",1,"a".repeat(64)))).isInstanceOf(ResponseStatusException.class);
  assertThatThrownBy(()->MediaInput.validate(new MediaInput.Upload("a","text/html",1,"a".repeat(64)))).isInstanceOf(ResponseStatusException.class);
  for(long size:new long[]{0,-1,10485761})assertThatThrownBy(()->MediaInput.validate(new MediaInput.Upload("a","application/pdf",size,"a".repeat(64)))).isInstanceOf(ResponseStatusException.class);
  String json="{\"filename\":\"a\",\"mimeType\":\"application/pdf\",\"byteSize\":18446744073709551617,\"sha256\":\""+"a".repeat(64)+"\"}";assertThatThrownBy(()->MediaInput.parse(json.getBytes())).isInstanceOf(ResponseStatusException.class);
  assertThatThrownBy(()->MediaInput.parse("{} {}".getBytes())).isInstanceOf(ResponseStatusException.class);
  assertThatThrownBy(()->MediaInput.parse(new byte[2049])).isInstanceOf(ResponseStatusException.class);
 }
}
