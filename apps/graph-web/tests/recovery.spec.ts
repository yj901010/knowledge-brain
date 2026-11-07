import {test,expect,Page,request} from '@playwright/test';
import {randomBytes,createHash} from 'node:crypto';
import {readFileSync,writeFileSync} from 'node:fs';

async function call(page:Page,path:string,method='GET',body?:unknown,etag?:string){
 return page.evaluate(async({path,method,body,etag})=>{
  const r=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:{...(body?{'Content-Type':'application/json'}:{}),...(etag?{'If-Match':etag}:{})},body:body?JSON.stringify(body):undefined});
  return {status:r.status,body:await r.json().catch(()=>null),etag:r.headers.get('ETag')};
 },{path,method,body,etag});
}
async function start(page:Page){
 await page.goto('/graph');await page.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();
 await expect(page.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible({timeout:90000});
 return (await call(page,'/api/spaces/personal')).body.spaceId as string;
}

test('persistent recovery fixture: login, exact revisions, sealed object, isolation and resumed events',async({page,browser})=>{
 test.skip(!['prepare','inspect','verify'].includes(process.env.BRAIN_RECOVERY_MODE??''),'Run with the explicit recovery fixture operator');
 test.setTimeout(240000);
 if(process.env.BRAIN_RECOVERY_MODE==='prepare'){
  const space=await start(page),username='recovery-'+Date.now(),password=randomBytes(24).toString('base64url')+'aA1!';
  await page.getByRole('button',{name:'로그인 · 이 공간 연결',exact:true}).click();
  await page.getByRole('link',{name:'Register',exact:true}).click();
  await page.locator('#username').fill(username);
  for(const [id,value] of [['email',username+'@example.invalid'],['firstName','Recovery'],['lastName','Drill']]){const field=page.locator('#'+id);if(await field.count())await field.fill(value);}
  await page.locator('#password').fill(password);await page.locator('#password-confirm').fill(password);
  await page.getByRole('button',{name:'Register',exact:true}).click();
  await expect(page.getByRole('button',{name:'로그아웃',exact:true})).toBeVisible({timeout:90000});
  await expect(page.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible({timeout:90000});
  expect((await call(page,'/api/spaces/personal')).body.spaceId).toBe(space);
  const account=(await call(page,'/api/identity/account-session')).body;
  const collection=`/api/spaces/${space}/notes`,note=(await call(page,collection,'POST',{title:'Recovery durable note',markdown:'original before backup'})).body,path=collection+'/'+note.noteId;
  expect((await call(page,path,'PUT',{title:'Recovery durable note',markdown:'version two survives restore'},'"1"')).status).toBe(200);
  const concept=(await call(page,`/api/spaces/${space}/concepts`,'POST',{title:'Recovery durable concept',description:'Private restore fixture'})).body;
  const links=await call(page,path+'/concepts');expect((await call(page,path+'/concepts','PUT',{conceptIds:[concept.conceptId]},links.etag!)).status).toBe(200);
  await expect.poll(async()=>{const r=await call(page,`/api/spaces/${space}/graph/notes?limit=200`);return r.status===200&&r.body.nodes.some((n:any)=>n.noteId===note.noteId);},{timeout:90000}).toBe(true);
  await page.reload();await page.getByRole('button',{name:'Recovery durable note',exact:true}).click();
  const panel=page.getByRole('region',{name:'노트 첨부파일'}),payload=Buffer.from('Knowledge Brain recovery bytes\u0000\u00ff preserved versioned object'),filename='recovery-proof.bin';
  await panel.getByLabel('업로드할 파일',{exact:true}).setInputFiles({name:filename,mimeType:'application/octet-stream',buffer:payload});
  await panel.getByRole('button',{name:'파일 업로드',exact:true}).click();await expect(panel.getByRole('status')).toContainText('파일 업로드를 완료했습니다');
  await panel.getByRole('button',{name:'노트에 연결 · '+filename,exact:true}).click();await expect(panel.locator('.attachment-list li')).toHaveCount(1);
  const media=(await call(page,`/api/spaces/${space}/media`)).body.items.find((v:any)=>v.filename===filename);
  const foreign=await browser.newContext(),guest=await foreign.newPage(),guestSpace=await start(guest);
  const guestNote=(await call(guest,`/api/spaces/${guestSpace}/notes`,'POST',{title:'Recovery guest note',markdown:'guest identity retained'})).body;
  const fixture={space,username,password,principalId:account.principalId,noteId:note.noteId,conceptId:concept.conceptId,mediaId:media.mediaId,filename,sha256:createHash('sha256').update(payload).digest('hex'),guestSpace,guestNoteId:guestNote.noteId,guestStorage:await foreign.storageState()};
  writeFileSync('/tmp/recovery-fixture.json',JSON.stringify(fixture),{mode:0o600});await foreign.close();
  console.log('RECOVERY_PREPARED: registered account, claimed space, two note versions, Concept link, sealed binary and independent guest');return;
 }
 const fixture=JSON.parse(readFileSync('/fixture/fixture.json','utf8'));
 await start(page);await page.getByRole('button',{name:'로그인 · 이 공간 연결',exact:true}).click();
 await page.locator('#username').fill(fixture.username);await page.locator('#password').fill(fixture.password);await page.locator('#kc-login').click();
 await expect(page.getByRole('button',{name:'로그아웃',exact:true})).toBeVisible({timeout:90000});
 await expect(page.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible({timeout:90000});
 // Login claims the newly created guest Space; switch to the original restored Space explicitly.
 const path=`/api/spaces/${fixture.space}/notes/${fixture.noteId}`;
 const account=(await call(page,'/api/identity/account-session')).body;
 expect(account.kind).toBe('REGISTERED');expect(account.principalId).toBe(fixture.principalId);
 const note=await call(page,path);expect(note.status).toBe(200);expect(note.body).toMatchObject({noteId:fixture.noteId,spaceId:fixture.space,version:2,markdown:'version two survives restore',visibility:'PRIVATE'});
 const revisions=await call(page,path+'/revisions');expect(revisions.status).toBe(200);expect(revisions.body.map((v:any)=>v.version)).toEqual([2,1]);expect(revisions.body[1].markdown).toBe('original before backup');
 expect((await call(page,path+'/concepts')).body.items.some((v:any)=>v.conceptId===fixture.conceptId)).toBe(true);
 const media=(await call(page,`/api/spaces/${fixture.space}/media/${fixture.mediaId}`)).body;expect(media.state).toBe('AVAILABLE');expect(media.sha256).toBe(fixture.sha256);
 const download=await call(page,`/api/spaces/${fixture.space}/media/${fixture.mediaId}/download`,'POST',{});expect(download.status).toBe(200);
 const signed=download.body.url;expect(signed).toMatch(/^https:\/\/localhost:9443\//);
 const objectClient=await request.newContext();try{const raw=await objectClient.get(signed);expect(raw.status()).toBe(200);expect(createHash('sha256').update(await raw.body()).digest('hex')).toBe(fixture.sha256);}finally{await objectClient.dispose();}
 const foreign=await browser.newContext({storageState:fixture.guestStorage}),guest=await foreign.newPage();await guest.goto('/graph');
 expect((await call(guest,`/api/spaces/${fixture.guestSpace}/notes/${fixture.guestNoteId}`)).body.markdown).toBe('guest identity retained');
 expect((await call(guest,path)).status).toBe(404);expect((await call(guest,`/api/spaces/${fixture.space}/media/${fixture.mediaId}`)).status).toBe(404);await foreign.close();
 if(process.env.BRAIN_RECOVERY_MODE==='inspect'){
  console.log('RECOVERY_INSPECTED: existing registered/guest identity, original v2/v1, private link, sealed binary and foreign denial; original Note unchanged');return;
 }
 const publicSpaces=await call(page,'/api/spaces/public');expect(publicSpaces.status).toBe(200);
 const development=publicSpaces.body.items.find((s:any)=>s.domain==='DEVELOPMENT');expect(development).toBeTruthy();
 for(let i=0;i<3;i++){
  const search=await call(page,`/api/public/spaces/${development.spaceId}/search?q=Discovery&kind=CONCEPT&limit=5`);
  expect(search.status).toBe(200);expect(search.body.items.length).toBeGreaterThan(0);
 }
 // A new write after restore must traverse restored Outbox/Connect/Kafka consumers.
 expect((await call(page,path,'PUT',{title:'Recovery durable note',markdown:'post-restore event verified'},'"2"')).status).toBe(200);
 await expect.poll(async()=>{const r=await call(page,`/api/spaces/${fixture.space}/activity/timeline?kind=NOTE&timezone=UTC&limit=100`);return r.status===200&&r.body.items.some((v:any)=>v.targetPath===path&&v.sourceVersion===3);},{timeout:90000}).toBe(true);
 expect((await call(page,path,'PUT',{title:'stale','markdown':'must not overwrite'},'"2"')).status).toBe(412);
 console.log('RECOVERY_RESTORED_VERIFIED: existing Keycloak password login, stable IDs, v2/v1 history, private Concept link, binary SHA256, guest credential, foreign denial, post-restore v3 event and CAS');
});
