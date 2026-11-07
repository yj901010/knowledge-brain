"use client";
import {useState} from "react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
function safeUrl(value:string){try{if(/[\u0000-\u0020\u007f]/u.test(value))return "";const url=new URL(value);if(!["http:","https:"].includes(url.protocol)||url.username||url.password)return "";return url.href}catch{return ""}}
export default function MarkdownView({markdown,context,draft=false,forcePreview=false}:{markdown:string;context:string;draft?:boolean;forcePreview?:boolean}){
 const [preview,setPreview]=useState(false);
 return <div className="markdown-view">{!forcePreview&&<button type="button" aria-pressed={preview} onClick={()=>setPreview(!preview)}>{preview?`${context} 원문 보기`:`${context} 미리보기`}</button>}{forcePreview||preview?<><div className="markdown-preview" role="region" aria-label={`${context} Markdown 미리보기`}><Markdown remarkPlugins={[remarkGfm]} urlTransform={safeUrl} components={{th:({children,style,align})=><th className={`markdown-align-${align||style?.textAlign||"left"}`}>{children}</th>,td:({children,style,align})=><td className={`markdown-align-${align||style?.textAlign||"left"}`}>{children}</td>,a:({href,children})=>href?<a href={href} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer">{children}</a>:<span>{children}</span>,img:({alt})=><span className="markdown-image">[이미지: {alt||"대체 텍스트 없음"}]</span>,input:({checked})=><input type="checkbox" checked={!!checked} readOnly disabled aria-label={checked?"완료된 항목":"미완료 항목"}/>}}>{markdown}</Markdown></div><p className="muted">이미지는 대체 텍스트로 표시합니다. Wiki Link는 연결된 노트 목록에서 탐색합니다.</p></>:!draft&&<pre>{markdown}</pre>}</div>;
}
