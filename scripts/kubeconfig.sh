#!/usr/bin/env bash
# Fetches the Hetzner cluster's kubeconfig from the k3s server over SSH into ~/.kube/quizacrowd-hetzner
# (context quizacrowd-hetzner), pointed at the server's public address. Doesn't touch ~/.kube/config.
#   AWS_PROFILE=shtev-admin scripts/kubeconfig.sh   (reads the address from Terraform state)
set -euo pipefail

cd "$(dirname "$0")/../infra/hetzner"
SERVER=${SERVER_IP:-$(terraform output -raw server_ipv4)}
OUT=${KUBECONFIG_OUT:-$HOME/.kube/quizacrowd-hetzner}

mkdir -p "$(dirname "$OUT")"
umask 077
# accept-new: trust the host key on first contact (new server), refuse if it later changes.
ssh -o StrictHostKeyChecking=accept-new "root@$SERVER" cat /etc/rancher/k3s/k3s.yaml |
  sed -e "s#https://127.0.0.1:6443#https://$SERVER:6443#" -e 's/: default$/: quizacrowd-hetzner/' > "$OUT"
chmod 600 "$OUT"
echo "Wrote $OUT. Use: kubectl --kubeconfig $OUT get nodes"
