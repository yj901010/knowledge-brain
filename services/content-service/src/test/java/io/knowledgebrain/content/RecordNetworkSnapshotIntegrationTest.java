package io.knowledgebrain.content;
import java.util.*;import java.time.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import org.postgresql.ds.PGSimpleDataSource;import org.springframework.jdbc.core.JdbcTemplate;
import org.flywaydb.core.Flyway;import org.springframework.mock.web.MockHttpServletRequest;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_CONTENT_DATABASE_URL",matches=".+")
class RecordNetworkSnapshotIntegrationTest {
 PGSimpleDataSource ds;JdbcTemplate sql;String schema;BrainSnapshotController api;RecordConceptStore links;BookmarkStore bookmarks;QuestionStore questions;DecisionStore decisions;
 UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space=ids.next(),actor=ids.next();String trace="a".repeat(32);
 @BeforeEach void setup(){ds=new PGSimpleDataSource();ds.setURL(System.getenv("BRAIN_TEST_CONTENT_DATABASE_URL"));ds.setUser("brain_content");ds.setPassword(System.getenv("BRAIN_TEST_CONTENT_DATABASE_PASSWORD"));schema="record_feed_test_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(ds).execute("CREATE SCHEMA "+schema);ds.setCurrentSchema(schema);Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();sql=new JdbcTemplate(ds);api=new BrainSnapshotController(ds,"a".repeat(43));var clock=new AdvancingTestClock();links=new RecordConceptStore(ds,clock);bookmarks=new BookmarkStore(ds,clock);questions=new QuestionStore(ds,clock);decisions=new DecisionStore(ds,clock);}
 @AfterEach void cleanup(){sql.execute("DROP SCHEMA "+schema+" CASCADE");}
 BrainSnapshotController.Page page(String kind,UUID after,UUID through){var request=new MockHttpServletRequest();request.setSecure(true);request.addHeader("Authorization","Bearer "+"a".repeat(43));request.addParameter("kind",kind);request.addParameter("limit","5");if(after!=null)request.addParameter("after",after.toString());if(through!=null)request.addParameter("through",through.toString());var response=api.read(request);assertEquals("no-store",response.getHeaders().getCacheControl());return response.getBody();}
 String key(){return UUID.randomUUID().toString().replace("-","");}
 UUID create(RecordConceptStore.Kind kind){return switch(kind){case BOOKMARK->bookmarks.create(space,actor,key(),new BookmarkStore.Input("Saved","https://example.test/","Memo"),trace).bookmark().bookmarkId();case QUESTION->questions.create(space,actor,key(),new QuestionStore.Input("Question","Context","","OPEN"),trace).question().questionId();case DECISION->decisions.create(space,actor,key(),new DecisionStore.Input("Decision","Context","Outcome","Reason","Alternative","DECIDED"),trace).decision().decisionId();};}
 void retire(RecordConceptStore.Kind kind,UUID id){switch(kind){case BOOKMARK->bookmarks.change(space,id,actor,1,null,true,trace);case QUESTION->questions.change(space,id,actor,1,null,true,trace);case DECISION->decisions.change(space,id,actor,1,null,true,trace);}}
 @Test void allSixFeedsAreBoundedAndPreserveArchivedAssociationRevisionInsteadOfInventingTombstones()throws Exception{
  for(var kind:RecordConceptStore.Kind.values()){
   assertNull(page(kind.name(),null,null).through());var records=new ArrayList<UUID>();var target=ids.next();
   for(int i=0;i<7;i++){var record=create(kind);records.add(record);links.replace(space,kind,record,actor,0,List.of(target),trace);}
   var first=page(kind.name(),null,null);assertEquals(5,first.items().size());assertNotNull(first.nextAfter());create(kind);var last=page(kind.name(),first.nextAfter(),first.through());assertEquals(2,last.items().size());assertNull(last.nextAfter());
   var head=links.get(space,kind,records.getFirst());retire(kind,records.getFirst());var source=page(kind.name(),null,null).items().getFirst();assertEquals(records.getFirst(),source.entityId());assertEquals(2,source.version());
   var relation=page(kind.name()+"_CONCEPTS",null,null);assertEquals(5,relation.items().size());var row=relation.items().getFirst();assertEquals(records.getFirst(),row.entityId());assertEquals(1,row.version());
   var e=io.knowledgebrain.contracts.content.recordconcepts.v1.RecordConceptEventEnvelopeV1.parseFrom(Base64.getDecoder().decode(row.payload()));var p=io.knowledgebrain.contracts.content.recordconcepts.v1.RecordConceptsChangedPayloadV1.parseFrom(e.getPayload());assertEquals(head.links().associationId().toString(),e.getAggregateId());assertEquals("ACTIVE",p.getRecordState());assertEquals(1,p.getRecordVersion());assertEquals(kind.name(),p.getRecordType());assertEquals(target.toString(),p.getLinks(0).getConceptId());
   assertEquals(row.payload(),page(kind.name()+"_CONCEPTS",null,null).items().getFirst().payload());
   var wire=Base64.getDecoder().decode(source.payload());String state=switch(kind){case BOOKMARK->io.knowledgebrain.contracts.content.bookmark.v1.BookmarkChangedPayloadV1.parseFrom(io.knowledgebrain.contracts.content.bookmark.v1.BookmarkEventEnvelopeV1.parseFrom(wire).getPayload()).getState();case QUESTION->io.knowledgebrain.contracts.content.question.v1.QuestionChangedPayloadV1.parseFrom(io.knowledgebrain.contracts.content.question.v1.QuestionEventEnvelopeV1.parseFrom(wire).getPayload()).getState();case DECISION->io.knowledgebrain.contracts.content.decision.v1.DecisionChangedPayloadV1.parseFrom(io.knowledgebrain.contracts.content.decision.v1.DecisionEventEnvelopeV1.parseFrom(wire).getPayload()).getState();};assertEquals("RETIRED",state);
  }
 }
}
