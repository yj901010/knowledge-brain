# 폐기한 노트 보관함

`/graph`의 **내 노트 목록 → 폐기한 노트 열기**에서 폐기한 일반 노트와 Daily Note를
다시 찾는다. 목록은 생성 ID 순서로 20개씩, 선택한 노트의 수정 이력은 최신부터
5개씩 조회한다. 같은 제목도 Note ID로 구별한다. 각 버전의 제목·Markdown,
수정 ID·수정자·시각과 ACTIVE/RETIRED 상태를 확인할 수 있다.

본문은 원문으로 시작한다. **폐기 노트 이력 미리보기**는 기존 Markdown 렌더러를
사용하며 HTML 실행과 자동 외부 이미지 로드를 하지 않는다. 미리보기의 링크는
사용자가 직접 누를 때만 연다. 보관함 조회는 현재 노트 편집 초안을 변경하지 않는다.
보관함 닫기·새로고침·노트 선택 변경 시 기존 조회 요청을 취소하고 늦은 응답을 버린다.
목록이나 이력 조회 실패 시 남은 결과를 비우고 명시적으로 다시 조회한다.

이번 기능은 읽기 전용이다. 폐기 상태, Daily Note 날짜·시간대 결합, 과거 이력과
첨부 해제 결과를 바꾸지 않는다. 활성 노트의 과거 내용을 새 버전으로 저장하는
[이전 내용 복원](note-restore.md)은 별도 기능이며 폐기 노트를 활성화하지 않는다.

## API와 아키텍처

AGENTS.md와 PROJECT.md를 확인했으며 기존 결정과 충돌하지 않는다. Content는
자기 PostgreSQL의 Note만 조회하고, Gateway는 허용된 URL을 전달한다. 타 서비스 DB
조회나 신규 동기 집계, 데이터 공개, 이벤트·의존성 변경은 없다.

`GET /api/spaces/{spaceId}/notes?state=RETIRED`가 기존 요약 페이지 형태를 반환한다.
생략한 state는 ACTIVE이며 명시적인 ACTIVE도 같은 결과다. PRIVATE 및 현재 권한이
있는 Space의 일치 상태만 선택한다. `after`는 배타적 UUIDv7 커서, limit은 1..100,
기본 20이다. 알 수 없는 키, 중복 키, 빈 값과 잘못된 상태는 거절한다.

state 필터는 정확히 위 collection GET에만 허용한다. 개별 노트, 수정 이력, 쓰기,
검색·그래프·Journal에 적용되지 않는다. 폐기 노트의 item GET/PUT/DELETE는 계속
404이며, 기존 `/{noteId}/revisions`로 보존된 이력을 읽는다. 모든 요청은 Space의
최신 권한을 확인하며 `Cache-Control: no-store`를 반환한다. 401/403/404가 오면
개인 이력과 상위 개인 화면을 비운다. 브라우저 저장소를 사용하지 않는다.

`V12__retired_note_discovery.sql`은 `(space_id,note_id)`의 PRIVATE/RETIRED 부분 인덱스만
추가한다. 기존 데이터·수정 이력·Outbox를 변환하지 않는다. 목록 읽기는 이벤트를
생성하지 않는다. 기존 HTTP/trace 추적과 함께
`brain.content.note.reads{type=list|retired_list}` 카운터로 조회를 구분한다.

## 검증과 배포

- `Run-BackendValidation.py --content --content-kafka`: 실제 PostgreSQL 및 Kafka,
  상태별 페이지·재시작·권한·Daily 결합·불변 이력·읽기 무변경 검증.
- `Run-BackendValidation.py --gateway`: 정확한 필터 경로, 잘못된 쿼리 차단과 기존 경로.
- `Run-GraphWebValidation.py --retired-notes-regressions`: 새 보관함 3개 시나리오,
  이전 내용 복원 6개 및 편집·폐기·목록·Journal·Markdown 회귀 검사.
- Content와 Gateway의 정확한 로컬 이미지를 두 노드에 가져온 후 Helm으로 배포.

실제 결과는 [retired-notes-validation.txt](retired-notes-validation.txt)에 기록한다.
