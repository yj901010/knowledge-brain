"use client";
import {useEffect,useRef,useState} from "react";
import {usePathname} from "next/navigation";
import {groupAreas,requestGroupArea,validGroupArea,type GroupArea} from "./groups/group-sections";
import {knowledgeAreas,requestKnowledgeArea,validKnowledgeArea,type KnowledgeArea} from "./knowledge/knowledge-sections";
import {roadmapAreas,requestRoadmapArea,validRoadmapArea,type RoadmapArea} from "./roadmaps/roadmap-sections";
import {personalAreas,usePersonalWorkspace} from "./personal-workspace";

const routes=[
 {href:"/graph",title:"개인 브레인",en:"Personal",icon:"graph"},
 {href:"/graph/knowledge",title:"공개 지식",en:"Knowledge",icon:"orbit"},
 {href:"/graph/roadmaps",title:"학습 로드맵",en:"Roadmaps",icon:"route"},
 {href:"/graph/groups",title:"그룹 공간",en:"Groups",icon:"people"}
];
const sections=personalAreas;
export function Icon({name}:{name:string}){return <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">{name==="graph"?<><path d="m6 7 11 4M6 7l4 11m7-7-7 7"/><circle cx="6" cy="7" r="3"/><circle cx="17" cy="11" r="3"/><circle cx="10" cy="18" r="2"/></>:name==="orbit"?<><circle cx="12" cy="12" r="3"/><ellipse cx="12" cy="12" rx="10" ry="5" transform="rotate(-40 12 12)"/><path d="M7 3a10 10 0 0 1 10 18"/></>:name==="route"?<><circle cx="6" cy="5" r="2"/><circle cx="18" cy="19" r="2"/><path d="M6 7v6a4 4 0 0 0 4 4h4a4 4 0 0 0 0-8h-1"/></>:name==="people"?<><circle cx="9" cy="8" r="3"/><path d="M3 20v-2a6 6 0 0 1 12 0v2m2-15a3 3 0 0 1 0 6m2 9v-2a6 6 0 0 0-2-4"/></>:name==="search"?<><circle cx="10" cy="10" r="6"/><path d="m15 15 6 6"/></>:name==="sun"?<><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/></>:name==="moon"?<path d="M20 15a8 8 0 0 1-11-11A9 9 0 1 0 20 15Z"/>:name==="menu"?<path d="M4 6h16M4 12h16M4 18h16"/>:name==="close"?<path d="m6 6 12 12M6 18 18 6"/>:<><rect x="3" y="4" width="18" height="16" rx="3"/><path d="M9 4v16"/></>}</svg>}

export default function WorkspaceShell({children}:{children:React.ReactNode}){
 const personal=usePersonalWorkspace();
 const pathname=usePathname()||"/";const current=pathname.includes("/knowledge")?1:pathname.includes("/roadmaps")?2:pathname.includes("/groups")?3:0;
 const [mobile,setMobile]=useState(false);
 const [theme,setTheme]=useState("dark"),[collapsed,setCollapsed]=useState(false),[menu,setMenu]=useState(false),[palette,setPalette]=useState(false),[query,setQuery]=useState("");
 const [roadmapActive,setRoadmapActive]=useState<RoadmapArea>("browse");
 const [knowledgeActive,setKnowledgeActive]=useState<KnowledgeArea>("discovery");
 const [groupNotice,setGroupNotice]=useState(""),[groupActive,setGroupActive]=useState<GroupArea>("all");
 const dialog=useRef<HTMLDialogElement>(null),search=useRef<HTMLInputElement>(null),previous=useRef<HTMLElement|null>(null),menuButton=useRef<HTMLButtonElement>(null),panel=useRef<HTMLDivElement>(null);
 useEffect(()=>{try{const saved=localStorage.getItem("brain.ui.theme");if(saved==="light"||saved==="dark"){setTheme(saved);document.documentElement.dataset.theme=saved}setCollapsed(localStorage.getItem("brain.ui.sidebar")==="collapsed")}catch{}},[]);
 useEffect(()=>{const media=window.matchMedia("(max-width:800px)");const update=()=>{setMobile(media.matches);if(!media.matches)setMenu(false)};update();media.addEventListener("change",update);return()=>media.removeEventListener("change",update)},[]);
 useEffect(()=>{function key(e:KeyboardEvent){if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==="k"){e.preventDefault();setMenu(false);setQuery("");setPalette(p=>!p)}if(e.key==="Escape")setMenu(false)}document.addEventListener("keydown",key);return()=>document.removeEventListener("keydown",key)},[]);
 useEffect(()=>{setMenu(false);setGroupNotice("")},[pathname]);
 useEffect(()=>{const ready=(e:Event)=>{if(e instanceof CustomEvent&&validGroupArea(e.detail)){setGroupActive(e.detail);setGroupNotice("")}};window.addEventListener("brain-group-ready",ready);return()=>window.removeEventListener("brain-group-ready",ready)},[]);
 useEffect(()=>{const ready=(e:Event)=>{if(e instanceof CustomEvent&&validKnowledgeArea(e.detail))setKnowledgeActive(e.detail)};window.addEventListener("brain-knowledge-ready",ready);return()=>window.removeEventListener("brain-knowledge-ready",ready)},[]);
 useEffect(()=>{const ready=(e:Event)=>{if(e instanceof CustomEvent&&validRoadmapArea(e.detail))setRoadmapActive(e.detail)};window.addEventListener("brain-roadmap-ready",ready);return()=>window.removeEventListener("brain-roadmap-ready",ready)},[]);
 useEffect(()=>{if(palette){previous.current=document.activeElement as HTMLElement;dialog.current?.showModal();search.current?.focus()}else if(dialog.current?.open){dialog.current.close();(previous.current?.getClientRects().length?previous.current:menuButton.current)?.focus()}},[palette]);
 useEffect(()=>{if(!menu||!mobile)return;const before=document.activeElement as HTMLElement;
  const controls=()=>Array.from(panel.current?.querySelectorAll<HTMLElement>("a[href],button:not(:disabled)")??[]).filter(e=>e.getClientRects().length>0);
  controls()[0]?.focus();const key=(e:KeyboardEvent)=>{if(e.key!=="Tab")return;const items=controls(),first=items[0],last=items[items.length-1];if(e.shiftKey&&document.activeElement===first){e.preventDefault();last?.focus()}else if(!e.shiftKey&&document.activeElement===last||!panel.current?.contains(document.activeElement)){e.preventDefault();first?.focus()}};
  document.addEventListener("keydown",key);return()=>{document.removeEventListener("keydown",key);(before?.getClientRects().length?before:menuButton.current)?.focus()}
 },[menu,mobile]);

 function changeTheme(){const next=theme==="dark"?"light":"dark";setTheme(next);document.documentElement.dataset.theme=next;try{localStorage.setItem("brain.ui.theme",next)}catch{}}
 function collapse(){setCollapsed(v=>{try{localStorage.setItem("brain.ui.sidebar",v?"expanded":"collapsed")}catch{}return !v})}
 function jump(id:string){setMenu(false);setPalette(false);if(current===0){personal.navigate(id);return}if(current===1&&validKnowledgeArea(id)){requestKnowledgeArea(id);return}if(current===2&&validRoadmapArea(id)){requestRoadmapArea(id);return}if(current===3&&validGroupArea(id)){if(!document.querySelector("[data-group-workboard]")){setGroupNotice("그룹을 먼저 선택한 뒤 작업을 이어가세요.");document.querySelector("[aria-labelledby=owned-groups-title]")?.scrollIntoView({block:"start"})}requestGroupArea(id);return}requestAnimationFrame(()=>{const target=document.getElementById(id);target?.scrollIntoView({block:"start"});if(target){target.tabIndex=-1;target.focus({preventScroll:true})}})}
 const commands=[...routes.map(r=>({title:r.title,href:r.href,id:""})),...(current===0?sections:current===3?groupAreas:current===1?knowledgeAreas:current===2?roadmapAreas:[]).map(s=>({...s,href:""}))].filter(c=>c.title.includes(query.trim()));
 return <div className={`app-shell${collapsed?" shell-collapsed":""}${menu?" shell-menu-open":""}`}>
  <a className="skip-link" href="#workspace-content">본문으로 건너뛰기</a>
  {menu&&<button className="shell-shade" aria-label="탐색 메뉴 닫기" onClick={()=>setMenu(false)}/>}
  <div className="shell-sidebar" ref={panel} aria-label="작업 공간 탐색" role={mobile&&menu?"dialog":"complementary"} aria-modal={mobile&&menu||undefined} inert={mobile&&!menu} aria-hidden={mobile&&!menu||undefined}>
   <a className="shell-brand" href="/graph" aria-label="knowledgebrain 홈"><span className="brand-mark"><Icon name="graph"/></span><span className="shell-wordmark">knowledge<span>brain</span></span></a>
   <button className="shell-search" aria-label="빠른 이동 ⌃ K 열기" onClick={()=>{setMenu(false);setQuery("");setPalette(true)}}><Icon name="search"/><span>빠른 이동</span><kbd>⌃ K</kbd></button>
   <p className="shell-caption">WORKSPACES</p>
   <nav aria-label="주요 화면">{routes.map((r,i)=><a key={r.href} href={r.href} aria-label={`${r.title} ${r.en} 이동`} aria-current={i===current?"page":undefined} title={r.title}><Icon name={r.icon}/><span>{r.title} <small>{r.en}</small></span>{i===current&&<i/>}</a>)}</nav>
   {!collapsed&&<div className="shell-sections"><p className="shell-caption">{current===0?"MY EXPLORER":current===1?"PUBLIC EXPLORER":current===3?"GROUP EXPLORER":"EXPLORE"}</p>{(current===0?sections:current===3?groupAreas:current===1?knowledgeAreas:current===2?roadmapAreas:[]).map(s=><button key={s.id} onClick={()=>jump(s.id)} aria-current={current===0&&personal.active===s.id||current===3&&groupActive===s.id||current===1&&knowledgeActive===s.id||current===2&&roadmapActive===s.id?"location":undefined}><span aria-hidden="true">↳</span>{s.title}</button>)}</div>}
   <div className="shell-bottom"><p className="shell-privacy"><span aria-hidden="true">◈</span> 개인 지식은 비공개로 시작합니다.</p><button onClick={changeTheme} aria-label={theme==="dark"?"라이트 테마 변경":"다크 테마 변경"} title="화면 테마 변경"><Icon name={theme==="dark"?"sun":"moon"}/><span>{theme==="dark"?"라이트 테마":"다크 테마"}</span></button><button className="sidebar-collapse" onClick={collapse} aria-label={collapsed?"사이드바 펼치기":"사이드바 접기"} aria-expanded={!collapsed}><Icon name="panel"/><span>사이드바 접기</span></button><button className="mobile-menu-close" onClick={()=>setMenu(false)}>메뉴 닫기</button></div>
  </div>
  <div className="shell-body" inert={mobile&&menu}><header className="shell-topbar"><button className="mobile-menu-toggle" ref={menuButton} onClick={()=>setMenu(true)} aria-label="탐색 메뉴 열기" aria-expanded={menu}><Icon name="menu"/></button><div className="shell-breadcrumb">Knowledge Brain <span>/</span> <strong>{routes[current].title}</strong></div><button className="topbar-command" aria-label="빠른 이동 Ctrl K 열기" onClick={()=>{setQuery("");setPalette(true)}}><Icon name="search"/><span>빠른 이동</span> <kbd>Ctrl K</kbd></button></header><div id="workspace-content" className="shell-content" tabIndex={-1}>{groupNotice&&<div className="workspace-nav-notice" aria-live="polite">{groupNotice}<button type="button" onClick={()=>setGroupNotice("")}>그룹 안내 닫기</button></div>}{personal.notice&&<div className="workspace-nav-notice" role="status">{personal.notice}<button type="button" onClick={personal.dismiss}>안내 닫기</button></div>}{children}</div></div>
  <dialog ref={dialog} className="command-dialog" aria-labelledby="command-title" onCancel={e=>{e.preventDefault();setPalette(false)}} onClick={e=>{if(e.target===e.currentTarget)setPalette(false)}}><div className="command-heading"><h2 id="command-title">빠른 이동</h2><button aria-label="빠른 이동 닫기" onClick={()=>setPalette(false)}><Icon name="close"/></button></div><label className="command-input"><Icon name="search"/><input ref={search} aria-label="이동할 화면 검색" placeholder="화면이나 작업을 찾아보세요" value={query} onChange={e=>setQuery(e.target.value)} onKeyDown={e=>{if(e.key==="ArrowDown"){e.preventDefault();dialog.current?.querySelector<HTMLElement>(".command-results a,.command-results button")?.focus()}}}/></label><div className="command-results">{commands.map(c=>c.href?<a key={c.href} href={c.href} onClick={()=>setPalette(false)}><span>{c.title}</span><small>화면 이동 ↗</small></a>:<button key={c.id} onClick={()=>jump(c.id)}><span>{c.title}</span><small>이 페이지 ↓</small></button>)}{!commands.length&&<p role="status">일치하는 화면이 없습니다.</p>}</div><p className="command-help">Tab으로 선택 · Enter로 이동 · Esc로 닫기</p></dialog>
 </div>
}
