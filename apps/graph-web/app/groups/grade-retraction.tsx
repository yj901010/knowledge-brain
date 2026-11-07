"use client";
import {FormEvent,useEffect,useRef,useState} from "react";
import {api,Failure,object,uuid} from "./membership-api";
type Target={gradeId:string;version:number;submissionVersion:number;assignmentVersion:number};
type Retraction={retractionId:string;gradeId:string;gradeVersion:number;submissionId:string;submissionVersion:number;assignmentId:string;assignmentVersion:number;studyId:string;groupId:string;spaceId:string;authorId:string;actorId:string;version:number;state:"RETRACTED"|"RESTORED";reason:string;audience:"AUTHOR_AND_OWNER";createdAt:string;updatedAt:string};
type View={gradeId:string;gradeVersion:number;retraction:Retraction|null;canWrite:boolean};
type Revision={revisionId:string;version:number;actorId:string;occurredAt:string;snapshot:Retraction};
const id=(v:unknown):v is string=>typeof v==="string"&&uuid.test(v);
const positive=(v:unknown):v is number=>Number.isSafeInteger(v)&&Number(v)>0;
const stamp=(v:unknown):v is string=>typeof v==="string"&&Number.isFinite(Date.parse(v));
const text=(v:unknown):v is string=>typeof v==="string"&&!!v.trim()&&new TextEncoder().encode(v).length<=8192&&Array.from(v).every(c=>{const n=c.codePointAt(0)!;return n!==0&&(n<0xd800||n>0xdfff);});
const stateLabel=(v:Retraction)=>v.state==="RETRACTED"?"철회":"복원";
export default function GradeRetraction({groupId,spaceId,studyId,assignmentId,submissionId,authorId,grade,owner,self=false,onUnavailable}:{groupId:string;spaceId:string;studyId:string;assignmentId:string;submissionId:string;authorId:string;grade:Target;owner:boolean;self?:boolean;onUnavailable:()=>void}){
 const [view,setView]=useState<View|null>(null),[draft,setDraft]=useState(""),[confirmed,setConfirmed]=useState(false),[editing,setEditing]=useState(false),[revisions,setRevisions]=useState<Revision[]>([]),[before,setBefore]=useState<number|null>(null),[busy,setBusy]=useState(false),[notice,setNotice]=useState("");
 const mounted=useRef(true),locked=useRef(false),controller=useRef<AbortController|null>(null);
 const base="/api/groups/"+groupId+"/studies/"+studyId+"/assignments/"+assignmentId+"/submissions/"+submissionId+"/grade/revisions/"+grade.version+"/retraction";
 function valid(v:unknown):v is Retraction{return object(v)&&Object.keys(v).length===18&&id(v.retractionId)&&v.gradeId===grade.gradeId&&v.gradeVersion===grade.version&&v.submissionId===submissionId&&v.submissionVersion===grade.submissionVersion&&v.assignmentId===assignmentId&&v.assignmentVersion===grade.assignmentVersion&&v.studyId===studyId&&v.groupId===groupId&&v.spaceId===spaceId&&v.authorId===authorId&&id(v.actorId)&&v.actorId!==authorId&&positive(v.version)&&["RETRACTED","RESTORED"].includes(String(v.state))&&text(v.reason)&&v.audience==="AUTHOR_AND_OWNER"&&stamp(v.createdAt)&&stamp(v.updatedAt);}
 function clear(){setView(null);setDraft("");setConfirmed(false);setEditing(false);setRevisions([]);setBefore(null);}
 async function run(action:(s:AbortSignal)=>Promise<void>){
  if(locked.current)return;locked.current=true;const c=new AbortController();controller.current=c;setBusy(true);setNotice("");
  try{await action(AbortSignal.any([c.signal,AbortSignal.timeout(20000)]));}
  catch(e){if(mounted.current&&!c.signal.aborted){setView(null);setConfirmed(false);setRevisions([]);setBefore(null);if(e instanceof Failure&&[401,403,404].includes(e.status)){clear();onUnavailable();}else if(e instanceof Failure&&e.status===412)setNotice("철회 표시가 바뀌었습니다. 입력은 유지됩니다. 상태를 새로고침해 확인해 주세요.");else if(e instanceof Failure&&e.status===409)setNotice("현재 과제 상태에서는 변경할 수 없거나 복원할 철회 기록이 없습니다. 입력은 유지됩니다. 상태를 새로고침해 주세요.");else setNotice("철회·복원 저장 결과를 확인하지 못했습니다. 상태를 새로고침해 확인해 주세요.");}}
  finally{if(controller.current===c){locked.current=false;if(mounted.current&&!c.signal.aborted)setBusy(false);}}
 }
 async function load(s:AbortSignal){
  const raw=await api(base,s);if(!object(raw)||Object.keys(raw).length!==4||raw.gradeId!==grade.gradeId||raw.gradeVersion!==grade.version||typeof raw.canWrite!=="boolean"||raw.canWrite&&(!owner||self))throw new Error();if(raw.retraction!==null&&!valid(raw.retraction))throw new Error();
  const value=raw as View;if(!s.aborted){setView(value);setDraft(value.retraction?.reason??"");setConfirmed(false);setRevisions([]);setBefore(null);}
 }
 async function history(s:AbortSignal,cursor:number|null=null){
  if(!view)return;const raw=await api(base+"/revisions?limit=5"+(cursor?"&before="+cursor:""),s);if(!object(raw)||Object.keys(raw).length!==2||!Array.isArray(raw.items)||raw.items.length>5||!(raw.nextBefore===null||positive(raw.nextBefore)))throw new Error();let previous=cursor??Infinity;
  for(const v of raw.items){if(!object(v)||Object.keys(v).length!==5||!id(v.revisionId)||!positive(v.version)||v.version>=previous||!id(v.actorId)||!stamp(v.occurredAt)||!valid(v.snapshot)||v.snapshot.retractionId!==view.retraction?.retractionId||v.snapshot.version!==v.version||v.snapshot.actorId!==v.actorId)throw new Error();previous=v.version;}
  if(raw.nextBefore!==null&&(raw.items.length!==5||raw.nextBefore!==previous))throw new Error();if(!s.aborted){setRevisions(raw.items as Revision[]);setBefore(raw.nextBefore as number|null);}
 }
 function command(state:"RETRACTED"|"RESTORED"){
  if(!view||!owner||self||!view.canWrite||!confirmed||!text(draft)||state==="RESTORED"&&!view.retraction)return;
  const current=view,reason=draft;void run(async s=>{const raw=await api(base,s,{method:"PUT",headers:{"If-Match":'"gr'+(current.retraction?.version??0)+'"'},body:JSON.stringify({state,reason})});if(!valid(raw)||raw.state!==state||raw.reason!==reason)throw new Error();await load(s);if(!s.aborted)setNotice(state==="RETRACTED"?"이 채점 버전에 철회 표시를 저장했습니다.":"이 채점 버전에 복원 표시를 저장했습니다.");});
 }
 function save(e:FormEvent){e.preventDefault();command("RETRACTED");}
 useEffect(()=>{mounted.current=true;void run(load);const visible=()=>{if(document.hidden){controller.current?.abort();locked.current=false;clear();setBusy(false);}else void run(load);};document.addEventListener("visibilitychange",visible);return()=>{mounted.current=false;controller.current?.abort();document.removeEventListener("visibilitychange",visible);};},[]);
 const canWrite=!!view&&owner&&!self&&view.canWrite;
 return <section aria-label="채점 철회·복원"><p><strong>채점 v{grade.version} 철회 상태</strong></p><p role="status">{notice}</p>{!view&&<p>철회 상태를 아직 확인하지 못했습니다.</p>}{view&&!view.retraction&&<p>이 버전에는 철회 기록이 없습니다.</p>}{view?.retraction&&<><p>{view.retraction.state==="RETRACTED"?"철회된 채점입니다. 원본 점수·사유와 이력은 제출자·현재 그룹 소유자가 계속 확인할 수 있습니다. 현재 유효한 점수로 사용하지 마세요.":"철회 후 복원된 채점입니다."}</p><p>표시 v{view.retraction.version} · 채점 v{grade.version} · {stateLabel(view.retraction)}</p><p className="source-text">{view.retraction.reason}</p><time dateTime={view.retraction.updatedAt}>{new Date(view.retraction.updatedAt).toLocaleString("ko-KR")}</time></>}
  <button disabled={busy} onClick={()=>void run(load)}>철회 상태 새로고침</button><button disabled={busy||!view?.retraction} onClick={()=>void run(s=>history(s))}>철회·복원 이력 조회</button><ol aria-label="철회·복원 이력">{revisions.map(v=><li key={v.revisionId}>표시 v{v.version} · {stateLabel(v.snapshot)}<p className="source-text">{v.snapshot.reason}</p><time dateTime={v.occurredAt}>{new Date(v.occurredAt).toLocaleString("ko-KR")}</time></li>)}</ol><button disabled={busy||!before} onClick={()=>void run(s=>history(s,before))}>이전 철회 이력 페이지</button>
  {owner&&!self&&<><button disabled={busy} onClick={()=>{setEditing(!editing);setConfirmed(false);}}>철회·복원 관리 {editing?"닫기":"열기"}</button>{view&&!view.canWrite&&<p>보관된 과제나 스터디에서는 철회·복원 표시를 변경할 수 없습니다.</p>}{editing&&<form aria-label="채점 철회·복원 양식" onSubmit={save}><label>철회·복원 사유<textarea aria-label="채점 철회·복원 사유" rows={3} value={draft} disabled={busy||!!view&&!canWrite} onChange={e=>{setDraft(e.target.value);setConfirmed(false);}}/></label><p>{new TextEncoder().encode(draft).length} / 8192 UTF-8 바이트</p><label><input type="checkbox" aria-label="채점 버전 철회·복원 확인" checked={confirmed} disabled={busy||!canWrite} onChange={e=>setConfirmed(e.target.checked)}/>채점 v{grade.version}에만 표시를 남깁니다. 원본 점수와 사유·이력은 제출자와 현재 그룹 소유자에게 계속 보입니다.</label><button disabled={busy||!canWrite||!confirmed||!text(draft)}>철회 표시 저장</button><button type="button" disabled={busy||!canWrite||!view?.retraction||!confirmed||!text(draft)} onClick={()=>command("RESTORED")}>복원 표시 저장</button></form>}</>}
 </section>;
}
