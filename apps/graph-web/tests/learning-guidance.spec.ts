import {test,expect,type Page,type Locator} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {randomBytes} from 'node:crypto';

async function call(page:Page,path:string,method='GET',body?:unknown,etag?:string,key?:string){
 return page.evaluate(async({path,method,body,etag,key})=>{
  const r=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:{...(body?{'Content-Type':'application/json'}:{}),...(etag?{'If-Match':etag}:{}),...(key?{'Idempotency-Key':key}:{})},body:body?JSON.stringify(body):undefined});
  return {status:r.status,body:await r.json().catch(()=>null),etag:r.headers.get('ETag'),cache:r.headers.get('Cache-Control')};
 },{path,method,body,etag,key});
}
async function start(page:Page){
 await page.goto('/graph');await page.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();
 await expect(page.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible({timeout:90000});
 return (await call(page,'/api/spaces/personal')).body.spaceId as string;
}
async function root(page:Page,space:string,prefix='Guidance'){
 const base=`/api/spaces/${space}/roadmaps`,created=await call(page,base,'POST',{title:prefix+' roadmap',description:'Private deterministic guidance'});
 expect(created.status).toBe(201);const path=base+'/'+created.body.roadmapId;
 const saved=await call(page,path+'/graph','PUT',{
  nodes:['A','B','C','D','E','F'].map(key=>({key:'new:'+key,title:prefix+' '+key,description:''})),
  edges:[['PREREQUISITE_OF','A','B'],['REQUIRED_FOR','A','B'],['REQUIRED_FOR','B','C'],['RECOMMENDED_BEFORE','A','D'],['PART_OF','E','F'],['OPTIONAL_FOR','A','E'],['RELATED_TO','A','F']].map(([kind,source,target],i)=>({key:'new:e'+i,kind,source:'new:'+source,target:'new:'+target}))
 },'"rm1"');expect(saved.status).toBe(200);
 const ids=Object.fromEntries(saved.body.nodes.map((n:any)=>[n.title.slice(-1),n.nodeId])) as Record<string,string>;
 return {path,space,prefix,value:saved.body,ids};
}
type Root=Awaited<ReturnType<typeof root>>;
async function open(page:Page,r:Root){
 const library=page.locator('.roadmap-library');
 if(await library.getByRole('button',{name:'학습 로드맵 열기',exact:true}).count())await library.getByRole('button',{name:'학습 로드맵 열기',exact:true}).click();
 await library.getByRole('button',{name:`로드맵 열기 ${r.prefix} roadmap ${r.value.roadmapId}`,exact:true}).click();
 const roadmap=library.getByRole('region',{name:'로드맵 상세',exact:true});
 await roadmap.getByRole('button',{name:'학습 진도·기록 열기',exact:true}).click();
 const panel=roadmap.getByRole('region',{name:'로드맵 학습 진도와 기록',exact:true}),guidance=panel.getByRole('region',{name:'다음 학습 안내',exact:true});
 await expect(guidance).toContainText('저장된 로드맵 v2 기준');return {roadmap,panel,guidance};
}
function suggestionRows(guidance:Locator){return guidance.getByRole('list',{name:'지금 학습할 항목',exact:true}).locator(':scope > li')}
function waitingRows(guidance:Locator){return guidance.getByRole('list',{name:'선수 항목을 기다리는 항목',exact:true}).locator(':scope > li')}
function guidanceNodeButton(guidance:Locator,title:string,nodeId:string){
 const escaped=title.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
 return guidance.getByRole('button',{name:new RegExp(`^안내 진도 열기 ${escaped} · (시작 전|학습 중|완료) ${nodeId} · 진도 v[0-9]+$`)});
}
function guidanceButton(guidance:Locator,r:Root,node:string){return guidanceNodeButton(guidance,`${r.prefix} ${node}`,r.ids[node])}
async function sessionDraft(panel:Locator,node:string,note:string){
 await panel.getByRole('button',{name:'학습 시간 기록',exact:true}).click();
 const form=panel.getByRole('form',{name:'학습 시간 기록 작성',exact:true});
 await form.getByLabel('새 기록 학습 항목').selectOption(node);await form.getByLabel('새 기록 학습 메모').fill(note);return form;
}
function sessionInput(nodeId:string,note='Stored independent session'){
 const endedAt=new Date(Math.floor(Date.now()/1000)*1000-600000),startedAt=new Date(endedAt.getTime()-1800000);
 return {nodeId,startedAt:startedAt.toISOString(),endedAt:endedAt.toISOString(),note};
}

test('real guidance ranks six relation kinds, opens fresh progress without writes, and manual completion releases only direct successors',async({page})=>{
 const space=await start(page),r=await root(page,space),progress=(node:string)=>r.path+'/nodes/'+r.ids[node]+'/progress';
 expect((await call(page,progress('F'),'PUT',{status:'IN_PROGRESS'},'"pr0"')).status).toBe(200);
 const before=await call(page,r.path+'/learning-guidance');expect(before).toMatchObject({status:200,cache:'no-store',body:{visibility:'PRIVATE',roadmapVersion:2,algorithm:'GRAPH_PROGRESS_V1'}});
 expect(before.body.suggestions.map((n:any)=>n.nodeId)).toEqual([r.ids.F,...[r.ids.A,r.ids.E].sort(),r.ids.D]);
 expect(before.body.waiting.find((n:any)=>n.nodeId===r.ids.B).requiredPredecessors).toMatchObject([{nodeId:r.ids.A,kinds:['PREREQUISITE_OF','REQUIRED_FOR']}]);
 const recorded=await call(page,r.path+'/study-sessions','POST',sessionInput(r.ids.A),undefined,randomBytes(16).toString('hex'));expect(recorded.status).toBe(201);
 expect(await call(page,r.path+'/learning-guidance')).toEqual(before);
 const graph=await call(page,r.path),history=await call(page,r.path+'/revisions?limit=10'),sessions=await call(page,r.path+'/study-sessions'),a=await call(page,progress('A')),f=await call(page,progress('F'));
 const note=await call(page,`/api/spaces/${space}/notes`,'POST',{title:'Guidance sibling Note',markdown:'Stored sibling'});expect(note.status).toBe(201);
 await page.getByRole('button',{name:'노트 목록 열기',exact:true}).click();await page.locator('.note-library').getByRole('button',{name:'Guidance sibling Note',exact:true}).click();
 const noteEditor=page.getByRole('region',{name:'노트 본문과 수정 이력',exact:true});await noteEditor.getByRole('button',{name:'노트 편집',exact:true}).click();await noteEditor.getByLabel('편집할 Markdown 본문').fill('Unsaved sibling Note guidance');
 const {roadmap,panel,guidance}=await open(page,r);await roadmap.getByRole('button',{name:'로드맵 제목·설명 편집',exact:true}).click();await roadmap.getByLabel('로드맵 제목',{exact:true}).fill('Unsaved sibling roadmap guidance');
 const form=await sessionDraft(panel,r.ids.E,'Unsaved independent session guidance');const writes:string[]=[];
 const watch=(req:any)=>{if(req.url().includes('/api/')&&!['GET','HEAD','OPTIONS'].includes(req.method()))writes.push(req.method()+' '+new URL(req.url()).pathname)};page.on('request',watch);
 await expect(suggestionRows(guidance)).toHaveCount(3);await expect(suggestionRows(guidance).first()).toContainText('Guidance F');
 await guidance.getByRole('button',{name:'학습 안내 모두 보기 (4개)',exact:true}).click();await expect(suggestionRows(guidance)).toHaveCount(4);
 await expect(suggestionRows(guidance).last()).toContainText('아직 완료하지 않은 권장 선행 항목 1개');
 await expect(waitingRows(guidance)).toHaveCount(2);await expect(guidanceButton(guidance,r,'B').locator('..')).toContainText('선수 지식 / 필수 조건');
 await guidance.screenshot({path:'/tmp/graph-ui-results/learning-guidance-desktop.png'});
 await guidanceButton(guidance,r,'F').focus();await page.keyboard.press('Enter');
 const detail=panel.getByRole('region',{name:'학습 노드 진도',exact:true});await expect(detail).toContainText('조회한 진도 v1: 학습 중');
 await guidance.getByRole('button',{name:'다음 학습 안내 새로고침',exact:true}).click();await expect(guidance).toContainText('저장된 로드맵 v2 기준');
 expect(writes).toEqual([]);expect(await call(page,progress('A'))).toEqual(a);expect(await call(page,progress('F'))).toEqual(f);expect(await call(page,r.path+'/study-sessions')).toEqual(sessions);
 await guidanceButton(guidance,r,'B').click();await expect(detail).toContainText('조회한 진도 v0: 시작 전');await detail.getByRole('button',{name:'학습 진도 편집',exact:true}).click();await detail.getByLabel('변경할 학습 상태').selectOption('COMPLETED');await detail.getByRole('button',{name:'학습 진도 저장',exact:true}).click();
 await expect(detail).toContainText('조회한 진도 v1: 완료');await expect(guidance).toContainText('저장된 로드맵 v2 기준');
 // The refreshed list resets to its first three; UUID ties need not follow node creation order.
 await guidance.getByRole('button',{name:'학습 안내 모두 보기 (5개)',exact:true}).click();await expect(suggestionRows(guidance)).toHaveCount(5);await expect(guidanceButton(guidance,r,'B')).toHaveCount(0);await expect(guidanceButton(guidance,r,'C')).toBeVisible();await expect(waitingRows(guidance)).toHaveCount(0);
 const after=await call(page,r.path+'/learning-guidance');expect(after.status).toBe(200);expect(after.body.suggestions.map((n:any)=>n.nodeId)).toEqual([r.ids.F,...[r.ids.A,r.ids.C,r.ids.E].sort(),r.ids.D]);expect(after.body.waiting).toEqual([]);
 expect((await call(page,progress('A'))).body.status).toBe('NOT_STARTED');expect(writes).toEqual(['PUT '+progress('B')]);page.off('request',watch);
 // A repeated selection must freshly read pr1, even if the same detail was already open.
 await guidanceButton(guidance,r,'A').click();await expect(detail).toContainText('조회한 진도 v0: 시작 전');
 expect((await call(page,progress('A'),'PUT',{status:'IN_PROGRESS'},'"pr0"')).status).toBe(200);await guidanceButton(guidance,r,'A').click();await expect(detail).toContainText('조회한 진도 v1: 학습 중');
 await expect(form.getByLabel('새 기록 학습 메모')).toHaveValue('Unsaved independent session guidance');await expect(noteEditor.getByLabel('편집할 Markdown 본문')).toHaveValue('Unsaved sibling Note guidance');await expect(roadmap.getByLabel('로드맵 제목',{exact:true})).toHaveValue('Unsaved sibling roadmap guidance');
 expect(await call(page,r.path)).toEqual(graph);expect(await call(page,r.path+'/revisions?limit=10')).toEqual(history);expect(await call(page,r.path+'/study-sessions')).toEqual(sessions);
});

test('only a saved canonical graph refreshes guidance while independent session drafts survive, with keyboard and 390px reflow',async({page})=>{
 const space=await start(page),r=await root(page,space),stored=await call(page,r.path+'/study-sessions','POST',sessionInput(r.ids.E),undefined,randomBytes(16).toString('hex'));expect(stored.status).toBe(201);
 const sessionPath=r.path+'/study-sessions/'+stored.body.sessionId,sessionSnapshot=await call(page,sessionPath),initial=await call(page,r.path+'/learning-guidance'),{roadmap,panel,guidance}=await open(page,r);
 await panel.getByRole('button',{name:'학습 기록 열기 '+stored.body.sessionId,exact:true}).click();const session=panel.getByRole('region',{name:'학습 기록 상세',exact:true});await session.getByRole('button',{name:'학습 기록 편집',exact:true}).click();await session.getByLabel('수정할 기록 학습 메모').fill('Retained existing session draft');
 const form=await sessionDraft(panel,r.ids.E,'Retained new session draft');await roadmap.getByRole('button',{name:'로드맵 그래프 편집',exact:true}).click();
 await roadmap.getByLabel('편집할 학습 노드').selectOption(r.ids.E);await roadmap.getByLabel('학습 노드 제목').fill('Saved E canonical');
 await guidance.getByRole('button',{name:'다음 학습 안내 새로고침',exact:true}).click();await expect(guidance).toContainText('저장된 로드맵 v2 기준');expect(await call(page,r.path+'/learning-guidance')).toEqual(initial);await expect(guidance.getByRole('button',{name:/안내 진도 열기 Saved E canonical/})).toHaveCount(0);
 await roadmap.getByLabel('편집할 학습 노드').selectOption(r.ids.A);await roadmap.getByRole('button',{name:'학습 노드 초안에서 제외',exact:true}).click();await roadmap.getByLabel('저장에서 제외되는 기존 노드·관계를 폐기하고 수정 이력을 보존함을 확인').check();
 const writes:string[]=[];page.on('request',req=>{if(req.url().includes('/api/')&&!['GET','HEAD','OPTIONS'].includes(req.method()))writes.push(req.method()+' '+new URL(req.url()).pathname)});
 await roadmap.getByRole('button',{name:'로드맵 그래프 저장',exact:true}).click();await expect(guidance).toContainText('저장된 로드맵 v3 기준');await expect(panel.locator('.learning-node-list')).not.toContainText('Guidance A');
 await guidance.getByRole('button',{name:'학습 안내 모두 보기 (4개)',exact:true}).focus();await page.keyboard.press('Enter');await expect(suggestionRows(guidance)).toHaveCount(4);await expect(guidanceButton(guidance,r,'B')).toBeVisible();await expect(guidanceNodeButton(guidance,'Saved E canonical',r.ids.E)).toBeVisible();
 await expect(form.getByLabel('새 기록 학습 메모')).toHaveValue('Retained new session draft');await expect(form.getByLabel('새 기록 학습 항목')).toHaveValue(r.ids.E);await expect(session.getByLabel('수정할 기록 학습 메모')).toHaveValue('Retained existing session draft');expect(await call(page,sessionPath)).toEqual(sessionSnapshot);
 // Historical graph selection must not replace the current saved guidance basis.
 await roadmap.getByRole('button',{name:'로드맵 수정 이력',exact:true}).click();await roadmap.getByRole('button',{name:'로드맵 이력 v2',exact:true}).click();await expect(roadmap.getByRole('article',{name:'로드맵 이력 상세',exact:true})).toContainText('Guidance A');await expect(guidance).toContainText('저장된 로드맵 v3 기준');await expect(guidanceButton(guidance,r,'A')).toHaveCount(0);
 await page.setViewportSize({width:390,height:844});await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
 const button=guidanceNodeButton(guidance,'Saved E canonical',r.ids.E);await button.focus();await expect(button).toBeFocused();await page.keyboard.press('Enter');await expect(panel.getByRole('region',{name:'학습 노드 진도',exact:true})).toContainText('조회한 진도 v0: 시작 전');
 await guidance.screenshot({path:'/tmp/graph-ui-results/learning-guidance-mobile.png'});
 const audit=await new AxeBuilder({page}).include('[aria-label="다음 학습 안내"]').withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa','wcag22aa']).analyze();expect(audit.violations.map(v=>({id:v.id,targets:v.nodes.map(n=>n.target)}))).toEqual([]);
 expect(writes).toEqual(['PUT '+r.path+'/graph']);expect((await call(page,r.path+'/learning-guidance')).body.basis.every((n:any)=>n.progressVersion===0&&n.status==='NOT_STARTED')).toBe(true);await expect(form.getByLabel('새 기록 학습 메모')).toHaveValue('Retained new session draft');await expect(session.getByLabel('수정할 기록 학습 메모')).toHaveValue('Retained existing session draft');
});

test('failed, malformed, late and revoked guidance never restores old private rows and preserves independent drafts on a read failure',async({page})=>{
 const space=await start(page),r=await root(page,space,'First'),other=await root(page,space,'Second'),{panel,guidance}=await open(page,r),form=await sessionDraft(panel,r.ids.E,'Kept on guidance read failure');
 const pattern='**'+r.path+'/learning-guidance';await page.route(pattern,route=>route.fulfill({status:503,headers:{'Cache-Control':'no-store'},contentType:'application/json',body:'{}'}));
 try{await guidance.getByRole('button',{name:'다음 학습 안내 새로고침',exact:true}).click();await expect(guidance).toContainText('다음 학습 안내를 불러오지 못했습니다');await expect(guidance.getByRole('button',{name:/안내 진도 열기/})).toHaveCount(0);await expect(form.getByLabel('새 기록 학습 메모')).toHaveValue('Kept on guidance read failure')}finally{await page.unroute(pattern)}
 await page.route(pattern,async route=>{const response=await route.fetch(),body=await response.json();body.basis[0].status='COMPLETED';body.basis[0].progressVersion=0;await route.fulfill({response,json:body})});
 try{await guidance.getByRole('button',{name:'다음 학습 안내 새로고침',exact:true}).click();await expect(guidance).toContainText('다음 학습 안내를 불러오지 못했습니다');await expect(guidance.getByRole('button',{name:/안내 진도 열기/})).toHaveCount(0)}finally{await page.unroute(pattern)}
 await guidance.getByRole('button',{name:'다음 학습 안내 새로고침',exact:true}).click();await expect(guidance).toContainText('저장된 로드맵 v2 기준');
 let release!:()=>void,done!:()=>void,started=false;const held=new Promise<void>(resolve=>release=resolve),finished=new Promise<void>(resolve=>done=resolve);
 await page.route(pattern,async route=>{const response=await route.fetch();started=true;try{await held;await route.fulfill({response}).catch(()=>{})}finally{done()}});
 try{
  await guidance.getByRole('button',{name:'다음 학습 안내 새로고침',exact:true}).click();await expect.poll(()=>started).toBe(true);
  const second=await open(page,other);release();await finished;await expect(second.guidance).toContainText('Second A');await expect(second.guidance).not.toContainText('First A');await expect(page.getByLabel('새 기록 학습 메모')).toHaveCount(0);
 }finally{release();await page.unroute(pattern)}
 const active=page.getByRole('region',{name:'다음 학습 안내',exact:true});expect((await call(page,'/api/identity/guest-session/revoke','POST',{})).status).toBe(204);
 await active.getByRole('button',{name:'다음 학습 안내 새로고침',exact:true}).click();await expect(page.getByRole('button',{name:'내 개인 공간 시작',exact:true})).toBeVisible();await expect(page.getByRole('region',{name:'다음 학습 안내',exact:true})).toHaveCount(0);await expect(page.locator('body')).not.toContainText('Kept on guidance read failure');
});

test('real registered Space switches clear guidance and session drafts without carrying them into another Personal Space',async({page})=>{
 test.setTimeout(300000);const first=await start(page);await page.getByRole('button',{name:'로그인 · 이 공간 연결',exact:true}).click();await page.getByRole('link',{name:'Register',exact:true}).click();
 const username='guidance-'+Date.now()+'-'+randomBytes(4).toString('hex'),password=randomBytes(24).toString('base64url')+'aA9!';
 for(const [id,value] of [['username',username],['email',username+'@example.invalid'],['firstName','Guidance'],['lastName','Validation'],['password',password],['password-confirm',password]]){const field=page.locator('#'+id);if(await field.count())await field.fill(value)}
 await page.getByRole('button',{name:'Register',exact:true}).click();await expect(page.getByRole('button',{name:'로그아웃',exact:true})).toBeVisible({timeout:90000});await page.getByRole('button',{name:'로그아웃',exact:true}).click();
 await page.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();await expect(page.getByRole('button',{name:'로그인 · 이 공간 연결',exact:true})).toBeVisible({timeout:90000});const second=(await call(page,'/api/spaces/personal')).body.spaceId as string;expect(second).not.toBe(first);
 await page.getByRole('button',{name:'로그인 · 이 공간 연결',exact:true}).click();await page.locator('#username').fill(username);await page.locator('#password').fill(password);await page.getByRole('button',{name:'Sign In',exact:true}).click();
 const picker=page.getByRole('combobox',{name:'개인 공간 선택',exact:true});await expect(picker.locator('option')).toHaveCount(2,{timeout:90000});await picker.selectOption(first);
 const a=await root(page,first,'First Space'),b=await root(page,second,'Second Space'),ui=await open(page,a);await sessionDraft(ui.panel,a.ids.A,'Private first Space session draft');await picker.selectOption(second);
 await expect(page.getByRole('region',{name:'다음 학습 안내',exact:true})).toHaveCount(0);await expect(page.getByLabel('새 기록 학습 메모')).toHaveCount(0);const secondUi=await open(page,b);await expect(secondUi.guidance).toContainText('Second Space A');await expect(secondUi.guidance).not.toContainText('First Space A');
 await picker.selectOption(first);const firstUi=await open(page,a);await expect(firstUi.guidance).toContainText('First Space A');const cleared=await sessionDraft(firstUi.panel,a.ids.A,'');await expect(cleared.getByLabel('새 기록 학습 메모')).toHaveValue('');expect((await call(page,a.path+'/study-sessions')).body.items).toEqual([]);expect((await call(page,b.path+'/study-sessions')).body.items).toEqual([]);await page.getByRole('button',{name:'로그아웃',exact:true}).click();await expect(page.getByRole('region',{name:'다음 학습 안내',exact:true})).toHaveCount(0);
});
