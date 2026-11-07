"use client";
import {useEffect,useRef,useState} from "react";
import {type ClaimObject,validClaimObject,claimObjectText} from "./claim-object";

type ClaimPin={revisionId:string;version:number;subjectConceptId:string;predicateConceptId:string;object:ClaimObject};
type SourcePin={sourceId:string;revisionId:string;version:number;title:string;url:string|null;citation:string;licenseNote:string};
type Citation={citationId:string;spaceId:string;subjectConceptId:string;claimId:string;claim:ClaimPin;source:SourcePin;state:"RETIRED";visibility:"PRIVATE";version:number;createdAt:string;updatedAt:string};
type Page={items:Citation[];nextAfter:string|null};
type Revision={revisionId:string;citationId:string;version:number;state:"ACTIVE"|"RETIRED";claim:ClaimPin;source:SourcePin;actorId:string;occurredAt:string};
type Lane="list"|"history";
type Operation={epoch:number;scope:string;controller:AbortController;timer:ReturnType<typeof setTimeout>};
const id=(v:unknown):v is string=>typeof v==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);
const version=(v:unknown):v is number=>typeof v==="number"&&Number.isSafeInteger(v)&&v>0;
const date=(v:unknown)=>typeof v==="string"&&Number.isFinite(Date.parse(v));
function text(value:unknown,max:number):value is string{return typeof value==="string"&&Array.from(value).length<=max&&!/[\u0000\ud800-\udfff]/u.test(value)}
function safeUrl(value:unknown):value is string|null{
 if(value===null)return true;if(typeof value!=="string"||!value||value.length>2048||/[^\x21-\x7e]|\\/.test(value)||!/^https?:\/\//i.test(value))return false;
 try{const parsed=new URL(value),authority=value.match(/^https?:\/\/([^/?#]+)/i)?.[1];if(!authority||parsed.username||parsed.password||/[@%]/.test(authority)||!parsed.hostname||!/^https?:$/.test(parsed.protocol))return false;const host=parsed.hostname;if(!(host.startsWith("[")&&host.endsWith("]"))&&!/^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.?$/i.test(host))return false;const port=authority.startsWith("[")?authority.slice(authority.indexOf("]")+1):authority.includes(":")?authority.slice(authority.lastIndexOf(":")):"";return !port||/^:[0-9]+$/.test(port)&&Number(port.slice(1))>=1&&Number(port.slice(1))<=65535}catch{return false}
}
function snapshots(row:{claim:ClaimPin;source:SourcePin},subject:string){
 const c=row?.claim,s=row?.source;
 return !!c&&!!s&&id(c.revisionId)&&version(c.version)&&c.subjectConceptId===subject&&id(c.predicateConceptId)&&validClaimObject(c.object)&&id(s.sourceId)&&id(s.revisionId)&&version(s.version)&&text(s.title,200)&&!!s.title.trim()&&safeUrl(s.url)&&text(s.citation,4000)&&text(s.licenseNote,1000)&&(s.url!==null||!!s.citation.trim());
}
function samePins(a:{claim:ClaimPin;source:SourcePin},b:{claim:ClaimPin;source:SourcePin}){
 return a.claim.revisionId===b.claim.revisionId&&a.claim.version===b.claim.version&&a.claim.subjectConceptId===b.claim.subjectConceptId&&a.claim.predicateConceptId===b.claim.predicateConceptId&&a.claim.object.type===b.claim.object.type&&a.claim.object.value===b.claim.object.value&&a.claim.object.conceptId===b.claim.object.conceptId&&a.source.sourceId===b.source.sourceId&&a.source.revisionId===b.source.revisionId&&a.source.version===b.source.version&&a.source.title===b.source.title&&a.source.url===b.source.url&&a.source.citation===b.source.citation&&a.source.licenseNote===b.source.licenseNote;
}
function readPage(raw:unknown,space:string,subject:string,claim:string,after:string|null):Page{
 const p=raw as Page;
 if(!p||!Array.isArray(p.items)||p.items.length>20||p.items.some((r,i)=>!r||!id(r.citationId)||r.spaceId!==space||r.subjectConceptId!==subject||r.claimId!==claim||r.state!=="RETIRED"||r.visibility!=="PRIVATE"||!snapshots(r,subject)||!version(r.version)||r.version<2||!date(r.createdAt)||!date(r.updatedAt)||(after!==null&&r.citationId<=after)||(i>0&&r.citationId<=p.items[i-1].citationId)))throw new Error();
 if(p.nextAfter!==null&&(!id(p.nextAfter)||p.items.length!==20||p.nextAfter!==p.items.at(-1)?.citationId))throw new Error();return p;
}
function readHistory(raw:unknown,citation:Citation,before:number|null):Revision[]{
 const rows=raw as Revision[];
 if(!Array.isArray(rows)||rows.length>5||rows.some((r,i)=>!r||!id(r.revisionId)||r.citationId!==citation.citationId||!version(r.version)||r.version>citation.version||!["ACTIVE","RETIRED"].includes(r.state)||!snapshots(r,citation.subjectConceptId)||!id(r.actorId)||!date(r.occurredAt)||(before!==null&&r.version>=before)||(i>0&&r.version>=rows[i-1].version))||new Set(rows.map(r=>r.revisionId)).size!==rows.length)throw new Error();
 if(before===null){const head=rows[0];if(!head||head.version!==citation.version||head.state!=="RETIRED"||!samePins(head,citation))throw new Error()}
 return rows;
}
function Pinned({row}:{row:Revision}){const {claim,source}=row;return <><h5>{source.title}</h5><p>고정한 관계 v{claim.version} · 출처 v{source.version}</p><p>주제 개념 ID: {claim.subjectConceptId}<br/>서술 개념 ID: {claim.predicateConceptId}<br/>관계 Revision ID: {claim.revisionId}<br/>출처 ID: {source.sourceId}<br/>출처 Revision ID: {source.revisionId}</p><h5>고정한 관계 대상</h5><pre>{claimObjectText(claim.object)}</pre>{source.url&&<p><a href={source.url} target="_blank" rel="noopener noreferrer">해제한 연결의 출처 URL 열기</a><small>{source.url}</small></p>}<h5>고정한 인용 정보</h5><pre>{source.citation||"입력한 인용 정보가 없습니다."}</pre><h5>고정한 라이선스 메모</h5><pre>{source.licenseNote||"입력한 라이선스 메모가 없습니다."}</pre></>}

export default function RetiredCitations({spaceId,conceptId,claimId,onDenied}:{spaceId:string;conceptId:string;claimId:string;onDenied:()=>void}){
 const path=`/api/spaces/${spaceId}/concepts/${conceptId}/claims/${claimId}/citations`;
 const [open,setOpen]=useState(false),[page,setPage]=useState<Page|null>(null),[listBusy,setListBusy]=useState(false),[listNotice,setListNotice]=useState("");
 const [selected,setSelected]=useState<Citation|null>(null),[rows,setRows]=useState<Revision[]>([]),[revision,setRevision]=useState<Revision|null>(null),[before,setBefore]=useState<number|null>(null),[historyBusy,setHistoryBusy]=useState(false),[historyNotice,setHistoryNotice]=useState("");
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
  catch{if(relevant("list",op)){setPage(null);closeDetail();setListNotice("해제한 출처 연결을 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish("list",op)}
 }
 async function history(citation:Citation,append=false){
  const cursor=append?before:null;if(append&&!cursor)return;const op=start("history");setHistoryNotice("");if(!append){setRows([]);setRevision(null);setBefore(null)}
  try{const response=await fetchRead(`${path}/${citation.citationId}/revisions?limit=5${cursor?`&before=${cursor}`:""}`,op);if(!active("history",op)||selectedId.current!==citation.citationId||denied(response))return;if(!response.ok)throw new Error();const result=readHistory(await response.json(),citation,cursor);if(!active("history",op)||selectedId.current!==citation.citationId)return;if(append&&result.some(r=>rows.some(prior=>prior.revisionId===r.revisionId||prior.version===r.version)))throw new Error();setRows(previous=>append?[...previous,...result]:result);if(!append)setRevision(result[0]);setBefore(result.length===5?result.at(-1)!.version:null)}
  catch{if(relevant("history",op)){clearHistory();setHistoryNotice("해제한 출처 연결의 이력을 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish("history",op)}
 }
 function select(citation:Citation){closeDetail();selectedId.current=citation.citationId;setSelected(citation);void history(citation)}
 return <section className="retired-citations" aria-label="해제한 출처 연결 보관함"><h4>해제한 출처 연결</h4><p className="muted">직접 해제한 연결에 고정된 관계·출처와 수정 이력을 다시 확인합니다.</p>
  {!open?<button type="button" onClick={()=>{opened.current=true;setOpen(true);void load()}}>해제한 출처 연결 열기</button>:<><div className="actions"><button type="button" onClick={close}>해제한 출처 연결 닫기</button><button type="button" disabled={listBusy} onClick={()=>void load()}>해제한 출처 연결 새로고침</button></div>
   {listBusy&&<p className="muted">해제한 출처 연결 확인 중…</p>}{listNotice&&<p role="status">{listNotice}</p>}
   {page&&<><p className="muted">불러온 해제 연결 {page.items.length}개 · 생성 순서</p>{page.items.length===0&&<p>해제한 출처 연결이 없습니다.</p>}<div className="retired-citation-results">{page.items.map(row=><button type="button" key={row.citationId} aria-label={`해제 연결 이력 열기: ${row.citationId}`} onClick={()=>select(row)}>{row.source.title}<small>출처 ID: {row.source.sourceId}</small><small>{row.citationId} · v{row.version}</small></button>)}</div>{page.nextAfter&&<button type="button" disabled={listBusy} onClick={()=>void load(true)}>해제한 출처 연결 더 보기</button>}</>}
   {selected&&<section aria-label="해제한 출처 연결 이력"><button type="button" onClick={closeDetail}>해제 연결 이력 닫기</button><p className="muted">연결 ID: {selected.citationId}</p>{historyBusy&&<p>연결 이력 확인 중…</p>}{historyNotice&&<p role="status">{historyNotice}</p>}<div className="retired-citation-revisions">{rows.map(row=><button type="button" key={row.revisionId} aria-pressed={revision?.revisionId===row.revisionId} disabled={historyBusy} onClick={()=>setRevision(row)}>v{row.version} · {row.state}</button>)}</div>{before&&<button type="button" disabled={historyBusy} onClick={()=>void history(selected,true)}>이전 해제 연결 이력 더 보기</button>}{revision&&<article aria-label="선택한 해제 연결 버전"><h5>연결 v{revision.version} · {revision.state}</h5><p>수정 ID: {revision.revisionId}<br/>수정자 ID: {revision.actorId}<br/>수정 시각: {revision.occurredAt}</p><Pinned row={revision}/></article>}<button type="button" disabled={historyBusy} onClick={()=>void history(selected)}>해제 연결 이력 새로고침</button></section>}
  </>}
 </section>;
}
