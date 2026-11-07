package io.knowledgebrain.collaboration;
record FeedbackRetractionInput(String state,String reason) {
 static FeedbackRetractionInput parse(byte[] bytes){
  if(bytes.length>20480)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,"Retraction request too large");
  var v=GroupInput.json(bytes);if(v==null||!v.isObject()||v.size()!=2||!v.path("state").isString()||!v.path("reason").isString())throw GroupInput.bad();
  var result=new FeedbackRetractionInput(v.path("state").asString(),v.path("reason").asString());validate(result);return result;
 }
 static void validate(FeedbackRetractionInput input){
  if(input==null||input.state()==null||!java.util.Set.of("RETRACTED","RESTORED").contains(input.state()))throw GroupInput.bad();
  SubmissionFeedbackInput.validate(input.reason(),1,1);
 }
}
