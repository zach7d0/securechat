/** Byte helpers. No cryptography here. */

export function utf8(s: string): Uint8Array {
  return new TextEncoder().encode(s)
}

export function fromUtf8(b: Uint8Array): string {
  return new TextDecoder().decode(b)
}

export function toBase64(b: Uint8Array | ArrayBuffer): string {
  const bytes = b instanceof Uint8Array ? b : new Uint8Array(b)
  let s = ''
  for (let i = 0; i < bytes.length; i += 0x8000) {
    s += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  }
  return btoa(s)
}

export function fromBase64(s: string): Uint8Array {
  const bin = atob(s)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

/** A fresh ArrayBuffer containing exactly these bytes (libsignal requires real ArrayBuffers). */
export function toArrayBuffer(b: Uint8Array): ArrayBuffer {
  const ab = new ArrayBuffer(b.byteLength)
  new Uint8Array(ab).set(b)
  return ab
}

/** libsignal returns ciphertext as a "binary string" (one char per byte). */
export function binaryStringToBytes(s: string): Uint8Array {
  const out = new Uint8Array(s.length)
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i) & 0xff
  return out
}

export function bytesToBinaryString(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000))
  return s
}

export function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let o = 0
  for (const p of parts) {
    out.set(p, o)
    o += p.length
  }
  return out
}

export function equalBytes(a: Uint8Array | ArrayBuffer, b: Uint8Array | ArrayBuffer): boolean {
  const x = a instanceof Uint8Array ? a : new Uint8Array(a)
  const y = b instanceof Uint8Array ? b : new Uint8Array(b)
  if (x.length !== y.length) return false
  let d = 0
  for (let i = 0; i < x.length; i++) d |= x[i] ^ y[i]
  return d === 0
}

export function uuid(): string {
  return crypto.randomUUID()
}
