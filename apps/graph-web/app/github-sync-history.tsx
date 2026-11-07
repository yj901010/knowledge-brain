"use client";
import {useEffect,useState} from "react";
import {api,useRequest} from "./learning-study";

type Receipt={syncId:string;repositoryId:string;repositoryVersion:number;observedAt:string;scannedCount:number;importedCount:number;headSha:string|null;possiblyMore:boolean};
type Page={items:Receipt[];nextBefore:number|null};
const id=(v:unknown):v is string=>typeof v==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);
const integer=(v:unknown):v is number=>typeof v==="number"&&Number.isSafeInteger(v);
function parse(raw:unknown,repo:string,before:number|null):Page{
 const p=raw as Page;if(!p||Object.keys(p).length!==2||!Array.isArray(p.items)||p.items.length>20)throw new Error();
 let version:number|null=before;const seen=new Set<string>();
 for(const v of p.items){if(!v||Object.keys(v).length!==8||!id(v.syncId)||seen.has(v.syncId)||v.repositoryId!==repo||!integer(v.repositoryVersion)||v.repositoryVersion<2||version!==null&&v.repositoryVersion>=version||typeof v.observedAt!=="string"||!Number.isFinite(Date.parse(v.observedAt))||!integer(v.scannedCount)||v.scannedCount<0||v.scannedCount>20||!integer(v.importedCount)||v.importedCount<0||v.importedCount>v.scannedCount||!(v.headSha===null||typeof v.headSha==="string"&&/^([0-9a-f]{40}|[0-9a-f]{64})$/.test(v.headSha))||(v.scannedCount===0)!==(v.headSha===null)||typeof v.possiblyMore!=="boolean"||v.possiblyMore!==(v.scannedCount===20))throw new Error();seen.add(v.syncId);version=v.repositoryVersion;}
 if(p.nextBefore!==null&&(!integer(p.nextBefore)||p.items.length!==20||p.nextBefore!==version))throw new Error();return p;
}
export default function GitHubSyncHistory({spaceId:space,repositoryId:repo,onDenied}:{spaceId:string;repositoryId:string;onDenied:()=>void}){
 const [open,setOpen]=useState(false),[items,setItems]=useState<Receipt[]>([]),[before,setBefore]=useState<number|null>(null),[loaded,setLoaded]=useState(false);
 const q=useRequest(`github-sync-history:${space}:${repo}:${open}`,space,onDenied);
 function clear(){setItems([]);setBefore(null);setLoaded(false);}
 function load(cursor:number|null=null){clear();void q.run(async signal=>{const r=await api(`/api/spaces/${space}/github/repositories/${repo}/syncs?limit=20`+(cursor===null?"":"&before="+cursor),signal);await q.check(r,signal);if(!r.ok||r.headers.get("Cache-Control")!=="no-store")throw new Error();return parse(await r.json(),repo,cursor);},p=>{setItems(p.items);setBefore(p.nextBefore);setLoaded(true);},"동기화 이력을 불러오지 못했습니다. 다시 조회해 주세요.");}
 useEffect(()=>{q.stop();clear();if(open)load();},[space,repo,open]);
 useEffect(()=>{const hide=()=>{if(document.hidden){q.stop();clear();setOpen(false);}};document.addEventListener("visibilitychange",hide);return()=>document.removeEventListener("visibilitychange",hide);},[space,repo]);
 return <section aria-label="GitHub 커밋 동기화 이력"><button aria-expanded={open} onClick={()=>setOpen(!open)}>GitHub 커밋 동기화 이력 {open?"닫기":"열기"}</button>{open&&<><p>완료된 커밋 가져오기를 최신순으로 확인합니다. 조회는 GitHub에 다시 요청하지 않습니다. 서버에서 완료된 실행만 기록되며 실패한 시도 자체는 저장하지 않습니다.</p><button disabled={q.busy} onClick={()=>load()}>동기화 이력 새로고침</button>{q.busy&&<p role="status">동기화 이력을 불러오는 중입니다.</p>}{q.message&&<p role="status">{q.message}</p>}<ol aria-label="GitHub 커밋 동기화 기록">{items.map(v=><li key={v.syncId}><p>저장소 v{v.repositoryVersion} · 확인 {v.scannedCount}개 · 새로 가져옴 {v.importedCount}개</p><p>확인 시각 {v.observedAt}</p>{v.headSha===null?<p>확인한 커밋이 없습니다.</p>:<p>확인한 최신 커밋 <code>{v.headSha}</code></p>}{v.possiblyMore&&<p>더 오래된 커밋이 있을 수 있습니다.</p>}</li>)}</ol>{loaded&&items.length===0&&<p>아직 완료된 커밋 가져오기 기록이 없습니다.</p>}{before!==null&&<button disabled={q.busy} onClick={()=>load(before)}>이전 동기화 기록</button>}<p>확인한 커밋 수는 이번 요청의 관측 범위이며, 전체 저장소 이력이나 본인의 기여 수를 뜻하지 않습니다. 새로 가져옴 0개도 완료된 실행입니다.</p></>}</section>;
}
