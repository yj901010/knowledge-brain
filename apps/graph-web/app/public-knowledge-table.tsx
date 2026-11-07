"use client";
import {useMemo,useState} from "react";

export type PublicKnowledgeItem={entityId:string;kind:string;title:string;version:number;state:string;content:Record<string,string|number>;qualifierPins:Record<string,number>;effectiveLevel:string;needsReview:boolean};
export const publicKindNames:Record<string,string>={CONCEPT:"개념",ALIAS:"별칭",CLAIM:"주장",SOURCE:"출처",EVIDENCE:"근거",MAPPING:"외부 매핑",VERIFICATION:"검증",QUALIFIER:"한정자"};
export const publicLevels:Record<string,string>={DRAFT:"초안",SOURCED:"출처 확인",COMMUNITY_REVIEWED:"커뮤니티 검토",EXPERT_VERIFIED:"전문가 검증"};
type Column="title"|"kind"|"version";

export default function PublicKnowledgeTable({items,references,caption,selected,onSelect,busy=false}:{items:PublicKnowledgeItem[];references:PublicKnowledgeItem[];caption:string;selected:string|null;onSelect:(item:PublicKnowledgeItem)=>void;busy?:boolean}){
 const [sort,setSort]=useState<{column:Column;descending:boolean}>({column:"title",descending:false});
 const labels=useMemo(()=>new Map(references.map(n=>[n.entityId,n.title])),[references]);
 const rows=useMemo(()=>[...items].sort((a,b)=>{
  const order=sort.column==="version"?a.version-b.version:
   (sort.column==="kind"?publicKindNames[a.kind]||a.kind:a.title).localeCompare(sort.column==="kind"?publicKindNames[b.kind]||b.kind:b.title,"ko");
  return (sort.descending?-order:order)||a.entityId.localeCompare(b.entityId);
 }),[items,sort]);
 function order(column:Column){setSort(old=>({column,descending:old.column===column?!old.descending:false}))}
 function reference(value:string|number|undefined){return value===undefined?"—":labels.get(String(value))??String(value)}
 function summary(item:PublicKnowledgeItem){const c=item.content;switch(item.kind){
  case "CONCEPT":return String(c.description||"설명 없음");
  case "CLAIM":return `${reference(c.subjectId)} → ${reference(c.predicateId)} → ${c.objectType==="CONCEPT"?reference(c.objectValue):String(c.objectValue??"")}`;
  case "QUALIFIER":return `${reference(c.claimId)} · ${reference(c.predicateId)}: ${c.valueType==="CONCEPT"?reference(c.value):String(c.value??"")}`;
  case "ALIAS":return `${String(c.label??item.title)} · ${reference(c.conceptId)}`;
  case "SOURCE":return String(c.citation||c.title||c.url||"서지 정보 없음");
  case "EVIDENCE":return `${reference(c.claimId)} · ${reference(c.sourceId)}`;
  case "MAPPING":return `${String(c.system??"")} · ${String(c.code??"")} · ${String(c.mappingState??"")}`;
  case "VERIFICATION":return `${reference(c.claimId)} · ${publicLevels[String(c.level)]??String(c.level??"")}`;
  default:return "상세에서 확인";
 }}
 function heading(column:Column,title:string){return <th scope="col" aria-sort={sort.column===column?sort.descending?"descending":"ascending":"none"}><button type="button" onClick={()=>order(column)} aria-label={`${caption} ${title} 정렬`}>{title}<span aria-hidden="true">{sort.column===column?sort.descending?" ↓":" ↑":" ↕"}</span></button></th>}
 return <div className="knowledge-table-scroll" role="region" aria-label={`${caption} 가로 스크롤`} tabIndex={0}>
  <table className="public-knowledge-table"><caption>{caption} · 불러온 {rows.length}개 안에서 정렬</caption>
   <thead><tr>{heading("title","제목")}{heading("kind","종류")}<th scope="col">주요 내용</th><th scope="col">현재 검증</th>{heading("version","원본 버전")}</tr></thead>
   <tbody>{rows.map(item=><tr key={item.entityId} data-selected={selected===item.entityId}>
    <th scope="row"><button type="button" disabled={busy} aria-label={`공개 표 자료 열기: ${item.title}`} aria-pressed={selected===item.entityId} onClick={()=>onSelect(item)}>{item.title}</button></th>
    <td>{publicKindNames[item.kind]??item.kind}</td><td className="knowledge-table-summary"><span>{summary(item)}</span></td>
    <td>{item.effectiveLevel?publicLevels[item.effectiveLevel]??item.effectiveLevel:"해당 없음"}{item.needsReview&&<strong className="knowledge-review-needed">재검토 필요</strong>}</td><td>v{item.version}</td>
   </tr>)}{!rows.length&&<tr><td colSpan={5}>현재 표시할 자료가 없습니다.</td></tr>}</tbody>
  </table>
 </div>;
}
