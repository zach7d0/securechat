import { describe, expect, it } from 'vitest'
import { KeyHelper } from '@privacyresearch/libsignal-protocol-typescript'
const generateIdentityKeyPair = () => KeyHelper.generateIdentityKeyPair()
import * as T from '../lib/crypto/transfer'

describe('history transfer crypto', () => {
  it('round-trips an archive only for the intended ephemeral key', async () => {
    const eph = await T.newEphemeralKey()
    const other = await T.newEphemeralKey()
    const archive = { v: 1, messages: [{ key: 'k', body: 'hello' }] }
    const { ciphertext, enc } = await T.sealArchive(archive, eph.publicKey, 'tid')
    expect(new TextDecoder().decode(ciphertext)).not.toContain('hello')
    expect(await T.openArchive(ciphertext, enc, eph.privateKey, 'tid')).toEqual(archive)
    await expect(T.openArchive(ciphertext, enc, other.privateKey, 'tid')).rejects.toThrow()
    await expect(T.openArchive(ciphertext, enc, eph.privateKey, 'other-transfer')).rejects.toThrow()   // bound to transfer id
    const tampered = ciphertext.slice()
    tampered[10] ^= 1
    await expect(T.openArchive(tampered, enc, eph.privateKey, 'tid')).rejects.toThrow()
  })

  it('request signatures bind the ephemeral key to the device identity', async () => {
    const id = await generateIdentityKeyPair()
    const eph = await T.newEphemeralKey()
    const msg = T.requestMessage('u', 'd', eph.publicKey)
    const sig = await T.sign(id.privKey, msg)
    expect(await T.verify(new Uint8Array(id.pubKey), msg, sig)).toBe(true)
    const swapped = await T.newEphemeralKey()     // server substitutes its own key
    expect(await T.verify(new Uint8Array(id.pubKey), T.requestMessage('u', 'd', swapped.publicKey), sig)).toBe(false)
  })

  it('comparison code changes if any key is substituted', async () => {
    const a = new Uint8Array(await (await generateIdentityKeyPair()).pubKey)
    const b = new Uint8Array(await (await generateIdentityKeyPair()).pubKey)
    const m = new Uint8Array(await (await generateIdentityKeyPair()).pubKey)
    const e = (await T.newEphemeralKey()).publicKey
    const code = await T.comparisonCode('t', a, e, b)
    expect(code).toMatch(/^\d{5} \d{5} \d{5} \d{5}$/)
    expect(await T.comparisonCode('t', a, e, b)).toBe(code)
    expect(await T.comparisonCode('t', m, e, b)).not.toBe(code)
    expect(await T.comparisonCode('t', a, e, m)).not.toBe(code)
    expect(await T.comparisonCode('t', a, (await T.newEphemeralKey()).publicKey, b)).not.toBe(code)
  })
})
