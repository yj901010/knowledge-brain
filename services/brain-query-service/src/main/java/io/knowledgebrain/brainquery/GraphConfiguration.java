package io.knowledgebrain.brainquery;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Properties;
import javax.sql.DataSource;
import org.apache.kafka.common.serialization.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.*;
import org.springframework.context.annotation.*;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="brain.query.graph.enabled",havingValue="true")
class GraphConfiguration {
 @Bean GraphProjectionInbox graphProjectionInbox(DataSource source){return new GraphProjectionInbox(source);}
 static Properties kafka(String bootstrap,String directory)throws Exception{
  var options=new Properties();options.put("bootstrap.servers",bootstrap);options.put("group.id",GraphProjectionConsumer.GROUP);
  options.put("enable.auto.commit","false");options.put("auto.offset.reset","none");options.put("max.poll.records","10");options.put("max.poll.interval.ms","600000");
  options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());
  options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of(directory,"ca.crt")));
  options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of(directory,"user.crt")));options.put("ssl.keystore.key",Files.readString(Path.of(directory,"user.key")));return options;
 }
 static String truststore(String directory)throws Exception{
  var store=KeyStore.getInstance("PKCS12");store.load(null,null);
  try(var input=Files.newInputStream(Path.of(directory,"ca.crt"))){int n=0;for(var cert:CertificateFactory.getInstance("X.509").generateCertificates(input))store.setCertificateEntry("ca-"+n++,cert);}
  String password=Files.readString(Path.of(directory,"password")).strip();var output=Files.createTempFile(Path.of("/tmp"),"graph-trust-",".p12");
  try(var stream=Files.newOutputStream(output)){store.store(stream,password.toCharArray());}return output.toString();
 }
 static NoteGraphStore open(String host,String directory,String truststore){
  try{return new NoteGraphStore(NoteGraphStore.open(host,Files.readString(Path.of(directory,"username")).strip(),Files.readString(Path.of(directory,"password")).strip(),truststore,Files.readString(Path.of(directory,"password")).strip()));}
  catch(java.io.IOException e){throw new IllegalStateException("Graph credentials unavailable");}
 }
 @Bean GraphProjectionConsumer graphProjectionConsumer(GraphProjectionInbox inbox,MeterRegistry metrics,
  @Value("${brain.query.graph.host:brain-cassandra.brain-data.svc}") String host,
  @Value("${brain.query.graph.credentials-directory:/graph-projector}") String directory,
  @Value("${brain.query.kafka.bootstrap-servers}") String bootstrap,
  @Value("${brain.query.graph.kafka-directory:/graph-kafka}") String kafkaDirectory)throws Exception{
   String trust=truststore(directory);return new GraphProjectionConsumer(inbox,()->open(host,directory,trust),kafka(bootstrap,kafkaDirectory),metrics);
 }
 @Bean GraphReadClient graphReadClient(GraphProjectionConsumer consumer){return consumer::read;}
 @Bean HealthIndicator graphProjectionHealthIndicator(GraphProjectionConsumer consumer){return ()->consumer.isReady()?Health.up().build():Health.down().build();}
}
