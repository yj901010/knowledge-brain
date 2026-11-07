export type GraphNode={id:string;title:string;kind?:string;label?:string};
export type GraphEdge={id:string;source:string;target:string;role?:string;directed?:boolean};
export type Point={x:number;y:number;degree:number};

// Bounded deterministic force layout. Geometry never reads or stores domain content elsewhere.
function* simulate(nodes:Pick<GraphNode,"id">[],edges:Pick<GraphEdge,"source"|"target">[]):Generator<void,Map<string,Point>>{
 const sorted=[...nodes].sort((a,b)=>a.id.localeCompare(b.id));
 const index=new Map(sorted.map((n,i)=>[n.id,i]));
 const springs=edges.filter(e=>e.source!==e.target&&index.has(e.source)&&index.has(e.target)).map(e=>[index.get(e.source)!,index.get(e.target)!]);
 const points=sorted.map((n,i)=>({x:Math.cos(i*2.399963)*Math.sqrt(i+1)*29,y:Math.sin(i*2.399963)*Math.sqrt(i+1)*29,degree:0,vx:0,vy:0}));
 for(const [a,b] of springs){points[a].degree++;points[b].degree++}
 for(let step=0;step<180;step++){
  const forces=points.map(p=>({x:-p.x*.004,y:-p.y*.004})),cool=1-step/210;
  for(let a=0;a<points.length;a++)for(let b=a+1;b<points.length;b++){
   const dx=points[a].x-points[b].x,dy=points[a].y-points[b].y,d2=Math.max(100,dx*dx+dy*dy),d=Math.sqrt(d2),push=Math.min(9,3300/d2);
   forces[a].x+=dx/d*push;forces[a].y+=dy/d*push;forces[b].x-=dx/d*push;forces[b].y-=dy/d*push;
  }
  for(const [a,b] of springs){const dx=points[b].x-points[a].x,dy=points[b].y-points[a].y,d=Math.max(1,Math.hypot(dx,dy)),pull=(d-95)*.035;forces[a].x+=dx/d*pull;forces[a].y+=dy/d*pull;forces[b].x-=dx/d*pull;forces[b].y-=dy/d*pull}
  points.forEach((p,i)=>{p.vx=(p.vx+forces[i].x*cool)*.65;p.vy=(p.vy+forces[i].y*cool)*.65;p.x+=Math.max(-12,Math.min(12,p.vx));p.y+=Math.max(-12,Math.min(12,p.vy))});
  if(step%10===9)yield;
 }
 return new Map(sorted.map((n,i)=>[n.id,{x:points[i].x,y:points[i].y,degree:points[i].degree}]));
}
export function forceLayout(nodes:Pick<GraphNode,"id">[],edges:Pick<GraphEdge,"source"|"target">[]):Map<string,Point>{
 const simulation=simulate(nodes,edges);let result=simulation.next();while(!result.done)result=simulation.next();return result.value;
}
// Worker-unavailable fallback yields between identical simulation steps.
export async function forceLayoutAsync(nodes:Pick<GraphNode,"id">[],edges:Pick<GraphEdge,"source"|"target">[],signal:AbortSignal):Promise<Map<string,Point>>{
 const simulation=simulate(nodes,edges);
 for(;;){if(signal.aborted)throw new DOMException("Aborted","AbortError");const result=simulation.next();if(result.done)return result.value;await new Promise<void>(resolve=>setTimeout(resolve,0));}
}
export function fitView(points:Point[],aspect:number){
 const xs=points.map(p=>p.x),ys=points.map(p=>p.y);const left=Math.min(...xs,0)-65,right=Math.max(...xs,0)+65,top=Math.min(...ys,0)-65,bottom=Math.max(...ys,0)+65;
 const w=Math.max(260,right-left,(bottom-top)*aspect),h=w/aspect;
 return {x:(left+right-w)/2,y:(top+bottom-h)/2,w,h};
}
