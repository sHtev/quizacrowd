// Shared by the three pages. Everything shown on screen goes in with textContent, never innerHTML,
// so player names and quiz text can't inject markup.

export const $ = (id) => document.getElementById(id)

export function show(element, visible) {
  element.hidden = !visible
}

/** Opens the quiz WebSocket, reconnecting after a drop with a growing delay. Close code 4401 means refused. */
export function connect(query, onEvent, onRefused) {
  let delay = 500
  const open = () => {
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
    const socket = new WebSocket(`${scheme}://${location.host}/ws?${query}`)
    socket.onopen = () => { delay = 500 }
    socket.onmessage = (message) => onEvent(JSON.parse(message.data))
    socket.onclose = (e) => {
      if (e.code === 4401) return onRefused?.()
      setTimeout(open, delay)
      delay = Math.min(delay * 2, 10_000)
    }
  }
  open()
}

/** Shows the seconds left until the deadline; call the returned function to stop. */
export function countdown(element, deadlineEpochMs) {
  const tick = () => {
    element.textContent = `${Math.max(0, Math.ceil((deadlineEpochMs - Date.now()) / 1000))}s`
  }
  tick()
  const timer = setInterval(tick, 250)
  return () => {
    clearInterval(timer)
    element.textContent = ''
  }
}

/** Fills an <ol> with standings: rank, name and score. */
export function renderStandings(list, standings) {
  list.replaceChildren(...standings.map((standing) => {
    const row = document.createElement('li')
    for (const [field, value] of [['rank', standing.rank], ['name', standing.name], ['score', standing.score]]) {
      const cell = document.createElement('span')
      cell.className = field
      cell.dataset.testid = field
      cell.textContent = value
      row.append(cell)
    }
    return row
  }))
}

export const progress = (question) => `Question ${question.index + 1} of ${question.total}`
