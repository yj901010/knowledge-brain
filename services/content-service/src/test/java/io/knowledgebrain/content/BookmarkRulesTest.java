package io.knowledgebrain.content;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class BookmarkRulesTest {
 @Test void onlyExplicitAbsoluteWebAddressesWithoutCredentialsOrParserAmbiguityAreAccepted(){
  for(String url:List.of("https://example.test/a%20b?q=One%2FTwo#section","http://localhost:8080/","https://[::1]:8443/","HTTPS://EXAMPLE.TEST/path"))assertDoesNotThrow(()->BookmarkRules.url(url));
  for(String url:List.of("javascript:alert(1)","data:text/html,a","file:///tmp/a","//example.test/a","https:example.test","https://user:pass@example.test","https://example.test\\@evil.test/","https://example.test/%","https://example.test/a\nb","https://example.test:0/","https://example.test:65536/","https://example.test:/"," https://example.test/","https://example.test/a b"))assertThrows(org.springframework.web.server.ResponseStatusException.class,()->BookmarkRules.url(url),url);
 }
 @Test void exactUnicodeAndMemoBoundsPreserveAllowedWhitespace(){
  var valid=new BookmarkStore.Input("😀".repeat(200),"https://example.test/%ED%95%9C%EA%B8%80","\n\r\t"+"a".repeat(32765));assertDoesNotThrow(()->BookmarkRules.input(valid));
  for(var value:List.of(new BookmarkStore.Input(" ",valid.url(),""),new BookmarkStore.Input("😀".repeat(201),valid.url(),""),new BookmarkStore.Input("a\nb",valid.url(),""),new BookmarkStore.Input("a",valid.url(),"a".repeat(32769)),new BookmarkStore.Input("a",valid.url(),"\ud800"),new BookmarkStore.Input("a",valid.url(),"\u007f")))assertThrows(org.springframework.web.server.ResponseStatusException.class,()->BookmarkRules.input(value));
 }
 @Test void lowerCaseUuidV7AndNonzeroTraceRemainRequired(){assertThrows(org.springframework.web.server.ResponseStatusException.class,()->BookmarkRules.id(UUID.randomUUID().toString()));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->BookmarkRules.trace("0".repeat(32)));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->BookmarkRules.url("https://example.test/"+"x".repeat(2048)));}
}
