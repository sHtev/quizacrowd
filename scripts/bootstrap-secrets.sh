#!/usr/bin/env bash
# Creates quizacrowd-secrets in the cluster: admin token, database password and the ES256 key pair
# for join tokens. Does nothing if the secret already exists, so it never rotates keys by accident.
# Key material goes straight from a temporary directory into the cluster; nothing is written to the repo.
set -euo pipefail

CONTEXT=${KUBE_CONTEXT:-k3d-quizacrowd}
NS=quizacrowd
k() { kubectl --context "$CONTEXT" "$@"; }

k get namespace "$NS" >/dev/null 2>&1 || k create namespace "$NS" >/dev/null
if k -n "$NS" get secret quizacrowd-secrets >/dev/null 2>&1; then
  echo "quizacrowd-secrets already exists in $CONTEXT; leaving it alone"
  exit 0
fi

dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
# P-256 private key as unencrypted PKCS#8, public key as X.509 SubjectPublicKeyInfo: what JoinTokens.kt reads.
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$dir/private.pem" 2>/dev/null
openssl pkey -in "$dir/private.pem" -pubout -out "$dir/public.pem"

k -n "$NS" create secret generic quizacrowd-secrets \
  --from-literal=ADMIN_TOKEN="$(openssl rand -hex 32)" \
  --from-literal=DATABASE_PASSWORD="$(openssl rand -hex 24)" \
  --from-file=JOIN_TOKEN_PRIVATE_KEY="$dir/private.pem" \
  --from-file=JOIN_TOKEN_PUBLIC_KEY="$dir/public.pem" >/dev/null
echo "created quizacrowd-secrets in $CONTEXT"
