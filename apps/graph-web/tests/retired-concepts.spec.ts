import {test,expect,Page} from '@playwright/test';
async function call(p:Page,path:string,method='GET',body?:unknown,etag?:string){return p.evaluate(async({path,method,body,etag})=>{const r=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:{...(body?{'Content-Type':'application/json'}:{}),...(etag?{'If-Match':etag}:{})},body:body?JSON.stringify(body):undefined});return {status:r.status,body:await r.json().catch(()=>null),etag:r.headers.get('ETag'),cache:r.headers.get('Cache-Control')}},{path,method,body,etag})}
async function start(p:Page){await p.goto('/graph');await p.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();await expect(p.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible();return (await call(p,'/api/spaces/personal')).body.spaceId}
async function revoke(p:Page){await call(p,'/api/identity/guest-session/revoke','POST',{}).catch(()=>{})}
async function fixture(page:Page,count=2){
 const space=await start(page),base=`/api/spaces/${space}`,path=base+'/concepts',ids:string[]=[],literal='<img src=x onerror="window.retiredConceptXss=true">'+ '🧠'.repeat(400);
 const active=await call(page,path,'POST',{title:'Keep active draft',description:'Active description'});expect(active.status).toBe(201);let claim='';
 for(let n=0;n<count;n++){
  const c=await call(page,path,'POST',{title:`Archived ${n}`,description:'Original description'});expect(c.status).toBe(201);ids.push(c.body.conceptId);
  if(n===0){
   const relation=await call(page,path+'/'+ids[0]+'/claims','POST',{predicateConceptId:active.body.conceptId,object:{type:'NUMBER',value:'9007199254740993.123456789012345678'}});expect(relation.status).toBe(201);claim=relation.body.claimId;
   expect((await call(page,path+'/'+ids[0]+'/claims/'+claim,'DELETE',undefined,'"cl1"')).status).toBe(204);
   for(let v=1;v<=5;v++)expect((await call(page,path+'/'+ids[0],'PUT',{title:'Archived 0',description:v===5?literal:`Description v${v+1}`},`"${v}"`)).status).toBe(200);
  }
  expect((await call(page,path+'/'+c.body.conceptId,'DELETE',undefined,n===0?'"6"':'"1"')).status).toBe(204);
 }
 const library=page.getByRole('region',{name:'개인 개념 보관함',exact:true}),archive=library.getByRole('region',{name:'폐기한 개념 보관함',exact:true}),history=archive.getByRole('region',{name:'폐기한 개념 이력',exact:true}),detail=history.getByRole('article',{name:'선택한 폐기 개념 버전',exact:true});
 async function open(){await library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true}).click();await archive.getByRole('button',{name:'폐기한 개념 열기',exact:true}).click();await expect(archive.locator('.retired-concept-results button')).toHaveCount(Math.min(count,20))}
 async function select(n=0){await archive.getByRole('button',{name:`폐기 개념 이력 열기: Archived ${n}`,exact:true}).click();await expect(detail).toBeVisible()}
 return {space,base,path,ids,literal,claim,active,library,archive,history,detail,open,select};
}

test('retired Concepts can be rediscovered with original histories and retired Claims after reload without affecting active drafts',async({page,browser})=>{
 const context=await browser.newContext(),foreign=await context.newPage(),errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 try{
  const f=await fixture(page,22),original=await call(page,f.path+'/'+f.ids[0]+'/revisions?limit=10');await start(foreign);
  const first=await call(page,f.path+'?state=RETIRED&limit=20');expect(first.status).toBe(200);expect(first.cache).toBe('no-store');expect(first.body.items.map((r:any)=>r.conceptId)).toEqual(f.ids.slice(0,20));expect(first.body.nextAfter).toBe(f.ids[19]);expect((await call(page,f.path+'?state=RETIRED&after='+f.ids[19])).body.items.map((r:any)=>r.conceptId)).toEqual(f.ids.slice(20));
  expect((await call(page,f.path)).body.items.map((r:any)=>r.conceptId)).toEqual([f.active.body.conceptId]);expect((await call(page,f.path+'/'+f.ids[0])).status).toBe(404);
  expect((await call(foreign,f.path+'?state=RETIRED')).status).toBe(404);expect((await call(foreign,f.path+'/'+f.ids[0]+'/revisions')).status).toBe(404);
  for(const suffix of ['?state=ALL','?state=RETIRED&state=ACTIVE','/'+f.ids[0]+'?state=RETIRED','/'+f.ids[0]+'/revisions?state=RETIRED'])expect((await call(page,f.path+suffix)).status).toBe(400);
  await f.open();await f.archive.getByRole('button',{name:'폐기한 개념 더 보기',exact:true}).click();await expect(f.archive.locator('.retired-concept-results button')).toHaveCount(22);
  await f.library.getByRole('button',{name:'보관함 개념 열기: Keep active draft',exact:true}).click();const activeEditor=f.library.getByRole('region',{name:'개인 개념 상세와 수정 이력',exact:true});await activeEditor.getByRole('button',{name:'개인 개념 편집',exact:true}).click();await activeEditor.getByLabel('편집할 개념 설명').fill('Retain this active draft');
  await f.library.getByRole('button',{name:'개인 개념 작성',exact:true}).click();await f.library.getByLabel('새 개인 개념 제목',{exact:true}).fill('Retain new Concept draft');
  let writes=0;const track=(r:any)=>{if(new URL(r.url()).pathname.startsWith(f.base)&&!['GET','HEAD'].includes(r.method()))writes++};page.on('request',track);
  await f.select();await expect(f.detail.locator('pre')).toHaveText(f.literal);await f.history.getByRole('button',{name:'이전 폐기 개념 이력 더 보기',exact:true}).click();await expect(f.history.locator('.retired-concept-revisions button')).toHaveCount(7);await f.history.getByRole('button',{name:'v1 · ACTIVE',exact:true}).click();await expect(f.detail.locator('pre')).toHaveText('Original description');await expect(f.detail).toContainText(original.body[6].revisionId);await expect(f.detail).toContainText(original.body[6].actorId);
  const claims=f.history.getByRole('region',{name:'폐기한 관계 보관함',exact:true});await claims.getByRole('button',{name:'폐기한 관계 열기',exact:true}).click();await claims.getByRole('button',{name:'폐기 관계 이력 열기: '+f.claim,exact:true}).click();await expect(claims.getByRole('region',{name:'관계 버전 비교',exact:true})).toContainText('9007199254740993.123456789012345678');
  await page.setViewportSize({width:390,height:844});await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await expect(f.archive.locator('img,script,iframe')).toHaveCount(0);expect(await page.evaluate(()=>Boolean((window as any).retiredConceptXss))).toBe(false);
  await f.archive.getByRole('button',{name:'폐기한 개념 닫기',exact:true}).click();await expect(f.history).toHaveCount(0);await f.archive.getByRole('button',{name:'폐기한 개념 열기',exact:true}).click();await expect(f.archive.locator('.retired-concept-results button')).toHaveCount(20);await f.select();await expect(activeEditor.getByLabel('편집할 개념 설명')).toHaveValue('Retain this active draft');await expect(f.library.getByLabel('새 개인 개념 제목',{exact:true})).toHaveValue('Retain new Concept draft');expect(writes).toBe(0);page.off('request',track);
  await page.reload();await f.open();await f.select();expect(await call(page,f.path+'/'+f.ids[0]+'/revisions?limit=10')).toEqual(original);expect(errors).toEqual([]);
 }finally{await revoke(page);await revoke(foreign);await context.close()}
});

test('archive rejects invalid pages and histories, clears stale results and allows explicit retry',async({page})=>{
 try{
  const f=await fixture(page,22);await f.open();await f.select();
  const listPattern=new RegExp(f.path+'\\?state=RETIRED.*$'),historyPattern=new RegExp(f.path+'/'+f.ids[0]+'/revisions\\?.*$');
  const catalogue=(await call(page,f.path+'?state=RETIRED&limit=20')).body,head=(await call(page,f.path+'/'+f.ids[0]+'/revisions?limit=5')).body;
  for(const corrupt of [(p:any)=>{p.items.reverse()},(p:any)=>{p.nextAfter=f.ids[21]},(p:any)=>{p.items[0].version=0},(p:any)=>{p.items[0].conceptId=f.ids[1]}]){
   const body=structuredClone(catalogue);corrupt(body);await page.route(listPattern,r=>r.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)}));await f.archive.getByRole('button',{name:'폐기한 개념 새로고침',exact:true}).click();await expect(f.archive.getByRole('status')).toContainText('폐기한 개념을 불러오지 못했습니다');await expect(f.archive.locator('.retired-concept-results button')).toHaveCount(0);await expect(f.history).toHaveCount(0);await page.unroute(listPattern);
  }
  await f.archive.getByRole('button',{name:'폐기한 개념 새로고침',exact:true}).click();await expect(f.archive.locator('.retired-concept-results button')).toHaveCount(20);await f.select();
  for(const corrupt of [(r:any[])=>{r[0].conceptId=f.ids[1]},(r:any[])=>{r[0].state='ACTIVE'},(r:any[])=>{r[0].title='Wrong head'},(r:any[])=>{r[1].revisionId=r[0].revisionId},(r:any[])=>{r[0].actorId='bad'}]){
   const body=structuredClone(head);corrupt(body);await page.route(historyPattern,r=>r.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)}));await f.history.getByRole('button',{name:'폐기 개념 이력 새로고침',exact:true}).click();await expect(f.history.getByRole('status')).toContainText('폐기한 개념의 이력을 불러오지 못했습니다');await expect(f.detail).toHaveCount(0);await expect(f.history.getByRole('region',{name:'폐기한 관계 보관함',exact:true})).toHaveCount(0);await page.unroute(historyPattern);
  }
  await f.history.getByRole('button',{name:'폐기 개념 이력 새로고침',exact:true}).click();await expect(f.detail).toBeVisible();
  const old=(await call(page,f.path+'/'+f.ids[0]+'/revisions?limit=5&before=3')).body;old[0].revisionId=head[0].revisionId;await page.route(historyPattern,r=>r.fulfill({status:200,contentType:'application/json',body:JSON.stringify(old)}));await f.history.getByRole('button',{name:'이전 폐기 개념 이력 더 보기',exact:true}).click();await expect(f.detail).toHaveCount(0);await expect(f.history.getByRole('status')).toBeVisible();await page.unroute(historyPattern);
  await page.route(listPattern,r=>r.fulfill({status:503,body:'{}'}));await f.archive.getByRole('button',{name:'폐기한 개념 더 보기',exact:true}).click();await expect(f.archive.locator('.retired-concept-results button')).toHaveCount(0);await expect(f.history).toHaveCount(0);await page.unroute(listPattern);await f.archive.getByRole('button',{name:'폐기한 개념 새로고침',exact:true}).click();await expect(f.archive.locator('.retired-concept-results button')).toHaveCount(20);await f.select();
 }finally{await revoke(page)}
});

test('closed or switched archive requests cannot repopulate private history and revocation clears the archive',async({page})=>{
 try{
  const f=await fixture(page);await f.open();const listPattern=new RegExp(f.path+'\\?state=RETIRED.*$'),historyPattern=new RegExp(f.path+'/'+f.ids[0]+'/revisions\\?.*$');
  const catalogue=(await call(page,f.path+'?state=RETIRED&limit=20')).body,head=(await call(page,f.path+'/'+f.ids[0]+'/revisions?limit=5')).body;
  let release!:()=>void,done!:()=>void,started=false;let held=new Promise<void>(resolve=>{release=resolve}),finished=new Promise<void>(resolve=>{done=resolve});
  await page.route(listPattern,async route=>{started=true;try{await held;await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(catalogue)}).catch(()=>{})}finally{done()}});
  await f.archive.getByRole('button',{name:'폐기한 개념 새로고침',exact:true}).click();await expect.poll(()=>started).toBe(true);await f.archive.getByRole('button',{name:'폐기한 개념 닫기',exact:true}).click();release();await finished;await page.unroute(listPattern);await expect(f.archive.locator('.retired-concept-results button')).toHaveCount(0);await f.archive.getByRole('button',{name:'폐기한 개념 열기',exact:true}).click();await expect(f.archive.locator('.retired-concept-results button')).toHaveCount(2);
  started=false;held=new Promise<void>(resolve=>{release=resolve});finished=new Promise<void>(resolve=>{done=resolve});await page.route(historyPattern,async route=>{started=true;try{await held;await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(head)}).catch(()=>{})}finally{done()}});
  await f.archive.getByRole('button',{name:'폐기 개념 이력 열기: Archived 0',exact:true}).click();await expect.poll(()=>started).toBe(true);await f.select(1);release();await finished;await page.unroute(historyPattern);await expect(f.detail).toContainText('Archived 1');await expect(f.detail).not.toContainText(f.literal);
  await call(page,'/api/identity/guest-session/revoke','POST',{});await f.history.getByRole('button',{name:'폐기 개념 이력 새로고침',exact:true}).click();await expect(f.archive).toHaveCount(0);await expect(f.library).toHaveCount(0);expect((await call(page,f.path+'?state=RETIRED')).status).toBe(401);
 }finally{await revoke(page)}
});
