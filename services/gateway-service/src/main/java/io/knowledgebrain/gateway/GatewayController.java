package io.knowledgebrain.gateway;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.*;

@RestController
@ConditionalOnProperty(name="brain.gateway.api.enabled",havingValue="true")
class GatewayController {
    private static final Logger log=LoggerFactory.getLogger(GatewayController.class);
    private final GatewayTransport transport;
    private final MeterRegistry metrics;
    private final String browserOrigin;
    private final SecureRandom random=new SecureRandom();
    GatewayController(GatewayTransport transport,MeterRegistry metrics,@Value("${brain.gateway.browser-origin}") String origin) {
        GatewayConfiguration.origin(origin);this.transport=transport;this.metrics=metrics;this.browserOrigin=origin;
    }
    @RequestMapping("/api/**")
    ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException {
        var route=GatewayRoute.select(request.getMethod(),request.getRequestURI());
        if(route==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Route unavailable");
        if(!request.isSecure() || (request.getQueryString()!=null && !validQuery(route,request)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"HTTPS without query parameters required");
        if(Set.of("POST","PUT","DELETE").contains(request.getMethod())) {
            if(Collections.list(request.getHeaders("Origin")).size()!=1 || !browserOrigin.equals(request.getHeader("Origin")) || (request.getHeader("Sec-Fetch-Site")!=null && !"same-origin".equals(request.getHeader("Sec-Fetch-Site"))))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Same-origin request required");
            if(!"DELETE".equals(request.getMethod()) && !MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(Optional.ofNullable(request.getContentType()).orElse("").split(";",2)[0].trim()))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"JSON required");
        }
        int limit="space".equals(route.service())&&(route.path().startsWith("/api/groups/")||route.path().equals("/api/group-invitations/accept"))?2048:"collaboration".equals(route.service())?65536:"planning".equals(route.service())?262144:"learning".equals(route.service())?1048576:"knowledge".equals(route.service())&&(route.path().contains("/qualifiers")||route.path().contains("/external-mappings")||(route.path().contains("/claims")&&!route.path().contains("/citations")))?16384:"knowledge".equals(route.service())&&(route.path().contains("/aliases")||route.path().contains("/claims"))?4096:"knowledge".equals(route.service())&&route.path().contains("/sources")?32768:("content".equals(route.service())||"knowledge".equals(route.service()))?262144:"media".equals(route.service())?2048:1024;
        byte[] body=request.getInputStream().readNBytes(limit+1);
        if(body.length>limit)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Request too large");
        if(body.length!=0 && "GET".equals(request.getMethod()) && "content".equals(route.service()) && (route.path().matches("/api/spaces/[0-9a-f-]+/(tags|property-definitions)") || route.path().matches("/api/spaces/[0-9a-f-]+/(tags|property-definitions)/[0-9a-f-]+/notes")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Metadata discovery reads require an empty body");
        if(body.length!=0 && "GET".equals(request.getMethod()) && route.path().matches("/api/spaces/[0-9a-f-]+/sources/[0-9a-f-]+/citations"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Source citation reads require an empty body");
        if(body.length!=0 && "GET".equals(request.getMethod()) && route.path().matches("/api/spaces/[0-9a-f-]+/concepts/[0-9a-f-]+/claim-usages"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Claim usage reads require an empty body");
        if(body.length!=0 && "GET".equals(request.getMethod()) && route.path().matches("/api/spaces/[0-9a-f-]+/(graph|search)/network"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Network reads require an empty body");
        if(body.length!=0&&"GET".equals(request.getMethod())&&"content".equals(route.service())&&(route.path().contains("/record-concepts/")||route.path().endsWith("/records")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Record concept reads require an empty body");
        if(body.length!=0 && ("GET".equals(request.getMethod())||"DELETE".equals(request.getMethod())) && Set.of("integration","learning","activity","planning","notification").contains(route.service()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Roadmap reads/retirement require an empty body");
        if(body.length!=0 && ("GET".equals(request.getMethod())||"DELETE".equals(request.getMethod())) && "content".equals(route.service()) && route.path().contains("/bookmarks"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Bookmark reads/archival require an empty body");
        if(body.length!=0 && ("GET".equals(request.getMethod())||"DELETE".equals(request.getMethod())) && "content".equals(route.service()) && route.path().contains("/questions"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Question reads/archival require an empty body");
        if(body.length!=0 && ("GET".equals(request.getMethod())||"DELETE".equals(request.getMethod())) && "content".equals(route.service()) && route.path().contains("/decisions"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Decision reads/archival require an empty body");
        if(body.length!=0&&Set.of("GET","DELETE").contains(request.getMethod())&&"collaboration".equals(route.service()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Group reads require an empty body");
        if((route.path().startsWith("/api/groups/")||route.path().equals("/api/group-invitations/accept"))&&route.service().equals("space")&&(request.getMethod().equals("GET")||request.getMethod().equals("DELETE"))&&body.length!=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Group reads/removal require an empty body");
        Map<String,List<String>> headers=new HashMap<>();
        // No caller Authorization, Host, Forwarded, principal, user or hop-by-hop headers.
        for(String name:List.of("Cookie","Origin","Sec-Fetch-Site","Content-Type","If-Match")) {
            var values=Collections.list(request.getHeaders(name));
            if(values.stream().mapToInt(String::length).sum()>8192)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Header too large");
            if(!values.isEmpty())headers.put(name,values);
        }
        if(route.service().equals("content")&&request.getMethod().equals("POST")&&route.path().endsWith("/bookmarks")){var keys=Collections.list(request.getHeaders("Idempotency-Key"));if(keys.size()!=1||!keys.getFirst().matches("[0-9a-f]{32}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Bookmark request key required");headers.put("Idempotency-Key",keys);}
        if(route.service().equals("content")&&request.getMethod().equals("POST")&&route.path().endsWith("/questions")){var keys=Collections.list(request.getHeaders("Idempotency-Key"));if(keys.size()!=1||!keys.getFirst().matches("[0-9a-f]{32}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Question request key required");headers.put("Idempotency-Key",keys);}
        if(route.service().equals("content")&&request.getMethod().equals("POST")&&route.path().endsWith("/decisions")){var keys=Collections.list(request.getHeaders("Idempotency-Key"));if(keys.size()!=1||!keys.getFirst().matches("[0-9a-f]{32}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Decision request key required");headers.put("Idempotency-Key",keys);}
        if(route.service().equals("planning")&&request.getMethod().equals("POST")&&!route.path().endsWith("/recurrences/preview")&&!route.path().endsWith("/reminders/preview")&&!route.path().endsWith("/cancel")){var keys=Collections.list(request.getHeaders("Idempotency-Key"));if(keys.size()!=1||!keys.getFirst().matches("[0-9a-f]{32}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Planning request key required");headers.put("Idempotency-Key",keys);}
        if(route.service().equals("learning")&&request.getMethod().equals("POST")&&(route.path().endsWith("/study-sessions")||route.path().endsWith("/evidence")||route.path().endsWith("/concept-evidence")||route.path().endsWith("/media-evidence")||route.path().endsWith("/github-evidence"))) {
            var keys=Collections.list(request.getHeaders("Idempotency-Key"));if(keys.size()!=1||!keys.getFirst().matches("[0-9a-f]{32}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Study session request key required");headers.put("Idempotency-Key",keys);
        }
        if(route.service().equals("collaboration")&&request.getMethod().equals("POST")&&(route.path().matches("/api/groups/[0-9a-f-]+/studies/[0-9a-f-]+/discussions(/[0-9a-f-]+/replies)?")||route.path().equals("/api/groups")||route.path().matches("/api/groups/[0-9a-f-]+/courses")||(route.path().matches("/api/groups/[0-9a-f-]+/studies(/[0-9a-f-]+/assignments)?")||route.path().matches("/api/groups/[0-9a-f-]+/studies/[0-9a-f-]+/assignments/[0-9a-f-]+/(submissions(/with-files)?|submission-files)")))){var keys=Collections.list(request.getHeaders("Idempotency-Key"));if(keys.size()!=1||!keys.getFirst().matches("[0-9a-f]{32}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Group request key required");headers.put("Idempotency-Key",keys);}
        if(route.service().equals("space")&&request.getMethod().equals("POST")&&route.path().matches("/api/groups/[0-9a-f-]+/invitations")){var keys=Collections.list(request.getHeaders("Idempotency-Key"));if(keys.size()!=1||!keys.getFirst().matches("[0-9a-f]{32}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invitation request key required");headers.put("Idempotency-Key",keys);}
        if(route.service().equals("integration")&&request.getMethod().equals("POST")){var keys=Collections.list(request.getHeaders("Idempotency-Key"));if(keys.size()!=1||!keys.getFirst().matches("[0-9a-f]{32}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"GitHub request key required");headers.put("Idempotency-Key",keys);}
        String parent=request.getHeader("traceparent");
        if(parent==null || !parent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}") || parent.substring(3,35).equals("0".repeat(32)) || parent.substring(36,52).equals("0".repeat(16)))parent="00-"+randomHex(16)+"-"+randomHex(8)+"-01";
        headers.put("traceparent",List.of(parent));headers.put("Accept",List.of("application/json"));String trace=parent.substring(3,35);
        try {
            var response=transport.send(new GatewayRoute(route.service(),route.path()+(request.getQueryString()==null?"":"?"+request.getQueryString())),request.getMethod(),headers,body);
            if(response.statusCode()<200 || response.statusCode()>=300 && response.statusCode()<400 && !(route.path().equals("/api/identity/login/callback")&&response.statusCode()==303&&response.headers().allValues("Location").equals(List.of("/graph"))))throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Upstream response unavailable");
            HttpHeaders outgoing=new HttpHeaders();outgoing.setCacheControl("no-store");outgoing.set("X-Trace-Id",trace);outgoing.set("Referrer-Policy","no-referrer");
            for(String name:List.of("Content-Type","Set-Cookie","Retry-After","ETag","Location"))response.headers().allValues(name).forEach(value->outgoing.add(name,value));
            metrics.counter("brain.gateway.requests","service",route.service(),"status",Integer.toString(response.statusCode())).increment();
            log.info("gateway_response service={} status={} trace_id={}",route.service(),response.statusCode(),trace);
            return new ResponseEntity<>(response.body(),outgoing,HttpStatusCode.valueOf(response.statusCode()));
        } catch(java.net.http.HttpTimeoutException error) { throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,"Upstream timed out"); }
        catch(IOException error) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Upstream unavailable"); }
        catch(InterruptedException error) { Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Gateway interrupted"); }
    }
    private boolean validQuery(GatewayRoute route,HttpServletRequest request) {
        if(route.path().startsWith("/api/public/")&&(route.path().endsWith("/search")||route.path().endsWith("/graph")))return publicDiscoveryQuery(route,request);
        if(route.path().startsWith("/api/public/")&&(route.path().contains("/records")||route.path().contains("/record-proposals")||route.path().endsWith("/explore")||route.path().endsWith("/relations")))return publicRecordQuery(route,request);
        if(route.path().equals("/api/identity/login/callback")){
            if(!"GET".equals(request.getMethod())||request.getQueryString().length()>4096)return false;var used=new HashSet<String>();
            for(String part:request.getQueryString().split("&",-1)){var pair=part.split("=",2);if(pair.length!=2||!Set.of("state","code","session_state","iss").contains(pair[0])||!used.add(pair[0])||!pair[1].matches("[A-Za-z0-9._~%+:/-]{1,2048}"))return false;}
            return used.contains("state")&&used.contains("code");
        }

        if("integration".equals(route.service())&&route.path().contains("/work-items")){if(!"GET".equals(request.getMethod())||request.getQueryString().length()>512)return false;boolean catalog=route.path().endsWith("/work-items"),history=route.path().endsWith("/revisions"),syncs=route.path().endsWith("/syncs");if(!catalog&&!history&&!syncs)return false;var seen=new HashSet<String>();for(String part:request.getQueryString().split("&",-1)){var p=part.split("=",-1);if(p.length!=2||!seen.add(p[0]))return false;if(p[0].equals("limit")){if(!p[1].matches("[1-9][0-9]{0,2}")||Integer.parseInt(p[1])>(history?10:100))return false;}else if((history||syncs)&&p[0].equals("before")){try{if(!p[1].matches("[1-9][0-9]{0,18}")||Long.parseLong(p[1])<1)return false;}catch(NumberFormatException e){return false;}}else if(catalog&&p[0].equals("kind")){if(!Set.of("ISSUE","PULL_REQUEST").contains(p[1]))return false;}else if(catalog&&p[0].equals("state")){if(!Set.of("ALL","OPEN","CLOSED").contains(p[1]))return false;}else if(catalog&&p[0].equals("after")){if(!p[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))return false;}else return false;}return true;}
        if("integration".equals(route.service())){if(!"GET".equals(request.getMethod())||request.getQueryString().length()>256)return false;var allowed=route.path().endsWith("/repositories")?Set.of("state","after","limit"):route.path().endsWith("/commits")?Set.of("after","limit"):(route.path().endsWith("/revisions")||route.path().endsWith("/syncs"))?Set.of("before","limit"):Set.<String>of();var seen=new HashSet<String>();for(var part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!allowed.contains(pair[0])||!seen.add(pair[0]))return false;try{if(pair[0].equals("state")){if(!Set.of("ACTIVE","RETIRED").contains(pair[1]))return false;}else if(pair[0].equals("after")){if(!pair[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))return false;}else if(!pair[1].matches("[1-9][0-9]{0,18}")||Long.parseLong(pair[1])>(pair[0].equals("limit")?100:Long.MAX_VALUE))return false;}catch(NumberFormatException e){return false;}}return true;}
        if("space".equals(route.service())&&route.path().startsWith("/api/groups/")){
            if(!"GET".equals(request.getMethod())||request.getQueryString().length()>256)return false;var allowed=route.path().endsWith("/revisions")?Set.of("before","limit"):route.path().endsWith("/members")||route.path().endsWith("/invitations")?Set.of("after","limit"):Set.<String>of();var seen=new HashSet<String>();
            for(String part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!allowed.contains(pair[0])||!seen.add(pair[0]))return false;
                if(pair[0].equals("after")){if(!pair[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))return false;}
                else if(pair[0].equals("limit")){if(!pair[1].matches("[1-9][0-9]{0,2}")||Integer.parseInt(pair[1])>(route.path().endsWith("/revisions")?10:100))return false;}
                else{try{if(!pair[1].matches("[1-9][0-9]{0,18}"))return false;Long.parseLong(pair[1]);}catch(NumberFormatException e){return false;}}
            }return true;
        }
        if("learning".equals(route.service())&&route.path().endsWith("/shared-progress")){if(!"GET".equals(request.getMethod())||request.getQueryString().length()>256)return false;var seen=new HashSet<String>();for(var part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!seen.add(pair[0]))return false;if(pair[0].equals("limit")){if(!pair[1].matches("[1-9][0-9]{0,2}")||Integer.parseInt(pair[1])>100)return false;}else if(pair[0].equals("after")){if(!pair[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))return false;}else return false;}return true;}
        if("learning".equals(route.service())&&route.path().contains("/my-progress")){if(!"GET".equals(request.getMethod())||!route.path().endsWith("/revisions")||request.getQueryString().length()>256)return false;var seen=new HashSet<String>();for(var part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!seen.add(pair[0]))return false;if(pair[0].equals("limit")){if(!pair[1].matches("[1-9][0-9]?")||Integer.parseInt(pair[1])>10)return false;}else if(pair[0].equals("before")){try{if(!pair[1].matches("[1-9][0-9]{0,18}"))return false;Long.parseLong(pair[1]);}catch(NumberFormatException e){return false;}}else return false;}return true;}
        if("collaboration".equals(route.service())){
            if(!"GET".equals(request.getMethod())||request.getQueryString().length()>256)return false;var seen=new HashSet<String>();
            var allowed=(route.path().endsWith("/submissions/review-queue")||route.path().endsWith("/submissions/grading-queue")||route.path().endsWith("/my-submissions")||route.path().endsWith("/my-resubmission-requests")||route.path().endsWith("/my-feedback")||route.path().endsWith("/my-assignments"))?Set.of("after","limit","filter"):(route.path().equals("/api/groups")||route.path().equals("/api/groups/participating")||route.path().endsWith("/enrollments")||route.path().endsWith("/courses")||route.path().endsWith("/studies")||route.path().endsWith("/assignments")||route.path().endsWith("/submissions")||route.path().endsWith("/discussions")||route.path().endsWith("/replies"))?Set.of("after","limit"):route.path().endsWith("/revisions")?Set.of("before","limit"):Set.<String>of();
            for(String part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!allowed.contains(pair[0])||!seen.add(pair[0]))return false;
                if(pair[0].equals("after")){if(!pair[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))return false;}
                else if(pair[0].equals("limit")){if(!pair[1].matches("[1-9][0-9]{0,2}")||Integer.parseInt(pair[1])>(route.path().contains("/courses/")&&!route.path().contains("/enrollment")&&route.path().endsWith("/revisions")?5:(route.path().endsWith("/revisions")||route.path().endsWith("/replies"))?10:100))return false;}
                else if(pair[0].equals("filter")){if(!(route.path().endsWith("/my-assignments")?Set.of("OPEN_NOT_SUBMITTED","ALL","OPEN","SUBMITTED","NOT_SUBMITTED"):route.path().endsWith("/my-feedback")?Set.of("ALL","CURRENT","OUTDATED","RETRACTED","UNAVAILABLE"):route.path().endsWith("/my-resubmission-requests")?Set.of("REQUESTED","RESOLVED","CANCELLED","ALL","UNAVAILABLE"):(route.path().endsWith("/submissions/grading-queue")||route.path().endsWith("/my-submissions"))?Set.of("NEEDS_GRADING","ALL","MISSING","CURRENT","OUTDATED","RETRACTED","UNAVAILABLE"):(route.path().matches("/api/groups/[^/]+/(courses|studies)/[^/]+/submissions/review-queue")||route.path().matches("/api/groups/[^/]+/submissions/review-queue"))?Set.of("ALL","NEEDS_FEEDBACK","HAS_FEEDBACK","RETRACTED_FEEDBACK","PENDING_RESUBMISSION","UNAVAILABLE"):Set.of("ALL","NEEDS_FEEDBACK","HAS_FEEDBACK","RETRACTED_FEEDBACK","PENDING_RESUBMISSION")).contains(pair[1]))return false;}
                else {try{if(!pair[1].matches("[1-9][0-9]{0,18}"))return false;Long.parseLong(pair[1]);}catch(NumberFormatException e){return false;}}
            }return true;
        }
        if("planning".equals(route.service())&&(route.path().endsWith("/schedules")||route.path().endsWith("/schedules/time-options"))){if(!request.getMethod().equals("GET")||request.getQueryString().length()>1536)return false;var allowed=route.path().endsWith("/time-options")?Set.of("local","timeZone"):Set.of("state","taskId","from","to","timeZone","after","limit");var seen=new HashSet<String>();for(String part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!allowed.contains(pair[0])||!seen.add(pair[0])||!pair[1].matches("[A-Za-z0-9_%+./:-]{1,768}"))return false;}return true;}
        if("notification".equals(route.service())||"planning".equals(route.service())&&route.path().contains("/deliveries")){if(!request.getMethod().equals("GET")||request.getQueryString().length()>768)return false;var allowed=route.path().endsWith("/revisions")?Set.of("before","limit"):route.path().endsWith("/notifications")?Set.of("state","read","after","limit"):(route.path().endsWith("/deliveries")||route.path().endsWith("/notification-suppressions"))?Set.of("after","limit"):Set.<String>of();var seen=new HashSet<String>();for(String part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!allowed.contains(pair[0])||!seen.add(pair[0])||!pair[1].matches("[A-Za-z0-9_-]{1,256}"))return false;}return true;}
        if("planning".equals(route.service())&&route.path().contains("/reminders")){if(!request.getMethod().equals("GET")||request.getQueryString().length()>768)return false;var allowed=route.path().endsWith("/revisions")?Set.of("before","limit"):route.path().endsWith("/reminders")?Set.of("state","scheduleId","after","limit"):Set.<String>of();var seen=new HashSet<String>();for(String part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!allowed.contains(pair[0])||!seen.add(pair[0])||!pair[1].matches("[A-Za-z0-9_-]{1,256}"))return false;}return true;}
        if("planning".equals(route.service())&&route.path().contains("/recurrences")){if(!request.getMethod().equals("GET")||request.getQueryString().length()>512)return false;var allowed=route.path().endsWith("/cancellation")?Set.of("fromOrdinal"):route.path().endsWith("/revisions")?Set.of("before","limit"):route.path().endsWith("/recurrences")?Set.of("state","after","limit"):Set.<String>of();var seen=new HashSet<String>();for(String part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!allowed.contains(pair[0])||!seen.add(pair[0])||!pair[1].matches("[A-Za-z0-9_-]{1,256}"))return false;}return true;}
        if("planning".equals(route.service())){if(!request.getMethod().equals("GET"))return false;Set<String> allowed=route.path().endsWith("/revisions")?Set.of("before","limit"):route.path().endsWith("/goals")?Set.of("after","limit","status","state"):route.path().endsWith("/tasks")?Set.of("after","limit","status","state","goalId"):Set.of();if(request.getQueryString().length()>512)return false;var seen=new HashSet<String>();for(String part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!allowed.contains(pair[0])||!seen.add(pair[0])||!pair[1].matches("[A-Za-z0-9_-]{1,64}"))return false;}return true;}
        if("content".equals(route.service())&&(route.path().contains("/record-concepts/")||route.path().endsWith("/records"))){if(!request.getMethod().equals("GET")||request.getQueryString().length()>768)return false;var allowed=route.path().endsWith("/revisions")?Set.of("before","limit"):route.path().endsWith("/records")?Set.of("type","state","after","limit"):Set.<String>of();var seen=new HashSet<String>();for(String part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!allowed.contains(pair[0])||!seen.add(pair[0])||!pair[1].matches("[A-Za-z0-9_-]{1,256}"))return false;}return true;}
        if("content".equals(route.service())&&route.path().contains("/bookmarks")){if(!request.getMethod().equals("GET")||request.getQueryString().length()>768)return false;var allowed=route.path().endsWith("/revisions")?Set.of("before","limit"):route.path().endsWith("/bookmarks")?Set.of("state","after","limit"):Set.<String>of();var seen=new HashSet<String>();for(String part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!allowed.contains(pair[0])||!seen.add(pair[0])||!pair[1].matches("[A-Za-z0-9_-]{1,256}"))return false;}return true;}
        if("content".equals(route.service())&&route.path().contains("/questions")){if(!request.getMethod().equals("GET")||request.getQueryString().length()>768)return false;var allowed=route.path().endsWith("/revisions")?Set.of("before","limit"):route.path().endsWith("/questions")?Set.of("state","status","after","limit"):Set.<String>of();var seen=new HashSet<String>();for(String part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!allowed.contains(pair[0])||!seen.add(pair[0])||!pair[1].matches("[A-Za-z0-9_-]{1,256}"))return false;}return true;}
        if("content".equals(route.service())&&route.path().contains("/decisions")){if(!request.getMethod().equals("GET")||request.getQueryString().length()>768)return false;var allowed=route.path().endsWith("/revisions")?Set.of("before","limit"):route.path().endsWith("/decisions")?Set.of("state","status","after","limit"):Set.<String>of();var seen=new HashSet<String>();for(String part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!allowed.contains(pair[0])||!seen.add(pair[0])||!pair[1].matches("[A-Za-z0-9_-]{1,256}"))return false;}return true;}
        if("activity".equals(route.service())){if(!"GET".equals(request.getMethod())||request.getQueryString().length()>1024)return false;var seen=new HashSet<String>();for(String part:request.getQueryString().split("&",-1)){var p=part.split("=",-1);if(p.length!=2||!Set.of("from","to","timezone","kind","limit","before").contains(p[0])||!seen.add(p[0])||!p[1].matches("[A-Za-z0-9_%+./:-]{1,512}"))return false;}return true;}
        if("learning".equals(route.service())&&route.path().endsWith("/roadmap-rankings")){if(!request.getMethod().equals("GET")||request.getQueryString().length()>128)return false;var used=new HashSet<String>();for(String part:request.getQueryString().split("&",-1)){var p=part.split("=",-1);if(p.length!=2||!used.add(p[0]))return false;if(p[0].equals("sort")){if(!Set.of("RATING","RATING_COUNT","RECENT").contains(p[1]))return false;}else if(p[0].equals("limit")){if(!p[1].matches("[1-9][0-9]{0,2}")||Integer.parseInt(p[1])>100)return false;}else return false;}return true;}
        if("learning".equals(route.service())&&(route.path().contains("/github-evidence")||route.path().endsWith("/github-learning-evidence"))){if(!"GET".equals(request.getMethod())||request.getQueryString().length()>512)return false;boolean related=route.path().endsWith("/github-learning-evidence"),catalog=related||route.path().endsWith("/github-evidence"),history=route.path().endsWith("/revisions");if(!catalog&&!history)return false;var seen=new HashSet<String>();for(String part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!seen.add(pair[0]))return false;if(pair[0].equals("limit")){if(!pair[1].matches("[1-9][0-9]{0,2}")||Integer.parseInt(pair[1])>(history?10:100))return false;}else if(history&&pair[0].equals("before")){try{if(!pair[1].matches("[1-9][0-9]{0,18}")||Long.parseLong(pair[1])<1)return false;}catch(NumberFormatException e){return false;}}else if(catalog&&pair[0].equals("state")){if(!Set.of("ACTIVE","RETIRED").contains(pair[1]))return false;}else if(catalog&&pair[0].equals("after")||related&&Set.of("repositoryId","commitId").contains(pair[0])){if(!pair[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))return false;}else return false;}return !related||seen.containsAll(Set.of("repositoryId","commitId"));}
        if("learning".equals(route.service())){
            if(!"GET".equals(request.getMethod())||request.getQueryString().length()>256)return false;boolean history=route.path().endsWith("/revisions"),catalog=route.path().endsWith("/group-roadmaps")||route.path().endsWith("/roadmap-contributions")||route.path().endsWith("/roadmaps")||route.path().endsWith("/study-sessions")||route.path().endsWith("/evidence")||route.path().endsWith("/concept-evidence")||route.path().endsWith("/media-evidence");if(!history&&!catalog)return false;var used=new HashSet<String>();
            for(String part:request.getQueryString().split("&",-1)){var pair=part.split("=",-1);if(pair.length!=2||!used.add(pair[0]))return false;
                if(pair[0].equals("limit")){if(!pair[1].matches("[1-9][0-9]{0,2}")||Integer.parseInt(pair[1])>(history?10:100))return false;}
                else if(history&&pair[0].equals("before")){if(!pair[1].matches("[1-9][0-9]{0,18}"))return false;try{Long.parseLong(pair[1]);}catch(NumberFormatException e){return false;}}
                else if(catalog&&pair[0].equals("after")){if(!pair[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))return false;}else return false;
            }return true;
        }
        if("brain-query".equals(route.service())){if((route.path().endsWith("/graph/notes")||route.path().endsWith("/graph/network")))return "GET".equals(request.getMethod()) && request.getQueryString().matches("limit=([1-9]|[1-9][0-9]|1[0-9]{2}|200)");return validSearchQuery(request);}
        if("content".equals(route.service())&&route.path().matches("/api/spaces/[0-9a-f-]+/(tags|property-definitions)"))return metadataQuery(request);
        if("content".equals(route.service())&&route.path().matches("/api/spaces/[0-9a-f-]+/property-definitions/[0-9a-f-]+/notes"))return propertyQuery(request);
        if(!Set.of("content","media","knowledge").contains(route.service()) || !"GET".equals(request.getMethod()) || request.getQueryString().length()>256)return false;
        if(route.path().endsWith("/daily-notes") || route.path().endsWith("/daily-notes/today")) {
            boolean today=route.path().endsWith("/today");var used=new HashSet<String>();
            for(String part:request.getQueryString().split("&",-1)) {
                String[] pair=part.split("=",-1);if(pair.length!=2 || !used.add(pair[0]))return false;
                if(today){if(!"timezone".equals(pair[0]))return false;try{String zone=java.net.URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8);if(!zone.matches("[A-Za-z0-9_+./-]{1,100}"))return false;}catch(IllegalArgumentException error){return false;}}
                else if("limit".equals(pair[0])){if(!pair[1].matches("[1-9][0-9]{0,2}"))return false;}
                else if(Set.of("from","to","before").contains(pair[0])){if(!pair[1].matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))return false;}else return false;
            }return !today || used.contains("timezone");
        }
        String id="[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
        if(route.path().matches("/api/spaces/"+id+"/concepts/"+id+"/claim-usages")) {
            var used=new HashSet<String>();
            for(String part:request.getQueryString().split("&",-1)) {
                String[] pair=part.split("=",-1);if(pair.length!=2||!used.add(pair[0]))return false;
                if("role".equals(pair[0])){if(!Set.of("OBJECT","PREDICATE").contains(pair[1]))return false;}
                else if("after".equals(pair[0])){if(!pair[1].matches(id))return false;}
                else if("limit".equals(pair[0])){if(!pair[1].matches("[1-9][0-9]{0,2}")||Integer.parseInt(pair[1])>100)return false;}
                else return false;
            }return true;
        }
        if(route.path().matches("/api/spaces/"+id+"/(tags|property-definitions)")||route.path().matches("/api/spaces/"+id+"/(tags|property-definitions)/"+id+"/notes")) {
            var used=new HashSet<String>();
            for(String part:request.getQueryString().split("&",-1)) {
                var pair=part.split("=",-1);if(pair.length!=2||!used.add(pair[0]))return false;
                if("after".equals(pair[0])){if(!pair[1].matches(id))return false;}
                else if("limit".equals(pair[0])){if(!pair[1].matches("[1-9][0-9]{0,2}")||Integer.parseInt(pair[1])>100)return false;}
                else return false;
            }return true;
        }
        if(route.path().matches("/api/spaces/"+id+"/notes")) {
            var used=new HashSet<String>();
            for(String part:request.getQueryString().split("&",-1)) {
                var pair=part.split("=",-1);if(pair.length!=2||!used.add(pair[0]))return false;
                if("state".equals(pair[0])){if(!Set.of("ACTIVE","RETIRED").contains(pair[1]))return false;}
                else if("after".equals(pair[0])){if(!pair[1].matches(id))return false;}
                else if("limit".equals(pair[0])){if(!pair[1].matches("[1-9][0-9]{0,2}")||Integer.parseInt(pair[1])>100)return false;}
                else return false;
            }return true;
        }
        if(route.path().matches("/api/spaces/"+id+"/sources(/"+id+"/(revisions|citations))?")) {
            boolean history=route.path().endsWith("/revisions");var used=new HashSet<String>();
            for(String part:request.getQueryString().split("&",-1)){
                String[] pair=part.split("=",-1);if(pair.length!=2||!used.add(pair[0]))return false;
                if("limit".equals(pair[0])){if(!pair[1].matches("[1-9][0-9]{0,2}")||Integer.parseInt(pair[1])>(history?10:100))return false;}
                else if(!history&&"after".equals(pair[0])){if(!pair[1].matches(id))return false;}
                else if(history&&"before".equals(pair[0])){if(!pair[1].matches("[1-9][0-9]{0,18}"))return false;try{Long.parseLong(pair[1]);}catch(NumberFormatException error){return false;}}
                else if(route.path().endsWith("/sources")&&"state".equals(pair[0])){if(!Set.of("ACTIVE","RETIRED").contains(pair[1]))return false;}
                else return false;
            }return true;
        }
        if(route.path().matches("/api/spaces/"+id+"/concepts/"+id+"/(aliases|claims|external-mappings)(/"+id+"/revisions)?")||route.path().matches("/api/spaces/"+id+"/concepts/"+id+"/claims/"+id+"/(citations|qualifiers)(/"+id+"/revisions)?")) {
            boolean revisions=route.path().endsWith("/revisions");var used=new HashSet<String>();
            for(String part:request.getQueryString().split("&",-1)) {
                String[] pair=part.split("=",-1);if(pair.length!=2 || !used.add(pair[0]))return false;
                if("limit".equals(pair[0])) { if(!pair[1].matches("[1-9][0-9]{0,2}") || Integer.parseInt(pair[1])>(revisions?10:100))return false; }
                else if(revisions && "before".equals(pair[0])) { if(!pair[1].matches("[1-9][0-9]{0,18}"))return false;try { Long.parseLong(pair[1]); } catch(NumberFormatException error){return false;} }
                else if(!revisions && "after".equals(pair[0])) { if(!pair[1].matches(id))return false; }
                else if(!revisions && "state".equals(pair[0])) { if(!Set.of("ACTIVE","RETIRED").contains(pair[1]))return false; }
                else return false;
            }return true;
        }
        var keys=new HashSet<String>();boolean history=route.path().endsWith("/revisions"),links=route.path().endsWith("/links"),backlinks=route.path().endsWith("/backlinks");
        if(!("knowledge".equals(route.service())&&(route.path().endsWith("/concepts")||route.path().endsWith("/contributions"))) && !history && !links && !backlinks && !route.path().endsWith("/notes") && !route.path().endsWith("/tags") && !route.path().endsWith("/property-definitions") && !route.path().endsWith("/media"))return false;
        for(String part:request.getQueryString().split("&",-1)) {
            String[] pair=part.split("=",-1);if(pair.length!=2 || !keys.add(pair[0]))return false;
            if("limit".equals(pair[0])) { if(!pair[1].matches("[1-9][0-9]{0,2}"))return false; }
            else if(history && "before".equals(pair[0])) { if(!pair[1].matches("[1-9][0-9]{0,18}"))return false; }
            else if(!history && "after".equals(pair[0])) { if(!pair[1].matches(links?"[0-9]{1,9}":id))return false; }
            else if(route.path().matches("/api/spaces/"+id+"/concepts") && "state".equals(pair[0])) { if(!Set.of("ACTIVE","RETIRED").contains(pair[1]))return false; }
            else return false;
        }return true;
    }
    private boolean publicDiscoveryQuery(GatewayRoute route,HttpServletRequest request){String q=request.getQueryString();if(q==null||q.isEmpty())return true;if(q.length()>2000)return false;boolean graph=route.path().endsWith("/graph");var allowed=graph?Set.of("center","depth","limit"):Set.of("q","kind","offset","limit");var seen=new HashSet<String>();for(String part:q.split("&",-1)){var p=part.split("=",-1);if(p.length!=2||!allowed.contains(p[0])||!seen.add(p[0]))return false;switch(p[0]){case "center"->{if(!p[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))return false;}case "depth"->{if(!p[1].matches("[1-3]"))return false;}case "limit"->{if(!p[1].matches("[1-9][0-9]{0,2}")||Integer.parseInt(p[1])>(graph?100:50))return false;}case "offset"->{if(!p[1].matches("0|[1-9][0-9]{0,2}"))return false;}case "kind"->{if(!Set.of("CONCEPT","ALIAS","CLAIM","SOURCE","EVIDENCE","MAPPING","VERIFICATION","QUALIFIER").contains(p[1]))return false;}case "q"->{try{String text=metadataText(p[1]);if(text.isBlank()||text.codePointCount(0,text.length())>128||text.codePoints().anyMatch(Character::isISOControl))return false;}catch(IllegalArgumentException|java.nio.charset.CharacterCodingException e){return false;}}default->{return false;}}}return true;}
    private boolean publicRecordQuery(GatewayRoute route,HttpServletRequest request) {
        String query=request.getQueryString();if(query==null||query.isEmpty())return true;
        if(!"GET".equals(request.getMethod())||query.length()>1500)return false;
        String path=route.path();boolean history=path.endsWith("/revisions"),records=path.endsWith("/records"),queue=path.endsWith("/record-proposals"),explore=path.endsWith("/explore"),relations=path.endsWith("/relations");
        var allowed=history?Set.of("before","limit"):records?Set.of("kind","state","after","limit","q"):queue?Set.of("kind","state","mine","after","limit"):explore?Set.of("q","after","limit"):relations?Set.of("limit"):Set.<String>of();var seen=new HashSet<String>();
        for(String part:query.split("&",-1)){var p=part.split("=",-1);if(p.length!=2||!allowed.contains(p[0])||!seen.add(p[0]))return false;
            switch(p[0]){
                case "limit"->{if(!p[1].matches("[1-9][0-9]?")||Integer.parseInt(p[1])>(history?10:relations?20:50))return false;}
                case "before"->{try{if(!p[1].matches("[1-9][0-9]{0,18}")||Long.parseLong(p[1])<1)return false;}catch(NumberFormatException e){return false;}}
                case "after"->{if(!p[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))return false;}
                case "kind"->{if(!Set.of("ALIAS","CLAIM","SOURCE","EVIDENCE","MAPPING","VERIFICATION","QUALIFIER").contains(p[1]))return false;}
                case "state"->{if(!(queue?Set.of("SUBMITTED","APPROVED","REJECTED","MERGED"):Set.of("ACTIVE","RETIRED")).contains(p[1]))return false;}
                case "mine"->{if(!Set.of("true","false").contains(p[1]))return false;}
                case "q"->{try{String text=metadataText(p[1]);if(text.isBlank()||text.codePointCount(0,text.length())>64||text.codePoints().anyMatch(Character::isISOControl))return false;}catch(IllegalArgumentException|java.nio.charset.CharacterCodingException e){return false;}}
                default->{return false;}
            }
        }return true;
    }
    private boolean metadataQuery(HttpServletRequest request) {
        if(!"GET".equals(request.getMethod())||request.getQueryString().length()>1700)return false;var used=new HashSet<String>();
        for(String part:request.getQueryString().split("&",-1)){
            var pair=part.split("=",-1);if(pair.length!=2||!used.add(pair[0]))return false;
            if(pair[0].equals("after")){if(!pair[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))return false;}
            else if(pair[0].equals("limit")){if(!pair[1].matches("[1-9][0-9]{0,2}")||Integer.parseInt(pair[1])>100)return false;}
            else if(pair[0].equals("q")){
                try{String raw=metadataText(pair[1]);String normalized=java.text.Normalizer.normalize(raw,java.text.Normalizer.Form.NFC);if(raw.codePointCount(0,raw.length())>128||normalized.isEmpty()||normalized.codePointCount(0,normalized.length())>64||raw.codePoints().anyMatch(Character::isISOControl))return false;}catch(IllegalArgumentException|java.nio.charset.CharacterCodingException error){return false;}
            }else return false;
        }return true;
    }
    private String metadataText(String encoded) throws java.nio.charset.CharacterCodingException {
        if(!encoded.matches("(?:[A-Za-z0-9._~*+\\-]|%[0-9A-Fa-f]{2})*"))throw new IllegalArgumentException();
        var bytes=new java.io.ByteArrayOutputStream();for(int i=0;i<encoded.length();i++){char c=encoded.charAt(i);if(c=='%'){bytes.write(Integer.parseInt(encoded.substring(i+1,i+3),16));i+=2;}else bytes.write(c=='+'?' ':c);}
        return java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
    }
    private boolean propertyQuery(HttpServletRequest request) {
        if(!"GET".equals(request.getMethod())||request.getQueryString().length()>2600)return false;var used=new HashSet<String>();
        for(String part:request.getQueryString().split("&",-1)){
            var pair=part.split("=",-1);if(pair.length!=2||!used.add(pair[0]))return false;
            if(pair[0].equals("after")||pair[0].equals("tagId")){if(!pair[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))return false;}
            else if(pair[0].equals("limit")){if(!pair[1].matches("[1-9][0-9]{0,2}")||Integer.parseInt(pair[1])>100)return false;}
            else if(Set.of("value","minValue","maxValue").contains(pair[0])){
                if(!pair[0].equals("value")&&pair[1].isEmpty())return false;
                if(!pair[1].matches("(?:[A-Za-z0-9._~*+\\-]|%[0-9A-Fa-f]{2})*"))return false;
                try{
                    var bytes=new java.io.ByteArrayOutputStream();for(int i=0;i<pair[1].length();i++){char c=pair[1].charAt(i);if(c=='%'){bytes.write(Integer.parseInt(pair[1].substring(i+1,i+3),16));i+=2;}else bytes.write(c=='+'?' ':c);}
                    String value=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
                    if(value.codePointCount(0,value.length())>200||value.indexOf(0)>=0)return false;
                }catch(IllegalArgumentException|java.nio.charset.CharacterCodingException error){return false;}
            }else return false;
        }return !used.contains("value")||!(used.contains("minValue")||used.contains("maxValue"));
    }
    private boolean validSearchQuery(HttpServletRequest request) {
        if(!"GET".equals(request.getMethod()) || request.getQueryString().length()>3072)return false;
        var used=new HashSet<String>();String q=null;int offset=0,limit=20;
        try {
            for(String part:request.getQueryString().split("&",-1)) {
                var pair=part.split("=",-1);if(pair.length!=2 || !used.add(pair[0]))return false;
                if("q".equals(pair[0])) {if(!pair[1].matches("[A-Za-z0-9._~!$'()*+,;:@/?%\\-]*"))return false;q=java.net.URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8);}
                else if("offset".equals(pair[0]) || "limit".equals(pair[0])) {if(!pair[1].matches("0|[1-9][0-9]{0,3}"))return false;int value=Integer.parseInt(pair[1]);if("offset".equals(pair[0]))offset=value;else limit=value;}
                else if("kind".equals(pair[0])&&request.getRequestURI().endsWith("/search/network")){if(!Set.of("NOTE","CONCEPT","CLAIM","BOOKMARK","QUESTION","DECISION").contains(pair[1]))return false;}
                else return false;
            }
            return q!=null && !q.isBlank() && q.codePointCount(0,q.length())<=200 && !q.codePoints().anyMatch(Character::isISOControl) && limit>=1 && limit<=50 && offset<=1000-limit;
        }catch(IllegalArgumentException e){return false;}
    }
    private String randomHex(int size) {
        byte[] bytes=new byte[size];do { random.nextBytes(bytes); } while(Arrays.equals(bytes,new byte[size]));return HexFormat.of().formatHex(bytes);
    }
}
