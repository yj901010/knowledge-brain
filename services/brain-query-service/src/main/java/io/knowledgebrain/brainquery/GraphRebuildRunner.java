package io.knowledgebrain.brainquery;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Operator-only canonical rebuild; coordinator must stop every live Query replica. */
public final class GraphRebuildRunner {
 static String env(String key){var value=System.getenv(key);if(value==null || value.isBlank())throw new IllegalStateException("Graph rebuild configuration missing");return value;}
 public static void main(String[] args)throws Exception{
  String directory=args.length==1 && args[0].equals("--initialize")?"/graph-admin":"/graph-projector";
  String trust=GraphConfiguration.truststore(directory);
  try(var graph=GraphConfiguration.open(env("BRAIN_QUERY_GRAPH_HOST"),directory,trust)){
   if(args.length==1 && args[0].equals("--initialize")){graph.initialize();System.out.println("PASS fixed JanusGraph schema initialized by separate operator");return;}
   var source=new NoteSnapshotClient(env("BRAIN_QUERY_CONTENT_ORIGIN"),env("BRAIN_QUERY_CONTENT_TRUST_FILE"),env("BRAIN_QUERY_CONTENT_SNAPSHOT_KEY"));
   var database=new PGSimpleDataSource();database.setURL(env("BRAIN_QUERY_DATABASE_URL"));database.setUser("brain_brainquery");database.setPassword(env("BRAIN_QUERY_DATABASE_PASSWORD"));
   var inbox=new GraphProjectionInbox(database);
   try(var consumer=new KafkaConsumer<String,byte[]>(GraphConfiguration.kafka(env("BRAIN_QUERY_KAFKA_BOOTSTRAP_SERVERS"),"/graph-kafka"))){
    var partitions=new ArrayList<TopicPartition>();for(String topic:GraphProjectionConsumer.TOPICS){var values=consumer.partitionsFor(topic);if(values.isEmpty())throw new IllegalStateException("Graph topic unavailable");for(var p:values)partitions.add(new TopicPartition(p.topic(),p.partition()));}
    consumer.assign(partitions);var start=consumer.endOffsets(partitions);for(var p:partitions)consumer.seek(p,start.get(p));
    new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(database)).executeWithoutResult(s->{var sql=new JdbcTemplate(database);sql.update("DELETE FROM note_graph_snapshot");sql.update("DELETE FROM note_graph_inbox");sql.update("DELETE FROM note_graph_anchor");});
    UUID after=null,through=null;long notes=0,links=0;
    do{var page=source.page(after,through);through=page.through();for(var item:page.items()){inbox.project(inbox.stage("NOTE",NoteSnapshotClient.noteWire(item)),graph);notes++;}after=page.nextAfter();}while(after!=null);
    after=null;
    do{var page=source.links(after,through);if(through==null)through=page.through();for(var item:page.items()){inbox.project(inbox.stage("LINKS",item.wire()),graph);links++;}after=page.nextAfter();}while(after!=null);
    System.out.println("PASS canonical Note/link snapshots projected; notes="+notes+" linkSnapshots="+links);
    if("true".equals(System.getenv("BRAIN_REBUILD_PAUSE"))){System.out.println("GRAPH_REBUILD_SNAPSHOT_READY");for(int i=0;!Files.exists(Path.of("/tmp/rebuild.resume"));i++){if(i>=180)throw new IllegalStateException("Graph rebuild handshake deadline");Thread.sleep(1000);}}
    var end=consumer.endOffsets(partitions);long deadline=System.nanoTime()+Duration.ofSeconds(300).toNanos();
    while(partitions.stream().anyMatch(p->consumer.position(p)<end.get(p))){
     if(System.nanoTime()>deadline)throw new IllegalStateException("Graph replay deadline");
     for(var record:consumer.poll(Duration.ofSeconds(1))){var headers=new HashMap<String,String>();for(var h:record.headers())if(h.value()==null || headers.putIfAbsent(h.key(),new String(h.value(),java.nio.charset.StandardCharsets.UTF_8))!=null)throw new IllegalStateException("Invalid graph replay headers");GraphProjectionConsumer.validate(record.topic(),record.value(),record.key(),headers);inbox.project(inbox.stage(GraphProjectionConsumer.kind(record.topic()),record.value()),graph);}
    }
    var positions=new HashMap<TopicPartition,OffsetAndMetadata>();for(var p:partitions)positions.put(p,new OffsetAndMetadata(consumer.position(p)));consumer.commitSync(positions);
    System.out.println("PASS both captured Kafka tails replayed; graph Inbox/queue and group checkpoint durable");
   }
  }
 }
}
