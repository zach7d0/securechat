/**
 * Regression for "new member: Group keys are not available on this device yet / only part of the history".
 *   cd frontend && npx tsx system/group-late-device.ts
 * Bob's only device is OFFLINE when Alice (inviter) adds him with "Last 1 day". Bob then opens a NEW
 * device. Without any manual refresh it must join the group by itself, be able to send, and receive
 * the history Alice shared (her device re-sends the key in the epoch that added Bob's new device).
 */
import { ApiClient, MemoryTokenStorage } from '../src/lib/api/http'
import { MemoryKeyStore } from '../src/lib/crypto/keystore'
import { uuid } from '../src/lib/crypto/bytes'
import { establishSession, type Session } from '../src/lib/session'

const EDGE = process.env.EDGE_URL ?? 'http://localhost:8100'
const WS = EDGE.replace(/^http/, 'ws') + '/ws'
const RUN = uuid().slice(0, 6)
const MARK = `LATE-${RUN}`
const results: boolean[] = []
const check = (name: string, ok: boolean, detail?: string) => {
  results.push(ok)
  console.log(`${ok ? '  ✔' : '  ✘'} ${name}${detail ? ` — ${detail}` : ''}`)
}
async function until<T>(what: string, fn: () => T | Promise<T>, timeoutMs = 60_000): Promise<T> {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    try {
      const v = await fn()
      if (v) return v
    } catch { /* retry */ }
    await new Promise((r) => setTimeout(r, 1000))
  }
  throw new Error(`timeout waiting for ${what}`)
}
interface Dev { name: string; session: Session }
async function device(name: string, username: string, pw: string): Promise<Dev> {
  const api = new ApiClient(EDGE, new MemoryTokenStorage())
  await api.login(username, pw)
  const store = new MemoryKeyStore()
  const session = await establishSession({ api, openStore: async () => store, wsUrl: WS, deviceName: name,
    log: process.env.VERBOSE === name ? (...z: unknown[]) => console.log(`[${name}]`, ...z.map((q) => q instanceof Error ? q.message : q)) : () => undefined })
  await session.engine.start()
  await until(`${name} online`, () => session.engine.connectionStatus() === 'online')
  return { name, session }
}
const bodies = (d: Dev, c: string) => d.session.engine.messages(c).filter((m) => m.direction !== 'system').map((m) => m.body ?? `<${m.error}>`)

async function main() {
  console.log(`Late new-member device test run=${RUN}`)
  const pw = 'correct horse battery staple'
  const names = ['alice', 'carol', 'bob'].map((n) => `gl_${n}_${RUN}`)
  for (const u of names) await new ApiClient(EDGE, new MemoryTokenStorage()).register(`${u}@example.test`, u, pw, u.toUpperCase())
  const a = await device('alice', names[0], pw)
  const c = await device('carol', names[1], pw)
  const bOld = await device('bob-old', names[2], pw)
  bOld.session.engine.stop()                                   // Bob's only device goes offline

  const g = await a.session.engine.createGroup(`late-${RUN}`, [c.session.user.userId])
  await until('carol joins', async () => { await c.session.engine.refreshConversations(); await c.session.engine.processWelcomes(); return c.session.engine.conversation(g.id)?.groupReady })
  const earlier: string[] = []
  for (let i = 0; i < 6; i++) {
    const t = `earlier ${i} ${MARK}`
    earlier.push(t)
    await (i % 2 === 0 ? a : c).session.engine.send(g.id, t)
    await until(`both have ${i}`, () => bodies(a, g.id).includes(t) && bodies(c, g.id).includes(t))
  }

  const r = await a.session.engine.addMembers(g.id, [bOld.session.user.userId], { shareHistory: '1d' })
  check('Inviter shares immediately with what it has (no waiting for other members)', r.shared === 6 && !r.pendingFromOthers, JSON.stringify(r))

  // Bob opens a NEW device well after being added (no membership event in flight); nobody polls for it
  await new Promise((r) => setTimeout(r, 10_000))
  const bNew = await device('bob-new', names[2], pw)
  await until('bob-new lists the group', () => bNew.session.engine.conversation(g.id))
  await until('bob-new joins by itself', () => bNew.session.engine.conversation(g.id)?.groupReady, 90_000)
    .then(() => check('New device joins the group automatically (no "Group keys are not available")', true))
    .catch((e) => check('New device joins the group automatically (no "Group keys are not available")', false, String(e)))

  const hello = `bob says hi ${MARK}`
  const sent = await bNew.session.engine.send(g.id, hello)
  await until('alice & carol get it', () => bodies(a, g.id).includes(hello) && bodies(c, g.id).includes(hello))
    .then(() => check('The new member can send; everyone receives it', sent.status !== 'failed'))
    .catch((e) => check('The new member can send; everyone receives it', false, `${sent.status} ${sent.error ?? ''} ${e}`))

  await until('history arrives on the new device', () => earlier.every((t) => bodies(bNew, g.id).includes(t)), 90_000)
    .then(() => check('All 6 earlier messages (last 1 day) arrive on the device opened after the invite', true))
    .catch(() => check('All 6 earlier messages (last 1 day) arrive on the device opened after the invite', false,
      `${earlier.filter((t) => bodies(bNew, g.id).includes(t)).length}/6`))
  const shared = bNew.session.engine.messages(g.id).filter((m) => m.body?.startsWith('earlier'))
  check('…labelled shared by the inviter, no duplicates', shared.length === 6 && shared.every((m) => m.sharedBy === a.session.user.userId))
  const junk = bNew.session.engine.messages(g.id).filter((m) => m.direction !== 'system' && m.body === null)
  check('No "Unable to decrypt" / locked bubbles on the new device', junk.length === 0, junk.map((m) => m.error).join('; '))

  const back = `alice replies ${MARK}`
  await a.session.engine.send(g.id, back)
  await until('bob-new receives', () => bodies(bNew, g.id).includes(back))
    .then(() => check('The new member receives new messages', true))
    .catch((e) => check('The new member receives new messages', false, String(e)))

  // the reported sequence: remove + re-add the member quickly (several times), then yet another new browser
  for (let i = 0; i < 2; i++) {
    await a.session.engine.removeMember(g.id, bOld.session.user.userId)
    await a.session.engine.addMembers(g.id, [bOld.session.user.userId], { shareHistory: '30d' })
  }
  bNew.session.engine.stop()
  await new Promise((r) => setTimeout(r, 10_000))
  const b3 = await device('bob-third', names[2], pw)
  await until('bob-third joins by itself', async () => b3.session.engine.conversation(g.id)?.groupReady, 90_000)
    .then(() => check('After remove/re-add cycles, yet another new device joins by itself', true))
    .catch((e) => check('After remove/re-add cycles, yet another new device joins by itself', false, String(e)))
  const hi3 = `third device says hi ${MARK}`
  const s3 = await b3.session.engine.send(g.id, hi3)
  await until('alice gets it', () => bodies(a, g.id).includes(hi3))
    .then(() => check('…and can send', s3.status !== 'failed'))
    .catch(() => check('…and can send', false, `${s3.status} ${s3.error ?? ''}`))

  for (const d of [a, c, b3]) d.session.engine.stop()
  const passed = results.filter(Boolean).length
  console.log(`\n${passed}/${results.length} checks passed`)
  process.exit(passed === results.length ? 0 : 1)
}
main().catch((e) => {
  console.error('LATE DEVICE ERROR', e)
  process.exit(1)
})
