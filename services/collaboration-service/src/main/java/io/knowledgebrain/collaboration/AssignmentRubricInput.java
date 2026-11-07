package io.knowledgebrain.collaboration;
import java.util.*;import java.nio.charset.StandardCharsets;
record AssignmentRubricInput(long assignmentVersion,List<Item> criteria) {
 record Item(UUID criterionId,String title,String description,int maxPoints) {}
 static AssignmentRubricInput parse(byte[] bytes){
  if(bytes.length>32768)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,"Rubric request too large");
  var v=GroupInput.json(bytes);if(v==null||!v.isObject()||v.size()!=2||!v.path("assignmentVersion").isIntegralNumber()||!v.path("assignmentVersion").canConvertToLong()||!v.path("criteria").isArray()||v.path("criteria").size()>10)throw GroupInput.bad();
  var criteria=new ArrayList<Item>();for(var n:v.path("criteria")){
   if(!n.isObject()||n.size()!=4||!(n.path("criterionId").isNull()||n.path("criterionId").isString())||!n.path("title").isString()||!n.path("description").isString()||!n.path("maxPoints").isIntegralNumber()||!n.path("maxPoints").canConvertToInt())throw GroupInput.bad();
   criteria.add(new Item(n.path("criterionId").isNull()?null:GroupInput.id(n.path("criterionId").asString()),n.path("title").asString(),n.path("description").asString(),n.path("maxPoints").asInt()));
  }var result=new AssignmentRubricInput(v.path("assignmentVersion").asLong(),List.copyOf(criteria));validate(result);return result;
 }
 static void validate(AssignmentRubricInput v){
  if(v==null||v.assignmentVersion()<1||v.criteria()==null||v.criteria().size()>10)throw GroupInput.bad();var seen=new HashSet<UUID>();int total=0;
  for(var n:v.criteria()){
   if(n==null||n.title()==null||n.title().isBlank()||!GroupInput.unicode(n.title())||n.title().codePointCount(0,n.title().length())>100||n.description()==null||!GroupInput.unicode(n.description())||n.description().getBytes(StandardCharsets.UTF_8).length>2048||n.maxPoints()<1||n.maxPoints()>100)throw GroupInput.bad();
   if(n.criterionId()!=null){GroupInput.id(n.criterionId().toString());if(!seen.add(n.criterionId()))throw GroupInput.bad();}total+=n.maxPoints();
  }if(!v.criteria().isEmpty()&&total!=100)throw GroupInput.bad();
 }
}
