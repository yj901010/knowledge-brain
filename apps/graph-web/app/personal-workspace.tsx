"use client";
import {createContext,useCallback,useContext,useEffect,useMemo,useRef,useState} from "react";
import {usePathname} from "next/navigation";

export const personalAreas=[
 {id:"connections",title:"노트 그래프"},{id:"notes",title:"노트와 검색"},
 {id:"network",title:"통합 탐색"},{id:"knowledge",title:"개념과 출처"},
 {id:"learning",title:"학습 로드맵"},{id:"journal",title:"저널과 활동"},
 {id:"thinking",title:"질문 · 결정 · 북마크"},{id:"planning",title:"목표와 계획"},
 {id:"notifications",title:"알림"},{id:"github",title:"GitHub 활동"}
] as const;
export type Area=(typeof personalAreas)[number]["id"];
type Focus=Area|"all";
function valid(value:string):value is Focus{return value==="all"||personalAreas.some(a=>a.id===value)}
type Navigation={active:Focus;available:readonly Area[];notice:string;navigate:(id:string)=>void;register:(ids:readonly Area[])=>void;dismiss:()=>void};
const Context=createContext<Navigation>({active:"all",available:[],notice:"",navigate:()=>{},register:()=>{},dismiss:()=>{}});
export const usePersonalWorkspace=()=>useContext(Context);

export function PersonalWorkspaceProvider({children}:{children:React.ReactNode}){
 const path=usePathname()||"/",personal=path==="/"||/^\/graph\/?$/.test(path);
 const [active,setActive]=useState<Focus>("all"),[available,setAvailable]=useState<readonly Area[]>([]),[notice,setNotice]=useState("");
 const register=useCallback((ids:readonly Area[])=>{setAvailable(ids);setNotice("")},[]),dismiss=useCallback(()=>setNotice(""),[]);
 useEffect(()=>{function hash(){if(!personal){setActive("all");return}if(location.hash&&!location.hash.startsWith("#work="))return;const value=location.hash.slice(6)||"all";setActive(valid(value)?value:"all")}hash();window.addEventListener("hashchange",hash);return()=>window.removeEventListener("hashchange",hash)},[personal]);
 useEffect(()=>{if(!personal||!available.length||active==="all"||available.includes(active))return;setActive("all");setNotice(active==="github"?"GitHub 활동은 개인 공간에서 로그인한 뒤 이용할 수 있습니다.":"이 작업을 사용할 수 없어 전체 모아보기를 엽니다.");window.history.replaceState(window.history.state,"",location.pathname+location.search)},[personal,available,active]);
 const navigate=useCallback((id:string)=>{
  if(!personal||!valid(id))return;
  if(id!=="all"&&!available.includes(id)){setNotice(id==="github"&&available.length?"GitHub 활동은 개인 공간에서 로그인한 뒤 이용할 수 있습니다.":"개인 공간을 먼저 시작해 주세요.");requestAnimationFrame(()=>document.querySelector(".account-mobile,.welcome")?.scrollIntoView({block:"start"}));return}
  setNotice("");setActive(id);const hash=id==="all"?"":"#work="+id;
  if(location.hash!==hash)window.history.pushState(window.history.state,"",location.pathname+location.search+hash);
  requestAnimationFrame(()=>{const target=document.getElementById(id==="all"?"personal-focus":id);if(target){target.scrollIntoView({block:"start"});target.tabIndex=-1;target.focus({preventScroll:true})}});
 },[personal,available]);
 const value=useMemo(()=>({active,available,notice,navigate,register,dismiss}),[active,available,notice,navigate,register,dismiss]);
 return <Context.Provider value={value}>{children}</Context.Provider>
}

export function PersonalFocusBar(){const {active,available,navigate}=usePersonalWorkspace();return <section className="personal-focus" id="personal-focus" aria-label="개인 작업 화면"><div><p className="eyebrow">MY WORKSPACE</p><h2>{active==="all"?"내 작업 모아보기":personalAreas.find(a=>a.id===active)?.title}</h2><p>{active==="all"?"필요한 작업을 선택하면 그 화면에 집중할 수 있습니다.":"작업 화면을 오가도 입력은 유지됩니다. 노트·공간 변경과 로그아웃에는 기존 정리 규칙이 적용됩니다."}</p></div><div className="focus-controls"><label>현재 작업<select aria-label="개인 작업 선택" value={active} onChange={e=>navigate(e.target.value)}><option value="all">전체 모아보기</option>{personalAreas.filter(a=>available.includes(a.id)).map(a=><option value={a.id} key={a.id}>{a.title}</option>)}</select></label>{active!=="all"&&<button type="button" onClick={()=>navigate("all")}>전체 모아보기</button>}<div className="focus-shortcuts">{(["notes","knowledge","learning","planning"] as Area[]).map(id=><button key={id} type="button" aria-pressed={active===id} onClick={()=>navigate(id)}>{({notes:"기록",knowledge:"지식",learning:"학습",planning:"계획"} as Record<string,string>)[id]}에 집중</button>)}</div></div></section>}

// Visit lazily; keep mounted drafts when hidden. The owning page keys every area
// by its private-session epoch, so Space/revocation/visibility clears still unmount it.
export function WorkspaceArea({id,title,description,children,ariaLabel}:{id:Area;title:string;description:string;children:React.ReactNode;ariaLabel?:string}){
 const {active}=usePersonalWorkspace(),visible=active==="all"||active===id||(active==="notes"&&id==="connections");
 const [visited,setVisited]=useState(visible);const section=useRef<HTMLElement>(null);
 useEffect(()=>{if(visible)setVisited(true)},[visible]);
 return <section ref={section} id={id} className="workspace-section" data-section={id} hidden={!visible} inert={!visible} aria-label={ariaLabel}><div className="section-heading"><h2>{title}</h2><p>{description}</p></div>{(visible||visited)&&children}</section>
}
