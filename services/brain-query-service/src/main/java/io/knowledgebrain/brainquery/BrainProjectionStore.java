package io.knowledgebrain.brainquery;
import java.util.*;
import java.security.MessageDigest;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Own-DB disposable snapshots and a durable fan-out queue shared by both derived stores. */
final class BrainProjectionStore {
 record Key(String kind,UUID id){}
 record Document(String kind,UUID id,UUID spaceId,long canonicalVersion,long sequence,boolean visible,String title,String text,List<BrainProjection.Ref> refs,UUID subject){
  String key(){return kind+":"+id;}
  Map<String,Object> source(){return Map.of("entityKey",key(),"kind",kind,"entityId",id.toString(),"spaceId",spaceId.toString(),"canonicalVersion",canonicalVersion,"visibility","PRIVATE","state",visible?"ACTIVE":"RETIRED","title",title,"text",text,"subjectConceptId",subject==null?"":subject.toString());}
 }
 static final class Conflict extends RuntimeException{Conflict(){super("Unified projection conflict");}}
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final JsonMapper json=JsonMapper.builder().build();
 BrainProjectionStore(DataSource ds){jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));}
 private void lock(){jdbc.execute("SELECT pg_advisory_xact_lock(714263901)");}
 static BrainProjection.Input snapshot(JsonMapper json,String raw){var data=json.readTree(raw);if(Set.of("NOTE","LINKS","NOTE_CONCEPTS","CONCEPT","CLAIM").contains(data.path("kind").asString())){var object=(tools.jackson.databind.node.ObjectNode)data;if(!object.has("recordVersion"))object.put("recordVersion",0L);if(!object.has("aggregateId"))object.put("aggregateId",object.path("entityId").asString());}return json.treeToValue(data,BrainProjection.Input.class);}
 private BrainProjection.Input get(String kind,UUID id){var rows=jdbc.query("SELECT data::text FROM brain_network_snapshot WHERE kind=? AND entity_id=?",(rs,n)->{try{return snapshot(json,rs.getString(1));}catch(Exception e){throw new Conflict();}},kind,id);return rows.isEmpty()?null:rows.getFirst();}
 void stage(String topic,byte[] wire){stage(BrainProjection.decode(topic,wire),wire);}
 void stage(BrainProjection.Input n,byte[] wire){
  tx.executeWithoutResult(status->{lock();byte[] hash;try{hash=MessageDigest.getInstance("SHA-256").digest(wire);}catch(Exception e){throw new Conflict();}
   jdbc.update("INSERT INTO brain_network_inbox(event_id,payload_hash) VALUES(?,?) ON CONFLICT DO NOTHING",n.eventId(),hash);if(!Arrays.equals(hash,jdbc.queryForObject("SELECT payload_hash FROM brain_network_inbox WHERE event_id=?",byte[].class,n.eventId())))throw new Conflict();
   var old=get(n.kind(),n.entityId());
   if(old!=null){if(!old.spaceId().equals(n.spaceId())||!old.visibility().equals(n.visibility())||!old.aggregateId().equals(n.aggregateId())||n.kind().equals("CLAIM")&&!old.refs().getFirst().targetId().equals(n.refs().getFirst().targetId()))throw new Conflict();if(old.version()>n.version())return;if(old.version()==n.version()){if(!old.fingerprint().equals(n.fingerprint()))throw new Conflict();return;}if(old.state().equals("RETIRED")&&!n.state().equals("RETIRED"))throw new Conflict();}
   // Note, link and Concept-reference versions are independent, but their Space is immutable.
   if(n.owner().equals("NOTE"))for(String kind:List.of("NOTE","LINKS","NOTE_CONCEPTS")){var prior=get(kind,n.entityId());if(prior!=null&&!prior.spaceId().equals(n.spaceId()))throw new Conflict();}
   if(BrainProjection.RECORDS.contains(n.owner())){var parent=get(n.owner(),n.entityId());var links=get(n.owner()+"_CONCEPTS",n.entityId());for(var prior:new BrainProjection.Input[]{parent,links})if(prior!=null&&!prior.spaceId().equals(n.spaceId()))throw new Conflict();}
   String data;try{data=json.writeValueAsString(n);}catch(Exception e){throw new Conflict();}
   jdbc.update("INSERT INTO brain_network_snapshot(kind,entity_id,space_id,version,fingerprint,data) VALUES(?,?,?,?,?,?::jsonb) ON CONFLICT(kind,entity_id) DO UPDATE SET version=EXCLUDED.version,fingerprint=EXCLUDED.fingerprint,data=EXCLUDED.data",n.kind(),n.entityId(),n.spaceId(),n.version(),n.fingerprint(),data);
   jdbc.update("DELETE FROM brain_network_relation WHERE owner_kind=? AND owner_id=?",n.kind(),n.entityId());
   if(n.state().equals("ACTIVE"))for(var r:n.refs())jdbc.update("INSERT INTO brain_network_relation(owner_kind,owner_id,relation_id,role,target_kind,target_id) VALUES(?,?,?,?,?,?)",n.kind(),n.entityId(),r.id(),r.role(),r.targetKind(),r.targetId());
   queue(new Key(n.owner(),n.entityId()));
   if(n.kind().equals("CONCEPT")){
    // A Concept rename/retirement updates Claim titles/visibility and linked Note search text.
    var dependents=jdbc.query("SELECT DISTINCT owner_kind,owner_id FROM brain_network_relation WHERE target_kind='CONCEPT' AND target_id=? ORDER BY owner_kind,owner_id",(rs,i)->new Key(owner(rs.getString(1)),rs.getObject(2,UUID.class)),n.entityId());
    for(var key:dependents)queue(key);
   }
  });
 }
 private static String owner(String kind){return kind.endsWith("_CONCEPTS")?kind.substring(0,kind.length()-9):kind;}
 private void queue(Key key){jdbc.update("INSERT INTO brain_network_work(kind,entity_id,sequence) VALUES(?,?,nextval('brain_work_sequence')) ON CONFLICT(kind,entity_id) DO UPDATE SET sequence=EXCLUDED.sequence",key.kind(),key.id());}
 List<Key> pending(int limit){if(limit<1||limit>100)throw BrainProjection.invalid();return jdbc.query("SELECT kind,entity_id FROM brain_network_work WHERE applied_sequence<sequence ORDER BY sequence LIMIT ?",(rs,n)->new Key(rs.getString(1),rs.getObject(2,UUID.class)),limit);}
 private boolean active(BrainProjection.Input row,UUID space){return row!=null&&row.spaceId().equals(space)&&row.state().equals("ACTIVE")&&row.visibility().equals("PRIVATE");}
 Document document(Key key,long seq){
  var n=get(key.kind(),key.id());if(n==null){var partial=key.kind().equals("NOTE")?get("LINKS",key.id()):get(key.kind()+"_CONCEPTS",key.id());if(partial==null&&key.kind().equals("NOTE"))partial=get("NOTE_CONCEPTS",key.id());if(partial==null)throw new Conflict();return new Document(key.kind(),key.id(),partial.spaceId(),0,seq,false,"","",List.of(),null);}
  boolean visible=active(n,n.spaceId());String title=n.title(),text=n.body();var refs=new ArrayList<BrainProjection.Ref>();UUID subject=null;
  if(n.kind().equals("CLAIM")){
   subject=n.refs().getFirst().targetId();var names=new ArrayList<String>();for(var r:n.refs()){var c=get("CONCEPT",r.targetId());visible&=active(c,n.spaceId());names.add(c==null?"":c.title());}if(n.refs().size()==2&&!n.body().isEmpty())names.add(n.body().substring(0,n.body().offsetByCodePoints(0,Math.min(200,n.body().codePointCount(0,n.body().length())))));title=String.join(" → ",names);refs.addAll(n.refs());
  }else if(n.kind().equals("NOTE")){
   var links=get("LINKS",n.entityId());var concepts=get("NOTE_CONCEPTS",n.entityId());
   if(links!=null){visible&=links.state().equals("ACTIVE");if(links.state().equals("ACTIVE"))refs.addAll(links.refs());}
   if(concepts!=null){visible&=concepts.state().equals("ACTIVE");for(var r:concepts.refs()){var c=get("CONCEPT",r.targetId());if(active(c,n.spaceId())){refs.add(r);text+="\n"+c.title();}}}
  }
  if(BrainProjection.RECORDS.contains(n.kind())){var concepts=get(n.kind()+"_CONCEPTS",n.entityId());if(concepts!=null)for(var r:concepts.refs()){var c=get("CONCEPT",r.targetId());if(active(c,n.spaceId())){refs.add(r);text+="\n"+c.title();}}}
  return new Document(n.kind(),n.entityId(),n.spaceId(),n.version(),seq,visible,visible?title:"",visible?text:"",visible?List.copyOf(refs):List.of(),subject);
 }
 void project(Key key,BrainGraphStore graph,NoteSearchClient search){projectBatch(List.of(key),graph,search);}
 void projectBatch(List<Key> keys,BrainGraphStore graph,NoteSearchClient search){if(keys.isEmpty()||keys.size()>50)throw BrainProjection.invalid();tx.executeWithoutResult(status->{lock();var docs=new ArrayList<Document>();for(var key:keys){var sequence=jdbc.queryForObject("SELECT sequence FROM brain_network_work WHERE kind=? AND entity_id=? FOR UPDATE",Long.class,key.kind(),key.id());docs.add(document(key,sequence));}graph.applyBatch(docs);search.applyBrainBatch(docs);for(var doc:docs)jdbc.update("UPDATE brain_network_work SET applied_sequence=? WHERE kind=? AND entity_id=?",doc.sequence(),doc.kind(),doc.id());});}
 void replayAll(){tx.executeWithoutResult(s->{lock();jdbc.update("UPDATE brain_network_work SET applied_sequence=0");});}
 void drain(BrainGraphStore graph,NoteSearchClient search){for(int i=0;i<100000;i++){var keys=pending(50);if(keys.isEmpty())return;projectBatch(keys,graph,search);}throw new IllegalStateException("Unified rebuild queue bound exceeded");}
}
