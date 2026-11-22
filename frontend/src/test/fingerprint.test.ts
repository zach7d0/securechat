import { FingerprintGenerator, KeyHelper } from '@privacyresearch/libsignal-protocol-typescript'
import { describe, expect, it } from 'vitest'
import { displayableFingerprint } from '../lib/crypto/fingerprint'

describe('safety number', () => {
  it('is byte-for-byte identical to libsignal FingerprintGenerator output', async () => {
    for (let i = 0; i < 3; i++) {
      const a = await KeyHelper.generateIdentityKeyPair()
      const b = await KeyHelper.generateIdentityKeyPair()
      const idA = crypto.randomUUID()
      const idB = crypto.randomUUID()
      const ours = await displayableFingerprint(idA, a.pubKey, idB, b.pubKey)
      const lib = await new FingerprintGenerator(5200).createFor(idA, a.pubKey, idB, b.pubKey)
      expect(ours).toHaveLength(60)
      expect(ours).toBe(lib)
    }
  })
})
