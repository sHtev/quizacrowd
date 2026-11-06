import { test, expect, type Browser, type Page } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'

const fixturePath = '../services/quiz-api/src/test/resources/quizzer-sample.json'

// Quizzer stores text HTML-escaped; the API unescapes it on import.
const unescape = (s: string) =>
  s.replace(/&quot;/g, '"').replace(/&#0?39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')

const correctAnswers = new Map<string, string>()
for (const round of JSON.parse(readFileSync(fixturePath, 'utf8')).rounds)
  for (const q of round.questions ?? [])
    if (q.correctAnswers?.length) correctAnswers.set(unescape(q.description), unescape(q.correctAnswers[0]))

const hostKey = () => execFileSync('../scripts/host-key.sh', ['redlion', 'The Red Lion'], { encoding: 'utf8' }).trim()

async function newPage(browser: Browser, path: string): Promise<Page> {
  const page = await (await browser.newContext()).newPage()
  await page.goto(path)
  return page
}

async function join(browser: Browser, quizId: string, name: string): Promise<Page> {
  const page = await newPage(browser, `/play?quiz=${quizId}`)
  await page.getByLabel('Your name').fill(name)
  await page.getByRole('button', { name: 'Join' }).click()
  await expect(page.getByText('Waiting for the next question')).toBeVisible()
  return page
}

test('a round from import to leaderboard', async ({ browser }) => {
  // Host imports the quiz.
  const host = await newPage(browser, '/host')
  await host.getByLabel('Host key').fill(hostKey())
  await host.getByLabel('Quizzer file').setInputFiles(fixturePath)
  await host.getByRole('button', { name: 'Import' }).click()
  await expect(host.getByTestId('quiz-id')).toHaveText(/^[0-9a-f-]{36}$/)
  const quizId = (await host.getByTestId('quiz-id').textContent())!

  // The big screen shows the join QR code.
  const screen = await newPage(browser, `/screen?quiz=${quizId}`)
  const qr = screen.getByRole('img', { name: 'Scan to join' })
  await expect(qr).toBeVisible()
  expect(await qr.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0)

  const ada = await join(browser, quizId, 'Ada')
  const bob = await join(browser, quizId, '<b>Bob</b>')

  // Question 1: Ada answers correctly, Bob doesn't.
  await host.getByRole('button', { name: 'Next' }).click()
  const question = ada.getByTestId('question')
  await expect(question).not.toBeEmpty()
  const text = (await question.textContent())!
  await expect(bob.getByTestId('question')).toHaveText(text)
  await expect(screen.getByTestId('question')).toHaveText(text)
  const correct = correctAnswers.get(text)!
  expect(correct, `fixture answer for "${text}"`).toBeDefined()

  await ada.getByRole('button', { name: correct, exact: true }).click()
  await bob.locator('[data-testid=options] button').filter({ hasNotText: new RegExp(`^${RegExp.escape(correct)}$`) }).first().click()
  // One tap locks the answer in; wait until both answers are accepted before closing.
  await expect(ada.locator('[data-testid=options] button:enabled')).toHaveCount(0)
  await expect(ada.getByTestId('result')).toHaveText('Answer in')
  await expect(bob.getByTestId('result')).toHaveText('Answer in')

  await host.getByRole('button', { name: 'Close' }).click()
  await expect(ada.getByTestId('result')).toHaveText('Correct')
  await expect(bob.getByTestId('result')).toHaveText('Wrong')
  await expect(host.getByTestId('answered')).toHaveText('2')

  // namesAreEscaped: Bob's name is shown as literal text, not markup.
  const rows = screen.getByTestId('leaderboard').locator('li')
  await expect(rows).toHaveCount(2)
  await expect(rows.nth(0)).toContainText('Ada')
  await expect(rows.nth(1).getByTestId('name')).toHaveText('<b>Bob</b>')
  await expect(screen.getByTestId('leaderboard').locator('b')).toHaveCount(0)
  await expect(ada.getByTestId('rank')).toHaveText('1')

  // A big screen that reloads gets the leaderboard back straight away.
  await screen.reload()
  await expect(rows).toHaveCount(2)

  // reconnectGetsCurrentState, end to end: Ada reloads mid-question 2 and is still in the quiz.
  await host.getByRole('button', { name: 'Next' }).click()
  await expect(bob.getByTestId('question')).not.toHaveText(text)
  const second = (await bob.getByTestId('question').textContent())!
  await ada.reload()
  await expect(ada.getByTestId('question')).toHaveText(second)
  await expect(ada.getByLabel('Your name')).toBeHidden()
  await expect(ada.locator('[data-testid=options] button:enabled')).not.toHaveCount(0)

  await host.getByRole('button', { name: 'End' }).click()
  await expect(screen.getByTestId('final')).toBeVisible()
})
