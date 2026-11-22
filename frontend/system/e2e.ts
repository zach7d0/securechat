/**
 * End-to-end system test against the RUNNING stack (docker compose) through the edge (default
 * http://localhost:8100). Uses the real client engine: Signal + MLS encryption happen here, in this
 * process; the backend must only ever see ciphertext.
 *
 *   cd frontend && npx tsx system/e2e.ts
 *
 * Exit code 0 = every check passed. Writes tests/system/evidence/e2e-<timestamp>.json.
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
const MARK = `PLAINTEXT-MARKER-${RUN}`      // every message body contains this; it must never reach the backend
const results: { check: string; ok: boolean; detail?: string }[] = []

function check(name: string, ok: boolean, detail?: string) {
  results.push({ check: name, ok, detail })
  console.log(`${ok ? '  ✔' : '  ✘'} ${name}${detail ? ` — ${detail}` : ''}`)
}

async function until<T>(what: string, fn: () => T | Promise<T>, timeoutMs = 30_000): Promise<T> {
  const end = Date.now() + timeoutMs
  let last: unknown
  while (Date.now() < end) {
    try {
      const v = await fn()
      if (v) return v
    } catch (e) {
      last = e
    }
    await new Promise((r) => setTimeout(r, 250))
  }
  throw new Error(`timeout waiting for ${what}${last ? `: ${String(last)}` : ''}`)
}

interface Dev {
  name: string
  api: ApiClient
  store: MemoryKeyStore
  session: Session
}

const quiet = () => undefined

async function account(username: string, password: string): Promise<void> {
  const api = new ApiClient(EDGE, new MemoryTokenStorage())
  await api.register(`${username}@example.test`, username, password, username.replace(/_.*/, '').toUpperCase())
}

async function device(name: string, username: string, password: string, store = new MemoryKeyStore()): Promise<Dev> {
  const api = new ApiClient(EDGE, new MemoryTokenStorage())
  await api.login(username, password)
  const session = await establishSession({ api, openStore: async () => store, wsUrl: WS, deviceName: name, log: quiet })
  await session.engine.start()
  await until(`${name} online`, () => session.engine.connectionStatus() === 'online')
  return { name, api, store, session }
}

function bodies(d: Dev, conversationId: string): string[] {
  return d.session.engine.messages(conversationId).filter((m) => m.direction !== 'system').map((m) => m.body ?? `<${m.error}>`)
}

function sh(cmd: string, args: string[]): string {
  return execFileSync(cmd, args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] })
}

async function main() {
  console.log(`SecureChat system test run=${RUN} edge=${EDGE}`)
  const pw = 'correct horse battery staple'
  const [alice, bob, charlie] = [`alice_${RUN}`, `bob_${RUN}`, `charlie_${RUN}`]
  for (const u of [alice, bob, charlie]) await account(u, pw)

  // 1 — separate devices, keys generated locally
  const aLaptop = await device('alice-laptop', alice, pw)
  const aPhone = await device('alice-phone', alice, pw)
  const bLaptop = await device('bob-laptop', bob, pw)
  const cLaptop = await device('charlie-laptop', charlie, pw)
  const ids = await aLaptop.api.identities(aLaptop.session.user.userId)
  check('Alice has two independently keyed devices', ids.length === 2 && ids[0].identityKey !== ids[1].identityKey)
  check('Alice and Bob have separate device ids', aLaptop.session.deviceId !== bLaptop.session.deviceId)

  // 2 — one-to-one E2EE
  const bobId = bLaptop.session.user.userId
  const direct = await aLaptop.session.engine.openDirect(bobId)
  await until('Bob sees the conversation', async () => {
    await bLaptop.session.engine.refreshConversations()
    return bLaptop.session.engine.conversation(direct.id)
  })
  const sent = await aLaptop.session.engine.send(direct.id, `hello bob ${MARK}`)
  check('Alice\'s message is ACCEPTED by the backend', sent.status === 'sent' && !!sent.id, sent.error)
  await until('Bob decrypts', () => bodies(bLaptop, direct.id).includes(`hello bob ${MARK}`))
  check('Bob decrypts Alice\'s message locally', true)
  await until('Alice\'s phone gets a synced copy', () => bodies(aPhone, direct.id).includes(`hello bob ${MARK}`))
  check('Alice\'s other device receives its own synced copy', true)
  await until('delivered tick', () => aLaptop.session.engine.messages(direct.id).find((m) => m.id === sent.id)?.status === 'delivered')
  check('Sender sees DELIVERED after the recipient device ACK', true)
  await bLaptop.session.engine.markRead(direct.id)
  await until('read tick', () => aLaptop.session.engine.messages(direct.id).find((m) => m.id === sent.id)?.status === 'read')
  check('Sender sees READ after the recipient read receipt', true)
  await bLaptop.session.engine.send(direct.id, `hi alice ${MARK}`)
  await until('Alice gets the reply on both devices', () => bodies(aLaptop, direct.id).includes(`hi alice ${MARK}`)
    && bodies(aPhone, direct.id).includes(`hi alice ${MARK}`))
  check('Bob replies; both Alice devices decrypt (Double Ratchet)', true)

  // 3 — group E2EE (MLS) with Alice, Bob, Charlie
  const charlieId = cLaptop.session.user.userId
  const grp = await aLaptop.session.engine.createGroup(`team-${RUN}`, [bobId, charlieId])
  for (const d of [bLaptop, cLaptop, aPhone]) {
    await until(`${d.name} joins the group`, async () => {
      await d.session.engine.refreshConversations()
      await d.session.engine.processWelcomes()
      return d.session.engine.conversation(grp.id)?.groupReady
    }, 45_000)
  }
  // Alice's phone joins via device reconciliation (a member's second device)
  await aLaptop.session.engine.reconcileDevices(grp.id).catch(() => false)
  await aLaptop.session.engine.send(grp.id, `group hello ${MARK}`)
  for (const d of [bLaptop, cLaptop, aPhone]) {
    await until(`${d.name} decrypts group message`, () => bodies(d, grp.id).includes(`group hello ${MARK}`), 45_000)
  }
  check('Alice, Bob and Charlie (and Alice\'s phone) decrypt the group message', true)
  await bLaptop.session.engine.send(grp.id, `bob to group ${MARK}`)
  await until('group reply', () => bodies(cLaptop, grp.id).includes(`bob to group ${MARK}`)
    && bodies(aLaptop, grp.id).includes(`bob to group ${MARK}`), 45_000)
  check('Bob\'s group message decrypts for the other members', true)

  // 4 — remove Charlie → cannot decrypt subsequent messages
  await aLaptop.session.engine.removeMember(grp.id, charlieId)
  const after = `after charlie left ${MARK}`
  await aLaptop.session.engine.send(grp.id, after)
  await until('Bob gets post-removal message', () => bodies(bLaptop, grp.id).includes(after), 45_000)
  await new Promise((r) => setTimeout(r, 4000))
  check('Removed member Charlie does not receive/decrypt messages sent after removal',
    !bodies(cLaptop, grp.id).includes(after) && !(await cLaptop.session.engine.mls.hasGroup(grp.id)))

  // 5 — presence across devices
  const presence = async () => (await bLaptop.api.presence([aLaptop.session.user.userId]))[0]?.status
  check('Alice is ONLINE with two devices', (await presence()) === 'ONLINE')
  aPhone.session.engine.stop()
  await new Promise((r) => setTimeout(r, 2500))
  check('Alice stays ONLINE after closing one device', (await presence()) === 'ONLINE')

  // 6 — offline recipient receives pending messages after reconnecting
  bLaptop.session.engine.stop()
  const offline = [`offline 1 ${MARK}`, `offline 2 ${MARK}`, `offline 3 ${MARK}`]
  for (const t of offline) await aLaptop.session.engine.send(direct.id, t)
  const bAgain = await device('bob-laptop', bob, pw, bLaptop.store)   // same browser storage → same device
  check('Reconnected Bob is the same device (keys persisted)', bAgain.session.deviceId === bLaptop.session.deviceId)
  await until('offline messages delivered after reconnect', () => offline.every((t) => bodies(bAgain, direct.id).includes(t)), 45_000)
  const order = bodies(bAgain, direct.id).filter((b) => b.startsWith('offline'))
  check('Offline messages arrive after reconnect, in order', JSON.stringify(order) === JSON.stringify(offline), order.join(' | '))

  // 7 — duplicate client submission → one logical message
  const dupBody = { clientMessageId: uuid(), protocol: 'MLS' as const, ciphertext: btoa('not-a-real-mls-message'), epoch: -1 }
  let dupOk = false
  try {
    const conv = aLaptop.session.engine.conversation(grp.id)!
    dupBody.epoch = conv.group?.epoch ?? 0
    const r = await aLaptop.api.conversation(grp.id)
    dupBody.epoch = r.group!.epoch
    const a1 = await aLaptop.api.sendMessage(grp.id, dupBody)
    const a2 = await aLaptop.api.sendMessage(grp.id, dupBody)
    dupOk = a1.messageId === a2.messageId && a2.duplicate === true
  } catch (e) {
    check('duplicate submission', false, String(e))
  }
  check('Duplicate client submission returns the same logical message', dupOk)

  // 8 — the backend never saw plaintext
  await new Promise((r) => setTimeout(r, 2000))
  const scans: Record<string, string> = {}
  try {
    scans.cassandra = sh('docker', ['compose', 'exec', '-T', '-e', `CQLPW=${process.env.CASSANDRA_ADMIN_PASSWORD ?? ''}`, 'cassandra', 'bash', '-c',
      'cqlsh -u scadmin -p "$CQLPW" -e "SELECT * FROM securechat_message.messages_by_conversation; SELECT * FROM securechat_message.message_outbox_by_bucket; SELECT * FROM securechat_delivery.pending_messages_by_device; SELECT * FROM securechat_delivery.message_receipts_by_message;" 2>/dev/null || true'])
  } catch { scans.cassandra = '' }
  try {
    scans.postgres = sh('docker', ['compose', 'exec', '-T', 'postgres', 'bash', '-c',
      'for d in auth_db user_db key_db conversation_db; do pg_dump -U postgres --data-only $d; done'])
  } catch { scans.postgres = '' }
  try {
    scans.kafka = sh('docker', ['compose', 'exec', '-T', 'kafka', 'bash', '-c',
      'for t in chat.message.accepted chat.message.delivery.requested chat.message.delivered chat.notification.requested; do /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic $t --from-beginning --timeout-ms 4000 2>/dev/null; done'])
  } catch { scans.kafka = '' }
  try {
    scans.logs = sh('docker', ['compose', 'logs', '--no-color', '--since', '30m'])
  } catch { scans.logs = '' }
  for (const [k, v] of Object.entries(scans)) {
    check(`No plaintext in ${k} (${(v.length / 1024).toFixed(0)} KiB scanned)`, v.length > 0 && !v.includes(MARK) && !v.includes('hello bob'))
  }
  check('Ciphertext was actually stored (scan not vacuous)', scans.cassandra.includes('0x') && scans.kafka.includes('ciphertext'))

  for (const d of [aLaptop, aPhone, bAgain, cLaptop]) d.session.engine.stop()
  const failed = results.filter((r) => !r.ok)
  const evidenceDir = resolve(ROOT, 'tests/system/evidence')
  mkdirSync(evidenceDir, { recursive: true })
  writeFileSync(resolve(evidenceDir, `e2e-${new Date().toISOString().replace(/[:.]/g, '-')}.json`),
    JSON.stringify({ run: RUN, edge: EDGE, at: new Date().toISOString(), results }, null, 2))
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`)
  process.exit(failed.length === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('SYSTEM TEST ERROR', e)
  process.exit(2)
})
