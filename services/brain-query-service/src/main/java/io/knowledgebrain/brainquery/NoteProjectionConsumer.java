package io.knowledgebrain.brainquery;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Collection;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.context.SmartLifecycle;
import io.knowledgebrain.contracts.content.v1.EventEnvelopeV1;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;

final class NoteProjectionConsumer implements SmartLifecycle {
    private final NoteProjectionStore store;
    private final NoteSearchClient index;
    private final Properties options;
    private final MeterRegistry metrics;
    private volatile KafkaConsumer<String,byte[]> client;
    private volatile Thread worker;
    private volatile boolean running,ready,joined;
    NoteProjectionConsumer(NoteProjectionStore store,NoteSearchClient index,Properties options,MeterRegistry metrics) {
        this.store=store;this.index=index;this.options=options;this.metrics=metrics;
    }
    static void validate(byte[] wire,String key,Map<String,String> headers) {
        var note=NoteSearchProjection.decode(wire);
        try {
            var event=EventEnvelopeV1.parseFrom(wire);
            if(!note.noteId().toString().equals(key) || !note.eventId().toString().equals(headers.get("id"))
                    || !"content".equals(headers.get("schema_group")) || !"note-events".equals(headers.get("schema_artifact"))
                    || !"1".equals(headers.get("schema_version")) || event.getOccurredAtEpochMillis()<=0
                    || !event.getTraceId().matches("[0-9a-f]{32}") || event.getTraceId().equals("0".repeat(32))
                    || !event.getTraceId().equals(headers.get("trace_id")))throw new IllegalArgumentException("Invalid note transport metadata");
        } catch(com.google.protobuf.InvalidProtocolBufferException e) { throw new IllegalArgumentException("Invalid note event"); }
    }
    static final class RebuildRequired extends RuntimeException {
        RebuildRequired() { super("Canonical rebuild required after Kafka retention gap"); }
    }
    static long initialOffset(Long committed,long beginning,long end) {
        if(beginning<0 || end<beginning || (committed==null && beginning>0) || (committed!=null && (committed<beginning || committed>end)))throw new RebuildRequired();
        return committed==null?beginning:committed;
    }
    private void project(byte[] wire) {
        var note=NoteSearchProjection.decode(wire);
        var result=index.apply(note);store.applied(note);
        metrics.counter("brain.search.projection.events","outcome",result.name()).increment();
    }
    @Override public void start() {
        if(running)return;running=true;worker=Thread.ofVirtual().name("note-search-projection").start(this::run);
    }
    private void run() {
        while(running) {
            try(var consumer=new KafkaConsumer<String,byte[]>(options)) {
                client=consumer;joined=false;
                consumer.subscribe(List.of("content.note.v1"),new ConsumerRebalanceListener() {
                    public void onPartitionsRevoked(Collection<TopicPartition> partitions) { joined=false;ready=false; }
                    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                        var committed=consumer.committed(Set.copyOf(partitions));
                        var beginning=consumer.beginningOffsets(partitions);var end=consumer.endOffsets(partitions);
                        for(var partition:partitions) {
                            var checkpoint=committed.get(partition);
                            consumer.seek(partition,initialOffset(checkpoint==null?null:checkpoint.offset(),beginning.get(partition),end.get(partition)));
                        }
                        joined=true;
                    }
                });
                while(running) {
                    var records=consumer.poll(Duration.ofSeconds(1));
                    for(var wire:store.pending(10))project(wire);
                    ready=joined; // Joined idle replicas are healthy when there are fewer partitions than consumers.
                    for(var record:records) {
                        var headers=new HashMap<String,String>();
                        for(var h:record.headers())if(h.value()==null || headers.putIfAbsent(h.key(),new String(h.value(),StandardCharsets.UTF_8))!=null)
                            throw new IllegalArgumentException("Duplicate or null note transport header");
                        validate(record.value(),record.key(),headers);
                        project(store.stage(record.value()));
                        // Inbox/queue and OpenSearch are durable before acknowledgement; replay is safe after any crash.
                        consumer.commitSync(Map.of(new TopicPartition(record.topic(),record.partition()),new OffsetAndMetadata(record.offset()+1)));
                        LoggerFactory.getLogger(getClass()).info("note_projection event_id={} trace_id={} outcome=ACKNOWLEDGED",headers.get("id"),headers.get("trace_id"));
                    }
                }
            } catch(WakeupException e) { if(!running)break; }
            catch(RuntimeException e) {
                metrics.counter("brain.search.projection.failures","type",e instanceof RebuildRequired?"rebuild_required":e instanceof NoteSearchClient.Conflict?"conflict":"retry").increment();
                LoggerFactory.getLogger(getClass()).warn("note_projection retry failure={}; offset remains uncommitted",e.getClass().getSimpleName());
            } finally { client=null;ready=false;joined=false; }
            if(running)try { Thread.sleep(3000); } catch(InterruptedException e) { Thread.currentThread().interrupt();break; }
        }
        running=false;
    }
    boolean isReady() { return running && ready; }
    @Override public boolean isRunning() { return running; }
    @Override public void stop() {
        running=false;ready=false;var current=client;if(current!=null)current.wakeup();
        if(worker!=null)try { worker.join(15000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
