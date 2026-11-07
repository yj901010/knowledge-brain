package io.knowledgebrain.brainquery;
import java.security.MessageDigest;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Brain Query-owned replay state; external graph commit precedes PG applied marker. */
final class GraphProjectionInbox {
 record Input(String kind,UUID eventId,UUID noteId,UUID spaceId,long version,String fingerprint) {}
 record Key(UUID noteId,String kind) {}
 static final class Conflict extends RuntimeException { Conflict(){super("Graph projection input conflict");} }
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;
 GraphProjectionInbox(DataSource source){jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));}
 static Input input(String kind,byte[] wire) {
  if(kind.equals("NOTE")){var n=NoteSearchProjection.decode(wire);return new Input(kind,n.eventId(),n.noteId(),n.spaceId(),n.version(),fingerprint(n.state(),n.title(),n.markdown()));}
  if(kind.equals("LINKS")){var n=NoteGraphProjection.decode(wire);return new Input(kind,n.eventId(),n.sourceNoteId(),n.spaceId(),n.linkVersion(),n.payloadHash());}
  throw new IllegalArgumentException("Unsupported graph input");
 }
 private static String fingerprint(String... fields) {
  try {var digest=MessageDigest.getInstance("SHA-256");for(var field:fields){var bytes=field.getBytes(java.nio.charset.StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}return HexFormat.of().formatHex(digest.digest());}
  catch(Exception e){throw new IllegalStateException("Graph digest unavailable");}
 }
 Key stage(String kind,byte[] wire) {
  var n=input(kind,wire);final byte[] hash;
  try{hash=MessageDigest.getInstance("SHA-256").digest(wire);}catch(Exception e){throw new IllegalStateException("Graph digest unavailable");}
  return tx.execute(status->{
   jdbc.update("INSERT INTO note_graph_anchor(note_id,space_id) VALUES (?,?) ON CONFLICT DO NOTHING",n.noteId(),n.spaceId());
   UUID space=jdbc.queryForObject("SELECT space_id FROM note_graph_anchor WHERE note_id=? FOR UPDATE",UUID.class,n.noteId());
   if(!n.spaceId().equals(space))throw new Conflict();
   jdbc.update("INSERT INTO note_graph_inbox(event_id,payload_hash) VALUES (?,?) ON CONFLICT DO NOTHING",n.eventId(),hash);
   if(!Arrays.equals(hash,jdbc.queryForObject("SELECT payload_hash FROM note_graph_inbox WHERE event_id=?",byte[].class,n.eventId())))throw new Conflict();
   jdbc.update("INSERT INTO note_graph_snapshot(note_id,kind,version,fingerprint,payload) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",n.noteId(),kind,n.version(),n.fingerprint(),wire);
   var current=jdbc.queryForObject("SELECT version,fingerprint FROM note_graph_snapshot WHERE note_id=? AND kind=?",(rs,row)->Map.entry(rs.getLong(1),rs.getString(2)),n.noteId(),kind);
   if(n.version()==current.getKey() && !n.fingerprint().equals(current.getValue()))throw new Conflict();
   if(n.version()>current.getKey())jdbc.update("UPDATE note_graph_snapshot SET version=?,fingerprint=?,payload=? WHERE note_id=? AND kind=?",n.version(),n.fingerprint(),wire,n.noteId(),kind);
   return new Key(n.noteId(),kind);
  });
 }
 List<Key> pending(int limit){return jdbc.query("SELECT note_id,kind FROM note_graph_snapshot WHERE projected_version<version ORDER BY note_id,kind LIMIT ?",(rs,row)->new Key(rs.getObject(1,UUID.class),rs.getString(2)),limit);}
 void project(Key key,NoteGraphStore graph){
  tx.executeWithoutResult(status->{
   jdbc.queryForObject("SELECT space_id FROM note_graph_anchor WHERE note_id=? FOR UPDATE",UUID.class,key.noteId());
   byte[] wire=jdbc.queryForObject("SELECT payload FROM note_graph_snapshot WHERE note_id=? AND kind=?",byte[].class,key.noteId(),key.kind());
   var n=input(key.kind(),wire);
   if(key.kind().equals("NOTE"))graph.apply(NoteSearchProjection.decode(wire));else graph.apply(NoteGraphProjection.decode(wire));
   jdbc.update("UPDATE note_graph_snapshot SET projected_version=? WHERE note_id=? AND kind=?",n.version(),key.noteId(),key.kind());
  });
 }
 void replayAll(){jdbc.update("UPDATE note_graph_snapshot SET projected_version=0");}
}
