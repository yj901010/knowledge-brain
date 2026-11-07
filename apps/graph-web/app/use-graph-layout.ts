"use client";
import {useEffect,useState} from "react";
import {forceLayout,forceLayoutAsync,type GraphNode,type GraphEdge,type Point} from "./graph-layout";
import type {LayoutInput,LayoutOutput} from "./graph-layout.worker";

const empty=new Map<string,Point>();
export function useGraphLayout(nodes:GraphNode[],edges:GraphEdge[],geometry:string){
 const [layout,setLayout]=useState<{geometry:string;points:Map<string,Point>;failed:boolean;mode:string}>({geometry:"",points:empty,failed:false,mode:"pending"});
 useEffect(()=>{
  let active=true,worker:Worker|null=null,timer:ReturnType<typeof setTimeout>|null=null,fallbackStarted=false,completed=false;
  const abort=new AbortController();
  const done=(points:Map<string,Point>,failed=false,mode="inline")=>{if(active){completed=true;setLayout({geometry,points,failed,mode:failed?"failed":mode})}};
  if(nodes.length>200||edges.length>1000){done(empty,true);return()=>{active=false}}
  if(nodes.length<=80){done(forceLayout(nodes,edges));return()=>{active=false}}
  const stop=()=>{if(timer!==null)clearTimeout(timer);worker?.terminate();worker=null};
  const fallback=()=>{
   if(!active||fallbackStarted||completed)return;fallbackStarted=true;stop();
   void forceLayoutAsync(nodes,edges,abort.signal).then(points=>done(points,false,"yielding")).catch(()=>{if(!abort.signal.aborted)done(empty,true)});
  };
  try{
   if(typeof Worker==="undefined")throw new Error("Worker unavailable");
   worker=new Worker(new URL("./graph-layout.worker.ts",import.meta.url),{type:"module",name:"knowledge-graph-layout"});
   worker.onerror=e=>{e.preventDefault();fallback()};
   worker.onmessage=({data}:{data:LayoutOutput})=>{
    if(!active||fallbackStarted||completed)return;
    try{
     if(!Array.isArray(data)||data.length!==nodes.length)throw new Error("Invalid layout output");
     const expected=new Set(nodes.map(n=>n.id)),points=new Map<string,Point>();
     for(const row of data){
      if(!Array.isArray(row)||row.length!==2||!expected.delete(row[0]))throw new Error("Invalid layout identity");
      const p=row[1];if(!p||!Number.isFinite(p.x)||!Number.isFinite(p.y)||Math.abs(p.x)>10000||Math.abs(p.y)>10000||!Number.isInteger(p.degree)||p.degree<0||p.degree>2000)throw new Error("Invalid layout point");
      points.set(row[0],{x:p.x,y:p.y,degree:p.degree});
     }
     stop();done(points,false,"worker");
    }catch{fallback()}
   };
   const index=new Map(nodes.map((n,i)=>[n.id,i]));
   const input:LayoutInput={ids:nodes.map(n=>n.id),links:edges.filter(e=>index.has(e.source)&&index.has(e.target)).map(e=>[index.get(e.source)!,index.get(e.target)!])};
   worker.postMessage(input);timer=setTimeout(fallback,5000);
  }catch{fallback()}
  return()=>{active=false;abort.abort();stop()};
 },[geometry]);
 const current=layout.geometry===geometry;
 return {points:current?layout.points:empty,loading:!current,failed:current&&layout.failed,mode:current?layout.mode:"pending"};
}
