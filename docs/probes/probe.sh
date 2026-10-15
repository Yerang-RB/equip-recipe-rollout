# 레저 B7·B12 실서버 시나리오 — smoke.sh 복사본 안에서 source된다(call·expect·body_of 재사용).
echo "== B12: 스로틀 없음 — 틀린 비밀번호 30회 뒤 정당한 요청이 무효화되는가 =="
codes=""
for i in $(seq 1 30); do
	out=$(call "" POST /api/auth/login '{"username":"smoke-operator","password":"wrong-password"}')
	codes="$codes ${out##*$'\n'}"
done
echo "B12 틀린 로그인 30회 응답 코드: $(tr ' ' '\n' <<<"$codes" | sort | uniq -c | tr '\n' ' ')"
out=$(call smoke-operator POST /api/auth/login "{\"username\":\"smoke-operator\",\"password\":\"$SMOKE_PASSWORD\"}")
echo "B12 직후 smoke-operator 정답 로그인: ${out##*$'\n'}"
out=$(call smoke-approver GET /api/jobs)
echo "B12 smoke-approver 기존 세션 GET /api/jobs: ${out##*$'\n'}"

echo "== B7: ADMIN 자기 강등 — 다른 사용자의 정당한 요청이 무효화되는가 =="
out=$(call smoke-admin GET /api/users)
admin_id=$(jq -r '.[] | select(.username=="smoke-admin") | .id' <<<"$(body_of "$out")")
out=$(call smoke-admin PUT "/api/users/$admin_id" '{"displayName":"smoke-admin","role":"OPERATOR","enabled":true}')
echo "B7 smoke-admin 자기 역할 OPERATOR로 변경: ${out##*$'\n'} $(body_of "$out")"
out=$(call smoke-admin GET /api/users)
echo "B7 강등 뒤 smoke-admin GET /api/users: ${out##*$'\n'}"
out=$(call smoke-operator POST /api/recipes "{\"partNo\":\"PB7-$$\",\"version\":1,\"params\":{\"temp\":\"200\"}}")
echo "B7 같은 시점 smoke-operator 초안 작성: ${out##*$'\n'}"
rid=$(jq -r .id <<<"$(body_of "$out")")
out=$(call smoke-approver POST "/api/recipes/$rid/approve")
echo "B7 같은 시점 smoke-approver 승인: ${out##*$'\n'}"
