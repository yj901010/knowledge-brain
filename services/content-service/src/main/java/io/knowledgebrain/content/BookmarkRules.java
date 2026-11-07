package io.knowledgebrain.content;
import java.util.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class BookmarkRules {
 static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid bookmark input");}
 static UUID id(String value){
  try{if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))throw bad();return UUID.fromString(value);}
  catch(IllegalArgumentException e){throw bad();}
 }
 static void text(String value,int bytes){
  if(value==null||value.getBytes(StandardCharsets.UTF_8).length>bytes)throw bad();
  for(int n=0;n<value.length();){int cp=value.codePointAt(n);if(cp>=0xd800&&cp<=0xdfff||Character.isISOControl(cp)&&cp!='\n'&&cp!='\r'&&cp!='\t')throw bad();n+=Character.charCount(cp);}
 }
 static void input(BookmarkStore.Input value){
  if(value==null)throw bad();text(value.title(),800);text(value.memo(),32768);
  if(value.title().isBlank()||value.title().codePointCount(0,value.title().length())>200||value.title().matches("(?s).*[\\r\\n\\t].*"))throw bad();
  url(value.url());
 }
 static void url(String value){
  if(value==null||value.length()>2048||!value.matches("[\\x21-\\x7e]+")||value.indexOf('\\')>=0)throw bad();
  try{
   var uri=new URI(value);
   if(!Set.of("http","https").contains(Optional.ofNullable(uri.getScheme()).orElse("").toLowerCase(Locale.ROOT))
    ||uri.isOpaque()||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getPort()==0||uri.getPort()>65535
    ||!uri.getRawAuthority().equals(uri.getHost()+(uri.getPort()<0?"":":"+uri.getPort())))throw bad();
  }catch(java.net.URISyntaxException e){throw bad();}
 }
 static void trace(String value){if(value==null||!value.matches("[0-9a-f]{32}")||value.equals("0".repeat(32)))throw bad();}
}
