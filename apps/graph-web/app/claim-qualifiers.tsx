"use client";
import RetiredQualifiers from "./retired-qualifiers";
import {type ClaimObject,type ClaimObjectKind,claimObjectKinds,claimObjectLabels,validClaimObject,claimObjectText,claimObjectInput} from "./claim-object";
import {useEffect,useRef,useState} from "react";
type Value={type:string;value:string};
type Claim={claimId:string;version:number;predicateConceptId:string;object:ClaimObject;state:string};
type Pin={revisionId:string;version:number;subjectConceptId:string;predicateConceptId:string;object:ClaimObject};
type Qualifier={qualifierId:string;spaceId:string;claimId:string;subjectConceptId:string;claim:Pin;predicateConceptId:string;value:Value;visibility:"PRIVATE";state:"ACTIVE"|"RETIRED";version:number};
type Revision={revisionId:string;qualifierId:string;version:number;state:string;claim:Pin;predicateConceptId:string;value:Value;actorId:string;occurredAt:string};
type Concept={conceptId:string;title:string};
const types=["STRING","NUMBER","DATE","BOOLEAN","CONCEPT"],labels=["문자","숫자","날짜","참·거짓","개념"];
const id=(s:unknown):s is string=>typeof s==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(s);
const version=(n:unknown):n is number=>typeof n==="number"&&Number.isSafeInteger(n)&&n>0;
function typed(v:Value){return v&&types.includes(v.type)&&typeof v.value==="string"&&v.value.length>0&&Array.from(v.value).length<=1000}
function pin(p:Pin){return p&&id(p.revisionId)&&version(p.version)&&id(p.subjectConceptId)&&id(p.predicateConceptId)&&validClaimObject(p.object)}
export default function ClaimQualifiers(props:{spaceId:string;conceptId:string;claimId:string;claimVersion:number;parentRetired:boolean;onDenied:()=>void}){
 const {spaceId,conceptId,claimId,claimVersion,parentRetired,onDenied}=props,path=`/api/spaces/${spaceId}/concepts/${conceptId}/claims/${claimId}`,scope=path;
 const [rows,setRows]=useState<Qualifier[]>([]),[after,setAfter]=useState<string|null>(null),[concepts,setConcepts]=useState<Concept[]>([]),[conceptAfter,setConceptAfter]=useState<string|null>(null);
 const [selected,setSelected]=useState<Qualifier|null>(null),[editing,setEditing]=useState(false),[predicate,setPredicate]=useState(""),[kind,setKind]=useState("STRING"),[text,setText]=useState("");
 const [inspected,setInspected]=useState<Claim|null>(null),[pending,setPending]=useState<{claim:Claim;qualifier:Qualifier|null}|null>(null),[stale,setStale]=useState(false);
 const [history,setHistory]=useState<Revision[]|null>(null),[before,setBefore]=useState<number|null>(null),[ack,setAck]=useState(false),[busy,setBusy]=useState(false),[notice,setNotice]=useState("");
 const operation=useRef<AbortController|null>(null),alive=useRef(true),retired=useRef(parentRetired),scopeRef=useRef(scope);retired.current=parentRetired;scopeRef.current=scope;
 function parse(q:Qualifier){if(!q||!id(q.qualifierId)||q.spaceId!==spaceId||q.claimId!==claimId||q.subjectConceptId!==conceptId||!pin(q.claim)||q.claim.subjectConceptId!==conceptId||!id(q.predicateConceptId)||!typed(q.value)||q.visibility!=="PRIVATE"||!["ACTIVE","RETIRED"].includes(q.state)||!version(q.version))throw Error("조건 응답을 확인하지 못했습니다.");return q}
 async function request(url:string,signal:AbortSignal,init:RequestInit={}){
  const response=await fetch(url,{...init,credentials:"same-origin",cache:"no-store",redirect:"error",signal,headers:{...(init.body?{"Content-Type":"application/json"}:{}),...init.headers}});
  if(signal.aborted||!alive.current||scopeRef.current!==scope)throw Error();
  if(response.status===401||response.status===403){onDenied();throw Error("공간 접근을 확인해 주세요.")}
  if(response.status===412){setStale(true);setPending(null);setInspected(null);setAck(false);throw Error("다른 수정이 있습니다. 입력은 유지했습니다. 최신 버전을 확인한 뒤 다시 시작해 주세요.")}
  if(response.status===409)throw Error("같은 조건이 이미 있습니다. 목록을 확인해 주세요.");
  if(response.status===404)throw Error("관계나 참조 개념이 폐기되었거나 조회할 수 없습니다.");
  if(!response.ok)throw Error(response.status===400?"조건의 종류와 값을 확인해 주세요.":"요청 결과를 확인하지 못했습니다. 입력은 유지했습니다. 목록과 이력을 확인해 주세요.");
  return response;
 }
 async function run(work:(signal:AbortSignal)=>Promise<void>){
  if(operation.current)return;const controller=new AbortController();operation.current=controller;setBusy(true);setNotice("");
  const timer=setTimeout(()=>controller.abort(),20000);
  try{await work(controller.signal)}catch(e){if(alive.current&&operation.current===controller&&scopeRef.current===scope)setNotice(controller.signal.aborted?"응답이 지연되어 요청을 중단했습니다. 입력은 유지했습니다.":e instanceof Error?e.message:"요청을 완료하지 못했습니다.")}
  finally{clearTimeout(timer);if(alive.current&&operation.current===controller){operation.current=null;setBusy(false)}}
 }
 async function list(signal:AbortSignal,cursor:string|null=null){
  const response=await request(path+`/qualifiers?limit=20${cursor?`&after=${cursor}`:""}`,signal),data=await response.json();
  if(signal.aborted)return;if(!data||!Array.isArray(data.items)||data.items.length>20)throw Error("조건 목록을 확인하지 못했습니다.");
  const items:Qualifier[]=data.items.map(parse);
  if(items.some((r,i)=>r.state!=="ACTIVE"||(cursor!==null&&r.qualifierId<=cursor)||(i>0&&r.qualifierId<=items[i-1].qualifierId))||(data.nextAfter!==null&&(!id(data.nextAfter)||items.length!==20||data.nextAfter!==items[items.length-1].qualifierId)))throw Error("조건 목록 순서를 확인하지 못했습니다.");
  setRows(old=>cursor?[...old,...items]:items);setAfter(data.nextAfter);
 }
 async function picker(signal:AbortSignal,cursor:string|null=null){
  const response=await request(`/api/spaces/${spaceId}/concepts?limit=20${cursor?`&after=${cursor}`:""}`,signal),data=await response.json();
  if(signal.aborted)return;if(!data||!Array.isArray(data.items)||data.items.length>20||data.items.some((r:Concept,i:number)=>!id(r?.conceptId)||typeof r.title!=="string"||(cursor!==null&&r.conceptId<=cursor)||(i>0&&r.conceptId<=data.items[i-1].conceptId))||(data.nextAfter!==null&&(!id(data.nextAfter)||data.items.length!==20||data.nextAfter!==data.items[data.items.length-1].conceptId)))throw Error("조건에 사용할 개념을 확인하지 못했습니다.");
  setConcepts(old=>cursor?[...old,...data.items]:data.items);setConceptAfter(data.nextAfter);
 }
 useEffect(()=>{alive.current=true;if(!parentRetired)void run(async signal=>{await list(signal);await picker(signal)});return()=>{alive.current=false;operation.current?.abort();operation.current=null}},[scope]);
 useEffect(()=>{setInspected(null);setPending(null);setAck(false);if(parentRetired){operation.current?.abort();operation.current=null;setBusy(false);setRows([]);setAfter(null);setConcepts([]);setEditing(false)}},[claimVersion,parentRetired]);
 const name=(value:string)=>concepts.find(c=>c.conceptId===value)?.title??value;
 function reset(q:Qualifier|null){setSelected(q);setPredicate(q?.predicateConceptId??"");setKind(q?.value.type??"STRING");setText(q?.value.value??"");setHistory(null);setBefore(null);setEditing(true);setInspected(null);setPending(null);setStale(false);setAck(false)}
 async function inspect(signal:AbortSignal){
  const response=await request(path,signal),claim:Claim=await response.json();
  if(signal.aborted)return;if(!claim||claim.claimId!==claimId||!version(claim.version)||claim.state!=="ACTIVE"||!id(claim.predicateConceptId)||!validClaimObject(claim.object))throw Error("최신 관계를 확인하지 못했습니다.");
  let qualifier:Qualifier|null=null;
  if(selected){const response=await request(path+"/qualifiers/"+selected.qualifierId,signal);qualifier=parse(await response.json());if(response.headers.get("ETag")!==`"qf${qualifier.version}"`||qualifier.qualifierId!==selected.qualifierId)throw Error("조건 버전을 확인하지 못했습니다.")}
  if(!signal.aborted)setPending({claim,qualifier});
 }
 function accept(){if(!pending)return;const current=pending;if(stale&&current.qualifier)reset(current.qualifier);setInspected(current.claim);setSelected(current.qualifier);setPending(null);setStale(false);setAck(false)}
 async function save(signal:AbortSignal){
  if(retired.current||!inspected||stale)return;
  const response=await request(path+"/qualifiers"+(selected?"/"+selected.qualifierId:""),signal,{method:selected?"PUT":"POST",headers:selected?{"If-Match":`"qf${selected.version}"`}:{},body:JSON.stringify({predicateConceptId:predicate,value:{type:kind,value:text},claimVersion:inspected.version})});
  const q=parse(await response.json());if(signal.aborted)return;if(response.headers.get("ETag")!==`"qf${q.version}"`)throw Error("저장한 조건 버전을 확인해 주세요.");
  setSelected(q);setEditing(false);setHistory(null);setAck(false);await list(signal);setNotice("관계 조건을 저장했습니다.");
 }
 async function select(q:Qualifier,signal:AbortSignal){
  const response=await request(path+"/qualifiers/"+q.qualifierId,signal),current=parse(await response.json());if(signal.aborted)return;
  if(current.qualifierId!==q.qualifierId||response.headers.get("ETag")!==`"qf${current.version}"`)throw Error("조건 버전을 확인하지 못했습니다.");
  reset(current);setEditing(false);
 }
 async function revisions(signal:AbortSignal,cursor:number|null=null){
  if(!selected)return;const response=await request(path+"/qualifiers/"+selected.qualifierId+`/revisions?limit=5${cursor?`&before=${cursor}`:""}`,signal),data:Revision[]=await response.json();
  if(signal.aborted)return;if(!Array.isArray(data)||data.length>5||data.some((r,i)=>!id(r.revisionId)||r.qualifierId!==selected.qualifierId||!version(r.version)||!pin(r.claim)||!id(r.predicateConceptId)||!typed(r.value)||!["ACTIVE","RETIRED"].includes(r.state)||!id(r.actorId)||!Number.isFinite(Date.parse(r.occurredAt))||(cursor!==null&&r.version>=cursor)||(i>0&&r.version>=data[i-1].version)))throw Error("조건 이력을 확인하지 못했습니다.");
  setHistory(old=>cursor?[...(old??[]),...data]:data);setBefore(data.length===5?data[data.length-1].version:null);setEditing(false);setAck(false);
 }
 async function retire(signal:AbortSignal){
  if(retired.current||!selected||!ack||stale)return;
  const response=await request(path+"/qualifiers/"+selected.qualifierId,signal,{method:"DELETE",headers:{"If-Match":`"qf${selected.version}"`}});
  if(response.status!==204||response.headers.get("ETag")!==`"qf${selected.version+1}"`)throw Error("조건 폐기 결과를 확인해 주세요.");
  setSelected({...selected,state:"RETIRED",version:selected.version+1});setAck(false);setHistory(null);await list(signal);setNotice("관계 조건을 폐기했습니다. 수정 이력은 유지됩니다.");
 }
 function choices(label:string,value:string,change:(s:string)=>void){return <label>{label}<select aria-label={label} value={value} disabled={busy} onChange={e=>change(e.target.value)}><option value="">개념을 선택하세요</option>{value&&!concepts.some(c=>c.conceptId===value)&&<option value={value}>현재 선택 · {value}</option>}{concepts.map(c=><option key={c.conceptId} value={c.conceptId}>{c.title} · {c.conceptId}</option>)}</select></label>}
 function snapshot(q:{claim:Pin;predicateConceptId:string;value:Value}){return <><p>{name(q.predicateConceptId)}: {q.value.type==="CONCEPT"?name(q.value.value):q.value.value} ({labels[types.indexOf(q.value.type)]})</p><p className="muted">고정한 관계 v{q.claim.version} · {name(q.claim.subjectConceptId)} → {name(q.claim.predicateConceptId)} → {claimObjectText(q.claim.object,name)}</p>{q.claim.version!==claimVersion&&<p>현재 열어 둔 관계와 버전이 다릅니다. 조건의 적용 범위를 확인해 주세요.</p>}</>}
 return <section aria-label="관계 조건과 수정 이력" className="claim-qualifiers"><h3>관계 조건 (Qualifier)</h3><p className="muted">적용 대상, 기준 날짜, 수치 등 관계의 조건을 기록합니다. 조건은 저장할 때 확인한 관계 버전에 고정됩니다.</p>
 {notice&&<p role="status">{notice}</p>}{busy&&<p className="muted">조건 확인 중…</p>}
 {parentRetired?<p>관계가 폐기되어 조건 수정을 종료했습니다. 선택한 조건의 이력은 확인할 수 있습니다.</p>:<>
 <div className="actions"><button disabled={busy} onClick={()=>void run(async signal=>{setRows([]);setAfter(null);await list(signal)})}>관계 조건 새로고침</button><button disabled={busy} onClick={()=>reset(null)}>새 조건 작성</button></div>
 <div className="qualifier-results">{rows.map(q=><button key={q.qualifierId} disabled={busy} aria-label={`조건 열기: ${q.qualifierId}`} onClick={()=>void run(signal=>select(q,signal))}>{name(q.predicateConceptId)}: {q.value.type==="CONCEPT"?name(q.value.value):q.value.value}<small>조건 v{q.version} · 관계 v{q.claim.version}</small></button>)}</div>{!rows.length&&!busy&&<p>불러온 활성 조건이 없습니다.</p>}{after&&<button disabled={busy} onClick={()=>void run(signal=>list(signal,after))}>관계 조건 더 보기</button>}
 </>}
 {(editing||selected)&&<section aria-label="선택한 관계 조건"><button disabled={busy} onClick={()=>{setSelected(null);setEditing(false);setHistory(null);setPending(null);setAck(false)}}>조건 상세 닫기</button>
 {selected&&<><h4>조건 v{selected.version} · {selected.state}</h4>{snapshot(selected)}<button disabled={busy} onClick={()=>void run(signal=>revisions(signal))}>조건 수정 이력</button></>}
 {!parentRetired&&selected?.state==="ACTIVE"&&!editing&&<button disabled={busy} onClick={()=>reset(selected)}>조건 편집</button>}
 {editing&&!parentRetired&&<form onSubmit={e=>{e.preventDefault();void run(save)}}>
 {choices("조건의 서술 개념",predicate,setPredicate)}<label>조건 값 종류<select aria-label="조건 값 종류" value={kind} disabled={busy} onChange={e=>{setKind(e.target.value);setText(e.target.value==="BOOLEAN"?"true":"")}}>{types.map((t,i)=><option key={t} value={t}>{labels[i]}</option>)}</select></label>
 {kind==="CONCEPT"?choices("조건 값 개념",text,setText):kind==="BOOLEAN"?<label>조건 값<select aria-label="조건 값" value={text} disabled={busy} onChange={e=>setText(e.target.value)}><option value="true">참</option><option value="false">거짓</option></select></label>:<label>조건 값<input aria-label="조건 값" value={text} maxLength={kind==="STRING"?2000:128} disabled={busy} placeholder={kind==="DATE"?"YYYY-MM-DD":kind==="NUMBER"?"예: 18.5":""} onChange={e=>setText(e.target.value)}/></label>}
 <div className="actions"><button type="button" disabled={busy} onClick={()=>void run(signal=>picker(signal))}>조건 개념 새로고침</button>{conceptAfter&&<button type="button" disabled={busy} onClick={()=>void run(signal=>picker(signal,conceptAfter))}>조건 개념 더 보기</button>}</div>
 <button type="button" disabled={busy} onClick={()=>void run(inspect)}>조건에 적용할 최신 버전 확인</button>
 {pending&&<div className="qualifier-latest"><p>확인한 관계 v{pending.claim.version}: {name(conceptId)} → {name(pending.claim.predicateConceptId)} → {claimObjectText(pending.claim.object,name)}</p>{pending.qualifier&&snapshot(pending.qualifier)}<p>{stale?"다시 시작하면 입력을 최신 저장 내용으로 바꿉니다.":"이 관계 버전에 조건을 적용합니다."}</p><button type="button" disabled={busy} onClick={accept}>확인한 버전으로 조건 작성</button></div>}
 {inspected&&<p>적용할 관계 버전: v{inspected.version}</p>}<button type="submit" disabled={busy||stale||!inspected||!predicate||!text.trim()}>{selected?"조건 수정 저장":"조건 추가"}</button>
 <button type="button" disabled={busy} onClick={()=>setEditing(false)}>조건 편집 취소</button></form>}
 {selected&&!parentRetired&&selected.state==="ACTIVE"&&!editing&&<div><label><input type="checkbox" disabled={busy||stale} checked={ack} onChange={e=>setAck(e.target.checked)}/>조건 폐기에 동의합니다</label><button disabled={busy||stale||!ack} onClick={()=>void run(retire)}>조건 폐기 확정</button></div>}
 {history&&<div className="qualifier-revisions">{history.map(r=><article key={r.revisionId}><h4>조건 이력 v{r.version} · {r.state}</h4>{snapshot(r)}</article>)}{before&&<button disabled={busy} onClick={()=>void run(signal=>revisions(signal,before))}>이전 조건 수정 더 보기</button>}</div>}
 </section>}
 <RetiredQualifiers key={`${spaceId}:${conceptId}:${claimId}`} spaceId={spaceId} conceptId={conceptId} claimId={claimId} onDenied={onDenied}/>
 </section>;
}
