import {test,expect,Page} from '@playwright/test';

async function call(page:Page,path:string,method='GET',body?:unknown,etag?:string){return page.evaluate(async({path,method,body,etag})=>{const r=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:{...(body?{'Content-Type':'application/json'}:{}),...(etag?{'If-Match':etag}:{})},body:body?JSON.stringify(body):undefined});return {status:r.status,body:await r.json().catch(()=>null),etag:r.headers.get('ETag'),cache:r.headers.get('Cache-Control')}},{path,method,body,etag})}
async function start(page:Page){await page.goto('/graph');await page.getByRole('button',{name:'내 개인 공간 시작'}).click();await expect(page.getByRole('button',{name:'+ 새 노트'})).toBeVisible({timeout:90000});return (await call(page,'/api/spaces/personal')).body.spaceId as string}
async function revoke(page:Page){await call(page,'/api/identity/guest-session/revoke','POST',{})}
async function fixture(page:Page,count=2){
 const space=await start(page),base=`/api/spaces/${space}`,path=base+'/notes',ids:string[]=[],titles:string[]=[];
 const literal='# 보존한 기록\n<script>window.archiveXss=true</script>\n![private](https://archive.invalid/pixel)\n'+('🧠 기록 '.repeat(500));
 const active=await call(page,path,'POST',{title:'Keep active Note',markdown:'Active body'});expect(active.status).toBe(201);
 for(let i=0;i<count;i++){
  const daily=i===count-1;const r=await call(page,daily?base+'/daily-notes':path,'POST',daily?{date:'2026-10-20',timezone:'Asia/Seoul'}:{title:i<2?'Duplicate archived title':`Archived ${i}`,markdown:'Original body'});expect(r.status).toBe(201);const note=daily?r.body.note:r.body;ids.push(note.noteId);titles.push(note.title);
  if(i===0)for(let v=1;v<=5;v++)expect((await call(page,path+'/'+note.noteId,'PUT',{title:note.title,markdown:v===5?literal:`Body v${v+1}`},`"${v}"`)).status).toBe(200);
  expect((await call(page,path+'/'+note.noteId,'DELETE',undefined,i===0?'"6"':'"1"')).status).toBe(204);
 }
 const library=page.getByRole('region',{name:'전체 노트 목록',exact:true}),archive=library.getByRole('region',{name:'폐기한 노트 보관함',exact:true}),history=archive.getByRole('region',{name:'폐기한 노트 이력',exact:true}),detail=history.getByRole('article',{name:'선택한 폐기 노트 버전',exact:true});
 async function open(){await archive.getByRole('button',{name:'폐기한 노트 열기',exact:true}).click();await expect(archive.locator('.retired-note-results button')).toHaveCount(Math.min(count,20))}
 async function select(index=0){await archive.getByRole('button',{name:`폐기 노트 이력 열기: ${titles[index]} · ${ids[index]}`,exact:true}).click();await expect(detail).toBeVisible()}
 return {space,base,path,ids,titles,literal,active,library,archive,history,detail,open,select};
}

test('retired ordinary and Daily Notes remain discoverable with immutable history and safe preview',async({page,browser})=>{
 const context=await browser.newContext(),foreign=await context.newPage(),errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 try{
  const f=await fixture(page,22);await start(foreign);const before=await call(page,f.path+'/'+f.ids[0]+'/revisions?limit=10');
  const list=await call(page,f.path+'?state=RETIRED&limit=20');expect(list.status).toBe(200);expect(list.cache).toBe('no-store');expect(list.body.items.map((n:any)=>n.noteId)).toEqual(f.ids.slice(0,20));expect(list.body.nextAfter).toBe(f.ids[19]);
  expect((await call(page,f.path+'?state=RETIRED&after='+f.ids[19])).body.items.map((n:any)=>n.noteId)).toEqual(f.ids.slice(20));expect((await call(page,f.path)).body.items.map((n:any)=>n.noteId)).toEqual([f.active.body.noteId]);expect((await call(page,f.path+'?state=ACTIVE')).body.items).toEqual((await call(page,f.path)).body.items);
  expect((await call(page,f.path+'/'+f.ids[0])).status).toBe(404);expect((await call(foreign,f.path+'?state=RETIRED')).status).toBe(404);expect((await call(foreign,f.path+'/'+f.ids[0]+'/revisions')).status).toBe(404);
  for(const suffix of ['?state=ALL','?state=RETIRED&state=ACTIVE','?state=RETIRED&limit=101','/'+f.ids[0]+'?state=RETIRED','/'+f.ids[0]+'/revisions?state=RETIRED'])expect((await call(page,f.path+suffix)).status).toBe(400);
  await f.library.getByRole('button',{name:'노트 목록 열기',exact:true}).click();await f.library.getByRole('button',{name:'Keep active Note',exact:true}).click();const editor=page.getByRole('region',{name:'노트 본문과 수정 이력'});await editor.getByRole('button',{name:'노트 편집',exact:true}).click();await editor.getByLabel('편집할 Markdown 본문').fill('Keep independent active draft');
  let writes=0,external=0;const track=(r:any)=>{if(new URL(r.url()).pathname.startsWith(f.base)&&!['GET','HEAD'].includes(r.method()))writes++;if(r.url().includes('archive.invalid'))external++};page.on('request',track);
  await f.open();await f.archive.getByRole('button',{name:'폐기한 노트 더 보기'}).click();await expect(f.archive.locator('.retired-note-results button')).toHaveCount(22);await f.select();await expect(f.detail.locator('pre')).toHaveText(f.literal);
  await f.detail.getByRole('button',{name:'폐기 노트 이력 미리보기'}).click();await expect(f.detail.getByRole('region',{name:'폐기 노트 이력 Markdown 미리보기'})).toContainText('보존한 기록');await expect(f.detail.locator('script,img,iframe')).toHaveCount(0);await f.detail.getByRole('button',{name:'폐기 노트 이력 원문 보기'}).click();await expect(f.detail.locator('pre')).toHaveText(f.literal);
  await f.history.getByRole('button',{name:'이전 폐기 노트 이력 더 보기'}).click();await expect(f.history.locator('.retired-note-revisions button')).toHaveCount(7);await f.history.getByRole('button',{name:'v1 · ACTIVE',exact:true}).click();await expect(f.detail.locator('pre')).toHaveText('Original body');await expect(f.detail).toContainText(before.body[6].revisionId);await expect(f.detail).toContainText(before.body[6].actorId);await expect(f.detail).toContainText(before.body[6].occurredAt);
  await expect(f.archive.getByRole('button',{name:/복원 준비|수정 저장|폐기 확정/})).toHaveCount(0);await page.setViewportSize({width:390,height:844});await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await expect(editor.getByLabel('편집할 Markdown 본문')).toHaveValue('Keep independent active draft');
  await f.select(21);await expect(f.detail.locator('pre')).toHaveText('# 2026-10-20');await f.archive.getByRole('button',{name:'폐기한 노트 닫기'}).click();await expect(f.history).toHaveCount(0);await f.open();await f.select();expect(writes).toBe(0);expect(external).toBe(0);page.off('request',track);
  expect(await call(page,f.path+'/'+f.ids[0]+'/revisions?limit=10')).toEqual(before);await page.reload();await f.open();await f.select();expect(await page.evaluate(()=>Boolean((window as any).archiveXss))).toBe(false);expect(errors).toEqual([]);
  // Daily binding still exists after discovery; the same date cannot produce a new Note.
  expect((await call(page,f.base+'/daily-notes','POST',{date:'2026-10-20',timezone:'Asia/Seoul'})).status).toBe(409);
 }finally{await revoke(page);await revoke(foreign);await context.close()}
});

test('archive rejects invalid lists and retained history and clears results after failed pagination',async({page})=>{
 try{
  const f=await fixture(page,22);await f.open();await f.select();const listPattern=new RegExp(f.path+'\\?state=RETIRED.*$'),historyPattern=new RegExp(f.path+'/'+f.ids[0]+'/revisions\\?.*$');
  const list=(await call(page,f.path+'?state=RETIRED&limit=20')).body,head=(await call(page,f.path+'/'+f.ids[0]+'/revisions?limit=5')).body;
  for(const corrupt of [(p:any)=>p.items.reverse(),(p:any)=>p.nextAfter=f.ids[21],(p:any)=>p.items[0].version=1,(p:any)=>p.items[0].noteId=f.ids[1],(p:any)=>p.items[0].title=' ',(p:any)=>p.items[0].updatedAt='bad']){
   const body=structuredClone(list);corrupt(body);await page.route(listPattern,r=>r.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)}));await f.archive.getByRole('button',{name:'폐기한 노트 새로고침'}).click();await expect(f.archive.getByRole('status')).toContainText('폐기한 노트를 불러오지 못했습니다');await expect(f.archive.locator('.retired-note-results button')).toHaveCount(0);await expect(f.history).toHaveCount(0);await page.unroute(listPattern);
  }
  await f.archive.getByRole('button',{name:'폐기한 노트 새로고침'}).click();await expect(f.archive.locator('.retired-note-results button')).toHaveCount(20);await f.select();
  for(const corrupt of [(r:any[])=>r[0].noteId=f.ids[1],(r:any[])=>r[0].state='ACTIVE',(r:any[])=>r[0].title='Wrong head',(r:any[])=>r[1].revisionId=r[0].revisionId,(r:any[])=>r[0].actorId='bad',(r:any[])=>r[0].markdown='가'.repeat(11000),(r:any[])=>r[0].version=99]){
   const body=structuredClone(head);corrupt(body);await page.route(historyPattern,r=>r.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)}));await f.history.getByRole('button',{name:'폐기 노트 이력 새로고침'}).click();await expect(f.history.getByRole('status')).toContainText('이력을 불러오지 못했습니다');await expect(f.detail).toHaveCount(0);await page.unroute(historyPattern);
  }
  await f.history.getByRole('button',{name:'폐기 노트 이력 새로고침'}).click();await expect(f.detail).toBeVisible();await page.route(historyPattern,r=>r.fulfill({status:200,body:JSON.stringify([{...head[0],version:2,state:'ACTIVE'}])}));await f.history.getByRole('button',{name:'이전 폐기 노트 이력 더 보기'}).click();await expect(f.detail).toHaveCount(0);await page.unroute(historyPattern);
  await f.history.getByRole('button',{name:'폐기 노트 이력 새로고침'}).click();await expect(f.detail).toBeVisible();await page.route(historyPattern,r=>r.fulfill({status:503,body:'{}'}));await f.history.getByRole('button',{name:'이전 폐기 노트 이력 더 보기'}).click();await expect(f.history.getByRole('status')).toContainText('이력을 불러오지 못했습니다');await expect(f.history.locator('.retired-note-revisions button')).toHaveCount(0);await page.unroute(historyPattern);
  await page.route(listPattern,r=>r.fulfill({status:503,body:'{}'}));await f.archive.getByRole('button',{name:'폐기한 노트 더 보기'}).click();await expect(f.archive.getByRole('status')).toContainText('폐기한 노트를 불러오지 못했습니다');await expect(f.history).toHaveCount(0);await expect(f.archive.locator('.retired-note-results button')).toHaveCount(0);await page.unroute(listPattern);await f.archive.getByRole('button',{name:'폐기한 노트 새로고침'}).click();await expect(f.archive.locator('.retired-note-results button')).toHaveCount(20);
 }finally{await revoke(page)}
});

test('late archive responses cannot restore closed or switched history; revocation clears both lanes',async({page})=>{
 try{
  const f=await fixture(page);await f.open();await f.select();
  async function hold(pattern:RegExp){let release!:()=>void,seen!:()=>void,finished!:()=>void;const gate=new Promise<void>(r=>release=r),ready=new Promise<void>(r=>seen=r),done=new Promise<void>(r=>finished=r);await page.route(pattern,async route=>{const response=await route.fetch();seen();await gate;try{await route.fulfill({response})}catch{}finally{finished()}});return {release,ready,done}}
  const historyPattern=new RegExp(f.path+'/'+f.ids[0]+'/revisions\\?.*$'),listPattern=new RegExp(f.path+'\\?state=RETIRED.*$');
  const h=await hold(historyPattern);await f.history.getByRole('button',{name:'폐기 노트 이력 새로고침'}).click();await h.ready;await f.select(1);await page.unroute(historyPattern);h.release();await h.done;await expect(f.detail.locator('pre')).toHaveText('# 2026-10-20');await expect(f.history).not.toContainText(f.ids[0]);
  const l=await hold(listPattern);await f.archive.getByRole('button',{name:'폐기한 노트 새로고침'}).click();await l.ready;await f.archive.getByRole('button',{name:'폐기한 노트 닫기'}).click();await page.unroute(listPattern);await page.route(listPattern,r=>r.fulfill({status:503,body:'{}'}));await f.archive.getByRole('button',{name:'폐기한 노트 열기'}).click();await expect(f.archive.getByRole('status')).toContainText('폐기한 노트를 불러오지 못했습니다');l.release();await l.done;await expect(f.archive.locator('.retired-note-results button')).toHaveCount(0);await page.unroute(listPattern);
  await f.archive.getByRole('button',{name:'폐기한 노트 새로고침'}).click();await expect(f.archive.locator('.retired-note-results button')).toHaveCount(2);await f.select();await revoke(page);await f.history.getByRole('button',{name:'폐기 노트 이력 새로고침'}).click();await expect(page.getByRole('button',{name:'내 개인 공간 시작'})).toBeVisible();await expect(f.archive).toHaveCount(0);await expect(page.locator('pre')).toHaveCount(0);expect(await page.evaluate(()=>Object.keys(localStorage).length+Object.keys(sessionStorage).length)).toBe(0);
  // Empty archive and separate list authorization, using a new real guest principal.
  await start(page);const archive=page.getByRole('region',{name:'폐기한 노트 보관함'});await archive.getByRole('button',{name:'폐기한 노트 열기'}).click();await expect(archive).toContainText('폐기한 노트가 없습니다.');await revoke(page);await archive.getByRole('button',{name:'폐기한 노트 새로고침'}).click();await expect(page.getByRole('button',{name:'내 개인 공간 시작'})).toBeVisible();await expect(archive).toHaveCount(0);
 }finally{await revoke(page)}
});
