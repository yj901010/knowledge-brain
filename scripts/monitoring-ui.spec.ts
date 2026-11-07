import {test,expect} from '@playwright/test';
import {readFileSync} from 'node:fs';
test('operator HTTPS login and provisioned dashboard render',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('https://localhost:8446/login');
 await page.locator('input[name="user"]').fill('brain-admin');
 await page.locator('input[name="password"]').fill(readFileSync('/admin/password','utf8').trim());
 await page.locator('button[type="submit"]').click();
 await expect(page).not.toHaveURL(/\/login/,{timeout:45000});
 await page.goto('https://localhost:8446/d/brain-platform');
 await expect(page.getByText('Ready applications /14',{exact:true})).toBeVisible({timeout:45000});
 await expect(page.getByText('Running Outbox connectors /10',{exact:true})).toBeVisible();
 await expect(page.getByText('Host memory headroom',{exact:true})).toBeVisible();
 await page.waitForTimeout(5000);
 const dashboard=await page.evaluate(async()=>{const r=await fetch('/api/dashboards/uid/brain-platform');return r.json()});
 expect(dashboard.dashboard.panels).toHaveLength(18);expect(errors).toEqual([]);
 console.log('MONITORING_UI_PNG='+(await page.screenshot({fullPage:false})).toString('base64'));
 console.log('MONITORING_UI_VERIFIED: trusted HTTPS, actual operator login and18 provisioned panels');
});
