# Load tests

[k6](https://k6.io) scripts that play a quiz against the running stack, through Traefik like real phones.

## Pub: 50 players (`k6/pub.js`)

Fifty players join, keep a WebSocket open and answer each question after a random 0.5–8 s.
One host imports the sample quiz (4 questions) and runs it: next, 20 s, close, 3 s, …, end.

```sh
scripts/local-up.sh
HOST_KEY=$(scripts/host-key.sh redlion "The Red Lion") \
  k6 run -e BASE=http://redlion.localhost:8080 load/k6/pub.js
```

`PLAYERS`, `QUESTION_SECONDS` and `LOBBY_SECONDS` (time to join before the first question) can be set with `-e`.

Thresholds: under 1% failed requests, p95 answer latency under 300 ms, at least 10 WebSocket messages per player,
over 99% of checks passing.

## Results

### Local, pub, 50 players

2026-11-06, k3d (one k3s node) under OrbStack on a 2019 MacBook Pro (i5-8257U, 4 cores), k6 2.3.0 on the same machine.

| Measure | Result |
| --- | --- |
| Answers accepted | 200 of 200 (50 players × 4 questions) |
| Failed requests | 0 of 260 |
| `POST /answers` latency | median 20 ms, p95 45 ms, max 98 ms |
| All requests | median 23 ms, p95 393 ms (the 50 joins arriving at once) |
| WebSocket connect | median 61 ms, p95 136 ms |
| Messages per player | 134 (6,700 in all) |

Each player gets about 33 messages per question: the question, the close, and a leaderboard plus their own
standing for every leaderboard update (at most two a second while answers are coming in).
