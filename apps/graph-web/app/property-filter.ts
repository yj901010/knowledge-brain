import {value as typedValue} from "./activity-metadata";

export function filterInput(type:string,input:string):string{
 if(Array.from(input).length>200||/[\u0000\ud800-\udfff]/u.test(input))throw new Error("조건은 NUL 없이 200자까지 입력해 주세요.");
 if(type==="STRING"||type==="STRING_LIST")return input;
 try{
  if(type==="NUMBER")typedValue(type,input);
  else if(type==="DATE")typedValue(type,JSON.stringify(input));
  else if(type==="BOOLEAN"){if(!["true","false"].includes(input))throw new Error()}
  else throw new Error();
 }catch{throw new Error(type==="NUMBER"?"숫자 조건을 확인해 주세요. 소수는 그대로 입력할 수 있습니다.":"속성 형식에 맞는 값을 입력해 주세요.")}
 return input;
}
export type PropertyCondition={kind:"exact";value:string}|{kind:"range";min:string|null;max:string|null};
function decimalKey(raw:string){
 const m=/^(-?)(0|[1-9][0-9]*)(?:\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?$/.exec(raw);if(!m)throw new Error();
 const digits=(m[2]+(m[3]??"")).replace(/^0+/,"");if(!digits)return "0";
 const significant=digits.replace(/0+$/,"");const exponent=Number(m[4]??0)-(m[3]?.length??0)+(digits.length-significant.length);
 return m[1]+significant+"e"+exponent;
}
export function matchesValue(type:string,value:unknown,condition:string):boolean{
 if(type==="NUMBER")return typeof value==="string"&&decimalKey(value)===decimalKey(condition);
 if(type==="BOOLEAN")return value===(condition==="true");
 if(type==="STRING_LIST")return Array.isArray(value)&&value.includes(condition);
 return value===condition;
}

function compareNumbers(left:string,right:string):number{
 function parts(raw:string){const key=decimalKey(raw);if(key==="0")return {digits:"0",exponent:0,negative:false};const [signed,exponent]=key.split("e");return {digits:signed.replace(/^-/,""),exponent:Number(exponent),negative:signed.startsWith("-")}}
 const a=parts(left),b=parts(right),scale=Math.min(a.exponent,b.exponent);
 if(a.negative!==b.negative)return a.negative?-1:1;
 const x=(a.digits+"0".repeat(a.exponent-scale)).replace(/^0+/,"")||"0",y=(b.digits+"0".repeat(b.exponent-scale)).replace(/^0+/,"")||"0";
 const order=x.length<y.length?-1:x.length>y.length?1:x<y?-1:x>y?1:0;
 return a.negative?-order:order;
}
function compare(type:string,left:string,right:string){return type==="NUMBER"?compareNumbers(left,right):left<right?-1:left>right?1:0}
export function rangeInput(type:string,min:string,max:string):PropertyCondition{
 if(type!=="NUMBER"&&type!=="DATE")throw new Error("숫자와 날짜에만 범위 조건을 적용할 수 있습니다.");
 if(min===""&&max==="")throw new Error("최솟값이나 최댓값을 하나 이상 입력해 주세요.");
 if(min!=="")filterInput(type,min);if(max!=="")filterInput(type,max);
 if(min!==""&&max!==""&&compare(type,min,max)>0)throw new Error("최솟값은 최댓값보다 클 수 없습니다.");
 return {kind:"range",min:min===""?null:min,max:max===""?null:max};
}
export function matchesCondition(type:string,value:unknown,condition:PropertyCondition):boolean{
 if(condition.kind==="exact")return matchesValue(type,value,condition.value);
 if((type!=="NUMBER"&&type!=="DATE")||typeof value!=="string")return false;
 return (condition.min===null||compare(type,value,condition.min)>=0)&&(condition.max===null||compare(type,value,condition.max)<=0);
}
export function conditionText(condition:PropertyCondition|null):string{
 if(condition===null)return "전체 값";
 if(condition.kind==="exact")return condition.value===""?"(빈 문자열)":condition.value;
 return [condition.min===null?null:condition.min+" 이상",condition.max===null?null:condition.max+" 이하"].filter(v=>v!==null).join(" · ");
}
