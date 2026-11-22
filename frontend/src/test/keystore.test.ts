import { describe, expect, it } from 'vitest'
import { IndexedDbKeyStore, MemoryKeyStore } from '../lib/crypto/keystore'
import { deserialize, serialize } from '../lib/crypto/serializer'

describe('key store', () => {
  it('serializer preserves binary and structured values', () => {
    const v = { a: new Uint8Array([1, 2, 3]), b: new Uint8Array([9]).buffer, n: 12345678901234567890n, m: new Map([['k', 1]]), s: new Set([1, 2]) }
    const r = deserialize<typeof v>(serialize(v))
    expect(r.a).toEqual(v.a)
    expect(new Uint8Array(r.b)).toEqual(new Uint8Array([9]))
    expect(r.b).toBeInstanceOf(ArrayBuffer)
    expect(r.n).toBe(v.n)
    expect(r.m.get('k')).toBe(1)
    expect(r.s.has(2)).toBe(true)
  })

  it('IndexedDB records are encrypted at rest with a non-extractable key', async () => {
    const ks = await IndexedDbKeyStore.open('test-' + crypto.randomUUID())
    await ks.put('signal', 'identityKey', { privKey: 'VERY-SECRET-PRIVATE-KEY' })
    expect(await ks.get('signal', 'identityKey')).toEqual({ privKey: 'VERY-SECRET-PRIVATE-KEY' })
    expect(await ks.keys('signal')).toEqual(['identityKey'])

    const raw = await new Promise<unknown>((resolve) => {
      const req = indexedDB.open((ks as unknown as { name: string }).name)
      req.onsuccess = () => {
        const tx = req.result.transaction('records')
        const get = tx.objectStore('records').get('signal/identityKey')
        get.onsuccess = () => resolve(get.result)
      }
    })
    const ct = new Uint8Array((raw as { ct: ArrayBuffer }).ct)
    expect(new TextDecoder().decode(ct)).not.toContain('VERY-SECRET')
    await ks.destroy()
  })

  it('memory store isolates copies', async () => {
    const ks = new MemoryKeyStore()
    const obj = { x: 1 }
    await ks.put('s', 'k', obj)
    obj.x = 2
    expect(await ks.get('s', 'k')).toEqual({ x: 1 })
  })
})
