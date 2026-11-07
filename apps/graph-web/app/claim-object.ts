export type ClaimObjectKind="CONCEPT"|"STRING"|"NUMBER"|"DATE"|"BOOLEAN";
export type ClaimObject={type:ClaimObjectKind;conceptId?:string;value?:string};
export const claimObjectKinds:ClaimObjectKind[]=["CONCEPT","STRING","NUMBER","DATE","BOOLEAN"];
export const claimObjectLabels=["개념","문자열","숫자","날짜","참/거짓"];
export function validClaimObject(raw:unknown):raw is ClaimObject{
 const v=raw as ClaimObject;if(!v||typeof v!=="object"||!claimObjectKinds.includes(v.type))return false;
 if(v.type==="CONCEPT")return Object.keys(v).length===2&&v.value===undefined&&typeof v.conceptId==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v.conceptId);
 if(Object.keys(v).length!==2||v.conceptId!==undefined||typeof v.value!=="string"||!v.value||Array.from(v.value).length>1000||/[\u0000\ud800-\udfff]/u.test(v.value))return false;
 if(v.type==="NUMBER")return v.value.length<=128&&/^-?(0|[1-9][0-9]*)(\.[0-9]+)?$/.test(v.value);
 if(v.type==="BOOLEAN")return v.value==="true"||v.value==="false";
 if(v.type==="DATE")return Number.isFinite(Date.parse(v.value+"T00:00:00Z"))&&/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(v.value)&&!v.value.startsWith("0000")&&new Date(v.value+"T00:00:00Z").toISOString().slice(0,10)===v.value;
 return true;
}
export function claimObjectText(v:ClaimObject,name:(id:string)=>string=id=>id){return v.type==="CONCEPT"?name(v.conceptId!):`${v.value} (${claimObjectLabels[claimObjectKinds.indexOf(v.type)]})`}
export function claimObjectInput(type:ClaimObjectKind,value:string):ClaimObject{return type==="CONCEPT"?{type,conceptId:value}:{type,value}}
