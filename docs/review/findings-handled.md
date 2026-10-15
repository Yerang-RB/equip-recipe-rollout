# 리뷰 발견 처리 (작업 9)

입력: `review-A.md`(F1·F2) · `review-B.md`(B1~B3·B5~B12 — B4는 보고서에 없다) · `review-A-rerun.md`(A1~A12 → 이 표에서는 `AR<n>`, W1·WS1).
처리는 셋 중 하나다 — **수정**(재현 테스트 먼저, 음성 팔은 `PROGRESS.md` 「작업 9 씨앗 표」) · **레저**(`PROGRESS.md` 「리스크 원장」 R 줄 +
실서버 시나리오) · **오탐**(의도된 동작의 근거 + R 줄). 스킬 불변식(기본 거부 · 단일 에러 형식 · 본문 상한 · 잠금 · 감사 append-only·전수)을
어기는 발견은 레저하지 않았다.

## review-B.md

| ID | 처리 | 내용 |
| --- | --- | --- |
| **B1** | 수정 | `PUT /api/recipes/{id}`가 `created_by`를 고친 사람으로 바꾼다 — 덮어쓴 사람은 자기 승인 불가(4-eyes). 매트릭스(초안 수정 OPERATOR·APPROVER)는 그대로. 재현 `AuthorizationMatrixTest.editorOfOthersDraftCannotApproveIt`, 음성 팔 N3 |
| **B2** | 수정 | 취소 시 실행 중(CLAIMED) task에 취소 표시(`last_error = CANCEL_REQUESTED…`)를 남기고, 그 시도가 재시도 가능 실패로 끝나면 `PENDING` 대신 `CANCELLED`. 재현 `TaskOutcomeTest.claimedTaskOfCancelledJobIsNotRetried`, 음성 팔 N4. 천장: 표시를 `last_error`에 싣는다(추가 열은 불변 조건 1이 막음) — R6 |
| **B3** | 수정 | 워커 대행 감사 행 — `AuditWriter.writeSystem`(`actor_id=null, actor_name='system', detail.on_behalf_of`)을 task 성공·실패 전이와 같은 트랜잭션에서 쓴다. 콘솔 오류 문구는 싣지 않는다. 재현 `TaskOutcomeTest.workerOutcomesWriteSystemAuditRows`, 음성 팔 N6 |
| B5 | 수정 | 계약의 `minLength`·`maxLength`·`minimum`을 요청 DTO `init`에서 강제 → 400 `INVALID_REQUEST`(의존성 추가 없음). 재현 `EquipmentApiTest.contractConstraintsAreEnforcedAsInvalidRequest`, 음성 팔 N8 |
| **B6** | 수정 | 소유자 id를 프로세스가 아니라 **클레임 시도마다** 새로 만든다(`worker-xxxx-<순번>`). 재현: E2E 12대 잡의 `count(distinct claimed_by) == 12`(음성 팔 N7) + 뺏긴 소유자가 끝내지도 연장하지도 못함 `TaskOutcomeTest.staleOwnerCannotFinishOrExtendReclaimedTask`(M3·M4 생존분 — 음성 팔 N5·N5b) |
| B7 | 레저 | ADMIN이 자기 역할·활성 여부를 바꿀 수 있다. 실서버 시나리오: smoke-admin이 자기를 OPERATOR로 강등 → 200, 이후 자기 `GET /api/users` 403. 같은 순간 다른 사용자의 정당한 요청(smoke-operator 초안 작성 201 · smoke-approver 승인 200)은 **무효화되지 않았다** — 피해는 자기 계정의 관리 권한 상실뿐이고 복구는 다른 ADMIN(또는 DB). R1 |
| B8 | 수정 | `consoleUrl`은 `http(s)://호스트…`만(계약 `pattern` 먼저). 재현 같은 테스트(`"x"` → 400), 음성 팔은 AR6과 같은 테스트의 N9 |
| B9 | 수정 | 레시피 `params` 키는 `^[a-z][a-z0-9_]{0,49}$`만(계약 `propertyNames` 먼저) — 워커가 키를 CSS 선택자에 넣는 경로를 입력에서 닫는다. 재현 같은 테스트, 음성 팔 N10 |
| B10 | 오탐 | 전부 CANCELLED인 잡의 집계가 FAILED인 것은 설계 §5 집계 규칙(「전부 FAILED/CANCELLED → FAILED」) 그대로이고 `JobServiceTest.cancelStopsPendingTasksAndRetryPutsThemBack`이 단언한다. R2 |
| B11 | 오탐 | `Idempotency-Key` 재사용 시 본문을 대조하지 않는 것은 불변 조건 5(「같으면 기존 잡 반환」) 그대로이고 `JobServiceTest.sameIdempotencyKeyReturnsExistingJobWithoutNewRows`가 단언한다. R3 |
| B12 | 레저 | 로그인 스로틀·잠금 없음 — 결정 기입 10이 범위 밖으로 정했다. 실서버 시나리오: smoke-operator 계정에 틀린 비밀번호 30회 → 전부 401(같은 본문), 직후 smoke-operator 정답 로그인 200 · smoke-approver 기존 세션의 `GET /api/jobs` 200 — 잠금이 없으니 **다른 사용자·같은 사용자의 정당한 요청을 무효화하지 않는다**(나쁜 갈래는 무차별 대입 허용 쪽). R4 |

## review-A.md

| ID | 처리 | 내용 |
| --- | --- | --- |
| F1 | 수정 | B3과 같은 지점 — B3 행 |
| F2 | 수정 | B1과 같은 지점 — B1 행 |

## review-A-rerun.md

| ID | 처리 | 내용 |
| --- | --- | --- |
| AR1 | 수정 | `AuditFilter`가 `UriUtils.decode`한 경로로 정규화(깨진 인코딩은 원문) — 인코딩 id 승인도 `POST /api/recipes/{id}/approve` OK 행 + `target_id`. 재현 `AuditInvariantTest.percentEncodedPathIsAuditedAsTheSameOperation`(인코딩 경로를 테스트가 만든다), 음성 팔 N1 |
| AR2 | 수정 | AR1과 같은 수정(짧은 인코딩의 `targetId=null`도 같은 테스트가 `target_id`로 단언) |
| AR3 | 수정 | `action`·`target_type`은 열 너비 50으로, `detail.path`는 200자로 자른다 — 무인증 긴 경로도 행이 남고 ERROR 로그가 없다. 재현 `AuditInvariantTest.longUnauthenticatedPathIsStillAudited`, 음성 팔 N2 |
| AR4 | 수정 | `consoleUser`를 계약 `Equipment` 스키마와 응답에서 제거(불변 조건 16 문면). 재현 `EquipmentApiTest.consoleCredentialsNeverAppearInResponses`에 부재 단언 추가, 음성 팔 N11. 천장(ADMIN도 저장된 콘솔 계정을 못 본다) R7 |
| AR5 | 수정 | B5 행 |
| AR6 | 수정 | 설비 수정에서 `consoleUrl`이 바뀌면 `consolePassword` 필수(없으면 400) — 저장된 비밀번호가 새 호스트로 가지 않는다. 계약 설명 먼저. 재현 같은 테스트, 음성 팔 N9 |
| AR7 | 수정 | `smoke.sh` 기동 대기가 gradle 생존을 매초 확인 — 기동 실패 시 즉시 실패(리뷰어 실측 181 s → 5 s 권고 그대로). |
| AR8 | 수정 | `smoke.sh`의 앱 종료 대상을 `--server.port=$PORT` 인자까지 좁혔다(다른 포트의 스모크를 죽이지 않는다). |
| AR9 | 수정 | `PROGRESS.md` 「리스크 원장」에 콘솔 비밀번호 평문 저장(운영 전 재검토)·비밀값 폐기 절차를 적었다(R5·R8). |
| AR10 | 수정 | compose의 api·worker에 `SERVER_ADDRESS: 0.0.0.0` — `deploy-gates.sh` ②가 그 줄 2개를 양의 앵커로 센다(씨앗 G27). |
| AR11 | 레저 | 샌드박스 프록시 비밀번호가 gradle JVM 명령줄에 보인다 — 샌드박스 수명의 임시 값, 러너 밖에서는 붙지 않는다. R9 |
| AR12 | 레저 | 공유 `~/.gradle` 동시 사용의 gradle 기동 오류 1회 — 코드 밖 환경 잡음(원인은 2026-09-17 동시 세션으로 특정됨). R10 |
| W1 | 레저 | `test.sh`의 「전부 skip」 방어는 한 줄뿐 — 그 줄이 지우면 붉어지는 대체 방어는 없다. 스크립트 자체가 정본 가드라 이중화하지 않는다. R11 |
| WS1 | 레저 | 「생성 비밀번호 로그 부재」는 스모크 한 줄만 지킨다(테스트 스위트에 대응 단언 없음). R12 |
