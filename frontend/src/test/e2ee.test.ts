import { describe, expect, it } from 'vitest'
import { fromBase64, fromUtf8, toBase64, utf8, uuid } from '../lib/crypto/bytes'
import { decodePayload, encodePayload } from '../lib/crypto/payload'
import { SignalService } from '../lib/crypto/signal'
import { FakeDirectory, makeDevice, type TestDevice } from './harness'

const containsBytes = (hay: Uint8Array, needle: Uint8Array) => {
  outer: for (let i = 0; i + needle.length <= hay.length; i++) {
    for (let j = 0; j < needle.length; j++) if (hay[i + j] !== needle[j]) continue outer
    return true
  }
  return false
}

async function send(from: TestDevice, to: TestDevice, text: string) {
  const [env] = await from.signal.encrypt([{ userId: to.userId, deviceId: to.deviceId }], utf8(text))
  return env
}

async function receive(to: TestDevice, from: TestDevice, env: { ciphertext: string; signalType: number }) {
  return fromUtf8(await to.signal.decrypt(from.deviceId, fromBase64(env.ciphertext), env.signalType))
}

async function newGroup(dir: FakeDirectory, owner: TestDevice, members: TestDevice[]) {
  const conv = uuid()
  await owner.mls.createGroup(conv, toBase64(crypto.getRandomValues(new Uint8Array(32))))
  const devices = []
  for (const m of members) {
    const b = await dir.claimBundle(m.userId, true)
    devices.push({ userId: m.userId, bundle: b.devices[0] })
  }
  const staged = await owner.mls.prepareAdd(conv, devices)
  await owner.mls.applyStaged(conv, staged)
  for (const [i, m] of members.entries()) {
    await m.mls.joinFromWelcome(conv, staged.welcome!, devices[i].bundle.mlsKeyPackage!.id)
  }
  return conv
}

describe('One-to-one E2EE (Signal Protocol)', () => {
  it('Alice sends an encrypted message to Bob (X3DH PreKeyWhisperMessage, no plaintext on the wire)', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    const env = await send(alice, bob, 'Hello Bob, this is secret')
    expect(env.signalType).toBe(3)
    expect(env.deviceId).toBe(bob.deviceId)
    expect(containsBytes(fromBase64(env.ciphertext), utf8('Hello Bob'))).toBe(false)
  })

  it('Bob decrypts Alice\'s message, replies, and the ratchet keeps working across restarts', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    expect(await receive(bob, alice, await send(alice, bob, 'hi bob'))).toBe('hi bob')

    const reply = await send(bob, alice, 'hi alice')
    expect(reply.signalType).toBe(1) // session established: plain WhisperMessage
    expect(await receive(alice, bob, reply)).toBe('hi alice')

    // Session persistence: rebuild services from the same key stores (simulated reload).
    const alice2 = { ...alice, signal: new SignalService(alice.store, dir, { userId: alice.userId, deviceId: alice.deviceId }) }
    const bob2 = { ...bob, signal: new SignalService(bob.store, dir, { userId: bob.userId, deviceId: bob.deviceId }) }
    const c1 = await send(alice2, bob2, 'after restart')
    const c2 = await send(alice2, bob2, 'after restart')
    expect(c1.ciphertext).not.toBe(c2.ciphertext) // ratchet: same plaintext, different ciphertext
    expect(await receive(bob2, alice2, c1)).toBe('after restart')
    expect(await receive(bob2, alice2, c2)).toBe('after restart')
  })

  it('the backend cannot decrypt: it holds only public keys and ciphertext', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    const env = await send(alice, bob, 'top secret plan')

    // Everything the server stores contains no private key material...
    const serverView = dir.publicDump() + JSON.stringify(env)
    const bobIdentity = (await bob.store.getIdentityKeyPair())!
    expect(serverView).not.toContain(toBase64(bobIdentity.privKey))
    expect(serverView).not.toContain('top secret plan')

    // ...and a "server" device built from only public directory data cannot decrypt it.
    const server = await makeDevice(dir, 'server-attacker')
    await expect(server.signal.decrypt(alice.deviceId, fromBase64(env.ciphertext), env.signalType)).rejects.toThrow()
  })

  it('a wrong key cannot decrypt, and tampered ciphertext is rejected (authenticated encryption)', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    const eve = await makeDevice(dir, 'eve')
    const env = await send(alice, bob, 'for bob only')
    await expect(receive(eve, alice, env)).rejects.toThrow()

    const first = await send(alice, bob, 'establish')
    await receive(bob, alice, first)
    const reply = await send(bob, alice, 'ok')
    await receive(alice, bob, reply)
    const msg = await send(alice, bob, 'integrity matters')
    const bytes = fromBase64(msg.ciphertext)
    bytes[bytes.length - 12] ^= 0x01
    await expect(receive(bob, alice, { ...msg, ciphertext: toBase64(bytes) })).rejects.toThrow()
  })

  it('an offline recipient receives pending encrypted messages and decrypts them in order', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    const queue = []
    for (const t of ['one', 'two', 'three']) queue.push(await send(alice, bob, t)) // Bob offline
    const received = []
    for (const env of queue) received.push(await receive(bob, alice, env)) // Bob comes online
    expect(received).toEqual(['one', 'two', 'three'])
  })

  it('a changed identity key for a known device is never silently accepted', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    await receive(bob, alice, await send(alice, bob, 'pin identity'))
    // Server swaps Bob's identity (MITM attempt) by replacing his directory entry.
    const impostor = await makeDevice(dir, 'impostor')
    const entry = dir.devices.get(impostor.deviceId)!
    dir.devices.set(bob.deviceId, { ...entry, userId: bob.userId, bundle: { ...entry.bundle, deviceId: bob.deviceId } })
    await alice.store.approveIdentity(bob.deviceId, (await alice.store.trustedIdentity(bob.deviceId))!) // drop session only
    await expect(send(alice, bob, 'hello?')).rejects.toThrow(/identity/i)
  })

  it('safety numbers match on both sides', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    const aId = new Uint8Array((await alice.store.getIdentityKeyPair())!.pubKey)
    const bId = new Uint8Array((await bob.store.getIdentityKeyPair())!.pubKey)
    const fromAlice = await alice.signal.safetyNumber(bob.userId, bId)
    const fromBob = await bob.signal.safetyNumber(alice.userId, aId)
    expect(fromAlice).toHaveLength(60)
    expect(fromAlice).toBe(fromBob)
  })
})

describe('Group E2EE (MLS, RFC 9420)', () => {
  it('a group message can be decrypted by every valid member', async () => {
    const dir = new FakeDirectory()
    const [alice, bob, charlie] = [await makeDevice(dir, 'alice'), await makeDevice(dir, 'bob'), await makeDevice(dir, 'charlie')]
    const conv = await newGroup(dir, alice, [bob, charlie])
    expect(await bob.mls.epoch(conv)).toBe(1)

    const m = await alice.mls.encrypt(conv, utf8('hello group'))
    expect(containsBytes(fromBase64(m.ciphertext), utf8('hello group'))).toBe(false)
    for (const member of [bob, charlie]) {
      const r = await member.mls.process(conv, m.ciphertext)
      expect(r.kind).toBe('application')
      if (r.kind === 'application') expect(fromUtf8(r.plaintext)).toBe('hello group')
    }
    const fromBob = await bob.mls.encrypt(conv, utf8('bob here'))
    for (const member of [alice, charlie]) {
      const r = await member.mls.process(conv, fromBob.ciphertext)
      if (r.kind !== 'application') throw new Error('expected application message')
      expect(fromUtf8(r.plaintext)).toBe('bob here')
    }
  })

  it('a removed member cannot decrypt future messages (key rotation on removal)', async () => {
    const dir = new FakeDirectory()
    const [alice, bob, charlie] = [await makeDevice(dir, 'alice'), await makeDevice(dir, 'bob'), await makeDevice(dir, 'charlie')]
    const conv = await newGroup(dir, alice, [bob, charlie])

    const staged = await alice.mls.prepareRemove(conv, charlie.userId)
    await alice.mls.applyStaged(conv, staged)
    expect((await bob.mls.process(conv, staged.commit)).kind).toBe('commit')
    expect(await bob.mls.epoch(conv)).toBe(2)
    // Even if Charlie obtains and processes the commit, it gives him no new epoch secrets.
    const charlieView = await charlie.mls.process(conv, staged.commit)
    expect(charlieView.kind === 'commit' && charlieView.removedSelf).toBe(true)

    const secret = await alice.mls.encrypt(conv, utf8('charlie must not read this'))
    const r = await bob.mls.process(conv, secret.ciphertext)
    if (r.kind !== 'application') throw new Error('bob should decrypt')
    expect(fromUtf8(r.plaintext)).toBe('charlie must not read this')
    await expect(charlie.mls.process(conv, secret.ciphertext)).rejects.toThrow()
    expect((await alice.mls.members(conv)).map((m) => m.userId)).not.toContain(charlie.userId)
  })

  it('rejects a leaf whose MLS key is not bound to the device\'s Signal identity', async () => {
    const dir = new FakeDirectory()
    const alice = await makeDevice(dir, 'alice')
    const bob = await makeDevice(dir, 'bob')
    const mallory = await makeDevice(dir, 'mallory')
    const conv = uuid()
    await alice.mls.createGroup(conv, toBase64(crypto.getRandomValues(new Uint8Array(32))))
    // Server substitutes Mallory's signature key/binding into Bob's directory entry.
    const bobEntry = dir.devices.get(bob.deviceId)!
    const malloryEntry = dir.devices.get(mallory.deviceId)!
    bobEntry.bundle = { ...bobEntry.bundle, mlsSignatureKey: malloryEntry.bundle.mlsSignatureKey }
    const b = await dir.claimBundle(bob.userId, true)
    await expect(alice.mls.prepareAdd(conv, [{ userId: bob.userId, bundle: b.devices[0] }])).rejects.toThrow()
  })

  it('offline group members catch up on commits and messages in order', async () => {
    const dir = new FakeDirectory()
    const [alice, bob, charlie, dave] = [await makeDevice(dir, 'a'), await makeDevice(dir, 'b'), await makeDevice(dir, 'c'), await makeDevice(dir, 'd')]
    const conv = await newGroup(dir, alice, [bob, charlie])
    const log: string[] = [] // server-ordered ciphertexts while Bob is offline
    log.push((await alice.mls.encrypt(conv, utf8('m1'))).ciphertext)
    const b = await dir.claimBundle(dave.userId, true)
    const add = await alice.mls.prepareAdd(conv, [{ userId: dave.userId, bundle: b.devices[0] }])
    await alice.mls.applyStaged(conv, add)
    log.push(add.commit)
    log.push((await alice.mls.encrypt(conv, utf8('m2'))).ciphertext)
    const out = []
    for (const c of log) {
      const r = await bob.mls.process(conv, c)
      out.push(r.kind === 'application' ? fromUtf8(r.plaintext) : r.kind)
    }
    expect(out).toEqual(['m1', 'commit', 'm2'])
  })
})

describe('Payload format', () => {
  it('pads to 64-byte buckets and round-trips', () => {
    const p = { v: 1 as const, kind: 'text' as const, body: 'hi', sentAt: new Date().toISOString(), clientMessageId: uuid() }
    const enc = encodePayload(p)
    expect(enc.length % 64).toBe(0)
    expect(decodePayload(enc)).toEqual(p)
  })
})
