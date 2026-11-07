package io.knowledgebrain.collaboration;
import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.collaboration.group.v1.*;
import java.time.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
final class GroupStore {
 record Group(UUID groupId,UUID creatorPrincipalId,String title,String description,String state,UUID spaceId,long version,Instant createdAt,Instant updatedAt,String visibility){}
 record Revision(long version,String state,UUID spaceId,UUID actorId,Instant occurredAt,String title,String description){}
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;private final UuidV7 ids;
 GroupStore(DataSource source,Clock clock){jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);}
 private Group row(ResultSet rs)throws SQLException{return new Group(rs.getObject("group_id",UUID.class),rs.getObject("creator_principal_id",UUID.class),rs.getString("title"),rs.getString("description"),rs.getString("state"),rs.getObject("space_id",UUID.class),rs.getLong("version"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),"PRIVATE");}
 Group owned(UUID id,UUID owner){var rows=jdbc.query("SELECT * FROM collaboration_group WHERE group_id=? AND creator_principal_id=?",(rs,n)->row(rs),id,owner);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Group unavailable");return rows.getFirst();}
 List<Group> candidates(UUID owner,UUID after,int limit){return jdbc.query("SELECT * FROM collaboration_group WHERE creator_principal_id=? AND group_id>? ORDER BY group_id LIMIT ?",(rs,n)->row(rs),owner,after==null?new UUID(0,0):after,limit+1);}
 List<Revision> history(UUID id,long before,int limit){return jdbc.query("SELECT * FROM group_revision WHERE group_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getLong("version"),rs.getString("state"),rs.getObject("space_id",UUID.class),rs.getObject("actor_id",UUID.class),rs.getTimestamp("occurred_at").toInstant(),rs.getString("title"),rs.getString("description")),id,before,limit+1);}
 List<Group> active(List<UUID> ids){if(ids.isEmpty())return List.of();return jdbc.query("SELECT * FROM collaboration_group WHERE state='ACTIVE' AND group_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY group_id",(rs,n)->row(rs),ids.toArray());}
 Group request(UUID owner,String key,String title,String description,String trace){return tx.execute(status->{
  jdbc.query("SELECT pg_advisory_xact_lock(?)",(rs,n)->0,owner.getMostSignificantBits()^owner.getLeastSignificantBits()^key.hashCode());
  var existing=jdbc.query("SELECT * FROM collaboration_group WHERE creator_principal_id=? AND request_key=?",(rs,n)->row(rs),owner,key);
  if(!existing.isEmpty()){var group=existing.getFirst();if(!group.title().equals(title)||!group.description().equals(description))throw new ResponseStatusException(HttpStatus.CONFLICT,"Request key already used for different Group settings");return group;}
  var id=ids.next();var now=Timestamp.from(clock.instant());
  jdbc.update("INSERT INTO collaboration_group(group_id,creator_principal_id,request_key,title,description,state,version,created_at,updated_at) VALUES (?,?,?,?,?,'PENDING',1,?,?)",id,owner,key,title,description,now,now);
  var group=owned(id,owner);record(group,trace,"GroupCreationRequested");return group;
 });}
 Group activate(UUID id,UUID owner,UUID space,String trace){return tx.execute(status->{
  jdbc.query("SELECT group_id FROM collaboration_group WHERE group_id=? FOR UPDATE",(rs,n)->0,id);var group=owned(id,owner);
  if(group.state().equals("ACTIVE")){if(!space.equals(group.spaceId()))throw new IllegalStateException("Group Space conflict");return group;}
  jdbc.update("UPDATE collaboration_group SET state='ACTIVE',space_id=?,version=2,updated_at=? WHERE group_id=?",space,Timestamp.from(clock.instant()),id);
  var active=owned(id,owner);record(active,trace,"GroupActivated");return active;
 });}
 private void record(Group group,String trace,String type){
  jdbc.update("INSERT INTO group_revision(group_id,version,state,space_id,actor_id,occurred_at,title,description) VALUES (?,?,?,?,?,?,?,?)",group.groupId(),group.version(),group.state(),group.spaceId(),group.creatorPrincipalId(),Timestamp.from(group.updatedAt()),group.title(),group.description());
  var payload=GroupSnapshotV1.newBuilder().setGroupId(group.groupId().toString()).setCreatorPrincipalId(group.creatorPrincipalId().toString()).setTitle(group.title()).setDescription(group.description()).setState(group.state()).setVersion(group.version()).setVisibility("PRIVATE");
  if(group.spaceId()!=null)payload.setSpaceId(group.spaceId().toString());var event=ids.next();
  var body=EventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(group.updatedAt().toEpochMilli()).setProducer("collaboration-service").setAggregateType("Group").setAggregateId(group.groupId().toString()).setActorId(group.creatorPrincipalId().toString()).setTraceId(trace).setPayload(ByteString.copyFrom(payload.build().toByteArray()));
  if(group.spaceId()!=null)body.setSpaceId(group.spaceId().toString());
  jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'group',?,?,?,?,'collaboration','group-events','1',?)",event,group.groupId(),type,Timestamp.from(group.updatedAt()),trace,body.build().toByteArray());
 }
}
