import puppeteer from 'puppeteer-core'
const EV = process.argv[2]
const run = Math.random().toString(36).slice(2, 7)
const user = `alice_ui_${run}`
const browser = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox'] })
const page = await browser.newPage()
await page.setViewport({ width: 1280, height: 800 })
const errors = []
page.on('pageerror', (e) => errors.push(String(e)))
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
const step = async (name, fn) => { try { await fn(); console.log('  ✔', name) } catch (e) { console.log('  ✘', name, '-', e.message); await page.screenshot({ path: `${EV}/ui-fail-${name.replace(/\W+/g, '_')}.png` }); throw e } }
const text = (t) => page.waitForFunction((t) => document.body.innerText.includes(t), { timeout: 45000 }, t)
try {
  await step('register in the browser (keys generated in-browser)', async () => {
    await page.goto('http://localhost:8100/register', { waitUntil: 'networkidle0' })
    await page.type('input[placeholder="Username"]', user)
    await page.type('input[placeholder="Email"]', `${user}@example.test`)
    await page.type('input[placeholder="Display name (optional)"]', 'Alice UI')
    await page.type('input[type=password]', 'correct horse battery staple')
    await page.click('button[type=submit]')
    await text('Connected')
  })
  await page.screenshot({ path: `${EV}/ui-1-home.png` })
  await step('start a direct chat with @bob_bot via user search', async () => {
    await page.click('button[aria-label="New chat"]')
    await page.type('input[placeholder="Search by username"]', 'bob_bot')
    await page.waitForSelector('::-p-text(@bob_bot)', { timeout: 15000 })
    await page.click('::-p-text(@bob_bot)')
    await page.waitForSelector('textarea[placeholder="Message"]')
  })
  await step('send an E2EE message and receive the decrypted echo', async () => {
    await page.type('textarea[placeholder="Message"]', 'hello from the real browser')
    await page.keyboard.press('Enter')
    await text('echo: hello from the real browser')
  })
  await step('read receipt shown (blue double tick)', async () => {
    await page.waitForSelector('[aria-label="read"]', { timeout: 30000 })
  })
  await page.screenshot({ path: `${EV}/ui-2-direct-chat.png` })
  await step('create an MLS group with the bot and exchange messages', async () => {
    await page.click('button[aria-label="New group"]')
    await page.type('input[placeholder="Group name"]', `UI group ${run}`)
    await page.type('input[placeholder="Search by username"]', 'bob_bot')
    await page.waitForSelector('::-p-text(@bob_bot)', { timeout: 15000 })
    await page.click('::-p-text(@bob_bot)')
    await page.click('::-p-text(Create group with 1)')
    await page.waitForSelector('textarea[placeholder="Message"]')
    await text('E2EE · MLS')
    await page.type('textarea[placeholder="Message"]', 'hello group')
    await page.keyboard.press('Enter')
    await text('echo: hello group')
  })
  await page.screenshot({ path: `${EV}/ui-3-group-chat.png` })
  await step('devices & security page lists this device and key inventory', async () => {
    await page.goto('http://localhost:8100/security', { waitUntil: 'networkidle0' })
    await text('THIS DEVICE')
    await text('One-time prekeys')
  })
  await page.screenshot({ path: `${EV}/ui-4-security.png` })
  await step('session survives reload (keys in encrypted IndexedDB)', async () => {
    await page.goto('http://localhost:8100/', { waitUntil: 'networkidle0' })
    await text('Connected')
    await text('UI group')
  })
  const stored = await page.evaluate(async () => {
    const dbs = await indexedDB.databases()
    return dbs.map((d) => d.name)
  })
  console.log('  ✔ IndexedDB key stores:', stored.join(', '))
  console.log(errors.length ? `  console errors: ${errors.slice(0, 5).join(' | ')}` : '  ✔ no console errors / CSP violations')
} finally {
  await browser.close()
}
