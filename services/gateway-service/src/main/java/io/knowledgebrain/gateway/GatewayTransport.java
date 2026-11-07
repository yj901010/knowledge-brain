package io.knowledgebrain.gateway;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

final class GatewayTransport {
    private final HttpClient client;
    private final Map<String,URI> origins;
    private final Duration deadline;
    GatewayTransport(HttpClient client,URI identity,URI space) { this(client,identity,space,Duration.ofSeconds(10)); }
    GatewayTransport(HttpClient client,URI identity,URI space,Duration deadline) { this(client,identity,space,space,deadline); }
    GatewayTransport(HttpClient client,URI identity,URI space,URI content,Duration deadline) { this(client,identity,space,content,space,deadline); }
    GatewayTransport(HttpClient client,URI identity,URI space,URI content,URI media,Duration deadline) { this(client,identity,space,content,media,space,deadline); }
    GatewayTransport(HttpClient client,URI identity,URI space,URI content,URI media,URI query,Duration deadline) {this(client,identity,space,content,media,query,space,deadline);}
    GatewayTransport(HttpClient client,URI identity,URI space,URI content,URI media,URI query,URI knowledge,Duration deadline) { this(client,identity,space,content,media,query,knowledge,space,deadline); }
    GatewayTransport(HttpClient client,URI identity,URI space,URI content,URI media,URI query,URI knowledge,URI learning,Duration deadline) { this(client,identity,space,content,media,query,knowledge,learning,space,deadline); }
    GatewayTransport(HttpClient client,URI identity,URI space,URI content,URI media,URI query,URI knowledge,URI learning,URI activity,Duration deadline) { this(client,identity,space,content,media,query,knowledge,learning,activity,space,deadline); }
    GatewayTransport(HttpClient client,URI identity,URI space,URI content,URI media,URI query,URI knowledge,URI learning,URI activity,URI planning,Duration deadline) {this(client,identity,space,content,media,query,knowledge,learning,activity,planning,space,deadline);}
    GatewayTransport(HttpClient client,URI identity,URI space,URI content,URI media,URI query,URI knowledge,URI learning,URI activity,URI planning,URI notification,Duration deadline) {this(client,identity,space,content,media,query,knowledge,learning,activity,planning,notification,space,deadline);}
    GatewayTransport(HttpClient client,URI identity,URI space,URI content,URI media,URI query,URI knowledge,URI learning,URI activity,URI planning,URI notification,URI collaboration,Duration deadline) {this(client,identity,space,content,media,query,knowledge,learning,activity,planning,notification,collaboration,space,deadline);}
    GatewayTransport(HttpClient client,URI identity,URI space,URI content,URI media,URI query,URI knowledge,URI learning,URI activity,URI planning,URI notification,URI collaboration,URI integration,Duration deadline) {this.client=client;this.origins=Map.ofEntries(Map.entry("identity",identity),Map.entry("space",space),Map.entry("content",content),Map.entry("media",media),Map.entry("brain-query",query),Map.entry("knowledge",knowledge),Map.entry("learning",learning),Map.entry("activity",activity),Map.entry("planning",planning),Map.entry("notification",notification),Map.entry("collaboration",collaboration),Map.entry("integration",integration));this.deadline=deadline;} 
    HttpResponse<byte[]> send(GatewayRoute route,String method,Map<String,List<String>> headers,byte[] body) throws IOException,InterruptedException {
        var timeout="integration".equals(route.service())?Duration.ofSeconds(35):("brain-query".equals(route.service())||"collaboration".equals(route.service()))?Duration.ofSeconds(15):deadline;
        var request=HttpRequest.newBuilder(origins.get(route.service()).resolve(route.path())).timeout(timeout);
        headers.forEach((name,values)->values.forEach(value->request.header(name,value)));
        request.method(method,("POST".equals(method) || "PUT".equals(method))?HttpRequest.BodyPublishers.ofByteArray(body):HttpRequest.BodyPublishers.noBody());
        // No automatic retries of guest-creation POSTs, redirects, cookies or authentication cache.
        var pending=client.sendAsync(request.build(),info->new LimitedBody("activity".equals(route.service())?8388608:"learning".equals(route.service())?8388608:("integration".equals(route.service()) || "collaboration".equals(route.service()) || "notification".equals(route.service()) || "planning".equals(route.service()) || "content".equals(route.service()) || "media".equals(route.service()) || "knowledge".equals(route.service()))?2097152:("brain-query".equals(route.service()) && route.path().split("\\?",2)[0].matches(".*/(graph|search)/(notes|network)"))?1048576:65536));
        try { return pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS); }
        catch(TimeoutException error) { pending.cancel(true);throw new HttpTimeoutException("Upstream response deadline exceeded"); }
        catch(InterruptedException error) { pending.cancel(true);throw error; }
        catch(ExecutionException error) {
            if(error.getCause() instanceof IOException io)throw io;
            throw new IOException("Upstream response failed",error.getCause());
        }
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit=limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription;subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(buffer.remaining()>limit-bytes.size()) { subscription.cancel();result.completeExceptionally(new IOException("Upstream response exceeds limit"));return; }
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
