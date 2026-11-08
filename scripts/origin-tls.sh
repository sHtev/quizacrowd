#!/usr/bin/env bash
# Loads the Cloudflare origin certificate from Terraform (infra/cloudflare) into the origin-tls Secret,
# which Traefik serves to Cloudflare. Safe to re-run.
#   AWS_PROFILE=shtev-admin KUBECONFIG=~/.kube/quizacrowd-hetzner scripts/origin-tls.sh
set -euo pipefail

CONTEXT=${KUBE_CONTEXT:-quizacrowd-hetzner}
cd "$(dirname "$0")/../infra/cloudflare"

dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
umask 077
terraform output -raw origin_certificate > "$dir/tls.crt"
terraform output -raw origin_private_key > "$dir/tls.key"

kubectl --context "$CONTEXT" -n quizacrowd create secret tls origin-tls \
  --cert "$dir/tls.crt" --key "$dir/tls.key" --dry-run=client -o yaml |
  kubectl --context "$CONTEXT" apply -f - >/dev/null
echo "origin-tls loaded into $CONTEXT"
