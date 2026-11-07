import {test,expect,Page} from '@playwright/test';

async function call(page:Page,path:string,method='GET',body?:unknown,etag?:string){return page.evaluate(async({path,method,body,etag})=>{const r=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:{...(body?{'Content-Type':'application/json'}:{}),...(etag?{'If-Match':etag}:{})},body:body?JSON.stringify(body):undefined});return {status:r.status,body:await r.json().catch(()=>null),etag:r.headers.get('ETag')}},{path,method,body,etag})}
async function fixture(page:Page){
 await page.goto('/graph');await page.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();await expect(page.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible();
 const space=(await call(page,'/api/spaces/personal')).body.spaceId,base=`/api/spaces/${space}`,concepts=base+'/concepts',ids:string[]=[];
 for(const title of ['Comparison subject','Comparison predicate','Comparison object','Replacement predicate']){const r=await call(page,concepts,'POST',{title,description:'Comparison fixture'});expect(r.status).toBe(201);ids.push(r.body.conceptId)}
 const path=concepts+'/'+ids[0]+'/claims',created=await call(page,path,'POST',{predicateConceptId:ids[1],object:{type:'CONCEPT',conceptId:ids[2]}});expect(created.status).toBe(201);
 const cp=path+'/'+created.body.claimId;
 const library=page.getByRole('region',{name:'개인 개념 보관함',exact:true}),panel=library.getByRole('region',{name:'개념 관계와 수정 이력',exact:true}),detail=panel.getByRole('region',{name:'선택한 개념 관계',exact:true}),comparison=detail.getByRole('region',{name:'관계 버전 비교',exact:true});
 async function open(){await library.getByRole('button',{name:'개인 개념 보관함 열기',exact:true}).click();await library.getByRole('button',{name:'보관함 개념 열기: Comparison subject',exact:true}).click();await panel.getByRole('button',{name:'관계 열기: '+created.body.claimId,exact:true}).click();await expect(detail).toContainText('관계 Revision')}
 async function edit(version:number,type:string,value:string,predicate=ids[3]){const r=await call(page,cp,'PUT',{predicateConceptId:predicate,object:type==='CONCEPT'?{type,conceptId:value}:{type,value}},`"cl${version-1}"`);expect(r.status).toBe(200)}
 return {base,concepts,ids,path,cp,library,panel,detail,comparison,open,edit};
}
async function revoke(page:Page){await call(page,'/api/identity/guest-session/revoke','POST',{}).catch(()=>{})}

test('immutable Claim versions compare exact typed values, predicate changes and retirement across pages without writes',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error'&&/Content Security Policy|Refused to/.test(m.text()))errors.push(m.text())});
 try{
  const f=await fixture(page),exact='9007199254740993.123456789012345678',text='<img src=x onerror="window.compareXss=true">\n'+ '🧠'.repeat(440);
  const source=await call(page,f.base+'/sources','POST',{title:'Comparison evidence',url:null,citation:'Evidence',licenseNote:''});expect(source.status).toBe(201);
  const citation=await call(page,f.cp+'/citations','POST',{sourceId:source.body.sourceId,sourceVersion:1,claimVersion:1});expect(citation.status).toBe(201);
  const qualifier=await call(page,f.cp+'/qualifiers','POST',{predicateConceptId:f.ids[1],value:{type:'STRING',value:'scope'},claimVersion:1});expect(qualifier.status).toBe(201);
  await f.edit(2,'NUMBER',exact);await f.edit(3,'STRING',exact);await f.edit(4,'DATE','2026-10-21');await f.edit(5,'BOOLEAN','false');await f.edit(6,'STRING',text);
  await f.open();await f.detail.getByRole('button',{name:'관계 폐기',exact:true}).click();await f.detail.getByLabel('관계 폐기에 동의합니다').check();await f.detail.getByRole('button',{name:'관계 폐기 확정',exact:true}).click();await expect(f.detail).toContainText('개념 관계를 폐기했습니다');
  const snapshots=[f.cp+'/revisions?limit=10',f.cp+'/citations/'+citation.body.citationId,f.cp+'/qualifiers/'+qualifier.body.qualifierId,f.concepts+'/'+f.ids[0]];
  const before=await Promise.all(snapshots.map(p=>call(page,p)));const history=before[0].body as any[],id=(version:number)=>history.find(r=>r.version===version).revisionId;
  // A current rename must never become a historical comparison label.
  expect((await call(page,f.concepts+'/'+f.ids[3],'PUT',{title:'Renamed current predicate',description:'Changed current name'},'"1"')).status).toBe(200);
  await f.panel.getByRole('button',{name:'관계 개념 새로고침',exact:true}).click();
  const parent=f.library.getByRole('region',{name:'개인 개념 상세와 수정 이력',exact:true});await parent.getByRole('button',{name:'개인 개념 편집',exact:true}).click();await parent.getByLabel('편집할 개념 설명').fill('Untouched comparison parent draft');
  await f.panel.getByLabel('새 관계의 대상 종류').selectOption('STRING');await f.panel.getByLabel('새 관계의 대상 값').fill('Untouched new relation draft');
  let writes=0;page.on('request',r=>{if(new URL(r.url()).pathname.startsWith(f.base)&&!['GET','HEAD'].includes(r.method()))writes++});
  await f.detail.getByRole('button',{name:'관계 수정 이력',exact:true}).click();await expect(f.comparison.getByRole('status')).toHaveText('v6 → v7 · 변경된 항목 1개');
  await expect(f.comparison.getByLabel('비교 기준 버전').locator('option')).toHaveCount(6);
  await f.comparison.getByLabel('변경된 항목만 보기').check();await expect(f.comparison.locator('tbody tr')).toHaveCount(1);await expect(f.comparison.locator('tbody')).toContainText('ACTIVE');await expect(f.comparison.locator('tbody')).toContainText('RETIRED');
  await f.detail.getByRole('button',{name:'이전 관계 수정 더 보기',exact:true}).click();await expect(f.comparison.getByLabel('비교 기준 버전').locator('option')).toHaveCount(8);await expect(f.comparison.getByLabel('비교 기준 버전')).toHaveValue(id(6));
  async function compare(a:number,b:number,count:number){await f.comparison.getByLabel('비교 기준 버전').selectOption(id(a));await f.comparison.getByLabel('비교 대상 버전').selectOption(id(b));await expect(f.comparison.getByRole('status')).toHaveText(`v${a} → v${b} · 변경된 항목 ${count}개`);await expect(f.comparison.getByLabel('이전 버전 정보', {exact:true})).toContainText(id(a));await expect(f.comparison.getByLabel('이후 버전 정보', {exact:true})).toContainText(id(b))}
  await compare(1,2,3);await expect(f.comparison.locator('tbody')).toContainText(f.ids[1]);await expect(f.comparison.locator('tbody')).toContainText(f.ids[3]);await expect(f.comparison.locator('tbody')).toContainText(exact);await expect(f.comparison).not.toContainText('Renamed current predicate');
  await compare(2,3,1);await expect(f.comparison.locator('tbody tr')).toHaveCount(1);await expect(f.comparison.locator('tbody')).toContainText('대상 종류');
  await f.comparison.getByLabel('변경된 항목만 보기').uncheck();const valueRow=f.comparison.getByRole('row').filter({hasText:'대상 ID 또는 값'});await expect(valueRow.locator('td').nth(0)).toHaveText(exact);await expect(valueRow.locator('td').nth(1)).toHaveText(exact);
  await compare(3,4,2);await expect(valueRow).toContainText('2026-10-21');await compare(4,5,2);await expect(valueRow).toContainText('false');await compare(5,6,2);expect(await valueRow.locator('td').nth(1).textContent()).toBe(text);
  await expect(f.comparison.locator('img,script,iframe')).toHaveCount(0);expect(await page.evaluate(()=>Boolean((window as any).compareXss))).toBe(false);
  await page.setViewportSize({width:390,height:844});await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await f.comparison.screenshot({path:'/tmp/graph-ui-results/claim-comparison-mobile.png'});
  await f.comparison.getByLabel('비교 기준 버전').selectOption(id(6));await expect(f.comparison.getByRole('status')).toContainText('기준보다 나중');await expect(f.comparison.getByRole('table')).toHaveCount(0);
  await f.comparison.getByLabel('비교 기준 버전').selectOption(id(7));await expect(f.comparison.getByRole('table')).toHaveCount(0);
  await f.detail.getByRole('button',{name:'관계 이력 새로고침',exact:true}).click();await expect(f.comparison.getByRole('status')).toHaveText('v6 → v7 · 변경된 항목 1개');await expect(f.comparison.getByLabel('비교 기준 버전').locator('option')).toHaveCount(6);
  expect(await Promise.all(snapshots.map(p=>call(page,p)))).toEqual(before);expect(writes).toBe(0);await expect(parent.getByLabel('편집할 개념 설명')).toHaveValue('Untouched comparison parent draft');await expect(f.panel.getByLabel('새 관계의 대상 값')).toHaveValue('Untouched new relation draft');
  expect((await call(page,f.cp)).status).toBe(404);expect(errors).toEqual([]);
  console.log('CLAIM_COMPARISON_VERIFIED: five exact object kinds, type-only change, predicate and retirement diffs, 5+2 immutable pages, escaped long Unicode, 390px fit, stable revision metadata, independent pinned Citation/Qualifier facts and drafts, zero comparison mutations');
 }finally{await revoke(page)}
});

test('comparison rejects ambiguous or foreign history and clears old snapshots on failed append, with explicit recovery',async({page})=>{
 try{
  const f=await fixture(page);await f.open();await f.detail.getByRole('button',{name:'관계 수정 이력',exact:true}).click();await expect(f.comparison.getByRole('status')).toContainText('두 개 이상의');await expect(f.comparison.getByRole('table')).toHaveCount(0);
  for(let v=2;v<=7;v++)await f.edit(v,'NUMBER',String(v));
  await f.detail.getByRole('button',{name:'관계 이력 새로고침',exact:true}).click();await expect(f.comparison.getByRole('table')).toBeVisible();
  const first=(await call(page,f.cp+'/revisions?limit=5')).body,pattern='**'+f.cp+'/revisions?*';
  const corruptions=[(rows:any[])=>{rows[1].revisionId=rows[0].revisionId},(rows:any[])=>{rows[0].claimId=f.ids[2]},(rows:any[])=>{rows[0].subjectConceptId=f.ids[2]},(rows:any[])=>{rows[0].object={type:'NUMBER',value:7}},(rows:any[])=>{rows[1].version=rows[0].version}];
  for(const corrupt of corruptions){
   await page.route(pattern,async route=>{const response=await route.fetch(),rows=await response.json();corrupt(rows);await route.fulfill({response,json:rows})});
   await f.detail.getByRole('button',{name:'관계 이력 새로고침',exact:true}).click();await expect(f.detail.getByRole('status')).toContainText('관계 수정 이력을 불러오지 못했습니다');await expect(f.comparison).toHaveCount(0);await page.unroute(pattern);
   await f.detail.getByRole('button',{name:'관계 이력 새로고침',exact:true}).click();await expect(f.comparison.getByRole('table')).toBeVisible();
  }
  await page.route(pattern,async route=>{const response=await route.fetch(),rows=await response.json();if(new URL(route.request().url()).searchParams.has('before'))rows[0].revisionId=first[0].revisionId;await route.fulfill({response,json:rows})});
  await f.detail.getByRole('button',{name:'이전 관계 수정 더 보기',exact:true}).click();await expect(f.detail.getByRole('status')).toContainText('관계 수정 이력을 불러오지 못했습니다');await expect(f.comparison).toHaveCount(0);await page.unroute(pattern);
  await f.detail.getByRole('button',{name:'관계 이력 새로고침',exact:true}).click();await expect(f.comparison.getByRole('table')).toBeVisible();
  await page.route(pattern,route=>route.fulfill({status:503,contentType:'application/json',body:'{}'}));await f.detail.getByRole('button',{name:'이전 관계 수정 더 보기',exact:true}).click();await expect(f.detail.getByRole('status')).toContainText('관계 수정 이력을 불러오지 못했습니다');await expect(f.comparison).toHaveCount(0);await page.unroute(pattern);
  await f.detail.getByRole('button',{name:'관계 이력 새로고침',exact:true}).click();await expect(f.comparison.getByRole('table')).toBeVisible();await f.detail.getByRole('button',{name:'이전 관계 수정 더 보기',exact:true}).click();await expect(f.comparison.getByLabel('비교 기준 버전').locator('option')).toHaveCount(8);
  // Different revision IDs can legitimately have identical content (changed away and back).
  await f.edit(8,'NUMBER','6');await f.detail.getByRole('button',{name:'관계 이력 새로고침',exact:true}).click();const all=(await call(page,f.cp+'/revisions?limit=10')).body;
  await f.comparison.getByLabel('비교 기준 버전').selectOption(all.find((r:any)=>r.version===6).revisionId);await f.comparison.getByLabel('비교 대상 버전').selectOption(all.find((r:any)=>r.version===8).revisionId);await expect(f.comparison.getByRole('status')).toContainText('변경된 항목 0개');await f.comparison.getByLabel('변경된 항목만 보기').check();await expect(f.comparison).toContainText('선택한 두 버전의 관계 내용이 같습니다');await expect(f.comparison.getByRole('table')).toHaveCount(0);
 }finally{await page.unrouteAll({behavior:'ignoreErrors'});await revoke(page)}
});

test('late history cannot restore closed or switched comparison and real session revocation clears private snapshots',async({page})=>{
 try{
  const f=await fixture(page);await f.edit(2,'STRING','Private comparison value');await f.open();await f.detail.getByRole('button',{name:'관계 수정 이력',exact:true}).click();await expect(f.comparison.getByRole('table')).toBeVisible();
  const pattern='**'+f.cp+'/revisions?*';
  for(const close of [true,false]){
   let release!:()=>void,done!:()=>void,started=false;const held=new Promise<void>(r=>release=r),finished=new Promise<void>(r=>done=r);
   await page.route(pattern,async route=>{const response=await route.fetch();started=true;try{await held;await route.fulfill({response}).catch(()=>{})}finally{done()}});
   try{await f.detail.getByRole('button',{name:'관계 이력 새로고침',exact:true}).click();await expect.poll(()=>started).toBe(true);if(close)await f.detail.getByRole('button',{name:'관계 상세 닫기',exact:true}).click();else await f.library.getByRole('button',{name:'보관함 개념 열기: Comparison object',exact:true}).click();await expect(f.comparison).toHaveCount(0);release();await finished;await expect(f.comparison).toHaveCount(0)}finally{release();await page.unroute(pattern)}
   if(!close)await f.library.getByRole('button',{name:'보관함 개념 열기: Comparison subject',exact:true}).click();await f.panel.getByRole('button',{name:'관계 열기: '+f.cp.split('/').at(-1),exact:true}).click();await f.detail.getByRole('button',{name:'관계 수정 이력',exact:true}).click();await expect(f.comparison.getByRole('table')).toBeVisible();
  }
  expect((await call(page,'/api/identity/guest-session/revoke','POST',{})).status).toBe(204);await f.detail.getByRole('button',{name:'관계 이력 새로고침',exact:true}).click();await expect(page.getByRole('button',{name:'내 개인 공간 시작',exact:true})).toBeVisible();await expect(f.comparison).toHaveCount(0);await expect(f.library).toHaveCount(0);await expect(page.getByText('Private comparison value',{exact:true})).toHaveCount(0);expect(await page.evaluate(()=>Object.keys(localStorage).length+Object.keys(sessionStorage).length)).toBe(0);
 }finally{await page.unrouteAll({behavior:'ignoreErrors'});await revoke(page)}
});
