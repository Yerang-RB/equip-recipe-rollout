#!/usr/bin/env bash
# 검증은 이 스크립트로만 한다(불변 조건 13). 맨 셸에서 단독 실행 가능해야 한다.
#
#   scripts/test.sh              전체 스위트
#   scripts/test.sh --tests X    지목 실행(:backend:test — 루트 test는 테스트 없는 모듈 때문에 매치 0으로 붉어진다)
#
# 러너가 같은 명령을 독립 재실행하므로 백투백 2회 통과가 채택 조건이다.
# `--rerun`이 핵심: 없으면 gradle이 UP-TO-DATE로 건너뛰고 **한 건도 실행하지 않은 채 exit 0**을 낸다.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ $# -eq 0 ]; then
	task=test
	results=(backend/build/test-results/test console-mock/build/test-results/test)
else
	task=:backend:test
	results=(backend/build/test-results/test)
fi

mkdir -p build
marker=build/.test-run-marker
touch "$marker"
scripts/gradlew-sbx.sh "$task" --rerun "$@"

# 존재하는 결과 디렉터리만 남긴다 — 테스트 없는 모듈(console-mock)은 디렉터리가 없고, 부재 경로가 find를 exit 1로 만들어
# set -e 아래 명령 치환이 메시지 없이 스크립트를 죽인다.
existing=()
for d in "${results[@]}"; do
	if [ -d "$d" ]; then existing+=("$d"); fi
done
if [ ${#existing[@]} -eq 0 ]; then
	echo "test.sh 실패: 테스트 결과 디렉터리가 하나도 없다(0건 실행)" >&2
	exit 1
fi
results=("${existing[@]}")

# 자기 점검: 이번 실행이 결과 XML을 하나도 새로 쓰지 않았으면 0건 실행이다(헛통과를 실패로 바꾼다).
new_xml=$(find "${results[@]}" -name 'TEST-*.xml' -newer "$marker" 2>/dev/null | head -1 || true)
if [ -z "$new_xml" ]; then
	echo "test.sh 실패: 이번 실행에서 테스트 결과가 하나도 쓰이지 않았다(0건 실행)" >&2
	exit 1
fi
# 결과 XML은 전부 skip이어도 쓰인다. 실제로 돈 건수(tests − skipped) 합이 0이면 실패.
ran=$(find "${results[@]}" -name 'TEST-*.xml' -newer "$marker" -exec grep -ho '<testsuite [^>]*' {} + |
	sed -E 's/.* tests="([0-9]+)".* skipped="([0-9]+)".*/\1 \2/' | awk '{ n += $1 - $2 } END { print n + 0 }')
if [ "$ran" -eq 0 ]; then
	echo "test.sh 실패: 이번 실행에서 실제로 돈 테스트가 0건이다(전부 skip)" >&2
	exit 1
fi
