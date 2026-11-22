import { describe, expect, it } from 'vitest'
import { KeyHelper } from '@privacyresearch/libsignal-protocol-typescript'
import { uuid } from '../lib/crypto/bytes'
import { scopeWindowFrom, selectForScope } from '../lib/crypto/historyShare'
import { decodePayload, encodePayload, type HistoryGrantPayload } from '../lib/crypto/payload'
import * as T from '../lib/crypto/transfer'

describe('automatic own-device sync: the signed request binds the time window', () => {
  it('a request signed for one month does not verify for a wider window or without one', async () => {
    const id = await KeyHelper.generateIdentityKeyPair()
    const eph = await T.newEphemeralKey()
    const pub = new Uint8Array(id.pubKey)
    const w = { from: Date.parse('2026-10-01T00:00:00Z'), to: Date.parse('2026-10-31T00:00:00Z') }
    const sig = await T.sign(id.privKey, T.requestMessage('u', 'd', eph.publicKey, w))
    expect(await T.verify(pub, T.requestMessage('u', 'd', eph.publicKey, w), sig)).toBe(true)
    expect(await T.verify(pub, T.requestMessage('u', 'd', eph.publicKey, { ...w, from: w.from - 86_400_000 }), sig)).toBe(false)
    expect(await T.verify(pub, T.requestMessage('u', 'd', eph.publicKey), sig)).toBe(false)
    // timestamps compared as epoch ms: server formatting (".000Z" vs "Z") cannot break verification
    expect(Date.parse('2026-10-01T00:00:00Z')).toBe(Date.parse('2026-10-01T00:00:00.000Z'))
  })
})

describe('group history scopes', () => {
  const now = Date.parse('2026-11-20T12:00:00Z')
  const at = (msAgo: number) => ({ sentAt: new Date(now - msAgo).toISOString(), id: String(msAgo) })
  const msgs = [at(40 * 86_400_000), at(8 * 86_400_000), at(2 * 86_400_000), at(11 * 3_600_000), at(2 * 3_600_000),
    at(30 * 60_000), at(5 * 60_000), at(-60_000)]   // the last one is after the grant: never shared

  it('computes the window start', () => {
    expect(scopeWindowFrom('10m', now)).toBe(now - 600_000)
    expect(scopeWindowFrom('7d', now)).toBe(now - 7 * 86_400_000)
    expect(scopeWindowFrom('all', now)).toBeNull()
    expect(scopeWindowFrom('last100', now)).toBeNull()
  })

  it('selects exactly the messages in scope', () => {
    const ids = (s: Parameters<typeof selectForScope>[1]) => selectForScope(msgs, s, now).map((m) => m.id)
    expect(ids('10m')).toEqual([String(5 * 60_000)])
    expect(ids('1h')).toEqual([String(30 * 60_000), String(5 * 60_000)])
    expect(ids('10h')).toHaveLength(3)
    expect(ids('1d')).toHaveLength(4)
    expect(ids('7d')).toHaveLength(5)
    expect(ids('30d')).toHaveLength(6)
    expect(ids('all')).toHaveLength(7)
    const many = Array.from({ length: 150 }, (_, i) => at((150 - i) * 1000))
    const last = selectForScope(many, 'last100', now)
    expect(last).toHaveLength(100)
    expect(last[99].sentAt).toBe(many[149].sentAt)
  })
})

describe('history-grant payload', () => {
  const g: HistoryGrantPayload = { v: 1, kind: 'history-grant', grantId: uuid(), scope: '7d', forUserIds: ['bob'],
    grantedAt: '2026-11-20T12:00:00.000Z', sentAt: '2026-11-20T12:00:00.000Z', clientMessageId: uuid() }
  it('round-trips and rejects unknown scopes or bad dates', () => {
    expect(decodePayload(encodePayload(g))).toEqual(g)
    for (const bad of [{ ...g, scope: 'forever' }, { ...g, grantedAt: 'yesterday' }, { ...g, forUserIds: 'bob' }, { ...g, grantId: 7 }]) {
      expect(() => decodePayload(encodePayload(bad as unknown as HistoryGrantPayload))).toThrow()
    }
  })
})
