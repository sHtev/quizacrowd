import { $, connect, countdown, progress, renderStandings, show } from './ws.js'

const quizId = new URLSearchParams(location.search).get('quiz')
let stopCountdown = () => {}

if (quizId) {
  $('qr').src = `/api/quizzes/${encodeURIComponent(quizId)}/qr.svg`
  connect(`screen=${encodeURIComponent(quizId)}`, onEvent)
} else {
  $('lobby').textContent = 'Add ?quiz=<id> to the address.'
}

function onEvent(event) {
  switch (event.type) {
    case 'question': return showQuestion(event)
    case 'closed':
      stopCountdown()
      ;[...$('options').children].forEach((item, index) => item.classList.toggle('correct', index === event.correctIndex))
      return
    case 'leaderboard':
      $('answered').textContent = event.answered
      renderStandings($('leaderboard'), event.top)
      return
    case 'ended':
      stopCountdown()
      show($('round'), false)
      show($('lobby'), false)
      show($('final'), true)
      renderStandings($('leaderboard'), event.top)
  }
}

function showQuestion(question) {
  show($('lobby'), false)
  show($('round'), true)
  $('progress').textContent = progress(question)
  $('question').textContent = question.text
  $('answered').textContent = 0
  $('options').replaceChildren(...question.options.map((option) => {
    const item = document.createElement('li')
    item.textContent = option
    return item
  }))
  stopCountdown()
  stopCountdown = countdown($('countdown'), question.deadlineEpochMs)
}
