package io.knowledgebrain.content;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_CONTENT_DATABASE_URL",matches=".+")
class PropertyNotesStoreIntegrationTest {
    PGSimpleDataSource source;JdbcTemplate jdbc;NoteStore store;final Clock clock=new AdvancingTestClock();final UuidV7 ids=new UuidV7(clock);final String trace="e".repeat(32);
    @BeforeEach void prepare(){source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_CONTENT_DATABASE_URL"));source.setUser("brain_content");source.setPassword(System.getenv("BRAIN_TEST_CONTENT_DATABASE_PASSWORD"));Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);store=new NoteStore(source,clock);}
    UUID assign(UUID space,UUID note,UUID actor,long version,String name,String type,String value){return store.metadata(space,note,actor,version,new MetadataInput.Edit(List.of(),List.of(new MetadataInput.Field(name,type,value))),trace).properties().getFirst().propertyId();}
    @Test void keysetsReturnCurrentPrivateValuesAndExcludeRetiredUnassignedAndForeignNotes(){
        UUID space=ids.next(),actor=ids.next(),foreign=ids.next(),property=null;var expected=new ArrayList<UUID>();
        for(int i=0;i<22;i++){var n=store.create(space,actor,"Repeated title","body",trace);property=assign(space,n.noteId(),actor,0,"score","NUMBER",String.valueOf(i));expected.add(n.noteId());}
        var daily=store.openDaily(space,"2026-10-22","Asia/Seoul",actor,trace).entry().note();assign(space,daily.noteId(),actor,0,"score","NUMBER","3.125");expected.add(daily.noteId());
        var retired=store.create(space,actor,"Retired","",trace);assign(space,retired.noteId(),actor,0,"score","NUMBER","0");store.change(space,retired.noteId(),actor,1,null,null,true,trace);
        store.create(space,actor,"Unassigned","",trace);var other=store.create(foreign,actor,"Foreign secret","",trace);UUID foreignProperty=assign(foreign,other.noteId(),actor,0,"score","NUMBER","4");assertThat(foreignProperty).isNotEqualTo(property);
        long events=events(space);var first=store.propertyNotes(space,property,null,20);var tail=store.propertyNotes(space,property,first.nextAfter(),20);var actual=new ArrayList<UUID>();first.items().forEach(n->actual.add(n.noteId()));tail.items().forEach(n->actual.add(n.noteId()));assertThat(actual).containsExactlyElementsOf(expected);assertThat(first.items()).hasSize(20);assertThat(tail.items()).hasSize(3);assertThat(tail.nextAfter()).isNull();assertThat(first.type()).isEqualTo("NUMBER");assertThat(first.name()).isEqualTo("score");assertThat(first.propertyId()).isEqualTo(property);
        for(int i=0;i<20;i++){assertThat(first.items().get(i).value().toString()).isEqualTo(String.valueOf(i));assertThat(first.items().get(i).metadataVersion()).isEqualTo(1);}
        store=new NoteStore(source,clock);assertThat(store.propertyNotes(space,property,null,20)).isEqualTo(first);assertThat(store.propertyNotes(space,property,expected.getLast(),20).items()).isEmpty();
        assertThatThrownBy(()->store.propertyNotes(space,foreignProperty,null,20)).isInstanceOf(ResponseStatusException.class);assertThatThrownBy(()->store.propertyNotes(space,ids.next(),null,20)).isInstanceOf(ResponseStatusException.class);assertThat(events(space)).isEqualTo(events);
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname=current_schema() AND indexname='note_property_lookup'",String.class)).contains("space_id, property_id, note_id");
    }
    @Test void fiveTypesKeepExactDecimalAndEmptyValuesAcrossSerializationAndRestart(){
        UUID space=ids.next(),actor=ids.next();var n=store.create(space,actor,"Typed","body",trace);String decimal="12345678901234567890.123456789012345678";
        var fields=List.of(new MetadataInput.Field("score","NUMBER",decimal),new MetadataInput.Field("done","BOOLEAN","false"),new MetadataInput.Field("day","DATE","\"2024-02-29\""),new MetadataInput.Field("text","STRING","\"\""),new MetadataInput.Field("list","STRING_LIST","[]"));
        var saved=store.metadata(space,n.noteId(),actor,0,new MetadataInput.Edit(List.of(),fields),trace);long events=events(space);store=new NoteStore(source,clock);
        for(var definition:saved.properties()){
            var page=store.propertyNotes(space,definition.propertyId(),null,20);assertThat(page.items()).hasSize(1);assertThat(page.items().getFirst().value()).isEqualTo(definition.value());assertThat(page.type()).isEqualTo(definition.type());
            if(definition.type().equals("NUMBER"))assertThat(new JsonMapper().writeValueAsString(page)).contains(decimal);
        }
        assertThat(events(space)).isEqualTo(events);assertThat(store.get(space,n.noteId()).version()).isEqualTo(1);assertThat(store.metadataHistory(space,n.noteId(),100,10)).hasSize(1);
    }
    @Test void currentValueVersionAndMembershipFollowEditsRemovalAndRetirement(){
        UUID space=ids.next(),actor=ids.next();var n=store.create(space,actor,"Original","body",trace);UUID property=assign(space,n.noteId(),actor,0,"score","NUMBER","1");store.change(space,n.noteId(),actor,1,"Latest","updated",false,trace);assign(space,n.noteId(),actor,1,"score","NUMBER","2.5");
        var row=store.propertyNotes(space,property,null,20).items().getFirst();assertThat(row.title()).isEqualTo("Latest");assertThat(row.version()).isEqualTo(2);assertThat(row.metadataVersion()).isEqualTo(2);assertThat(row.value().toString()).isEqualTo("2.5");
        store.metadata(space,n.noteId(),actor,2,new MetadataInput.Edit(List.of(),List.of()),trace);assertThat(store.propertyNotes(space,property,null,20).items()).isEmpty();assertThat(assign(space,n.noteId(),actor,3,"score","NUMBER","0")).isEqualTo(property);
        store.change(space,n.noteId(),actor,2,null,null,true,trace);assertThat(store.propertyNotes(space,property,null,20).items()).isEmpty();assertThat(store.definitions(space,false,null,20).items()).hasSize(1).allSatisfy(v->assertThat(v.activeNoteCount()).isZero());assertThat(store.history(space,n.noteId(),100,10)).hasSize(3);assertThat(store.metadataHistory(space,n.noteId(),100,10)).hasSize(5);
    }
    @Test void filteringHappensBeforeKeysetPagingAndUsesExactDecimalEquality(){
        UUID space=ids.next(),actor=ids.next(),property=null;var expected=new ArrayList<UUID>();String exact="12345678901234567890.123456789012345678",near="12345678901234567890.123456789012345679";
        for(int i=0;i<24;i++){var n=store.create(space,actor,"Matching "+i,"",trace);property=assign(space,n.noteId(),actor,0,"score","NUMBER",exact);if(i<22)expected.add(n.noteId());else store.change(space,n.noteId(),actor,1,null,null,true,trace);var miss=store.create(space,actor,"Near value","",trace);assign(space,miss.noteId(),actor,0,"score","NUMBER",near);}
        long events=events(space);var first=store.propertyNotes(space,property,null,20,exact);var tail=store.propertyNotes(space,property,first.nextAfter(),20,exact);assertThat(first.items().stream().map(NoteMetadataStore.PropertyNote::noteId)).containsExactlyElementsOf(expected.subList(0,20));assertThat(tail.items().stream().map(NoteMetadataStore.PropertyNote::noteId)).containsExactlyElementsOf(expected.subList(20,22));assertThat(tail.nextAfter()).isNull();assertThat(store.propertyNotes(space,property,null,100,"0").items()).isEmpty();assertThat(events(space)).isEqualTo(events);
        var zero=store.create(space,actor,"Zero","",trace);assign(space,zero.noteId(),actor,0,"score","NUMBER","0");assertThat(store.propertyNotes(space,property,null,20,"-0.000").items().getFirst().noteId()).isEqualTo(zero.noteId());
        store.metadata(space,expected.getFirst(),actor,1,new MetadataInput.Edit(List.of(),List.of()),trace);assertThat(store.propertyNotes(space,property,null,100,exact).items()).hasSize(21);
    }
    @Test void literalStringBooleanDateAndListConditionsStayTypedAndPrivate(){
        UUID space=ids.next(),actor=ids.next();var n=store.create(space,actor,"Typed","",trace);String literal=" %_ '한글+&\n ";var json=new JsonMapper();var fields=List.of(new MetadataInput.Field("text","STRING",json.writeValueAsString(literal)),new MetadataInput.Field("done","BOOLEAN","false"),new MetadataInput.Field("day","DATE","\"2024-02-29\""),new MetadataInput.Field("list","STRING_LIST","[\"\",\"whole item\",\"other\"]"));
        var saved=store.metadata(space,n.noteId(),actor,0,new MetadataInput.Edit(List.of(),fields),trace);long events=events(space);
        for(var property:saved.properties()){
            String value=switch(property.type()){case "STRING"->literal;case "BOOLEAN"->"false";case "DATE"->"2024-02-29";default->"whole item";};assertThat(store.propertyNotes(space,property.propertyId(),null,20,value).items()).hasSize(1);
            String miss=switch(property.type()){case "STRING"->literal.trim();case "BOOLEAN"->"true";case "DATE"->"2024-03-01";default->"whole";};assertThat(store.propertyNotes(space,property.propertyId(),null,20,miss).items()).isEmpty();
            if(property.type().equals("STRING_LIST"))assertThat(store.propertyNotes(space,property.propertyId(),null,20,"").items()).hasSize(1);
            assertThatThrownBy(()->store.propertyNotes(ids.next(),property.propertyId(),null,20,value)).isInstanceOf(ResponseStatusException.class);
        }
        assertThat(events(space)).isEqualTo(events);assertThat(store.metadataHistory(space,n.noteId(),100,10)).hasSize(1);
    }
    long events(UUID space){return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid IN (SELECT note_id FROM note WHERE space_id=?)",Long.class,space);}

    @Test void numericRangesFilterBeforePagingAndKeepBothEndpointsExact(){
        UUID space=ids.next(),actor=ids.next(),property=null;var expected=new ArrayList<UUID>();String low="12345678901234567890.123456789012345678",high="12345678901234567890.123456789012345679",outside="12345678901234567890.123456789012345680";
        for(int i=0;i<24;i++){var n=store.create(space,actor,"Range match "+i,"",trace);property=assign(space,n.noteId(),actor,0,"range","NUMBER",i%2==0?low:high);if(i<22)expected.add(n.noteId());else store.change(space,n.noteId(),actor,1,null,null,true,trace);var miss=store.create(space,actor,"Just outside","",trace);assign(space,miss.noteId(),actor,0,"range","NUMBER",outside);}
        long before=events(space);var first=store.propertyNotes(space,property,null,20,null,low,high);var tail=store.propertyNotes(space,property,first.nextAfter(),20,null,low,high);
        assertThat(first.items().stream().map(NoteMetadataStore.PropertyNote::noteId)).containsExactlyElementsOf(expected.subList(0,20));assertThat(tail.items().stream().map(NoteMetadataStore.PropertyNote::noteId)).containsExactlyElementsOf(expected.subList(20,22));assertThat(tail.nextAfter()).isNull();
        assertThat(store.propertyNotes(space,property,null,100,null,high,null).items()).hasSize(35);assertThat(store.propertyNotes(space,property,null,100,null,null,low).items()).hasSize(11);assertThat(store.propertyNotes(space,property,null,100,null,"1", "1.0").items()).isEmpty();
        UUID target=property;assertThatThrownBy(()->store.propertyNotes(ids.next(),target,null,20,null,low,high)).isInstanceOf(ResponseStatusException.class);assertThatThrownBy(()->store.propertyNotes(space,target,null,20,null,high,low)).isInstanceOf(ResponseStatusException.class);assertThat(events(space)).isEqualTo(before);
        store.metadata(space,expected.getFirst(),actor,1,new MetadataInput.Edit(List.of(),List.of()),trace);store=new NoteStore(source,clock);assertThat(store.propertyNotes(space,property,null,100,null,low,high).items()).hasSize(21);
    }
    @Test void dateNegativeAndZeroBoundsAreOrderedByValueAndUnsupportedTypesFail(){
        UUID space=ids.next(),actor=ids.next(),day=null,number=null;var dateIds=new ArrayList<UUID>();
        for(String date:List.of("2024-02-28","2024-02-29","2024-03-01","2024-03-02")){var n=store.create(space,actor,date,"",trace);dateIds.add(n.noteId());day=assign(space,n.noteId(),actor,0,"day","DATE","\""+date+"\"");}
        assertThat(store.propertyNotes(space,day,null,20,null,"2024-02-29","2024-03-01").items().stream().map(NoteMetadataStore.PropertyNote::noteId)).containsExactly(dateIds.get(1),dateIds.get(2));assertThat(store.propertyNotes(space,day,null,20,null,null,"2024-02-29").items()).hasSize(2);assertThat(store.propertyNotes(space,day,null,20,null,"2024-03-01",null).items()).hasSize(2);
        for(String value:List.of("-10","-2","0","0.000000000000000001","80")){var n=store.create(space,actor,value,"",trace);number=assign(space,n.noteId(),actor,0,"number","NUMBER",value);}
        assertThat(store.propertyNotes(space,number,null,20,null,"-10","-0.000").items()).hasSize(3);assertThat(store.propertyNotes(space,number,null,20,null,"8e1","80.0").items()).hasSize(1);
        var n=store.create(space,actor,"text","",trace);UUID text=assign(space,n.noteId(),actor,0,"text","STRING","\"80\"");assertThatThrownBy(()->store.propertyNotes(space,text,null,20,null,"1",null)).isInstanceOf(ResponseStatusException.class);
        UUID d=day;assertThatThrownBy(()->store.propertyNotes(space,d,null,20,null,null,"2025-02-29")).isInstanceOf(ResponseStatusException.class);
    }

    @Test void combinedTagAndPropertyPredicatesRunBeforeCursorAndLimitWithoutReadSideEffects(){
        UUID space=ids.next(),actor=ids.next(),property=null,tag=null;var expected=new ArrayList<UUID>();String exact="12345678901234567890.123456789012345678",near="12345678901234567890.123456789012345679";
        for(int i=0;i<24;i++){
            var n=store.create(space,actor,"Combined "+i,"",trace);var saved=store.metadata(space,n.noteId(),actor,0,new MetadataInput.Edit(List.of("study","second"),List.of(new MetadataInput.Field("score","NUMBER",exact))),trace);property=saved.properties().getFirst().propertyId();tag=saved.tags().stream().filter(t->t.name().equals("study")).findFirst().orElseThrow().tagId();if(i<22)expected.add(n.noteId());else store.change(space,n.noteId(),actor,1,null,null,true,trace);
            var miss=store.create(space,actor,"Wrong tag","",trace);store.metadata(space,miss.noteId(),actor,0,new MetadataInput.Edit(List.of("other"),List.of(new MetadataInput.Field("score","NUMBER",exact))),trace);
            var value=store.create(space,actor,"Wrong value","",trace);store.metadata(space,value.noteId(),actor,0,new MetadataInput.Edit(List.of("study"),List.of(new MetadataInput.Field("score","NUMBER",near))),trace);
        }
        long before=events(space);var first=store.propertyNotes(space,property,null,20,null,exact,exact,tag);var tail=store.propertyNotes(space,property,first.nextAfter(),20,exact,null,null,tag);assertThat(first.tagId()).isEqualTo(tag);assertThat(first.items().stream().map(NoteMetadataStore.PropertyNote::noteId)).containsExactlyElementsOf(expected.subList(0,20));assertThat(tail.items().stream().map(NoteMetadataStore.PropertyNote::noteId)).containsExactlyElementsOf(expected.subList(20,22));assertThat(tail.nextAfter()).isNull();assertThat(store.propertyNotes(space,property,null,100,null,null,null,tag).items()).hasSize(46);assertThat(events(space)).isEqualTo(before);
        store.metadata(space,expected.getFirst(),actor,1,new MetadataInput.Edit(List.of(),List.of(new MetadataInput.Field("score","NUMBER",exact))),trace);store=new NoteStore(source,clock);assertThat(store.propertyNotes(space,property,null,100,exact,null,null,tag).items()).hasSize(21);
    }
    @Test void unusedUnknownAndForeignTagsRemainDistinctAndTypedConditionsStillApply(){
        UUID space=ids.next(),actor=ids.next(),foreign=ids.next();var n=store.create(space,actor,"Typed tagged","",trace);var fields=List.of(new MetadataInput.Field("done","BOOLEAN","false"),new MetadataInput.Field("day","DATE","\"2024-02-29\""),new MetadataInput.Field("list","STRING_LIST","[\"\",\"whole\"]"),new MetadataInput.Field("text","STRING","\"\""));var saved=store.metadata(space,n.noteId(),actor,0,new MetadataInput.Edit(List.of("study"),fields),trace);UUID tag=saved.tags().getFirst().tagId();
        var unusedNote=store.create(space,actor,"Unused","",trace);UUID unused=store.metadata(space,unusedNote.noteId(),actor,0,new MetadataInput.Edit(List.of("unused"),List.of()),trace).tags().getFirst().tagId();store.metadata(space,unusedNote.noteId(),actor,1,new MetadataInput.Edit(List.of(),List.of()),trace);
        var other=store.create(foreign,actor,"Foreign","",trace);UUID foreignTag=store.metadata(foreign,other.noteId(),actor,0,new MetadataInput.Edit(List.of("study"),List.of()),trace).tags().getFirst().tagId();long before=events(space);
        for(var field:saved.properties()){
            String value=switch(field.type()){case "BOOLEAN"->"false";case "DATE"->"2024-02-29";default->"";};assertThat(store.propertyNotes(space,field.propertyId(),null,20,value,null,null,tag).items()).hasSize(1);assertThat(store.propertyNotes(space,field.propertyId(),null,20,value,null,null,unused).items()).isEmpty();
            assertThatThrownBy(()->store.propertyNotes(space,field.propertyId(),null,20,value,null,null,foreignTag)).isInstanceOf(ResponseStatusException.class);assertThatThrownBy(()->store.propertyNotes(space,field.propertyId(),null,20,value,null,null,ids.next())).isInstanceOf(ResponseStatusException.class);
            if(field.type().equals("DATE"))assertThat(store.propertyNotes(space,field.propertyId(),null,20,null,"2024-02-29","2024-03-01",tag).items()).hasSize(1);
        }assertThat(events(space)).isEqualTo(before);assertThat(store.metadataHistory(space,n.noteId(),100,10)).hasSize(1);
    }
}
