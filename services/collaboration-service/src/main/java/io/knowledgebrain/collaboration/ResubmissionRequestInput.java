package io.knowledgebrain.collaboration;
record ResubmissionRequestInput(String action,String reason,long submissionVersion,long assignmentVersion) {
 static ResubmissionRequestInput parse(byte[] bytes){
  if(bytes.length>20480)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,"Request too large");
  var v=GroupInput.json(bytes);if(v==null||!v.isObject()||!v.path("action").isString())throw GroupInput.bad();
  if(v.path("action").asString().equals("CANCEL")&&v.size()==1)return new ResubmissionRequestInput("CANCEL",null,0,0);
  if(!v.path("action").asString().equals("REQUEST")||v.size()!=4||!v.path("reason").isString())throw GroupInput.bad();
  for(var n:java.util.List.of("submissionVersion","assignmentVersion"))if(!v.path(n).isIntegralNumber()||!v.path(n).canConvertToLong()||v.path(n).asLong()<1)throw GroupInput.bad();
  var result=new ResubmissionRequestInput("REQUEST",v.path("reason").asString(),v.path("submissionVersion").asLong(),v.path("assignmentVersion").asLong());validate(result);return result;
 }
 static void validate(ResubmissionRequestInput v){
  if(v==null)throw GroupInput.bad();
  if("CANCEL".equals(v.action())&&v.reason()==null&&v.submissionVersion()==0&&v.assignmentVersion()==0)return;
  if(!"REQUEST".equals(v.action()))throw GroupInput.bad();
  SubmissionFeedbackInput.validate(v.reason(),v.submissionVersion(),v.assignmentVersion());
 }
}
