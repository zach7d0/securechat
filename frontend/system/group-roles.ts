/**
 * Group roles against the RUNNING stack.   cd frontend && npx tsx system/group-roles.ts
 * Owner invites as ADMIN or MEMBER; an ADMIN can only invite MEMBERs; owner promotes / demotes;
 * the new admin can then invite and edit the group; members can do neither. Messaging still works.
 */
import { ApiClient, MemoryTokenStorage } from '../src/lib/api/http'
import { MemoryKeyStore } from '../src/lib/crypto/keystore'
import { uuid } from '../src/lib/crypto/bytes'
import { establishSession, type Session } from '../src/lib/session'

const EDGE = process.env.EDGE_URL ?? 'http://localhost:8100'
const WS = EDGE.replace(/^http/, 'ws') + '/ws'
const RUN = uuid().slice(0, 6)
const results: boolean[] = []
const check = (name: string, ok: boolean, detail?: string) => {
  results.push(ok)
  console.log(`${ok ? '  ✔' : '  ✘'} ${name}${detail ? ` — ${detail}` : ''}`)
}
async function until<T>(what: string, fn: () => T | Promise<T>, timeoutMs = 45_000): Promise<T> {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    try {
      const v = await fn()
      if (v) return v
    } catch { /* retry */ }
    await new Promise((r) => setTimeout(r, 1000))   // gentle polling: the gateway rate-limits per user
  }
  throw new Error(`timeout waiting for ${what}`)
}
interface Dev { name: string; api: ApiClient; session: Session }
async function device(username: string, pw: string): Promise<Dev> {
  const api = new ApiClient(EDGE, new MemoryTokenStorage())
  await api.login(username, pw)
  const store = new MemoryKeyStore()
  const session = await establishSession({ api, openStore: async () => store, wsUrl: WS, deviceName: username,
    log: process.env.VERBOSE && username.includes(process.env.VERBOSE) ? (...z: unknown[]) => console.log(`[${username}]`, ...z.map((q) => q instanceof Error ? q.message : q)) : () => undefined })
  await session.engine.start()
  await until(`${username} online`, () => session.engine.connectionStatus() === 'online')
  return { name: username, api, session }
}
async function calm<T>(f: () => Promise<T>): Promise<T> {
  for (let i = 0; ; i++) {
    try {
      return await f()
    } catch (e) {
      if ((e as { status?: number }).status !== 429 || i >= 8) throw e
      await new Promise((r) => setTimeout(r, 1000 * (i + 1)))   // gateway rate limit: back off
    }
  }
}
const refused = (f: () => Promise<unknown>, status: number) => calm(f).then(() => false, (e) => (e as { status?: number }).status === status)
const human = () => new Promise((r) => setTimeout(r, 1500))   // admin actions at human speed
const roleOf = (d: Dev, gid: string, u: Dev) => d.session.engine.conversation(gid)?.members.find((m) => m.userId === u.session.user.userId)?.role

async function main() {
  console.log(`Group roles system test run=${RUN}`)
  const pw = 'correct horse battery staple'
  const names = ['owner', 'admin', 'mem', 'x', 'y'].map((n) => `gr_${n}_${RUN}`)
  for (const u of names) await new ApiClient(EDGE, new MemoryTokenStorage()).register(`${u}@example.test`, u, pw, u.toUpperCase())
  const [o, a, m, x, y] = [await device(names[0], pw), await device(names[1], pw), await device(names[2], pw), await device(names[3], pw),
    await device(names[4], pw)]
  const g = await o.session.engine.createGroup(`roles-${RUN}`, [])

  await o.session.engine.addMembers(g.id, [a.session.user.userId], { role: 'ADMIN' })
  await human()
  check('Owner invites as ADMIN', roleOf(o, g.id, a) === 'ADMIN')
  await o.session.engine.addMembers(g.id, [m.session.user.userId], { role: 'MEMBER' })
  await human()
  check('Owner invites as MEMBER', roleOf(o, g.id, m) === 'MEMBER')

  await until('admin joins', async () => { await a.session.engine.refreshConversations(); await a.session.engine.processWelcomes(); return a.session.engine.conversation(g.id)?.groupReady })
  check('The invited admin sees their role', a.session.engine.conversation(g.id)?.myRole === 'ADMIN')
  check('An ADMIN cannot invite an ADMIN (403)', await refused(() => a.session.engine.addMembers(g.id, [x.session.user.userId], { role: 'ADMIN' }), 403))
  await a.session.engine.addMembers(g.id, [x.session.user.userId], { role: 'MEMBER' })
  await human()
  check('An ADMIN can invite a MEMBER', roleOf(a, g.id, x) === 'MEMBER')
  await a.session.engine.updateGroupInfo(g.id, { name: `renamed-${RUN}`, description: 'by admin' })
  check('An ADMIN can edit the group name/description', a.session.engine.conversation(g.id)?.displayName === `renamed-${RUN}`)

  await until('member joins', async () => { await m.session.engine.refreshConversations(); await m.session.engine.processWelcomes(); return m.session.engine.conversation(g.id)?.groupReady })
  check('A MEMBER cannot invite (403)', await refused(() => m.session.engine.addMembers(g.id, [y.session.user.userId]), 403))
  check('A MEMBER cannot edit the group (403)', await refused(() => m.session.engine.updateGroupInfo(g.id, { name: 'nope' }), 403))
  check('An ADMIN cannot promote (403)', await refused(() => a.session.engine.setMemberRole(g.id, m.session.user.userId, 'ADMIN'), 403))

  await o.session.engine.setMemberRole(g.id, m.session.user.userId, 'ADMIN')
  await human()
  check('Owner promotes MEMBER → ADMIN', roleOf(o, g.id, m) === 'ADMIN')
  await calm(() => m.session.engine.refreshConversations())
  await calm(() => m.session.engine.addMembers(g.id, [y.session.user.userId]))
  await human()
  check('The promoted admin can now invite', roleOf(m, g.id, y) === 'MEMBER')
  await calm(() => o.session.engine.setMemberRole(g.id, m.session.user.userId, 'MEMBER'))
  await human()
  check('Owner demotes ADMIN → MEMBER', roleOf(o, g.id, m) === 'MEMBER')

  for (const d of [x, y]) {
    await until(`${d.name} joins`, async () => {
      await calm(() => d.session.engine.refreshConversations()); await calm(() => d.session.engine.processWelcomes())
      return d.session.engine.conversation(g.id)?.groupReady
    })
  }
  const t = `hello ${RUN}`
  await o.session.engine.send(g.id, t)
  await until('everyone receives', () => [a, m, x].every((d) => d.session.engine.messages(g.id).some((z) => z.body === t)))
    .then(() => check('Group messaging still works for all members', true))
    .catch((e) => check('Group messaging still works for all members', false, String(e) + ' ' + JSON.stringify([o, a, m, x].map((d) => ({
      who: d.name, ready: d.session.engine.conversation(g.id)?.groupReady,
      last: d.session.engine.messages(g.id).slice(-3).map((z) => `${z.body ?? `<${z.error}>`} [${z.status}${z.error ? ': ' + z.error : ''}]`) })))))

  for (const d of [o, a, m, x, y]) d.session.engine.stop()
  const passed = results.filter(Boolean).length
  console.log(`\n${passed}/${results.length} checks passed`)
  process.exit(passed === results.length ? 0 : 1)
}
main().catch((e) => {
  console.error('GROUP ROLES ERROR', e)
  process.exit(1)
})
