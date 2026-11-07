"use client";
import {useEffect,useRef,useState} from "react";

type Note={noteId:string;title:string};
type Page={items:Note[];nextAfter:string|null};
type View={scope:string;page:Page|null;busy:boolean;notice:string};
type Operation={version:number;scope:string;controller:AbortController;timer:ReturnType<typeof setTimeout>};

function validId(value:unknown):value is string{return typeof value==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)}
function validTitle(value:unknown):value is string{return typeof value==="string"&&!!value.trim()&&Array.from(value).length<=200&&!value.includes("\0")}
function readPage(value:unknown,cursor:string|null):Page{
 if(!value||typeof value!=="object"||Array.isArray(value))throw new Error();
 const data=value as Page;
 if(!Array.isArray(data.items)||data.items.length>20||data.items.some((row,index)=>!row||!validId(row.noteId)||!validTitle(row.title)||(cursor!==null&&row.noteId<=cursor)||(index>0&&row.noteId<=data.items[index-1].noteId)))throw new Error();
 if(data.nextAfter!==null&&(!validId(data.nextAfter)||data.items.length!==20||data.nextAfter!==data.items[data.items.length-1]?.noteId||(cursor!==null&&data.nextAfter<=cursor)))throw new Error();
 return {items:data.items.map(row=>({noteId:row.noteId,title:row.title})),nextAfter:data.nextAfter};
}

export default function ConceptNotes({spaceId,conceptId,refreshVersion=0,onOpenNote,onDenied}:{spaceId:string;conceptId:string;refreshVersion?:number;onOpenNote?:(note:Note)=>void;onDenied:()=>void}){
 const path=`/api/spaces/${spaceId}/concept-references/${conceptId}/notes`,scope=`${path}:${refreshVersion}`;
 const [view,setView]=useState<View>({scope,page:null,busy:false,notice:""});
 const alive=useRef(true),epoch=useRef(0),request=useRef<Operation|null>(null),currentScope=useRef(scope);currentScope.current=scope;
 function stop(operation:Operation|null){if(operation){clearTimeout(operation.timer);operation.controller.abort()}}
 function active(operation:Operation){return alive.current&&currentScope.current===operation.scope&&operation.version===epoch.current&&!operation.controller.signal.aborted}
 async function load(append=false){
  const cursor=append&&view.scope===scope?view.page?.nextAfter??null:null;if(append&&!cursor)return;
  stop(request.current);const controller=new AbortController(),operation={version:++epoch.current,scope,controller,timer:setTimeout(()=>controller.abort(),8000)};request.current=operation;
  setView(previous=>({scope,page:append&&previous.scope===scope?previous.page:null,busy:true,notice:""}));
  try{
   const query=new URLSearchParams({limit:"20"});if(cursor)query.set("after",cursor);
   const response=await fetch(`${path}?${query}`,{credentials:"same-origin",cache:"no-store",redirect:"error",signal:controller.signal});
   if(!active(operation))return;
   if([401,403,404].includes(response.status)){setView({scope,page:null,busy:false,notice:""});onDenied();return}
   if(!response.ok)throw new Error();
   const page=readPage(await response.json(),cursor);if(!active(operation))return;
   setView(previous=>({scope,busy:false,notice:"",page:{nextAfter:page.nextAfter,items:[...new Map([...(append&&previous.scope===scope?previous.page?.items??[]:[]),...page.items].map(note=>[note.noteId,note])).values()]}}));
  }catch{if(alive.current&&currentScope.current===scope&&operation.version===epoch.current)setView({scope,page:null,busy:false,notice:"연결된 노트를 불러오지 못했습니다. 다시 조회해 주세요."})}
  finally{clearTimeout(operation.timer);if(alive.current&&currentScope.current===scope&&operation.version===epoch.current){request.current=null;setView(previous=>previous.scope===scope?{...previous,busy:false}:previous)}}
 }
 useEffect(()=>{alive.current=true;void load();return()=>{alive.current=false;epoch.current++;stop(request.current);request.current=null}},[path,refreshVersion]);
 const visible=view.scope===scope?view:{scope,page:null,busy:true,notice:""};
 return <section className="concept-notes" aria-label="개념에 연결된 노트"><h3>이 개념을 사용한 노트</h3>
  <p className="muted">이 공간의 활성 노트에 저장된 개념 연결을 확인합니다. 폐기된 개념의 기존 노트 연결도 확인할 수 있습니다.</p>
  <button type="button" disabled={visible.busy} onClick={()=>void load()}>연결된 노트 새로고침</button>
  {visible.busy&&<p className="muted">연결된 노트 확인 중…</p>}{visible.notice&&<p role="status">{visible.notice}</p>}
  {visible.page&&<><p className="muted">불러온 연결 노트 {visible.page.items.length}개 · 생성 순서</p>
   {!visible.page.items.length&&<p>이 개념에 연결된 활성 노트가 없습니다.</p>}
   <div className="concept-note-results">{visible.page.items.map(note=><button type="button" key={note.noteId} aria-label={`개념 연결 노트 열기: ${note.title}`} disabled={!onOpenNote} onClick={()=>onOpenNote?.(note)}>{note.title}</button>)}</div>
   {visible.page.nextAfter&&<button type="button" disabled={visible.busy} onClick={()=>void load(true)}>연결된 노트 더 보기</button>}
  </>}
  <p className="muted">표시 개수는 현재 불러온 노트의 개수입니다. 조회 중 연결이나 제목이 바뀔 수 있습니다.</p>
 </section>;
}
