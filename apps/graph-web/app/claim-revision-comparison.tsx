"use client";
import {useState} from "react";
import {type ClaimObject,claimObjectKinds,claimObjectLabels} from "./claim-object";

export type ClaimRevision={revisionId:string;claimId:string;subjectConceptId:string;predicateConceptId:string;object:ClaimObject;version:number;state:"ACTIVE"|"RETIRED";verificationState:"DRAFT";actorId:string;occurredAt:string};

// Compare immutable snapshots only. Current Concept labels are not historical facts.
export default function ClaimRevisionComparison({revisions,busy}:{revisions:ClaimRevision[];busy:boolean}){
 const [fromId,setFromId]=useState(revisions[1]?.revisionId??""),[toId,setToId]=useState(revisions[0]?.revisionId??""),[changedOnly,setChangedOnly]=useState(false);
 const from=revisions.find(row=>row.revisionId===fromId),to=revisions.find(row=>row.revisionId===toId);
 const comparable=from&&to&&from.claimId===to.claimId&&from.subjectConceptId===to.subjectConceptId&&from.version<to.version;
 const kind=(value:ClaimObject)=>claimObjectLabels[claimObjectKinds.indexOf(value.type)];
 const objectValue=(value:ClaimObject)=>value.type==="CONCEPT"?value.conceptId!:value.value!;
 const fields=comparable?[
  {label:"서술 개념 ID",before:from.predicateConceptId,after:to.predicateConceptId},
  {label:"대상 종류",before:kind(from.object),after:kind(to.object)},
  {label:"대상 ID 또는 값",before:objectValue(from.object),after:objectValue(to.object)},
  {label:"관계 상태",before:from.state,after:to.state},
  {label:"검증 상태",before:from.verificationState,after:to.verificationState}
 ]:[];
 const changed=fields.filter(row=>row.before!==row.after).length;
 function selector(label:string,value:string,setValue:(id:string)=>void){return <label>{label}<select aria-label={label} value={value} disabled={busy||revisions.length<2} onChange={event=>setValue(event.target.value)}><option value="">버전을 선택하세요</option>{revisions.map(row=><option key={row.revisionId} value={row.revisionId}>v{row.version} · {row.state}</option>)}</select></label>}
 return <section className="claim-revision-comparison" aria-label="관계 버전 비교"><h4>관계 버전 비교</h4>
  <p className="muted">불러온 이력에서 이전·이후 버전을 선택하세요. 개념은 당시 저장된 ID로 비교합니다. 인용 근거와 조건은 각각의 수정 이력에서 확인할 수 있습니다.</p>
  <div className="claim-comparison-selectors">{selector("비교 기준 버전",fromId,setFromId)}{selector("비교 대상 버전",toId,setToId)}</div>
  {revisions.length<2?<p role="status">비교하려면 두 개 이상의 관계 버전이 필요합니다.</p>:!comparable?<p role="status">기준보다 나중의 버전을 비교 대상으로 선택해 주세요.</p>:<>
   <p className="muted">관계 ID: {from.claimId}<br/>주어 개념 ID: {from.subjectConceptId}</p>
   <div className="claim-comparison-audit">{[{label:"이전",row:from},{label:"이후",row:to}].map(({label,row})=><div key={label} aria-label={`${label} 버전 정보`}><h5>{label} · v{row.version}</h5><dl><dt>수정 ID</dt><dd>{row.revisionId}</dd><dt>수정자 ID</dt><dd>{row.actorId}</dd><dt>수정 시각</dt><dd><time dateTime={row.occurredAt}>{row.occurredAt}</time></dd></dl></div>)}</div>
   <p role="status">v{from.version} → v{to.version} · 변경된 항목 {changed}개</p>
   <label className="claim-comparison-filter"><input type="checkbox" checked={changedOnly} onChange={event=>setChangedOnly(event.target.checked)}/>변경된 항목만 보기</label>
   {changedOnly&&changed===0?<p>선택한 두 버전의 관계 내용이 같습니다.</p>:<table><caption>관계 내용 비교 · v{from.version} → v{to.version}</caption><thead><tr><th scope="col">항목</th><th scope="col">이전 · v{from.version}</th><th scope="col">이후 · v{to.version}</th></tr></thead><tbody>{fields.filter(row=>!changedOnly||row.before!==row.after).map(row=><tr key={row.label} data-changed={row.before!==row.after}><th scope="row">{row.label}<small>{row.before!==row.after?"변경":"동일"}</small></th><td><pre>{row.before}</pre></td><td><pre>{row.after}</pre></td></tr>)}</tbody></table>}
  </>}
 </section>;
}
