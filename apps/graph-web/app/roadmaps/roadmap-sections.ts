export const roadmapAreas=[
 {id:"browse",title:"공개 로드맵 탐색",description:"검토된 학습 경로와 원본 버전을 확인하세요."},
 {id:"rankings",title:"평점·순위",description:"현재 공개 버전의 평가를 비교하세요. 검증과는 별개입니다."},
 {id:"contributions",title:"로드맵 기여·검토",description:"그래프를 편집하고 공개 제안과 검토 이력을 확인하세요."}
] as const;
export type RoadmapArea=(typeof roadmapAreas)[number]["id"]|"all";
export function validRoadmapArea(v:unknown):v is RoadmapArea{return v==="all"||roadmapAreas.some(a=>a.id===v)}
export function roadmapHash():RoadmapArea{const value=location.hash.startsWith("#roadmap-work=")?location.hash.slice(14):"browse";return validRoadmapArea(value)?value:"browse"}
export function roadmapFragment(value:RoadmapArea){return value==="browse"?"":"#roadmap-work="+value}
export function requestRoadmapArea(value:RoadmapArea){const hash=roadmapFragment(value);if(location.hash!==hash)history.pushState(history.state,"",location.pathname+location.search+hash);window.dispatchEvent(new CustomEvent("brain-roadmap-work",{detail:value}))}
