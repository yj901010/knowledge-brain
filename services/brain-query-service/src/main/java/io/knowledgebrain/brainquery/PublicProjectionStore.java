package io.knowledgebrain.brainquery;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class PublicProjectionStore {
 static final long LOCK=87361041L;
 static final class Conflict extends RuntimeException{}
 static final class Paused extends RuntimeException{}
 record Key(UUID space,UUID id,long sequence){}
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;
 PublicProjectionStore(DataSource source){jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(30);}
 void lock(){jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,LOCK);}
 boolean available(){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT available FROM public_discovery_control WHERE id=1",Boolean.class));}
 void available(boolean value){tx.executeWithoutResult(s->{lock();jdbc.update("UPDATE public_discovery_control SET available=? WHERE id=1",value);});}
 void reset(){tx.executeWithoutResult(s->{lock();jdbc.update("DELETE FROM public_discovery_work");jdbc.update("DELETE FROM public_discovery_source");jdbc.update("DELETE FROM public_discovery_inbox");});}
 void stage(PublicProjection.Event e,byte[] wire){stage(e,wire,false);}
 void stage(PublicProjection.Event e,byte[] wire,boolean operator){if(e==null)return;tx.executeWithoutResult(s->{lock();if(!operator&&!available())throw new Paused();String hash=PublicProjection.hash(Base64.getEncoder().encodeToString(wire));var seen=jdbc.queryForList("SELECT wire_hash FROM public_discovery_inbox WHERE event_id=?",String.class,e.eventId());if(!seen.isEmpty()){if(!seen.getFirst().equals(hash))throw new Conflict();return;}
  var old=jdbc.query("SELECT version,snapshot_hash,kind,state FROM public_discovery_source WHERE space_id=? AND entity_id=?",(r,n)->List.of(r.getLong(1),r.getString(2),r.getString(3),r.getString(4)),e.space(),e.id());
  if(!old.isEmpty()&&old.getFirst().get(3).equals("RETIRED")&&e.version()>(long)old.getFirst().get(0)&&!e.state().equals("RETIRED"))throw new Conflict();
  if(!old.isEmpty()&&(!old.getFirst().get(2).equals(e.kind())||(long)old.getFirst().get(0)==e.version()&&!old.getFirst().get(1).equals(e.hash())))throw new Conflict();
  jdbc.update("INSERT INTO public_discovery_inbox(event_id,wire_hash) VALUES (?,?)",e.eventId(),hash);
  if(!old.isEmpty()&&(long)old.getFirst().get(0)>=e.version())return;
  jdbc.update("INSERT INTO public_discovery_source VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?) ON CONFLICT(space_id,entity_id) DO UPDATE SET version=EXCLUDED.version,state=EXCLUDED.state,title=EXCLUDED.title,content=EXCLUDED.content,qualifier_pins=EXCLUDED.qualifier_pins,snapshot_hash=EXCLUDED.snapshot_hash",e.space(),e.id(),e.kind(),e.version(),e.state(),e.title(),e.content().toString(),e.qualifierPins().toString(),e.hash());
  var affected=new LinkedHashSet<UUID>();affected.add(e.id());
  affected.addAll(jdbc.queryForList("SELECT entity_id FROM public_discovery_source WHERE space_id=? AND (content->>'conceptId'=? OR content->>'subjectId'=? OR content->>'predicateId'=? OR content->>'objectValue'=? OR content->>'sourceId'=? OR content->>'claimId'=? OR content->>'evidenceId'=? OR content->>'value'=?)",UUID.class,e.space(),e.id().toString(),e.id().toString(),e.id().toString(),e.id().toString(),e.id().toString(),e.id().toString(),e.id().toString(),e.id().toString()));
  for(var ref:PublicProjection.references(e.kind(),e.content()))if(Set.of("ALIAS_OF","QUALIFIES","SUPPORTS").contains(ref.role()))affected.add(ref.target());
  for(UUID id:affected)jdbc.update("INSERT INTO public_discovery_work VALUES (?,?,nextval('public_discovery_sequence')) ON CONFLICT(space_id,entity_id) DO UPDATE SET sequence=EXCLUDED.sequence",e.space(),id);
 });}
 List<Key> pending(int limit){return jdbc.query("SELECT space_id,entity_id,sequence FROM public_discovery_work ORDER BY sequence LIMIT ?",(r,n)->new Key(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getLong(3)),limit);}
 PublicProjection.Document document(Key key){var rows=jdbc.query("SELECT * FROM public_discovery_source WHERE space_id=? AND entity_id=?",(r,n)->{
  String kind=r.getString("kind"),title=r.getString("title");var content=PublicProjection.JSON.readTree(r.getString("content"));boolean active=r.getString("state").equals("ACTIVE");var text=new StringBuilder(title).append(' ').append(content.toString());var refs=PublicProjection.references(kind,content);
  for(var ref:refs){var names=jdbc.queryForList("SELECT title FROM public_discovery_source WHERE space_id=? AND entity_id=? AND state='ACTIVE'",String.class,key.space(),ref.target());if(!names.isEmpty())text.append(' ').append(names.getFirst());}
  if(kind.equals("CONCEPT")||kind.equals("CLAIM"))for(String extra:jdbc.queryForList("SELECT title||' '||content::text FROM public_discovery_source WHERE space_id=? AND state='ACTIVE' AND ((kind='ALIAS' AND content->>'conceptId'=?) OR (kind='QUALIFIER' AND content->>'claimId'=?))",String.class,key.space(),key.id().toString(),key.id().toString())){if(text.length()+extra.length()<60000)text.append(' ').append(extra);}
  String hash=PublicProjection.hash(kind+title+content.toString()+key.sequence()+active);
  return new PublicProjection.Document(key.id(),key.space(),kind,r.getLong("version"),key.sequence(),active,title,text.toString(),refs,hash);
 },key.space(),key.id());return rows.isEmpty()?null:rows.getFirst();}
 void materialize(List<Key> keys,PublicGraphStore graph,NoteSearchClient search){tx.executeWithoutResult(s->{lock();for(var key:keys){var current=jdbc.queryForList("SELECT sequence FROM public_discovery_work WHERE space_id=? AND entity_id=?",Long.class,key.space(),key.id());if(current.isEmpty()||current.getFirst()!=key.sequence())continue;var d=document(key);if(d!=null){graph.apply(d);search.applyPublic(d);}jdbc.update("DELETE FROM public_discovery_work WHERE space_id=? AND entity_id=? AND sequence=?",key.space(),key.id(),key.sequence());}});}
}
