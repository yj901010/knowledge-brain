package io.knowledgebrain.planning;

import java.io.IOException;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.knowledgebrain.planning.GoalController.*;

@RestController
@ConditionalOnProperty(name="brain.planning.api.enabled",havingValue="true")
@RequestMapping("/api/spaces/{spaceId}/reminders")
final class ReminderController {
    private final ReminderStore store;
    private final SpaceAuthorizationClient authority;
    private final MeterRegistry metrics;
    private final String origin;
    private final JsonMapper json=JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    ReminderController(ReminderStore store, SpaceAuthorizationClient authority, MeterRegistry metrics,
                       @Value("${brain.planning.browser-origin}") String origin) {
        this.store=store; this.authority=authority; this.metrics=metrics; this.origin=origin;
    }
    static String tag(long version) { return "\"rm"+version+"\""; }
    static long version(HttpServletRequest r) {
        var h=Collections.list(r.getHeaders("If-Match"));
        if(h.isEmpty()) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match required");
        if(h.size()!=1||!h.getFirst().matches("\"rm[1-9][0-9]{0,18}\"")) throw ScheduleRules.bad();
        try { return Long.parseLong(h.getFirst().substring(3,h.getFirst().length()-1)); }
        catch(NumberFormatException e) { throw ScheduleRules.bad(); }
    }
    private UUID authorize(UUID space,HttpServletRequest r,boolean write,String trace) {
        if(!r.isSecure()) throw ScheduleRules.bad();
        if(!r.getMethod().equals("GET")&&(Collections.list(r.getHeaders("Origin")).size()!=1
                ||!origin.equals(r.getHeader("Origin"))||r.getHeader("Sec-Fetch-Site")!=null
                &&!r.getHeader("Sec-Fetch-Site").equals("same-origin")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Same-origin request required");
        String token=null;
        if(r.getCookies()!=null) for(var cookie:r.getCookies()) if(cookie.getName().equals("__Host-brain_guest")) {
            if(token!=null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Ambiguous session");
            token=cookie.getValue();
        }
        return authority.authorize(space,token,write?"SCHEDULE_WRITE":"SCHEDULE_READ","PRIVATE",trace);
    }
    private JsonNode body(HttpServletRequest r) throws IOException {
        if(!MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(Optional.ofNullable(r.getContentType()).orElse("").split(";",2)[0].strip()))
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        var bytes=r.getInputStream().readNBytes(262145);
        if(bytes.length>262144) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        try { var v=json.readTree(bytes); if(v==null||!v.isObject()) throw ScheduleRules.bad(); return v; }
        catch(RuntimeException e) { throw ScheduleRules.bad(); }
    }
    private ReminderRules.Input input(JsonNode v) {
        if(!v.isObject()||v.size()!=3||!v.path("scheduleId").isString()
                ||!v.path("scheduleVersion").isIntegralNumber()||!v.path("scheduleVersion").canConvertToLong()
                ||!v.path("minutesBefore").isIntegralNumber()||!v.path("minutesBefore").canConvertToInt()) throw ScheduleRules.bad();
        var input=new ReminderRules.Input(ScheduleRules.id(v.path("scheduleId").asString()),v.path("scheduleVersion").asLong(),v.path("minutesBefore").asInt());
        ReminderRules.input(input); return input;
    }
    private String hash(JsonNode v) {
        if(v.size()!=2||!v.path("previewHash").isString()) throw ScheduleRules.bad();
        String hash=v.path("previewHash").asString(); RecurrenceRules.hash(hash); return hash;
    }
    private void readMetric(String type) { metrics.counter("brain.planning.reminder.reads","type",type).increment(); }
    private void writeMetric(String type) { metrics.counter("brain.planning.reminder.changes","type",type).increment(); }
    private ResponseEntity<ReminderStore.Reminder> response(ReminderStore.Reminder v) {
        return ResponseEntity.ok().eTag(tag(v.version())).cacheControl(CacheControl.noStore()).body(v);
    }
    @PostMapping("/preview") ResponseEntity<ReminderRules.Preview> preview(@PathVariable String spaceId,HttpServletRequest r) throws IOException {
        parameters(r,Set.of()); var space=ScheduleRules.id(spaceId); authorize(space,r,false,parent(r));
        var result=store.preview(space,input(body(r))); readMetric("preview");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    @PostMapping ResponseEntity<ReminderStore.Reminder> create(@PathVariable String spaceId,HttpServletRequest r) throws IOException {
        parameters(r,Set.of()); var space=ScheduleRules.id(spaceId); var trace=parent(r); var actor=authorize(space,r,true,trace);
        var keys=Collections.list(r.getHeaders("Idempotency-Key"));
        if(keys.size()!=1||!keys.getFirst().matches("[0-9a-f]{32}")) throw ScheduleRules.bad();
        var body=body(r); var hash=hash(body);
        var result=store.create(space,actor,keys.getFirst(),input(body.path("input")),hash,trace.substring(3,35));
        var v=result.reminder(); writeMetric(result.replay()?"replayed":"created");
        return ResponseEntity.status(result.replay()?200:201).header("Location","/api/spaces/"+space+"/reminders/"+v.reminderId())
                .eTag(tag(v.version())).cacheControl(CacheControl.noStore()).body(v);
    }
    @GetMapping ResponseEntity<ReminderStore.Page> list(@PathVariable String spaceId,HttpServletRequest r) {
        parameters(r,Set.of("state","scheduleId","after","limit")); var space=ScheduleRules.id(spaceId); authorize(space,r,false,parent(r));
        var page=store.list(space,Optional.ofNullable(r.getParameter("state")).orElse("ACTIVE"),
                Optional.ofNullable(r.getParameter("scheduleId")).orElse("ALL"),r.getParameter("after"),(int)number(r.getParameter("limit"),20,100));
        readMetric("catalog"); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }
    @GetMapping("/{reminderId}") ResponseEntity<ReminderStore.Reminder> get(@PathVariable String spaceId,@PathVariable String reminderId,HttpServletRequest r) {
        parameters(r,Set.of()); var space=ScheduleRules.id(spaceId); var id=ScheduleRules.id(reminderId); authorize(space,r,false,parent(r));
        readMetric("current"); return response(store.get(space,id));
    }
    @PutMapping("/{reminderId}") ResponseEntity<ReminderStore.Reminder> update(@PathVariable String spaceId,@PathVariable String reminderId,HttpServletRequest r) throws IOException {
        parameters(r,Set.of()); var space=ScheduleRules.id(spaceId); var id=ScheduleRules.id(reminderId); var trace=parent(r);
        var actor=authorize(space,r,true,trace); long expected=version(r); var body=body(r); var hash=hash(body);
        var v=store.change(space,id,actor,expected,input(body.path("input")),hash,false,trace.substring(3,35));
        writeMetric("updated"); return response(v);
    }
    @DeleteMapping("/{reminderId}") ResponseEntity<Void> retire(@PathVariable String spaceId,@PathVariable String reminderId,HttpServletRequest r) {
        parameters(r,Set.of()); var space=ScheduleRules.id(spaceId); var id=ScheduleRules.id(reminderId); var trace=parent(r);
        var actor=authorize(space,r,true,trace); var v=store.change(space,id,actor,version(r),null,null,true,trace.substring(3,35));
        writeMetric("retired"); return ResponseEntity.noContent().eTag(tag(v.version())).cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/{reminderId}/revisions") ResponseEntity<List<ReminderStore.Revision>> history(@PathVariable String spaceId,@PathVariable String reminderId,HttpServletRequest r) {
        parameters(r,Set.of("before","limit")); var space=ScheduleRules.id(spaceId); var id=ScheduleRules.id(reminderId); authorize(space,r,false,parent(r));
        readMetric("history"); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.history(space,id,
                number(r.getParameter("before"),Long.MAX_VALUE,Long.MAX_VALUE),(int)number(r.getParameter("limit"),5,10)));
    }
}
