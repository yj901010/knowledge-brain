import {javaStrip} from "./unicode-text";
// Preserve JSON decimal tokens without conversion to IEEE-754 numbers.
export function decimalJson(text:string):unknown{
 JSON.parse(text); // Validate the original grammar before preserving number tokens.
 let output="",i=0;
 while(i<text.length){
  if(text[i]==='"'){const start=i++;while(i<text.length){if(text[i]==="\\"){i+=2;continue}if(text[i++]==='"')break}output+=text.slice(start,i);continue}
  if(text[i]==="-"||/[0-9]/.test(text[i])){const token=/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(text.slice(i));if(!token)throw new Error();output+=JSON.stringify(token[0]);i+=token[0].length;continue}
  output+=text[i++];
 }
 return JSON.parse(output);
}
export type Field={name:string;type:string;value:string;list:string[];locked:boolean};
export function metadataBody(tags:string[],fields:Field[]):string{
 const normalize=(name:string)=>{const n=javaStrip(name).normalize("NFC");if(!n||Array.from(n).length>64||/[\u0000-\u001f\u007f-\u009f]/u.test(n))throw new Error("이름은 1~64자로 입력해 주세요.");return n};
 const names=tags.map(normalize);if(new Set(names).size!==names.length)throw new Error("중복된 태그 이름이 있습니다.");const keys=fields.map(f=>normalize(f.name));if(new Set(keys).size!==keys.length)throw new Error("중복된 속성 이름이 있습니다.");if(tags.length>32||fields.length>32)throw new Error("태그와 속성은 각각 32개까지 가능합니다.");
 const properties=fields.map((f,index)=>{let value:string;
  switch(f.type){
   case "NUMBER":value=f.value.trim();if(!/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$/.test(value))throw new Error("숫자 값을 확인해 주세요.");break;
   case "BOOLEAN":if(!["true","false"].includes(f.value))throw new Error("참/거짓 값을 선택해 주세요.");value=f.value;break;
   case "DATE":if(!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(f.value))throw new Error("날짜를 입력해 주세요.");value=JSON.stringify(f.value);break;
   case "STRING":if(Array.from(f.value).length>1000||f.value.includes("\u0000"))throw new Error("문자열은 1000자까지 가능합니다.");value=JSON.stringify(f.value);break;
   case "STRING_LIST":if(f.list.length>20||f.list.some(v=>Array.from(v).length>200||v.includes("\u0000")))throw new Error("목록은 항목당 200자, 최대 20개까지 가능합니다.");value=JSON.stringify(f.list);break;
   default:throw new Error("지원하지 않는 속성 형식입니다.");
  }
  return `${JSON.stringify(keys[index])}:{"type":${JSON.stringify(f.type)},"value":${value}}`;
 });
 const body=`{"tags":${JSON.stringify(names)},"properties":{${properties.join(",")}}}`;if(new TextEncoder().encode(body).length>16384)throw new Error("태그·속성 전체 입력이 너무 큽니다.");return body;
}
