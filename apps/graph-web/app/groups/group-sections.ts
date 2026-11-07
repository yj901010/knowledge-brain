export const groupAreas=[
 {id:"assignments",title:"내 과제",description:"제출할 과제와 지시문을 확인하세요.",owner:false},
 {id:"submissions",title:"내 제출·채점",description:"내가 제출한 버전과 채점 상태를 확인하세요.",owner:false},
 {id:"requests",title:"내 재제출 요청",description:"내 답안에 도착한 재제출 요청을 확인하세요.",owner:false},
 {id:"feedback",title:"내 피드백",description:"내 제출에 남겨진 피드백을 확인하세요.",owner:false},
 {id:"review",title:"피드백·재제출 검토",description:"제출 원본을 열어 피드백과 요청을 검토하세요.",owner:true},
 {id:"grading",title:"채점 대기",description:"다른 참여자의 제출 버전을 확인하고 채점하세요.",owner:true},
 {id:"studies",title:"스터디·공유 로드맵",description:"스터디와 공유 학습 경로를 탐색하세요.",owner:false},
 {id:"courses",title:"코스·커리큘럼",description:"코스 구성과 내 학습을 확인하세요.",owner:false},
 {id:"notifications",title:"내 그룹 알림",description:"내가 동의한 제출 변경 알림을 확인하세요.",owner:false},
 {id:"members",title:"멤버·초대 관리",description:"그룹 참여자와 초대를 관리하세요.",owner:true}
] as const;
export type GroupArea=(typeof groupAreas)[number]["id"]|"all";
export function validGroupArea(v:unknown):v is GroupArea{return v==="all"||groupAreas.some(a=>a.id===v)}
export function groupHash():GroupArea{const value=location.hash.startsWith("#group-work=")?location.hash.slice(12):"all";return validGroupArea(value)?value:"all"}
export function requestGroupArea(value:GroupArea){const hash=value==="all"?"":"#group-work="+value;if(location.hash!==hash)window.history.pushState(window.history.state,"",location.pathname+location.search+hash);window.dispatchEvent(new CustomEvent("brain-group-work",{detail:value}))}
