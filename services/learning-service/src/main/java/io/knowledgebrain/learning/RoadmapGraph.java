package io.knowledgebrain.learning;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Canonical single-domain graph rules; draft keys are never persistent identifiers. */
final class RoadmapGraph {
 static final Set<String> KINDS=Set.of("PREREQUISITE_OF","RECOMMENDED_BEFORE","PART_OF","REQUIRED_FOR","OPTIONAL_FOR","RELATED_TO");
 static final Set<String> PRECEDENCE=Set.of("PREREQUISITE_OF","RECOMMENDED_BEFORE","REQUIRED_FOR");
 record Node(UUID nodeId,String title,String description){}
 record Edge(UUID edgeId,String kind,UUID sourceNodeId,UUID targetNodeId){}
 record Graph(List<Node> nodes,List<Edge> edges){Graph{nodes=List.copyOf(nodes);edges=List.copyOf(edges);}}
 record NodeEdit(String key,String title,String description){}
 record EdgeEdit(String key,String kind,String source,String target){}
 record Edit(List<NodeEdit> nodes,List<EdgeEdit> edges){}
 static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid roadmap graph, duplicate relation or cyclic ordering");}
 static UUID id(String raw){try{var id=UUID.fromString(raw);if(!id.toString().equals(raw)||id.version()!=7||id.variant()!=2)throw bad();return id;}catch(RuntimeException e){throw bad();}}
 static void id(UUID id){if(id==null||id.version()!=7||id.variant()!=2)throw bad();}
 static boolean fresh(String key){return key!=null&&key.matches("new:[A-Za-z0-9_-]{1,32}");}
 static void key(String key){if(!fresh(key))id(key);}
 static void text(String title,String description,int bytes){if(title==null||title.isBlank()||title.codePointCount(0,title.length())>200||title.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xd800&&c<=0xdfff)||description==null||description.getBytes(StandardCharsets.UTF_8).length>bytes||description.codePoints().anyMatch(c->c>=0xd800&&c<=0xdfff||Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw bad();}
 static void validate(Edit edit){if(edit==null||edit.nodes()==null||edit.edges()==null||edit.nodes().size()>100||edit.edges().size()>500)throw bad();var nodes=new HashSet<String>();var edges=new HashSet<String>();var semantics=new HashSet<String>();
  for(var node:edit.nodes()){if(node==null)throw bad();key(node.key());text(node.title(),node.description(),2048);if(!nodes.add(node.key()))throw bad();}
  for(var edge:edit.edges()){if(edge==null)throw bad();key(edge.key());if(!edges.add(edge.key())||edge.kind()==null||!KINDS.contains(edge.kind())||!nodes.contains(edge.source())||!nodes.contains(edge.target())||edge.source().equals(edge.target()))throw bad();String a=edge.source(),b=edge.target();if(edge.kind().equals("RELATED_TO")&&a.compareTo(b)>0){var swap=a;a=b;b=swap;}if(!semantics.add(edge.kind()+":"+a+":"+b))throw bad();}
  acyclic(nodes,edit.edges(),PRECEDENCE);acyclic(nodes,edit.edges(),Set.of("PART_OF"));
 }
 private static void acyclic(Set<String> nodes,List<EdgeEdit> edges,Set<String> kinds){var outgoing=new HashMap<String,List<String>>();var degrees=new HashMap<String,Integer>();for(String n:nodes){outgoing.put(n,new ArrayList<>());degrees.put(n,0);}for(var edge:edges)if(kinds.contains(edge.kind())){outgoing.get(edge.source()).add(edge.target());degrees.merge(edge.target(),1,Integer::sum);}var queue=new ArrayDeque<String>();degrees.forEach((n,d)->{if(d==0)queue.add(n);});int visited=0;while(!queue.isEmpty()){String n=queue.remove();visited++;for(String target:outgoing.get(n))if(degrees.merge(target,-1,Integer::sum)==0)queue.add(target);}if(visited!=nodes.size())throw bad();}
}
