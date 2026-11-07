"use client";
import {useEffect,useRef,useState} from "react";

type Pin={sourceId:string;revisionId:string;version:number;title:string;url:string|null;citation:string;licenseNote:string};
type Source=Omit<Pin,"revisionId">&{spaceId:string;visibility:"PRIVATE";state:"ACTIVE";createdAt:string;updatedAt:string};
function version(value:unknown):value is number{return typeof value==="number"&&Number.isSafeInteger(value)&&value>0}
function text(value:unknown,max:number):value is string{return typeof value==="string"&&Array.from(value).length<=max&&!/[\u0000\ud800-\udfff]/u.test(value)}
function safeUrl(value:unknown):value is string|null{
 if(value===null)return true;if(typeof value!=="string"||!value||value.length>2048||/[^\x21-\x7e]|\\/.test(value)||!/^https?:\/\//i.test(value))return false;
 try{const url=new URL(value),authority=value.match(/^https?:\/\/([^/?#]+)/i)?.[1];if(!authority||url.username||url.password||/[@%]/.test(authority)||!url.hostname||!/^https?:$/.test(url.protocol))return false;const host=url.hostname;if(!(host.startsWith("[")&&host.endsWith("]"))&&!/^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.?$/i.test(host))return false;const port=authority.startsWith("[")?authority.slice(authority.indexOf("]")+1):authority.includes(":")?authority.slice(authority.lastIndexOf(":")):"";return !port||/^:[0-9]+$/.test(port)&&Number(port.slice(1))>=1&&Number(port.slice(1))<=65535}catch{return false}
}
function date(value:unknown){return typeof value==="string"&&Number.isFinite(Date.parse(value))}

export default function EvidenceSourceComparison({spaceId,pin,onDenied}:{spaceId:string;pin:Pin;onDenied:()=>void}){
 const [current,setCurrent]=useState<Source|null>(null),[busy,setBusy]=useState(false),[notice,setNotice]=useState("");
 const alive=useRef(true),epoch=useRef(0),request=useRef<{controller:AbortController;timer:ReturnType<typeof setTimeout>}|null>(null);
 function stop(){epoch.current++;if(request.current){clearTimeout(request.current.timer);request.current.controller.abort()}request.current=null}
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;stop()}},[]);
 async function inspect(){
  stop();const generation=epoch.current,controller=new AbortController(),timer=setTimeout(()=>controller.abort(),8000);request.current={controller,timer};setBusy(true);setCurrent(null);setNotice("");
  const relevant=()=>alive.current&&epoch.current===generation;
  try{const response=await fetch(`/api/spaces/${spaceId}/sources/${pin.sourceId}`,{credentials:"same-origin",cache:"no-store",redirect:"error",signal:controller.signal});if(!relevant()||controller.signal.aborted)return;if([401,403].includes(response.status)){stop();setCurrent(null);setNotice("");setBusy(false);onDenied();return}if(response.status===404){setNotice("현재 출처가 폐기되었거나 조회할 수 없습니다. 고정한 인용 정보와 연결 이력은 유지됩니다.");return}if(!response.ok)throw new Error();const row:Source=await response.json();if(!row||row.sourceId!==pin.sourceId||row.spaceId!==spaceId||row.visibility!=="PRIVATE"||row.state!=="ACTIVE"||!version(row.version)||!text(row.title,200)||!row.title||!safeUrl(row.url)||!text(row.citation,4000)||!text(row.licenseNote,1000)||!date(row.createdAt)||!date(row.updatedAt)||response.headers.get("ETag")!==`"src${row.version}"`)throw new Error();if(relevant()&&!controller.signal.aborted)setCurrent(row)}
  catch{if(relevant()){setCurrent(null);setNotice("현재 출처 비교 정보를 불러오지 못했습니다. 다시 조회해 주세요.")}}finally{clearTimeout(timer);if(relevant()){request.current=null;setBusy(false)}}
 }
 const fields:[string,keyof Pick<Pin,"version"|"title"|"url"|"citation"|"licenseNote">][]=[["버전","version"],["제목","title"],["URL","url"],["인용 정보","citation"],["라이선스 메모","licenseNote"]];
 return <section className="evidence-source-comparison" aria-label="고정 인용과 현재 출처 비교"><h5>고정 인용과 현재 출처 비교</h5><p className="muted">현재 정보는 비교 버튼을 누른 시점의 출처입니다. 비교해도 고정한 정보는 바뀌지 않습니다.</p><button type="button" disabled={busy} onClick={()=>void inspect()}>현재 출처와 비교</button>{busy&&<p className="muted">현재 출처 비교 중…</p>}{notice&&<p role="status">{notice}</p>}
  {current&&<><p className="muted">고정한 출처 v{pin.version} · 조회한 현재 출처 v{current.version}</p><table><caption>선택한 고정 출처와 조회한 현재 출처의 항목별 비교</caption><thead><tr><th scope="col">항목</th><th scope="col">고정한 정보</th><th scope="col">조회한 현재 정보</th><th scope="col">비교</th></tr></thead><tbody>{fields.map(([label,key])=><tr key={key}><th scope="row">{label}</th><td>{pin[key]===null?"없음":pin[key]===""?"입력 없음":String(pin[key])}</td><td>{current[key]===null?"없음":current[key]===""?"입력 없음":String(current[key])}</td><td>{pin[key]===current[key]?"동일":"다름"}</td></tr>)}</tbody></table><small>조회한 출처의 최근 수정: {new Date(current.updatedAt).toLocaleString()}</small></>}
 </section>;
}
