package io.knowledgebrain.content;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
final class PropertyValueFilter {
    record Predicate(boolean contains,String json) {}
    private static final JsonMapper JSON=new JsonMapper();
    static void input(String value){if(value==null||value.codePointCount(0,value.length())>200||value.indexOf(0)>=0||value.codePoints().anyMatch(c->c>=0xd800&&c<=0xdfff))throw bad();}
    static Predicate parse(String type,String value){
        input(value);
        try{return switch(type){
            case "STRING" -> new Predicate(false,JSON.writeValueAsString(value));
            case "STRING_LIST" -> new Predicate(true,JSON.writeValueAsString(List.of(value)));
            case "BOOLEAN" -> {if(!List.of("true","false").contains(value))throw bad();yield new Predicate(false,value);}
            case "DATE" -> {if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw bad();LocalDate.parse(value);yield new Predicate(false,JSON.writeValueAsString(value));}
            case "NUMBER" -> {
                if(value.length()>96||!value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]{1,3})?"))throw bad();
                BigDecimal number=new BigDecimal(value).stripTrailingZeros();
                if(number.precision()>38||number.scale()>18||(long)number.precision()-number.scale()>56)throw bad();
                yield new Predicate(false,number.toPlainString());
            }
            default -> throw bad();
        };}catch(ResponseStatusException error){throw error;}catch(RuntimeException error){throw bad();}
    }
    record Bounds(String minJson,String maxJson) {}
    static void selection(String value,String min,String max){
        if(value!=null&&(min!=null||max!=null))throw bad();
        for(String bound:new String[]{value,min,max})if(bound!=null)input(bound);
        if(min!=null&&min.isEmpty()||max!=null&&max.isEmpty())throw bad();
    }
    static Bounds bounds(String type,String min,String max){
        if(min==null&&max==null)return new Bounds(null,null);
        if(!List.of("NUMBER","DATE").contains(type))throw bad();
        String low=min==null?null:parse(type,min).json(),high=max==null?null:parse(type,max).json();
        if(low!=null&&high!=null&&(type.equals("NUMBER")?new BigDecimal(low).compareTo(new BigDecimal(high)):min.compareTo(max))>0)throw bad();
        return new Bounds(low,high);
    }
    private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid property value condition");}
}
