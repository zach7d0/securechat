/**
 * MVP failure-injection matrix (SECURECHAT_ARCHITECTURE.md §13, prompts Phase 21) against the RUNNING
 * stack. Real clients + real `docker compose stop/kill/restart`. For every scenario: no message is
 * reported ACCEPTED without durable persistence, every ACCEPTED message is eventually delivered
 * exactly once (logically) to every recipient device, and the system recovers without manual repair.
 *
 *   cd frontend && npx tsx system/failure.ts [scenario-filter]
 *
 * Writes tests/failure/evidence/failure-<ts>.json and RECOVERY_REPORT.md.
 */
import { execFileSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { ApiClient, ApiError, MemoryTokenStorage } from '../src/lib/api/http'
import { toBase64, uuid } from '../src/lib/crypto/bytes'
import { MemoryKeyStore } from '../src/lib/crypto/keystore'
import { establishSession, type Session } from '../src/lib/session'

const EDGE = process.env.EDGE_URL ?? 'http://localhost:8100'
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const filter = process.argv[2]
const PW = 'correct horse battery staple'
type Result = { scenario: string; ok: boolean; checks: { name: string; ok: boolean; detail?: string }[]; seconds: number }
const results: Result[] = []

function dc(...args: string[]): string {
  try {
    return execFileSync('docker', ['compose', ...args], { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  } catch (e) {
    return String((e as { stdout?: string }).stdout ?? '')
  }
}
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
async function until(fn: () => boolean | Promise<boolean>, ms = 90_000, step = 500): Promise<boolean> {
  const end = Date.now() + ms
  while (Date.now() < end) {
    try {
      if (await fn()) return true
    } catch { /* retry */ }
    await sleep(step)
  }
  return false
}
async function healthy(services: string[], ms = 300_000): Promise<boolean> {
  return until(() => {
    const ps = dc('ps', '--format', '{{.Service}} {{.Health}}')
    return services.every((s) => new RegExp(`^${s} healthy$`, 'm').test(ps))
  }, ms, 2000)
}

interface Client { name: string; api: ApiClient; s: Session; store: MemoryKeyStore; user: string }
async function client(name: string, user: string, register: boolean, store = new MemoryKeyStore()): Promise<Client> {
  const api = new ApiClient(EDGE, new MemoryTokenStorage())
  if (register) await api.register(`${user}@example.test`, user, PW, name)
  else await api.login(user, PW)
  const s = await establishSession({ api, openStore: async () => store, wsUrl: EDGE.replace(/^http/, 'ws') + '/ws', deviceName: name, log: () => undefined })
  await s.engine.start()
  await until(() => s.engine.connectionStatus() === 'online', 30_000)
  return { name, api, s, store, user }
}
const bodies = (c: Client, conv: string) => c.s.engine.messages(conv).filter((m) => m.direction !== 'system').map((m) => m.body ?? `<${m.error}>`)
const count = (c: Client, conv: string, text: string) => bodies(c, conv).filter((b) => b === text).length

async function pair(tag: string) {
  const run = uuid().slice(0, 5)
  const a = await client('a', `f_${tag}_a_${run}`, true)
  const b = await client('b', `f_${tag}_b_${run}`, true)
  const conv = (await a.s.engine.openDirect(b.s.user.userId)).id
  await until(async () => { await b.s.engine.refreshConversations(); return !!b.s.engine.conversation(conv) })
  // warm-up so the Signal session exists (failures then hit the steady-state path)
  await a.s.engine.send(conv, `warmup-${run}`)
  await until(() => count(b, conv, `warmup-${run}`) === 1)
  return { a, b, conv, run }
}

async function scenario(name: string, fn: (check: (n: string, ok: boolean, d?: string) => void) => Promise<void>) {
  if (filter && !name.toLowerCase().includes(filter.toLowerCase())) return
  console.log(`\n▶ ${name}`)
  const checks: Result['checks'] = []
  const start = Date.now()
  const check = (n: string, ok: boolean, detail?: string) => {
    checks.push({ name: n, ok, detail })
    console.log(`  ${ok ? '✔' : '✘'} ${n}${detail ? ` — ${detail}` : ''}`)
  }
  try {
    await fn(check)
  } catch (e) {
    check('scenario completed without unexpected error', false, String(e))
  }
  results.push({ scenario: name, ok: checks.length > 0 && checks.every((c) => c.ok), checks, seconds: Math.round((Date.now() - start) / 1000) })
}

/** Sends while a dependency is down, expecting ACCEPTED (durable path intact) and later delivery. */
async function acceptedThenDelivered(stopped: string[], check: (n: string, ok: boolean, d?: string) => void, label: string) {
  const { a, b, conv, run } = await pair(label)
  dc('stop', ...stopped)
  const texts = [1, 2, 3].map((i) => `${label}-${i}-${run}`)
  for (const t of texts) {
    const m = await a.s.engine.send(conv, t)
    check(`"${t}" ACCEPTED while ${stopped.join('+')} down`, m.status === 'sent' && !!m.id, m.status)
  }
  await sleep(3000)
  check(`nothing delivered while ${stopped.join('+')} down`, texts.every((t) => count(b, conv, t) === 0))
  dc('start', ...stopped)
  await healthy(stopped)
  const done = await until(() => texts.every((t) => count(b, conv, t) === 1), 120_000)
  check('after recovery every message delivered exactly once, in order', done && JSON.stringify(bodies(b, conv).filter((x) => x.startsWith(label))) === JSON.stringify(texts),
    bodies(b, conv).filter((x) => x.startsWith(label)).join(' | '))
  a.s.engine.stop()
  b.s.engine.stop()
}

async function main() {
  console.log(`SecureChat failure matrix — edge ${EDGE}`)
  if (!(await healthy(['api-gateway', 'message-service', 'delivery-service', 'fanout-service'], 60_000))) throw new Error('stack not healthy')

  let outboxFailures: string | undefined
  await scenario('Kafka broker unavailable: outbox retains events, published after recovery', async (c) => {
    await acceptedThenDelivered(['kafka'], c, 'kafka')
    // captured now: the final scenario recreates every container, which resets counters
    outboxFailures = /outbox_publish_total\{[^}]*result="failure"[^}]*\} ([0-9.]+)/.exec(
      dc('exec', '-T', 'message-service', 'curl', '-s', 'localhost:9085/actuator/prometheus'))?.[1]
  })

  await scenario('Fanout Service down: events wait in Kafka, processed after restart', (c) => acceptedThenDelivered(['fanout-service'], c, 'fanout'))

  await scenario('Delivery Service down: tasks wait, pending delivered after restart', (c) => acceptedThenDelivered(['delivery-service'], c, 'delivery'))

  await scenario('Cassandra unavailable: no false acceptance; client retry succeeds once', async (check) => {
    const { a, b, conv, run } = await pair('cass')
    dc('stop', 'cassandra')
    const t = `cassandra-${run}`
    const m = await a.s.engine.send(conv, t)
    check('message NOT accepted while Cassandra is down', m.status !== 'sent' && !m.id, `${m.status} ${m.error ?? ''}`)
    dc('start', 'cassandra')
    await healthy(['cassandra', 'message-service', 'delivery-service'], 400_000)
    await until(async () => { await a.s.engine.retry(conv, m.key); return a.s.engine.messages(conv).find((x) => x.key === m.key)?.status === 'sent' }, 180_000, 5000)
    check('client retry with the same clientMessageId is ACCEPTED', a.s.engine.messages(conv).find((x) => x.key === m.key)?.status === 'sent')
    check('recipient receives it exactly once', await until(() => count(b, conv, t) === 1, 120_000) && count(b, conv, t) === 1)
    a.s.engine.stop(); b.s.engine.stop()
  })

  await scenario('PostgreSQL unavailable: authorization fails closed; recovery restores service', async (check) => {
    const { a, b, conv, run } = await pair('pg')
    dc('stop', 'postgres')
    await sleep(2000)
    let loginStatus = 0
    try { await new ApiClient(EDGE, new MemoryTokenStorage()).login(a.user, PW) } catch (e) { loginStatus = (e as ApiError).status }
    check('login is refused (no stale credentials used)', loginStatus >= 500, `HTTP ${loginStatus}`)
    const t = `pg-${run}`
    const m = await a.s.engine.send(conv, t)
    check('message NOT accepted: membership cannot be verified (fail closed)', m.status !== 'sent', `${m.status} ${m.error ?? ''}`)
    dc('start', 'postgres')
    await healthy(['postgres', 'auth-service', 'conversation-service', 'user-service', 'key-service'], 300_000)
    await until(async () => { await a.s.engine.retry(conv, m.key); return a.s.engine.messages(conv).find((x) => x.key === m.key)?.status === 'sent' }, 120_000, 3000)
    check('retry after recovery is accepted and delivered once', await until(() => count(b, conv, t) === 1, 90_000))
    a.s.engine.stop(); b.s.engine.stop()
  })

  await scenario('Redis unavailable: presence UNKNOWN (not offline); messaging recovers after restart', async (check) => {
    const { a, b, conv, run } = await pair('redis')
    dc('stop', 'redis')
    await sleep(3000)
    let presence = ''
    try { presence = (await a.api.presence([b.s.user.userId]))[0]?.status ?? '' } catch (e) { presence = `HTTP ${(e as ApiError).status}` }
    check('presence reported UNKNOWN or unavailable, never OFFLINE', presence !== 'OFFLINE', presence)
    const t = `redis-${run}`
    const m = await a.s.engine.send(conv, t)
    check('message ACCEPTED (durable path does not depend on Redis)', m.status === 'sent', m.status)
    dc('start', 'redis')
    await healthy(['redis'])
    check('delivered after Redis recovery (gateway leases rebuilt, pending retried)', await until(() => count(b, conv, t) === 1, 120_000))
    check('presence ONLINE again (rebuilt from heartbeats)', await until(async () => (await a.api.presence([b.s.user.userId]))[0]?.status === 'ONLINE', 60_000))
    a.s.engine.stop(); b.s.engine.stop()
  })

  await scenario('Message Service crash (SIGKILL) mid-burst: no false ACCEPTED, no loss, no duplicates', async (check) => {
    const { a, b, conv, run } = await pair('msgkill')
    const sends: Promise<{ status: string; body: string | null }>[] = []
    for (let i = 0; i < 10; i++) {
      sends.push(a.s.engine.send(conv, `burst-${i}-${run}`))
      if (i === 4) dc('kill', 'message-service')
    }
    const out = await Promise.all(sends)
    const accepted = out.filter((m) => m.status === 'sent').map((m) => m.body!)
    dc('start', 'message-service')
    await healthy(['message-service'])
    for (const m of a.s.engine.messages(conv).filter((x) => x.status === 'queued' || x.status === 'failed')) await a.s.engine.retry(conv, m.key)
    const all = Array.from({ length: 10 }, (_, i) => `burst-${i}-${run}`)
    const finalAccepted = await until(() => a.s.engine.messages(conv).filter((m) => m.body?.startsWith('burst-') && m.status !== 'queued' && m.status !== 'failed').length === 10, 120_000, 2000)
    check('every message eventually ACCEPTED after client retry', finalAccepted)
    check('every ACCEPTED message delivered exactly once', await until(() => all.every((t) => count(b, conv, t) === 1), 120_000),
      `${all.filter((t) => count(b, conv, t) === 1).length}/10 (accepted during crash: ${accepted.length})`)
    a.s.engine.stop(); b.s.engine.stop()
  })

  await scenario('WebSocket Gateway crash: clients reconnect to the other gateway; pending replayed', async (check) => {
    const { a, b, conv, run } = await pair('gw')
    dc('kill', 'websocket-gateway')
    check('clients reconnect', await until(() => a.s.engine.connectionStatus() === 'online' && b.s.engine.connectionStatus() === 'online', 90_000))
    const t = `gateway-${run}`
    await a.s.engine.send(conv, t)
    check('message delivered through the surviving gateway', await until(() => count(b, conv, t) === 1, 90_000))
    dc('start', 'websocket-gateway')
    await healthy(['websocket-gateway'])
    a.s.engine.stop(); b.s.engine.stop()
  })

  await scenario('Consumer restarts (Fanout + Delivery) during a burst: nothing skipped', async (check) => {
    const { a, b, conv, run } = await pair('restart')
    const all = Array.from({ length: 12 }, (_, i) => `restart-${i}-${run}`)
    for (const [i, t] of all.entries()) {
      await a.s.engine.send(conv, t)
      if (i === 3) dc('restart', 'fanout-service')
      if (i === 7) dc('restart', 'delivery-service')
    }
    await healthy(['fanout-service', 'delivery-service'])
    check('all 12 delivered exactly once', await until(() => all.every((t) => count(b, conv, t) === 1), 150_000),
      `${all.filter((t) => count(b, conv, t) === 1).length}/12`)
    a.s.engine.stop(); b.s.engine.stop()
  })

  await scenario('Duplicate Kafka events: re-published accepted event creates no duplicate logical message', async (check) => {
    const { a, b, conv, run } = await pair('dup')
    const t = `dup-${run}`
    const m = await a.s.engine.send(conv, t)
    await until(() => count(b, conv, t) === 1)
    const line = dc('exec', '-T', 'kafka', 'bash', '-c',
      `/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic chat.message.accepted --from-beginning --timeout-ms 6000 2>/dev/null | grep '${m.id}' | head -1`).trim()
    check('captured the original accepted event', line.includes(m.id!))
    for (let i = 0; i < 3; i++) {
      execFileSync('docker', ['compose', 'exec', '-T', 'kafka', 'bash', '-c',
        `/opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic chat.message.accepted --property parse.key=true --property key.separator=$'\\t'`],
      { cwd: ROOT, input: `${conv}\t${line}\n` })
    }
    await sleep(8000)
    check('recipient still has exactly one copy', count(b, conv, t) === 1, `${count(b, conv, t)}`)
    a.s.engine.stop(); b.s.engine.stop()
  })

  await scenario('Monitoring stack down: chat unaffected', async (check) => {
    const { a, b, conv, run } = await pair('mon')
    dc('stop', 'prometheus', 'grafana', 'monitoring-service')
    const t = `monitoring-${run}`
    const m = await a.s.engine.send(conv, t)
    check('message accepted and delivered with Prometheus, Grafana and monitoring-service stopped',
      m.status === 'sent' && await until(() => count(b, conv, t) === 1))
    dc('start', 'prometheus', 'grafana', 'monitoring-service')
    await healthy(['monitoring-service'])
    a.s.engine.stop(); b.s.engine.stop()
  })

  // ---------------------------------------------------------------- E2EE-specific
  await scenario('E2EE: corrupted ciphertext is rejected by the recipient without breaking the session', async (check) => {
    const { a, b, conv, run } = await pair('corrupt')
    const garbage = toBase64(crypto.getRandomValues(new Uint8Array(120)))
    await a.api.sendMessage(conv, { clientMessageId: uuid(), protocol: 'SIGNAL', envelopes: [{ deviceId: b.s.deviceId, signalType: 1, ciphertext: garbage }] })
      .catch((e) => check('server accepted opaque ciphertext', false, String(e)))
    check('recipient shows an undecryptable placeholder (authentication failure)', await until(() => bodies(b, conv).some((x) => x.includes('Unable to decrypt'))))
    const t = `after-corrupt-${run}`
    await a.s.engine.send(conv, t)
    check('the real session keeps working afterwards', await until(() => count(b, conv, t) === 1))
    a.s.engine.stop(); b.s.engine.stop()
  })

  await scenario('E2EE: client restart recovers session state from the key store', async (check) => {
    const { a, b, conv, run } = await pair('restartc')
    b.s.engine.stop()
    const b2 = await client('b', b.user, false, b.store)
    check('same device after restart', b2.s.deviceId === b.s.deviceId)
    const t1 = `post-restart-${run}`
    await a.s.engine.send(conv, t1)
    check('decrypts with the persisted ratchet state', await until(() => count(b2, conv, t1) === 1))
    const t2 = `reply-${run}`
    await b2.s.engine.send(conv, t2)
    check('restarted device can reply', await until(() => count(a, conv, t2) === 1))
    a.s.engine.stop(); b2.s.engine.stop()
  })

  await scenario('E2EE: revoked device is cut off and stops receiving', async (check) => {
    const { a, b, conv, run } = await pair('revoke')
    const bPhone = await client('b-phone', b.user, false)
    await b.api.revokeDevice(bPhone.s.deviceId)
    check('revoked device socket closed (offline)', await until(() => bPhone.s.engine.connectionStatus() === 'offline', 30_000))
    const t = `after-revoke-${run}`
    await a.s.engine.send(conv, t)
    check('remaining device still receives', await until(() => count(b, conv, t) === 1))
    const ids = await a.api.identities(b.s.user.userId)
    check('revoked device no longer published in the key directory', !ids.some((d) => d.deviceId === bPhone.s.deviceId))
    let bundleAfter = true
    try { await bPhone.api.claimBundle(a.s.user.userId, false) } catch { bundleAfter = false }
    check('revoked device can no longer fetch key bundles', !bundleAfter)
    a.s.engine.stop(); b.s.engine.stop(); bPhone.s.engine.stop()
  })

  await scenario('E2EE: depleted one-time prekeys fall back to signed-prekey X3DH', async (check) => {
    const run = uuid().slice(0, 5)
    const target = await client('t', `f_prekey_t_${run}`, true)
    // one requester device drains the inventory (stays under the per-IP signup limit and the
    // per-device claim limit of 120/min)
    target.s.engine.stop()   // otherwise the client correctly auto-replenishes on "prekeys_low"
    const other = await client('o', `f_prekey_o_${run}`, true)
    // paced (~8 req/s) to stay under the edge's per-IP limit, which the test itself would otherwise trip
    let left = (await target.api.keyStatus()).oneTimePreKeys
    while (left > 0) {
      for (let i = 0; i < left; i++) {
        await other.api.claimBundle(target.s.user.userId, false)
        await sleep(125)
      }
      left = (await target.api.keyStatus()).oneTimePreKeys
    }
    other.s.engine.stop()
    target.s.engine.stop()           // don't replenish while we test the fallback
    const inv = await target.api.keyStatus()
    check('inventory depleted', inv.oneTimePreKeys === 0, `${inv.oneTimePreKeys} left`)
    const sender = await client('s', `f_prekey_s_${run}`, true)
    const conv = await sender.s.engine.openDirect(target.s.user.userId)
    const m = await sender.s.engine.send(conv.id, `fallback-${run}`)
    check('session established without a one-time prekey; message accepted', m.status === 'sent', m.error)
    const t2 = await client('t', `f_prekey_t_${run}`, false, target.store)
    check('recipient decrypts', await until(() => count(t2, conv.id, `fallback-${run}`) === 1))
    sender.s.engine.stop(); t2.s.engine.stop()
  })

  // ---------------------------------------------------------------- full restart & persistence
  await scenario('Full stack restart + container recreation: data persists, messaging works', async (check) => {
    const { a, b, conv, run } = await pair('host')
    a.s.engine.stop(); b.s.engine.stop()
    execFileSync(resolve(ROOT, 'scripts/stop.sh'), { cwd: ROOT, stdio: 'ignore' })
    execFileSync('docker', ['compose', 'up', '-d', '--force-recreate'], { cwd: ROOT, stdio: 'ignore' })
    const ok = await healthy(['postgres', 'cassandra', 'redis', 'kafka', 'api-gateway', 'message-service', 'delivery-service', 'fanout-service',
      'websocket-gateway', 'websocket-gateway-2', 'auth-service', 'user-service', 'key-service', 'conversation-service', 'presence-service'], 600_000)
    check('every service healthy after full recreation', ok)
    await sleep(10_000)
    const a2 = await client('a', a.user, false, a.store)
    const b2 = await client('b', b.user, false, b.store)
    check('accounts, devices and conversations survived', a2.s.deviceId === a.s.deviceId && !!a2.s.engine.conversation(conv))
    const t = `after-host-restart-${run}`
    await a2.s.engine.send(conv, t)
    check('messaging works after restart (same Signal session)', await until(() => count(b2, conv, t) === 1, 90_000))
    a2.s.engine.stop(); b2.s.engine.stop()
  })

  // ---------------------------------------------------------------- report
  const dir = resolve(ROOT, 'tests/failure/evidence')
  mkdirSync(dir, { recursive: true })
  const ts = new Date().toISOString().replace(/[:.]/g, '-')
  writeFileSync(resolve(dir, `failure-${ts}.json`), JSON.stringify({ at: new Date().toISOString(), results, outboxPublishFailuresObserved: outboxFailures }, null, 2))
  const md = ['# Failure-injection recovery report', '', `Run: ${new Date().toISOString()} against ${EDGE} (local Compose).`, '',
    `Outbox publish failures recorded by message-service right after the Kafka outage: ${outboxFailures ?? 'n/a'} (> 0 shows the outage hit the publisher).`, '',
    '| Scenario | Result | Duration | Checks |', '|---|---|---|---|',
    ...results.map((r) => `| ${r.scenario} | ${r.ok ? 'PASS' : '**FAIL**'} | ${r.seconds}s | ${r.checks.map((c) => `${c.ok ? '✔' : '✘'} ${c.name}${c.detail ? ` (${c.detail})` : ''}`).join('<br>')} |`)]
  if (!filter) writeFileSync(resolve(dir, 'RECOVERY_REPORT.md'), md.join('\n') + '\n')
  const passed = results.filter((r) => r.ok).length
  console.log(`\n${passed}/${results.length} scenarios passed`)
  process.exit(passed === results.length ? 0 : 1)
}
main().catch((e) => { console.error('FAILURE MATRIX ERROR', e); process.exit(2) })
