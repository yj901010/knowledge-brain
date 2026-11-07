package io.knowledgebrain.planning;
import java.util.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.beans.factory.annotation.Value;import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;
@RestController @ConditionalOnProperty(name="brain.planning.dispatch-export.enabled",havingValue="true")
final class ReminderDispatchExportController {
 private final ReminderDeliveryStore store;private final byte[] key;
 ReminderDispatchExportController(ReminderDeliveryStore store,@Value("${brain.planning.dispatch-export.key}")String key){if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Dedicated export key required");this.store=store;this.key=("Bearer "+key).getBytes(StandardCharsets.US_ASCII);}
 @GetMapping("/internal/planning/dispatched-reminders") ResponseEntity<ReminderDeliveryStore.ExportPage> export(HttpServletRequest r){var h=Collections.list(r.getHeaders("Authorization"));if(!r.isSecure()||h.size()!=1||!MessageDigest.isEqual(key,h.getFirst().getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);if(r.getHeader("Cookie")!=null)throw new ResponseStatusException(HttpStatus.FORBIDDEN);GoalController.parameters(r,Set.of("after","limit"));var after=r.getParameter("after")==null?null:ScheduleRules.id(r.getParameter("after"));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.export(after,(int)GoalController.number(r.getParameter("limit"),50,100)));}
}
