"use client";
import {useState} from "react";

export function metadataNameQuery(raw:string):string{
 if(Array.from(raw).length>128||/[\u0000-\u001f\u007f-\u009f\ud800-\udfff]/u.test(raw))throw new Error("제어 문자 없이 이름 검색어를 입력해 주세요.");
 const value=raw.normalize("NFC");if(!value||Array.from(value).length>64)throw new Error("이름 검색어는 정규화 후 1~64자로 입력해 주세요.");return value;
}
export default function MetadataNameSearch({label,applied,onApply}:{label:string;applied:string|null;onApply:(q:string|null)=>void}){
 const [draft,setDraft]=useState(""),[error,setError]=useState("");
 return <form className="metadata-name-search" onSubmit={event=>{event.preventDefault();try{const value=metadataNameQuery(draft);setError("");onApply(value)}catch(failure){setError(failure instanceof Error?failure.message:"검색어를 확인해 주세요.")}}}>
  <label>{label} 이름 일부<input aria-label={`${label} 이름 검색어`} value={draft} onChange={event=>setDraft(event.target.value)}/></label>
  <div className="actions"><button type="submit">{label} 이름 검색</button><button type="button" onClick={()=>{setDraft("");setError("");onApply(null)}}>{label} 이름 검색 해제</button></div>
  {error&&<p role="alert">{error}</p>}<p className="metadata-name-applied">적용된 {label} 이름 검색: {applied===null?"전체":applied}</p><p className="muted">대소문자·공백을 구분하는 부분 일치입니다. 검색어는 정규화 후 64자까지 사용할 수 있습니다.</p>
 </form>;
}
