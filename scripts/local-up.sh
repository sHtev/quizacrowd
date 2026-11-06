#!/usr/bin/env bash
# Builds the images and runs everything on a local k3d cluster, with Traefik on localhost:8080.
# Leaves your current kubectl context alone; everything targets k3d-quizacrowd explicitly.
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=quizacrowd
export KUBE_CONTEXT=k3d-$CLUSTER
k() { kubectl --context "$KUBE_CONTEXT" "$@"; }

if ! k3d cluster list "$CLUSTER" >/dev/null 2>&1; then
  k3d cluster create "$CLUSTER" -p "8080:80@loadbalancer" --kubeconfig-switch-context=false --wait
fi

./gradlew jibDockerBuild
k3d image import -c "$CLUSTER" \
  ghcr.io/shtev/quizacrowd-quiz-api:local ghcr.io/shtev/quizacrowd-ingest:local \
  ghcr.io/shtev/quizacrowd-scorer:local ghcr.io/shtev/quizacrowd-gateway:local

# k3s installs Traefik shortly after the cluster starts; its CRDs must exist before the IngressRoute.
until k get crd ingressroutes.traefik.io >/dev/null 2>&1; do sleep 2; done

scripts/bootstrap-secrets.sh
existing=$(k -n quizacrowd get deployment quiz-api -o name 2>/dev/null || true)
k apply -k deploy/overlays/local
# Re-imported images keep the same tag, so running pods must be restarted to pick them up.
[ -n "$existing" ] && k -n quizacrowd rollout restart deployment quiz-api ingest scorer gateway
for workload in statefulset/postgres statefulset/redpanda deployment/valkey \
                deployment/quiz-api deployment/ingest deployment/scorer deployment/gateway; do
  k -n quizacrowd rollout status "$workload" --timeout=5m
done
echo "up: try curl -H 'Host: redlion.localhost' http://localhost:8080/api/..."
