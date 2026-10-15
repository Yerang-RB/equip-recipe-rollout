# CLAUDE.md — 설비 레시피 일괄 배포 시스템

설비 콘솔(웹 UI)에 레시피를 브라우저 조작으로 일괄 배포하는 시스템. 정본 설계는
`docs/superpowers/specs/2026-09-16-equip-recipe-rollout-design.md`이고, 작업 규칙은 `PLAN.md` `## 불변 조건`이다.
둘이 어긋나면 설계가 이긴다.

## 스택 (실측 — 이 상자에서 실제로 해석·컴파일·통과한 것)

Kotlin 2.4.20 · Spring Boot 4.1.1 · Gradle 9.5.1 · JDK 21(`~/.local/jdk-21.0.11+10`) · PostgreSQL(운영) ·
테스트는 zonky embedded-postgres 2.2.2 + binaries BOM 18.6.0 · Playwright Java 1.62.0 · 프론트는 `web/`(Next.js, 작업 8).
ORM은 쓰지 않는다 — `JdbcClient`/`JdbcTemplate` + 파라미터 바인딩.

## 모듈

- `backend/` — api·worker 한 jar. `mode` 프로퍼티가 역할을 정하고(`api`|`worker`), 없으면 기동이 실패한다
  (`ModeConfiguration`). api 빈은 `@ConditionalOnProperty(mode=api)`, 워커 빈은 `mode=worker`.
- `console-mock/` — 설비 콘솔 목업(별도 Gradle 모듈, 패키지 `com.equipconsole` — `com.rollout` 아래에 두면 backend 스캔이 빨아들인다).
  경로 첫 마디가 설비다(`/{station}/login`·`/{station}/recipe`). backend 운영 코드는 import하지 않는다(`testImplementation`만).
- `web/` — 운영 화면(Next.js).

## 명령 (검증은 스크립트로만 — `./gradlew`를 직접 부르지 않는다)

- `scripts/test.sh` — 전체 테스트(`test --rerun`). `scripts/test.sh --tests 'com.rollout.XTest.method'`는 `:backend:test` 지목.
  두 경우 모두 「이번 실행에 새 결과 XML이 없거나 실제로 돈 건수가 0이면 exit 1」 자기점검이 붙어 있다.
- `scripts/smoke.sh` — 실서버 기동 스모크(`${SMOKE_PORT:-18080}`, api 모드, `/api/health` 200, 부팅 로그 감사, 종료 확인).
- `scripts/gradlew-sbx.sh` — 샌드박스 안에서 gradle을 돌리는 프록시 래퍼(바깥에서는 그대로 위임).
- `scripts/design-gates.sh` — 프론트 정적 게이트(서체 앵커·keep-all·tnum·외부 URL·한다체·`NEXT_PUBLIC_` 등). 씨앗 표는 `PROGRESS.md` 작업 8.
- `scripts/web-smoke.sh` — api(`${SMOKE_PORT:-18080}`) + web(`next build && next start`, `${WEB_PORT:-13000}`) 실기동 → 화면 7 SSR 셸·자산·서체 CSS·rewrite·`/ws-origin`.
- `scripts/dev-up.sh` — 사람 판독용 실기동(api 8080 · worker 8081 · console-mock 8090 · next dev 3000). `--check`는 넷 200 확인 후 내린다.
- `scripts/deploy-gates.sh` — `compose.yaml`·Dockerfile 3개의 정적 점검(비밀값 `:?` 필수 · 게시 포트 전부 `127.0.0.1` · `NEXT_PUBLIC_`·이미지 비밀번호 ENV 없음 · 시드 계정 이름은 `DemoSeed.kt` 한 파일). `docker compose up`은 샌드박스가 못 돈다 — 사람 몫.
- 스모크는 로그인 3역할 → 설비 등록 → 다른 사람 승인 → 잡 멱등 → WS 101/401 → 401·403 → 로그 비밀번호 부재까지 밟는다. 앱 종료는 `--server.port=$PORT` 인자로 찾는다.
- `web/`에서 `npm test`(순수 함수 `node --test`) · `npx tsc --noEmit`.

## 구조·컨벤션

- 스키마의 주인은 Flyway(`backend/src/main/resources/db/migration/`). 변경은 새 `V파일`로만.
  상태 열거는 CHECK, 유일성은 UNIQUE, `audit_log` append-only는 트리거가 막는다 — 각 제약은 `SchemaConstraintTest`가
  앱을 우회한 JDBC 문장으로 존재를 못박는다(SQLSTATE 23514/23505/P0001).
- REST 계약의 정본은 `backend/src/main/resources/openapi.yml`이다 — 엔드포인트·상태코드를 늘리려면 계약을 먼저 고친다.
  인가·무인증 401·감사 전수 테스트는 이 파일에서 (경로, 메서드)를 읽어 돌므로, 계약에 없는 엔드포인트는 테스트가 못 본다.
  → 상세: `PROGRESS.md` "2026-09-16 · 작업 5"
- 인가 검사는 `com.rollout.api.Policy` 한 곳뿐이다(3역할 × 7행위 + 자기 잡 조건 + 4-eyes). 초안을 고친 사람이 `created_by`가 된다(4-eyes 우회 방지).
- 감사는 api 쓰기 요청을 `AuditFilter` 한 자리에서 기록한다(컨트롤러에서 부르지 않는다). 정규화는 **디코딩한 경로** 기준이고 `action`·`target_type`은
  열 너비 50으로 자른다 — 어기면 기록이 조용히 빠진다. 워커 대행 기록은 필터 밖이다: `TaskOutcomeService`가 `AuditWriter.writeSystem`
  (`actor_name='system'`, `detail.on_behalf_of`)을 task 전이와 같은 트랜잭션에서 쓴다. → 상세: `docs/review/findings-handled.md` AR1·B3
- 요청 DTO의 계약 제약(`minLength`·`maxLength`·`pattern`)은 DTO `init`의 `require`/`requireText`로 강제한다 → 400 `INVALID_REQUEST`(검증 의존성 없음).
- 워커의 `claimed_by`는 클레임 **시도마다** 새 id다(`worker-xxxx-<순번>`) — 스레드끼리 id를 나누면 소유권 검사가 무력하다.
- 데모 시드는 `DemoSeed`(`app.seed.demo=true` + `SEED_PASSWORD` 필수, 멱등). compose는 5 서비스이고 컨테이너 안 바인드만 `SERVER_ADDRESS=0.0.0.0`이다.
- 시각은 `Clock` 빈으로만 읽는다(SQL `now()` 금지). 테스트는 `MutableClock`으로 앞으로만 전진시킨다.
- 테스트 메서드명은 영문 카멜케이스(Kotlin 백틱 이름 금지). 통합 테스트는 `EmbeddedPostgresConfiguration`을 `@Import`하고
  밟는 역할대로 `@SpringBootTest(properties = ["mode=api"|"mode=worker"])`를 준다. 하위 클래스의 `@SpringBootTest` properties는 상위를 대체한다.
- 들여쓰기는 탭(Kotlin·Gradle·셸). `web/`(TS·CSS)은 공백 2칸.
- web은 동일 출처 `/api/*` rewrite로만 api에 닿는다(CORS 없음). WS 원점은 `GET /ws-origin`(런타임 `WS_ORIGIN`) — 브라우저 번들 env(`NEXT_PUBLIC_`) 금지.
- 서체는 `next/font/local` 셀프호스팅(Pretendard · `Rollout Mono`). 수정본이라 원래 이름(Monoplex)을 코드·CSS에 쓰지 않는다 → 상세: `web/app/fonts/NOTICE.md`.
- 커밋 메시지·`PROGRESS.md`는 한국어.

## 함정 (겪은 것)

- Kotlin 데몬이 샌드박스에서 `~/.local/share/kotlin/daemon` 쓰기 실패로 죽지만 fallback(데몬 없이 컴파일)으로 빌드는 통과한다 —
  로그의 `읽기전용 파일 시스템` 줄은 정상이다.
- Boot가 찍는 기동 완료 줄은 `Started RolloutApplicationKt`다(`bootTestRun` 진입점이 `TestRolloutApplicationKt`여도) — 스모크 앵커가 그 이름.
- Next 16 Turbopack은 `next/font/local`의 `--font-*` 변수를 `declarations`의 패밀리명이 아니라 JS 상수 이름으로 채워 `Rollout Mono`(공백)와 어긋난다 —
  web의 `dev`·`build`는 `--webpack`으로 고정한다(게이트·web-smoke가 확인). → 상세: `PROGRESS.md` 작업 8 「계획과 다른 점」
- `bootTestRun`에 포트·모드는 env가 아니라 `--args`로 준다(gradle 데몬 재사용 때문에 호출마다의 env가 자식에 닿는 보장이 없다).
