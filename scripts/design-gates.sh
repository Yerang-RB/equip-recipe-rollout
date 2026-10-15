#!/usr/bin/env bash
# design-quality 기계 게이트(own-shop `scripts/design-gates.sh`에서 이 프로젝트에 필요한 팔만 이식).
# 회귀 가드지 결함 발견기가 아니다 — 전부 초록이어도 사람 시각 판독은 남는다.
#
# 따옴표 문자는 \x22(")·\x27(')로 쓴다. 패턴을 bash -c로 감싸면 따옴표 중첩이 깨져 exit 0이 된다.
set -uo pipefail
cd "$(dirname "$0")/.."

DIRS=$(ls -d web/app 2>/dev/null) || true
[ -n "$DIRS" ] || { echo "gates: 소스 디렉터리 없음(web/app)"; exit 1; }

# neg 게이트는 「매치 없음 = 통과」다. rg 부재·pcre2 미지원이 깨끗한 트리와 같은 침묵을 내므로 먼저 막는다.
rg --pcre2 -q 'x' <<<'x' || { echo "gates: rg --pcre2를 쓸 수 없다" >&2; exit 1; }

fail=0

# rg는 매치 없음(1)과 도구 오류(2 — 패턴 컴파일 실패·경로 오류)를 구별한다. `! rg`는 둘을 같은 「통과」로
# 접으므로 뒤집는 검사는 전부 이 래퍼를 지난다 — 매치 없음(1)만 통과다.
nomatch() {
	local err rc
	err=$(rg -Uq --pcre2 "$@" 2>&1 >/dev/null); rc=$?
	[ $rc -ge 2 ] && printf 'rg 오류(exit %s): %s\n' "$rc" "$err" >&2
	[ $rc -eq 1 ]
}
neg() {  # <설명> <rg 패턴> [rg 옵션...] — web/app에 매치가 있으면 실패
	local d="$1"; shift
	if nomatch "$@" $DIRS; then echo "OK   $d"; else echo "FAIL $d" >&2; fail=1; fi
}
pos() {  # <설명> <명령...> — 명령이 성공해야 통과
	local d="$1"; shift
	if "$@" >/dev/null 2>&1; then echo "OK   $d"; else echo "FAIL $d" >&2; fail=1; fi
}
count_is() {  # <기대 수> <고정 문자열> <파일> — 매치 줄 수 등호
	[ "$(grep -cF -- "$2" "$3")" -eq "$1" ]
}

# 뒤집는 검사가 패턴 오류를 실패로 접는지 자기시험 — 래퍼가 `!`로 퇴행하면 여기서 붉어진다.
nomatch_fails_on_bad_pattern() { ! nomatch 'a(' web/app/globals.css 2>/dev/null; }
pos "뒤집는 검사가 패턴 오류를 실패로 접음(자기시험)" nomatch_fails_on_bad_pattern

# ── 타이포그래피(설계 §10 · 불변 조건 15) ──
pos "keep-all" grep -q "word-break: keep-all" web/app/globals.css
pos "tnum" grep -qF 'font-feature-settings: "tnum"' web/app/globals.css
pos "폴백 스택 문자열 리터럴 1줄" count_is 1 'system-ui, -apple-system, "Malgun Gothic", sans-serif' web/app/globals.css
for f in Pretendard-Regular Pretendard-Medium Pretendard-SemiBold Pretendard-Bold RolloutMono-Regular RolloutMono-Medium RolloutMono-Bold; do
	pos "서체 파일 $f.woff2" test -s "web/app/fonts/$f.woff2"
done
pos "layout의 Pretendard 경로 4" count_is 4 'path: "./fonts/Pretendard-' web/app/layout.tsx
pos "layout의 RolloutMono 경로 3" count_is 3 'path: "./fonts/RolloutMono-' web/app/layout.tsx
pos "패밀리명 Rollout Mono 선언" grep -qF '{ prop: "font-family", value: "Rollout Mono" }' web/app/layout.tsx
# Turbopack은 `--font-mono`를 선언의 패밀리명이 아니라 상수 이름으로 채운다(layout.tsx 주석) — webpack 고정.
pos "빌드·개발 서버는 webpack" grep -qE '"build": "next build --webpack"' web/package.json
# OFL Reserved Font Name: 수정본에 원래 이름을 쓰지 않는다. 출처 고지 문서(fonts/*.md)만 원저작 표기로 허용.
neg "원래 서체 이름(Monoplex) 없음" '(?i)monoplex' --glob '!**/fonts/*.md'
neg "AI 기본 서체" '(next/font/google)|(font-family:[^;]*\b(Inter|Roboto|Arial)\b)|(--[a-z-]*font[a-z-]*:[^;]*\b(Inter|Roboto|Arial)\b)|(fontFamily:\s*[\x22\x27][^\x22\x27]*\b(Inter|Roboto|Arial)\b)'
# 인터넷 없이 떠야 한다 — 외부 url()은 예외 없이 금지(서체든 이미지든).
neg "CSS 외부 url()" '(?i)url\(\s*[\x22\x27]?(https?:)?//'
neg "외부 서체 CDN 없음" '(?i)(fonts\.googleapis|fonts\.gstatic|cdn\.jsdelivr|unpkg\.com)' --glob '!**/fonts/*.md'

# ── 마크업 ──
neg "img alt"          '<img(?:(?!alt=)[\s\S])*?>'
neg "img width"        '<img(?:(?!width=)[\s\S])*?>'
neg "img loading"      '<img(?:(?!loading=)[^>])*>'
neg "_blank noopener"  '<a\b(?![^>]*(?:noopener|noreferrer))[^>]*target=[\x22\x27]_blank'
neg "외부 CSS/JS SRI"   '<(?:link|script)\b(?![^>]*integrity=)[^>]*(?:href|src)=[\x22\x27]https?://'

# 표시 카피에 한다체가 남았는가(화면은 합니다체). 코드 주석 줄은 제외한다.
if grep -RInE '(한다|이다|었다|았다|겠다|없다|있다)\.' --include='*.tsx' $DIRS \
	| grep -vE '^[^:]*:[0-9]+:[[:space:]]*(//|/\*|\*|\{/\*)'; then
	echo "FAIL 한다체 잔존" >&2; fail=1
else echo "OK   한다체 없음"; fi

# ── 환경변수 주입 시점 ── 브라우저 번들에 굳는 NEXT_PUBLIC_는 쓰지 않는다(런타임 env만).
pos "NEXT_PUBLIC_ 없음" nomatch 'NEXT_PUBLIC_' web/app web/next.config.ts

exit $fail
