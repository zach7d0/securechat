import { describe, expect, it } from 'vitest'
import { fromBase64, toBase64, utf8, uuid } from '../lib/crypto/bytes'
import { openShare, sealShare } from '../lib/crypto/historyShare'
import { decodePayload, encodePayload, type HistorySharePayload } from '../lib/crypto/payload'
import { FakeDirectory, makeDevice } from './harness'

const containsBytes = (hay: Uint8Array, needle: Uint8Array) => {
  outer: for (let i = 0; i + needle.length <= hay.length; i++) {
    for (let j = 0; j < needle.length; j++) if (hay[i + j] !== needle[j]) continue outer
    return true
  }
  return false
}

const archive = (conversationId: string) => ({
  v: 1, conversationId, sharedBy: 'alice',
  messages: [{ key: 'k1', id: 'm1', conversationId, senderUserId: 'carol', body: 'SECRET-HISTORY-MARKER', sentAt: '2026-01-01T00:00:00Z' }],
})

describe('group history share archive (AES-256-GCM, fresh key)', () => {
  it('round-trips and the ciphertext holds no plaintext', async () => {
    const conv = uuid()
    const share = uuid()
    const sealed = await sealShare(archive(conv), conv, share)
    expect(fromBase64(sealed.key)).toHaveLength(32)
    expect(containsBytes(sealed.ciphertext, utf8('SECRET-HISTORY-MARKER'))).toBe(false)
    const opened = await openShare<ReturnType<typeof archive>>(sealed.ciphertext, sealed.key, sealed.sha256, conv, share)
    expect(opened.messages[0].body).toBe('SECRET-HISTORY-MARKER')
  })

  it('uses a different key every time', async () => {
    const conv = uuid()
    const a = await sealShare(archive(conv), conv, 's')
    const b = await sealShare(archive(conv), conv, 's')
    expect(a.key).not.toBe(b.key)
  })

  it('fails with a wrong key, a tampered chunk, or another conversation/share id (AAD)', async () => {
    const conv = uuid()
    const share = uuid()
    const s = await sealShare(archive(conv), conv, share)
    const other = toBase64(crypto.getRandomValues(new Uint8Array(32)))
    await expect(openShare(s.ciphertext, other, s.sha256, conv, share)).rejects.toThrow()
    const tampered = s.ciphertext.slice()
    tampered[tampered.length - 1] ^= 1
    await expect(openShare(tampered, s.key, s.sha256, conv, share)).rejects.toThrow(/hash mismatch/)
    // even if an attacker also fixes up the hash, AES-GCM authentication fails
    const { sha256Hex } = await import('../lib/crypto/transfer')
    await expect(openShare(tampered, s.key, await sha256Hex(tampered), conv, share)).rejects.toThrow()
    await expect(openShare(s.ciphertext, s.key, s.sha256, uuid(), share)).rejects.toThrow()
    await expect(openShare(s.ciphertext, s.key, s.sha256, conv, uuid())).rejects.toThrow()
  })
})

describe('history-share payload', () => {
  const p: HistorySharePayload = { v: 1, kind: 'history-share', shareId: uuid(), key: toBase64(new Uint8Array(32)), sha256: 'ab',
    chunkCount: 2, messageCount: 7, forUserIds: ['bob'], sentAt: '2026-01-01T00:00:00Z', clientMessageId: uuid() }

  it('encodes and decodes next to text payloads', () => {
    expect(decodePayload(encodePayload(p))).toEqual(p)
    const t = { v: 1 as const, kind: 'text' as const, body: 'hi', sentAt: '2026-01-01T00:00:00Z', clientMessageId: uuid() }
    expect(decodePayload(encodePayload(t))).toEqual(t)
  })

  it('rejects malformed shares', () => {
    for (const bad of [{ ...p, chunkCount: 0 }, { ...p, forUserIds: [1] }, { ...p, shareId: undefined }, { ...p, kind: 'other' }]) {
      expect(() => decodePayload(encodePayload(bad as unknown as HistorySharePayload))).toThrow()
    }
  })

  it('the key message sent in the new epoch is readable by the new member and existing members', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const carol = await makeDevice(dir, 'carol')
    const bob = await makeDevice(dir, 'bob')
    const conv = uuid()
    await alice.mls.createGroup(conv, toBase64(crypto.getRandomValues(new Uint8Array(32))))
    const add = async (who: typeof bob) => {
      const b = await dir.claimBundle(who.userId, true)
      const staged = await alice.mls.prepareAdd(conv, [{ userId: who.userId, bundle: b.devices[0] }])
      await alice.mls.applyStaged(conv, staged)
      return staged
    }
    const s1 = await add(carol)
    await carol.mls.joinFromWelcome(conv, s1.welcome!)
    const s2 = await add(bob)
    expect((await carol.mls.process(conv, s2.commit)).kind).toBe('commit')
    await bob.mls.joinFromWelcome(conv, s2.welcome!)

    const msg = await alice.mls.encrypt(conv, encodePayload({ ...p, forUserIds: [bob.userId] }))
    for (const reader of [bob, carol]) {
      const r = await reader.mls.process(conv, msg.ciphertext)
      expect(r.kind).toBe('application')
      if (r.kind === 'application') expect(decodePayload(r.plaintext)).toMatchObject({ kind: 'history-share', key: p.key })
    }
  })
})
