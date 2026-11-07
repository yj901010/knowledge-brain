package io.knowledgebrain.brainquery;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed-origin mTLS adapter. Live roles have no administrator credentials or arbitrary index/query API; fixed public reset is operator-only. */
public final class NoteSearchClient {
    public enum Applied { INDEXED, DUPLICATE, SUPERSEDED }
    public static final class Unavailable extends RuntimeException {
        public Unavailable() { super("Search storage unavailable"); }
    }
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("Conflicting search revision"); }
    }
    private final URI origin;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public NoteSearchClient(String endpoint, Path identity) throws Exception {
        origin = URI.create(endpoint);
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getRawUserInfo() != null
                || origin.getRawQuery() != null || origin.getRawFragment() != null || !origin.getRawPath().isEmpty())
            throw new IllegalArgumentException("Fixed HTTPS search origin required");
        var certificates = CertificateFactory.getInstance("X.509");
        var trust = KeyStore.getInstance("PKCS12"); trust.load(null, null);
        try (var stream = Files.newInputStream(identity.resolve("ca.crt"))) {
            int n=0; for (var cert : certificates.generateCertificates(stream)) trust.setCertificateEntry("ca-"+n++, cert);
        }
        var trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trusts.init(trust);
        var keys = KeyStore.getInstance("PKCS12"); keys.load(null, null);
        var pem = Files.readString(identity.resolve("tls.key")).replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        try (var stream = Files.newInputStream(identity.resolve("tls.crt"))) {
            var chain = certificates.generateCertificates(stream).toArray(java.security.cert.Certificate[]::new);
            keys.setKeyEntry("client", key, new char[0], chain);
        }
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(keys, new char[0]);
        var tls = SSLContext.getInstance("TLS"); tls.init(managers.getKeyManagers(), trusts.getTrustManagers(), null);
        http = HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription=value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + (long)buffer.remaining() > 2097152) {
                    subscription.cancel(); result.completeExceptionally(new Unavailable()); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(new Unavailable()); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    private record Response(int status, JsonNode body) {}
    private Response send(String method, String path, Object body) {
        try {
            var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json").method(method, body == null
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body instanceof Bulk bulk?bulk.json():json.writeValueAsString(body)));
            var response = http.send(request.build(), info -> new BoundedBody());
            byte[] bytes = response.body();
            return new Response(response.statusCode(), bytes.length == 0 ? null : json.readTree(bytes));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new Unavailable();
        } catch (Exception e) { throw new Unavailable(); }
    }

    public Applied apply(NoteSearchProjection.Revision revision) {
        var response = send("PUT", "/"+NoteSearchProjection.INDEX+"/_doc/"+revision.noteId()
                +"?version="+revision.version()+"&version_type=external", revision.source());
        if (response.status == 200 || response.status == 201) return Applied.INDEXED;
        if (response.status != 409) throw new Unavailable();
        var stored = send("GET", "/"+NoteSearchProjection.INDEX+"/_doc/"+revision.noteId(), null);
        if (stored.status != 200 || stored.body == null) throw new Unavailable();
        var source = stored.body.path("_source");
        if (!source.path("spaceId").asString().equals(revision.spaceId().toString())
                || !source.path("noteId").asString().equals(revision.noteId().toString())
                || !source.path("visibility").asString().equals("PRIVATE")) throw new Conflict();
        long version = stored.body.path("_version").asLong();
        if (version > revision.version()) return Applied.SUPERSEDED;
        if (version == revision.version() && source.equals(json.valueToTree(revision.source()))) return Applied.DUPLICATE;
        throw new Conflict();
    }

    /** Caller must obtain fresh authoritative Space read permission before calling. */
    public JsonNode search(UUID authorizedSpace, String text, int offset, int size) {
        var query = NoteSearchProjection.query(authorizedSpace, text, offset, size);
        var response = send("POST", "/"+NoteSearchProjection.INDEX+"/_search", query);
        if (response.status != 200 || response.body == null || !response.body.path("timed_out").isBoolean() || response.body.path("timed_out").asBoolean()
                || !response.body.path("_shards").path("failed").isIntegralNumber() || response.body.path("_shards").path("failed").asInt() != 0) throw new Unavailable();
        return response.body.path("hits");
    }

    static final String BRAIN_INDEX="brain-network-v1";
    private record Bulk(String json){}
    void applyBrainBatch(List<BrainProjectionStore.Document> documents){
        if(documents.isEmpty()||documents.size()>50)throw BrainProjection.invalid();var body=new StringBuilder();
        for(var d:documents){body.append(json.writeValueAsString(java.util.Map.of("index",java.util.Map.of("_id",d.key(),"version",d.sequence(),"version_type","external")))).append('\n');body.append(json.writeValueAsString(d.source())).append('\n');}
        var r=send("POST","/"+BRAIN_INDEX+"/_bulk",new Bulk(body.toString()));if(r.status!=200||r.body==null||!r.body.path("items").isArray()||r.body.path("items").size()!=documents.size())throw new Unavailable();
        for(int i=0;i<documents.size();i++){var item=r.body.path("items").get(i).path("index");var d=documents.get(i);if(!item.path("_id").asString().equals(d.key())||!item.path("_index").asString().equals(BRAIN_INDEX)||!item.path("status").isIntegralNumber())throw new Unavailable();int status=item.path("status").asInt();if(status==409)applyBrain(d);else if(status!=200&&status!=201)throw new Unavailable();}
    }
    void pingBrain(){var r=send("GET","/"+BRAIN_INDEX+"/_doc/NOTE:019a0000-0000-7000-8000-ffffffffffff",null);if(r.status!=200&&r.status!=404)throw new Unavailable();if(r.status==404&&r.body!=null&&r.body.has("error"))throw new Unavailable();}
    void applyBrain(BrainProjectionStore.Document document){
        var response=send("PUT","/"+BRAIN_INDEX+"/_doc/"+document.key()+"?version="+document.sequence()+"&version_type=external",document.source());
        if(response.status==200||response.status==201)return;if(response.status!=409)throw new Unavailable();
        var stored=send("GET","/"+BRAIN_INDEX+"/_doc/"+document.key(),null);if(stored.status!=200||stored.body==null)throw new Unavailable();var source=stored.body.path("_source");
        if(!source.path("entityKey").asString().equals(document.key())||!source.path("spaceId").asString().equals(document.spaceId().toString()))throw new Conflict();
        long sequence=stored.body.path("_version").asLong();if(sequence>document.sequence())return;if(sequence!=document.sequence()||source.size()!=document.source().size())throw new Conflict();
        for(var entry:document.source().entrySet()){var v=source.path(entry.getKey());if(entry.getValue() instanceof Number n){if(!v.isIntegralNumber()||v.asLong()!=n.longValue())throw new Conflict();}else if(!v.isString()||!v.asString().equals(entry.getValue()))throw new Conflict();}
    }
    static Object brainQuery(UUID space,String text,int offset,int limit,String kind){
        NoteSearchProjection.query(space,text,offset,limit);if(kind!=null&&!List.of("NOTE","CONCEPT","CLAIM","BOOKMARK","QUESTION","DECISION").contains(kind))throw BrainProjection.invalid();
        var filters=new java.util.ArrayList<Object>(List.of(java.util.Map.of("term",java.util.Map.of("spaceId",space.toString())),java.util.Map.of("term",java.util.Map.of("visibility","PRIVATE")),java.util.Map.of("term",java.util.Map.of("state","ACTIVE"))));if(kind!=null)filters.add(java.util.Map.of("term",java.util.Map.of("kind",kind)));
        return java.util.Map.of("query",java.util.Map.of("bool",java.util.Map.of("filter",filters,"must",List.of(java.util.Map.of("multi_match",java.util.Map.of("query",text.strip(),"fields",List.of("title^3","text"),"operator","and","type","best_fields"))))),"from",offset,"size",limit,"track_total_hits",true,"_source",List.of("entityKey","kind","entityId","spaceId","visibility","state","title","canonicalVersion","subjectConceptId"),"sort",List.of(java.util.Map.of("_score","desc"),java.util.Map.of("entityKey","asc")));
    }
    JsonNode searchBrain(UUID space,String text,int offset,int limit,String kind){var r=send("POST","/"+BRAIN_INDEX+"/_search",brainQuery(space,text,offset,limit,kind));if(r.status!=200||r.body==null||!r.body.path("timed_out").isBoolean()||r.body.path("timed_out").asBoolean()||!r.body.path("_shards").path("failed").isIntegralNumber()||r.body.path("_shards").path("failed").asInt()!=0)throw new Unavailable();return r.body.path("hits");}
    void applyPublic(PublicProjection.Document d){
        var r=send("PUT","/"+PublicProjection.INDEX+"/_doc/"+d.id()+"?version="+d.sequence()+"&version_type=external",d.source());
        if(r.status==200||r.status==201)return;if(r.status!=409)throw new Unavailable();
        var prior=send("GET","/"+PublicProjection.INDEX+"/_doc/"+d.id(),null);if(prior.status!=200||!prior.body.path("_source").path("spaceId").asString().equals(d.space().toString())||!prior.body.path("_source").path("visibility").asString().equals("PUBLIC"))throw new Conflict();
        long version=prior.body.path("_version").asLong();if(version>d.sequence())return;if(version!=d.sequence()||prior.body.path("_source").size()!=d.source().size())throw new Conflict();for(var entry:d.source().entrySet()){var v=prior.body.path("_source").path(entry.getKey());if(entry.getValue() instanceof Number number){if(!v.isIntegralNumber()||v.asLong()!=number.longValue())throw new Conflict();}else if(!v.isString()||!v.asString().equals(entry.getValue()))throw new Conflict();}
    }
    JsonNode searchPublic(UUID space,String q,int offset,int limit,String kind){
        if(q==null||q.isBlank()||q.codePointCount(0,q.length())>128||q.codePoints().anyMatch(Character::isISOControl)||offset<0||limit<1||limit>50||offset+limit>1000||kind!=null&&!PublicProjection.KINDS.contains(kind))throw new IllegalArgumentException("Invalid public search");
        var filters=new java.util.ArrayList<Object>();filters.add(java.util.Map.of("term",java.util.Map.of("spaceId",space.toString())));filters.add(java.util.Map.of("term",java.util.Map.of("visibility","PUBLIC")));filters.add(java.util.Map.of("term",java.util.Map.of("state","ACTIVE")));if(kind!=null)filters.add(java.util.Map.of("term",java.util.Map.of("kind",kind)));
        var query=java.util.Map.of("from",offset,"size",limit,"track_total_hits",true,"sort",java.util.List.of(java.util.Map.of("_score","desc"),java.util.Map.of("entityId","asc")),"query",java.util.Map.of("bool",java.util.Map.of("filter",filters,"must",java.util.List.of(java.util.Map.of("multi_match",java.util.Map.of("query",q,"fields",java.util.List.of("title^3","text"),"operator","and"))))));
        var r=send("POST","/"+PublicProjection.INDEX+"/_search",query);if(r.status!=200||r.body==null||!r.body.path("timed_out").isBoolean()||r.body.path("timed_out").asBoolean()||!r.body.path("_shards").path("failed").isIntegralNumber()||r.body.path("_shards").path("failed").asInt()!=0)throw new Unavailable();return r.body.path("hits");
    }
    void resetPublic(String mapping){var deleted=send("DELETE","/"+PublicProjection.INDEX,null);if(deleted.status!=200&&deleted.status!=404)throw new Unavailable();var body=json.readTree(mapping);var created=send("PUT","/"+PublicProjection.INDEX,body);if(created.status!=200)throw new Unavailable();}
    void pingPublic(){var r=send("GET","/"+PublicProjection.INDEX+"/_doc/019a0000-0000-7000-8000-ffffffffffff",null);if(r.status!=200&&r.status!=404||r.body!=null&&r.body.has("error"))throw new Unavailable();}

}
