"use client";
import {useEffect,useRef,useState} from "react";
import RoadmapSource from "./roadmap-source";
import {roadmapAreas,roadmapFragment,roadmapHash,requestRoadmapArea,validRoadmapArea,type RoadmapArea} from "./roadmap-sections";

type Space={spaceId:string;domain:string;title:string;canPropose:boolean;canReview:boolean};
type Context={space:Space;actor:string;kind:"GUEST"|"REGISTERED"};
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const object=(v:unknown):v is Record<string,unknown>=>typeof v==="object"&&v!==null&&!Array.isArray(v);
function catalog(v:unknown):v is {items:Space[]}{return object(v)&&Array.isArray(v.items)&&v.items.length>0&&v.items.length<=20&&v.items.every(s=>object(s)&&typeof s.spaceId==="string"&&uuid.test(s.spaceId)&&["MEDICINE","DEVELOPMENT"].includes(String(s.domain))&&typeof s.title==="string"&&s.title.trim().length>0&&typeof s.canPropose==="boolean"&&typeof s.canReview==="boolean")&&new Set(v.items.map(s=>s.spaceId)).size===v.items.length}
class Failure extends Error{constructor(public status:number){super()}}
async function read(path:string,signal:AbortSignal,create=false){const r=await fetch(path,{signal,credentials:"same-origin",cache:"no-store",redirect:"error",...(create?{method:"POST",headers:{"Content-Type":"application/json"},body:"{}"}:{})});if(!r.ok)throw new Failure(r.status);return r.json() as Promise<unknown>}


export default function Roadmaps(){
 const [spaces,setSpaces]=useState<Space[]>([]),[space,setSpace]=useState(""),[context,setContext]=useState<Context|null>(null),[active,setActive]=useState<RoadmapArea>("browse"),[status,setStatus]=useState<"checking"|"ready"|"error">("checking"),[notice,setNotice]=useState("");
 const [working,setWorking]=useState(false);const commands=useRef(new Set<symbol>());function report(token:symbol,value:boolean){if(value)commands.current.add(token);else commands.current.delete(token);setWorking(commands.current.size>0)}function drop(){commands.current.clear();setWorking(false);setContext(null)}
 const chosen=useRef(""),current=useRef<RoadmapArea>("browse"),controller=useRef<AbortController|null>(null),generation=useRef(0),alive=useRef(true),initial=useRef(true),board=useRef<HTMLElement>(null);
 async function verify(area:RoadmapArea,id=chosen.current,focus=false,force=false){
  if(!force&&commands.current.size>0){setNotice("요청을 처리하고 있습니다. 결과를 확인한 뒤 다른 작업으로 이동해 주세요.");history.replaceState(history.state,"",location.pathname+location.search+roadmapFragment(current.current));return}
  const version=++generation.current;controller.current?.abort();const c=new AbortController();controller.current=c;setStatus("checking");setNotice("");
  try{const signal=AbortSignal.any([c.signal,AbortSignal.timeout(15000)]);let principal:unknown;
   try{principal=await read("/api/identity/guest-session",signal)}catch(e){if(!(e instanceof Failure)||e.status!==401||!initial.current)throw e;principal=await read("/api/identity/guest-session",signal,true)}
   initial.current=false;
   if(!object(principal)||typeof principal.principalId!=="string"||!uuid.test(principal.principalId)||!["GUEST","REGISTERED"].includes(String(principal.kind)))throw new Error();
   const data=await read("/api/spaces/public",signal);if(!catalog(data)||principal.kind==="GUEST"&&data.items.some(s=>s.canPropose||s.canReview))throw new Error();
   const selected=data.items.find(s=>s.spaceId===id)||(id?null:data.items.find(s=>s.domain==="DEVELOPMENT")??data.items[0]);if(!selected)throw new Error();
   if(!alive.current||c.signal.aborted||version!==generation.current)return;
   setSpaces(data.items);setSpace(selected.spaceId);chosen.current=selected.spaceId;setContext({space:selected,actor:principal.principalId,kind:principal.kind as Context["kind"]});current.current=area;setActive(area);setStatus("ready");const hash=roadmapFragment(area);if(location.hash!==hash)history.replaceState(history.state,"",location.pathname+location.search+hash);window.dispatchEvent(new CustomEvent("brain-roadmap-ready",{detail:area}));
   if(focus)requestAnimationFrame(()=>{const target=board.current?.querySelector<HTMLElement>(".roadmap-current");target?.scrollIntoView({block:"start"});target?.focus({preventScroll:true})});
  }catch(e){if(!alive.current||c.signal.aborted||version!==generation.current)return;drop();setStatus("error");setNotice(e instanceof Failure&&e.status===401?"접속이 만료되어 작업 입력을 비웠습니다. 개인 공간에서 접속 상태를 확인한 뒤 다시 열어 주세요.":"현재 로드맵 분야와 권한을 확인하지 못해 작업 입력을 비웠습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.")}
 }
 useEffect(()=>{alive.current=true;void verify(roadmapHash());const navigate=(e:Event)=>{if(e instanceof CustomEvent&&validRoadmapArea(e.detail))void verify(e.detail,chosen.current,true)},history=()=>void verify(roadmapHash(),chosen.current,true),visible=()=>{if(document.hidden){generation.current++;controller.current?.abort();drop();setStatus("checking")}else void verify(current.current)};window.addEventListener("brain-roadmap-work",navigate);window.addEventListener("hashchange",history);document.addEventListener("visibilitychange",visible);return()=>{alive.current=false;generation.current++;controller.current?.abort();window.removeEventListener("brain-roadmap-work",navigate);window.removeEventListener("hashchange",history);document.removeEventListener("visibilitychange",visible)}},[]);
 const scope=context?`${context.space.spaceId}:${context.actor}:${context.kind}:${context.space.canPropose}:${context.space.canReview}`:"";
 function authorityFailure(){drop();void verify(current.current,chosen.current,false,true)}
 return <main className="public-workspace roadmap-workspace"><section className="workspace" ref={board} aria-label="학습 로드맵 작업 공간"><header><div><p className="eyebrow">LEARNING PATHS</p><h1>학습 로드맵</h1><p>검토된 경로를 둘러보고, 나만의 비공개 학습으로 이어가세요.</p></div><a href="/graph">내 개인 공간</a></header>
  <div className="roadmap-controls"><label>로드맵 공개 분야<select aria-label="로드맵 공개 분야" disabled={status==="checking"||working} value={space} onChange={e=>{chosen.current=e.target.value;setSpace(e.target.value);drop();void verify(current.current,e.target.value)}}>{spaces.map(s=><option key={s.spaceId} value={s.spaceId}>{s.title}</option>)}</select></label><label>로드맵 작업<select aria-label="로드맵 작업 선택" value={active} disabled={status==="checking"||working} onChange={e=>{if(validRoadmapArea(e.target.value))requestRoadmapArea(e.target.value)}}>{roadmapAreas.map(a=><option key={a.id} value={a.id}>{a.title}</option>)}<option value="all">전체 작업 모아보기</option></select></label><button disabled={status==="checking"||working} onClick={()=>void verify(current.current)}>접속 상태 다시 확인</button><span className="roadmap-access">{status!=="ready"?"접속 확인 중":context?.space.canReview?"지정 검토자 · 제안·검토 가능":context?.space.canPropose?"등록 계정 · 제안 가능":"게스트 · 공개 자료 둘러보기"}</span></div>
  <div className="roadmap-work-cards">{roadmapAreas.map((a,i)=><button type="button" key={a.id} aria-pressed={active===a.id} disabled={status==="checking"||working} onClick={()=>requestRoadmapArea(a.id)}><small>0{i+1}</small><strong>{a.title}</strong><span>{a.description}</span></button>)}<a className="roadmap-private-link" href="/graph#work=learning"><small>MY LEARNING</small><strong>내 학습 로드맵</strong><span>비공개 진도·학습 기록과 근거를 이어서 확인하세요.</span></a></div>
  <p className="roadmap-policy">공개 제안은 독립 검토·병합을 거칩니다. 평점·순위는 검토 상태와 별개입니다. 가져온 사본과 개인 진도·학습 기록·첨부 근거는 비공개이며 공개 원본의 변경을 자동 반영하지 않습니다.</p>
  {status==="checking"&&<p className="roadmap-checking" role="status">현재 접속과 로드맵 공개 분야 권한을 확인하고 있습니다…</p>}{notice&&<p role="status" className="notice">{notice} <a href="/graph">개인 공간에서 접속 확인</a></p>}
  <div className="roadmap-current" tabIndex={-1}><h2>{active==="all"?"전체 작업":roadmapAreas.find(a=>a.id===active)?.title}</h2><p>{active==="browse"?"검토된 그래프와 원본 버전을 확인하세요. 개인 공간으로 가져오기는 상세에서 직접 선택합니다.":"제출할 내용과 공개 사유, 원본 버전을 확인한 뒤 직접 실행하세요."}</p></div>
  {context&&<fieldset key={scope} className="roadmap-source-fieldset" hidden={status!=="ready"} inert={status!=="ready"} disabled={working} aria-busy={status==="checking"}>
   <RoadmapSource space={context.space.spaceId} actor={context.actor} canPropose={context.space.canPropose} canReview={context.space.canReview} active={active} onNavigate={area=>requestRoadmapArea(area)} onAuthorityFailure={authorityFailure} onCommand={report}/>
  </fieldset>}
 </section></main>
}
