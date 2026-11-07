package io.knowledgebrain.learning;
import java.time.*;
import java.util.*;
import java.sql.Timestamp;
import java.security.MessageDigest;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_LEARNING_DATABASE_URL",matches=".+")
class NoteEvidenceMigrationIntegrationTest {
 String schema;JdbcTemplate sql;
 @AfterEach void cleanup(){if(schema!=null){if(!schema.matches("learning_evidence_test_[0-9a-f]{32}"))throw new IllegalStateException("Invalid fixture schema");sql.execute("DROP SCHEMA "+schema+" CASCADE");}}
 @Test void v3NoteSnapshotsFingerprintsIdsAndEventBytesSurviveV4AndRemainIsolated()throws Exception{
  var ds=new PGSimpleDataSource();ds.setURL(System.getenv("BRAIN_TEST_LEARNING_DATABASE_URL"));ds.setUser("brain_learning");ds.setPassword(System.getenv("BRAIN_TEST_LEARNING_DATABASE_PASSWORD"));schema="learning_evidence_test_"+UUID.randomUUID().toString().replace("-","");ds.setCurrentSchema(schema);sql=new JdbcTemplate(ds);Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration").target("3").load().migrate();var clock=Clock.systemUTC();var ids=new UuidV7(clock);var space=ids.next();var actor=ids.next();var roadmaps=new RoadmapStore(ds,clock);String trace="c".repeat(32);var r=roadmaps.create(space,actor,"Legacy Note evidence","",trace);r=roadmaps.graph(space,r.roadmapId(),actor,1,new RoadmapGraph.Edit(List.of(new RoadmapGraph.NodeEdit("new:a","Legacy node","")),List.of()),trace);var node=r.nodes().getFirst().nodeId();var note=ids.next();var evidence=ids.next();var revision=ids.next();var input=new LearningEvidenceStore.Input(node,note,3,"Legacy raw memo");var now=clock.instant();var value=new LearningEvidenceStore.Evidence(evidence,r.roadmapId(),space,node,"Legacy node","PRIVATE","ACTIVE",1,note,3,"Legacy title",input.memo(),now.toString(),now.toString());var json=JsonMapper.builder().build();String key="a".repeat(32),hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(input)));sql.update("INSERT INTO learning_evidence VALUES (?,?,?,'ACTIVE',1,?,?,?,?)",evidence,r.roadmapId(),node,note,3,key,hash);sql.update("INSERT INTO learning_evidence_revision VALUES (?,?,1,?,?,?::jsonb)",revision,evidence,actor,Timestamp.from(now),json.writeValueAsString(value));var event=ids.next();byte[] bytes={8,1,18,3,111,108,100};sql.update("INSERT INTO outbox_event VALUES (?,'evidence',?,'LearningEvidenceLinked',?,?,'learning','evidence-events','1',?)",event,evidence,Timestamp.from(now),trace,bytes);
  Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration").load().migrate();var notes=new LearningEvidenceStore(ds,clock,roadmaps);var concepts=new ConceptEvidenceStore(ds,clock,roadmaps);assertEquals(value,notes.get(space,r.roadmapId(),evidence));assertEquals(value,notes.history(space,r.roadmapId(),evidence,2,5).getFirst().snapshot());assertEquals(value,notes.record(space,r.roadmapId(),actor,key,input,trace,()->{fail("Legacy retry must not check source again");return null;}).evidence());assertArrayEquals(bytes,sql.queryForObject("SELECT payload FROM outbox_event WHERE id=?",byte[].class,event));assertEquals("NOTE",sql.queryForObject("SELECT kind FROM learning_evidence WHERE evidence_id=?",String.class,evidence));assertTrue(concepts.page(space,r.roadmapId(),null,20).items().isEmpty());var error=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->concepts.get(space,value.roadmapId(),evidence));assertEquals(404,error.getStatusCode().value());assertEquals(1,notes.page(space,r.roadmapId(),null,20).items().size());
 }
}
