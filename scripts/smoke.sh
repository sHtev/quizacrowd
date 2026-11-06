#!/usr/bin/env bash
# End-to-end check against the local cluster: one tenant, one quiz, two players, one question.
# Public traffic goes through Traefik on localhost:8080; the admin call goes through a port-forward,
# because /admin isn't routed publicly.
set -euo pipefail

CONTEXT=${KUBE_CONTEXT:-k3d-quizacrowd}
NS=quizacrowd
BASE=http://localhost:8080
HOST="redlion.localhost"
FIXTURE="$(dirname "$0")/../services/quiz-api/src/test/resources/quizzer-sample.json"
k() { kubectl --context "$CONTEXT" -n "$NS" "$@"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

secret() { k get secret quizacrowd-secrets -o "jsonpath={.data.$1}" | base64 -d; }
ADMIN_TOKEN=$(secret ADMIN_TOKEN)

# Admin: create the tenant through a port-forward to quiz-api. Right after a restart the forward can
# attach to a pod that is shutting down, so start it again until /healthz answers.
PF=
trap 'if [ -n "$PF" ]; then kill "$PF" 2>/dev/null || true; fi' EXIT
forward() {
  for _ in $(seq 5); do
    k port-forward svc/quiz-api 18080:8080 >/dev/null 2>&1 &
    PF=$!
    for _ in $(seq 25); do curl -s -o /dev/null http://localhost:18080/healthz && return 0; sleep 0.2; done
    kill "$PF" 2>/dev/null || true
  done
  return 1
}
forward || fail "no port-forward to quiz-api"
SLUG="redlion"
TENANT=$(mktemp)
status=$(curl -s -o "$TENANT" -w '%{http_code}' -X POST http://localhost:18080/admin/tenants \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"slug\":\"$SLUG\",\"name\":\"The Red Lion\"}")
case $status in
  201) HOST_KEY=$(jq -r .hostKey "$TENANT")
       k create secret generic smoke-host-key --from-literal=key="$HOST_KEY" --dry-run=client -o yaml | k apply -f - >/dev/null ;;
  409) HOST_KEY=$(k get secret smoke-host-key -o 'jsonpath={.data.key}' | base64 -d) ;; # tenant left by an earlier run
  *)   fail "create tenant: HTTP $status" ;;
esac
rm -f "$TENANT"

api() { curl -sf -H "Host: $HOST" "$@"; }
host() { api -H "Authorization: Bearer $HOST_KEY" "$@"; }

QUIZ=$(host -X POST "$BASE/api/quizzes?mode=PUB" --data-binary @"$FIXTURE" | jq -r .quizId) || fail "import quiz"
join() { api -X POST "$BASE/api/quizzes/$QUIZ/join" -H 'Content-Type: application/json' -d "{\"name\":\"$1\"}"; }
ADA=$(join Ada) || fail "join Ada"
BOB=$(join Bob) || fail "join Bob"

host -X POST "$BASE/api/quizzes/$QUIZ/next" >/dev/null || fail "next"
QUESTION=$(k exec deploy/valkey -- valkey-cli HGET "quiz:$QUIZ:current" questionId | tr -d '\r')
[ -n "$QUESTION" ] || fail "no open question in Valkey"
CORRECT=$(k exec statefulset/postgres -- psql -U quizacrowd -d quizacrowd -tAc \
  "select correct_index from questions where id = '$QUESTION'" | tr -d '\r')
WRONG=$(( (CORRECT + 1) % 2 ))

answer() {
  api -o /dev/null -w '%{http_code}' -X POST "$BASE/answers" -H "Authorization: Bearer $(jq -r .token <<<"$1")" \
    -H 'Content-Type: application/json' -d "{\"questionId\":\"$QUESTION\",\"choice\":$2}"
}
[ "$(answer "$ADA" "$CORRECT")" = 202 ] || fail "Ada's answer not accepted"
[ "$(answer "$BOB" "$WRONG")" = 202 ] || fail "Bob's answer not accepted"
host -X POST "$BASE/api/quizzes/$QUIZ/close" >/dev/null || fail "close"

# The scorer works asynchronously: wait for both players to appear on the leaderboard.
ADA_ID=$(jq -r .playerId <<<"$ADA"); BOB_ID=$(jq -r .playerId <<<"$BOB")
for _ in $(seq 50); do
  [ "$(k exec deploy/valkey -- valkey-cli ZCARD "lb:$QUIZ" | tr -d '\r')" = 2 ] && break
  sleep 0.2
done
ADA_SCORE=$(k exec deploy/valkey -- valkey-cli ZSCORE "lb:$QUIZ" "$ADA_ID" | tr -d '\r')
BOB_SCORE=$(k exec deploy/valkey -- valkey-cli ZSCORE "lb:$QUIZ" "$BOB_ID" | tr -d '\r')
host -X POST "$BASE/api/quizzes/$QUIZ/end" >/dev/null || fail "end"

[ -n "$ADA_SCORE" ] && [ -n "$BOB_SCORE" ] || fail "players not scored (Ada '$ADA_SCORE', Bob '$BOB_SCORE')"
[ "${ADA_SCORE%.*}" -gt 0 ] || fail "Ada answered correctly but scored $ADA_SCORE"
[ "${BOB_SCORE%.*}" -eq 0 ] || fail "Bob answered wrongly but scored $BOB_SCORE"
echo "PASS: Ada $ADA_SCORE, Bob $BOB_SCORE"
