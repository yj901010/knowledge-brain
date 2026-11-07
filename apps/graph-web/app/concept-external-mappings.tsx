"use client";
import RetiredMappings from "./retired-mappings";
import {useEffect,useRef,useState} from "react";
type ConceptPin={revisionId:string;version:number;title:string;description:string};
type SourcePin={sourceId:string;revisionId:string;version:number;title:string;url:string|null;citation:string;licenseNote:string};
type Source=Omit<SourcePin,"revisionId">&{spaceId:string;visibility:string;state:string};
type Concept={conceptId:string;spaceId:string;version:number;title:string;description:string;visibility:string;state:string};
type Fields={system:string;code:string;systemVersion:string;label:string};
type Snapshot=Fields&{concept:ConceptPin;source:SourcePin;mappingState:"CANDIDATE"};
type Mapping=Snapshot&{mappingId:string;spaceId:string;conceptId:string;visibility:"PRIVATE";state:"ACTIVE"|"RETIRED";version:number;createdAt:string;updatedAt:string};
type Revision=Snapshot&{revisionId:string;mappingId:string;version:number;state:"ACTIVE"|"RETIRED";actorId:string;occurredAt:string};
type Inspected={concept:Concept;source:Source;mapping:Mapping|null};
const empty:Fields={system:"",code:"",systemVersion:"",label:""};
const id=(s:unknown):s is string=>typeof s==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(s);
const version=(n:unknown):n is number=>typeof n==="number"&&Number.isSafeInteger(n)&&n>0;
const text=(s:unknown,max:number):s is string=>typeof s==="string"&&Array.from(s).length<=max&&!/[\u0000\ud800-\udfff]/u.test(s);
const date=(s:unknown)=>typeof s==="string"&&Number.isFinite(Date.parse(s));
function sourceFields(s:Source|SourcePin){return s&&id(s.sourceId)&&version(s.version)&&text(s.title,200)&&!!s.title&&(s.url===null||text(s.url,2048))&&text(s.citation,4000)&&text(s.licenseNote,1000)}
function snapshot(s:Snapshot){return s&&s.mappingState==="CANDIDATE"&&text(s.system,100)&&!!s.system&&text(s.code,200)&&!!s.code&&text(s.systemVersion,100)&&text(s.label,200)&&s.concept&&id(s.concept.revisionId)&&version(s.concept.version)&&text(s.concept.title,200)&&text(s.concept.description,32768)&&sourceFields(s.source)&&id(s.source.revisionId)}
function Pinned({value}:{value:Snapshot}){return <div className="mapping-pinned"><h5>{value.system} · {value.code}</h5><p>판본: {value.systemVersion||"미기록"} · 외부 명칭: {value.label||"미기록"}</p><p className="muted">검토 전 후보(CANDIDATE) · 고정한 개념 v{value.concept.version} · 출처 v{value.source.version}</p><h5>{value.concept.title}</h5><p>{value.concept.description}</p><h5>고정한 근거: {value.source.title}</h5><p>{value.source.citation||"인용 정보 미기록"}</p>{value.source.url&&<p>출처 URL: {value.source.url}</p>}<p>라이선스 메모: {value.source.licenseNote||"미기록"}</p><small>개념 Revision: {value.concept.revisionId}<br/>출처: {value.source.sourceId} · Revision: {value.source.revisionId}</small></div>}
export default function ConceptExternalMappings({spaceId,conceptId,parentRetired=false,onDenied}:{spaceId:string;conceptId:string;parentRetired?:boolean;onDenied:()=>void}){
 const conceptPath=`/api/spaces/${spaceId}/concepts/${conceptId}`,path=conceptPath+"/external-mappings",sourcesPath=`/api/spaces/${spaceId}/sources`,scope=path;
 const [rows,setRows]=useState<Mapping[]>([]),[after,setAfter]=useState<string|null>(null),[sources,setSources]=useState<Source[]>([]),[sourceAfter,setSourceAfter]=useState<string|null>(null);
 const [selected,setSelected]=useState<Mapping|null>(null),[editing,setEditing]=useState(false),[fields,setFields]=useState<Fields>(empty),[sourceId,setSourceId]=useState("");
 const [inspected,setInspected]=useState<Inspected|null>(null),[pending,setPending]=useState<Inspected|null>(null),[stale,setStale]=useState(false);
 const [history,setHistory]=useState<Revision[]|null>(null),[before,setBefore]=useState<number|null>(null),[ack,setAck]=useState(false),[busy,setBusy]=useState(false),[notice,setNotice]=useState("");
 const operation=useRef<AbortController|null>(null),alive=useRef(true),retired=useRef(parentRetired),scopeRef=useRef(scope);retired.current=parentRetired;scopeRef.current=scope;
 function parse(m:Mapping){if(!m||!id(m.mappingId)||m.spaceId!==spaceId||m.conceptId!==conceptId||!snapshot(m)||m.visibility!=="PRIVATE"||!["ACTIVE","RETIRED"].includes(m.state)||!version(m.version)||!date(m.createdAt)||!date(m.updatedAt))throw Error("외부 연결 응답을 확인하지 못했습니다.");return m}
 async function request(url:string,signal:AbortSignal,init:RequestInit={}){
  const r=await fetch(url,{...init,credentials:"same-origin",cache:"no-store",redirect:"error",signal,headers:{...(init.body?{"Content-Type":"application/json"}:{}),...init.headers}});
  if(signal.aborted||!alive.current||scopeRef.current!==scope)throw Error();
  if(r.status===401||r.status===403){onDenied();throw Error("공간 접근을 확인해 주세요.")}
  if(r.status===412){setStale(true);setPending(null);setInspected(null);setAck(false);throw Error("다른 수정이 있습니다. 입력은 유지했습니다. 최신 개념·출처·연결을 확인한 뒤 다시 시작해 주세요.")}
  if(r.status===409)throw Error("같은 체계·코드·판본이 이미 연결되어 있습니다. 목록을 확인해 주세요.");
  if(r.status===404)throw Error("개념이나 출처가 폐기되었거나 연결을 조회할 수 없습니다.");
  if(!r.ok)throw Error(r.status===400?"외부 체계·코드·판본과 출처를 확인해 주세요.":"요청 결과를 확인하지 못했습니다. 입력은 유지했습니다. 목록과 이력을 확인해 주세요.");
  return r;
 }
 async function run(work:(signal:AbortSignal)=>Promise<void>){
  if(operation.current)return;const controller=new AbortController();operation.current=controller;setBusy(true);setNotice("");
  const timer=setTimeout(()=>controller.abort(),20000);
  try{await work(controller.signal)}catch(e){if(alive.current&&operation.current===controller&&scopeRef.current===scope)setNotice(controller.signal.aborted?"응답이 지연되어 요청을 중단했습니다. 입력은 유지했습니다.":e instanceof Error?e.message:"요청을 완료하지 못했습니다.")}
  finally{clearTimeout(timer);if(alive.current&&operation.current===controller){operation.current=null;setBusy(false)}}
 }
 function pageCheck<T>(data:{items:T[];nextAfter:string|null},cursor:string|null,key:(t:T)=>string){if(!data||!Array.isArray(data.items)||data.items.length>20||data.items.some((r,i)=>(cursor!==null&&key(r)<=cursor)||(i>0&&key(r)<=key(data.items[i-1])))||(data.nextAfter!==null&&(!id(data.nextAfter)||data.items.length!==20||data.nextAfter!==key(data.items[data.items.length-1]))))throw Error("목록 순서를 확인하지 못했습니다.")}
 async function list(signal:AbortSignal,cursor:string|null=null){
  const r=await request(path+`?limit=20${cursor?`&after=${cursor}`:""}`,signal),data=await r.json();if(signal.aborted)return;
  if(!data||!Array.isArray(data.items))throw Error("연결 목록을 확인하지 못했습니다.");const items:Mapping[]=data.items.map(parse);
  if(items.some(m=>m.state!=="ACTIVE"))throw Error("연결 상태를 확인하지 못했습니다.");pageCheck({items,nextAfter:data.nextAfter},cursor,m=>m.mappingId);
  setRows(old=>cursor?[...old,...items]:items);setAfter(data.nextAfter);
 }
 async function picker(signal:AbortSignal,cursor:string|null=null){
  const r=await request(sourcesPath+`?limit=20${cursor?`&after=${cursor}`:""}`,signal),data=await r.json();if(signal.aborted)return;
  pageCheck<Source>(data,cursor,s=>s.sourceId);if(data.items.some((s:Source)=>!sourceFields(s)||s.spaceId!==spaceId||s.visibility!=="PRIVATE"||s.state!=="ACTIVE"))throw Error("근거 출처를 확인하지 못했습니다.");
  setSources(old=>cursor?[...old,...data.items]:data.items);setSourceAfter(data.nextAfter);
 }
 useEffect(()=>{alive.current=true;if(!parentRetired)void run(async signal=>{await list(signal);await picker(signal)});return()=>{alive.current=false;operation.current?.abort();operation.current=null}},[scope]);
 useEffect(()=>{if(parentRetired){operation.current?.abort();operation.current=null;setBusy(false);setRows([]);setAfter(null);setSources([]);setEditing(false);setInspected(null);setPending(null);setAck(false)}},[parentRetired]);
 function reset(m:Mapping|null){setSelected(m);setFields(m?{system:m.system,code:m.code,systemVersion:m.systemVersion,label:m.label}:empty);setSourceId(m?.source.sourceId??"");setHistory(null);setBefore(null);setEditing(true);setInspected(null);setPending(null);setStale(false);setAck(false);setNotice("")}
 async function inspect(signal:AbortSignal){
  let mapping:Mapping|null=null;
  if(selected){const r=await request(path+"/"+selected.mappingId,signal);mapping=parse(await r.json());if(r.headers.get("ETag")!==`"xm${mapping.version}"`||mapping.mappingId!==selected.mappingId)throw Error("연결 버전을 확인하지 못했습니다.")}
  const chosen=stale&&mapping?mapping.source.sourceId:sourceId;
  const r=await request(conceptPath,signal),concept:Concept=await r.json();
  if(!concept||concept.conceptId!==conceptId||concept.spaceId!==spaceId||concept.state!=="ACTIVE"||concept.visibility!=="PRIVATE"||!version(concept.version)||!text(concept.title,200)||!text(concept.description,32768)||r.headers.get("ETag")!==`"${concept.version}"`)throw Error("현재 개념을 확인하지 못했습니다.");
  const sr=await request(sourcesPath+"/"+chosen,signal),source:Source=await sr.json();
  if(!sourceFields(source)||source.sourceId!==chosen||source.spaceId!==spaceId||source.visibility!=="PRIVATE"||source.state!=="ACTIVE"||sr.headers.get("ETag")!==`"src${source.version}"`)throw Error("현재 출처를 확인하지 못했습니다.");
  if(!signal.aborted)setPending({concept,source,mapping});
 }
 function accept(){if(!pending)return;const current=pending;if(stale&&current.mapping)reset(current.mapping);setSourceId(current.source.sourceId);setInspected(current);setSelected(current.mapping);setPending(null);setStale(false);setAck(false)}
 async function save(signal:AbortSignal){
  if(retired.current||!inspected||stale||sourceId!==inspected.source.sourceId)return;
  const r=await request(path+(selected?"/"+selected.mappingId:""),signal,{method:selected?"PUT":"POST",headers:selected?{"If-Match":`"xm${selected.version}"`}:{},body:JSON.stringify({...fields,sourceId,sourceVersion:inspected.source.version,conceptVersion:inspected.concept.version})});
  const m=parse(await r.json());if(signal.aborted)return;if(r.headers.get("ETag")!==`"xm${m.version}"`)throw Error("저장한 연결 버전을 확인해 주세요.");
  setSelected(m);setEditing(false);setHistory(null);setAck(false);await list(signal);setNotice("외부 연결을 저장했습니다. 검토 전 후보 상태입니다.");
 }
 async function select(m:Mapping,signal:AbortSignal){const r=await request(path+"/"+m.mappingId,signal),current=parse(await r.json());if(signal.aborted)return;if(current.mappingId!==m.mappingId||r.headers.get("ETag")!==`"xm${current.version}"`)throw Error("연결 버전을 확인하지 못했습니다.");reset(current);setEditing(false)}
 async function revisions(signal:AbortSignal,cursor:number|null=null){
  if(!selected)return;const r=await request(path+"/"+selected.mappingId+`/revisions?limit=5${cursor?`&before=${cursor}`:""}`,signal),data:Revision[]=await r.json();if(signal.aborted)return;
  if(!Array.isArray(data)||data.length>5||data.some((r,i)=>!id(r.revisionId)||r.mappingId!==selected.mappingId||!version(r.version)||!snapshot(r)||!["ACTIVE","RETIRED"].includes(r.state)||!id(r.actorId)||!date(r.occurredAt)||(cursor!==null&&r.version>=cursor)||(i>0&&r.version>=data[i-1].version)))throw Error("연결 이력을 확인하지 못했습니다.");
  setHistory(old=>cursor?[...(old??[]),...data]:data);setBefore(data.length===5?data[data.length-1].version:null);setEditing(false);setAck(false);
 }
 async function retire(signal:AbortSignal){
  if(retired.current||!selected||!ack||stale)return;const r=await request(path+"/"+selected.mappingId,signal,{method:"DELETE",headers:{"If-Match":`"xm${selected.version}"`}});
  if(r.status!==204||r.headers.get("ETag")!==`"xm${selected.version+1}"`)throw Error("연결 해제 결과를 확인해 주세요.");
  setSelected({...selected,state:"RETIRED",version:selected.version+1});setAck(false);setHistory(null);await list(signal);setNotice("외부 연결을 해제했습니다. 수정 이력은 유지됩니다.");
 }
 return <section aria-label="개념 외부 연결과 근거" className="concept-external-mappings"><h3>외부 체계 연결 (External Mapping)</h3><p className="muted">개인 개념과 외부 식별자를 근거 출처와 함께 기록합니다. 모두 검토 전 후보(CANDIDATE)이며 공식 매핑이나 지식 검증을 뜻하지 않습니다. 데이터는 개인 공간에 저장합니다.</p>
 {notice&&<p role="status">{notice}</p>}{busy&&<p>외부 연결 확인 중…</p>}
 {parentRetired?<p>개념이 폐기되어 외부 연결 수정을 종료했습니다. 선택한 연결의 이력은 확인할 수 있습니다.</p>:<><div className="actions"><button disabled={busy} onClick={()=>void run(async signal=>{setRows([]);setAfter(null);await list(signal)})}>외부 연결 새로고침</button><button disabled={busy} onClick={()=>reset(null)}>새 외부 연결 작성</button></div>
 <div className="external-mapping-results">{rows.map(m=><button key={m.mappingId} disabled={busy} aria-label={`외부 연결 열기: ${m.mappingId}`} onClick={()=>void run(signal=>select(m,signal))}>{m.system} · {m.code}<small>판본 {m.systemVersion||"미기록"} · CANDIDATE · 연결 v{m.version}</small></button>)}</div>{!rows.length&&!busy&&<p>불러온 활성 외부 연결이 없습니다.</p>}{after&&<button disabled={busy} onClick={()=>void run(signal=>list(signal,after))}>외부 연결 더 보기</button>}</>}
 {(editing||selected)&&<section aria-label="선택한 외부 연결"><button disabled={busy} onClick={()=>{setSelected(null);setEditing(false);setHistory(null);setPending(null);setAck(false)}}>외부 연결 상세 닫기</button>
 {selected&&<><h4>외부 연결 v{selected.version} · {selected.state}</h4><Pinned value={selected}/><button disabled={busy} onClick={()=>void run(signal=>revisions(signal))}>외부 연결 수정 이력</button></>}
 {!parentRetired&&selected?.state==="ACTIVE"&&!editing&&<button disabled={busy} onClick={()=>reset(selected)}>외부 연결 편집</button>}
 {editing&&!parentRetired&&<form onSubmit={e=>{e.preventDefault();void run(save)}}>
 {([["system","외부 체계",200],["code","외부 코드",400],["systemVersion","외부 체계 판본",200],["label","외부 명칭",400]] as const).map(([key,label,max])=><label key={key}>{label}<input aria-label={label} value={fields[key]} maxLength={max} disabled={busy} onChange={e=>setFields(previous=>({...previous,[key]:e.target.value}))}/></label>)}
 <p className="muted">코드의 대소문자와 앞자리 0을 구분합니다. 판본을 모르면 비워두세요. 빈 판본은 최신 판본을 뜻하지 않습니다.</p>
 <label>외부 연결 근거 출처<select aria-label="외부 연결 근거 출처" value={sourceId} disabled={busy} onChange={e=>{setSourceId(e.target.value);setInspected(null);setPending(null)}}><option value="">출처를 선택하세요</option>{sourceId&&!sources.some(s=>s.sourceId===sourceId)&&<option value={sourceId}>현재 선택 · {sourceId}</option>}{sources.map(s=><option key={s.sourceId} value={s.sourceId}>{s.title} · {s.sourceId}</option>)}</select></label>
 <p className="muted">근거는 개인 출처 보관함에서 작성합니다. 라이선스 메모는 입력한 기록이며 이용 권한 확인을 대신하지 않습니다.</p>
 <button type="button" disabled={busy} onClick={()=>void run(signal=>picker(signal))}>매핑 출처 새로고침</button>{sourceAfter&&<button type="button" disabled={busy} onClick={()=>void run(signal=>picker(signal,sourceAfter))}>매핑 출처 더 보기</button>}
 <button type="button" disabled={busy||!sourceId} onClick={()=>void run(inspect)}>연결할 최신 개념·출처 확인</button>
 {pending&&<div className="mapping-latest"><h5>확인한 개념 v{pending.concept.version} · 출처 v{pending.source.version}</h5><p>{pending.concept.title}</p><p>{pending.concept.description}</p><p>{pending.source.title}</p><p>{pending.source.citation}</p><p>{pending.source.url}</p><p>{pending.source.licenseNote}</p>{pending.mapping&&<Pinned value={pending.mapping}/>}<p>{stale?"다시 시작하면 입력을 최신 저장 내용으로 바꿉니다.":"확인한 두 버전에 연결 근거를 고정합니다."}</p><button type="button" disabled={busy} onClick={accept}>확인한 버전으로 연결 작성</button></div>}
 {inspected&&<p>적용할 개념 v{inspected.concept.version} · 출처 v{inspected.source.version}</p>}<button type="submit" disabled={busy||stale||!inspected||!fields.system.trim()||!fields.code.trim()||!sourceId}>{selected?"외부 연결 수정 저장":"외부 연결 추가"}</button><button type="button" disabled={busy} onClick={()=>setEditing(false)}>외부 연결 편집 취소</button>
 </form>}
 {selected&&!parentRetired&&selected.state==="ACTIVE"&&!editing&&<div><label><input type="checkbox" disabled={busy||stale} checked={ack} onChange={e=>setAck(e.target.checked)}/>외부 연결 해제에 동의합니다</label><button disabled={busy||stale||!ack} onClick={()=>void run(retire)}>외부 연결 해제 확정</button></div>}
 {history&&<div className="external-mapping-revisions">{history.map(r=><article key={r.revisionId}><h4>연결 이력 v{r.version} · {r.state}</h4><Pinned value={r}/></article>)}{before&&<button disabled={busy} onClick={()=>void run(signal=>revisions(signal,before))}>이전 외부 연결 수정 더 보기</button>}</div>}
 </section>}
 <RetiredMappings key={`${spaceId}:${conceptId}`} spaceId={spaceId} conceptId={conceptId} onDenied={onDenied}/>
 </section>;
}
