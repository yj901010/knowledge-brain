package io.knowledgebrain.planning;
import java.util.*;import java.time.*;import java.nio.charset.StandardCharsets;import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;
final class TaskRules {
 static final Set<String> STATUSES=Set.of("NOT_STARTED","IN_PROGRESS","COMPLETED");
 static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid task input");}
 static UUID id(String s){try{var id=UUID.fromString(s);if(id.version()!=7||id.variant()!=2||!id.toString().equals(s))throw bad();return id;}catch(IllegalArgumentException e){throw bad();}}
 static void id(UUID id){if(id==null)throw bad();id(id.toString());}
 static void trace(String trace){if(trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw bad();}
 static void input(TaskStore.Input v){if(v==null||v.title()==null||v.description()==null||v.status()==null||!STATUSES.contains(v.status())||v.title().isBlank()||v.title().codePointCount(0,v.title().length())>200||v.title().getBytes(StandardCharsets.UTF_8).length>800||v.description().getBytes(StandardCharsets.UTF_8).length>32768)throw bad();clean(v.title(),false);clean(v.description(),true);date(v.dueDate());if(v.goalId()!=null)id(v.goalId());}
 static void clean(String s,boolean lines){if(s.codePoints().anyMatch(c->c>=0xd800&&c<=0xdfff||Character.isISOControl(c)&&!(lines&&(c=='\n'||c=='\r'||c=='\t'))))throw bad();}
 static void date(String value){if(value==null)return;try{if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||LocalDate.parse(value).getYear()<1)throw bad();}catch(DateTimeException e){throw bad();}}
}
