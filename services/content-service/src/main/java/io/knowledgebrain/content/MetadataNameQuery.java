package io.knowledgebrain.content;
import java.text.Normalizer;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
final class MetadataNameQuery {
    static String parse(String raw){
        if(raw==null||raw.codePointCount(0,raw.length())>128||raw.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xd800&&c<=0xdfff))throw bad();
        String value=Normalizer.normalize(raw,Normalizer.Form.NFC);if(value.isEmpty()||value.codePointCount(0,value.length())>64)throw bad();return value;
    }
    private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid metadata name query");}
}
