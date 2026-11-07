package io.knowledgebrain.learning;

import java.util.*;

/** Pure guidance over one validated current graph/progress snapshot; never changes progress. */
final class RoadmapGuidanceRules {
 static final String ALGORITHM="GRAPH_PROGRESS_V1";
 private static final Set<String> REQUIRED=Set.of("PREREQUISITE_OF","REQUIRED_FOR");
 record Basis(UUID nodeId,String status,long progressVersion){}
 record Predecessor(UUID nodeId,String title,String status,long progressVersion,List<String> kinds){Predecessor{kinds=List.copyOf(kinds);}}
 record GuidanceNode(UUID nodeId,String title,String status,long progressVersion,int unmetRecommendedCount,List<Predecessor> requiredPredecessors,List<Predecessor> recommendedPredecessors){GuidanceNode{requiredPredecessors=List.copyOf(requiredPredecessors);recommendedPredecessors=List.copyOf(recommendedPredecessors);}}
 record Guidance(UUID roadmapId,UUID spaceId,String visibility,long roadmapVersion,String algorithm,List<Basis> basis,List<GuidanceNode> suggestions,List<GuidanceNode> waiting){Guidance{basis=List.copyOf(basis);suggestions=List.copyOf(suggestions);waiting=List.copyOf(waiting);}}

 static IllegalStateException invalid(){return new IllegalStateException("Canonical guidance basis unavailable");}
 static void validate(RoadmapStore.Roadmap graph,List<Basis> basis){
  try {
   if(graph==null||basis==null||graph.version()<1||!"PRIVATE".equals(graph.visibility())||!"ACTIVE".equals(graph.state())||graph.nodes().size()>100||graph.edges().size()>500||basis.size()!=graph.nodes().size())throw invalid();
   RoadmapGraph.id(graph.roadmapId());RoadmapGraph.id(graph.spaceId());RoadmapGraph.text(graph.title(),graph.description(),32768);
   for(var n:graph.nodes())RoadmapGraph.id(n.nodeId());for(var e:graph.edges()){RoadmapGraph.id(e.edgeId());RoadmapGraph.id(e.sourceNodeId());RoadmapGraph.id(e.targetNodeId());if("RELATED_TO".equals(e.kind())&&e.sourceNodeId().toString().compareTo(e.targetNodeId().toString())>0)throw invalid();}
   RoadmapGraph.validate(new RoadmapGraph.Edit(graph.nodes().stream().map(n->new RoadmapGraph.NodeEdit(n.nodeId().toString(),n.title(),n.description())).toList(),graph.edges().stream().map(e->new RoadmapGraph.EdgeEdit(e.edgeId().toString(),e.kind(),e.sourceNodeId().toString(),e.targetNodeId().toString())).toList()));
   var nodes=new HashSet<UUID>();graph.nodes().forEach(n->nodes.add(n.nodeId()));var seen=new HashSet<UUID>();
   for(var p:basis){if(p==null||!nodes.contains(p.nodeId())||!seen.add(p.nodeId())||!LearningStudyStore.STATUSES.contains(p.status())||p.progressVersion()<0||p.progressVersion()==0&&!"NOT_STARTED".equals(p.status()))throw invalid();}
  }catch(RuntimeException e){throw invalid();}
 }

 static Guidance compute(RoadmapStore.Roadmap graph,List<Basis> basis){
  validate(graph,basis);var progress=new HashMap<UUID,Basis>();basis.forEach(p->progress.put(p.nodeId(),p));var nodes=new HashMap<UUID,RoadmapGraph.Node>();graph.nodes().forEach(n->nodes.put(n.nodeId(),n));
  var required=new HashMap<UUID,Map<UUID,SortedSet<String>>>();var recommended=new HashMap<UUID,Map<UUID,SortedSet<String>>>();
  for(var edge:graph.edges()){
   if("COMPLETED".equals(progress.get(edge.sourceNodeId()).status()))continue;
   var destination=REQUIRED.contains(edge.kind())?required:"RECOMMENDED_BEFORE".equals(edge.kind())?recommended:null;
   if(destination!=null)destination.computeIfAbsent(edge.targetNodeId(),k->new HashMap<>()).computeIfAbsent(edge.sourceNodeId(),k->new TreeSet<>()).add(edge.kind());
  }
  var suggestions=new ArrayList<GuidanceNode>();var waiting=new ArrayList<GuidanceNode>();
  for(var node:graph.nodes()){
   var p=progress.get(node.nodeId());if("COMPLETED".equals(p.status()))continue;
   var hard=predecessors(required.get(node.nodeId()),nodes,progress);var soft=predecessors(recommended.get(node.nodeId()),nodes,progress);
   var item=new GuidanceNode(node.nodeId(),node.title(),p.status(),p.progressVersion(),soft.size(),hard,soft);(hard.isEmpty()?suggestions:waiting).add(item);
  }
  suggestions.sort(Comparator.comparingInt((GuidanceNode n)->"IN_PROGRESS".equals(n.status())?0:1).thenComparingInt(GuidanceNode::unmetRecommendedCount).thenComparing(n->n.nodeId().toString()));waiting.sort(Comparator.comparing(n->n.nodeId().toString()));
  return new Guidance(graph.roadmapId(),graph.spaceId(),"PRIVATE",graph.version(),ALGORITHM,basis.stream().sorted(Comparator.comparing(p->p.nodeId().toString())).toList(),suggestions,waiting);
 }
 private static List<Predecessor> predecessors(Map<UUID,SortedSet<String>> incoming,Map<UUID,RoadmapGraph.Node> nodes,Map<UUID,Basis> progress){
  if(incoming==null)return List.of();return incoming.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString))).map(e->{var p=progress.get(e.getKey());return new Predecessor(e.getKey(),nodes.get(e.getKey()).title(),p.status(),p.progressVersion(),List.copyOf(e.getValue()));}).toList();
 }
}
