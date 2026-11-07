package io.knowledgebrain.learning;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoadmapGuidanceRulesTest {
 static UUID id(int n){return UUID.fromString("01900000-0000-7000-8000-"+String.format("%012x",n));}
 static RoadmapGraph.Edge edge(int id,String kind,int from,int to){return new RoadmapGraph.Edge(id(1000+id),kind,id(from),id(to));}
 static RoadmapStore.Roadmap graph(int count,List<RoadmapGraph.Edge> edges){return new RoadmapStore.Roadmap(id(900),id(901),"PRIVATE","ACTIVE",2,"Study","","2026-10-15T10:00:00Z","2026-10-15T10:00:00Z",java.util.stream.IntStream.rangeClosed(1,count).mapToObj(n->new RoadmapGraph.Node(id(n),"Node "+n,"")).toList(),edges);}
 static List<RoadmapGuidanceRules.Basis> basis(int count,Map<Integer,String> statuses){return java.util.stream.IntStream.rangeClosed(1,count).mapToObj(n->new RoadmapGuidanceRules.Basis(id(n),statuses.getOrDefault(n,"NOT_STARTED"),statuses.containsKey(n)?1:0)).toList();}
 static List<UUID> ids(List<RoadmapGuidanceRules.GuidanceNode> nodes){return nodes.stream().map(RoadmapGuidanceRules.GuidanceNode::nodeId).toList();}

 @Test void sixRelationsHaveOnlyTheirExplicitSemantics(){
  var edges=new ArrayList<RoadmapGraph.Edge>();int n=0;for(String kind:List.of("PREREQUISITE_OF","RECOMMENDED_BEFORE","PART_OF","REQUIRED_FOR","OPTIONAL_FOR","RELATED_TO"))edges.add(edge(++n,kind,1,n+1));
  var result=RoadmapGuidanceRules.compute(graph(7,edges),basis(7,Map.of()));
  assertEquals(List.of(id(1),id(4),id(6),id(7),id(3)),ids(result.suggestions()));assertEquals(List.of(id(2),id(5)),ids(result.waiting()));
  assertEquals(List.of("PREREQUISITE_OF"),result.waiting().getFirst().requiredPredecessors().getFirst().kinds());assertEquals(List.of("REQUIRED_FOR"),result.waiting().getLast().requiredPredecessors().getFirst().kinds());
  assertEquals(1,result.suggestions().getLast().unmetRecommendedCount());assertEquals(List.of("RECOMMENDED_BEFORE"),result.suggestions().getLast().recommendedPredecessors().getFirst().kinds());
 }
 @Test void completedDirectPredecessorSatisfiesNextNodeWithoutTransitiveInference(){
  var g=graph(3,List.of(edge(1,"PREREQUISITE_OF",1,2),edge(2,"REQUIRED_FOR",2,3)));
  var result=RoadmapGuidanceRules.compute(g,basis(3,Map.of(2,"COMPLETED")));
  assertEquals(List.of(id(1),id(3)),ids(result.suggestions()));assertTrue(result.waiting().isEmpty());assertEquals("COMPLETED",result.basis().get(1).status());
 }
 @Test void inProgressPrecedesSoftCountAndUuidBreaksTies(){
  var g=graph(5,List.of(edge(1,"RECOMMENDED_BEFORE",1,3),edge(2,"RECOMMENDED_BEFORE",2,3),edge(3,"RECOMMENDED_BEFORE",1,4)));
  var result=RoadmapGuidanceRules.compute(g,basis(5,Map.of(3,"IN_PROGRESS",4,"IN_PROGRESS")));
  assertEquals(List.of(id(4),id(3),id(1),id(2),id(5)),ids(result.suggestions()));assertEquals(List.of(1,2,0,0,0),result.suggestions().stream().map(RoadmapGuidanceRules.GuidanceNode::unmetRecommendedCount).toList());
 }
 @Test void explanationsGroupUniquePredecessorsAndRetainMultipleRelationKinds(){
  var g=graph(3,List.of(edge(1,"REQUIRED_FOR",2,3),edge(2,"PREREQUISITE_OF",2,3),edge(3,"PREREQUISITE_OF",1,3),edge(4,"RECOMMENDED_BEFORE",2,3)));
  var row=RoadmapGuidanceRules.compute(g,basis(3,Map.of())).waiting().getFirst();
  assertEquals(List.of(id(1),id(2)),row.requiredPredecessors().stream().map(RoadmapGuidanceRules.Predecessor::nodeId).toList());assertEquals(List.of("PREREQUISITE_OF","REQUIRED_FOR"),row.requiredPredecessors().getLast().kinds());assertEquals(1,row.unmetRecommendedCount());assertEquals(id(2),row.recommendedPredecessors().getFirst().nodeId());
 }
 @Test void completedNodesAndCompletedSoftPredecessorsAreExcluded(){
  var result=RoadmapGuidanceRules.compute(graph(3,List.of(edge(1,"RECOMMENDED_BEFORE",1,3))),basis(3,Map.of(1,"COMPLETED",2,"IN_PROGRESS")));
  assertEquals(List.of(id(2),id(3)),ids(result.suggestions()));assertTrue(result.suggestions().getLast().recommendedPredecessors().isEmpty());assertEquals(3,result.basis().size());
 }
 @Test void inputPermutationNeverChangesResponse(){
  var g=graph(5,List.of(edge(1,"REQUIRED_FOR",1,5),edge(2,"PREREQUISITE_OF",2,5),edge(3,"RECOMMENDED_BEFORE",1,3)));var b=basis(5,Map.of(4,"IN_PROGRESS"));var expected=RoadmapGuidanceRules.compute(g,b);var random=new Random(42);
  for(int n=0;n<30;n++){var nodes=new ArrayList<>(g.nodes());var edges=new ArrayList<>(g.edges());var shuffled=new ArrayList<>(b);Collections.shuffle(nodes,random);Collections.shuffle(edges,random);Collections.shuffle(shuffled,random);var permuted=new RoadmapStore.Roadmap(g.roadmapId(),g.spaceId(),g.visibility(),g.state(),g.version(),g.title(),g.description(),g.createdAt(),g.updatedAt(),nodes,edges);assertEquals(expected,RoadmapGuidanceRules.compute(permuted,shuffled));}
 }
 @Test void emptyAndAllCompletedGraphsHaveNoSuggestionsOrWaiting(){
  var empty=RoadmapGuidanceRules.compute(graph(0,List.of()),List.of());assertTrue(empty.basis().isEmpty());assertTrue(empty.suggestions().isEmpty());assertTrue(empty.waiting().isEmpty());var done=RoadmapGuidanceRules.compute(graph(2,List.of(edge(1,"REQUIRED_FOR",1,2))),basis(2,Map.of(1,"COMPLETED",2,"COMPLETED")));assertEquals(2,done.basis().size());assertTrue(done.suggestions().isEmpty());assertTrue(done.waiting().isEmpty());
 }
 @Test void maximumGraphRetainsBoundsWithoutLosingExplanations(){
  var edges=new ArrayList<RoadmapGraph.Edge>();outer:for(int from=1;from<=100;from++)for(int to=from+1;to<=100;to++){edges.add(edge(edges.size()+1,"PREREQUISITE_OF",from,to));if(edges.size()==500)break outer;}
  var result=RoadmapGuidanceRules.compute(graph(100,edges),basis(100,Map.of()));assertEquals(100,result.basis().size());assertEquals(100,result.suggestions().size()+result.waiting().size());assertEquals(500,result.waiting().stream().flatMap(n->n.requiredPredecessors().stream()).mapToInt(p->p.kinds().size()).sum());
 }
 @Test void malformedCanonicalBasisFailsClosed(){
  var g=graph(2,List.of());var valid=basis(2,Map.of());
  for(var bad:List.of(List.of(valid.getFirst()),List.of(valid.getFirst(),valid.getFirst()),List.of(valid.getFirst(),new RoadmapGuidanceRules.Basis(id(3),"NOT_STARTED",0)),List.of(valid.getFirst(),new RoadmapGuidanceRules.Basis(id(2),"COMPLETED",0)),List.of(valid.getFirst(),new RoadmapGuidanceRules.Basis(id(2),"UNKNOWN",1)),List.of(valid.getFirst(),new RoadmapGuidanceRules.Basis(id(2),"NOT_STARTED",-1))))assertThrows(IllegalStateException.class,()->RoadmapGuidanceRules.compute(g,bad));
 }
 @Test void corruptGraphIdsDuplicateRelationsCyclesAndOversizedGraphsFailClosed(){
  assertThrows(IllegalStateException.class,()->RoadmapGuidanceRules.compute(graph(101,List.of()),basis(101,Map.of())));
  for(var edges:List.of(List.of(edge(1,"UNKNOWN",1,2)),List.of(edge(1,"REQUIRED_FOR",1,2),edge(2,"REQUIRED_FOR",1,2)),List.of(edge(1,"REQUIRED_FOR",1,2),edge(2,"RECOMMENDED_BEFORE",2,1)),List.of(edge(1,"RELATED_TO",2,1)),List.of(edge(1,"REQUIRED_FOR",1,3))))assertThrows(IllegalStateException.class,()->RoadmapGuidanceRules.compute(graph(2,edges),basis(2,Map.of())));
  var corrupt=new RoadmapStore.Roadmap(guid(),id(901),"PRIVATE","ACTIVE",1,"Study","","2026-10-15T10:00:00Z","2026-10-15T10:00:00Z",List.of(),List.of());assertThrows(IllegalStateException.class,()->RoadmapGuidanceRules.compute(corrupt,List.of()));
 }
 private static UUID guid(){return UUID.fromString("00000000-0000-4000-8000-000000000001");}
}
