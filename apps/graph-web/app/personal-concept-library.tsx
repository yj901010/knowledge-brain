"use client";
import {javaBlank} from "./unicode-text";
import {useEffect,useRef,useState} from "react";
import PersonalConceptEditor from "./personal-concept-editor";
import RetiredConcepts from "./retired-concepts";
import ConceptNotes from "./concept-notes";
import {ConceptRecords} from "./record-concepts";
import ConceptAliases from "./concept-aliases";
import ConceptClaims from "./concept-claims";
import ConceptExternalMappings from "./concept-external-mappings";
import ClaimUsages from "./claim-usages";

type Summary={conceptId:string;title:string;version:number;updatedAt:string};
type Page={items:Summary[];nextAfter:string|null};
type Operation={version:number;controller:AbortController;timer:ReturnType<typeof setTimeout>};
type Created={conceptId:string;spaceId:string;visibility:string;state:string;version:number;title:string;description:string;createdAt:string;updatedAt:string};

function validId(value:unknown):value is string{return typeof value==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)}
function validVersion(value:unknown):value is number{return typeof value==="number"&&Number.isSafeInteger(value)&&value>0}
function validDate(value:unknown){return typeof value==="string"&&Number.isFinite(Date.parse(value))}
function validText(title:unknown,description:unknown):title is string{
 return typeof title==="string"&&!javaBlank(title)&&Array.from(title).length<=200&&!title.includes("\0")&&typeof description==="string"&&!description.includes("\0")&&new TextEncoder().encode(description).length<=32768;
}
async function api(path:string,signal:AbortSignal,options:RequestInit={}){
 return fetch(path,{...options,credentials:"same-origin",cache:"no-store",redirect:"error",signal,headers:{...(options.body?{"Content-Type":"application/json"}:{}),...options.headers}});
}
function readPage(data:Page,cursor:string|null):Page{
 if(!data||!Array.isArray(data.items)||data.items.length>20||data.items.some(row=>!row||!validId(row.conceptId)||!validText(row.title,"")||!validVersion(row.version)||!validDate(row.updatedAt)))throw new Error();
 if(data.nextAfter!==null&&(!validId(data.nextAfter)||data.items.length!==20||data.nextAfter!==data.items[data.items.length-1]?.conceptId||(cursor!==null&&data.nextAfter<=cursor)))throw new Error();
 return {items:data.items.map(row=>({conceptId:row.conceptId,title:row.title,version:row.version,updatedAt:row.updatedAt})),nextAfter:data.nextAfter};
}
async function readCreated(response:Response,spaceId:string):Promise<Created>{
 const etag=response.headers.get("ETag"),data:Created=await response.json();
 if(!data||!validId(data.conceptId)||data.spaceId!==spaceId||data.visibility!=="PRIVATE"||data.state!=="ACTIVE"||!validVersion(data.version)||!etag||!/^"[1-9][0-9]{0,18}"$/.test(etag)||etag!==`"${data.version}"`||!validText(data.title,data.description)||!validDate(data.createdAt)||!validDate(data.updatedAt))throw new Error();
 return data;
}

export default function PersonalConceptLibrary({spaceId,refreshVersion,notesRefreshVersion=0,onOpenNote,onDenied,onChanged}:{spaceId:string;refreshVersion:number;notesRefreshVersion?:number;onOpenNote?:(note:{noteId:string;title:string})=>void;onDenied:()=>void;onChanged:()=>void}){
 const [open,setOpen]=useState(false),[page,setPage]=useState<Page|null>(null),[catalogBusy,setCatalogBusy]=useState(false),[catalogNotice,setCatalogNotice]=useState("");
 const [filter,setFilter]=useState(""),[creating,setCreating]=useState(false),[title,setTitle]=useState(""),[description,setDescription]=useState(""),[createBusy,setCreateBusy]=useState(false),[createNotice,setCreateNotice]=useState("");
 const [selected,setSelected]=useState<string|null>(null),[detailEpoch,setDetailEpoch]=useState(0),[selectedRetired,setSelectedRetired]=useState(false);
 const alive=useRef(true),opened=useRef(false),catalogEpoch=useRef(0),createEpoch=useRef(0),catalogRequest=useRef<Operation|null>(null),createRequest=useRef<Operation|null>(null),retiredIds=useRef(new Set<string>()),path=`/api/spaces/${spaceId}/concepts`;

 function stop(operation:Operation|null){if(operation){clearTimeout(operation.timer);operation.controller.abort()}}
 function invalidateCatalog(){catalogEpoch.current++;stop(catalogRequest.current);catalogRequest.current=null;setCatalogBusy(false)}
 function invalidateCreate(){createEpoch.current++;stop(createRequest.current);createRequest.current=null;setCreateBusy(false)}
 function clearPrivate(){
  invalidateCatalog();invalidateCreate();setPage(null);setFilter("");setCatalogNotice("");setCreating(false);setTitle("");setDescription("");setCreateNotice("");setSelected(null);setSelectedRetired(false);setDetailEpoch(0);retiredIds.current.clear();
 }
 function denied(response:Response){
  if(![401,403,404].includes(response.status))return false;
  opened.current=false;setOpen(false);clearPrivate();onDenied();return true;
 }
 function active(operation:Operation,kind:"catalog"|"create"){
  return alive.current&&opened.current&&!operation.controller.signal.aborted&&operation.version===(kind==="catalog"?catalogEpoch.current:createEpoch.current);
 }
 function operation(version:number):Operation{
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),8000);return {version,controller,timer};
 }
 async function load(append=false){
  if(!opened.current)return;const cursor=append?page?.nextAfter??null:null;if(append&&!cursor)return;
  stop(catalogRequest.current);const request=operation(++catalogEpoch.current);catalogRequest.current=request;setCatalogBusy(true);setCatalogNotice("");if(!append)setPage(null);
  try{
   const query=new URLSearchParams({limit:"20"});if(cursor)query.set("after",cursor);
   const response=await api(`${path}?${query}`,request.controller.signal);
   if(!active(request,"catalog")||denied(response))return;if(!response.ok)throw new Error();
   const data=readPage(await response.json(),cursor);if(!active(request,"catalog"))return;
   setPage(previous=>({nextAfter:data.nextAfter,items:[...new Map([...(append?previous?.items??[]:[]),...data.items].filter(row=>!retiredIds.current.has(row.conceptId)).map(row=>[row.conceptId,row])).values()]}));
  }catch{if(alive.current&&opened.current&&request.version===catalogEpoch.current){setPage(null);setCatalogNotice("개인 개념 보관함을 불러오지 못했습니다. 다시 조회해 주세요.")}}
  finally{clearTimeout(request.timer);if(request.version===catalogEpoch.current){catalogRequest.current=null;setCatalogBusy(false)}}
 }
 useEffect(()=>{
  alive.current=true;return()=>{alive.current=false;opened.current=false;catalogEpoch.current++;createEpoch.current++;stop(catalogRequest.current);stop(createRequest.current);retiredIds.current.clear()};
 },[path]);
 useEffect(()=>{if(open)void load();return()=>{catalogEpoch.current++;stop(catalogRequest.current);catalogRequest.current=null}},[path,refreshVersion,open]);
 function toggle(){const next=!opened.current;clearPrivate();opened.current=next;setOpen(next)}
 function cancelCreate(){if(createRequest.current)return;setCreating(false);setTitle("");setDescription("");setCreateNotice("")}
 async function create(event:React.FormEvent){
  event.preventDefault();if(!opened.current||createRequest.current||!creating)return;
  if(!validText(title,description)){setCreateNotice("제목은 1~200자, 설명은 UTF-8 32 KiB 이내로 입력해 주세요.");return}
  const request=operation(++createEpoch.current);createRequest.current=request;setCreateBusy(true);setCreateNotice("");
  try{
   const response=await api(path,request.controller.signal,{method:"POST",body:JSON.stringify({title,description})});
   if(!active(request,"create")||denied(response))return;
   if(response.status===400||response.status===413){setCreateNotice("입력 형식을 확인해 주세요. 제목과 설명은 유지했습니다.");return}
   if(response.status!==201)throw new Error();const data=await readCreated(response,spaceId);if(!active(request,"create"))return;
   setCreating(false);setTitle("");setDescription("");setCreateNotice("개인 개념을 만들었습니다.");setSelected(data.conceptId);setSelectedRetired(false);setDetailEpoch(previous=>previous+1);invalidateCatalog();onChanged();
  }catch{if(alive.current&&opened.current&&request.version===createEpoch.current)setCreateNotice("개념 생성 결과를 확인하지 못했습니다. 입력은 유지했습니다. 중복 생성 전 보관함을 확인해 주세요.")}
  finally{clearTimeout(request.timer);if(request.version===createEpoch.current){createRequest.current=null;setCreateBusy(false)}}
 }
 const rows=page?.items.filter(row=>row.title.toLocaleLowerCase().includes(filter.toLocaleLowerCase()))??[];
 return <section className="personal-concept-library" aria-label="개인 개념 보관함">
  <h2>내 개인 개념</h2><button type="button" aria-expanded={open} onClick={toggle}>{open?"개인 개념 보관함 닫기":"개인 개념 보관함 열기"}</button>
  {open&&<>
   <p className="muted">노트 선택 없이 개인 개념을 작성하고 살펴봅니다. 새 개념은 이 공간에 비공개로 저장합니다.</p>
   <div className="actions"><button type="button" disabled={catalogBusy} onClick={()=>void load()}>보관함 개념 새로고침</button><button type="button" disabled={creating||createBusy} onClick={()=>{setCreating(true);setCreateNotice("")}}>개인 개념 작성</button></div>
   <label>불러온 보관함 개념 찾기<input value={filter} onChange={event=>setFilter(event.target.value)}/></label>
   {catalogBusy&&<p className="muted">개인 개념 보관함 확인 중…</p>}{catalogNotice&&<p role="status">{catalogNotice}</p>}
   {page&&<>
    <p className="muted">{page.items.length}개 개념 표시 · 생성 순서</p>
    {!page.items.length?<p>저장된 개인 개념이 없습니다.</p>:!rows.length&&<p>불러온 목록에서 일치하는 개념이 없습니다.</p>}
    <div className="concept-library-results">{rows.map(row=><button type="button" key={row.conceptId} aria-label={`보관함 개념 열기: ${row.title}`} onClick={()=>{setSelected(row.conceptId);setSelectedRetired(false);setDetailEpoch(previous=>previous+1)}}>{row.title}</button>)}</div>
    {page.nextAfter&&<button type="button" disabled={catalogBusy} onClick={()=>void load(true)}>보관함 개념 더 보기</button>}
   </>}
   <p className="muted">찾기는 현재 불러온 제목에만 적용합니다. 폐기된 개념은 활성 목록에서 제외합니다.</p>
   <RetiredConcepts key={`retired-concepts:${spaceId}`} spaceId={spaceId} onDenied={()=>{opened.current=false;setOpen(false);clearPrivate();onDenied()}}/>
   {createNotice&&<p role="status">{createNotice}</p>}
   {creating&&<section className="concept-library-create" aria-label="새 개인 개념"><h3>새 개인 개념</h3><form onSubmit={create}>
    <label>새 개인 개념 제목<input required value={title} disabled={createBusy} onChange={event=>setTitle(event.target.value)}/></label>
    <label>새 개인 개념 설명<textarea rows={7} value={description} disabled={createBusy} onChange={event=>setDescription(event.target.value)}/></label>
    <p className="muted">저장하면 별도 개념으로 만듭니다. 노트 연결은 원하는 노트에서 선택할 수 있습니다.</p>
    <div className="actions"><button type="submit" disabled={createBusy||javaBlank(title)}>개인 개념 저장</button><button type="button" disabled={createBusy} onClick={cancelCreate}>개념 작성 취소</button></div>
   </form></section>}
   {selected&&<div className="concept-library-detail"><button type="button" onClick={()=>setSelected(null)}>보관함 개념 상세 닫기</button>
    <PersonalConceptEditor key={`library:${spaceId}:${selected}:${detailEpoch}`} spaceId={spaceId} conceptId={selected} onDenied={()=>{opened.current=false;setOpen(false);clearPrivate();onDenied()}}
     onSaved={concept=>{invalidateCatalog();setPage(previous=>previous?{...previous,items:previous.items.map(row=>row.conceptId===concept.conceptId?{...row,title:concept.title}:row)}:null);onChanged()}}
     onRetired={id=>{retiredIds.current.add(id);if(id===selected)setSelectedRetired(true);invalidateCatalog();setPage(previous=>previous?{...previous,items:previous.items.filter(row=>row.conceptId!==id)}:null);onChanged()}}/>
    <ConceptAliases key={`concept-aliases:${spaceId}:${selected}`} spaceId={spaceId} conceptId={selected} parentRetired={selectedRetired} onDenied={()=>{opened.current=false;setOpen(false);clearPrivate();onDenied()}}/>
    <ConceptExternalMappings key={`concept-external-mappings:${spaceId}:${selected}`} spaceId={spaceId} conceptId={selected} parentRetired={selectedRetired} onDenied={()=>{opened.current=false;setOpen(false);clearPrivate();onDenied()}}/>
    <ConceptClaims key={`concept-claims:${spaceId}:${selected}`} spaceId={spaceId} conceptId={selected} parentRetired={selectedRetired} onDenied={()=>{opened.current=false;setOpen(false);clearPrivate();onDenied()}}/>
    <ClaimUsages key={`claim-usages:${spaceId}:${selected}`} spaceId={spaceId} conceptId={selected} parentRetired={selectedRetired} onDenied={()=>{opened.current=false;setOpen(false);clearPrivate();onDenied()}}/>
    <ConceptNotes key={`concept-notes:${spaceId}:${selected}`} spaceId={spaceId} conceptId={selected} refreshVersion={notesRefreshVersion} onOpenNote={onOpenNote} onDenied={()=>{opened.current=false;setOpen(false);clearPrivate();onDenied()}}/>
    <ConceptRecords key={`concept-records:${spaceId}:${selected}`} spaceId={spaceId} conceptId={selected} onDenied={()=>{opened.current=false;setOpen(false);clearPrivate();onDenied()}}/>
   </div>}
  </>}
 </section>;
}
