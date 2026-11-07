"use client";
import {FormEvent,useEffect,useRef,useState} from "react";
import {api,Failure,object,uuid} from "./membership-api";
import FeedbackRetraction from "./feedback-retraction";

type Source={submissionId:string;authorId:string;version:number;assignmentVersion:number};
type Feedback={feedbackId:string;submissionId:string;assignmentId:string;studyId:string;groupId:string;spaceId:string;authorId:string;reviewerId:string;version:number;submissionVersion:number;assignmentVersion:number;content:string;audience:"AUTHOR_AND_OWNER";createdAt:string;updatedAt:string};
type View={submissionVersion:number;assignmentVersion:number;feedback:Feedback|null;canWrite:boolean};
type Revision={revisionId:string;version:number;actorId:string;occurredAt:string;snapshot:Feedback};
const id=(v:unknown):v is string=>typeof v==="string"&&uuid.test(v);
const positive=(v:unknown):v is number=>Number.isSafeInteger(v)&&Number(v)>0;
const stamp=(v:unknown):v is string=>typeof v==="string"&&Number.isFinite(Date.parse(v));
const text=(v:unknown):v is string=>typeof v==="string"&&!!v.trim()&&new TextEncoder().encode(v).length<=8192&&Array.from(v).every(c=>{const code=c.codePointAt(0)!;return code!==0&&(code<0xd800||code>0xdfff);});

export default function SubmissionFeedback({groupId,spaceId,studyId,assignmentId,submission,owner,onUnavailable,onRefreshSubmission}:{groupId:string;spaceId:string;studyId:string;assignmentId:string;submission:Source;owner:boolean;onUnavailable:()=>void;onRefreshSubmission:()=>void}){
 const [view,setView]=useState<View|null>(null),[draft,setDraft]=useState(""),[confirmed,setConfirmed]=useState(false),[revisions,setRevisions]=useState<Revision[]>([]),[before,setBefore]=useState<number|null>(null),[busy,setBusy]=useState(false),[notice,setNotice]=useState("");
 const mounted=useRef(true),locked=useRef(false),controller=useRef<AbortController|null>(null);
 const base="/api/groups/"+groupId+"/studies/"+studyId+"/assignments/"+assignmentId+"/submissions/"+submission.submissionId+"/feedback";
 function valid(v:unknown):v is Feedback{return object(v)&&id(v.feedbackId)&&v.submissionId===submission.submissionId&&v.assignmentId===assignmentId&&v.studyId===studyId&&v.groupId===groupId&&v.spaceId===spaceId&&v.authorId===submission.authorId&&id(v.reviewerId)&&positive(v.version)&&positive(v.submissionVersion)&&positive(v.assignmentVersion)&&text(v.content)&&v.audience==="AUTHOR_AND_OWNER"&&stamp(v.createdAt)&&stamp(v.updatedAt);}
 function clear(){setView(null);setDraft("");setConfirmed(false);setRevisions([]);setBefore(null);}
 async function run(action:(s:AbortSignal)=>Promise<void>){
  if(locked.current)return;locked.current=true;const c=new AbortController();controller.current=c;setBusy(true);setNotice("");
  try{await action(AbortSignal.any([c.signal,AbortSignal.timeout(20000)]));}
  catch(e){if(mounted.current&&!c.signal.aborted){setView(null);setConfirmed(false);setRevisions([]);setBefore(null);if(e instanceof Failure&&[401,403,404].includes(e.status)){clear();onUnavailable();}else if(e instanceof Failure&&e.status===409)setNotice("검토 대상 제출이나 과제 상태가 바뀌었습니다. 입력은 유지됩니다. 최신 제출을 다시 열어 확인해 주세요.");else if(e instanceof Failure&&e.status===412)setNotice("다른 피드백 수정이 저장되었습니다. 입력은 유지됩니다. 피드백 상태를 새로고침해 확인해 주세요.");else setNotice("피드백 저장 결과를 확인하지 못했습니다. 피드백 상태를 새로고침해 확인해 주세요.");}}
  finally{if(controller.current===c){locked.current=false;if(mounted.current&&!c.signal.aborted)setBusy(false);}}
 }
 async function load(s:AbortSignal){
  const raw=await api(base,s);if(!object(raw)||Object.keys(raw).length!==4||!positive(raw.submissionVersion)||!positive(raw.assignmentVersion)||typeof raw.canWrite!=="boolean"||raw.submissionVersion<submission.version)throw new Error();
  if(raw.feedback!==null){if(!valid(raw.feedback))throw new Error();if(raw.feedback.submissionVersion>raw.submissionVersion||raw.feedback.submissionVersion===raw.submissionVersion&&raw.feedback.assignmentVersion!==raw.assignmentVersion)throw new Error();}
  const value=raw as View;if(!s.aborted){setView(value);setDraft(value.feedback?.content??"");setConfirmed(false);setRevisions([]);setBefore(null);}
 }
 async function history(s:AbortSignal,cursor:number|null=null){
  if(!view)return;const raw=await api(base+"/revisions?limit=5"+(cursor?"&before="+cursor:""),s);if(!object(raw)||!Array.isArray(raw.items)||raw.items.length>5||!(raw.nextBefore===null||positive(raw.nextBefore)))throw new Error();let previous=cursor??Infinity;
  for(const v of raw.items){if(!object(v)||!id(v.revisionId)||!positive(v.version)||v.version>=previous||!id(v.actorId)||!stamp(v.occurredAt)||!valid(v.snapshot)||v.snapshot.feedbackId!==view.feedback?.feedbackId||v.snapshot.version!==v.version||v.snapshot.reviewerId!==v.actorId)throw new Error();previous=v.version;}
  if(raw.nextBefore!==null&&(raw.items.length!==5||raw.nextBefore!==previous))throw new Error();if(!s.aborted){setRevisions(raw.items as Revision[]);setBefore(raw.nextBefore as number|null);}
 }
 function save(e:FormEvent){e.preventDefault();if(!view||!owner||!view.canWrite||!confirmed||!text(draft)||view.submissionVersion!==submission.version||view.assignmentVersion!==submission.assignmentVersion)return;
  const current=view,draftValue=draft;void run(async s=>{const raw=await api(base,s,{method:"PUT",headers:{"If-Match":'"sf'+(current.feedback?.version??0)+'"'},body:JSON.stringify({content:draftValue,submissionVersion:submission.version,assignmentVersion:submission.assignmentVersion})});if(!valid(raw)||raw.submissionVersion!==submission.version||raw.assignmentVersion!==submission.assignmentVersion||raw.content!==draftValue)throw new Error();await load(s);if(!s.aborted)setNotice("확인한 제출 버전의 피드백을 저장했습니다.");});
 }
 useEffect(()=>{mounted.current=true;void run(load);const visible=()=>{if(document.hidden){controller.current?.abort();locked.current=false;clear();setBusy(false);}else void run(load);};document.addEventListener("visibilitychange",visible);return()=>{mounted.current=false;controller.current?.abort();document.removeEventListener("visibilitychange",visible);};},[]);
 const sourceMatches=!!view&&view.submissionVersion===submission.version&&view.assignmentVersion===submission.assignmentVersion;
 const canWrite=!!view&&owner&&view.canWrite&&sourceMatches;
 return <section aria-label="제출물 피드백"><h5>제출물 피드백</h5><p className="muted">이 제출자와 현재 그룹 소유자만 읽는 의견입니다. 피드백에 표시된 제출 버전을 확인해 주세요.</p><p role="status">{notice}</p><button disabled={busy} onClick={()=>void run(load)}>피드백 상태 새로고침</button><button disabled={busy} onClick={onRefreshSubmission}>최신 제출 다시 열기</button>
  {view&&<>{!sourceMatches&&<p>새 제출이 등록되었습니다. 최신 제출을 다시 열어 답안을 확인한 뒤 피드백을 작성해 주세요.</p>}{!view.feedback&&<p>아직 등록된 피드백이 없습니다.</p>}{view.feedback&&<><p>피드백 v{view.feedback.version} · 제출 v{view.feedback.submissionVersion} · 과제 v{view.feedback.assignmentVersion} 기준</p><FeedbackRetraction key={"fr-"+view.feedback.feedbackId+"-"+view.feedback.version} groupId={groupId} spaceId={spaceId} studyId={studyId} assignmentId={assignmentId} submissionId={submission.submissionId} authorId={submission.authorId} feedback={view.feedback} owner={owner} onUnavailable={onUnavailable}/><p className="source-text">{view.feedback.content}</p><p>작성자 {view.feedback.reviewerId} · <time dateTime={view.feedback.updatedAt}>{new Date(view.feedback.updatedAt).toLocaleString("ko-KR")}</time></p>{view.feedback.submissionVersion<view.submissionVersion&&<p>이전 제출에 대한 피드백입니다. 새 답안에 대한 검토 결과는 아직 반영되지 않았습니다.</p>}</>}
  {owner&&!view.canWrite&&<p>보관된 과제나 스터디의 피드백은 조회만 가능합니다.</p>}
  <button disabled={busy||!view.feedback} onClick={()=>void run(s=>history(s))}>피드백 수정 이력 조회</button><ol aria-label="피드백 수정 이력">{revisions.map(v=><li key={v.revisionId}>피드백 v{v.version} · 제출 v{v.snapshot.submissionVersion} · 과제 v{v.snapshot.assignmentVersion} 기준<FeedbackRetraction key={"fr-history-"+v.revisionId} groupId={groupId} spaceId={spaceId} studyId={studyId} assignmentId={assignmentId} submissionId={submission.submissionId} authorId={submission.authorId} feedback={v.snapshot} owner={owner} onUnavailable={onUnavailable}/><p className="source-text">{v.snapshot.content}</p><time dateTime={v.occurredAt}>{new Date(v.occurredAt).toLocaleString("ko-KR")}</time></li>)}</ol><button disabled={busy||!before} onClick={()=>void run(s=>history(s,before))}>이전 피드백 이력 페이지</button></>}
  {owner&&<form aria-label="제출물 피드백 양식" onSubmit={save}><label>피드백 내용<textarea aria-label="제출물 피드백 내용" rows={4} value={draft} disabled={busy||!!view&&!canWrite} onChange={e=>{setDraft(e.target.value);setConfirmed(false);}}/></label><p>{new TextEncoder().encode(draft).length} / 8192 UTF-8 바이트</p><label><input type="checkbox" aria-label="검토한 제출 버전과 피드백 공개 확인" checked={confirmed} disabled={busy||!canWrite} onChange={e=>setConfirmed(e.target.checked)}/>제출 v{submission.version} 답안과 과제 v{submission.assignmentVersion} 지시문을 확인했고, 의견을 이 제출자에게 공유합니다.</label><button disabled={busy||!canWrite||!confirmed||!text(draft)}>{view?.feedback?"피드백 수정 저장":"피드백 저장"}</button></form>}
 </section>;
}
