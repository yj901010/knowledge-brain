# 태그별 노트 탐색

[태그 이름 검색](metadata-name-search.md)으로 전체 정의에서 원하는 태그를 찾을 수 있다.

`/graph`의 **내 노트 목록 → 태그 탐색 열기**에서 Space의 태그를 조회하고,
태그를 선택해 연결된 활성 노트를 연다. 태그와 노트 목록은 생성 ID 순서로
20개씩 불러온다. 일반 노트와 Daily Note가 함께 표시되며 같은 제목은 Note ID로
구별한다. 선택한 노트를 열면 기존 편집기가 현재 본문과 수정 이력을 조회한다.

태그 탐색 자체는 노트 편집 초안을 변경하지 않는다. 노트 열기는 기존의 노트 선택
동작이다. 태그·노트 목록은 각각 새로고침할 수 있다. 태그 선택 해제·변경,
탐색 닫기와 Space 전환은 진행 중인 요청을 취소하며 늦은 응답은 버린다.
서버 오류나 잘못된 페이지 응답은 해당 결과를 비우고 명시적 재조회를 안내한다.
401/403/404는 개인 화면을 비운다. 브라우저 저장소에 목록을 저장하지 않는다.

연결된 활성 노트가 없는 태그도 유지된다. 태그 이름의 기존 정규화·대소문자 구분은
유지한다. 태그 목록의 활성 노트 개수는 그 목록을 조회한 시점의 값이다. 목록과
연결은 실시간 조회이므로 페이지 사이의 변경은 새로고침으로 확인한다. 태그 연결
해제나 노트 폐기는 다음 조회부터 반영된다. 전역 태그 검색·다중 태그 교집합
필터는 이번 기능 범위에 포함하지 않는다.

## API와 아키텍처

AGENTS.md와 PROJECT.md를 검토했으며 기존 결정과 충돌하지 않는다. Content 서비스의
기존 정본 `content_tag`, `note_tag`, `note`만 조회한다. 다른 서비스의 DB를 읽지
않으며 OpenSearch/그래프 projection에 의존하지 않는다. 신규 의존성, DB migration,
이벤트 형식 변경, 과거 데이터 변환이 없다. 기존 `note_tag_lookup(space_id,tag_id,note_id)`
인덱스를 활용한다.

- `GET /api/spaces/{spaceId}/tags` — 기존 태그 정의와 활성 노트 개수.
- `GET /api/spaces/{spaceId}/tags/{tagId}/notes` — 해당 태그에 연결된 PRIVATE/ACTIVE
  Note의 `{items:[{noteId,title,version,updatedAt}],nextAfter}` 페이지.

모든 페이지는 현재 Space 읽기 권한을 확인하고 `Cache-Control: no-store`를 반환한다.
같은 Space의 알려진 태그에 연결이 없으면 빈 페이지, 미등록·다른 Space의 태그는
404이다. `after`는 배타적 UUIDv7 커서, `limit`은 기본 20, 범위 1..100이다.
태그·속성 정의 및 태그 노트 조회는 `after`, `limit`만 허용하며 중복·빈 값·알 수
없는 query key를 거절한다. Gateway는 조회 본문과 허용되지 않은 쓰기 URL을 차단한다.

읽기는 revision·Outbox를 생성하지 않는다. 기존 trace/HTTP 관측과
`brain.content.metadata.reads{type=tags|property_definitions|tag_notes}` 카운터를 사용하며
Space·태그·노트 ID를 metric label로 넣지 않는다.

## 검증

Content PostgreSQL/Kafka 검증, Gateway 단위·프록시 검증과 실제 로컬 Kubernetes의
브라우저 검증을 사용한다. 새 브라우저 시나리오는 22개 태그·22개 노트 페이지,
Daily Note·같은 제목·빈 태그, 연결 변경·폐기, 초안 유지·모바일 폭, 잘못된 페이지,
늦은 응답·접근 철회를 다룬다. 기존 메타데이터·노트 목록·폐기·복원·Journal 검사도
함께 실행한다. 실행 결과는 [검증 기록](tag-notes-validation.txt)에 기록한다.
