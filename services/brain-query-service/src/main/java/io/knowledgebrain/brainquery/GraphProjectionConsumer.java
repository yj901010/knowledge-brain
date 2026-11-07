package io.knowledgebrain.brainquery;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.context.SmartLifecycle;
import io.micrometer.core.instrument.MeterRegistry;
import io.knowledgebrain.contracts.content.links.v1.LinkEventEnvelopeV1;
import org.slf4j.LoggerFactory;

final class GraphProjectionConsumer implements SmartLifecycle {
 static final List<String> TOPICS=List.of("content.note.v1","content.note-links.v1");
 static final String GROUP="brain-query-note-graph-v1";
 private final GraphProjectionInbox inbox;private final Supplier<NoteGraphStore> factory;private final Properties options;private final MeterRegistry metrics;
 private volatile NoteGraphStore activeGraph;private volatile KafkaConsumer<String,byte[]> client;private volatile Thread worker;private volatile boolean running,ready,joined;
 GraphProjectionConsumer(GraphProjectionInbox inbox,Supplier<NoteGraphStore> factory,Properties options,MeterRegistry metrics){this.inbox=inbox;this.factory=factory;this.options=options;this.metrics=metrics;}
 static String kind(String topic){if(topic.equals(TOPICS.get(0)))return "NOTE";if(topic.equals(TOPICS.get(1)))return "LINKS";throw new IllegalArgumentException("Unsupported graph topic");}
 static void validate(String topic,byte[] wire,String key,Map<String,String> headers){
  if(kind(topic).equals("NOTE")){NoteProjectionConsumer.validate(wire,key,headers);return;}
  var input=NoteGraphProjection.decode(wire);
  try {
   var event=LinkEventEnvelopeV1.parseFrom(wire);
   if(!input.sourceNoteId().toString().equals(key) || !input.eventId().toString().equals(headers.get("id"))
     || !"content".equals(headers.get("schema_group")) || !"note-links-events".equals(headers.get("schema_artifact"))
     || !"1".equals(headers.get("schema_version")) || event.getOccurredAtEpochMillis()<=0
     || !event.getTraceId().matches("[0-9a-f]{32}") || event.getTraceId().equals("0".repeat(32)) || !event.getTraceId().equals(headers.get("trace_id")))throw new IllegalArgumentException("Invalid graph transport metadata");
  }catch(com.google.protobuf.InvalidProtocolBufferException e){throw new IllegalArgumentException("Invalid graph event");}
 }
 @Override public void start(){if(running)return;running=true;worker=Thread.ofVirtual().name("note-graph-projection").start(this::run);}
 private void run(){
  while(running){
   try(var graph=factory.get();var consumer=new KafkaConsumer<String,byte[]>(options)){
    client=consumer;activeGraph=graph;joined=false;
    consumer.subscribe(TOPICS,new ConsumerRebalanceListener(){
     public void onPartitionsRevoked(Collection<TopicPartition> partitions){joined=false;ready=false;}
     public void onPartitionsAssigned(Collection<TopicPartition> partitions){
      var committed=consumer.committed(Set.copyOf(partitions));var beginning=consumer.beginningOffsets(partitions);var end=consumer.endOffsets(partitions);
      for(var partition:partitions){var checkpoint=committed.get(partition);consumer.seek(partition,NoteProjectionConsumer.initialOffset(checkpoint==null?null:checkpoint.offset(),beginning.get(partition),end.get(partition)));}
      joined=true;
     }
    });
    while(running){
     var records=consumer.poll(Duration.ofSeconds(1));
     for(var key:inbox.pending(10))project(key,graph);
     graph.ping();ready=joined;
     for(var record:records){
      var headers=new HashMap<String,String>();for(var h:record.headers())if(h.value()==null || headers.putIfAbsent(h.key(),new String(h.value(),StandardCharsets.UTF_8))!=null)throw new IllegalArgumentException("Invalid graph transport header");
      validate(record.topic(),record.value(),record.key(),headers);
      project(inbox.stage(kind(record.topic()),record.value()),graph);
      consumer.commitSync(Map.of(new TopicPartition(record.topic(),record.partition()),new OffsetAndMetadata(record.offset()+1)));
      LoggerFactory.getLogger(getClass()).info("graph_projection event_id={} trace_id={} kind={} outcome=ACKNOWLEDGED",headers.get("id"),headers.get("trace_id"),kind(record.topic()));
     }
    }
   }catch(WakeupException e){if(!running)break;}
   catch(RuntimeException e){
    String type=e instanceof NoteProjectionConsumer.RebuildRequired?"rebuild_required":e instanceof GraphProjectionInbox.Conflict?"conflict":"retry";
    metrics.counter("brain.graph.projection.failures","type",type).increment();
    LoggerFactory.getLogger(getClass()).warn("graph_projection failure={}; offset remains uncommitted",type);
   }finally{activeGraph=null;client=null;ready=false;joined=false;}
   if(running)try{Thread.sleep(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
  }
  running=false;
 }
 private void project(GraphProjectionInbox.Key key,NoteGraphStore graph){var timer=io.micrometer.core.instrument.Timer.start(metrics);try{inbox.project(key,graph);metrics.counter("brain.graph.projection.events","kind",key.kind()).increment();}finally{timer.stop(metrics.timer("brain.graph.projection.duration","kind",key.kind()));}}
 NoteGraphStore.View read(UUID space,int limit){var graph=activeGraph;if(!isReady() || graph==null)throw new GraphReadClient.Unavailable();try{var result=graph.read(space,limit);if(!isReady())throw new GraphReadClient.Unavailable();return result;}catch(RuntimeException e){throw new GraphReadClient.Unavailable();}}
 boolean isReady(){return running && ready;}
 @Override public boolean isRunning(){return running;}
 @Override public void stop(){running=false;ready=false;var current=client;if(current!=null)current.wakeup();if(worker!=null)try{worker.join(15000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
