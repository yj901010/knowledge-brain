"use client";
import {useState} from "react";

type Audit={revisionId:string;version:number;state:string;actorId:string;occurredAt:string};
export type ConceptRevision=Audit&{conceptId:string;title:string;description:string};
export type SourceRevision=Audit&{sourceId:string;title:string;url:string|null;citation:string;licenseNote:string};
type Field={label:string;before:string|null;after:string|null};

// Inputs are validated by the owning history reader; comparisons never read current data.
function Comparison<R extends Audit>({revisions,busy,label,id,fields}:{revisions:R[];busy:boolean;label:string;id:(row:R)=>string;fields:(from:R,to:R)=>Field[]}){
 const [fromId,setFromId]=useState(revisions[1]?.revisionId??""),[toId,setToId]=useState(revisions[0]?.revisionId??""),[changedOnly,setChangedOnly]=useState(false);
 const from=revisions.find(row=>row.revisionId===fromId),to=revisions.find(row=>row.revisionId===toId);
 const comparable=from&&to&&id(from)===id(to)&&from.version<to.version;
 const rows=comparable?fields(from,to):[],changed=rows.filter(row=>row.before!==row.after).length;
 function selector(text:string,value:string,setValue:(value:string)=>void){return <label>{text}<select aria-label={text} value={value} disabled={busy||revisions.length<2} onChange={event=>setValue(event.target.value)}><option value="">버전을 선택하세요</option>{revisions.map(row=><option key={row.revisionId} value={row.revisionId}>v{row.version} · {row.state}</option>)}</select></label>}
 function content(value:string|null){return value===null?<em>미입력 (null)</em>:value===""?<em>빈 문자열</em>:<pre>{value}</pre>}
 return <section className="knowledge-revision-comparison" aria-label={`${label} 버전 비교`}><h4>{label} 버전 비교</h4>
  <p className="muted">불러온 수정 이력의 이전·이후 내용을 비교합니다. 저장된 공백과 줄바꿈도 구분합니다.</p>
  <div className="knowledge-comparison-selectors">{selector("비교 기준 버전",fromId,setFromId)}{selector("비교 대상 버전",toId,setToId)}</div>
  {revisions.length<2?<p role="status">비교하려면 두 개 이상의 {label} 버전이 필요합니다.</p>:!comparable?<p role="status">기준보다 나중의 버전을 비교 대상으로 선택해 주세요.</p>:<>
   <p className="muted">{label} ID: {id(from)}</p>
   <div className="knowledge-comparison-audit">{[{label:"이전",row:from},{label:"이후",row:to}].map(({label,row})=><div key={label} aria-label={`${label} 버전 정보`}><h5>{label} · v{row.version}</h5><dl><dt>수정 ID</dt><dd>{row.revisionId}</dd><dt>수정자 ID</dt><dd>{row.actorId}</dd><dt>수정 시각</dt><dd><time dateTime={row.occurredAt}>{row.occurredAt}</time></dd></dl></div>)}</div>
   <p role="status">v{from.version} → v{to.version} · 변경된 항목 {changed}개</p>
   <label className="knowledge-comparison-filter"><input type="checkbox" checked={changedOnly} disabled={busy} onChange={event=>setChangedOnly(event.target.checked)}/>변경된 항목만 보기</label>
   {changedOnly&&changed===0?<p>선택한 두 버전의 {label} 내용이 같습니다.</p>:<table><caption>{label} 내용 비교 · v{from.version} → v{to.version}</caption><thead><tr><th scope="col">항목</th><th scope="col">이전 · v{from.version}</th><th scope="col">이후 · v{to.version}</th></tr></thead><tbody>{rows.filter(row=>!changedOnly||row.before!==row.after).map(row=><tr key={row.label} data-changed={row.before!==row.after}><th scope="row">{row.label}<small>{row.before!==row.after?"변경":"동일"}</small></th><td>{content(row.before)}</td><td>{content(row.after)}</td></tr>)}</tbody></table>}
  </>}
 </section>;
}
export function ConceptRevisionComparison({revisions,busy}:{revisions:ConceptRevision[];busy:boolean}){
 return <Comparison revisions={revisions} busy={busy} label="개념" id={row=>row.conceptId} fields={(from,to)=>[
  {label:"제목",before:from.title,after:to.title},{label:"설명",before:from.description,after:to.description},{label:"상태",before:from.state,after:to.state}
 ]}/>;
}
export function SourceRevisionComparison({revisions,busy}:{revisions:SourceRevision[];busy:boolean}){
 return <Comparison revisions={revisions} busy={busy} label="출처" id={row=>row.sourceId} fields={(from,to)=>[
  {label:"제목",before:from.title,after:to.title},{label:"URL",before:from.url,after:to.url},{label:"인용 정보",before:from.citation,after:to.citation},{label:"라이선스 메모",before:from.licenseNote,after:to.licenseNote},{label:"상태",before:from.state,after:to.state}
 ]}/>;
}
