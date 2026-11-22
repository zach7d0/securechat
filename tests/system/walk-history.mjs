import puppeteer from 'puppeteer-core'
const EV = process.argv[2]
const run = Math.random().toString(36).slice(2, 7)
const user = `alice_ht_${run}`
const pw = 'correct horse battery staple'
const browser = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox'] })
const ctxA = await browser.createBrowserContext()
const ctxB = await browser.createBrowserContext()
const A = await ctxA.newPage(); const B = await ctxB.newPage()
for (const p of [A, B]) await p.setViewport({ width: 1280, height: 800 })
const errors = []
for (const [n, p] of [['A', A], ['B', B]]) p.on('pageerror', (e) => errors.push(`${n}: ${e}`))
const text = (p, t, timeout = 60000) => p.waitForFunction((t) => document.body.innerText.includes(t), { timeout }, t)
let ok = 0, fail = 0, codeA, codeB
const step = async (name, fn) => { try { await fn(); ok++; console.log('  ✔', name) } catch (e) { fail++; console.log('  ✘', name, '-', e.message); await A.screenshot({ path: `${EV}/ht-fail-A.png` }); await B.screenshot({ path: `${EV}/ht-fail-B.png` }); throw e } }
const msg = `history check ${run}`
try {
  await step('old device: register and chat with @bob_bot', async () => {
    await A.goto('http://localhost:8100/register', { waitUntil: 'networkidle0' })
    await A.type('input[placeholder="Username"]', user)
    await A.type('input[placeholder="Email"]', `${user}@example.test`)
    await A.type('input[type=password]', pw)
    await A.click('button[type=submit]')
    await text(A, 'Connected')
    await A.click('button[aria-label="New chat"]')
    await A.type('input[placeholder="Search by username"]', 'bob_bot')
    await A.waitForSelector('::-p-text(@bob_bot)', { timeout: 15000 })
    await A.click('::-p-text(@bob_bot)')
    await A.waitForSelector('textarea[placeholder="Message"]')
    await A.type('textarea[placeholder="Message"]', msg)
    await A.keyboard.press('Enter')
    await text(A, `echo: ${msg}`)
  })
  const chatUrl = A.url()
  await step('new device (separate browser profile): older messages are placeholders', async () => {
    await B.goto('http://localhost:8100/login', { waitUntil: 'networkidle0' })
    await B.type('input[autocomplete=username]', user)
    await B.type('input[type=password]', pw)
    await B.click('button[type=submit]')
    await text(B, 'Connected')
    await B.goto(chatUrl, { waitUntil: 'networkidle0' })
    await B.click('::-p-text(Load older messages)')
    await text(B, 'Sent before this device was added')
  })
  await B.screenshot({ path: `${EV}/ht-1-new-device-placeholders.png` })
  await step('new device requests history', async () => {
    await B.goto('http://localhost:8100/security', { waitUntil: 'networkidle0' })
    await B.click('::-p-text(Get history from another device)')
    await text(B, 'Waiting for another of your devices')
  })
  await step('old device shows the banner and the request', async () => {
    await A.goto('http://localhost:8100/', { waitUntil: 'networkidle0' })
    await A.waitForSelector('[data-testid=transfer-banner]', { timeout: 40000 })
    await A.screenshot({ path: `${EV}/ht-2-old-device-banner.png` })
    await A.click('[data-testid=transfer-banner]')
    await text(A, 'asks for your message history')
    await A.click('::-p-text(Continue)')
  })

  await step('both devices display the same comparison code', async () => {
    await A.waitForFunction(() => /\d{5} \d{5} \d{5} \d{5}/.test(document.querySelector('[data-testid=transfer-code]')?.textContent ?? ''), { timeout: 30000 })
    codeA = await A.$eval('[data-testid=transfer-code]', (e) => e.textContent)
    await B.waitForFunction(() => /\d{5} \d{5} \d{5} \d{5}/.test(document.querySelector('[data-testid=transfer-code]')?.textContent ?? ''), { timeout: 40000 })
    codeB = await B.$eval('[data-testid=transfer-code]', (e) => e.textContent)
    if (codeA !== codeB) throw new Error(`${codeA} != ${codeB}`)
  })
  await A.screenshot({ path: `${EV}/ht-3-old-device-code.png` })
  await B.screenshot({ path: `${EV}/ht-4-new-device-code.png` })
  await step('old device sends; new device imports and reads the history', async () => {
    await A.click('::-p-text(Codes match)')
    await text(A, 'Sent ')
    await text(B, 'History imported', 60000).catch(() => undefined)
    await B.goto(chatUrl, { waitUntil: 'networkidle0' })
    await text(B, `echo: ${msg}`)
    await text(B, msg)
  })
  await B.screenshot({ path: `${EV}/ht-5-new-device-imported.png` })
  await step('no page errors', async () => { if (errors.length) throw new Error(errors.join(' | ')) })
} catch {}
console.log(`${ok}/${ok + fail} UI steps passed; codes ${codeA} / ${codeB}`)
await browser.close()
process.exit(fail ? 1 : 0)
