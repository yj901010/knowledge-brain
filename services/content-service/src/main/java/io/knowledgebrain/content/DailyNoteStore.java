package io.knowledgebrain.content;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.content.daily.v1.*;
final class DailyNoteStore {
 record Binding(UUID noteId,String timezone){}
 record Entry(LocalDate date,String timezone,NoteStore.Note note){}
 record Opened(boolean created,Entry entry){}
 record Summary(LocalDate date,String timezone,UUID noteId,String title,long version,Instant updatedAt){}
 record Page(List<Summary> items,LocalDate nextBefore){}
 record Today(LocalDate date,String timezone,Entry entry){}
 private final JdbcTemplate jdbc;private final Clock clock;private final UuidV7 ids;
 DailyNoteStore(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;ids=new UuidV7(clock);}
 Binding binding(UUID space,LocalDate date){var rows=jdbc.query("SELECT note_id,timezone FROM daily_note WHERE space_id=? AND journal_date=?",(rs,n)->new Binding(rs.getObject(1,UUID.class),rs.getString(2)),space,date);return rows.isEmpty()?null:rows.getFirst();}
 void bind(UUID space,LocalDate date,String zone,UUID note){jdbc.update("INSERT INTO daily_note(space_id,journal_date,timezone,note_id) VALUES (?,?,?,?)",space,date,zone,note);}
 void publishIfDaily(NoteStore.Note note,UUID actor,String trace){
  var dates=jdbc.query("SELECT journal_date,timezone FROM daily_note WHERE space_id=? AND note_id=?",(rs,n)->Map.entry(rs.getObject(1,LocalDate.class),rs.getString(2)),note.spaceId(),note.noteId());if(dates.isEmpty())return;
  UUID event=ids.next();Instant now=clock.instant();var binding=dates.getFirst();
  var payload=DailyNoteChangedPayloadV1.newBuilder().setNoteId(note.noteId().toString()).setJournalDate(binding.getKey().toString()).setTimezone(binding.getValue()).setNoteVersion(note.version()).setNoteState(note.state()).setTitle(note.title()).setVisibility(note.visibility()).build();
  var envelope=DailyEventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType("DailyNoteChanged").setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("content-service").setAggregateType("DailyNote").setAggregateId(note.noteId().toString()).setSpaceId(note.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(ByteString.copyFrom(payload.toByteArray())).build();
  jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'daily-note',?,'DailyNoteChanged',?,?,'content','daily-note-events','1',?)",event,note.noteId(),Timestamp.from(now),trace,envelope.toByteArray());
 }
 Page list(UUID space,LocalDate from,LocalDate to,LocalDate before,int limit){
  var args=new ArrayList<Object>();args.add(space);String sql="SELECT d.journal_date,d.timezone,n.note_id,n.title,n.version,n.updated_at FROM daily_note d JOIN note n ON n.space_id=d.space_id AND n.note_id=d.note_id WHERE d.space_id=? AND n.state='ACTIVE'";
  if(from!=null){sql+=" AND d.journal_date>=?";args.add(from);}if(to!=null){sql+=" AND d.journal_date<=?";args.add(to);}if(before!=null){sql+=" AND d.journal_date<?";args.add(before);}args.add(limit+1);sql+=" ORDER BY d.journal_date DESC LIMIT ?";
  var rows=jdbc.query(sql,(rs,n)->new Summary(rs.getObject(1,LocalDate.class),rs.getString(2),rs.getObject(3,UUID.class),rs.getString(4),rs.getLong(5),rs.getTimestamp(6).toInstant()),args.toArray());var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(page,rows.size()>limit?page.getLast().date():null);
 }
}
