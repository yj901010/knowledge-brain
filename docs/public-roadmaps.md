# 공개 로드맵 제안·검토·병합

2026-10-20. `/graph`의 **공개 로드맵** 또는
`https://localhost:8443/graph/roadmaps`를 연다. 게스트는 Medicine/Development의
공개 그래프, 제출된 제안·사유, 검토 이력과 병합된 Revision을 열람한다.
등록 계정은 제목·설명과 노드·관계를 직접 작성하고 **제안 미리보기**에서
전체 내용·방향을 확인한 뒤 공개 확인란을 선택해 제출한다.

개인 로드맵의 공개 전환이나 자동 복사는 없다. 새로운 공개 그래프에 Learning이
새 UUIDv7 ID를 발급한다. 개인 Space ID, 진도·학습 시간·세션·노트·Concept/파일
근거·첨부파일·공개하지 않은 편집 초안은 제출 DTO에 없으며 공개 원본 생성 시
개인 도메인 테이블도 읽지 않는다. 기존 개인 로드맵은 그대로 유지된다.

## 검토와 동시성

사용자가 승인한 정책은 [0004-public-roadmap-review.md](decisions/0004-public-roadmap-review.md)에 기록했다.
SUBMITTED → APPROVED → MERGED 또는 SUBMITTED → REJECTED다. 승인만으로는 공개
원본이 생기거나 변경되지 않는다. 제출된 그래프·사유는 이후 수정할 수 없고,
반려 후 수정 또는 오래된 제안의 재제출에는 새로운 제안이 필요하다.

Space의 기존 공개 분야와 운영자 지정 REVIEWER/ADMIN/OWNER 멤버십을 사용한다.
새 공개 Learning action을 매 요청 검증하며 등록 principal만 제안할 수 있다.
검토자는 자신의 제안을 승인·반려·병합할 수 없다. 브라우저에서 역할을 부여하지
않으며 권한 회수는 다음 요청부터 적용된다. 운영자 지정은 기존
`Set-LocalPublicReviewer.py --principal-id <APPLICATION_UUIDV7> --domain DEVELOPMENT`
방식을 재사용한다. 서비스 인증 키와 브라우저 쿠키를 혼용하지 않는다.

제안은 강한 `"pcN"`, 공개 원본은 `"rmpN"` ETag를 사용한다. 검토·병합에 If-Match가
없으면 428, 잘못된 tag는 400, 다른 제안 버전 또는 오래된 canonical base는 412다.
UPDATE 제안은 현재 공개 원본의 baseVersion과 현재 ACTIVE 그래프의 ID만 사용한다.
한 번 제외한 공개 ID나 개인 노드·관계 ID를 새 제안에 재사용할 수 없다.
자기 관계·중복·6종 관계·선후/포함 DAG 규칙은 기존 RoadmapGraph 검증을 재사용한다.

Learning의 공개 원본, immutable Revision, 검토 행, 제안 상태와 두 Outbox 행은
하나의 로컬 ACID 트랜잭션이다. 원본 잠금 후 기준 버전을 다시 확인한다. Outbox
실패는 전체 병합을 rollback한다. 검토 상태 변경은 자신의 pcN을 증가시키고,
병합은 공개 원본 버전도 증가시킨다. 기존 개인 rmN·진도 버전은 변경하지 않는다.

`visibility=PUBLIC`과 `verification=REVIEWED`는 별도 필드다. REVIEWED는 이 그래프의
지정 검토 절차 완료를 뜻하며, 평점·사용량·의학적 검증·전문가 인증을 뜻하지 않는다.

## 계약·저장·운영

Learning V7은 자신의 DB에 public_roadmap/public_roadmap_revision,
roadmap_contribution/roadmap_contribution_review만 추가한다. 개인 데이터 migration,
타 서비스 DB 접근·join·FK, 새 외부 의존성·데이터셋은 없다.

- 브라우저: [public-roadmaps.yaml](../contracts/openapi/public-roadmaps.yaml).
- Space 전용 권한: [space-learning-authorization.yaml](../contracts/openapi/space-learning-authorization.yaml) 1.1.0.
- [learning_public_roadmap_v1.proto](../contracts/events/src/main/protobuf/learning_public_roadmap_v1.proto): 두 typed payload와 공통 11-field envelope.
- `learning.public-roadmap.v1` / `learning/public-roadmap-events` V1.
- `learning.roadmap-contribution.v1` / `learning/roadmap-contribution-events` V1.

Own Outbox → 기존 learning-outbox Debezium → Kafka를 사용한다. 별도 slot이나
checkpoint 재설정은 없다. Connect 쓰기·검증 client 읽기는 두 literal topic만
추가한다. Registry VALIDITY=FULL, COMPATIBILITY=FULL_TRANSITIVE다. eventId·actor·traceId,
원본 bytes와 schema header를 검증한다. 기존 7종 Learning private 이벤트와 Activity
20종 소비·복구 범위는 바뀌지 않는다. 공개 원본 조회는 단일 도메인의 canonical
REST이며 새 projection consumer는 추가하지 않는다.

노드 100개·관계 500개·제목 200 Unicode code point, 설명 32768 UTF-8 byte,
노드 설명 2048 byte, 사유 2000 code point 제한이다. 제안 body 1 MiB, 직렬화된
개별 snapshot 512 KiB, 검토·병합 body 32 KiB다. 목록은 UUID keyset 기본20/최대100,
Revision은 버전 역순 기본5/최대10이다. 목록은 본문·그래프를 포함하지 않는다.
GET body·중복/미지정 query, 중복/미지정 JSON key·trailing token을 거부한다.
HTTPS 인증서 검증·고정 origin·redirect 거부·쓰기 Origin·no-store를 유지한다.
Gateway에는 공개 원본 직접 PUT/DELETE 또는 공개 진도/근거 API가 없다.

화면은 저장 전에 그래프를 미리 보여 주고 공개 확인을 받는다. 412와 입력 오류는
초안을 보존하고 재편집을 안내한다. 분야 변경·unmount는 요청을 취소하고 늦은
응답을 무시한다. 목록 새로고침 실패는 이전 목록을 지우며 권한 상실은 제안·편집 상태를
지운다. 브라우저 storage에 제안·세션을 기록하지 않고 문자열은 escaped text로
표시한다. 390px 화면을 검증한다.

`brain.learning.public.enabled`는 공통 서비스 values에서 false이며, 필요한 계약을
준비한 로컬 values에서 true다. 같은 서비스/DB/event 배포 구조를 유지한다.
Space/Learning/Gateway 이미지를 build/import한 뒤
`python scripts/Install-LocalPublicRoadmaps.py --helm <HELM_EXE>`를 실행한다.
설치 도구는 local context, 양 노드의 정확한 image import, literal Kafka ACL/topic,
Registry 계약 Job 성공을 확인한 다음 Space → Learning → Gateway를 배포한다.
`Start-LocalGateway.ps1`로 HTTPS 화면을 연다.

`brain.learning.public.reads/changes{type}` counter, 기존 Hikari/own-DB readiness,
state·contributionId·traceId 로그를 사용하며 세션·제안 본문은 로그에 기록하지 않는다.
중앙 OTel exporter/Prometheus/Loki/Tempo/Grafana 수집은 기존 플랫폼 후속 범위다.

## 검증과 다음 범위

`Run-BackendValidation.py --learning --learning-kafka`는 own PostgreSQL ACID/동시 검토/
stale base/자기 검토/크기·ID 제약과 실제 Debezium의 exact byte/header 전송을 검증한다.
Space/Gateway 권한 경계는 전체 backend 검증에 포함된다.
`Run-GraphWebValidation.py --public`은 실제 두 Keycloak 등록 계정과 테스트 검토자의
일시적 역할 부여·회수를 통해 전체 제출/미리보기/승인/병합/반려/수정/이력과
개인 데이터 격리를 검증한다. 테스트 역할은 finally에서 회수한다.
`--public-roadmaps-only`는 게스트·오류·늦은 응답 경로만 좁게 확인한다.

게시 철회, 검토자 관리 UI, 공개 search/graph projection과 최종 Obsidian 스타일은 후속 범위다.
이번 단계는 공개 그래프 기여와 검토·병합을 완성한다.
실행 근거는 [public-roadmaps-validation.txt](public-roadmaps-validation.txt)에 기록한다.

2026-10-20 후속 구현: [평점·순위](roadmap-ratings.md)를 추가했다. 원래 공개 그래프·검토·병합 계약과 이력은 유지한다.

2026-10-20 후속 구현: [개인 학습으로 가져오기](roadmap-adoptions.md)를 추가했다. 공개 버전 출처를 보존한 비공개 사본에서 기존 진도·세션·근거를 사용한다. 공개 원본과 개인 학습은 독립적이다.
