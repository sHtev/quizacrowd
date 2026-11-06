# QuizACrowd

A live multiplayer quiz that scales from the pub to the stadium to the telly: players join by QR code, questions fan out to every phone, answers flow through a queue to a scorer, and a live leaderboard reaches every phone and the big screen. A portfolio project in Kotlin on Kubernetes.

## Running it locally

Needs JDK 25, Docker (OrbStack works), k3d, kubectl and jq.

```sh
./gradlew build          # unit and integration tests (Testcontainers)
scripts/local-up.sh      # k3d cluster, images, secrets, deploy
scripts/smoke.sh         # one question end to end with curl
(cd e2e && npm ci && npx playwright test)   # browser test of a round
```

Then open `http://redlion.localhost:8080/host` with the key from `scripts/host-key.sh redlion "The Red Lion"`.
Load tests are in [`load/`](load/README.md).

## CI

GitHub Actions runs `./gradlew build` on every pull request and push. On `main` it also pushes images to GHCR
(tagged with the short commit SHA) and points the Hetzner overlay at them, for ArgoCD to roll out.
The Playwright test needs a running cluster, so it isn't run in CI yet.
