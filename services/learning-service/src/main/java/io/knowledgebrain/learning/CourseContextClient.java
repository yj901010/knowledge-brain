package io.knowledgebrain.learning;
import java.net.URI;import java.net.http.HttpClient;import java.nio.file.*;import java.security.KeyStore;import java.security.cert.CertificateFactory;import java.time.*;import java.util.*;import javax.net.ssl.*;
import org.springframework.http.*;import org.springframework.http.client.JdkClientHttpRequestFactory;import org.springframework.web.client.*;import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;import tools.jackson.databind.DeserializationFeature;import tools.jackson.core.StreamReadFeature;
final class CourseContextClient {
 record Study(UUID studyId,long studyVersion,String title) {}
 record Reference(UUID principalId,UUID groupId,UUID spaceId,UUID courseId,long courseVersion,String courseState,String role,UUID enrollmentId,long enrollmentVersion,String enrollmentState,List<Study> studies){boolean writable(){return Set.of("OWNER","MEMBER").contains(role)&&enrollmentState.equals("ENROLLED")&&Set.of("ACTIVE","COMPLETED").contains(courseState);}}
 record Eligible(UUID principalId,long membershipVersion,UUID enrollmentId,long enrollmentVersion) {}
 record Sharing(Reference learning,long policyVersion,boolean enabled,String audience,long membershipVersion,List<Eligible> eligibleParticipants) {}
 private final RestClient client;
 private final JsonMapper json=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
 CourseContextClient(RestClient client){this.client=client;}
 CourseContextClient(String url,String trustFile)throws Exception{
        URI endpoint=URI.create(url);
        if(!"https".equals(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getRawUserInfo()!=null || endpoint.getRawQuery()!=null || endpoint.getRawFragment()!=null || !endpoint.getRawPath().isEmpty())throw new IllegalArgumentException("HTTPS Collaboration origin required");
        var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);
        try(var input=Files.newInputStream(Path.of(trustFile))) {
            int index=0;for(var certificate:CertificateFactory.getInstance("X.509").generateCertificates(input))trust.setCertificateEntry("collaboration-ca-"+index++,certificate);
        }
        var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(trust);
        var tls=SSLContext.getInstance("TLS");tls.init(null,factory.getTrustManagers(),null);
        var transport=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        var requests=new JdkClientHttpRequestFactory(transport);requests.setReadTimeout(Duration.ofSeconds(5));
        this.client=RestClient.builder().baseUrl(url).requestFactory(requests).build();
 }
 Reference check(UUID group,UUID course,String token,String trace){
  if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Session required");
  try{return client.get().uri("/api/groups/{group}/courses/{course}/learning-context",group,course).header("Cookie","__Host-brain_guest="+token).header("traceparent",trace).exchange((request,response)->{
   int code=response.getStatusCode().value();if(Set.of(401,403,404).contains(code))throw new ResponseStatusException(response.getStatusCode(),"Course context unavailable");if(code!=200||response.getHeaders().getContentType()==null||!response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON))throw unavailable();byte[] bytes=response.getBody().readNBytes(65537);if(bytes.length>65536)throw unavailable();
   var v=json.readTree(bytes);if(v==null||!v.isObject()||v.size()!=11)throw unavailable();for(String n:List.of("principalId","groupId","spaceId","courseId","courseState","role","enrollmentState"))if(!v.path(n).isString())throw unavailable();for(String n:List.of("courseVersion","enrollmentVersion"))if(!v.path(n).isIntegralNumber()||!v.path(n).canConvertToLong())throw unavailable();if(!(v.path("enrollmentId").isNull()||v.path("enrollmentId").isString())||!v.path("studies").isArray())throw unavailable();
   for(var study:v.path("studies"))if(!study.isObject()||study.size()!=3||!study.path("studyId").isString()||!study.path("studyVersion").isIntegralNumber()||!study.path("studyVersion").canConvertToLong()||!study.path("title").isString())throw unavailable();
   Reference ref=json.treeToValue(v,Reference.class);validate(ref);if(!group.equals(ref.groupId())||!course.equals(ref.courseId()))throw unavailable();return ref;
  });}catch(ResponseStatusException e){throw e;}catch(RuntimeException e){throw unavailable();}
 }
 Sharing sharing(UUID group,UUID course,String token,String trace,List<UUID> participants){
  if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Session required");if(participants==null||participants.size()>100||new HashSet<>(participants).size()!=participants.size())throw RoadmapGraph.bad();
  try{return client.post().uri("/api/groups/{group}/courses/{course}/progress-sharing-context",group,course).header("Cookie","__Host-brain_guest="+token).header("traceparent",trace).body(Map.of("participantIds",participants)).exchange((request,response)->{
   int code=response.getStatusCode().value();if(Set.of(401,403,404).contains(code))throw new ResponseStatusException(response.getStatusCode(),"Sharing context unavailable");if(code!=200||response.getHeaders().getContentType()==null||!response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON))throw unavailable();var bytes=response.getBody().readNBytes(98305);if(bytes.length>98304)throw unavailable();var v=json.readTree(bytes);if(v==null||!v.isObject()||v.size()!=6||!v.path("learning").isObject()||v.path("learning").size()!=11||!v.path("eligibleParticipants").isArray()||v.path("eligibleParticipants").size()>participants.size()||!v.path("audience").isString()||!v.path("enabled").isBoolean())throw unavailable();for(var n:List.of("policyVersion","membershipVersion"))if(!v.path(n).isIntegralNumber()||!v.path(n).canConvertToLong())throw unavailable();
   var source=v.path("learning");for(var n:List.of("principalId","groupId","spaceId","courseId","courseState","role","enrollmentState"))if(!source.path(n).isString())throw unavailable();for(var n:List.of("courseVersion","enrollmentVersion"))if(!source.path(n).isIntegralNumber()||!source.path(n).canConvertToLong())throw unavailable();if(!(source.path("enrollmentId").isNull()||source.path("enrollmentId").isString())||!source.path("studies").isArray())throw unavailable();for(var study:source.path("studies"))if(!study.isObject()||study.size()!=3||!study.path("studyId").isString()||!study.path("studyVersion").isIntegralNumber()||!study.path("studyVersion").canConvertToLong()||!study.path("title").isString())throw unavailable();
   for(var e:v.path("eligibleParticipants")){if(!e.isObject()||e.size()!=4||!e.path("principalId").isString()||!e.path("enrollmentId").isString())throw unavailable();for(var n:List.of("membershipVersion","enrollmentVersion"))if(!e.path(n).isIntegralNumber()||!e.path(n).canConvertToLong())throw unavailable();}
   var a=json.treeToValue(v,Sharing.class);validate(a.learning());if(!group.equals(a.learning().groupId())||!course.equals(a.learning().courseId())||a.policyVersion()<0||a.membershipVersion()<1||a.enabled()&&a.policyVersion()<1||!"OWNER".equals(a.audience())||!participants.isEmpty()&&!a.learning().role().equals("OWNER"))throw unavailable();var seen=new HashSet<UUID>();for(var e:a.eligibleParticipants()){if(!participants.contains(e.principalId())||!seen.add(e.principalId())||e.membershipVersion()<1||e.enrollmentVersion()<1||e.enrollmentId()==null||e.enrollmentId().version()!=7||e.enrollmentId().variant()!=2)throw unavailable();}return a;
  });}catch(ResponseStatusException e){throw e;}catch(RuntimeException e){throw unavailable();}
 }
 static void validate(Reference ref){
  if(ref==null||ref.principalId()==null||ref.groupId()==null||ref.spaceId()==null||ref.courseId()==null||ref.courseVersion()<1||ref.role()==null||!Set.of("OWNER","MEMBER","VIEWER").contains(ref.role())||ref.courseState()==null||!Set.of("DRAFT","ACTIVE","COMPLETED","RETIRED").contains(ref.courseState())||ref.enrollmentState()==null||!Set.of("NOT_ENROLLED","ENROLLED","WITHDRAWN").contains(ref.enrollmentState()))throw unavailable();
  for(var id:List.of(ref.principalId(),ref.groupId(),ref.spaceId(),ref.courseId()))if(id.version()!=7||id.variant()!=2)throw unavailable();
  if(ref.enrollmentState().equals("NOT_ENROLLED")){if(ref.enrollmentId()!=null||ref.enrollmentVersion()!=0)throw unavailable();}else if(ref.enrollmentId()==null||ref.enrollmentId().version()!=7||ref.enrollmentId().variant()!=2||ref.enrollmentVersion()<1)throw unavailable();
  if(ref.studies()==null||ref.studies().size()>50||Set.of("ACTIVE","COMPLETED").contains(ref.courseState())&&ref.studies().isEmpty())throw unavailable();var seen=new HashSet<UUID>();for(var study:ref.studies()){if(study==null||study.studyId()==null||study.studyId().version()!=7||study.studyId().variant()!=2||!seen.add(study.studyId())||study.studyVersion()<1)throw unavailable();try{RoadmapGraph.text(study.title(),"",0);}catch(ResponseStatusException e){throw unavailable();}}
 }
 private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Collaboration context unavailable");}
}
