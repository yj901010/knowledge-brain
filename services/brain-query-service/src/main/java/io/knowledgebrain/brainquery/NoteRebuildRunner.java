package io.knowledgebrain.brainquery;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
/** Confined operator Job only. All live consumer replicas must be stopped by the coordinator. */
public final class NoteRebuildRunner {
    private static String env(String key) { var value=System.getenv(key);if(value==null || value.isBlank())throw new IllegalStateException("Rebuild configuration missing");return value; }
    public static void main(String[] ignored) throws Exception {
        var source=new NoteSnapshotClient(env("BRAIN_QUERY_CONTENT_ORIGIN"),env("BRAIN_QUERY_CONTENT_TRUST_FILE"),env("BRAIN_QUERY_CONTENT_SNAPSHOT_KEY"));
        var index=new NoteSearchClient(env("BRAIN_QUERY_SEARCH_ORIGIN"),Path.of("/search-projector"));
        var options=new Properties();options.put("bootstrap.servers",env("BRAIN_QUERY_KAFKA_BOOTSTRAP_SERVERS"));options.put("group.id","brain-query-note-search-v1");
        options.put("enable.auto.commit","false");options.put("auto.offset.reset","none");options.put("max.poll.records","10");
        options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());
        options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/kafka/ca.crt")));
        options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/kafka/user.crt")));options.put("ssl.keystore.key",Files.readString(Path.of("/kafka/user.key")));
        try(var consumer=new KafkaConsumer<String,byte[]>(options)) {
            var partitions=consumer.partitionsFor("content.note.v1").stream().map(p->new TopicPartition(p.topic(),p.partition())).toList();
            if(partitions.isEmpty())throw new IllegalStateException("Note topic unavailable");
            consumer.assign(partitions);var start=consumer.endOffsets(partitions);for(var p:partitions)consumer.seek(p,start.get(p));
            // Capture Kafka positions BEFORE scanning canonical data. Later commits are replayed at full note versions.
            UUID after=null,through=null;long count=0;
            do {
                var page=source.page(after,through);through=page.through();
                for(var note:page.items()) { index.apply(note.projection());count++; }
                after=page.nextAfter();
            } while(after!=null);
            System.out.println("PASS canonical snapshot indexed; notes="+count);
            if("true".equals(System.getenv("BRAIN_REBUILD_PAUSE"))) {
                System.out.println("REBUILD_SNAPSHOT_READY");
                for(int i=0;!Files.exists(Path.of("/tmp/rebuild.resume"));i++) { if(i>=180)throw new IllegalStateException("Rebuild handshake timed out");Thread.sleep(1000); }
            }
            var end=consumer.endOffsets(partitions);long deadline=System.nanoTime()+Duration.ofSeconds(180).toNanos();
            while(partitions.stream().anyMatch(p->consumer.position(p)<end.get(p))) {
                if(System.nanoTime()>deadline)throw new IllegalStateException("Rebuild replay timed out");
                for(var record:consumer.poll(Duration.ofSeconds(1))) {
                    var headers=new HashMap<String,String>();
                    for(var h:record.headers())if(h.value()==null || headers.putIfAbsent(h.key(),new String(h.value(),java.nio.charset.StandardCharsets.UTF_8))!=null)throw new IllegalStateException("Invalid rebuild event headers");
                    NoteProjectionConsumer.validate(record.value(),record.key(),headers);
                    index.apply(NoteSearchProjection.decode(record.value()));
                }
            }
            var positions=new HashMap<TopicPartition,OffsetAndMetadata>();for(var p:partitions)positions.put(p,new OffsetAndMetadata(consumer.position(p)));
            consumer.commitSync(positions);
            System.out.println("PASS captured Kafka tail replayed and durable group checkpoint committed");
        }
    }
}
