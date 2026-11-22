/**
 * Automatic device-to-device history sync against the RUNNING stack.
 *   cd frontend && npx tsx system/history-transfer.ts
 * Old device has 1:1 + group history. A NEW device signs in: it asks for the last month by itself and the
 * old device answers by itself — no approve call, no code. The archive is HPKE-encrypted and relayed as
 * ciphertext; the old device records a "sent to new device" notice with a revoke action. A second new device
 * that skips the first-start sync gets the history when "Load older" reaches it. Forged requests are
 * declined automatically; other accounts see nothing; revoking from the notice cuts the device off.
 */
import { execFileSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { ApiClient, MemoryTokenStorage } from '../src/lib/api/http'
import { MemoryKeyStore } from '../src/lib/crypto/keystore'
import { toBase64, uuid } from '../src/lib/crypto/bytes'
import { establishSession, type Session } from '../src/lib/session'

const EDGE = process.env.EDGE_URL ?? 'http://localhost:8100'
const WS = EDGE.replace(/^http/, 'ws') + '/ws'
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const RUN = uuid().slice(0, 6)
const MARK = `HISTORY-MARKER-${RUN}`
const results: { check: string; ok: boolean; detail?: string }[] = []
const check = (name: string, ok: boolean, detail?: string) => {
  results.push({ check: name, ok, detail })
  console.log(`${ok ? '  ✔' : '  ✘'} ${name}${detail ? ` — ${detail}` : ''}`)
}
async function until<T>(what: string, fn: () => T | Promise<T>, timeoutMs = 60_000): Promise<T> {
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
const soft = (name: string, p: Promise<unknown>) => p.then(() => check(name, true)).catch((e) => check(name, false, String(e)))
const sh = (cmd: string, args: string[]) =>
  execFileSync(cmd, args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] })
const psql = (sql: string) => sh('docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'user_db', '-tAc', sql]).trim()

interface Dev { name: string; api: ApiClient; session: Session; store: MemoryKeyStore }
async function device(name: string, username: string, password: string, opts: { skipFirstSync?: boolean } = {}): Promise<Dev> {
  const api = new ApiClient(EDGE, new MemoryTokenStorage())
  await api.login(username, password)
  const store = new MemoryKeyStore()
  if (opts.skipFirstSync) await store.put('meta', 'historySync', { at: 'test' })
  const session = await establishSession({ api, openStore: async () => store, wsUrl: WS, deviceName: name, log: () => undefined })
  await session.engine.start()
  await until(`${name} online`, () => session.engine.connectionStatus() === 'online')
  return { name, api, session, store }
}
const bodies = (d: Dev, c: string) => d.session.engine.messages(c).filter((m) => m.direction !== 'system').map((m) => m.body ?? `<${m.error}>`)
const pace = () => new Promise((r) => setTimeout(r, 400))

async function main() {
  console.log(`Automatic history sync system test run=${RUN} edge=${EDGE}`)
  const pw = 'correct horse battery staple'
  const [alice, bob, carol] = [`ht_alice_${RUN}`, `ht_bob_${RUN}`, `ht_carol_${RUN}`]
  for (const u of [alice, bob, carol]) await new ApiClient(EDGE, new MemoryTokenStorage()).register(`${u}@example.test`, u, pw, u.toUpperCase())
  const aOld = await device('alice-old', alice, pw)
  const b = await device('bob', bob, pw)
  const c = await device('carol', carol, pw)
  const bobId = b.session.user.userId

  // history on the old device: 1:1 both directions + a group
  const direct = await aOld.session.engine.openDirect(bobId)
  await until('bob sees direct', async () => { await b.session.engine.refreshConversations(); return b.session.engine.conversation(direct.id) })
  const expected: string[] = []
  for (let i = 0; i < 6; i++) {
    const t = `dm ${i} ${MARK}`
    expected.push(t)
    if (i % 2 === 0) await aOld.session.engine.send(direct.id, t)
    else {
      await until(`bob has ${i - 1}`, () => bodies(b, direct.id).includes(expected[i - 1]))
      await b.session.engine.send(direct.id, t)
    }
    await pace()
  }
  await until('old device has the whole DM', () => expected.every((t) => bodies(aOld, direct.id).includes(t)))
  const grp = await aOld.session.engine.createGroup(`ht-${RUN}`, [bobId, c.session.user.userId])
  await until('bob joins group', async () => {
    await b.session.engine.refreshConversations(); await b.session.engine.processWelcomes(); return b.session.engine.conversation(grp.id)?.groupReady
  })
  const g1 = `group 1 ${MARK}`
  await aOld.session.engine.send(grp.id, g1)
  await until('old device has group msg', () => bodies(aOld, grp.id).includes(g1))
  check('Old device holds 1:1 and group history', true)

  // a forged request (signature by a different key) is declined automatically by the old device
  const aNew = await device('alice-new', alice, pw)
  const forged = await aNew.api.requestTransfer(toBase64(crypto.getRandomValues(new Uint8Array(32))), toBase64(crypto.getRandomValues(new Uint8Array(64))),
    { from: new Date(Date.now() - 86_400_000).toISOString(), to: new Date().toISOString() })
  await soft('Old device automatically declines a request whose key is not signed by the new device',
    until('forged declined', () => psql(`select status from device_transfers where id='${forged.id}'`) === 'DECLINED'))

  // the NEW device: nothing to click — it asked for the last month at first start, the old device answered
  await until('new device lists conversations', async () => { await aNew.session.engine.refreshConversations(); return aNew.session.engine.conversation(direct.id) })
  const auto = psql(`select count(*) from device_transfers where target_device_id='${aNew.session.deviceId}' and window_from is not null and status <> 'DECLINED'`)
  check('New device requested the last month by itself (window bound to the request)', Number(auto) >= 1,
    psql(`select window_to - window_from from device_transfers where target_device_id='${aNew.session.deviceId}' and window_from is not null and status <> 'DECLINED' limit 1`))
  await soft('New device reads the full 1:1 history with no approval step',
    until('auto import', () => expected.every((x) => bodies(aNew, direct.id).includes(x))))
  await soft('…and the group history', until('group import', () => bodies(aNew, grp.id).includes(g1)))
  check('No placeholders or duplicates', bodies(aNew, direct.id).filter((x) => x.startsWith('<')).length === 0
    && new Set(bodies(aNew, direct.id)).size === bodies(aNew, direct.id).length)
  const notice = aOld.session.engine.historySentNotices().find((n) => n.targetDeviceId === aNew.session.deviceId)
  check('Old device shows "history was sent to new device" with the device name', !!notice && notice.messages >= 7,
    notice ? `${notice.messages} messages → ${notice.targetDeviceName}` : 'missing')

  // the server holds only ciphertext and deletes it after import
  const tid = psql(`select id from device_transfers where target_device_id='${aNew.session.deviceId}' and window_from is not null and status <> 'DECLINED' order by created_at limit 1`)
  await soft('Ciphertext is deleted from the server after import', until('deleted', () =>
    psql(`select count(*) from device_transfer_chunks where transfer_id='${tid}'`) === '0' && psql(`select status from device_transfers where id='${tid}'`) === 'COMPLETED'))
  const dbDump = sh('docker', ['compose', 'exec', '-T', 'postgres', 'pg_dump', '-U', 'postgres', '-d', 'user_db', '--data-only'])
  check('pg_dump of user_db contains no plaintext marker', !dbDump.includes(MARK))

  // a device that skipped the first-start sync: "Load older" finds unreadable months and fetches them automatically
  const aThird = await device('alice-third', alice, pw, { skipFirstSync: true })
  await until('third lists conversations', async () => { await aThird.session.engine.refreshConversations(); return aThird.session.engine.conversation(direct.id) })
  const older = await aThird.session.engine.loadOlder(direct.id)
  check('Load older: unreadable pre-device messages are not shown as locked bubbles', older.syncing >= 6
    && bodies(aThird, direct.id).filter((x) => x.startsWith('<')).length === 0, `${older.syncing} to sync`)
  await soft('Load older: those messages arrive automatically from the other device',
    until('third import', () => expected.every((x) => bodies(aThird, direct.id).includes(x))))

  // live messaging still works
  const after = `after sync ${MARK}`
  await b.session.engine.send(direct.id, after)
  await soft('Live messaging continues on all devices', until('all get new msg', () =>
    [aOld, aNew, aThird].every((d) => bodies(d, direct.id).includes(after))))

  // another account sees nothing
  const r2 = await aNew.session.engine.requestHistory()
  check('Another account cannot see or approve the request', !(await c.api.transfers()).some((x) => x.id === r2.id)
    && await c.api.approveTransfer(r2.id).then(() => false, () => true))

  // "Not me — revoke" from the notice cuts the new device off
  await aOld.api.revokeDevice(aThird.session.deviceId)
  await soft('Revoking a device that received history signs it out', until('third revoked', () =>
    aThird.session.engine.connectionStatus() !== 'online'))

  for (const d of [aOld, aNew, aThird, b, c]) d.session.engine.stop()
  const passed = results.filter((r) => r.ok).length
  console.log(`\n${passed}/${results.length} checks passed`)
  mkdirSync(resolve(ROOT, 'tests/system/evidence'), { recursive: true })
  writeFileSync(resolve(ROOT, `tests/system/evidence/history-transfer-${new Date().toISOString().replace(/[:.]/g, '-')}.json`),
    JSON.stringify({ run: RUN, results }, null, 2))
  process.exit(passed === results.length ? 0 : 1)
}
main().catch((e) => {
  console.error('HISTORY TRANSFER ERROR', e)
  process.exit(1)
})
