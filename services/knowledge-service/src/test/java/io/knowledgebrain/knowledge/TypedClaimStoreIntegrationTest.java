package io.knowledgebrain.knowledge;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_KNOWLEDGE_DATABASE_URL",matches=".+")
class TypedClaimStoreIntegrationTest {
 PGSimpleDataSource db;ClaimStore claims;ConceptStore concepts;SourceStore sources;CitationStore citations;QualifierStore qualifiers;JdbcTemplate jdbc;
 UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space,actor,subject,predicate,target;String trace="c".repeat(32);
 @BeforeEach void setup(){
  db=new PGSimpleDataSource();db.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));db.setUser("brain_knowledge");db.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));Flyway.configure().dataSource(db).locations("classpath:db/migration").load().migrate();
  claims=new ClaimStore(db,Clock.systemUTC());concepts=new ConceptStore(db,Clock.systemUTC());sources=new SourceStore(db,Clock.systemUTC());citations=new CitationStore(db,Clock.systemUTC());qualifiers=new QualifierStore(db,Clock.systemUTC());jdbc=new JdbcTemplate(db);
  space=ids.next();actor=ids.next();subject=concepts.create(space,actor,"Typed subject","",trace).conceptId();predicate=concepts.create(space,actor,"Typed predicate","",trace).conceptId();target=concepts.create(space,actor,"Old target","",trace).conceptId();
 }
 ClaimStore.ObjectValue scalar(String type,String value){return new ClaimStore.ObjectValue(type,null,value);}
 ClaimStore.Claim create(String type,String value){return claims.create(space,subject,actor,predicate,scalar(type,value),trace);}
 void status(int code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(code));}
 @Test void exactValuesAndCanonicalDuplicatesRemainTypedAndPrivate()throws Exception{
  var exact=create("NUMBER","9007199254740993.123456789012345678");assertThat(exact.object().value()).isEqualTo("9007199254740993.123456789012345678");
  var one=create("NUMBER","1.000");assertThat(one.object().value()).isEqualTo("1");status(409,()->create("NUMBER","1.0"));create("STRING","1");
  assertThat(create("NUMBER","-0.00").object().value()).isEqualTo("0");create("DATE","2024-02-29");create("BOOLEAN","false");
  assertThat(create("STRING","😀".repeat(1000)).object().value()).hasSize(2000);
  for(var bad:List.of(scalar("NUMBER","1e3"),scalar("NUMBER","1.1234567890123456789"),scalar("DATE","2025-02-29"),scalar("DATE","0000-01-01"),scalar("BOOLEAN","False"),scalar("STRING","\uD800"),scalar("STRING"," "),scalar("ENUM","x"),new ClaimStore.ObjectValue("NUMBER",target,"1")))status(400,()->claims.create(space,subject,actor,predicate,bad,trace));
  var json=tools.jackson.databind.json.JsonMapper.builder().build();assertThat(json.writeValueAsString(one.object())).isEqualTo("{\"type\":\"NUMBER\",\"value\":\"1\"}");
  assertThat(json.writeValueAsString(new ClaimStore.ObjectValue("CONCEPT",target))).contains("\"conceptId\"").doesNotContain("\"value\"");
  assertThat(one.visibility()).isEqualTo("PRIVATE");assertThat(one.verificationState()).isEqualTo("DRAFT");
 }
 @Test void transitionsPreserveOldCitationQualifierPinsAndRevisionIdentity(){
  var c=claims.create(space,subject,actor,predicate,new ClaimStore.ObjectValue("CONCEPT",target),trace);
  var source=sources.create(space,actor,"Evidence",null,"Original citation","",trace);
  var cite=citations.create(space,subject,c.claimId(),actor,CitationStore.reference(source.sourceId(),1,1),trace);
  var q=qualifiers.create(space,subject,c.claimId(),actor,QualifierStore.edit(predicate,QualifierStore.value("STRING","scope"),1),trace);
  claims.change(space,subject,c.claimId(),actor,1,predicate,scalar("NUMBER","12.500"),false,trace);
  assertThat(citations.get(space,subject,c.claimId(),cite.citationId())).isEqualTo(cite);
  assertThat(qualifiers.get(space,subject,c.claimId(),q.qualifierId())).isEqualTo(q);
  var c2=citations.change(space,subject,c.claimId(),cite.citationId(),actor,1,CitationStore.reference(source.sourceId(),1,2),false,trace);
  var q2=qualifiers.change(space,subject,c.claimId(),q.qualifierId(),actor,1,QualifierStore.edit(predicate,QualifierStore.value("BOOLEAN","true"),2),false,trace);
  assertThat(c2.claim().object()).isEqualTo(scalar("NUMBER","12.5"));assertThat(q2.claim().object()).isEqualTo(c2.claim().object());
  assertThat(citations.bySource(space,source.sourceId(),null,20).items().getFirst().claim().object()).isEqualTo(c2.claim().object());
  concepts.change(space,target,actor,1,null,null,true,trace);assertThat(claims.get(space,subject,c.claimId()).version()).isEqualTo(2);
  assertThat(claims.usages(space,predicate,ClaimStore.UsageRole.PREDICATE,null,20).items().getFirst().objectTitle()).isEqualTo("12.5");
  assertThat(claims.usages(space,target,ClaimStore.UsageRole.OBJECT,null,20).items()).isEmpty();
  claims.change(space,subject,c.claimId(),actor,2,predicate,scalar("DATE","2026-10-20"),false,trace);
  claims.change(space,subject,c.claimId(),actor,3,predicate,new ClaimStore.ObjectValue("CONCEPT",predicate),false,trace);
  var h=claims.history(space,subject,c.claimId(),Long.MAX_VALUE,10);assertThat(h).extracting(v->v.object().type()).containsExactly("CONCEPT","DATE","NUMBER","CONCEPT");
  assertThat(h.getLast().object().conceptId()).isEqualTo(target);assertThat(citations.history(space,subject,c.claimId(),cite.citationId(),Long.MAX_VALUE,10).getLast().claim()).isEqualTo(cite.claim());
  assertThat(qualifiers.history(space,subject,c.claimId(),q.qualifierId(),Long.MAX_VALUE,10).getLast().claim()).isEqualTo(q.claim());
  claims.change(space,subject,c.claimId(),actor,4,null,null,true,trace);status(404,()->claims.get(space,subject,c.claimId()));assertThat(claims.history(space,subject,c.claimId(),Long.MAX_VALUE,10)).hasSize(5);
 }
 @Test void literalDuplicatesAndStaleEditorsSerializeUnderTheSubjectLock()throws Exception{
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();for(String v:List.of("1.00","1.0"))tasks.add(pool.submit(()->{gate.await();try{create("NUMBER",v);return 201;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));gate.countDown();assertThat(List.of(tasks.get(0).get(20,TimeUnit.SECONDS),tasks.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
  }
  var c=claims.list(space,subject,null,20).items().getFirst();
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();for(String v:List.of("2","3"))tasks.add(pool.submit(()->{gate.await();try{claims.change(space,subject,c.claimId(),actor,1,predicate,scalar("NUMBER",v),false,trace);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));gate.countDown();assertThat(List.of(tasks.get(0).get(20,TimeUnit.SECONDS),tasks.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,412);
  }
  assertThat(claims.history(space,subject,c.claimId(),Long.MAX_VALUE,10)).hasSize(2);
 }
 @Test void literalScopeRetirementAndRollbackDoNotWeakenExistingBoundaries()throws Exception{
  var c=create("BOOLEAN","false");status(404,()->claims.get(ids.next(),subject,c.claimId()));status(404,()->claims.create(space,subject,actor,ids.next(),scalar("STRING","x"),trace));
  UUID rollback;try(var connection=db.getConnection()){connection.setAutoCommit(false);var shared=new SingleConnectionDataSource(connection,true);var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(shared));rollback=tx.execute(st->{var v=new ClaimStore(shared,Clock.systemUTC()).create(space,subject,actor,predicate,scalar("STRING","rollback"),trace);st.setRollbackOnly();return v.claimId();});}
  for(String table:List.of("claim","claim_revision"))assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE claim_id=?",Long.class,rollback)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=?",Long.class,rollback)).isZero();
  concepts.change(space,predicate,actor,1,null,null,true,trace);assertThat(claims.list(space,subject,null,20).items()).isEmpty();status(404,()->claims.change(space,subject,c.claimId(),actor,1,null,null,true,trace));assertThat(claims.history(space,subject,c.claimId(),Long.MAX_VALUE,10)).hasSize(1);
 }

 @Test void canonicalSnapshotUsesTypedLiteralBytesAndStableLegacyConceptBytes()throws Exception{
  var api=new KnowledgeSnapshotController(db,"a".repeat(43));
  for(var object:List.of(scalar("NUMBER","9007199254740993.123456789012345678"),new ClaimStore.ObjectValue("CONCEPT",target))){
   var c=claims.create(space,subject,actor,predicate,object,trace);
   var request=new org.springframework.mock.web.MockHttpServletRequest();request.setSecure(true);request.addHeader("Authorization","Bearer "+"a".repeat(43));request.addParameter("kind","CLAIM");request.addParameter("limit","5");request.addParameter("through",c.claimId().toString());
   var previous=new UUID(c.claimId().getMostSignificantBits(),c.claimId().getLeastSignificantBits()-1);request.addParameter("after",previous.toString());
   var page=api.read(request).getBody();assertThat(page.items()).hasSize(1);byte[] wire=Base64.getDecoder().decode(page.items().getFirst().payload());
   var e=io.knowledgebrain.contracts.knowledge.claim.v2.EventEnvelopeV2.parseFrom(wire);var body=io.knowledgebrain.contracts.knowledge.claim.v2.ClaimChangedPayloadV2.parseFrom(e.getPayload());
   assertThat(e.getEventVersion()).isEqualTo(object.type().equals("CONCEPT")?1:2);assertThat(body.getObjectType()).isEqualTo(object.type());assertThat(body.getObjectValue()).isEqualTo(object.value()==null?"":object.value());assertThat(body.getObjectConceptId()).isEqualTo(object.conceptId()==null?"":object.conceptId().toString());
   assertThat(e.getEventId()).isEqualTo(claims.history(space,subject,c.claimId(),Long.MAX_VALUE,5).getFirst().revisionId().toString());assertThat(api.read(request).getBody()).isEqualTo(page);
  }
 }
}
