#!/usr/bin/env bash
# 실행 환경 점검(작업 9) — **정적** 구성 검사다. 실행 중 포트를 재지 않는다: `docker compose up`은 샌드박스가
# 소켓을 막아 루프가 못 돌고, 살아 있는 배포본의 바인드 확인(`/usr/sbin/ss -tln`)은 병합 전 사람 몫이다.
#
# ① 필수 비밀값: 둘 다 있으면 compose가 풀리고, 하나라도 없으면 실패한다(기본값 없음)
# ② 게시 포트: 줄마다 127.0.0.1 양의 앵커 3줄 == 포트 줄 총수 3줄(0.0.0.0·호스트 생략 줄이 끼면 총수가 어긋난다)
# ③ 환경변수 주입 시점: 브라우저 번들 env(NEXT_PUBLIC_) 없음 · 이미지에 비밀번호 ENV 없음
# ④ 기본 자격증명 잔존: 시드 계정 이름은 시드 클래스 한 파일에만 · SEED_PASSWORD 기본값 없음
set -uo pipefail
cd "$(dirname "$0")/.."

command -v docker >/dev/null && docker compose version >/dev/null 2>&1 || { echo "gates: docker compose를 쓸 수 없다" >&2; exit 1; }
rg -q 'x' <<<'x' || { echo "gates: rg를 쓸 수 없다" >&2; exit 1; }
test -f compose.yaml || { echo "gates: compose.yaml 없음" >&2; exit 1; }

fail=0
check() {  # <설명> <명령...> — 명령이 성공해야 통과
	local d="$1"; shift
	if "$@" >/dev/null 2>&1; then echo "OK   $d"; else echo "FAIL $d" >&2; fail=1; fi
}
# rg는 매치 없음(1)과 도구 오류(2)를 구별한다 — 매치 없음만 통과다(`! rg`는 경로 오류도 통과로 접는다).
nomatch() {
	rg -q "$@" 2>/dev/null
	[ $? -eq 1 ]
}

# ① 필수 비밀값
check "① 비밀값 둘 다 있으면 compose가 풀린다" env POSTGRES_PASSWORD=x SEED_PASSWORD=x docker compose config -q
check "① POSTGRES_PASSWORD 없으면 실패" bash -c '! env -u POSTGRES_PASSWORD SEED_PASSWORD=x docker compose config -q'
check "① SEED_PASSWORD 없으면 실패" bash -c '! env -u SEED_PASSWORD POSTGRES_PASSWORD=x docker compose config -q'

# ② 게시 포트
loopback=$(grep -cE '^\s*-\s*"127\.0\.0\.1:[0-9]+:[0-9]+"$' compose.yaml)
published=$(grep -cE '^\s*-\s*"?[^"]*:[0-9]+(:[0-9]+)?"?$' compose.yaml | tr -d ' ')
ports_blocks=$(grep -cE '^\s*ports:' compose.yaml)
check "② 127.0.0.1 게시 포트 줄 3 (실제 $loopback)" test "$loopback" -eq 3
check "② 포트 줄 총수 3 (실제 $published)" test "$published" -eq 3
check "② ports 블록 3 (실제 $ports_blocks)" test "$ports_blocks" -eq 3
# 컨테이너 안 서버는 0.0.0.0이어야 compose 네트워크에서 닿는다(로컬 기본 127.0.0.1은 ConfigPinTest가 지킨다)
check "② api·worker 컨테이너 바인드 SERVER_ADDRESS=0.0.0.0 2줄" test "$(grep -cE '^\s*SERVER_ADDRESS: 0\.0\.0\.0$' compose.yaml)" -eq 2

# ③ 환경변수 주입 시점
check "③ web/Dockerfile 존재(부재 검사의 대상)" test -f web/Dockerfile
check "③ NEXT_PUBLIC_ 없음(web 소스·next.config·Dockerfile)" nomatch 'NEXT_PUBLIC_' web/app web/next.config.ts web/Dockerfile
check "③ 이미지에 비밀번호 ENV 없음" nomatch -i '^\s*(ENV|ARG)\s.*PASSWORD' backend/Dockerfile console-mock/Dockerfile web/Dockerfile

# ④ 기본 자격증명 잔존
seed_files=$(rg -l 'operator1|approver1|admin1' backend/src/main console-mock/src/main web/app 2>/dev/null | wc -l)
check "④ 시드 계정 이름은 운영 소스 한 파일에만 (실제 $seed_files)" test "$seed_files" -eq 1
check "④ compose에 SEED_PASSWORD 기본값 없음" nomatch 'SEED_PASSWORD:-' compose.yaml
check "④ compose가 SEED_PASSWORD를 요구한다(:?)" rg -q 'SEED_PASSWORD:\?' compose.yaml
check "④ 시드 코드에 비밀번호 리터럴·플레이스홀더 기본값 없음" nomatch -i '(password\s*=\s*"|SEED_PASSWORD:)' backend/src/main/kotlin/com/rollout/api/DemoSeed.kt backend/src/main/resources

exit $fail
