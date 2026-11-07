package io.knowledgebrain.brainquery;
import java.util.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;
import org.postgresql.ds.PGSimpleDataSource;

/** Resets only public derived namespaces. Canonical sources, private graph and offsets survive. */
public final class PublicRebuildRunner {
 static String env(String key){return GraphRebuildRunner.env(key);}
 static void drain(PublicProjectionStore store,PublicGraphStore graph,NoteSearchClient search){for(int i=0;i<100000;i++){var keys=store.pending(20);if(keys.isEmpty())return;store.materialize(keys,graph,search);}throw new IllegalStateException("Public queue bound exceeded");}
 public static void main(String[] args)throws Exception{if(args.length>1||args.length==1&&!Set.of("--preflight","--pause").contains(args[0]))throw new IllegalArgumentException("Unknown public rebuild command");var ds=new PGSimpleDataSource();ds.setURL(env("BRAIN_QUERY_DATABASE_URL"));ds.setUser("brain_brainquery");ds.setPassword(env("BRAIN_QUERY_DATABASE_PASSWORD"));var store=new PublicProjectionStore(ds);var source=new PublicSnapshotClient(env("BRAIN_QUERY_KNOWLEDGE_ORIGIN"),"/public-trust/ca.crt",env("BRAIN_QUERY_PUBLIC_SNAPSHOT_KEY"));for(String kind:List.of("CONCEPT","RECORD"))source.page(kind,null,null);if(args.length==1&&args[0].equals("--preflight")){System.out.println("PASS bounded public canonical feeds; no reset");return;}
  try(var lock=ds.getConnection();var statement=lock.createStatement()){try(var result=statement.executeQuery("SELECT pg_try_advisory_lock(87361042)")){result.next();if(!result.getBoolean(1))throw new IllegalStateException("Another public rebuild is active");}store.available(false);
   var options=GraphConfiguration.kafka(env("BRAIN_QUERY_KAFKA_BOOTSTRAP_SERVERS"),"/public-kafka");options.put("group.id",PublicProjection.GROUP);
   try(var admin=Admin.create(options)){for(int i=0;;i++){ConsumerGroupDescription group;try{group=admin.describeConsumerGroups(List.of(PublicProjection.GROUP)).all().get(15,TimeUnit.SECONDS).get(PublicProjection.GROUP);}catch(java.util.concurrent.ExecutionException e){if(e.getCause() instanceof org.apache.kafka.common.errors.GroupIdNotFoundException)break;throw e;}if(group.members().isEmpty())break;if(i>=30)throw new IllegalStateException("Public consumer did not release group");Thread.sleep(2000);}}
   try(var c=new KafkaConsumer<String,byte[]>(options)){var partitions=c.partitionsFor(PublicProjection.TOPIC).stream().map(p->new TopicPartition(p.topic(),p.partition())).toList();if(partitions.isEmpty())throw new IllegalStateException("Public topic unavailable");c.assign(partitions);var start=c.endOffsets(partitions);for(var p:partitions)c.seek(p,start.get(p));
    String host=env("BRAIN_QUERY_GRAPH_HOST"),adminTrust=GraphConfiguration.truststore("/graph-admin");try(var graph=NoteGraphStore.open(host,Files.readString(Path.of("/graph-admin/username")).strip(),Files.readString(Path.of("/graph-admin/password")).strip(),adminTrust,Files.readString(Path.of("/graph-admin/password")).strip())){PublicGraphStore.initialize(graph);}
    var indexAdmin=new NoteSearchClient(env("BRAIN_QUERY_SEARCH_ORIGIN"),Path.of("/search-admin"));indexAdmin.resetPublic(Files.readString(Path.of("/source/mapping.json")));var search=new NoteSearchClient(env("BRAIN_QUERY_SEARCH_ORIGIN"),Path.of("/search-projector"));String trust=GraphConfiguration.truststore("/graph-projector");try(var graph=PublicDiscoveryConfiguration.graph(host,"/graph-projector",trust)){graph.reset();store.reset();long count=0;
     for(String kind:List.of("CONCEPT","RECORD")){UUID after=null,through=null;do{var page=source.page(kind,after,through);through=page.through();for(var item:page.items()){byte[] wire=Base64.getDecoder().decode(item.payload());store.stage(PublicProjection.decode(wire,item.entityId().toString(),null),wire,true);count++;}after=page.nextAfter();}while(after!=null);System.out.println("PASS public source staged; kind="+kind+" cumulative_count="+count);}drain(store,graph,search);
     if(args.length==1&&args[0].equals("--pause")){System.out.println("PUBLIC_REBUILD_SNAPSHOT_READY");for(int i=0;!Files.exists(Path.of("/tmp/rebuild.resume"));i++){if(i>=180)throw new IllegalStateException("Rebuild handshake deadline");Thread.sleep(1000);}}
     var end=c.endOffsets(partitions);long replayed=0;long deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();while(partitions.stream().anyMatch(p->c.position(p)<end.get(p))){if(System.nanoTime()>deadline)throw new IllegalStateException("Public tail replay deadline");for(var record:c.poll(Duration.ofSeconds(1))){var headers=new HashMap<String,String>();for(var h:record.headers())if(h.value()==null||headers.putIfAbsent(h.key(),new String(h.value(),java.nio.charset.StandardCharsets.UTF_8))!=null)throw PublicProjection.invalid();store.stage(PublicProjection.decode(record.value(),record.key(),headers),record.value(),true);replayed++;}drain(store,graph,search);}var checkpoints=new HashMap<TopicPartition,OffsetAndMetadata>();for(var p:partitions)checkpoints.put(p,new OffsetAndMetadata(c.position(p)));c.commitSync(checkpoints);graph.ping();search.pingPublic();store.available(true);System.out.println("PASS public-only canonical rebuild, captured Kafka tail, durable queue and checkpoints; count="+count+"; tail_events="+replayed);
    }
   }
  }
 }
}
