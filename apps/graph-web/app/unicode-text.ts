// Match java.lang.Character.isWhitespace / String.strip used by the owner APIs.
// NBSP, narrow NBSP and BOM are intentionally not Java whitespace.
const javaWhitespace="\\u0009-\\u000d\\u001c-\\u0020\\u1680\\u2000-\\u2006\\u2008-\\u200a\\u2028\\u2029\\u205f\\u3000";
const blank=new RegExp(`^[${javaWhitespace}]*$`,"u");
const edges=new RegExp(`^[${javaWhitespace}]+|[${javaWhitespace}]+$`,"gu");

export function javaBlank(value:string):boolean{return blank.test(value)}
export function javaStrip(value:string):string{return value.replace(edges,"")}
export function codePointLength(value:string):number{return Array.from(value).length}
// Native HTML maxlength counts UTF-16 units; semantic limits stay in each validator.
export function utf16MaxLength(codePointLimit:number):number{return codePointLimit*2}
