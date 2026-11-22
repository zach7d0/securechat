import { fromBase64, toBase64 } from './bytes'

/**
 * Lossless JSON encoding for key-store records: preserves Uint8Array, ArrayBuffer, bigint, Map
 * and Set, which libsignal and ts-mls state objects contain.
 */
export function serialize(value: unknown): string {
  return JSON.stringify(value, function (this: Record<string, unknown>, key: string) {
    const raw = key === '' ? value : this[key]
    if (raw instanceof Uint8Array) return { $u8: toBase64(raw) }
    if (raw instanceof ArrayBuffer) return { $ab: toBase64(raw) }
    if (typeof raw === 'bigint') return { $bi: raw.toString() }
    if (raw instanceof Map) return { $map: [...raw.entries()] }
    if (raw instanceof Set) return { $set: [...raw.values()] }
    return raw
  })
}

export function deserialize<T>(text: string): T {
  return JSON.parse(text, (_key, v) => {
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      const keys = Object.keys(v)
      if (keys.length === 1) {
        if ('$u8' in v) return fromBase64(v.$u8)
        if ('$ab' in v) {
          const b = fromBase64(v.$ab)
          return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength)
        }
        if ('$bi' in v) return BigInt(v.$bi)
        if ('$map' in v) return new Map(v.$map)
        if ('$set' in v) return new Set(v.$set)
      }
    }
    return v
  }) as T
}
