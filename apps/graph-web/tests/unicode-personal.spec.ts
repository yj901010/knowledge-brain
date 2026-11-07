import {test,expect,type Page,type Locator} from '@playwright/test';
import {randomBytes,createHash} from 'node:crypto';

const key=()=>randomBytes(16).toString('hex');
async function call(page:Page,path:string,method='GET',body?:unknown,etag?:string,requestKey?:string){
 return page.evaluate(async({path,method,body,etag,requestKey})=>{const response=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:{...(body!==undefined?{'Content-Type':'application/json'}:{}),...(etag?{'If-Match':etag}:{}),...(requestKey?{'Idempotency-Key':requestKey}:{})},body:body===undefined?undefined:JSON.stringify(body)});return {status:response.status,body:await response.json().catch(()=>null),etag:response.headers.get('ETag')}},{path,method,body,etag,requestKey});
}
async function start(page:Page){await page.goto('/graph');await page.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();await expect(page.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible({timeout:90000});return (await call(page,'/api/spaces/personal')).body.spaceId as string}
async function nativeText(input:Locator,value:string){await input.fill('');await input.pressSequentially(value);await expect(input).toHaveValue(value)}
async function openNote(page:Page,space:string,note:{noteId:string;title:string}){
 await expect.poll(async()=>{const r=await call(page,`/api/spaces/${space}/graph/notes?limit=200`);return r.status===200&&r.body.nodes.some((n:any)=>n.noteId===note.noteId)},{timeout:90000}).toBe(true);
 await page.reload();await page.locator('.note-list').getByRole('button',{name:note.title,exact:true}).click();
 await expect(page.getByRole('region',{name:'노트 본문과 수정 이력',exact:true})).toContainText('Revision v1');
}

test('real native Note and inline Concept inputs accept200 astral code points, reject201 ASCII, and send200-code-point search intact',async({page})=>{
 test.setTimeout(240000);const space=await start(page),base=`/api/spaces/${space}`,noteTitle='😀'.repeat(200),conceptTitle='🧠'.repeat(200),prefix='u'+randomBytes(7).toString('hex'),query=prefix+'😀'.repeat(200-prefix.length);
 const noteWrites:any[]=[],conceptWrites:any[]=[],searches:string[]=[];
 page.on('request',r=>{const u=new URL(r.url());if(r.method()==='POST'&&u.pathname===base+'/notes')noteWrites.push(r.postDataJSON());if(r.method()==='POST'&&u.pathname===base+'/concepts')conceptWrites.push(r.postDataJSON());if(r.method()==='GET'&&u.pathname===base+'/search/notes')searches.push(u.searchParams.get('q')??'')});
 await page.getByRole('button',{name:'+ 새 노트',exact:true}).click();const dialog=page.getByRole('dialog',{name:'새 노트',exact:true}),title=dialog.getByLabel('제목',{exact:true}),save=dialog.getByRole('button',{name:'저장',exact:true});
 await nativeText(title,'a'.repeat(201));await expect(save).toBeDisabled();await title.press('Enter');expect(noteWrites).toEqual([]);
 await nativeText(title,noteTitle);await dialog.getByLabel('Markdown 본문',{exact:true}).fill(query);await expect(save).toBeEnabled();await save.click();await expect(dialog).not.toBeVisible();
 expect(noteWrites).toEqual([{title:noteTitle,markdown:query}]);const notes=await call(page,base+'/notes?limit=20');expect(notes.status).toBe(200);expect(notes.body.items).toHaveLength(1);const note=notes.body.items[0];expect(note.title).toBe(noteTitle);expect((await call(page,base+'/notes/'+note.noteId)).body.markdown).toBe(query);
 await openNote(page,space,note);const concepts=page.getByRole('region',{name:'노트 개념 연결과 이력',exact:true}),name=concepts.getByLabel('새 개념 이름',{exact:true}),create=concepts.getByRole('button',{name:'개념 만들기',exact:true});
 await nativeText(name,'b'.repeat(201));await expect(create).toBeDisabled();await name.press('Enter');expect(conceptWrites).toEqual([]);
 await nativeText(name,conceptTitle);await expect(create).toBeEnabled();await create.click();await expect(concepts.getByRole('status')).toContainText('개념을 만들었습니다.');
 expect(conceptWrites).toEqual([{title:conceptTitle,description:''}]);const canonical=await call(page,base+'/concepts?limit=20');expect(canonical.status).toBe(200);expect(canonical.body.items).toHaveLength(1);expect(canonical.body.items[0].title).toBe(conceptTitle);expect((await call(page,base+'/notes/'+note.noteId+'/concepts')).body.items).toEqual([]);
 await expect.poll(async()=>{const r=await call(page,base+'/search/notes?'+new URLSearchParams({q:query}));return r.status===200?r.body.total:0},{timeout:90000}).toBe(1);
 const search=page.getByRole('region',{name:'개인 노트 검색',exact:true}),input=search.getByRole('searchbox');await nativeText(input,query);const count=searches.length;await search.getByRole('button',{name:'검색',exact:true}).click();await expect(search.locator('.search-results button')).toHaveCount(1);expect(searches.slice(count)).toEqual([query]);
 const rejected=searches.length;await nativeText(input,'c'.repeat(201));await search.getByRole('button',{name:'검색',exact:true}).click();await expect(search.getByRole('status')).toContainText('1~200자');expect(searches).toHaveLength(rejected);
 console.log('UNICODE_PERSONAL_NATIVE_VERIFIED: exact real200-code-point Note/Concept saves,201 ASCII no-write guards and unchanged200-code-point search');
});

test('real metadata keeps NBSP narrow-NBSP and BOM names across explicit replacement with exact CAS and immutable history',async({page})=>{
 test.setTimeout(180000);const space=await start(page),base=`/api/spaces/${space}`,created=await call(page,base+'/notes','POST',{title:'Unicode metadata '+Date.now(),markdown:'Independent original body'});expect(created.status).toBe(201);const note=created.body,path=base+'/notes/'+note.noteId+'/metadata',markers=['\u00a0','\u202f','\ufeff'];
 const tags=markers.map((m,i)=>m+'tag-'+i+m),properties=Object.fromEntries(markers.map((m,i)=>[m+'property-'+i+m,{type:'STRING',value:'😀'.repeat(1000)}]));
 const saved=await call(page,path,'PUT',{tags,properties},'"m0"');expect(saved.status).toBe(200);expect(saved.etag).toBe('"m1"');const history=await call(page,path+'/revisions');expect(history.body).toHaveLength(1);
 await openNote(page,space,note);const panel=page.getByRole('region',{name:'노트 태그와 속성',exact:true});await panel.getByRole('button',{name:'태그·속성 편집',exact:true}).click();
 for(let i=0;i<saved.body.tags.length;i++)await expect(panel.getByLabel('태그 '+(i+1),{exact:true})).toHaveValue(saved.body.tags[i].name);
 for(let i=0;i<saved.body.properties.length;i++){const property=panel.getByRole('group',{name:'속성 '+(i+1),exact:true});await expect(property.getByLabel('속성 '+(i+1)+' 이름',{exact:true})).toHaveValue(saved.body.properties[i].name);await expect(property.getByRole('combobox',{name:'속성 '+(i+1)+' 형식',exact:true})).toHaveValue('STRING');await expect(property.locator('textarea')).toHaveValue('😀'.repeat(1000))}
 const sent:{body:any;etag:string|undefined}[]=[];page.on('request',r=>{if(r.method()==='PUT'&&new URL(r.url()).pathname===path)sent.push({body:r.postDataJSON(),etag:r.headers()['if-match']})});await panel.getByRole('button',{name:'태그·속성 저장',exact:true}).click();await expect(panel.getByRole('status')).toHaveText('태그·속성을 저장했습니다.');
 expect(sent).toHaveLength(1);expect(sent[0].etag).toBe(saved.etag);expect(new Set(sent[0].body.tags)).toEqual(new Set(tags));expect(Object.keys(sent[0].body.properties).sort()).toEqual(Object.keys(properties).sort());const current=await call(page,path);expect(current.etag).toBe('"m2"');expect(current.body).toEqual({...saved.body,metadataVersion:2});const replacedHistory=await call(page,path+'/revisions');expect(replacedHistory.body).toHaveLength(2);expect(replacedHistory.body[1]).toEqual(history.body[0]);expect(replacedHistory.body[0].snapshot).toEqual(current.body);expect((await call(page,base+'/notes/'+note.noteId)).etag).toBe('"1"');
 // Existing metadata writes append a revision even for equal contents; stale CAS cannot append one.
 expect((await call(page,path,'PUT',{tags,properties},saved.etag!)).status).toBe(412);expect(await call(page,path)).toEqual(current);expect((await call(page,path+'/revisions')).body).toEqual(replacedHistory.body);
 // U+001C is Java whitespace, unlike ECMAScript trim: only the name edges normalize.
 await panel.getByRole('button',{name:'태그·속성 편집',exact:true}).click();await panel.getByRole('button',{name:'태그 추가',exact:true}).click();await panel.getByLabel('태그 4',{exact:true}).fill('\u001cnew tag\u001c');await panel.getByRole('button',{name:'태그·속성 저장',exact:true}).click();await expect(panel.getByRole('status')).toHaveText('태그·속성을 저장했습니다.');const updated=await call(page,path);expect(updated.etag).toBe('"m3"');expect(new Set(updated.body.tags.map((t:any)=>t.name))).toEqual(new Set([...tags,'new tag']));expect(updated.body.properties).toEqual(saved.body.properties);
 console.log('UNICODE_METADATA_CAS_VERIFIED: three retained non-Java-whitespace name classes,1000 astral STRING values, exact m1 replacement, immutable history, stale no-write and Java-strip edge normalization');
});

test('real MinIO uploads preserve NBSP narrow-NBSP and BOM filename edges and sealed download bytes',async({page})=>{
 test.setTimeout(240000);const space=await start(page),base=`/api/spaces/${space}`,created=await call(page,base+'/notes','POST',{title:'Unicode media '+Date.now(),markdown:'Independent media body'});expect(created.status).toBe(201);await openNote(page,space,created.body);const panel=page.getByRole('region',{name:'노트 첨부파일',exact:true}),markers=['\u00a0','\u202f','\ufeff'];
 for(let i=0;i<markers.length;i++){
  const filename=markers[i]+'unicode-'+i+'-😀.bin'+markers[i],bytes=Buffer.from('Sealed Unicode filename '+i),hash=createHash('sha256').update(bytes).digest('hex');await panel.getByLabel('업로드할 파일',{exact:true}).setInputFiles({name:filename,mimeType:'application/octet-stream',buffer:bytes});await panel.getByRole('button',{name:'파일 업로드',exact:true}).click();await expect(panel.getByRole('status')).toContainText('파일 업로드를 완료했습니다.');
  const media=(await call(page,base+'/media?limit=100')).body.items.find((m:any)=>m.filename===filename);expect(media).toMatchObject({filename,state:'AVAILABLE',sha256:hash,byteSize:bytes.length});const row=panel.locator('.media-library li').filter({hasText:'unicode-'+i+'-'});await expect(row).toHaveCount(1);expect(await row.locator('strong').textContent()).toBe(filename);
  const downloadEvent=page.waitForEvent('download');await row.getByRole('button',{name:/^파일 다운로드/}).click();const download=await downloadEvent,stream=await download.createReadStream(),chunks:Buffer[]=[];for await(const chunk of stream!)chunks.push(Buffer.from(chunk));expect(Buffer.concat(chunks)).toEqual(bytes);
 }
 expect((await call(page,base+'/media?limit=100')).body.items).toHaveLength(3);expect((await call(page,base+'/notes/'+created.body.noteId)).etag).toBe('"1"');expect((await call(page,base+'/notes/'+created.body.noteId+'/attachments')).body.items).toEqual([]);
 console.log('UNICODE_MEDIA_FILENAME_VERIFIED: three original filename edge classes, actual direct MinIO upload and exact sealed download bytes');
});

test('real Java-nonblank titles remain editable in Concept Bookmarks Goals Tasks and Schedules without trimming or rewriting source',async({page})=>{
 test.setTimeout(240000);const space=await start(page),base=`/api/spaces/${space}`,raw='\u00a0\u202f\ufeff';
 // No interceptions: each canonical owner first accepts the same unusual but valid title.
 const fixtures=[
  {path:'/bookmarks',body:{title:raw,url:'https://example.invalid/unicode',memo:'Original bookmark'},id:'bookmarkId',open:'개인 북마크 열기',region:'개인 북마크 관리',edit:'북마크 편집',field:'수정할 북마크 제목',save:'북마크 수정 저장'},
  {path:'/goals',body:{title:raw,description:'Original goal',status:'NOT_STARTED',targetDate:null},id:'goalId',open:'개인 목표 열기',region:'개인 목표 관리',edit:'목표 편집',field:'수정할 목표 제목',save:'목표 수정 저장'},
  {path:'/tasks',body:{title:raw,description:'Original task',status:'NOT_STARTED',dueDate:null,goalId:null},id:'taskId',open:'개인 할 일 열기',region:'개인 할 일 관리',edit:'할 일 편집',field:'수정할 할 일 제목',save:'할 일 수정 저장'},
  {path:'/schedules',body:{title:raw,description:'Original schedule',startsAt:'2030-01-01T00:00:00Z',endsAt:'2030-01-01T01:00:00Z',timeZone:'UTC',taskId:null},id:'scheduleId',open:'개인 일정 열기',region:'개인 일정 관리',edit:'일정 편집',field:'수정할 일정 제목',save:'일정 수정 저장'}
 ];
 for(const f of fixtures){
  const created=await call(page,base+f.path,'POST',f.body,undefined,key());expect(created.status).toBe(201);expect(created.body.title).toBe(raw);const path=base+f.path+'/'+created.body[f.id],before=await call(page,path);
  await page.getByRole('button',{name:f.open,exact:true}).click();const panel=page.getByRole('region',{name:f.region,exact:true});
  if(f.id==='scheduleId'){await panel.getByLabel('일정 조회 시작일',{exact:true}).fill('2030-01-01');await panel.getByLabel('일정 조회 종료일',{exact:true}).fill('2030-01-01');await panel.getByLabel('일정 조회 시간대',{exact:true}).fill('UTC');await panel.getByRole('button',{name:'개인 일정 새로고침',exact:true}).click()}
  await panel.getByRole('button',{name:new RegExp(created.body[f.id])}).click();await panel.getByRole('button',{name:f.edit,exact:true}).click();await expect(panel.getByLabel(f.field,{exact:true})).toHaveValue(raw);await expect(panel.getByRole('button',{name:f.save,exact:true})).toBeEnabled();expect(await call(page,path)).toEqual(before);
 }
 const library=page.getByRole('region',{name:'개인 개념 보관함',exact:true});await library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true}).click();await library.getByRole('button',{name:'개인 개념 작성',exact:true}).click();const form=library.getByRole('region',{name:'새 개인 개념',exact:true});await form.getByLabel('새 개인 개념 제목',{exact:true}).fill(raw);await form.getByLabel('새 개인 개념 설명',{exact:true}).fill('Original description');await expect(form.getByRole('button',{name:'개인 개념 저장',exact:true})).toBeEnabled();await form.getByRole('button',{name:'개인 개념 저장',exact:true}).click();await expect(form).toHaveCount(0);const concept=(await call(page,base+'/concepts?limit=20')).body.items[0];expect(concept.title).toBe(raw);
 await library.locator('.concept-library-results button').click();const editor=library.getByRole('region',{name:'개인 개념 상세와 수정 이력',exact:true});await editor.getByRole('button',{name:'개인 개념 편집',exact:true}).click();await expect(editor.getByLabel('편집할 개념 제목',{exact:true})).toHaveValue(raw);await expect(editor.getByRole('button',{name:'개념 수정 저장',exact:true})).toBeEnabled();expect((await call(page,base+'/concepts/'+concept.conceptId)).body).toMatchObject({title:raw,version:1,description:'Original description'});
 console.log('UNICODE_JAVA_BLANK_VERIFIED: real required-title canonical reads and retained edit values across Concept/Bookmark/Goal/Task/Schedule, no implicit strip or save');
});
