"use client";
import {useEffect,useRef,useState} from "react";
type Origin={sourceTitle:string;sourceSpaceId:string;sourceRoadmapId:string;sourceVersion:number;createdAt:string;nodes:unknown[];edges:unknown[]};
const id=(v:unknown):v is string=>typeof v==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);
export default function RoadmapOrigin({spaceId,roadmapId,onDenied}:{spaceId:string;roadmapId:string;onDenied:()=>void}){
 const [value,setValue]=useState<Origin|null>(null),[notice,setNotice]=useState(""),[busy,setBusy]=useState(false);
 const epoch=useRef(0),request=useRef<AbortController|null>(null);
 useEffect(()=>{epoch.current++;setValue(null);setNotice("");setBusy(false);return()=>{epoch.current++;request.current?.abort()}},[spaceId,roadmapId]);
 async function load(){request.current?.abort();const n=++epoch.current,c=new AbortController();request.current=c;setBusy(true);setValue(null);setNotice("");const timer=setTimeout(()=>c.abort(),8000);
  try{const r=await fetch(`/api/spaces/${spaceId}/roadmaps/${roadmapId}/adoption`,{signal:c.signal,credentials:"same-origin",cache:"no-store",redirect:"error"});if(n!==epoch.current)return;if([401,403,404].includes(r.status)){onDenied();return}if(!r.ok)throw new Error();const p=await r.json();if(!p||!Object.hasOwn(p,"adoption"))throw new Error();if(p.adoption===null){setNotice("직접 만든 개인 로드맵입니다.");return}const v=p.adoption;if(v.spaceId!==spaceId||v.roadmapId!==roadmapId||!id(v.adoptionId)||!id(v.sourceSpaceId)||v.sourceSpaceId===spaceId||!id(v.sourceRoadmapId)||!Number.isSafeInteger(v.sourceVersion)||v.sourceVersion<1||typeof v.sourceTitle!=="string"||!v.sourceTitle.trim()||Array.from(v.sourceTitle).length>200||!Number.isFinite(Date.parse(v.createdAt))||!Array.isArray(v.nodes)||v.nodes.length>100||!Array.isArray(v.edges)||v.edges.length>500)throw new Error();if(n===epoch.current&&!c.signal.aborted)setValue(v)}
  catch{if(n===epoch.current)setNotice("로드맵 출처를 불러오지 못했습니다. 다시 확인해 주세요.")}
  finally{clearTimeout(timer);if(n===epoch.current)setBusy(false)}
 }
 return <section aria-label="개인 로드맵 출처"><button disabled={busy} onClick={()=>void load()}>로드맵 출처 확인</button>{notice&&<p role="status">{notice}</p>}{value&&<><p>가져온 출처: {value.sourceTitle} · 공개 v{value.sourceVersion}</p><p>가져온 시각: {value.createdAt} · 원본 노드 {value.nodes.length}개 · 원본 관계 {value.edges.length}개</p><p>개인 수정·진도는 독립적으로 보존됩니다. 공개 원본의 검토 상태는 이 개인 사본에 적용되지 않습니다.</p><details><summary>출처 식별자</summary><p>{value.sourceSpaceId} / {value.sourceRoadmapId}</p></details></>}</section>;
}
