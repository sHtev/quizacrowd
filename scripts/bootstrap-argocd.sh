#!/usr/bin/env bash
# Installs Argo CD on the Hetzner cluster and hands it the root app; from then on the cluster follows main.
#   KUBECONFIG=~/.kube/quizacrowd-hetzner scripts/bootstrap-argocd.sh
# Run scripts/bootstrap-secrets.sh against the same cluster first: the secrets aren't in git.
set -euo pipefail

CONTEXT=${KUBE_CONTEXT:-quizacrowd-hetzner}
CHART_VERSION=10.17.0 # Argo CD v3.6.1
cd "$(dirname "$0")/.."

helm repo add argo https://argoproj.github.io/argo-helm >/dev/null 2>&1 || true
helm repo update argo >/dev/null
helm upgrade --install argocd argo/argo-cd --kube-context "$CONTEXT" \
  --namespace argocd --create-namespace --version "$CHART_VERSION" \
  --values deploy/argocd/values.yaml --wait
kubectl --context "$CONTEXT" apply -f deploy/argocd/root.yaml
echo "Argo CD installed. UI: kubectl --context $CONTEXT -n argocd port-forward svc/argocd-server 8081:80"
echo "Admin password: kubectl --context $CONTEXT -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d"
