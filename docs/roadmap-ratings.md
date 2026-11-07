# 공개 로드맵 평점과 순위

2026-10-20. `/graph/roadmaps`에서 **로드맵 평점·순위 열기** 또는 병합된 공개
로드맵 상세의 **로드맵 평점**을 사용한다. 기존 공개 그래프 제안·검토·병합에
Learning 소유의 평점과 결정적 상위 순위를 추가한다. AGENTS/PROJECT의 MSA,
DB 소유권, 검토와 인기도 분리, 비공개 기본값 및 Outbox 결정과 충돌하지 않는다.

## 이번 단계의 규칙

- 등록 계정당 공개 로드맵에 평점 하나, 정수 1~5점이다. 수정·철회·재평가한다.
- 최초 CREATE 제안의 작성자는 자기 로드맵을 평가하지 못한다. 병합한 검토자나
  후속 기여자라는 이유만으로 평가를 막거나 별도 평가 권한을 부여하지 않는다.
- 평점은 평가한 공개 graph version에 고정된다. 현재 버전의 ACTIVE 평점만 공개
  평균·분포·평가 수·순위에 포함한다. 새 공개 Revision은 옛 평점을 지우지 않으며,
  새 그래프를 확인하고 명시적으로 다시 평가해야 현재 버전에 반영된다.
- 평가 점수는 공개 집계에 반영됨을 화면에 표시한다. 공개 API는 집계만 반환하고
  평가자 ID·개별 투표 목록을 공개하지 않는다. 내 평점·이력은 현재 등록 principal만
  읽는다. 게스트는 공개 집계와 순위를 열람한다.
- 확인된 학습 완료·사용 경험을 평점의 자격으로 주장하지 않는다. 공개 로드맵의
  개인 채택/진도는 [가져오기 기능](roadmap-adoptions.md)으로 제공한다.

현재 버전 평균점수순은 **정확한 평균 내림차순 → 평가 수 내림차순 → UUID 오름차순**,
평가 수순은 **수 내림차순 → 정확한 평균 내림차순 → UUID 오름차순**이다.
평균이 없는 항목은 평균점수순에서 뒤로 간다. 최근 수정순은 **공개 그래프 수정시각
내림차순 → UUID 오름차순**이며 평점으로 그래프 수정시각을 바꾸지 않는다.
서버 정렬은 PostgreSQL numeric의 반올림하지 않은 평균을 사용한다. 표시 평균은
HALF_UP 소수 둘째 자리 문자열이다. 0건 평균은 null/평가 없음이며 0점으로 표현하지 않는다.

순위는 매 조회의 단일 SQL statement snapshot에서 반환한다. 화면은 조회 시각과
상위 20/50/100개 범위를 보여 주며 최대100개다. 영구 순위·안정된 페이지 cursor가
아니다. 전체 탐색은 기존 UUID keyset 공개 목록을 사용한다. 평가 수정/철회 후
열어 둔 순위는 다시 조회한다. usage/completion/rule-based 추천 및 가중 점수는
아직 포함하지 않는다. REVIEWED 검토 상태와 평점은 독립적이다.

## 저장·동시성·이벤트

Learning V8은 자신의 DB에 roadmap_rating/roadmap_rating_revision을 추가한다.
한 roadmap/principal에 영구 UUIDv7 rating ID 하나를 사용하며 철회 후 재평가도 ID를
유지하고 버전만 증가시킨다. immutable 전체 snapshot 이력을 보존한다. 철회 snapshot은
기존 점수와 평가한 그래프 버전을 유지하지만 집계에는 포함하지 않는다.
공개 원본/최초 기여자 확인은 같은 Learning DB에 한정된다. 타 서비스 DB 접근·join·FK는 없다.

공개 그래프 원본 행 잠금을 병합과 공유하여 graph 변경과 평점의 현재 버전 검사를
직렬화한다. 평점 root, revision, full Protobuf envelope Outbox는 하나의 ACID transaction이다.
Outbox 실패는 평점과 이력을 모두 rollback한다. 평점은 그래프 본문·Revision·검토·
개인 진도/학습 기록에 쓰지 않는다. 요약과 각 순위는 current public version을 포함한
한 SQL 문으로 읽어 다른 버전의 평점을 섞지 않는다.

내 평점은 강한 `"rrN"` ETag를 사용한다. 아직 평가하지 않았으면 rating=null과 rr0이다.
PUT은 현재 roadmapVersion/score, DELETE는 빈 body, 둘 다 If-Match가 필요하다.
없으면 428, 약하거나 잘못된 tag/JSON/query는 400, 평점 버전 충돌은 412,
평가할 공개 버전이 바뀌었으면 409다. 버전을 먼저 검사하고 유효한 동일 내용과
이미 철회/없는 평점의 철회는 no-op이며 추가 revision/event가 없다.
같은 rr0의 동시 생성은 한 번만 성공한다. 최신 rrN 재조회가 유실된 응답 복구 수단이다.

Space의 전용 Learning authority에 ROADMAP_RATING_READ/ROADMAP_RATE를 추가한다.
매 요청 신선한 Identity 세션과 등록 여부를 확인하고 Learning이 자신 평점과 최초
작성자 제한을 집행한다. 검토자 역할 회수는 평가 권한을 없애지 않는다. 등록 세션의
무효화는 다음 요청부터 적용된다. Cookie 대신 내부 서비스 키와 불투명 token으로
권한을 확인하며, HTTPS 검증·고정 origin·redirect 거부·쓰기 Origin·no-store를 유지한다.

- [public-roadmap-ratings.yaml](../contracts/openapi/public-roadmap-ratings.yaml)
- [space-learning-authorization.yaml](../contracts/openapi/space-learning-authorization.yaml) 1.2.0
- [learning_roadmap_rating_v1.proto](../contracts/events/src/main/protobuf/learning_roadmap_rating_v1.proto)
- 새 topic `learning.roadmap-rating.v1`, Registry `learning/roadmap-rating-events` V1,
  VALIDITY=FULL, COMPATIBILITY=FULL_TRANSITIVE.

Own Outbox → 기존 learning-outbox Debezium → Kafka를 사용한다. 새 topic은 Connect
쓰기/validator 읽기의 literal ACL만 추가한다. 원래 private7종/public2종 Learning
계약, CDC config/slot/checkpoint, Activity20종 소비/복구 범위는 유지한다. 새 rating은
private Activity export에 포함하지 않는다. 새 consumer/projection은 없다. 향후
집계 consumer는 원본의 공개 graph 버전 이벤트와 rating 이벤트를 함께 처리하고
서로 다른 topic의 도착 순서에 의존하지 않아야 한다. stable eventId와 rating version으로
멱등 처리해야 하며, 요약/순위는 지금 Learning canonical 소유 데이터를 직접 읽는다.

## 화면과 검증

평점 선택과 철회 확인, 저장/재평가, 현재 집계, 내 이력 5개씩 더 보기를 제공한다.
409는 최신 공개 그래프를 다시 열도록 안내하고 이전 선택을 새 버전에 자동 적용하지 않는다.
412/통신 실패는 점수 초안을 보존하고 재저장을 잠근다. **내 평점 다시 조회하고 초안 초기화**로
최신 rrN을 읽고 다시 선택한다. 철회에는 별도의 확인이 필요하다. 분야/로드맵/세션
변경·unmount는 요청을 취소하고 늦은 응답을 무시한다. 평점 새로고침 실패/잘못된 응답은 새 조회 내용을
반영하지 않으며 401/403은 내 평점·초안·이력을 지운다. 브라우저 storage를 사용하지 않는다.
집계 검증과 반올림은 BigInt 정수식으로 점수 합/분포/평균의 일치도 검사한다.

`brain.learning.ratings.reads/changes{type}`, 기존 Hikari/own-DB readiness,
roadmap ID/버전/trace ID 로그를 사용한다. 공통 중앙 OTel/Prometheus/Loki/Tempo/Grafana
구축은 기존 플랫폼 후속 범위다. 새 외부 의존성이나 데이터셋은 추가하지 않는다.

빌드/import 뒤 `Install-LocalRoadmapRatings.py --helm <HELM_EXE>`는 정확한 양 노드
이미지 반입, 새 topic/ACL과 세 Registry 계약 성공을 확인한 다음 Space/Learning/Gateway를
배포한다. 기존 feature gate와 동일한 논리 배포 구조를 사용한다. 원본/Inbox/PVC/topic/
CDC slot/checkpoint 삭제나 전체 Activity 재생은 필요하지 않다.

`Run-BackendValidation.py --learning --learning-kafka`와 전체 backend 검증은 own
PostgreSQL lifecycle·one-vote·자기 평가·stale version·동시 쓰기·rollback·순위와 실제 Kafka
원본 bytes/header를 검사한다. `Run-GraphWebValidation.py --roadmap-ratings-only`는 게스트
경계·실패·늦은 응답을, `--public`은 실제 두 Keycloak 등록 계정으로 수정/철회/재평가,
5+3 이력, 새 graph version, 자기 평가 차단, 검토자 역할 회수 이후 평가 독립성을 검사한다.
실행 근거는 [roadmap-ratings-validation.txt](roadmap-ratings-validation.txt)에 기록한다.
