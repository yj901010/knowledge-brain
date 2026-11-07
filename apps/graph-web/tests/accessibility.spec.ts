import {test,expect,type Page} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {randomBytes} from 'node:crypto';

const tags=['wcag2a','wcag2aa','wcag21a','wcag21aa','wcag22aa','best-practice'];
async function scan(page:Page,name:string){
 // Inspect the settled screen. Theme changes interpolate button backgrounds for
 // 140ms; the audit must not compare their transient old/new-theme mixture.
 await page.evaluate(async()=>{
  await new Promise<void>(resolve=>requestAnimationFrame(()=>resolve()));
  await Promise.all(document.getAnimations().filter(a=>a instanceof CSSTransition).map(a=>a.finished.catch(()=>{})));
 });
 const result=await new AxeBuilder({page}).withTags(tags).analyze();
 // Never log DOM HTML, input values, cookies or complete accessibility trees.
 const concise=(rows:typeof result.violations)=>rows.map(r=>({id:r.id,impact:r.impact,targets:r.nodes.map(n=>n.target).slice(0,12),count:r.nodes.length,
  diagnostics:r.nodes.slice(0,3).map(n=>({target:n.target,checks:[...n.any,...n.all].map(c=>({id:c.id,data:c.data}))}))}));
 const proof={name,violations:concise(result.violations),incomplete:concise(result.incomplete),passes:result.passes.length};
 console.log('ACCESSIBILITY_AUDIT='+JSON.stringify(proof));return proof;
}
async function start(page:Page){
 await page.goto('/graph');await page.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();
 await expect(page.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible({timeout:90000});
}

test('four workspace routes, themes, mobile drawer and command dialog accessibility',async({page})=>{
 test.setTimeout(360000);await start(page);const failures:unknown[]=[];
 for(const viewport of [{width:1440,height:1000},{width:390,height:844}]){
  await page.setViewportSize(viewport);
  for(const theme of ['dark','light']){
   for(const route of ['/graph','/graph/knowledge#knowledge-work=all','/graph/roadmaps#roadmap-work=all','/graph/groups']){
    const ready=page.waitForResponse(r=>r.url().endsWith('/api/identity/guest-session')&&r.status()===200);
    await page.goto(route);await ready;await expect(page.locator('h1')).toBeVisible();
    if((await page.locator('html').getAttribute('data-theme')??'dark')!==theme){
     if(viewport.width<600)await page.getByRole('button',{name:'탐색 메뉴 열기',exact:true}).click();
     await page.getByRole('button',{name:theme==='light'?'라이트 테마 변경':'다크 테마 변경',exact:true}).click();
     await expect(page.locator('html')).toHaveAttribute('data-theme',theme);
     if(viewport.width<600)await page.getByRole('button',{name:'메뉴 닫기',exact:true}).click();
    }
    await page.evaluate(()=>new Promise<void>(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve()))));
    const proof=await scan(page,`${route}:${theme}:${viewport.width}`);failures.push(...proof.violations);
    expect.soft(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'page reflow '+route).toBe(true);
   }
  }
 }
 await page.getByRole('button',{name:'빠른 이동 Ctrl K 열기',exact:true}).click();
 await expect(page.getByRole('dialog',{name:'빠른 이동',exact:true})).toBeVisible();
 failures.push(...(await scan(page,'command-dialog')).violations);await page.keyboard.press('Escape');
 await page.getByRole('button',{name:'탐색 메뉴 열기',exact:true}).click();
 failures.push(...(await scan(page,'mobile-drawer')).violations);
 const drawer=page.getByRole('dialog',{name:'작업 공간 탐색',exact:true});
 const first=drawer.getByRole('link',{name:'knowledgebrain 홈',exact:true}),last=drawer.getByRole('button',{name:'메뉴 닫기',exact:true});
 await expect(first).toBeFocused();await last.focus();await page.keyboard.press('Tab');await expect(first).toBeFocused();
 await page.keyboard.press('Shift+Tab');await expect(last).toBeFocused();
 await page.keyboard.press('Escape');
 await expect(page.getByRole('button',{name:'탐색 메뉴 열기',exact:true})).toBeFocused();
 await expect(page.locator('.shell-sidebar')).toHaveAttribute('inert','');
 await page.setViewportSize({width:1440,height:1000});await page.getByRole('button',{name:'사이드바 접기',exact:true}).click();
 failures.push(...(await scan(page,'collapsed-sidebar')).violations);
 expect(failures).toEqual([]);
});

test('registered owner Group forms and public proposal drafts are accessible without publishing',async({page})=>{
 test.setTimeout(240000);await start(page);await page.getByRole('button',{name:'로그인 · 이 공간 연결',exact:true}).click();
 await page.getByRole('link',{name:'Register',exact:true}).click();
 const user='a11y-'+Date.now()+'-'+randomBytes(4).toString('hex'),password=randomBytes(24).toString('base64url')+'aA9!';
 for(const [id,value] of [['username',user],['email',user+'@example.invalid'],['firstName','Accessibility'],['lastName','Validation'],['password',password],['password-confirm',password]]){
  const field=page.locator('#'+id);if(await field.count())await field.fill(value);
 }
 await page.getByRole('button',{name:'Register',exact:true}).click();await expect(page.getByRole('button',{name:'로그아웃',exact:true})).toBeVisible({timeout:90000});
 const writes:string[]=[];page.on('request',r=>{if(r.url().includes('/api/public/')&&r.method()!=='GET')writes.push(r.method())});
 const group=await page.evaluate(async()=>{const r=await fetch('/api/groups',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':crypto.randomUUID().replaceAll('-','')},body:JSON.stringify({title:'Accessibility private group '+Date.now(),description:'Private owner form validation'})});if(!r.ok)throw new Error('Group setup');return r.json()});
 await page.goto('/graph/groups#group-work=studies');await page.getByRole('button',{name:group.title,exact:true}).click();
 const board=page.getByRole('region',{name:'그룹 작업판',exact:true}),area=board.getByRole('combobox',{name:'그룹 작업 선택'});
 await expect(area).toBeEnabled();await expect(board.getByRole('form',{name:'새 그룹 스터디'})).toBeVisible();
 for(const theme of ['dark','light']){
  if((await page.locator('html').getAttribute('data-theme')??'dark')!==theme)await page.getByRole('button',{name:theme==='light'?'라이트 테마 변경':'다크 테마 변경',exact:true}).click();
  for(const value of ['studies','courses','members']){
   await area.selectOption(value);await expect(area).toBeEnabled();
   const result=await scan(page,`registered-group-${value}-${theme}`);expect.soft(result.violations).toEqual([]);
  }
 }
 await page.goto('/graph/knowledge#knowledge-work=contributions');await page.getByRole('button',{name:'새 공개 개념 제안',exact:true}).click();
 await page.getByLabel('공개 개념 제목',{exact:true}).fill('Unsubmitted accessibility draft');
 const draft=await scan(page,'registered-public-concept-draft');expect(draft.violations).toEqual([]);
 await page.goto('/graph/roadmaps#roadmap-work=contributions');await page.getByRole('button',{name:'새 공개 로드맵 제안',exact:true}).click();
 const roadmap=await scan(page,'registered-public-roadmap-draft');expect(roadmap.violations).toEqual([]);
 expect(writes).toEqual([]);console.log('PASS registered private Group and unsubmitted public draft checks; no public submission or authority grant');
});

test('loaded private graph controls appear in the browser accessibility tree and editor retains keyboard focus',async({page})=>{
 await start(page);const personal=await page.evaluate(async()=>(await (await fetch('/api/spaces/personal')).json()).spaceId as string);
 const title='접근성 긴 이름 '+Date.now()+' · 연결 자료의 전체 제목을 키보드와 보조 기술에서 확인합니다';
 for(const [name,markdown] of [[title,'Private accessibility fixture'],['A11y source '+Date.now(),'[['+title+']]']]){
  const status=await page.evaluate(async({personal,name,markdown})=>(await fetch(`/api/spaces/${personal}/notes`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({title:name,markdown})})).status,{personal,name,markdown});expect(status).toBe(201);
 }
 await page.getByRole('button',{name:'새로고침',exact:true}).click();
 const node=page.getByRole('button',{name:'노트 열기: '+title,exact:true});await expect(node).toBeVisible({timeout:90000});
 const client=await page.context().newCDPSession(page);const tree=await client.send('Accessibility.getFullAXTree');
 const exposed=tree.nodes.filter(n=>!n.ignored&&n.role?.value==='button'&&n.name?.value==='노트 열기: '+title);
 console.log('GRAPH_ACCESSIBILITY_EXPOSED_BUTTONS='+exposed.length);expect(exposed).toHaveLength(1);
 await node.focus();await node.press('Space');await expect(node).toHaveAttribute('aria-pressed','true');
 await expect(page.locator('.inspector')).toContainText(title);
 const graph=await scan(page,'loaded-private-graph');expect(graph.violations).toEqual([]);
 const add=page.getByRole('button',{name:'+ 새 노트',exact:true});await add.click();
 const dialog=page.getByRole('dialog');await expect(dialog.getByLabel('제목',{exact:true})).toBeFocused();
 const editor=await scan(page,'note-editor-dialog');expect(editor.violations).toEqual([]);
 await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);await expect(add).toBeFocused();
 await add.click();await dialog.getByLabel('제목',{exact:true}).fill('Clear this private draft after revoked identity');
 expect(await page.evaluate(async()=>(await fetch('/api/identity/guest-session/revoke',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).status)).toBe(204);
 await dialog.getByRole('button',{name:'저장',exact:true}).click();await expect(dialog).toHaveCount(0);
 await expect(page.getByRole('button',{name:'내 개인 공간 시작',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();await add.click();
 await expect(dialog.getByLabel('제목',{exact:true})).toHaveValue('');await expect(dialog.getByLabel('Markdown 본문',{exact:true})).toHaveValue('');
});
