import { describe, expect, it } from 'vitest'
import { toBase64, utf8, uuid } from '../lib/crypto/bytes'
import { FakeDirectory, makeDevice, type TestDevice } from './harness'

/**
 * Regression: a device joining a group whose tree has several unmerged leaves in different subtrees
 * (members added in separate commits without a path update in between) was rejected by ts-mls 1.6.4 with
 * "non-blank intermediate node must list leaf node in its unmerged_leaves" — the library required the
 * unmerged-leaf lists of all ancestors to be EQUAL instead of CONTAINING the leaf (RFC 9420 §7.9.2).
 */
describe('MLS join into a tree with unmerged leaves in several subtrees', () => {
  it('a new member can join after several separate adds, removals and re-adds', async () => {
    const dir = new FakeDirectory()
    const owner = await makeDevice(dir, 'owner')
    const others: TestDevice[] = []
    for (let i = 0; i < 6; i++) others.push(await makeDevice(dir, `m${i}`))
    const conv = uuid()
    await owner.mls.createGroup(conv, toBase64(crypto.getRandomValues(new Uint8Array(32))))
    const add = async (d: TestDevice) => {
      const b = await dir.claimBundle(d.userId, true)
      const staged = await owner.mls.prepareAdd(conv, [{ userId: d.userId, bundle: b.devices[0] }])
      await owner.mls.applyStaged(conv, staged)
      return staged
    }
    // grow the tree, then remove a member in the middle and keep adding (add-only commits leave unmerged leaves)
    for (const d of others.slice(0, 4)) await add(d)
    const rm = await owner.mls.prepareRemove(conv, others[1].userId)
    await owner.mls.applyStaged(conv, rm)
    await add(others[4])
    const last = await add(others[5])
    // the newest member must be able to join and read
    await others[5].mls.joinFromWelcome(conv, last.welcome!)
    const msg = await owner.mls.encrypt(conv, utf8('hello'))
    const r = await others[5].mls.process(conv, msg.ciphertext)
    expect(r.kind).toBe('application')
  })
})
