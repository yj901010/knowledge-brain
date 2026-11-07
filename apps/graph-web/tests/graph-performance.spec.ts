import {test,expect} from '@playwright/test';

test('bounded graph client layout and camera latency at normal and throttled CPU',async({page})=>{
 test.setTimeout(180000);await page.goto('/graph');await page.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();
 await expect(page.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible({timeout:90000});
 const workers:string[]=[];page.on("worker",w=>workers.push(w.url()));
 const client=await page.context().newCDPSession(page);let fixture:{nodes:unknown[];links:unknown[];truncated:boolean}={nodes:[],links:[],truncated:true};
 await page.route('**/api/spaces/*/graph/notes?*',r=>r.fulfill({status:200,contentType:'application/json',body:JSON.stringify(fixture)}));
 await page.evaluate(()=>{
  const state=window as typeof window&{graphLongTasks:number[];graphObserver:PerformanceObserver};state.graphLongTasks=[];
  state.graphObserver=new PerformanceObserver(list=>state.graphLongTasks.push(...list.getEntries().map(e=>e.duration)));state.graphObserver.observe({entryTypes:['longtask']});
 });
 let round=0;
 try{
  for(const rate of [1,4])for(const size of [50,200]){
   await client.send('Emulation.setCPUThrottlingRate',{rate});
   const nodes=Array.from({length:size},(_,i)=>({noteId:'019a0000-0000-7000-8000-'+(round*512+i).toString(16).padStart(12,'0'),title:'Client graph fixture '+i}));round++;
   const edgeCount=size===200?1000:100;
   const links=Array.from({length:edgeCount},(_,i)=>({linkId:'019b0000-0000-7000-8000-'+(round*2048+i).toString(16).padStart(12,'0'),sourceNoteId:nodes[i%size].noteId,targetNoteId:nodes[(i*13+1)%size].noteId}));fixture={nodes,links,truncated:true};
   await page.evaluate(()=>{(window as typeof window&{graphLongTasks:number[]}).graphLongTasks=[]});
   const began=Date.now();await page.getByRole('button',{name:'새로고침',exact:true}).click();
   const graph=page.locator('svg.kb-graph-svg');await expect(graph.locator('.kb-node')).toHaveCount(size);await expect(graph.locator('.kb-edge')).toHaveCount(edgeCount);
   await page.evaluate(()=>new Promise<void>(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve()))));
   const paintMs=Date.now()-began,tasks=await page.evaluate(()=>(window as typeof window&{graphLongTasks:number[]}).graphLongTasks);
   const before=await graph.getAttribute('viewBox');await graph.focus();const keyStart=Date.now();await graph.press('ArrowRight');await expect(graph).not.toHaveAttribute('viewBox',before!);const cameraMs=Date.now()-keyStart;
   const mode=await graph.locator('..').getAttribute('data-layout-mode');
   console.log('GRAPH_CLIENT_PERFORMANCE='+JSON.stringify({rate,nodes:size,edges:edgeCount,paintMs,cameraMs,maxLongTaskMs:Math.max(0,...tasks),longTaskCount:tasks.length,mode}));
   expect(mode).toBe(size>80?'worker':'inline');if(size===200)expect(Math.max(0,...tasks)).toBeLessThan(225);
   expect(paintMs).toBeLessThan(5000);expect(cameraMs).toBeLessThan(1000);
  }
 }finally{await client.send('Emulation.setCPUThrottlingRate',{rate:1})}
 expect(workers.length).toBeGreaterThanOrEqual(2);expect(workers.every(url=>url.startsWith('https://localhost:8443/graph/'))).toBe(true);
 console.log('Client-only intercepted graph fixture; excludes backend/storage latency and does not certify sustained load');
});

test('bounded graph remains usable when Worker is unavailable',async({page})=>{
 await page.addInitScript(()=>Object.defineProperty(window,'Worker',{value:undefined,writable:true}));
 await page.goto('/graph');await page.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();await expect(page.getByRole('button',{name:'+ 새 노트',exact:true})).toBeVisible({timeout:90000});
 const nodes=Array.from({length:200},(_,i)=>({noteId:'019c0000-0000-7000-8000-'+i.toString(16).padStart(12,'0'),title:'Fallback graph fixture '+i}));
 const links=Array.from({length:1000},(_,i)=>({linkId:'019d0000-0000-7000-8000-'+i.toString(16).padStart(12,'0'),sourceNoteId:nodes[i%200].noteId,targetNoteId:nodes[(i*13+1)%200].noteId}));
 await page.route('**/api/spaces/*/graph/notes?*',r=>r.fulfill({status:200,json:{nodes,links,truncated:true}}));
 await page.getByRole('button',{name:'새로고침',exact:true}).click();const graph=page.locator('svg.kb-graph-svg');
 await expect(graph.locator('.kb-node')).toHaveCount(200);await expect(graph.locator('..')).toHaveAttribute('data-layout-mode','yielding');
 const before=await graph.getAttribute('viewBox');await graph.focus();await graph.press('ArrowRight');await expect(graph).not.toHaveAttribute('viewBox',before!);
 console.log('PASS Worker-unavailable fallback renders the full bounded graph and retains keyboard camera');
});
