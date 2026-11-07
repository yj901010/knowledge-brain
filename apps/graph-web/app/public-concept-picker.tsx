"use client";
import {useEffect,useRef,useState} from "react";

type Concept={conceptId:string;title:string};
type Space={spaceId:string;title:string;domain:string};
async function api(path:string,signal:AbortSignal){return fetch(path,{credentials:"same-origin",cache:"no-store",redirect:"error",signal})}

export default function PublicConceptPicker({privateIds,linkedIds,busy,onToggle}:{privateIds:string[];linkedIds:string[];busy:boolean;onToggle:(id:string)=>Promise<void>}){
 const [spaces,setSpaces]=useState<Space[]>([]),[space,setSpace]=useState(""),[items,setItems]=useState<Concept[]>([]),[known,setKnown]=useState<Record<string,Concept>>({}),[after,setAfter]=useState<string|null>(null),[filter,setFilter]=useState(""),[loading,setLoading]=useState(false),[notice,setNotice]=useState("");
 const epoch=useRef(0),request=useRef<AbortController|null>(null);
 // Resolve retained IDs through public APIs only; a private ID cannot reveal another Space's text.
 const unresolved=linkedIds.filter(id=>!privateIds.includes(id)).join(",");
 useEffect(()=>{const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),10000);
  void (async()=>{const response=await api("/api/spaces/public",controller.signal);if(!response.ok)throw new Error();const catalog:Space[]=(await response.json()).items;if(controller.signal.aborted)return;setSpaces(catalog);setSpace(current=>current||catalog.find(s=>s.domain==="DEVELOPMENT")?.spaceId||catalog[0]?.spaceId||"");
   const resolved:Record<string,Concept>={};await Promise.all(unresolved.split(",").filter(Boolean).map(async id=>{for(const s of catalog){const r=await api(`/api/public/spaces/${s.spaceId}/concepts/${id}`,controller.signal);if(r.status===404)continue;if(!r.ok)throw new Error();const c:Concept=await r.json();resolved[id]={conceptId:c.conceptId,title:c.title};break}}));if(!controller.signal.aborted)setKnown(resolved);
  })().catch(()=>{if(!controller.signal.aborted){setKnown({});setNotice("공개 개념을 확인하지 못했습니다. 기존 연결은 유지됩니다.")}}).finally(()=>clearTimeout(timer));
  return()=>{controller.abort();clearTimeout(timer)};
 },[unresolved]);
 async function load(target:string,cursor:string|null,append:boolean){request.current?.abort();const controller=new AbortController();request.current=controller;const version=++epoch.current;const timer=setTimeout(()=>controller.abort(),10000);setLoading(true);setNotice("");
  try{const response=await api(`/api/public/spaces/${target}/concepts?limit=20${cursor?`&after=${cursor}`:""}`,controller.signal);if(!response.ok)throw new Error();const page:{items:Concept[];nextAfter:string|null}=await response.json();if(version!==epoch.current||controller.signal.aborted)return;setItems(current=>append?[...current,...page.items]:page.items);setAfter(page.nextAfter)}
  catch{if(version===epoch.current){setItems([]);setAfter(null);setNotice("공개 목록을 불러오지 못했습니다. 다시 시도해 주세요.")}}
  finally{clearTimeout(timer);if(version===epoch.current)setLoading(false)}
 }
 useEffect(()=>{setItems([]);setAfter(null);setFilter("");if(space)void load(space,null,false);return()=>{epoch.current++;request.current?.abort()}},[space]);
 const retained=linkedIds.filter(id=>!privateIds.includes(id)&&!items.some(c=>c.conceptId===id));
 return <section aria-label="공개 개념 연결"><h3>공개 개념 연결</h3><p className="muted">공개 개념을 복제하지 않고 참조합니다. 최대 20개 개념을 연결할 수 있습니다.</p>
  <label>연결할 공개 분야<select aria-label="연결할 공개 분야" value={space} disabled={busy} onChange={e=>setSpace(e.target.value)}>{spaces.map(s=><option key={s.spaceId} value={s.spaceId}>{s.title}</option>)}</select></label>
  <label>불러온 공개 개념 찾기<input value={filter} onChange={e=>setFilter(e.target.value)} placeholder="목록의 제목으로 필터"/></label>
  {notice&&<p role="status">{notice}</p>}
  <div className="concept-list">{items.filter(c=>c.title.toLocaleLowerCase().includes(filter.toLocaleLowerCase())).map(c=><label key={c.conceptId}><input type="checkbox" aria-label={`공개 개념 연결: ${c.title}`} checked={linkedIds.includes(c.conceptId)} disabled={busy||!linkedIds.includes(c.conceptId)&&linkedIds.length>=20} onChange={()=>void onToggle(c.conceptId)}/>{c.title}</label>)}</div>
  {!items.length&&!loading&&!notice&&<p className="muted">아직 병합된 공개 개념이 없습니다.</p>}
  {after&&<button disabled={busy||loading} onClick={()=>void load(space,after,true)}>공개 개념 더 보기</button>}
  <button disabled={busy||loading||!space} onClick={()=>void load(space,null,false)}>{loading?"공개 목록 불러오는 중…":"공개 목록 새로고침"}</button>
  {retained.map(id=><button key={id} disabled={busy} onClick={()=>void onToggle(id)}>{known[id]?.title??`목록 밖 개념 ${id.slice(-6)}`} · 연결 해제</button>)}
 </section>;
}
