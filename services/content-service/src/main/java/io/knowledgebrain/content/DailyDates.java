package io.knowledgebrain.content;
import java.time.*;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
final class DailyDates {
 private static final Set<String> ZONES=Set.copyOf(ZoneId.getAvailableZoneIds());
 static LocalDate date(String value){
  try{if(value==null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw bad();var date=LocalDate.parse(value);if(date.getYear()<1)throw bad();return date;}catch(ResponseStatusException error){throw error;}catch(DateTimeException error){throw bad();}
 }
 static ZoneId zone(String value){if(value==null || value.length()>100 || !ZONES.contains(value))throw bad();return ZoneId.of(value);}
 static LocalDate today(Clock clock,String zone){return date(LocalDate.ofInstant(clock.instant(),zone(zone)).toString());}
 private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Valid YYYY-MM-DD date and explicit IANA timezone required");}
}
