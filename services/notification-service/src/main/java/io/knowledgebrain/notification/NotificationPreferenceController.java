package io.knowledgebrain.notification;

import java.io.IOException;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ConditionalOnProperty(name="brain.notification.api.enabled",havingValue="true")
@RequestMapping("/api/spaces/{spaceId}")
final class NotificationPreferenceController {
 private final NotificationStore store;
 private final SpaceAuthorizationClient authority;
 private final String origin;
 private final MeterRegistry metrics;
 private final JsonMapper json=JsonMapper.builder()
  .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
  .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

 NotificationPreferenceController(NotificationStore store,SpaceAuthorizationClient authority,
  @Value("${brain.notification.browser-origin}")String origin,MeterRegistry metrics){
  this.store=store;this.authority=authority;this.origin=origin;this.metrics=metrics;
 }
 private UUID authorize(UUID space,HttpServletRequest r,boolean write,String trace){
  if(!r.isSecure())throw NotificationController.bad();
  if(write&&(Collections.list(r.getHeaders("Origin")).size()!=1||!origin.equals(r.getHeader("Origin"))
   ||r.getHeader("Sec-Fetch-Site")!=null&&!r.getHeader("Sec-Fetch-Site").equals("same-origin")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  String token=null;
  if(r.getCookies()!=null)for(var cookie:r.getCookies())if(cookie.getName().equals("__Host-brain_guest")){
   if(token!=null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);token=cookie.getValue();
  }
  return authority.authorize(space,token,write,trace);
 }
 static long version(HttpServletRequest r){
  var h=Collections.list(r.getHeaders("If-Match"));
  if(h.isEmpty())throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED);
  if(h.size()!=1||!h.getFirst().matches("\"np(0|[1-9][0-9]{0,18})\""))throw NotificationController.bad();
  try{return Long.parseLong(h.getFirst().substring(3,h.getFirst().length()-1));}
  catch(NumberFormatException e){throw NotificationController.bad();}
 }
 private ResponseEntity<NotificationStore.Preference> response(NotificationStore.Preference value){
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\"np"+value.version()+"\"").body(value);
 }
 @GetMapping("/notification-preference")
 ResponseEntity<NotificationStore.Preference> get(@PathVariable String spaceId,HttpServletRequest r){
  NotificationController.parameters(r,Set.of());var space=NotificationStore.id(spaceId);
  authorize(space,r,false,NotificationController.parent(r));
  metrics.counter("brain.notification.preferences","operation","read").increment();
  return response(store.preference(space));
 }
 @PutMapping("/notification-preference")
 ResponseEntity<NotificationStore.Preference> put(@PathVariable String spaceId,HttpServletRequest r)throws IOException{
  NotificationController.parameters(r,Set.of());var space=NotificationStore.id(spaceId);
  var trace=NotificationController.parent(r);var actor=authorize(space,r,true,trace);long expected=version(r);
  if(!MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(Optional.ofNullable(r.getContentType()).orElse("").split(";",2)[0].strip()))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
  var bytes=r.getInputStream().readNBytes(1025);if(bytes.length>1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
  boolean enabled;
  try{var value=json.readTree(bytes);if(value==null||!value.isObject()||value.size()!=1||!value.path("inAppEnabled").isBoolean())throw NotificationController.bad();enabled=value.path("inAppEnabled").asBoolean();}
  catch(RuntimeException e){throw NotificationController.bad();}
  var value=store.preference(space,actor,expected,enabled,trace.substring(3,35));
  metrics.counter("brain.notification.preferences","operation","write").increment();return response(value);
 }
 @GetMapping("/notification-preference/revisions")
 ResponseEntity<List<NotificationStore.PreferenceRevision>> revisions(@PathVariable String spaceId,HttpServletRequest r){
  NotificationController.parameters(r,Set.of("before","limit"));var space=NotificationStore.id(spaceId);
  authorize(space,r,false,NotificationController.parent(r));
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.preferenceHistory(space,
   NotificationController.number(r.getParameter("before"),Long.MAX_VALUE,Long.MAX_VALUE),
   (int)NotificationController.number(r.getParameter("limit"),5,10)));
 }
 @GetMapping("/notification-suppressions")
 ResponseEntity<NotificationStore.SuppressionPage> suppressions(@PathVariable String spaceId,HttpServletRequest r){
  NotificationController.parameters(r,Set.of("after","limit"));var space=NotificationStore.id(spaceId);
  authorize(space,r,false,NotificationController.parent(r));
  metrics.counter("brain.notification.preferences","operation","suppression-history").increment();
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.suppressions(space,r.getParameter("after"),
   (int)NotificationController.number(r.getParameter("limit"),20,100)));
 }
}
