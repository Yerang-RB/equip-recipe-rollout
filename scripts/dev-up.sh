#!/usr/bin/env bash
# 사람 시각 판독용 로컬 실기동: api(:8080, 임베디드 DB) + worker(:8081, 같은 DB) + console-mock(:8090) + next dev(:3000).
# `--check`는 넷이 전부 200으로 답하는지 확인하고 내린 뒤 exit 0 — 아니면 Ctrl-C까지 떠 있는다.
# 전부 127.0.0.1에만 바인드한다(불변 조건 16). 데모 시드 계정 비밀번호 = 목업 콘솔 비밀번호(`build/dev-up/console-credentials`).
set -euo pipefail
cd "$(dirname "$0")/.."

CHECK=0
[ "${1:-}" = --check ] && CHECK=1

API_PORT=8080 WORKER_PORT=8081 MOCK_PORT=8090 WEB_PORT=3000
JAVA="${JAVA_HOME:-$HOME/.local/jdk-21.0.11+10}/bin/java"
LOG_DIR=build/dev-up

fail() { echo "dev-up 실패: $*" >&2; exit 1; }

SS=/usr/sbin/ss
[ -x "$SS" ] || fail "ss 명령이 없다"
port_busy() { "$SS" -tln | grep -qE "[:.]$1\s"; }
for p in $API_PORT $WORKER_PORT $MOCK_PORT $WEB_PORT; do port_busy "$p" && fail "$p 포트가 이미 사용 중이다"; done

api_pids() { ps -eo pid,args | grep "TestRolloutApplicationK[t].*--server.port=$API_PORT" | awk '{print $1}' || true; }
cleanup() {
	local pids
	pids=$(api_pids)
	[ -n "$pids" ] && kill $pids 2>/dev/null || true
	for p in "${API_PID:-}" "${WORKER_PID:-}" "${MOCK_PID:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done
	[ -n "${WEB_PID:-}" ] && kill -- "-$WEB_PID" 2>/dev/null || true
	for _ in $(seq 1 30); do
		port_busy $API_PORT || port_busy $WORKER_PORT || port_busy $MOCK_PORT || port_busy $WEB_PORT || break
		sleep 1
	done
	rm -f build/dev-db.url
	return 0
}
trap cleanup EXIT
mkdir -p "$LOG_DIR"
rm -f build/dev-db.url

echo "== jar 빌드(worker·console-mock) =="
scripts/gradlew-sbx.sh :backend:bootJar :console-mock:bootJar >"$LOG_DIR/build.log" 2>&1 || { tail -30 "$LOG_DIR/build.log" >&2; fail "jar 빌드 실패"; }

# 목업 콘솔 계정은 기동마다 새로 만든다(코드·스크립트에 고정 자격증명을 두지 않는다). 설비 등록 때 쓰도록 파일로 남긴다.
MOCK_USER="${MOCK_CONSOLE_USER:-console}"
MOCK_PASS="${MOCK_CONSOLE_PASS:-dev-$(od -An -tx8 -N8 /dev/urandom | tr -d ' ')}"
(umask 077; printf '%s\n%s\n' "$MOCK_USER" "$MOCK_PASS" >"$LOG_DIR/console-credentials")
# 데모 시드(operator1·approver1·admin1)의 비밀번호도 같은 값이다. 명령줄(ps)·env(gradle 데몬 재사용) 대신 0600 파일로 넘긴다.
# 시드 설비의 콘솔 URL은 compose 호스트명(console-mock)이라 dev-up에서 배포하면 네트워크 오류로 끝난다 — 화면 판독용이다.
(umask 077; printf 'SEED_PASSWORD=%s\n' "$MOCK_PASS" >"$LOG_DIR/seed.properties")

echo "== api(:$API_PORT) =="
scripts/gradlew-sbx.sh :backend:bootTestRun \
	--args="--mode=api --server.port=$API_PORT --app.seed.demo=true --spring.config.import=optional:file:$PWD/$LOG_DIR/seed.properties --dev-db-url" >"$LOG_DIR/api.log" 2>&1 &
API_PID=$!
for _ in $(seq 1 120); do [ -s build/dev-db.url ] && break; sleep 1; done
[ -s build/dev-db.url ] || { tail -30 "$LOG_DIR/api.log" >&2; fail "api가 DB URL을 쓰지 않았다"; }
DB_URL=$(cat build/dev-db.url)

echo "== worker(:$WORKER_PORT) =="
"$JAVA" -jar backend/build/libs/backend-0.0.1-SNAPSHOT.jar --mode=worker --server.port=$WORKER_PORT \
	--spring.datasource.url="$DB_URL" --spring.datasource.username=postgres >"$LOG_DIR/worker.log" 2>&1 &
WORKER_PID=$!

echo "== console-mock(:$MOCK_PORT) =="
MOCK_CONSOLE_USER="$MOCK_USER" MOCK_CONSOLE_PASS="$MOCK_PASS" \
	"$JAVA" -jar console-mock/build/libs/console-mock-0.0.1-SNAPSHOT.jar --server.port=$MOCK_PORT --server.address=127.0.0.1 \
	>"$LOG_DIR/console-mock.log" 2>&1 &
MOCK_PID=$!

echo "== web(:$WEB_PORT, next dev) =="
API_ORIGIN="http://127.0.0.1:$API_PORT" WS_ORIGIN="ws://localhost:$API_PORT" \
	setsid bash -c "cd web && exec npx next dev --webpack -p $WEB_PORT -H 127.0.0.1" >"$LOG_DIR/web.log" 2>&1 &
WEB_PID=$!

wait_200() { # <이름> <URL> <로그>
	curl -sf -o /dev/null --max-time 10 --retry 90 --retry-delay 2 --retry-connrefused --retry-all-errors "$2" \
		|| { tail -30 "$3" >&2; fail "$1 이 200으로 답하지 않는다 — $2 · $3"; }
	echo "OK  $1 — $2"
}
wait_200 api "http://127.0.0.1:$API_PORT/api/health" "$LOG_DIR/api.log"
wait_200 worker "http://127.0.0.1:$WORKER_PORT/actuator/health" "$LOG_DIR/worker.log"
wait_200 console-mock "http://127.0.0.1:$MOCK_PORT/health" "$LOG_DIR/console-mock.log"
wait_200 web "http://127.0.0.1:$WEB_PORT/login" "$LOG_DIR/web.log"

if [ "$CHECK" = 1 ]; then
	echo "dev-up --check 통과"
	exit 0
fi
echo "떠 있습니다: http://localhost:$WEB_PORT (시드 계정 operator1·approver1·admin1 · 비밀번호와 목업 콘솔 계정: $LOG_DIR/console-credentials) — Ctrl-C로 내립니다."
wait "$API_PID"
