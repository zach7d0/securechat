import puppeteer from 'puppeteer-core'
import { createHmac } from 'node:crypto'
import { readFileSync } from 'node:fs'
const [EV, STATE, USER] = process.argv.slice(2)
const st = JSON.parse(readFileSync(STATE, 'utf8'))
const b32 = (s) => { const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'; let b = 0, v = 0; const o = []; for (const c of s) { v = (v << 5) | A.indexOf(c); b += 5; if (b >= 8) { o.push((v >>> (b - 8)) & 255); b -= 8 } } return Buffer.from(o) }
const totp = () => { const buf = Buffer.alloc(8); buf.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000))); const h = createHmac('sha1', b32(st.secret)).update(buf).digest(); const o = h[19] & 15; return String((h.readUInt32BE(o) & 0x7fffffff) % 1e6).padStart(6, '0') }
const browser = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox'] })
const page = await browser.newPage(); await page.setViewport({ width: 1360, height: 860 })
const errors = []; page.on('pageerror', (e) => errors.push(String(e)))
const text = (t) => page.waitForFunction((t) => document.body.innerText.toLowerCase().includes(t.toLowerCase()), { timeout: 20000 }, t)
const step = async (n, f) => { try { await f(); console.log('  ✔', n) } catch (e) { console.log('  ✘', n, e.message); await page.screenshot({ path: `${EV}/admin-fail.png` }); process.exitCode = 1 } }
await new Promise((r) => setTimeout(r, 31000))  // fresh TOTP step (codes are single-use)
await step('sign in with password, then TOTP code', async () => {
  await page.goto('http://localhost:8101/', { waitUntil: 'networkidle0' })
  await page.type('input[placeholder="Admin username"]', USER); await page.type('input[type=password]', st.password)
  await page.click('form button'); await page.waitForSelector('input[placeholder="6-digit authenticator code"]')
  await page.type('input[placeholder="6-digit authenticator code"]', totp()); await page.click('form button')
  await text('System dashboard'); await text('Registered users')
})
await page.screenshot({ path: `${EV}/admin-1-dashboard.png` })
await step('users list and search', async () => { await page.click('a[href="/users"]'); await page.waitForSelector('input[placeholder^="Search username"]'); await page.type('input[placeholder^="Search username"]', 'trace_'); await page.click('form button'); await text('@trace_') })
await page.screenshot({ path: `${EV}/admin-2-users.png` })
await step('user detail with devices and key status', async () => { await page.click('a[href^="/users/"]'); await text('Registered devices'); await text('prekeys') })
await page.screenshot({ path: `${EV}/admin-3-user.png` })
await step('reports page', async () => { await page.click('a[href="/reports"]'); await text('Failure categories') })
await page.screenshot({ path: `${EV}/admin-4-reports.png` })
await step('health & alerts page', async () => { await page.click('a[href="/health"]'); await text('message-service'); await text('Incidents') })
await page.screenshot({ path: `${EV}/admin-5-health.png` })
await step('audit log page', async () => { await page.click('a[href="/audit"]'); await text('ADMIN_LOGIN') })
await page.screenshot({ path: `${EV}/admin-6-audit.png` })
console.log(errors.length ? `  page errors: ${errors.join(' | ')}` : '  ✔ no page errors')
await browser.close()
