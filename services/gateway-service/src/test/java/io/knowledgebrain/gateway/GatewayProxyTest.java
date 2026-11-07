package io.knowledgebrain.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class GatewayProxyTest {
    HttpServer server;GatewayController gateway;AtomicInteger requests;AtomicReference<com.sun.net.httpserver.Headers> headers;
    @BeforeEach void prepare() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);requests=new AtomicInteger();headers=new AtomicReference<>();
        server.createContext("/",exchange->{
            requests.incrementAndGet();headers.set(exchange.getRequestHeaders());
            String behavior=exchange.getRequestHeaders().getFirst("Cookie");
            if("login-redirect".equals(behavior)||"login-evil".equals(behavior)){exchange.getResponseHeaders().add("Location","login-redirect".equals(behavior)?"/graph":"https://evil.example");exchange.sendResponseHeaders(303,-1);exchange.close();return;}
            if("redirect".equals(behavior)) { exchange.getResponseHeaders().add("Location","/internal/forbidden");exchange.sendResponseHeaders(302,-1);exchange.close();return; }
            byte[] body="graph-large".equals(behavior)?new byte[70000]:"graph-oversize".equals(behavior)?new byte[1048577]:"oversize".equals(behavior)?new byte[65537]:"{\"kind\":\"GUEST\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Set-Cookie","__Host-brain_guest=sample; Path=/; Secure; HttpOnly; SameSite=Lax");
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.getResponseHeaders().add("X-Internal-Secret","must-not-forward");
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        URI origin=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        var transport=new GatewayTransport(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),origin,origin);
        gateway=new GatewayController(transport,new SimpleMeterRegistry(),"https://localhost:8443");
    }
    @Test void learningGuidanceUsesOnlyTheExactPrivateGetWithoutSpoofedActorHeaders() throws Exception {
        String id="019a0808-1234-7000-8000-000000000001",path="/api/spaces/"+id+"/roadmaps/"+id+"/learning-guidance";
        assertThat(GatewayRoute.select("GET",path)).isEqualTo(new GatewayRoute("learning",path));
        var read=request("GET",path);read.addHeader("X-Brain-Actor","spoofed");read.addHeader("Authorization","Bearer spoofed");
        var result=gateway.proxy(read);assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getHeaders().getCacheControl()).contains("no-store");
        assertThat(headers.get().getFirst("X-Brain-Actor")).isNull();assertThat(headers.get().getFirst("Authorization")).isNull();
        for(String method:new String[]{"POST","PUT","DELETE","PATCH","HEAD","OPTIONS"})assertThat(GatewayRoute.select(method,path)).isNull();
        for(String unavailable:new String[]{path+"/",path+"/revisions",path.replace("/api/spaces/","/api/public/spaces/"),path.replace("/api/spaces/","/api/groups/"),path.replace(id,id.toUpperCase()),path.replace(id,"019a0808-1234-4000-8000-000000000001")})assertThat(GatewayRoute.select("GET",unavailable)).isNull();
        assertThat(requests.get()).isEqualTo(1);
    }
    @Test void learningGuidanceRejectsQueriesBodiesAndWritesBeforeAnyUpstreamRequest() throws Exception {
        String id="019a0808-1234-7000-8000-000000000001",path="/api/spaces/"+id+"/roadmaps/"+id+"/learning-guidance";
        for(String query:new String[]{"","limit=20","actorId="+id,"roadmapVersion=1","before=1","algorithm=GRAPH_PROGRESS_V1","x=%00"}){var read=request("GET",path);read.setQueryString(query);assertThatThrownBy(()->gateway.proxy(read)).isInstanceOf(ResponseStatusException.class);}
        for(String content:new String[]{"{}"," "}){var read=request("GET",path);read.setContent(content.getBytes(StandardCharsets.UTF_8));assertThatThrownBy(()->gateway.proxy(read)).isInstanceOf(ResponseStatusException.class);}
        for(String method:new String[]{"POST","PUT","DELETE","PATCH"}){var write=request(method,path);write.addHeader("Origin","https://localhost:8443");assertThatThrownBy(()->gateway.proxy(write)).isInstanceOf(ResponseStatusException.class);}
        assertThat(requests.get()).isZero();
    }
    @Test void activityQueryAndReadBodiesAreStrictBeforeProxying() throws Exception {
        String path="/api/spaces/019a0808-1234-7000-8000-000000000001/activity/timeline";
        for(String query:new String[]{"kind=ALL&kind=ROADMAP","unknown=x","limit=1&","timezone=UTC=evil","before=","spaceId=spoof"}){var r=request("GET",path);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        var body=request("GET",path);body.setContent("{}".getBytes());assertThatThrownBy(()->gateway.proxy(body)).isInstanceOf(ResponseStatusException.class);assertThat(requests.get()).isZero();
        var valid=request("GET",path);valid.setQueryString("timezone=America%2FNew_York&kind=STUDY_SESSION&limit=20&from=2026-10-01");assertThat(gateway.proxy(valid).getStatusCode().value()).isEqualTo(200);assertThat(requests.get()).isEqualTo(1);
    }
    @Test void goalQueriesKeysAndGetBodiesStayStrictBeforeProxying() throws Exception {
        String root="/api/spaces/019a0808-1234-7000-8000-000000000001/goals";
        for(String query:new String[]{"status=ALL&status=COMPLETED","unknown=x","limit=1&","before=1","after="}){var r=request("GET",root);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        var missing=request("POST",root);missing.setContent("{}".getBytes());assertThatThrownBy(()->gateway.proxy(missing)).isInstanceOf(ResponseStatusException.class);var get=request("GET",root);get.setContent("{}".getBytes());assertThatThrownBy(()->gateway.proxy(get)).isInstanceOf(ResponseStatusException.class);assertThat(requests.get()).isZero();var valid=request("POST",root);valid.addHeader("Idempotency-Key","a".repeat(32));valid.addHeader("Origin","https://localhost:8443");valid.setContentType("application/json");valid.setContent(("{\"description\":\""+"x".repeat(2000)+"\"}").getBytes());assertThat(gateway.proxy(valid).getStatusCode().value()).isEqualTo(200);assertThat(headers.get().getFirst("Idempotency-Key")).isEqualTo("a".repeat(32));
    }
    @AfterEach void stop() { server.stop(0); }
    MockHttpServletRequest request(String method,String path) {
        var request=new MockHttpServletRequest(method,path);request.setSecure(true);return request;
    }
    @Test void preservesCookieStatusAndTraceWhileRemovingUntrustedAndInternalHeaders() throws Exception {
        var request=request("POST","/api/identity/guest-session");request.setContentType("application/json");request.setContent("{}".getBytes());request.addHeader("Origin","https://localhost:8443");
        request.addHeader("X-Principal-Id","attacker");request.addHeader("Forwarded","proto=https");request.addHeader("Authorization","Bearer attacker");
        request.addHeader("Cookie","__Host-brain_guest=sample");String trace="0123456789abcdef0123456789abcdef";
        request.addHeader("traceparent","00-"+trace+"-0123456789abcdef-01");
        var response=gateway.proxy(request);assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Set-Cookie")).contains("Secure","HttpOnly");assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo(trace);assertThat(response.getHeaders().getFirst("X-Internal-Secret")).isNull();
        assertThat(headers.get().getFirst("Cookie")).isEqualTo("__Host-brain_guest=sample");
        for(String name:new String[]{"X-Principal-Id","Forwarded","Authorization"})assertThat(headers.get().getFirst(name)).isNull();
    }
    @Test void rejectsHttpsOriginBodyAndQueryViolationsBeforeSending() throws Exception {
        var insecure=request("GET","/api/spaces/personal");insecure.setSecure(false);insecure.addHeader("X-Forwarded-Proto","https");assertThatThrownBy(()->gateway.proxy(insecure)).isInstanceOf(ResponseStatusException.class);
        var query=request("GET","/api/spaces/personal");query.setQueryString("url=https://evil.example");assertThatThrownBy(()->gateway.proxy(query)).isInstanceOf(ResponseStatusException.class);
        var wrong=request("POST","/api/identity/guest-session");wrong.setContentType("application/json");wrong.addHeader("Origin","https://evil.example");assertThatThrownBy(()->gateway.proxy(wrong)).isInstanceOf(ResponseStatusException.class);
        var large=request("POST","/api/identity/guest-session");large.setContentType("application/json");large.addHeader("Origin","https://localhost:8443");large.setContent(new byte[1025]);
        assertThatThrownBy(()->gateway.proxy(large)).isInstanceOf(ResponseStatusException.class);assertThat(requests.get()).isZero();
    }
    @Test void rejectsRedirectAndOversizedResponseWithoutFollowingInternalUrl() {
        for(String cookie:new String[]{"redirect","oversize"}) {
            var request=request("GET","/api/identity/guest-session");request.addHeader("Cookie",cookie);
            assertThatThrownBy(()->gateway.proxy(request)).isInstanceOf(ResponseStatusException.class).satisfies(error->assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(502));
        }
        assertThat(requests.get()).isEqualTo(2);
    }
    @Test void unavailableUpstreamFailsClosedWithGeneric502() {
        server.stop(0);
        assertThatThrownBy(()->gateway.proxy(request("GET","/api/spaces/personal"))).isInstanceOf(ResponseStatusException.class)
            .satisfies(error->assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(502));
    }
    @Test void completeResponseDeadlineIncludesAStalledBody() {
        server.createContext("/api/spaces/personal",exchange->{
            exchange.sendResponseHeaders(200,2);exchange.getResponseBody().flush();
            try { Thread.sleep(500);exchange.getResponseBody().write("{}".getBytes()); } catch(InterruptedException error) { Thread.currentThread().interrupt(); } finally { exchange.close(); }
        });
        URI origin=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        var transport=new GatewayTransport(HttpClient.newBuilder().build(),origin,origin,java.time.Duration.ofMillis(100));
        var limited=new GatewayController(transport,new SimpleMeterRegistry(),"https://localhost:8443");
        assertThatThrownBy(()->limited.proxy(request("GET","/api/spaces/personal"))).isInstanceOf(ResponseStatusException.class)
            .satisfies(error->assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(504));
    }
    @Test void noteEditsForwardBodyEtagAndSafePaginationButKeepGuestLimits() throws Exception {
        String space="0199b30d-0000-7000-8000-000000000001",note="0199b30d-0000-7000-8000-000000000002";
        var request=request("PUT","/api/spaces/"+space+"/notes/"+note);request.addHeader("Origin","https://localhost:8443");request.setContentType("application/json");request.addHeader("If-Match","\"1\"");request.setContent("body".repeat(300).getBytes());
        gateway.proxy(request);assertThat(headers.get().getFirst("If-Match")).isEqualTo("\"1\"");
        var list=request("GET","/api/spaces/"+space+"/notes");list.setQueryString("limit=20&after="+note);assertThat(gateway.proxy(list).getStatusCode().value()).isEqualTo(200);
        list.setQueryString("limit=20&limit=1");assertThatThrownBy(()->gateway.proxy(list)).isInstanceOf(ResponseStatusException.class);list.setQueryString("url=https://evil.example");assertThatThrownBy(()->gateway.proxy(list)).isInstanceOf(ResponseStatusException.class);
        assertThat(GatewayRoute.select("POST","/api/spaces/"+space+"/notes/"+note+"/revisions")).isNull();assertThat(GatewayRoute.select("PUT","/api/spaces/"+space+"/notes")).isNull();
    }
    @Test void conceptNoteReadsRouteToContentAndRejectWritesAndUnsafeCursors() throws Exception {
        String space="0199b30d-0000-7000-8000-000000000001",concept="0199b30d-0000-7000-8000-000000000002";
        String path="/api/spaces/"+space+"/concept-references/"+concept+"/notes";
        assertThat(GatewayRoute.select("GET",path).service()).isEqualTo("content");
        var page=request("GET",path);page.setQueryString("limit=20&after="+concept);page.addHeader("Cookie","__Host-brain_guest=sample");
        var response=gateway.proxy(page);assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(headers.get().getFirst("Cookie")).isEqualTo("__Host-brain_guest=sample");
        for(String query:new String[]{"limit=20&limit=1","after="+concept+"&after="+concept,"before=1","title=private","limit=20&url=https://evil.example","after=0199b30d-0000-4000-8000-000000000002"}) {
            page.setQueryString(query);assertThatThrownBy(()->gateway.proxy(page)).isInstanceOf(ResponseStatusException.class);
        }
        for(String method:new String[]{"POST","PUT","DELETE","PATCH"})assertThat(GatewayRoute.select(method,path)).isNull();
        assertThat(GatewayRoute.select("GET",path+"/revisions")).isNull();assertThat(requests.get()).isEqualTo(1);
    }
    @Test void aliasRoutesPreserveIndependentEtagAndOnlyExactBoundedRoutes() throws Exception {
        String id="0199b30d-0000-7000-8000-000000000001",base="/api/spaces/"+id+"/concepts/"+id+"/aliases",item=base+"/"+id;
        assertThat(GatewayRoute.select("POST",base).service()).isEqualTo("knowledge");
        var create=request("POST",base);create.addHeader("Origin","https://localhost:8443");create.setContentType("application/json");create.setContent("{\"label\":\"별칭\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(gateway.proxy(create).getStatusCode().value()).isEqualTo(200);
        var update=request("PUT",item);update.addHeader("Origin","https://localhost:8443");update.setContentType("application/json");update.addHeader("If-Match","\"a2\"");update.setContent("{\"label\":\"수정\"}".getBytes(StandardCharsets.UTF_8));gateway.proxy(update);
        assertThat(headers.get().getFirst("If-Match")).isEqualTo("\"a2\"");
        var list=request("GET",base);list.setQueryString("limit=100&after="+id);gateway.proxy(list);
        var history=request("GET",item+"/revisions");history.setQueryString("before=9223372036854775807&limit=10");gateway.proxy(history);
        for(String query:new String[]{"limit=101","limit=01","limit=1&limit=2","before=2","after=0199b30d-0000-4000-8000-000000000001","scope=GLOBAL"}){list.setQueryString(query);assertThatThrownBy(()->gateway.proxy(list)).isInstanceOf(ResponseStatusException.class);}
        for(String query:new String[]{"limit=11","before=9223372036854775808","after="+id}){history.setQueryString(query);assertThatThrownBy(()->gateway.proxy(history)).isInstanceOf(ResponseStatusException.class);}
        create.setContent(new byte[4097]);assertThatThrownBy(()->gateway.proxy(create)).isInstanceOf(ResponseStatusException.class);
        for(String path:new String[]{base+"/extra",item+"/extra","/api/public/spaces/"+id+"/concepts/"+id+"/aliases"})assertThat(GatewayRoute.select("GET",path)).isNull();
        assertThat(GatewayRoute.select("PATCH",item)).isNull();assertThat(GatewayRoute.select("POST",item+"/revisions")).isNull();assertThat(GatewayRoute.select("PUT",base)).isNull();assertThat(requests.get()).isEqualTo(4);
    }
    @Test void retiredSourceFilterDoesNotChangeCitationHistoryItemOrWriteRoutes() throws Exception {
        String id="0199b30d-0000-7000-8000-000000000001",base="/api/spaces/"+id+"/sources";
        for(String state:new String[]{"ACTIVE","RETIRED"}){var r=request("GET",base);r.setQueryString("state="+state+"&limit=20&after="+id);assertThat(gateway.proxy(r).getStatusCode().value()).isEqualTo(200);}
        for(String query:new String[]{"state=ALL","state=retired","state=","state=RETIRED&state=ACTIVE","state=%52ETIRED","state=RETIRED&limit=101"}){var r=request("GET",base);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String path:new String[]{base+"/"+id,base+"/"+id+"/revisions",base+"/"+id+"/citations"}){var r=request("GET",path);r.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String method:new String[]{"POST","PUT","DELETE"}){var r=request(method,method.equals("POST")?base:base+"/"+id);r.addHeader("Origin","https://localhost:8443");r.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        assertThat(GatewayRoute.select("GET","/api/public/spaces/"+id+"/sources")).isNull();assertThat(requests.get()).isEqualTo(2);
    }
    @Test void retiredConceptFilterIsConfinedToPrivateCollectionGet() throws Exception {
        String id="0199b30d-0000-7000-8000-000000000001",base="/api/spaces/"+id+"/concepts";
        for(String state:new String[]{"ACTIVE","RETIRED"}){var r=request("GET",base);r.setQueryString("state="+state+"&limit=20&after="+id);assertThat(gateway.proxy(r).getStatusCode().value()).isEqualTo(200);}
        for(String query:new String[]{"state=ALL","state=retired","state=","state=RETIRED&state=ACTIVE","state=%52ETIRED"}){var r=request("GET",base);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String path:new String[]{base+"/"+id,base+"/"+id+"/revisions","/api/public/spaces/"+id+"/concepts"}){var r=request("GET",path);r.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String method:new String[]{"POST","PUT","DELETE"}){var r=request(method,method.equals("POST")?base:base+"/"+id);r.addHeader("Origin","https://localhost:8443");r.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        assertThat(requests.get()).isEqualTo(2);
    }
    @Test void retiredStateIsAllowedOnlyOnExactClaimCollectionReads() throws Exception {
        String id="0199b30d-0000-7000-8000-000000000001",root="/api/spaces/"+id+"/concepts/"+id,claims=root+"/claims";
        for(String state:new String[]{"ACTIVE","RETIRED"}){var r=request("GET",claims);r.setQueryString("state="+state+"&limit=20&after="+id);assertThat(gateway.proxy(r).getStatusCode().value()).isEqualTo(200);}
        for(String query:new String[]{"state=ALL","state=retired","state=","state=RETIRED&state=ACTIVE","state=%52ETIRED"}){var r=request("GET",claims);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String path:new String[]{claims+"/"+id,claims+"/"+id+"/revisions"}){var r=request("GET",path);r.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        var write=request("POST",claims);write.addHeader("Origin","https://localhost:8443");write.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(write)).isInstanceOf(ResponseStatusException.class);
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test void retiredCitationsRequireExactNestedClaimCollectionRead() throws Exception {
        String id="0199b30d-0000-7000-8000-000000000001",base="/api/spaces/"+id+"/concepts/"+id+"/claims/"+id+"/citations";
        for(String state:new String[]{"ACTIVE","RETIRED"}){var r=request("GET",base);r.setQueryString("state="+state+"&limit=20&after="+id);assertThat(gateway.proxy(r).getStatusCode().value()).isEqualTo(200);}
        for(String query:new String[]{"state=ALL","state=retired","state=","state=RETIRED&state=ACTIVE","state=%52ETIRED"}){var r=request("GET",base);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String path:new String[]{base+"/"+id,base+"/"+id+"/revisions","/api/spaces/"+id+"/sources/"+id+"/citations"}){var r=request("GET",path);r.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String method:new String[]{"POST","PUT","DELETE"}){var r=request(method,method.equals("POST")?base:base+"/"+id);r.addHeader("Origin","https://localhost:8443");r.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        assertThat(requests.get()).isEqualTo(2);
    }
    @Test void retiredQualifiersRequireExactNestedClaimCollectionRead() throws Exception {
        String id="0199b30d-0000-7000-8000-000000000001",base="/api/spaces/"+id+"/concepts/"+id+"/claims/"+id+"/qualifiers";
        for(String state:new String[]{"ACTIVE","RETIRED"}){var r=request("GET",base);r.setQueryString("state="+state+"&limit=20&after="+id);assertThat(gateway.proxy(r).getStatusCode().value()).isEqualTo(200);}
        for(String query:new String[]{"state=ALL","state=retired","state=","state=RETIRED&state=ACTIVE","state=%52ETIRED"}){var r=request("GET",base);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String path:new String[]{base+"/"+id,base+"/"+id+"/revisions","/api/spaces/"+id+"/sources/"+id+"/citations"}){var r=request("GET",path);r.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String method:new String[]{"POST","PUT","DELETE"}){var r=request(method,method.equals("POST")?base:base+"/"+id);r.addHeader("Origin","https://localhost:8443");r.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        assertThat(requests.get()).isEqualTo(2);
    }
    @Test void claimRoutesPreserveTheirEtagAndRejectUnsafeQueriesBodiesAndPublicRoutes() throws Exception {
        String id="0199b30d-0000-7000-8000-000000000001",base="/api/spaces/"+id+"/concepts/"+id+"/claims",item=base+"/"+id;
        assertThat(GatewayRoute.select("POST",base).service()).isEqualTo("knowledge");
        var update=request("PUT",item);update.addHeader("Origin","https://localhost:8443");update.setContentType("application/json");update.addHeader("If-Match","\"cl2\"");update.setContent(("{\"predicateConceptId\":\""+id+"\",\"object\":{\"type\":\"CONCEPT\",\"conceptId\":\""+id+"\"}}").getBytes(StandardCharsets.UTF_8));
        gateway.proxy(update);assertThat(headers.get().getFirst("If-Match")).isEqualTo("\"cl2\"");
        var list=request("GET",base);list.setQueryString("limit=100&after="+id);gateway.proxy(list);
        var history=request("GET",item+"/revisions");history.setQueryString("before=9223372036854775807&limit=10");gateway.proxy(history);
        for(String query:new String[]{"limit=101","limit=01","limit=1&limit=2","before=2","after=0199b30d-0000-4000-8000-000000000001","scope=GLOBAL"}){list.setQueryString(query);assertThatThrownBy(()->gateway.proxy(list)).isInstanceOf(ResponseStatusException.class);}
        for(String query:new String[]{"limit=11","before=9223372036854775808","after="+id}){history.setQueryString(query);assertThatThrownBy(()->gateway.proxy(history)).isInstanceOf(ResponseStatusException.class);}
        update.setContent(new byte[16385]);assertThatThrownBy(()->gateway.proxy(update)).isInstanceOf(ResponseStatusException.class);
        var read=request("GET",item);read.setQueryString("limit=1");assertThatThrownBy(()->gateway.proxy(read)).isInstanceOf(ResponseStatusException.class);
        var spaceClaim=request("GET","/api/spaces/claims/"+id);spaceClaim.setContent(new byte[1025]);
        assertThatThrownBy(()->gateway.proxy(spaceClaim)).isInstanceOf(ResponseStatusException.class).satisfies(error->assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(413));
        for(String path:new String[]{base+"/extra",item+"/extra","/api/public/spaces/"+id+"/concepts/"+id+"/claims"})assertThat(GatewayRoute.select("GET",path)).isNull();
        assertThat(GatewayRoute.select("PATCH",item)).isNull();assertThat(GatewayRoute.select("POST",item+"/revisions")).isNull();assertThat(GatewayRoute.select("PUT",base)).isNull();assertThat(requests.get()).isEqualTo(3);
    }
    @Test void sourceRoutesKeepSourceBodiesAndPaginationIndependentFromClaims() throws Exception {
        String id="0199b30d-0000-7000-8000-000000000001",base="/api/spaces/"+id+"/sources",item=base+"/"+id;
        assertThat(GatewayRoute.select("POST",base).service()).isEqualTo("knowledge");
        var update=request("PUT",item);update.addHeader("Origin","https://localhost:8443");update.setContentType("application/json");update.addHeader("If-Match","\"src2\"");update.setContent(new byte[32768]);gateway.proxy(update);
        assertThat(headers.get().getFirst("If-Match")).isEqualTo("\"src2\"");
        var list=request("GET",base);list.setQueryString("limit=100&after="+id);gateway.proxy(list);
        var history=request("GET",item+"/revisions");history.setQueryString("before=9223372036854775807&limit=10");gateway.proxy(history);
        for(String query:new String[]{"limit=101","limit=01","limit=1&limit=2","before=2","after=0199b30d-0000-4000-8000-000000000001","scope=GLOBAL"}){list.setQueryString(query);assertThatThrownBy(()->gateway.proxy(list)).isInstanceOf(ResponseStatusException.class);}
        for(String query:new String[]{"limit=11","before=9223372036854775808","after="+id}){history.setQueryString(query);assertThatThrownBy(()->gateway.proxy(history)).isInstanceOf(ResponseStatusException.class);}
        update.setContent(new byte[32769]);assertThatThrownBy(()->gateway.proxy(update)).isInstanceOf(ResponseStatusException.class);
        var read=request("GET",item);read.setQueryString("limit=1");assertThatThrownBy(()->gateway.proxy(read)).isInstanceOf(ResponseStatusException.class);
        for(String path:new String[]{base+"/extra",item+"/extra","/api/public/spaces/"+id+"/sources"})assertThat(GatewayRoute.select("GET",path)).isNull();
        assertThat(GatewayRoute.select("PATCH",item)).isNull();assertThat(GatewayRoute.select("POST",item+"/revisions")).isNull();assertThat(GatewayRoute.select("PUT",base)).isNull();assertThat(requests.get()).isEqualTo(3);
    }
    @Test void citationRoutesKeepPinnedVersionsAndTheirOwnEtagWithinExactClaimScope() throws Exception {
        String id="0199b30d-0000-7000-8000-000000000001",base="/api/spaces/"+id+"/concepts/"+id+"/claims/"+id+"/citations",item=base+"/"+id;
        assertThat(GatewayRoute.select("POST",base).service()).isEqualTo("knowledge");
        var update=request("PUT",item);update.addHeader("Origin","https://localhost:8443");update.setContentType("application/json");update.addHeader("If-Match","\"cite2\"");update.setContent(("{\"sourceId\":\""+id+"\",\"sourceVersion\":3,\"claimVersion\":4}").getBytes(StandardCharsets.UTF_8));gateway.proxy(update);
        assertThat(headers.get().getFirst("If-Match")).isEqualTo("\"cite2\"");
        var list=request("GET",base);list.setQueryString("limit=100&after="+id);gateway.proxy(list);
        var history=request("GET",item+"/revisions");history.setQueryString("before=9223372036854775807&limit=10");gateway.proxy(history);
        for(String query:new String[]{"limit=101","limit=01","limit=1&limit=2","before=2","after=0199b30d-0000-4000-8000-000000000001","sourceId="+id}){list.setQueryString(query);assertThatThrownBy(()->gateway.proxy(list)).isInstanceOf(ResponseStatusException.class);}
        for(String query:new String[]{"limit=11","before=9223372036854775808","after="+id}){history.setQueryString(query);assertThatThrownBy(()->gateway.proxy(history)).isInstanceOf(ResponseStatusException.class);}
        update.setContent(new byte[4097]);assertThatThrownBy(()->gateway.proxy(update)).isInstanceOf(ResponseStatusException.class);
        var read=request("GET",item);read.setQueryString("limit=1");assertThatThrownBy(()->gateway.proxy(read)).isInstanceOf(ResponseStatusException.class);
        for(String path:new String[]{base+"/extra",item+"/extra","/api/public/spaces/"+id+"/concepts/"+id+"/claims/"+id+"/citations"})assertThat(GatewayRoute.select("GET",path)).isNull();
        assertThat(GatewayRoute.select("DELETE",item).service()).isEqualTo("knowledge");assertThat(GatewayRoute.select("PATCH",item)).isNull();assertThat(GatewayRoute.select("POST",item+"/revisions")).isNull();assertThat(GatewayRoute.select("PUT",base)).isNull();assertThat(requests.get()).isEqualTo(3);
    }
    @Test void sourceCitationReadsAllowOnlyExactBodylessSourceScopeAndBoundedCursor() throws Exception {
        String id="0199b30d-0000-7000-8000-000000000001",path="/api/spaces/"+id+"/sources/"+id+"/citations";
        assertThat(GatewayRoute.select("GET",path).service()).isEqualTo("knowledge");
        var page=request("GET",path);page.setQueryString("limit=20&after="+id);page.addHeader("Cookie","__Host-brain_guest=sample");
        var response=gateway.proxy(page);assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(response.getHeaders().getETag()).isNull();
        assertThat(headers.get().getFirst("Cookie")).isEqualTo("__Host-brain_guest=sample");
        for(String query:new String[]{"limit=101","limit=0","limit=01","limit=1&limit=2","before=2","after=0199b30d-0000-4000-8000-000000000001","after="+id+"&after="+id,"sourceId="+id,"url=https://evil.example"}) {
            var bad=request("GET",path);bad.setQueryString(query);assertThatThrownBy(()->gateway.proxy(bad)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error->assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(400));
        }
        var body=request("GET",path);body.setContent("x".getBytes(StandardCharsets.UTF_8));assertThatThrownBy(()->gateway.proxy(body)).isInstanceOf(ResponseStatusException.class)
            .satisfies(error->assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(400));
        for(String method:new String[]{"POST","PUT","DELETE","PATCH"})assertThat(GatewayRoute.select(method,path)).isNull();
        for(String extra:new String[]{path+"/"+id,path+"/revisions",path+"/extra","/api/public/spaces/"+id+"/sources/"+id+"/citations"})assertThat(GatewayRoute.select("GET",extra)).isNull();
        assertThat(requests.get()).isEqualTo(1);
    }
    @Test void claimUsageReadsKeepExactPrivateRolesCursorsAndBodylessContract()throws Exception{
        String id="0199b30d-0000-7000-8000-000000000001",path="/api/spaces/"+id+"/concepts/"+id+"/claim-usages";
        assertThat(GatewayRoute.select("GET",path).service()).isEqualTo("knowledge");
        var read=request("GET",path);read.addHeader("Cookie","__Host-brain_guest=sample");read.setQueryString("role=PREDICATE&after="+id+"&limit=100");var response=gateway.proxy(read);assertThat(response.getStatusCode().value()).isEqualTo(200);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(response.getHeaders().getETag()).isNull();assertThat(headers.get().getFirst("Cookie")).isEqualTo("__Host-brain_guest=sample");
        for(String query:new String[]{"role=SUBJECT","role=object","role=OBJECT&role=PREDICATE","limit=101","limit=01","limit=1&limit=2","before=2","after=0199b30d-0000-4000-8000-000000000001","q=private"}){var bad=request("GET",path);bad.setQueryString(query);assertThatThrownBy(()->gateway.proxy(bad)).isInstanceOf(ResponseStatusException.class).satisfies(error->assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(400));}
        var body=request("GET",path);body.setContent(" ".getBytes());assertThatThrownBy(()->gateway.proxy(body)).isInstanceOf(ResponseStatusException.class).satisfies(error->assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(400));
        for(String method:new String[]{"POST","PUT","DELETE","PATCH"})assertThat(GatewayRoute.select(method,path)).isNull();for(String extra:new String[]{path+"/"+id,path+"/revisions",path.replace("/api/spaces/","/api/public/spaces/")})assertThat(GatewayRoute.select("GET",extra)).isNull();assertThat(requests.get()).isEqualTo(1);
    }
    @Test void wikiReadRoutesAllowOnlyTheirBoundedCursorShape() throws Exception {
        String item="/api/spaces/0199b30d-0000-7000-8000-000000000001/notes/0199b30d-0000-7000-8000-000000000002";
        var links=request("GET",item+"/links");links.setQueryString("after=0&limit=20");assertThat(gateway.proxy(links).getStatusCode().value()).isEqualTo(200);
        links.setQueryString("after=other-space");assertThatThrownBy(()->gateway.proxy(links)).isInstanceOf(ResponseStatusException.class);
        var backlinks=request("GET",item+"/backlinks");backlinks.setQueryString("after=0199b30d-0000-7000-8000-000000000003");assertThat(gateway.proxy(backlinks).getStatusCode().value()).isEqualTo(200);backlinks.setQueryString("after=0");assertThatThrownBy(()->gateway.proxy(backlinks)).isInstanceOf(ResponseStatusException.class);
        assertThat(GatewayRoute.select("POST",item+"/links")).isNull();assertThat(GatewayRoute.select("PUT",item+"/backlinks")).isNull();
    }
    @Test void onlyDeclaredMethodsAndPathsCanRouteAndOriginsMustBeFixedHttps() {
        for(String path:new String[]{"/internal/identity/guest-introspection","/api/identity/guest-session/extra","//api/spaces/personal","/api/spaces/../internal","/actuator/env"})assertThat(GatewayRoute.select("GET",path)).isNull();
        assertThat(GatewayRoute.select("DELETE","/api/identity/guest-session")).isNull();
        for(String origin:new String[]{"http://localhost","https://user@localhost","https://localhost/path","https://localhost?url=other","https://localhost#fragment"})assertThatThrownBy(()->GatewayConfiguration.origin(origin)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void dailyRoutesValidateEncodedTimezoneAndDateCursorsBeforeForwarding() throws Exception {
        String base="/api/spaces/018f1234-5678-7abc-8def-0123456789ab/daily-notes";
        var today=request("GET",base+"/today");today.setQueryString("timezone=Asia%2FSeoul");assertThat(gateway.proxy(today).getStatusCode().value()).isEqualTo(200);
        var journal=request("GET",base);journal.setQueryString("from=2026-10-01&to=2026-10-07&before=2026-10-06&limit=20");assertThat(gateway.proxy(journal).getStatusCode().value()).isEqualTo(200);
        for(String query:new String[]{"timezone=Asia%2FSeoul&timezone=UTC","timezone=%ZZ","url=https://evil.example","timezone=Asia%2FSeoul%26evil%3D1"}){var bad=request("GET",base+"/today");bad.setQueryString(query);assertThatThrownBy(()->gateway.proxy(bad)).isInstanceOf(ResponseStatusException.class);}
        for(String method:new String[]{"PUT","DELETE","POST"})assertThat(GatewayRoute.select(method,base+"/2026-10-07")).isNull();assertThat(requests.get()).isEqualTo(2);
    }
    @Test void searchRoutesAcceptPlainEncodedUnicodeAndRejectDslDuplicateAndUnsafeQueries() throws Exception {
        String path="/api/spaces/01900000-0000-7000-8000-000000000001/search/notes";
        var req=request("GET",path);req.setQueryString("q=%EA%B3%B5%EB%B6%80+Kafka&limit=1&offset=0");assertThat(gateway.proxy(req).getStatusCode().value()).isEqualTo(200);
        assertThat(GatewayRoute.select("GET",path).service()).isEqualTo("brain-query");assertThat(GatewayRoute.select("POST",path)).isNull();
        for(String q:new String[]{"q=a&q=b","q=a&index=other","q=%ZZ","q=%0A","q=a&limit=51","q=a&offset=999&limit=20","q=a&dsl={}"}) {req.setQueryString(q);assertThatThrownBy(()->gateway.proxy(req)).isInstanceOf(ResponseStatusException.class);}
        assertThat(requests.get()).isEqualTo(1);
    }
 @Test void graphOnlyReadRouteLimitsQueryAndPermitsBoundedLargeGraphWithoutBroadeningOtherLimits()throws Exception{
  String path="/api/spaces/01900000-0000-7000-8000-000000000001/graph/notes";
  var r=request("GET",path);r.setQueryString("limit=200");r.addHeader("Cookie","graph-large");assertThat(gateway.proxy(r).getBody()).hasSize(70000);
  for(String q:new String[]{"limit=0","limit=201","limit=01","limit=1&limit=2","index=foreign","q=text"}){var invalid=request("GET",path);invalid.setQueryString(q);assertThatThrownBy(()->gateway.proxy(invalid)).isInstanceOf(ResponseStatusException.class);}
  assertThat(GatewayRoute.select("POST",path)).isNull();assertThat(GatewayRoute.select("GET",path+"/extra")).isNull();
  var huge=request("GET",path);huge.addHeader("Cookie","graph-oversize");assertThatThrownBy(()->gateway.proxy(huge)).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(502));
  var other=request("GET","/api/identity/guest-session");other.addHeader("Cookie","graph-large");assertThatThrownBy(()->gateway.proxy(other)).isInstanceOf(ResponseStatusException.class);
 }

    @Test void conceptRoutesAllowBoundedPaginationAndDescriptionBodiesButRejectUnknownQueries() throws Exception {
        String base="/api/spaces/0199b30d-0000-7000-8000-000000000001/concepts";
        var list=request("GET",base);list.setQueryString("limit=100");assertThat(gateway.proxy(list).getStatusCode().value()).isEqualTo(200);
        for(String query:new String[]{"limit=10&limit=20","q=secret","url=evil"}){var bad=request("GET",base);bad.setQueryString(query);assertThatThrownBy(()->gateway.proxy(bad)).isInstanceOf(ResponseStatusException.class);}
        var create=request("POST",base);create.setContentType("application/json");create.addHeader("Origin","https://localhost:8443");create.setContent(new byte[32768]);assertThat(gateway.proxy(create).getStatusCode().value()).isEqualTo(200);
        assertThat(GatewayRoute.select("POST",base+"/0199b30d-0000-7000-8000-000000000002/revisions")).isNull();
    }
    @Test void callbackAllowsOnlyBoundedOidcParametersAndExactGraphRedirect() throws Exception {
        var callback=request("GET","/api/identity/login/callback");callback.setQueryString("state=abc&code=abc.def&iss=https%3A%2F%2Flocalhost%3A8445%2Frealms%2Fbrain-users");callback.addHeader("Cookie","login-redirect");assertThat(gateway.proxy(callback).getStatusCode().value()).isEqualTo(303);
        for(String query:new String[]{"state=a&state=b&code=c","state=a&code=b&redirect_uri=https://evil.example","code=a","state=a&code=%00"}){var invalid=request("GET","/api/identity/login/callback");invalid.setQueryString(query);if(query.endsWith("%00")){invalid.addHeader("Cookie","login-evil");}assertThatThrownBy(()->gateway.proxy(invalid)).isInstanceOf(ResponseStatusException.class);}
        var evil=request("GET","/api/identity/login/callback");evil.setQueryString("state=a&code=b");evil.addHeader("Cookie","login-evil");assertThatThrownBy(()->gateway.proxy(evil)).isInstanceOf(ResponseStatusException.class);
        assertThat(GatewayRoute.select("GET","/api/identity/login")).isNull();assertThat(GatewayRoute.select("POST","/api/spaces/personal/all")).isNull();
    }


    @Test void roadmapRoutesAreExactAndReadQueriesAndBodiesAreBounded() throws Exception {
        String id="0199b30d-0000-7000-8000-000000000001",path="/api/spaces/"+id+"/roadmaps";
        assertThat(GatewayRoute.select("GET",path).service()).isEqualTo("learning");
        assertThat(GatewayRoute.select("PUT",path+"/"+id+"/graph").service()).isEqualTo("learning");
        for(String method:new String[]{"POST","DELETE","GET"})assertThat(GatewayRoute.select(method,path+"/"+id+"/graph")).isNull();
        assertThat(GatewayRoute.select("GET","/internal/space/learning-authorization")).isNull();assertThat(GatewayRoute.select("POST","/api/public/spaces/"+id+"/roadmaps")).isNull();
        var list=request("GET",path);list.setQueryString("limit=20&after="+id);gateway.proxy(list);
        var history=request("GET",path+"/"+id+"/revisions");history.setQueryString("before=8&limit=5");gateway.proxy(history);
        int before=requests.get();for(String query:new String[]{"limit=101","limit=20&limit=1","after=not-id","url=https://evil.example"}){list.setQueryString(query);assertThatThrownBy(()->gateway.proxy(list)).isInstanceOf(ResponseStatusException.class);}
        list.setQueryString(null);list.setContent("{}".getBytes());assertThatThrownBy(()->gateway.proxy(list)).isInstanceOf(ResponseStatusException.class);
        history.setQueryString("limit=11");assertThatThrownBy(()->gateway.proxy(history)).isInstanceOf(ResponseStatusException.class);assertThat(requests.get()).isEqualTo(before);
        var update=request("PUT",path+"/"+id+"/graph");update.addHeader("Origin","https://localhost:8443");update.addHeader("If-Match","\"rm2\"");update.setContentType("application/json");update.setContent(new byte[80000]);gateway.proxy(update);assertThat(headers.get().getFirst("If-Match")).isEqualTo("\"rm2\"");
        update.setContent(new byte[1048577]);assertThatThrownBy(()->gateway.proxy(update)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void learningRecordsHaveExactRoutesPaginationAndBoundedIdempotencyHeader()throws Exception {
        String id="0199b30d-0000-7000-8000-000000000001",root="/api/spaces/"+id+"/roadmaps/"+id,progress=root+"/nodes/"+id+"/progress",sessions=root+"/study-sessions";
        assertThat(GatewayRoute.select("GET",root+"/learning").service()).isEqualTo("learning");assertThat(GatewayRoute.select("PUT",progress).service()).isEqualTo("learning");assertThat(GatewayRoute.select("DELETE",progress)).isNull();assertThat(GatewayRoute.select("PUT",sessions+"/"+id+"/revisions")).isNull();
        var list=request("GET",sessions);list.setQueryString("after="+id+"&limit=20");gateway.proxy(list);list.setQueryString("nodeId="+id);assertThatThrownBy(()->gateway.proxy(list)).isInstanceOf(ResponseStatusException.class);
        var post=request("POST",sessions);post.addHeader("Origin","https://localhost:8443");post.setContentType("application/json");post.setContent("{}".getBytes());assertThatThrownBy(()->gateway.proxy(post)).isInstanceOf(ResponseStatusException.class);post.addHeader("Idempotency-Key","a".repeat(32));gateway.proxy(post);assertThat(headers.get().getFirst("Idempotency-Key")).isEqualTo("a".repeat(32));post.addHeader("Idempotency-Key","b".repeat(32));assertThatThrownBy(()->gateway.proxy(post)).isInstanceOf(ResponseStatusException.class);
        var get=request("GET",progress);get.setContent("{}".getBytes());assertThatThrownBy(()->gateway.proxy(get)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void retiredAliasesRequireExactConceptCollectionRead() throws Exception {
        String id="0199b30d-0000-7000-8000-000000000001",base="/api/spaces/"+id+"/concepts/"+id+"/aliases";
        for(String state:new String[]{"ACTIVE","RETIRED"}){var r=request("GET",base);r.setQueryString("state="+state+"&limit=20&after="+id);assertThat(gateway.proxy(r).getStatusCode().value()).isEqualTo(200);}
        for(String query:new String[]{"state=ALL","state=retired","state=","state=RETIRED&state=ACTIVE","state=%52ETIRED"}){var r=request("GET",base);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String path:new String[]{base+"/"+id,base+"/"+id+"/revisions","/api/spaces/"+id+"/sources/"+id+"/citations"}){var r=request("GET",path);r.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String method:new String[]{"POST","PUT","DELETE"}){var r=request(method,method.equals("POST")?base:base+"/"+id);r.addHeader("Origin","https://localhost:8443");r.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        assertThat(requests.get()).isEqualTo(2);
    }
    @Test void retiredExternalMappingsRequireExactConceptCollectionRead() throws Exception {
        String id="0199b30d-0000-7000-8000-000000000001",base="/api/spaces/"+id+"/concepts/"+id+"/external-mappings";
        for(String state:new String[]{"ACTIVE","RETIRED"}){var r=request("GET",base);r.setQueryString("state="+state+"&limit=20&after="+id);assertThat(gateway.proxy(r).getStatusCode().value()).isEqualTo(200);}
        for(String query:new String[]{"state=ALL","state=retired","state=","state=RETIRED&state=ACTIVE","state=%52ETIRED"}){var r=request("GET",base);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String path:new String[]{base+"/"+id,base+"/"+id+"/revisions","/api/spaces/"+id+"/sources/"+id+"/citations"}){var r=request("GET",path);r.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String method:new String[]{"POST","PUT","DELETE"}){var r=request(method,method.equals("POST")?base:base+"/"+id);r.addHeader("Origin","https://localhost:8443");r.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test void propertyValueQueryAcceptsLiteralEncodedUnicodeAndEmptyStringsOnlyOnThePropertyRoute() throws Exception {
        String id="019a0808-1234-7000-8000-000000000001",path="/api/spaces/"+id+"/property-definitions/"+id+"/notes";
        for(String value:new String[]{"","false","80.0","1e+18"," %_ '한글+&\n ","🧠".repeat(200)}){var r=request("GET",path);r.setQueryString("limit=20&value="+java.net.URLEncoder.encode(value,StandardCharsets.UTF_8)+"&after="+id);assertThat(gateway.proxy(r).getStatusCode().value()).isEqualTo(200);}
        int sent=requests.get();for(String url:new String[]{"/api/spaces/"+id+"/property-definitions","/api/spaces/"+id+"/tags/"+id+"/notes","/api/spaces/"+id+"/notes"}){var r=request("GET",url);r.setQueryString("value=1");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}assertThat(requests.get()).isEqualTo(sent);
    }
    @Test void malformedDuplicateAndOversizeValueQueriesNeverReachTheUpstream() throws Exception {
        String id="019a0808-1234-7000-8000-000000000001",path="/api/spaces/"+id+"/property-definitions/"+id+"/notes";
        for(String query:new String[]{"value=x&value=y","value=%","value=%GG","value=%FF","value=%C0%AF","value=%00","value="+"x".repeat(201),"value="+"%F0%9F%A7%A0".repeat(201),"value=x&limit=101","value=x&after=","value=x&operator=GT","value=x=y","value=x&"}){var r=request("GET",path);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}assertThat(requests.get()).isZero();
    }
    @Test void propertyNotesAreReadOnlyWithStrictKeysetQueriesAndNoRequestBody() throws Exception {
        String id="019a0808-1234-7000-8000-000000000001",base="/api/spaces/"+id+"/property-definitions",path=base+"/"+id+"/notes";
        for(String url:new String[]{base,path}){var r=request("GET",url);r.setQueryString("limit=100&after="+id);assertThat(gateway.proxy(r).getStatusCode().value()).isEqualTo(200);}
        int sent=requests.get();
        for(String query:new String[]{"state=ACTIVE","operator=GT","type=NUMBER","limit=0","limit=101","limit=20&limit=1","after=bad","after=","after="+id+"&after="+id,"before=1","spaceId="+id}){var r=request("GET",path);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String method:new String[]{"POST","PUT","DELETE"}){var r=request(method,path);r.addHeader("Origin","https://localhost:8443");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        var body=request("GET",path);body.setContent("{}".getBytes());assertThatThrownBy(()->gateway.proxy(body)).isInstanceOf(ResponseStatusException.class);assertThat(requests.get()).isEqualTo(sent);
    }
    @Test void tagNotesAreReadOnlyAndStrictlyBoundedBeforeProxying() throws Exception {
        String id="019a0808-1234-7000-8000-000000000001",base="/api/spaces/"+id+"/tags",path=base+"/"+id+"/notes";
        for(String url:new String[]{base,path}){var r=request("GET",url);r.setQueryString("limit=20&after="+id);assertThat(gateway.proxy(r).getStatusCode().value()).isEqualTo(200);}
        int sent=requests.get();
        for(String url:new String[]{base,path})for(String query:new String[]{"state=ACTIVE","state=RETIRED","limit=0","limit=101","limit=20&limit=1","after=bad","after=","after="+id+"&after="+id,"before=1","search=study","spaceId="+id,"limit=20&"}){var r=request("GET",url);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String method:new String[]{"POST","PUT","DELETE"}){var r=request(method,path);r.addHeader("Origin","https://localhost:8443");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String url:new String[]{base+"/"+id,path+"/revisions","/api/spaces/"+id+"/property-definitions/"+id+"/notes/revisions"})assertThatThrownBy(()->gateway.proxy(request("GET",url))).isInstanceOf(ResponseStatusException.class);
        var body=request("GET",path);body.setContent("{}".getBytes());assertThatThrownBy(()->gateway.proxy(body)).isInstanceOf(ResponseStatusException.class);assertThat(requests.get()).isEqualTo(sent);
    }
    @Test void retiredNoteStateIsConfinedToExactContentCollectionReads() throws Exception {
        String id="019a0808-1234-7000-8000-000000000001",base="/api/spaces/"+id+"/notes";
        for(String state:new String[]{"ACTIVE","RETIRED"}){var r=request("GET",base);r.setQueryString("state="+state+"&limit=20&after="+id);assertThat(gateway.proxy(r).getStatusCode().value()).isEqualTo(200);}
        int sent=requests.get();
        for(String query:new String[]{"state=ALL","state=retired","state=","state=RETIRED&state=ACTIVE","state=%52ETIRED","state=RETIRED&limit=101","state=RETIRED&after=bad","state=RETIRED&before=1","state=RETIRED&limit=20&limit=10"}){var r=request("GET",base);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String path:new String[]{base+"/"+id,base+"/"+id+"/revisions",base+"/"+id+"/metadata",base+"/"+id+"/links",base+"/"+id+"/backlinks",base+"/"+id+"/attachments",base+"/"+id+"/concepts","/api/spaces/"+id+"/daily-notes","/api/spaces/"+id+"/concepts/"+id+"/notes","/api/spaces/"+id+"/search/notes","/api/spaces/"+id+"/graph/notes"}){var r=request("GET",path);r.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        for(String method:new String[]{"POST","PUT","DELETE"}){var r=request(method,method.equals("POST")?base:base+"/"+id);r.addHeader("Origin","https://localhost:8443");r.setQueryString("state=RETIRED");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}
        assertThat(requests.get()).isEqualTo(sent);
    }

    @Test void propertyRangeQueriesAllowOneOrTwoEncodedBoundsOnlyOnExactReadRoute() throws Exception {
        String id="019a0808-1234-7000-8000-000000000001",path="/api/spaces/"+id+"/property-definitions/"+id+"/notes";
        for(String query:new String[]{"minValue=8e%2B1&maxValue=100&limit=20&after="+id,"minValue=-0.001","maxValue=2024-02-29"}){var r=request("GET",path);r.setQueryString(query);assertThat(gateway.proxy(r).getStatusCode().value()).isEqualTo(200);}
        int sent=requests.get();for(String url:new String[]{"/api/spaces/"+id+"/property-definitions","/api/spaces/"+id+"/tags/"+id+"/notes","/api/spaces/"+id+"/notes"})for(String query:new String[]{"minValue=1","maxValue=2"}){var r=request("GET",url);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}assertThat(requests.get()).isEqualTo(sent);
    }
    @Test void malformedMixedAndDuplicateRangeQueriesNeverReachUpstream() throws Exception {
        String id="019a0808-1234-7000-8000-000000000001",path="/api/spaces/"+id+"/property-definitions/"+id+"/notes";
        for(String query:new String[]{"minValue=","maxValue=","minValue=1&minValue=2","maxValue=1&maxValue=2","value=&minValue=1","maxValue=2&value=1","minValue=%FF","maxValue=%00","minValue=%","maxValue="+"x".repeat(201),"minValue=1&operator=GT","maxValue=2&limit=101"}){var r=request("GET",path);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}assertThat(requests.get()).isZero();
    }

    @Test void propertyTagFilterCombinesWithAllExactAndRangeReadsOnlyOnPropertyRoute() throws Exception {
        String id="019a0808-1234-7000-8000-000000000001",path="/api/spaces/"+id+"/property-definitions/"+id+"/notes";
        for(String query:new String[]{"tagId="+id,"tagId="+id+"&value=","minValue=80&tagId="+id+"&maxValue=100&after="+id+"&limit=20"}){var r=request("GET",path);r.setQueryString(query);assertThat(gateway.proxy(r).getStatusCode().value()).isEqualTo(200);}
        int sent=requests.get();for(String url:new String[]{"/api/spaces/"+id+"/property-definitions","/api/spaces/"+id+"/tags","/api/spaces/"+id+"/tags/"+id+"/notes","/api/spaces/"+id+"/notes"}){var r=request("GET",url);r.setQueryString("tagId="+id);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}assertThat(requests.get()).isEqualTo(sent);
    }
    @Test void malformedDuplicateAndEncodedTagIdsNeverReachUpstream() throws Exception {
        String id="019a0808-1234-7000-8000-000000000001",path="/api/spaces/"+id+"/property-definitions/"+id+"/notes";
        for(String query:new String[]{"tagId=","tagId=bad","tagId="+id.toUpperCase(),"tagId=%30"+id.substring(1),"tagId="+id+"&tagId="+id,"tagId="+id+"&value=80&minValue=1","tagId="+id+"&limit=101","tagId="+id+"&operator=OR"}){var r=request("GET",path);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}assertThat(requests.get()).isZero();
    }

    @Test void metadataCatalogNameQueryAllowsEncodedLiteralUnicodeWithKeysetOnlyOnCatalogs() throws Exception {
        String id="019a0808-1234-7000-8000-000000000001";
        for(String catalog:new String[]{"tags","property-definitions"})for(String q:new String[]{"study","%_ '한글+&","🧠".repeat(64),"e\u0301".repeat(64)}){var r=request("GET","/api/spaces/"+id+"/"+catalog);r.setQueryString("limit=20&after="+id+"&q="+java.net.URLEncoder.encode(q,StandardCharsets.UTF_8));assertThat(gateway.proxy(r).getStatusCode().value()).isEqualTo(200);}
        int sent=requests.get();for(String catalog:new String[]{"tags","property-definitions"}){var r=request("GET","/api/spaces/"+id+"/"+catalog+"/"+id+"/notes");r.setQueryString("q=study");assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}assertThat(requests.get()).isEqualTo(sent);
    }
    @Test void metadataNameQueryRejectsInvalidUtf8ControlsDuplicatesAndLimitsBeforeUpstream() throws Exception {
        String id="019a0808-1234-7000-8000-000000000001";
        for(String catalog:new String[]{"tags","property-definitions"})for(String query:new String[]{"q=","q=a&q=b","q=%FF","q=%C0%AF","q=%00","q=%0A","q=%C2%85","q=%","q="+"x".repeat(65),"q="+"%F0%9F%A7%A0".repeat(65),"q=x&limit=101","q=x&operator=OR","q=x&after=","q=x&tagId="+id}){var r=request("GET","/api/spaces/"+id+"/"+catalog);r.setQueryString(query);assertThatThrownBy(()->gateway.proxy(r)).isInstanceOf(ResponseStatusException.class);}assertThat(requests.get()).isZero();
    }
}
