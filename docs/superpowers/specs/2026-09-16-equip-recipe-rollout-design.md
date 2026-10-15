# 설계 — 설비 레시피 일괄 배포 시스템 (equip-recipe-rollout)

작성 2026-09-16 · 상태: 사용자 검토 대기

## 1. 무엇이고 왜 만드나

광학부품(마이크로렌즈) 제조사의 클린룸에 성형기가 여러 대 있다. 성형기는 품번마다 운전 조건이
다르고(금형온도·사출압력·보압·냉각시간·사이클타임), 이 조건이 곧 품질이다. 품번을 전환할 때마다
담당자가 설비 콘솔에 하나씩 로그인해 값을 손으로 바꾼다. 설비 수만큼 반복이고, 틀리면 로트 전체가
불량이며, **누가 언제 무엇을 바꿨는지 기록이 남지 않는다.**

**설비 벤더의 내장 웹 콘솔에는 API가 없다.** 그래서 사람이 브라우저로 하던 일을 서버가 브라우저로
대신 한다 — 이 전제가 이 시스템의 존재 이유 전부다.

이 저장소는 **포트폴리오 목적의 재작성본**이다. 실제 사내 코드·데이터·사명은 포함하지 않으며,
도메인은 위 서술대로 가상으로 설정했다.

### 이 시스템이 주장하는 것

1. 설비 N대에 대한 조건 배포를 **부분 실패까지 다루는 잡**으로 만든다.
2. 조작 결과를 **자기 신고로 믿지 않고 다시 읽어 대조**한다.
3. 누가 무엇을 바꿨는지 **감사 가능한 이력**으로 남긴다.
4. 위 셋이 **테스트로 증명**된다 — 씨앗(의도적 결함)을 심으면 해당 테스트가 붉어진다.

## 2. 범위

**포함**: 잡 API · 워커 · Playwright 실행 · 실시간 진행 · 재시도 · 적용 이력 · 3역할 권한 ·
감사 로그 · Prometheus 메트릭 · 구조화 로깅 · 운영 화면 7개 · 목업 설비 콘솔 · compose 데모.

**제외** (의도적):
- 큐 미들웨어(Redis/RabbitMQ) — §11 참조
- Vault 등 외부 시크릿 관리 — 환경변수 + DB로 충분한 규모
- 품질 검사·재고·생산계획 등 MES의 다른 모듈
- 범용 RBAC 엔진(역할·권한 테이블 + 관리 UI) — 역할이 셋이라 순비용
- CI/CD 파이프라인, 멀티 테넌시, 국제화

## 3. 아키텍처

컨테이너 5개. 공유 상태는 PostgreSQL 하나뿐이다.

| 서비스 | 역할 | 경계 |
|---|---|---|
| `console-mock` | **자동화 대상**. 설비 내장 웹 콘솔 흉내 | 우리 시스템이 아니다. 별도 Gradle 모듈 |
| `api` (`--mode=api`) | 잡 생성·조회, 인증, WebSocket 진행 스트림 | **브라우저를 띄우지 않는다** |
| `worker` (`--mode=worker`) | 잡 클레임 → Playwright 실행 → 결과 기록 | **HTTP를 받지 않는다** |
| `web` (Next.js) | 운영 화면 | |
| `postgres` | 유일한 공유 상태 | |

`api`와 `worker`는 서로를 호출하지 않는다. 워커가 상태를 커밋하면 PostgreSQL `LISTEN/NOTIFY`로
`api`가 깨어나 WebSocket으로 밀어 준다. 미들웨어 0개로 실시간이 되고 폴링이 없다.

### 모듈 구조

```
backend/            Kotlin + Spring Boot (api·worker 한 jar, 모드로 분기)
console-mock/       별도 모듈. 서버렌더 HTML + 폼 POST (설비 HMI 흉내)
web/                Next.js (운영 화면)
docs/               설계·운영 문서
```

`console-mock`을 별도 모듈로 둔 이유는 **경계를 빌드 구조로 못박기 위해서다.** 같은 모듈에 있으면
테스트가 내부 구현에 기대게 되고, 「외부 시스템을 브라우저로 조작한다」는 주장이 흐려진다.

### 목업 콘솔은 의도적으로 불안정하다

저장이 일정 확률로 500을 내고, 세션이 만료되며, 폼 로딩이 느려진다. 확률·지연은 환경변수로
조절한다(데모 10%, 테스트에서는 결정적으로 강제). 그래야 재시도·타임아웃·세션 복구가
**테스트로 증명 가능한 진짜 기능**이 된다.

## 4. 데이터 모델

| 테이블 | 핵심 컬럼 |
|---|---|
| `app_user` | `username` · `password_hash`(bcrypt) · `role`(OPERATOR/APPROVER/ADMIN) · `enabled` |
| `equipment` | `code`(EQ-01) · `console_url` · `model` · `status`(RUNNING/STOPPED/MAINTENANCE) · 콘솔 계정 |
| `recipe` | `part_no`(LNS-2040) · `version` · `params`(jsonb) · `status`(DRAFT/ACTIVE/RETIRED) · `created_by` · `approved_by` |
| `rollout_job` | `recipe_id` · `status` · `idempotency_key`(unique) · `requested_by` · 시각 |
| `rollout_task` | `job_id` · `equipment_id` · `status` · `attempt` · `last_error` · `claimed_by` · `lease_expires_at` |
| `application_history` | `equipment_id` · `part_no` · `version` · `params_snapshot`(jsonb) · `applied_at` · `applied_by` · `result`(APPLIED/NO_CHANGE) |
| `audit_log` | `at` · `actor_id` · `actor_name`(스냅샷) · `action` · `target_type` · `target_id` · `detail`(jsonb) · `result`(OK/DENIED/ERROR) |

`recipe.params` 예: `{"mold_temp_c":128,"inject_pressure_mpa":92,"hold_pressure_mpa":45,"cooling_sec":12,"cycle_sec":38}`

### `application_history`와 `audit_log`를 합치지 않는 이유

- `application_history` = **설비에 무엇이 적용됐나**. 제조 이력이고 성공한 적용만 남는다.
- `audit_log` = **누가 무엇을 시도했나**. 로그인 실패·권한 거부·취소까지 남는다.

거부된 시도가 안 남으면 감사 로그가 아니다. 성공하지 않은 적용이 제조 이력에 섞이면 이력이 아니다.

## 5. 잡 수명주기

1. **생성** — `api`가 `rollout_job` + `rollout_task` N개를 한 트랜잭션에 INSERT.
   `Idempotency-Key` 헤더가 같으면 기존 잡을 반환한다(중복 생성 없음).
2. **클레임** — 워커가
   `SELECT ... WHERE status='PENDING' OR (status='CLAIMED' AND lease_expires_at < now())
    ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1`
   로 한 건을 집고 `CLAIMED` + `claimed_by` + `lease_expires_at = now() + 2분`.
3. **실행** — 브라우저 세마포어 획득 → 로그인(세션 재사용) → 레시피 폼 입력 → 저장 →
   **저장 후 재조회로 실제 반영값 대조**.
4. **성공** — `task=SUCCEEDED` + `application_history` INSERT를 **같은 트랜잭션**에. 그리고 `NOTIFY`.
5. **실패** — `attempt++`. 재시도 가능이면 지수 백오프 후 `PENDING` 복귀, 상한(3회) 초과면 `FAILED`.
6. **집계** — 모든 task 종료 시 job이 `SUCCEEDED` / `PARTIAL` / `FAILED`.
7. **하트비트** — 실행 중 리스를 30초마다 연장. **워커가 죽으면 리스가 만료돼 다른 워커가 회수한다.**
   별도의 크래시 복구 로직은 없다 — 회수가 곧 복구다.

### 클레임 단위는 잡이 아니라 task(설비 1대)

그래야 워커 여럿이 같은 잡의 설비를 나눠 갖고, 부분 실패가 자연스럽게 표현되며, 재시도가
설비 단위로 끊긴다.

### 3번의 「저장 후 재조회」가 품질 주장이다

저장 버튼을 눌렀다는 것과 값이 실제로 들어갔다는 것은 다르다. 조작 주체의 자기 신고를 믿지 않고
독립적으로 다시 읽어 대조하며, 불일치는 실패로 처리한다.

## 6. 동시성 2층

| 층 | 무엇을 보장 | 수단 |
|---|---|---|
| DB | **한 task는 한 워커만** | `FOR UPDATE SKIP LOCKED` + 리스 만료 |
| 워커 내 | **동시 브라우저 세션 ≤ N** | `Semaphore(N)`, 기본 N=3 |

두 층 모두 테스트로 단언한다. 동시성 단언은 **총 호출 수 같은 대리지표를 쓰지 않고
peak 동시 점유를 직접 세어 등호로 단언**한다(대리지표는 하네스 타이밍의 우연에 기대게 된다).

## 7. 실패 분류와 멱등성

**재시도 O**: 타임아웃 · 5xx · 세션 만료(재로그인 후 1회) · 네트워크 오류
**재시도 X**: 콘솔이 값을 거부(유효성 오류) · 설비가 `MAINTENANCE` 상태 · 인증 정보 오류

분류를 틀리면 무한 재시도이거나 조기 포기다. 이 표는 코드와 테스트 양쪽에 존재한다.

**멱등성 2층**
1. 요청 — `Idempotency-Key`로 같은 잡이 두 번 만들어지지 않는다.
2. 적용 — 이미 같은 `part_no`+`version`이 적용된 설비는 재적용을 건너뛰고
   `application_history.result = NO_CHANGE`로 남긴다. 재시도가 이력을 오염시키지 않는다.

## 8. 권한과 감사

### 역할 매트릭스 (고정 3역할)

| 행위 | OPERATOR | APPROVER | ADMIN |
|---|---|---|---|
| 설비·레시피·잡 조회 | O | O | O |
| 레시피 초안 생성·수정 | O | O | X |
| **레시피 활성화(승인)·폐기** | X | O | **X** |
| 배포 잡 생성 (ACTIVE 레시피만) | O | O | X |
| 잡 취소·재시도 | 자기 잡만 | 전부 | 전부 |
| 감사 로그 조회 | X | O | O |
| 사용자·설비 등록·수정 | X | X | O |

**ADMIN이 레시피를 승인할 수 없다.** 시스템 관리자가 품질 승인까지 하면 직무 분리가 깨진다 —
계정을 쥔 사람이 조건을 바꿔 밀어 넣을 수 있게 된다.

**4-eyes**: 자기가 만든 레시피는 자기가 승인할 수 없다(APPROVER라도).

역할은 enum으로 고정하고 정책 검사는 한 곳에서만 한다. 역할·권한을 DB로 관리하는 엔진은 만들지
않는다 — 역할이 셋인데 테이블 3개와 관리 UI를 더하면 테스트와 감사가 어려워지기만 한다.

### 실행 주체 구분

브라우저를 실제로 조작하는 것은 워커지만, `application_history.applied_by`는 **잡을 요청한 사람**이다.
워커는 대행자이고, 감사 로그에는 `actor=system, on_behalf_of=<요청자>`로 남는다.

### 감사 불변식 (전부 테스트로 단언)

1. 쓰기 엔드포인트는 **실패·거부를 포함해** 감사 로그를 남긴다.
2. `audit_log`는 append-only — 애플리케이션 경로에 UPDATE/DELETE가 없다.
3. **자격증명·토큰은 `detail`에 들어가지 않는다.**
4. `actor_name`은 당시 이름 스냅샷 — 사용자가 개명해도 과거 이력이 바뀌지 않는다.

설비 콘솔 자격증명(우리 사용자 계정과 다른 것)은 설비별로 DB에 두며, 응답·로그·감사 어디에도
노출되지 않는다. 불변식 3이 그것을 지킨다.

## 9. 테스트 전략

| 층 | 대상 | 수단 |
|---|---|---|
| 단위 | 실패 분류기 · 백오프 · 멱등 키 · 권한 정책 | 순수 함수 |
| 통합(실DB) | 클레임 경합 · 리스 만료 회수 · 이력과 상태의 트랜잭션 원자성 | **Zonky 임베디드 PostgreSQL** |
| 인가 | 3역할 × 7행위 + 경계 2개 전수 | `@ParameterizedTest` |
| E2E | 실제 브라우저로 목업 콘솔 조작 | Playwright + 랜덤 포트 목업 |
| 씨앗 | 불변식마다 결함을 심어 **테스트가 실제로 붉어지는지** | 스크립트 |

**Docker 없이 `./gradlew test`가 통과한다** — Zonky가 번들 PostgreSQL을 띄운다. 읽는 사람이
바로 돌려볼 수 있어야 한다.

Zonky는 다음 설정이 필수다(미설정 시 실패하거나 소켓 문제로 오진을 유도한다):
`initdbFlags=['--locale=C.UTF-8']`, `unix_socket_directories=`(비활성), `listen_addresses=127.0.0.1`.

### E2E 시나리오 5개

1. 성공 경로 — 12대 전부 적용, 이력 12건
2. 500 주입 → 재시도 후 성공
3. 세션 만료 → 재로그인 후 이어감
4. **값 거부는 재시도하지 않는다** (attempt가 1에서 멈춘다)
5. **동시 브라우저 세션이 N을 넘지 않는다** (peak 직접 측정, 등호 단언)

### 씨앗 목록

| 심는 결함 | 붉어져야 하는 테스트 |
|---|---|
| `SKIP LOCKED` 제거 | 클레임 경합 |
| 저장 후 재조회 대조 제거 | 반영 검증 |
| 감사 기록 제거 | 감사 불변식 1 |
| 리스 갱신 제거 | 회수 |
| 세마포어 상한 제거 | 동시성 2 |
| 4-eyes 검사 제거 | 인가 경계 |

씨앗을 심었는데 초록이면 그 테스트는 아무것도 지키지 않는 것이다.

## 10. 관측 · 화면 · 데모

### 메트릭·로깅

- `rollout_task_duration_seconds`(히스토그램) · `rollout_task_total{result}` ·
  `browser_sessions_active`(게이지) · `lease_expired_recovered_total`
- 구조화 JSON 로깅 + `job_id`·`task_id` MDC — 잡 하나의 전 생애를 한 번에 뽑을 수 있다
- 워커 헬스체크는 「클레임 루프가 살아 있는지」를 포함한다
- Prometheus·Grafana 컨테이너는 넣지 않는다. `/actuator/prometheus`만 노출하고 README에
  스크레이프 예시를 둔다(데모 실행 부담을 올리지 않는다)

### 운영 화면 7개

로그인 · 설비 목록(상태·마지막 적용 레시피) · 레시피 목록/상세(승인 버튼은 APPROVER에게만) ·
배포 잡 생성(레시피 + 설비 다중 선택) · **잡 상세 실시간 진행**(설비별 단계·재시도) ·
적용 이력 타임라인 · 감사 로그.

### 타이포그래피 (확정값)

```
본문·UI:  Pretendard 400/500/600/700 — next/font/local, woff2 셀프호스팅
수치·ID:  MonoPlexKR 400/500/700 — 설비코드·잡ID·파라미터·로그
폴백:     system-ui, -apple-system, "Malgun Gothic", sans-serif
숫자:     font-feature-settings: "tnum"
줄바꿈:   word-break: keep-all
```

셀프호스팅은 취향이 아니라 요건이다 — `docker compose up` 데모는 **인터넷 없이도 폰트가 떠야 한다.**
라이선스: Pretendard(OFL) · MonoPlexKR — 구현 시 원 라이선스를 확인해 README에 고지한다.

### 데모

`docker compose up` 한 번. 시드: 설비 12대 · 품번 3종(각 v1~v3) · 계정 3개
(`operator1`/`approver1`/`admin1`) · 목업 실패율 10%.

README 첫머리에 60초 시나리오를 둔다: 로그인 → 레시피 승인(approver) → 12대 배포 →
진행이 실시간으로 차오르고 1~2대 실패 → 재시도 → 성공 → 이력·감사 로그 확인.

## 11. 확장 지점 (만들지 않되 문서로 남긴다)

- **큐 미들웨어로 옮기는 조건**: ① 초당 수백 건 이상 ② 다중 소비자(여러 서비스가 같은 이벤트 구독)
  ③ 이종 언어 워커. 현재는 셋 다 해당하지 않고, 잡 상태가 이미 DB에 있어야 하므로 큐를 더하면
  **같은 사실이 두 곳에 살게 되어** 정합성 코드가 생긴다.
- **인증을 SSO/AD로 교체하는 지점**: 인증 필터와 `app_user` 조회 한 곳. 권한 매트릭스는 그대로 둔다.
- **설비 콘솔이 API를 제공하게 되면**: 워커의 실행 어댑터만 교체한다. 잡·이력·감사는 영향이 없다.

## 12. 리스크와 천장

- **Playwright 이미지가 크다.** compose 데모의 첫 pull이 오래 걸린다. README에 예상 시간을 적는다.
- **목업 콘솔은 실제 설비 HMI가 아니다.** 실물은 더 느리고 더 이상하게 실패한다. 이 시스템이
  실증한 것은 「그런 실패를 다루는 구조」이지 「실물에서 동작함」이 아니다.
- **n=1 도메인이다.** 성형기 한 종류, 조건 5개뿐이다. 설비 기종별 콘솔 차이(셀렉터·흐름)는
  다루지 않는다 — 실무에서는 그게 가장 큰 비용이고, 어댑터 인터페이스만 열어 둔다.
- **포트폴리오 목적이다.** 운영 규모(수백 대, 다중 라인, 교대조)를 가정하지 않는다.
