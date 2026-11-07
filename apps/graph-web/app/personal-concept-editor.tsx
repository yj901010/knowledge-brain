"use client";
import {javaBlank} from "./unicode-text";
import {ConceptRevisionComparison,type ConceptRevision as Revision} from "./knowledge-revision-comparison";
import {useEffect,useRef,useState} from "react";

type Concept={conceptId:string;spaceId:string;visibility:string;state:string;version:number;title:string;description:string;createdAt:string;updatedAt:string};
type Current={concept:Concept;etag:string};
type Request={version:number;controller:AbortController;timer:ReturnType<typeof setTimeout>};

async function api(path:string,signal:AbortSignal,options:RequestInit={}){
 return fetch(path,{...options,credentials:"same-origin",cache:"no-store",redirect:"error",signal,headers:{...(options.body?{"Content-Type":"application/json"}:{}),...options.headers}});
}
function validId(value:unknown){return typeof value==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)}
function validTitle(value:unknown){return typeof value==="string"&&!javaBlank(value)&&Array.from(value).length<=200&&!value.includes("\0")}
function validDescription(value:unknown){return typeof value==="string"&&!value.includes("\0")&&new TextEncoder().encode(value).length<=32768}
function validDate(value:unknown){return typeof value==="string"&&Number.isFinite(Date.parse(value))}
function validVersion(value:unknown){return typeof value==="number"&&Number.isSafeInteger(value)&&value>0}
async function current(response:Response,spaceId:string,conceptId:string):Promise<Current>{
 const etag=response.headers.get("ETag"),concept:Concept=await response.json();
 if(!etag||!/^"[1-9][0-9]{0,18}"$/.test(etag)||!concept||concept.conceptId!==conceptId||concept.spaceId!==spaceId||concept.visibility!=="PRIVATE"||concept.state!=="ACTIVE"||!validVersion(concept.version)||etag!==`"${concept.version}"`||typeof concept.title!=="string"||typeof concept.description!=="string"||!validDate(concept.createdAt)||!validDate(concept.updatedAt))throw new Error();
 return {concept,etag};
}

export default function PersonalConceptEditor({spaceId,conceptId,onDenied,onSaved,onRetired}:{spaceId:string;conceptId:string;onDenied:()=>void;onSaved:(concept:{conceptId:string;title:string})=>void;onRetired:(conceptId:string)=>void}){
 const [value,setValue]=useState<Current|null>(null),[mode,setMode]=useState<"read"|"edit"|"history"|"retire">("read");
 const [title,setTitle]=useState(""),[description,setDescription]=useState(""),[base,setBase]=useState("");
 const [busy,setBusy]=useState(false),[notice,setNotice]=useState(""),[conflict,setConflict]=useState(false),[latest,setLatest]=useState<Current|null>(null);
 const [acknowledged,setAcknowledged]=useState(false);
 const [revisions,setRevisions]=useState<Revision[]>([]),[revision,setRevision]=useState<Revision|null>(null),[before,setBefore]=useState<number|null>(null);
 const epoch=useRef(0),request=useRef<Request|null>(null),path=`/api/spaces/${spaceId}/concepts/${conceptId}`;
 function cancelRequest(){if(request.current){clearTimeout(request.current.timer);request.current.controller.abort();request.current=null}}
 function start():Request{
  cancelRequest();const controller=new AbortController(),version=++epoch.current,timer=setTimeout(()=>controller.abort(),8000),operation={version,controller,timer};
  request.current=operation;setBusy(true);return operation;
 }
 function active(operation:Request){return operation.version===epoch.current&&!operation.controller.signal.aborted}
 function finish(operation:Request){clearTimeout(operation.timer);if(operation.version===epoch.current){request.current=null;setBusy(false)}}
 function clearPrivate(){setValue(null);setTitle("");setDescription("");setBase("");setLatest(null);setRevisions([]);setRevision(null);setBefore(null);setConflict(false);setAcknowledged(false);setMode("read")}
 function denied(response:Response){
  if(![401,403,404].includes(response.status))return false;
  epoch.current++;cancelRequest();clearPrivate();setBusy(false);setNotice("개념 접근 권한을 확인할 수 없습니다.");onDenied();return true;
 }
 useEffect(()=>{
  clearPrivate();setNotice("");const operation=start();
  void (async()=>{try{
   const response=await api(path,operation.controller.signal);if(!active(operation)||denied(response))return;if(!response.ok)throw new Error();
   const data=await current(response,spaceId,conceptId);if(active(operation))setValue(data);
  }catch{if(operation.version===epoch.current)setNotice("개념을 불러오지 못했습니다. 다시 선택해 주세요.")}finally{finish(operation)}})();
  return()=>{epoch.current++;cancelRequest()};
 },[path]);
 function edit(data:Current){if(data.concept.state!=="ACTIVE")return;setMode("edit");setTitle(data.concept.title);setDescription(data.concept.description);setBase(data.etag);setConflict(false);setLatest(null);setAcknowledged(false);setNotice("")}
 function leaveEdit(){setMode("read");setTitle("");setDescription("");setBase("");setLatest(null);setConflict(false);setAcknowledged(false);setNotice("")}
 function confirmRetirement(data:Current){if(data.concept.state!=="ACTIVE")return;setValue(data);setMode("retire");setBase(data.etag);setLatest(null);setConflict(false);setAcknowledged(false);setNotice("")}
 async function save(event:React.FormEvent){
  event.preventDefault();if(busy||conflict||!base||mode!=="edit"||value?.concept.state!=="ACTIVE")return;
  if(javaBlank(title)||Array.from(title).length>200||title.includes("\0")||description.includes("\0")||new TextEncoder().encode(description).length>32768){setNotice("제목은 1~200자, 설명은 UTF-8 32 KiB 이내로 입력해 주세요.");return}
  const operation=start();setNotice("");
  try{
   const response=await api(path,operation.controller.signal,{method:"PUT",headers:{"If-Match":base},body:JSON.stringify({title,description})});
   if(!active(operation)||denied(response))return;
   if(response.status===412){setConflict(true);setLatest(null);setNotice("다른 수정이 있습니다. 입력은 유지했습니다. 최신 개념 내용을 확인해 주세요.");return}
   if(response.status===400||response.status===413){setNotice("입력 형식을 확인해 주세요. 제목과 설명은 유지했습니다.");return}
   if(!response.ok)throw new Error();const data=await current(response,spaceId,conceptId);if(!active(operation))return;
   setValue(data);leaveEdit();setRevisions([]);setRevision(null);setBefore(null);setNotice("개념을 수정했습니다.");onSaved({conceptId:data.concept.conceptId,title:data.concept.title});
  }catch{if(operation.version===epoch.current)setNotice("저장 결과를 확인하지 못했습니다. 입력은 유지했습니다. 다시 저장할 때 개념 버전을 확인합니다.")}
  finally{finish(operation)}
 }
 async function retire(){
  if(busy||conflict||!acknowledged||!base||mode!=="retire"||!value||value.concept.state!=="ACTIVE")return;
  const previous=value,operation=start();setNotice("");
  try{
   const response=await api(path,operation.controller.signal,{method:"DELETE",headers:{"If-Match":base}});
   if(!active(operation)||denied(response))return;
   if(response.status===412){setConflict(true);setLatest(null);setAcknowledged(false);setNotice("다른 수정이 있습니다. 최신 개념 내용을 확인한 뒤 폐기를 다시 확인해 주세요.");return}
   const nextVersion=previous.concept.version+1,etag=response.headers.get("ETag");
   if(response.status!==204||!validVersion(nextVersion)||!etag||!/^"[1-9][0-9]{0,18}"$/.test(etag)||etag!==`"${nextVersion}"`)throw new Error();
   setValue({concept:{...previous.concept,state:"RETIRED",version:nextVersion},etag});leaveEdit();setRevisions([]);setRevision(null);setBefore(null);
   setNotice("개념을 폐기했습니다. 기존 노트 연결은 유지됩니다.");onRetired(conceptId);
  }catch{if(operation.version===epoch.current){setAcknowledged(false);setNotice("개념 폐기 결과를 확인하지 못했습니다. 폐기 여부를 다시 확인한 뒤 시도해 주세요.")}}
  finally{finish(operation)}
 }
 async function inspectLatest(){
  if(busy)return;const operation=start();setLatest(null);setNotice("");
  try{
   const response=await api(path,operation.controller.signal);if(!active(operation)||denied(response))return;if(!response.ok)throw new Error();
   const data=await current(response,spaceId,conceptId);if(active(operation))setLatest(data);
  }catch{if(operation.version===epoch.current)setNotice(mode==="retire"?"최신 개념 내용을 불러오지 못했습니다. 폐기는 진행하지 않았습니다.":"최신 개념 내용을 불러오지 못했습니다. 입력은 유지했습니다.")}
  finally{finish(operation)}
 }
 async function history(append=false){
  if(busy)return;const cursor=append?before:null;if(append&&!cursor)return;
  const operation=start();setNotice("");setMode("history");
  if(!append){setRevisions([]);setRevision(null);setBefore(null)}
  try{
   const response=await api(`${path}/revisions?limit=5${cursor?`&before=${cursor}`:""}`,operation.controller.signal);
   if(!active(operation)||denied(response))return;if(!response.ok)throw new Error();const rows:Revision[]=await response.json();if(!active(operation))return;
   if(!Array.isArray(rows)||rows.length>5||rows.some((r,index)=>!r||!validId(r.revisionId)||r.conceptId!==conceptId||!validVersion(r.version)||!validTitle(r.title)||!validDescription(r.description)||!validId(r.actorId)||!validDate(r.occurredAt)||!["ACTIVE","RETIRED","DEPRECATED","MERGED"].includes(r.state)||(cursor!==null&&r.version>=cursor)||(index>0&&r.version>=rows[index-1].version)))throw new Error();
   if(new Set(rows.map(row=>row.revisionId)).size!==rows.length||append&&rows.some(row=>revisions.some(prior=>prior.revisionId===row.revisionId||prior.version===row.version)))throw new Error();
   setRevisions(previous=>append?[...previous,...rows]:rows);
   if(!append)setRevision(rows[0]??null);setBefore(rows.length===5?rows[rows.length-1].version:null);
  }catch{if(operation.version===epoch.current){setRevisions([]);setRevision(null);setBefore(null);setNotice("개념 수정 이력을 불러오지 못했습니다. 다시 시도해 주세요.")}}
  finally{finish(operation)}
 }
 return <section className="personal-concept-editor" aria-label="개인 개념 상세와 수정 이력">
  {notice&&<p role="status">{notice}</p>}
  {!value?<p className="muted">{busy?"개념 불러오는 중…":"개념을 다시 선택해 주세요."}</p>:mode==="read"?<>
   <h4>{value.concept.title}</h4><pre>{value.concept.description}</pre>
   <p className="muted">Revision v{value.concept.version} · {value.concept.state}{value.concept.state==="ACTIVE"&&` · ${new Date(value.concept.updatedAt).toLocaleString()}`}</p>
   {value.concept.state==="RETIRED"&&<p className="muted">폐기된 개념입니다. 수정 이력과 기존 노트 연결은 보존되며 현재 복원 기능은 없습니다.</p>}
   <div className="actions">{value.concept.state==="ACTIVE"&&<button disabled={busy} onClick={()=>edit(value)}>개인 개념 편집</button>}<button disabled={busy} onClick={()=>void history()}>개인 개념 수정 이력</button>{value.concept.state==="ACTIVE"&&<button disabled={busy} onClick={()=>confirmRetirement(value)}>개인 개념 폐기</button>}</div>
  </>:mode==="edit"?<form onSubmit={save}>
   <label>편집할 개념 제목<input required value={title} disabled={busy} onChange={e=>setTitle(e.target.value)}/></label>
   <label>편집할 개념 설명<textarea rows={9} value={description} disabled={busy} onChange={e=>setDescription(e.target.value)}/></label>
   <p className="muted">저장 전에는 반영되지 않습니다. 개념·공간을 바꾸거나 상세를 닫으면 저장하지 않은 입력은 사라집니다.</p>
   <div className="actions"><button type="button" disabled={busy} onClick={leaveEdit}>개념 편집 취소</button><button disabled={busy||conflict||javaBlank(title)}>개념 수정 저장</button></div>
   {conflict&&<><button type="button" disabled={busy} onClick={()=>void inspectLatest()}>최신 개념 내용 확인</button>{latest&&<div className="concept-edit-conflict">
    <h4>최신 Revision v{latest.concept.version} · {latest.concept.title}</h4><pre>{latest.concept.description}</pre>
    <p>아래 버튼을 누르면 현재 입력을 최신 제목과 설명으로 바꿉니다.</p>
    <button type="button" disabled={busy} onClick={()=>{setValue(latest);edit(latest)}}>최신 개념 버전으로 다시 시작</button>
   </div>}</>}
  </form>:mode==="retire"?<section className="retire-concept" aria-label="개인 개념 폐기 확인">
   <h4>이 개인 개념을 폐기할까요?</h4><p>{value.concept.title} · Revision v{value.concept.version}</p>
   <p>활성 개인 개념 목록에서 제외합니다. 수정 이력과 기존 노트 연결은 유지되며 연결 해제는 별도로 선택할 수 있습니다. 현재 복원 기능은 없습니다.</p>
   <label className="concept-retire-ack"><input type="checkbox" checked={acknowledged} disabled={busy||conflict} onChange={e=>setAcknowledged(e.target.checked)}/>개념 폐기에 동의합니다</label>
   <div className="actions"><button disabled={busy} onClick={leaveEdit}>개념 폐기 취소</button><button disabled={busy||conflict||!acknowledged} onClick={()=>void retire()}>개념 폐기 확정</button></div>
   {conflict&&<><button disabled={busy} onClick={()=>void inspectLatest()}>최신 개념 내용 확인</button>{latest&&<div className="concept-edit-conflict">
    <h4>최신 Revision v{latest.concept.version} · {latest.concept.title}</h4><pre>{latest.concept.description}</pre>
    <button disabled={busy} onClick={()=>confirmRetirement(latest)}>최신 개념 버전으로 폐기 다시 확인</button>
   </div>}</>}
  </section>:<>
   <button disabled={busy} onClick={()=>{setMode("read");setNotice("");setRevisions([]);setRevision(null);setBefore(null)}}>현재 개념으로 돌아가기</button>
   <h4>개념 수정 이력</h4>{revisions.length>0&&<ConceptRevisionComparison key={conceptId+revisions[0].revisionId} revisions={revisions} busy={busy}/>}<div className="personal-concept-revisions">{revisions.map(r=><button key={r.revisionId} aria-pressed={revision?.revisionId===r.revisionId} disabled={busy} onClick={()=>setRevision(r)}>v{r.version} · {r.title}</button>)}</div>
   {before&&<button disabled={busy} onClick={()=>void history(true)}>이전 개념 수정 더 보기</button>}
   {revision&&<><p className="muted">v{revision.version} · {new Date(revision.occurredAt).toLocaleString()} · {revision.state}</p><h4>{revision.title}</h4><pre>{revision.description}</pre></>}
   {!busy&&!revisions.length&&!notice&&<p className="muted">불러온 개념 수정 이력이 없습니다.</p>}
   <button disabled={busy} onClick={()=>void history()}>개념 이력 새로고침</button>
  </>}
 </section>;
}
