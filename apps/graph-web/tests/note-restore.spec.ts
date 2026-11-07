import {test,expect,Page,Locator} from '@playwright/test';
import {createHash} from 'node:crypto';

async function call(page:Page,path:string,method='GET',body?:unknown,etag?:string){
 return page.evaluate(async({path,method,body,etag})=>{const r=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:{...(body?{'Content-Type':'application/json'}:{}),...(etag?{'If-Match':etag}:{})},body:body?JSON.stringify(body):undefined});return {status:r.status,body:await r.json().catch(()=>null),etag:r.headers.get('ETag')}},{path,method,body,etag});
}
async function start(page:Page){await page.goto('/graph');await page.getByRole('button',{name:'내 개인 공간 시작'}).click();await expect(page.getByRole('button',{name:'+ 새 노트'})).toBeVisible({timeout:90000});return (await call(page,'/api/spaces/personal')).body.spaceId as string}
async function create(page:Page,space:string,title='Restore original',markdown='Original body'){
 const r=await call(page,`/api/spaces/${space}/notes`,'POST',{title,markdown});expect(r.status).toBe(201);return {path:`/api/spaces/${space}/notes/${r.body.noteId}`,note:r.body};
}
async function change(page:Page,path:string,title:string,markdown:string){const r=await call(page,path);const saved=await call(page,path,'PUT',{title,markdown},r.etag!);expect(saved.status).toBe(200);return saved}
async function open(page:Page,title:string){const library=page.getByRole('region',{name:'전체 노트 목록'});if(await library.getByRole('button',{name:'노트 목록 열기',exact:true}).count())await library.getByRole('button',{name:'노트 목록 열기',exact:true}).click();else await library.getByRole('button',{name:'노트 목록 새로고침',exact:true}).click();await library.getByRole('button',{name:title,exact:true}).click();const editor=page.getByRole('region',{name:'노트 본문과 수정 이력'});await expect(editor.getByRole('button',{name:'수정 이력',exact:true})).toBeEnabled();return editor}
async function select(editor:Locator,version:number,title:string){await editor.getByRole('button',{name:'수정 이력',exact:true}).click();await expect(editor.locator('.revision-list button').first()).toBeVisible();while(!await editor.getByRole('button',{name:`v${version} · ${title}`,exact:true}).count()){const count=await editor.locator('.revision-list button').count();await editor.getByRole('button',{name:'이전 수정 더 보기',exact:true}).click();await expect.poll(()=>editor.locator('.revision-list button').count()).toBeGreaterThan(count)}await editor.getByRole('button',{name:`v${version} · ${title}`,exact:true}).click()}
async function prepare(editor:Locator){await editor.getByRole('button',{name:'이 내용으로 복원 준비',exact:true}).click();await expect(editor.getByRole('region',{name:'이전 노트 내용 복원 확인'})).toBeVisible()}
async function revoke(page:Page){await call(page,'/api/identity/guest-session/revoke','POST',{})}

test('restore exact paged Note text as a new version; preserve independent data and recompute links',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));const space=await start(page),base=`/api/spaces/${space}`;
 try{
  const oldTitle='🧠'.repeat(150),oldBody='  # 기록\n\n[[Restore target]]\n<script>window.restoreXss=true</script>\n![x](https://restore.invalid/image)\n'+('장문 🧠 '.repeat(900))+'\n  ';
  const {path,note}=await create(page,space,oldTitle,oldBody),target=await create(page,space,'Restore target');
  for(let i=2;i<=7;i++)await change(page,path,'Restore current','Current '+i);
  const metadata=await call(page,path+'/metadata');expect((await call(page,path+'/metadata','PUT',{tags:['current tag'],properties:{}},metadata.etag!)).status).toBe(200);
  const concept=await call(page,base+'/concepts','POST',{title:'Current linked concept',description:''});expect(concept.status).toBe(201);
  const concepts=await call(page,path+'/concepts');expect((await call(page,path+'/concepts','PUT',{conceptIds:[concept.body.conceptId]},concepts.etag!)).status).toBe(200);
  const content='retained attachment',media=await call(page,base+'/media','POST',{filename:'restore.bin',mimeType:'application/octet-stream',byteSize:Buffer.byteLength(content),sha256:createHash('sha256').update(content).digest('hex')});expect(media.status).toBe(201);
  const uploaded=await page.evaluate(async({upload,content})=>{const f=new FormData();for(const [k,v] of Object.entries(upload.fields))f.append(k,v as string);f.append('file',new Blob([content],{type:'application/octet-stream'}),'upload.bin');return (await fetch(upload.url,{method:'POST',body:f,credentials:'omit',redirect:'error',referrerPolicy:'no-referrer'})).status},{upload:media.body.upload,content});expect([200,201,204]).toContain(uploaded);expect((await call(page,base+'/media/'+media.body.media.mediaId+'/complete','POST',{})).status).toBe(200);
  const attachments=await call(page,path+'/attachments');expect((await call(page,path+'/attachments','PUT',{mediaIds:[media.body.media.mediaId]},attachments.etag!)).status).toBe(200);
  const independent=new Map<string,unknown>();for(const child of ['metadata','metadata/revisions','attachments','attachments/revisions','concepts','concepts/revisions'])independent.set(child,(await call(page,path+'/'+child)).body);
  const editor=await open(page,'Restore current');await select(editor,1,oldTitle);await expect(editor.locator('.revision-list button')).toHaveCount(7);
  // A writer changes the Note after history was read: preparation must fetch v8, not reuse the inspector's v7.
  await change(page,path,'Restore current','Newest before preparation');const before=(await call(page,path+'/revisions?limit=10')).body;
  let writes=0,match='';page.on('request',r=>{if(r.url().endsWith(path)&&r.method()==='PUT'){writes++;match=r.headers()['if-match']}});
  await prepare(editor);await expect(editor.getByLabel('편집할 제목')).toHaveValue(oldTitle);await expect(editor.getByLabel('편집할 Markdown 본문')).toHaveValue(oldBody);await expect(editor.locator('.note-restore')).toContainText('현재 저장된 내용 · v8');expect(writes).toBe(0);
  await editor.getByRole('button',{name:'편집 취소'}).click();expect(writes).toBe(0);expect((await call(page,path)).body.version).toBe(8);
  await select(editor,1,oldTitle);await prepare(editor);await page.setViewportSize({width:390,height:844});await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await editor.getByRole('button',{name:'이전 내용으로 새 버전 저장',exact:true}).click();await expect(editor.getByRole('status')).toContainText('새 버전 v9');expect(writes).toBe(1);expect(match).toBe('"8"');
  const restored=await call(page,path);expect(restored.body).toMatchObject({noteId:note.noteId,title:oldTitle,markdown:oldBody,version:9,state:'ACTIVE'});
  const after=(await call(page,path+'/revisions?limit=10')).body;expect(after.slice(1)).toEqual(before);expect(after[0].revisionId).not.toBe(before[0].revisionId);
  for(const [child,snapshot] of independent)expect((await call(page,path+'/'+child)).body).toEqual(snapshot);
  expect((await call(page,path+'/links')).body.items[0]).toMatchObject({targetNoteId:target.note.noteId,status:'RESOLVED'});
  expect((await call(page,target.path+'/backlinks')).body.items.some((r:{sourceNoteId:string})=>r.sourceNoteId===note.noteId)).toBe(true);
  await expect.poll(async()=>{const r=await call(page,base+'/graph/notes?limit=200');return r.body?.nodes?.find((n:{noteId:string})=>n.noteId===note.noteId)?.title},{timeout:90000}).toBe(oldTitle);
  expect(await page.evaluate(()=>Boolean((window as unknown as {restoreXss?:boolean}).restoreXss))).toBe(false);expect(errors).toEqual([]);
 }finally{await revoke(page)}
});

test('restore conflict retains edits; explicit rebase and uncertain save do not overwrite or duplicate silently',async({page})=>{
 const space=await start(page);try{
  const {path}=await create(page,space);await change(page,path,'Restore current','Current v2');const editor=await open(page,'Restore current');await select(editor,1,'Restore original');await prepare(editor);
  await editor.getByLabel('편집할 Markdown 본문').fill('Edited restoration draft');await change(page,path,'Restore current','Independent v3');
  const save=editor.getByRole('button',{name:'이전 내용으로 새 버전 저장',exact:true});await save.click();await expect(editor.getByRole('status')).toContainText('다른 수정이 있습니다');await expect(save).toBeDisabled();await expect(editor.getByLabel('편집할 Markdown 본문')).toHaveValue('Edited restoration draft');expect((await call(page,path)).body.markdown).toBe('Independent v3');
  await editor.getByRole('button',{name:'최신 내용 확인',exact:true}).click();await expect(editor.locator('.conflict pre')).toHaveText('Independent v3');await editor.getByRole('button',{name:'최신 버전 기준으로 복원 다시 확인'}).click();await expect(save).toBeEnabled();await expect(editor.getByLabel('편집할 Markdown 본문')).toHaveValue('Edited restoration draft');expect((await call(page,path)).body.version).toBe(3);
  const pattern='**'+path;await page.route(pattern,r=>r.request().method()==='PUT'?r.fulfill({status:503,body:'{}'}):r.continue());await save.click();await expect(editor.getByRole('status')).toContainText('입력은 유지했습니다');await expect(editor.getByLabel('편집할 Markdown 본문')).toHaveValue('Edited restoration draft');await page.unroute(pattern);expect((await call(page,path)).body.version).toBe(3);
  // Commit really succeeds, but the browser receives a failure: retry must use the old version.
  await page.route(pattern,async route=>{if(route.request().method()!=='PUT')return route.continue();const r=await route.fetch();expect(r.status()).toBe(200);await route.fulfill({status:503,body:'{}'})});await save.click();await expect(editor.getByRole('status')).toContainText('입력은 유지했습니다');await page.unroute(pattern);expect((await call(page,path)).body.version).toBe(4);
  await save.click();await expect(editor.getByRole('status')).toContainText('다른 수정이 있습니다');expect((await call(page,path)).body.version).toBe(4);await editor.getByRole('button',{name:'최신 내용 확인',exact:true}).click();await expect(editor.locator('.conflict pre')).toHaveText('Edited restoration draft');await editor.getByRole('button',{name:'최신 버전으로 다시 시작'}).click();await expect(editor.getByRole('region',{name:'이전 노트 내용 복원 확인'})).toHaveCount(0);await editor.getByRole('button',{name:'편집 취소'}).click();expect((await call(page,path+'/revisions?limit=10')).body).toHaveLength(4);
 }finally{await revoke(page)}
});

test('restore rejects malformed or repeated history pages and recovers explicitly',async({page})=>{
 const space=await start(page);try{
  const {path}=await create(page,space);for(let i=2;i<=7;i++)await change(page,path,'Restore current','v'+i);const editor=await open(page,'Restore current'),pattern='**'+path+'/revisions?*';const rows=(await call(page,path+'/revisions?limit=5')).body;
  await editor.getByRole('button',{name:'수정 이력',exact:true}).click();
  const badRows=[{},[...rows,rows[0]],[rows[0],rows[0]],[{...rows[0],noteId:'00000000-0000-7000-8000-000000000001'}],[{...rows[0],actorId:'bad'}],[{...rows[0],occurredAt:'yesterday'}],[{...rows[0],state:'PUBLIC'}],[{...rows[0],title:' '}],[{...rows[0],markdown:'한'.repeat(11000)}],[{...rows[0],revisionId:'bad'}],[{...rows[0],title:'\0'}]];
  for(const body of badRows){await page.route(pattern,r=>r.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)}));await editor.getByRole('button',{name:'이력 새로고침',exact:true}).click();await expect(editor.getByRole('status')).toContainText('수정 이력을 불러오지 못했습니다');await expect(editor.getByRole('button',{name:'이 내용으로 복원 준비'})).toHaveCount(0);await page.unroute(pattern)}
  await editor.getByRole('button',{name:'이력 새로고침',exact:true}).click();await expect(editor.locator('.revision-list button')).toHaveCount(5);
  await page.route(pattern,r=>r.fulfill({status:200,body:JSON.stringify([{...rows[0],version:2}])}));await editor.getByRole('button',{name:'이전 수정 더 보기'}).click();await expect(editor.locator('.revision-list button')).toHaveCount(0);await page.unroute(pattern);
  await editor.getByRole('button',{name:'이력 새로고침',exact:true}).click();await expect(editor.locator('.revision-list button')).toHaveCount(5);await editor.getByRole('button',{name:'이전 수정 더 보기'}).click();await expect(editor.locator('.revision-list button')).toHaveCount(7);
  await page.route(pattern,r=>r.fulfill({status:503,body:'{}'}));await editor.getByRole('button',{name:'이력 새로고침',exact:true}).click();await expect(editor.locator('.revision-list button')).toHaveCount(0);await page.unroute(pattern);expect((await call(page,path)).body.version).toBe(7);
 }finally{await revoke(page)}
});

test('restore preparation requires valid fresh active Note and matching strong ETag',async({page})=>{
 const space=await start(page);try{
  const {path}=await create(page,space);await change(page,path,'Restore current','v2');const editor=await open(page,'Restore current');await select(editor,1,'Restore original');const current=await call(page,path),pattern='**'+path;
  for(const [body,etag] of [[current.body,'W/"2"'],[current.body,'"1"'],[{...current.body,spaceId:'wrong'},'"2"'],[{...current.body,state:'RETIRED'},'"2"'],[{...current.body,visibility:'PUBLIC'},'"2"'],[{...current.body,version:9007199254740992},'"9007199254740992"']] as const){
   await page.route(pattern,r=>r.fulfill({status:200,contentType:'application/json',headers:{ETag:etag},body:JSON.stringify(body)}));await editor.getByRole('button',{name:'이 내용으로 복원 준비'}).click();await expect(editor.getByRole('status')).toContainText('복원 전 현재 노트를 확인하지 못했습니다');await expect(editor.getByLabel('편집할 Markdown 본문')).toHaveCount(0);await page.unroute(pattern);
  }
  await page.route(pattern,r=>r.fulfill({status:503,body:'{}'}));await editor.getByRole('button',{name:'이 내용으로 복원 준비'}).click();await expect(editor.getByRole('status')).toContainText('복원 전 현재 노트를 확인하지 못했습니다');await page.unroute(pattern);await prepare(editor);await editor.getByRole('button',{name:'편집 취소'}).click();
  await select(editor,2,'Restore current');await editor.getByRole('button',{name:'이 내용으로 복원 준비'}).click();await expect(editor.getByRole('status')).toContainText('이전의 수정 이력을 선택');expect((await call(page,path)).body.version).toBe(2);
 }finally{await revoke(page)}
});

test('late restore preparation and unsaved restoration clear on Note switch; real revocation clears private data',async({page})=>{
 const space=await start(page);const {path}=await create(page,space);await create(page,space,'Restore other','Other private body');await change(page,path,'Restore current','v2');const editor=await open(page,'Restore current');await select(editor,1,'Restore original');
 let release!:()=>void,seen!:()=>void,finished!:()=>void;const held=new Promise<void>(r=>release=r),ready=new Promise<void>(r=>seen=r),done=new Promise<void>(r=>finished=r);const pattern='**'+path;
 await page.route(pattern,async route=>{const response=await route.fetch();seen();await held;try{await route.fulfill({response})}catch{}finally{finished()}});
 await editor.getByRole('button',{name:'이 내용으로 복원 준비'}).click();await ready;await open(page,'Restore other');await page.unroute(pattern);release();await done;await expect(editor.locator('pre')).toHaveText('Other private body');await expect(editor.getByLabel('편집할 Markdown 본문')).toHaveCount(0);
 await open(page,'Restore current');await select(editor,1,'Restore original');await prepare(editor);await editor.getByLabel('편집할 Markdown 본문').fill('Discarded restore draft');await open(page,'Restore other');await expect(editor.getByLabel('편집할 Markdown 본문')).toHaveCount(0);expect((await call(page,path)).body.version).toBe(2);
 await open(page,'Restore current');await select(editor,1,'Restore original');await prepare(editor);await revoke(page);await editor.getByRole('button',{name:'이전 내용으로 새 버전 저장'}).click();await expect(page.getByRole('button',{name:'내 개인 공간 시작'})).toBeVisible();await expect(editor).toHaveCount(0);expect(await page.evaluate(()=>Object.keys(localStorage).length+Object.keys(sessionStorage).length)).toBe(0);
});

test('Daily Note restoration preserves date binding; foreign access and retired target remain denied',async({page,browser})=>{
 const space=await start(page),other=await browser.newContext(),foreign=await other.newPage();try{
  await start(foreign);const base=`/api/spaces/${space}`,daily=await call(page,base+'/daily-notes','POST',{date:'2026-10-01',timezone:'Asia/Seoul'});expect(daily.status).toBe(201);const note=daily.body.note,path=base+'/notes/'+note.noteId;
  await change(page,path,'Restore journal','Changed daily');const editor=await open(page,'Restore journal');await select(editor,1,note.title);await prepare(editor);await editor.getByRole('button',{name:'이전 내용으로 새 버전 저장'}).click();await expect(editor.getByRole('status')).toContainText('새 버전 v3');const binding=await call(page,base+'/daily-notes/2026-10-01');expect(binding.body).toMatchObject({date:'2026-10-01',timezone:'Asia/Seoul',note:{noteId:note.noteId,title:note.title,markdown:note.markdown,version:3}});
  expect((await call(foreign,path+'/revisions')).status).toBe(404);expect((await call(foreign,path,'PUT',{title:note.title,markdown:note.markdown},'"3"')).status).toBe(404);
  await select(editor,1,note.title);await prepare(editor);expect((await call(page,path,'DELETE',undefined,'"3"')).status).toBe(204);await editor.getByRole('button',{name:'이전 내용으로 새 버전 저장'}).click();await expect(editor).toHaveCount(0);expect((await call(page,path)).status).toBe(404);const revisions=(await call(page,path+'/revisions?limit=10')).body;expect(revisions).toHaveLength(4);expect(revisions[0].state).toBe('RETIRED');
 }finally{await revoke(page);await revoke(foreign);await other.close()}
});
