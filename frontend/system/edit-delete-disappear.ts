/**
 * Edit / delete for everyone / delete for me / disappearing messages, against the RUNNING stack.
 *   set -a && . ../.env && set +a && cd frontend && npx tsx system/edit-delete-disappear.ts
 */
import { execFileSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { ApiClient, MemoryTokenStorage } from '../src/lib/api/http'
import { MemoryKeyStore } from '../src/lib/crypto/keystore'
import { uuid } from '../src/lib/crypto/bytes'
import { establishSession, type Session } from '../src/lib/session'

const EDGE = process.env.EDGE_URL ?? 'http://localhost:8100'
const WS = EDGE.replace(/^http/, 'ws') + '/ws'
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const RUN = uuid().slice(0, 6)
const results: boolean[] = []
const check = (name: string, ok: boolean, detail?: string) => {
  results.push(ok)
  console.log(`${ok ? '  ✔' : '  ✘'} ${name}${detail ? ` — ${detail}` : ''}`)
}
async function until<T>(fn: () => T | Promise<T>, ms = 45_000): Promise<T | undefined> {
  const end = Date.now() + ms
  while (Date.now() < end) {
    try { const v = await fn(); if (v) return v } catch { /* retry */ }
    await new Promise((r) => setTimeout(r, 1000))
  }
  return undefined
}
interface Dev { name: string; session: Session; api: ApiClient }
async function device(name: string, user: string, pw: string): Promise<Dev> {
  const api = new ApiClient(EDGE, new MemoryTokenStorage())
  await api.login(user, pw)
  const store = new MemoryKeyStore()
  const session = await establishSession({ api, openStore: async () => store, wsUrl: WS, deviceName: name, log: () => undefined })
  await session.engine.start()
  await until(() => session.engine.connectionStatus() === 'online')
  return { name, session, api }
}
const msg = (d: Dev, c: string, text: string) => d.session.engine.messages(c).find((m) => m.body === text || m.clientMessageId === text)
const byId = (d: Dev, c: string, id: string) => d.session.engine.messages(c).find((m) => m.id === id)
const sys = (d: Dev, c: string) => d.session.engine.messages(c).filter((m) => m.direction === 'system').map((m) => m.body ?? '')
const cql = (q: string) => execFileSync('docker', ['compose', 'exec', '-T', 'cassandra', 'cqlsh', '-u', 'scadmin', '-p',
  process.env.CASSANDRA_ADMIN_PASSWORD ?? '', '-e', q], { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })

async function main() {
  console.log(`Edit / delete / disappearing system test run=${RUN}`)
  const pw = 'correct horse battery staple'
  const names = ['alice', 'bob', 'carol', 'dave'].map((n) => `ed_${n}_${RUN}`)
  for (const u of names) await new ApiClient(EDGE, new MemoryTokenStorage()).register(`${u}@example.test`, u, pw, u.toUpperCase())
  const [a, b, c, d] = [await device('alice', names[0], pw), await device('bob', names[1], pw), await device('carol', names[2], pw),
    await device('dave', names[3], pw)]

  // ---------------- one-to-one
  const direct = await a.session.engine.openDirect(b.session.user.userId)
  await until(async () => { await b.session.engine.refreshConversations(); return b.session.engine.conversation(direct.id) })
  const sent = await a.session.engine.send(direct.id, 'orignal typo')
  await until(() => msg(b, direct.id, 'orignal typo'))
  await a.session.engine.editMessage(direct.id, sent.key, 'original, fixed')
  const edited = await until(() => byId(b, direct.id, sent.id!)?.body === 'original, fixed' && byId(b, direct.id, sent.id!))
  check('Edit within 1 hour shows on the other side, marked "edited"', !!edited?.editedAt)

  check('You cannot edit someone else\'s message', await b.session.engine.editMessage(direct.id, byId(b, direct.id, sent.id!)!.key, 'hack')
    .then(() => false, () => true))
  // a forged edit notice from bob for alice's message is ignored by every device
  await (b.session.engine as unknown as { sendNotice: (c: string, p: unknown) => Promise<void> })
    .sendNotice(direct.id, { v: 1, kind: 'edit', target: sent.id, body: 'FORGED', sentAt: new Date().toISOString(), clientMessageId: uuid() })
  await new Promise((r) => setTimeout(r, 3000))
  check('A forged edit by another user is ignored', byId(a, direct.id, sent.id!)?.body === 'original, fixed'
    && byId(b, direct.id, sent.id!)?.body === 'original, fixed')

  const del = await a.session.engine.send(direct.id, 'delete me please')
  await until(() => msg(b, direct.id, 'delete me please'))
  await a.session.engine.deleteForEveryone(direct.id, del.key)
  check('Delete for everyone → tombstone on the other side', !!(await until(() => byId(b, direct.id, del.id!)?.deleted)))
  const history = await b.api.history(direct.id, undefined, 100)
  check('…and the ciphertext is gone from server history', !history.messages.some((h) => h.messageId === del.id))
  check('Only the sender can delete for everyone', await b.session.engine.deleteForEveryone(direct.id, byId(b, direct.id, sent.id!)!.key)
    .then(() => false, () => true))

  const keep = await a.session.engine.send(direct.id, 'delete just for bob')
  await until(() => msg(b, direct.id, 'delete just for bob'))
  await b.session.engine.deleteForMe(direct.id, msg(b, direct.id, 'delete just for bob')!.key)
  await new Promise((r) => setTimeout(r, 1500))
  check('Delete for me removes it only on my device', !byId(b, direct.id, keep.id!) && !!byId(a, direct.id, keep.id!))

  // ---------------- disappearing
  await a.session.engine.setDisappearing(direct.id, 300)
  check('Timer change is announced to the other person', !!(await until(() =>
    sys(b, direct.id).some((l) => /set disappearing messages to 5 minutes/.test(l)) && b.session.engine.conversation(direct.id)?.disappearingSeconds === 300)))
  const eph = await a.session.engine.send(direct.id, 'this will vanish')
  const got = await until(() => msg(b, direct.id, 'this will vanish'))
  check('A message sent with the timer carries its expiry', !!got?.expiresAt && Math.abs(Date.parse(got.expiresAt) - Date.parse(eph.serverAt!) - 300_000) < 2000)
  const day = eph.serverAt!.slice(0, 10)
  const ttl = Number((cql(`SELECT TTL(ciphertext) FROM securechat_message.messages_by_conversation WHERE conversation_id=${direct.id} AND bucket_date='${day}' AND created_at='${eph.serverAt}' AND message_id=${eph.id};`)
    .match(/^\s*(\d+)\s*$/m) ?? [])[1])
  check('The server copy expires too (Cassandra TTL ≤ 5 min)', ttl > 200 && ttl <= 300, `TTL ${ttl}s`)
  const realNow = Date.now
  Date.now = () => realNow() + 6 * 60_000                       // jump 6 minutes ahead on these devices
  await (b.session.engine as unknown as { sweepExpired: () => Promise<void> }).sweepExpired()
  await (a.session.engine as unknown as { sweepExpired: () => Promise<void> }).sweepExpired()
  Date.now = realNow
  check('After the timer, the message disappears from both devices', !byId(b, direct.id, eph.id!) && !byId(a, direct.id, eph.id!))
  check('…but older messages sent before the timer stay', !!byId(b, direct.id, sent.id!))
  await b.session.engine.setDisappearing(direct.id, 0)
  check('Either person can turn it off', !!(await until(() => a.session.engine.conversation(direct.id)?.disappearingSeconds == null
    && sys(a, direct.id).some((l) => /turned off disappearing messages/.test(l)))))

  // ---------------- group (MLS)
  const g = await a.session.engine.createGroup(`ed-${RUN}`, [b.session.user.userId, c.session.user.userId])
  for (const x of [b, c]) await until(async () => { await x.session.engine.refreshConversations(); await x.session.engine.processWelcomes(); return x.session.engine.conversation(g.id)?.groupReady })
  await c.session.engine.setDisappearing(g.id, 3600)                // a plain member may set it
  await until(() => a.session.engine.conversation(g.id)?.disappearingSeconds === 3600)
  const gm = await c.session.engine.send(g.id, 'group typo mesage')
  await until(() => msg(a, g.id, 'group typo mesage') && msg(b, g.id, 'group typo mesage'))
  await c.session.engine.editMessage(g.id, gm.key, 'group message')
  check('Group: edit reaches every member', !!(await until(() => byId(a, g.id, gm.id!)?.body === 'group message' && byId(b, g.id, gm.id!)?.body === 'group message')))
  await c.session.engine.deleteForEveryone(g.id, gm.key)
  check('Group: delete for everyone reaches every member', !!(await until(() => byId(a, g.id, gm.id!)?.deleted && byId(b, g.id, gm.id!)?.deleted)))
  check('Group: a member set the timer, everyone sees 1 hour', a.session.engine.conversation(g.id)?.disappearingSeconds === 3600
    && sys(b, g.id).some((l) => /set disappearing messages to 1 hour/.test(l)))
  await a.session.engine.addMembers(g.id, [d.session.user.userId])
  await until(async () => { await d.session.engine.refreshConversations(); return d.session.engine.conversation(g.id) })
  check('A member who joins later sees the current timer', d.session.engine.conversation(g.id)?.disappearingSeconds === 3600)

  for (const x of [a, b, c, d]) x.session.engine.stop()
  const passed = results.filter(Boolean).length
  console.log(`\n${passed}/${results.length} checks passed`)
  process.exit(passed === results.length ? 0 : 1)
}
main().catch((e) => { console.error('ERROR', e); process.exit(1) })
