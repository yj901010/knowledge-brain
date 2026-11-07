# 공개 로드맵을 개인 학습으로 가져오기

2026-10-20. 공개 로드맵 상세에서 **내 공간에 가져오기 열기** → 개인 공간 선택 →
공개 버전 확인 → **이 버전 가져오기**를 사용한다. 게스트와 등록 계정 모두 지원한다.
**가져온 로드맵 학습 열기**에서 기존 진도·학습 기록·노트/Concept/파일 근거 기능을
사용한다. `/graph`의 내 학습 로드맵 목록에도 나타난다. 개인 상세의
**로드맵 출처 확인**으로 공개 원본 ID·버전·제목·가져온 시각을 확인한다.

## 버전과 개인정보

- Learning이 공개 원본의 현재 버전을 잠그고 제목·설명·노드·관계를 새 UUIDv7의
  비공개 로드맵 v1로 만든다. 노드·관계 원본→개인 ID 매핑과 출처는 불변 보존한다.
- 이후 개인 편집과 진도는 독립적이다. 원본 검토 상태를 개인 사본의 검증으로
  표시하지 않는다. 공개 원본이 수정되어도 기존 개인 그래프·진도를 자동 변경하지 않는다.
- 최신 버전은 다시 확인하고 별도로 가져온다. 새 개인 로드맵/노드이므로 진도는 새로 시작한다.
  버전 간 자동 병합·진도 이전·공개 사용량/완료율 집계는 후속 범위다.
- 같은 개인 공간·원본·버전은 한 번만 생성한다. 재시도는 기존 출처/ID를 반환한다.
  게스트 공간 귀속으로 actor가 바뀌어도 유지된다. 개인 로드맵을 폐기했어도 재시도는
  기존 ID를 반환하며 되살리거나 중복 생성하지 않는다. 이력은 기존 UI에서 볼 수 있다.
- 최초 요청은 확인한 현재 버전만 허용한다(409). 이미 성공한 요청은 공개 버전이
  나중에 바뀌었어도 같은 결과를 반환해 응답 유실을 복구한다.
- 개인 로드맵·학습 기록·근거·출처는 개인 공간 권한으로만 조회한다. 공개 원본에
  개인 ID/진도/가져온 사람 목록을 추가하지 않는다. 공개 평점·검토와도 독립적이다.

## 소유권과 일관성

AGENTS/PROJECT의 Learning 소유권, 개인 비공개 기본값, UUIDv7, local ACID+Outbox,
검토와 인기도 분리에 부합한다. 기존 결정 변경이나 새 의존성/데이터셋은 없다.
V9 roadmap_adoption과 FK는 Learning DB 내부에만 존재한다. Space DB 접근은 없다.
공개 root 잠금을 병합과 공유하고, 개인 graph/root/revision/provenance/두 Outbox가
한 transaction으로 commit/rollback한다. 동시에 가져오기는 하나만 생성한다.
출처 매핑은 최초 개인 ID를 보존한다. 개인 노드 제외/추가 이후 현재 노드와 혼동하거나
다른 노드에 과거 진도를 재배정하지 않는다.

기존 ROADMAP_WRITE와 PUBLIC_ROADMAP_READ를 매 요청 Space authority로 확인한다.
같은 브라우저 credential이 두 확인에서 같은 principal이어야 한다. 등록 강제/검토자
권한은 필요 없다. POST는 HTTPS·Origin·strict JSON 2048bytes/세 필드·query 금지,
GET도 body/query 금지다. 모든 응답 no-store. 오류는 계약에 명시한다.

계약: [personal-roadmap-adoptions.yaml](../contracts/openapi/personal-roadmap-adoptions.yaml).
기존 private RoadmapCreated는 v1 전체 그래프를 담으며 Activity의 기존 흐름을 따른다.
새 [learning_roadmap_adoption_v1.proto](../contracts/events/src/main/protobuf/learning_roadmap_adoption_v1.proto)는
비공개 출처 이벤트 `learning.roadmap-adoption.v1`, Registry
`learning/roadmap-adoption-events` V1이다. envelope11필드와 stable eventId를 사용한다.
소비자는 adoptionId/eventId로 멱등 처리하고 서로 다른 토픽의 순서를 가정하면 안 된다.
새 출처 stream은 Activity20 export/recovery에 추가하지 않는다. 별도 consumer는 없다.
기존 private7/public2/rating 계약·CDC config/slot/checkpoint는 유지한다.
Connect write/validator read literal ACL과 Registry FULL/FULL_TRANSITIVE를 사용한다.

`brain.learning.adoption.requests{result=created|existing}`, `brain.learning.adoption.reads`,
기존 Hikari/readiness와 roadmap ID/trace 로그를 제공한다. 중앙 관측 스택 구축은
기존 후속 범위다. 개인 값/token은 로그에 기록하지 않는다.

## 검증과 실행

`Run-BackendValidation.py --learning --learning-kafka`: 실제 PostgreSQL 복사/매핑,
동시성·멱등 재시도·공간 격리·개인 폐기·원본 업데이트·Outbox rollback 및 실제 Kafka bytes/header.
`Build-GraphWeb.py`: TypeScript/Next 정적 export, pinned dependency/license/CSP.
`Install-LocalRoadmapAdoptions.py --helm <HELM_EXE>`: 정확한 Learning/Gateway 이미지의
양 노드 import와 토픽/ACL/Registry 선행 확인 후 로컬 배포.
`Run-GraphWebValidation.py --roadmap-adoptions-only --no-images`: 실제 guest 개인 가져오기/
진도 및 UI 오류·버전 충돌·늦은 응답. `--public --public-only`는 실제 두 Keycloak 계정의
공개 게시/등록 계정 가져오기/원본 수정 독립성까지 확인한다.
원본/Inbox/PVC/topic/CDC 슬롯 삭제·전체 Activity 재생은 필요하지 않다.
실제 실행 근거는 roadmap-adoptions-validation.txt에 기록한다.
