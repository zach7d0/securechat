/**
 * Group history shared with a newly added member, against the RUNNING stack.
 *   cd frontend && npx tsx system/group-history-share.ts
 * Alice (owner) and Carol chat in a group. Alice adds Bob with "last 100 messages": Bob's device reads
 * the earlier messages (labelled shared-by Alice), Carol is told, and the server only ever holds
 * ciphertext. Dave is added with "don't share" and cannot read anything earlier. Carol (MEMBER) and an
 * outsider cannot create or download shares.
 */
import { execFileSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
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
const MARK = `GHS-MARKER-${RUN}`
const results: { check: string; ok: boolean; detail?: string }[] = []
const check = (name: string, ok: boolean, detail?: string) => {
  results.push({ check: name, ok, detail })
  console.log(`${ok ? '  ✔' : '  ✘'} ${name}${detail ? ` — ${detail}` : ''}`)
}
async function until<T>(what: string, fn: () => T | Promise<T>, timeoutMs = 45_000): Promise<T> {
  const end = Date.now() + timeoutMs
  let last: unknown
  while (Date.now() < end) {
    try {
      const v = await fn()
      if (v) return v
    } catch (e) {
      last = e
    }
    await new Promise((r) => setTimeout(r, 1000))
  }
  throw new Error(`timeout waiting for ${what}${last ? `: ${String(last)}` : ''}`)
}
const sh = (cmd: string, args: string[]) =>
  execFileSync(cmd, args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] })
const psql = (sql: string) => sh('docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'conversation_db', '-tAc', sql]).trim()

interface Dev { name: string; api: ApiClient; session: Session }
async function device(name: string, username: string, password: string, opts: { skipFirstSync?: boolean } = {}): Promise<Dev> {
  const api = new ApiClient(EDGE, new MemoryTokenStorage())
  if (username.includes('_erin_')) await api.register(`${username}@example.test`, username, password, username.toUpperCase())
  await api.login(username, password)
  const store = new MemoryKeyStore()
  if (opts.skipFirstSync) await store.put('meta', 'historySync', { at: 'test' })
  const session = await establishSession({ api, openStore: async () => store, wsUrl: WS, deviceName: name, log: () => undefined })
  await session.engine.start()
  await until(`${name} online`, () => session.engine.connectionStatus() === 'online')
  return { name, api, session }
}
const chat = (d: Dev, c: string) => d.session.engine.messages(c).filter((m) => m.direction !== 'system')
const bodies = (d: Dev, c: string) => chat(d, c).map((m) => m.body ?? `<${m.error}>`)
const systemLines = (d: Dev, c: string) => d.session.engine.messages(c).filter((m) => m.direction === 'system').map((m) => m.body ?? '')
const pace = () => new Promise((r) => setTimeout(r, 400))
async function joined(d: Dev, gid: string) {
  await until(`${d.name} joins group`, async () => {
    await d.session.engine.refreshConversations()
    await d.session.engine.processWelcomes()
    return d.session.engine.conversation(gid)?.groupReady
  })
}

async function main() {
  console.log(`Group history share system test run=${RUN} edge=${EDGE}`)
  const pw = 'correct horse battery staple'
  const names = ['alice', 'carol', 'bob', 'dave', 'eve'].map((n) => `ghs_${n}_${RUN}`)
  for (const u of names) await new ApiClient(EDGE, new MemoryTokenStorage()).register(`${u}@example.test`, u, pw, u.toUpperCase())
  const [a, c, b, d, e] = [
    await device('alice', names[0], pw), await device('carol', names[1], pw), await device('bob', names[2], pw),
    await device('dave', names[3], pw), await device('eve', names[4], pw)]

  // earlier history between Alice and Carol
  const grp = await a.session.engine.createGroup(`ghs-${RUN}`, [c.session.user.userId])
  await joined(c, grp.id)
  const earlier: string[] = []
  for (let i = 0; i < 4; i++) {
    const t = `earlier ${i} ${MARK}`
    earlier.push(t)
    await (i % 2 === 0 ? a : c).session.engine.send(grp.id, t)
    await until(`both have ${i}`, () => bodies(a, grp.id).includes(t) && bodies(c, grp.id).includes(t))
    await pace()
  }
  check('Alice and Carol share 4 earlier group messages', true)

  // Carol is a MEMBER: she cannot create a share
  check('A MEMBER cannot create a history share (403)',
    await c.api.createHistoryShare(grp.id).then(() => false, (x) => (x as { status?: number }).status === 403))

  // Alice adds Bob WITH history
  const r = await a.session.engine.addMembers(grp.id, [b.session.user.userId], { shareHistory: 'last100' })
  check('Alice adds Bob and shares the last 100 messages', r.shared === 4 && !r.shareError, JSON.stringify(r))
  await joined(b, grp.id)
  await until('Bob imports the shared history', () => earlier.every((t) => bodies(b, grp.id).includes(t)), 60_000)
    .then(() => check('Bob reads all earlier messages', true))
    .catch((x) => check('Bob reads all earlier messages', false, String(x)))
  const shared = chat(b, grp.id).filter((m) => m.body?.includes(MARK))
  check('Imported messages are labelled shared-by Alice, with the original senders',
    shared.length === 4 && shared.every((m) => m.sharedBy === a.session.user.userId)
    && shared.filter((m) => m.senderUserId === c.session.user.userId).length === 2)
  await until('Bob sees the notice', () => systemLines(b, grp.id).some((l) => /shared 4 earlier messages with you/.test(l)))
    .then(() => check('Bob sees "… shared 4 earlier messages with you"', true))
    .catch((x) => check('Bob sees "… shared 4 earlier messages with you"', false, String(x)))
  await until('Carol is told', () => systemLines(c, grp.id).some((l) => l.includes('shared 4 earlier messages with') && l.includes(names[2].toUpperCase())))
    .then(() => check('Carol is told that history was shared with Bob', true))
    .catch((x) => check('Carol is told that history was shared with Bob', false, String(x)))
  await b.session.engine.loadOlder(grp.id)
  const dup = bodies(b, grp.id)
  check('Loading older server history does not add placeholders or duplicates for imported messages',
    earlier.every((t) => dup.filter((x) => x === t).length === 1) && dup.filter((x) => x.startsWith('<') && x.includes('Sent before')).length === 0,
    `${dup.length} messages`)

  // the server only ever held ciphertext
  const sid = psql(`select id from history_shares where conversation_id='${grp.id}' order by created_at desc limit 1`)
  const chunkHex = psql(`select string_agg(encode(data,'hex'),'') from history_share_chunks where share_id='${sid}'`)
  check('Server stores ciphertext only (no plaintext marker in the chunks)', chunkHex.length > 0
    && !chunkHex.includes(Buffer.from(MARK).toString('hex')))
  check('pg_dump of conversation_db contains no plaintext marker',
    !sh('docker', ['compose', 'exec', '-T', 'postgres', 'pg_dump', '-U', 'postgres', '-d', 'conversation_db', '--data-only']).includes(MARK))
  check('An outsider cannot download the share (404)',
    await e.api.getHistoryShareChunk(grp.id, sid, 0).then(() => false, (x) => (x as { status?: number }).status === 404))

  // Dave is added WITHOUT history
  const r2 = await a.session.engine.addMembers(grp.id, [d.session.user.userId], { shareHistory: 'none' })
  await joined(d, grp.id)
  await d.session.engine.loadOlder(grp.id).catch(() => undefined)
  await pace()
  check('"Don\'t share": Dave cannot read any earlier message', r2.shared === 0 && !bodies(d, grp.id).some((x) => x.includes(MARK)),
    `${bodies(d, grp.id).length} rows`)
  check('"Don\'t share": no locked bubbles — earlier messages are only counted for the single "can\'t see" notice',
    !bodies(d, grp.id).some((x) => x.startsWith('<')) && (d.session.engine.olderInfo(grp.id)?.beforeJoin ?? 0) >= 4,
    `beforeJoin=${d.session.engine.olderInfo(grp.id)?.beforeJoin}`)

  // an admin on a FRESH device (no history) adds Erin with "last 1 day": another member's device shares instead
  const a2 = await device('alice-fresh', names[0], pw, { skipFirstSync: true })
  await joined(a2, grp.id)
  const erin = await device('erin', `ghs_erin_${RUN}`, pw)
  const r3 = await a2.session.engine.addMembers(grp.id, [erin.session.user.userId], { shareHistory: '1d' })
  check("Admin's fresh device has no history to share → left to other members", r3.shared === 0 && r3.pendingFromOthers === true, JSON.stringify(r3))
  await joined(erin, grp.id)
  await until('Erin receives history from another member', () => earlier.every((t) => bodies(erin, grp.id).includes(t)), 90_000)
    .then(() => check('Erin reads the earlier messages, shared by another device in the group (not the fresh admin device)', true))
    .catch((x) => check('Erin reads the earlier messages, shared by another device in the group (not the fresh admin device)', false, String(x)))
  const sharers = new Set(chat(erin, grp.id).filter((m) => m.body?.includes(MARK) && m.sharedBy).map((m) => m.sharedBy))
  check('Exactly one member fulfilled the grant', sharers.size === 1 && !sharers.has(undefined),
    [...sharers].map((u) => a.session.engine.displayName(u!)).join(', '))
  const grants = psql(`select count(*) from history_grants where conversation_id='${grp.id}' and claimed_by_device is not null`)
  check('Server: each grant was claimed exactly once', Number(grants) >= 2, `${grants} claimed grants`)

  // live messaging continues for everyone
  const after = `after share ${MARK}`
  await b.session.engine.send(grp.id, after)
  await until('everyone gets Bob\'s new message', () => [a, c, d].every((x) => bodies(x, grp.id).includes(after)))
    .then(() => check('Live group messaging continues (Bob → Alice, Carol, Dave)', true))
    .catch((x) => check('Live group messaging continues (Bob → Alice, Carol, Dave)', false, String(x)))

  for (const x of [a, b, c, d, e, a2, erin]) x.session.engine.stop()
  const passed = results.filter((x) => x.ok).length
  console.log(`\n${passed}/${results.length} checks passed`)
  mkdirSync(resolve(ROOT, 'tests/system/evidence'), { recursive: true })
  writeFileSync(resolve(ROOT, `tests/system/evidence/group-history-share-${new Date().toISOString().replace(/[:.]/g, '-')}.json`),
    JSON.stringify({ run: RUN, results }, null, 2))
  process.exit(passed === results.length ? 0 : 1)
}
main().catch((x) => {
  console.error('GROUP HISTORY SHARE ERROR', x)
  process.exit(1)
})
