export type GuidanceProgress={nodeId:string;status:"NOT_STARTED"|"IN_PROGRESS"|"COMPLETED";progressVersion:number};
export type GuidancePredecessor=GuidanceProgress&{title:string;kinds:string[]};
export type GuidanceNode=GuidanceProgress&{title:string;unmetRecommendedCount:number;requiredPredecessors:GuidancePredecessor[];recommendedPredecessors:GuidancePredecessor[]};
export type LearningGuidance={roadmapId:string;spaceId:string;visibility:"PRIVATE";roadmapVersion:number;algorithm:"GRAPH_PROGRESS_V1";basis:GuidanceProgress[];suggestions:GuidanceNode[];waiting:GuidanceNode[]};

const id=(v:unknown):v is string=>typeof v==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);
const integer=(v:unknown):v is number=>typeof v==="number"&&Number.isSafeInteger(v)&&v>=0;
const title=(v:unknown):v is string=>typeof v==="string"&&Array.from(v).length>0&&Array.from(v).length<=200&&new TextEncoder().encode(v).length<=800&&!/[\n\r\t\u0000-\u001f\u007f-\u009f\ud800-\udfff]/u.test(v);
const progress=(v:GuidanceProgress)=>v&&id(v.nodeId)&&["NOT_STARTED","IN_PROGRESS","COMPLETED"].includes(v.status)&&integer(v.progressVersion)&&(v.progressVersion>0||v.status==="NOT_STARTED");
const compare=(a:GuidanceNode,b:GuidanceNode)=>(a.status==="IN_PROGRESS"?0:1)-(b.status==="IN_PROGRESS"?0:1)||a.unmetRecommendedCount-b.unmetRecommendedCount||(a.nodeId<b.nodeId?-1:a.nodeId>b.nodeId?1:0);

// Accept only a bounded, internally coherent snapshot of current active-node progress.
export function readLearningGuidance(raw:unknown,space:string,roadmap:string,minVersion=1):LearningGuidance{
 const v=raw as LearningGuidance;
 if(!v||v.spaceId!==space||v.roadmapId!==roadmap||v.visibility!=="PRIVATE"||v.algorithm!=="GRAPH_PROGRESS_V1"||!integer(v.roadmapVersion)||v.roadmapVersion<minVersion||!Array.isArray(v.basis)||v.basis.length>100||!Array.isArray(v.suggestions)||!Array.isArray(v.waiting)||v.suggestions.length+v.waiting.length>100)throw new Error();
 const basis=new Map<string,GuidanceProgress>(),titles=new Map<string,string>(),rows=new Set<string>();let relations=0;
 for(const n of v.basis){if(!progress(n)||basis.has(n.nodeId))throw new Error();basis.set(n.nodeId,n)}
 function bound(n:GuidanceProgress&{title:string}){const b=basis.get(n?.nodeId);if(!b||!progress(n)||!title(n.title)||n.status!==b.status||n.progressVersion!==b.progressVersion||(titles.has(n.nodeId)&&titles.get(n.nodeId)!==n.title))throw new Error();titles.set(n.nodeId,n.title)}
 function predecessors(values:GuidancePredecessor[],node:string,required:boolean){if(!Array.isArray(values)||values.length>100)throw new Error();for(let i=0;i<values.length;i++){const p=values[i];bound(p);if(p.nodeId===node||p.status==="COMPLETED"||(i&&values[i-1].nodeId>=p.nodeId)||!Array.isArray(p.kinds)||!p.kinds.length||p.kinds.length>(required?2:1))throw new Error();for(let j=0;j<p.kinds.length;j++){const k=p.kinds[j];if(!(required?["PREREQUISITE_OF","REQUIRED_FOR"]:["RECOMMENDED_BEFORE"]).includes(k)||(j&&p.kinds[j-1]>=k))throw new Error();if(++relations>500)throw new Error()}}}
 for(const [waiting,values] of [[false,v.suggestions],[true,v.waiting]] as const)for(let i=0;i<values.length;i++){
  const n=values[i];bound(n);if(n.status==="COMPLETED"||rows.has(n.nodeId)||!integer(n.unmetRecommendedCount))throw new Error();rows.add(n.nodeId);predecessors(n.requiredPredecessors,n.nodeId,true);predecessors(n.recommendedPredecessors,n.nodeId,false);
  if(n.unmetRecommendedCount!==n.recommendedPredecessors.length||waiting!==!!n.requiredPredecessors.length||(i&&(waiting?values[i-1].nodeId>=n.nodeId:compare(values[i-1],n)>0)))throw new Error();
 }
 if([...basis.values()].filter(n=>n.status!=="COMPLETED").some(n=>!rows.has(n.nodeId)))throw new Error();
 return v;
}
