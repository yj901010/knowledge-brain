"use client";
import MetadataNameSearch from "./metadata-name-search";
import {useEffect,useRef,useState} from "react";
import PropertyTagFilter,{PropertyTag} from "./property-tag-filter";
import {validNoteText} from "./note-history";
import {filterInput,rangeInput,matchesCondition,conditionText,PropertyCondition} from "./property-filter";
import {decimalJson} from "./metadata-values";
import {value as typedValue} from "./activity-metadata";

type Definition={id:string;name:string;type:string;activeNoteCount:number};
type Note={noteId:string;title:string;version:number;metadataVersion:number;updatedAt:string;value:unknown};
type Page<T>={items:T[];nextAfter:string|null};
type Lane="definitions"|"notes";
type Operation={epoch:number;scope:string;controller:AbortController;timer:ReturnType<typeof setTimeout>};
const types:Record<string,string>={STRING:"문자열",NUMBER:"숫자",BOOLEAN:"참/거짓",DATE:"날짜",STRING_LIST:"문자열 목록"};
const id=(v:unknown):v is string=>typeof v==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);
const count=(v:unknown):v is number=>typeof v==="number"&&Number.isSafeInteger(v)&&v>=0;
const name=(v:unknown):v is string=>typeof v==="string"&&Array.from(v).length>0&&Array.from(v).length<=64&&!/[\u0000-\u001f\u007f-\u009f]/.test(v);
function readPage<T>(raw:unknown,after:string|null,key:(r:T)=>unknown,valid:(r:T)=>boolean):Page<T>{
 const p=raw as Page<T>;if(!p||!Array.isArray(p.items)||p.items.length>20)throw new Error();let last=after;
 for(const row of p.items){if(!row||!valid(row))throw new Error();const value=key(row);if(!id(value)||(last!==null&&value<=last))throw new Error();last=value}
 if(p.nextAfter!==null&&(!id(p.nextAfter)||p.items.length!==20||p.nextAfter!==last))throw new Error();return p;
}
function readDefinitions(raw:unknown,after:string|null){return readPage<Definition>(raw,after,r=>r.id,r=>name(r.name)&&Object.hasOwn(types,r.type)&&count(r.activeNoteCount))}
function readNotes(raw:string,definition:Definition,after:string|null,condition:PropertyCondition|null,tag:PropertyTag|null):Page<Note>{
 if(raw.length>2097152)throw new Error();const plain=JSON.parse(raw),exact=decimalJson(raw) as {items:Note[]};
 if((tag?plain?.tagId!==tag.id:plain?.tagId!=null))throw new Error();
 if(!plain||plain.propertyId!==definition.id||plain.name!==definition.name||plain.type!==definition.type)throw new Error();
 const page=readPage<Note>(plain,after,r=>r.noteId,r=>validNoteText(r.title,"")&&count(r.version)&&r.version>0&&count(r.metadataVersion)&&r.metadataVersion>0&&typeof r.updatedAt==="string"&&Number.isFinite(Date.parse(r.updatedAt)));
 return {...page,items:page.items.map((row,index)=>{
  if(definition.type==="NUMBER"&&typeof row.value!=="number")throw new Error();
  const value=typedValue(definition.type,definition.type==="NUMBER"?exact.items[index].value:JSON.stringify(row.value));if(condition!==null&&!matchesCondition(definition.type,value,condition))throw new Error();return {...row,value};
 })};
}
function PropertyValue({type,value}:{type:string;value:unknown}){
 if(Array.isArray(value))return value.length?<ul>{value.map((v,i)=><li key={i}>{v}</li>)}</ul>:<span className="muted">(빈 목록)</span>;
 if(type==="BOOLEAN")return <>{value?"참":"거짓"}</>;
 return value===""?<span className="muted">(빈 문자열)</span>:<>{String(value)}</>;
}

export default function PropertyNotes({spaceId,onOpen,onDenied}:{spaceId:string;onOpen:(note:{noteId:string;title:string})=>void;onDenied:()=>void}){
 const path=`/api/spaces/${spaceId}/property-definitions`;
 const [open,setOpen]=useState(false),[definitions,setDefinitions]=useState<Page<Definition>|null>(null),[selected,setSelected]=useState<Definition|null>(null),[notes,setNotes]=useState<Page<Note>|null>(null);
 const [condition,setCondition]=useState<PropertyCondition|null>(null),[draft,setDraft]=useState(""),[filterError,setFilterError]=useState("");const conditionRef=useRef<PropertyCondition|null>(null);
 const [appliedTag,setAppliedTag]=useState<PropertyTag|null>(null);const tagRef=useRef<PropertyTag|null>(null);
 const [mode,setMode]=useState<"exact"|"range">("exact"),[minDraft,setMinDraft]=useState(""),[maxDraft,setMaxDraft]=useState("");
 const [nameQuery,setNameQuery]=useState<string|null>(null);const nameQueryRef=useRef<string|null>(null);
 const [busy,setBusy]=useState({definitions:false,notes:false}),[notice,setNotice]=useState({definitions:"",notes:""});
 const alive=useRef(true),opened=useRef(false),scope=useRef(path),selectedId=useRef<string|null>(null),epochs=useRef({definitions:0,notes:0}),requests=useRef<Record<Lane,Operation|null>>({definitions:null,notes:null});scope.current=path;
 function stop(lane:Lane){epochs.current[lane]++;const op=requests.current[lane];if(op){clearTimeout(op.timer);op.controller.abort()}requests.current[lane]=null}
 function working(lane:Lane,value:boolean){setBusy(prior=>({...prior,[lane]:value}))}
 function message(lane:Lane,value:string){setNotice(prior=>({...prior,[lane]:value}))}
 function start(lane:Lane){stop(lane);const controller=new AbortController(),op={epoch:epochs.current[lane],scope:path,controller,timer:setTimeout(()=>controller.abort(),8000)};requests.current[lane]=op;working(lane,true);message(lane,"");return op}
 function relevant(lane:Lane,op:Operation){return alive.current&&opened.current&&scope.current===op.scope&&epochs.current[lane]===op.epoch}
 function active(lane:Lane,op:Operation){return relevant(lane,op)&&!op.controller.signal.aborted}
 function finish(lane:Lane,op:Operation){clearTimeout(op.timer);if(relevant(lane,op)){requests.current[lane]=null;working(lane,false)}}
 function closeNotes(){stop("notes");tagRef.current=null;setAppliedTag(null);conditionRef.current=null;setCondition(null);setDraft("");setMode("exact");setMinDraft("");setMaxDraft("");setFilterError("");selectedId.current=null;setSelected(null);setNotes(null);working("notes",false);message("notes","")}
 function close(){nameQueryRef.current=null;setNameQuery(null);opened.current=false;stop("definitions");closeNotes();setOpen(false);setDefinitions(null);working("definitions",false);message("definitions","")}
 function denied(response:Response){if(![401,403,404].includes(response.status))return false;close();onDenied();return true}
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;opened.current=false;stop("definitions");stop("notes")}},[path]);
 function fetchRead(url:string,op:Operation){return fetch(url,{credentials:"same-origin",cache:"no-store",redirect:"error",signal:op.controller.signal})}
 async function loadDefinitions(append=false,q=nameQueryRef.current){
  const after=append?definitions?.nextAfter??null:null;if(append&&!after)return;const op=start("definitions");if(!append){setDefinitions(null);closeNotes()}
  try{const response=await fetchRead(`${path}?${new URLSearchParams({limit:"20",...(after?{after}:{}),...(q===null?{}:{q})})}`,op);if(!active("definitions",op)||denied(response))return;if(!response.ok)throw new Error();const result=readDefinitions(await response.json(),after);if(q!==null&&result.items.some(row=>!row.name.includes(q)))throw new Error();if(!active("definitions",op))return;setDefinitions(prior=>({items:[...(append?prior?.items??[]:[]),...result.items],nextAfter:result.nextAfter}))}
  catch{if(relevant("definitions",op)){setDefinitions(null);closeNotes();message("definitions","속성을 불러오지 못했습니다. 새로고침으로 다시 조회해 주세요.")}}finally{finish("definitions",op)}
 }
 async function loadNotes(selectedDefinition:Definition,append=false,filter=conditionRef.current,tag=tagRef.current){
  const after=append?notes?.nextAfter??null:null;if(append&&!after)return;const op=start("notes");if(!append)setNotes(null);
  try{const query=new URLSearchParams({limit:"20"});if(tag)query.set("tagId",tag.id);if(after)query.set("after",after);if(filter?.kind==="exact")query.set("value",filter.value);else if(filter?.kind==="range"){if(filter.min!==null)query.set("minValue",filter.min);if(filter.max!==null)query.set("maxValue",filter.max);}const response=await fetchRead(`${path}/${selectedDefinition.id}/notes?${query}`,op);if(!active("notes",op)||selectedId.current!==selectedDefinition.id||denied(response))return;if(!response.ok)throw new Error();const result=readNotes(await response.text(),selectedDefinition,after,filter,tag);if(!active("notes",op)||selectedId.current!==selectedDefinition.id)return;setNotes(prior=>({items:[...(append?prior?.items??[]:[]),...result.items],nextAfter:result.nextAfter}))}
  catch{if(relevant("notes",op)){setNotes(null);message("notes","속성별 노트를 불러오지 못했습니다. 새로고침으로 다시 조회해 주세요.")}}finally{finish("notes",op)}
 }
 function select(value:Definition){closeNotes();selectedId.current=value.id;setSelected(value);setDraft(value.type==="BOOLEAN"?"false":"");void loadNotes(value)}
 function applyCondition(event:React.FormEvent){event.preventDefault();if(!selected)return;try{const value:PropertyCondition=mode==="range"?rangeInput(selected.type,minDraft,maxDraft):{kind:"exact",value:filterInput(selected.type,draft)};setFilterError("");conditionRef.current=value;setCondition(value);void loadNotes(selected,false,value)}catch(error){setFilterError(error instanceof Error?error.message:"조건을 확인해 주세요.")}}
 function applyTag(tag:PropertyTag|null){if(!selected)return;tagRef.current=tag;setAppliedTag(tag);void loadNotes(selected,false,conditionRef.current,tag)}
 function clearCondition(){if(!selected)return;conditionRef.current=null;setCondition(null);setDraft(selected.type==="BOOLEAN"?"false":"");setMode("exact");setMinDraft("");setMaxDraft("");setFilterError("");void loadNotes(selected,false,null)}
 return <section className="property-notes" aria-label="속성별 노트 탐색"><h3>속성으로 노트 찾기</h3>
  <p className="muted">속성을 선택하면 연결된 노트의 현재 값을 함께 볼 수 있습니다.</p>
  {!open?<button type="button" onClick={()=>{opened.current=true;setOpen(true);void loadDefinitions()}}>속성 탐색 열기</button>:<>
   <div className="actions"><button type="button" onClick={close}>속성 탐색 닫기</button><button type="button" disabled={busy.definitions} onClick={()=>void loadDefinitions()}>속성 목록 새로고침</button></div>
   <MetadataNameSearch label="속성" applied={nameQuery} onApply={q=>{nameQueryRef.current=q;setNameQuery(q);void loadDefinitions(false,q)}}/>
   {busy.definitions&&<p>속성 확인 중…</p>}{notice.definitions&&<p role="status">{notice.definitions}</p>}
   {definitions&&<><p className="muted">불러온 속성 {definitions.items.length}개 · 생성 순서</p>{!definitions.items.length&&<p>{nameQuery===null?"아직 속성이 없습니다. 노트의 태그·속성에서 추가할 수 있습니다.":"이름 검색에 맞는 속성이 없습니다."}</p>}<div className="property-results">{definitions.items.map(row=><button type="button" key={row.id} aria-pressed={selected?.id===row.id} aria-label={`속성 선택: ${row.name}`} onClick={()=>select(row)}>{row.name}<small>{types[row.type]} · 활성 노트 {row.activeNoteCount}개</small></button>)}</div>{definitions.nextAfter&&<button type="button" disabled={busy.definitions} onClick={()=>void loadDefinitions(true)}>속성 더 보기</button>}</>}
   {selected&&<section aria-label="선택한 속성의 노트"><h4>속성: {selected.name} · {types[selected.type]}</h4>
    <PropertyTagFilter key={selected.id} spaceId={spaceId} applied={appliedTag} onApply={applyTag} onDenied={()=>{close();onDenied()}}/>
    <form className="property-filter" onSubmit={applyCondition}>
     {(selected.type==="NUMBER"||selected.type==="DATE")&&<label>조건 방식<select aria-label="속성 조건 방식" value={mode} onChange={e=>{setMode(e.target.value as "exact"|"range");setFilterError("")}}><option value="exact">정확한 값</option><option value="range">범위</option></select></label>}
     {mode==="range"?<div className="property-range-fields"><label>최솟값 (이상)<input aria-label="속성 최솟값" type={selected.type==="DATE"?"date":"text"} inputMode={selected.type==="NUMBER"?"decimal":undefined} value={minDraft} onChange={e=>setMinDraft(e.target.value)}/></label><label>최댓값 (이하)<input aria-label="속성 최댓값" type={selected.type==="DATE"?"date":"text"} inputMode={selected.type==="NUMBER"?"decimal":undefined} value={maxDraft} onChange={e=>setMaxDraft(e.target.value)}/></label></div>:<label>{selected.type==="STRING_LIST"?"포함할 목록 항목":"일치할 속성값"}{selected.type==="BOOLEAN"?<select aria-label="속성값 조건" value={draft} onChange={e=>setDraft(e.target.value)}><option value="true">참</option><option value="false">거짓</option></select>:selected.type==="STRING"||selected.type==="STRING_LIST"?<textarea aria-label="속성값 조건" rows={2} value={draft} onChange={e=>setDraft(e.target.value)}/>:<input aria-label="속성값 조건" type={selected.type==="DATE"?"date":"text"} inputMode={selected.type==="NUMBER"?"decimal":undefined} value={draft} onChange={e=>setDraft(e.target.value)}/>}</label>}<div className="actions"><button type="submit">값 조건 적용</button><button type="button" onClick={clearCondition}>값 조건 해제</button></div>{filterError&&<p role="alert">{filterError}</p>}<p className="muted">{mode==="range"?"입력한 경계값을 포함합니다. 한쪽을 비우면 그 방향에는 제한이 없습니다.":<>{selected.type==="STRING_LIST"?"목록의 항목 전체가 같은 노트를 찾습니다.":"저장된 값과 같은 노트를 찾습니다."} 문자열은 공백·대소문자를 구분하며 200자까지 입력할 수 있습니다.</>}</p></form>
    <p className="property-applied-condition">{`적용된 조건: ${conditionText(condition)}`}</p><div className="actions"><button type="button" onClick={closeNotes}>속성 선택 해제</button><button type="button" disabled={busy.notes} onClick={()=>void loadNotes(selected)}>속성 노트 새로고침</button></div>{busy.notes&&<p>연결된 노트 확인 중…</p>}{notice.notes&&<p role="status">{notice.notes}</p>}
    {notes&&<><p className="muted">불러온 노트 {notes.items.length}개 · 생성 순서</p>{!notes.items.length?<p>{condition===null&&appliedTag===null?"이 속성에 연결된 활성 노트가 없습니다.":"조건에 맞는 활성 노트가 없습니다."}</p>:<table className="property-note-table"><caption>{selected.name} 속성값</caption><thead><tr><th scope="col">노트</th><th scope="col">현재 값</th></tr></thead><tbody>{notes.items.map(row=><tr key={row.noteId}><td><button type="button" aria-label={`속성 노트 열기: ${row.title} · ${row.noteId}`} onClick={()=>onOpen({noteId:row.noteId,title:row.title})}>{row.title}</button><small>{row.noteId}<br/>본문 v{row.version} · 속성 v{row.metadataVersion}</small></td><td className="property-current-value"><PropertyValue type={selected.type} value={row.value}/></td></tr>)}</tbody></table>}{notes.nextAfter&&<button type="button" disabled={busy.notes} onClick={()=>void loadNotes(selected,true)}>속성 노트 더 보기</button>}</>}
   </section>}
   <p className="muted">사용 중인 노트가 없는 속성도 표시합니다. 속성 목록의 개수는 조건 적용 전 전체 활성 노트의 조회 시점 기준입니다. 값·연결 변경 후 새로고침으로 확인해 주세요. 숫자는 저장된 정밀도로 표시합니다.</p>
  </>}
 </section>;
}
