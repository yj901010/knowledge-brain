package io.knowledgebrain.learning;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** One owner-database statement gives graph and progress the same MVCC snapshot. No writes/cache. */
final class RoadmapGuidanceStore {
 private final JdbcTemplate jdbc;
 private final JsonMapper json=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
 record ActiveNode(UUID nodeId,String title,String description,UUID progressId,Long progressVersion,String status,String updatedAt,LearningStudyStore.Progress snapshot){}
 RoadmapGuidanceStore(DataSource dataSource){jdbc=new JdbcTemplate(dataSource);}
 private static final String READ="""
  SELECT r.version,r.visibility,r.state,r.title,r.description,r.created_at,r.updated_at,
    CASE WHEN octet_length(v.snapshot::text)<=1048576 THEN v.snapshot::text END graph,
    nodes.items::text nodes,edges.items::text edges
  FROM roadmap r
  LEFT JOIN roadmap_revision v ON v.roadmap_id=r.roadmap_id AND v.version=r.version
  LEFT JOIN LATERAL (
    SELECT coalesce(jsonb_agg(jsonb_build_object(
      'nodeId',n.node_id,'title',CASE WHEN octet_length(n.title)<=800 THEN n.title END,
      'description',CASE WHEN octet_length(n.description)<=2048 THEN n.description END,
      'progressId',p.progress_id,'progressVersion',p.version,'status',p.status,'updatedAt',p.updated_at,
      'snapshot',CASE WHEN octet_length(pv.snapshot::text)<=8192 THEN pv.snapshot END
    ) ORDER BY n.node_id),'[]'::jsonb) items
    FROM (SELECT node_id,title,description FROM roadmap_node WHERE roadmap_id=r.roadmap_id AND state='ACTIVE' ORDER BY node_id LIMIT 101) n
    LEFT JOIN node_progress p ON p.roadmap_id=r.roadmap_id AND p.node_id=n.node_id
    LEFT JOIN node_progress_revision pv ON pv.progress_id=p.progress_id AND pv.version=p.version
  ) nodes ON true
  LEFT JOIN LATERAL (
    SELECT coalesce(jsonb_agg(jsonb_build_object('edgeId',e.edge_id,'kind',e.kind,'sourceNodeId',e.source_node_id,'targetNodeId',e.target_node_id) ORDER BY e.edge_id),'[]'::jsonb) items
    FROM (SELECT edge_id,kind,source_node_id,target_node_id FROM roadmap_edge WHERE roadmap_id=r.roadmap_id AND state='ACTIVE' ORDER BY edge_id LIMIT 501) e
  ) edges ON true
  WHERE r.space_id=? AND r.roadmap_id=? AND r.state='ACTIVE'
  """;

 RoadmapGuidanceRules.Guidance read(UUID space,UUID roadmap){
  RoadmapGraph.id(space);RoadmapGraph.id(roadmap);
  var rows=jdbc.query(READ,(rs,index)->{
   try {
    String raw=rs.getString("graph");if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>1048576)throw RoadmapGuidanceRules.invalid();
    var graph=json.readValue(raw,RoadmapStore.Roadmap.class);
    if(json.writeValueAsBytes(graph).length>524288)throw RoadmapGuidanceRules.invalid();
    if(!space.equals(graph.spaceId())||!roadmap.equals(graph.roadmapId())||graph.version()!=rs.getLong("version")||!"PRIVATE".equals(rs.getString("visibility"))||!"ACTIVE".equals(rs.getString("state"))||!Objects.equals(graph.title(),rs.getString("title"))||!Objects.equals(graph.description(),rs.getString("description"))||!sameTime(graph.createdAt(),rs.getTimestamp("created_at").toInstant())||!sameTime(graph.updatedAt(),rs.getTimestamp("updated_at").toInstant()))throw RoadmapGuidanceRules.invalid();
    var active=Arrays.asList(json.readValue(rs.getString("nodes"),ActiveNode[].class));var edges=Arrays.asList(json.readValue(rs.getString("edges"),RoadmapGraph.Edge[].class));
    if(active.size()>100||edges.size()>500)throw RoadmapGuidanceRules.invalid();
    var nodeHeads=active.stream().map(n->new RoadmapGraph.Node(n.nodeId(),n.title(),n.description())).toList();
    if(nodeHeads.size()!=graph.nodes().size()||!new HashSet<>(nodeHeads).equals(new HashSet<>(graph.nodes()))||edges.size()!=graph.edges().size()||!new HashSet<>(edges).equals(new HashSet<>(graph.edges())))throw RoadmapGuidanceRules.invalid();
    var basis=new ArrayList<RoadmapGuidanceRules.Basis>();
    for(var node:active){
     if(node.progressId()==null){
      if(node.progressVersion()!=null||node.status()!=null||node.updatedAt()!=null||node.snapshot()!=null)throw RoadmapGuidanceRules.invalid();
      basis.add(new RoadmapGuidanceRules.Basis(node.nodeId(),"NOT_STARTED",0));
     }else{
      RoadmapGraph.id(node.progressId());var p=node.snapshot();
      if(p==null||node.progressVersion()==null||node.progressVersion()<1||!node.progressId().equals(p.progressId())||!roadmap.equals(p.roadmapId())||!space.equals(p.spaceId())||!node.nodeId().equals(p.nodeId())||!"PRIVATE".equals(p.visibility())||!Objects.equals(node.status(),p.status())||node.progressVersion()!=p.version()||!sameTime(p.updatedAt(),OffsetDateTime.parse(node.updatedAt()).toInstant()))throw RoadmapGuidanceRules.invalid();
      basis.add(new RoadmapGuidanceRules.Basis(node.nodeId(),p.status(),p.version()));
     }
    }
    return RoadmapGuidanceRules.compute(graph,basis);
   }catch(RuntimeException failure){throw RoadmapGuidanceRules.invalid();}
  },space,roadmap);
  if(rows.isEmpty())throw RoadmapStore.missing();if(rows.size()!=1)throw RoadmapGuidanceRules.invalid();return rows.getFirst();
 }
 // Existing snapshots can retain sub-microsecond instants; PostgreSQL timestamps round to micros.
 private static boolean sameTime(String raw,Instant canonical){return raw!=null&&Duration.between(Instant.parse(raw),canonical).abs().compareTo(Duration.ofNanos(1000))<0;}
}
