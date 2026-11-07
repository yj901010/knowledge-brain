package io.knowledgebrain.gateway;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@RestController
@ConditionalOnProperty(name="brain.gateway.api.enabled",havingValue="true")
class GraphWebController {
 private final String mediaOrigin;
 GraphWebController(@Value("${brain.gateway.media-public-origin:}") String origin){mediaOrigin=checkedOrigin(origin);}
 static String checkedOrigin(String origin){
  if(origin.isEmpty())return "";
  URI uri=URI.create(origin);
  if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !"".equals(uri.getRawPath()) || !uri.toASCIIString().equals(origin))throw new IllegalArgumentException("HTTPS Media origin required");
  return origin;
 }
 @GetMapping("/graph/media-config") ResponseEntity<Map<String,String>> mediaConfig(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("origin",mediaOrigin));}
 @GetMapping({"/graph","/graph/"}) ResponseEntity<byte[]> page()throws java.io.IOException{
  return html("index.html");
 }
 @GetMapping({"/graph/knowledge","/graph/knowledge/"}) ResponseEntity<byte[]> knowledge()throws java.io.IOException{return html("knowledge.html");}
 @GetMapping({"/graph/groups","/graph/groups/"}) ResponseEntity<byte[]> groups()throws java.io.IOException{return html("groups.html");}
 @GetMapping({"/graph/roadmaps","/graph/roadmaps/"}) ResponseEntity<byte[]> roadmaps()throws java.io.IOException{return html("roadmaps.html");}
 private ResponseEntity<byte[]> html(String path)throws java.io.IOException{
  var html=new ClassPathResource("static/graph/"+path);var policy=new ClassPathResource("static/graph/csp.txt");
  return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore()).header("Content-Security-Policy",new String(policy.getContentAsByteArray(),StandardCharsets.UTF_8).strip().replace("connect-src 'self'", "connect-src 'self'"+(mediaOrigin.isEmpty()?"":" "+mediaOrigin))).header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer").body(html.getContentAsByteArray());
 }
}
