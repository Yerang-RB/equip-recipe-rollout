# GOTCHAS — equip-recipe-rollout (이 프로젝트 코드·도메인의 함정. 환경·스택 공통은 글로벌/`~/.claude/gotchas/jvm.md`)

- [test] `scripts/test.sh` 전체 모드가 `BUILD SUCCESSFUL` 뒤 메시지 없이 exit 1 → 자기점검 `find`에 테스트 없는 `console-mock`의 `build/test-results/test`(부재)가 들어가 find exit 1 → `set -e`+`pipefail`+`2>/dev/null`이 침묵 실패로 만든다(2026-09-16 러너 재실행 ABORT 실측) → 존재하는 결과 디렉터리만 find에 넘기고, 검증 줄은 `; echo exit=$?`로 마지막 명령까지 확인
- [convention] PLAN.md `## 결정 대기`만 읽고 그 아래 `## 결정 기입`(사람의 답)을 놓치면 이미 풀린 결정을 「막힘」으로 오판한다(작업 8을 두 세션이 연속 건너뜀, 2026-09-17 실측) → 결정 대기를 인용하기 전에 결정 기입 절을 먼저 grep
- [convention] 리뷰 위임 보고서가 PLAN 검증 줄이 grep하는 헤더(`## 리뷰어 입력`·`진위 씨앗`·`| S<n> |`)를 재구성해 실질은 맞는데 검증 줄이 붉었다(작업 7, 2026-09-17 실측) → 위임 프롬프트에 그 문자열을 그대로 박고, 리뷰어는 하나씩 순차로, 보고서는 worktree 안에 쓰고 마지막에 `cp`(worktree 격리 에이전트의 Write는 메인 트리 경로를 거부한다)
