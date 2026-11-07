"use client";
import {type ClaimObject,type ClaimObjectKind,claimObjectKinds,claimObjectLabels,validClaimObject,claimObjectText,claimObjectInput} from "./claim-object";
import {useEffect,useRef,useState} from "react";
import ClaimEvidence from "./claim-evidence";

type Role="OBJECT"|"PREDICATE";
type Triple={subjectConceptId:string;predicateConceptId:string;object:ClaimObject};
type Claim=Triple&{claimId:string;spaceId:string;visibility:"PRIVATE";state:"ACTIVE";version:number;verificationState:"DRAFT";createdAt:string;updatedAt:string};
type Usage={claim:Claim;subjectTitle:string;predicateTitle:string;objectTitle:string};
type Page={items:Usage[];nextAfter:string|null};
type Revision=Triple&{revisionId:string;claimId:string;version:number;state:"ACTIVE"|"RETIRED";verificationState:"DRAFT";actorId:string;occurredAt:string};
type Kind="catalog"|"detail";
type Operation={epoch:number;scope:string;controller:AbortController;timer:ReturnType<typeof setTimeout>};
function id(value:unknown):value is string{return typeof value==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)}
function version(value:unknown):value is number{return typeof value==="number"&&Number.isSafeInteger(value)&&value>0}
function title(value:unknown):value is string{return typeof value==="string"&&!!value.trim()&&Array.from(value).length<=200&&!/[\u0000\ud800-\udfff]/u.test(value)}
function date(value:unknown){return typeof value==="string"&&Number.isFinite(Date.parse(value))}
function triple(row:Triple){return row&&id(row.subjectConceptId)&&id(row.predicateConceptId)&&validClaimObject(row.object)}
function claim(row:Claim,space:string){return triple(row)&&id(row.claimId)&&row.spaceId===space&&row.visibility==="PRIVATE"&&row.state==="ACTIVE"&&version(row.version)&&row.verificationState==="DRAFT"&&date(row.createdAt)&&date(row.updatedAt)}
function readPage(raw:unknown,space:string,concept:string,role:Role,cursor:string|null):Page{
 const page=raw as Page;
 if(!page||!Array.isArray(page.items)||page.items.length>20||page.items.some((row,index)=>!row||!claim(row.claim,space)||!title(row.subjectTitle)||!title(row.predicateTitle)||(row.claim.object.type==="CONCEPT"?!title(row.objectTitle):row.objectTitle!==row.claim.object.value)||(role==="OBJECT"?row.claim.object.conceptId:row.claim.predicateConceptId)!==concept||(cursor!==null&&row.claim.claimId<=cursor)||(index>0&&row.claim.claimId<=page.items[index-1].claim.claimId)))throw new Error();
 if(page.nextAfter!==null&&(!id(page.nextAfter)||page.items.length!==20||page.nextAfter!==page.items[page.items.length-1]?.claim.claimId||(cursor!==null&&page.nextAfter<=cursor)))throw new Error();return page;
}
async function api(path:string,signal:AbortSignal){return fetch(path,{credentials:"same-origin",cache:"no-store",redirect:"error",signal})}
function TripleIds({row}:{row:Triple}){return <p className="muted">주제 개념 ID: {row.subjectConceptId}<br/>서술 개념 ID: {row.predicateConceptId}<br/>대상: {claimObjectText(row.object)}</p>}

export default function ClaimUsages({spaceId,conceptId,parentRetired=false,onDenied}:{spaceId:string;conceptId:string;parentRetired?:boolean;onDenied:()=>void}){
 const path=`/api/spaces/${spaceId}/concepts/${conceptId}/claim-usages`;
 const [role,setRole]=useState<Role>("OBJECT"),[page,setPage]=useState<Page|null>(null),[catalogBusy,setCatalogBusy]=useState(false),[catalogNotice,setCatalogNotice]=useState("");
 const [selected,setSelected]=useState<Usage|null>(null),[value,setValue]=useState<Claim|null>(null),[mode,setMode]=useState<"read"|"history">("read"),[busy,setBusy]=useState(false),[notice,setNotice]=useState("");
 const [revisions,setRevisions]=useState<Revision[]>([]),[revision,setRevision]=useState<Revision|null>(null),[before,setBefore]=useState<number|null>(null);
 const [evidenceOpen,setEvidenceOpen]=useState(false);
 const scope=`${path}:${role}`,alive=useRef(true),currentScope=useRef(scope),chosen=useRef<string|null>(null),epochs=useRef<Record<Kind,number>>({catalog:0,detail:0}),requests=useRef<Record<Kind,Operation|null>>({catalog:null,detail:null});currentScope.current=scope;
 function stop(kind:Kind){epochs.current[kind]++;const operation=requests.current[kind];if(operation){clearTimeout(operation.timer);operation.controller.abort()}requests.current[kind]=null}
 function working(kind:Kind,state:boolean){if(kind==="catalog")setCatalogBusy(state);else setBusy(state)}
 function start(kind:Kind){stop(kind);const controller=new AbortController(),operation={epoch:epochs.current[kind],scope,controller,timer:setTimeout(()=>controller.abort(),8000)};requests.current[kind]=operation;working(kind,true);return operation}
 function relevant(operation:Operation,kind:Kind){return alive.current&&currentScope.current===operation.scope&&epochs.current[kind]===operation.epoch}
 function active(operation:Operation,kind:Kind){return relevant(operation,kind)&&!operation.controller.signal.aborted}
 function finish(operation:Operation,kind:Kind){clearTimeout(operation.timer);if(relevant(operation,kind)){requests.current[kind]=null;working(kind,false)}}
 function clearDetail(){setValue(null);setMode("read");setNotice("");setRevisions([]);setRevision(null);setBefore(null)}
 function clearPrivate(){for(const kind of ["catalog","detail"] as Kind[]){stop(kind);working(kind,false)}setPage(null);setCatalogNotice("");chosen.current=null;setSelected(null);setEvidenceOpen(false);clearDetail()}
 function chooseRole(next:Role){if(next===role)return;clearPrivate();setRole(next)}
 function denied(response:Response,missing=false){if(![401,403].includes(response.status)&&!(missing&&response.status===404))return false;clearPrivate();onDenied();return true}
 function detailPath(row:Usage){return `/api/spaces/${spaceId}/concepts/${row.claim.subjectConceptId}/claims/${row.claim.claimId}`}
 async function load(append=false){
  const cursor=append?page?.nextAfter??null:null;if(append&&!cursor)return;const operation=start("catalog");setCatalogNotice("");if(!append)setPage(null);
  try{const response=await api(`${path}?role=${role}&limit=20${cursor?`&after=${cursor}`:""}`,operation.controller.signal);if(!active(operation,"catalog")||denied(response,true))return;if(!response.ok)throw new Error();const data=readPage(await response.json(),spaceId,conceptId,role,cursor);if(!active(operation,"catalog"))return;setPage(previous=>({nextAfter:data.nextAfter,items:[...new Map([...(append?previous?.items??[]:[]),...data.items].map(row=>[row.claim.claimId,row])).values()]}))}
  catch{if(relevant(operation,"catalog")){setPage(null);setCatalogNotice("이 개념을 사용하는 관계를 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish(operation,"catalog")}
 }
 useEffect(()=>{alive.current=true;clearPrivate();void load();return()=>{alive.current=false;stop("catalog");stop("detail")}},[scope]);
 useEffect(()=>{if(parentRetired)void load()},[parentRetired]);
 async function read(row:Usage){
  if(chosen.current!==row.claim.claimId)setEvidenceOpen(false);clearDetail();setSelected(row);chosen.current=row.claim.claimId;const operation=start("detail");
  try{const response=await api(detailPath(row),operation.controller.signal);if(!active(operation,"detail")||chosen.current!==row.claim.claimId||denied(response))return;if(response.status===404){setNotice("현재 활성 관계를 조회할 수 없습니다. 이미 선택한 관계의 수정 이력을 확인할 수 있습니다.");return}if(!response.ok)throw new Error();const data:Claim=await response.json();if(!claim(data,spaceId)||data.claimId!==row.claim.claimId||data.subjectConceptId!==row.claim.subjectConceptId||response.headers.get("ETag")!==`"cl${data.version}"`)throw new Error();if(active(operation,"detail")&&chosen.current===row.claim.claimId)setValue(data)}
  catch{if(relevant(operation,"detail")&&chosen.current===row.claim.claimId){setValue(null);setNotice("사용 관계 상세를 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish(operation,"detail")}
 }
 function close(){stop("detail");chosen.current=null;setSelected(null);setEvidenceOpen(false);clearDetail();setBusy(false)}
 async function history(append=false){
  if(!selected)return;const row=selected,cursor=append?before:null;if(append&&!cursor)return;const operation=start("detail");setMode("history");setValue(null);setNotice("");if(!append){setRevisions([]);setRevision(null);setBefore(null)}
  try{const response=await api(`${detailPath(row)}/revisions?limit=5${cursor?`&before=${cursor}`:""}`,operation.controller.signal);if(!active(operation,"detail")||chosen.current!==row.claim.claimId||denied(response))return;if(!response.ok)throw new Error();const rows:Revision[]=await response.json();if(!Array.isArray(rows)||rows.length>5||rows.some((item,index)=>!triple(item)||!id(item.revisionId)||item.claimId!==row.claim.claimId||item.subjectConceptId!==row.claim.subjectConceptId||!version(item.version)||!["ACTIVE","RETIRED"].includes(item.state)||item.verificationState!=="DRAFT"||!id(item.actorId)||!date(item.occurredAt)||(cursor!==null&&item.version>=cursor)||(index>0&&item.version>=rows[index-1].version)))throw new Error();if(!active(operation,"detail")||chosen.current!==row.claim.claimId)return;setRevisions(previous=>append?[...previous,...rows.filter(item=>!previous.some(prior=>prior.revisionId===item.revisionId))]:rows);if(!append)setRevision(rows[0]??null);setBefore(rows.length===5?rows[rows.length-1].version:null)}
  catch{if(relevant(operation,"detail")&&chosen.current===row.claim.claimId){setRevisions([]);setRevision(null);setBefore(null);setNotice("사용 관계 수정 이력을 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{finish(operation,"detail")}
 }
 return <section className="claim-usages" aria-label="이 개념을 사용하는 관계"><h3>이 개념을 사용하는 관계</h3><p className="muted">선택한 개념을 대상이나 서술로 사용하는 활성 관계를 확인합니다. 제목은 목록을 조회한 시점의 현재 제목입니다.</p>{parentRetired&&<p className="muted">선택한 개념이 폐기되어 활성 사용 관계는 표시되지 않습니다. 이미 선택한 관계의 수정 이력은 확인할 수 있습니다.</p>}
  <div className="actions"><button type="button" aria-pressed={role==="OBJECT"} onClick={()=>chooseRole("OBJECT")}>대상으로 사용한 관계</button><button type="button" aria-pressed={role==="PREDICATE"} onClick={()=>chooseRole("PREDICATE")}>서술로 사용한 관계</button><button type="button" disabled={catalogBusy} onClick={()=>void load()}>개념 사용 관계 새로고침</button></div>
  {catalogBusy&&<p className="muted">개념 사용 관계 확인 중…</p>}{catalogNotice&&<p role="status">{catalogNotice}</p>}{page&&<><p className="muted">불러온 활성 사용 관계 {page.items.length}개 · 관계 생성 순서</p>{!page.items.length&&<p>이 역할로 개념을 사용하는 활성 관계가 없습니다.</p>}<div className="claim-usage-results">{page.items.map(row=><button type="button" key={row.claim.claimId} aria-label={`사용 관계 열기: ${row.claim.claimId}`} onClick={()=>void read(row)}><span>{row.subjectTitle} → {row.predicateTitle} → {row.objectTitle}</span><small>현재 관계 v{row.claim.version} · DRAFT</small><small>주제 개념 ID: {row.claim.subjectConceptId}<br/>관계 ID: {row.claim.claimId}</small></button>)}</div>{page.nextAfter&&<button type="button" disabled={catalogBusy} onClick={()=>void load(true)}>개념 사용 관계 더 보기</button>}</>}
  {selected&&<section className="claim-usage-detail" aria-label="선택한 개념 사용 관계"><button type="button" onClick={close}>사용 관계 상세 닫기</button><small>선택한 관계 ID: {selected.claim.claimId}</small>{notice&&<p role="status">{notice}</p>}{busy&&<p className="muted">사용 관계 확인 중…</p>}<div className="actions"><button type="button" disabled={busy} onClick={()=>void read(selected)}>사용 관계 상세 다시 조회</button><button type="button" disabled={busy} onClick={()=>void history()}>사용 관계 수정 이력</button></div>
   {mode==="read"?value&&<><h4>관계 v{value.version} · {value.state} · {value.verificationState}</h4><TripleIds row={value}/><small>최근 수정: {new Date(value.updatedAt).toLocaleString()}</small></>:<><h4>사용 관계 수정 이력</h4><p className="muted">이력에는 당시 관계의 개념 ID를 표시합니다. 현재 제목을 과거 관계의 제목으로 표시하지 않습니다.</p><div className="claim-usage-revisions">{revisions.map(row=><button type="button" key={row.revisionId} aria-pressed={revision?.revisionId===row.revisionId} disabled={busy} onClick={()=>setRevision(row)}>관계 v{row.version} · {row.state}</button>)}</div>{before&&<button type="button" disabled={busy} onClick={()=>void history(true)}>이전 사용 관계 수정 더 보기</button>}{revision&&<><h4>관계 v{revision.version} · {revision.state} · {revision.verificationState}</h4><TripleIds row={revision}/><small>수정 이력 ID: {revision.revisionId}<br/>{new Date(revision.occurredAt).toLocaleString()}</small></>}{!busy&&!revisions.length&&!notice&&<p>불러온 사용 관계 수정 이력이 없습니다.</p>}<button type="button" disabled={busy} onClick={()=>void history()}>사용 관계 이력 새로고침</button></>}
   <button type="button" aria-expanded={evidenceOpen} onClick={()=>setEvidenceOpen(previous=>!previous)}>{evidenceOpen?"사용 관계 근거 닫기":"사용 관계 근거 확인"}</button>
   {evidenceOpen&&<ClaimEvidence key={`evidence:${spaceId}:${selected.claim.subjectConceptId}:${selected.claim.claimId}`} spaceId={spaceId} subjectConceptId={selected.claim.subjectConceptId} claimId={selected.claim.claimId} parentRetired={parentRetired} onDenied={()=>{clearPrivate();onDenied()}}/>}
  </section>}
 </section>;
}
