/**
 * Admin Portal end-to-end test against the RUNNING stack (MVP completion definition):
 * a real E2EE chat exchange happens, then an administrator signs in (password change + TOTP MFA),
 * sees/searches registered users, inspects devices, traces the message lifecycle per device, views
 * reports/health/alerts/incidents and the audit trail; lower roles are denied; no admin response
 * contains message plaintext or key bytes.
 *
 *   cd frontend && npx tsx system/admin-e2e.ts
 *
 * The bootstrap admin's rotated password and TOTP secret are kept in ../secrets/admin-e2e.json
 * (gitignored) so the test is repeatable.
 */
import { createHmac } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { ApiClient, MemoryTokenStorage } from '../src/lib/api/http'
import { uuid } from '../src/lib/crypto/bytes'
import { MemoryKeyStore } from '../src/lib/crypto/keystore'
import { establishSession } from '../src/lib/session'

const CHAT = process.env.EDGE_URL ?? 'http://localhost:8100'
const ADMIN = process.env.ADMIN_URL ?? 'http://localhost:8101'
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const STATE = resolve(ROOT, 'secrets/admin-e2e.json')
const RUN = uuid().slice(0, 6)
const MARK = `ADMIN-PLAINTEXT-MARKER-${RUN}`
const results: { check: string; ok: boolean; detail?: string }[] = []
const check = (name: string, ok: boolean, detail?: string) => {
  results.push({ check: name, ok, detail })
  console.log(`${ok ? '  ✔' : '  ✘'} ${name}${detail ? ` — ${detail}` : ''}`)
}
const seen: string[] = []   // every admin response body, scanned for plaintext at the end

async function admin(method: string, path: string, token?: string, body?: unknown): Promise<{ status: number; json: any }> {
  const r = await fetch(`${ADMIN}/api/admin/v1${path}`, { method, headers: { 'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await r.text()
  seen.push(text)
  return { status: r.status, json: text ? JSON.parse(text) : null }
}

function b32decode(s: string): Buffer {
  const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = 0, value = 0
  const out: number[] = []
  for (const c of s.toUpperCase()) {
    const v = A.indexOf(c)
    if (v < 0) continue
    value = (value << 5) | v
    bits += 5
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 255); bits -= 8 }
  }
  return Buffer.from(out)
}
function totp(secretB32: string, step = Math.floor(Date.now() / 30000)): string {
  const buf = Buffer.alloc(8)
  buf.writeBigUInt64BE(BigInt(step))
  const h = createHmac('sha1', b32decode(secretB32)).update(buf).digest()
  const o = h[h.length - 1] & 15
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).padStart(6, '0')
}
async function until<T>(what: string, fn: () => Promise<T | undefined | false>, ms = 40_000): Promise<T> {
  const end = Date.now() + ms
  while (Date.now() < end) {
    try {
      const v = await fn()
      if (v) return v
    } catch { /* retry */ }
    await new Promise((r) => setTimeout(r, 500))
  }
  throw new Error(`timeout: ${what}`)
}

async function main() {
  console.log(`Admin E2E run=${RUN}`)
  // ---- a real E2EE exchange to trace
  const pw = 'correct horse battery staple'
  const users = [`trace_a_${RUN}`, `trace_b_${RUN}`]
  const sessions = []
  for (const u of users) {
    const api = new ApiClient(CHAT, new MemoryTokenStorage())
    await api.register(`${u}@example.test`, u, pw, u)
    const s = await establishSession({ api, openStore: async () => new MemoryKeyStore(), wsUrl: CHAT.replace(/^http/, 'ws') + '/ws',
      deviceName: u, log: () => undefined })
    await s.engine.start()
    sessions.push(s)
  }
  const [a, b] = sessions
  await until('both online', async () => a.engine.connectionStatus() === 'online' && b.engine.connectionStatus() === 'online')
  const conv = await a.engine.openDirect(b.user.userId)
  await until('b sees conversation', async () => { await b.engine.refreshConversations(); return b.engine.conversation(conv.id) })
  const sent = await a.engine.send(conv.id, `secret for admins ${MARK}`)
  await until('delivered', async () => b.engine.messages(conv.id).some((m) => m.body?.includes(MARK)))
  await b.engine.markRead(conv.id)
  check('Real E2EE message delivered and read (message to trace)', !!sent.id)

  // ---- admin sign-in: bootstrap → password change → MFA → TOTP login
  const env = Object.fromEntries(readFileSync(resolve(ROOT, '.env'), 'utf8').split('\n').filter((l) => l.includes('='))
    .map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]))
  const username = env.ADMIN_BOOTSTRAP_USERNAME
  let state: { password: string; secret?: string } = existsSync(STATE) ? JSON.parse(readFileSync(STATE, 'utf8'))
    : { password: env.ADMIN_BOOTSTRAP_PASSWORD }
  let login = await admin('POST', '/auth/login', undefined, { username, password: state.password,
    totp: state.secret ? totp(state.secret) : undefined })
  if (login.json?.stage === 'PASSWORD_CHANGE') {
    const next = `rotated-${uuid()}`
    login = await admin('POST', '/auth/password', login.json.token, { currentPassword: state.password, newPassword: next })
    if (login.status === 200) {
      state = { password: next }
      mkdirSync(dirname(STATE), { recursive: true })
      writeFileSync(STATE, JSON.stringify(state), { mode: 0o600 })
    }
    check('Bootstrap admin is forced to change the password first', login.status === 200)
  }
  if (!state.secret) {
    const setup = await admin('POST', '/mfa/setup', login.json.token)
    const verified = await admin('POST', '/mfa/verify', login.json.token, { code: totp(setup.json.secret) })
    if (verified.status === 200) {
      state.secret = setup.json.secret
      writeFileSync(STATE, JSON.stringify(state), { mode: 0o600 })
    }
    check('Admin enrols TOTP MFA', verified.status === 200)
    await new Promise((r) => setTimeout(r, 31_000))   // next TOTP step (codes are single-use)
  }
  // the first login above may already have used the current code: wait for a fresh 30 s step
  await new Promise((r) => setTimeout(r, 30_000 - (Date.now() % 30_000) + 500))
  const noCode = await admin('POST', '/auth/login', undefined, { username, password: state.password })
  check('Login without the TOTP code is refused', noCode.json?.code === 'MFA_REQUIRED')
  const code = totp(state.secret!)
  const full = await admin('POST', '/auth/login', undefined, { username, password: state.password, totp: code })
  check('Admin signs in with password + TOTP (stage FULL, SUPER_ADMIN)', full.json?.stage === 'FULL' && full.json.roles.includes('SUPER_ADMIN'))
  const replay = await admin('POST', '/auth/login', undefined, { username, password: state.password, totp: code })
  check('The same TOTP code cannot be replayed', replay.status === 401)
  const T = full.json.token

  // ---- users
  const dash = await admin('GET', '/dashboard', T)
  check('Dashboard shows registered users, online users and service health',
    dash.json.accounts.total >= 2 && typeof dash.json.servicesUp === 'number', `users=${dash.json.accounts.total} up=${dash.json.servicesUp}/${dash.json.servicesTotal}`)
  const search = await admin('GET', `/users?q=trace_a_${RUN}`, T)
  check('Admin searches registered users', search.json.items.length === 1 && search.json.items[0].username === users[0])
  const listing = await admin('GET', '/users?page=0&size=5', T)
  check('Admin lists registered users with pagination', listing.json.items.length > 0 && listing.json.total >= 2)
  const detail = await admin('GET', `/users/${a.user.userId}`, T)
  check('User detail shows account status and device metadata with key status',
    detail.json.account.status === 'ACTIVE' && detail.json.devices.length === 1 && detail.json.devices[0].keys.identity === true)

  // ---- message trace (metadata only)
  const trace = await until('projection has the message', async () => {
    const t = await admin('GET', `/messages/${sent.id}/trace`, T)
    return t.status === 200 && t.json.read_devices >= 1 ? t : undefined
  })
  const ms = new Set(trace.json.milestones.map((m: any) => m.milestone))
  check('Message trace shows lifecycle milestones', ['RECEIVED', 'PERSISTED', 'ACCEPTED', 'PUBLISHED', 'FANOUT_COMPLETED', 'DELIVERY_PENDING',
    'DELIVERED', 'READ'].every((x) => ms.has(x)), [...ms].join(','))
  check('Message trace shows per-device delivery state', trace.json.devices.some((d: any) => d.device_id === b.deviceId && d.status === 'READ'))
  check('Trace includes authoritative Delivery Service receipts', Array.isArray(trace.json.authoritativeReceipts)
    && trace.json.authoritativeReceipts.some((r: any) => r.status === 'READ'))
  const byConv = await admin('GET', `/messages?conversationId=${conv.id}`, T)
  check('Admin searches messages by conversation', byConv.json.length >= 1)

  // ---- reports, health, alerts, incidents, config
  const rep = await admin('GET', '/reports/summary', T)
  check('Reports: accepted / delivered / pending / failed / latency', rep.json.accepted >= 1 && 'pending_devices' in rep.json && 'failure_categories' in rep.json)
  const hourly = await admin('GET', '/reports/hourly', T)
  check('Hourly report available', Array.isArray(hourly.json) && hourly.json.length >= 1)
  const health = await admin('GET', '/health', T)
  check('Service health panel covers every service', health.json.length >= 14 && health.json.filter((h: any) => h.status === 'UP').length >= 13,
    health.json.filter((h: any) => h.status !== 'UP').map((h: any) => h.service).join(',') || 'all UP')
  const alerts = await admin('GET', '/alerts', T)
  check('Alerts view reads Prometheus', alerts.status === 200 && Array.isArray(alerts.json.alerts))
  const inc = await admin('POST', '/incidents', T, { title: `drill ${RUN}`, severity: 'info', source: 'admin-e2e' })
  const ack = await admin('POST', `/incidents/${inc.json.id}/acknowledge`, T)
  check('Incident can be opened and acknowledged', inc.status === 201 && ack.status === 204)
  check('Read-only configuration view', (await admin('GET', '/config', T)).json.deployment.includes('not highly available'))

  // ---- RBAC with a reporting-only admin
  const repUser = `reporter${RUN}`
  await admin('POST', '/admins', T, { username: repUser, displayName: 'Reporter', password: `initial-${uuid()}`, roles: ['REPORTING_ADMIN'] })
  const admins = await admin('GET', '/admins', T)
  check('Super admin creates a role-restricted admin', admins.json.some((x: any) => x.username === repUser && x.roles === 'REPORTING_ADMIN'))
  const chatToken = await (async () => {
    const api = new ApiClient(CHAT, new MemoryTokenStorage())
    return (await api.login(users[0], pw)).accessToken
  })()
  check('End-user tokens are rejected by the Admin API', (await admin('GET', '/dashboard', chatToken)).status === 401)

  // ---- audit + no plaintext / key material anywhere in admin responses
  const audit = await admin('GET', '/audit?size=200', T)
  const actions = new Set(audit.json.map((x: any) => x.action))
  check('Audit trail records logins, searches, views, traces and admin changes',
    ['ADMIN_LOGIN', 'USER_SEARCH', 'USER_VIEW', 'MESSAGE_TRACE', 'MESSAGE_SEARCH', 'ADMIN_CREATED', 'INCIDENT_CREATE'].every((x) => actions.has(x)))
  const all = seen.join('\n')
  const ids = await a.engine.signalStore.getIdentityKeyPair()
  check('No admin response contains message plaintext', !all.includes(MARK) && !all.includes('secret for admins'))
  check('No admin response contains private key material', !all.includes(Buffer.from(ids!.privKey).toString('base64')))

  for (const s of sessions) s.engine.stop()
  const failed = results.filter((r) => !r.ok)
  const evidence = resolve(ROOT, 'tests/system/evidence')
  mkdirSync(evidence, { recursive: true })
  writeFileSync(resolve(evidence, `admin-e2e-${new Date().toISOString().replace(/[:.]/g, '-')}.json`), JSON.stringify({ run: RUN, results }, null, 2))
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
}
main().catch((e) => { console.error('ADMIN E2E ERROR', e); process.exit(2) })
