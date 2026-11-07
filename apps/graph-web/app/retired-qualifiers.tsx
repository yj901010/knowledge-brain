"use client";
import {useEffect,useRef,useState} from "react";
import {type ClaimObject,validClaimObject,claimObjectText} from "./claim-object";
type ClaimPin={revisionId:string;version:number;subjectConceptId:string;predicateConceptId:string;object:ClaimObject};
type Value={type:"CONCEPT"|"STRING"|"NUMBER"|"DATE"|"BOOLEAN";value:string};
type Snapshot={claim:ClaimPin;predicateConceptId:string;value:Value};
type Qualifier=Snapshot&{qualifierId:string;spaceId:string;subjectConceptId:string;claimId:string;state:"RETIRED";visibility:"PRIVATE";version:number;createdAt:string;updatedAt:string};
type Page={items:Qualifier[];nextAfter:string|null};
type Revision=Snapshot&{revisionId:string;qualifierId:string;version:number;state:"ACTIVE"|"RETIRED";actorId:string;occurredAt:string};
type Lane="list"|"history";
type Operation={epoch:number;scope:string;controller:AbortController;timer:ReturnType<typeof setTimeout>};
const id=(v:unknown):v is string=>typeof v==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);
const version=(v:unknown):v is number=>typeof v==="number"&&Number.isSafeInteger(v)&&v>0;
const date=(v:unknown)=>typeof v==="string"&&Number.isFinite(Date.parse(v));
function validValue(v:Value){
 if(!v||Object.keys(v).length!==2||!validClaimObject(v.type==="CONCEPT"?{type:v.type,conceptId:v.value}:v))return false;
 if(v.type!=="NUMBER")return true;
 const unsigned=v.value.replace(/^-/,""),[integer,fraction=""]=unsigned.split(".");
 return v.value!=="-0"&&integer.length<=38&&fraction.length<=18&&(!fraction||!fraction.endsWith("0"))&&(integer+fraction).replace(/^0+/,"").length<=38;
}
function snapshots(row:Snapshot,subject:string){const c=row?.claim;return !!c&&id(c.revisionId)&&version(c.version)&&c.subjectConceptId===subject&&id(c.predicateConceptId)&&validClaimObject(c.object)&&id(row.predicateConceptId)&&validValue(row.value)}
function samePins(a:Snapshot,b:Snapshot){return a.claim.revisionId===b.claim.revisionId&&a.claim.version===b.claim.version&&a.claim.subjectConceptId===b.claim.subjectConceptId&&a.claim.predicateConceptId===b.claim.predicateConceptId&&a.claim.object.type===b.claim.object.type&&a.claim.object.value===b.claim.object.value&&a.claim.object.conceptId===b.claim.object.conceptId&&a.predicateConceptId===b.predicateConceptId&&a.value.type===b.value.type&&a.value.value===b.value.value}
function valueText(value:Value){return claimObjectText(value.type==="CONCEPT"?{type:value.type,conceptId:value.value}:value)}
function readPage(raw:unknown,space:string,subject:string,claim:string,after:string|null):Page{
 const p=raw as Page;
 if(!p||!Array.isArray(p.items)||p.items.length>20||p.items.some((r,i)=>!r||!id(r.qualifierId)||r.spaceId!==space||r.subjectConceptId!==subject||r.claimId!==claim||r.state!=="RETIRED"||r.visibility!=="PRIVATE"||!snapshots(r,subject)||!version(r.version)||r.version<2||!date(r.createdAt)||!date(r.updatedAt)||(after!==null&&r.qualifierId<=after)||(i>0&&r.qualifierId<=p.items[i-1].qualifierId)))throw new Error();
 if(p.nextAfter!==null&&(!id(p.nextAfter)||p.items.length!==20||p.nextAfter!==p.items.at(-1)?.qualifierId))throw new Error();return p;
}
function readHistory(raw:unknown,qualifier:Qualifier,before:number|null):Revision[]{
 const rows=raw as Revision[];
 if(!Array.isArray(rows)||rows.length>5||rows.some((r,i)=>!r||!id(r.revisionId)||r.qualifierId!==qualifier.qualifierId||!version(r.version)||r.version>qualifier.version||!["ACTIVE","RETIRED"].includes(r.state)||!snapshots(r,qualifier.subjectConceptId)||!id(r.actorId)||!date(r.occurredAt)||(before!==null&&r.version>=before)||(i>0&&r.version>=rows[i-1].version))||new Set(rows.map(r=>r.revisionId)).size!==rows.length)throw new Error();
 if(before===null){const head=rows[0];if(!head||head.version!==qualifier.version||head.state!=="RETIRED"||!samePins(head,qualifier))throw new Error()}
 return rows;
}
function Pinned({row}:{row:Revision}){const {claim}=row;return <><h5>폐기 조건의 값</h5><p>조건 서술 개념 ID: {row.predicateConceptId}</p><pre>{valueText(row.value)}</pre><h5>고정한 관계 v{claim.version}</h5><p>주제 개념 ID: {claim.subjectConceptId}<br/>서술 개념 ID: {claim.predicateConceptId}<br/>관계 Revision ID: {claim.revisionId}</p><h5>고정한 관계 대상</h5><pre>{claimObjectText(claim.object)}</pre></>}

export default function RetiredQualifiers({spaceId,conceptId,claimId,onDenied}:{spaceId:string;conceptId:string;claimId:string;onDenied:()=>void}){
 const path=`/api/spaces/${spaceId}/concepts/${conceptId}/claims/${claimId}/qualifiers`;
 const [open,setOpen]=useState(false),[page,setPage]=useState<Page|null>(null),[listBusy,setListBusy]=useState(false),[listNotice,setListNotice]=useState("");
 const [selected,setSelected]=useState<Qualifier|null>(null),[rows,setRows]=useState<Revision[]>([]),[revision,setRevision]=useState<Revision|null>(null),[before,setBefore]=useState<number|null>(null),[historyBusy,setHistoryBusy]=useState(false),[historyNotice,setHistoryNotice]=useState("");
 const alive=useRef(true),opened=useRef(false),scope=useRef(path),selectedId=useRef<string|null>(null),epochs=useRef({list:0,history:0}),requests=useRef<Record<Lane,Operation|null>>({list:null,history:null});scope.current=path;
 function stop(lane:Lane){epochs.current[lane]++;const op=requests.current[lane];if(op){clearTimeout(op.timer);op.controller.abort()}requests.current[lane]=null}
 function working(lane:Lane,value:boolean){if(lane==="list")setListBusy(value);else setHistoryBusy(value)}
 function start(lane:Lane){stop(lane);const controller=new AbortController(),op={epoch:epochs.current[lane],scope:path,controller,timer:setTimeout(()=>controller.abort(),8000)};requests.current[lane]=op;working(lane,true);return op}
 function relevant(lane:Lane,op:Operation){return alive.current&&opened.current&&scope.current===op.scope&&epochs.current[lane]===op.epoch}
 function active(lane:Lane,op:Operation){return relevant(lane,op)&&!op.controller.signal.aborted}
 function finish(lane:Lane,op:Operation){clearTimeout(op.timer);if(relevant(lane,op)){requests.current[lane]=null;working(lane,false)}}
 function clearHistory(){setRows([]);setRevision(null);setBefore(null);setHistoryNotice("")}
 function closeDetail(){stop("history");selectedId.current=null;setSelected(null);clearHistory();setHistoryBusy(false)}
 function close(){opened.current=false;stop("list");closeDetail();setOpen(false);setPage(null);setListBusy(false);setListNotice("")}
 function denied(response:Response){if(![401,403,404].includes(response.status))return false;close();onDenied();return true}
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;opened.current=false;stop("list");stop("history")}},[path]);
 async function fetchRead(url:string,op:Operation){return fetch(url,{credentials:"same-origin",cache:"no-store",redirect:"error",signal:op.controller.signal})}
 async function load(append=false){
  const after=append?page?.nextAfter??null:null;if(append&&!after)return;const op=start("list");setListNotice("");if(!append){setPage(null);closeDetail()}
  try{const query=new URLSearchParams({state:"RETIRED",limit:"20"});if(after)query.set("after",after);const response=await fetchRead(`${path}?${query}`,op);if(!active("list",op)||denied(response))return;if(!response.ok)throw new Error();const result=readPage(await response.json(),spaceId,conceptId,claimId,after);if(!active("list",op))return;setPage(previous=>({items:[...(append?previous?.items??[]:[]),...result.items],nextAfter:result.nextAfter}))}
  catch{if(relevant("list",op)){setPage(null);closeDetail();setListNotice("폐기한 관계 조건을 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish("list",op)}
 }
 async function history(qualifier:Qualifier,append=false){
  const cursor=append?before:null;if(append&&!cursor)return;const op=start("history");setHistoryNotice("");if(!append){setRows([]);setRevision(null);setBefore(null)}
  try{const response=await fetchRead(`${path}/${qualifier.qualifierId}/revisions?limit=5${cursor?`&before=${cursor}`:""}`,op);if(!active("history",op)||selectedId.current!==qualifier.qualifierId||denied(response))return;if(!response.ok)throw new Error();const result=readHistory(await response.json(),qualifier,cursor);if(!active("history",op)||selectedId.current!==qualifier.qualifierId)return;if(append&&result.some(r=>rows.some(prior=>prior.revisionId===r.revisionId||prior.version===r.version)))throw new Error();setRows(previous=>append?[...previous,...result]:result);if(!append)setRevision(result[0]);setBefore(result.length===5?result.at(-1)!.version:null)}
  catch{if(relevant("history",op)){clearHistory();setHistoryNotice("폐기한 관계 조건의 이력을 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish("history",op)}
 }
 function select(qualifier:Qualifier){closeDetail();selectedId.current=qualifier.qualifierId;setSelected(qualifier);void history(qualifier)}
 return <section className="retired-qualifiers" aria-label="폐기한 관계 조건 보관함"><h4>폐기한 관계 조건</h4><p className="muted">직접 폐기한 조건의 값과 당시 고정한 관계, 수정 이력을 다시 확인합니다.</p>
  {!open?<button type="button" onClick={()=>{opened.current=true;setOpen(true);void load()}}>폐기한 관계 조건 열기</button>:<><div className="actions"><button type="button" onClick={close}>폐기한 관계 조건 닫기</button><button type="button" disabled={listBusy} onClick={()=>void load()}>폐기한 관계 조건 새로고침</button></div>
   {listBusy&&<p className="muted">폐기한 관계 조건 확인 중…</p>}{listNotice&&<p role="status">{listNotice}</p>}
   {page&&<><p className="muted">불러온 폐기 조건 {page.items.length}개 · 생성 순서</p>{page.items.length===0&&<p>폐기한 관계 조건이 없습니다.</p>}<div className="retired-qualifier-results">{page.items.map(row=><button type="button" key={row.qualifierId} aria-label={`폐기 조건 이력 열기: ${row.qualifierId}`} onClick={()=>select(row)}>{valueText(row.value)}<small>조건 서술 개념 ID: {row.predicateConceptId}</small><small>{row.qualifierId} · v{row.version}</small></button>)}</div>{page.nextAfter&&<button type="button" disabled={listBusy} onClick={()=>void load(true)}>폐기한 관계 조건 더 보기</button>}</>}
   {selected&&<section aria-label="폐기한 관계 조건 이력"><button type="button" onClick={closeDetail}>폐기 조건 이력 닫기</button><p className="muted">조건 ID: {selected.qualifierId}</p>{historyBusy&&<p>조건 이력 확인 중…</p>}{historyNotice&&<p role="status">{historyNotice}</p>}<div className="retired-qualifier-revisions">{rows.map(row=><button type="button" key={row.revisionId} aria-pressed={revision?.revisionId===row.revisionId} disabled={historyBusy} onClick={()=>setRevision(row)}>v{row.version} · {row.state}</button>)}</div>{before&&<button type="button" disabled={historyBusy} onClick={()=>void history(selected,true)}>이전 폐기 조건 이력 더 보기</button>}{revision&&<article aria-label="선택한 폐기 조건 버전"><h5>조건 v{revision.version} · {revision.state}</h5><p>수정 ID: {revision.revisionId}<br/>수정자 ID: {revision.actorId}<br/>수정 시각: {revision.occurredAt}</p><Pinned row={revision}/></article>}<button type="button" disabled={historyBusy} onClick={()=>void history(selected)}>폐기 조건 이력 새로고침</button></section>}
  </>}
 </section>;
}
