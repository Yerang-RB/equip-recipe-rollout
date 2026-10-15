# 설비 레시피 일괄 배포 시스템

공장 설비의 **웹 콘솔을 브라우저로 조작해** 운전 조건(레시피)을 여러 대에 한 번에 배포하고,
진행을 실시간으로 보여 주고, 실패한 설비만 다시 시도하고, 누가 무엇을 바꿨는지 감사 가능한 이력으로 남깁니다.

## 왜 브라우저 자동화인가

성형기 같은 설비는 품번마다 운전 조건이 다르고(금형온도·사출압력·보압·냉각시간·사이클타임), 그 조건이 곧 품질입니다.
품번을 전환할 때마다 담당자가 설비 콘솔에 하나씩 로그인해 값을 손으로 바꿉니다.
설비 수만큼 반복이고, 틀리면 로트 전체가 불량이며, **누가 언제 무엇을 바꿨는지 기록이 남지 않습니다.**

그리고 **설비 벤더의 내장 웹 콘솔에는 API가 없습니다.** 사람이 브라우저로 하던 일을 서버가 브라우저로 대신 하는 것이
이 시스템의 존재 이유입니다. 자동화 대상인 설비 콘솔도 이 저장소가 함께 구현합니다(`console-mock` — 의도적으로 느리고 가끔 실패합니다).

> 포트폴리오 목적의 구현입니다. 도메인은 가상이고 실제 회사의 코드·데이터는 포함하지 않습니다.

## 이 코드가 주장하는 것

| 주장 | 어디서 | 지키는 테스트 |
|---|---|---|
| 배포를 **부분 실패까지 다루는 잡**으로 만든다 | 설비 1대 = task 1개, 잡은 SUCCEEDED/PARTIAL/FAILED로 집계 | `JobServiceTest` · E2E 5종 |
| **한 task는 한 워커만** 집는다 | `SELECT … FOR UPDATE SKIP LOCKED` + 리스 만료 회수 | `ClaimContentionTest` · `LeaseTest` |
| **동시 브라우저 세션이 상한을 넘지 않는다** | 워커 안 `Semaphore(N)` — peak 점유를 **직접 세어 등호로** 단언(대리지표 금지) | `BrowserConcurrencyE2ETest` |
| 조작 결과를 **자기 신고로 믿지 않는다** | 저장 후 **재조회로 실제 반영값 대조**, 불일치는 실패 | `ApplyVerificationTest` |
| **크래시가 무한 재시도가 되지 않는다** | 리스 회수에도 상한, 상한 소진은 FAILED로 닫음 | `CrashedTaskCapTest` · `CrashingWorkerE2ETest` |
| **권한이 실제로 막힌다** | 3역할 × 7행위 전수 + 4-eyes + 직무 분리(ADMIN은 승인 불가) | `AuthorizationMatrixTest`(21셀 등호) |
| **감사가 거부까지 남는다** | append-only 트리거, 자격증명은 절대 안 실림 | `AuditInvariantTest` |

`application_history`(설비에 무엇이 적용됐나)와 `audit_log`(누가 무엇을 시도했나)는 **합치지 않습니다.**
거부된 시도가 안 남으면 감사 로그가 아니고, 성공하지 않은 적용이 제조 이력에 섞이면 이력이 아니기 때문입니다.

## 구조

```
backend/        Kotlin + Spring Boot — api·worker 한 jar, --mode으로 분기
console-mock/   자동화 «대상» (서버렌더 HTML). 별도 모듈로 경계를 빌드 구조에 못박습니다
web/            Next.js 운영 화면 7개
```

| 서비스 | 역할 | 경계 |
|---|---|---|
| `api` | 잡·레시피·설비·감사 REST, WebSocket 진행 스트림 | **브라우저를 띄우지 않습니다** |
| `worker` | 잡 클레임 → Playwright로 콘솔 조작 → 결과 기록 | **HTTP를 받지 않습니다** |
| `console-mock` | 설비 내장 콘솔 흉내(실패·세션만료 주입) | 우리 시스템이 아닙니다 |
| `web` | 운영 화면 | |
| `postgres` | **유일한 공유 상태** | |

두 프로세스는 서로를 호출하지 않습니다. 워커가 상태를 커밋하면 PostgreSQL `LISTEN/NOTIFY`로 api가 깨어나 WebSocket으로 밀어 줍니다 —
미들웨어 0개로 실시간이 되고 폴링이 없습니다.

## 갈렸던 설계 결정 셋

- **큐 미들웨어를 두지 않았습니다.** 잡 상태는 어차피 DB에 있어야 하는데(진행 조회·이력·감사) 큐를 더하면 같은 사실이 두 곳에 살고
  그 둘을 맞추는 코드가 생깁니다. DB를 큐로 쓰면 클레임과 상태 갱신이 **같은 트랜잭션**입니다.
  옮겨야 하는 조건(초당 수백 건 · 다중 소비자 · 이종 언어 워커)은 설계 §11에 적어 뒀습니다.
- **역할은 enum 3개로 고정했습니다.** 역할·권한을 DB로 관리하는 엔진은 만들지 않았습니다 — 역할이 셋인데 테이블과 관리 UI를 더하면
  테스트와 감사만 어려워집니다. 대신 **매트릭스 전체를 전수 테스트**로 덮었습니다.
- **ADMIN에게 레시피 승인 권한을 주지 않았습니다.** 계정을 쥔 사람이 품질 조건을 몰래 바꿔 밀어 넣지 못하게 하는 직무 분리입니다.

## 검증

```bash
scripts/test.sh          # 백엔드 87건 — Docker 없이 돕니다(Zonky 임베디드 PostgreSQL)
scripts/smoke.sh         # api 실기동 종단 스모크
bash scripts/deploy-gates.sh   # compose 구성 정적 점검
```

테스트가 실제로 무엇을 지키는지는 **씨앗(의도적 결함)으로 확인했습니다** — `SKIP LOCKED` 제거, 재조회 대조 제거, 감사 기록 제거,
리스 갱신 제거, 세마포어 상한 제거, 4-eyes 검사 제거를 하나씩 심어 대응 테스트가 붉어지는 것을 보고 복원했습니다(`PROGRESS.md` 「씨앗 표」).
심었는데 초록이면 그 테스트는 아무것도 지키지 않는 것이기 때문입니다.

## 60초 시나리오

```bash
SEED_PASSWORD='데모용-비밀번호' POSTGRES_PASSWORD='db-비밀번호' docker compose up --build
```

두 값에는 기본값이 없습니다 — 하나라도 빠지면 compose가 시작 전에 멈춥니다. `SEED_PASSWORD`는 데모 계정 셋과 목업 콘솔 계정의 비밀번호가 됩니다.
다섯 서비스(postgres · console-mock · api · worker · web)가 healthy가 되면 <http://localhost:3000>을 엽니다.

1. **로그인** — `approver1` / `SEED_PASSWORD`.
2. **승인** — 레시피 화면에서 `LNS-2040 v3`(초안, 작성자 `operator1`)을 승인합니다. 자기가 만든 초안은 승인할 수 없습니다(4-eyes).
3. **배포** — 로그아웃 후 `operator1`로 로그인 → 새 잡 → `LNS-2040 v3` + 설비 `EQ-01`~`EQ-11` 선택.
   `EQ-12`는 MAINTENANCE라 고를 수 없습니다.
4. **실시간 진행** — 잡 화면이 WebSocket으로 설비별 상태를 갱신합니다. 워커는 브라우저를 3개까지 동시에 엽니다.
5. **실패** — 목업 콘솔은 저장의 10%를 500으로 실패시킵니다(`MOCK_FAIL_RATE`, 기본 0.10). 재시도 가능 실패라 대부분 자동
   재시도(총 3회)에 흡수되어 시도 횟수 2로만 보이고, 최종 실패는 드뭅니다. 실패로 끝나는 설비를 확실히 보려면 실패율을 올려 띄웁니다:
   `MOCK_FAIL_RATE=0.7 SEED_PASSWORD=… POSTGRES_PASSWORD=… docker compose up` — 11대 중 몇 대가 3회 모두 실패해 잡이 PARTIAL이 됩니다.
6. **재시도** — 콘솔을 정상으로 되돌리고(`MOCK_FAIL_RATE=0 SEED_PASSWORD=… POSTGRES_PASSWORD=… docker compose up -d console-mock`)
   잡 화면에서 재시도합니다. 실패·취소된 task만 처음부터 다시 돕니다.
7. **이력·감사** — 이력 화면에 설비별 적용 기록(APPLIED/NO_CHANGE)이, 감사 화면(`approver1`·`admin1`)에 로그인·승인·
   배포·워커 대행 적용(`system`, 요청자 id 포함)이 남습니다.

목업 콘솔 화면은 <http://localhost:8090/EQ-01/recipe>(계정 `console` / `SEED_PASSWORD`)에서 직접 볼 수 있습니다.
게시 포트는 전부 `127.0.0.1`에만 열립니다(3000 · 8080 · 8090).

**첫 실행 시간**: worker·api 이미지의 베이스가 Playwright Java(`mcr.microsoft.com/playwright/java:v1.62.0-noble`, 크로미엄 포함)라 큽니다.
첫 pull과 이미지 빌드(gradle·npm 의존성 다운로드 포함)는 회선에 따라 수 분에서 10분 이상 걸릴 수 있다 — 추정치이고 이 저장소의
자율 루프는 docker를 실행하지 못해 재지 못했습니다. 두 번째부터는 캐시로 수십 초 안에 뜹니다.

## 메트릭

워커가 `rollout_task_duration_seconds` · `rollout_task_total{result}` · `browser_sessions_active` · `lease_expired_recovered_total`을 냅니다.
워커 포트(8081)는 게시하지 않으므로 compose 네트워크 안에서 긁습니다:

```yaml
# prometheus.yml (같은 compose 네트워크에 Prometheus를 붙일 때)
scrape_configs:
  - job_name: rollout
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["worker:8081", "api:8080"]
```

바로 확인: `docker compose exec worker curl -s localhost:8081/actuator/prometheus | grep -E '^(rollout_|browser_|lease_)'`

## 로컬 개발

- `scripts/test.sh` — 전체 테스트(임베디드 postgres, docker 불필요) · `scripts/smoke.sh` — api 실기동 종단 스모크
- `scripts/dev-up.sh` — api 8080 · worker 8081 · console-mock 8090 · next dev 3000을 로컬에서 띄웁니다(시드 없음)
- `scripts/deploy-gates.sh` — compose 구성 정적 점검(비밀값 필수 · 게시 포트 127.0.0.1 · 번들 env · 기본 자격증명)
- 나머지 명령과 구조는 [`CLAUDE.md`](CLAUDE.md)를 보세요

## 확장 지점과 천장

- 확장 지점(큐 미들웨어로 옮기는 조건 · SSO 교체 지점 · 콘솔 API 어댑터): 설계 [§11](docs/superpowers/specs/2026-09-16-equip-recipe-rollout-design.md#11-확장-지점-만들지-않되-문서로-남긴다)
- 리스크와 천장(이미지 크기 · 목업은 실물 HMI가 아님 · n=1 도메인): 설계 [§12](docs/superpowers/specs/2026-09-16-equip-recipe-rollout-design.md#12-리스크와-천장)
- 운영 전 재검토 항목(콘솔 자격증명 평문 저장 등)은 `PROGRESS.md` 「리스크 원장」에 있습니다.

## 어떻게 만들었나

설계 문서를 먼저 쓰고([`docs/superpowers/specs/`](docs/superpowers/specs/2026-09-16-equip-recipe-rollout-design.md)),
그 문서를 정본으로 구현했습니다. 구현은 자율 루프([loop-runner](https://github.com/Yerang-RB/loop-runner))가 돌렸고,
독립 리뷰어 2인 + 마감 리뷰가 발견 38건을 냈습니다.
루프 안에서 27건을 닫았고(수정 19 · 리스크 원장 6 · 오탐 2), 마감 리뷰 11건 중 7건을 그 뒤에 닫았습니다(수정 5 · 원장 2).
남은 4건은 「참고」 등급이고 **미처리인 채로 이 문서와 원장에 적혀 있습니다.**
각 수정은 **재현 테스트를 먼저 붉게 만든 뒤** 했습니다.

무엇이 언제 왜 그렇게 됐는지는 [`PROGRESS.md`](PROGRESS.md)에, 리뷰 원본과 처리표는
[`docs/review/`](docs/review/)에 그대로 있습니다. 고치지 않고 남긴 것도 「리스크 원장」에 실서버 시나리오와 함께 적었습니다 —
알고 남긴 것과 모르고 빠뜨린 것은 다르기 때문입니다.

## 서체 라이선스

화면은 서체 두 벌을 셀프호스팅합니다(인터넷 없이 뜹니다). 둘 다 SIL Open Font License 1.1입니다.

- **Pretendard** — 수정 없이 그대로 배포합니다.
- **Rollout Mono** — OFL 서체의 **수정본**(서브셋 · woff2 변환)입니다. OFL의 Reserved Font Name 조항 때문에 수정본은 원래 이름을 쓸 수 없어
  이름을 바꿨습니다.

원저작권 표기 · 원본 링크 · 수정 내역은 [`web/app/fonts/NOTICE.md`](web/app/fonts/NOTICE.md)에 있습니다.
