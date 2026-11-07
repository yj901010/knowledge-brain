import {test,expect,type Page,type Locator} from '@playwright/test';
import {randomBytes} from 'node:crypto';

const title='😀'.repeat(200),changedTitle='🧠'.repeat(200),tooLong='a'.repeat(201);
const key=()=>randomBytes(16).toString('hex');
async function call(page:Page,path:string,method='GET',body?:unknown,idempotency?:string,etag?:string){return page.evaluate(async({path,method,body,idempotency,etag})=>{
 const r=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:{...(body===undefined?{}:{'Content-Type':'application/json'}),...(idempotency?{'Idempotency-Key':idempotency}:{}),...(etag?{'If-Match':etag}:{})},body:body===undefined?undefined:JSON.stringify(body)});
 return {status:r.status,body:await r.json().catch(()=>null),etag:r.headers.get('ETag')};
},{path,method,body,idempotency,etag})}
async function register(page:Page){
 await page.goto('/graph');await page.getByRole('button',{name:'내 개인 공간 시작',exact:true}).click();await expect(page.getByRole('button',{name:'로그인 · 이 공간 연결',exact:true})).toBeVisible({timeout:90000});
 const personal=(await call(page,'/api/spaces/personal')).body.spaceId;
 await page.getByRole('button',{name:'로그인 · 이 공간 연결',exact:true}).click();await page.getByRole('link',{name:'Register',exact:true}).click();
 const name='unicode-public-'+Date.now()+'-'+randomBytes(4).toString('hex'),password=randomBytes(24).toString('base64url')+'aA9!';
 for(const [id,value] of [['username',name],['email',name+'@example.invalid'],['firstName','Unicode'],['lastName','Validation'],['password',password],['password-confirm',password]])if(await page.locator('#'+id).count())await page.locator('#'+id).fill(value);
 await page.getByRole('button',{name:'Register',exact:true}).click();await expect(page.getByRole('button',{name:'로그아웃',exact:true})).toBeVisible({timeout:90000});
 await expect.poll(async()=>(await call(page,'/api/spaces/'+personal)).status,{timeout:90000}).toBe(200);return personal;
}
async function fieldBoundary(field:Locator,submit:Locator,value=title){
 await field.fill(tooLong);await expect(field).toHaveValue(tooLong);await expect(submit).toBeDisabled();
 await field.fill(value);await expect(field).toHaveValue(value);await expect(submit).toBeEnabled();
}
async function publicDomain(page:Page,area:string){
 const space=(await call(page,'/api/spaces/public')).body.items.find((s:any)=>s.domain==='DEVELOPMENT').spaceId;
 await page.goto('/graph/knowledge#knowledge-work='+area);const picker=page.getByLabel('공개 분야',{exact:true});await expect(picker).toBeEnabled();await picker.selectOption(space);await expect(picker).toBeEnabled();return space;
}

test('real registered Group titles accept200 code points in all five writers and reject201 without writes',async({page})=>{
 test.setTimeout(420000);const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));const personal=await register(page);
 const note=(await call(page,'/api/spaces/'+personal+'/notes','POST',{title:'Unicode private sentinel',markdown:'Private original remains unchanged'})).body;
 await page.goto('/graph/groups');const groupSubmit=page.getByRole('button',{name:'비공개 그룹 만들기',exact:true});
 let writes=0;page.on('request',r=>{if(r.url().includes('/api/groups')&&!['GET','HEAD','OPTIONS'].includes(r.method()))writes++});
 await fieldBoundary(page.getByLabel('그룹 이름',{exact:true}),groupSubmit);expect(writes).toBe(0);
 const created=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/api/groups');await groupSubmit.click();const gr=await created;expect(gr.status()).toBe(200);const g=await gr.json();expect(g.title).toBe(title);expect((await call(page,'/api/groups/'+g.groupId)).body.title).toBe(title);
 expect((await call(page,'/api/groups','POST',{title:tooLong,description:''},key())).status).toBe(400);
 const board=page.getByRole('region',{name:'그룹 작업판',exact:true}),area=board.getByLabel('그룹 작업 선택',{exact:true});await expect(area).toBeEnabled();await area.selectOption('studies');
 const studies=board.getByRole('region',{name:'그룹 스터디',exact:true}),studyForm=studies.getByRole('form',{name:'새 그룹 스터디',exact:true}),studySubmit=studyForm.getByRole('button',{name:'스터디 만들기',exact:true});
 const baseline=writes;await fieldBoundary(studyForm.getByLabel('새 스터디 이름',{exact:true}),studySubmit);expect(writes).toBe(baseline);
 const studyRoot='/api/groups/'+g.groupId+'/studies',studyResponse=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname===studyRoot);await studySubmit.click();const sr=await studyResponse;expect(sr.status()).toBe(201);const study=await sr.json();expect(study.title).toBe(title);
 const studyDetail=studies.getByRole('region',{name:'그룹 스터디 상세',exact:true}),studyEdit=studyDetail.getByRole('button',{name:'스터디 정보 저장',exact:true});
 await fieldBoundary(studyDetail.getByLabel('스터디 이름 수정',{exact:true}),studyEdit,changedTitle);
 const studyChanged=page.waitForResponse(r=>r.request().method()==='PUT'&&new URL(r.url()).pathname===studyRoot+'/'+study.studyId);await studyEdit.click();expect((await (await studyChanged).json()).title).toBe(changedTitle);
 await expect(studyDetail.getByLabel('스터디 이름 수정',{exact:true})).toHaveValue(changedTitle);
 const assignments=studyDetail.getByRole('region',{name:'스터디 과제',exact:true}),assignmentForm=assignments.getByRole('form',{name:'새 스터디 과제',exact:true}),assignmentSubmit=assignmentForm.getByRole('button',{name:'과제 만들기',exact:true});
 await fieldBoundary(assignmentForm.getByLabel('새 과제 이름',{exact:true}),assignmentSubmit);
 const assignmentRoot=studyRoot+'/'+study.studyId+'/assignments',assignmentResponse=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname===assignmentRoot);await assignmentSubmit.click();const ar=await assignmentResponse;expect(ar.status()).toBe(201);const assignment=await ar.json();expect(assignment.title).toBe(title);
 const assignmentDetail=assignments.getByRole('region',{name:'스터디 과제 상세',exact:true}),assignmentEdit=assignmentDetail.getByRole('button',{name:'과제 정보 저장',exact:true});await fieldBoundary(assignmentDetail.getByLabel('과제 이름 수정',{exact:true}),assignmentEdit,changedTitle);
 const assignmentChanged=page.waitForResponse(r=>r.request().method()==='PUT'&&new URL(r.url()).pathname===assignmentRoot+'/'+assignment.assignmentId);await assignmentEdit.click();expect((await (await assignmentChanged).json()).title).toBe(changedTitle);
 const discussions=studyDetail.getByRole('region',{name:'스터디 토론',exact:true}),discussionForm=discussions.getByRole('form',{name:'새 토론',exact:true}),discussionSubmit=discussionForm.getByRole('button',{name:'토론 만들기',exact:true}),discussionTitle=discussionForm.getByLabel('새 토론 제목',{exact:true});
 await discussionTitle.fill(tooLong);await discussionForm.getByRole('checkbox').check();await expect(discussionSubmit).toBeDisabled();await expect(discussionTitle).toHaveValue(tooLong);
 await discussionTitle.fill(title);await discussionForm.getByRole('checkbox').check();await expect(discussionTitle).toHaveValue(title);await expect(discussionSubmit).toBeEnabled();
 const discussionRoot=studyRoot+'/'+study.studyId+'/discussions',discussionResponse=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname===discussionRoot);await discussionSubmit.click();const dr=await discussionResponse;expect(dr.status()).toBe(201);const discussion=await dr.json();expect(discussion.title).toBe(title);
 await area.selectOption('courses');const courses=board.getByRole('region',{name:'그룹 코스',exact:true}),courseForm=courses.getByRole('form',{name:'새 그룹 코스',exact:true}),courseSubmit=courseForm.getByRole('button',{name:'코스 만들기',exact:true});await fieldBoundary(courseForm.getByLabel('새 코스 이름',{exact:true}),courseSubmit);
 const courseRoot='/api/groups/'+g.groupId+'/courses',courseResponse=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname===courseRoot);await courseSubmit.click();const cr=await courseResponse;expect(cr.status()).toBe(201);const course=await cr.json();expect(course.title).toBe(title);
 const courseDetail=courses.getByRole('region',{name:'그룹 코스 상세',exact:true}),courseEdit=courseDetail.getByRole('button',{name:'코스 정보 저장',exact:true});await fieldBoundary(courseDetail.getByLabel('코스 이름 수정',{exact:true}),courseEdit,changedTitle);
 const courseChanged=page.waitForResponse(r=>r.request().method()==='PUT'&&new URL(r.url()).pathname===courseRoot+'/'+course.courseId);await courseEdit.click();expect((await (await courseChanged).json()).title).toBe(changedTitle);
 for(const path of [studyRoot,assignmentRoot,discussionRoot,courseRoot])expect((await call(page,path,'POST',{title:tooLong,description:''},key())).status).toBe(400);
 expect((await call(page,studyRoot+'/'+study.studyId)).body.title).toBe(changedTitle);expect((await call(page,assignmentRoot+'/'+assignment.assignmentId)).body.title).toBe(changedTitle);expect((await call(page,discussionRoot+'/'+discussion.discussionId)).body.title).toBe(title);expect((await call(page,courseRoot+'/'+course.courseId)).body.title).toBe(changedTitle);
 expect((await call(page,'/api/spaces/'+personal+'/notes/'+note.noteId)).body).toEqual(note);expect(errors).toEqual([]);expect(await page.evaluate(()=>localStorage.length+sessionStorage.length)).toBe(0);
 console.log('UNICODE_GROUP_VERIFIED='+JSON.stringify({groupId:g.groupId,codePoints:200,group:true,study:true,course:true,assignment:true,discussion:true,metadataVersions:true,tooLongDenied:true,privateSentinelUnchanged:true}));
});

test('real Development proposals retain200emoji titles and full rationale/source text without canonical publication',async({page})=>{
 test.setTimeout(300000);await register(page);const space=await publicDomain(page,'contributions'),base='/api/public/spaces/'+space;
 const concept=page.getByRole('region',{name:'공개 개념 기여 작업실',exact:true});await concept.getByRole('button',{name:'새 공개 개념 제안',exact:true}).click();
 const rationale='🧠'.repeat(2000),conceptSubmit=concept.getByRole('button',{name:'공개 제안 제출',exact:true}),conceptReason=concept.getByRole('textbox',{name:'제안 사유',exact:true});await conceptReason.fill(rationale);await expect(conceptReason).toHaveValue(rationale);
 await fieldBoundary(concept.getByLabel('공개 개념 제목',{exact:true}),conceptSubmit);await conceptReason.fill('a'.repeat(2001));await expect(conceptSubmit).toBeDisabled();await conceptReason.fill(rationale);await expect(conceptSubmit).toBeEnabled();
 const contributionResponse=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname===base+'/contributions');await conceptSubmit.click();const cp=await contributionResponse;expect(cp.status()).toBe(201);const contribution=await cp.json();expect(contribution.title).toBe(title);expect(contribution.rationale).toBe(rationale);
 expect((await call(page,base+'/contributions/'+contribution.contributionId)).body).toMatchObject({state:'SUBMITTED',title,rationale});expect((await call(page,base+'/concepts/'+contribution.conceptId)).status).toBe(404);
 expect((await call(page,base+'/contributions','POST',{conceptId:null,baseVersion:0,title:tooLong,description:'',rationale:'Too long title must fail'})).status).toBe(400);
 await publicDomain(page,'verification');const work=page.getByRole('region',{name:'공개 지식 검토 작업실',exact:true});await work.getByRole('button',{name:'출처 제안',exact:true}).click();let recordPosts=0;page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname===base+'/record-proposals')recordPosts++});
 // The12KB aggregate content ceiling still applies even when each field's CP count is valid.
 const citation='😀'.repeat(2001)+'a'.repeat(1999),license='a'.repeat(1000),longLicense='🧠'.repeat(1000),sourceTitle='📖'.repeat(200),sourceSubmit=work.getByRole('button',{name:'공개 자료 제안 제출',exact:true});
 expect(new TextEncoder().encode(JSON.stringify({title:sourceTitle,url:'https://example.invalid/unicode-fixture',citation,licenseNote:license})).length).toBeLessThanOrEqual(12000);
 await work.getByRole('textbox',{name:'공개 제안 사유',exact:true}).fill(rationale);await work.getByLabel('출처 제목',{exact:true}).fill(sourceTitle);await work.getByLabel('원문 주소',{exact:true}).fill('https://example.invalid/unicode-fixture');await work.getByLabel('서지 정보',{exact:true}).fill(citation);await work.getByLabel('이용·라이선스 근거',{exact:true}).fill(license);await work.getByLabel('내용·사유·검토 이력을 공개함을 확인합니다',{exact:true}).check();
 for(const [label,value] of [['서지 정보',citation],['이용·라이선스 근거',license],['출처 제목',sourceTitle],['공개 제안 사유',rationale]])await expect(work.getByRole('textbox',{name:label,exact:true})).toHaveValue(value);
 await fieldBoundary(work.getByLabel('공개 제안 제목',{exact:true}),sourceSubmit);await work.getByLabel('출처 제목',{exact:true}).fill(tooLong);await expect(sourceSubmit).toBeDisabled();await work.getByLabel('출처 제목',{exact:true}).fill(sourceTitle);
 await work.getByLabel('이용·라이선스 근거',{exact:true}).fill('a'.repeat(1001));await expect(sourceSubmit).toBeDisabled();await work.getByLabel('이용·라이선스 근거',{exact:true}).fill(license);
 const beforeOversize=recordPosts;await work.getByLabel('서지 정보',{exact:true}).fill('😀'.repeat(4000));await expect(work.getByLabel('서지 정보',{exact:true})).toHaveValue('😀'.repeat(4000));await expect(sourceSubmit).toBeDisabled();await sourceSubmit.evaluate(button=>(button as HTMLButtonElement).form!.requestSubmit());await page.evaluate(()=>new Promise<void>(resolve=>requestAnimationFrame(()=>resolve())));expect(recordPosts).toBe(beforeOversize);
 expect((await call(page,base+'/record-proposals','POST',{kind:'SOURCE',operation:'CREATE',recordId:null,baseVersion:0,title,content:{title:sourceTitle,url:'https://example.invalid/unicode-fixture',citation:'😀'.repeat(4000),licenseNote:license},rationale:'Aggregate content remains bounded'})).status).toBe(400);
 await work.getByLabel('서지 정보',{exact:true}).fill(citation);await expect(sourceSubmit).toBeEnabled();
 const sourceResponse=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname===base+'/record-proposals');await sourceSubmit.click();const sp=await sourceResponse;expect(sp.status()).toBe(201);const proposal=await sp.json();expect(proposal).toMatchObject({kind:'SOURCE',state:'SUBMITTED',title,rationale,content:{title:sourceTitle,citation,licenseNote:license}});
 expect((await call(page,base+'/record-proposals/'+proposal.proposalId)).body.content).toMatchObject({title:sourceTitle,citation,licenseNote:license});expect((await call(page,base+'/records/'+proposal.recordId)).status).toBe(404);
 expect((await call(page,base+'/record-proposals','POST',{kind:'SOURCE',operation:'CREATE',recordId:null,baseVersion:0,title:tooLong,content:proposal.content,rationale:'Overlong title denied'})).status).toBe(400);
 expect((await call(page,base+'/record-proposals/'+proposal.proposalId+'/review','POST',{decision:'APPROVED',reason:'No reviewer authority'},undefined,'"kp1"')).status).toBe(403);
 await work.getByRole('button',{name:'출처 제안',exact:true}).click();await work.getByLabel('공개 제안 제목',{exact:true}).fill(title);await work.getByLabel('출처 제목',{exact:true}).fill(sourceTitle);await work.getByLabel('원문 주소',{exact:true}).fill('https://example.invalid/unicode-license-fixture');await work.getByLabel('서지 정보',{exact:true}).fill('Short original citation');await work.getByLabel('이용·라이선스 근거',{exact:true}).fill(longLicense);await work.getByRole('textbox',{name:'공개 제안 사유',exact:true}).fill(rationale);await work.getByLabel('내용·사유·검토 이력을 공개함을 확인합니다',{exact:true}).check();await expect(work.getByLabel('이용·라이선스 근거',{exact:true})).toHaveValue(longLicense);await expect(sourceSubmit).toBeEnabled();
 const licenseResponse=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname===base+'/record-proposals');await sourceSubmit.click();const lp=await licenseResponse;expect(lp.status()).toBe(201);const licenseProposal=await lp.json();expect(licenseProposal.content.licenseNote).toBe(longLicense);expect((await call(page,base+'/records/'+licenseProposal.recordId)).status).toBe(404);
 console.log('UNICODE_PUBLIC_PROPOSALS_VERIFIED='+JSON.stringify({spaceId:space,contributionId:contribution.contributionId,sourceProposalIds:[proposal.proposalId,licenseProposal.proposalId],titleCodePoints:200,rationaleCodePoints:2000,citationCodePoints:4000,licenseCodePoints:1000,aggregateContentBoundPreserved:true,canonicalWrites:false,medicalWrites:false,reviewerGranted:false}));
});

test('real public128 and canonical64 code-point searches preserve complete encoded Unicode queries',async({page})=>{
 test.setTimeout(240000);await register(page);const space=await publicDomain(page,'discovery'),base='/api/public/spaces/'+space,search=page.getByLabel('전체 공개 지식 검색',{exact:true}),submit=page.getByRole('button',{name:'전체 공개 검색',exact:true});
 const query='😀'.repeat(128);await search.fill('a'.repeat(129));await expect(search).toHaveValue('a'.repeat(129));await expect(submit).toBeDisabled();await search.fill(query);await expect(search).toHaveValue(query);await expect(submit).toBeEnabled();
 const response=page.waitForResponse(r=>new URL(r.url()).pathname===base+'/search'&&new URL(r.url()).searchParams.get('q')===query);await submit.click();const result=await response;expect(result.status()).toBe(200);expect(new URL(result.request().url()).searchParams.get('q')).toBe(query);expect(Array.isArray((await result.json()).items)).toBe(true);
 expect((await call(page,base+'/search?'+new URLSearchParams({q:'a'.repeat(129),limit:'20'}))).status).toBe(400);
 const curation=page.locator('details[aria-label="출처 확인 초안 작성"]');await curation.locator(':scope > summary').click();await curation.getByRole('button',{name:/^PostgreSQL 트랜잭션은/}).click();
 const reference=curation.getByRole('group',{name:'용어 개념',exact:true}),canonicalQuery='🧠'.repeat(64),canonicalInput=reference.getByLabel('용어 개념 검색어',{exact:true}),canonicalSubmit=reference.getByRole('button',{name:'현재 용어 개념 찾기',exact:true});
 await canonicalInput.fill('a'.repeat(65));await expect(canonicalInput).toHaveValue('a'.repeat(65));await expect(canonicalSubmit).toBeDisabled();await canonicalInput.fill(canonicalQuery);await expect(canonicalInput).toHaveValue(canonicalQuery);await expect(canonicalSubmit).toBeEnabled();
 const canonicalResponse=page.waitForResponse(r=>new URL(r.url()).pathname===base+'/explore'&&new URL(r.url()).searchParams.get('q')===canonicalQuery);await canonicalSubmit.click();const canonical=await canonicalResponse;expect(canonical.status()).toBe(200);expect(new URL(canonical.request().url()).searchParams.get('q')).toBe(canonicalQuery);expect(Array.isArray((await canonical.json()).items)).toBe(true);
 expect((await call(page,base+'/explore?'+new URLSearchParams({q:'a'.repeat(65),limit:'20'}))).status).toBe(400);
 await publicDomain(page,'verification');const work=page.getByRole('region',{name:'공개 지식 검토 작업실',exact:true}),catalogSearch=work.getByLabel('공개 지식 검색',{exact:true}),catalogSubmit=work.getByRole('button',{name:'검색',exact:true});await catalogSearch.fill('a'.repeat(65));await expect(catalogSubmit).toBeDisabled();await catalogSearch.fill(canonicalQuery);await expect(catalogSearch).toHaveValue(canonicalQuery);
 const catalogResponse=page.waitForResponse(r=>new URL(r.url()).pathname===base+'/records'&&new URL(r.url()).searchParams.get('q')===canonicalQuery);await catalogSubmit.click();const catalog=await catalogResponse;expect(catalog.status()).toBe(200);expect(new URL(catalog.request().url()).searchParams.get('q')).toBe(canonicalQuery);
 console.log('UNICODE_PUBLIC_SEARCH_VERIFIED='+JSON.stringify({spaceId:space,projectedQueryCodePoints:128,canonicalQueryCodePoints:64,uriRoundTrips:true,beyondLimitDenied:true,publicWrites:false}));
});
