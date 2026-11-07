import {test,expect,type Page} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

async function call(page:Page,path:string){return page.evaluate(async path=>{const r=await fetch(path,{credentials:'same-origin',cache:'no-store'});return {status:r.status,body:await r.json()}},path)}
async function start(page:Page){
 await page.goto('/graph');await page.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();await expect(page.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible({timeout:90000});
 const spaces=(await call(page,'/api/spaces/public')).body.items as {spaceId:string;domain:string}[];
 await page.goto('/graph/knowledge');const field=page.getByLabel('공개 분야',{exact:true});await expect(field).toBeEnabled();
 const space=spaces.find(s=>s.domain==='DEVELOPMENT')!.spaceId;
 if(await field.inputValue()!==space)await field.selectOption(space);
 const discovery=page.getByRole('region',{name:'공개 검색과 그래프',exact:true});await expect(discovery.getByRole('group',{name:'공개 지식 관계 그래프'})).toBeVisible({timeout:90000});return {space,spaces,discovery};
}

test('real public graph and search tables sort only loaded rows, preserve typed literals and pin paging filters',async({page})=>{
 test.setTimeout(180000);const {space,discovery}=await start(page);const writes:string[]=[];
 page.on('request',r=>{if(r.url().includes('/api/public/')&&r.method()!=='GET')writes.push(r.method())});
 await discovery.getByRole('button',{name:'공개 표로 보기',exact:true}).click();
 const graphTable=discovery.getByRole('table',{name:/공개 그래프 자료 표/});await expect(graphTable).toBeVisible();
 const first=graphTable.getByRole('button',{name:/^공개 표 자료 열기:/}).first();await expect(first).toBeVisible();
 const title=(await first.textContent())!;await first.focus();await first.press('Enter');
 await expect(discovery.getByRole('region',{name:'공개 탐색 자료 상세'})).toContainText(title);
 const header=graphTable.getByRole('columnheader',{name:/제목/});await expect(header).toHaveAttribute('aria-sort','ascending');
 await header.getByRole('button').click();await expect(header).toHaveAttribute('aria-sort','descending');
 const sorted=await graphTable.getByRole('button',{name:/^공개 표 자료 열기:/}).allTextContents();
 expect(await page.evaluate(rows=>rows.every((v,i)=>i===0||rows[i-1].localeCompare(v,'ko')>=0),sorted)).toBe(true);
 await graphTable.locator('caption').scrollIntoViewIfNeeded();
 await page.screenshot({path:'/tmp/graph-ui-results/public-tables-desktop.png',animations:'disabled'});
 await discovery.getByRole('button',{name:'공개 그래프로 보기',exact:true}).click();
 await expect(discovery.getByRole('group',{name:'공개 지식 관계 그래프'}).locator('.is-selected')).toHaveCount(1);

 await discovery.getByRole('textbox',{name:'전체 공개 지식 검색',exact:true}).fill('Discovery');await discovery.getByLabel('공개 검색 종류').selectOption('CONCEPT');
 const initial=page.waitForResponse(r=>r.url().includes(`/api/public/spaces/${space}/search?`)&&new URL(r.url()).searchParams.get('q')==='Discovery');
 await discovery.getByRole('button',{name:'전체 공개 검색',exact:true}).click();const data=await (await initial).json();expect(data.items.length).toBeGreaterThan(0);expect(data.nextOffset).not.toBeNull();
 await discovery.getByRole('button',{name:'공개 검색 표로 보기',exact:true}).click();
 const results=discovery.getByRole('region',{name:'공개 통합 검색 결과'}),table=results.getByRole('table',{name:/공개 검색 결과 표/});
 await expect(table.getByRole('button',{name:/^공개 표 자료 열기:/})).toHaveCount(data.items.length);
 await discovery.getByRole('textbox',{name:'전체 공개 지식 검색',exact:true}).fill('Changed form is not the applied query');
 const next=page.waitForResponse(r=>r.url().includes(`/api/public/spaces/${space}/search?`)&&new URL(r.url()).searchParams.get('offset')===String(data.nextOffset));
 await results.getByRole('button',{name:'검색 다음 페이지',exact:true}).click();const nextResponse=await next;
 expect(new URL(nextResponse.url()).searchParams.get('q')).toBe('Discovery');expect(new URL(nextResponse.url()).searchParams.get('kind')).toBe('CONCEPT');
 await expect(results).toContainText('적용된 검색어: Discovery');

 await discovery.getByRole('textbox',{name:'전체 공개 지식 검색',exact:true}).fill('Discovery literal');await discovery.getByLabel('공개 검색 종류').selectOption('CLAIM');
 const literal=page.waitForResponse(r=>r.url().includes(`/api/public/spaces/${space}/search?`)&&new URL(r.url()).searchParams.get('q')==='Discovery literal');
 await discovery.getByRole('button',{name:'전체 공개 검색',exact:true}).click();const literalData=await (await literal).json();
 const row=literalData.items.find((v:any)=>v.content.objectType==='STRING');expect(row).toBeTruthy();
 const tr=table.getByRole('row').filter({has:page.getByRole('button',{name:'공개 표 자료 열기: '+row.title,exact:true})});
 await expect(tr.locator('td').nth(1)).toContainText(row.content.objectValue);
 expect((await tr.locator('td').nth(1).textContent())!.endsWith(row.content.objectValue)).toBe(true);
 await page.setViewportSize({width:390,height:844});await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
 const scroll=results.getByRole('region',{name:'공개 검색 결과 표 가로 스크롤'});await scroll.scrollIntoViewIfNeeded();await scroll.focus();await scroll.press('ArrowRight');
 await expect.poll(()=>scroll.evaluate(el=>el.scrollLeft)).toBeGreaterThan(0);
 await scroll.evaluate(el=>{el.scrollLeft=0});await table.locator('caption').scrollIntoViewIfNeeded();
 await page.screenshot({path:'/tmp/graph-ui-results/public-tables-mobile.png',animations:'disabled'});
 const audit=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa','wcag22aa','best-practice']).analyze();
 console.log('PUBLIC_TABLE_ACCESSIBILITY='+JSON.stringify(audit.violations.map(v=>({id:v.id,count:v.nodes.length,targets:v.nodes.map(n=>n.target)}))));expect(audit.violations.map(v=>({id:v.id,count:v.nodes.length,targets:v.nodes.map(n=>n.target)}))).toEqual([]);expect(writes).toEqual([]);
 console.log('PASS real canonical graph/search table rows, loaded-page sorting, pinned paging, literal UUID, keyboard/mobile and no public writes');
});

test('late public search responses cannot enter another domain table and failed reads clear the table',async({page})=>{
 const {space,spaces,discovery}=await start(page);let release:()=>void=()=>{},seen:()=>void=()=>{};
 const gate=new Promise<void>(r=>release=r),started=new Promise<void>(r=>seen=r);
 await page.route(`**/api/public/spaces/${space}/search?*`,async route=>{const response=await route.fetch();seen();await gate;await route.fulfill({response}).catch(()=>{})});
 await discovery.getByRole('textbox',{name:'전체 공개 지식 검색',exact:true}).fill('Discovery');await discovery.getByRole('button',{name:'전체 공개 검색',exact:true}).click();await started;
 await page.getByLabel('공개 분야',{exact:true}).selectOption(spaces.find(s=>s.spaceId!==space)!.spaceId);release();
 await expect(discovery.getByRole('group',{name:'공개 지식 관계 그래프'})).toBeVisible();await expect(discovery.getByRole('region',{name:'공개 통합 검색 결과'})).toHaveCount(0);
 await page.unroute(`**/api/public/spaces/${space}/search?*`);
 await page.route('**/api/public/spaces/*/graph?*',r=>r.fulfill({status:503,json:{error:'unavailable'}}));
 await discovery.getByRole('button',{name:'공개 그래프 새로고침',exact:true}).click();await expect(discovery).toContainText('공개 그래프를 불러오지 못했습니다');await expect(discovery.getByRole('table')).toHaveCount(0);
});
