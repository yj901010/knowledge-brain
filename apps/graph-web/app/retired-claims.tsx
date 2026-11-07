"use client";
import RetiredQualifiers from "./retired-qualifiers";
import RetiredCitations from "./retired-citations";
import {useEffect,useRef,useState} from "react";
import {type ClaimObject,validClaimObject,claimObjectText} from "./claim-object";
import ClaimRevisionComparison,{type ClaimRevision} from "./claim-revision-comparison";

type RetiredClaim={claimId:string;spaceId:string;subjectConceptId:string;predicateConceptId:string;object:ClaimObject;state:"RETIRED";visibility:"PRIVATE";verificationState:"DRAFT";version:number;createdAt:string;updatedAt:string};
type Page={items:RetiredClaim[];nextAfter:string|null};
type Lane="list"|"history";
type Operation={epoch:number;scope:string;controller:AbortController;timer:ReturnType<typeof setTimeout>};
const id=(v:unknown):v is string=>typeof v==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);
const version=(v:unknown):v is number=>typeof v==="number"&&Number.isSafeInteger(v)&&v>0;
const date=(v:unknown)=>typeof v==="string"&&Number.isFinite(Date.parse(v));
function readPage(raw:unknown,space:string,subject:string,after:string|null):Page{
 const p=raw as Page;
 if(!p||!Array.isArray(p.items)||p.items.length>20||p.items.some((r,i)=>!r||!id(r.claimId)||r.spaceId!==space||r.subjectConceptId!==subject||!id(r.predicateConceptId)||!validClaimObject(r.object)||r.state!=="RETIRED"||r.visibility!=="PRIVATE"||r.verificationState!=="DRAFT"||!version(r.version)||!date(r.createdAt)||!date(r.updatedAt)||(after!==null&&r.claimId<=after)||(i>0&&r.claimId<=p.items[i-1].claimId)))throw new Error();
 if(p.nextAfter!==null&&(!id(p.nextAfter)||p.items.length!==20||p.nextAfter!==p.items.at(-1)?.claimId))throw new Error();return p;
}
function readHistory(raw:unknown,claim:RetiredClaim,before:number|null):ClaimRevision[]{
 const rows=raw as ClaimRevision[];
 if(!Array.isArray(rows)||rows.length>5||rows.some((r,i)=>!r||!id(r.revisionId)||r.claimId!==claim.claimId||r.subjectConceptId!==claim.subjectConceptId||!id(r.predicateConceptId)||!validClaimObject(r.object)||!version(r.version)||r.version>claim.version||!["ACTIVE","RETIRED"].includes(r.state)||r.verificationState!=="DRAFT"||!id(r.actorId)||!date(r.occurredAt)||(before!==null&&r.version>=before)||(i>0&&r.version>=rows[i-1].version))||new Set(rows.map(r=>r.revisionId)).size!==rows.length)throw new Error();
 if(before===null){const head=rows[0];if(!head||head.version!==claim.version||head.state!=="RETIRED"||head.predicateConceptId!==claim.predicateConceptId||head.object.type!==claim.object.type||head.object.value!==claim.object.value||head.object.conceptId!==claim.object.conceptId)throw new Error()}
 return rows;
}

export default function RetiredClaims({spaceId,conceptId,onDenied}:{spaceId:string;conceptId:string;onDenied:()=>void}){
 const path=`/api/spaces/${spaceId}/concepts/${conceptId}/claims`;
 const [open,setOpen]=useState(false),[page,setPage]=useState<Page|null>(null),[listBusy,setListBusy]=useState(false),[listNotice,setListNotice]=useState("");
 const [selected,setSelected]=useState<RetiredClaim|null>(null),[rows,setRows]=useState<ClaimRevision[]>([]),[revision,setRevision]=useState<ClaimRevision|null>(null),[before,setBefore]=useState<number|null>(null),[historyBusy,setHistoryBusy]=useState(false),[historyNotice,setHistoryNotice]=useState("");
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
  catch{if(relevant("list",op)){setPage(null);closeDetail();setListNotice("폐기한 관계를 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish("list",op)}
 }
 async function history(claim:RetiredClaim,append=false){
  const cursor=append?before:null;if(append&&!cursor)return;const op=start("history");setHistoryNotice("");if(!append){setRows([]);setRevision(null);setBefore(null)}
  try{const response=await fetchRead(`${path}/${claim.claimId}/revisions?limit=5${cursor?`&before=${cursor}`:""}`,op);if(!active("history",op)||selectedId.current!==claim.claimId||denied(response))return;if(!response.ok)throw new Error();const result=readHistory(await response.json(),claim,cursor);if(!active("history",op)||selectedId.current!==claim.claimId)return;if(append&&result.some(r=>rows.some(prior=>prior.revisionId===r.revisionId||prior.version===r.version)))throw new Error();setRows(previous=>append?[...previous,...result]:result);if(!append)setRevision(result[0]);setBefore(result.length===5?result.at(-1)!.version:null)}
  catch{if(relevant("history",op)){clearHistory();setHistoryNotice("폐기한 관계의 이력을 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish("history",op)}
 }
 function select(claim:RetiredClaim){closeDetail();selectedId.current=claim.claimId;setSelected(claim);void history(claim)}
 return <section className="retired-claims" aria-label="폐기한 관계 보관함"><h4>폐기한 관계</h4><p className="muted">직접 폐기한 관계의 기록을 다시 확인합니다. 연결된 개념이 폐기되어 활성 목록에서 숨겨진 관계는 포함하지 않습니다.</p>
  {!open?<button type="button" onClick={()=>{opened.current=true;setOpen(true);void load()}}>폐기한 관계 열기</button>:<><div className="actions"><button type="button" onClick={close}>폐기한 관계 닫기</button><button type="button" disabled={listBusy} onClick={()=>void load()}>폐기한 관계 새로고침</button></div>
   {listBusy&&<p className="muted">폐기한 관계 확인 중…</p>}{listNotice&&<p role="status">{listNotice}</p>}
   {page&&<><p className="muted">불러온 폐기 관계 {page.items.length}개 · 생성 순서</p>{page.items.length===0&&<p>폐기한 관계가 없습니다.</p>}<div className="retired-claim-results">{page.items.map(row=><button type="button" key={row.claimId} aria-label={`폐기 관계 이력 열기: ${row.claimId}`} onClick={()=>select(row)}>{claimObjectText(row.object)}<small>서술 개념 ID: {row.predicateConceptId}</small><small>{row.claimId} · v{row.version} · RETIRED</small></button>)}</div>{page.nextAfter&&<button type="button" disabled={listBusy} onClick={()=>void load(true)}>폐기한 관계 더 보기</button>}</>}
   {selected&&<section aria-label="폐기한 관계 이력"><button type="button" onClick={closeDetail}>폐기 관계 이력 닫기</button><p className="muted">관계 ID: {selected.claimId}</p>{historyBusy&&<p>관계 이력 확인 중…</p>}{historyNotice&&<p role="status">{historyNotice}</p>}{rows.length>0&&<ClaimRevisionComparison key={selected.claimId+rows[0].revisionId} revisions={rows} busy={historyBusy}/>}<div className="retired-claim-revisions">{rows.map(row=><button type="button" key={row.revisionId} aria-pressed={revision?.revisionId===row.revisionId} disabled={historyBusy} onClick={()=>setRevision(row)}>v{row.version} · {row.state}</button>)}</div>{before&&<button type="button" disabled={historyBusy} onClick={()=>void history(selected,true)}>이전 폐기 관계 이력 더 보기</button>}{revision&&<article aria-label="선택한 폐기 관계 버전"><h5>v{revision.version} · {revision.state} · DRAFT</h5><p>수정 ID: {revision.revisionId}<br/>수정자 ID: {revision.actorId}<br/>수정 시각: {revision.occurredAt}<br/>서술 개념 ID: {revision.predicateConceptId}</p><pre>{claimObjectText(revision.object)}</pre></article>}<button type="button" disabled={historyBusy} onClick={()=>void history(selected)}>폐기 관계 이력 새로고침</button>{rows.length>0&&<RetiredQualifiers key={`${spaceId}:${conceptId}:${selected.claimId}`} spaceId={spaceId} conceptId={conceptId} claimId={selected.claimId} onDenied={()=>{close();onDenied()}}/>}{rows.length>0&&<RetiredCitations key={`${spaceId}:${conceptId}:${selected.claimId}`} spaceId={spaceId} conceptId={conceptId} claimId={selected.claimId} onDenied={()=>{close();onDenied()}}/>}</section>}
  </>}
 </section>;
}
