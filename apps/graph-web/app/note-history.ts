import {javaBlank} from "./unicode-text";
export type Note={noteId:string;title:string;markdown:string;version:number};
export type Revision=Note&{revisionId:string;state:"ACTIVE"|"RETIRED";actorId:string;occurredAt:string};
export type Current={note:Note;etag:string};

const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export function validNoteText(title:unknown,markdown:unknown):boolean{
 return typeof title==="string"&&!javaBlank(title)&&Array.from(title).length<=200&&!title.includes("\0")&&
  typeof markdown==="string"&&!markdown.includes("\0")&&new TextEncoder().encode(markdown).length<=32768;
}
function note(value:unknown,noteId:string):boolean{
 if(!value||typeof value!=="object")return false;
 const v=value as Note;
 return v.noteId===noteId&&uuid.test(v.noteId)&&Number.isSafeInteger(v.version)&&v.version>0&&validNoteText(v.title,v.markdown);
}
export async function readCurrent(response:Response,noteId:string,spaceId:string):Promise<Current>{
 const etag=response.headers.get("ETag"),value=await response.json();
 if(!note(value,noteId)||value.spaceId!==spaceId||value.visibility!=="PRIVATE"||value.state!=="ACTIVE"||etag!==`"${value.version}"`)throw new Error("Invalid current Note");
 return {note:value,etag};
}
export function readHistory(value:unknown,noteId:string,before:number|null,previous:Revision[]):Revision[]{
 if(!Array.isArray(value)||value.length>5)throw new Error("Invalid Note history");
 let ceiling=before??Infinity;
 const ids=new Set(previous.map(r=>r.revisionId));
 for(const v of value){
  if(!note(v,noteId)||!uuid.test(v.revisionId)||!uuid.test(v.actorId)||!['ACTIVE','RETIRED'].includes(v.state)||
   typeof v.occurredAt!=="string"||!/^\d{4}-\d{2}-\d{2}T/.test(v.occurredAt)||!Number.isFinite(Date.parse(v.occurredAt))||
   v.version>=ceiling||ids.has(v.revisionId))throw new Error("Invalid Note revision");
  ceiling=v.version;ids.add(v.revisionId);
 }
 return value;
}
