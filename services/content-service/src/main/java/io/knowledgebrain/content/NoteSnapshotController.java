package io.knowledgebrain.content;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.MeterRegistry;
@RestController
@ConditionalOnProperty(name="brain.content.snapshot.enabled",havingValue="true")
final class NoteSnapshotController {
    private final NoteSnapshotStore store;private final byte[] expected;private final MeterRegistry metrics;
    NoteSnapshotController(DataSource source,@Value("${brain.content.snapshot.key}") String key,MeterRegistry metrics) {
        if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Scoped snapshot credential required");
        store=new NoteSnapshotStore(source);expected=("Bearer "+key).getBytes(StandardCharsets.US_ASCII);this.metrics=metrics;
    }
    @GetMapping("/internal/content/note-link-snapshots")
    ResponseEntity<NoteSnapshotStore.LinkPage> links(HttpServletRequest request){
        authorize(request);
        try{var cursor=cursor(request);var page=store.links(cursor.after(),cursor.through(),cursor.limit());metrics.counter("brain.content.snapshot.pages","kind","links").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(page);}
        catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid snapshot request");}
    }
    private record Cursor(UUID after,UUID through,int limit){}
    private Cursor cursor(HttpServletRequest request){
        for(var entry:request.getParameterMap().entrySet())if(!Set.of("after","through","limit").contains(entry.getKey()) || entry.getValue().length!=1)throw new IllegalArgumentException();
        String raw=request.getParameter("limit");if(raw!=null && !raw.matches("[1-5]"))throw new IllegalArgumentException();
        return new Cursor(id(request.getParameter("after")),id(request.getParameter("through")),raw==null?5:Integer.parseInt(raw));
    }
    private void authorize(HttpServletRequest request){
        if(!request.isSecure())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"HTTPS required");
        var headers=Collections.list(request.getHeaders("Authorization"));
        if(headers.size()!=1 || !MessageDigest.isEqual(expected,headers.getFirst().getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Snapshot credential required");
        if(request.getHeader("Cookie")!=null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Service credential only");
    }
    @GetMapping("/internal/content/note-snapshots")
    ResponseEntity<NoteSnapshotStore.Page> get(HttpServletRequest request) {
        if(!request.isSecure())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"HTTPS required");
        var headers=Collections.list(request.getHeaders("Authorization"));
        if(headers.size()!=1 || !MessageDigest.isEqual(expected,headers.getFirst().getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Snapshot credential required");
        if(request.getHeader("Cookie")!=null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Service credential only");
        try {
            for(var entry:request.getParameterMap().entrySet())if(!Set.of("after","through","limit").contains(entry.getKey()) || entry.getValue().length!=1)throw new IllegalArgumentException();
            UUID after=id(request.getParameter("after")),through=id(request.getParameter("through"));
            String raw=request.getParameter("limit");if(raw!=null && !raw.matches("[1-5]"))throw new IllegalArgumentException();
            var page=store.page(after,through,raw==null?5:Integer.parseInt(raw));metrics.counter("brain.content.snapshot.pages").increment();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(page);
        } catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid snapshot request"); }
    }
    private UUID id(String text) {
        if(text==null)return null;var value=UUID.fromString(text);
        if(value.version()!=7 || value.variant()!=2 || !value.toString().equals(text))throw new IllegalArgumentException();return value;
    }
}
