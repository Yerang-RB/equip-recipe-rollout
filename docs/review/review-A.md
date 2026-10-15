# 리뷰어 A 보고서 (완료)

## 리뷰어 입력

받은 것:
- `git merge --ff-only baf9677` — 「이미 업데이트 상태」, HEAD=`a3435e3`(baf9677 조상, 그 뒤 커밋은 코드 변경 없이 리뷰 초안·PROGRESS만 추가 — `git diff baf9677 a3435e3 --stat` 3파일 190줄 추가로 직접 확인)
- `backend/src/main/resources/openapi.yml`
- 불변 조건 3·4·9·16 + 씨앗 절차(불변 조건 14, S1~S6 정의) 원문 `/tmp/review-inputs/A-invariants.md`
- 레포 `CLAUDE.md` · `.claude/GOTCHAS.md` (ⓓ 정정 대상으로만)
- 이전 반복(사용량 상한으로 중단)이 남긴 초안 — S1·S3·S6은 그 반복이 이미 실행까지 마친 기록이라 재실행하지 않고 표만 통합했다.

읽지 않은 것: `PLAN.md` 전문, `docs/superpowers/`. `PROGRESS.md`는 작성자 자기 서술이라 **씨앗 표 대조용**으로만 읽었다(주장은 이번 반복이 직접 실행해 재확인했다).

## 씨앗 표 (S1~S6 전부, 뮤테이션 총수 6)

| S<n> | 심은 것 | 붉어진 테스트(실패 메시지 요지) | 복원 후 초록 확인 |
| --- | --- | --- | --- |
| S1 | `TaskClaimer` 클레임 CTE의 `for update skip locked` → `for update` | `ClaimContentionTest.claimSkipsRowsLockedByAnotherWorker` FAILED — `java.util.concurrent.TimeoutException`(남의 잠금에 막혀 클레임이 돌아오지 못한다) | `git checkout HEAD -- TaskClaimer.kt` 후 같은 지목 실행 `BUILD SUCCESSFUL` exit=0 |
| S2 | `PlaywrightRecipeApplier.applyOn`에서 `verify(page, target, recipe)` 호출 삭제(저장 후 재조회 대조 제거) | `ApplyVerificationTest.mismatchAfterSaveFailsTheTask` FAILED — `AssertionFailedError at ApplyVerificationTest.kt:22`(대조가 없으니 불일치를 잡는 실패가 안 남) | `git checkout HEAD -- PlaywrightRecipeApplier.kt` 후 같은 지목 실행 `BUILD SUCCESSFUL` exit=0 |
| S3 | `AuditFilter.doFilterInternal`의 `writer.write(...)` 호출 블록 삭제 | `AuditInvariantTest` **5건 전부** FAILED — `쓰기 오퍼레이션 전부에 OK 감사 행이 있다 ==> expected: <[POST /api/auth/login … POST /api/jobs/{id}/retry]> but was: <[]>` 외 4건(로그인 실패 감사 `expected:<1> but was:<0>`, append-only 앵커 `append-only를 어기는 문장이 성공했다`) | `git checkout HEAD -- Audit.kt` 후 `AuditInvariantTest` 전체 `BUILD SUCCESSFUL` exit=0 |
| S4 | `LeaseHeartbeat.tick`을 `if (true) return true`로 만든 no-op(리스 갱신 제거) | `LeaseTest.heartbeatKeepsLeaseFromBeingReclaimed` FAILED — `AssertionFailedError at LeaseTest.kt:67`(갱신이 안 되니 리스가 회수돼야 하는데 회수를 못 감지) | `git checkout HEAD -- LeaseHeartbeat.kt` 후 같은 지목 실행 `BUILD SUCCESSFUL` exit=0 |
| S5 | `ClaimLoop`의 `Semaphore(props.browserConcurrency)` → `Semaphore(props.claimParallelism)`(브라우저 상한을 클레임 병렬도 6으로 확대) | `BrowserConcurrencyE2ETest.peakConcurrentSessionsEqualsLimit` FAILED — `AssertionFailedError at BrowserConcurrencyE2ETest.kt:39`(동시 세션 최댓값이 설계 상한 3을 넘어 등호가 깨짐) | `git checkout HEAD -- ClaimLoop.kt` 후 같은 지목 실행 `BUILD SUCCESSFUL` exit=0 |
| S6 | `Policy.check`의 `Action.RECIPE_APPROVE_RETIRE -> if (ownerId != null && ownerId == actor.id) forbidden("SELF_APPROVAL_FORBIDDEN")` 한 줄 삭제 | `AuthorizationMatrixTest.approverCannotApproveOwnRecipe` FAILED — `expected: <403> but was: <200>` | `git checkout HEAD -- Policy.kt` 후 같은 지목 실행 `BUILD SUCCESSFUL` exit=0 |

전부 한 번에 하나씩 심고 포그라운드 지목 실행으로 실패를 직접 확인한 뒤 복원·재확인했다(`run_in_background` 미사용). 씨앗을 심은 시점과 복원한 시점 사이 외에는 `git status --porcelain`이 항상 0건이었다.

### 씨앗 실행 주석 (중간 관찰, 이전 반복 기록)

- S3에서 `AuditInvariantTest.auditLogHasNoUpdateOrDeletePath`까지 붉어진 것은 설계된 연쇄다 — 감사 행이 하나도 안 생기면
  `maxAuditId()`가 0이 되고 존재하지 않는 id에 대한 UPDATE는 트리거를 못 깨우므로 `assertViolation`이 `fail()`로 떨어진다.
  **fail-closed 방향**이라 결함은 아니다(빈 감사 로그로 트리거 테스트가 헛통과하지는 않는다).

## 검증 명령 실효

### 낱말 삭제 씨앗

`scripts/test.sh`의 자기점검 코드 자체를 지우는 뮤테이션은 **이번 반복(재개 반복, 리뷰어 A가 아니라 재위임을
조율한 세션)이 직접 시도했으나 환경 경합으로 미완**이다 — 정직하게 남긴다:

- 시도: 존재하는 결과 디렉터리만 남기는 필터 블록(25~35행, `existing=()` … `results=("${existing[@]}")`)을
  삭제해 2026-09-16에 이미 한 번 고쳤던 침묵 실패(`console-mock`에 결과 디렉터리가 없어 `find`가 exit 1 →
  `set -e`+`pipefail`이 메시지 없이 스크립트를 죽이는 버그)를 재현하려 했다.
- 실행 결과: `scripts/test.sh`(전체 모드)가 gradle 데몬 단계에서 `Timeout waiting to lock journal cache
  (~/.gradle/caches/journal-1)`로 **두 번 다 실패**(`Owner PID`가 매번 다른 프로세스 — "3 busy daemons"로
  찍힘, 이 머신의 `~/.gradle` 캐시를 다른 세션들과 공유하는 데서 오는 경합). 내 뮤테이션이 원인인 실패인지
  판별이 안 되는 잡음이라 **결론을 내릴 수 없어 즉시 원본으로 복원**(`cp` 백업본으로 diff 없음 확인).
- 대신 인용할 수 있는 기존 증거(같은 종류의 낱말 삭제 씨앗이 **이미 이 저장소 역사에서 한 번 실행됨**): 2026-09-16
  튜닝 반복이 정확히 이 자기점검 로직 부재로 인한 침묵 실패를 겪은 뒤 고쳤고(PROGRESS.md "`scripts/test.sh` 전체
  모드 침묵 실패 수정"), 고친 직후 가드 생존을 `scripts/test.sh --tests 'com.rollout.NoSuchTest.nope'` →
  exit 1(붉음 유지)로 확인한 기록이 있다(`.claude/GOTCHAS.md`). 이번 반복이 코드를 읽어 그 수정이 여전히
  트리에 남아 있음을 확인했다(25~49행 그대로).
- **미검증으로 명시**: 이번 리뷰 주기 동안 자기점검 로직을 실제로 지워서 실패를 재현하는 데는 성공하지 못했다.
  재현하려면 다른 세션들의 gradle 사용이 뜸한 시점에 재시도해야 한다.

### 진위 씨앗

(리뷰어 A가 이번 위임에서 실행 — 스크립트 코드는 그대로 두고, 스크립트 밖의 실제 조건을 조작)

- **지목 실행 실패 확인**: `scripts/test.sh --tests 'com.rollout.NoSuchTest.noSuchMethod'` → gradle이
  `No tests found for given includes: [com.rollout.NoSuchTest.noSuchMethod](--tests filter)`로 즉시 `BUILD FAILED`,
  파이프에 걸지 않고 직접 잰 실제 종료 코드 **1**(처음에 `| tail | echo $?`로 쟀다가 tail의 종료 코드를 잰 내 실수를
  발견해 파이프 없이 재측정했다 — 스크립트 결함 아님).
- **스모크 — 포트 점유 오탐 없음**: `/usr/sbin/ss -tln`으로 18080이 비어 있음을 먼저 확인한 뒤 `scripts/smoke.sh`
  전체를 실행 — 실서버 기동(`Started RolloutApplicationKt` 앵커) → `/api/health` 200 → 로그인 3명 → 설비 등록
  (콘솔 자격증명 응답에 없음 확인) → 레시피 초안·승인·잡 생성·멱등 재요청 → WS 101(쿠키 있음)/401(없음) →
  무인증 401 · OPERATOR의 감사 조회 403 → 정상 종료까지 **실제 종료 코드 0**으로 통과, 종료 후 포트 해제도
  `ss -tln`으로 재확인.
- **스모크 — 포트 선점 시 진짜로 막히는지**: 더미 HTTP 서버로 18080을 미리 점유시킨 뒤 `scripts/smoke.sh`를
  실행 — `스모크 실패: 18080가 이미 응답한다 — 남은 서버를 죽이고 다시 실행하라` 메시지와 함께 **실제 종료
  코드 1**로 정지함을 확인(더미 서버는 이후 정리). 헬스 비-200·기동 실패 시 위장 성공 가능성은 스크립트의
  `curl -sf --retry-connrefused` 대기 + `ANCHOR` 로그 grep 이중 확인 구조상 낮다(코드로 확인, 별도 인위적 기동
  실패 재현은 비용 대비 낮은 우선순위로 생략).
- 결론: 조사한 진위 씨앗 시나리오(존재하지 않는 테스트 지목·포트 선점)에서 `scripts/test.sh`·`scripts/smoke.sh`
  모두 조용히 성공으로 위장하지 않았다. 낱말 삭제 씨앗 쪽은 이번 주기에 환경 경합으로 미완이라 완전한 결론은
  아니다(위 소절 참고).

## ⓒ 불변 조건 3·4·9·16 대조 — 코드 직접 확인

- 불변 조건 3(무인증 허용 경로 정확히 4개, 4-eyes, ADMIN 승인·폐기 불가): `ApiConfiguration.securityFilterChain`
  (`backend/src/main/kotlin/com/rollout/api/ApiConfiguration.kt:104-106`)이 `POST /api/auth/login` ·
  `GET /api/health,/actuator/health,/actuator/prometheus` 넷만 permitAll, 나머지 `anyRequest().authenticated()`.
  `Policy.ALLOWED[RECIPE_APPROVE_RETIRE] = {APPROVER}`(ADMIN 제외, `Policy.kt:18`), 4-eyes는 `Policy.kt:33`.
  `AuthController.login`(`AuthController.kt:34-49`)이 더미 해시로 같은 인코더 cost factor 대조, 세션 재발급,
  bcrypt 72바이트 상한(`requirePasswordFits`)까지 원문과 일치. **일치.**
- 불변 조건 4(에러 형식 하나, 64 KiB 상한, 파싱 전 차단): `ApiErrors.kt`가 `{code,message}` 하나로 400/401/403/
  404/405/409/413/500 전부를 반환하고 로그는 예외 클래스명만(`internal()`). `BodyLimitFilter`
  (`BodyLimitFilter.kt:37-58`)가 `HIGHEST_PRECEDENCE`에서 입력 스트림을 감싸 64×1024바이트를 넘는 순간
  `IOException`을 던져 컨트롤러(파서) 진입 전에 끊는다. `application.properties`에 멀티파트·formcontent 필터
  비활성화, `fail-on-unknown-properties=true` 확인. **일치.**
- 불변 조건 9(감사 불변식) — **부분 위반, 확인됨**:
  - ①②③④의 API 경로(로그인 실패·403·append-only 트리거·`actor_name` 스냅샷)는 `AuditFilter`
    (`Audit.kt:49-73`)와 DB 트리거(`V1__schema.sql:88-96`)로 구현·테스트(`AuditInvariantTest`, S3로 재확인)돼
    있어 **일치**.
  - 그러나 **워커 대행 감사 행이 코드에 없다**: `AuditWriter`·`AuditFilter`는 전부
    `@ConditionalOnProperty(mode=api)`(`Audit.kt:22`)이고, `grep -rln "AuditWriter\|audit_log" backend/src/main`으로
    확인한 결과 `com.rollout.worker`·`com.rollout.job` 어디에도 `AuditWriter` 호출이나 `audit_log` 문장이 없다
    (일치하는 파일: `ReadControllers.kt`(조회), `V1__schema.sql`(스키마), `Audit.kt` 자신뿐). 스키마 주석
    (`V1__schema.sql:77`)에만 `actor_id=null, actor_name='system', detail.on_behalf_of=<요청자>` 의도가 적혀
    있고 실제 삽입 코드가 없다 — 워커가 레시피를 적용해도 `audit_log`에는 행이 **전혀 남지 않는다**(참고로
    `application_history.applied_by`=요청자 기록은 `TaskOutcomeService.kt:48`에 존재해 그쪽은 맞다). 이는
    PROGRESS.md의 리뷰어 B 중간 결과 "B3(중) 워커 대행 감사 행이 트리에 0건"과 같은 지점이며, 이번 반복이
    코드 읽기로 **독립 확인**했다 — B는 "미검증 가설"로 남겼지만 이 대조는 코드 사실이라 발견 표에 확정 항목으로
    올린다.
- 불변 조건 16(보안 값): `application.properties:4`가 `server.address=127.0.0.1`, `app.web-origin`으로 WS
  허용 Origin 하나(`Realtime.kt:166`), 세션 쿠키 `http-only=true`·`same-site=lax`(`application.properties:31-32`).
  `app_user` 스키마(`V1__schema.sql:4-11`)에 `username`·`display_name`뿐, 실명·연락처·이메일 컬럼 없음.
  `equipment.console_password`는 평문 컬럼(리스크 원장에 이미 기재된 결정)이고 스모크가 응답 DTO에 없음을
  실측 확인. compose 파일(`"127.0.0.1:<h>:<c>"` 포트 매핑)은 **레포에 아직 없음** — PLAN 원문이 그 항목을
  "작업 9"로 명시하므로 이번 작업(작업 6) 범위 밖이라 위반으로 세지 않았다. **나머지는 일치.**

### 추가 관찰 (ⓒ 확인 중 코드로 직접 발견, 참고용)

- `RecipeController.update`(`RecipeController.kt:64-74`, `PUT /api/recipes/{id}`)는 `Policy.check(actor,
  Action.RECIPE_DRAFT)`만 하고 `ownerId`를 넘기지 않는다 — DRAFT 권한이 있는 OPERATOR·APPROVER 누구나 **남의**
  초안 내용을 덮어쓸 수 있다(작성자 본인 여부를 안 본다). 이후 그 내용을 다른 APPROVER가 승인해도 4-eyes 검사는
  `created_by`(DB 컬럼, PUT으로 안 바뀜) 기준이라 통과한다. PROGRESS.md의 "B1(높음) 4-eyes 우회 가설"과 같은
  코드 지점이며, 이번 반복은 코드만 읽어 **소유자 검사 부재 자체는 확인**했지만 승인까지 이어지는 종단 시나리오는
  실행하지 않았다(리뷰어 B가 M 씨앗으로 실증하기로 역할이 나뉘어 있어 중복 실행하지 않음 — PROGRESS.md "다음
  반복이 할 일" 참고). 불변 조건 3 원문은 "4-eyes: `recipe.created_by == actor` 승인 → 403"만 명시해 PUT의
  소유자 검사까지 요구하는지는 문면상 불명확하므로 발견 표에는 넣되 심각도는 B의 실증 결과를 기다리는 것으로
  표기한다.

## 발견 목록

| # | 심각도 | 위치(file:line) | 내용 | 근거 |
| --- | --- | --- | --- | --- |
| F1 | 중 | `backend/src/main/kotlin/com/rollout/api/Audit.kt:22` (부재 지점), 관련: `job/`·`worker/` 전체 | 워커가 대행하는 레시피 적용 행위가 `audit_log`에 전혀 기록되지 않는다 — 불변 조건 9의 `actor_id=null, actor_name='system', detail.on_behalf_of` 축이 코드에 없다(스키마 주석에만 의도가 있음). | 코드 확인: `AuditWriter`/`AuditFilter`는 `mode=api` 전용 빈이고 `com.rollout.worker`·`com.rollout.job` 어디에도 `AuditWriter` 호출이 없음(grep). PROGRESS.md B3와 동일 지점, 이번 반복이 코드로 독립 확인해 확정. |
| F2 | 정보(참고, 실증 미완) | `backend/src/main/kotlin/com/rollout/api/RecipeController.kt:64-74` | `PUT /api/recipes/{id}`가 소유자(`created_by`) 검사 없이 DRAFT 권한만 확인해 남의 초안을 편집할 수 있다. 4-eyes는 `created_by` 컬럼 기준이라 편집자가 아닌 원 작성자만 걸러 실질적 우회 여지가 있다. | 코드 확인만(`Policy.check(actor, Action.RECIPE_DRAFT)`에 `ownerId` 인자 없음). PROGRESS.md B1과 같은 지점 — 종단 실증은 리뷰어 B 담당으로 역할 분리, 이번 반복은 중복 실행하지 않음. |

## ⓓ 문서 정정 제안

- `CLAUDE.md`·`.claude/GOTCHAS.md`를 코드와 대조한 결과 **사실 오류는 찾지 못했다**(스택·명령·구조·컨벤션·함정
  서술 전부 이번 반복이 실행·코드로 확인한 내용과 일치, `web/`을 "작업 8" 미착수로 적은 것도 실제로 `web/app/`에
  폰트 결정 커밋 하나만 있어 정확).
- 제안(오류 정정이 아니라 추가 제안): `CLAUDE.md` "구조·컨벤션"의 "감사는 `AuditFilter` 한 자리가 쓰기
  오퍼레이션 전부를 덮는다" 항목 옆에, F1에서 확인한 "단, API 경유가 아닌 워커 대행 행위는 아직 감사 대상 밖"이라는
  단서를 한 줄 덧붙이면 다음에 이 파일을 읽는 사람이 워커 감사 공백을 재발견하는 시간을 아낄 수 있다(직접 수정은
  하지 않음 — 재위임 반복이 반영 여부 판단).

## 뮤테이션 총수

**6** (S1~S6 전부 심고 붉음 확인 후 복원·초록 재확인 완료).
