import {test,expect,Page} from '@playwright/test';
async function call(p:Page,path:string,method='GET',body?:unknown,etag?:string){return p.evaluate(async({path,method,body,etag})=>{const r=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:{...(body?{'Content-Type':'application/json'}:{}),...(etag?{'If-Match':etag}:{})},body:body?JSON.stringify(body):undefined});return {status:r.status,body:await r.json().catch(()=>null),etag:r.headers.get('ETag')}},{path,method,body,etag})}
async function start(page:Page){await page.goto('/graph');await page.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();await expect(page.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible();return (await call(page,'/api/spaces/personal')).body.spaceId}
const triple=(predicateConceptId:string,conceptId:string)=>({predicateConceptId,object:{type:'CONCEPT',conceptId}});
test('Claim qualifiers retain exact typed conditions, pinning, private authority, conflicts and retired history',async({page,browser})=>{
 test.setTimeout(180000);const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));const context=await browser.newContext(),foreign=await context.newPage();
 try{
 const space=await start(page);await start(foreign);const base=`/api/spaces/${space}/concepts`,concepts=[];
 for(const title of ['Qualifier subject','Qualifier predicate','Qualifier object','Qualifier scope']){const r=await call(page,base,'POST',{title,description:'original'});expect(r.status).toBe(201);concepts.push(r.body)}
 const c=(i:number)=>concepts[i].conceptId,claim=(await call(page,base+'/'+c(0)+'/claims','POST',triple(c(1),c(2)))).body,claimPath=base+'/'+c(0)+'/claims/'+claim.claimId,path=claimPath+'/qualifiers';
 const edit=(type:string,value:string,claimVersion=1)=>({predicateConceptId:c(3),value:{type,value},claimVersion});
 const exact='9007199254740993.123456789012345678',created=await call(page,path,'POST',edit('NUMBER',exact));expect(created.status).toBe(201);expect(created.etag).toBe('"qf1"');const qpath=path+'/'+created.body.qualifierId;
 for(const endpoint of [path+'?limit=20',qpath,qpath+'/revisions?limit=5'])expect((await call(foreign,endpoint)).status).toBe(404);
 expect((await call(page,qpath,'PUT',edit('NUMBER','1'))).status).toBe(428);
 expect((await call(page,qpath,'PUT',edit('NUMBER','1'),'"cl1"')).status).toBe(400);
 expect((await call(page,path,'POST',edit('DATE','2025-02-29'))).status).toBe(400);
 expect((await call(page,path,'POST',edit('NUMBER',exact))).status).toBe(409);
 const library=page.getByRole('region',{name:'개인 개념 보관함',exact:true});await library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true}).click();
 await library.getByRole('button',{name:'보관함 개념 열기: Qualifier subject',exact:true}).click();await library.getByRole('button',{name:'관계 열기: '+claim.claimId,exact:true}).click();
 const panel=page.getByRole('region',{name:'관계 조건과 수정 이력',exact:true}),detail=panel.getByRole('region',{name:'선택한 관계 조건',exact:true});
 await panel.getByRole('button',{name:'조건 열기: '+created.body.qualifierId,exact:true}).click();await expect(detail).toContainText(exact);await expect(detail).toContainText('고정한 관계 v1');
 const parent=library.getByRole('region',{name:'개인 개념 상세와 수정 이력',exact:true});await parent.getByRole('button',{name:'개인 개념 편집',exact:true}).click();await parent.getByLabel('편집할 개념 설명').fill('unsaved parent draft');
 await detail.getByRole('button',{name:'조건 편집',exact:true}).click();await detail.getByLabel('조건 값',{exact:true}).fill('12.34');await expect(detail.getByRole('button',{name:'조건 수정 저장',exact:true})).toBeDisabled();
 async function inspect(){await detail.getByRole('button',{name:'조건에 적용할 최신 버전 확인',exact:true}).click();await detail.getByRole('button',{name:'확인한 버전으로 조건 작성',exact:true}).click()}
 await inspect();expect((await call(page,claimPath,'PUT',triple(c(1),c(0)),'"cl1"')).status).toBe(200);
 await detail.getByRole('button',{name:'조건 수정 저장',exact:true}).click();await expect(panel.getByRole('status')).toContainText('다른 수정');await expect(detail.getByLabel('조건 값',{exact:true})).toHaveValue('12.34');expect((await call(page,qpath)).body.claim.version).toBe(1);
 await inspect();await expect(detail.getByLabel('조건 값',{exact:true})).toHaveValue(exact);await detail.getByLabel('조건 값',{exact:true}).fill('12.34');await detail.getByRole('button',{name:'조건 수정 저장',exact:true}).click();await expect(panel.getByRole('status')).toContainText('조건을 저장');await expect(detail).toContainText('고정한 관계 v2');await expect(parent.getByLabel('편집할 개념 설명')).toHaveValue('unsaved parent draft');
 expect((await call(page,claimPath)).etag).toBe('"cl2"');expect((await call(page,base+'/'+c(0))).body.description).toBe('original');
 await detail.getByRole('button',{name:'조건 편집',exact:true}).click();await inspect();expect((await call(page,qpath,'PUT',edit('BOOLEAN','false',2),'"qf2"')).status).toBe(200);
 await detail.getByRole('button',{name:'조건 수정 저장',exact:true}).click();await expect(panel.getByRole('status')).toContainText('다른 수정');await inspect();await expect(detail.getByLabel('조건 값 종류')).toHaveValue('BOOLEAN');await expect(detail.getByLabel('조건 값',{exact:true})).toHaveValue('false');await detail.getByRole('button',{name:'조건 수정 저장',exact:true}).click();await expect(panel.getByRole('status')).toContainText('조건을 저장');
 await detail.getByRole('button',{name:'조건 수정 이력',exact:true}).click();await expect(panel.locator('.qualifier-revisions article')).toHaveCount(4);await expect(panel.locator('.qualifier-revisions article').last()).toContainText(exact);
 await panel.getByRole('button',{name:'새 조건 작성',exact:true}).click();await detail.getByLabel('조건의 서술 개념').selectOption(c(3));await detail.getByLabel('조건 값 종류').selectOption('STRING');
 const html='<img src="https://example.invalid/no.png" onerror="alert(1)">';await detail.getByLabel('조건 값',{exact:true}).fill(html);await inspect();await detail.getByRole('button',{name:'조건 추가',exact:true}).click();await expect(panel.getByRole('status')).toContainText('조건을 저장');await expect(detail).toContainText(html);await expect(detail.locator('img,script,iframe')).toHaveCount(0);
 for(const [type,value] of [['DATE','2024-02-29'],['CONCEPT',c(2)]])expect((await call(page,path,'POST',edit(type,value,2))).status).toBe(201);
 for(let i=0;i<20;i++)expect((await call(page,path,'POST',edit('STRING','page '+i,2))).status).toBe(201);
 await panel.getByRole('button',{name:'관계 조건 새로고침',exact:true}).click();await expect(panel.locator('.qualifier-results button')).toHaveCount(20);await panel.getByRole('button',{name:'관계 조건 더 보기',exact:true}).click();await expect(panel.locator('.qualifier-results button')).toHaveCount(24);
 await panel.getByRole('button',{name:'조건 열기: '+created.body.qualifierId,exact:true}).click();await detail.getByLabel('조건 폐기에 동의합니다').check();await detail.getByRole('button',{name:'조건 폐기 확정',exact:true}).click();await expect(panel.getByRole('status')).toContainText('조건을 폐기');
 expect((await call(page,qpath)).status).toBe(404);await detail.getByRole('button',{name:'조건 수정 이력',exact:true}).click();await expect(panel.locator('.qualifier-revisions article')).toHaveCount(5);const history=await call(page,qpath+'/revisions?limit=10');
 await page.setViewportSize({width:390,height:844});await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await page.setViewportSize({width:1280,height:900});
 const claimDetail=library.getByRole('region',{name:'선택한 개념 관계',exact:true});
 // Re-open the Claim before retirement so its independent optimistic version is current.
 await claimDetail.getByRole('button',{name:'관계 편집',exact:true}).click();await claimDetail.getByRole('button',{name:'관계 수정 저장',exact:true}).click();await expect(claimDetail).toContainText('다른 관계 수정');
 await claimDetail.getByRole('button',{name:'최신 관계 확인',exact:true}).click();await claimDetail.getByRole('button',{name:'최신 관계 버전으로 다시 시작',exact:true}).click();await claimDetail.getByRole('button',{name:'관계 편집 취소',exact:true}).click();
 await claimDetail.getByRole('button',{name:'관계 폐기',exact:true}).click();await claimDetail.getByLabel('관계 폐기에 동의합니다').check();await claimDetail.getByRole('button',{name:'관계 폐기 확정',exact:true}).click();
 await expect(panel).toContainText('조건 수정을 종료');await detail.getByRole('button',{name:'조건 수정 이력',exact:true}).click();expect(await call(page,qpath+'/revisions?limit=10')).toEqual(history);expect((await call(page,path+'?limit=20')).status).toBe(404);
 console.log('QUALIFIER_DURABILITY_FIXTURE='+JSON.stringify({claimId:claim.claimId,spaceId:space,qualifierId:created.body.qualifierId}));expect(errors).toEqual([]);console.log('CLAIM_QUALIFIERS_VERIFIED: five typed values, exact decimals, scoped authorization, pins and explicit re-pin, stale Claim and qualifier edits, duplicate prevention, independent parent draft, 20+4 paging, retirement history, XSS escaping and mobile fit');
 }finally{await call(page,'/api/identity/guest-session/revoke','POST',{}).catch(()=>{});await call(foreign,'/api/identity/guest-session/revoke','POST',{}).catch(()=>{});await context.close()}
});
test('Qualifier delayed responses and failed refresh cannot leak across Claim selection or session revocation',async({page})=>{
 const space=await start(page),base=`/api/spaces/${space}/concepts`,concept=(await call(page,base,'POST',{title:'Qualifier race subject',description:''})).body;
 const cp=base+'/'+concept.conceptId,claim=(await call(page,cp+'/claims','POST',triple(concept.conceptId,concept.conceptId))).body,path=cp+'/claims/'+claim.claimId+'/qualifiers';
 const q=await call(page,path,'POST',{predicateConceptId:concept.conceptId,value:{type:'STRING',value:'private race condition'},claimVersion:1});expect(q.status).toBe(201);
 const library=page.getByRole('region',{name:'개인 개념 보관함',exact:true});await library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true}).click();await library.getByRole('button',{name:'보관함 개념 열기: Qualifier race subject',exact:true}).click();await library.getByRole('button',{name:'관계 열기: '+claim.claimId,exact:true}).click();
 const panel=page.getByRole('region',{name:'관계 조건과 수정 이력',exact:true});await expect(panel.locator('.qualifier-results button')).toHaveCount(1);
 await page.route('**'+path+'?*',r=>r.fulfill({status:503,body:'{}'}));await panel.getByRole('button',{name:'관계 조건 새로고침',exact:true}).click();await expect(panel.getByRole('status')).toContainText('요청 결과');await expect(panel.locator('.qualifier-results button')).toHaveCount(0);await page.unrouteAll({behavior:'ignoreErrors'});
 let release!:()=>void,started=false;const held=new Promise<void>(r=>release=r);await page.route('**'+path+'?*',async route=>{const response=await route.fetch();started=true;await held;await route.fulfill({response}).catch(()=>{})});
 await panel.getByRole('button',{name:'관계 조건 새로고침',exact:true}).click();await expect.poll(()=>started).toBe(true);await library.getByRole('button',{name:'관계 상세 닫기',exact:true}).click();release();await expect(panel).toHaveCount(0);await page.unrouteAll({behavior:'ignoreErrors'});
 await library.getByRole('button',{name:'관계 열기: '+claim.claimId,exact:true}).click();await expect(panel.locator('.qualifier-results button')).toHaveCount(1);
 await expect(panel.getByRole('button',{name:'관계 조건 새로고침',exact:true})).toBeEnabled();expect((await call(page,'/api/identity/guest-session/revoke','POST',{})).status).toBe(204);await panel.getByRole('button',{name:'관계 조건 새로고침',exact:true}).click();await expect(page.getByRole('button',{name:'내 개인 공간 시작',exact:true})).toBeVisible();await expect(panel).toHaveCount(0);
});
