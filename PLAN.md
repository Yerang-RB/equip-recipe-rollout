# PLAN — 설비 레시피 일괄 배포 시스템 (그린필드, 브랜치 loop-20260916-1706)

정본은 `docs/superpowers/specs/2026-09-16-equip-recipe-rollout-design.md`(이하 「설계」)다. 이 계획은 설계의 범위·데이터 모델·불변식·테스트
전략·타이포그래피 확정값을 **그대로** 구현 작업 9개로 나눈다(늘리지도 줄이지도 않는다 — 설계 §2 「의도적 제외」 6항목은 넣지 않는다).
병합 대상 브랜치는 `master`(`git branch` 실측 — `main`은 없다). 저장소에는 설계 문서 1커밋뿐이다.
직접 선례: `~/exp-gf-mes`(Kotlin·Boot 4.1.1·Zonky·같은 하네스, 2026-09-16 완주) — `scripts/gradlew-sbx.sh`·`scripts/test.sh`·
`EmbeddedPostgresConfiguration`·`BodyLimitFilter`·`ConfigPinTest`·`OpenApiContract`·`ContractEndpointTest`·에러 핸들러 골격은 그 트리를
**읽고 이식**하되 도메인은 전부 새로 쓴다. 프론트 선례: `~/own-shop/web`(Next 16.3.0·`next/font/local` 셀프호스팅·`scripts/design-gates.sh`).

작업 수가 9개인 이유는 `## 결정 대기` 8에 적었다.

## 계획 세션이 실측한 것 (2026-09-16, srt 샌드박스 안 `/tmp/erp-spike`·`/tmp/erp-web`·`/tmp/erp-compose` — 산출물은 프로젝트에 없다)

- **JVM 스택(오늘 이 상자에서 실제로 받아진 것)**: Kotlin **2.4.20** · Spring Boot **4.1.1** · dependency-management **1.1.7** · Gradle **9.5.1**(래퍼는
  `~/.gradle/wrapper/dists/gradle-9.5.1-bin/*/gradle-9.5.1/bin/gradle wrapper --gradle-version 9.5.1 --offline`으로 생성 — 배포본 다운로드 호스트는 403) ·
  JDK 21 `~/.local/jdk-21.0.11+10` · zonky embedded-postgres **2.2.2** + binaries BOM **18.6.0** · **Playwright Java `com.microsoft.playwright:playwright:1.62.0`**
  · `spring-boot-starter-{webmvc,jdbc,flyway,security,websocket,actuator}` + `io.micrometer:micrometer-registry-prometheus` + `tools.jackson.module:jackson-module-kotlin`
  전부 Central에서 해석·컴파일·테스트 통과. 멀티모듈(`backend`·`console-mock`) 루트 `settings.gradle.kts`는 `pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }`.
- **Zonky**: `setLocaleConfig("locale","C.UTF-8")` + `setServerConfig("unix_socket_directories","")` + `setServerConfig("listen_addresses","127.0.0.1")` 셋으로
  `select 41+1` 통과(설계 §9의 필수 설정 3개 그대로).
- **Playwright Java가 샌드박스에서 브라우저를 띄운다**: 호스트 캐시 `~/.cache/ms-playwright/chromium-1234`가 1.62.x의 크로미엄 리비전과 일치(own-shop
  `playwright-core@1.62.1`의 `browsers.json` 실측 `chromium:1234`). **단 `Playwright.create(CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1")))`가
  필수다** — 그 env 없이 `create()`는 firefox/webkit 다운로드를 시도하다 **5분 38초 뒤에도 끝나지 않았다**(실측, kill). 있으면 `chromium().launch()` → `setContent`
  → `textContent` 2초.
- **Gradle 테스트 필터 exit 코드(멀티모듈)**: `:backend:test --rerun --tests 'spike.Nope'` → **exit 1**(지목 조각으로 쓸 수 있다). 루트 `test --rerun --tests X`도 console-mock에
  테스트가 없는 동안은 exit 0이지만 나중에 붉어질 수 있으므로 지목은 `:backend:test`로 한다(아래 `scripts/test.sh` 규약).
- **Next.js**: `next@16.3.0`·`react@19.2.4`·`@playwright/test@1.62.1`(own-shop 핀) `npm install` 7초 · `node --test --experimental-strip-types lib/*.test.ts` 통과 ·
  `next/font/local`로 Pretendard woff2 로드 + `output: "standalone"` `next build` 성공(`.next/static/media/Pretendard_Regular-*.woff2` 생성).
  tsconfig에 `allowImportingTsExtensions: true` + 상대 import에 `.ts` 확장자(node.md GOTCHAS).
- **서체 소싱**: Pretendard는 `https://cdn.jsdelivr.net/npm/pretendard@1.3.9/dist/web/static/woff2/Pretendard-{Regular,Medium,SemiBold,Bold}.woff2`가 **200**(base allowlist의
  `cdn.jsdelivr.net`; Regular 765,892B). `pyftsubset`(fontTools 4.63.0 + brotli 있음)으로 한글 전 범위 서브셋 → 616,568B(19% 절감 — 대부분이 한글이라 큰 이득이 없다.
  원본 그대로 써도 된다). **MonoplexKR은 루프가 받을 수 없다**: 저장소(`y-kim/monoplex`)에는 생성 스크립트만 있고 폰트 파일은 릴리스 zip
  (`MonoplexKR-v0.0.2.zip`, TTF)뿐인데 다운로드가 `release-assets.githubusercontent.com`으로 302되고 그 호스트는 차단(000). 라이선스는 OFL 1.1(`LICENSE.md` 실측 —
  Reserved Font Name "Monoplex KR"). → **결정 대기 1.** TTF→woff2 변환은 `pyftsubset --flavor=woff2`로 가능(brotli 있음).
- **`docker compose config`는 샌드박스에서 데몬 없이 돈다**(exit 0) — compose 정적 검사에 쓸 수 있다. `${POSTGRES_PASSWORD:?}` 미설정이면 exit 1
  (`required variable … is missing a value`) 실측. `docker compose up`·이미지 빌드는 소켓 차단으로 **루프 불가** → 병합 전 사람 몫.
- Boot 4.1.1 설정 메타데이터에 `logging.structured.format.console` 존재(jar 실측) — 구조화 JSON 로깅은 내장 기능, 의존성 추가 없음.
- `jq`·`curl`·`/usr/sbin/ss`·`rg --pcre2`·`pyftsubset` 있음. **`LOOP_UI=0`** — UI 작업은 기계 확인(curl·빌드·게이트)으로만 계획한다. 배포 산출물(compose·Dockerfile)이
  있으므로 실행 환경 점검 줄을 둔다(작업 9).

## 직전 회고 (범용) 줄 반영

이 프로젝트의 RETRO는 없다. 다른 프로젝트 RETRO의 (범용) 줄 중 반영: `.gitignore`에 `.attach_pid*`(작업 1) · 씨앗은 포그라운드 + 턴 종료 전 추적 변경 0건(불변 조건 14,
PROMPT) · 형제 경로 처치는 우회 클래스 열(씨앗 표 형식) · worktree 위임 `merge --ff-only <sha>` + `git worktree remove`(작업 7) · 「결정 사항」 레저마다 실서버 시나리오
1건(작업 9) · 「미검증」 관찰은 재현 테스트나 원장 R 줄로 닫는다(작업 9 검증) · 잠금 씨앗은 진입점마다(클레임 진입점은 하나 — 작업 2) · 부정 서술 대조(작업 9) ·
`/usr/sbin/ss` 절대 경로(불변 조건 12) · 부재 팔은 `test -f FILE && ! grep -q PAT FILE` 모양(검증 줄 전부) · 새 불변 조건에는 레포 상주 검사 동반(불변 조건 14) ·
리뷰어 초안·재위임·`## 리뷰어 입력`·씨앗 실행까지가 완료 조건(작업 7) · 낱말 삭제 씨앗과 진위 씨앗 분리(작업 7) · 새 게이트에 소요 시간 단언 없음(전부) ·
멀티파트 비활성(`spring.servlet.multipart.enabled=false`, 불변 조건 4). **작업 수 ≤7은 미반영 — 결정 대기 8.** prettier 접힘 앵커 규칙 — TSX에 줄 앵커를 두지 않는다(게이트의
서체 앵커는 `grep -c 'path: "./fonts/…'` 무앵커 계수).

## 불변 조건 (모든 작업에 적용)

1. **설계 문서가 정본이다.** 범위(§2)·데이터 모델(§4)·수명주기(§5)·동시성(§6)·분류표(§7)·권한 매트릭스(§8)·테스트 전략(§9)·타이포그래피(§10)를 바꾸지 않는다. 데이터
   모델의 유일한 추가는 `rollout_task.next_attempt_at`(백오프 시각 — 결정 대기 2ⓐ)와 `app_user.display_name`(`actor_name` 스냅샷의 원본 — §8 「개명」)이다.
   `openapi.yml`은 REST 계약 정본(작업 5부터)이고 엔드포인트·상태코드를 늘리면 계약을 먼저 고친다. 모든 `type: object` 스키마는 `additionalProperties: false`.
2. **모듈 경계**: `backend/`(api·worker 한 jar, `--mode=api|worker` 필수 — 없으면 기동 실패) · `console-mock/`(별도 Gradle 모듈, 서버렌더 HTML + 폼 POST, backend
   운영 코드는 이 모듈을 **import하지 않는다** — `backend/build.gradle.kts`의 `project(":console-mock")` 의존은 `testImplementation`만) · `web/`(Next.js). api 빈(컨트롤러·
   시큐리티·WebSocket·LISTEN 리스너)은 `mode=api`에서만, 워커 빈(클레임 루프·Playwright 어댑터·하트비트)은 `mode=worker`에서만 뜬다. api는 Playwright를
   생성하지 않고(`ApiModeTest`가 컨텍스트에 `Playwright` 빈 부재 단언), worker는 애플리케이션 API를 매핑하지 않는다(워커의 HTTP는 `/actuator/health`·
   `/actuator/prometheus` 둘뿐 — 결정 대기 3, 그 외 경로는 404). `api`와 `worker`는 서로를 호출하지 않는다 — 공유 상태는 PostgreSQL뿐.
3. **인증·인가(결정 대기 4 기본값)**: 세션 쿠키(`HttpOnly`·`SameSite=Lax`, 서버 메모리 세션 — api 재기동 = 전 세션 폐기), `POST /api/auth/login` `{username,password}` →
   200 `{username, displayName, role}` / 실패는 **같은 401 본문**(존재 은닉, 더미 해시 대조로 비용도 같게 — `verify(encoder, times(1))` 계수 **그리고** 더미 해시의 cost
   factor == 실제 저장 해시의 cost factor 단언 — 더미만 낮은 라운드로 떨어지면 타이밍이 다시 갈린다). **로그인은 세션 id를 새로 발급한다**(세션 고정 방어 — 로그인 전 쿠키
   값 ≠ 후, 로그인 전 세션 id로는 인증 경로 401) 그리고 쿠키 속성은 **실포트의 실제 `Set-Cookie` 헤더**로 `HttpOnly`·`SameSite=Lax`를 단언한다(MockMvc는 그 헤더를 만들지
   않는다). 비밀번호는 bcrypt의 축(72바이트)으로 서버가 제한한다 — 초과는 400, 클라이언트 자릿수 상한은 두지 않는다. 무인증 허용 경로는 **정확히**
   `POST /api/auth/login` · `GET /api/health` · `/actuator/health` · `/actuator/prometheus`(테스트가 계약에서 경로를 읽어 그 넷 밖 전부에 무인증 → 401 JSON). 그 외 전부
   `anyRequest().authenticated()` 기본 거부, 401은 JSON `{code:"UNAUTHENTICATED"}`(리다이렉트 없음). CSRF 필터 off의 근거 = SameSite=Lax + 상태 변경 GET 없음(계약에서
   GET 오퍼레이션 전부가 조회임을 테스트가 확인). 비밀번호는 bcrypt, 응답·로그·감사 어디에도 비밀번호·해시 없음. **비활성화된 사용자(`enabled=false`)의 기존 세션은
   다음 요청부터 401**(인증 필터가 매 요청 `app_user`를 읽는다 — 비밀값 폐기 축의 실체, 결정 대기 5). 정책 검사는 `Policy.check(actor, action, target)` **한 곳**(설계 §8
   매트릭스 리터럴 그대로, 역할 enum 3 · 행위 enum 7), 거부는 403 `{code:"FORBIDDEN"}`(잡은 모든 역할에 보이므로 404 은닉이 아니라 403 — 계약에 명시). 4-eyes: `recipe.created_by ==
   actor` 승인 → 403 `SELF_APPROVAL_FORBIDDEN`. ADMIN은 승인·폐기 불가(매트릭스 그대로).
4. **에러 형식은 하나** `{code, message}`: 400 `INVALID_REQUEST`(검증·미지 필드·타입·파싱) · 401 `UNAUTHENTICATED` · 403 `FORBIDDEN`·`SELF_APPROVAL_FORBIDDEN` · 404 `NOT_FOUND`
   (없는 경로·없는 id — 인증된 요청의 없는 경로는 404이고 무인증이면 시큐리티가 먼저 401을 낸다: 기본 거부가 라우팅을 가리는 것이 아니라 순서다, 계약에 명시) · 405 `METHOD_NOT_ALLOWED` · 409 `RECIPE_NOT_ACTIVE`(ACTIVE 아닌 레시피로 잡 생성)·`INVALID_TRANSITION`(레시피·잡 상태 전이 위반)·`CONSTRAINT_VIOLATION`
   (DataIntegrityViolation 매핑, 제약 이름 없이) · 413 `PAYLOAD_TOO_LARGE` · 500 `INTERNAL_ERROR`(같은 형식 + ERROR 로그 클래스명). 응답에 스택트레이스·SQL·클래스명·제약 이름을
   싣지 않고, 로그에는 예외 **클래스명·필드명·상태·코드**만(프레임워크 메시지·rejected value 금지). 요청 본문 상한 **64 KiB, 읽은 바이트 기준, 파싱 전**(`BodyLimitFilter`
   이식, 「파싱 전」 증거 = 프로브 핸들러 호출 수 0) + `spring.servlet.multipart.enabled=false`(멀티파트 우회 경로 닫기) + `spring.mvc.formcontent.filter.enabled=false`.
   엄격 JSON: `fail-on-unknown-properties=true`. 모든 에러 테스트는 상태코드와 본문 `code`를 함께 단언한다.
5. **잡 수명주기(설계 §5 그대로)**: 생성은 `rollout_job` + `rollout_task` N개 한 트랜잭션, `Idempotency-Key` 헤더(필수, 1..100자, `rollout_job.idempotency_key UNIQUE`)가 같으면
   기존 잡 반환(200, 새 행 0). 클레임 SQL은 **문장 하나**: `SELECT … FROM rollout_task WHERE (status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= :now)) OR
   (status='CLAIMED' AND lease_expires_at < :now) ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1` → 같은 트랜잭션에서 `CLAIMED`·`claimed_by`·`lease_expires_at = :now + lease`
   (기본 120초). 만료 리스를 집으면 `lease_expired_recovered_total`++. 하트비트(기본 30초)는 자기 task의 `lease_expires_at`만 연장. 성공 = `task=SUCCEEDED` + `application_history`
   INSERT **같은 트랜잭션** + 커밋 후 `NOTIFY rollout_progress, '<job_id>'`. 실패 = `attempt` 유지(클레임 시 이미 +1)·`last_error` 기록 → 재시도 가능 ∧ `attempt < 3`이면
   `PENDING` + `next_attempt_at = :now + backoff(attempt)`, 아니면 `FAILED`. **총 시도 3회**(`attempt`는 시도 횟수, 3회째 실패가 `FAILED` — 결정 대기 2ⓑ). `backoff(a) =
   base × 2^(a−1)`(base 기본 2초, 상한 60초, 지터 없음 — 결정적). 집계: task가 종료될 때마다 같은 트랜잭션에서 job 상태 재계산 — 미종료 있으면 `RUNNING`, 전부 `SUCCEEDED`
   (NO_CHANGE 포함) → `SUCCEEDED`, 전부 `FAILED`/`CANCELLED` → `FAILED`, 섞이면 `PARTIAL`. 취소 = `PENDING` task → `CANCELLED`(실행 중인 것은 끝까지). 재시도 = `FAILED`·`CANCELLED`
   task → `PENDING`, `attempt=0`, `next_attempt_at=null`. **시각은 `java.time.Clock` 빈으로만** 읽고 SQL `now()`를 쓰지 않는다(테스트가 가변 클록으로 리스·백오프를 결정적으로
   움직인다 — 시간 대기 없음). 시각은 마이크로초로 절단한다.
6. **실행 어댑터(설계 §5 3번·§12)**: `RecipeApplier` 인터페이스 하나 + Playwright 구현 하나(설계가 명시적으로 열어 둔 유일한 인터페이스). 순서: 세마포어 획득 → 설비별
   `BrowserContext` 재사용(세션 쿠키 유지) → 로그인 페이지가 보이면 로그인(자격증명은 `equipment` 행) → 레시피 폼 5필드 + 품번·버전 입력 → 저장 → **저장 후 폼을 다시 열어
   5필드 + 품번·버전을 읽어 대조** → 불일치는 `VERIFY_MISMATCH`로 **재시도하지 않는** 실패. 세션 만료(저장 응답이 로그인 페이지)는 재로그인 후 **한 번** 다시 시도.
   `Playwright.create`는 반드시 `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`을 env로 준다(실측 — 없으면 무한 대기). 적용 전 `application_history`에 같은 `equipment_id`+`part_no`+
   `version`의 최신 APPLIED가 있으면 브라우저를 열지 않고 `NO_CHANGE` 이력 + `SUCCEEDED`(멱등 2층).
7. **실패 분류표(설계 §7)는 코드와 테스트 양쪽에 같은 표로 존재한다**: 재시도 O = 타임아웃 · 5xx · 세션 만료(재로그인 1회 후에도 실패) · 네트워크 오류. 재시도 X = 콘솔의 값
   거부(4xx + 유효성 메시지) · 설비 `MAINTENANCE` · 인증 정보 오류(로그인 실패) · `VERIFY_MISMATCH`. `FailureClassifier.classify(outcome)`는 순수 함수, 단위 테스트가 표의 모든
   행을 밟는다(`assertEquals(8, rows)`).
8. **동시성 2층(설계 §6)**: DB 층은 5의 클레임 문장. 워커 층은 `Semaphore(N)`(`worker.browser-concurrency`, 기본 3)이고 클레임 실행 풀은 `worker.claim-parallelism`(기본 6)으로
   **N보다 크다**(세마포어가 실제 상한이 되도록). 동시성 단언은 **peak 동시 점유를 직접 세어 등호**로 한다 — 워커 쪽 `browser_sessions_active` 게이지의 peak(어댑터의
   `AtomicInteger` + max 추적)와 **목업 콘솔 쪽 in-flight 요청 peak**(콘솔이 자기 처리 중 요청 수의 최댓값을 센다 — 워커 자기 신고가 아닌 독립 관측) **둘 다** `== N`.
   총 호출 수·소요 시간을 판정에 쓰지 않는다. 씨앗은 상한 「제거」가 아니라 N을 풀 크기로 넓히는 것(글로벌 GOTCHAS: 자원 상한 제거 뮤테이션은 진짜 고갈).
9. **감사 불변식(설계 §8, 전부 테스트)**: ① 모든 쓰기 엔드포인트(계약의 POST/PUT/PATCH/DELETE 전부를 테스트가 읽는다)는 성공·거부(403)·실패(4xx/5xx)·로그인 실패에 `audit_log`
   행을 남긴다(`result` = OK/DENIED/ERROR) ② append-only — DB 트리거 `BEFORE UPDATE OR DELETE ON audit_log … RAISE EXCEPTION`(앱 코드가 아니라 DB가 막는다) + 운영 코드에
   `audit_log` UPDATE/DELETE 문장 없음 ③ `detail`에 비밀번호·해시·콘솔 자격증명·세션 id가 들어가지 않는다(테스트는 알려진 비밀 문자열들이 `audit_log`·응답·`CapturedOutput`
   어디에도 없음을 **양의 앵커**(같은 행위의 감사 행이 있다)와 함께 단언) ④ `actor_name`은 시점 스냅샷(개명 뒤 과거 행 불변). 워커 대행은 `actor_id=null, actor_name='system',
   detail.on_behalf_of=<요청자 id>`, `application_history.applied_by`는 잡 요청자.
10. **스키마는 Flyway V파일로만**(`V1__schema.sql` 하나에 7테이블 + 트리거, 이후 변경은 새 V파일). 상태 열거는 CHECK로 못박는다(`app_user.role` 3 · `equipment.status` 3 ·
    `recipe.status` 3 · `rollout_job.status` 5(PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED) · `rollout_task.status` 6(PENDING/CLAIMED/SUCCEEDED/FAILED/CANCELLED + `RUNNING`은 두지 않는다 —
    CLAIMED가 실행 중이다) · `application_history.result` 2 · `audit_log.result` 3). UNIQUE: `app_user.username` · `equipment.code` · `recipe(part_no, version)` ·
    `rollout_job.idempotency_key` · `rollout_task(job_id, equipment_id)`. 시각 컬럼은 전부 `timestamptz`, `params`·`params_snapshot`·`detail`은 `jsonb`. 각 제약은 JDBC 우회 삽입이
    23514/23505로 죽는 테스트로 존재를 못박는다(`SchemaConstraintTest`). ORM은 쓰지 않는다 — `JdbcClient`/`JdbcTemplate` + 파라미터 바인딩(문자열 결합 SQL 금지).
11. **관측(설계 §10)**: 메트릭 4종 이름 그대로 — `rollout_task_duration_seconds`(Timer) · `rollout_task_total{result}` · `browser_sessions_active`(Gauge) ·
    `lease_expired_recovered_total`. 구조화 JSON 로깅은 Boot 내장 `logging.structured.format.console=logstash`(항상 켠다 — 테스트도 JSON 줄을 읽는다) + MDC `job_id`·`task_id`
    (task 실행 구간 전체, `finally`에서 제거). 워커 헬스는 `ClaimLoopHealthIndicator` — 마지막 루프 반복 시각이 `worker.loop-stale-seconds`(기본 60) 안이면 UP, 아니면 DOWN
    (클록 빈 기준). Prometheus·Grafana 컨테이너는 넣지 않는다.
12. **테스트 규율**: 통합 테스트는 Zonky 실DB(위 실측 옵션 셋 — 하나라도 빠지면 10초 타임아웃 ConnectException으로만 보인다). E2E는 `console-mock`을 **같은 JVM 안에서 포트 0
    (랜덤)으로** `SpringApplicationBuilder`로 띄우고(`testImplementation(project(":console-mock"))`), 결정적 주입은 HTTP가 아니라 그 컨텍스트의 `MockControl` 빈으로
    (`failNextSaves(n)` · `expireSessionsAfterRequests(n)` · `corruptNextSave()` · `delayMs` · `peakInFlight()`·`loginCount(equipment)`). 데모의 확률 주입은 env(`MOCK_FAIL_RATE`,
    기본 0.10 · `MOCK_DELAY_MS`) — 테스트에서는 0. 테스트 메서드명은 영문 카멜케이스, Kotlin 백틱 이름 금지. 동시성·리스·백오프 판정에 시간(초·타임아웃)을 쓰지 않는다 —
    횟수·상태·클록 전진으로 판정한다(`future.get` 상한은 행 걸림 방지용). 테스트끼리 데이터 공유 금지(각 테스트가 자기 설비 코드·품번을 UUID 접미로 만든다). 동시성
    테스트는 커밋되는 실호출(테스트 `@Transactional` 금지). Mockito 인라인 목은 샌드박스에서 죽는다 → 필요하면 `mock-maker-subclass` 한 줄. 반복 재개 시 포트 점유 확인은
    `/usr/sbin/ss -tln`(절대 경로)이고 `lsof`는 쓰지 않는다. 테스트·스모크는 8080·3000을 쓰지 않는다(RANDOM_PORT, 스모크 `${SMOKE_PORT:-18080}`, web-smoke `${WEB_PORT:-13000}`).
13. **검증은 스크립트로만**: `scripts/test.sh`(인자 없음 → `./gradlew test --rerun`, `--tests X` → `./gradlew :backend:test --rerun --tests X`; 두 경우 모두 「이번 실행에 새
    `TEST-*.xml`이 없거나 실제로 돈 건수 합이 0이면 exit 1」 자기점검 — exp-gf-mes 것 이식) · `scripts/gradlew-sbx.sh`(프록시 시스템 프로퍼티) · `scripts/smoke.sh`(api 모드
    실기동) · `scripts/design-gates.sh`·`scripts/web-smoke.sh`(작업 8) · `scripts/deploy-gates.sh`(작업 9). `./gradlew`를 직접 부르지 않는다(UP-TO-DATE 0건 실행 exit 0).
    `/tmp/embedded-pg/PG-*` 잔여물로 전 클래스 컨텍스트 실패면 그 디렉터리를 `/tmp/_trash-<날짜>/`로 옮긴다.
14. **씨앗(설계 §9 6종 = S1~S6) 절차**: 심기 전 작업분 커밋 → 결함 → 지목 테스트 **포그라운드**로 붉어짐 확인 → `git show HEAD:<파일> > <파일>` 복원 → 초록 → `git status
    --porcelain` 추적 변경 0건. `run_in_background`로 씨앗을 띄운 채 턴을 끝내지 않는다(exp-gf-mes 반복 2 ABORT). 결과는 PROGRESS `## 씨앗 표`에 첫 열이 **정확히**
    `S1`~`S6`인 행 `| S<n> | 심은 것 | 죽인 테스트 | 우회 클래스 |`로(S1 `SKIP LOCKED` 제거 · S2 저장 후 재조회 대조 제거 · S3 감사 기록 제거 · S4 리스 갱신 제거 · S5 세마포어
    상한(N→풀 크기) · S6 4-eyes 검사 제거). 게이트 씨앗은 `| G<n> |`. 씨앗이 살아남으면 어서션을 느슨하게 하지 말고 잡는 테스트를 더한다. **부재 단언(「~가 응답·로그·감사에
    없다」)마다 그것을 지키는 것이 내 코드인가·프레임워크 기본값인가·현재 코드 모양의 우연인가를 씨앗 표 행(또는 테스트 KDoc)에 적는다** — 기본값이 지키면 핀 테스트(`ConfigPinTest`)를,
    우연이 지키면 그 조건을 테스트 안에서 만들어 낸다(예: 공개 접두 아래 보호 경로를 테스트가 등록해 401 확인). **새 불변 조건·금지를 세우는
    작업은 그것을 지키는 레포 상주 검사(테스트·CHECK·트리거·게이트 스크립트)를 같은 작업에 만든다** — PLAN의 검증 줄은 병합과 함께 죽는다.
15. **타이포그래피(설계 §10 확정값 그대로, 다른 서체 금지)**: 본문·UI Pretendard 400/500/600/700 · 수치·ID MonoplexKR 400/500/700 · `next/font/local` woff2 셀프호스팅
    (`web/app/fonts/`) · 폴백 `system-ui, -apple-system, "Malgun Gothic", sans-serif` · `font-feature-settings: "tnum"` · `word-break: keep-all`. 외부 URL 폰트·CDN 로드 금지
    (인터넷 없이 떠야 한다). fonts-data API는 쓰지 않는다(값이 이미 확정됐다). 디자인 판단(색·여백·카피 톤)은 대화 세션 몫 — 루프는 값 그대로 구현하고 깨짐만 고친다.
16. **보안 값(결정 대기 4·5·6·7 기본값)**: 노출 범위 = 컨테이너 안 바인드 `0.0.0.0`(compose 네트워크 필수) + compose `ports`는 **전부 `"127.0.0.1:<h>:<c>"` 모양**(web 3000 ·
    api 8080 · console-mock 8090 셋), 로컬 스크립트는 `server.address=127.0.0.1`. CORS 없음(web이 `/api/*`를 rewrite로 동일 출처화). WebSocket 허용 Origin은 `APP_WEB_ORIGIN`
    (기본 `http://localhost:3000`) 하나. 인증 예외 경로 = 3의 넷. 개인정보 = `username`·`display_name`만(실명·연락처·이메일 컬럼 없음). 콘솔 자격증명은 `equipment.console_user`·
    `console_password`에 그대로 저장(암호화 없음 — 리스크 원장 「운영 전 재검토」), 응답 DTO·로그·감사에 절대 없음. 비밀값 수명 = 세션은 메모리(재기동·로그아웃·`enabled=false`로
    폐기) · DB 비밀번호 `${POSTGRES_PASSWORD:?}` · 시드 비밀번호 `${SEED_PASSWORD:?}`(compose가 요구, 코드에 기본값 없음) · 콘솔 자격증명 교체는 ADMIN의 설비 수정.
    재는 것: 예외 경로는 계약 기반 401 테스트(작업 5) · `enabled=false` 즉시 401(작업 5) · compose 양의 앵커 + `docker compose config`(작업 9) · 비밀 문자열 부재 + 양의 앵커(작업 5) ·
    `NEXT_PUBLIC_` 부재(작업 9 — 런타임 env만).
17. **단순함**: controller→service→repository 3층, 인터페이스는 `RecipeApplier` 하나, DTO는 응답용 한 벌, 계약에 없는 엔드포인트 없음, 설정 가능 값은 이 문서에 이름이 있는
    것만. 의도적 천장은 `// ponytail:` 주석. **새 의존성은 위 실측 목록 밖에서 추가하지 않는다**(필요하면 `## 결정 대기`에 올리고 멈춘다). 샌드박스 도메인은 base allowlist
    (`repo.maven.apache.org`·`registry.npmjs.org`·`cdn.jsdelivr.net`)만으로 돈다 — MonoplexKR만 예외(결정 대기 1).

보안 축: 결정 대기 4(인증·인가 모델)·5(비밀값 수명·폐기)·6(노출 범위)·7(개인정보·민감데이터 범위) — 기본값은 불변 조건 16에 값으로 박았고 재는 명령은 작업 5·9에 있다.

## API 요약 (계약 `openapi.yml`이 정본 — 작업 5에서 쓴다)

| 자원 | 엔드포인트 | 권한(설계 §8) |
|---|---|---|
| 인증 | `POST /api/auth/login` · `POST /api/auth/logout` · `GET /api/auth/me` | login 무인증 |
| 사용자 | `GET /api/users` · `POST /api/users` · `PUT /api/users/{id}`(displayName·role·enabled·password) | ADMIN |
| 설비 | `GET /api/equipment`(상태·마지막 적용 레시피 포함) · `POST /api/equipment` · `PUT /api/equipment/{id}`(콘솔 자격증명 포함 — 응답에는 없음) | 조회 전원 · 쓰기 ADMIN |
| 레시피 | `GET /api/recipes` · `GET /api/recipes/{id}` · `POST /api/recipes`(DRAFT) · `PUT /api/recipes/{id}`(DRAFT만) · `POST /api/recipes/{id}/approve` · `POST /api/recipes/{id}/retire` | 초안 OPERATOR·APPROVER · 승인·폐기 APPROVER(4-eyes) |
| 잡 | `POST /api/jobs`(`Idempotency-Key` 헤더, `{recipeId, equipmentIds[]}`, ACTIVE만) · `GET /api/jobs` · `GET /api/jobs/{id}`(task 포함) · `POST /api/jobs/{id}/cancel` · `POST /api/jobs/{id}/retry` | 생성 OPERATOR·APPROVER · 취소·재시도 OPERATOR 자기 잡만/APPROVER·ADMIN 전부 |
| 이력 | `GET /api/history?equipmentId=` | 전원 |
| 감사 | `GET /api/audit` | APPROVER·ADMIN |
| 실시간 | `GET /api/ws/jobs/{id}`(WebSocket, 세션 쿠키 핸드셰이크) | 전원 |
| 헬스 | `GET /api/health` → `{status:"ok"}`(스모크 앵커) | 무인증 |

## 작업

- [x] 스캐폴드 + 스키마 + 도메인 + 하네스 + 스모크 + CLAUDE.md: 루트 `settings.gradle.kts`(pluginManagement mavenCentral 우선 · `include("backend","console-mock")`) · 루트 `build.gradle.kts`(Kotlin 2.4.20 / Boot 4.1.1 / dep-mgmt 1.1.7 `apply false` + subprojects 공통) · 래퍼 9.5.1(위 실측 오프라인 생성) · `backend/build.gradle.kts`(위 실측 의존성 전부 + zonky + playwright 1.62.0 + `testImplementation(project(":console-mock"))`) · `console-mock/build.gradle.kts`(webmvc만, 빈 `@SpringBootApplication` + `/health`) · `scripts/gradlew-sbx.sh`·`scripts/test.sh`(불변 조건 13 규약 — exp-gf-mes 이식, `--tests`는 `:backend:test`) · `backend/src/main/resources/application.properties`(`server.address=127.0.0.1` · `spring.servlet.multipart.enabled=false` · `spring.mvc.formcontent.filter.enabled=false` · `spring.jackson.deserialization.fail-on-unknown-properties=true` · `logging.structured.format.console=logstash` · `worker.*` 기본값 — 값은 작업 6 `ConfigPinTest`가 핀) · Flyway `V1__schema.sql`(불변 조건 10의 7테이블·CHECK·UNIQUE·FK·`audit_log` append-only 트리거) · 도메인 enum 6종(역할·설비 상태·레시피 상태·잡 상태·task 상태·이력 결과) · `--mode` 분기(`mode` 프로퍼티 필수, `api`/`worker` 조건부 빈 — 지금은 api의 `GET /api/health`와 worker의 빈 `ClaimLoop` 자리만) · `src/test`: `EmbeddedPostgresConfiguration`(옵션 셋) · `MutableClock`(앞으로만) · `TestRolloutApplication`(bootTestRun 진입점, `mode=api`) · `HealthApiTest.healthReturnsOk`(MockMvc 200 + flyway 적용 실DB) · `SchemaConstraintTest`(`auditLogRejectsUpdateAndDelete` — JDBC UPDATE·DELETE가 트리거로 죽음 / 상태 CHECK·UNIQUE 우회 삽입 23514·23505 팔 하나씩) · `ApiModeTest.apiModeHasNoPlaywrightBean` · **`scripts/smoke.sh`**(포트 `${SMOKE_PORT:-18080}` 이미 응답하면 실패 · `SERVER_PORT=$PORT MODE=api scripts/gradlew-sbx.sh :backend:bootTestRun` 백그라운드 · `/api/health` 200 폴링 · 로그에 `Started TestRolloutApplicationKt` 앵커 뒤 `generated security password` 0줄 · 앱 JVM은 `TestRolloutApplicationK[t]` 브래킷 트릭으로 kill(`|| true`, 파일로 쓴 뒤 별도 호출로 실행) · 포트 해제 확인 · 백투백 2회) · 루트 `CLAUDE.md`(스택·명령·구조·컨벤션 — 실제 확인된 것만, 글로벌 §10 형식) · `.gitignore`(`build/` `.gradle/` `.kotlin/` `*.log` `.env` `.attach_pid*` `node_modules/` `.next/`) — backend-quality 스킬 사용(글로벌 CLAUDE.md §10.6): 계약 우선·실기동 스모크는 초반에(walking skeleton)·부팅 로그를 읽는다·DB 제약은 우회 삽입으로 못박는다 — 검증: `scripts/test.sh --tests 'com.rollout.SchemaConstraintTest.auditLogRejectsUpdateAndDelete' && scripts/test.sh --tests 'com.rollout.HealthApiTest.healthReturnsOk' && scripts/test.sh --tests 'com.rollout.ApiModeTest.apiModeHasNoPlaywrightBean' && test -s CLAUDE.md && grep -qxF '.attach_pid*' .gitignore && scripts/smoke.sh && scripts/smoke.sh && scripts/test.sh`
- [x] `scripts/test.sh` 전체 스위트 모드 침묵 실패 수정(작업 1 검증 줄의 마지막 `scripts/test.sh`가 러너 독립 재실행에서 exit 1 — 2026-09-16 21:41 ABORT): `ran=$(find "${results[@]}" … 2>/dev/null | …)` 의 `find`가 테스트 없는 `console-mock`의 `build/test-results/test` 부재로 exit 1을 내고 `set -euo pipefail`+`2>/dev/null`이라 **메시지 없이** 스크립트가 exit 1 한다(`BUILD SUCCESSFUL` 뒤 침묵). 존재하는 결과 디렉터리만 `find`에 넘겨라(`results`를 `[ -d ]`로 걸러서) — 0건 실행·전부 skip 판정은 그대로 유지하고 `|| true`로 검사를 무력화하지 마라. 고친 뒤 작업 1 검증 줄 전체를 한 번 더 돌려 `echo exit=$?`로 마지막 명령까지 0임을 눈으로 확인하라 — 검증: `scripts/test.sh && scripts/test.sh --tests 'com.rollout.HealthApiTest.healthReturnsOk' && scripts/smoke.sh`
- [x] 잡 수명주기 코어(서비스 층, HTTP 없음) + 클레임·리스·재시도·집계 실DB 테스트 + 씨앗 S1·S4: `JobService.create(recipeId, equipmentIds, requestedBy, idempotencyKey)`(한 트랜잭션, 같은 키면 기존 잡) · `TaskClaimer.claim(workerId)`(불변 조건 5의 문장 하나, `:now`는 클록 빈, 만료 리스 회수 시 `lease_expired_recovered_total`++) · `LeaseHeartbeat.tick(taskId)`(자기 task만 연장) · `TaskOutcomeService.succeed(taskId, paramsSnapshot, result)`(`SUCCEEDED` + `application_history` 같은 트랜잭션 + 커밋 후 `NOTIFY rollout_progress`) · `.fail(taskId, error, retryable)`(불변 조건 5의 attempt·백오프·FAILED 규칙) · 집계 `JobStatus.aggregate(taskStatuses)` 순수 함수 · `FailureClassifier`(불변 조건 7의 표 8행) · `Backoff.of(attempt)` · 취소·재시도(`PENDING`→`CANCELLED`, `FAILED|CANCELLED`→`PENDING` attempt 0) · NO_CHANGE 판정 `HistoryRepository.latestApplied(equipmentId)`. 테스트(전부 실DB, 클록 전진으로 판정): `ClaimContentionTest.concurrentClaimersNeverShareATask`(40 task, 8 스레드 `CountDownLatch` 도착 장벽, 각 스레드가 빌 때까지 반복 클레임 → 클레임된 id 합집합 40 · 총 클레임 수 40 · 예외 목록 비어 있음) · `ClaimContentionTest.claimSkipsRowsLockedByAnotherWorker`(테스트가 열린 트랜잭션으로 task 1을 `FOR UPDATE`로 쥔 채 다른 커넥션이 클레임 → **즉시 task 2를 돌려준다**(`get` 상한은 행 걸림 방지용, 판정은 돌아온 id) — `SKIP LOCKED`가 없으면 막혀 돌아오지 못한다) · `LeaseTest.expiredLeaseIsReclaimedByAnotherWorker`(A가 클레임 → 클록 +121초 → B 클레임이 **같은 task**를 받고 `claimed_by=B`, 카운터 +1) · `LeaseTest.heartbeatKeepsLeaseFromBeingReclaimed`(A 클레임 t0 → 클록 +100초 → `tick` → 클록 +100초(t0+200) → B 클레임 결과 **없음**; 갱신이 없으면 t0+120에 만료돼 B가 받는다) · `TaskOutcomeTest.successWritesHistoryAndStatusAtomically`(이력 INSERT를 실패시키는 프로브(존재하지 않는 equipment_id FK 위반)로 예외 → task 상태 그대로 `CLAIMED`·이력 0행; 정상 경로는 둘 다 1) · `TaskOutcomeTest.retryableFailureSchedulesBackoffAndThirdFailureIsFinal`(3회 실패 → `FAILED`, 2회째 `next_attempt_at = now+4s`, 그 전에는 클레임 안 됨·이후 클레임됨) · `TaskOutcomeTest.nonRetryableFailureIsFinalAtAttemptOne` · `JobServiceTest.sameIdempotencyKeyReturnsExistingJobWithoutNewRows` · `JobServiceTest.aggregateCoversAllTerminalCombinations`(SUCCEEDED/PARTIAL/FAILED/RUNNING 4가지) · `FailureClassifierTest.everyTableRowIsClassified`(8행 등호) · `BackoffTest`. 씨앗 **S1**(`SKIP LOCKED` 삭제 → `claimSkipsRowsLockedByAnotherWorker` 붉음)·**S4**(`tick` no-op → `heartbeatKeepsLeaseFromBeingReclaimed` 붉음)를 불변 조건 14 절차로 심고 PROGRESS `## 씨앗 표`에 `| S1 |`·`| S4 |` 행 — backend-quality 스킬 사용(글로벌 CLAUDE.md §10.6): 락 게이트는 승패가 아니라 상태로·lost update는 원자 문장으로·동시성은 횟수로 판정 — 검증: `scripts/test.sh --tests 'com.rollout.ClaimContentionTest.claimSkipsRowsLockedByAnotherWorker' && scripts/test.sh --tests 'com.rollout.ClaimContentionTest.concurrentClaimersNeverShareATask' && scripts/test.sh --tests 'com.rollout.LeaseTest.heartbeatKeepsLeaseFromBeingReclaimed' && scripts/test.sh --tests 'com.rollout.TaskOutcomeTest.successWritesHistoryAndStatusAtomically' && scripts/test.sh --tests 'com.rollout.FailureClassifierTest.everyTableRowIsClassified' && test "$(grep -oE '^\| S[14] \|' PROGRESS.md | sort -u | wc -l)" -eq 2 && scripts/test.sh`
- [x] 목업 콘솔 + Playwright 실행 어댑터 + 저장 후 재조회 대조 + E2E 시나리오 1~4 + 씨앗 S2: `console-mock`(서버렌더 HTML + 폼 POST, Thymeleaf 없이 Kotlin 문자열 템플릿): `GET /login` 폼 → `POST /login`(설비 계정 `MOCK_CONSOLE_USER/PASS` env, 세션 쿠키) · `GET /recipe`(현재 5필드 + 품번·버전 표시, 미로그인이면 `/login`으로 302) · `POST /recipe`(유효성: `mold_temp_c` 50..250 · `inject_pressure_mpa` 10..200 · `hold_pressure_mpa` 0..150 · `cooling_sec` 1..120 · `cycle_sec` 5..300 — 위반은 400 + 오류 문구, 성공은 저장 후 `/recipe` 302) · 불안정 주입(env `MOCK_FAIL_RATE` 기본 0.10 · `MOCK_DELAY_MS` · `MOCK_SESSION_TTL_MS`; 테스트용 `MockControl` 빈: `failNextSaves(n)` → 다음 n번 저장 500 · `expireSessionsAfterRequests(n)` · `corruptNextSave()` → 저장은 200인데 `cooling_sec`를 +1로 저장 · `peakInFlight()`(처리 중 요청 수 최댓값 — 필터에서 `AtomicInteger` 증감·max) · `loginCount(user)`) · `RecipeApplier` 인터페이스 + `PlaywrightRecipeApplier`(불변 조건 6 순서 그대로 · `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` · 설비별 `BrowserContext` 캐시 · 재로그인 1회 · 저장 후 `GET /recipe` 재조회 대조 → `VERIFY_MISMATCH`) · `ClaimLoop`(워커 모드: `claim-parallelism` 풀, 클레임 → 세마포어 → 어댑터 → `TaskOutcomeService`, MDC `job_id`·`task_id`, 하트비트 스케줄) · 워커 모드 시큐리티 체인(`/actuator/health`·`/actuator/prometheus`만 허용, 나머지 404). E2E(`com.rollout.e2e`, 목업을 같은 JVM 포트 0으로 띄우고 `equipment.console_url`에 그 포트를 넣는다, 워커 모드 컨텍스트, 백오프 base 50ms·`browser-concurrency` 3): `RolloutE2ETest.allTwelveEquipmentsAppliedWithTwelveHistoryRows`(12대 → 전부 SUCCEEDED · 이력 APPLIED 12 · 잡 SUCCEEDED · 콘솔에 실제 저장된 값 == 레시피 params · **같은 레시피 재배포 → NO_CHANGE 12, 브라우저 로그인 0회 증가**) · `RolloutE2ETest.retriesAfterInjected500`(`failNextSaves(1)` → SUCCEEDED · `attempt == 2` · 이력 1) · `RolloutE2ETest.reLoginsAfterSessionExpiry`(`expireSessionsAfterRequests(2)` → SUCCEEDED · 그 설비 `loginCount == 2`) · `RolloutE2ETest.rejectedValueIsNotRetried`(`cooling_sec=999` 레시피 → FAILED · **`attempt == 1`** · 이력 0 · `last_error`에 콘솔 문구) · `ApplyVerificationTest.mismatchAfterSaveFailsTheTask`(`corruptNextSave()` → FAILED `VERIFY_MISMATCH` · attempt 1 · 이력 0). 씨앗 **S2**(재조회 대조 삭제 → `mismatchAfterSaveFailsTheTask` 붉음 — SUCCEEDED가 된다) 심고 `| S2 |` 행 — backend-quality 스킬 사용(글로벌 CLAUDE.md §10.6): 외부 시스템 경계는 빌드 구조로·결정적 주입·자기 신고를 믿지 않는 재조회 — 검증: `scripts/test.sh --tests 'com.rollout.e2e.ApplyVerificationTest.mismatchAfterSaveFailsTheTask' && scripts/test.sh --tests 'com.rollout.e2e.RolloutE2ETest.rejectedValueIsNotRetried' && scripts/test.sh --tests 'com.rollout.e2e.RolloutE2ETest.allTwelveEquipmentsAppliedWithTwelveHistoryRows' && test -f PROGRESS.md && grep -qE '^\| S2 \|' PROGRESS.md && scripts/test.sh`
- [x] 동시성 2층 마감(E2E 5 peak 등호) + 메트릭 4종 + 구조화 로깅·MDC + 워커 헬스 + 씨앗 S5: `BrowserConcurrencyE2ETest.peakConcurrentSessionsEqualsLimit`(`browser-concurrency=2` · `claim-parallelism=6` · `delayMs=300` · 6대 → 전부 SUCCEEDED ∧ **워커 peak(`browser_sessions_active` max 추적) == 2 ∧ 목업 `peakInFlight() == 2`** — 등호 둘, 총 호출 수·시간 판정 없음) · `MetricsTest.fourRolloutMetricsAreExposedOnWorkerPrometheus`(E2E 한 번 뒤 워커 모드 `/actuator/prometheus` 본문에 4종 이름 + `rollout_task_total{result="SUCCEEDED"}` ≥ 1 · `lease_expired_recovered_total` 존재) · `LoggingTest.taskLogLinesAreJsonWithJobAndTaskIdAndNoCredentials`(`CapturedOutput` JSON 줄에 `"job_id"`·`"task_id"` 키 + 그 줄들에 콘솔 비밀번호 문자열 부재 — 양의 앵커: 같은 task의 `applied` 로그 줄 존재) · `WorkerHealthTest.claimLoopHealthIsDownWhenLoopIsStale`(클록 +61초 후 `/actuator/health` DOWN · `ClaimLoop.iterateOnce()` 뒤 UP). 씨앗 **S5**(세마포어 N을 `claim-parallelism`으로 → peak 6 ≠ 2 붉음) 심고 `| S5 |` 행 — backend-quality 스킬 사용(글로벌 CLAUDE.md §10.6): 동시성 가드는 N 동시 → 허용 ≤ 한도를 횟수로·헬스는 실기동 신호·로그에 자격증명 없음은 양의 앵커와 함께 — 검증: `scripts/test.sh --tests 'com.rollout.e2e.BrowserConcurrencyE2ETest.peakConcurrentSessionsEqualsLimit' && scripts/test.sh --tests 'com.rollout.MetricsTest.fourRolloutMetricsAreExposedOnWorkerPrometheus' && scripts/test.sh --tests 'com.rollout.WorkerHealthTest.claimLoopHealthIsDownWhenLoopIsStale' && test -f PROGRESS.md && grep -qE '^\| S5 \|' PROGRESS.md && scripts/test.sh`
- [x] 인증·인가·감사 + REST API 전부 + 계약 + 인가 매트릭스 전수 + 감사 불변식 4 + 씨앗 S3·S6: `openapi.yml` 먼저(위 API 요약 전부 + `Error` 스키마 `code` enum, 모든 object `additionalProperties: false`, 401/403을 무인증 예외 넷 밖 오퍼레이션 전부에 선언) → 시큐리티(불변 조건 3: 세션 쿠키 · JSON 401 · 기본 거부 · 매 요청 `app_user` 재조회 · 더미 해시 대조) · `Policy`(3역할 × 7행위 리터럴 표 + `JOB_CANCEL_RETRY`의 자기 잡 조건 + 4-eyes) · `AuditWriter`(성공·DENIED·ERROR·로그인 실패 — 컨트롤러 어드바이스/필터 한 곳에서 계약의 쓰기 오퍼레이션 전부를 덮는다) · 에러 핸들러(불변 조건 4, exp-gf-mes 이식) · 사용자·설비·레시피·잡·이력·감사 컨트롤러/서비스(설비 응답 DTO에 `console_password` 없음 · 잡 생성은 `Idempotency-Key` 필수 + ACTIVE만 409 `RECIPE_NOT_ACTIVE` · 레시피 전이 허용 집합 `{DRAFT→ACTIVE, ACTIVE→RETIRED}` 외 409 `INVALID_TRANSITION`). 테스트: **`AuthorizationMatrixTest.everyRoleActionCellMatchesTheMatrix`**(`@ParameterizedTest` 3역할 × 7행위 = **21 셀**, 각 셀을 대표 엔드포인트 실호출로 밟고 허용은 2xx·거부는 403 `FORBIDDEN`, `assertEquals(21, cells)`) · `AuthorizationMatrixTest.approverCannotApproveOwnRecipe`(경계 1 — 403 `SELF_APPROVAL_FORBIDDEN`, 다른 APPROVER는 200) · `AuthorizationMatrixTest.operatorCancelsOnlyOwnJob`(경계 2 — 남의 잡 403, 자기 잡 200) · `AuthorizationMatrixTest.everyContractEndpointHasAMatrixRow`(계약 (경로,메서드) 전부가 21 셀 또는 무인증 예외 넷 중 하나에 매핑됨 — 등호) · `AuthApiTest.unknownUserAndWrongPasswordReturnIdenticalBodyAndOneHashCompare`(본문 바이트 동일 ∧ `matches` 호출 정확히 1 ∧ 더미 해시 cost == 실제 해시 cost) · `AuthApiTest.loginRotatesSessionIdAndSetsHttpOnlySameSiteLaxOverRealPort`(RANDOM_PORT 실 `Set-Cookie` — 로그인 전후 세션 쿠키 값 다름 · 옛 세션 id로 `GET /api/auth/me` 401 · 헤더에 `HttpOnly`·`SameSite=Lax`) · `AuthApiTest.passwordOver72BytesIsRejectedWith400` · `AuthApiTest.everyNonPublicEndpointRejectsUnauthenticatedWithJson401`(계약에서 읽은 경로 전부) · `AuthApiTest.disabledUserSessionIsRejectedImmediately`(로그인 → ADMIN이 `enabled=false` → 같은 쿠키로 다음 요청 401) · `AuthApiTest.noStateChangingGetInContract` · **`AuditInvariantTest.everyWriteEndpointAuditsDeniedAndSuccessfulAttempts`**(계약의 쓰기 오퍼레이션 전부: 거부 역할로 403 → `DENIED` 행, 허용 역할로 성공 → `OK` 행, 각 오퍼레이션마다 두 행 존재 — 등호) · `AuditInvariantTest.loginFailureIsAudited` · `AuditInvariantTest.auditLogHasNoUpdateOrDeletePath`(트리거 + `grep -rniE 'update audit_log|delete from audit_log' backend/src/main` 0건을 테스트 안에서 실행) · `AuditInvariantTest.detailNeverContainsCredentials`(비밀번호·해시·콘솔 비밀번호·세션 쿠키 값이 `audit_log.detail` 전 행·응답·`CapturedOutput`에 없음 + 양의 앵커) · `AuditInvariantTest.actorNameIsSnapshotAfterRename` · `EquipmentApiTest.consoleCredentialsNeverAppearInResponses`. 씨앗 **S3**(`AuditWriter` 호출 삭제 → `everyWriteEndpointAudits…` 붉음)·**S6**(4-eyes 비교 삭제 → `approverCannotApproveOwnRecipe` 붉음) 심고 `| S3 |`·`| S6 |` 행. 스모크에 종단 추가: 로그인(admin1 시드 없음 — 스모크가 JDBC로 임시 계정을 만든다) → 설비·레시피 등록 → 승인(다른 사용자) → 잡 생성 201 → 같은 키 재요청 200 동일 id → 무인증 `GET /api/jobs` 401 JSON → OPERATOR가 `GET /api/audit` 403 → 로그에 비밀번호 0건 — backend-quality 스킬 사용(글로벌 CLAUDE.md §10.6): 계약 우선·보안 불변조건 테스트화·인가 테스트 매트릭스(계약에서 엔드포인트 집합을 읽어 등호)·존재 은닉 401은 비용까지 같게·기본 거부·에러 형식과 로그 규율·인증 직후 리뷰(작업 7) — 검증: `scripts/test.sh --tests 'com.rollout.AuthorizationMatrixTest' && scripts/test.sh --tests 'com.rollout.AuditInvariantTest' && scripts/test.sh --tests 'com.rollout.AuthApiTest.disabledUserSessionIsRejectedImmediately' && scripts/test.sh --tests 'com.rollout.AuthApiTest.everyNonPublicEndpointRejectsUnauthenticatedWithJson401' && test "$(grep -oE '^\| S[36] \|' PROGRESS.md | sort -u | wc -l)" -eq 2 && scripts/smoke.sh && scripts/test.sh`
- [x] 실시간 진행(LISTEN/NOTIFY → WebSocket) + 계약 인프라 마감(엔드포인트 집합 대조·본문 상한·설정 핀) + 스모크 WS: `ProgressNotifier`(워커 커밋 후 `NOTIFY rollout_progress, '<job_id>'` — 작업 2의 자리에서 실제 발행) · api 모드 `ProgressListener`(전용 JDBC 커넥션 하나로 `LISTEN rollout_progress`, `PGConnection.getNotifications(timeout)` 루프, 알림마다 잡 스냅샷 `{jobId, status, tasks:[{equipmentCode, status, attempt, lastError}]}`을 해당 잡의 WebSocket 세션들에 push) · `GET /api/ws/jobs/{id}` 핸들러(핸드셰이크 인터셉터가 세션 쿠키 인증 확인 — 무인증은 핸드셰이크 거부, 허용 Origin은 `APP_WEB_ORIGIN` 하나, 연결 직후 현재 스냅샷 1회 전송). 테스트: `RealtimeTest.taskCommitReachesWebSocketSubscriberWithoutPolling`(RANDOM_PORT api 모드 · 로그인 쿠키로 WS 연결 · 초기 스냅샷 수신 · 테스트가 `TaskOutcomeService.succeed` 호출 → 갱신 스냅샷 수신(task SUCCEEDED) · 그 사이 api의 `rollout_task` 조회 SQL 실행 수가 **알림 수와 같다**(`pg_stat_statements` 없이 — `JdbcClient`를 감싼 계수 데이터소스 프록시로 SELECT 수 계수) — 폴링 부재의 증거) · `RealtimeTest.unauthenticatedHandshakeIsRejected` · `RealtimeTest.foreignOriginHandshakeIsRejected` · `ContractEndpointTest.contractEndpointSetMatchesActualMappings`(계약 (경로,메서드) == 스프링 실매핑 **양방향**, WS 경로 포함, 빈 집합 `check`) · `ErrorHandlingTest.oversizedChunkedBodyIsRejectedBeforeParsing`(RANDOM_PORT + JDK HttpClient `ofInputStream` 5 MB → 413 `PAYLOAD_TOO_LARGE` ∧ 프로브 핸들러 호출 0 · exp-gf-mes 이식) · `ErrorHandlingTest.unknownPathIs404AndUnknownFieldIs400` · `ConfigPinTest`(`assertEquals("<리터럴>", env.getProperty(키))` 모양으로 `server.address=127.0.0.1` · `spring.servlet.multipart.enabled=false` · `spring.mvc.formcontent.filter.enabled=false` · `spring.jackson.deserialization.fail-on-unknown-properties=true` · `logging.structured.format.console=logstash` · `worker.browser-concurrency=3` · `worker.lease-seconds=120` · `worker.heartbeat-seconds=30` — 삭제·반전 둘 다 잡는 형태) + 멀티파트 동작 팔(`multipart/form-data` 1 MB → 400 `INVALID_REQUEST` ∧ 핸들러 호출 0 ∧ `MultipartResolver` 빈 부재). 스모크에 WS 핸드셰이크 추가(`curl -i -N -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: …'` 쿠키 있음 → 101, 없음 → 401/403) — backend-quality 스킬 사용(글로벌 CLAUDE.md §10.6): 계약↔매핑 양방향·본문 바이트 상한은 파싱 전·설정 핀은 값과 동작 따로·인증은 WS 핸드셰이크에도 — 검증: `scripts/test.sh --tests 'com.rollout.RealtimeTest.taskCommitReachesWebSocketSubscriberWithoutPolling' && scripts/test.sh --tests 'com.rollout.RealtimeTest.unauthenticatedHandshakeIsRejected' && scripts/test.sh --tests 'com.rollout.ContractEndpointTest.contractEndpointSetMatchesActualMappings' && scripts/test.sh --tests 'com.rollout.ErrorHandlingTest.oversizedChunkedBodyIsRejectedBeforeParsing' && scripts/test.sh --tests 'com.rollout.ConfigPinTest' && scripts/smoke.sh && scripts/test.sh`
- [x] 독립 리뷰 2인(채점자 분리 — 작성자 자기 채점 금지, 인증·감사·동시성이라 두 렌즈를 **따로**): 위임 전 남은 예산을 보고 못 끝낼 것 같으면 B를 먼저 줄인다. 두 위임 프롬프트 첫 줄은 `git merge --ff-only <현재 HEAD sha>`(worktree 격리는 `origin/master`에서 만들어진다 — 글로벌 GOTCHAS), 끝나면 `git worktree remove`로 지운다. **리뷰어 A**(diff 진입): `git diff master...HEAD` + `openapi.yml` + `backend-quality` 스킬 본문 + 이 PLAN의 **불변 조건 3·4·9·16만**(계획 서사·결정 대기는 주지 않는다)을 주고 — ⓐ **S1~S6 각각** 씨앗을 다시 심어 붉음·복원을 확인(불변 조건 14 절차, 자기 손으로) ⓑ 검증 명령 `scripts/test.sh`·`scripts/smoke.sh`에 결함을 심어 실제로 실패하는지 확인 후 복원 — 보고서 `## 검증 명령 실효` 절에 **낱말 삭제 씨앗과 진위 씨앗을 갈라** 적는다 ⓒ 보안 잔여분: 불변 조건 16의 값과 구현이 어긋난 곳(바인드·허용 경로·저장·로그 항목) · 저장·로깅 항목이 과한가 · 코드 밖 폐기 절차가 레저에 남았나 ⓓ `CLAUDE.md`·`.claude/GOTCHAS.md`(있으면)에서 코드가 무효화한 서술 정정 ⓔ 표 — 발견 `| A1 | … |`, 씨앗은 **정확히 한 행씩** `| S1 | … |`~`| S6 |`. **리뷰어 B**(diff 없이, worktree): 소스 트리·계약·스킬 본문·불변 조건 16의 보안 값만 주고 — 인가의 형제 경로(레시피 수정·폐기·잡 재시도·사용자 수정의 역할 경계, 자기 잡 조건의 우회) · 경계 산술(attempt 상한 ±1 · 리스 만료 등호 · 백오프 상한 · 세마포어 N과 풀 크기) · 이 증분이 만들지 않은 카운터 · 잡 취소와 실행 중 task의 경합 · NOTIFY 유실(커밋 전 발행·리스너 커넥션 끊김) · 감사 누락 경로(WS 핸드셰이크 거부·로그아웃)를 처음부터 읽고 **스스로 고른 씨앗 3개 이상** `| M1 | … |`, 발견 `| B1 | … |`. 두 보고서 머리에 `## 리뷰어 입력`(받은 것·읽지 않은 것)을 요구하고, 리뷰어는 **씨앗 실행·기록까지가 완료 조건**이며 자기 권고를 직접 심어 재 본 결과를 적는다(뮤테이션 총수를 보고서에). 초안을 먼저 `docs/review/review-{A,B}.md`로 저장시키고, 위임이 가드·타임아웃에 잘리면 초안을 입력으로 **재위임**한다(재개 반복이 직접 리뷰하지 않는다). 모델 다운그레이드 없이 위임. `PROGRESS.md`는 「작성자 자기 서술이니 주장 확인용」으로만 — 검증: `test "$(grep -cE '^\| S[1-6] \|' docs/review/review-A.md)" -eq 6 && grep -q '^## 리뷰어 입력' docs/review/review-A.md && grep -q '^## 리뷰어 입력' docs/review/review-B.md && test "$(grep -cE '^\| M[0-9]+ \|' docs/review/review-B.md)" -ge 3 && grep -q '진위 씨앗' docs/review/review-A.md && test "$(git worktree list | wc -l)" -eq 1 && git diff --quiet && scripts/test.sh`
- [x] (결정 대기 1 확정 후) 운영 화면 7개(Next.js) + 서체 셀프호스팅 + 디자인 게이트 + web-smoke + dev-up: `web/`(own-shop 핀 그대로 `next@16.3.0`·`react@19.2.4`·`react-dom@19.2.4`·`typescript`·`@types/*`, `output: "standalone"`, `rewrites` `/api/:path*` → `${API_ORIGIN:-http://127.0.0.1:8080}`, `NEXT_PUBLIC_` 금지 — WS 원점은 `GET /ws-origin` 라우트 핸들러가 런타임 env `WS_ORIGIN`(기본 `ws://localhost:8080`)을 돌려준다) · 서체(불변 조건 15): `web/app/fonts/Pretendard-{Regular,Medium,SemiBold,Bold}.woff2`(위 jsdelivr npm URL에서 받아 커밋, 서브셋 선택) + `MonoplexKR-{Regular,Medium,Bold}.woff2`(결정 대기 1의 방법으로 확보 — 없으면 이 작업을 시작하지 말고 PROGRESS에 막힘 기록) · `layout.tsx` `next/font/local` 두 벌(`--font-sans`·`--font-mono`, `display: "swap"`) · `globals.css`(폴백 스택 문자열 그대로 · `font-feature-settings: "tnum"` · `word-break: keep-all` · 설비코드·잡ID·파라미터·로그는 `--font-mono`) · `web/app/fonts/README.md`(출처 URL·버전·OFL 고지 둘) · 화면 7: `/login` · `/equipment`(상태·마지막 적용 레시피) · `/recipes`·`/recipes/[id]`(승인 버튼은 `me.role === APPROVER`에게만 렌더 — 서버가 강제, UI는 숨김) · `/jobs/new`(레시피 select + 설비 다중 선택, `Idempotency-Key`는 페이지 마운트 시 `crypto.randomUUID()`) · `/jobs/[id]`(WS 구독 → 설비별 단계·attempt·오류, 재연결 1회) · `/history`(타임라인) · `/audit`(APPROVER·ADMIN). 순수 함수는 `web/app/lib/*.ts` + `node --test --experimental-strip-types` 테스트(잡 진행률 집계 `progress()`·상태 라벨·WS 스냅샷 병합). `scripts/design-gates.sh`(own-shop 것 이식 — `nomatch` 래퍼(exit 1만 통과) · keep-all · tnum · AI 기본 서체 4팔 · CSS 외부 `url()` **예외 없이** 금지 · img alt/width/loading · `_blank` · SRI · 한다체 + **타이포그래피 양의 앵커**: 7개 woff2 파일 `test -s` · `grep -c 'path: "./fonts/Pretendard-' web/app/layout.tsx` == 4 · `MonoplexKR-` == 3 · 폴백 문자열 리터럴 1줄 · `NEXT_PUBLIC_` 0건) — 게이트마다 씨앗 하나로 발화 확인(`| G<n> |` 행) · `scripts/web-smoke.sh`(`${WEB_PORT:-13000}`·`${SMOKE_PORT:-18080}` 비어 있음 확인 → api 모드 bootTestRun + `npm run build && npm run start` → 7경로 curl 200(SSR 셸) → 루트 HTML이 참조하는 `/_next/static/**` 자산 전부 200(standalone 함정) → 서빙 CSS에 `Pretendard`·`MonoplexKR` `@font-face` 존재 → 정리·포트 해제) · `scripts/dev-up.sh`(사람 시각 판독용 실기동: api(bootTestRun, 데모 시드 켬, 임베디드 DB URL을 `build/dev-db.url`에 씀) + worker(`:backend:bootRun --args='--mode=worker --spring.datasource.url=<그 URL>'`) + console-mock + `next dev`; `--check`는 넷의 200 확인 후 종료) — 프론트 스킬(frontend-design·design-quality)과 fonts-data API 사용(글로벌 CLAUDE.md §10.5) — 단 서체는 설계 확정값이라 fonts-data 조회는 하지 않고 값 그대로 쓴다; `LOOP_UI=0`이라 캡처·E2E 없이 기계 확인만, 디자인 판단은 PROGRESS 「대화 세션 몫」으로 — 힌트(튜닝 2026-09-17): **결정 대기 1은 이미 확정됐다** — 이 파일 `## 결정 기입` 1(사람이 ⓐ로 해결: `web/app/fonts/RolloutMono-{Regular,Medium,Bold}.woff2` 3종 + `NOTICE.md`가 커밋돼 있다, `git ls-files web/app/fonts`로 확인). **즉시 시작하라, 「막힘」 기록 금지**(직전 두 세션이 `## 결정 대기`만 읽고 답을 놓쳐 이 작업을 건너뛰었다). 이 줄 본문의 `MonoplexKR-*.woff2`·게이트 앵커 `MonoplexKR-` == 3·web-smoke의 `MonoplexKR` @font-face는 전부 **`RolloutMono-` / family `Rollout Mono`**로 읽는다(OFL Reserved Font Name 개명 — 코드·CSS·문서에 Monoplex 이름 금지, README·fonts README는 `NOTICE.md`를 링크). Pretendard 4종은 allowlist 안의 `cdn.jsdelivr.net`에서 받아 커밋. 이식 원본: `~/own-shop/scripts/design-gates.sh`·`web-smoke.sh`, `~/own-shop/web/{package.json,next.config.ts,app/layout.tsx}`. 씨앗은 포그라운드 하나씩·즉시 복원. — 검증: `bash scripts/design-gates.sh && test -s web/app/fonts/RolloutMono-Regular.woff2 && test -s web/app/fonts/RolloutMono-Medium.woff2 && test -s web/app/fonts/RolloutMono-Bold.woff2 && test "$(grep -c 'path: "./fonts/Pretendard-' web/app/layout.tsx)" -eq 4 && (cd web && npm test && npx tsc --noEmit) && scripts/web-smoke.sh && scripts/dev-up.sh --check`
- [x] compose 데모 + 시드 + README + 실행 환경 점검 게이트 + 발견 처리 + 마감: `compose.yaml`(5 서비스 — `postgres:18` · `console-mock` · `api`(`--mode=api`) · `worker`(`--mode=worker`, `mcr.microsoft.com/playwright/java:v1.62.0-noble` 기반 Dockerfile) · `web`(node standalone, `public/`·`.next/static` 복사) · `ports`는 **전부** `"127.0.0.1:<h>:<c>"`(3000·8080·8090) · `${POSTGRES_PASSWORD:?}`·`${SEED_PASSWORD:?}` · `MOCK_FAIL_RATE=0.10` · healthcheck 넷(worker는 `/actuator/health`) · `depends_on` condition) · Dockerfile 3개 · 데모 시드(`app.seed.demo=true`일 때 api 기동 시 1회: 설비 12대 `EQ-01..12`(콘솔 URL `http://console-mock:8090`, 상태 RUNNING 10·STOPPED 1·MAINTENANCE 1) · 품번 3종(`LNS-2040`·`LNS-2050`·`LNS-3010`) × v1~v3(v1·v2 RETIRED/ACTIVE, **v3는 DRAFT** — 60초 시나리오의 「승인」 대상) · 계정 `operator1`/`approver1`/`admin1`(비밀번호 = `SEED_PASSWORD`, 코드에 기본값 없음) · 시드는 멱등(재기동 시 중복 0)) + `SeedTest.demoSeedIsIdempotentAndAbsentWithoutFlag` · `README.md`(첫머리 60초 시나리오: `SEED_PASSWORD=… POSTGRES_PASSWORD=… docker compose up` → 로그인 → v3 승인(approver1) → 12대 배포 → 실시간 진행 → 1~2대 실패 → 재시도 → 이력·감사 확인 · Playwright 이미지 첫 pull 예상 시간 · `/actuator/prometheus` 스크레이프 예시 · 서체 라이선스 고지(Pretendard OFL · MonoplexKR OFL, Reserved Font Name) · 설계 §11 확장 지점·§12 천장 링크) · **`scripts/deploy-gates.sh`**(실행 환경 점검 — 정적, 실행 중 포트를 재지 않는다: ① `POSTGRES_PASSWORD=x SEED_PASSWORD=x docker compose config -q` exit 0 ∧ 둘 중 하나 미설정 → exit 1 ② 게시 포트 양의 앵커: `grep -cE '^\s*-\s*"127\.0\.0\.1:[0-9]+:[0-9]+"$' compose.yaml` == 3 ∧ 포트 줄 총수(`^\s*-\s*"[^"]*:[0-9]+:[0-9]+"$`) == 3 ③ 환경변수 주입 시점: `web/` 소스·Dockerfile에 `NEXT_PUBLIC_` 0건(`test -d web && ! rg -q 'NEXT_PUBLIC_' web/app web/next.config.ts web/Dockerfile`) ∧ 백엔드 Dockerfile에 `ENV .*PASSWORD` 0건 ④ 기본 자격증명 잔존: 운영 소스에 `operator1|approver1|admin1` 리터럴은 시드 클래스 한 파일에만(`rg -l` 결과 == 1) ∧ `SEED_PASSWORD` 기본값 없음(`rg -q 'SEED_PASSWORD:-' compose.yaml` 매치 없음 ∧ 시드 코드에 비밀번호 리터럴 없음)) — 게이트마다 씨앗(`0.0.0.0` 포트 줄 · `:-기본값` · `NEXT_PUBLIC_` 한 줄)으로 발화 확인 후 복원, `| G<n> |` 행. 이어서 **발견 처리**: 입력 `docs/review/review-A.md`·`review-B.md`, 각 `A<n>`·`B<n>`을 **수정**(재현 테스트 먼저 붉게 → 통과, 새 테스트마다 결함을 심어 붉은지 보는 **음성 팔**을 씨앗 표에)하거나 **레저**(PROGRESS `## 리스크 원장` `- R<n> (A3) …` + 「운영 전 재검토」 플래그 — **스킬 불변식(기본 거부·단일 에러 형식·본문 상한·잠금·감사 append-only 등)을 어기는 발견은 레저할 수 없다: 고치거나 결정 대기로**; 「고치면 X가 깨진다」는 X를 돌려 결과를 적는다; 「결정 사항」 레저마다 나쁜 갈래가 **다른 사용자·다른 잡의 정당한 요청을 무효화하는지 실서버 시나리오 1건**을 `findings-handled.md` 행에)하거나 **오탐**으로 닫고 `docs/review/findings-handled.md` `| A1 | 수정 | … |` 표. 마감: ① PROGRESS `## 씨앗 표` S1~S6 각 한 행 이상 + 우회 클래스 열 + G 행 ② `CLAUDE.md` 최종(구조·명령·게이트·모드) + 「없다/하지 않는다」 문장마다 반례 grep 0건 **부정 서술 대조** 표(개인정보 컬럼 없음 `rg -i 'email|phone|contact'` · CORS 없음 `CrossOrigin|CorsRegistry` · ORM 없음 `jakarta.persistence` · 콘솔 비밀번호 응답 없음 `consolePassword` DTO 0건 · SQL `now()` 0건) ③ PROGRESS 「관찰」의 **미검증** 항목을 재현 테스트 또는 원장 R 줄로 전부 닫는다 ④ 원장에 콘솔 자격증명 평문 저장(운영 전 재검토) · Playwright 이미지 크기 · 결정 대기 1~7 채택 해석 · 「나중 형제 쓰기 경로는 아무 검사도 못 죽인다」 천장 · MonoplexKR 소싱 경로를 적는다 — 힌트(튜닝 2026-09-17): 발견 처리 입력에 `docs/review/review-A-rerun.md`(A1~A12 + 「검증 명령 실효」 W1·WS1)도 넣는다 — `findings-handled.md`의 행 id는 **`AR<n>`**(예 `| AR1 | 수정 | … |`)로 써서 검증 줄의 `[AB][0-9]+` 집계(review-A/B 행 수 등호)와 섞이지 않게 한다(원장 줄도 `- R<n> (AR1)` 꼴). AR1~AR3(퍼센트 인코딩 경로의 감사 누락 = 감사 전수 불변식 위반)은 레저 불가 → 필터에서 디코딩 후 정규화(+`action` 길이 상한 처리) + 인코딩 경로 회귀 테스트로 수정. AR4(`GET /api/equipment`의 `consoleUser`)는 불변 조건 16 문면대로 응답 DTO에서 제거(계약 먼저) — ADMIN이 값을 못 보는 천장은 원장 R 줄. AR10: compose의 api·worker에 `SERVER_ADDRESS=0.0.0.0`(핀 테스트가 로컬 기본 `127.0.0.1`을 지킨다). — 검증: `bash scripts/deploy-gates.sh && scripts/test.sh --tests 'com.rollout.SeedTest.demoSeedIsIdempotentAndAbsentWithoutFlag' && test -s docs/review/findings-handled.md && test "$(cat docs/review/review-A.md docs/review/review-B.md | grep -cE '^\| [AB][0-9]+ \|')" -eq "$(grep -cE '^\| [AB][0-9]+ \| (수정|레저|오탐) \|' docs/review/findings-handled.md)" && test "$(grep -cE '^\| [AB][0-9]+ \| (레저|오탐) \|' docs/review/findings-handled.md)" -eq "$(grep -cE '^- R[0-9]+ \([AB][0-9]+\)' PROGRESS.md)" && test "$(grep -cE '^\| [AB][0-9]+ \| 레저 \|' docs/review/findings-handled.md)" -eq "$(grep -cE '^\| [AB][0-9]+ \| 레저 \|.*실서버' docs/review/findings-handled.md)" && test "$(grep -oE '^\| S[1-6] \|' PROGRESS.md | sort -u | wc -l)" -eq 6 && grep -q '우회 클래스' PROGRESS.md && grep -q '부정 서술 대조' PROGRESS.md && test -f PROGRESS.md && ! grep -qE '미검증(이다|\.| 관찰)' PROGRESS.md && grep -q '60초' README.md && scripts/smoke.sh && bash scripts/design-gates.sh && scripts/test.sh`

## 병합 전 사람 몫

- **`docker compose up`은 루프가 못 돈다**(소켓 차단). 병합 전 샌드박스 밖에서 `SEED_PASSWORD=… POSTGRES_PASSWORD=… docker compose up`으로 README 60초 시나리오를 실제로
  밟고, 게시 포트가 `127.0.0.1`에만 열렸는지(`/usr/sbin/ss -tln`) 살아 있는 배포본으로 확인한다. 루프의 실행 환경 점검(`scripts/deploy-gates.sh`)은 정적 구성 검사뿐이다.
- 화면 7개의 시각 판독은 `scripts/dev-up.sh`로 띄워 사람이 한다(`LOOP_UI=0` — 루프는 캡처·E2E를 만들지 않았다). 색·여백·카피 톤은 이 런의 산출물이 아니다(불변 조건 15).
- `docs/review/*.md`·PROGRESS 씨앗 표·리스크 원장을 읽는다 — 특히 「살아남았다」고 적힌 씨앗과 「결정 사항」 레저의 실서버 시나리오.
- 결정 대기 2~7의 기본값이 채택된 채 구현된다. 다른 해석을 원하면 계획 검토 때 적는다. **결정 대기 1(MonoplexKR)은 작업 8을 막는다** — 런 시작 전에 답해야 한다.

## 결정 대기

1. **MonoplexKR 소싱(작업 8 차단)**: 루프는 파일을 받을 수 없다(위 실측 — 릴리스 zip이 `release-assets.githubusercontent.com`으로 302, 차단). 선택지: ⓐ 사람이
   `MonoplexKR-v0.0.2.zip`을 받아 TTF를 `pyftsubset --flavor=woff2`로 변환(또는 TTF 그대로)해 `web/app/fonts/MonoplexKR-{Regular,Medium,Bold}.woff2`를 런 시작 전에 커밋한다
   ⓑ `.claude/sandbox-domains.txt`에 `release-assets.githubusercontent.com`을 추가해 루프가 받게 한다(zip 안의 파일명·굵기 구성은 미검증 전제 — 루프가 열어 보고 맞춘다).
   두 경우 모두 라이선스는 OFL 1.1(실측)이고 README에 Reserved Font Name 고지를 넣는다. 다른 모노 서체로 대체하는 선택지는 없다(설계 §10 확정값).
2. **소규칙 기본값 묶음**(이견 없으면 그대로 간다): ⓐ 백오프 시각을 위해 `rollout_task.next_attempt_at` 컬럼을 추가한다(대안: `lease_expires_at`을 「다음 시도 전 대기」로
   겹쳐 쓰기 — 한 컬럼에 두 뜻이라 배제) ⓑ 「상한(3회) 초과면 FAILED」를 **총 시도 3회**로 읽는다(`attempt` = 시도 횟수, 3회째 실패 = FAILED; 대안: 재시도 3회 = 총 4회)
   ⓒ `VERIFY_MISMATCH`는 재시도하지 않는다(값 거부와 같은 부류 — 재시도해도 같은 값이 들어간다) ⓓ 백오프 base 2초·상한 60초·지터 없음 ⓔ 잡 취소는 `PENDING` task만 `CANCELLED`
   (실행 중은 끝까지), 재시도는 `FAILED`·`CANCELLED` task를 `attempt 0`으로 ⓕ 레시피 `PUT`은 DRAFT에만, ACTIVE 레시피의 수정은 새 버전 초안으로 ⓖ 잡 생성 시 `MAINTENANCE`
   설비는 요청에 포함할 수 없다(400) — 실행 시점에 MAINTENANCE로 바뀐 설비는 분류표대로 재시도 X ⓗ `application_history`의 NO_CHANGE 판정 기준은 그 설비의 **최신** APPLIED
   행의 `part_no`+`version`(폐기된 레시피여도 마지막에 적용된 것이 기준) ⓘ 세션 타임아웃은 Boot 기본(30분) ⓙ 목업 콘솔 유효 범위는 작업 3의 수치 다섯.
3. **워커의 HTTP**: 설계 §3 「worker는 HTTP를 받지 않는다」와 §10 「워커 헬스체크 + `/actuator/prometheus`」를 함께 만족하는 읽기 — 기본값: 워커는 **애플리케이션 API를 매핑하지
   않고** `/actuator/health`·`/actuator/prometheus`만 연다(그 외 404). 대안: 헬스는 파일 하트비트(`compose healthcheck: test -f`)로 하고 메트릭 스크레이프를 포기 — §10과 충돌.
4. **인증·인가 모델(보안 축)**: 기본값 = 세션 쿠키(`HttpOnly`·`SameSite=Lax`, 메모리 세션) + 무인증 허용 경로 **정확히 넷**(`POST /api/auth/login`·`GET /api/health`·
   `/actuator/health`·`/actuator/prometheus`). 대안: JWT 베어러(SSO 교체 지점을 §11이 「인증 필터 한 곳」으로 두므로 세션이 더 단순) · `/actuator/prometheus`를 인증 뒤로
   (Prometheus 스크레이프 설정이 복잡해진다 — 데모 범위에서 열어 둔다). 재는 것: 작업 5의 계약 기반 401 테스트(넷 밖 전부).
5. **비밀값 수명·폐기(보안 축)**: 기본값 = 세션은 메모리(api 재기동·로그아웃·`enabled=false`로 즉시 폐기 — 작업 5 테스트가 「비활성화 뒤 기존 쿠키 401」을 돌린다) · DB 비밀번호
   `${POSTGRES_PASSWORD:?}` · 시드 비밀번호 `${SEED_PASSWORD:?}`(compose가 요구, 소스에 기본값 없음 — `deploy-gates.sh`) · 콘솔 자격증명 교체 = ADMIN의 `PUT /api/equipment/{id}`.
   「사람이 손 떼면 폐기」 대상 = 그 사람의 `app_user.enabled=false`(ADMIN) + 그가 알던 콘솔 자격증명 교체(코드 밖 절차 — 원장에 남긴다). 서명키는 없다(세션 id는 난수, 재기동이
   곧 전체 폐기).
6. **노출 범위(보안 축)**: 기본값 = compose `ports` 전부 `127.0.0.1`(web 3000 · api 8080 · console-mock 8090), 컨테이너 안은 `0.0.0.0`(compose 네트워크), 로컬 스크립트는
   `server.address=127.0.0.1`, CORS 없음, WS Origin `APP_WEB_ORIGIN` 하나. 대안: console-mock을 게시하지 않음(사람이 콘솔 화면을 직접 볼 수 없다) · LAN 공개(인증이 있으니
   가능하지만 TLS 없음 — 이 런에서는 하지 않는다). **천장**: `--unshare-net` 안에서 실바인드는 못 잰다 — 정적 검사만.
7. **개인정보·민감데이터 범위(보안 축)**: 기본값 = `username`·`display_name`만(실명·연락처·이메일 컬럼 없음) · 콘솔 자격증명은 DB 평문(설계가 Vault를 제외하고 「환경변수 + DB」라
   함 — 컬럼 암호화(앱 키 env + pgcrypto)는 **운영 전 재검토**로 원장에) · `audit_log.detail`에는 대상 id·변경 필드명·상태 전이만(값 스냅샷은 `params`처럼 비밀이 아닌 것만).
   재는 것: 작업 5의 비밀 문자열 부재 + 양의 앵커, 작업 9의 부정 서술 대조.
8. **작업 수 9 > 7**(직전 회고 (범용) 줄 미반영): 설계 범위(워커·목업·E2E 5·인가 매트릭스·감사 4·실시간·화면 7·compose)가 2시간 fresh-context 작업 7개에 들어가지 않고, 더
   합치면 「2시간 안에 구현+검증+커밋」 쪽이 깨진다(그쪽이 더 단단한 제약). 첫 청크 반복 상한 8 → 재시도 여유 0이고 둘째 청크가 필요하다는 뜻이다. 대안: 리뷰(7)를 마감(9)에
   합쳐 8개 — 인증 직후 리뷰라는 스킬 규칙을 잃는다.
9. **샌드박스 도메인**: 기본 계획은 base allowlist만으로 돈다(`repo.maven.apache.org`·`registry.npmjs.org`·`cdn.jsdelivr.net` 실측). 결정 대기 1ⓑ를 택하면
   `release-assets.githubusercontent.com` 추가. `mcr.microsoft.com`은 사람의 compose 실행에만 필요(루프 밖).
10. **스킬 규칙 미전사**: backend-quality의 계약 적합성 테스트(선언 (오퍼레이션, 상태) 전부를 밟는 등호 커버리지)·redocly strict 린트·trivy·SpotBugs 게이트 — 설계 §9 테스트
    전략에 없고 9작업 예산 밖이라 넣지 않았다(계약↔매핑 양방향 집합 대조·인가 매트릭스의 계약 기반 등호까지만). 로그인 스로틀(429) — 설계 범위 밖(원장에 「운영 전」으로).
    Lost update 봉인(`insertable=false` 류) — ORM 없음, 쓰기는 원자 UPDATE 문장. 리뷰어 B가 다르게 보면 발견으로 올린다.
11. **글로벌·jvm GOTCHAS 승격 후보(대화 세션 몫)**: 「Playwright Java `create()`는 `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` env 없이 샌드박스에서 5분 넘게 멈춘다(firefox/webkit
    다운로드 시도) — `CreateOptions().setEnv`로 줄 것, 크로미엄 리비전은 호스트 캐시(1234 = 1.62.x)에 맞춘다」 · 「`docker compose config`는 데몬 없이 샌드박스에서 돈다 — compose
    정적 검사에 쓸 수 있다」 · 「멀티모듈 `./gradlew test --tests X`는 테스트 없는 모듈이 있어도 exit 0이지만 그 모듈에 테스트가 생기면 매치 0으로 붉어진다 — 지목은
    `:모듈:test`로」. 스킬 승격 후보는 없다(사람 교정 기록이 아직 없다).

## 결정 기입 (2026-09-16, 대화 세션 — 이 절이 결정 대기의 답이다)

**1. MonoplexKR 소싱 — ⓐ로 해결했고 파일은 이미 커밋돼 있다. 루프는 아무것도 받지 않는다.**
`web/app/fonts/`에 `RolloutMono-{Regular,Medium,Bold}.woff2` 3종(합계 1.0MB)이 있다. 그대로 `next/font/local`로 실으면 된다.
- **패밀리명은 `Rollout Mono`다.** CSS/`next/font/local`의 family도 그 이름을 쓴다. 원본 이름(`Monoplex`·`Monoplex KR`)을 코드·문서 어디에도 폰트 이름으로 쓰지 마라 —
  OFL 1.1 Reserved Font Name 조항상 **수정본(서브셋+포맷변환)은 원래 이름을 쓸 수 없다.** 이 이유로 개명한 것이고 되돌리지 마라.
- 굵기 매핑: Regular=400 · Medium=500 · Bold=700. 커버리지는 한글 음절 U+AC00–D7A3 전체 + ASCII·Latin-1·문장부호·통화·화살표·괘선·전각기호.
- 고지는 `web/app/fonts/NOTICE.md`에 있다. **README에서 이 파일을 링크하라**(원저작권·OFL·수정 내역).
- Pretendard는 base allowlist의 `cdn.jsdelivr.net`에서 받아 같은 디렉터리에 셀프호스팅한다(수정 없이 그대로). 받은 파일도 커밋한다 —
  compose 데모는 인터넷 없이 떠야 한다.
- `.claude/sandbox-domains.txt`는 추가하지 않는다(결정 대기 1ⓑ·9 기각 — 릴리스 자산 도메인을 무인 루프에 열 이유가 없다).

**2~7. 전부 기본값 그대로 간다.** 소규칙 묶음(ⓐ~ⓙ) · 워커는 `/actuator/health`·`/actuator/prometheus`만 열고 그 외 404 ·
세션 쿠키 + 무인증 경로 정확히 넷 · 세션은 메모리(재기동=전체 폐기)·`${POSTGRES_PASSWORD:?}`·`${SEED_PASSWORD:?}` ·
게시 포트 전부 `127.0.0.1` · 개인정보는 `username`·`display_name`만. 이견 없음.

**8. 작업 9개 그대로 간다.** 근거(2시간 안에 구현+검증+커밋이 더 단단한 제약)를 받아들인다. 리뷰를 마감에 합치지 마라.

**10. 미전사 규칙은 이번 범위 밖으로 둔다** — 계약 적합성 등호 커버리지·redocly strict·trivy·SpotBugs·로그인 스로틀은 넣지 않는다.
리뷰어가 발견으로 올리면 그때 원장에 적는다.

**11. GOTCHAS 승격 후보 3건은 대화 세션이 처리한다.** 루프는 PROGRESS에만 남기고 글로벌 파일을 건드리지 마라.

## 스킬 전사 회수

- backend-quality의 「Login: same 401 body **and** same cost」: 빠졌던 반쪽(더미 해시와 실제 해시의 cost factor 등호 단언 — `calls[0] == DUMMY` 자기참조 단언은 라운드 하향을 못 잡는다)을 불변 조건 3과 작업 5(`unknownUserAndWrongPasswordReturnIdenticalBodyAndOneHashCompare`)에 회수
- backend-quality의 「Sessions: httpOnly + SameSite=Lax **and** invalidate the old session and issue a new id on login」: 빠졌던 반쪽(로그인 시 세션 id 재발급 — 세션 고정 방어)을 불변 조건 3과 작업 5(`loginRotatesSessionIdAndSetsHttpOnlySameSiteLaxOverRealPort`)에 회수
- backend-quality의 「CSRF-disabled 검증 셋: SameSite 설정 · **실포트 실제 `Set-Cookie` 헤더로 단언(MockMvc는 못 한다)** · 상태 변경 GET 없음」: 빠졌던 가운데 조각(실포트 헤더 단언)을 같은 테스트에 회수
