import { openDB, type IDBPDatabase } from 'idb'
import { utf8, fromUtf8 } from './bytes'
import { deserialize, serialize } from './serializer'

/**
 * Local storage for private key material, protocol sessions, MLS group state and decrypted
 * message history. Nothing in here is ever sent to the server.
 */
export interface KeyStore {
  get<T>(store: string, key: string): Promise<T | undefined>
  put(store: string, key: string, value: unknown): Promise<void>
  delete(store: string, key: string): Promise<void>
  keys(store: string): Promise<string[]>
  /** Removes everything (used when a device is revoked / "forget this device"). */
  destroy(): Promise<void>
}

/** In-memory implementation (tests, headless simulation). Values are deep-copied. */
export class MemoryKeyStore implements KeyStore {
  private data = new Map<string, string>()

  async get<T>(store: string, key: string): Promise<T | undefined> {
    const v = this.data.get(`${store}/${key}`)
    return v === undefined ? undefined : deserialize<T>(v)
  }

  async put(store: string, key: string, value: unknown): Promise<void> {
    this.data.set(`${store}/${key}`, serialize(value))
  }

  async delete(store: string, key: string): Promise<void> {
    this.data.delete(`${store}/${key}`)
  }

  async keys(store: string): Promise<string[]> {
    const prefix = `${store}/`
    return [...this.data.keys()].filter((k) => k.startsWith(prefix)).map((k) => k.slice(prefix.length)).sort()
  }

  async destroy(): Promise<void> {
    this.data.clear()
  }

  /** For tests: raw serialized records (what an attacker reading storage would see). */
  dump(): string[] {
    return [...this.data.values()]
  }
}

interface EncryptedRecord {
  iv: Uint8Array
  ct: ArrayBuffer
}

/**
 * IndexedDB store. Every record is AES-256-GCM encrypted under a NON-EXTRACTABLE WebCrypto key that
 * is itself stored in IndexedDB as a CryptoKey object: page scripts can use it but not export its
 * bytes, and copying the database files off disk does not reveal key material in the clear.
 * (This does not defend against malicious script running in the page; see docs/security.md.)
 */
export class IndexedDbKeyStore implements KeyStore {
  private constructor(
    private readonly db: IDBPDatabase,
    private readonly wrapKey: CryptoKey,
    private readonly name: string,
  ) {}

  static async open(name: string): Promise<IndexedDbKeyStore> {
    const db = await openDB(name, 1, {
      upgrade(d) {
        d.createObjectStore('records')
        d.createObjectStore('meta')
      },
    })
    let key = (await db.get('meta', 'wrapKey')) as CryptoKey | undefined
    if (!key) {
      key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
      await db.put('meta', key, 'wrapKey')
    }
    return new IndexedDbKeyStore(db, key, name)
  }

  async get<T>(store: string, key: string): Promise<T | undefined> {
    const rec = (await this.db.get('records', `${store}/${key}`)) as EncryptedRecord | undefined
    if (!rec) return undefined
    const pt = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: rec.iv as BufferSource, additionalData: utf8(`${store}/${key}`) as BufferSource },
      this.wrapKey,
      rec.ct,
    )
    return deserialize<T>(fromUtf8(new Uint8Array(pt)))
  }

  async put(store: string, key: string, value: unknown): Promise<void> {
    const iv = crypto.getRandomValues(new Uint8Array(12))
    const ct = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: iv as BufferSource, additionalData: utf8(`${store}/${key}`) as BufferSource },
      this.wrapKey,
      utf8(serialize(value)) as BufferSource,
    )
    await this.db.put('records', { iv, ct } satisfies EncryptedRecord, `${store}/${key}`)
  }

  async delete(store: string, key: string): Promise<void> {
    await this.db.delete('records', `${store}/${key}`)
  }

  async keys(store: string): Promise<string[]> {
    const prefix = `${store}/`
    const all = await this.db.getAllKeys('records', IDBKeyRange.bound(prefix, `${prefix}￿`))
    return all.map((k) => String(k).slice(prefix.length))
  }

  async destroy(): Promise<void> {
    this.db.close()
    await new Promise<void>((resolve, reject) => {
      const req = indexedDB.deleteDatabase(this.name)
      req.onsuccess = () => resolve()
      req.onerror = () => reject(req.error)
      req.onblocked = () => resolve()
    })
  }
}
