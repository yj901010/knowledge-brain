"use client";
import {javaStrip,utf16MaxLength} from "./unicode-text";
import {publicText} from "./public-text";
import {useEffect,useRef,useState} from "react";
import {canonical,request,uuid,validPublicTitle,type Canonical} from "./public-curation-model";
import styles from "./public-curation.module.css";

type Row={conceptId?:string;recordId?:string;title:string;version:number};
function validPage(value:unknown,kind:string):value is {items:Row[];nextAfter:string|null}{
 if(!value||typeof value!=="object"||Array.isArray(value))return false;
 const page=value as {items?:unknown;nextAfter?:unknown};
 if(!Array.isArray(page.items)||page.items.length>20||page.nextAfter!==null&&(typeof page.nextAfter!=="string"||!uuid.test(page.nextAfter)))return false;
 const ids:string[]=[];
 for(const row of page.items){if(!row||typeof row!=="object"||Array.isArray(row))return false;const id=row[kind==="CONCEPT"?"conceptId":"recordId"];if(typeof id!=="string"||!uuid.test(id)||!validPublicTitle(row.title)||!Number.isSafeInteger(row.version)||row.version<1||kind!=="CONCEPT"&&(row.kind!==kind||row.state!=="ACTIVE"))return false;ids.push(id)}
 return new Set(ids).size===ids.length&&(page.nextAfter===null||ids.length>0&&page.nextAfter===ids[ids.length-1]);
}
export default function CurationReference({space,kind,label,initialQuery,disabled,pinned,onPin,onInvalidate}:{space:string;kind:string;label:string;initialQuery:string;disabled:boolean;pinned?:Canonical;onPin:(value:Canonical)=>boolean;onInvalidate?:()=>void}){
 const [query,setQuery]=useState(Array.from(initialQuery).slice(0,64).join("")),[applied,setApplied]=useState(""),[rows,setRows]=useState<Row[]>([]),[after,setAfter]=useState<string|null>(null),[id,setId]=useState(""),[candidate,setCandidate]=useState<Canonical|null>(null),[consent,setConsent]=useState(false),[loading,setLoading]=useState(false),[notice,setNotice]=useState("");
 const epoch=useRef(0),controller=useRef<AbortController|null>(null);
 useEffect(()=>()=>{epoch.current++;controller.current?.abort()},[]);
 async function run(action:(signal:AbortSignal)=>Promise<void>){controller.current?.abort();const c=new AbortController();controller.current=c;const version=++epoch.current;setLoading(true);setNotice("");try{await action(AbortSignal.any([c.signal,AbortSignal.timeout(15000)]))}catch{if(version===epoch.current&&!c.signal.aborted){setCandidate(null);setConsent(false);setNotice("현재 공개 원본을 확인하지 못했습니다. 다른 분야·개인 자료·미병합 제안 ID·철회된 자료는 사용할 수 없습니다.")}}finally{if(version===epoch.current)setLoading(false)}}
 async function search(more=false){if(disabled)return;const q=more?applied:javaStrip(query);if(!q||!publicText(q,64))return;setCandidate(null);setConsent(false);if(!more){setRows([]);setAfter(null)}await run(async signal=>{const page=await request<unknown>(`/api/public/spaces/${space}/${kind==="CONCEPT"?"explore":"records"}?${new URLSearchParams({q,limit:"20",...kind!=="CONCEPT"?{kind,state:"ACTIVE"}:{},...more&&after?{after}:{}})}`,{signal});if(signal.aborted)return;if(!validPage(page,kind))throw new Error("Invalid canonical page");setRows(old=>more?[...old,...page.items]:page.items);setAfter(page.nextAfter);setApplied(q)})}
 async function inspect(value:string){if(disabled)return;setCandidate(null);setConsent(false);onInvalidate?.();await run(async signal=>{const c=await canonical(space,kind,value.trim(),signal);if(!signal.aborted)setCandidate(c)})}
 return <fieldset aria-label={label} disabled={disabled}><legend>{label}</legend>
  <p className={styles.muted}>현재 공개 분야에서 검색한 뒤 원본 내용·ID·버전을 직접 확인하세요. 제목이 같아도 의미가 같다는 보장은 없습니다.</p>
  {pinned&&<p className={styles.pin}>재사용 선택: {pinned.title} · {pinned.id} · v{pinned.version}</p>}
  <label>{label} 검색어<input maxLength={utf16MaxLength(64)} value={query} onChange={e=>setQuery(e.target.value)} onKeyDown={e=>{if(e.key==="Enter"){e.preventDefault();void search()}}}/></label><button type="button" disabled={loading||!publicText(javaStrip(query),64,true)} onClick={()=>void search()}>현재 {label} 찾기</button>
  {rows.map(row=><button className="note-row" type="button" key={row.conceptId??row.recordId} disabled={loading} onClick={()=>void inspect(row.conceptId??row.recordId??"")}>원본 검사: {row.title} · v{row.version}</button>)}
  {after&&<button type="button" disabled={loading} onClick={()=>void search(true)}>현재 {label} 더 찾기</button>}
  {applied&&rows.length===0&&!loading&&<p>검색 결과가 없습니다. 아직 병합되지 않은 제안은 표시되지 않습니다.</p>}
  <details><summary>알고 있는 공개 원본 ID로 검사</summary><label>{label} 원본 ID<input value={id} maxLength={36} onChange={e=>setId(e.target.value)}/></label><button type="button" disabled={loading||!id.trim()} onClick={()=>void inspect(id)}>이 {label} ID 검사</button></details>
  {notice&&<p role="status">{notice}</p>}
  {candidate&&<section className={styles.snapshot} aria-label={`${label} 원본 미리보기`}><h5>{candidate.title}</h5><p>공개 원본 · {candidate.id} · v{candidate.version} · 유효</p>{candidate.description&&<pre>{candidate.description}</pre>}{Object.keys(candidate.content).length>0&&<dl>{Object.entries(candidate.content).map(([key,value])=><div key={key}><dt>{key}</dt><dd>{String(value)}</dd></div>)}</dl>}<label><input type="checkbox" checked={consent} onChange={e=>setConsent(e.target.checked)}/> 이 {label}의 의미·내용·현재 버전을 확인했습니다</label><button type="button" disabled={!consent||loading} onClick={()=>{if(onPin(candidate)){setCandidate(null);setConsent(false)}}}>확인한 {label} 재사용</button></section>}
 </fieldset>;
}
