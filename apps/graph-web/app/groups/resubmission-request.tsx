"use client";
import {FormEvent,useEffect,useRef,useState} from "react";
import {api,Failure,object,uuid} from "./membership-api";
type Source={submissionId:string;authorId:string;version:number;assignmentVersion:number};
type Request={requestId:string;submissionId:string;assignmentId:string;studyId:string;groupId:string;spaceId:string;authorId:string;requestedBy:string;changedBy:string;version:number;submissionVersion:number;assignmentVersion:number;reason:string;state:"REQUESTED"|"CANCELLED"|"RESOLVED";resolvedSubmissionVersion:number|null;audience:"AUTHOR_AND_OWNER";createdAt:string;updatedAt:string};
type View={submissionVersion:number;assignmentVersion:number;request:Request|null;canRequest:boolean;canCancel:boolean};
type Revision={revisionId:string;version:number;actorId:string;occurredAt:string;snapshot:Request};
const id=(v:unknown):v is string=>typeof v==="string"&&uuid.test(v);
const positive=(v:unknown):v is number=>Number.isSafeInteger(v)&&Number(v)>0;
const stamp=(v:unknown):v is string=>typeof v==="string"&&Number.isFinite(Date.parse(v));
const text=(v:unknown):v is string=>typeof v==="string"&&!!v.trim()&&new TextEncoder().encode(v).length<=8192&&Array.from(v).every(c=>{const code=c.codePointAt(0)!;return code!==0&&(code<0xd800||code>0xdfff);});
const stateLabel=(v:Request)=>v.state==="REQUESTED"?"재제출 요청 중":v.state==="CANCELLED"?"요청 취소됨":"새 답안 제출로 해결";
export default function ResubmissionRequest({groupId,spaceId,studyId,assignmentId,submission,owner,onUnavailable,onRefreshSubmission}:{groupId:string;spaceId:string;studyId:string;assignmentId:string;submission:Source;owner:boolean;onUnavailable:()=>void;onRefreshSubmission:()=>void}){
 const [view,setView]=useState<View|null>(null),[draft,setDraft]=useState(""),[confirmed,setConfirmed]=useState(false),[revisions,setRevisions]=useState<Revision[]>([]),[before,setBefore]=useState<number|null>(null),[busy,setBusy]=useState(false),[notice,setNotice]=useState("");
 const mounted=useRef(true),locked=useRef(false),controller=useRef<AbortController|null>(null);
 const base="/api/groups/"+groupId+"/studies/"+studyId+"/assignments/"+assignmentId+"/submissions/"+submission.submissionId+"/resubmission-request";
 function valid(v:unknown):v is Request{return object(v)&&id(v.requestId)&&v.submissionId===submission.submissionId&&v.assignmentId===assignmentId&&v.studyId===studyId&&v.groupId===groupId&&v.spaceId===spaceId&&v.authorId===submission.authorId&&id(v.requestedBy)&&id(v.changedBy)&&positive(v.version)&&positive(v.submissionVersion)&&positive(v.assignmentVersion)&&text(v.reason)&&["REQUESTED","CANCELLED","RESOLVED"].includes(String(v.state))&&(v.state==="RESOLVED"?positive(v.resolvedSubmissionVersion)&&v.resolvedSubmissionVersion>v.submissionVersion:v.resolvedSubmissionVersion===null)&&v.audience==="AUTHOR_AND_OWNER"&&stamp(v.createdAt)&&stamp(v.updatedAt);}
 function clear(){setView(null);setDraft("");setConfirmed(false);setRevisions([]);setBefore(null);}
 async function run(action:(s:AbortSignal)=>Promise<void>){
  if(locked.current)return;locked.current=true;const c=new AbortController();controller.current=c;setBusy(true);setNotice("");
  try{await action(AbortSignal.any([c.signal,AbortSignal.timeout(20000)]));}
  catch(e){if(mounted.current&&!c.signal.aborted){setView(null);setConfirmed(false);setRevisions([]);setBefore(null);if(e instanceof Failure&&[401,403,404].includes(e.status)){clear();onUnavailable();}else if(e instanceof Failure&&e.status===409)setNotice("검토 대상 답안이나 과제 상태가 바뀌었습니다. 입력은 유지됩니다. 최신 답안을 다시 열어 확인해 주세요.");else if(e instanceof Failure&&e.status===412)setNotice("재제출 요청 상태가 바뀌었습니다. 입력은 유지됩니다. 상태를 새로고침해 확인해 주세요.");else setNotice("재제출 요청 저장 결과를 확인하지 못했습니다. 상태를 새로고침해 확인해 주세요.");}}
  finally{if(controller.current===c){locked.current=false;if(mounted.current&&!c.signal.aborted)setBusy(false);}}
 }
 async function load(s:AbortSignal){
  const raw=await api(base,s);if(!object(raw)||Object.keys(raw).length!==5||!positive(raw.submissionVersion)||!positive(raw.assignmentVersion)||typeof raw.canRequest!=="boolean"||typeof raw.canCancel!=="boolean"||raw.submissionVersion<submission.version)throw new Error();
  if(raw.request!==null){if(!valid(raw.request))throw new Error();if(raw.request.submissionVersion>raw.submissionVersion||raw.request.submissionVersion===raw.submissionVersion&&raw.request.assignmentVersion!==raw.assignmentVersion||raw.request.resolvedSubmissionVersion!==null&&raw.request.resolvedSubmissionVersion>raw.submissionVersion||raw.canCancel&&raw.request.state!=="REQUESTED")throw new Error();}else if(raw.canCancel)throw new Error();
  const value=raw as View;if(!s.aborted){setView(value);setDraft(value.request?.reason??"");setConfirmed(false);setRevisions([]);setBefore(null);}
 }
 async function history(s:AbortSignal,cursor:number|null=null){
  if(!view)return;const raw=await api(base+"/revisions?limit=5"+(cursor?"&before="+cursor:""),s);if(!object(raw)||!Array.isArray(raw.items)||raw.items.length>5||!(raw.nextBefore===null||positive(raw.nextBefore)))throw new Error();let previous=cursor??Infinity;
  for(const v of raw.items){if(!object(v)||!id(v.revisionId)||!positive(v.version)||v.version>=previous||!id(v.actorId)||!stamp(v.occurredAt)||!valid(v.snapshot)||v.snapshot.requestId!==view.request?.requestId||v.snapshot.version!==v.version||v.snapshot.changedBy!==v.actorId)throw new Error();previous=v.version;}
  if(raw.nextBefore!==null&&(raw.items.length!==5||raw.nextBefore!==previous))throw new Error();if(!s.aborted){setRevisions(raw.items as Revision[]);setBefore(raw.nextBefore as number|null);}
 }
 function command(action:"REQUEST"|"CANCEL"){
  if(!view||!owner||!confirmed||!sourceMatches||action==="REQUEST"&&(!view.canRequest||!text(draft))||action==="CANCEL"&&!view.canCancel)return;
  const current=view,reason=draft;void run(async s=>{const body=action==="REQUEST"?{action,reason,submissionVersion:submission.version,assignmentVersion:submission.assignmentVersion}:{action};const raw=await api(base,s,{method:"PUT",headers:{"If-Match":'"rr'+(current.request?.version??0)+'"'},body:JSON.stringify(body)});if(!valid(raw)||action==="REQUEST"&&(raw.state!=="REQUESTED"||raw.submissionVersion!==submission.version||raw.assignmentVersion!==submission.assignmentVersion||raw.reason!==reason)||action==="CANCEL"&&raw.state!=="CANCELLED")throw new Error();await load(s);if(!s.aborted)setNotice(action==="REQUEST"?"재제출 요청을 저장했습니다.":"재제출 요청을 취소했습니다.");});
 }
 function save(e:FormEvent){e.preventDefault();command("REQUEST");}
 useEffect(()=>{mounted.current=true;void run(load);const visible=()=>{if(document.hidden){controller.current?.abort();locked.current=false;clear();setBusy(false);}else void run(load);};document.addEventListener("visibilitychange",visible);return()=>{mounted.current=false;controller.current?.abort();document.removeEventListener("visibilitychange",visible);};},[]);
 const sourceMatches=!!view&&view.submissionVersion===submission.version&&view.assignmentVersion===submission.assignmentVersion;
 const canRequest=!!view&&owner&&view.canRequest&&sourceMatches,canCancel=!!view&&owner&&view.canCancel&&sourceMatches;
 return <section aria-label="재제출 요청"><h5>재제출 요청</h5><p className="muted">제출자와 현재 그룹 소유자만 읽습니다. 새 답안을 제출하면 요청이 해결됩니다. 해결은 답안 도착을 뜻하며 채점·승인이 아닙니다. 마감되거나 보관된 과제는 다시 제출할 수 없습니다.</p><p role="status">{notice}</p><button disabled={busy} onClick={()=>void run(load)}>재제출 요청 상태 새로고침</button><button disabled={busy} onClick={onRefreshSubmission}>재제출 검토용 최신 답안 열기</button>
  {view&&<>{!sourceMatches&&<p>새 답안이 등록되었습니다. 최신 답안을 다시 열어 확인해 주세요.</p>}{!view.request&&<p>아직 재제출 요청이 없습니다.</p>}{view.request&&<><p>요청 v{view.request.version} · 제출 v{view.request.submissionVersion} · 과제 v{view.request.assignmentVersion} 기준</p><p>{stateLabel(view.request)}{view.request.resolvedSubmissionVersion!==null&&" · 제출 v"+view.request.resolvedSubmissionVersion}</p><p className="source-text">{view.request.reason}</p><time dateTime={view.request.updatedAt}>{new Date(view.request.updatedAt).toLocaleString("ko-KR")}</time></>}
  {owner&&!view.canRequest&&!view.canCancel&&<p>현재 과제·스터디 상태에서는 요청을 변경할 수 없습니다.</p>}
  <button disabled={busy||!view.request} onClick={()=>void run(s=>history(s))}>재제출 요청 이력 조회</button><ol aria-label="재제출 요청 이력">{revisions.map(v=><li key={v.revisionId}>요청 v{v.version} · 제출 v{v.snapshot.submissionVersion} · {stateLabel(v.snapshot)}{v.snapshot.resolvedSubmissionVersion!==null&&" · 제출 v"+v.snapshot.resolvedSubmissionVersion}<p className="source-text">{v.snapshot.reason}</p><time dateTime={v.occurredAt}>{new Date(v.occurredAt).toLocaleString("ko-KR")}</time></li>)}</ol><button disabled={busy||!before} onClick={()=>void run(s=>history(s,before))}>이전 재제출 요청 이력 페이지</button></>}
  {owner&&<form aria-label="재제출 요청 양식" onSubmit={save}><label>수정 사유<textarea aria-label="재제출 요청 사유" rows={4} value={draft} disabled={busy||!!view&&!canRequest} onChange={e=>{setDraft(e.target.value);setConfirmed(false);}}/></label><p>{new TextEncoder().encode(draft).length} / 8192 UTF-8 바이트</p><label><input type="checkbox" aria-label="재제출 요청 변경 확인" checked={confirmed} disabled={busy||!canRequest&&!canCancel} onChange={e=>setConfirmed(e.target.checked)}/>제출 v{submission.version} 답안과 과제 v{submission.assignmentVersion} 지시문을 확인했습니다. 요청 사유를 이 제출자에게 공유하거나 현재 요청을 취소합니다.</label><button disabled={busy||!canRequest||!confirmed||!text(draft)}>재제출 요청 저장</button><button type="button" disabled={busy||!canCancel||!confirmed} onClick={()=>command("CANCEL")}>재제출 요청 취소</button></form>}
 </section>;
}
