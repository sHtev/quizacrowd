import { $, connect, progress, show } from './ws.js'

// Kept for this tab only, so a reload doesn't lose the quiz in progress.
const stored = {
  key: () => sessionStorage.getItem('hostKey'),
  quiz: () => sessionStorage.getItem('hostQuiz'),
}

function fail(message) {
  $('error').textContent = message
  show($('error'), true)
}

if (stored.key()) $('key').value = stored.key()
if (stored.quiz()) manage(stored.quiz())

$('import').addEventListener('submit', async (e) => {
  e.preventDefault()
  sessionStorage.setItem('hostKey', $('key').value)
  const response = await fetch('/api/quizzes?mode=PUB', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${stored.key()}` },
    body: await $('file').files[0].text(),
  })
  if (response.status === 401) return fail('That host key was not accepted.')
  if (response.status === 400) return fail('No usable multiple-choice questions in that file.')
  if (!response.ok) return fail(`Import failed (HTTP ${response.status}).`)
  const { quizId, imported, skipped } = await response.json()
  sessionStorage.setItem('hostQuiz', quizId)
  manage(quizId)
  $('status').textContent = `Imported ${imported} questions` + (skipped ? `, skipped ${skipped} unsupported.` : '.')
})

function manage(quizId) {
  show($('error'), false)
  show($('import'), false)
  show($('quiz'), true)
  $('quiz-id').textContent = quizId
  $('screen-link').href = `/screen?quiz=${quizId}`
  // Watch the quiz the way the big screen does, for the status line and the answered count.
  connect(`screen=${quizId}`, onEvent)
}

function onEvent(event) {
  switch (event.type) {
    case 'question':
      $('status').textContent = `${progress(event)} is open.`
      $('answered').textContent = 0
      return
    case 'closed': $('status').textContent = 'Answers are closed.'; return
    case 'leaderboard': $('answered').textContent = event.answered; return
    case 'ended': $('status').textContent = 'The quiz has ended.'
  }
}

for (const action of ['next', 'close', 'end']) {
  $(action).addEventListener('click', async () => {
    show($('error'), false)
    const response = await fetch(`/api/quizzes/${stored.quiz()}/${action}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${stored.key()}` },
    })
    if (response.status === 409) fail(`Can't ${action} right now.`)
    else if (!response.ok) fail(`${action} failed (HTTP ${response.status}).`)
  })
}

$('another').addEventListener('click', () => {
  sessionStorage.removeItem('hostQuiz')
  location.reload()
})
