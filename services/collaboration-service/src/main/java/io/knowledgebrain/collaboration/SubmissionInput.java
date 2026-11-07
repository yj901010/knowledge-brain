package io.knowledgebrain.collaboration;
import java.nio.charset.StandardCharsets;
record SubmissionInput(String content,long assignmentVersion) {
 static SubmissionInput parse(byte[] bytes){
  var v=GroupInput.json(bytes);if(v==null||!v.isObject()||v.size()!=2||!v.path("content").isString()||!v.path("assignmentVersion").isIntegralNumber()||!v.path("assignmentVersion").canConvertToLong())throw GroupInput.bad();
  long version=v.path("assignmentVersion").asLong();String content=v.path("content").asString();validate(content,version);return new SubmissionInput(content,version);
 }
 static void validate(String content,long version){if(content==null||content.isBlank()||version<1)throw GroupInput.bad();if(!GroupInput.unicode(content)||content.getBytes(StandardCharsets.UTF_8).length>32768)throw GroupInput.bad();}
}
