"use client";
import {useEffect,useRef,useState} from "react";
type Link={linkId:string;targetToken:string;label:string|null;fragment:string|null;targetNoteId:string|null;targetTitle:string|null;status:string};
type Back={sourceNoteId:string;title:string;linkCount:number};
type Links={noteVersion:number;linkVersion:number;items:Link[];nextAfter:number|null};
type Backs={items:Back[];nextAfter:string|null};
const statuses:Record<string,string>={UNRESOLVED:"대상 없음",AMBIGUOUS:"제목 중복",TARGET_RETIRED:"폐기된 대상",RESOLVED:"연결됨"};
export default function NoteLinks({spaceId,noteId,onOpen,onDenied}:{spaceId:string;noteId:string;onOpen:(note:{noteId:string;title:string})=>void;onDenied:()=>void}){
 const [links,setLinks]=useState<Links|null>(null),[backs,setBacks]=useState<Backs|null>(null),[busy,setBusy]=useState(false),[notice,setNotice]=useState("");const epoch=useRef(0),request=useRef<AbortController|null>(null),path=`/api/spaces/${spaceId}/notes/${noteId}`;
 async function load(kind:"links"|"backlinks"|"both"="both"){
  request.current?.abort();const version=++epoch.current,controller=new AbortController();request.current=controller;const timer=setTimeout(()=>controller.abort(),8000);setBusy(true);setNotice("");
  if(kind==="both"){setLinks(null);setBacks(null)}
  try{
   async function read(endpoint:string,after:string|number|null){const response=await fetch(`${path}/${endpoint}?limit=20${after!==null?`&after=${encodeURIComponent(after)}`:""}`,{credentials:"same-origin",cache:"no-store",redirect:"error",signal:controller.signal});if([401,403,404].includes(response.status))throw new Error("denied");if(!response.ok)throw new Error();return response.json()}
   const [outgoing,incoming]=await Promise.all([kind!=="backlinks"?read("links",kind==="links"?links?.nextAfter??null:null):null,kind!=="links"?read("backlinks",kind==="backlinks"?backs?.nextAfter??null:null):null]);
   if(version!==epoch.current||controller.signal.aborted)return;
   if(outgoing){if(kind==="links"&&links&&outgoing.linkVersion!==links.linkVersion)throw new Error("changed");setLinks(kind==="links"&&links?{...outgoing,items:[...links.items,...outgoing.items]}:outgoing)}
   if(incoming)setBacks(kind==="backlinks"&&backs?{...incoming,items:[...new Map([...backs.items,...incoming.items].map((b:Back)=>[b.sourceNoteId,b])).values()]}:incoming);
  }catch(error){if(version!==epoch.current)return;setLinks(null);setBacks(null);if(error instanceof Error&&error.message==="denied")onDenied();else setNotice(error instanceof Error&&error.message==="changed"?"링크가 변경됐습니다. 연결 새로고침으로 다시 확인해 주세요.":"연결을 확인할 수 없습니다. 다시 새로고침해 주세요.")}
  finally{clearTimeout(timer);if(version===epoch.current)setBusy(false)}
 }
 useEffect(()=>{void load();return()=>{epoch.current++;request.current?.abort()}},[path]);
 return <section className="note-links" aria-label="Wiki Link와 Backlink"><h3>Wiki Link · Backlink</h3><button disabled={busy} onClick={()=>void load()}>연결 새로고침</button>{notice&&<p role="status">{notice}</p>}{busy&&<p className="muted">연결 확인 중…</p>}
 <h4>나가는 링크</h4>{links&&<><p className="muted">노트 v{links.noteVersion} · 링크 v{links.linkVersion}</p>{!links.items.length&&<p>나가는 링크가 없습니다.</p>}{links.items.map(link=><div key={link.linkId} className="wiki-row">{link.status==="RESOLVED"&&link.targetNoteId?<button className="note-row" onClick={()=>onOpen({noteId:link.targetNoteId!,title:link.targetTitle??link.targetToken})}>{link.label??link.targetTitle??link.targetToken}</button>:<span>{link.label??link.targetToken}</span>}<small>{statuses[link.status]??link.status}{link.fragment?` · #${link.fragment}`:""}</small></div>)}{links.nextAfter!==null&&<button disabled={busy} onClick={()=>void load("links")}>나가는 링크 더 보기</button>}</>}
 <h4>들어오는 링크 (Backlink)</h4>{backs&&<>{!backs.items.length&&<p>들어오는 링크가 없습니다.</p>}{backs.items.map(back=><div key={back.sourceNoteId} className="wiki-row"><button className="note-row" onClick={()=>onOpen({noteId:back.sourceNoteId,title:back.title})}>{back.title}</button><small>{back.linkCount}회 참조</small></div>)}{backs.nextAfter!==null&&<button disabled={busy} onClick={()=>void load("backlinks")}>Backlink 더 보기</button>}</>}<p className="muted">같은 개인 공간의 연결만 표시합니다. 중복 참조는 나가는 링크에 각각 표시됩니다. 읽는 동안 변경될 수 있습니다.</p></section>;
}
