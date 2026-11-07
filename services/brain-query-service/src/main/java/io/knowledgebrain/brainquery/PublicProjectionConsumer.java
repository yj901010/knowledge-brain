package io.knowledgebrain.brainquery;
import java.util.*;
import java.util.function.Supplier;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.context.SmartLifecycle;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;

final class PublicProjectionConsumer implements SmartLifecycle {
 static final String GROUP="brain-query-public-v1";
 private final PublicProjectionStore store;private final NoteSearchClient search;private final Supplier<PublicGraphStore> factory;private final Properties options;private final MeterRegistry metrics;
 private volatile KafkaConsumer<String,byte[]> client;private volatile PublicGraphStore graph;private volatile Thread worker;private volatile boolean running,ready,joined;
 PublicProjectionConsumer(PublicProjectionStore store,NoteSearchClient search,Supplier<PublicGraphStore> factory,Properties options,MeterRegistry metrics){this.store=store;this.search=search;this.factory=factory;this.options=options;this.metrics=metrics;}
 @Override public void start(){if(running)return;running=true;worker=Thread.ofVirtual().name("public-knowledge-projection").start(this::run);}
 private void run(){while(running){if(!store.available()){try{Thread.sleep(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}continue;}try(var g=factory.get();var c=new KafkaConsumer<String,byte[]>(options)){graph=g;client=c;joined=false;c.subscribe(List.of(PublicProjection.TOPIC),new ConsumerRebalanceListener(){public void onPartitionsRevoked(Collection<TopicPartition> p){joined=false;ready=false;}public void onPartitionsAssigned(Collection<TopicPartition> p){var committed=c.committed(Set.copyOf(p));var beginning=c.beginningOffsets(p);var end=c.endOffsets(p);for(var partition:p){var checkpoint=committed.get(partition);c.seek(partition,NoteProjectionConsumer.initialOffset(checkpoint==null?null:checkpoint.offset(),beginning.get(partition),end.get(partition)));}joined=true;}});
  while(running){if(!store.available())throw new PublicProjectionStore.Paused();var records=c.poll(Duration.ofSeconds(1));for(var record:records){var headers=new HashMap<String,String>();for(var h:record.headers())if(h.value()==null||headers.putIfAbsent(h.key(),new String(h.value(),StandardCharsets.UTF_8))!=null)throw PublicProjection.invalid();var n=PublicProjection.decode(record.value(),record.key(),headers);store.stage(n,record.value());c.commitSync(Map.of(new TopicPartition(record.topic(),record.partition()),new OffsetAndMetadata(record.offset()+1)));metrics.counter("brain.public.events","kind",(n==null?"PROPOSAL":n.kind())).increment();if(n!=null)LoggerFactory.getLogger(getClass()).info("public_discovery event_id={} trace_id={} kind={} outcome=DURABLE",n.eventId(),n.trace(),n.kind());}
   var keys=store.pending(10);if(!keys.isEmpty())store.materialize(keys,g,search);g.ping();search.pingPublic();ready=joined;
  }
 }catch(WakeupException e){if(!running)break;}catch(RuntimeException e){metrics.counter("brain.public.failures","type",e instanceof NoteProjectionConsumer.RebuildRequired?"rebuild_required":e instanceof PublicProjectionStore.Conflict?"conflict":"retry").increment();LoggerFactory.getLogger(getClass()).warn("public_discovery projection retry; durable queue retained");}finally{ready=false;joined=false;client=null;graph=null;}if(running)try{Thread.sleep(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}running=false;}
 boolean isReady(){return running&&ready&&store.available();}
 PublicGraphStore.View read(UUID space,UUID center,int depth,int limit){var g=graph;if(!isReady()||g==null)throw new GraphReadClient.Unavailable();try{var view=g.read(space,center,depth,limit);if(!isReady())throw new GraphReadClient.Unavailable();return view;}catch(RuntimeException e){throw new GraphReadClient.Unavailable();}}
 @Override public boolean isRunning(){return running;}
 @Override public void stop(){running=false;ready=false;var c=client;if(c!=null)c.wakeup();if(worker!=null)try{worker.join(15000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
