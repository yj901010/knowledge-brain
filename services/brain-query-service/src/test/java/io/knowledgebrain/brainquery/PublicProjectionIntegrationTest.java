package io.knowledgebrain.brainquery;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_QUERY_DATABASE_URL",matches=".+")
class PublicProjectionIntegrationTest {
 PublicProjectionStore store;JdbcTemplate sql;UUID space;String schema;
 static UUID id(){return BrainProjectionIntegrationTest.id();}
 @BeforeEach void setup(){var d=new PGSimpleDataSource();d.setURL(System.getenv("BRAIN_TEST_QUERY_DATABASE_URL"));d.setUser("brain_brainquery");d.setPassword(System.getenv("BRAIN_TEST_QUERY_DATABASE_PASSWORD"));schema="public_discovery_test_"+UUID.randomUUID().toString().replace("-","");d.setCurrentSchema(schema);Flyway.configure().dataSource(d).schemas(schema).locations("classpath:db/migration").load().migrate();store=new PublicProjectionStore(d);sql=new JdbcTemplate(d);space=id();}
 @AfterEach void cleanup(){if(schema!=null&&schema.matches("public_discovery_test_[0-9a-f]{32}"))sql.execute("DROP SCHEMA "+schema+" CASCADE");}
 PublicProjection.Event event(UUID id,String kind,long version,String state,String title,String content){return new PublicProjection.Event(id(),id,space,kind,version,state,title,PublicProjection.JSON.readTree(content),PublicProjection.JSON.createObjectNode(),id(),"a".repeat(32),PublicProjection.hash(kind+version+state+title+content));}
 void stage(PublicProjection.Event e){store.stage(e,e.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),true);}
 PublicProjection.Document document(UUID id){return store.document(store.pending(100).stream().filter(k->k.id().equals(id)).findFirst().orElseThrow());}
 @Test void durableInboxRejectsConflictAndStaleEventsDoNotOverwrite(){var entity=id();var first=event(entity,"CONCEPT",2,"ACTIVE","Newest","{}");stage(first);stage(first);stage(event(entity,"CONCEPT",1,"ACTIVE","Old","{}"));assertThat(document(entity).title()).isEqualTo("Newest");assertThatThrownBy(()->stage(event(entity,"CONCEPT",2,"ACTIVE","Poison","{}"))).isInstanceOf(PublicProjectionStore.Conflict.class);assertThatThrownBy(()->store.stage(first,new byte[]{1},true)).isInstanceOf(PublicProjectionStore.Conflict.class);}
 @Test void partialExternalFailureRetainsQueueAndRestartCanRetrySameVersion(){var entity=id();stage(event(entity,"CONCEPT",1,"ACTIVE","Durable","{}"));var keys=store.pending(10);var graph=mock(PublicGraphStore.class);var search=mock(NoteSearchClient.class);doThrow(new NoteSearchClient.Unavailable()).when(search).applyPublic(any());assertThatThrownBy(()->store.materialize(keys,graph,search)).isInstanceOf(NoteSearchClient.Unavailable.class);assertThat(store.pending(10)).isEqualTo(keys);verify(graph).apply(any());reset(search);store=new PublicProjectionStore(sql.getDataSource());store.materialize(store.pending(10),graph,search);assertThat(store.pending(10)).isEmpty();verify(graph,times(2)).apply(any());}
 @Test void aliasAndQualifierRefreshParentWithoutCrossSpaceEnrichment(){var concept=id();var claim=id();stage(event(concept,"CONCEPT",1,"ACTIVE","Parent","{}"));stage(event(claim,"CLAIM",1,"ACTIVE","Claim","{\"subjectId\":\""+concept+"\",\"predicateId\":\""+concept+"\",\"objectType\":\"STRING\",\"objectValue\":\"Literal\"}"));stage(event(id(),"ALIAS",1,"ACTIVE","Searchable Alias","{\"conceptId\":\""+concept+"\",\"label\":\"Alias needle\"}"));assertThat(document(concept).text()).contains("Alias needle");stage(event(id(),"QUALIFIER",1,"ACTIVE","Age scope","{\"claimId\":\""+claim+"\",\"predicateId\":\""+concept+"\",\"valueType\":\"NUMBER\",\"value\":\"18\"}"));assertThat(document(claim).text()).contains("Age scope");assertThat(document(claim).refs()).hasSize(2);}
 @Test void operatorPauseIsDurableAndNoLiveOffsetCanAdvanceThroughIt(){var e=event(id(),"CONCEPT",1,"ACTIVE","Title","{}");assertThat(store.available()).isFalse();assertThatThrownBy(()->store.stage(e,new byte[]{1})).isInstanceOf(PublicProjectionStore.Paused.class);assertThat(store.pending(10)).isEmpty();store.available(true);store.stage(e,new byte[]{1});assertThat(store.pending(10)).hasSize(1);store.available(false);store=new PublicProjectionStore(sql.getDataSource());assertThat(store.available()).isFalse();}
}
