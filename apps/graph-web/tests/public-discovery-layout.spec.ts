import {test,expect} from '@playwright/test';
test('public discovery remains single after repeated catalog refresh and domain changes',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));await page.goto('/graph');await page.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();await expect(page.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible();await page.goto('/graph/knowledge#knowledge-work=all');await expect(page.locator('.public-discovery')).toBeVisible();
 async function stable(){await page.evaluate(()=>new Promise<void>(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve()))));await expect(page.locator('.public-discovery')).toHaveCount(1);await expect(page.locator('input[aria-label="전체 공개 지식 검색"]')).toHaveCount(1);await expect(page.getByRole('region',{name:'공개 지식 검토 작업실',exact:true})).toHaveCount(1);}
 for(let i=0;i<3;i++){const response=page.waitForResponse(r=>r.url().endsWith('/api/spaces/public')&&r.status()===200);await page.getByRole('button',{name:'새로고침',exact:true}).click();await response;await stable();}
 const picker=page.getByLabel('공개 분야',{exact:true});const ids=await picker.locator('option').evaluateAll(nodes=>nodes.map(n=>(n as HTMLOptionElement).value));for(const id of ids){await picker.selectOption(id);await stable();}expect(errors).toEqual([]);
});
