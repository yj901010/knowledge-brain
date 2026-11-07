import {test,expect,Page} from '@playwright/test';
async function call(p:Page,path:string,method='GET',body?:unknown,etag?:string){return p.evaluate(async({path,method,body,etag})=>{const r=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:{...(body?{'Content-Type':'application/json'}:{}),...(etag?{'If-Match':etag}:{})},body:body?JSON.stringify(body):undefined});return {status:r.status,body:await r.json().catch(()=>null),etag:r.headers.get('ETag')}},{path,method,body,etag})}
async function start(page:Page){await page.goto('/graph');await page.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();await expect(page.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible();return (await call(page,'/api/spaces/personal')).body.spaceId}
const exact='9007199254740993.123456789012345678';
test('typed Claim objects support exact values, immutable evidence pins, search and graph transitions',async({page,browser})=>{
 test.setTimeout(180000);const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));const context=await browser.newContext(),foreign=await context.newPage();
 try{
 const space=await start(page);await start(foreign);const base=`/api/spaces/${space}`,concepts=base+'/concepts',cs=[];
 for(const title of ['Typed subject','Typed predicate','Typed original object']){const r=await call(page,concepts,'POST',{title,description:'Original'});expect(r.status).toBe(201);cs.push(r.body)}
 const c=(i:number)=>cs[i].conceptId,path=concepts+'/'+c(0)+'/claims',edit=(type:string,value:string)=>({predicateConceptId:c(1),object:type==='CONCEPT'?{type,conceptId:value}:{type,value}});
 const initial=await call(page,path,'POST',edit('CONCEPT',c(2)));expect(initial.status).toBe(201);const claim=initial.body,cp=path+'/'+claim.claimId;
 const source=(await call(page,base+'/sources','POST',{title:'Typed evidence',url:null,citation:'Evidence',licenseNote:''})).body;
 const cite=await call(page,cp+'/citations','POST',{sourceId:source.sourceId,sourceVersion:1,claimVersion:1});expect(cite.status).toBe(201);const cip=cp+'/citations/'+cite.body.citationId;
 const qualifier=await call(page,cp+'/qualifiers','POST',{predicateConceptId:c(1),value:{type:'STRING',value:'scope'},claimVersion:1});expect(qualifier.status).toBe(201);const qp=cp+'/qualifiers/'+qualifier.body.qualifierId;
 const library=page.getByRole('region',{name:'개인 개념 보관함',exact:true});await library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true}).click();await library.getByRole('button',{name:'보관함 개념 열기: Typed subject',exact:true}).click();
 const panel=library.getByRole('region',{name:'개념 관계와 수정 이력',exact:true}),detail=panel.getByRole('region',{name:'선택한 개념 관계',exact:true});
 await panel.getByRole('button',{name:'관계 열기: '+claim.claimId,exact:true}).click();await detail.getByRole('button',{name:'관계 편집',exact:true}).click();await detail.getByLabel('편집할 관계의 대상 종류').selectOption('NUMBER');await detail.getByLabel('편집할 관계의 대상 값').fill(exact);await detail.getByRole('button',{name:'관계 수정 저장',exact:true}).click();await expect(detail).toContainText('관계 Revision v2');await expect(detail).toContainText(exact);
 expect((await call(page,cp)).body.object).toEqual({type:'NUMBER',value:exact});expect(await call(page,cip)).toEqual(cite.status===201?{...cite,status:200}:cite);expect((await call(page,qp)).body.claim.object).toEqual({type:'CONCEPT',conceptId:c(2)});
 expect((await call(page,cip,'PUT',{sourceId:source.sourceId,sourceVersion:1,claimVersion:2},cite.etag!)).status).toBe(200);
 expect((await call(page,qp,'PUT',{predicateConceptId:c(1),value:{type:'STRING',value:'scope'},claimVersion:2},qualifier.etag!)).status).toBe(200);
 for(const endpoint of [cp,cip,qp])expect((await call(foreign,endpoint)).status).toBe(404);
 expect((await call(page,cp,'PUT',edit('NUMBER','1'),'\"cl1\"')).status).toBe(412);
 const graph=base+'/graph/network',search=base+'/search/network';await expect.poll(async()=>{const r=await call(page,search+'?q='+encodeURIComponent(exact)+'&kind=CLAIM');return r.body?.items?.some((n:any)=>n.id===claim.claimId)},{timeout:90000}).toBe(true);
 await expect.poll(async()=>{const r=await call(page,graph);return r.body?.edges?.filter((e:any)=>e.source==='CLAIM:'+claim.claimId).map((e:any)=>e.role).sort()},{timeout:60000}).toEqual(['PREDICATE','SUBJECT']);
 expect((await call(page,concepts+'/'+c(2),'DELETE',undefined,'\"1\"')).status).toBe(204);expect((await call(page,cp)).status).toBe(200);
 await panel.getByRole('button',{name:'관계 열기: '+claim.claimId,exact:true}).click();await expect(panel.getByRole('region',{name:'관계 조건과 수정 이력',exact:true})).toBeVisible();
 const net=page.getByRole('region',{name:'노트 개념 관계 통합 탐색',exact:true});await net.getByRole('button',{name:'통합 탐색 열기',exact:true}).click();await net.getByRole('button',{name:'통합 목록 열기: CLAIM:'+claim.claimId,exact:true}).click();await expect(net.getByRole('region',{name:'통합 탐색 원본 상세',exact:true})).toContainText(exact);
 await detail.getByRole('button',{name:'관계 편집',exact:true}).click();await detail.getByLabel('편집할 관계의 대상 종류').selectOption('DATE');await detail.getByLabel('편집할 관계의 대상 값').fill('2026-10-20');await detail.getByRole('button',{name:'관계 수정 저장',exact:true}).click();await expect(detail).toContainText('관계 Revision v3');
 for(const endpoint of [cip,qp])expect((await call(page,endpoint)).body.claim.object).toEqual({type:'NUMBER',value:exact});
 await detail.getByRole('button',{name:'관계 수정 이력',exact:true}).click();await expect(detail.locator('.concept-claim-revisions button')).toHaveCount(3);await expect(detail).toContainText(exact);
 for(const [type,value] of [['BOOLEAN','false'],['STRING','<img src=x onerror="window.typedXss=true">']]){
  await panel.getByLabel('새 관계의 서술 개념').selectOption(c(1));await panel.getByLabel('새 관계의 대상 종류').selectOption(type);const input=panel.getByLabel('새 관계의 대상 값');if(type==='BOOLEAN')await input.selectOption(value);else await input.fill(value);
  await panel.getByRole('button',{name:'관계 추가',exact:true}).click();await expect(panel.getByLabel('새 관계의 대상 종류')).toHaveValue('CONCEPT');await expect(panel).toContainText(value);
 }
 expect(await page.evaluate(()=>Boolean((window as any).typedXss))).toBe(false);await expect(panel.locator('img,iframe,script')).toHaveCount(0);
 for(const object of [{type:'NUMBER',value:1},{type:'BOOLEAN',value:false},{type:'DATE',value:'2025-02-29'},{type:'STRING',value:''}])expect((await call(page,path,'POST',{predicateConceptId:c(1),object})).status).toBe(400);
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);expect(errors).toEqual([]);
 console.log('TYPED_CLAIM_FIXTURE='+JSON.stringify({spaceId:space,subjectId:c(0),claimId:claim.claimId,citationId:cite.body.citationId,qualifierId:qualifier.body.qualifierId}));
 console.log('TYPED_CLAIMS_VERIFIED: five object kinds, exact decimal, source and qualifier revision pins, real CDC graph/search, two edges, historical target retirement, private authority and XSS/mobile checks');
 }finally{await call(page,'/api/identity/guest-session/revoke','POST',{}).catch(()=>{});await call(foreign,'/api/identity/guest-session/revoke','POST',{}).catch(()=>{});await context.close()}
});
test('large literal Claim text and concurrent typed edits retain drafts and independent history',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 try{
 const space=await start(page),base=`/api/spaces/${space}/concepts`;
 const subject=(await call(page,base,'POST',{title:'Long literal subject',description:'Original'})).body,predicate=(await call(page,base,'POST',{title:'Long literal predicate',description:''})).body,path=base+'/'+subject.conceptId+'/claims';
 const edit=(type:string,value:string)=>({predicateConceptId:predicate.conceptId,object:{type,value}}),long='😀'.repeat(1000),created=await call(page,path,'POST',edit('STRING',long));expect(created.status).toBe(201);expect(created.body.object).toEqual({type:'STRING',value:long});const cp=path+'/'+created.body.claimId;
 expect((await call(page,path,'POST',edit('STRING',long+'x'))).status).toBe(400);
 expect((await call(page,path,'POST',edit('NUMBER','1.00'))).status).toBe(201);expect((await call(page,path,'POST',edit('NUMBER','1.0'))).status).toBe(409);expect((await call(page,path,'POST',edit('STRING','1'))).status).toBe(201);
 const library=page.getByRole('region',{name:'개인 개념 보관함',exact:true});await library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true}).click();await library.getByRole('button',{name:'보관함 개념 열기: Long literal subject',exact:true}).click();
 const parent=library.getByRole('region',{name:'개인 개념 상세와 수정 이력',exact:true});await parent.getByRole('button',{name:'개인 개념 편집',exact:true}).click();await parent.getByLabel('편집할 개념 설명').fill('keep parent draft');
 const panel=library.getByRole('region',{name:'개념 관계와 수정 이력',exact:true});await panel.getByRole('button',{name:'관계 열기: '+created.body.claimId,exact:true}).click();const detail=panel.getByRole('region',{name:'선택한 개념 관계',exact:true});await detail.getByRole('button',{name:'관계 편집',exact:true}).click();await expect(detail.getByLabel('편집할 관계의 대상 값')).toHaveValue(long);await detail.getByLabel('편집할 관계의 대상 값').fill('unsaved literal');
 expect((await call(page,cp,'PUT',edit('BOOLEAN','true'),'\"cl1\"')).status).toBe(200);await detail.getByRole('button',{name:'관계 수정 저장',exact:true}).click();await expect(detail).toContainText('다른 관계 수정');await expect(detail.getByLabel('편집할 관계의 대상 값')).toHaveValue('unsaved literal');
 await detail.getByRole('button',{name:'최신 관계 확인',exact:true}).click();await detail.getByRole('button',{name:'최신 관계 버전으로 다시 시작',exact:true}).click();await expect(detail.getByLabel('편집할 관계의 대상 종류')).toHaveValue('BOOLEAN');await expect(detail.getByLabel('편집할 관계의 대상 값')).toHaveValue('true');await detail.getByLabel('편집할 관계의 대상 값').selectOption('false');await detail.getByRole('button',{name:'관계 수정 저장',exact:true}).click();await expect(detail).toContainText('관계 Revision v3');await expect(parent.getByLabel('편집할 개념 설명')).toHaveValue('keep parent draft');
 const history=(await call(page,cp+'/revisions?limit=5')).body;expect(history.map((r:any)=>r.object.type)).toEqual(['BOOLEAN','BOOLEAN','STRING']);expect(history[2].object.value).toBe(long);
 expect((await call(page,'/api/identity/guest-session/revoke','POST',{})).status).toBe(204);await panel.getByRole('button',{name:'개념 관계 새로고침',exact:true}).click();await expect(page.getByRole('button',{name:'내 개인 공간 시작',exact:true})).toBeVisible();await expect(panel).toHaveCount(0);expect(errors).toEqual([]);
 }finally{await call(page,'/api/identity/guest-session/revoke','POST',{}).catch(()=>{})}
});
