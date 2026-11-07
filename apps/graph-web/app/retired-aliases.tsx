"use client";
import {useEffect,useRef,useState} from "react";
type Alias={aliasId:string;conceptId:string;spaceId:string;label:string;scope:"SPACE";visibility:"PRIVATE";state:"RETIRED";version:number;createdAt:string;updatedAt:string};
type Page={items:Alias[];nextAfter:string|null};
type Revision={revisionId:string;aliasId:string;label:string;state:"ACTIVE"|"RETIRED";version:number;actorId:string;occurredAt:string};
type Lane="list"|"history";
type Operation={epoch:number;scope:string;controller:AbortController;timer:ReturnType<typeof setTimeout>};
const id=(v:unknown):v is string=>typeof v==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);
const version=(v:unknown):v is number=>typeof v==="number"&&Number.isSafeInteger(v)&&v>0;
const date=(v:unknown)=>typeof v==="string"&&Number.isFinite(Date.parse(v));
const javaWhitespace=/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g;
function strip(value:string){return value.replace(javaWhitespace,"")}
function validText(value:unknown,max:number):value is string{return typeof value==="string"&&Array.from(value).length<=max&&!/[\u0000\ud800-\udfff]/u.test(value)}
function snapshots(row:{label:string}){return validText(row.label,200)&&!!strip(row.label)&&row.label===strip(row.label)}
function samePins(a:{label:string},b:{label:string}){return a.label===b.label}
function readPage(raw:unknown,space:string,concept:string,after:string|null):Page{
 const p=raw as Page;
 if(!p||!Array.isArray(p.items)||p.items.length>20||p.items.some((r,i)=>!r||!id(r.aliasId)||r.spaceId!==space||r.conceptId!==concept||r.state!=="RETIRED"||r.visibility!=="PRIVATE"||r.scope!=="SPACE"||!snapshots(r)||!version(r.version)||r.version<2||!date(r.createdAt)||!date(r.updatedAt)||(after!==null&&r.aliasId<=after)||(i>0&&r.aliasId<=p.items[i-1].aliasId)))throw new Error();
 if(p.nextAfter!==null&&(!id(p.nextAfter)||p.items.length!==20||p.nextAfter!==p.items.at(-1)?.aliasId))throw new Error();return p;
}
function readHistory(raw:unknown,alias:Alias,before:number|null):Revision[]{
 const rows=raw as Revision[];
 if(!Array.isArray(rows)||rows.length>5||rows.some((r,i)=>!r||!id(r.revisionId)||r.aliasId!==alias.aliasId||!version(r.version)||r.version>alias.version||!["ACTIVE","RETIRED"].includes(r.state)||!snapshots(r)||!id(r.actorId)||!date(r.occurredAt)||(before!==null&&r.version>=before)||(i>0&&r.version>=rows[i-1].version))||new Set(rows.map(r=>r.revisionId)).size!==rows.length)throw new Error();
 if(before===null){const head=rows[0];if(!head||head.version!==alias.version||head.state!=="RETIRED"||!samePins(head,alias))throw new Error()}
 return rows;
}
function Pinned({row}:{row:Revision}){return <><h5>당시 별칭</h5><pre>{row.label}</pre><p className="muted">개인 공간 범위(SPACE)의 별칭입니다.</p></>}

export default function RetiredAliases({spaceId,conceptId,onDenied}:{spaceId:string;conceptId:string;onDenied:()=>void}){
 const path=`/api/spaces/${spaceId}/concepts/${conceptId}/aliases`;
 const [open,setOpen]=useState(false),[page,setPage]=useState<Page|null>(null),[listBusy,setListBusy]=useState(false),[listNotice,setListNotice]=useState("");
 const [selected,setSelected]=useState<Alias|null>(null),[rows,setRows]=useState<Revision[]>([]),[revision,setRevision]=useState<Revision|null>(null),[before,setBefore]=useState<number|null>(null),[historyBusy,setHistoryBusy]=useState(false),[historyNotice,setHistoryNotice]=useState("");
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
  try{const query=new URLSearchParams({state:"RETIRED",limit:"20"});if(after)query.set("after",after);const response=await fetchRead(`${path}?${query}`,op);if(!active("list",op)||denied(response))return;if(!response.ok)throw new Error();const result=readPage(await response.json(),spaceId,conceptId,after);if(!active("list",op))return;setPage(previous=>({items:[...(append?previous?.items??[]:[]),...result.items],nextAfter:result.nextAfter}))}
  catch{if(relevant("list",op)){setPage(null);closeDetail();setListNotice("폐기한 별칭을 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish("list",op)}
 }
 async function history(alias:Alias,append=false){
  const cursor=append?before:null;if(append&&!cursor)return;const op=start("history");setHistoryNotice("");if(!append){setRows([]);setRevision(null);setBefore(null)}
  try{const response=await fetchRead(`${path}/${alias.aliasId}/revisions?limit=5${cursor?`&before=${cursor}`:""}`,op);if(!active("history",op)||selectedId.current!==alias.aliasId||denied(response))return;if(!response.ok)throw new Error();const result=readHistory(await response.json(),alias,cursor);if(!active("history",op)||selectedId.current!==alias.aliasId)return;if(append&&result.some(r=>rows.some(prior=>prior.revisionId===r.revisionId||prior.version===r.version)))throw new Error();setRows(previous=>append?[...previous,...result]:result);if(!append)setRevision(result[0]);setBefore(result.length===5?result.at(-1)!.version:null)}
  catch{if(relevant("history",op)){clearHistory();setHistoryNotice("폐기한 별칭의 이력을 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish("history",op)}
 }
 function select(alias:Alias){closeDetail();selectedId.current=alias.aliasId;setSelected(alias);void history(alias)}
 return <section className="retired-aliases" aria-label="폐기한 별칭 보관함"><h4>폐기한 별칭</h4><p className="muted">직접 폐기한 별칭과 남아 있는 수정 이력을 다시 확인합니다.</p>
  {!open?<button type="button" onClick={()=>{opened.current=true;setOpen(true);void load()}}>폐기한 별칭 열기</button>:<><div className="actions"><button type="button" onClick={close}>폐기한 별칭 닫기</button><button type="button" disabled={listBusy} onClick={()=>void load()}>폐기한 별칭 새로고침</button></div>
   {listBusy&&<p className="muted">폐기한 별칭 확인 중…</p>}{listNotice&&<p role="status">{listNotice}</p>}
   {page&&<><p className="muted">불러온 폐기 별칭 {page.items.length}개 · 생성 순서</p>{page.items.length===0&&<p>폐기한 별칭이 없습니다.</p>}<div className="retired-alias-results">{page.items.map(row=><button type="button" key={row.aliasId} aria-label={`폐기 별칭 이력 열기: ${row.aliasId}`} onClick={()=>select(row)}>{row.label}<small>개인 공간 범위 · SPACE</small><small>{row.aliasId} · v{row.version}</small></button>)}</div>{page.nextAfter&&<button type="button" disabled={listBusy} onClick={()=>void load(true)}>폐기한 별칭 더 보기</button>}</>}
   {selected&&<section aria-label="폐기한 별칭 이력"><button type="button" onClick={closeDetail}>폐기 별칭 이력 닫기</button><p className="muted">별칭 ID: {selected.aliasId}</p>{historyBusy&&<p>별칭 이력 확인 중…</p>}{historyNotice&&<p role="status">{historyNotice}</p>}<div className="retired-alias-revisions">{rows.map(row=><button type="button" key={row.revisionId} aria-pressed={revision?.revisionId===row.revisionId} disabled={historyBusy} onClick={()=>setRevision(row)}>v{row.version} · {row.state}</button>)}</div>{before&&<button type="button" disabled={historyBusy} onClick={()=>void history(selected,true)}>이전 폐기 별칭 이력 더 보기</button>}{revision&&<article aria-label="선택한 폐기 별칭 버전"><h5>별칭 v{revision.version} · {revision.state}</h5><p>수정 ID: {revision.revisionId}<br/>수정자 ID: {revision.actorId}<br/>수정 시각: {revision.occurredAt}</p><Pinned row={revision}/></article>}<button type="button" disabled={historyBusy} onClick={()=>void history(selected)}>폐기 별칭 이력 새로고침</button></section>}
  </>}
 </section>;
}
