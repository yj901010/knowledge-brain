import {forceLayout,type Point} from "./graph-layout";
export type LayoutInput={ids:string[];links:[number,number][]};
export type LayoutOutput=[string,Point][];
const worker=self as unknown as {onmessage:((event:MessageEvent<LayoutInput>)=>void)|null;postMessage:(data:LayoutOutput)=>void};
worker.onmessage=({data})=>{
 if(!Array.isArray(data.ids)||data.ids.length>200||new Set(data.ids).size!==data.ids.length||data.ids.some(id=>typeof id!=="string"||id.length>200)||!Array.isArray(data.links)||data.links.length>1000)throw new Error("Invalid layout bounds");
 for(const pair of data.links)if(!Array.isArray(pair)||pair.length!==2||pair.some(i=>!Number.isInteger(i)||i<0||i>=data.ids.length))throw new Error("Invalid layout link");
 const points=forceLayout(data.ids.map(id=>({id})),data.links.map(([a,b])=>({source:data.ids[a],target:data.ids[b]})));
 worker.postMessage([...points]);
};
