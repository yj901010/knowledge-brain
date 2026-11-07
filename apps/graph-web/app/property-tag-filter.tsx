"use client";
import MetadataNameSearch from "./metadata-name-search";
import {useEffect,useRef,useState} from "react";

export type PropertyTag={id:string;name:string};
type Tag=PropertyTag&{type:"TAG";activeNoteCount:number};
type Page={items:Tag[];nextAfter:string|null};
const id=(v:unknown):v is string=>typeof v==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);
function read(raw:unknown,after:string|null):Page{
 const page=raw as Page;if(!page||!Array.isArray(page.items)||page.items.length>20)throw new Error();let last=after;
 for(const row of page.items){if(!row||!id(row.id)||(last!==null&&row.id<=last)||row.type!=="TAG"||typeof row.name!=="string"||Array.from(row.name).length<1||Array.from(row.name).length>64||/[\u0000-\u001f\u007f-\u009f]/.test(row.name)||!Number.isSafeInteger(row.activeNoteCount)||row.activeNoteCount<0)throw new Error();last=row.id}
 if(page.nextAfter!==null&&(!id(page.nextAfter)||page.items.length!==20||page.nextAfter!==last))throw new Error();return page;
}

export default function PropertyTagFilter({spaceId,applied,onApply,onDenied}:{spaceId:string;applied:PropertyTag|null;onApply:(tag:PropertyTag|null)=>void;onDenied:()=>void}){
 const [open,setOpen]=useState(false),[page,setPage]=useState<Page|null>(null),[draft,setDraft]=useState<PropertyTag|null>(null),[busy,setBusy]=useState(false),[notice,setNotice]=useState("");
 const [nameQuery,setNameQuery]=useState<string|null>(null);const nameQueryRef=useRef<string|null>(null);
 const alive=useRef(true),opened=useRef(false),epoch=useRef(0),operation=useRef<{controller:AbortController;timer:ReturnType<typeof setTimeout>}|null>(null);
 function stop(){epoch.current++;if(operation.current){operation.current.controller.abort();clearTimeout(operation.current.timer)}operation.current=null}
 function close(){nameQueryRef.current=null;setNameQuery(null);opened.current=false;stop();setOpen(false);setPage(null);setDraft(null);setBusy(false);setNotice("")}
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;opened.current=false;stop()}},[spaceId]);
 async function load(append=false,q=nameQueryRef.current){
  const after=append?page?.nextAfter??null:null;if(append&&!after)return;stop();const current=epoch.current,controller=new AbortController(),timer=setTimeout(()=>controller.abort(),8000);operation.current={controller,timer};setBusy(true);setNotice("");if(!append){setPage(null);setDraft(null)}
  const relevant=()=>alive.current&&opened.current&&epoch.current===current;
  try{const response=await fetch(`/api/spaces/${spaceId}/tags?${new URLSearchParams({limit:"20",...(after?{after}:{}),...(q===null?{}:{q})})}`,{credentials:"same-origin",cache:"no-store",redirect:"error",signal:controller.signal});if(!relevant()||controller.signal.aborted)return;if([401,403,404].includes(response.status)){close();onDenied();return}if(!response.ok)throw new Error();const result=read(await response.json(),after);if(q!==null&&result.items.some(row=>!row.name.includes(q)))throw new Error();if(!relevant()||controller.signal.aborted)return;setPage(prior=>({items:[...(append?prior?.items??[]:[]),...result.items],nextAfter:result.nextAfter}))}
  catch{if(relevant()){setPage(null);setDraft(null);setNotice("태그 조건 목록을 불러오지 못했습니다. 새로고침으로 다시 조회해 주세요.")}}
  finally{clearTimeout(timer);if(relevant()){operation.current=null;setBusy(false)}}
 }
 return <section className="property-tag-filter" aria-label="속성의 태그 조건"><p><strong>태그도 함께 적용</strong></p>
  <p className="property-applied-tag">적용된 태그: {applied?.name??"모든 태그"}</p>
  <p className="muted">선택한 속성과 현재 적용된 값 조건을 만족하면서 이 태그가 있는 노트를 찾습니다.</p>
  <div className="actions">{!open?<button type="button" onClick={()=>{opened.current=true;setOpen(true);void load()}}>태그 조건 고르기</button>:<><button type="button" onClick={close}>태그 조건 목록 닫기</button><button type="button" disabled={busy} onClick={()=>void load()}>태그 조건 목록 새로고침</button></>}<button type="button" onClick={()=>{setDraft(null);onApply(null)}}>태그 조건 해제</button></div>
  {open&&<><MetadataNameSearch label="조건 태그" applied={nameQuery} onApply={q=>{nameQueryRef.current=q;setNameQuery(q);void load(false,q)}}/>{busy&&<p>태그 조건 확인 중…</p>}{notice&&<p role="status">{notice}</p>}{page&&<><p className="muted">불러온 태그 {page.items.length}개 · 개수는 조건 적용 전 전체 활성 노트 기준입니다.</p>{!page.items.length&&<p>{nameQuery===null?"아직 태그가 없습니다. 노트의 태그·속성에서 추가할 수 있습니다.":"이름 검색에 맞는 태그가 없습니다."}</p>}<div className="tag-results">{page.items.map(tag=><button type="button" key={tag.id} aria-pressed={draft?.id===tag.id} aria-label={`태그 조건 선택: ${tag.name}`} onClick={()=>setDraft(tag)}>{tag.name}<small>활성 노트 {tag.activeNoteCount}개</small></button>)}</div>{page.nextAfter&&<button type="button" disabled={busy} onClick={()=>void load(true)}>태그 조건 더 보기</button>}</>}<p>선택한 태그: {draft?.name??"없음"}</p><button type="button" disabled={!draft} onClick={()=>{if(draft)onApply(draft)}}>태그 조건 적용</button></>}
 </section>;
}
