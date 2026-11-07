"use client";
import {utf16MaxLength} from "./unicode-text";
import {useEffect,useRef,useState} from "react";

type Note={noteId:string;title:string};
type Page={total:number;offset:number;limit:number;nextOffset:number|null;items:Note[]};

export default function NoteSearch({spaceId,onOpen,onDenied}:{spaceId:string;onOpen:(note:Note)=>void;onDenied:()=>void}){
 const [query,setQuery]=useState(""),[submitted,setSubmitted]=useState(""),[result,setResult]=useState<Page|null>(null),[busy,setBusy]=useState(false),[notice,setNotice]=useState("");
 const epoch=useRef(0),request=useRef<AbortController|null>(null);
 function clear(){epoch.current++;request.current?.abort();setQuery("");setSubmitted("");setResult(null);setNotice("");setBusy(false)}
 useEffect(()=>()=>{epoch.current++;request.current?.abort()},[spaceId]);
 async function search(q:string,offset=0,append=false){request.current?.abort();const version=++epoch.current;const text=q.trim();if(!text||Array.from(text).length>200||/[\u0000-\u001f\u007f-\u009f]/u.test(text)){setBusy(false);setResult(null);setNotice("검색어는 제어문자 없이 1~200자로 입력해 주세요.");return}
  const controller=new AbortController();request.current=controller;const timer=setTimeout(()=>controller.abort(),8000);setBusy(true);setNotice("");setSubmitted(text);if(!append)setResult(null);
  try{const response=await fetch(`/api/spaces/${spaceId}/search/notes?${new URLSearchParams({q:text,offset:String(offset),limit:"20"})}`,{credentials:"same-origin",cache:"no-store",redirect:"error",signal:controller.signal});if(version!==epoch.current||controller.signal.aborted)return;
   if([401,403,404].includes(response.status)){setResult(null);onDenied();return}if(!response.ok)throw new Error();const data:Page=await response.json();if(version!==epoch.current||controller.signal.aborted)return;
   setResult(previous=>append&&previous?{...data,items:[...new Map([...previous.items,...data.items].map(note=>[note.noteId,note])).values()]}:data);
  }catch{if(version===epoch.current){setResult(null);setNotice("검색을 확인할 수 없습니다. 잠시 후 다시 검색해 주세요.")}}
  finally{clearTimeout(timer);if(version===epoch.current)setBusy(false)}
 }
 return <section className="note-search" aria-label="개인 노트 검색"><h2>내 노트 검색</h2><form onSubmit={e=>{e.preventDefault();void search(query)}}><label>제목·본문 검색<input type="search" value={query} maxLength={utf16MaxLength(200)} onChange={e=>setQuery(e.target.value)} placeholder="노트 제목이나 본문의 단어"/></label><button className="primary" disabled={busy||!query.trim()}>{busy?"검색 중…":"검색"}</button><button type="button" onClick={clear}>검색 지우기</button></form><p className="muted">선택한 개인 공간의 제목·본문을 검색합니다. 저장 후 검색 반영까지 잠시 걸릴 수 있습니다.</p>
  {notice&&<p role="status">{notice}</p>}
  {result&&<><p aria-live="polite">‘{submitted}’ · 총 {result.total}개 · {result.items.length}개 표시</p>{!result.items.length&&<p>일치하는 노트가 없습니다.</p>}<div className="search-results">{result.items.map(note=><button key={note.noteId} className="note-row" onClick={()=>onOpen(note)}>{note.title}</button>)}</div>{result.nextOffset!==null?<button disabled={busy} onClick={()=>void search(submitted,result.nextOffset!,true)}>검색 결과 더 보기</button>:result.total>result.items.length&&<p className="muted">표시 가능한 검색 범위가 끝났습니다. 더 구체적인 검색어로 찾아보세요.</p>}<p className="muted">검색 중 노트가 수정되면 결과 순서가 바뀔 수 있습니다.</p></>}
 </section>;
}
