"use client";
import {useEffect,useRef,useState} from "react";
import {api,Failure,object,uuid} from "./membership-api";
type Source={submissionId:string;version:number;assignmentVersion:number};
type Assignment={assignmentId:string;studyId:string;groupId:string;spaceId:string;creatorId:string;version:number;title:string;description:string;state:"OPEN";visibility:"PRIVATE";createdAt:string;updatedAt:string};
const stamp=(v:unknown):v is string=>typeof v==="string"&&Number.isFinite(Date.parse(v));
const unicode=(v:string)=>Array.from(v).every(c=>{const n=c.codePointAt(0)!;return n!==0&&(n<0xd800||n>0xdfff);});
export default function SubmissionInstructions({groupId,spaceId,studyId,assignmentId,submission,onUnavailable}:{groupId:string;spaceId:string;studyId:string;assignmentId:string;submission:Source;onUnavailable:()=>void}){
 const [value,setValue]=useState<Assignment|null>(null),[busy,setBusy]=useState(false),[notice,setNotice]=useState("");
 const mounted=useRef(true),controller=useRef<AbortController|null>(null),locked=useRef(false);
 const path="/api/groups/"+groupId+"/studies/"+studyId+"/assignments/"+assignmentId+"/submissions/"+submission.submissionId+"/revisions/"+submission.version+"/instructions";
 async function load(){
  if(locked.current)return;locked.current=true;const c=new AbortController();controller.current=c;setBusy(true);setNotice("");setValue(null);
  try{const v=await api(path,AbortSignal.any([c.signal,AbortSignal.timeout(20000)]));if(!object(v)||Object.keys(v).length!==4||v.submissionId!==submission.submissionId||v.submissionVersion!==submission.version||v.assignmentVersion!==submission.assignmentVersion)throw new Error();
   const a=v.assignment;if(!object(a)||Object.keys(a).length!==12||a.assignmentId!==assignmentId||a.studyId!==studyId||a.groupId!==groupId||a.spaceId!==spaceId||typeof a.creatorId!=="string"||!uuid.test(a.creatorId)||a.version!==submission.assignmentVersion||typeof a.title!=="string"||!a.title.trim()||[...a.title].length>200||!unicode(a.title)||typeof a.description!=="string"||!unicode(a.description)||new TextEncoder().encode(a.description).length>32768||a.state!=="OPEN"||a.visibility!=="PRIVATE"||!stamp(a.createdAt)||!stamp(a.updatedAt))throw new Error();
   if(!c.signal.aborted&&mounted.current)setValue(a as Assignment);
  }catch(e){if(!c.signal.aborted&&mounted.current){setValue(null);if(e instanceof Failure&&[401,403,404].includes(e.status)){setNotice("이 제출의 원본 지시문에 접근할 수 없습니다.");onUnavailable();}else setNotice("원본 지시문을 불러오지 못했습니다. 다시 조회해 주세요.");}}
  finally{if(controller.current===c){locked.current=false;if(!c.signal.aborted&&mounted.current)setBusy(false);}}
 }
 useEffect(()=>{mounted.current=true;const hide=()=>{if(document.hidden){controller.current?.abort();locked.current=false;setValue(null);setNotice("");setBusy(false);}};document.addEventListener("visibilitychange",hide);return()=>{mounted.current=false;controller.current?.abort();document.removeEventListener("visibilitychange",hide);};},[]);
 return <section aria-label="제출 당시 지시문"><p className="muted">제출 v{submission.version}이 참조한 과제 v{submission.assignmentVersion}의 원본입니다. 현재 과제 지시문과 다를 수 있습니다.</p><p role="status">{notice}</p><button disabled={busy} onClick={()=>void load()}>{busy?"원본 지시문 조회 중":"이 제출의 원본 지시문 조회"}</button>{value&&<article aria-label="제출 당시 과제 원본"><h5>{value.title}</h5><p>제출 v{submission.version} · 과제 v{value.version} 기준</p><p className="source-text">{value.description}</p><p>제출 당시 과제 상태: 진행 중</p><time dateTime={value.updatedAt}>{new Date(value.updatedAt).toLocaleString("ko-KR")}</time><button onClick={()=>{setValue(null);setNotice("");}}>원본 지시문 닫기</button></article>}</section>;
}
