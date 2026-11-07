package io.knowledgebrain.collaboration;
import java.util.*;import java.io.IOException;import jakarta.servlet.http.HttpServletRequest;import org.springframework.web.bind.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;import io.micrometer.core.instrument.MeterRegistry;
@RestController @ConditionalOnProperty(name="brain.collaboration.api.enabled",havingValue="true")
@RequestMapping("/api/groups/{groupId}/my-assignments")
final class GroupMyAssignmentsController {
 private final GroupMyAssignmentsStore store;private final StudyController gate;private final MeterRegistry metrics;
 GroupMyAssignmentsController(GroupMyAssignmentsStore store,StudyController gate,MeterRegistry metrics){this.store=store;this.gate=gate;this.metrics=metrics;}
 @GetMapping ResponseEntity<GroupMyAssignmentsStore.Page> list(@PathVariable String groupId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of("after","limit","filter"));gate.empty(r);var group=GroupInput.id(groupId);var after=r.getParameter("after")==null?null:GroupInput.id(r.getParameter("after"));int limit=(int)gate.number(r.getParameter("limit"),20,100);String filter=Optional.ofNullable(r.getParameter("filter")).orElse("OPEN_NOT_SUBMITTED");if(!GroupMyAssignmentsStore.FILTERS.contains(filter))throw GroupInput.bad();var a=gate.participant(group,r,false,gate.trace(r));var p=store.list(group,a.space(),a.actor(),after,limit,filter);metrics.counter("brain.collaboration.assignment.reads","type","group-mine").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(p);
 }
}
