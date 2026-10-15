#!/usr/bin/env bash
# 프론트 종단 스모크: api(bootTestRun) + web(next build && next start)을 실제로 띄워 서빙된 것을 curl로 단언한다.
# 화면 본문은 마운트 뒤 클라이언트가 그리므로 여기서는 SSR 셸·정적 자산·서체 CSS·rewrite·런타임 env를 본다.
# 클릭·입력·WS 렌더는 curl이 구조적으로 못 본다 — 브라우저 E2E가 없는 런(LOOP_UI=0)에서는 미검증이다.
set -euo pipefail
cd "$(dirname "$0")/.."

WEB_PORT="${WEB_PORT:-13000}"
API_PORT="${SMOKE_PORT:-18080}"
WEB="http://127.0.0.1:$WEB_PORT"
API_LOG=build/web-smoke-api.log
WEB_LOG=build/web-smoke-web.log

fail() { echo "web-smoke 실패: $*" >&2; exit 1; }

# 남의 서버를 상대로 헛통과하지 않도록 먼저 비어 있음을 본다. ss가 없으면 「비어 있음」과 구별이 안 되니 실패다.
SS=/usr/sbin/ss
[ -x "$SS" ] || fail "ss 명령이 없다"
port_busy() { "$SS" -tln | grep -qE "[:.]$1\s"; }
for p in "$WEB_PORT" "$API_PORT"; do port_busy "$p" && fail "$p 포트가 이미 사용 중이다"; done

# 앱 JVM은 gradle 데몬의 자식이라 래퍼만 죽이면 산다. 이 포트로 띄운 것만 명령줄로 찾는다(머신 전체를 죽이지 않게).
api_pids() { ps -eo pid,args | grep "TestRolloutApplicationK[t].*--server.port=$API_PORT" | awk '{print $1}' || true; }
cleanup() {
	local pids
	pids=$(api_pids)
	[ -n "$pids" ] && kill $pids 2>/dev/null || true
	[ -n "${API_PID:-}" ] && kill "$API_PID" 2>/dev/null || true
	# next start는 npm의 손자다 — setsid로 띄운 프로세스 그룹째 죽인다.
	[ -n "${WEB_PID:-}" ] && kill -- "-$WEB_PID" 2>/dev/null || true
	for _ in $(seq 1 30); do port_busy "$WEB_PORT" || port_busy "$API_PORT" || break; sleep 1; done
	return 0
}
trap cleanup EXIT
mkdir -p build

echo "== api 기동(:$API_PORT) =="
scripts/gradlew-sbx.sh :backend:bootTestRun --args="--mode=api --server.port=$API_PORT" >"$API_LOG" 2>&1 &
API_PID=$!

echo "== web 빌드·기동(:$WEB_PORT) =="
# rewrite 목적지는 빌드와 기동 양쪽에서 env로 준다(설정이 두 시점에 읽힌다).
(cd web && API_ORIGIN="http://127.0.0.1:$API_PORT" npm run build) >"$WEB_LOG" 2>&1 || { tail -30 "$WEB_LOG" >&2; fail "web 빌드 실패"; }
WS_ORIGIN="ws://smoke.invalid:1" API_ORIGIN="http://127.0.0.1:$API_PORT" \
	setsid bash -c "cd web && exec npx next start -p $WEB_PORT -H 127.0.0.1" >>"$WEB_LOG" 2>&1 &
WEB_PID=$!

curl -sf -o /dev/null --max-time 5 --retry 90 --retry-delay 2 --retry-connrefused "http://127.0.0.1:$API_PORT/api/health" \
	|| { tail -30 "$API_LOG" >&2; fail "api가 뜨지 않았다 — $API_LOG"; }
curl -sf -o /dev/null --max-time 5 --retry 60 --retry-delay 1 --retry-connrefused "$WEB/login" \
	|| { tail -30 "$WEB_LOG" >&2; fail "web이 뜨지 않았다 — $WEB_LOG"; }

echo "== 화면 7개 SSR 셸 =="
# h1 문구까지 본다 — 200만 보면 모든 경로가 같은 오류 셸이어도 통과한다(내비 링크 문구와 겹치지 않게 h1 태그째).
check_page() { # <경로> <h1 문구>
	local html
	html=$(curl -sf "$WEB$1") || fail "$1 가 200이 아니다"
	grep -qF "<h1>$2" <<<"$html" || fail "$1 에 제목 「$2」가 없다"
	echo "OK  $1 — 200 · $2"
}
check_page /login "로그인"
check_page /equipment "설비"
check_page /recipes "레시피"
check_page /recipes/1 "레시피"
check_page /jobs/new "배포 시작"
check_page /jobs/1 "배포 잡"
check_page /history "적용 이력"
check_page /audit "감사 로그"

echo "== 정적 자산(standalone 함정: HTML이 참조하는 자산이 실제로 서빙되는가) =="
html=$(curl -sf "$WEB/login") || fail "/login 재요청 실패"
assets=$(grep -oE '/_next/static/[^"'\'' )\\]+' <<<"$html" | sort -u) || fail "/login HTML에 /_next/static 참조가 없다"
[ -n "$assets" ] || fail "/login HTML에 /_next/static 참조가 없다"
n=0
for a in $assets; do
	code=$(curl -s -o /dev/null -w '%{http_code}' "$WEB$a")
	[ "$code" = 200 ] || fail "$a → $code"
	n=$((n + 1))
done
echo "OK  자산 ${n}개 전부 200"

echo "== 서체(셀프호스팅 @font-face + 변수 이름 일치) =="
css=$(for a in $(grep -E '\.css$' <<<"$assets"); do curl -sf "$WEB$a"; done)  || fail "CSS 자산을 받지 못했다"
[ -n "$css" ] || fail "서빙 CSS가 없다"
grep -qF '@font-face{font-family:Pretendard;' <<<"$css" || fail "Pretendard @font-face 없음"
grep -qF '@font-face{font-family:Rollout Mono;' <<<"$css" || fail "Rollout Mono @font-face 없음"
# 변수가 @font-face와 다른 이름을 가리키면 서체가 조용히 폴백된다(Turbopack 실측 — layout.tsx 주석).
grep -qF -- '--font-mono:"Rollout Mono"' <<<"$css" || fail "--font-mono가 Rollout Mono를 가리키지 않는다"
grep -qF -- '--font-sans:"Pretendard"' <<<"$css" || fail "--font-sans가 Pretendard를 가리키지 않는다"
# @font-face의 src가 전부 같은 출처 자산이고 실제로 200인가. 파이프를 `$(…)`에 넣지 않는다(set -e 아래 매치 0이면 조용히 죽는다).
faces=$(grep -oE '@font-face\{[^}]*\}' <<<"$css") || fail "@font-face를 읽지 못했다"
srcs=$(grep -oE 'src:url\([^)]*\)' <<<"$faces") || fail "@font-face에 src가 없다"
m=0
for src in $srcs; do
	url=${src#src:url(}; url=${url%)}
	case "$url" in /_next/static/media/*.woff2) ;; *) fail "@font-face src가 셀프호스팅 woff2가 아니다: $url" ;; esac
	code=$(curl -s -o /dev/null -w '%{http_code}' "$WEB$url")
	[ "$code" = 200 ] || fail "서체 파일 $url → $code"
	m=$((m + 1))
done
[ "$m" -eq 7 ] || fail "@font-face src가 ${m}개(기대 7 — Pretendard 4 + Rollout Mono 3)"
echo "OK  Pretendard·Rollout Mono @font-face · 변수 일치 · 서체 파일 ${m}개 200"

echo "== rewrite·런타임 env =="
[ "$(curl -sf "$WEB/api/health" | jq -r .status)" = ok ] || fail "web의 /api/health rewrite가 api에 닿지 않는다"
echo "OK  /api/health — web → api rewrite"
code=$(curl -s -o /dev/null -w '%{http_code}' "$WEB/api/auth/me")
[ "$code" = 401 ] || fail "무인증 /api/auth/me 가 $code(기대 401)"
echo "OK  /api/auth/me — 무인증 401이 그대로 전달"
[ "$(curl -sf "$WEB/ws-origin" | jq -r .origin)" = "ws://smoke.invalid:1" ] || fail "/ws-origin이 기동 시점 WS_ORIGIN을 돌려주지 않는다"
echo "OK  /ws-origin — 런타임 env"

echo
echo "web-smoke 통과"
