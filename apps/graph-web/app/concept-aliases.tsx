"use client";
import RetiredAliases from "./retired-aliases";
import {useEffect,useRef,useState} from "react";

type Alias={aliasId:string;conceptId:string;spaceId:string;visibility:"PRIVATE";scope:"SPACE";state:"ACTIVE"|"RETIRED";version:number;label:string;createdAt:string;updatedAt:string};
type Page={items:Alias[];nextAfter:string|null};
type Revision={revisionId:string;aliasId:string;version:number;state:"ACTIVE"|"RETIRED";label:string;actorId:string;occurredAt:string};
type Current={alias:Alias;etag:string};
type Operation={epoch:number;scope:string;controller:AbortController;timer:ReturnType<typeof setTimeout>};
type Kind="catalog"|"create"|"detail";

function validId(value:unknown):value is string{return typeof value==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)}
function validVersion(value:unknown):value is number{return typeof value==="number"&&Number.isSafeInteger(value)&&value>0}
function validDate(value:unknown){return typeof value==="string"&&Number.isFinite(Date.parse(value))}
function validLabel(value:unknown):value is string{return typeof value==="string"&&!!value.trim()&&Array.from(value.trim()).length<=200&&!value.includes("\0")}
function readAlias(value:unknown,spaceId:string,conceptId:string):Alias{
 const row=value as Alias;
 if(!row||!validId(row.aliasId)||row.spaceId!==spaceId||row.conceptId!==conceptId||row.visibility!=="PRIVATE"||row.scope!=="SPACE"||!["ACTIVE","RETIRED"].includes(row.state)||!validVersion(row.version)||!validLabel(row.label)||!validDate(row.createdAt)||!validDate(row.updatedAt))throw new Error();
 return row;
}
async function readCurrent(response:Response,spaceId:string,conceptId:string,aliasId?:string):Promise<Current>{
 const alias=readAlias(await response.json(),spaceId,conceptId),etag=response.headers.get("ETag");
 if(alias.state!=="ACTIVE"||(aliasId&&alias.aliasId!==aliasId)||!etag||!/^"a[1-9][0-9]{0,18}"$/.test(etag)||etag!==`"a${alias.version}"`)throw new Error();
 return {alias,etag};
}
function readPage(value:unknown,spaceId:string,conceptId:string,cursor:string|null):Page{
 const data=value as Page;
 if(!data||!Array.isArray(data.items)||data.items.length>20)throw new Error();
 const items=data.items.map(row=>readAlias(row,spaceId,conceptId));
 if(items.some((row,index)=>row.state!=="ACTIVE"||(cursor!==null&&row.aliasId<=cursor)||(index>0&&row.aliasId<=items[index-1].aliasId)))throw new Error();
 if(data.nextAfter!==null&&(!validId(data.nextAfter)||items.length!==20||data.nextAfter!==items[items.length-1]?.aliasId||(cursor!==null&&data.nextAfter<=cursor)))throw new Error();
 return {items,nextAfter:data.nextAfter};
}
async function api(path:string,signal:AbortSignal,options:RequestInit={}){
 return fetch(path,{...options,credentials:"same-origin",cache:"no-store",redirect:"error",signal,headers:{...(options.body?{"Content-Type":"application/json"}:{}),...options.headers}});
}

export default function ConceptAliases({spaceId,conceptId,parentRetired=false,onDenied}:{spaceId:string;conceptId:string;parentRetired?:boolean;onDenied:()=>void}){
 const path=`/api/spaces/${spaceId}/concepts/${conceptId}/aliases`,scope=path;
 const [page,setPage]=useState<Page|null>(null),[catalogBusy,setCatalogBusy]=useState(false),[catalogNotice,setCatalogNotice]=useState("");
 const [newLabel,setNewLabel]=useState(""),[createBusy,setCreateBusy]=useState(false),[createNotice,setCreateNotice]=useState("");
 const [selected,setSelected]=useState<string|null>(null),[value,setValue]=useState<Current|null>(null),[mode,setMode]=useState<"read"|"edit"|"retire"|"history">("read");
 const [label,setLabel]=useState(""),[base,setBase]=useState(""),[busy,setBusy]=useState(false),[notice,setNotice]=useState(""),[conflict,setConflict]=useState(false),[latest,setLatest]=useState<Current|null>(null),[acknowledged,setAcknowledged]=useState(false);
 const [revisions,setRevisions]=useState<Revision[]>([]),[revision,setRevision]=useState<Revision|null>(null),[before,setBefore]=useState<number|null>(null);
 const alive=useRef(true),currentScope=useRef(scope),retired=useRef(parentRetired),selectedId=useRef<string|null>(selected),epochs=useRef<Record<Kind,number>>({catalog:0,create:0,detail:0}),requests=useRef<Record<Kind,Operation|null>>({catalog:null,create:null,detail:null});
 currentScope.current=scope;retired.current=parentRetired;selectedId.current=selected;
 function stop(kind:Kind){const operation=requests.current[kind];epochs.current[kind]++;if(operation){clearTimeout(operation.timer);operation.controller.abort()}requests.current[kind]=null}
 function start(kind:Kind):Operation{stop(kind);const controller=new AbortController(),operation={epoch:epochs.current[kind],scope,controller,timer:setTimeout(()=>controller.abort(),8000)};requests.current[kind]=operation;if(kind==="catalog")setCatalogBusy(true);else if(kind==="create")setCreateBusy(true);else setBusy(true);return operation}
 function active(operation:Operation,kind:Kind){return alive.current&&currentScope.current===operation.scope&&operation.epoch===epochs.current[kind]&&!operation.controller.signal.aborted}
 function finish(operation:Operation,kind:Kind){clearTimeout(operation.timer);if(alive.current&&currentScope.current===operation.scope&&operation.epoch===epochs.current[kind]){requests.current[kind]=null;if(kind==="catalog")setCatalogBusy(false);else if(kind==="create")setCreateBusy(false);else setBusy(false)}}
 function clearDetail(){setValue(null);setMode("read");setLabel("");setBase("");setNotice("");setConflict(false);setLatest(null);setAcknowledged(false);setRevisions([]);setRevision(null);setBefore(null)}
 function denied(response:Response){
  if(![401,403,404].includes(response.status))return false;
  for(const kind of ["catalog","create","detail"] as Kind[])stop(kind);
  setPage(null);setNewLabel("");setSelected(null);clearDetail();setCreateNotice("");setCatalogNotice("");setCatalogBusy(false);setCreateBusy(false);setBusy(false);onDenied();return true;
 }
 async function load(append=false){
  if(retired.current)return;const cursor=append?page?.nextAfter??null:null;if(append&&!cursor)return;
  const operation=start("catalog");setCatalogNotice("");if(!append)setPage(null);
  try{
   const query=new URLSearchParams({limit:"20"});if(cursor)query.set("after",cursor);
   const response=await api(`${path}?${query}`,operation.controller.signal);
   if(!active(operation,"catalog")||retired.current||denied(response))return;if(!response.ok)throw new Error();
   const data=readPage(await response.json(),spaceId,conceptId,cursor);if(!active(operation,"catalog")||retired.current)return;
   setPage(previous=>({nextAfter:data.nextAfter,items:[...new Map([...(append?previous?.items??[]:[]),...data.items].map(row=>[row.aliasId,row])).values()]}));
  }catch{if(alive.current&&currentScope.current===scope&&operation.epoch===epochs.current.catalog&&!retired.current){setPage(null);setCatalogNotice("개념 별칭을 불러오지 못했습니다. 다시 조회해 주세요.")}}
  finally{finish(operation,"catalog")}
 }
 useEffect(()=>{alive.current=true;setPage(null);setNewLabel("");setSelected(null);clearDetail();setCreateNotice("");setCatalogNotice("");if(!retired.current)void load();return()=>{alive.current=false;for(const kind of ["catalog","create","detail"] as Kind[])stop(kind)}},[path]);
 useEffect(()=>{if(parentRetired){for(const kind of ["catalog","create","detail"] as Kind[])stop(kind);setPage(null);setCatalogBusy(false);setCreateBusy(false);setBusy(false);setNewLabel("");setLabel("");setBase("");setLatest(null);setConflict(false);setAcknowledged(false);setMode("read");setNotice("")} },[parentRetired]);
 async function select(id:string){
  if(retired.current)return;stop("detail");clearDetail();selectedId.current=id;setSelected(id);const operation=start("detail");
  try{
   const response=await api(`${path}/${id}`,operation.controller.signal);if(!active(operation,"detail")||selectedId.current!==id||retired.current||denied(response))return;if(!response.ok)throw new Error();
   const data=await readCurrent(response,spaceId,conceptId,id);if(active(operation,"detail")&&selectedId.current===id&&!retired.current)setValue(data);
  }catch{if(alive.current&&currentScope.current===scope&&operation.epoch===epochs.current.detail)setNotice("별칭을 불러오지 못했습니다. 다시 선택해 주세요.")}
  finally{finish(operation,"detail")}
 }
 function close(){stop("detail");selectedId.current=null;setSelected(null);clearDetail();setBusy(false)}
 async function create(event:React.FormEvent){
  event.preventDefault();if(retired.current||requests.current.create)return;if(!validLabel(newLabel)){setCreateNotice("별칭은 1~200자로 입력해 주세요.");return}
  const operation=start("create");setCreateNotice("");
  try{
   const response=await api(path,operation.controller.signal,{method:"POST",body:JSON.stringify({label:newLabel})});if(!active(operation,"create")||retired.current||denied(response))return;
   if(response.status===409){setCreateNotice("이미 사용 중인 별칭이거나 활성 별칭 한도에 도달했습니다. 입력은 유지했습니다.");return}
   if(response.status===400||response.status===413){setCreateNotice("별칭 입력 형식을 확인해 주세요. 입력은 유지했습니다.");return}
   if(response.status!==201)throw new Error();await readCurrent(response,spaceId,conceptId);if(!active(operation,"create")||retired.current)return;
   setNewLabel("");setCreateNotice("개념 별칭을 추가했습니다.");void load();
  }catch{if(alive.current&&currentScope.current===scope&&operation.epoch===epochs.current.create)setCreateNotice("별칭 추가 결과를 확인하지 못했습니다. 입력은 유지했습니다. 다시 추가하기 전 목록을 확인해 주세요.")}
  finally{finish(operation,"create")}
 }
 function edit(data:Current){setValue(data);setMode("edit");setLabel(data.alias.label);setBase(data.etag);setConflict(false);setLatest(null);setAcknowledged(false);setNotice("")}
 function leave(){setMode("read");setLabel("");setBase("");setLatest(null);setConflict(false);setAcknowledged(false);setNotice("");setRevisions([]);setRevision(null);setBefore(null)}
 async function save(event:React.FormEvent){
  event.preventDefault();if(retired.current||requests.current.detail||conflict||!value||mode!=="edit"||!base)return;
  if(!validLabel(label)){setNotice("별칭은 1~200자로 입력해 주세요.");return}const id=value.alias.aliasId,operation=start("detail");setNotice("");
  try{
   const response=await api(`${path}/${id}`,operation.controller.signal,{method:"PUT",headers:{"If-Match":base},body:JSON.stringify({label})});if(!active(operation,"detail")||selectedId.current!==id||retired.current||denied(response))return;
   if(response.status===412){setConflict(true);setLatest(null);setNotice("다른 별칭 수정이 있습니다. 입력은 유지했습니다. 최신 별칭을 확인해 주세요.");return}
   if(response.status===409){setNotice("이 개념에서 이미 사용 중인 별칭입니다. 입력은 유지했습니다.");return}
   if(response.status===400||response.status===413){setNotice("별칭 입력 형식을 확인해 주세요. 입력은 유지했습니다.");return}if(!response.ok)throw new Error();
   const data=await readCurrent(response,spaceId,conceptId,id);if(!active(operation,"detail")||selectedId.current!==id||retired.current)return;
   setValue(data);leave();setNotice("개념 별칭을 수정했습니다.");void load();
  }catch{if(alive.current&&currentScope.current===scope&&operation.epoch===epochs.current.detail)setNotice("별칭 저장 결과를 확인하지 못했습니다. 입력은 유지했습니다. 다시 저장할 때 별칭 버전을 확인합니다.")}
  finally{finish(operation,"detail")}
 }
 function confirmRetirement(data:Current){setValue(data);setMode("retire");setBase(data.etag);setLatest(null);setConflict(false);setAcknowledged(false);setNotice("")}
 async function retireAlias(){
  if(retired.current||requests.current.detail||conflict||!acknowledged||!base||mode!=="retire"||!value)return;
  const previous=value,id=previous.alias.aliasId,operation=start("detail");setNotice("");
  try{
   const response=await api(`${path}/${id}`,operation.controller.signal,{method:"DELETE",headers:{"If-Match":base}});if(!active(operation,"detail")||selectedId.current!==id||retired.current||denied(response))return;
   if(response.status===412){setConflict(true);setLatest(null);setAcknowledged(false);setNotice("다른 별칭 수정이 있습니다. 최신 별칭을 확인한 뒤 폐기를 다시 확인해 주세요.");return}
   const version=previous.alias.version+1,etag=response.headers.get("ETag");if(response.status!==204||!validVersion(version)||etag!==`"a${version}"`)throw new Error();
   setValue({alias:{...previous.alias,state:"RETIRED",version},etag});leave();setNotice("개념 별칭을 폐기했습니다. 수정 이력은 유지됩니다.");void load();
  }catch{if(alive.current&&currentScope.current===scope&&operation.epoch===epochs.current.detail){setAcknowledged(false);setNotice("별칭 폐기 결과를 확인하지 못했습니다. 목록과 이력을 확인한 뒤 다시 시도해 주세요.")}}
  finally{finish(operation,"detail")}
 }
 async function inspectLatest(){
  if(retired.current||requests.current.detail||!selected)return;const id=selected,operation=start("detail");setLatest(null);setNotice("");
  try{const response=await api(`${path}/${id}`,operation.controller.signal);if(!active(operation,"detail")||selectedId.current!==id||retired.current||denied(response))return;if(!response.ok)throw new Error();const data=await readCurrent(response,spaceId,conceptId,id);if(active(operation,"detail")&&selectedId.current===id&&!retired.current)setLatest(data)}
  catch{if(alive.current&&currentScope.current===scope&&operation.epoch===epochs.current.detail)setNotice("최신 별칭을 불러오지 못했습니다. 입력은 유지했습니다.")}finally{finish(operation,"detail")}
 }
 async function history(append=false){
  if(requests.current.detail||!selected)return;const id=selected,cursor=append?before:null;if(append&&!cursor)return;const operation=start("detail");setMode("history");setNotice("");if(!append){setRevisions([]);setRevision(null);setBefore(null)}
  try{
   const response=await api(`${path}/${id}/revisions?limit=5${cursor?`&before=${cursor}`:""}`,operation.controller.signal);if(!active(operation,"detail")||selectedId.current!==id||denied(response))return;if(!response.ok)throw new Error();const rows:Revision[]=await response.json();if(!active(operation,"detail")||selectedId.current!==id)return;
   if(!Array.isArray(rows)||rows.length>5||rows.some((row,index)=>!row||!validId(row.revisionId)||row.aliasId!==id||!validVersion(row.version)||!["ACTIVE","RETIRED"].includes(row.state)||!validLabel(row.label)||!validId(row.actorId)||!validDate(row.occurredAt)||(cursor!==null&&row.version>=cursor)||(index>0&&row.version>=rows[index-1].version)))throw new Error();
   setRevisions(previous=>append?[...previous,...rows.filter(row=>!previous.some(prior=>prior.revisionId===row.revisionId))]:rows);if(!append)setRevision(rows[0]??null);setBefore(rows.length===5?rows[rows.length-1].version:null);
  }catch{if(alive.current&&currentScope.current===scope&&operation.epoch===epochs.current.detail){setRevisions([]);setRevision(null);setBefore(null);setNotice("별칭 수정 이력을 불러오지 못했습니다. 다시 시도해 주세요.")}}
  finally{finish(operation,"detail")}
 }
 return <section className="concept-aliases" aria-label="개념 별칭과 수정 이력"><h3>개념 별칭</h3>
  <p className="muted">이 개인 공간에서 사용할 다른 이름을 저장합니다. 개념 제목과 노트 연결은 별칭 수정으로 바뀌지 않습니다.</p>
  {parentRetired?<p className="muted">개념이 폐기되어 별칭 수정과 활성 조회를 종료했습니다. 선택한 별칭의 수정 이력은 확인할 수 있습니다.</p>:<>
   <button type="button" disabled={catalogBusy} onClick={()=>void load()}>개념 별칭 새로고침</button>
   {catalogBusy&&<p className="muted">개념 별칭 확인 중…</p>}{catalogNotice&&<p role="status">{catalogNotice}</p>}
   {page&&<><p className="muted">불러온 활성 별칭 {page.items.length}개 · 생성 순서</p>{!page.items.length&&<p>저장된 활성 별칭이 없습니다.</p>}
    <div className="concept-alias-results">{page.items.map(row=><button type="button" key={row.aliasId} aria-label={`별칭 열기: ${row.label}`} onClick={()=>void select(row.aliasId)}>{row.label}</button>)}</div>
    {page.nextAfter&&<button type="button" disabled={catalogBusy} onClick={()=>void load(true)}>개념 별칭 더 보기</button>}
   </>}
   <form className="concept-alias-create" onSubmit={create}><label>새 별칭<input value={newLabel} disabled={createBusy} onChange={event=>setNewLabel(event.target.value)}/></label><button type="submit" disabled={createBusy||!newLabel.trim()}>별칭 추가</button></form>
   {createNotice&&<p role="status">{createNotice}</p>}
  </>}
  {selected&&<section className="concept-alias-detail" aria-label="선택한 개념 별칭"><button type="button" onClick={close}>별칭 상세 닫기</button>{notice&&<p role="status">{notice}</p>}
   {!value?<p className="muted">{busy?"별칭 불러오는 중…":"별칭을 다시 선택해 주세요."}</p>:mode==="read"?<><h4>{value.alias.label}</h4><p className="muted">별칭 Revision v{value.alias.version} · {value.alias.state}</p><div className="actions">
    {!parentRetired&&value.alias.state==="ACTIVE"&&<button type="button" disabled={busy} onClick={()=>edit(value)}>별칭 편집</button>}
    <button type="button" disabled={busy} onClick={()=>void history()}>별칭 수정 이력</button>
    {!parentRetired&&value.alias.state==="ACTIVE"&&<button type="button" disabled={busy} onClick={()=>confirmRetirement(value)}>별칭 폐기</button>}
   </div></>:mode==="edit"?<form onSubmit={save}><label>편집할 별칭<input value={label} disabled={busy} onChange={event=>setLabel(event.target.value)}/></label>
    <p className="muted">저장 전에는 반영되지 않습니다. 별칭·개념·공간을 바꾸거나 상세를 닫으면 저장하지 않은 별칭 입력은 사라집니다.</p>
    <div className="actions"><button type="button" disabled={busy} onClick={leave}>별칭 편집 취소</button><button type="submit" disabled={busy||conflict||!label.trim()}>별칭 수정 저장</button></div>
    {conflict&&<><button type="button" disabled={busy} onClick={()=>void inspectLatest()}>최신 별칭 확인</button>{latest&&<div className="concept-alias-conflict"><h4>최신 별칭 v{latest.alias.version}</h4><p>{latest.alias.label}</p><p>아래 버튼을 누르면 현재 입력을 최신 별칭으로 바꿉니다.</p><button type="button" disabled={busy} onClick={()=>edit(latest)}>최신 별칭 버전으로 다시 시작</button></div>}</>}
   </form>:mode==="retire"?<section aria-label="개념 별칭 폐기 확인"><h4>이 별칭을 폐기할까요?</h4><p>{value.alias.label} · 별칭 Revision v{value.alias.version}</p><p>활성 별칭 목록에서 제외합니다. 수정 이력은 유지됩니다.</p><label className="concept-alias-retire-ack"><input type="checkbox" checked={acknowledged} disabled={busy||conflict} onChange={event=>setAcknowledged(event.target.checked)}/>별칭 폐기에 동의합니다</label><div className="actions"><button type="button" disabled={busy} onClick={leave}>별칭 폐기 취소</button><button type="button" disabled={busy||conflict||!acknowledged} onClick={()=>void retireAlias()}>별칭 폐기 확정</button></div>
    {conflict&&<><button type="button" disabled={busy} onClick={()=>void inspectLatest()}>최신 별칭 확인</button>{latest&&<div className="concept-alias-conflict"><h4>최신 별칭 v{latest.alias.version}</h4><p>{latest.alias.label}</p><button type="button" disabled={busy} onClick={()=>confirmRetirement(latest)}>최신 별칭 버전으로 폐기 다시 확인</button></div>}</>}
   </section>:<><button type="button" disabled={busy} onClick={leave}>현재 별칭으로 돌아가기</button><h4>별칭 수정 이력</h4><div className="concept-alias-revisions">{revisions.map(row=><button type="button" key={row.revisionId} aria-pressed={revision?.revisionId===row.revisionId} disabled={busy} onClick={()=>setRevision(row)}>v{row.version} · {row.label}</button>)}</div>
    {before&&<button type="button" disabled={busy} onClick={()=>void history(true)}>이전 별칭 수정 더 보기</button>}
    {revision&&<><p className="muted">v{revision.version} · {revision.state} · {new Date(revision.occurredAt).toLocaleString()}</p><h4>{revision.label}</h4></>}{!busy&&!revisions.length&&!notice&&<p className="muted">불러온 별칭 수정 이력이 없습니다.</p>}<button type="button" disabled={busy} onClick={()=>void history()}>별칭 이력 새로고침</button>
   </>}
  </section>}
 <RetiredAliases key={`${spaceId}:${conceptId}`} spaceId={spaceId} conceptId={conceptId} onDenied={onDenied}/>
 </section>;
}
