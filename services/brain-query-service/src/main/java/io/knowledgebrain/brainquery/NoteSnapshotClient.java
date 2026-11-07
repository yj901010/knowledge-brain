package io.knowledgebrain.brainquery;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;
import javax.net.ssl.*;
import tools.jackson.databind.json.JsonMapper;
final class NoteSnapshotClient {
    record Item(UUID noteId,UUID spaceId,UUID revisionId,long version,String visibility,String state,String title,String markdown) {
        NoteSearchProjection.Revision projection() {
            if(noteId==null || spaceId==null || revisionId==null)throw invalid();
            for(var id:List.of(noteId,spaceId,revisionId))if(id.version()!=7 || id.variant()!=2)throw invalid();
            if(version<1 || !"PRIVATE".equals(visibility) || !List.of("ACTIVE","RETIRED").contains(state)
                    || title==null || markdown==null || title.codePointCount(0,title.length())>200
                    || markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768
                    || (state.equals("ACTIVE") && title.isBlank())
                    || (state.equals("RETIRED") && (!title.isEmpty() || !markdown.isEmpty())))throw invalid();
            return new NoteSearchProjection.Revision(revisionId,spaceId,noteId,version,state,title,markdown);
        }
    }
    record Page(UUID through,UUID nextAfter,List<Item> items) {}
    record LinkItem(UUID noteId,UUID spaceId,UUID revisionId,long linkVersion,String payload) {
        byte[] wire(){
            try{
                var data=io.knowledgebrain.contracts.content.links.v1.NoteLinksChangedPayloadV1.parseFrom(Base64.getDecoder().decode(payload));
                if(noteId==null || spaceId==null || revisionId==null || !noteId.toString().equals(data.getSourceNoteId()) || !revisionId.toString().equals(data.getRevisionId()) || linkVersion!=data.getLinkVersion())throw invalid();
                var wire=io.knowledgebrain.contracts.content.links.v1.LinkEventEnvelopeV1.newBuilder().setEventId(revisionId.toString()).setSpaceId(spaceId.toString()).setAggregateId(noteId.toString()).setAggregateType("NoteLinks").setEventType("NoteLinksChanged").setEventVersion(1).setProducer("content-service").setOccurredAtEpochMillis(1).setTraceId("e".repeat(32)).setPayload(data.toByteString()).build().toByteArray();
                NoteGraphProjection.decode(wire);return wire;
            }catch(Exception e){throw invalid();}
        }
    }
    record LinkPage(UUID through,UUID nextAfter,List<LinkItem> items) {}
    LinkPage links(UUID after,UUID through){
        try{
            String path="/internal/content/note-link-snapshots?limit=5"+(after==null?"":"&after="+after)+(through==null?"":"&through="+through);
            var response=http.send(HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+key).GET().build(),info->new NoteSearchClient.BoundedBody());
            if(response.statusCode()!=200)throw invalid();
            var page=JsonMapper.builder().build().readValue(response.body(),LinkPage.class);
            if(page==null || page.items()==null || page.items().size()>5 || through!=null && !through.equals(page.through()) || page.through()==null && (!page.items().isEmpty() || page.nextAfter()!=null))throw invalid();
            if(page.through()!=null)NoteGraphProjection.id(page.through().toString());
            UUID previous=after;
            for(var item:page.items()){item.wire();if(page.through()==null || item.noteId().toString().compareTo(page.through().toString())>0 || previous!=null && item.noteId().toString().compareTo(previous.toString())<=0)throw invalid();previous=item.noteId();}
            if(page.nextAfter()!=null && (page.items().isEmpty() || !page.nextAfter().equals(previous)))throw invalid();return page;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw invalid();}catch(Exception e){throw invalid();}
    }
    static byte[] noteWire(Item item){
        var n=item.projection();
        var payload=io.knowledgebrain.contracts.content.v1.NoteChangedPayloadV1.newBuilder().setNoteId(n.noteId().toString()).setRevisionId(item.revisionId().toString()).setVersion(n.version()).setVisibility("PRIVATE").setState(n.state()).setTitle(n.state().equals("RETIRED")?"retired":n.title()).setMarkdown(n.markdown()).build();
        return io.knowledgebrain.contracts.content.v1.EventEnvelopeV1.newBuilder().setEventId(item.revisionId().toString()).setSpaceId(n.spaceId().toString()).setAggregateId(n.noteId().toString()).setAggregateType("Note").setEventType(n.state().equals("RETIRED")?"NoteRetired":n.version()==1?"NoteCreated":"NoteUpdated").setEventVersion(1).setProducer("content-service").setOccurredAtEpochMillis(1).setTraceId("e".repeat(32)).setPayload(payload.toByteString()).build().toByteArray();
    }
    private final URI origin;private final String key;private final HttpClient http;
    NoteSnapshotClient(String endpoint,String trustFile,String key) throws Exception {
        origin=URI.create(endpoint);
        if(!"https".equals(origin.getScheme()) || origin.getHost()==null || origin.getRawUserInfo()!=null || origin.getRawQuery()!=null || origin.getRawFragment()!=null || !origin.getRawPath().isEmpty() || !key.matches("[A-Za-z0-9_-]{43}"))throw invalid();
        this.key=key;var trust=KeyStore.getInstance("PKCS12");trust.load(null,null);
        try(var input=Files.newInputStream(Path.of(trustFile))) { int n=0;for(var cert:CertificateFactory.getInstance("X.509").generateCertificates(input))trust.setCertificateEntry("ca-"+n++,cert); }
        var manager=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());manager.init(trust);
        var tls=SSLContext.getInstance("TLS");tls.init(null,manager.getTrustManagers(),null);
        http=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    Page page(UUID after,UUID through) {
        try {
            String path="/internal/content/note-snapshots?limit=5"+(after==null?"":"&after="+after)+(through==null?"":"&through="+through);
            var request=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+key).GET().build();
            var response=http.send(request,info->new NoteSearchClient.BoundedBody());
            if(response.statusCode()!=200)throw invalid();
            var page=JsonMapper.builder().build().readValue(response.body(),Page.class);
            validate(page,after,through);return page;
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw invalid(); }
        catch(Exception e) { throw invalid(); }
    }
    static void validate(Page page,UUID after,UUID through) {
        if(page==null || page.items()==null || page.items().size()>5 || (through!=null && !through.equals(page.through()))
                || (page.through()==null && (!page.items().isEmpty() || page.nextAfter()!=null)))throw invalid();
        if(page.through()!=null && (page.through().version()!=7 || page.through().variant()!=2))throw invalid();
        UUID previous=after;
        for(var item:page.items()) {
            item.projection();
            if(page.through()==null || item.noteId().toString().compareTo(page.through().toString())>0
                    || (previous!=null && item.noteId().toString().compareTo(previous.toString())<=0))throw invalid();
            previous=item.noteId();
        }
        if(page.nextAfter()!=null && (page.items().isEmpty() || !page.nextAfter().equals(previous)))throw invalid();
    }
    private static IllegalStateException invalid() { return new IllegalStateException("Canonical snapshot unavailable or invalid"); }
}
