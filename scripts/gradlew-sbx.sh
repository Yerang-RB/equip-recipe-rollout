#!/usr/bin/env bash
# srt 샌드박스 안에서 ./gradlew를 돌리기 위한 프록시 래퍼.
# JVM은 http(s)_proxy 환경변수를 무시하고, JDK는 HTTPS 터널의 Basic 인증을 기본 차단한다
# → 프록시 설정을 시스템 프로퍼티로 넘기고 터널 인증 차단을 해제한다.
# https_proxy가 없으면(비샌드박스) 변환 없이 그대로 ./gradlew에 위임한다.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk-21.0.11+10}"

if [ -n "${https_proxy:-}" ]; then
	pw=$(echo "$https_proxy" | sed -E 's#http://srt:([^@]+)@.*#\1#')
	export GRADLE_OPTS="${GRADLE_OPTS:-} -Dhttp.proxyHost=localhost -Dhttp.proxyPort=3128 -Dhttp.proxyUser=srt -Dhttp.proxyPassword=$pw -Dhttps.proxyHost=localhost -Dhttps.proxyPort=3128 -Dhttps.proxyUser=srt -Dhttps.proxyPassword=$pw -Djdk.http.auth.tunneling.disabledSchemes="
fi

exec ./gradlew "$@"
