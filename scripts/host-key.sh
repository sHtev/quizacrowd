#!/usr/bin/env bash
# Prints the host key for a tenant on the local cluster, creating the tenant on first use.
# The key is only shown once by the API, so it's kept in a Secret (host-key-<slug>) for later runs.
# /admin isn't routed publicly, so the call goes through a port-forward to quiz-api.
set -euo pipefail

SLUG=${1:?usage: host-key.sh <slug> <name>}
NAME=${2:?usage: host-key.sh <slug> <name>}
CONTEXT=${KUBE_CONTEXT:-k3d-quizacrowd}
k() { kubectl --context "$CONTEXT" -n quizacrowd "$@"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

ADMIN_TOKEN=$(k get secret quizacrowd-secrets -o 'jsonpath={.data.ADMIN_TOKEN}' | base64 -d)

# Right after a restart the forward can attach to a pod that is shutting down,
# so start it again until /healthz answers.
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

BODY=$(mktemp)
trap 'rm -f "$BODY"; if [ -n "$PF" ]; then kill "$PF" 2>/dev/null || true; fi' EXIT
status=$(curl -s -o "$BODY" -w '%{http_code}' -X POST http://localhost:18080/admin/tenants \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg slug "$SLUG" --arg name "$NAME" '{slug: $slug, name: $name}')")
case $status in
  201) KEY=$(jq -r .hostKey "$BODY")
       k create secret generic "host-key-$SLUG" --from-literal=key="$KEY" --dry-run=client -o yaml | k apply -f - >/dev/null ;;
  409) KEY=$(k get secret "host-key-$SLUG" -o 'jsonpath={.data.key}' | base64 -d) \
         || fail "tenant $SLUG exists but there's no host-key-$SLUG Secret" ;;
  *)   fail "create tenant: HTTP $status" ;;
esac
echo "$KEY"
