#!/usr/bin/env bash
# 실서버 부팅 스모크. 테스트가 아니라 진짜 서버를 띄워 종단을 밟는다 — 자동설정이 기동 때 찍는 것
# (생성 비밀번호·경고)은 diff에도 MockMvc 테스트에도 안 나타난다.
#
# 러너가 같은 명령을 독립 재실행하므로 백투백 2회 통과가 채택 조건이다 — 시작 전에 포트가 비어 있음을,
# 끝에 앱이 실제로 죽었음을 확인한다. 남은 서버가 다음 실행에 200을 대신 답하면 게이트가 헛통과한다.
#
# 주의: 이 파일을 heredoc으로 쓰는 명령과 같은 호출에서 실행하지 말 것 — app_pids의 브래킷 트릭은 같은
# 명령줄에 앱 클래스명 평문이 있으면 자기 셸을 찾아 죽인다.
set -euo pipefail
cd "$(dirname "$0")/.."

PORT="${SMOKE_PORT:-18080}"
BASE="http://127.0.0.1:$PORT"
LOG=build/smoke-boot.log

fail() {
	echo "스모크 실패: $*" >&2
	exit 1
}

# 앱 JVM은 gradle 데몬의 자식이라 래퍼를 죽여도 살아남는다. 명령줄로 직접 찾는다(브래킷 트릭: grep 자신 제외).
# 포트 인자까지 보아 다른 포트로 도는 스모크의 앱은 건드리지 않는다(리뷰 AR8).
app_pids() {
	ps -eo pid,args | grep 'TestRolloutApplicationK[t]' | grep -- "--server.port=$PORT " | awk '{print $1}' || true
}

stop_app() {
	local pids
	pids=$(app_pids)
	# `|| true` 필수: AND 리스트 마지막 명령은 errexit 면제가 없어, EXIT trap 재호출 때 ESRCH로 exit 1이 된다.
	[ -n "$pids" ] && kill $pids 2>/dev/null || true
	[ -n "${gradle_pid:-}" ] && kill "$gradle_pid" 2>/dev/null || true
	rm -f build/smoke-seed-password build/smoke-cookies-*
	return 0
}
trap stop_app EXIT

if curl -s -o /dev/null --max-time 2 "$BASE/api/health"; then
	fail "$PORT가 이미 응답한다 — 남은 서버를 죽이고 다시 실행하라(/usr/sbin/ss -tln 으로 확인)"
fi

mkdir -p build
: >"$LOG"
# 종단 시나리오용 임시 계정의 비밀번호. 명령줄(ps에 보인다)·env(gradle 데몬 재사용으로 자식에 닿는 보장이
# 없다) 대신 파일로 넘기고, 끝나면 지운다.
SEED_FILE=build/smoke-seed-password
SMOKE_PASSWORD="smoke-$(date +%s%N)-$$"
(umask 077; printf '%s' "$SMOKE_PASSWORD" >"$SEED_FILE")
# 모드·포트는 env가 아니라 인자로 준다 — gradle 데몬은 재사용돼 호출마다의 env가 자식에 닿는다는 보장이 없다.
scripts/gradlew-sbx.sh :backend:bootTestRun --args="--mode=api --server.port=$PORT --smoke-seed" >"$LOG" 2>&1 &
gradle_pid=$!

# gradle이 먼저 죽으면(컴파일·Flyway 실패) 기다리지 않고 바로 실패한다 — 고정 재시도는 181초를 태웠다(리뷰 AR7).
for _ in $(seq 1 180); do
	curl -sf -o /dev/null --max-time 5 "$BASE/api/health" && break
	kill -0 "$gradle_pid" 2>/dev/null || fail "기동 실패(gradle 종료) — 로그: $LOG"
	sleep 1
done
curl -sf -o /dev/null --max-time 5 "$BASE/api/health" || fail "기동 대기 실패 — 로그: $LOG"

# ── 부팅 로그 감사 ── 부재 단언 전에 먼저 앵커를 기다린다(버퍼된 로그를 즉시 grep하면 "아직"이 "없음"으로 통과한다).
# 앵커는 운영 main 클래스다 — bootTestRun의 진입점은 TestRolloutApplicationKt지만 Boot가 찍는 「Started」 줄은
# primary source(RolloutApplication)의 이름으로 나온다(실측).
ANCHOR='Started RolloutApplicationKt'
for _ in $(seq 1 30); do
	grep -q "$ANCHOR" "$LOG" && break
	sleep 1
done
grep -q "$ANCHOR" "$LOG" || fail "기동 완료 줄이 로그에 없다 — $LOG"
# 자동설정의 기본 사용자를 쓰지 않는다는 증거(ApiConfiguration의 빈 UserDetailsManager).
! grep -q 'generated security password' "$LOG" || fail "부팅 로그에 생성 비밀번호가 찍힌다 — $LOG"

out=$(curl -s -w $'\n%{http_code}' "$BASE/api/health")
code=${out##*$'\n'}
body=${out%$'\n'*}
[ "$code" = 200 ] || fail "GET /api/health → $code · 본문: $body"
[ "$(jq -r .status <<<"$body")" = ok ] || fail "헬스 본문: $body"

# ── 종단 시나리오: 로그인 → 등록 → 승인(다른 사람) → 잡 생성 → 멱등 재요청 → 무인증·인가 거부 ──
for _ in $(seq 1 30); do
	grep -q '스모크 시드 완료' "$LOG" && break
	sleep 1
done
grep -q '스모크 시드 완료' "$LOG" || fail "임시 계정 시드가 끝나지 않았다 — $LOG"

call() { # 사용자(빈 문자열이면 무인증) 메서드 경로 [본문] [추가헤더]
	local user=$1 method=$2 path=$3 data=${4:-} header=${5:-}
	local args=(-s -w $'\n%{http_code}' -X "$method" "$BASE$path")
	[ -n "$user" ] && args+=(-b "build/smoke-cookies-$user" -c "build/smoke-cookies-$user")
	[ -n "$data" ] && args+=(-H 'Content-Type: application/json' -d "$data")
	[ -n "$header" ] && args+=(-H "$header")
	curl "${args[@]}"
}

expect() { # 기대코드 설명 출력
	local want=$1 what=$2 out=$3
	local got=${out##*$'\n'}
	[ "$got" = "$want" ] || fail "$what → $got (기대 $want) · 본문: ${out%$'\n'*}"
}

body_of() { local out=$1; printf '%s' "${out%$'\n'*}"; }

rm -f build/smoke-cookies-*
for u in smoke-admin smoke-operator smoke-approver; do
	out=$(call "$u" POST /api/auth/login "{\"username\":\"$u\",\"password\":\"$SMOKE_PASSWORD\"}")
	expect 200 "로그인 $u" "$out"
done

out=$(call smoke-admin POST /api/equipment "{\"code\":\"E-$$\",\"consoleUrl\":\"http://127.0.0.1:1/\",\"model\":\"M1\",\"status\":\"RUNNING\",\"consoleUser\":\"cu\",\"consolePassword\":\"$SMOKE_PASSWORD\"}")
expect 201 "설비 등록" "$out"
equipment_id=$(jq -r .id <<<"$(body_of "$out")")
if grep -q consolePassword <<<"$(body_of "$out")"; then fail "설비 응답에 콘솔 자격증명이 있다"; fi

out=$(call smoke-operator POST /api/recipes "{\"partNo\":\"P-$$\",\"version\":1,\"params\":{\"temp\":\"200\"}}")
expect 201 "레시피 초안" "$out"
recipe_id=$(jq -r .id <<<"$(body_of "$out")")

out=$(call smoke-approver POST "/api/recipes/$recipe_id/approve")
expect 200 "승인(작성자가 아닌 APPROVER)" "$out"
[ "$(jq -r .status <<<"$(body_of "$out")")" = ACTIVE ] || fail "승인 뒤 상태: $(body_of "$out")"

key="k-$(date +%s%N)"
out=$(call smoke-operator POST /api/jobs "{\"recipeId\":$recipe_id,\"equipmentIds\":[$equipment_id]}" "Idempotency-Key: $key")
expect 201 "잡 생성" "$out"
job_id=$(jq -r .id <<<"$(body_of "$out")")

out=$(call smoke-operator POST /api/jobs "{\"recipeId\":$recipe_id,\"equipmentIds\":[$equipment_id]}" "Idempotency-Key: $key")
expect 200 "같은 키 재요청" "$out"
[ "$(jq -r .id <<<"$(body_of "$out")")" = "$job_id" ] || fail "멱등 재요청이 다른 잡을 만들었다"

# ── WebSocket 핸드셰이크: 쿠키 있으면 101, 없으면 401(기본 거부가 업그레이드에도 걸린다) ──
ws_headers=(-H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==')
# 101 뒤에는 서버가 스냅샷 프레임을 보내고 연결을 연 채로 둔다 — 응답 첫 줄만 보고 끊는다(타임아웃 종료는 정상).
ws_out=$(curl -s -i -N --max-time 3 "${ws_headers[@]}" -b build/smoke-cookies-smoke-operator "$BASE/api/ws/jobs/$job_id" || true)
head -n1 <<<"$ws_out" | grep -q '^HTTP/1.1 101' || fail "WS 핸드셰이크(쿠키 있음) 응답: $(head -n1 <<<"$ws_out")"

ws_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${ws_headers[@]}" "$BASE/api/ws/jobs/$job_id")
[ "$ws_code" = 401 ] || fail "무인증 WS 핸드셰이크 → $ws_code (기대 401)"

out=$(call "" GET /api/jobs)
expect 401 "무인증 조회" "$out"
[ "$(jq -r .code <<<"$(body_of "$out")")" = UNAUTHENTICATED ] || fail "무인증 본문: $(body_of "$out")"

out=$(call smoke-operator GET /api/audit)
expect 403 "OPERATOR의 감사 조회" "$out"
[ "$(jq -r .code <<<"$(body_of "$out")")" = FORBIDDEN ] || fail "거부 본문: $(body_of "$out")"

! grep -q "$SMOKE_PASSWORD" "$LOG" || fail "비밀번호가 로그에 찍힌다 — $LOG"
rm -f "$SEED_FILE" build/smoke-cookies-*

# ── 종료 확인: 죽지 않으면 다음 실행이 헛통과한다 ──
stop_app
down=""
for _ in $(seq 1 30); do
	if ! curl -s -o /dev/null --max-time 2 "$BASE/api/health"; then
		down=1
		break
	fi
	sleep 1
done
[ -n "$down" ] || fail "종료 후에도 $PORT가 응답한다"
if /usr/sbin/ss -tln | grep -q ":$PORT "; then
	fail "종료 후에도 $PORT가 LISTEN 상태다"
fi

echo "스모크 통과 (포트 $PORT · api 모드 기동 → /api/health 200 → 로그인·등록·승인·잡 멱등·WS 101/401·401·403 → 종료)"
