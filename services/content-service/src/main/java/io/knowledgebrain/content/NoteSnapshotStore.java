package io.knowledgebrain.content;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
final class NoteSnapshotStore {
    record Item(UUID noteId,UUID spaceId,UUID revisionId,long version,String visibility,String state,String title,String markdown) {}
    record Page(UUID through,UUID nextAfter,List<Item> items) {}
    record LinkItem(UUID noteId,UUID spaceId,UUID revisionId,long linkVersion,String payload) {}
    record LinkPage(UUID through,UUID nextAfter,List<LinkItem> items) {}
    private final JdbcTemplate jdbc;
    NoteSnapshotStore(DataSource source) { jdbc=new JdbcTemplate(source); }
    LinkPage links(UUID after,UUID through,int limit){
        if(limit<1 || limit>5 || (after!=null && through==null) || (after!=null && after.toString().compareTo(through.toString())>=0))throw new IllegalArgumentException("Invalid snapshot cursor");
        if(through==null){var ids=jdbc.query("SELECT note_id FROM note ORDER BY note_id DESC LIMIT 1",(rs,n)->rs.getObject(1,UUID.class));if(ids.isEmpty())return new LinkPage(null,null,List.of());through=ids.getFirst();}
        String sql="SELECT n.note_id,n.space_id,r.revision_id,n.link_version,r.payload,n.links_dirty FROM note n LEFT JOIN note_link_revision r ON r.source_note_id=n.note_id AND r.link_version=n.link_version WHERE n.note_id<=?"+(after==null?"":" AND n.note_id>?")+" ORDER BY n.note_id LIMIT ?";
        Object[] arguments=after==null?new Object[]{through,limit+1}:new Object[]{through,after,limit+1};
        var items=jdbc.query(sql,(rs,n)->{
            if(rs.getBoolean(6) || rs.getObject(3)==null || rs.getLong(4)<1)throw new IllegalStateException("Canonical link snapshot unavailable");
            return new LinkItem(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getLong(4),java.util.Base64.getEncoder().encodeToString(rs.getBytes(5)));
        },arguments);
        boolean more=items.size()>limit;var result=List.copyOf(items.subList(0,Math.min(limit,items.size())));
        return new LinkPage(through,more?result.getLast().noteId():null,result);
    }
    Page page(UUID after,UUID through,int limit) {
        if(limit<1 || limit>5 || (after!=null && through==null) || (after!=null && after.toString().compareTo(through.toString())>=0))throw new IllegalArgumentException("Invalid snapshot cursor");
        if(through==null) {
            var ids=jdbc.query("SELECT note_id FROM note ORDER BY note_id DESC LIMIT 1",(rs,n)->rs.getObject(1,UUID.class));
            if(ids.isEmpty())return new Page(null,null,List.of());through=ids.getFirst();
        }
        String sql="SELECT n.note_id,n.space_id,r.revision_id,n.version,n.visibility,n.state,n.title,n.markdown FROM note n LEFT JOIN note_revision r ON r.note_id=n.note_id AND r.version=n.version WHERE n.note_id<=?"+(after==null?"":" AND n.note_id>?")+" ORDER BY n.note_id LIMIT ?";
        Object[] arguments=after==null?new Object[]{through,limit+1}:new Object[]{through,after,limit+1};
        var items=jdbc.query(sql,(rs,n)->{
            var revision=rs.getObject(3,UUID.class);if(revision==null)throw new IllegalStateException("Canonical revision missing");
            boolean retired="RETIRED".equals(rs.getString(6));
            return new Item(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),revision,rs.getLong(4),rs.getString(5),rs.getString(6),retired?"":rs.getString(7),retired?"":rs.getString(8));
        },arguments);
        boolean more=items.size()>limit;var result=List.copyOf(items.subList(0,Math.min(limit,items.size())));
        return new Page(through,more?result.getLast().noteId():null,result);
    }
}
