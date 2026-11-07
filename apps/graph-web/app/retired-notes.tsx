"use client";
import {readHistory as readNoteHistory,validNoteText,type Revision} from "./note-history";
import MarkdownView from "./markdown-view";
import {useEffect,useRef,useState} from "react";

type Summary={noteId:string;title:string;version:number;updatedAt:string};
type Page={items:Summary[];nextAfter:string|null};
type Lane="list"|"history";
type Operation={epoch:number;scope:string;controller:AbortController;timer:ReturnType<typeof setTimeout>};
const id=(v:unknown):v is string=>typeof v==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);
const version=(v:unknown):v is number=>typeof v==="number"&&Number.isSafeInteger(v)&&v>0;
const date=(v:unknown)=>typeof v==="string"&&Number.isFinite(Date.parse(v));
const title=(v:unknown)=>validNoteText(v,"");
function readPage(raw:unknown,after:string|null):Page{
 const p=raw as Page;
 if(!p||!Array.isArray(p.items)||p.items.length>20||p.items.some((r,i)=>!r||!id(r.noteId)||!title(r.title)||!version(r.version)||r.version<2||!date(r.updatedAt)||(after!==null&&r.noteId<=after)||(i>0&&r.noteId<=p.items[i-1].noteId)))throw new Error();
 if(p.nextAfter!==null&&(!id(p.nextAfter)||p.items.length!==20||p.nextAfter!==p.items.at(-1)?.noteId))throw new Error();return p;
}
function readHistory(raw:unknown,note:Summary,before:number|null,previous:Revision[]):Revision[]{
 const rows=readNoteHistory(raw,note.noteId,before,previous);
 if(rows.some(r=>r.version>note.version))throw new Error();
 if(before===null){const head=rows[0];if(!head||head.version!==note.version||head.state!=="RETIRED"||head.title!==note.title)throw new Error()}
 return rows;
}

export default function RetiredNotes({spaceId,onDenied}:{spaceId:string;onDenied:()=>void}){
 const path=`/api/spaces/${spaceId}/notes`;
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
  catch{if(relevant("list",op)){setPage(null);closeDetail();setListNotice("폐기한 노트를 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish("list",op)}
 }
 async function history(note:Summary,append=false){
  const cursor=append?before:null;if(append&&!cursor)return;const op=start("history");setHistoryNotice("");if(!append){setRows([]);setRevision(null);setBefore(null)}
  try{const response=await fetchRead(`${path}/${note.noteId}/revisions?limit=5${cursor?`&before=${cursor}`:""}`,op);if(!active("history",op)||selectedId.current!==note.noteId||denied(response))return;if(!response.ok)throw new Error();const result=readHistory(await response.json(),note,cursor,append?rows:[]);if(!active("history",op)||selectedId.current!==note.noteId)return;if(append&&result.some(r=>rows.some(prior=>prior.revisionId===r.revisionId||prior.version===r.version)))throw new Error();setRows(previous=>append?[...previous,...result]:result);if(!append)setRevision(result[0]);setBefore(result.length===5?result.at(-1)!.version:null)}
  catch{if(relevant("history",op)){clearHistory();setHistoryNotice("폐기한 노트의 이력을 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish("history",op)}
 }
 function select(note:Summary){closeDetail();selectedId.current=note.noteId;setSelected(note);void history(note)}
 return <section className="retired-notes" aria-label="폐기한 노트 보관함"><h4>폐기한 노트</h4><p className="muted">폐기한 노트와 날짜별 기록의 제목·본문 이력을 읽습니다. 폐기 상태는 유지됩니다.</p>
  {!open?<button type="button" onClick={()=>{opened.current=true;setOpen(true);void load()}}>폐기한 노트 열기</button>:<><div className="actions"><button type="button" onClick={close}>폐기한 노트 닫기</button><button type="button" disabled={listBusy} onClick={()=>void load()}>폐기한 노트 새로고침</button></div>
   {listBusy&&<p className="muted">폐기한 노트 확인 중…</p>}{listNotice&&<p role="status">{listNotice}</p>}
   {page&&<><p className="muted">불러온 폐기 노트 {page.items.length}개 · 생성 순서</p>{page.items.length===0&&<p>폐기한 노트가 없습니다.</p>}<div className="retired-note-results">{page.items.map(row=><button type="button" key={row.noteId} aria-label={`폐기 노트 이력 열기: ${row.title} · ${row.noteId}`} onClick={()=>select(row)}>{row.title}<small>{row.noteId} · v{row.version}</small></button>)}</div>{page.nextAfter&&<button type="button" disabled={listBusy} onClick={()=>void load(true)}>폐기한 노트 더 보기</button>}</>}
   {selected&&<section aria-label="폐기한 노트 이력"><button type="button" onClick={closeDetail}>폐기 노트 이력 닫기</button><p className="muted">노트 ID: {selected.noteId}</p>{historyBusy&&<p>노트 이력 확인 중…</p>}{historyNotice&&<p role="status">{historyNotice}</p>}<div className="retired-note-revisions">{rows.map(row=><button type="button" key={row.revisionId} aria-pressed={revision?.revisionId===row.revisionId} disabled={historyBusy} onClick={()=>setRevision(row)}>v{row.version} · {row.state}</button>)}</div>{before&&<button type="button" disabled={historyBusy} onClick={()=>void history(selected,true)}>이전 폐기 노트 이력 더 보기</button>}{revision&&<article aria-label="선택한 폐기 노트 버전"><h5>{revision.title} · v{revision.version} · {revision.state}</h5><p>수정 ID: {revision.revisionId}<br/>수정자 ID: {revision.actorId}<br/>수정 시각: {revision.occurredAt}</p><MarkdownView key={revision.revisionId} markdown={revision.markdown} context="폐기 노트 이력"/></article>}<button type="button" disabled={historyBusy} onClick={()=>void history(selected)}>폐기 노트 이력 새로고침</button>
   </section>}
  </>}
 </section>;
}
