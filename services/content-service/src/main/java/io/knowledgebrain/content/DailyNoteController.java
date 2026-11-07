package io.knowledgebrain.content;
import java.util.UUID;
import java.util.Optional;
import jakarta.servlet.http.HttpServletRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
@RestController
@ConditionalOnProperty(name="brain.content.api.enabled",havingValue="true")
@RequestMapping("/api/spaces/{spaceId}/daily-notes")
class DailyNoteController {
 private final NoteStore notes;private final NoteController guard;private final MeterRegistry metrics;
 private final JsonMapper json=JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
 DailyNoteController(NoteStore notes,NoteController guard,MeterRegistry metrics){this.notes=notes;this.guard=guard;this.metrics=metrics;}
 @PostMapping ResponseEntity<DailyNoteStore.Entry> open(@PathVariable UUID spaceId,HttpServletRequest request) throws java.io.IOException {
  String parent=guard.parent(request);UUID actor=guard.authorize(spaceId,request,true,parent);
  if(!MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(Optional.ofNullable(request.getContentType()).orElse("").split(";",2)[0].trim()))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"JSON required");
  byte[] body=request.getInputStream().readNBytes(1025);if(body.length>1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Daily request exceeds 1 KiB");String date,zone;
  try{var node=json.readTree(body);if(!node.isObject() || node.size()!=2 || !node.path("date").isString() || !node.path("timezone").isString())throw new IllegalArgumentException();date=node.path("date").asString();zone=node.path("timezone").asString();}catch(RuntimeException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expected date and timezone strings");}
  var result=notes.openDaily(spaceId,date,zone,actor,parent.substring(3,35));metrics.counter("brain.content.daily.opens","result",result.created()?"created":"existing").increment();var entry=result.entry();return ResponseEntity.status(result.created()?201:200).header("Location","/api/spaces/"+spaceId+"/notes/"+entry.note().noteId()).eTag("\""+entry.note().version()+"\"").cacheControl(CacheControl.noStore()).body(entry);
 }
 @GetMapping("/{date}") ResponseEntity<DailyNoteStore.Entry> get(@PathVariable UUID spaceId,@PathVariable String date,HttpServletRequest request){guard.authorize(spaceId,request,false,guard.parent(request));var entry=notes.daily(spaceId,date);metrics.counter("brain.content.daily.reads","type","entry").increment();return ResponseEntity.ok().eTag("\""+entry.note().version()+"\"").cacheControl(CacheControl.noStore()).body(entry);}
 @GetMapping("/today") ResponseEntity<DailyNoteStore.Today> today(@PathVariable UUID spaceId,@RequestParam(required=false) String timezone,HttpServletRequest request){guard.authorize(spaceId,request,false,guard.parent(request));DailyDates.zone(timezone);metrics.counter("brain.content.daily.reads","type","today").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notes.dailyToday(spaceId,timezone));}
 @GetMapping ResponseEntity<DailyNoteStore.Page> list(@PathVariable UUID spaceId,@RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(required=false) String before,@RequestParam(defaultValue="20") int limit,HttpServletRequest request){guard.authorize(spaceId,request,false,guard.parent(request));metrics.counter("brain.content.daily.reads","type","journal").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notes.dailyList(spaceId,from,to,before,limit));}
}
