# 리뷰어 A 보고서 (diff 진입 · 독립 채점)

## 리뷰어 입력

받은 것:
- `git diff master...HEAD` — 1차 세션은 worktree `agent-a2e51eb3cc4bc2c68`·HEAD `baf9677`, 재개 세션은 worktree
  `agent-a42b738997e027af8`·HEAD `a3435e3bbe0aff83b0df722fa327171731e2f495`(`git merge --ff-only`는 「이미 업데이트 상태」,
  `git rev-parse HEAD` 일치 확인). `baf9677..a3435e3`은 PROGRESS·리뷰 초안 회수 커밋뿐이라 코드 대상은 같다.
- `backend/src/main/resources/openapi.yml`
- backend-quality 스킬 본문 `/home/loop-claude/.claude/skills/backend-quality/SKILL.md` (재개 세션에서 전문 열람)
- 불변 조건 3·4·9·16 + 씨앗 절차(불변 조건 14, S1~S6 정의) 원문 `/tmp/review-inputs/A-invariants.md`
- 레포 `CLAUDE.md` · `.claude/GOTCHAS.md` (ⓓ 정정 대상으로만)

읽지 않은 것: `PLAN.md`, `docs/superpowers/`.
`PROGRESS.md`는 작성자 자기 서술이라 주장 대조용으로만 읽었다. **공개**: 재개 세션에서 ⓒ 원장 확인을 위해 읽은 구간에
리뷰어 B의 미검증 가설 목록(B1~B12)이 들어 있었다. 아래 A5는 B5와 겹치는데, A5는 실서버 탐침으로 직접 실측한 것만 적었다.

## 씨앗 표

| S<n> | 심은 것 | 붉어진 테스트(실패 메시지 요지) | 복원 후 초록 확인 |
| --- | --- | --- | --- |
| S1 | `TaskClaimer` 클레임 CTE의 `for update skip locked` → `for update` | `ClaimContentionTest.claimSkipsRowsLockedByAnotherWorker` FAILED — `java.util.concurrent.TimeoutException`(남의 잠금에 막혀 클레임이 돌아오지 못한다) | `git checkout HEAD -- TaskClaimer.kt` 후 같은 지목 실행 `BUILD SUCCESSFUL` exit=0 |
| S2 | `PlaywrightRecipeApplier.applyOn`의 `verify(page, target, recipe)` 호출 줄을 주석으로 대체 | `ApplyVerificationTest.mismatchAfterSaveFailsTheTask` FAILED — `ApplyVerificationTest.kt:22` `expected: <FAILED> but was: <SUCCEEDED>`(콘솔이 cooling_sec를 +1로 저장했는데 잡이 성공) | `git show HEAD:… > PlaywrightRecipeApplier.kt` 후 같은 지목 `BUILD SUCCESSFUL` exit=0 |
| S3 | `AuditFilter.doFilterInternal`의 `writer.write(...)` 호출 블록 삭제 | `AuditInvariantTest` **5건 전부** FAILED — `쓰기 오퍼레이션 전부에 OK 감사 행이 있다 ==> expected: <[POST /api/auth/login … POST /api/jobs/{id}/retry]> but was: <[]>` 외 4건(로그인 실패 감사 `expected:<1> but was:<0>`, append-only 앵커 `append-only를 어기는 문장이 성공했다`) | `git checkout HEAD -- Audit.kt` 후 `AuditInvariantTest` 전체 `BUILD SUCCESSFUL` exit=0 |
| S4 | `LeaseHeartbeat.tick` 첫 줄에 `if (true) return true`(갱신 UPDATE 미실행, 반환은 true) | `LeaseTest.heartbeatKeepsLeaseFromBeingReclaimed` FAILED — `LeaseTest.kt:67` `actual value is not null ==> expected: <null> but was: <ClaimedTask(id=1, jobId=1, equipmentId=1, attempt=2)>`(갱신 안 된 리스가 t0+200에 B에게 회수됨) | `git show HEAD:… > LeaseHeartbeat.kt` 후 같은 지목 `BUILD SUCCESSFUL` exit=0 |
| S5 | `ClaimLoop`의 `Semaphore(props.browserConcurrency)` → `Semaphore(props.claimParallelism)`(상한 2 → 풀 크기 6) | `BrowserConcurrencyE2ETest.peakConcurrentSessionsEqualsLimit` FAILED — `BrowserConcurrencyE2ETest.kt:39` `워커가 연 동시 세션의 최댓값 ==> expected: <2> but was: <6>` | `git show HEAD:… > ClaimLoop.kt` 후 같은 지목 `BUILD SUCCESSFUL` exit=0 |
| S6 | `Policy.check`의 `Action.RECIPE_APPROVE_RETIRE -> if (ownerId != null && ownerId == actor.id) forbidden("SELF_APPROVAL_FORBIDDEN")` 한 줄 삭제 | `AuthorizationMatrixTest.approverCannotApproveOwnRecipe` FAILED — `expected: <403> but was: <200>` | `git checkout HEAD -- Policy.kt` 후 같은 지목 실행 `BUILD SUCCESSFUL` exit=0 |

씨앗 6종 전부 붉음 → 복원 → 초록. 각 복원 뒤 `git status --porcelain` 추적 변경은 이 보고서 1건뿐이었다.

### 씨앗 실행 주석

- S3에서 `AuditInvariantTest.auditLogHasNoUpdateOrDeletePath`까지 붉어진 것은 설계된 연쇄다 — 감사 행이 하나도 안 생기면
  `maxAuditId()`가 0이 되고 존재하지 않는 id에 대한 UPDATE는 트리거를 못 깨우므로 `assertViolation`이 `fail()`로 떨어진다.
  **fail-closed 방향**이라 결함은 아니다.
- S4의 반환값을 `true`로 둔 것은 의도다 — `assertTrue(heartbeat.tick(...))`를 통과시킨 채 **갱신 자체**가 사라졌을 때 잡히는지
  보려는 것이고, 뒤의 `assertNull(claimer.claim("B"))`가 잡았다.
- **씨앗은 잡지만 스위트가 못 보는 결함이 있다**: 감사 필터 S3(기록 삭제)는 죽지만, 같은 필터가 **입력에 따라** 기록을 못
  남기는 경로(A1)는 HEAD의 스위트 전체가 초록인 채로 살아 있다. 씨앗 목록이 「코드 한 줄 삭제」만 보기 때문이다.

## 검증 명령 실효

기준선: HEAD 그대로 `scripts/smoke.sh` exit 0(11 s), `scripts/test.sh --tests …` exit 0. 포트 18080은 매 실행 전 `/usr/sbin/ss -tln`으로
비어 있음을 확인했다. 모든 변형은 `git show HEAD:<파일> > <파일>`로 복원했고 복원 뒤 같은 지목이 초록이었다.

### 낱말 삭제 씨앗

스크립트의 자기점검 줄·조건을 지웠을 때 그 결함을 놓치는가.

| # | 지운 것 | 함께 만든 실제 상황 | exit | 판정 |
| --- | --- | --- | --- | --- |
| W1 | test.sh `if [ "$ran" -eq 0 ]` → `if false` | `HealthApiTest`에 `@Disabled`(전부 skip, 결과 XML은 새로 쓰임) | **0** | 놓침 — 전부 skip을 막는 것은 이 줄 하나뿐이다(대체 방어 없음) |
| W2 | test.sh `--rerun` 삭제 | 같은 지목 2회 연속(2회차 `:backend:test UP-TO-DATE`) | 1회차 0 · **2회차 1** | 잡힘 — `new_xml` 점검 `이번 실행에서 테스트 결과가 하나도 쓰이지 않았다` |
| W3 | W2 + `if [ -z "$new_xml" ]` → `if false` | UP-TO-DATE | **1** | 잡힘 — `ran` 점검(`-newer` XML 0개 → 합 0). 메시지는 `전부 skip`이라 원인과 다르다(무해) |
| W4 | W3 + `ran` 조건도 `if false` | UP-TO-DATE | **0** | 놓침(기대대로) — 세 겹이 전부 사라져야 뚫린다 |
| WS1 | smoke.sh `! grep -q 'generated security password'` 줄 → `true` | TS4 결함(빈 `UserDetailsService` 빈 제거) 유지 | **0** | 놓침 — 생성 비밀번호 부재를 지키는 것은 **스모크의 이 한 줄뿐**이다(테스트 스위트에 대응 단언 없음 — `grep -rn "generated security" backend/src/test` 0건) |
| WS2a | smoke.sh `[ "$code" = 200 ] \|\| fail` → `true` | TS2 결함(헬스 매핑 경로 변경 → 404) | **1** | 잡힘 — 앞선 기동 대기 `curl -sf`가 404로 먼저 실패 |
| WS2b | WS2a + 기동 대기 `curl -sf` → `curl -s` | TS2 결함 | **1** | 잡힘 — `jq .status == ok` 점검(`헬스 본문: {"code":"NOT_FOUND",…}`) |
| WS3 | smoke.sh 시작 전 포트 점검 `if curl …; then` → `if false` | `python3 -m http.server 18080` 선점 | **1** | 잡힘 — 기동 대기가 선점 서버의 404로 즉시 실패. 선점자가 **옛 스모크 서버**여도 새 시드 비밀번호로 로그인이 401이라 뒤에서 잡힌다(코드 읽기) |

### 진위 씨앗

스크립트가 지키려는 실제 상황을 만들었을 때 exit≠0 하는가(스크립트는 무변경).

| # | 만든 상황 | 방법 | exit | 걸린 곳 · 소요 |
| --- | --- | --- | --- | --- |
| T1 | 테스트 0건 실행(지목 불일치) | `scripts/test.sh --tests 'com.rollout.NoSuchTest'` | 1 | gradle `No tests found for given includes` · 7 s |
| T2 | 결과 XML 부재(테스트 태스크 미실행) | `scripts/test.sh -x :backend:test` → gradle `BUILD SUCCESSFUL` | 1 | 스크립트 자기점검 `테스트 결과가 하나도 쓰이지 않았다` · 9 s |
| T3 | 전부 skip | `HealthApiTest`에 `@Disabled` 후 지목 → XML `tests="1" skipped="1"`, gradle `BUILD SUCCESSFUL` | 1 | 스크립트 자기점검 `실제로 돈 테스트가 0건이다(전부 skip)` · 6 s |
| T4 | 테스트 실패 | 씨앗 S2·S4·S5(위 표) | 1 | gradle `BUILD FAILED` |
| TS1 | 포트 선점 | `python3 -m http.server 18080 --bind 127.0.0.1` | 1 | 시작 전 점검 `18080가 이미 응답한다` · 0 s |
| TS2 | 헬스 비200 | `HealthController`의 `@GetMapping("/api/health")` → `/api/health-seed`(실제 응답 404) | 1 | `기동 대기 실패` · 20 s |
| TS3 | 기동 실패 | `V1__schema.sql` 끝에 `select seed_boot_failure from nowhere;`(Flyway 실패 → gradle `BUILD FAILED`) | 1 | `기동 대기 실패` · **181 s**(A7) |
| TS4 | 부팅 로그에 생성 비밀번호 | `ApiConfiguration`의 `@Bean fun userDetailsService()` 두 줄 제거 → 로그에 `generated security password` 1줄 | 1 | `부팅 로그에 생성 비밀번호가 찍힌다` · 18 s |

부수 관찰: T1 **첫 시도**는 코드와 무관한 gradle 기동 오류(`Cannot create service of type BuildSessionActionExecutor … BuildLifecycleAwareVirtualFileSystem`)로
exit 1이었고 재실행은 정상이었다(A12). 실패 방향이지만 러너에서는 한 번의 이런 실패가 런 ABORT다.

## 발견

| # | 심각도 | 위치(file:line) | 내용 | 근거(실행/코드) |
| --- | --- | --- | --- | --- |
| A1 | **높음** | `backend/src/main/kotlin/com/rollout/api/Audit.kt:54-60` · `V1__schema.sql`(`action varchar(50)`) | **감사 우회: 퍼센트 인코딩한 id로 승인하면 상태는 바뀌고 감사 행은 없고 클라이언트는 401을 받는다.** 필터가 **디코딩 안 된** `requestURI`로 정규화해 `%30%30…%32`가 `{id}`로 접히지 않고, `action`이 50자를 넘어 감사 insert가 `22001`로 죽는다. 업무 트랜잭션은 이미 커밋됐고, 필터 예외가 ERROR 디스패치로 넘어가 시큐리티가 401을 쓴다. 불변 조건 9①(성공한 쓰기는 OK 행) 위반이고 4-eyes 승인의 흔적이 사라진다. 계약 기반 전수 테스트는 정상 경로 문자열만 밟으므로 HEAD 스위트는 초록이다. | 실서버 탐침 P6: APPROVER `POST /api/recipes/%30%30%30%30%30%30%30%30%30%30%30%30%32/approve` → **401** `UNAUTHENTICATED`, 감사 행 수 10→10, 레시피 2 `status=ACTIVE, approvedBy=3`. **자기 권고 실측**: `val segments = UriUtils.decode(request.requestURI, UTF_8)…` + `action`·`targetType`에 `.take(50)` 을 심고 재탐침 → P6 **200**, 감사 11→12 행 `POST /api/recipes/{id}/approve targetId=2`, 스모크 exit 0, 부팅 로그 ERROR 0줄, `AuditInvariantTest` 5건 초록. (첫 시도 `request.servletPath`는 실서버에선 됐지만 MockMvc의 servletPath가 빈 문자열이라 `AuditInvariantTest` 3건이 붉어져 버렸다 — 권고는 `UriUtils.decode` 형태.) 회귀 테스트는 **인코딩 경로를 테스트 안에서 만들어** OK 행·targetId를 단언해야 한다(현재 모양의 우연에 기대지 않게). 천장: 감사가 여전히 커밋 뒤 별도 insert라 DB 장애 시 누락은 남는다(PROGRESS가 이미 적은 천장) — 다만 이 발견은 그 천장이 **클라이언트 입력으로 도달 가능**했다는 뜻이다. |
| A2 | 중 | `Audit.kt:54-62` | A1과 같은 원인의 짧은 변형: `POST /api/recipes/%33/approve`는 감사 행이 남지만 `action="POST /api/recipes/%33/approve"`, **`targetId=null`** — 어느 레시피를 승인했는지 감사가 말하지 못하고, 행위별 집계(`action` 등호)에서도 빠진다. | 탐침 P7: 200, 감사 행 `{"action":"POST /api/recipes/%33/approve","targetId":null,"result":"OK"}`. A1 권고 적용 후 `action="POST /api/recipes/{id}/approve", targetId=3`(실측). |
| A3 | 중 | `Audit.kt:52-60` · `ApiConfiguration.kt`(엔트리 포인트) | **무인증 로그 증폭 + 감사 누락**: 무인증 `POST /api/<60자>` 한 번에 응답은 정상 401이지만 감사 insert가 `22001`로 죽어 행이 안 남고, Tomcat이 스택트레이스 ERROR 여러 줄(DataIntegrityViolation + 에러 디스패치의 AuthorizationDenied)을 찍어 **요청 1건당 로그 5,105 바이트**가 는다. 인증된 긴 경로 404도 행이 안 남는다(탐침 P4). 스킬의 「무인증 도달 로깅 지점의 길이 상한」 규칙 위반. | 탐침 P3·P4: 감사 10→11(P3·P4·P4b 세 요청 중 P4b 짧은 경로만 기록), 부팅 로그 `"level":"ERROR"` 4줄, P3 한 건 로그 증가 5105 B. A1 권고 적용 후 같은 탐침 로그 증가 **0 B**, ERROR 0줄(실측). |
| A4 | 중 | `AdminControllers.kt:180-189`(`equipmentRow`) · `openapi.yml:512` | 불변 조건 16은 콘솔 자격증명을 **`console_user`·`console_password`** 로 적고 「응답 DTO·로그·감사에 절대 없음」인데, `GET /api/equipment`가 **모든 역할(OPERATOR 포함)** 에 `consoleUser`를 돌려준다. 계약의 Equipment 스키마도 이 필드를 선언한다 — 계약이 불변 조건과 어긋난 채 적혔다. 스모크는 `consolePassword` 부재만 본다. 결정 필요: DTO·계약에서 빼거나, 불변 조건의 「자격증명」 정의를 비밀번호로 좁혀 명시. | 탐침 P5: OPERATOR `GET /api/equipment` → `[{"…","consoleUser":"cu",…}]` 200. |
| A5 | 중 | `AdminControllers.kt:28-60,99-150` · `ApiErrors.kt:65` | 계약의 `minLength`/`maxLength`가 서버에서 강제되지 않는다(불변 조건 4: 검증 실패는 400 `INVALID_REQUEST`). 빈 `username`·`displayName`·`password`로 사용자 생성 → **201**. `consoleUser` 101자 → **409 `CONSTRAINT_VIOLATION`**(DB `varchar(100)`이 대신 막음). 계약 적합성 테스트는 한 방향이라 못 본다. (B5와 겹침 — 여기서는 실측분만.) | 탐침 P1: `{"id":4,"username":"","displayName":"","role":"OPERATOR","enabled":true} 201`. P2: `{"code":"CONSTRAINT_VIOLATION",…} 409`. 빈 비밀번호 계정 로그인은 401(P8) — 쓸 수 없는 계정이 생길 뿐 인증 우회는 아니다(원인은 미확인). |
| A6 | 낮음 | `AdminControllers.kt:141-150`(`coalesce(:password, console_password)`) · `ClaimLoop.kt:178-190`(`load`) | ADMIN은 콘솔 비밀번호를 **볼 수 없게** 설계됐지만 `consolePassword` 없이 `consoleUrl`만 바꿀 수 있고, 워커는 매 시도마다 DB의 URL을 읽어 저장된 자격증명을 그 호스트 로그인 폼에 넣는다 → 응답 은닉을 URL 재지정으로 우회. `consoleUrl`은 형식 검증도 없다(P2에서 `"x"` 수용). 권고: URL이 바뀌면 `consolePassword`를 필수로(또는 비움). | 코드 읽기(실행 안 함 — 잡이 실제로 돌아야 해 E2E가 필요). |
| A7 | 낮음 | `scripts/smoke.sh:53-54` | 기동이 실패해도(gradle 이미 종료) `curl --retry 90 --retry-delay 2`가 끝까지 기다려 **181 s** 뒤에야 실패한다. 실패 방향이라 헛통과는 아니지만 러너 시간을 태운다. | TS3 실측 181 s. **자기 권고 실측**: 대기를 `for 1..180 { curl -sf && break; kill -0 $gradle_pid \|\| fail; sleep 1 }` + 마지막 `curl -sf` 로 바꿔 → 기준선 exit 0(21 s), TS3 exit 1 **5 s** `기동 실패(gradle 종료)`. |
| A8 | 낮음 | `scripts/smoke.sh:23-32` | `app_pids`가 머신 전체의 `TestRolloutApplicationKt` 프로세스를 찾아 죽인다 — 다른 worktree·다른 포트(`SMOKE_PORT=18181`)의 스모크가 동시에 돌면 서로의 앱을 죽여 불규칙 실패한다. 권고: 포트 인자(`--server.port=$PORT`)까지 grep 조건에 넣는다. | 코드 읽기(다른 리뷰어 세션을 방해할 수 있어 실행하지 않음). |
| A9 | 낮음 | `PROGRESS.md`(원장 부재) | 불변 조건 16의 코드 밖 항목이 레저에 없다: 콘솔 비밀번호 평문 저장의 「운영 전 재검토」 리스크 원장 행, 비밀값 폐기 절차(`POSTGRES_PASSWORD`·`SEED_PASSWORD` 교체, 콘솔 자격증명 교체 = ADMIN 설비 수정, 유출 계정의 세션 강제 폐기 = `enabled=false`). `grep -n "재검토\|SEED_PASSWORD\|POSTGRES_PASSWORD" PROGRESS.md` 0건. 「작업 9 원장 후보」로 미룬 항목들과 함께 작업 9에서 원장을 만들 때 반드시 들어가야 한다. | grep 실측. 코드 쪽 폐기 경로는 실재함을 확인: 로그아웃 `invalidate`(`AuthController.kt:50-54`), `enabled=false` 다음 요청 401(`CurrentUserFilter` + `AuthApiTest.kt:164` 부근), 세션은 서버 메모리. |
| A10 | 정보 | `application.properties:4` · `ConfigPinTest.kt:26` | `server.address=127.0.0.1`이 **모든 실행**의 기본값으로 박혀 있고 핀 테스트가 그 값을 지킨다. 불변 조건 16은 컨테이너 안 바인드가 `0.0.0.0`이므로 작업 9의 compose가 `SERVER_ADDRESS=0.0.0.0`을 넘기지 않으면 컨테이너가 도달 불가가 된다(fail-closed). compose 게이트에 그 env의 양의 앵커를 둘 것. 스모크 실행 시 실제 바인드는 `http-nio-127.0.0.1-18080`(로그 스레드명)으로 루프백 확인. | 부팅 로그 스레드명 실측 · compose는 아직 없음. |
| A11 | 정보 | `scripts/gradlew-sbx.sh:12-13` | 샌드박스 프록시 비밀번호가 `GRADLE_OPTS`의 `-Dhttp.proxyPassword=`로 gradle JVM 명령줄에 실려 `ps`에 보인다. 샌드박스 수명의 임시 값이라 영향은 작다. | 코드 읽기. |
| A12 | 정보 | 검증 환경 | T1 첫 실행이 코드와 무관한 gradle 기동 오류로 exit 1(재실행 정상). 여러 worktree가 같은 `~/.gradle`을 동시에 쓰는 것이 원인 후보(미확인). | 실측 1회. |

B가 가설로 적은 다른 항목(B1 PUT으로 남의 초안 덮어쓰기, B2 취소 비점착, B3 워커 대행 감사 부재)은 이 리뷰에서 실측하지 않았다.
단 A1 권고는 B3을 고치지 않는다 — 워커 대행 감사(`actor_name='system'`)는 `AuditWriter`가 api 모드 전용이라 여전히 경로가 없다(코드 읽기).

### 보안 잔여분(ⓒ) 대조 요약 — 불변 조건 16

| 값 | 구현 | 판정 |
| --- | --- | --- |
| 로컬 바인드 127.0.0.1 | `application.properties` + 핀 테스트 + 스모크 실측 루프백 | 일치(컨테이너 0.0.0.0은 A10) |
| CORS 없음 | CORS 설정 코드 없음(diff 전수 grep `cors` 0건 — 확인은 `ApiConfiguration`에 `.cors` 없음) | 일치 |
| WS 허용 Origin 하나 | `Realtime.kt:166` `setAllowedOrigins(webOrigin)`, 기본 `http://localhost:3000` | 일치 |
| 무인증 예외 정확히 넷 | `ApiConfiguration.kt` permitAll 4경로(POST login · GET 3) | 일치 |
| 개인정보 username·display_name만 | `V1__schema.sql` `app_user` 열 | 일치 |
| 콘솔 자격증명 응답·로그·감사 부재 | 비밀번호는 부재, **`consoleUser`는 응답에 있음** | **불일치(A4)**, 우회 경로 A6 |
| 비밀값 수명·폐기 | 코드 경로는 있음, 레저 없음 | **레저 부재(A9)** |
| 저장·로깅이 과한가 | 감사 `detail`은 `{status, path}`만(본문·질의 미기록 — 과하지 않음). 워커 실패 로그·`last_error`는 콘솔의 `.error` 텍스트를 그대로 싣는다(콘솔이 제어하는 문자열, 길이 상한 없음 — 낮음, 표에 별도 행 없음). 과다 로깅은 A3 | 부분 |

## 문서 정정안(ⓓ) — 파일은 고치지 않았다

1. `CLAUDE.md` 「구조·컨벤션」 감사 항목 — 현재: 「감사는 `AuditFilter` 한 자리가 쓰기 오퍼레이션 전부를 덮는다」.
   코드가 무효화한 부분: 입력(인코딩 경로·긴 경로)에 따라 행이 안 남는다(A1·A3), 워커 대행 감사는 이 필터 밖이다(`AuditWriter`는 `mode=api` 전용).
   정정안: 「감사는 api 쓰기 요청을 `AuditFilter` 한 자리에서 기록한다(컨트롤러에서 부르지 않는다). 정규화는 디코딩한 경로 기준이어야 하고
   `action` 50자 열에 맞춰 자른다 — 어기면 기록이 조용히 빠진다(리뷰 A1). 워커 대행 기록은 이 필터 밖이다.」
2. `CLAUDE.md` 통합 테스트 컨벤션 — 현재: 「통합 테스트는 … `@SpringBootTest(properties = ["mode=api"])`로 모드를 준다」.
   코드와 다름: `E2ESupport`·`WorkerHealthTest`·`BrowserConcurrencyE2ETest`는 `mode=worker`, 하위 클래스의 `@SpringBootTest`가 상위를 **대체**한다
   (`BrowserConcurrencyE2ETest` 주석이 이미 실측으로 적음), `RealtimeTest`는 `RANDOM_PORT`.
   정정안: 「통합 테스트는 `EmbeddedPostgresConfiguration`을 `@Import`하고 밟는 역할대로 `mode=api|worker`를 준다. 하위 클래스의
   `@SpringBootTest` properties는 상위를 대체하므로 부모 값도 다시 적는다.」
3. `CLAUDE.md` 「명령」 스모크 항목 — 현재 서술(헬스 200·부팅 로그 감사·종료 확인)이 실제보다 좁다. 정정안에 추가:
   「로그인 3역할 → 설비 등록 → 다른 사람 승인 → 잡 생성·같은 키 멱등 → WS 101/401 → 무인증 401·권한 403 → 로그 비밀번호 부재」,
   그리고 「머신 전체의 `TestRolloutApplicationKt`를 죽인다 — 동시에 두 개 돌리지 말 것」(A8, 고치기 전까지).
4. `CLAUDE.md` 「명령」 test.sh 항목 — 서술은 실측과 일치(T2·T3). 무효화 없음.
5. `.claude/GOTCHAS.md` 유일 항목(test.sh 전체 모드 침묵 exit 1) — 수정이 `scripts/test.sh:25-35`에 들어가 있고 서술은 이력으로 유효. 무효화 없음.
6. `CLAUDE.md` 「함정」 3항목 — Kotlin 데몬 `읽기전용 파일 시스템` 줄(이번 실행 로그에 실재, 빌드 통과) · `Started RolloutApplicationKt` 앵커(스모크 통과) ·
   `--args` 전달(스모크 50행) 모두 이번 실행과 일치. 무효화 없음.

## 뮤테이션 총수

**21** (+ 무변경 진위 실행 3 · 탐침 스모크 2)

- 씨앗 6: S1~S6 (1차 세션 S1·S3·S6, 재개 세션 S2·S4·S5)
- test.sh 계열 5: T3(`@Disabled`) · W1 · W2 · W3 · W4
- smoke.sh 계열 7: TS2(헬스 경로) · TS3(V1 SQL) · TS4(빈 UserDetailsService 제거) · WS1 · WS2a · WS2b · WS3
- 자기 권고 실측 3: A1 권고 1차(`servletPath` — MockMvc에서 붉음, 폐기) · A1 권고 2차(`UriUtils.decode`+`take(50)` — 초록·탐침 해소) · A7 권고(gradle 생존 감시 — 181 s → 5 s)
- 무변경 실행(뮤테이션 아님): T1(없는 테스트 지목) · T2(`-x :backend:test`) · TS1(포트 선점)
- 탐침(스모크에 임시 `. /tmp/revA-probe.sh` 한 줄 삽입, 판정 대상 아님): P1~P5, P3·P6~P8

마지막 복원 뒤 `git status --porcelain` 추적 변경은 `docs/review/review-A.md` 1건이었다. 이후 보고서를 저장하는 사이(02:47)
이 worktree가 외부에서 제거됐고, 메인 트리에는 **다른 리뷰어 A 세션의 완료 보고서가 이미 커밋돼 있었다**(`547113c`·`8c95ac8`).
그래서 이 보고서는 그 파일을 덮지 않고 `docs/review/review-A-rerun.md`로 복사했다 — 어느 쪽을 정본으로 삼을지는 사람/상위 반복이 정한다.
