package io.knowledgebrain.collaboration;
import java.nio.charset.StandardCharsets;
record SubmissionGradeInput(int score,String reason,long submissionVersion,long assignmentVersion) {
 static SubmissionGradeInput parse(byte[] bytes){
  if(bytes.length>20480)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,"Grade request too large");
  var v=GroupInput.json(bytes);if(v==null||!v.isObject()||v.size()!=4||!v.path("score").isIntegralNumber()||!v.path("score").canConvertToInt()||!v.path("reason").isString())throw GroupInput.bad();
  for(var n:java.util.List.of("submissionVersion","assignmentVersion"))if(!v.path(n).isIntegralNumber()||!v.path(n).canConvertToLong()||v.path(n).asLong()<1)throw GroupInput.bad();
  var result=new SubmissionGradeInput(v.path("score").asInt(),v.path("reason").asString(),v.path("submissionVersion").asLong(),v.path("assignmentVersion").asLong());validate(result.score(),result.reason(),result.submissionVersion(),result.assignmentVersion());return result;
 }
 static void validate(int score,String reason,long submissionVersion,long assignmentVersion){
  if(score<0||score>100||submissionVersion<1||assignmentVersion<1||reason==null||reason.isBlank()||!GroupInput.unicode(reason)||reason.getBytes(StandardCharsets.UTF_8).length>8192)throw GroupInput.bad();
 }
}
