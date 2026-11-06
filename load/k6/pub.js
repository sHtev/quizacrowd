// A pub quiz night: 50 players on their phones and one host, against the full stack.
//
//   HOST_KEY=$(scripts/host-key.sh redlion "The Red Lion") \
//     k6 run -e BASE=http://redlion.localhost:8080 load/k6/pub.js
//
// Players join, keep a WebSocket open, and answer each question after a random 0.5–8 s.
// The host imports the sample quiz, then runs every question: next, wait, close.
import http from 'k6/http'
import { check, fail, sleep } from 'k6'
import { Counter, Trend } from 'k6/metrics'
import { WebSocket } from 'k6/websockets'

const BASE = __ENV.BASE || 'http://redlion.localhost:8080'
const WS_BASE = BASE.replace(/^http/, 'ws')
const HOST_KEY = __ENV.HOST_KEY
const PLAYERS = Number(__ENV.PLAYERS || 50)
const QUESTION_SECONDS = Number(__ENV.QUESTION_SECONDS || 20)
// Time for every player to join and connect before the first question.
const LOBBY_SECONDS = Number(__ENV.LOBBY_SECONDS || 10)
const quizFile = open('../../services/quiz-api/src/test/resources/quizzer-sample.json')

const messagesPerPlayer = new Trend('ws_messages_per_player')
const answersAccepted = new Counter('answers_accepted')

export const options = {
  // Browsers send *.localhost to this machine by themselves; k6 needs telling.
  hosts: { '*.localhost': '127.0.0.1' },
  scenarios: {
    players: { executor: 'per-vu-iterations', exec: 'player', vus: PLAYERS, iterations: 1, maxDuration: '10m' },
    host: { executor: 'per-vu-iterations', exec: 'host', vus: 1, iterations: 1, startTime: `${LOBBY_SECONDS}s`, maxDuration: '10m' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:answers}': ['p(95)<300'],
    ws_messages_per_player: ['min>=10'],
    checks: ['rate>0.99'],
  },
}

const json = { 'Content-Type': 'application/json' }
const asHost = { ...json, Authorization: `Bearer ${HOST_KEY}` }

export function setup() {
  if (!HOST_KEY) fail('set HOST_KEY (scripts/host-key.sh prints it)')
  const response = http.post(`${BASE}/api/quizzes?mode=PUB`, quizFile, { headers: asHost, tags: { name: 'import' } })
  if (response.status !== 201) fail(`import quiz: HTTP ${response.status}`)
  return response.json() // { quizId, imported, skipped }
}

export function host({ quizId, imported }) {
  const act = (action) => {
    const response = http.post(`${BASE}/api/quizzes/${quizId}/${action}`, null, { headers: asHost, tags: { name: action } })
    check(response, { [`${action} accepted`]: (r) => r.status === 200 })
  }
  for (let i = 0; i < imported; i++) {
    act('next')
    sleep(QUESTION_SECONDS)
    act('close')
    sleep(3) // time on the leaderboard before the next question
  }
  act('end')
}

export function player({ quizId }) {
  const joined = http.post(`${BASE}/api/quizzes/${quizId}/join`, JSON.stringify({ name: `Player ${__VU}` }),
    { headers: json, tags: { name: 'join' } })
  if (!check(joined, { 'joined': (r) => r.status === 201 })) return
  const token = joined.json('token')

  let received = 0
  const socket = new WebSocket(`${WS_BASE}/ws?token=${encodeURIComponent(token)}`)
  socket.addEventListener('message', (message) => {
    received++
    const event = JSON.parse(message.data)
    if (event.type === 'question') {
      setTimeout(() => answer(token, event), 500 + Math.random() * 7_500)
    } else if (event.type === 'ended') {
      socket.close()
    }
  })
  socket.addEventListener('close', () => messagesPerPlayer.add(received))
  socket.addEventListener('error', (e) => console.error(`VU ${__VU}: ${e.error}`))
}

function answer(token, question) {
  const response = http.post(`${BASE}/answers`,
    JSON.stringify({ questionId: question.questionId, choice: Math.floor(Math.random() * question.options.length) }),
    { headers: { ...json, Authorization: `Bearer ${token}` }, tags: { name: 'answers' } })
  if (check(response, { 'answer accepted': (r) => r.status === 202 })) answersAccepted.add(1)
}
