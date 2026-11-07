package io.knowledgebrain.content;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class DecisionRules {
 static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid decision input");}
 static UUID id(String value){
  try{if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))throw bad();return UUID.fromString(value);}
  catch(IllegalArgumentException e){throw bad();}
 }
 static void text(String value,int bytes){
  if(value==null||value.getBytes(StandardCharsets.UTF_8).length>bytes)throw bad();
  for(int n=0;n<value.length();){int cp=value.codePointAt(n);if(cp>=0xd800&&cp<=0xdfff||Character.isISOControl(cp)&&cp!='\n'&&cp!='\r'&&cp!='\t')throw bad();n+=Character.charCount(cp);}
 }
 static void input(DecisionStore.Input value){
  if(value==null)throw bad();text(value.title(),800);text(value.context(),8192);text(value.outcome(),8192);text(value.rationale(),8192);text(value.alternatives(),8192);
  if(value.title().isBlank()||value.title().codePointCount(0,value.title().length())>200||value.title().matches("(?s).*[\\r\\n\\t].*"))throw bad();
  if(value.status()==null||!Set.of("DRAFT","DECIDED").contains(value.status())||value.status().equals("DECIDED")&&(value.outcome().isBlank()||value.rationale().isBlank()))throw bad();
 }
 static void trace(String value){if(value==null||!value.matches("[0-9a-f]{32}")||value.equals("0".repeat(32)))throw bad();}
}
