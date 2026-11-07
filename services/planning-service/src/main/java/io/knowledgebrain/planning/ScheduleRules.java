package io.knowledgebrain.planning;
import java.time.*;import java.time.format.DateTimeFormatter;import java.time.temporal.ChronoUnit;import java.util.*;import java.nio.charset.StandardCharsets;import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;
final class ScheduleRules {
 static final DateTimeFormatter LOCAL=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");
 record Option(String instant,String offset){}
 record TimeOptions(UUID spaceId,String local,String timeZone,List<Option> options){}
 record Window(String from,String to,String timeZone,Instant start,Instant end){}
 static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid schedule input or time window");}
 static UUID id(String s){try{UUID v=UUID.fromString(s);if(v.version()!=7||v.variant()!=2||!v.toString().equals(s))throw bad();return v;}catch(IllegalArgumentException e){throw bad();}}
 static void id(UUID v){if(v==null)throw bad();id(v.toString());}
 static ZoneId zone(String value){if(value==null||value.length()>64||!ZoneId.getAvailableZoneIds().contains(value))throw bad();return ZoneId.of(value);}
 static Instant instant(String raw){try{if(raw==null||!raw.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"))throw bad();var v=Instant.parse(raw);if(!v.toString().equals(raw))throw bad();return v;}catch(DateTimeException e){throw bad();}}
 static LocalDateTime local(String raw){try{if(raw==null||!raw.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"))throw bad();var v=LocalDateTime.parse(raw);if(!LOCAL.format(v).equals(raw))throw bad();return v;}catch(DateTimeException e){throw bad();}}
 static LocalDate date(String raw){try{GoalRules.date(raw);if(raw==null)throw bad();return LocalDate.parse(raw);}catch(DateTimeException e){throw bad();}}
 static void input(ScheduleStore.Input v){if(v==null)throw bad();GoalRules.input(new GoalStore.Input(v.title(),v.description(),"NOT_STARTED",null));var z=zone(v.timeZone());var a=instant(v.startsAt());var b=instant(v.endsAt());if(!b.isAfter(a))throw bad();for(var i:List.of(a,b)){int year=i.atZone(z).getYear();if(year<1||year>9999)throw bad();}if(v.taskId()!=null)id(v.taskId());}
 static TimeOptions options(UUID space,String raw,String zone){id(space);var local=local(raw);var z=zone(zone);var offsets=z.getRules().getValidOffsets(local);var values=offsets.stream().map(o->new Option(local.toInstant(o).toString(),o.toString())).sorted(Comparator.comparing(Option::instant)).toList();for(var v:values)instant(v.instant());return new TimeOptions(space,raw,zone,values);}
 static Window window(String from,String to,String timeZone){var a=date(from);var b=date(to);var z=zone(timeZone);long days=ChronoUnit.DAYS.between(a,b);if(days<0||days>92)throw bad();var start=a.atStartOfDay(z).toInstant();var end=b.plusDays(1).atStartOfDay(z).toInstant();if(!end.isAfter(start))throw bad();return new Window(from,to,timeZone,start,end);}
 record Cursor(Instant start,UUID id){}
 static String cursor(UUID space,String state,String task,Window w,String start,UUID id){String raw=String.join("|",space.toString(),state,task,w.from(),w.to(),w.timeZone(),start,id.toString());return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));}
 static Cursor cursor(String raw,UUID space,String state,String task,Window w){try{if(raw==null)return null;if(!raw.matches("[A-Za-z0-9_-]{1,768}"))throw bad();byte[] bytes=Base64.getUrlDecoder().decode(raw);if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw))throw bad();String[] v=new String(bytes,StandardCharsets.US_ASCII).split("\\|",-1);if(v.length!=8||!v[0].equals(space.toString())||!v[1].equals(state)||!v[2].equals(task)||!v[3].equals(w.from())||!v[4].equals(w.to())||!v[5].equals(w.timeZone()))throw bad();return new Cursor(instant(v[6]),id(v[7]));}catch(IllegalArgumentException e){throw bad();}}
}
