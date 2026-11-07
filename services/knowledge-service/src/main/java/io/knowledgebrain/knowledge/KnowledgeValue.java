package io.knowledgebrain.knowledge;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
final class KnowledgeValue {
    record Value(String type,String value) {}
    static Value value(String type,String text) {
        if(type==null||text==null||text.indexOf('\0')>=0||text.codePoints().anyMatch(c->c>=0xd800&&c<=0xdfff)||text.length()>4000)throw bad("Invalid knowledge value");
        String normalized;
        try {
            normalized=switch(type) {
                case "CONCEPT" -> {UUID id=UUID.fromString(text);ConceptStore.id(id);if(!id.toString().equals(text))throw new IllegalArgumentException();yield text;}
                case "STRING" -> {String s=text.strip();if(s.isEmpty()||s.codePointCount(0,s.length())>1000)throw new IllegalArgumentException();yield s;}
                case "NUMBER" -> {
                    if(text.length()>128||!text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"))throw new IllegalArgumentException();
                    var n=new java.math.BigDecimal(text).stripTrailingZeros();if(n.precision()>38||n.scale()>18||n.precision()-n.scale()>38)throw new IllegalArgumentException();
                    yield n.signum()==0?"0":n.toPlainString();
                }
                case "DATE" -> {if(!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||text.startsWith("0000"))throw new IllegalArgumentException();yield LocalDate.parse(text).toString();}
                case "BOOLEAN" -> {if(!Set.of("true","false").contains(text))throw new IllegalArgumentException();yield text;}
                default -> throw new IllegalArgumentException();
            };
        }catch(RuntimeException e){throw bad("Invalid typed knowledge value");}
        return new Value(type,normalized);
    }
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
