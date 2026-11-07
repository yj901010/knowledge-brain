"use client";
import MetadataNameSearch from "./metadata-name-search";
import {useEffect,useRef,useState} from "react";
import {validNoteText} from "./note-history";

type Tag={id:string;name:string;type:"TAG";activeNoteCount:number};
type Note={noteId:string;title:string;version:number;updatedAt:string};
type Page<T>={items:T[];nextAfter:string|null};
type Lane="tags"|"notes";
type Operation={epoch:number;scope:string;controller:AbortController;timer:ReturnType<typeof setTimeout>};
const id=(v:unknown):v is string=>typeof v==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);
const count=(v:unknown):v is number=>typeof v==="number"&&Number.isSafeInteger(v)&&v>=0;
function readPage<T>(raw:unknown,after:string|null,key:(r:T)=>unknown,valid:(r:T)=>boolean):Page<T>{
 const p=raw as Page<T>;
 if(!p||!Array.isArray(p.items)||p.items.length>20)throw new Error();
 let last=after;
 for(const row of p.items){if(!row||!valid(row))throw new Error();const value=key(row);if(!id(value)||(last!==null&&value<=last))throw new Error();last=value}
 if(p.nextAfter!==null&&(!id(p.nextAfter)||p.items.length!==20||p.nextAfter!==last))throw new Error();return p;
}
const tag=(r:Tag)=>r.type==="TAG"&&typeof r.name==="string"&&Array.from(r.name).length>0&&Array.from(r.name).length<=64&&!/[\u0000-\u001f\u007f-\u009f]/.test(r.name)&&count(r.activeNoteCount);
const note=(r:Note)=>validNoteText(r.title,"")&&count(r.version)&&r.version>0&&typeof r.updatedAt==="string"&&Number.isFinite(Date.parse(r.updatedAt));

export default function TagNotes({spaceId,onOpen,onDenied}:{spaceId:string;onOpen:(note:{noteId:string;title:string})=>void;onDenied:()=>void}){
 const path=`/api/spaces/${spaceId}/tags`;
 const [open,setOpen]=useState(false),[tags,setTags]=useState<Page<Tag>|null>(null),[selected,setSelected]=useState<Tag|null>(null),[notes,setNotes]=useState<Page<Note>|null>(null);
 const [nameQuery,setNameQuery]=useState<string|null>(null);const nameQueryRef=useRef<string|null>(null);
 const [busy,setBusy]=useState({tags:false,notes:false}),[notice,setNotice]=useState({tags:"",notes:""});
 const alive=useRef(true),opened=useRef(false),scope=useRef(path),selectedId=useRef<string|null>(null),epochs=useRef({tags:0,notes:0}),requests=useRef<Record<Lane,Operation|null>>({tags:null,notes:null});scope.current=path;
 function stop(lane:Lane){epochs.current[lane]++;const op=requests.current[lane];if(op){clearTimeout(op.timer);op.controller.abort()}requests.current[lane]=null}
 function working(lane:Lane,value:boolean){setBusy(prior=>({...prior,[lane]:value}))}
 function message(lane:Lane,value:string){setNotice(prior=>({...prior,[lane]:value}))}
 function start(lane:Lane){stop(lane);const controller=new AbortController(),op={epoch:epochs.current[lane],scope:path,controller,timer:setTimeout(()=>controller.abort(),8000)};requests.current[lane]=op;working(lane,true);message(lane,"");return op}
 function relevant(lane:Lane,op:Operation){return alive.current&&opened.current&&scope.current===op.scope&&epochs.current[lane]===op.epoch}
 function active(lane:Lane,op:Operation){return relevant(lane,op)&&!op.controller.signal.aborted}
 function finish(lane:Lane,op:Operation){clearTimeout(op.timer);if(relevant(lane,op)){requests.current[lane]=null;working(lane,false)}}
 function closeNotes(){stop("notes");selectedId.current=null;setSelected(null);setNotes(null);working("notes",false);message("notes","")}
 function close(){nameQueryRef.current=null;setNameQuery(null);opened.current=false;stop("tags");closeNotes();setOpen(false);setTags(null);working("tags",false);message("tags","")}
 function denied(response:Response){if(![401,403,404].includes(response.status))return false;close();onDenied();return true}
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;opened.current=false;stop("tags");stop("notes")}},[path]);
 function fetchRead(url:string,op:Operation){return fetch(url,{credentials:"same-origin",cache:"no-store",redirect:"error",signal:op.controller.signal})}
 async function loadTags(append=false,q=nameQueryRef.current){
  const after=append?tags?.nextAfter??null:null;if(append&&!after)return;const op=start("tags");if(!append){setTags(null);closeNotes()}
  try{const response=await fetchRead(`${path}?${new URLSearchParams({limit:"20",...(after?{after}:{}),...(q===null?{}:{q})})}`,op);if(!active("tags",op)||denied(response))return;if(!response.ok)throw new Error();const result=readPage<Tag>(await response.json(),after,r=>r.id,tag);if(q!==null&&result.items.some(row=>!row.name.includes(q)))throw new Error();if(!active("tags",op))return;setTags(prior=>({items:[...(append?prior?.items??[]:[]),...result.items],nextAfter:result.nextAfter}))}
  catch{if(relevant("tags",op)){setTags(null);closeNotes();message("tags","태그를 불러오지 못했습니다. 새로고침으로 다시 조회해 주세요.")}}finally{finish("tags",op)}
 }
 async function loadNotes(selectedTag:Tag,append=false){
  const after=append?notes?.nextAfter??null:null;if(append&&!after)return;const op=start("notes");if(!append)setNotes(null);
  try{const response=await fetchRead(`${path}/${selectedTag.id}/notes?limit=20${after?`&after=${after}`:""}`,op);if(!active("notes",op)||selectedId.current!==selectedTag.id||denied(response))return;if(!response.ok)throw new Error();const result=readPage<Note>(await response.json(),after,r=>r.noteId,note);if(!active("notes",op)||selectedId.current!==selectedTag.id)return;setNotes(prior=>({items:[...(append?prior?.items??[]:[]),...result.items],nextAfter:result.nextAfter}))}
  catch{if(relevant("notes",op)){setNotes(null);message("notes","태그의 노트를 불러오지 못했습니다. 새로고침으로 다시 조회해 주세요.")}}finally{finish("notes",op)}
 }
 function select(value:Tag){closeNotes();selectedId.current=value.id;setSelected(value);void loadNotes(value)}
 return <section className="tag-notes" aria-label="태그별 노트 탐색"><h3>태그로 노트 찾기</h3>
  <p className="muted">태그를 선택하면 연결된 일반 노트와 날짜별 기록을 볼 수 있습니다.</p>
  {!open?<button type="button" onClick={()=>{opened.current=true;setOpen(true);void loadTags()}}>태그 탐색 열기</button>:<>
   <div className="actions"><button type="button" onClick={close}>태그 탐색 닫기</button><button type="button" disabled={busy.tags} onClick={()=>void loadTags()}>태그 목록 새로고침</button></div>
   <MetadataNameSearch label="태그" applied={nameQuery} onApply={q=>{nameQueryRef.current=q;setNameQuery(q);void loadTags(false,q)}}/>
   {busy.tags&&<p>태그 확인 중…</p>}{notice.tags&&<p role="status">{notice.tags}</p>}
   {tags&&<><p className="muted">불러온 태그 {tags.items.length}개 · 생성 순서</p>{!tags.items.length&&<p>{nameQuery===null?"아직 태그가 없습니다. 노트의 태그·속성에서 추가할 수 있습니다.":"이름 검색에 맞는 태그가 없습니다."}</p>}<div className="tag-results">{tags.items.map(row=><button type="button" key={row.id} aria-pressed={selected?.id===row.id} aria-label={`태그 선택: ${row.name}`} onClick={()=>select(row)}>{row.name}<small>활성 노트 {row.activeNoteCount}개</small></button>)}</div>{tags.nextAfter&&<button type="button" disabled={busy.tags} onClick={()=>void loadTags(true)}>태그 더 보기</button>}</>}
   {selected&&<section aria-label="선택한 태그의 노트"><h4>태그: {selected.name}</h4><div className="actions"><button type="button" onClick={closeNotes}>태그 선택 해제</button><button type="button" disabled={busy.notes} onClick={()=>void loadNotes(selected)}>태그 노트 새로고침</button></div>{busy.notes&&<p>연결된 노트 확인 중…</p>}{notice.notes&&<p role="status">{notice.notes}</p>}
    {notes&&<><p className="muted">불러온 노트 {notes.items.length}개 · 생성 순서</p>{!notes.items.length&&<p>이 태그에 연결된 활성 노트가 없습니다.</p>}<div className="tag-note-results">{notes.items.map(row=><button type="button" key={row.noteId} aria-label={`태그 노트 열기: ${row.title} · ${row.noteId}`} onClick={()=>onOpen({noteId:row.noteId,title:row.title})}>{row.title}<small>{row.noteId} · v{row.version}</small></button>)}</div>{notes.nextAfter&&<button type="button" disabled={busy.notes} onClick={()=>void loadNotes(selected,true)}>태그 노트 더 보기</button>}</>}
   </section>}
   <p className="muted">사용 중인 노트가 없는 태그도 표시합니다. 개수는 태그 목록 조회 시점 기준이며, 연결 변경 후 새로고침으로 확인할 수 있습니다.</p>
  </>}
 </section>;
}
