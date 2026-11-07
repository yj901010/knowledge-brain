package io.knowledgebrain.content;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class DailyDatesTest {
 @Test void datesUseExplicitZoneAcrossMidnightAndNeverHostTimezone(){
 var clock=Clock.fixed(Instant.parse("2026-10-07T15:00:00Z"),ZoneId.of("America/New_York"));assertThat(DailyDates.today(clock,"Asia/Seoul")).isEqualTo(LocalDate.of(2026,10,8));assertThat(DailyDates.today(clock,"UTC")).isEqualTo(LocalDate.of(2026,10,7));assertThat(DailyDates.today(clock,"America/Los_Angeles")).isEqualTo(LocalDate.of(2026,10,7));
 }
 @Test void dstSpringGapAndAutumnRepeatedHourKeepCorrectJournalDate(){
 for(String instant:new String[]{"2026-03-08T06:59:59Z","2026-03-08T07:00:00Z"})assertThat(DailyDates.today(Clock.fixed(Instant.parse(instant),ZoneOffset.UTC),"America/New_York")).isEqualTo(LocalDate.of(2026,3,8));
 for(String instant:new String[]{"2026-11-01T05:30:00Z","2026-11-01T06:30:00Z"})assertThat(DailyDates.today(Clock.fixed(Instant.parse(instant),ZoneOffset.UTC),"America/New_York")).isEqualTo(LocalDate.of(2026,11,1));
 }
 @Test void rejectsImpossibleDatesImplicitZonesOffsetsAndMalformedCalendar(){
 for(String date:new String[]{"2026-02-29","2026-2-01","0000-01-01","2026-10-07T00:00:00Z","10000-01-01"})assertThatThrownBy(()->DailyDates.date(date)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 for(String zone:new String[]{"","+09:00","UTC+09:00","Asia/Unknown"," Asia/Seoul "})assertThatThrownBy(()->DailyDates.zone(zone)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);assertThat(DailyDates.date("2028-02-29")).isEqualTo(LocalDate.of(2028,2,29));
 }
}
