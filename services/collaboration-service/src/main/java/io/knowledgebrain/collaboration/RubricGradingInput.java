package io.knowledgebrain.collaboration;
import java.util.*;import java.nio.charset.StandardCharsets;
record RubricGradingInput(String mode,long submissionVersion,long assignmentVersion,long rubricVersion,String reason,Integer score,List<Item> criteria){
 record Item(UUID criterionId,int score,String comment){}
 static RubricGradingInput parse(byte[] bytes){
  if(bytes.length>32768)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,"Grading request too large");
  var v=GroupInput.json(bytes);if(v==null||!v.isObject()||!v.path("mode").isString()||!v.path("reason").isString()||!v.path("submissionVersion").isIntegralNumber()||!v.path("submissionVersion").canConvertToLong()||!v.path("assignmentVersion").isIntegralNumber()||!v.path("assignmentVersion").canConvertToLong())throw GroupInput.bad();
  String mode=v.path("mode").asString();var items=new ArrayList<Item>();long rubricVersion=0;Integer score=null;
  if(mode.equals("DIRECT")){if(v.size()!=5||!v.path("score").isIntegralNumber()||!v.path("score").canConvertToInt())throw GroupInput.bad();score=v.path("score").asInt();}
  else if(mode.equals("RUBRIC")){if(v.size()!=6||!v.path("rubricVersion").isIntegralNumber()||!v.path("rubricVersion").canConvertToLong()||!v.path("criteria").isArray())throw GroupInput.bad();rubricVersion=v.path("rubricVersion").asLong();for(var n:v.path("criteria")){if(!n.isObject()||n.size()!=3||!n.path("criterionId").isString()||!n.path("score").isIntegralNumber()||!n.path("score").canConvertToInt()||!n.path("comment").isString())throw GroupInput.bad();items.add(new Item(GroupInput.id(n.path("criterionId").asString()),n.path("score").asInt(),n.path("comment").asString()));}}
  else throw GroupInput.bad();
  var result=new RubricGradingInput(mode,v.path("submissionVersion").asLong(),v.path("assignmentVersion").asLong(),rubricVersion,v.path("reason").asString(),score,List.copyOf(items));validate(result);return result;
 }
 static void validate(RubricGradingInput v){
  if(v==null||v.mode()==null||v.criteria()==null)throw GroupInput.bad();SubmissionGradeInput.validate(v.mode().equals("DIRECT")&&v.score()!=null?v.score():0,v.reason(),v.submissionVersion(),v.assignmentVersion());
  if(v.mode().equals("DIRECT")){if(v.score()==null||v.rubricVersion()!=0||!v.criteria().isEmpty())throw GroupInput.bad();return;}
  if(!v.mode().equals("RUBRIC")||v.score()!=null||v.rubricVersion()<1||v.criteria().isEmpty()||v.criteria().size()>10)throw GroupInput.bad();var seen=new HashSet<UUID>();
  for(var n:v.criteria()){if(n==null||n.criterionId()==null||n.score()<0||n.score()>100||n.comment()==null||!GroupInput.unicode(n.comment())||n.comment().getBytes(StandardCharsets.UTF_8).length>2048||!seen.add(n.criterionId()))throw GroupInput.bad();GroupInput.id(n.criterionId().toString());}
 }
}
