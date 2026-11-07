package io.knowledgebrain.brainquery;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Service-owned durable Inbox and rebuildable projection queue, never canonical note data. */
final class NoteProjectionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    NoteProjectionStore(DataSource source) {
        jdbc=new JdbcTemplate(source); transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    byte[] stage(byte[] wire) {
        var note=NoteSearchProjection.decode(wire);
        final byte[] hash;
        try { hash=MessageDigest.getInstance("SHA-256").digest(wire); }
        catch (Exception e) { throw new IllegalStateException("Digest unavailable"); }
        return transactions.execute(status -> {
            jdbc.update("INSERT INTO note_search_inbox(event_id,payload_hash) VALUES (?,?) ON CONFLICT DO NOTHING",note.eventId(),hash);
            byte[] stored=jdbc.queryForObject("SELECT payload_hash FROM note_search_inbox WHERE event_id=?",byte[].class,note.eventId());
            if(!Arrays.equals(hash,stored))throw new NoteSearchClient.Conflict();
            jdbc.update("INSERT INTO note_search_snapshot(note_id,space_id,version,payload) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",note.noteId(),note.spaceId(),note.version(),wire);
            var current=jdbc.queryForObject("SELECT space_id,version,payload FROM note_search_snapshot WHERE note_id=? FOR UPDATE",(rs,n)->new Snapshot(rs.getObject(1,UUID.class),rs.getLong(2),rs.getBytes(3)),note.noteId());
            if(!note.spaceId().equals(current.space()))throw new NoteSearchClient.Conflict();
            if(note.version()>current.version()) {
                jdbc.update("UPDATE note_search_snapshot SET version=?,payload=? WHERE note_id=?",note.version(),wire,note.noteId());return wire.clone();
            }
            if(note.version()==current.version() && !NoteSearchProjection.decode(current.wire()).source().equals(note.source()))
                throw new NoteSearchClient.Conflict();
            return current.wire();
        });
    }
    private record Snapshot(UUID space,long version,byte[] wire) {}
    List<byte[]> pending(int limit) {
        return jdbc.query("SELECT payload FROM note_search_snapshot WHERE projected_version<version ORDER BY note_id LIMIT ?",(rs,n)->rs.getBytes(1),limit);
    }
    void applied(NoteSearchProjection.Revision note) {
        jdbc.update("UPDATE note_search_snapshot SET projected_version=GREATEST(projected_version,?) WHERE note_id=? AND space_id=? AND version>=?",note.version(),note.noteId(),note.spaceId(),note.version());
    }
}
