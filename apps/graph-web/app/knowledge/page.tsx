"use client";
import {useEffect,useRef,useState} from "react";
import PublicDiscovery from "../public-discovery";
import PublicKnowledgeWorkbench from "../public-knowledge-workbench";
import ConceptWorkspace from "./concept-workspace";
import {knowledgeAreas,knowledgeFragment,knowledgeHash,requestKnowledgeArea,validKnowledgeArea,type KnowledgeArea} from "./knowledge-sections";

type Space={spaceId:string;domain:string;title:string;canPropose:boolean;canReview:boolean};
type Context={space:Space;actor:string;kind:"GUEST"|"REGISTERED"};
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const object=(v:unknown):v is Record<string,unknown>=>typeof v==="object"&&v!==null&&!Array.isArray(v);
function catalog(v:unknown):v is {items:Space[]}{return object(v)&&Array.isArray(v.items)&&v.items.length>0&&v.items.length<=20&&v.items.every(s=>object(s)&&typeof s.spaceId==="string"&&uuid.test(s.spaceId)&&["MEDICINE","DEVELOPMENT"].includes(String(s.domain))&&typeof s.title==="string"&&s.title.trim().length>0&&typeof s.canPropose==="boolean"&&typeof s.canReview==="boolean")&&new Set(v.items.map(s=>s.spaceId)).size===v.items.length}
class Failure extends Error{constructor(public status:number){super()}}
async function read(path:string,signal:AbortSignal,create=false){const r=await fetch(path,{signal,credentials:"same-origin",cache:"no-store",redirect:"error",...(create?{method:"POST",headers:{"Content-Type":"application/json"},body:"{}"}:{})});if(!r.ok)throw new Failure(r.status);return r.json() as Promise<unknown>}
function Pane({active,area,children}:{active:KnowledgeArea;area:KnowledgeArea;children:React.ReactNode}){const show=active==="all"||active===area;const [visited,setVisited]=useState(false);useEffect(()=>{if(show)setVisited(true)},[show]);return <section className="knowledge-work-pane" hidden={!show} inert={!show} data-knowledge-area={area}>{(show||visited)&&children}</section>}

export default function Knowledge(){
 const [spaces,setSpaces]=useState<Space[]>([]),[space,setSpace]=useState(""),[context,setContext]=useState<Context|null>(null),[active,setActive]=useState<KnowledgeArea>("discovery"),[status,setStatus]=useState<"checking"|"ready"|"error">("checking"),[notice,setNotice]=useState("");
 const [working,setWorking]=useState(false);const writes=useRef<Record<string,boolean>>({});function report(area:string,value:boolean){writes.current[area]=value;setWorking(Object.values(writes.current).some(Boolean))}
 const chosen=useRef(""),current=useRef<KnowledgeArea>("discovery"),controller=useRef<AbortController|null>(null),generation=useRef(0),alive=useRef(true),initial=useRef(true),board=useRef<HTMLElement>(null);
 async function verify(area:KnowledgeArea,id=chosen.current,focus=false,force=false){
  if(!force&&Object.values(writes.current).some(Boolean)){setNotice("공개 요청을 처리하고 있습니다. 결과를 확인한 뒤 다른 작업으로 이동해 주세요.");history.replaceState(history.state,"",location.pathname+location.search+knowledgeFragment(current.current));return}
  const version=++generation.current;controller.current?.abort();const c=new AbortController();controller.current=c;setStatus("checking");setNotice("");
  try{const signal=AbortSignal.any([c.signal,AbortSignal.timeout(15000)]);let principal:unknown;
   try{principal=await read("/api/identity/guest-session",signal)}catch(e){if(!(e instanceof Failure)||e.status!==401||!initial.current)throw e;principal=await read("/api/identity/guest-session",signal,true)}
   initial.current=false;
   if(!object(principal)||typeof principal.principalId!=="string"||!uuid.test(principal.principalId)||!["GUEST","REGISTERED"].includes(String(principal.kind)))throw new Error();
   const data=await read("/api/spaces/public",signal);if(!catalog(data)||principal.kind==="GUEST"&&data.items.some(s=>s.canPropose||s.canReview))throw new Error();
   const selected=data.items.find(s=>s.spaceId===id)||(id?null:data.items.find(s=>s.domain==="DEVELOPMENT")??data.items[0]);if(!selected)throw new Error();
   if(!alive.current||c.signal.aborted||version!==generation.current)return;
   setSpaces(data.items);setSpace(selected.spaceId);chosen.current=selected.spaceId;setContext({space:selected,actor:principal.principalId,kind:principal.kind as Context["kind"]});current.current=area;setActive(area);setStatus("ready");const hash=knowledgeFragment(area);if(location.hash!==hash)history.replaceState(history.state,"",location.pathname+location.search+hash);window.dispatchEvent(new CustomEvent("brain-knowledge-ready",{detail:area}));
   if(focus)requestAnimationFrame(()=>{const target=board.current?.querySelector<HTMLElement>(".knowledge-current");target?.scrollIntoView({block:"start"});target?.focus({preventScroll:true})});
  }catch(e){if(!alive.current||c.signal.aborted||version!==generation.current)return;setContext(null);setStatus("error");setNotice(e instanceof Failure&&e.status===401?"접속이 만료되어 작업 입력을 비웠습니다. 개인 공간에서 접속 상태를 확인한 뒤 다시 열어 주세요.":"현재 분야와 권한을 확인하지 못해 작업 입력을 비웠습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.")}
 }
 useEffect(()=>{alive.current=true;void verify(knowledgeHash());const navigate=(e:Event)=>{if(e instanceof CustomEvent&&validKnowledgeArea(e.detail))void verify(e.detail,chosen.current,true)},history=()=>void verify(knowledgeHash(),chosen.current,true),visible=()=>{if(document.hidden){generation.current++;controller.current?.abort();setContext(null);setStatus("checking")}else void verify(current.current)};window.addEventListener("brain-knowledge-work",navigate);window.addEventListener("hashchange",history);document.addEventListener("visibilitychange",visible);return()=>{alive.current=false;generation.current++;controller.current?.abort();window.removeEventListener("brain-knowledge-work",navigate);window.removeEventListener("hashchange",history);document.removeEventListener("visibilitychange",visible)}},[]);
 const scope=context?`${context.space.spaceId}:${context.actor}:${context.kind}:${context.space.canPropose}:${context.space.canReview}`:"";
 function authorityFailure(){setContext(null);void verify(current.current,chosen.current,false,true)}
 return <main className="public-workspace knowledge-workspace"><section className="workspace" ref={board} aria-label="공개 지식 작업 공간"><header><div><p className="eyebrow">SHARED KNOWLEDGE</p><h1>함께 만드는 공개 지식</h1><p>공개 자료를 탐색하고, 확인한 내용을 제안·검토하세요.</p></div><a href="/graph">내 개인 공간</a></header>
  <div className="knowledge-controls"><label>공개 분야<select aria-label="공개 분야" disabled={status==="checking"||working} value={space} onChange={e=>{chosen.current=e.target.value;setSpace(e.target.value);setContext(null);void verify(current.current,e.target.value)}}>{spaces.map(s=><option key={s.spaceId} value={s.spaceId}>{s.title}</option>)}</select></label><label>공개 지식 작업<select aria-label="공개 지식 작업 선택" value={active} disabled={status==="checking"||working} onChange={e=>{if(validKnowledgeArea(e.target.value))requestKnowledgeArea(e.target.value)}}>{knowledgeAreas.map(a=><option key={a.id} value={a.id}>{a.title}</option>)}<option value="all">전체 작업 모아보기</option></select></label><button disabled={status==="checking"||working} onClick={()=>void verify(current.current)}>새로고침</button><span className="knowledge-access">{status!=="ready"?"접속 확인 중":context?.space.canReview?"지정 검토자 · 제안·검토 가능":context?.space.canPropose?"등록 계정 · 제안 가능":"게스트 · 공개 자료 둘러보기"}</span></div>
  <div className="knowledge-work-cards">{knowledgeAreas.map((a,i)=><button type="button" key={a.id} aria-pressed={active===a.id} disabled={status==="checking"||working} onClick={()=>requestKnowledgeArea(a.id)}><small>0{i+1}</small><strong>{a.title}</strong><span>{a.description}</span></button>)}</div>
  <p className="knowledge-policy">제안 → 독립 검토 → 병합 순서로 공개됩니다. 검증 단계와 외부 매핑은 별개이며, 전문가 검증·공식 매핑에는 별도 권한과 근거가 필요합니다. 개인 기록은 자동 공개되지 않습니다.</p>
  {status==="checking"&&<p className="knowledge-checking" role="status">현재 접속과 공개 분야 권한을 확인하고 있습니다…</p>}{notice&&<p role="status" className="notice">{notice} <a href="/graph">개인 공간에서 접속 확인</a></p>}
  <div className="knowledge-current" tabIndex={-1}><h2>{active==="all"?"전체 작업":knowledgeAreas.find(a=>a.id===active)?.title}</h2><p>{active==="discovery"?"병합된 공개 자료만 탐색합니다. 제안은 검토·병합 전까지 여기에 반영되지 않습니다.":"제출할 내용과 공개 사유, 원본 버전을 확인한 뒤 직접 실행하세요."}</p></div>
  {context&&<div key={scope} className="knowledge-work-panes" hidden={status!=="ready"} inert={status!=="ready"} aria-busy={status==="checking"}>
   <Pane active={active} area="discovery"><PublicDiscovery spaceId={context.space.spaceId} domain={context.space.domain} actorId={context.actor} canPropose={context.space.canPropose} onBusyChange={value=>report("discovery",value)}/></Pane>
   <Pane active={active} area="contributions"><ConceptWorkspace space={context.space.spaceId} actor={context.actor} canPropose={context.space.canPropose} canReview={context.space.canReview} onAuthorityFailure={authorityFailure} onBusyChange={value=>report("contributions",value)}/></Pane>
   <Pane active={active} area="verification"><PublicKnowledgeWorkbench spaceId={context.space.spaceId} actorId={context.actor} canPropose={context.space.canPropose} canReview={context.space.canReview} onBusyChange={value=>report("verification",value)}/></Pane>
  </div>}
 </section></main>
}
