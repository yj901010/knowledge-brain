export const knowledgeAreas=[
 {id:"discovery",title:"공개 검색·그래프",description:"병합된 개념과 주장·근거의 연결을 탐색하세요."},
 {id:"contributions",title:"개념 기여·검토",description:"개념의 현재 내용과 제안을 비교하고 검토하세요."},
 {id:"verification",title:"주장·근거·검증",description:"별칭·주장·한정자·출처와 검증 이력을 다루세요."}
] as const;
export type KnowledgeArea=(typeof knowledgeAreas)[number]["id"]|"all";
export function validKnowledgeArea(v:unknown):v is KnowledgeArea{return v==="all"||knowledgeAreas.some(a=>a.id===v)}
export function knowledgeHash():KnowledgeArea{const raw=location.hash.startsWith("#knowledge-work=")?location.hash.slice(16):location.hash.slice(1);return validKnowledgeArea(raw)?raw:"discovery"}
export function knowledgeFragment(value:KnowledgeArea){return value==="discovery"?"":"#knowledge-work="+value}
export function requestKnowledgeArea(value:KnowledgeArea){const hash=knowledgeFragment(value);if(location.hash!==hash)history.pushState(history.state,"",location.pathname+location.search+hash);window.dispatchEvent(new CustomEvent("brain-knowledge-work",{detail:value}))}
