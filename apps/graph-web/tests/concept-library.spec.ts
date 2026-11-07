import {test,expect,Page} from '@playwright/test';

test('standalone personal Concept library, canonical pages and independent Note editing',async({page,browser})=>{
 const other=await browser.newContext(),foreign=await other.newPage(),errors:string[]=[];
 page.on('pageerror',error=>errors.push(error.message));
 page.on('console',message=>{if(message.type()==='error'&&/Content Security Policy|Refused to/.test(message.text()))errors.push(message.text())});
 async function call(p:Page,path:string,method='GET',body?:unknown,etag?:string){return p.evaluate(async({path,method,body,etag})=>{
  const response=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:{...(body?{'Content-Type':'application/json'}:{}),...(etag?{'If-Match':etag}:{})},body:body?JSON.stringify(body):undefined});
  return {status:response.status,body:await response.json().catch(()=>null),etag:response.headers.get('ETag')};
 },{path,method,body,etag})}
 async function start(p:Page){await p.goto('/graph');await p.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();await expect(p.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible({timeout:90000});return (await call(p,'/api/spaces/personal')).body.spaceId as string}
 try{
  const space=await start(page);await start(foreign);const base=`/api/spaces/${space}`,collection=base+'/concepts';
  const library=page.getByRole('region',{name:'개인 개념 보관함',exact:true}),rows=library.locator('.concept-library-results button');
  const form=library.getByRole('region',{name:'새 개인 개념',exact:true}),detail=library.getByRole('region',{name:'개인 개념 상세와 수정 이력',exact:true});
  const openConcept=(title:string)=>library.getByRole('button',{name:'보관함 개념 열기: '+title,exact:true});
  let noteWrites=0,conceptPosts=0;
  page.on('request',request=>{const url=new URL(request.url());if(url.pathname.startsWith(base+'/notes')&&!['GET','HEAD'].includes(request.method()))noteWrites++;if(url.pathname===collection&&request.method()==='POST')conceptPosts++});
  expect((await call(page,base+'/notes?limit=20')).body.items).toEqual([]);
  await expect(library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true})).toHaveAttribute('aria-expanded','false');
  await library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true}).click();await expect(rows).toHaveCount(0);
  await expect(library.getByRole('button',{name:'개인 개념 작성',exact:true})).toBeVisible();
  expect((await call(foreign,collection+'?limit=20')).status).toBe(404);
  expect((await call(foreign,collection,'POST',{title:'Forbidden personal Concept',description:'Forbidden'})).status).toBe(404);
  // No Note is needed to create a private Concept. Cancel has no write side effect.
  await library.getByRole('button',{name:'개인 개념 작성',exact:true}).click();
  await form.getByLabel('새 개인 개념 제목').fill('Cancelled standalone Concept');await form.getByLabel('새 개인 개념 설명').fill('Cancelled standalone description');
  await form.getByRole('button',{name:'개념 작성 취소',exact:true}).click();await expect(form).toHaveCount(0);expect(conceptPosts).toBe(0);expect(noteWrites).toBe(0);
  const originalTitle='Concept library original',originalDescription='<script>window.conceptLibraryXss=true</script>\n<img src="https://example.invalid/private-library.png" onerror="window.conceptLibraryXss=true">\nStandalone private Concept definition';
  await library.getByRole('button',{name:'개인 개념 작성',exact:true}).click();await expect(form.getByLabel('새 개인 개념 제목')).toHaveValue('');
  await form.getByLabel('새 개인 개념 제목').fill(originalTitle);await form.getByLabel('새 개인 개념 설명').fill(originalDescription);
  await page.route('**'+collection,route=>route.request().method()==='POST'?route.fulfill({status:503,contentType:'application/json',body:'{}'}):route.continue());
  await form.getByRole('button',{name:'개인 개념 저장',exact:true}).click();
  await expect(form.getByLabel('새 개인 개념 제목')).toHaveValue(originalTitle);await expect(form.getByLabel('새 개인 개념 설명')).toHaveValue(originalDescription);
  await expect(library.getByRole('status')).toContainText(/확인하지 못|저장하지 못/);expect(conceptPosts).toBe(1);expect((await call(page,collection+'?limit=20')).body.items).toEqual([]);expect(noteWrites).toBe(0);
  await page.unroute('**'+collection);
  const [createdResponse]=await Promise.all([page.waitForResponse(response=>response.url().endsWith(collection)&&response.request().method()==='POST'),form.getByRole('button',{name:'개인 개념 저장',exact:true}).click()]);
  expect(createdResponse.status()).toBe(201);const created=await createdResponse.json(),path=collection+'/'+created.conceptId;
  expect(created.conceptId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
  expect(created).toMatchObject({spaceId:space,visibility:'PRIVATE',state:'ACTIVE',version:1,title:originalTitle,description:originalDescription});
  expect((await call(page,path)).etag).toBe('"1"');await expect(form).toHaveCount(0);await expect(openConcept(originalTitle)).toBeVisible();
  // Create may select its own detail; selecting the canonical row also works without a Note.
  await openConcept(originalTitle).click();await expect(detail.locator('pre')).toHaveText(originalDescription);await expect(detail.locator('script,img,iframe')).toHaveCount(0);
  expect(await page.evaluate(()=>Boolean((window as unknown as {conceptLibraryXss?:boolean}).conceptLibraryXss))).toBe(false);
  expect((await call(page,base+'/notes?limit=20')).body.items).toEqual([]);expect(noteWrites).toBe(0);expect(conceptPosts).toBe(2);
  for(const response of [await call(foreign,path),await call(foreign,path+'/revisions?limit=5')]){expect(response.status).toBe(404);expect(JSON.stringify(response.body)).not.toContain('Standalone private Concept definition')}
  await library.getByRole('button',{name:'보관함 개념 상세 닫기',exact:true}).click();
  const fixtures:{conceptId:string;title:string;description:string}[]=[];
  for(let i=1;i<=40;i++){const title=i<3?'Concept library duplicate':i===40?'Concept library late page':'Concept library '+String(i).padStart(3,'0'),description='Canonical library fixture '+i;const result=await call(page,collection,'POST',{title,description});expect(result.status).toBe(201);fixtures.push(result.body)}
  expect(fixtures[0].conceptId).not.toBe(fixtures[1].conceptId);
  await library.getByRole('button',{name:'보관함 개념 새로고침',exact:true}).click();await expect(rows).toHaveCount(20);
  await library.getByRole('button',{name:'보관함 개념 더 보기',exact:true}).click();await expect(rows).toHaveCount(40);
  await library.getByRole('button',{name:'보관함 개념 더 보기',exact:true}).click();await expect(rows).toHaveCount(41);await expect(library.getByRole('button',{name:'보관함 개념 더 보기',exact:true})).toHaveCount(0);
  await expect(openConcept('Concept library duplicate')).toHaveCount(2);expect(new Set(await rows.allTextContents()).size).toBe(40);
  const allIds:string[]=[];let after:string|null=null;
  do{const result=await call(page,collection+'?limit=20'+(after?'&after='+after:''));expect(result.status).toBe(200);allIds.push(...result.body.items.map((row:{conceptId:string})=>row.conceptId));after=result.body.nextAfter}while(after);
  expect(allIds).toHaveLength(41);expect(new Set(allIds).size).toBe(41);expect(allIds).toContain(created.conceptId);
  await library.getByLabel('불러온 보관함 개념 찾기').fill('duplicate');await expect(rows).toHaveCount(2);await library.getByLabel('불러온 보관함 개념 찾기').fill('late page');await expect(rows).toHaveCount(1);await library.getByLabel('불러온 보관함 개념 찾기').fill('');await expect(rows).toHaveCount(41);
  // A Concept from the third page remains open after a canonical save resets the catalog to page one.
  const late=fixtures[39],latePath=collection+'/'+late.conceptId;await openConcept(late.title).click();await expect(detail.locator('pre')).toHaveText(late.description);
  await detail.getByRole('button',{name:'개인 개념 편집',exact:true}).click();await detail.getByLabel('편집할 개념 제목').fill('Concept library late renamed');await detail.getByLabel('편집할 개념 설명').fill('Updated third-page Concept definition');
  const [lateSave]=await Promise.all([page.waitForRequest(request=>request.url().endsWith(latePath)&&request.method()==='PUT'),detail.getByRole('button',{name:'개념 수정 저장',exact:true}).click()]);expect(lateSave.headers()['if-match']).toBe('"1"');
  await expect(detail.locator('pre')).toHaveText('Updated third-page Concept definition');await expect(detail.getByRole('heading',{name:'Concept library late renamed',exact:true})).toBeVisible();await expect(rows).toHaveCount(20);expect((await call(page,latePath)).etag).toBe('"2"');
  await detail.getByRole('button',{name:'개인 개념 수정 이력',exact:true}).click();await expect(detail.locator('.personal-concept-revisions button')).toHaveCount(2);await detail.getByRole('button',{name:'v1 · '+late.title,exact:true}).click();await expect(detail.locator('pre')).toHaveText(late.description);
  await library.getByRole('button',{name:'보관함 개념 상세 닫기',exact:true}).click();await expect(detail).toHaveCount(0);
  // Reject an old canonical catalog response even when it finishes after a title save.
  await openConcept(originalTitle).click();await expect(detail.locator('pre')).toHaveText(originalDescription);await detail.getByRole('button',{name:'개인 개념 편집',exact:true}).click();await detail.getByLabel('편집할 개념 제목').fill('Concept library renamed');await detail.getByLabel('편집할 개념 설명').fill('Standalone Concept edited definition');
  const oldCatalog=(await call(page,collection+'?limit=20')).body,catalogPattern='**'+collection+'?*';let releaseOld!:()=>void,finishOld!:()=>void,oldStarted=false,oldCount=0;
  const oldHeld=new Promise<void>(resolve=>{releaseOld=resolve}),oldFinished=new Promise<void>(resolve=>{finishOld=resolve});
  await page.route(catalogPattern,async route=>{if(oldCount++===0){oldStarted=true;try{await oldHeld;await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(oldCatalog)}).catch(()=>{})}finally{finishOld()}}else await route.continue()});
  try{await library.getByRole('button',{name:'보관함 개념 새로고침',exact:true}).click();await expect.poll(()=>oldStarted).toBe(true);await expect(detail.getByLabel('편집할 개념 제목')).toHaveValue('Concept library renamed');await detail.getByRole('button',{name:'개념 수정 저장',exact:true}).click();await expect(detail.locator('pre')).toHaveText('Standalone Concept edited definition');await expect(openConcept('Concept library renamed')).toBeVisible()}finally{releaseOld();if(oldStarted)await oldFinished;await page.unroute(catalogPattern)}
  await expect(openConcept(originalTitle)).toHaveCount(0);expect((await call(page,path)).etag).toBe('"2"');expect((await call(page,base+'/notes?limit=20')).body.items).toEqual([]);expect(noteWrites).toBe(0);
  // Only now introduce a Note. Editing the library refreshes the existing Note picker without losing an unsaved Note draft or changing Content-owned references.
  const note=await call(page,base+'/notes','POST',{title:'Concept library linked Note',markdown:'Unchanged linked Note source'});expect(note.status).toBe(201);const notePath=base+'/notes/'+note.body.noteId,refs=notePath+'/concepts';
  expect((await call(page,refs,'PUT',{conceptIds:[created.conceptId]},'"c0"')).status).toBe(200);const initialNote=await call(page,notePath),initialRefs=await call(page,refs),initialNoteWrites=noteWrites;
  const noteLibrary=page.getByRole('region',{name:'전체 노트 목록',exact:true});await noteLibrary.getByRole('button',{name:'노트 목록 열기',exact:true}).click();await noteLibrary.getByRole('button',{name:'Concept library linked Note',exact:true}).click();
  const noteEditor=page.getByRole('region',{name:'노트 본문과 수정 이력',exact:true}),picker=page.getByRole('region',{name:'노트 개념 연결과 이력',exact:true});await expect(picker.getByRole('checkbox',{name:'Concept library renamed',exact:true})).toBeChecked();
  await noteEditor.getByRole('button',{name:'노트 편집',exact:true}).click();await noteEditor.getByLabel('편집할 Markdown 본문').fill('Unsaved Note draft preserved across Concept library changes');
  await detail.getByRole('button',{name:'개인 개념 편집',exact:true}).click();await detail.getByLabel('편집할 개념 제목').fill('Concept library shared renamed');await detail.getByLabel('편집할 개념 설명').fill('Shared canonical library definition');await detail.getByRole('button',{name:'개념 수정 저장',exact:true}).click();
  await expect(detail.locator('pre')).toHaveText('Shared canonical library definition');await expect(picker.getByRole('checkbox',{name:'Concept library shared renamed',exact:true})).toBeChecked();await expect(picker.getByRole('checkbox',{name:'Concept library renamed',exact:true})).toHaveCount(0);
  await expect(noteEditor.getByLabel('편집할 Markdown 본문')).toHaveValue('Unsaved Note draft preserved across Concept library changes');expect(await call(page,notePath)).toEqual(initialNote);expect(await call(page,refs)).toEqual(initialRefs);expect(noteWrites).toBe(initialNoteWrites);expect((await call(page,path)).etag).toBe('"3"');
  // A Note-side Concept mutation also refreshes the standalone library while its detail draft remains local.
  await library.getByRole('button',{name:'보관함 개념 더 보기',exact:true}).click();await expect(rows).toHaveCount(40);await library.getByRole('button',{name:'보관함 개념 더 보기',exact:true}).click();await expect(rows).toHaveCount(41);
  await detail.getByRole('button',{name:'개인 개념 편집',exact:true}).click();await detail.getByLabel('편집할 개념 설명').fill('Unsaved library detail draft preserved across Note picker create');
  await picker.getByLabel('새 개념 이름').fill('Concept library created from Note picker');await picker.getByRole('button',{name:'개념 만들기',exact:true}).click();await expect(rows).toHaveCount(20);await expect(picker.getByRole('status')).toContainText('개념을 만들었습니다');
  await expect(detail.getByLabel('편집할 개념 설명')).toHaveValue('Unsaved library detail draft preserved across Note picker create');await expect(noteEditor.getByLabel('편집할 Markdown 본문')).toHaveValue('Unsaved Note draft preserved across Concept library changes');expect(await call(page,notePath)).toEqual(initialNote);expect(await call(page,refs)).toEqual(initialRefs);expect(noteWrites).toBe(initialNoteWrites);
  await detail.getByRole('button',{name:'개념 편집 취소',exact:true}).click();
  // Actual retirement removes the active row from both catalogs, retains its read-only history and preserves the existing immutable Content reference.
  const beforeRetirement=await call(page,path+'/revisions?limit=5');expect(beforeRetirement.body.map((row:{version:number})=>row.version)).toEqual([3,2,1]);
  const preRetireCatalog=(await call(page,collection+'?limit=20')).body;let releaseRetire!:()=>void,finishRetire!:()=>void,retireStarted=false,retireCount=0;const retireHeld=new Promise<void>(resolve=>{releaseRetire=resolve}),retireFinished=new Promise<void>(resolve=>{finishRetire=resolve});
  await page.route(catalogPattern,async route=>{if(retireCount++===0){retireStarted=true;try{await retireHeld;await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(preRetireCatalog)}).catch(()=>{})}finally{finishRetire()}}else await route.continue()});
  try{
   await library.getByRole('button',{name:'보관함 개념 새로고침',exact:true}).click();await expect.poll(()=>retireStarted).toBe(true);await detail.getByRole('button',{name:'개인 개념 폐기',exact:true}).click();const confirm=detail.getByRole('region',{name:'개인 개념 폐기 확인',exact:true});await confirm.getByRole('checkbox',{name:'개념 폐기에 동의합니다',exact:true}).check();
   const [retired]=await Promise.all([page.waitForResponse(response=>response.url().endsWith(path)&&response.request().method()==='DELETE'),confirm.getByRole('button',{name:'개념 폐기 확정',exact:true}).click()]);expect(retired.status()).toBe(204);expect(retired.headers()['etag']).toBe('"4"');await expect(detail).toContainText('RETIRED');await expect(detail.getByRole('button',{name:'개인 개념 편집',exact:true})).toHaveCount(0);await expect(openConcept('Concept library shared renamed')).toHaveCount(0);await expect(picker.getByRole('checkbox',{name:'Concept library shared renamed',exact:true})).toHaveCount(0);
  }finally{releaseRetire();if(retireStarted)await retireFinished;await page.unroute(catalogPattern)}
  await expect(openConcept('Concept library shared renamed')).toHaveCount(0);expect((await call(page,path)).status).toBe(404);expect(await call(page,notePath)).toEqual(initialNote);expect(await call(page,refs)).toEqual(initialRefs);expect(noteWrites).toBe(initialNoteWrites);await expect(noteEditor.getByLabel('편집할 Markdown 본문')).toHaveValue('Unsaved Note draft preserved across Concept library changes');
  const retiredHistory=await call(page,path+'/revisions?limit=5');expect(retiredHistory.body.map((row:{version:number;state:string})=>[row.version,row.state])).toEqual([[4,'RETIRED'],[3,'ACTIVE'],[2,'ACTIVE'],[1,'ACTIVE']]);expect(retiredHistory.body.slice(1)).toEqual(beforeRetirement.body);
  await detail.getByRole('button',{name:'개인 개념 수정 이력',exact:true}).click();await expect(detail.locator('.personal-concept-revisions button')).toHaveCount(4);await detail.getByRole('button',{name:'v1 · '+originalTitle,exact:true}).click();await expect(detail.locator('pre')).toHaveText(originalDescription);await expect(detail.locator('script,img,iframe')).toHaveCount(0);expect((await call(foreign,path+'/revisions?limit=5')).status).toBe(404);
  await library.evaluate(element=>element.scrollIntoView({block:'start'}));await page.screenshot({path:'/tmp/graph-ui-results/concept-library.png',fullPage:true});await page.setViewportSize({width:390,height:844});await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await library.evaluate(element=>element.scrollIntoView({block:'start'}));await page.screenshot({path:'/tmp/graph-ui-results/concept-library-mobile.png',fullPage:true});
  // Explicit library close clears every local creation/edit/ack/history surface; reopening starts from a fresh first page.
  await library.getByRole('button',{name:'개인 개념 보관함 닫기',exact:true}).click();await expect(detail).toHaveCount(0);await expect(rows).toHaveCount(0);await library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true}).click();await expect(rows).toHaveCount(20);
  await openConcept('Concept library 003').click();await expect(detail.locator('pre')).toHaveText('Canonical library fixture 3');await expect(detail.locator('.personal-concept-revisions')).toHaveCount(0);await detail.getByRole('button',{name:'개인 개념 편집',exact:true}).click();await detail.getByLabel('편집할 개념 설명').fill('Discarded library draft');
  await library.getByRole('button',{name:'개인 개념 보관함 닫기',exact:true}).click();await library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true}).click();await expect(rows).toHaveCount(20);await openConcept('Concept library 003').click();await expect(detail.getByLabel('편집할 개념 설명')).toHaveCount(0);await expect(detail.locator('pre')).toHaveText('Canonical library fixture 3');
  await detail.getByRole('button',{name:'개인 개념 폐기',exact:true}).click();await detail.getByRole('checkbox',{name:'개념 폐기에 동의합니다',exact:true}).check();await library.getByRole('button',{name:'개인 개념 보관함 닫기',exact:true}).click();await library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true}).click();await expect(rows).toHaveCount(20);await openConcept('Concept library 003').click();await detail.getByRole('button',{name:'개인 개념 폐기',exact:true}).click();await expect(detail.getByRole('checkbox',{name:'개념 폐기에 동의합니다',exact:true})).not.toBeChecked();await detail.getByRole('button',{name:'개념 폐기 취소',exact:true}).click();await library.getByRole('button',{name:'보관함 개념 상세 닫기',exact:true}).click();
  await library.getByRole('button',{name:'개인 개념 작성',exact:true}).click();await form.getByLabel('새 개인 개념 제목').fill('Discarded new Concept');await form.getByLabel('새 개인 개념 설명').fill('Discarded new description');await library.getByRole('button',{name:'개인 개념 보관함 닫기',exact:true}).click();await library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true}).click();await expect(rows).toHaveCount(20);await library.getByRole('button',{name:'개인 개념 작성',exact:true}).click();await expect(form.getByLabel('새 개인 개념 제목')).toHaveValue('');await expect(form.getByLabel('새 개인 개념 설명')).toHaveValue('');await form.getByRole('button',{name:'개념 작성 취소',exact:true}).click();
  // A delayed pre-close read cannot repopulate a newly failed catalog; explicit retry recovers canonical rows.
  let releaseRead!:()=>void,finishRead!:()=>void,readStarted=false;const readHeld=new Promise<void>(resolve=>{releaseRead=resolve}),readFinished=new Promise<void>(resolve=>{finishRead=resolve});
  await page.route(catalogPattern,async route=>{const response=await route.fetch();readStarted=true;try{await readHeld;await route.fulfill({response}).catch(()=>{})}finally{finishRead()}});
  await library.getByRole('button',{name:'보관함 개념 새로고침',exact:true}).click();await expect.poll(()=>readStarted).toBe(true);await page.unroute(catalogPattern);await library.getByRole('button',{name:'개인 개념 보관함 닫기',exact:true}).click();await page.route(catalogPattern,route=>route.fulfill({status:503,contentType:'application/json',body:'{}'}));
  try{await library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true}).click();await expect(library.getByRole('status')).toContainText(/불러오지 못/);releaseRead();await readFinished;await expect(rows).toHaveCount(0)}finally{releaseRead();await page.unroute(catalogPattern)}
  await library.getByRole('button',{name:'보관함 개념 새로고침',exact:true}).click();await expect(rows).toHaveCount(20);await expect(openConcept('Concept library shared renamed')).toHaveCount(0);expect(await call(page,notePath)).toEqual(initialNote);expect(await call(page,refs)).toEqual(initialRefs);expect(noteWrites).toBe(initialNoteWrites);
  // Revocation during an outstanding create removes the library, its inputs and the unsaved Note surface.
  await library.getByRole('button',{name:'개인 개념 작성',exact:true}).click();await form.getByLabel('새 개인 개념 제목').fill('Revoked private Concept draft');await form.getByLabel('새 개인 개념 설명').fill('Revoked private description');
  let releasePost!:()=>void,finishPost!:()=>void,postStarted=false;const postHeld=new Promise<void>(resolve=>{releasePost=resolve}),postFinished=new Promise<void>(resolve=>{finishPost=resolve});
  await page.route('**'+collection,async route=>{if(route.request().method()!=='POST'){await route.continue();return}postStarted=true;try{await postHeld;await route.continue().catch(()=>{})}finally{finishPost()}});
  try{await form.getByRole('button',{name:'개인 개념 저장',exact:true}).click();await expect.poll(()=>postStarted).toBe(true);expect((await call(page,'/api/identity/guest-session/revoke','POST',{})).status).toBe(204)}finally{releasePost();if(postStarted)await postFinished;await page.unroute('**'+collection)}
  await expect(page.getByRole('button',{name:'내 개인 공간 시작',exact:true})).toBeVisible();await expect(library).toHaveCount(0);await expect(form).toHaveCount(0);await expect(detail).toHaveCount(0);await expect(noteEditor).toHaveCount(0);await expect(page.locator('pre,textarea')).toHaveCount(0);
  expect(await page.evaluate(()=>Object.keys(localStorage).length+Object.keys(sessionStorage).length)).toBe(0);expect(await page.evaluate(()=>Boolean((window as unknown as {conceptLibraryXss?:boolean}).conceptLibraryXss))).toBe(false);expect(errors).toEqual([]);
  console.log('PERSONAL_CONCEPT_LIBRARY_VERIFIED: standalone private creation before any Note exists, cancellation and 503 input retention with explicit retry only; exact UUIDv7 and strong ETag; real 41-Concept 20+20+1 canonical pages, distinct same-title IDs and loaded filter; third-page detail retained across catalog reset, read-only revisions and escaped descriptions; stale catalog rejection after save and retirement; bidirectional library/Note-picker refresh preserving both local detail draft and unsaved Note draft with exact unchanged Content Note/version/reference c-ETag/Concept IDs/link IDs; retained retired history; close/reopen clearing creation/edit/ack/history, stale delayed read rejection and explicit recovery; foreign list/create/detail/history denial, mobile fit and pending create revocation clearing without browser storage');
 }finally{await other.close()}
});
