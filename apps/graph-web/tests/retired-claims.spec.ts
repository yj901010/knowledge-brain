import {test,expect,Page} from '@playwright/test';
async function call(p:Page,path:string,method='GET',body?:unknown,etag?:string){return p.evaluate(async({path,method,body,etag})=>{const r=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:{...(body?{'Content-Type':'application/json'}:{}),...(etag?{'If-Match':etag}:{})},body:body?JSON.stringify(body):undefined});return {status:r.status,body:await r.json().catch(()=>null),etag:r.headers.get('ETag'),cache:r.headers.get('Cache-Control')}},{path,method,body,etag})}
async function start(p:Page){await p.goto('/graph');await p.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();await expect(p.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible();return (await call(p,'/api/spaces/personal')).body.spaceId}
async function fixture(page:Page,count=2){
 const space=await start(page),base=`/api/spaces/${space}`,concepts=base+'/concepts',ids:string[]=[];
 for(const title of ['Retired relation subject','Retired relation predicate','Retired relation target']){const r=await call(page,concepts,'POST',{title,description:'Archive fixture'});expect(r.status).toBe(201);ids.push(r.body.conceptId)}
 const path=concepts+'/'+ids[0]+'/claims',retired:string[]=[],edit=(type:string,value:string)=>({predicateConceptId:ids[1],object:type==='CONCEPT'?{type,conceptId:value}:{type,value}}),exact='9007199254740993.123456789012345678',literal='<img src=x onerror="window.retiredXss=true">'+ '🧠'.repeat(400);
 for(let n=0;n<count;n++){
  const created=await call(page,path,'POST',edit('NUMBER',n===0?exact:String(n)));expect(created.status).toBe(201);const cp=path+'/'+created.body.claimId;retired.push(created.body.claimId);
  if(n===0){for(const [index,[type,value]] of [['STRING',exact],['DATE','2026-10-21'],['BOOLEAN','false'],['CONCEPT',ids[2]],['STRING',literal]].entries())expect((await call(page,cp,'PUT',edit(type,value),`"cl${index+1}"`)).status).toBe(200)}
  expect((await call(page,cp,'DELETE',undefined,n===0?'"cl6"':'"cl1"')).status).toBe(204);
 }
 const active=await call(page,path,'POST',edit('STRING','Active relation draft'));expect(active.status).toBe(201);
 const library=page.getByRole('region',{name:'개인 개념 보관함',exact:true}),panel=library.getByRole('region',{name:'개념 관계와 수정 이력',exact:true}),archive=panel.getByRole('region',{name:'폐기한 관계 보관함',exact:true}),history=archive.getByRole('region',{name:'폐기한 관계 이력',exact:true}),comparison=history.getByRole('region',{name:'관계 버전 비교',exact:true});
 async function open(){await library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true}).click();await library.getByRole('button',{name:'보관함 개념 열기: Retired relation subject',exact:true}).click();await archive.getByRole('button',{name:'폐기한 관계 열기',exact:true}).click();await expect(archive.locator('.retired-claim-results button')).toHaveCount(Math.min(count,20))}
 async function select(index=0){await archive.getByRole('button',{name:'폐기 관계 이력 열기: '+retired[index],exact:true}).click();await expect(comparison.getByRole('table')).toBeVisible()}
 return {space,base,concepts,ids,path,retired,active,library,panel,archive,history,comparison,open,select,edit,exact,literal};
}
async function revoke(p:Page){await call(p,'/api/identity/guest-session/revoke','POST',{}).catch(()=>{})}

test('retired Claim catalogue persists across reopening and refresh with typed history and comparison after reference retirement',async({page,browser})=>{
 const context=await browser.newContext(),foreign=await context.newPage(),errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error'&&/Content Security Policy|Refused to/.test(m.text()))errors.push(m.text())});
 try{
  const f=await fixture(page,22);await start(foreign);const cp=f.path+'/'+f.retired[0],original=await call(page,cp+'/revisions?limit=10');
  const first=await call(page,f.path+'?state=RETIRED&limit=20');expect(first.status).toBe(200);expect(first.cache).toBe('no-store');expect(first.body.items.map((r:any)=>r.claimId)).toEqual(f.retired.slice(0,20));expect(first.body.nextAfter).toBe(f.retired[19]);expect((await call(page,f.path+'?state=RETIRED&limit=20&after='+first.body.nextAfter)).body.items.map((r:any)=>r.claimId)).toEqual(f.retired.slice(20));
  expect((await call(page,f.path)).body.items.map((r:any)=>r.claimId)).toEqual([f.active.body.claimId]);for(const query of ['state=ALL','state=retired','state=RETIRED&state=ACTIVE','state=RETIRED&limit=101','state=RETIRED&after=bad'])expect((await call(page,f.path+'?'+query)).status).toBe(400);
  for(const endpoint of [f.path+'?state=RETIRED',cp+'/revisions'])expect((await call(foreign,endpoint)).status).toBe(404);
  expect((await call(page,cp)).status).toBe(404);expect((await call(page,cp,'PUT',f.edit('STRING','restore'),'"cl7"')).status).toBe(404);expect((await call(page,cp,'DELETE',undefined,'"cl7"')).status).toBe(404);
  await f.open();await f.archive.getByRole('button',{name:'폐기한 관계 더 보기',exact:true}).click();await expect(f.archive.locator('.retired-claim-results button')).toHaveCount(22);
  const parent=f.library.getByRole('region',{name:'개인 개념 상세와 수정 이력',exact:true});await parent.getByRole('button',{name:'개인 개념 편집',exact:true}).click();await parent.getByLabel('편집할 개념 설명').fill('Archive must retain parent draft');
  await f.panel.getByRole('button',{name:'관계 열기: '+f.active.body.claimId,exact:true}).click();const activeDetail=f.panel.getByRole('region',{name:'선택한 개념 관계',exact:true});await activeDetail.getByRole('button',{name:'관계 편집',exact:true}).click();await activeDetail.getByLabel('편집할 관계의 대상 값').fill('Archive must retain active Claim draft');
  let writes=0;const track=(r:any)=>{if(new URL(r.url()).pathname.startsWith(f.base)&&!['GET','HEAD'].includes(r.method()))writes++};page.on('request',track);
  await f.select();await expect(f.comparison.getByRole('status')).toHaveText('v6 → v7 · 변경된 항목 1개');await f.history.getByRole('button',{name:'이전 폐기 관계 이력 더 보기',exact:true}).click();await expect(f.history.locator('.retired-claim-revisions button')).toHaveCount(7);await f.comparison.getByLabel('비교 기준 버전').selectOption(original.body[6].revisionId);await f.comparison.getByLabel('비교 대상 버전').selectOption(original.body[5].revisionId);await expect(f.comparison.getByRole('status')).toContainText('변경된 항목 1개');await expect(f.comparison).toContainText(f.exact);
  await expect(f.archive.locator('img,script,iframe')).toHaveCount(0);expect(await page.evaluate(()=>Boolean((window as any).retiredXss))).toBe(false);await page.setViewportSize({width:390,height:844});await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await f.archive.getByRole('button',{name:'폐기한 관계 닫기',exact:true}).click();await expect(f.history).toHaveCount(0);await f.archive.getByRole('button',{name:'폐기한 관계 열기',exact:true}).click();await expect(f.archive.locator('.retired-claim-results button')).toHaveCount(20);await f.select();await expect(parent.getByLabel('편집할 개념 설명')).toHaveValue('Archive must retain parent draft');await expect(activeDetail.getByLabel('편집할 관계의 대상 값')).toHaveValue('Archive must retain active Claim draft');expect(writes).toBe(0);page.off('request',track);
  await page.reload();await f.open();await f.select();expect(await call(page,cp+'/revisions?limit=10')).toEqual(original);
  for(const ref of [f.ids[1],f.ids[2]])expect((await call(page,f.concepts+'/'+ref,'DELETE',undefined,'"1"')).status).toBe(204);
  expect((await call(page,f.path+'?state=ACTIVE')).body.items).toEqual([]);expect((await call(page,f.path+'?state=RETIRED&limit=100')).body.items.map((r:any)=>r.claimId)).toEqual(f.retired);
  await parent.getByRole('button',{name:'개인 개념 폐기',exact:true}).click();await parent.getByLabel('개념 폐기에 동의합니다').check();await parent.getByRole('button',{name:'개념 폐기 확정',exact:true}).click();await expect(f.panel).toContainText('개념이 폐기되어');await f.archive.getByRole('button',{name:'폐기한 관계 새로고침',exact:true}).click();await expect(f.archive.locator('.retired-claim-results button')).toHaveCount(20);await f.select();expect((await call(page,f.path+'?state=ACTIVE')).status).toBe(404);expect(await call(page,cp+'/revisions?limit=10')).toEqual(original);
  console.log('RETIRED_CLAIM_FIXTURE='+JSON.stringify({spaceId:f.space,subjectId:f.ids[0],claimId:f.retired[0],revisionIds:original.body.map((r:any)=>r.revisionId)}));expect(errors).toEqual([]);
 }finally{await revoke(page);await revoke(foreign);await context.close()}
});

test('retired catalogue and history validate identity, state and cursors, clear failed reads and recover explicitly',async({page})=>{
 try{
  const f=await fixture(page,22);await f.open();await f.select();const list='**'+f.path+'?*',history='**'+f.path+'/'+f.retired[0]+'/revisions?*';
  for(const corrupt of [(p:any)=>{p.items[0].state='ACTIVE'},(p:any)=>{p.items[0].spaceId=f.ids[2]},(p:any)=>{p.nextAfter=f.ids[2]}]){
   await page.route(list,async route=>{if(new URL(route.request().url()).searchParams.get('state')!=='RETIRED')return route.continue();const response=await route.fetch(),body=await response.json();corrupt(body);await route.fulfill({response,json:body})});
   await f.archive.getByRole('button',{name:'폐기한 관계 새로고침',exact:true}).click();await expect(f.archive.getByRole('status')).toContainText('폐기한 관계를 불러오지 못했습니다');await expect(f.archive.locator('.retired-claim-results button')).toHaveCount(0);await expect(f.history).toHaveCount(0);await page.unroute(list);await f.archive.getByRole('button',{name:'폐기한 관계 새로고침',exact:true}).click();await expect(f.archive.locator('.retired-claim-results button')).toHaveCount(20);
  }
  await f.select();await page.route(list,route=>new URL(route.request().url()).searchParams.get('state')==='RETIRED'?route.fulfill({status:503,contentType:'application/json',body:'{}'}):route.continue());await f.archive.getByRole('button',{name:'폐기한 관계 더 보기',exact:true}).click();await expect(f.archive.getByRole('status')).toContainText('폐기한 관계를 불러오지 못했습니다');await expect(f.history).toHaveCount(0);await page.unroute(list);await f.archive.getByRole('button',{name:'폐기한 관계 새로고침',exact:true}).click();await f.select();
  for(const corrupt of [(p:any[])=>{p[0].claimId=f.ids[2]},(p:any[])=>{p[0].state='ACTIVE'},(p:any[])=>{p[0].object={type:'NUMBER',value:'1'}},(p:any[])=>{p[1].revisionId=p[0].revisionId}]){
   await page.route(history,async route=>{const response=await route.fetch(),body=await response.json();corrupt(body);await route.fulfill({response,json:body})});await f.history.getByRole('button',{name:'폐기 관계 이력 새로고침',exact:true}).click();await expect(f.history.getByRole('status')).toContainText('이력을 불러오지 못했습니다');await expect(f.comparison).toHaveCount(0);await page.unroute(history);await f.history.getByRole('button',{name:'폐기 관계 이력 새로고침',exact:true}).click();await expect(f.comparison.getByRole('table')).toBeVisible();
  }
  const original=(await call(page,f.path+'/'+f.retired[0]+'/revisions?limit=5')).body;
  await page.route(history,async route=>{const response=await route.fetch(),body=await response.json();if(new URL(route.request().url()).searchParams.has('before'))body[0].revisionId=original[0].revisionId;await route.fulfill({response,json:body})});await f.history.getByRole('button',{name:'이전 폐기 관계 이력 더 보기',exact:true}).click();await expect(f.history.getByRole('status')).toContainText('이력을 불러오지 못했습니다');await expect(f.comparison).toHaveCount(0);await page.unroute(history);await f.history.getByRole('button',{name:'폐기 관계 이력 새로고침',exact:true}).click();await expect(f.comparison.getByRole('table')).toBeVisible();
 }finally{await page.unrouteAll({behavior:'ignoreErrors'});await revoke(page)}
});

test('deferred retired reads cannot repopulate a closed or switched archive and revocation clears private history',async({page})=>{
 try{
  const f=await fixture(page);await f.open();await f.select();const pattern='**'+f.path+'/'+f.retired[0]+'/revisions?*';
  for(const switchConcept of [false,true]){
   let release!:()=>void,done!:()=>void,started=false;const held=new Promise<void>(r=>release=r),finished=new Promise<void>(r=>done=r);await page.route(pattern,async route=>{const response=await route.fetch();started=true;try{await held;await route.fulfill({response}).catch(()=>{})}finally{done()}});
   try{await f.history.getByRole('button',{name:'폐기 관계 이력 새로고침',exact:true}).click();await expect.poll(()=>started).toBe(true);if(switchConcept)await f.library.getByRole('button',{name:'보관함 개념 열기: Retired relation target',exact:true}).click();else await f.archive.getByRole('button',{name:'폐기한 관계 닫기',exact:true}).click();release();await finished;await expect(f.history).toHaveCount(0)}finally{release();await page.unroute(pattern)}
   if(switchConcept)await f.library.getByRole('button',{name:'보관함 개념 열기: Retired relation subject',exact:true}).click();await f.archive.getByRole('button',{name:'폐기한 관계 열기',exact:true}).click();await f.select();
  }
  expect((await call(page,'/api/identity/guest-session/revoke','POST',{})).status).toBe(204);await f.history.getByRole('button',{name:'폐기 관계 이력 새로고침',exact:true}).click();await expect(page.getByRole('button',{name:'내 개인 공간 시작',exact:true})).toBeVisible();await expect(f.library).toHaveCount(0);expect(await page.evaluate(()=>Object.keys(localStorage).length+Object.keys(sessionStorage).length)).toBe(0);
 }finally{await page.unrouteAll({behavior:'ignoreErrors'});await revoke(page)}
});
