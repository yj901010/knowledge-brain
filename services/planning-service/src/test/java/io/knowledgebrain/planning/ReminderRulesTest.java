package io.knowledgebrain.planning;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReminderRulesTest {
    final UuidV7 ids=new UuidV7(Clock.systemUTC());
    ScheduleStore.Schedule source(String utc,String zone){var at=Instant.parse(utc).atZone(ZoneId.of(zone));var end=at.plusHours(1);return new ScheduleStore.Schedule(ids.next(),ids.next(),"PRIVATE","ACTIVE",1,"Pinned","",utc,end.toInstant().toString(),zone,ScheduleRules.LOCAL.format(at),ScheduleRules.LOCAL.format(end),at.getOffset().toString(),end.getOffset().toString(),null,Instant.now().toString(),Instant.now().toString());}
    @Test void elapsedMinutesRespectBothDstTransitionsAndRetainPinnedScheduleLabels(){for(String utc:new String[]{"2028-03-12T07:10:00Z","2028-11-05T06:10:00Z"}){var s=source(utc,"America/New_York");var p=ReminderRules.preview(s.spaceId(),new ReminderRules.Input(s.scheduleId(),1,20),s);assertEquals(1200,Duration.between(Instant.parse(p.remindsAt()),Instant.parse(s.startsAt())).toSeconds());assertEquals(s.startsLocal(),p.schedule().startsLocal());assertEquals(utc.contains("03-12")?"2028-03-12T01:50:00":"2028-11-05T01:50:00",p.remindsLocal());assertEquals(utc.contains("03-12")?"-05:00":"-04:00",p.remindsOffset());assertEquals(p,ReminderRules.preview(s.spaceId(),p.input(),s));}}
    @Test void boundsDoNotWrapOrNormalizeAcrossYearZero(){var s=source("0001-01-01T00:00:00Z","UTC");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->ReminderRules.preview(s.spaceId(),new ReminderRules.Input(s.scheduleId(),1,1),s));assertEquals(s.startsAt(),ReminderRules.preview(s.spaceId(),new ReminderRules.Input(s.scheduleId(),1,0),s).remindsAt());for(int n:new int[]{-1,10081,Integer.MAX_VALUE})assertThrows(org.springframework.web.server.ResponseStatusException.class,()->ReminderRules.input(new ReminderRules.Input(s.scheduleId(),1,n)));}
}
