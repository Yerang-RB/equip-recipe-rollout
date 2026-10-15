# 리뷰어 B 보고서 (HEAD baf9677 위 — 실증 완료)

이전 반복이 소스 트리를 읽고 세운 가설(B1~B12)을 이번 반복이 뮤테이션 심기·테스트 실행으로 실증/기각했다.
새 회귀 테스트(M5·M6)는 이 worktree에 그대로 남겨 뒀다 — 실제 결함의 증거다.

## 리뷰어 입력

받은 것(이전 반복 + 이번 재위임 누적):
- 소스 트리 `backend/`·`console-mock/`·`scripts/` (diff 없이 처음부터 읽음, `git diff`는 보지 않음)
- 계약 `backend/src/main/resources/openapi.yml`
- backend-quality 스킬 본문
- 보안 값(불변 조건 16 원문) `/tmp/review-inputs/B-security.md`
- 이전 반복(사용량 상한으로 중단)이 코드 읽기만으로 세운 가설 B1~B12 — 이번 반복은 이걸 그대로 받아 **뮤테이션
  씨앗으로 실증/기각**하는 것이 임무였다(코드 재해석이 아니라 실행 검증).
- 리뷰어 A의 발견 F1(워커 대행 감사 행 부재, 코드로 확정) — B3로 그대로 인용.

읽지 않은 것: `git diff`·`git log -p`, `PLAN.md` 전문, `PROGRESS.md`(작성자 자기 서술이라 가설 출처로만 참고),
`docs/`, `CLAUDE.md`의 계획 서사(빌드 명령만 확인).

## 씨앗 표

| 씨앗 | 심은 것 | 확인한 테스트 | 결과 | 근거 |
|------|---------|----------------|------|------|
| M5 | (신규 테스트) APPROVER B가 OPERATOR A의 초안을 `PUT`으로 덮어쓴 뒤 스스로 승인 | `AuthorizationMatrixTest.approverOverwritesOthersDraftThenApprovesOwnContent` | **실증됨** | `PUT` 200, 뒤이은 `approve` 200(BUILD SUCCESSFUL) — B1 확정 |
| M6 | (신규 테스트) `cancel(jobId)` 뒤 `CLAIMED` task를 재시도 가능 실패로 끝내고 재클레임 확인 | `TaskOutcomeTest.claimedTaskOfCancelledJobIsReclaimableAfterRetryableFailure` | **실증됨** | `fail` 뒤 상태 PENDING, `claimer.claim("B")`가 같은 task를 집음(BUILD SUCCESSFUL) — B2 확정 |
| M2 | `TaskClaimer.kt:33` `lease_expires_at < :now` → `<= :now` | `com.rollout.LeaseTest` | **생존** | BUILD SUCCESSFUL(실패 0) — `LeaseTest`는 121초를 밀어 등호 지점을 밟지 않는다(경계 커버리지 공백) |
| M3 | `LeaseHeartbeat.kt:24` `and claimed_by = :worker` 삭제 | `com.rollout.LeaseTest` | **생존** | BUILD SUCCESSFUL(실패 0) — 남의 리스를 되살리는 경로를 어느 테스트도 밟지 않는다 |
| M4 | `TaskOutcomeService.kt` 4곳(`succeed`·`fail`·`ownedTask`)의 `claimed_by = :worker` 전부 삭제 | `com.rollout.TaskOutcomeTest`·`com.rollout.RealtimeTest` | **생존** | 두 클래스 모두 BUILD SUCCESSFUL(실패 0) — 워커 소유권 강제를 지키는 테스트가 없다(B6의 방어가 테스트로 안 지켜진다) |

각 씨앗은 지목 실행(`scripts/test.sh --tests '...'`)으로 실행 로그를 직접 확인한 뒤 `git checkout --`으로 복원했다.
턴 종료 시 `git status --porcelain`에는 M5·M6의 새 테스트 파일 변경만 남아 있다(운영 코드는 전부 원복 확인).

뮤테이션 총수: **5**(M2~M6 각 1회. M1은 이전 반복 계획에만 있던 대조군으로, attempt 상한은 이미
`TaskOutcomeTest.retryableFailureSchedulesBackoffAndThirdFailureIsFinal`이 지키고 있어 재확인할 신규성이 없다고
판단해 생략했다).

## 발견 목록

### 실증됨

| ID | 심각도 | 위치 | 내용 |
|----|--------|------|------|
| **B1** | 높음 | `backend/src/main/kotlin/com/rollout/api/RecipeController.kt:63-74`(`update`) · `api/Policy.kt:33` | **4-eyes 우회(수정 후 승인)**. `PUT /api/recipes/{id}`는 `RECIPE_DRAFT` 권한(OPERATOR·APPROVER)만 보고 `created_by`를 건드리지 않은 채 `part_no`·`version`·`params`를 통째로 덮어쓴다. APPROVER B가 OPERATOR A의 초안을 자기 내용으로 갈아엎어도 `created_by`는 여전히 A라서, `approve`의 4-eyes 검사(`ownerId == actor.id`, `Policy.kt:33`)는 원작성자 A만 걸러내고 실제로 내용을 쓴 B는 통과시킨다. **M5로 실행 실증**(PUT 200 → approve 200). |
| **B2** | 높음 | `job/JobService.kt:64-75`(`cancel`) · `job/TaskOutcomeService.kt:68-112`(`fail`) · `job/TaskClaimer.kt:25-62`(`claim`) | **취소가 끈적이지 않는다**. `cancel`은 `status='PENDING'`인 task만 `CANCELLED`로 바꾸고, 잡 행 자체에는 취소 표시를 남기지 않는다. 취소 시점에 `CLAIMED`였던 task는 그대로 남고, 그 task가 재시도 가능 실패로 끝나면(`fail`) `PENDING`으로 되돌아간다. `claim`은 잡 상태를 전혀 보지 않는 순수 task-레벨 쿼리라, 취소된 잡의 레시피가 취소 이후에도 다시 집혀 설비에 적용될 수 있다. **M6로 실행 실증**(취소 후 fail → PENDING → 재클레임 성공). |

### 코드로 확정(리뷰어 A의 F1 인용)

| ID | 심각도 | 위치 | 내용 |
|----|--------|------|------|
| **B3** | 중 | `db/migration/V1__schema.sql:77` · `api/Audit.kt:22` | 워커 대행 감사 행 부재 — 리뷰어 A가 F1로 이미 코드 확인했다(그대로 인용). `AuditWriter`는 `mode=api` 조건부 빈이라 워커가 설비에 실제로 값을 쓰는 행위는 `audit_log`에 한 줄도 남기지 않는다(`application_history`만). |

### 씨앗으로 지지된 코드 읽기 지적 (생존 = 방어 자체가 아니라 테스트 커버리지 공백)

| ID | 심각도 | 위치 | 내용 |
|----|--------|------|------|
| **M2 관련** | 정보 | `job/TaskClaimer.kt:33` | 리스 만료 경계(`<` vs `<=`)의 등호 지점을 지키는 테스트가 없다. 기능 결함은 아니다(`<`가 맞는 방향 — 만료 "직후"만 회수해야 하므로) — 순수 커버리지 공백. |
| **B6 / M3·M4 관련** | 중 → **재평가: 높음에 가깝다** | `job/LeaseHeartbeat.kt:18-32` · `job/TaskOutcomeService.kt:27-122`(특히 `ownedTask`) · `worker/ClaimLoop.kt:61,74-77` | **같은 워커 id의 자기 리스 회수로 이중 실행 가능**. `LeaseHeartbeat.tick`·`TaskOutcomeService.succeed`/`fail` 모두 `claimed_by = :worker`(워커 id 문자열)로만 소유를 판정한다. 그런데 `ClaimLoop`는 **프로세스당 workerId 하나**(`"worker-${UUID...}"`, `ClaimLoop.kt:61`)를 만들고, `props.claimParallelism`(기본 6, `application.properties:23`) 개의 스레드가 **그 하나의 workerId를 공유**하며 동시에 `claim`/`succeed`/`fail`을 호출한다(`ClaimLoop.kt:74-77`). 따라서 스레드 A가 쥔 task의 리스가 만료돼 같은 프로세스의 스레드 B가 **같은 workerId로** 재클레임하면, 두 스레드 모두 `claimed_by = 'worker-XXXX'` 조건을 통과한다 — 소유권 검사가 두 시도를 구분하지 못한다. 스레드 A가 스레드 B보다 늦게(스레드 B가 아직 `CLAIMED` 상태인 동안) `succeed`/`fail`을 호출하면, 상태 필터(`status='CLAIMED'`)는 여전히 열려 있어 A의 완료가 그대로 반영된다 — `application_history`에 스레드 A의 (오래된) 결과가 끼어들고, 그 후 스레드 B의 진짜 완료는 상태가 이미 바뀌어 조용히 버려진다. M3·M4에서 `claimed_by` 조건을 지워도 `LeaseTest`·`TaskOutcomeTest`·`RealtimeTest`가 전부 생존한 것은, 애초에 이 소유권 검사를 실제로 갈라 세우는(=B와 A가 다른 workerId를 갖는다고 전제하는) 테스트가 하나도 없다는 뜻이다. **코드 구조상 이 결함을 만드는 경로가 여럿이 아니라 정확히 하나**(`ClaimLoop`의 workerId 필드가 인스턴스당 하나, `claimParallelism` 스레드가 공유)임을 확인했다 — 원래 B6이 "중"으로 매겼던 이유(코드만 읽고 가설 수준)보다 실행 경로가 더 구체적이고 일상적인 다중 스레드 운용(claimParallelism=6)에서 자연히 열리는 창이라 **심각도를 높음에 가깝게 재평가**한다. |

### 낮음/정보 — 코드 재확인만(전수 조사: 그 결함을 만드는 경로가 코드에 하나뿐인지)

| ID | 심각도 | 위치 | 내용 · 전수성 확인 |
|----|--------|------|------|
| B5 | 중 | `backend/build.gradle.kts` · 계약 `openapi.yml` | `grep -rn "jakarta.validation" backend/src/main` → **0건**, `build.gradle.kts`에 `spring-boot-starter-validation` 의존성 없음. `RecipeRequest.version: minimum 1` 등 계약의 제약을 강제하는 코드 경로가 애초에 존재하지 않는다(전수 확인 — validation 의존성이 아예 없으므로 예외 케이스가 있을 수 없다). |
| B7 | 낮 | `api/AdminControllers.kt:59-78`(`UserController.update`) | `PUT /api/users/{id}`는 `Policy.check(actor, USER_EQUIPMENT_MANAGE)`만 보고 대상 `id`가 자기 자신인지 검사하는 코드가 없다(`AdminControllers.kt` 전체에 `actor.id == id` 류 비교 부재 확인). ADMIN이 자기 role을 바꿀 수 있는 경로가 이거 하나뿐이라 전수 확인됨. |
| B8 | 낮 | `api/AdminControllers.kt:100-178`(`EquipmentController`) | `create`·`update` 모두 `consoleUrl`을 문자열 그대로 저장한다 — 스킴·호스트 검증 코드가 `EquipmentController`에 없다(유일한 쓰기 경로). |
| B9 | 낮 | `worker/PlaywrightRecipeApplier.kt:112-114,127-134` (**이전 반복이 적은 줄 번호 561-565·583은 오기 — 이 파일은 총 147줄이라 존재하지 않는 줄이었다. 실제 위치로 정정**) | `recipe.params.forEach { (field, value) -> page.fill("input[name=$field]", value) }` — `field` 키가 검증 없이 CSS 속성 선택자 문자열에 직결된다. 계약도 `additionalProperties: {type: string}`뿐이라 키 문자셋 제한이 어디에도 없다(fillForm·verify 두 곳 다 같은 패턴, 다른 경로 없음). |
| B10 | 정보 | `domain/Enums.kt:10-26`(`JobStatus.aggregate`) | `TaskStatus`에 `CANCELLED` 대응 집계 분기가 없어 전부 `CANCELLED`인 잡은 `FAILED`로 집계된다. `JobServiceTest.cancelStopsPendingTasksAndRetryPutsThemBack`이 이 동작을 명시적으로 단언하고 있어 **의도된 설계**로 보인다 — 결함이 아니라 UX 상 "취소"와 "실패"가 화면에서 구분되지 않는다는 정보성 지적. |
| B11 | 정보 | `job/JobService.kt:20-47`(`create`) | `Idempotency-Key` 재사용 시 요청 본문 일치를 보지 않는다. `JobServiceTest.sameIdempotencyKeyReturnsExistingJobWithoutNewRows`가 이 동작을 테스트명으로 명시적 의도라고 못박고 있다 — 결함이 아니라 정보성. |
| B12 | 정보 | `api/AuthController.kt:36-50`(`login`) | 로그인 시도 횟수 제한·계정 잠금 코드가 없다(`AuthController.kt` 전체 확인, 로그인 경로는 이거 하나뿐). 타이밍 은닉(더미 해시)·bcrypt 비용은 갖췄지만 무차별 대입 자체를 막는 장치는 없다. |

## 대조 결과 (이전 반복이 「문제 없음」으로 확인한 것 — 이번 반복도 코드로 재확인, 흔들지 않음)

- attempt 상한 3회·백오프 `base×2^(a−1)`·60초 상한: `TaskOutcomeTest.retryableFailureSchedulesBackoffAndThirdFailureIsFinal`이 지킨다.
- 무인증 허용 경로 정확히 넷: `AuthorizationMatrixTest.everyContractEndpointHasAMatrixRow`가 등호로 못박는다.
- 세마포어 3 < 클레임 풀 6, 비밀값 미노출, 메트릭 4종 실노출: 코드 구조상 변경 없음(이전 반복 관찰 유지).

## 요약

- **B1·B2는 실행으로 실증됐다** — 회귀 테스트 `AuthorizationMatrixTest.approverOverwritesOthersDraftThenApprovesOwnContent`(M5)와
  `TaskOutcomeTest.claimedTaskOfCancelledJobIsReclaimableAfterRetryableFailure`(M6)가 이 worktree에 남아 있다.
- **B6은 씨앗(M3·M4)으로 "테스트가 안 지킨다"는 것까지 확인했고, 코드 구조 분석(`ClaimLoop`의 workerId 공유)으로 실행 경로까지
  구체화됐다** — 별도 exploit 테스트는 이번 위임 범위 밖이라 worktree에 추가하지 않았다.
- B3는 리뷰어 A의 F1을 그대로 인용(중복 확인 생략).
- B5·B7~B12는 코드로 재확인해 전수성(그 결함을 만드는 경로가 하나뿐임)을 확인했고 심각도는 이전 반복과 동일하게 유지(B9는 줄 번호만 정정).
