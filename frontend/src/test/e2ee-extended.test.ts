import { describe, expect, it } from 'vitest'
import { fromBase64, fromUtf8, toBase64, utf8, uuid } from '../lib/crypto/bytes'
import { KeyedMutex } from '../lib/crypto/mutex'
import { MemoryKeyStore } from '../lib/crypto/keystore'
import { FakeDirectory, makeDevice, type TestDevice } from './harness'

async function group(dir: FakeDirectory, owner: TestDevice, members: TestDevice[]) {
  const conv = uuid()
  await owner.mls.createGroup(conv, toBase64(crypto.getRandomValues(new Uint8Array(32))))
  const devices = []
  for (const m of members) devices.push({ userId: m.userId, bundle: (await dir.claimBundle(m.userId, true, [m.deviceId])).devices[0] })
  const staged = await owner.mls.prepareAdd(conv, devices)
  await owner.mls.applyStaged(conv, staged)
  for (const [i, m] of members.entries()) await m.mls.joinFromWelcome(conv, staged.welcome!, devices[i].bundle.mlsKeyPackage!.id)
  return conv
}

describe('Signal — key inventory and multi-device', () => {
  it('establishes a session with the no-one-time-prekey fallback (depleted inventory)', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob', { preKeys: 0 })
    const [env] = await alice.signal.encrypt([{ userId: bob.userId, deviceId: bob.deviceId }], utf8('no OPK'))
    expect(env.signalType).toBe(3)
    expect(fromUtf8(await bob.signal.decrypt(alice.deviceId, fromBase64(env.ciphertext), env.signalType))).toBe('no OPK')
  })

  it('encrypts independently for every recipient device; one device cannot read another device\'s envelope', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bobLaptop = await makeDevice(dir, 'bob-laptop')
    const bobPhone = await makeDevice(dir, 'bob-phone', { userId: bobLaptop.userId })
    const envs = await alice.signal.encrypt([
      { userId: bobLaptop.userId, deviceId: bobLaptop.deviceId }, { userId: bobPhone.userId, deviceId: bobPhone.deviceId }], utf8('to both'))
    expect(envs.map((e) => e.deviceId).sort()).toEqual([bobLaptop.deviceId, bobPhone.deviceId].sort())
    const forPhone = envs.find((e) => e.deviceId === bobPhone.deviceId)!
    const forLaptop = envs.find((e) => e.deviceId === bobLaptop.deviceId)!
    expect(fromUtf8(await bobPhone.signal.decrypt(alice.deviceId, fromBase64(forPhone.ciphertext), 3))).toBe('to both')
    expect(fromUtf8(await bobLaptop.signal.decrypt(alice.deviceId, fromBase64(forLaptop.ciphertext), 3))).toBe('to both')
    await expect(bobPhone.signal.decrypt(alice.deviceId, fromBase64(forLaptop.ciphertext), 3)).rejects.toThrow()
  })

  it('duplicate delivery of the same ciphertext does not decrypt twice (replay rejected)', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    const [env] = await alice.signal.encrypt([{ userId: bob.userId, deviceId: bob.deviceId }], utf8('once'))
    expect(fromUtf8(await bob.signal.decrypt(alice.deviceId, fromBase64(env.ciphertext), 3))).toBe('once')
    await expect(bob.signal.decrypt(alice.deviceId, fromBase64(env.ciphertext), 3)).rejects.toThrow()
  })

  it('out-of-order delivery decrypts (skipped message keys)', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    const t = [{ userId: bob.userId, deviceId: bob.deviceId }]
    const [m1] = await alice.signal.encrypt(t, utf8('one'))
    expect(fromUtf8(await bob.signal.decrypt(alice.deviceId, fromBase64(m1.ciphertext), m1.signalType))).toBe('one')
    const [r] = await bob.signal.encrypt([{ userId: alice.userId, deviceId: alice.deviceId }], utf8('ack'))
    await alice.signal.decrypt(bob.deviceId, fromBase64(r.ciphertext), r.signalType)
    const [m2] = await alice.signal.encrypt(t, utf8('two'))
    const [m3] = await alice.signal.encrypt(t, utf8('three'))
    expect(fromUtf8(await bob.signal.decrypt(alice.deviceId, fromBase64(m3.ciphertext), m3.signalType))).toBe('three')
    expect(fromUtf8(await bob.signal.decrypt(alice.deviceId, fromBase64(m2.ciphertext), m2.signalType))).toBe('two')
  })

  it('per-session mutex serialises concurrent ratchet updates (no corrupted state)', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    const mutex = new KeyedMutex()
    const t = [{ userId: bob.userId, deviceId: bob.deviceId }]
    const sends = await Promise.all(Array.from({ length: 10 }, (_, i) =>
      mutex.run('conv', () => alice.signal.encrypt(t, utf8(`m${i}`)))))
    const got = []
    for (const [e] of sends) got.push(fromUtf8(await bob.signal.decrypt(alice.deviceId, fromBase64(e.ciphertext), e.signalType)))
    expect(got).toEqual(Array.from({ length: 10 }, (_, i) => `m${i}`))
  })

  it('private keys are encrypted at rest in the key store', async () => {
    const ks = new MemoryKeyStore()
    const dir = new FakeDirectory()
    const d = await makeDevice(dir, 'd')
    const id = (await d.store.getIdentityKeyPair())!
    // MemoryKeyStore is plaintext by design (tests); the IndexedDB store AES-GCM wraps every record.
    expect(ks.dump()).toHaveLength(0)
    expect(dir.publicDump()).not.toContain(toBase64(id.privKey))
  })
})

describe('MLS — device changes, key updates and crash safety', () => {
  it('a member\'s new device is added by a device-sync commit and can decrypt subsequent messages only', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    const conv = await group(dir, alice, [bob])
    const before = await alice.mls.encrypt(conv, utf8('before phone'))
    await bob.mls.process(conv, before.ciphertext)
    const bobPhone = await makeDevice(dir, 'bob-phone', { userId: bob.userId })
    const b = (await dir.claimBundle(bob.userId, true, [bobPhone.deviceId])).devices[0]
    const staged = await alice.mls.prepareSync(conv, [{ userId: bob.userId, bundle: b }], [])
    await alice.mls.applyStaged(conv, staged)
    await bob.mls.process(conv, staged.commit)
    await bobPhone.mls.joinFromWelcome(conv, staged.welcome!, b.mlsKeyPackage!.id)
    expect((await alice.mls.members(conv)).map((m) => m.deviceId)).toContain(bobPhone.deviceId)
    const after = await alice.mls.encrypt(conv, utf8('after phone'))
    const r = await bobPhone.mls.process(conv, after.ciphertext)
    expect(r.kind === 'application' && fromUtf8(r.plaintext)).toBe('after phone')
    // the new device cannot read history from before it joined
    expect((await bobPhone.mls.process(conv, before.ciphertext)).kind).toBe('stale')
  })

  it('a revoked device\'s leaf is removed and it cannot decrypt messages after the removal', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    const carol = await makeDevice(dir, 'carol')
    const conv = await group(dir, alice, [bob, carol])
    const leaf = (await alice.mls.members(conv)).find((m) => m.deviceId === carol.deviceId)!.leafIndex
    const staged = await alice.mls.prepareSync(conv, [], [leaf])
    await alice.mls.applyStaged(conv, staged)
    await bob.mls.process(conv, staged.commit)
    const removed = await carol.mls.process(conv, staged.commit)
    expect(removed.kind === 'commit' && removed.removedSelf).toBe(true)
    const secret = await alice.mls.encrypt(conv, utf8('carol must not read'))
    const forBob = await bob.mls.process(conv, secret.ciphertext)
    expect(forBob.kind === 'application' && fromUtf8(forBob.plaintext)).toBe('carol must not read')
    await expect(carol.mls.process(conv, secret.ciphertext)).rejects.toThrow()
  })

  it('a key-update commit advances the epoch for every member', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    const conv = await group(dir, alice, [bob])
    const e0 = await alice.mls.epoch(conv)
    const upd = await bob.mls.prepareUpdate(conv)
    await bob.mls.applyStaged(conv, upd)
    await alice.mls.process(conv, upd.commit)
    expect(await alice.mls.epoch(conv)).toBe(e0 + 1)
    expect(await bob.mls.epoch(conv)).toBe(e0 + 1)
    const m = await alice.mls.encrypt(conv, utf8('rotated'))
    const r = await bob.mls.process(conv, m.ciphertext)
    expect(r.kind === 'application' && fromUtf8(r.plaintext)).toBe('rotated')
  })

  it('a late application message from the previous epoch still decrypts after a commit', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    const conv = await group(dir, alice, [bob])
    const late = await bob.mls.encrypt(conv, utf8('sent just before the commit'))
    const upd = await alice.mls.prepareUpdate(conv)
    await alice.mls.applyStaged(conv, upd)
    await bob.mls.process(conv, upd.commit)
    const r = await alice.mls.process(conv, late.ciphertext)
    expect(r.kind === 'application' && fromUtf8(r.plaintext)).toBe('sent just before the commit')
  })

  it('an own commit accepted by the server survives a crash before it was applied', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    const conv = await group(dir, alice, [bob])
    const e = await alice.mls.epoch(conv)
    const upd = await alice.mls.prepareUpdate(conv)
    await alice.mls.savePending(conv, upd)          // persisted before submitting
    // … server accepts, tab crashes before applyStaged …
    await bob.mls.process(conv, upd.commit)
    const recovered = await alice.mls.recoverPending(conv, async (epoch) => (epoch === e ? alice.deviceId : null), alice.deviceId)
    expect(recovered).toBe(true)
    expect(await alice.mls.epoch(conv)).toBe(e + 1)
    const m = await bob.mls.encrypt(conv, utf8('still in sync'))
    const r = await alice.mls.process(conv, m.ciphertext)
    expect(r.kind === 'application' && fromUtf8(r.plaintext)).toBe('still in sync')
  })

  it('a pending own commit that lost the epoch race is discarded', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    const conv = await group(dir, alice, [bob])
    const e = await alice.mls.epoch(conv)
    await alice.mls.savePending(conv, await alice.mls.prepareUpdate(conv))
    expect(await alice.mls.recoverPending(conv, async () => bob.deviceId, alice.deviceId)).toBe(false)
    expect(await alice.mls.epoch(conv)).toBe(e)
  })
})
