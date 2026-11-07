# 개인 학습 로드맵의 노드·관계 관리

2026-10-14 구현 범위는 PERSONAL Space의 PRIVATE Roadmap 원본과 그래프 편집이다. 게스트와 등록 계정은 같은 애플리케이션 principal과 Space 권한을 사용한다. 기존 게스트 공간을 계정에 귀속하면 로드맵 ID, 그래프와 이력이 그대로 남는다. 공개 로드맵의 검토·병합, 평점·순위, 학습 세션·진도·증거와 Concept 참조는 후속 범위다.

2026-10-15 후속 단계에서 노드별 진도와 학습 시간 기록을 추가했다. 현재 구현은 [learning-study.md](learning-study.md)를 함께 참고한다. 학습 증거와 공개 검토·순위 등은 여전히 후속 범위다.

## 소유권과 저장

Learning Service의 `brain_learning`에 Roadmap, Node, Edge, Revision, Outbox를 저장한다. Space Service는 전용 `/internal/space/learning-authorization` REST API로 매 요청의 현재 권한을 확인한다. 브라우저 세션 쿠키를 내부 API의 Cookie 헤더로 전달하지 않고, 공통 principal 확인용 불투명 토큰을 전용 서비스 자격 증명과 함께 보낸다. PERSONAL PRIVATE만 허용하며 OWNER/ADMIN/EDITOR가 수정할 수 있다. 다른 서비스 DB 조회·조인·FK와 공개 공간으로의 자동 이동은 없다.

Roadmap 생성은 빈 그래프와 버전 1을 만든다. Learning이 모든 영구 도메인 ID를 UUIDv7으로 발급한다. 그래프 요청의 `new:` 키는 요청 안에서만 새 노드와 관계를 지칭하며 저장되지 않는다. 기존 ID는 같은 로드맵에서 현재 ACTIVE인 ID만 재사용할 수 있다. 제목이 같은 로드맵·노드는 별개 ID로 구분한다.

제목·설명, 그래프 교체, 로드맵 폐기가 하나의 강한 `"rmN"` ETag를 공유한다. `If-Match`가 없으면 428, 잘못된 형식은 400, 최신 버전과 다르면 412다. 메타데이터 변경은 그래프를 보존하고 그래프 변경은 메타데이터를 보존한다. 동일한 내용도 버전 검사를 먼저 하며, 유효한 no-op은 수정 버전·이력·이벤트를 추가하지 않는다.

수정은 루트 행 잠금을 얻은 뒤 새 SQL 문으로 해당 버전의 immutable revision을 읽는다. 잠금을 기다리던 쓰기가 예전 revision과 새 루트 버전을 섞어서 읽지 않는다. 현재 GET은 루트와 revision을 한 PostgreSQL 문에서 읽는다. changed root, nodes, edges, full JSON revision, full Protobuf envelope Outbox는 하나의 로컬 ACID 트랜잭션으로 저장한다.

## 그래프 규칙

| 종류 | 의미와 제약 |
| --- | --- |
| PREREQUISITE_OF | 출발 항목이 도착 항목의 선수 지식 |
| RECOMMENDED_BEFORE | 출발 항목을 먼저 학습하도록 권장 |
| REQUIRED_FOR | 출발 항목이 도착 항목의 필수 조건 |
| PART_OF | 출발 항목이 도착 상위 항목의 구성 요소 |
| OPTIONAL_FOR | 선택 학습 관계; 순환 가능 |
| RELATED_TO | 방향 없는 관련 항목; UUID 문자열 순서로 양 끝 정규화 |

선수·권장·필수 세 종류를 합친 그래프는 DAG여야 한다. PART_OF는 별도의 DAG여야 한다. 자기 자신 연결, 제출하지 않은 끝점, 중복 node/edge key와 같은 종류의 동일 끝점 관계는 거부한다. RELATED_TO는 역방향도 중복이다. 동일 두 노드에 서로 다른 종류의 관계는 가능하다.

그래프 PUT은 전체 ACTIVE 그래프를 교체한다. 빠지는 기존 노드와 관계는 RETIRED로 남으며, 해당 ID를 다시 넣는 방식의 복원은 거부한다. 노드를 제외할 때 연결된 관계도 요청에서 제외해야 한다. 수정 이력의 원래 노드·관계·ID는 바뀌지 않는다. 같은 트랜잭션에서 기존 관계를 먼저 retire하고 유지할 관계를 갱신하므로, 두 관계의 의미를 서로 바꿀 때 중간 unique constraint 충돌이 생기지 않는다. 로드맵 폐기 후 목록과 현재 GET에서 제외하지만, 현재 Space 권한이 있는 사용자는 알고 있는 ID로 마지막 그래프와 전체 이력을 조회할 수 있다.

제한: 노드 100개, 관계 500개; 제목 200 Unicode code point; 로드맵 설명 32,768 UTF-8 byte; 노드 설명 2,048 UTF-8 byte. 설명은 LF/CR/TAB를 허용하며 다른 ISO control과 unpaired surrogate를 거부한다. 그래프 HTTP body 1 MiB, 메타데이터 body 256 KiB, 직렬화된 전체 canonical snapshot 512 KiB다. 합쳐진 snapshot 제한을 초과하면 노드·관계·루트·revision·Outbox 모두 rollback한다. History는 기본 5개, 최대 10개며 Gateway Learning 응답 제한은 8 MiB다. 목록은 UUID 기준 keyset pagination, 이력은 버전 기준 exclusive cursor다.

## 이벤트와 운영

Outbox -> Debezium `learning-outbox` -> Kafka `learning.roadmap.v1`을 사용한다. 종류는 `RoadmapCreated`, `RoadmapUpdated`, `RoadmapGraphChanged`, `RoadmapRetired`다. Envelope의 stable eventId, aggregate/space/actor ID, traceId와 full graph payload가 원본 트랜잭션에 함께 저장된다. at-least-once 전송이므로 향후 consumer는 eventId 또는 aggregate version으로 멱등 처리해야 한다. 지금 단계에는 Roadmap projection consumer가 없다. 단일 도메인 원본 편집 화면은 Learning canonical API를 사용한다.

Apicurio `learning/roadmap-events` v1의 Protobuf 파일 SHA-256은 `a2c1db67ceded3c5c965b92797b2caa0e7224aeb71386e71899d2edd4db5e1a9`다. Registry VALIDITY=FULL, COMPATIBILITY=FULL_TRANSITIVE를 확인했다. CDC role은 Learning Outbox SELECT/복제만 허용하고 Learning 본문 테이블과 다른 12개 owner DB 접근을 거부한다. Connect는 exact Roadmap topic 쓰기, 검증 client는 exact topic과 검증용 group prefix 읽기만 허용한다.

고정 HTTPS backend origin, 인증서 검증, redirect 거부, 전용 Space service key, secure cookie와 쓰기 Origin 검사를 기존 구조와 동일하게 적용한다. 모든 API 응답은 no-store다. 외부 Gateway에 내부 인증 API 경로를 노출하지 않는다. PostgreSQL 실패와 권한 확인 실패는 private 데이터를 반환하지 않는다. HTTP traceparent의 traceId를 Outbox/Kafka header/envelope에 전달하고 Gateway 응답 로그에 연계한다. Learning read/change 종류별 Micrometer counter와 Hikari pool 지표, own-DB readiness를 제공한다. 플랫폼 공통 OpenTelemetry exporter 및 Prometheus/Loki/Tempo/Grafana 중앙 수집 구축은 여전히 플랫폼 후속 범위다.

## 화면과 실행

`https://localhost:8443/graph`에서 **학습 로드맵 열기**를 사용한다. 목록 20개씩 불러오기, 새 로드맵, 제목·설명 편집, 학습 노드 추가·수정·제외, 6종 관계 편집, 그래프 저장, 수정 이력, 폐기를 제공한다. SVG는 방향과 노드를 표시하며 노드를 선택해서 편집할 수 있다. 최종 force-layout/Obsidian 스타일은 후속 디자인 범위다.

화면 변경은 저장 버튼으로만 반영한다. 기존 노드·관계의 제외와 루트 폐기는 별도 확인이 필요하다. 412에서는 초안을 유지하고 최신 canonical graph를 확인한 뒤 명시적으로 기존 초안을 버리고 다시 편집한다. 순환·중복·크기 오류도 초안을 유지한다. 노트·Concept 등의 편집 상태를 수정하지 않는다. Space 변경, 닫기, 권한 상실은 private 상태와 요청을 폐기하며, 늦게 도착한 응답이 다른 선택의 내용을 덮지 않는다. 목록 장애는 이전 목록을 지운다. 브라우저 storage에 로드맵이나 토큰을 쓰지 않는다.

신규 로컬 설치 순서: 기존 플랫폼 DB·Registry·Kafka 준비 -> `Provision-GuestApiSecrets.py` -> Space/Learning 이미지 build/import/`Install-LocalRoadmaps.py` -> Learning Flyway/readiness 확인 -> `Provision-LearningCdc.py` -> brain-connect Helm 적용 -> `Provision-LearningValidationClient.py`와 `Run-LearningConnectorConfiguration.py` -> `Run-RoadmapContractValidation.py` -> Gateway 이미지 build/import/`Install-LocalRoadmaps.py --gateway`. 빌더·import 도구에 Learning을 지원한다. `Start-LocalGateway.ps1`은 localhost:8443의 HTTPS forward를 연다. IP 대신 localhost를 사용한다.

관련 계약: [personal-roadmaps.yaml](../contracts/openapi/personal-roadmaps.yaml), [space-learning-authorization.yaml](../contracts/openapi/space-learning-authorization.yaml), [learning_roadmap_v1.proto](../contracts/events/src/main/protobuf/learning_roadmap_v1.proto).

검증 근거와 Job 이름은 [private-roadmaps-validation.txt](private-roadmaps-validation.txt)에 기록한다. 기존 BrainProjectionIntegrationTest는 실행 중인 프로젝터와 테스트 fixture가 경합하지 않도록 owner DB 안의 별도 임시 스키마를 사용하고 테스트 종료 시 해당 스키마만 정리한다.

2026-10-20 현재 기능: [공개 그래프 검토·병합](public-roadmaps.md), [평점·순위](roadmap-ratings.md), [공개 버전을 개인 학습으로 가져오기](roadmap-adoptions.md)를 제공한다. 가져온 사본도 기존 개인 편집·진도·세션·학습 근거를 사용하고 출처를 보존한다. 앞선 날짜의 범위 설명은 해당 단계의 기록이다.
