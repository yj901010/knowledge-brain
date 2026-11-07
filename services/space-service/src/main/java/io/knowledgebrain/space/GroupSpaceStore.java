package io.knowledgebrain.space;
import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.space.group.v1.*;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
final class GroupSpaceStore {
 record Grant(UUID groupId,UUID spaceId,String role,String visibility) {}
 private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final UuidV7 ids; private final Clock clock;
 GroupSpaceStore(DataSource source,Clock clock){jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);ids=new UuidV7(clock);this.clock=clock;}
 List<Grant> access(UUID principal,List<UUID> groups){
  if(groups.isEmpty())return List.of();
  var sql="SELECT g.group_id,g.space_id,m.role,s.visibility FROM group_space_origin g JOIN space s ON s.space_id=g.space_id JOIN space_membership m ON m.space_id=s.space_id WHERE g.creator_principal_id=? AND s.owner_principal_id=? AND m.principal_id=? AND s.kind='GROUP' AND s.visibility='PRIVATE' AND s.state='ACTIVE' AND m.state='ACTIVE' AND m.role='OWNER' AND g.group_id IN ("+String.join(",",Collections.nCopies(groups.size(),"?"))+") ORDER BY g.group_id";
  var args=new ArrayList<Object>();args.add(principal);args.add(principal);args.add(principal);args.addAll(groups);
  return jdbc.query(sql,(rs,n)->new Grant(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4)),args.toArray());
 }
 record ReviewAccess(UUID groupId,UUID spaceId,UUID principalId,long membershipVersion) {}
 ReviewAccess reviewAccess(UUID group,UUID principal){var rows=jdbc.query("SELECT g.group_id,g.space_id,m.principal_id,m.group_version FROM group_space_origin g JOIN space s ON s.space_id=g.space_id JOIN space_membership m ON m.space_id=s.space_id WHERE g.group_id=? AND m.principal_id=? AND s.kind='GROUP' AND s.visibility='PRIVATE' AND s.state='ACTIVE' AND m.state='ACTIVE' AND m.role IN ('OWNER','MEMBER','VIEWER')",(rs,n)->new ReviewAccess(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getLong(4)),group,principal);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);return rows.getFirst();}
 record ParticipationPage(List<Grant> grants,UUID nextAfter){}
 List<Grant> participation(UUID principal,List<UUID> groups){if(groups.isEmpty())return List.of();var args=new ArrayList<Object>();args.add(principal);args.addAll(groups);return jdbc.query("SELECT g.group_id,g.space_id,m.role,s.visibility FROM group_space_origin g JOIN space s ON s.space_id=g.space_id JOIN space_membership m ON m.space_id=s.space_id WHERE m.principal_id=? AND s.kind='GROUP' AND s.visibility='PRIVATE' AND s.state='ACTIVE' AND m.state='ACTIVE' AND m.role IN ('OWNER','MEMBER','VIEWER') AND g.group_id IN ("+String.join(",",Collections.nCopies(groups.size(),"?"))+") ORDER BY g.group_id",(rs,n)->new Grant(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4)),args.toArray());}
 ParticipationPage participations(UUID principal,UUID after,int limit){var rows=jdbc.query("SELECT g.group_id,g.space_id,m.role,s.visibility FROM group_space_origin g JOIN space s ON s.space_id=g.space_id JOIN space_membership m ON m.space_id=s.space_id WHERE m.principal_id=? AND s.kind='GROUP' AND s.visibility='PRIVATE' AND s.state='ACTIVE' AND m.state='ACTIVE' AND m.role IN ('OWNER','MEMBER','VIEWER') AND g.group_id>? ORDER BY g.group_id LIMIT ?",(rs,n)->new Grant(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4)),principal,after==null?new UUID(0,0):after,limit+1);var page=rows.subList(0,Math.min(limit,rows.size()));return new ParticipationPage(page,rows.size()>limit?page.getLast().groupId():null);}
 Grant provision(UUID group,UUID principal,String trace){return tx.execute(status->{
  jdbc.query("SELECT pg_advisory_xact_lock(?)",(rs,n)->0,group.getMostSignificantBits()^group.getLeastSignificantBits());
  var existing=jdbc.queryForList("SELECT group_id FROM group_space_origin WHERE group_id=?",group);
  if(!existing.isEmpty()){var grants=access(principal,List.of(group));if(grants.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Group Space unavailable");return grants.getFirst();}
  var now=clock.instant();var space=ids.next();var event=ids.next();
  jdbc.update("INSERT INTO space(space_id,kind,visibility,owner_principal_id,created_at) VALUES (?,'GROUP','PRIVATE',?,?)",space,principal,Timestamp.from(now));
  jdbc.update("INSERT INTO space_membership(space_id,principal_id,role) VALUES (?,?,'OWNER')",space,principal);
  jdbc.update("INSERT INTO group_membership_revision(space_id,principal_id,version,role,state,actor_id,occurred_at,reason) VALUES (?,?,1,'OWNER','ACTIVE',?,?,'OWNER_CREATED') ON CONFLICT DO NOTHING",space,principal,principal,Timestamp.from(now));
  jdbc.update("INSERT INTO group_space_origin(group_id,space_id,creator_principal_id) VALUES (?,?,?)",group,space,principal);
  var payload=GroupSpaceCreatedPayloadV1.newBuilder().setGroupId(group.toString()).setSpaceId(space.toString()).setCreatorPrincipalId(principal.toString()).setKind("GROUP").setVisibility("PRIVATE").build();
  var body=EventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType("GroupSpaceCreated").setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("space-service").setAggregateType("Space").setAggregateId(space.toString()).setSpaceId(space.toString()).setActorId(principal.toString()).setTraceId(trace).setPayload(ByteString.copyFrom(payload.toByteArray())).build();
  jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'group-space',?,'GroupSpaceCreated',?,?,'space','group-space-events','1',?)",event,space,Timestamp.from(now),trace,body.toByteArray());
  return new Grant(group,space,"OWNER","PRIVATE");
 });}
}
