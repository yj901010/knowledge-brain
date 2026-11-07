import {codePointLength,javaBlank,javaStrip,utf16MaxLength} from "./unicode-text";

export function publicText(value:string,limit:number,required=false):boolean{
 return codePointLength(value)<=limit&&(!required||!javaBlank(value))&&!/[\u0000\ud800-\udfff]/u.test(value);
}
type Content=Record<string,string|number>;
function rule(kind:string,key:string,content:Content){
 const optional=["url","citation","scopeValue","systemVersion","label","authority"].includes(key)&&!(key==="label"&&kind==="ALIAS");
 let max=1000,ascii=false,strip=false,required=!optional;
 if(key==="url"){max=2048;ascii=true;strip=true;}
 else if(key==="citation")max=4000;
 else if(key==="locator")max=500;
 else if(key==="title"||key==="label"||key==="scopeValue"||["system","code","systemVersion"].includes(key))max=200;
 if(key==="title"||key==="label"&&kind==="ALIAS")strip=true;
 if(key==="scopeValue"&&content.scope==="COUNTRY"){max=2;ascii=true;required=true;}
 if(key==="scopeValue"&&content.scope==="ORGANIZATION")required=true;
 if(kind==="MAPPING"&&["systemVersion","authority"].includes(key)&&content.mappingState!=="CANDIDATE")required=true;
 if(key==="objectValue"||key==="value"){
  const type=content[key==="objectValue"?"objectType":"valueType"];
  if(type==="STRING")strip=true;
  else if(type==="NUMBER"){max=128;ascii=true;}
  else if(type==="DATE"){max=10;ascii=true;}
  else if(type==="BOOLEAN"){max=5;ascii=true;}
 }
 return {max,ascii,strip,required};
}
export function publicFieldMaxLength(kind:string,key:string,content:Content):number{
 const r=rule(kind,key,content);return r.ascii?r.max:utf16MaxLength(r.max);
}
export function publicFieldText(kind:string,key:string,content:Content):boolean{
 const r=rule(kind,key,content),raw=content[key];if(typeof raw!=="string")return false;
 const value=r.strip?javaStrip(raw):raw;
 return publicText(value,r.max,r.required)&&(!r.ascii||/^[\u0000-\u007f]*$/.test(value));
}
