# 속성별 노트 탐색

`/graph`의 **내 노트 목록 → 속성 탐색 열기**에서 Space의 속성을 고르면 해당 속성이
있는 활성 노트와 현재 값을 표로 확인한다. 노트 제목을 누르면 기존 노트 편집기가
현재 본문·이력을 조회한다. 일반 노트와 Daily Note가 함께 표시되며 같은 제목은
Note ID로 구별한다. 속성과 노트는 생성 ID 순서로 20개씩 불러온다.

문자열, 숫자, 참/거짓, 날짜, 문자열 목록을 지원한다. NUMBER는 JSON 숫자 토큰을
그대로 보존해 표시하므로 JavaScript 부동소수점 변환으로 자릿수를 잃지 않는다.
빈 문자열, 빈 목록, 0, 거짓도 표시하며 HTML은 텍스트로만 출력한다. 본문 버전과
태그·속성 버전을 함께 보여 준다. 속성 수정은 본문 버전이나 본문 수정 시각을
바꾸지 않는다.

속성 목록 개수는 조회 당시 값이다. 속성값 수정·연결 해제·노트 폐기는 새로고침하면
반영된다. 사용 중인 활성 노트가 없는 정의도 남아 빈 결과로 조회할 수 있다.
목록은 실시간 조회이며 페이지 전체를 고정한 snapshot은 아니다.
[정확한 속성값 조건](property-filter.md)으로 노트를 좁혀 볼 수 있다.
[숫자·날짜 범위 검색](property-range.md)도 지원한다. 정렬은 별도 확장이다.
[태그와 속성 조건 조합](property-tag.md)으로 더 좁혀 볼 수 있다.
[속성 이름 검색](metadata-name-search.md)으로 원하는 정의를 찾을 수 있다.

탐색 자체는 편집 중인 노트 초안을 바꾸지 않는다. 노트 열기는 기존 노트 선택
동작이다. 속성 선택 변경·해제·탐색 닫기·Space 전환 시 요청을 취소하고 늦은
응답을 버린다. 잘못된 값·다른 속성 응답·반복 페이지·조회 실패는 해당 결과를
비우고 새로고침을 안내한다. 401/403/404는 개인 화면을 비우며 브라우저 저장소에
목록이나 값을 저장하지 않는다.

## API와 기존 결정

AGENTS.md와 PROJECT.md를 검토했으며 기존 결정과 충돌하지 않는다. Content가 소유한
PostgreSQL의 `property_definition`, `note_property`, `note`만 조회한다. 기존 속성
타입·이름 규칙과 Note lifecycle을 유지한다. 타 서비스 DB 조회, 새 dependency,
이벤트 계약 변경이나 read projection 추가가 없다.

- `GET /api/spaces/{spaceId}/property-definitions` — 기존 속성 정의 목록과 활성 노트 개수.
- `GET /api/spaces/{spaceId}/property-definitions/{propertyId}/notes` —
  `{propertyId,name,type,items,nextAfter}`. 각 item은
  `{noteId,title,version,metadataVersion,updatedAt,value}`이다.

각 페이지는 현재 Space 권한을 확인하며 PRIVATE/ACTIVE Note만 반환한다.
알려진 속성의 연결이 없으면 빈 페이지, 미등록 또는 다른 Space의 속성은 404이다.
`after`는 배타적 UUIDv7 커서이며 `limit`은 기본 20, 범위 1..100이다. 선택적 `value`는
속성값 조건이며 빈 문자열도 허용한다. 숫자·날짜는 `minValue`/`maxValue` 범위 조건도
허용한다. 정확 일치와 범위 조건은 동시에 사용할 수 없다. 중복 키·빈 after/limit/경계·알 수 없는 query key는
거절하며 Gateway는 조회 본문과 쓰기 경로를 허용하지 않는다.
모든 정상 응답에 `Cache-Control: no-store`를 적용한다.

value는 parent page의 type에 맞는 JSON 값이다. NUMBER의 정밀도는 기존 최대 38자리,
절대 scale 18 규칙을 따른다. API 클라이언트도 decimal을 보존해 읽어야 한다.
updatedAt은 Note 본문 갱신 시각이며 속성 변경 시각이 아니다. 독립 metadataVersion으로
속성 변경 버전을 확인한다. 속성 타입은 Space 안에서 고정된다.

`V13__property_note_discovery.sql`은 `(space_id,property_id,note_id)` 조회 인덱스만
추가한다. 정본·수정 이력·Outbox 데이터 변환은 없다. 조회는 이벤트를 만들지 않는다.
기존 HTTP/trace 관측과 `brain.content.metadata.reads{type=property_notes}` 카운터를
사용하며 domain ID를 metric label로 넣지 않는다.

## 검증

실제 PostgreSQL/Kafka 및 Gateway 테스트, Next.js/TypeScript 빌드와 로컬 Kubernetes
브라우저 검증을 수행한다. 22개 속성·22개 노트 페이지, 정확한 소수, 5가지 형식,
빈 값, 현재 값 변경·해제·폐기, Daily Note, 다른 Space 차단, 초안 유지, 모바일 폭,
잘못된 응답, 늦은 응답과 접근 철회를 확인한다. 기존 태그·메타데이터 활동·노트
목록·복원·보관함·Journal 회귀 검사도 포함한다.

실행 결과는 [검증 기록](property-notes-validation.txt)에 기록한다.
