package io.knowledgebrain.content;
import java.time.Clock;
import java.util.*;
import jakarta.servlet.http.Cookie;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PropertyNotesApiTest {
    NoteStore store;SpaceAuthorizationClient authority;MetadataDefinitionsController api;SimpleMeterRegistry metrics;
    UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space=ids.next(),tag=ids.next(),actor=ids.next();
    @BeforeEach void prepare(){store=mock(NoteStore.class);authority=mock(SpaceAuthorizationClient.class);metrics=new SimpleMeterRegistry();api=new MetadataDefinitionsController(store,new NoteController(store,authority,metrics,"https://localhost:8443"),metrics);when(authority.authorize(eq(space),eq("a".repeat(43)),eq(false),anyString())).thenReturn(actor);}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/spaces/"+space+"/property-definitions/"+tag+"/notes");r.setSecure(true);r.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)));return r;}
    void status(int code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(code));}
    @Test void eachPageUsesFreshSpaceAuthorityAndNoStoreWithBoundedMetrics(){
        UUID after=ids.next();var page=new NoteMetadataStore.PropertyNotesPage(tag,"score","NUMBER",List.of(),null);when(store.propertyNotes(space,tag,null,20)).thenReturn(page);when(store.propertyNotes(space,tag,after,20)).thenReturn(page);
        var r=request();r.addHeader("X-Principal-Id",ids.next().toString());assertThat(api.propertyNotes(space,tag,null,20,r).getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(api.propertyNotes(space,tag,after,20,r).getBody()).isEqualTo(page);
        verify(authority,times(2)).authorize(eq(space),eq("a".repeat(43)),eq(false),anyString());verify(store).propertyNotes(space,tag,null,20);verify(store).propertyNotes(space,tag,after,20);assertThat(metrics.get("brain.content.metadata.reads").tag("type","property_notes").counter().count()).isEqualTo(2);
    }
    @Test void invalidScopeCursorsLimitsAndUnknownDuplicateEmptyQueriesFailBeforeStore(){
        for(int limit:new int[]{0,-1,101})status(400,()->api.propertyNotes(space,tag,null,limit,request()));
        status(400,()->api.propertyNotes(space,UUID.randomUUID(),null,20,request()));status(400,()->api.propertyNotes(space,tag,UUID.randomUUID(),20,request()));status(400,()->api.propertyNotes(UUID.randomUUID(),tag,null,20,request()));
        for(String key:List.of("state","spaceId","before","search")){var r=request();r.setParameter(key,"spoof");status(400,()->api.propertyNotes(space,tag,null,20,r));status(400,()->api.list(space,null,20,r));}
        var duplicate=request();duplicate.setParameter("limit",new String[]{"20","1"});status(400,()->api.propertyNotes(space,tag,null,20,duplicate));status(400,()->api.list(space,null,20,duplicate));
        var empty=request();empty.setParameter("after","");status(400,()->api.propertyNotes(space,tag,null,20,empty));verifyNoInteractions(store,authority);
    }
    @Test void valueConditionKeepsEmptyDistinctFromMissingAndReauthorizesEveryPage(){
        UUID after=ids.next();var page=new NoteMetadataStore.PropertyNotesPage(tag,"text","STRING",List.of(),null);when(store.propertyNotes(space,tag,null,20,"")).thenReturn(page);when(store.propertyNotes(space,tag,after,20," %_ ")).thenReturn(page);
        var empty=request();empty.setParameter("value","");assertThat(api.propertyNotes(space,tag,null,20,empty).getBody()).isEqualTo(page);var next=request();next.setParameter("value"," %_ ");assertThat(api.propertyNotes(space,tag,after,20,next).getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(authority,times(2)).authorize(eq(space),eq("a".repeat(43)),eq(false),anyString());verify(store).propertyNotes(space,tag,null,20,"");verify(store).propertyNotes(space,tag,after,20," %_ ");assertThat(metrics.get("brain.content.metadata.reads").tag("type","property_notes_filtered").counter().count()).isEqualTo(2);
        when(authority.authorize(eq(space),anyString(),eq(false),anyString())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));status(403,()->api.propertyNotes(space,tag,after,20,next));verify(store,times(1)).propertyNotes(space,tag,after,20," %_ ");
    }
    @Test void duplicateOversizeAndControlValuesAreRejectedAndOtherRoutesRejectValue(){
        var r=request();r.setParameter("value",new String[]{"a","b"});status(400,()->api.propertyNotes(space,tag,null,20,r));
        for(String value:List.of("x".repeat(201),"\u0000","\ud800")){var bad=request();bad.setParameter("value",value);status(400,()->api.propertyNotes(space,tag,null,20,bad));}
        var extra=request();extra.setParameter("value","");status(400,()->api.list(space,null,20,extra));status(400,()->api.notes(space,tag,null,20,extra));verifyNoInteractions(store,authority);
    }
    @Test void denialOutageAndInsecureReadsNeverReachStore(){
        var insecure=request();insecure.setSecure(false);status(400,()->api.propertyNotes(space,tag,null,20,insecure));
        for(var code:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)){when(authority.authorize(eq(space),anyString(),eq(false),anyString())).thenThrow(new ResponseStatusException(code));status(code.value(),()->api.propertyNotes(space,tag,null,20,request()));}
        verifyNoInteractions(store);assertThat(metrics.find("brain.content.metadata.reads").counter()).isNull();
    }

    @Test void eachRangePageUsesFreshAuthorityAndKeepsInclusiveBounds(){
        UUID after=ids.next();var page=new NoteMetadataStore.PropertyNotesPage(tag,"score","NUMBER",List.of(),null);
        when(store.propertyNotes(space,tag,null,20,null,"8e1","100")).thenReturn(page);when(store.propertyNotes(space,tag,after,20,null,"8e1","100")).thenReturn(page);
        var r=request();r.setParameter("minValue","8e1");r.setParameter("maxValue","100");assertThat(api.propertyNotes(space,tag,null,20,r).getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(api.propertyNotes(space,tag,after,20,r).getBody()).isEqualTo(page);
        verify(authority,times(2)).authorize(eq(space),eq("a".repeat(43)),eq(false),anyString());assertThat(metrics.get("brain.content.metadata.reads").tag("type","property_notes_filtered").counter().count()).isEqualTo(2);
        when(authority.authorize(eq(space),anyString(),eq(false),anyString())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));status(403,()->api.propertyNotes(space,tag,after,20,r));verify(store,times(1)).propertyNotes(space,tag,after,20,null,"8e1","100");
    }
    @Test void rangeShapeIsCheckedBeforeAuthorizationAndOtherRoutesDoNotAcceptBounds(){
        for(String key:List.of("minValue","maxValue")){
            var empty=request();empty.setParameter(key,"");status(400,()->api.propertyNotes(space,tag,null,20,empty));
            var duplicate=request();duplicate.setParameter(key,new String[]{"1","2"});status(400,()->api.propertyNotes(space,tag,null,20,duplicate));
            var mixed=request();mixed.setParameter(key,"1");mixed.setParameter("value","");status(400,()->api.propertyNotes(space,tag,null,20,mixed));
            var other=request();other.setParameter(key,"1");status(400,()->api.list(space,null,20,other));status(400,()->api.notes(space,tag,null,20,other));
        }verifyNoInteractions(store,authority);
    }

    @Test void combinedTagAndValuePagesReauthorizeAndEchoCanonicalTagCondition(){
        UUID filter=ids.next(),after=ids.next();var page=new NoteMetadataStore.PropertyNotesPage(tag,"score","NUMBER",List.of(),null,filter);
        when(store.propertyNotes(space,tag,null,20,null,"80","100",filter)).thenReturn(page);when(store.propertyNotes(space,tag,after,20,"80",null,null,filter)).thenReturn(page);
        var range=request();range.setParameter("tagId",filter.toString());range.setParameter("minValue","80");range.setParameter("maxValue","100");var result=api.propertyNotes(space,tag,null,20,range);assertThat(result.getBody().tagId()).isEqualTo(filter);assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        var exact=request();exact.setParameter("tagId",filter.toString());exact.setParameter("value","80");assertThat(api.propertyNotes(space,tag,after,20,exact).getBody()).isEqualTo(page);verify(authority,times(2)).authorize(eq(space),eq("a".repeat(43)),eq(false),anyString());assertThat(metrics.get("brain.content.metadata.reads").tag("type","property_notes_filtered").counter().count()).isEqualTo(2);
        when(authority.authorize(eq(space),anyString(),eq(false),anyString())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));status(403,()->api.propertyNotes(space,tag,after,20,exact));verify(store,times(1)).propertyNotes(space,tag,after,20,"80",null,null,filter);
    }
    @Test void malformedDuplicateAndMisplacedTagConditionsFailBeforeAuthorityOrStore(){
        for(String value:List.of("","bad",UUID.randomUUID().toString(),tag.toString().toUpperCase(),"019a0808-1234-7000-8000-1")){var r=request();r.setParameter("tagId",value);status(400,()->api.propertyNotes(space,tag,null,20,r));}
        var duplicate=request();duplicate.setParameter("tagId",new String[]{tag.toString(),tag.toString()});status(400,()->api.propertyNotes(space,tag,null,20,duplicate));
        var other=request();other.setParameter("tagId",tag.toString());status(400,()->api.list(space,null,20,other));status(400,()->api.notes(space,tag,null,20,other));verifyNoInteractions(store,authority);
    }
}
