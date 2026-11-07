"use client";
import {ConceptRevisionComparison,type ConceptRevision as Revision} from "./knowledge-revision-comparison";
import RetiredMappings from "./retired-mappings";
import RetiredAliases from "./retired-aliases";
import {useEffect,useRef,useState} from "react";
import RetiredClaims from "./retired-claims";

type Summary={conceptId:string;title:string;version:number;updatedAt:string};
type Page={items:Summary[];nextAfter:string|null};
type Lane="list"|"history";
type Operation={epoch:number;scope:string;controller:AbortController;timer:ReturnType<typeof setTimeout>};
const id=(v:unknown):v is string=>typeof v==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);
const version=(v:unknown):v is number=>typeof v==="number"&&Number.isSafeInteger(v)&&v>0;
const date=(v:unknown)=>typeof v==="string"&&Number.isFinite(Date.parse(v));
const title=(v:unknown):v is string=>typeof v==="string"&&v.trim().length>0&&[...v].length<=200&&!v.includes("\0");
const description=(v:unknown):v is string=>typeof v==="string"&&!v.includes("\0")&&new TextEncoder().encode(v).length<=32768;
function readPage(raw:unknown,after:string|null):Page{
 const p=raw as Page;
 if(!p||!Array.isArray(p.items)||p.items.length>20||p.items.some((r,i)=>!r||!id(r.conceptId)||!title(r.title)||!version(r.version)||r.version<2||!date(r.updatedAt)||(after!==null&&r.conceptId<=after)||(i>0&&r.conceptId<=p.items[i-1].conceptId)))throw new Error();
 if(p.nextAfter!==null&&(!id(p.nextAfter)||p.items.length!==20||p.nextAfter!==p.items.at(-1)?.conceptId))throw new Error();return p;
}
function readHistory(raw:unknown,concept:Summary,before:number|null):Revision[]{
 const rows=raw as Revision[];
 if(!Array.isArray(rows)||rows.length>5||rows.some((r,i)=>!r||!id(r.revisionId)||r.conceptId!==concept.conceptId||!version(r.version)||r.version>concept.version||!["ACTIVE","RETIRED"].includes(r.state)||!title(r.title)||!description(r.description)||!id(r.actorId)||!date(r.occurredAt)||(before!==null&&r.version>=before)||(i>0&&r.version>=rows[i-1].version))||new Set(rows.map(r=>r.revisionId)).size!==rows.length)throw new Error();
 if(before===null){const head=rows[0];if(!head||head.version!==concept.version||head.state!=="RETIRED"||head.title!==concept.title)throw new Error()}
 return rows;
}

export default function RetiredConcepts({spaceId,onDenied}:{spaceId:string;onDenied:()=>void}){
 const path=`/api/spaces/${spaceId}/concepts`;
 const [open,setOpen]=useState(false),[page,setPage]=useState<Page|null>(null),[listBusy,setListBusy]=useState(false),[listNotice,setListNotice]=useState("");
 const [selected,setSelected]=useState<Summary|null>(null),[rows,setRows]=useState<Revision[]>([]),[revision,setRevision]=useState<Revision|null>(null),[before,setBefore]=useState<number|null>(null),[historyBusy,setHistoryBusy]=useState(false),[historyNotice,setHistoryNotice]=useState("");
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
  try{const query=new URLSearchParams({state:"RETIRED",limit:"20"});if(after)query.set("after",after);const response=await fetchRead(`${path}?${query}`,op);if(!active("list",op)||denied(response))return;if(!response.ok)throw new Error();const result=readPage(await response.json(),after);if(!active("list",op))return;setPage(previous=>({items:[...(append?previous?.items??[]:[]),...result.items],nextAfter:result.nextAfter}))}
  catch{if(relevant("list",op)){setPage(null);closeDetail();setListNotice("폐기한 개념을 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish("list",op)}
 }
 async function history(concept:Summary,append=false){
  const cursor=append?before:null;if(append&&!cursor)return;const op=start("history");setHistoryNotice("");if(!append){setRows([]);setRevision(null);setBefore(null)}
  try{const response=await fetchRead(`${path}/${concept.conceptId}/revisions?limit=5${cursor?`&before=${cursor}`:""}`,op);if(!active("history",op)||selectedId.current!==concept.conceptId||denied(response))return;if(!response.ok)throw new Error();const result=readHistory(await response.json(),concept,cursor);if(!active("history",op)||selectedId.current!==concept.conceptId)return;if(append&&result.some(r=>rows.some(prior=>prior.revisionId===r.revisionId||prior.version===r.version)))throw new Error();setRows(previous=>append?[...previous,...result]:result);if(!append)setRevision(result[0]);setBefore(result.length===5?result.at(-1)!.version:null)}
  catch{if(relevant("history",op)){clearHistory();setHistoryNotice("폐기한 개념의 이력을 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish("history",op)}
 }
 function select(concept:Summary){closeDetail();selectedId.current=concept.conceptId;setSelected(concept);void history(concept)}
 return <section className="retired-concepts" aria-label="폐기한 개념 보관함"><h4>폐기한 개념</h4><p className="muted">폐기한 개인 개념과 남아 있는 수정 이력을 다시 확인합니다.</p>
  {!open?<button type="button" onClick={()=>{opened.current=true;setOpen(true);void load()}}>폐기한 개념 열기</button>:<><div className="actions"><button type="button" onClick={close}>폐기한 개념 닫기</button><button type="button" disabled={listBusy} onClick={()=>void load()}>폐기한 개념 새로고침</button></div>
   {listBusy&&<p className="muted">폐기한 개념 확인 중…</p>}{listNotice&&<p role="status">{listNotice}</p>}
   {page&&<><p className="muted">불러온 폐기 개념 {page.items.length}개 · 생성 순서</p>{page.items.length===0&&<p>폐기한 개념이 없습니다.</p>}<div className="retired-concept-results">{page.items.map(row=><button type="button" key={row.conceptId} aria-label={`폐기 개념 이력 열기: ${row.title}`} onClick={()=>select(row)}>{row.title}<small>{row.conceptId} · v{row.version}</small></button>)}</div>{page.nextAfter&&<button type="button" disabled={listBusy} onClick={()=>void load(true)}>폐기한 개념 더 보기</button>}</>}
   {selected&&<section aria-label="폐기한 개념 이력"><button type="button" onClick={closeDetail}>폐기 개념 이력 닫기</button><p className="muted">개념 ID: {selected.conceptId}</p>{historyBusy&&<p>개념 이력 확인 중…</p>}{historyNotice&&<p role="status">{historyNotice}</p>}{rows.length>0&&<ConceptRevisionComparison key={selected.conceptId+rows[0].revisionId} revisions={rows} busy={historyBusy}/>}<div className="retired-concept-revisions">{rows.map(row=><button type="button" key={row.revisionId} aria-pressed={revision?.revisionId===row.revisionId} disabled={historyBusy} onClick={()=>setRevision(row)}>v{row.version} · {row.state}</button>)}</div>{before&&<button type="button" disabled={historyBusy} onClick={()=>void history(selected,true)}>이전 폐기 개념 이력 더 보기</button>}{revision&&<article aria-label="선택한 폐기 개념 버전"><h5>{revision.title} · v{revision.version} · {revision.state}</h5><p>수정 ID: {revision.revisionId}<br/>수정자 ID: {revision.actorId}<br/>수정 시각: {revision.occurredAt}</p><pre>{revision.description}</pre></article>}<button type="button" disabled={historyBusy} onClick={()=>void history(selected)}>폐기 개념 이력 새로고침</button>
    {rows.length>0&&<RetiredAliases key={`${spaceId}:${selected.conceptId}`} spaceId={spaceId} conceptId={selected.conceptId} onDenied={()=>{close();onDenied()}}/>}
    {rows.length>0&&<RetiredMappings key={`${spaceId}:${selected.conceptId}`} spaceId={spaceId} conceptId={selected.conceptId} onDenied={()=>{close();onDenied()}}/>}
    {rows.length>0&&<RetiredClaims key={`${spaceId}:${selected.conceptId}`} spaceId={spaceId} conceptId={selected.conceptId} onDenied={()=>{close();onDenied()}}/>}
   </section>}
  </>}
 </section>;
}
