import {codePointLength,javaBlank,utf16MaxLength} from "../unicode-text";

export const GROUP_TITLE_MAX_LENGTH=utf16MaxLength(200);
export function groupTitle(value:string):boolean{
 return !javaBlank(value)&&codePointLength(value)<=200&&!/[\u0000-\u001f\u007f-\u009f\ud800-\udfff]/u.test(value);
}
export function groupSettings(title:string,description:string):boolean{
 return groupTitle(title)&&new TextEncoder().encode(description).length<=32768&&!/[\u0000\ud800-\udfff]/u.test(description);
}
