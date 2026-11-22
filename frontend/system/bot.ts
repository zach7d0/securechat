/**
 * Echo bot for manual/browser testing: a real E2EE client (headless) that registers BOT_USER and
 * replies "echo: <text>" to every decrypted message. npx tsx system/bot.ts
 */
import { ApiClient, MemoryTokenStorage } from '../src/lib/api/http'
import { MemoryKeyStore } from '../src/lib/crypto/keystore'
import { establishSession } from '../src/lib/session'

const EDGE = process.env.EDGE_URL ?? 'http://localhost:8100'
const user = process.env.BOT_USER ?? 'bob_bot'
const pw = 'correct horse battery staple'

async function main() {
  const api = new ApiClient(EDGE, new MemoryTokenStorage())
  try {
    await api.register(`${user}@example.test`, user, pw, 'Bob (bot)')
  } catch {
    await api.login(user, pw)
  }
  const s = await establishSession({ api, openStore: async () => new MemoryKeyStore(), wsUrl: EDGE.replace(/^http/, 'ws') + '/ws',
    deviceName: 'echo-bot', log: () => undefined })
  const replied = new Set<string>()
  s.engine.on((topic, conv) => {
    if (topic !== 'messages' || !conv) return
    for (const m of s.engine.messages(conv)) {
      if (m.direction === 'in' && m.body && m.id && !replied.has(m.id)) {
        replied.add(m.id)
        void s.engine.markRead(conv)
        void s.engine.send(conv, `echo: ${m.body}`)
        console.log(`[bot] decrypted in ${conv.slice(0, 8)}: ${m.body}`)
      }
    }
  })
  // the engine already processes welcomes on membership frames; poll slowly as a fallback
  setInterval(() => void s.engine.processWelcomes(), 15_000)
  await s.engine.start()
  console.log(`[bot] ready as @${user} device=${s.deviceId}`)
}
main().catch((e) => { console.error(e); process.exit(1) })
