# 개인 로드맵 학습 진도와 학습 시간 기록

2026-10-15. PERSONAL PRIVATE Space의 로드맵에 노드별 수동 진도와 학습 시간 기록을 연결한다. 기존 로드맵 그래프 구현 다음 단계다. 소유권은 PROJECT.md의 Learning Service에 있으며, Space의 기존 전용 ROADMAP_READ/ROADMAP_WRITE 권한 API와 공통 게스트·등록 principal을 재사용한다. DB 소유권, 서비스 경계, UUIDv7, ACID/Outbox, Kubernetes 결정과 충돌하지 않는다.

## 동작

- 진도는 `NOT_STARTED`(시작 전), `IN_PROGRESS`(학습 중), `COMPLETED`(완료)를 사용자가 직접 변경한다. 시간 기록만으로 상태를 바꾸거나 선수 관계에 따라 완료를 강제하지 않는다. 그래프의 선후관계는 학습 순서 안내이며, 완료는 지식·의학적 검증 상태가 아니다.
- 현재 ACTIVE 노드 전체를 분모로 완료 비율을 계산한다. 빈 그래프는 0%다. 노드가 폐기되면 현재 집계에서 제외하지만 기존 진도 이력과 시간 기록은 남긴다. ‘현재 항목의 기록 시간’은 ACTIVE 노드에 연결된 ACTIVE 기록의 합계다.
- 학습 시간은 항목, 시작 시각, 종료 시각, 메모를 명시적으로 기록한다. 입력 offset을 UTC의 초 단위로 정규화한다. Unix epoch 이후, 1초~24시간, 서버 현재 시각보다 최대 5분 뒤까지를 허용한다(시계 차이 여유). 시각의 소수 초는 버린다. 별도 기록끼리 시간이 겹치는 것은 허용하고 기록된 시간을 합산한다.
- 메모는 원문을 보존한다. 최대 8,192 UTF-8 byte, LF/CR/TAB 허용, 다른 ISO control·unpaired surrogate 거부. 외부 URL이나 HTML을 실행·수집하지 않는다. 화면은 텍스트로 출력한다.
- 기록은 작성 시 연결한 노드 제목을 보존한다. 이후 노드 이름을 바꾸어도 기록 당시 제목이 바뀌지 않는다. 다른 ACTIVE 노드로 기록을 명시적으로 재지정하면 새 노드 제목을 저장하며 이전 이력은 보존한다.
- PERSONAL Space의 학습 상태를 저장하며 변경한 actor는 감사 정보다. 로그인 전후 principal ID로 데이터를 다시 나누지 않는다. 게스트 공간의 계정 귀속 이후에도 기존 진도·기록 ID와 이력이 그대로 유지된다. GROUP/PUBLIC 진도, 개인별 강좌 enrollment, 학습 증거, Activity timeline projection, 평점·순위, 추천은 이번 범위가 아니다.

## 독립된 버전과 원자성

`roadmap`의 `"rmN"`은 제목·그래프·폐기를 보호한다. `node_progress`는 노드별 `"prN"`, `study_session`은 기록별 `"ssN"`을 가진다. 학습 데이터 변경으로 Roadmap 그래프·revision·event를 만들지 않는다. 각 변경은 자체 상태, immutable full revision, full Protobuf Outbox를 하나의 Learning DB 트랜잭션으로 저장한다.

진도를 저장하지 않은 노드는 GET 시 NOT_STARTED/version0/`progressId=null`/`updatedAt=null`/ETag `"pr0"`로 보인다. 읽기에는 DB 쓰기가 없다. NOT_STARTED를 그대로 저장해도 row/event를 만들지 않는다. 처음 실제 상태를 바꿀 때 Learning이 UUIDv7과 버전1을 부여한다. 이후 같은 내용의 유효한 no-op은 버전·이력·이벤트를 추가하지 않는다. no-op도 버전 검사를 먼저 하므로 오래된 precondition은 412다.

상태 쓰기는 Learning의 로드맵 행 잠금을 그래프 변경과 공유한 후 현재 revision/ACTIVE 노드를 읽는다. 그래프가 노드를 폐기하는 작업과 경합해도 폐기 이후에 새 진도·세션을 생성하지 않는다. root lock은 진도/세션과 Roadmap 간 서비스 외부 트랜잭션이 아니라 동일 소유 DB 내부 일관성 규칙이다. 현재 진도·세션은 own row와 같은 버전의 immutable snapshot을 함께 읽고, 요약은 단일 SQL statement에서 읽는다.

세션 작성에는 32자리 소문자 hex `Idempotency-Key`가 필수다. 이는 영구 도메인 ID가 아닌 요청 토큰이다. `(roadmap_id, request_key)`에 생성 요청의 정규화된 payload fingerprint를 유지한다. 동일 요청 재전송은 같은 기록의 **현재 버전**을 200으로 반환하며 새 revision/event를 만들지 않는다. 같은 key에 다른 payload는 409다. 처음 작성은 201이다. 수정·폐기 후에도 최초 fingerprint가 유지되므로 재시도로 내용을 되돌리거나 폐기한 기록을 복원하지 않는다. 같은 key를 사용하는 동시 요청도 한 번만 생성한다. 게스트 로그인 이후에도 같은 Space에서 재시도할 수 있다.

세션 수정은 ACTIVE 로드맵과 ACTIVE 대상 노드가 필요하다. 대상 노드가 폐기된 기존 기록도 폐기하거나 읽을 수 있다. 기록 폐기는 시간 합계와 현재 목록에서 제외하면서 원문과 이전 버전을 유지한다. 로드맵이 폐기되면 쓰기를 막지만, 같은 Space의 현재 권한으로 기존 기록과 진도/세션 이력을 조회할 수 있다. 세션 current GET은 세션 자체가 RETIRED면 404이며 이력은 남는다. 복원 API는 없다.

목록은 UUID 기준 20개 기본·최대100 keyset 페이지, 각 이력은 버전 내림차순 기본5·최대10이다. 프로그레스 body는 4 KiB, 세션 body는 64 KiB다. 입력 JSON의 중복/알 수 없는 key·trailing token·GET/DELETE body와 중복/알 수 없는 query key를 거부한다. 모든 응답은 no-store다. 쓰기 Origin, secure cookie, 신선한 Space 권한 확인, 고정 HTTPS origin/인증서 검증/redirect 거부는 기존 로드맵과 동일하다.

## 이벤트와 관측

기존 Learning publication과 `learning-outbox` connector가 동일 Outbox에서 두 aggregate type을 라우팅한다. 새 consumer projection은 이 단계에 추가하지 않는다.

| Kafka topic | Schema artifact | 이벤트 |
| --- | --- | --- |
| `learning.progress.v1` | `learning/progress-events` v1 | `LearningProgressChanged` |
| `learning.study-session.v1` | `learning/study-session-events` v1 | `StudySessionRecorded`, `StudySessionUpdated`, `StudySessionRetired` |

각 이벤트는 stable UUIDv7 eventId, producer, aggregate/space/actor ID, traceId, full revision payload를 가진다. 전송은 at-least-once다. 향후 consumer는 eventId/aggregate version에 따른 멱등 처리가 필요하다. HTTP traceparent -> Outbox -> Kafka envelope/header의 traceId를 연결한다.

Apicurio VALIDITY FULL, COMPATIBILITY FULL_TRANSITIVE. 파일 SHA-256:

- learning_progress_v1.proto: `ef3bf592594564efa67c9ddd1f3b9f3dddb7f6cc5e6fc7d20943464f013c1113`
- learning_study_session_v1.proto: `98b45509343000736c25510aa59fb5cd814a9f2672de5e9dcdd242a2cf55a90e`

Connect Kafka ACL은 두 exact topic 쓰기만 추가하고 검증 client는 세 Learning topic 읽기만 허용한다. `brain_learning_cdc`는 Outbox SELECT/복제만 가지며 8개 Learning canonical table SELECT와 다른 12개 owner DB 접속을 거부하는 검사를 확장했다. 검증 client의 Kafka 쓰기 거부도 실제 broker에서 검사한다.

`brain.learning.records{operation}` 카운터가 요약/진도 조회·저장·이력, 세션 목록·읽기·생성·동일 요청 재생·수정·폐기·이력을 구분한다. Hikari 지표와 own-DB readiness를 재사용한다. 플랫폼 중앙 OpenTelemetry exporter 및 Prometheus/Loki/Tempo/Grafana 수집 구축은 기존 플랫폼 후속 범위다.

## 화면과 로컬 실행

`https://localhost:8443/graph` -> **학습 로드맵 열기** -> 로드맵 선택 -> **학습 진도·기록 열기**. 노드를 선택해 진도를 편집하거나, **학습 시간 기록**에서 항목과 로컬 시간대의 시작·종료 시각을 입력한다. 명시적으로 저장해야 반영된다. 로드맵 그래프를 바꾼 뒤에는 학습 영역을 새로고침한다.

진도·세션의 초안과 기존 Note/Roadmap 편집 초안은 독립적이다. 412에서는 초안을 유지하고 최신 버전을 먼저 조회한 뒤 명시적으로 버리고 다시 편집해야 한다. 폐기 확인도 충돌하면 다시 받아야 한다. 응답을 잃은 세션 작성은 같은 내용/요청 key로 재시도할 수 있다. 입력을 바꾸면 새로운 작성 의도로 새 key를 사용하며, 불확실한 저장 후 새 기록을 만들기 전에 목록을 확인하도록 안내한다. key를 포함한 private 자료를 브라우저 storage에 보관하지 않는다.

시각 입력 아래에는 저장할 시간과 시간대 이름을 미리 표시한다. 기존 시각 필드를 바꾸지 않았다면 원래 UTC instant를 사용한다. 서머타임 종료 때 현지 시각이 반복되더라도 메모 수정만으로 기록 시각을 다른 발생 시각으로 바꾸지 않는다. America/New_York의 두 번째 01:15 EST를 실제 브라우저에서 검사했다.

닫기·Space/로드맵/노드/기록 전환 때 요청을 취소하고 늦은 응답을 무시한다. 401/403은 private 상태를 지운다. 리소스 404는 현재 Space 조회로 접근 권한 상실과 합법적인 폐기 이력을 구분한다. 목록 장애는 이전 목록을 지우며 편집 초안은 유지한다.

V2 Flyway migration과 같은 기존 service chart를 사용한다. 순서: Learning scoped backend 검사/DB migration -> brain-connect Helm(새 topic/ACL) -> `Provision-LearningValidationClient.py` -> `Provision-LearningCdc.py`/`Run-LearningConnectorConfiguration.py` -> `Run-LearningTrackingContractValidation.py` -> Learning/Gateway build/import -> `Install-LocalRoadmaps.py --learning-only` 및 `--gateway` -> HTTPS browser 검사. `Run-BackendValidation.py --learning --learning-kafka`, `Run-GraphWebValidation.py --learning-study-only`로 이번 범위를 검사한다. 전체 browser는 `--public`을 사용한다.

전체 회귀는 `--public --no-images`로 실행해 PNG base64 출력으로 최종 검사 로그가 회전되지 않도록 할 수 있다. 기능별 검사는 계속 스크린샷을 수집하며, 마지막 exit code도 별도로 남긴다.

계약: [personal-learning-study.yaml](../contracts/openapi/personal-learning-study.yaml), [learning_progress_v1.proto](../contracts/events/src/main/protobuf/learning_progress_v1.proto), [learning_study_session_v1.proto](../contracts/events/src/main/protobuf/learning_study_session_v1.proto). 검증 Job과 결과는 [learning-study-validation.txt](learning-study-validation.txt)에 기록한다. 기존 승인된 라이브러리 버전만 재사용하며 새로운 UI 라이브러리·의료 데이터셋·AI 기능을 추가하지 않는다.

Note-version learning Evidence is now implemented as an independent Learning relationship; see [learning-evidence.md](learning-evidence.md). Other source kinds and optional session association remain follow-up scope.
