"use client";
import {useEffect,useRef,useState} from "react";
import {api,Failure,member,object} from "./membership-api";
import {groupAreas,groupHash,requestGroupArea,validGroupArea,type GroupArea} from "./group-sections";
import GroupMyAssignmentsWorkspace from "./group-my-assignments-workspace";
import GroupMySubmissionsWorkspace from "./group-my-submissions-workspace";
import GroupMyResubmissionsWorkspace from "./group-my-resubmissions-workspace";
import GroupMyFeedbackWorkspace from "./group-my-feedback-workspace";
import GroupReviewWorkspace from "./group-review-workspace";
import GroupGradingWorkspace from "./group-grading-workspace";
import GroupCourses from "./courses";
import GroupNotifications from "./notifications";
import GroupStudies from "./studies";
import GroupRoadmaps from "./roadmaps";
import MembershipPanel from "./membership-panel";

type Role="OWNER"|"MEMBER"|"VIEWER";
function Pane({show,enabled,title,description,children}:{show:boolean;enabled:boolean;title:string;description:string;children:React.ReactNode}){
 const [visited,setVisited]=useState(false);useEffect(()=>{if(show&&enabled)setVisited(true)},[show,enabled]);
 return <section className="group-work-pane" hidden={!show||!enabled} inert={!show||!enabled}><div className="group-pane-heading"><h4>{title}</h4><p>{description}</p></div>{enabled&&(show||visited)&&children}</section>
}

export default function GroupWorkspace({groupId,spaceId,role,membershipVersion,onUnavailable}:{groupId:string;spaceId:string;role:Role;membershipVersion?:number;onUnavailable:(authenticationLost?:boolean)=>void}){
 const [active,setActive]=useState<GroupArea>("all"),[state,setState]=useState<"checking"|"ready"|"error">("checking"),[notice,setNotice]=useState(""),[reset,setReset]=useState(0);
 const root=useRef<HTMLElement>(null),controller=useRef<AbortController|null>(null),generation=useRef(0),authority=useRef<{principalId:string;version:number;role:string}|null>(null),alive=useRef(true),current=useRef<GroupArea>("all"),unavailable=useRef(onUnavailable);unavailable.current=onUnavailable;
 const owner=role==="OWNER",choices=groupAreas.filter(a=>!a.owner||owner);
 async function verify(requested:GroupArea,focus=false){
  const version=++generation.current;controller.current?.abort();const c=new AbortController();controller.current=c;setState("checking");setNotice("");
  try{const signal=AbortSignal.any([c.signal,AbortSignal.timeout(15000)]);const [p,m]=await Promise.all([api(`/api/groups/${groupId}/participation`,signal),api(`/api/groups/${groupId}/membership`,signal)]);
   if(!alive.current||c.signal.aborted||generation.current!==version)return;
   if(!object(p)||!object(p.group)||p.group.groupId!==groupId||p.group.spaceId!==spaceId||p.group.state!=="ACTIVE"||p.group.visibility!=="PRIVATE"||!member(m,groupId,spaceId)||m.state!=="ACTIVE"||p.role!==m.role)throw new Error();
   const prior=authority.current;if(m.role!==role||membershipVersion!==undefined&&m.version!==membershipVersion||prior&&(prior.principalId!==m.principalId||prior.version!==m.version||prior.role!==m.role)){unavailable.current();return}
   authority.current={principalId:m.principalId,version:m.version,role:m.role};const allowed=requested==="all"||choices.some(a=>a.id===requested);const next=allowed?requested:"all";current.current=next;const hash=next==="all"?"":"#group-work="+next;if(location.hash!==hash)window.history.replaceState(window.history.state,"",location.pathname+location.search+hash);setActive(next);setState("ready");window.dispatchEvent(new CustomEvent("brain-group-ready",{detail:next}));if(!allowed)setNotice("이 작업은 현재 그룹 소유자만 사용할 수 있습니다. 내 학습 작업을 선택해 주세요.")
   if(focus)requestAnimationFrame(()=>{const target=root.current?.querySelector<HTMLElement>(".group-work-current");target?.scrollIntoView({block:"start"});target?.focus({preventScroll:true})});
  }catch(error){if(!alive.current||c.signal.aborted||generation.current!==version)return;setReset(v=>v+1);setState("error");if(error instanceof Failure&&[401,403,404].includes(error.status))unavailable.current(error.status===401);else setNotice("그룹 권한을 확인하지 못해 작업 입력을 정리했습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.")}
 }
 useEffect(()=>{alive.current=true;void verify(groupHash());const requested=(e:Event)=>{if(e instanceof CustomEvent&&validGroupArea(e.detail))void verify(e.detail,true)},hash=()=>{if(!location.hash||location.hash.startsWith("#group-work="))void verify(groupHash(),true)};window.addEventListener("brain-group-work",requested);window.addEventListener("hashchange",hash);return()=>{alive.current=false;generation.current++;controller.current?.abort();window.removeEventListener("brain-group-work",requested);window.removeEventListener("hashchange",hash)}},[groupId,spaceId,role,membershipVersion]);
 const enabled=state!=="error";const show=(id:GroupArea)=>state==="ready"&&(active==="all"||active===id);
 const panes:Record<string,React.ReactNode>={
  assignments:<GroupMyAssignmentsWorkspace groupId={groupId} spaceId={spaceId} onUnavailable={onUnavailable}/>,
  submissions:<GroupMySubmissionsWorkspace groupId={groupId} spaceId={spaceId} onUnavailable={onUnavailable}/>,
  requests:<GroupMyResubmissionsWorkspace groupId={groupId} spaceId={spaceId} onUnavailable={onUnavailable}/>,
  feedback:<GroupMyFeedbackWorkspace groupId={groupId} spaceId={spaceId} onUnavailable={onUnavailable}/>,
  review:owner&&<GroupReviewWorkspace groupId={groupId} spaceId={spaceId} onUnavailable={onUnavailable}/>,
  grading:owner&&<GroupGradingWorkspace groupId={groupId} spaceId={spaceId} onUnavailable={onUnavailable}/>,
  studies:<><GroupStudies groupId={groupId} spaceId={spaceId} owner={owner} onUnavailable={onUnavailable}/><GroupRoadmaps spaceId={spaceId} owner={owner} onUnavailable={onUnavailable}/></>,
  courses:<GroupCourses groupId={groupId} spaceId={spaceId} owner={owner} onUnavailable={onUnavailable}/>,
  notifications:<GroupNotifications groupId={groupId} spaceId={spaceId} onUnavailable={onUnavailable}/>,
  members:owner&&<MembershipPanel groupId={groupId} spaceId={spaceId} onUnavailable={onUnavailable}/>
 };
 return <section ref={root} className="group-workspace-board" data-group-workboard="" aria-label="그룹 작업판"><div className="group-board-intro"><div><p className="eyebrow">GROUP WORKSPACE</p><h3>그룹에서 할 일</h3><p>내 과제와 운영 작업을 나누어 확인하세요.</p></div><span className={`group-role role-${role.toLowerCase()}`}>{({OWNER:"그룹 소유자",MEMBER:"참여자",VIEWER:"읽기 참여자"})[role]} · 비공개</span></div>
  <div className="group-board-nav"><label>현재 그룹 작업<select aria-label="그룹 작업 선택" value={active} disabled={state==="checking"} onChange={e=>{if(validGroupArea(e.target.value))requestGroupArea(e.target.value)}}><option value="all">전체 작업 모아보기</option>{choices.map(a=><option key={a.id} value={a.id}>{a.title}</option>)}</select></label>{active!=="all"&&<button type="button" disabled={state==="checking"} onClick={()=>requestGroupArea("all")}>그룹 전체 작업</button>}<button type="button" disabled={state==="checking"} onClick={()=>void verify(current.current)}>그룹 권한 다시 확인</button></div>
  <div className="group-quick-grid">{choices.filter(a=>["assignments","submissions","requests","feedback",...(owner?["grading","review"]:[])].includes(a.id)).map(a=><button type="button" key={a.id} disabled={state==="checking"} aria-pressed={active===a.id} onClick={()=>requestGroupArea(a.id)}><strong>{a.title}</strong><span>{a.description}</span><i aria-hidden="true">↗</i></button>)}</div>
  {state==="checking"&&<p className="group-checking" aria-live="polite">현재 그룹 권한을 확인하고 있습니다…</p>}{notice&&<p className="notice" aria-live="polite">{notice}</p>}
  <div className="group-work-current" tabIndex={-1}><h3>{active==="all"?"전체 작업":choices.find(a=>a.id===active)?.title}</h3><p>{active==="all"?"작업을 선택하면 관련 목록과 원본에 집중할 수 있습니다.":"목록에서 대상을 선택한 뒤 원본의 현재 버전을 확인하고 작업하세요."}</p></div>
  <div key={reset} className="group-work-panes">{choices.map(a=><Pane key={a.id} enabled={enabled} show={show(a.id)} title={a.title} description={a.description}>{panes[a.id]}</Pane>)}</div>
 </section>
}
