package io.knowledgebrain.collaboration;
import java.nio.charset.StandardCharsets;
record SubmissionFeedbackInput(String content,long submissionVersion,long assignmentVersion) {
 static SubmissionFeedbackInput parse(byte[] bytes){
  if(bytes.length>20480)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,"Feedback request too large");
  var v=GroupInput.json(bytes);if(v==null||!v.isObject()||v.size()!=3||!v.path("content").isString())throw GroupInput.bad();
  for(var n:java.util.List.of("submissionVersion","assignmentVersion"))if(!v.path(n).isIntegralNumber()||!v.path(n).canConvertToLong()||v.path(n).asLong()<1)throw GroupInput.bad();
  var result=new SubmissionFeedbackInput(v.path("content").asString(),v.path("submissionVersion").asLong(),v.path("assignmentVersion").asLong());validate(result.content(),result.submissionVersion(),result.assignmentVersion());return result;
 }
 static void validate(String content,long submissionVersion,long assignmentVersion){
  if(submissionVersion<1||assignmentVersion<1||content==null||content.isBlank()||!GroupInput.unicode(content)||content.getBytes(StandardCharsets.UTF_8).length>8192)throw GroupInput.bad();
 }
}
