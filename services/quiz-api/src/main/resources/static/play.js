import { $, connect, countdown, progress, show } from './ws.js'

const quizId = new URLSearchParams(location.search).get('quiz')
// sessionStorage survives a reload or the phone sleeping, so the player rejoins without retyping their name.
const tokenKey = `token:${quizId}`
const answerKey = (questionId) => `answer:${questionId}`
let current = null
let stopCountdown = () => {}

function fail(message) {
  $('error').textContent = message
  show($('error'), true)
}

if (!quizId) fail('This link is missing its quiz. Scan the QR code again.')
else if (sessionStorage.getItem(tokenKey)) start()
else show($('join'), true)

$('join').addEventListener('submit', async (e) => {
  e.preventDefault()
  const response = await fetch(`/api/quizzes/${quizId}/join`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: $('name').value }),
  })
  if (response.status === 400) return fail('Names need 1 to 24 characters.')
  if (response.status === 409) return fail('This quiz has finished.')
  if (!response.ok) return fail("Couldn't join this quiz.")
  sessionStorage.setItem(tokenKey, (await response.json()).token)
  show($('error'), false)
  show($('join'), false)
  start()
})

function start() {
  show($('game'), true)
  connect(`token=${encodeURIComponent(sessionStorage.getItem(tokenKey))}`, onEvent, () => {
    // The token wasn't accepted (expired or keys rotated): ask for a name again.
    sessionStorage.removeItem(tokenKey)
    show($('game'), false)
    show($('join'), true)
  })
}

function onEvent(event) {
  switch (event.type) {
    case 'question': return showQuestion(event)
    case 'closed': return showResult(event)
    case 'you':
      $('score').textContent = event.score
      $('rank').textContent = event.rank ?? '–'
      return
    case 'ended':
      stopCountdown()
      show($('waiting'), false)
      show($('round'), false)
      show($('final'), true)
  }
}

function showQuestion(question) {
  current = question
  const chosen = sessionStorage.getItem(answerKey(question.questionId))
  show($('waiting'), false)
  show($('round'), true)
  $('progress').textContent = progress(question)
  $('question').textContent = question.text
  $('result').textContent = chosen === null ? '' : 'Answer in'
  $('options').replaceChildren(...question.options.map((option, index) => {
    const button = document.createElement('button')
    button.type = 'button'
    button.textContent = option
    button.disabled = chosen !== null
    button.classList.toggle('chosen', chosen === String(index))
    button.addEventListener('click', () => answer(question, index))
    return button
  }))
  stopCountdown()
  stopCountdown = countdown($('countdown'), question.deadlineEpochMs)
}

function buttons() {
  return [...$('options').children]
}

async function answer(question, choice) {
  // Lock in straight away so a second tap can't send another answer.
  buttons().forEach((button, index) => {
    button.disabled = true
    button.classList.toggle('chosen', index === choice)
  })
  sessionStorage.setItem(answerKey(question.questionId), choice)
  const response = await fetch('/answers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${sessionStorage.getItem(tokenKey)}` },
    body: JSON.stringify({ questionId: question.questionId, choice }),
  }).catch(() => null)

  if (response?.status === 202) $('result').textContent = 'Answer in'
  else if (response?.status === 409) $('result').textContent = 'Too late'
  else {
    // Not received: let them try again.
    sessionStorage.removeItem(answerKey(question.questionId))
    buttons().forEach((button) => { button.disabled = false })
    $('result').textContent = "That didn't send. Try again."
  }
}

function showResult(closed) {
  stopCountdown()
  if (current?.questionId !== closed.questionId) return
  buttons().forEach((button, index) => {
    button.disabled = true
    button.classList.toggle('correct', index === closed.correctIndex)
  })
  const chosen = sessionStorage.getItem(answerKey(closed.questionId))
  $('result').textContent = chosen === null ? 'No answer' : Number(chosen) === closed.correctIndex ? 'Correct' : 'Wrong'
}
