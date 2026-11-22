/**
 * Backup/restore drill client. Keys live in JSON files (like a browser profile on disk), so the same
 * devices can be resumed after the server state was wiped and restored.
 *   npx tsx system/drill.ts before <stateDir>   — Alice sends 2 messages while Bob is offline
 *   npx tsx system/drill.ts after  <stateDir>   — after restore: logins work, Bob receives + decrypts
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { ApiClient, MemoryTokenStorage } from '../src/lib/api/http'
import { uuid } from '../src/lib/crypto/bytes'
import type { KeyStore } from '../src/lib/crypto/keystore'
import { deserialize, serialize } from '../src/lib/crypto/serializer'
import { establishSession } from '../src/lib/session'

const EDGE = process.env.EDGE_URL ?? 'http://localhost:8100'
const [mode, dir] = process.argv.slice(2)
mkdirSync(dir, { recursive: true })

class FileKeyStore implements KeyStore {
  private data: Record<string, string>
  constructor(private readonly file: string) {
    this.data = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : {}
  }
  private flush() { writeFileSync(this.file, JSON.stringify(this.data), { mode: 0o600 }) }
  async get<T>(s: string, k: string) { const v = this.data[`${s}/${k}`]; return v === undefined ? undefined : deserialize<T>(v) }
  async put(s: string, k: string, v: unknown) { this.data[`${s}/${k}`] = serialize(v); this.flush() }
  async delete(s: string, k: string) { delete this.data[`${s}/${k}`]; this.flush() }
  async keys(s: string) { return Object.keys(this.data).filter((k) => k.startsWith(`${s}/`)).map((k) => k.slice(s.length + 1)) }
  async destroy() { this.data = {}; this.flush() }
}

async function open(name: string, username: string, register: boolean) {
  const api = new ApiClient(EDGE, new MemoryTokenStorage())
  const pw = 'correct horse battery staple'
  if (register) await api.register(`${username}@example.test`, username, pw, name)
  else await api.login(username, pw)
  const s = await establishSession({ api, openStore: async () => new FileKeyStore(resolve(dir, `${name}.keys.json`)),
    wsUrl: EDGE.replace(/^http/, 'ws') + '/ws', deviceName: name, log: () => undefined })
  await s.engine.start()
  Object.assign(s, { api })
  for (let i = 0; i < 80 && s.engine.connectionStatus() !== 'online'; i++) await new Promise((r) => setTimeout(r, 250))
  return s
}

async function main() {
  const statePath = resolve(dir, 'state.json')
  if (mode === 'before') {
    const run = uuid().slice(0, 6)
    const users = { alice: `drill_a_${run}`, bob: `drill_b_${run}` }
    const bob = await open('bob', users.bob, true)
    bob.engine.stop()                                     // Bob goes offline (device keys persisted on "disk")
    const alice = await open('alice', users.alice, true)
    const conv = await alice.engine.openDirect(bob.user.userId)
    const texts = [`before-backup-1-${run}`, `before-backup-2-${run}`]
    for (const t of texts) {
      const m = await alice.engine.send(conv.id, t)
      if (m.status !== 'sent') throw new Error(`send failed: ${m.error}`)
    }
    await new Promise((r) => setTimeout(r, 4000))        // let fanout/delivery persist the pending rows
    alice.engine.stop()
    writeFileSync(statePath, JSON.stringify({ users, conversationId: conv.id, texts, bobDevice: bob.deviceId }))
    console.log(`drill before: Alice sent ${texts.length} messages to offline Bob (conversation ${conv.id})`)
    process.exit(0)
  }
  const st = JSON.parse(readFileSync(statePath, 'utf8'))
  const ok: string[] = []
  const fail: string[] = []
  const check = (n: string, c: boolean) => (c ? ok : fail).push(n)
  const bob = await open('bob', st.users.bob, false)
  check('Bob logs in after restore (auth_db restored)', true)
  check('Bob resumes the SAME device (user_db + key_db restored)', bob.deviceId === st.bobDevice)
  for (let i = 0; i < 120; i++) {
    if (st.texts.every((t: string) => bob.engine.messages(st.conversationId).some((m) => m.body === t))) break
    await new Promise((r) => setTimeout(r, 500))
  }
  const got = bob.engine.messages(st.conversationId).map((m) => m.body)
  check('Pending deliveries from before the wipe are delivered and decrypted (Cassandra restored)',
    st.texts.every((t: string) => got.includes(t)))
  const alice = await open('alice', st.users.alice, false)
  const aliceApi = (alice as unknown as { api: ApiClient }).api
  const hist = await aliceApi.history(st.conversationId)
  check('Message history survived (messages_by_conversation restored)', hist.messages.length >= st.texts.length)
  check('Conversation membership survived (conversation_db restored)', !!(await aliceApi.conversation(st.conversationId)))
  bob.engine.stop()
  alice.engine.stop()
  for (const n of ok) console.log(`  ✔ ${n}`)
  for (const n of fail) console.log(`  ✘ ${n}`)
  console.log(`${ok.length}/${ok.length + fail.length} restore checks passed`)
  process.exit(fail.length ? 1 : 0)
}
main().catch((e) => { console.error('DRILL ERROR', e); process.exit(2) })
