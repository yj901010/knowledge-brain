import pack from "./public-curation-pack.json";

export {pack};
export type Statement=(typeof pack.statements)[number];
export type Term=(typeof pack.terms)[number];
export type Source=(typeof pack.sources)[number];
export type Content=Record<string,string|number>;
export type Canonical={id:string;spaceId:string;kind:string;title:string;version:number;state:string;description?:string;content:Content};
export type Prepared={kind:string;binding:string;title:string;description?:string;content?:Content;rationale:string;pins:Canonical[]};
export const kindNames:Record<string,string>={CONCEPT:"개념",SOURCE:"출처",CLAIM:"주장",EVIDENCE:"근거",QUALIFIER:"한정자"};
export const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
// Java canonical titles are bounded by code points, not UTF-16 code units.
// Keep display/proposal titles plain text without controls or lone surrogates.
export function validPublicTitle(value:unknown):value is string{return typeof value==="string"&&!!value.trim()&&Array.from(value).length<=200&&!/[\u0000-\u001f\u007f-\u009f]/u.test(value)&&!Array.from(value).some(char=>{const cp=char.codePointAt(0)!;return cp>=0xd800&&cp<=0xdfff})}
export class Failure extends Error{constructor(public status:number){super(String(status))}}
export async function request<T>(path:string,options:RequestInit={}):Promise<T>{
 const r=await fetch(path,{...options,credentials:"same-origin",cache:"no-store",redirect:"error",signal:options.signal??AbortSignal.timeout(15000),headers:{...(options.body?{"Content-Type":"application/json"}:{}),...options.headers}});
 if(!r.ok)throw new Failure(r.status);return r.json();
}
// Only the current Knowledge-owned canonical endpoints may resolve dependencies.
// A submitted proposal's reserved record/concept ID is not a canonical record.
export async function canonical(space:string,kind:string,id:string,signal?:AbortSignal):Promise<Canonical>{
 if(!uuid.test(id))throw new Failure(404);
 const v=await request<Record<string,unknown>>(`/api/public/spaces/${space}/${kind==="CONCEPT"?"concepts":"records"}/${id}`,{signal});
 if(v.spaceId!==space||v.state!=="ACTIVE"||v[kind==="CONCEPT"?"conceptId":"recordId"]!==id||kind==="CONCEPT"&&v.visibility!=="PUBLIC"||kind!=="CONCEPT"&&v.kind!==kind||!validPublicTitle(v.title)||!Number.isSafeInteger(v.version)||Number(v.version)<1)throw new Failure(412);
 if(kind!=="CONCEPT"&&(!v.content||typeof v.content!=="object"||Array.isArray(v.content)))throw new Failure(412);
 return {id,spaceId:space,kind,title:v.title,version:Number(v.version),state:"ACTIVE",description:typeof v.description==="string"?v.description:undefined,content:kind==="CONCEPT"?{}:v.content as Content};
}
export function contextFor(statement:Statement){
 const source=pack.sources.find(s=>s.slug===statement.evidence.sourceSlug)!;
 const rights=pack.rightsPolicies.find(p=>p.slug===source.rightsPolicySlug)!;
 return {source,rights};
}
export function licenseFor(source:Source){const rights=pack.rightsPolicies.find(p=>p.slug===source.rightsPolicySlug)!;return [rights.title,rights.url,`이용 조건 확인: ${rights.checkedAt}`,rights.noteKo,rights.restrictionKo,source.rightsNoteKo,rights.nonendorsement].filter(Boolean).join("\n")}
export function provenance(statement:Statement){const {source,rights}=contextFor(statement);return [
 `작성 초안 ${pack.packSlug} / ${statement.slug} · 독립 검토 전 DRAFT`,statement.statementKo,
 `범위: ${statement.scopeNotesKo}`,...statement.qualifierNotesKo.map(s=>`한정 메모: ${s}`),
 `${source.publisher} · ${source.title} · ${source.url}`,`확인 부분: ${statement.evidence.section} · 조회 ${statement.evidence.retrievedAt} · 원문 갱신 ${source.sourceUpdatedAt??"확인된 날짜 없음"}`,
 statement.evidence.evidenceNoteKo,statement.evidence.rightsNoteKo,licenseFor(source),rights.nonendorsement?"NCI/PDQ의 공식 번역·전체 요약문이 아닙니다.":null,
 "공개 제안이며 독립 검토·병합 전입니다. 전문가 검증·공식 매핑·프로젝트 라이선스 부여를 뜻하지 않습니다."
 ].filter(Boolean).join("\n")}
export function claimMatches(claim:Canonical,statement:Statement,terms:Record<string,Canonical>){const s=terms[statement.subjectSlug],p=terms[statement.predicateSlug],c=claim.content;return !!s&&!!p&&claim.kind==="CLAIM"&&c.subjectId===s.id&&c.subjectVersion===s.version&&c.predicateId===p.id&&c.predicateVersion===p.version&&c.objectType===statement.object.type&&c.objectValue===statement.object.value}
