package io.knowledgebrain.space;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@ConditionalOnProperty(name="brain.space.group-authorization.enabled",havingValue="true")
class GroupAuthorizationController {
 record Check(String token,List<UUID> groupIds){@Override public String toString(){return "Check[REDACTED]";}}
 record Provision(String token,UUID groupId){@Override public String toString(){return "Provision[REDACTED]";}}
 record Participations(UUID principalId,String kind,List<GroupSpaceStore.Grant> grants,UUID nextAfter){}
 record Access(UUID principalId,String kind,List<GroupSpaceStore.Grant> grants){}
 private final GroupProgressPolicyStore policies;private final GroupSpaceStore store;private final IdentityPrincipalClient identity;private final byte[] key;
 GroupAuthorizationController(GroupSpaceStore store,GroupProgressPolicyStore policies,IdentityPrincipalClient identity,@Value("${brain.space.group-authorization.key}")String key){if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Group service key required");this.policies=policies;this.store=store;this.identity=identity;this.key=("Bearer "+key).getBytes(StandardCharsets.US_ASCII);}
 private IdentityPrincipalClient.Principal principal(HttpServletRequest request,String token){
  var headers=Collections.list(request.getHeaders("Authorization"));if(!request.isSecure()||headers.size()!=1||!MessageDigest.isEqual(key,headers.getFirst().getBytes(StandardCharsets.US_ASCII)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Service authentication required");
  var p=identity.resolve(token,request.getHeader("traceparent"));if(!"REGISTERED".equals(p.kind()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Registered account required");return p;
 }
 static boolean valid(UUID id){return id!=null&&id.version()==7&&id.variant()==2;}
 @PostMapping("/internal/space/group-access") ResponseEntity<Access> accessRequest(HttpServletRequest request)throws java.io.IOException{
  var json=input(request,Set.of("token","groupIds"));var values=json.get("groupIds");if(!values.isArray()||values.size()>101)throw invalid();var ids=new ArrayList<UUID>();for(var value:values){if(!value.isString())throw invalid();ids.add(id(value.asString()));}return access(new Check(json.get("token").asString(),ids),request);
 }
 ResponseEntity<Access> access(Check check,HttpServletRequest request){
  var p=principal(request,check.token());if(check.groupIds()==null||check.groupIds().size()>101||check.groupIds().stream().anyMatch(id->!valid(id))||new HashSet<>(check.groupIds()).size()!=check.groupIds().size())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Group references");
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Access(p.principalId(),p.kind(),store.access(p.principalId(),check.groupIds())));
 }
 @PostMapping("/internal/space/group-provision") ResponseEntity<Access> provisionRequest(HttpServletRequest request)throws java.io.IOException{
  var json=input(request,Set.of("token","groupId"));if(!json.get("groupId").isString())throw invalid();return provision(new Provision(json.get("token").asString(),id(json.get("groupId").asString())),request);
 }
 @PostMapping("/internal/space/group-participation") ResponseEntity<Access> participation(HttpServletRequest request)throws java.io.IOException{
  var json=input(request,Set.of("token","groupIds"));var p=principal(request,json.get("token").asString());var values=json.get("groupIds");if(!values.isArray()||values.size()>101)throw invalid();var ids=new ArrayList<UUID>();for(var value:values){if(!value.isString())throw invalid();ids.add(id(value.asString()));}if(new HashSet<>(ids).size()!=ids.size())throw invalid();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Access(p.principalId(),p.kind(),store.participation(p.principalId(),ids)));
 }
 @PostMapping("/internal/space/group-participations") ResponseEntity<Participations> participations(HttpServletRequest request)throws java.io.IOException{
  var json=input(request,Set.of("token","after","limit"));var p=principal(request,json.get("token").asString());if(!(json.get("after").isNull()||json.get("after").isString())||!json.get("limit").isInt()||json.get("limit").asInt()<1||json.get("limit").asInt()>100)throw invalid();var page=store.participations(p.principalId(),json.get("after").isNull()?null:id(json.get("after").asString()),json.get("limit").asInt());return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Participations(p.principalId(),p.kind(),page.grants(),page.nextAfter()));
 }
 @PostMapping("/internal/space/group-progress-sharing") ResponseEntity<?> sharing(HttpServletRequest r)throws java.io.IOException{var v=input(r,Set.of("token","groupId","participantIds"));var p=principal(r,v.path("token").asString());if(!v.path("groupId").isString()||!v.path("participantIds").isArray()||v.path("participantIds").size()>100)throw invalid();var ids=new ArrayList<UUID>();for(var n:v.path("participantIds")){if(!n.isString())throw invalid();ids.add(id(n.asString()));}return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(policies.check(id(v.path("groupId").asString()),p.principalId(),ids));}
 private static UUID id(String value){if(!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))throw invalid();return UUID.fromString(value);}
 private static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Group capability request");}
 private tools.jackson.databind.JsonNode input(HttpServletRequest request,Set<String> fields)throws java.io.IOException{
  var headers=Collections.list(request.getHeaders("Authorization"));if(!request.isSecure()||headers.size()!=1||!MessageDigest.isEqual(key,headers.getFirst().getBytes(StandardCharsets.US_ASCII)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Service authentication required");
  if(request.getQueryString()!=null)throw invalid();if(!"application/json".equalsIgnoreCase(Optional.ofNullable(request.getContentType()).orElse("").split(";",2)[0].trim()))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"JSON required");
  var bytes=request.getInputStream().readNBytes(8193);if(bytes.length>8192)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Request too large");
  try{var json=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(bytes);
   if(json==null||!json.isObject()||json.size()!=fields.size()||fields.stream().anyMatch(f->!json.has(f))||!json.get("token").isString()||!json.get("token").asString().matches("[A-Za-z0-9_-]{43}"))throw invalid();return json;
  }catch(tools.jackson.core.JacksonException error){throw invalid();}
 }
 ResponseEntity<Access> provision(Provision check,HttpServletRequest request){
  var p=principal(request,check.token());if(!valid(check.groupId()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Group reference");
  var parent=request.getHeader("traceparent");var trace=parent!=null&&parent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")&&!parent.substring(3,35).equals("0".repeat(32))?parent.substring(3,35):UUID.randomUUID().toString().replace("-","");
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Access(p.principalId(),p.kind(),List.of(store.provision(check.groupId(),p.principalId(),trace))));
 }
}
