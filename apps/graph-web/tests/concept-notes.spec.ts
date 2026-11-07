import {test,expect,Page} from '@playwright/test';

test('canonical reverse Concept references, independent Note navigation and private lifetime',async({page,browser})=>{
 const other=await browser.newContext(),foreign=await other.newPage(),errors:string[]=[];
 page.on('pageerror',error=>errors.push(error.message));
 page.on('console',message=>{if(message.type()==='error'&&/Content Security Policy|Refused to/.test(message.text()))errors.push(message.text())});
 async function call(p:Page,path:string,method='GET',body?:unknown,etag?:string){return p.evaluate(async({path,method,body,etag})=>{
  const response=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:{...(body?{'Content-Type':'application/json'}:{}),...(etag?{'If-Match':etag}:{})},body:body?JSON.stringify(body):undefined});
  return {status:response.status,body:await response.json().catch(()=>null),etag:response.headers.get('ETag')};
 },{path,method,body,etag})}
 async function start(p:Page){await p.goto('/graph');await p.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();await expect(p.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible({timeout:90000});return (await call(p,'/api/spaces/personal')).body.spaceId as string}
 let revoked=false;
 try{
  const space=await start(page),foreignSpace=await start(foreign),base=`/api/spaces/${space}`,concepts=base+'/concepts',notesPath=base+'/notes';
  const concept=await call(page,concepts,'POST',{title:'Concept reverse primary',description:'Canonical unchanged Concept description'}),retired=await call(page,concepts,'POST',{title:'Concept reverse retired',description:'A retired Concept keeps immutable Content references'}),empty=await call(page,concepts,'POST',{title:'Concept reverse empty',description:'No Note refers to this Concept'});
  for(const result of [concept,retired,empty])expect(result.status).toBe(201);
  const foreignConcept=await call(foreign,`/api/spaces/${foreignSpace}/concepts`,'POST',{title:'Foreign Concept reference',description:'Foreign private Concept'});expect(foreignConcept.status).toBe(201);
  const conceptPath=concepts+'/'+concept.body.conceptId,reversePath=base+'/concept-references/'+concept.body.conceptId+'/notes',retiredReverse=base+'/concept-references/'+retired.body.conceptId+'/notes',emptyReverse=base+'/concept-references/'+empty.body.conceptId+'/notes';
  const htmlTitle='<img src="https://example.invalid/private-concept-notes.png" onerror="window.conceptNotesXss=true">';
  const notes:{noteId:string;title:string;markdown:string;etag:string}[]=[];
  for(let i=0;i<22;i++){
   const title=i===0?'Concept linked Note original':i<3?'Concept linked same title':i===3?htmlTitle:'Concept linked Note '+String(i).padStart(3,'0'),markdown='Canonical Concept-linked Note body '+i;
   const result=await call(page,notesPath,'POST',{title,markdown});expect(result.status).toBe(201);notes.push({...result.body,etag:result.etag!});
   const linked=await call(page,notesPath+'/'+result.body.noteId+'/concepts','PUT',{conceptIds:i===0?[concept.body.conceptId,retired.body.conceptId]:[concept.body.conceptId]},'"c0"');expect(linked.status).toBe(200);
  }
  expect(notes[1].noteId).not.toBe(notes[2].noteId);
  // Content owns this reverse read: an unknown or foreign immutable Concept ID is an empty result inside an authorized Space.
  const unknown='018f0000-0000-7000-8000-000000000001';
  for(const id of [unknown,foreignConcept.body.conceptId]){const response=await call(page,base+'/concept-references/'+id+'/notes?limit=20');expect(response.status).toBe(200);expect(response.body).toEqual({items:[],nextAfter:null})}
  expect((await call(foreign,reversePath+'?limit=20')).status).toBe(404);
  expect((await call(page,`/api/spaces/${foreignSpace}/concept-references/${concept.body.conceptId}/notes?limit=20`)).status).toBe(404);
  expect((await call(page,retiredReverse+'?limit=20')).body.items).toEqual([{noteId:notes[0].noteId,title:notes[0].title}]);
  expect((await call(page,concepts+'/'+retired.body.conceptId,'DELETE',undefined,retired.etag!)).status).toBe(204);
  expect((await call(page,concepts+'/'+retired.body.conceptId)).status).toBe(404);
  const retained=await call(page,retiredReverse+'?limit=20');expect(retained.status).toBe(200);expect(retained.body).toEqual({items:[{noteId:notes[0].noteId,title:notes[0].title}],nextAfter:null});
  const mainPath=notesPath+'/'+notes[0].noteId,refs=mainPath+'/concepts',initialNote=await call(page,mainPath),initialRefs=await call(page,refs),initialConcept=await call(page,conceptPath);
  let canonicalWrites=0;page.on('request',request=>{const url=new URL(request.url());if(!['GET','HEAD'].includes(request.method())&&(url.pathname.startsWith(notesPath)||url.pathname.startsWith(concepts)))canonicalWrites++});
  const first=await call(page,reversePath+'?limit=20');expect(first.status).toBe(200);expect(first.body.items).toEqual(notes.slice(0,20).map(({noteId,title})=>({noteId,title})));expect(first.body.nextAfter).toBe(notes[19].noteId);
  const second=await call(page,reversePath+'?limit=20&after='+first.body.nextAfter);expect(second.status).toBe(200);expect(second.body).toEqual({items:notes.slice(20).map(({noteId,title})=>({noteId,title})),nextAfter:null});
  expect(new Set([...first.body.items,...second.body.items].map((row:{noteId:string})=>row.noteId)).size).toBe(22);
  expect(await call(page,mainPath)).toEqual(initialNote);expect(await call(page,refs)).toEqual(initialRefs);expect(await call(page,conceptPath)).toEqual(initialConcept);expect(canonicalWrites).toBe(0);
  // Only the bounded projection is held empty. Every reverse page, Note read, reference write and lifecycle transition is an actual canonical API operation.
  const graphPattern='**'+base+'/graph/notes?*';await page.route(graphPattern,route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({nodes:[],links:[],truncated:true})}));
  await page.getByRole('button',{name:'새로고침',exact:true}).click();await expect(page.getByRole('region',{name:'노트 연결 지도',exact:true}).getByRole('button',{name:/노트 열기:/})).toHaveCount(0);
  const library=page.getByRole('region',{name:'개인 개념 보관함',exact:true}),detail=library.getByRole('region',{name:'개인 개념 상세와 수정 이력',exact:true}),reverse=library.getByRole('region',{name:'개념에 연결된 노트',exact:true}),rows=reverse.locator('.concept-note-results button');
  const openConcept=(title:string)=>library.getByRole('button',{name:'보관함 개념 열기: '+title,exact:true}),openNote=(title:string)=>reverse.getByRole('button',{name:'개념 연결 노트 열기: '+title,exact:true});
  const editor=page.getByRole('region',{name:'노트 본문과 수정 이력',exact:true}),picker=page.getByRole('region',{name:'노트 개념 연결과 이력',exact:true});
  await expect(editor).toHaveCount(0);await library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true}).click();await openConcept(concept.body.title).click();
  await expect(detail.locator('pre')).toHaveText(initialConcept.body.description);await expect(rows).toHaveCount(20);await expect(editor).toHaveCount(0);
  await reverse.getByRole('button',{name:'연결된 노트 더 보기',exact:true}).click();await expect(rows).toHaveCount(22);await expect(reverse.getByRole('button',{name:'연결된 노트 더 보기',exact:true})).toHaveCount(0);await expect(openNote('Concept linked same title')).toHaveCount(2);
  expect(new Set(await rows.allTextContents()).size).toBe(21);await expect(reverse.locator('img,script,iframe')).toHaveCount(0);expect(await page.evaluate(()=>Boolean((window as unknown as {conceptNotesXss?:boolean}).conceptNotesXss))).toBe(false);expect(canonicalWrites).toBe(0);
  // Selecting any immutable Note ID leaves the library Concept edit draft mounted, including distinct Notes with identical titles.
  const conceptDraft='Unsaved Concept draft retained while following reverse references',noteDraft='Unsaved Note draft retained through reverse refresh and reference writes';
  await detail.getByRole('button',{name:'개인 개념 편집',exact:true}).click();await detail.getByLabel('편집할 개념 설명').fill(conceptDraft);
  await openNote('Concept linked same title').nth(1).click();await expect(editor.locator('pre')).toHaveText(notes[2].markdown);await expect(page.locator('.inspector')).toContainText('현재 지도 범위에 포함되지 않은 노트');await expect(detail.getByLabel('편집할 개념 설명')).toHaveValue(conceptDraft);
  await openNote(notes[0].title).click();await expect(editor.locator('pre')).toHaveText(notes[0].markdown);await expect(picker.getByRole('checkbox',{name:concept.body.title,exact:true})).toBeChecked();await expect(detail.getByLabel('편집할 개념 설명')).toHaveValue(conceptDraft);
  await editor.getByRole('button',{name:'노트 편집',exact:true}).click();await editor.getByLabel('편집할 Markdown 본문').fill(noteDraft);
  await reverse.getByRole('button',{name:'연결된 노트 새로고침',exact:true}).click();await expect(rows).toHaveCount(20);await expect(editor.getByLabel('편집할 Markdown 본문')).toHaveValue(noteDraft);await expect(detail.getByLabel('편집할 개념 설명')).toHaveValue(conceptDraft);
  expect(await call(page,mainPath)).toEqual(initialNote);expect(await call(page,refs)).toEqual(initialRefs);expect(await call(page,conceptPath)).toEqual(initialConcept);expect(canonicalWrites).toBe(0);
  // Note-side reference saves refresh the reverse page without remounting either editor or writing the Note body.
  await picker.getByRole('checkbox',{name:concept.body.title,exact:true}).click();await expect(picker.getByRole('checkbox',{name:concept.body.title,exact:true})).not.toBeChecked();await expect(rows).toHaveCount(20);await expect(openNote(notes[0].title)).toHaveCount(0);
  const removed=await call(page,refs);expect(removed.etag).toBe('"c2"');expect(removed.body.items.map((row:{conceptId:string})=>row.conceptId)).toEqual([retired.body.conceptId]);expect(await call(page,mainPath)).toEqual(initialNote);
  await expect(editor.getByLabel('편집할 Markdown 본문')).toHaveValue(noteDraft);await expect(detail.getByLabel('편집할 개념 설명')).toHaveValue(conceptDraft);
  await reverse.getByRole('button',{name:'연결된 노트 더 보기',exact:true}).click();await expect(rows).toHaveCount(21);
  await picker.getByRole('checkbox',{name:concept.body.title,exact:true}).click();await expect(picker.getByRole('checkbox',{name:concept.body.title,exact:true})).toBeChecked();await expect(rows).toHaveCount(20);await expect(openNote(notes[0].title)).toBeVisible();
  const readded=await call(page,refs);expect(readded.etag).toBe('"c3"');expect(new Set(readded.body.items.map((row:{conceptId:string})=>row.conceptId))).toEqual(new Set([concept.body.conceptId,retired.body.conceptId]));expect(readded.body.items.find((row:{conceptId:string})=>row.conceptId===retired.body.conceptId).linkId).toBe(initialRefs.body.items.find((row:{conceptId:string})=>row.conceptId===retired.body.conceptId).linkId);
  await expect(editor.getByLabel('편집할 Markdown 본문')).toHaveValue(noteDraft);await expect(detail.getByLabel('편집할 개념 설명')).toHaveValue(conceptDraft);expect(await call(page,mainPath)).toEqual(initialNote);expect(canonicalWrites).toBe(2);
  // A canonical Note rename updates its reverse summary immediately; the unsaved Concept draft remains untouched.
  const renamed='Concept linked Note renamed';await editor.getByLabel('편집할 제목').fill(renamed);await editor.getByRole('button',{name:'수정 저장',exact:true}).click();await expect(editor.getByRole('status')).toContainText('노트를 수정했습니다');await expect(openNote(renamed)).toBeVisible();await expect(openNote(notes[0].title)).toHaveCount(0);await expect(detail.getByLabel('편집할 개념 설명')).toHaveValue(conceptDraft);
  const renamedNote=await call(page,mainPath);expect(renamedNote.etag).toBe('"2"');expect(renamedNote.body).toMatchObject({noteId:notes[0].noteId,title:renamed,markdown:noteDraft,version:2});expect(await call(page,refs)).toEqual(readded);expect(await call(page,conceptPath)).toEqual(initialConcept);expect(canonicalWrites).toBe(3);
  await reverse.evaluate(element=>element.scrollIntoView({block:'start'}));await page.screenshot({path:'/tmp/graph-ui-results/concept-notes.png',fullPage:true});await page.setViewportSize({width:390,height:844});await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await reverse.evaluate(element=>element.scrollIntoView({block:'start'}));await page.screenshot({path:'/tmp/graph-ui-results/concept-notes-mobile.png',fullPage:true});
  // Retired Notes disappear from the Content-owned reverse read even though their immutable relationship history remains.
  await editor.getByRole('button',{name:'노트 폐기',exact:true}).click();const confirmation=editor.getByRole('region',{name:'노트 폐기 확인',exact:true});await confirmation.getByLabel('복원할 수 없음을 확인했습니다').check();await confirmation.getByRole('button',{name:'폐기 확정',exact:true}).click();
  await expect(editor).toHaveCount(0);await expect(rows).toHaveCount(20);await expect(openNote(renamed)).toHaveCount(0);await expect(detail.getByLabel('편집할 개념 설명')).toHaveValue(conceptDraft);expect((await call(page,mainPath)).status).toBe(404);expect((await call(page,retiredReverse+'?limit=20')).body).toEqual({items:[],nextAfter:null});expect(canonicalWrites).toBe(4);
  const retiredHistory=await call(page,mainPath+'/revisions?limit=5');expect(retiredHistory.body.map((row:{version:number;state:string})=>[row.version,row.state])).toEqual([[3,'RETIRED'],[2,'ACTIVE'],[1,'ACTIVE']]);
  const fresh=await call(page,reversePath+'?limit=20');expect(fresh.body.items).toEqual(notes.slice(1,21).map(({noteId,title})=>({noteId,title})));expect(fresh.body.nextAfter).toBe(notes[20].noteId);expect(await call(page,conceptPath)).toEqual(initialConcept);
  // A delayed old read cannot populate a different Concept after an explicit selection change.
  const reversePattern='**'+reversePath+'?*';let releaseSwitch!:()=>void,finishSwitch!:()=>void,switchStarted=false;const switchHeld=new Promise<void>(resolve=>{releaseSwitch=resolve}),switchDone=new Promise<void>(resolve=>{finishSwitch=resolve});
  await page.route(reversePattern,async route=>{const response=await route.fetch();switchStarted=true;try{await switchHeld;await route.fulfill({response}).catch(()=>{})}finally{finishSwitch()}});
  try{await reverse.getByRole('button',{name:'연결된 노트 새로고침',exact:true}).click();await expect.poll(()=>switchStarted).toBe(true);await page.unroute(reversePattern);await openConcept(empty.body.title).click();await expect(reverse).toContainText('이 개념에 연결된 활성 노트가 없습니다.');releaseSwitch();await switchDone;await expect(rows).toHaveCount(0);await expect(detail.locator('pre')).toHaveText(empty.body.description);expect((await call(page,emptyReverse+'?limit=20')).body).toEqual({items:[],nextAfter:null})}finally{releaseSwitch();await page.unroute(reversePattern)}
  await openConcept(concept.body.title).click();await expect(rows).toHaveCount(20);
  // Closing the Concept also invalidates an outstanding read. A failed fresh read clears all old rows and requires an explicit retry.
  let releaseClose!:()=>void,finishClose!:()=>void,closeStarted=false;const closeHeld=new Promise<void>(resolve=>{releaseClose=resolve}),closeDone=new Promise<void>(resolve=>{finishClose=resolve});
  await page.route(reversePattern,async route=>{const response=await route.fetch();closeStarted=true;try{await closeHeld;await route.fulfill({response}).catch(()=>{})}finally{finishClose()}});
  await reverse.getByRole('button',{name:'연결된 노트 새로고침',exact:true}).click();await expect.poll(()=>closeStarted).toBe(true);await page.unroute(reversePattern);await library.getByRole('button',{name:'보관함 개념 상세 닫기',exact:true}).click();await expect(reverse).toHaveCount(0);
  await page.route(reversePattern,route=>route.fulfill({status:503,contentType:'application/json',body:'{}'}));
  try{await openConcept(concept.body.title).click();await expect(reverse.getByRole('status')).toContainText('연결된 노트를 불러오지 못했습니다');releaseClose();await closeDone;await expect(rows).toHaveCount(0)}finally{releaseClose();await page.unroute(reversePattern)}
  await reverse.getByRole('button',{name:'연결된 노트 새로고침',exact:true}).click();await expect(rows).toHaveCount(20);await expect(openNote(renamed)).toHaveCount(0);await expect(detail.getByLabel('편집할 개념 설명')).toHaveCount(0);expect(await call(page,conceptPath)).toEqual(initialConcept);
  // Session revocation while a canonical reverse read is pending clears every private surface; no persisted browser cache restores it.
  await page.unroute(graphPattern);let releaseDenied!:()=>void,finishDenied!:()=>void,deniedStarted=false;const deniedHeld=new Promise<void>(resolve=>{releaseDenied=resolve}),deniedDone=new Promise<void>(resolve=>{finishDenied=resolve});
  await page.route(reversePattern,async route=>{deniedStarted=true;try{await deniedHeld;await route.continue().catch(()=>{})}finally{finishDenied()}});
  try{await reverse.getByRole('button',{name:'연결된 노트 새로고침',exact:true}).click();await expect.poll(()=>deniedStarted).toBe(true);expect((await call(page,'/api/identity/guest-session/revoke','POST',{})).status).toBe(204);revoked=true}finally{releaseDenied();if(deniedStarted)await deniedDone;await page.unroute(reversePattern)}
  await expect(page.getByRole('button',{name:'내 개인 공간 시작',exact:true})).toBeVisible();await expect(library).toHaveCount(0);await expect(detail).toHaveCount(0);await expect(reverse).toHaveCount(0);await expect(editor).toHaveCount(0);await expect(page.locator('pre,textarea')).toHaveCount(0);
  expect(await page.evaluate(()=>Object.keys(localStorage).length+Object.keys(sessionStorage).length)).toBe(0);expect(await page.evaluate(()=>Boolean((window as unknown as {conceptNotesXss?:boolean}).conceptNotesXss))).toBe(false);expect(errors).toEqual([]);
  console.log('CONCEPT_NOTES_VERIFIED: real 22-Note Content-owned 20+2 cursor pages and distinct same-title IDs before any Note selection; unknown/foreign Concept IDs return an authorized empty result, foreign Space denied, retired Concept references remain readable without an active Knowledge object; exact canonical Note/reference/Concept snapshots unchanged by GET; immutable outside-projection Note navigation and both local drafts retained during reverse refresh and reference remove/readd; retained link ID and separate c-ETag; actual Note rename and retirement refresh reverse summaries without Concept edit loss, retired Note excluded with preserved immutable history; stale reads discarded across Concept switch and close, 503 clears rows with explicit recovery; escaped title, mobile fit, pending-read revocation clears private data without storage or CSP/JavaScript errors');
 }finally{
  await page.unrouteAll({behavior:'ignoreErrors'}).catch(()=>{});if(!revoked)await call(page,'/api/identity/guest-session/revoke','POST',{}).catch(()=>{});
  await call(foreign,'/api/identity/guest-session/revoke','POST',{}).catch(()=>{});await other.close();
 }
});
