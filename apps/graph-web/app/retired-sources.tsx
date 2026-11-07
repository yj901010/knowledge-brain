"use client";
import {SourceRevisionComparison,type SourceRevision as Revision} from "./knowledge-revision-comparison";
import {useEffect,useRef,useState} from "react";
import SourceCitations from "./source-citations";

type Fields={title:string;url:string|null;citation:string;licenseNote:string};
type Summary=Fields&{sourceId:string;spaceId:string;visibility:"PRIVATE";state:"RETIRED";version:number;createdAt:string;updatedAt:string};
type Page={items:Summary[];nextAfter:string|null};
type Lane="list"|"history";
type Operation={epoch:number;scope:string;controller:AbortController;timer:ReturnType<typeof setTimeout>};
const id=(v:unknown):v is string=>typeof v==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);
const version=(v:unknown):v is number=>typeof v==="number"&&Number.isSafeInteger(v)&&v>0;
const date=(v:unknown)=>typeof v==="string"&&Number.isFinite(Date.parse(v));
const javaWhitespace=/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g;
function strip(value:string){return value.replace(javaWhitespace,"")}
function validText(value:unknown,max:number):value is string{return typeof value==="string"&&Array.from(value).length<=max&&!/[\u0000\ud800-\udfff]/u.test(value)}
function validUrl(value:unknown):value is string|null{
 if(value===null)return true;
 if(typeof value!=="string"||!value||value!==strip(value)||value.length>2048||/[^\x21-\x7e]|\\/.test(value)||!/^https?:\/\//i.test(value))return false;
 try{const parsed=new URL(value),authority=value.match(/^https?:\/\/([^/?#]+)/i)?.[1];if(!authority||parsed.username||parsed.password||/[@%]/.test(authority)||!parsed.hostname||!/^https?:$/.test(parsed.protocol))return false;
  const host=parsed.hostname;if(!(host.startsWith("[")&&host.endsWith("]"))&&!/^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.?$/i.test(host))return false;
  const port=authority.startsWith("[")?authority.slice(authority.indexOf("]")+1):authority.includes(":")?authority.slice(authority.lastIndexOf(":")):"";
  return !port||/^:[0-9]+$/.test(port)&&Number(port.slice(1))>=1&&Number(port.slice(1))<=65535;
 }catch{return false}
}
function validFields(value:Fields){return validText(value.title,200)&&!!strip(value.title)&&validUrl(value.url)&&validText(value.citation,4000)&&validText(value.licenseNote,1000)&&(value.url!==null||!!strip(value.citation))}
function readPage(raw:unknown,space:string,after:string|null):Page{
 const p=raw as Page;
 if(!p||!Array.isArray(p.items)||p.items.length>20||p.items.some((r,i)=>!r||!id(r.sourceId)||!validFields(r)||r.title!==strip(r.title)||r.spaceId!==space||r.visibility!=="PRIVATE"||r.state!=="RETIRED"||!version(r.version)||r.version<2||!date(r.createdAt)||!date(r.updatedAt)||(after!==null&&r.sourceId<=after)||(i>0&&r.sourceId<=p.items[i-1].sourceId)))throw new Error();
 if(p.nextAfter!==null&&(!id(p.nextAfter)||p.items.length!==20||p.nextAfter!==p.items.at(-1)?.sourceId))throw new Error();return p;
}
function readHistory(raw:unknown,source:Summary,before:number|null):Revision[]{
 const rows=raw as Revision[];
 if(!Array.isArray(rows)||rows.length>5||rows.some((r,i)=>!r||!id(r.revisionId)||r.sourceId!==source.sourceId||!version(r.version)||r.version>source.version||!["ACTIVE","RETIRED"].includes(r.state)||!validFields(r)||r.title!==strip(r.title)||!id(r.actorId)||!date(r.occurredAt)||(before!==null&&r.version>=before)||(i>0&&r.version>=rows[i-1].version))||new Set(rows.map(r=>r.revisionId)).size!==rows.length)throw new Error();
 if(before===null){const head=rows[0];if(!head||head.version!==source.version||head.state!=="RETIRED"||head.title!==source.title||head.url!==source.url||head.citation!==source.citation||head.licenseNote!==source.licenseNote)throw new Error()}
 return rows;
}

export default function RetiredSources({spaceId,onDenied}:{spaceId:string;onDenied:()=>void}){
 const path=`/api/spaces/${spaceId}/sources`;
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
  try{const query=new URLSearchParams({state:"RETIRED",limit:"20"});if(after)query.set("after",after);const response=await fetchRead(`${path}?${query}`,op);if(!active("list",op)||denied(response))return;if(!response.ok)throw new Error();const result=readPage(await response.json(),spaceId,after);if(!active("list",op))return;setPage(previous=>({items:[...(append?previous?.items??[]:[]),...result.items],nextAfter:result.nextAfter}))}
  catch{if(relevant("list",op)){setPage(null);closeDetail();setListNotice("폐기한 출처를 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish("list",op)}
 }
 async function history(source:Summary,append=false){
  const cursor=append?before:null;if(append&&!cursor)return;const op=start("history");setHistoryNotice("");if(!append){setRows([]);setRevision(null);setBefore(null)}
  try{const response=await fetchRead(`${path}/${source.sourceId}/revisions?limit=5${cursor?`&before=${cursor}`:""}`,op);if(!active("history",op)||selectedId.current!==source.sourceId||denied(response))return;if(!response.ok)throw new Error();const result=readHistory(await response.json(),source,cursor);if(!active("history",op)||selectedId.current!==source.sourceId)return;if(append&&result.some(r=>rows.some(prior=>prior.revisionId===r.revisionId||prior.version===r.version)))throw new Error();setRows(previous=>append?[...previous,...result]:result);if(!append)setRevision(result[0]);setBefore(result.length===5?result.at(-1)!.version:null)}
  catch{if(relevant("history",op)){clearHistory();setHistoryNotice("폐기한 출처의 이력을 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish("history",op)}
 }
 function select(source:Summary){closeDetail();selectedId.current=source.sourceId;setSelected(source);void history(source)}
 return <section className="retired-sources" aria-label="폐기한 출처 보관함"><h4>폐기한 출처</h4><p className="muted">폐기한 개인 출처와 남아 있는 수정 이력을 다시 확인합니다.</p>
  {!open?<button type="button" onClick={()=>{opened.current=true;setOpen(true);void load()}}>폐기한 출처 열기</button>:<><div className="actions"><button type="button" onClick={close}>폐기한 출처 닫기</button><button type="button" disabled={listBusy} onClick={()=>void load()}>폐기한 출처 새로고침</button></div>
   {listBusy&&<p className="muted">폐기한 출처 확인 중…</p>}{listNotice&&<p role="status">{listNotice}</p>}
   {page&&<><p className="muted">불러온 폐기 출처 {page.items.length}개 · 생성 순서</p>{page.items.length===0&&<p>폐기한 출처가 없습니다.</p>}<div className="retired-source-results">{page.items.map(row=><button type="button" key={row.sourceId} aria-label={`폐기 출처 이력 열기: ${row.title} · ${row.sourceId}`} onClick={()=>select(row)}>{row.title}<small>{row.sourceId} · v{row.version}</small></button>)}</div>{page.nextAfter&&<button type="button" disabled={listBusy} onClick={()=>void load(true)}>폐기한 출처 더 보기</button>}</>}
   {selected&&<section aria-label="폐기한 출처 이력"><button type="button" onClick={closeDetail}>폐기 출처 이력 닫기</button><p className="muted">출처 ID: {selected.sourceId}</p>{historyBusy&&<p>출처 이력 확인 중…</p>}{historyNotice&&<p role="status">{historyNotice}</p>}{rows.length>0&&<SourceRevisionComparison key={selected.sourceId+rows[0].revisionId} revisions={rows} busy={historyBusy}/>}<div className="retired-source-revisions">{rows.map(row=><button type="button" key={row.revisionId} aria-pressed={revision?.revisionId===row.revisionId} disabled={historyBusy} onClick={()=>setRevision(row)}>v{row.version} · {row.state}</button>)}</div>{before&&<button type="button" disabled={historyBusy} onClick={()=>void history(selected,true)}>이전 폐기 출처 이력 더 보기</button>}{revision&&<article aria-label="선택한 폐기 출처 버전"><h5>{revision.title} · v{revision.version} · {revision.state}</h5><p>수정 ID: {revision.revisionId}<br/>수정자 ID: {revision.actorId}<br/>수정 시각: {revision.occurredAt}</p>{revision.url&&<p><a href={revision.url} target="_blank" rel="noopener noreferrer">폐기 출처 URL 열기</a><small>{revision.url}</small></p>}<h5>인용 정보</h5><pre>{revision.citation||"입력한 인용 정보가 없습니다."}</pre><h5>라이선스 메모</h5><pre>{revision.licenseNote||"입력한 라이선스 메모가 없습니다."}</pre></article>}<button type="button" disabled={historyBusy} onClick={()=>void history(selected)}>폐기 출처 이력 새로고침</button>
    {rows.length>0&&<SourceCitations key={`${spaceId}:${selected.sourceId}`} spaceId={spaceId} sourceId={selected.sourceId} onDenied={()=>{close();onDenied()}}/>}
   </section>}
  </>}
 </section>;
}
