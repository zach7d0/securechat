/**
 * Signal "displayable fingerprint" (version 0, the format produced by libsignal's
 * FingerprintGenerator): per party SHA-512 iterated N times over version || key || identifier,
 * then 6 chunks of 5 decimal digits; the two 30-digit halves are sorted and concatenated.
 *
 * Identical output to the library (verified in src/test/fingerprint.test.ts), but uses native
 * WebCrypto SHA-512: the library's bundled pure-JS hash yields to the event loop with setTimeout
 * on every round in browsers, taking ~40 s for the standard 5200 iterations.
 */
export async function displayableFingerprint(localId: string, localKey: ArrayBuffer, remoteId: string,
  remoteKey: ArrayBuffer, iterations = 5200): Promise<string> {
  const [a, b] = await Promise.all([displayStringFor(localId, localKey, iterations), displayStringFor(remoteId, remoteKey, iterations)])
  return [a, b].sort().join('')
}

async function displayStringFor(identifier: string, key: ArrayBuffer, iterations: number): Promise<string> {
  const keyBytes = new Uint8Array(key)
  const id = new Uint8Array(identifier.length)
  for (let i = 0; i < identifier.length; i++) id[i] = identifier.charCodeAt(i) & 0xff
  // version (uint16, value 0) || key || identifier
  let data = new Uint8Array(2 + keyBytes.length + id.length)
  data.set(keyBytes, 2)
  data.set(id, 2 + keyBytes.length)
  for (let i = 0; i < iterations; i++) {
    const input = new Uint8Array(data.length + keyBytes.length)
    input.set(data)
    input.set(keyBytes, data.length)
    data = new Uint8Array(await crypto.subtle.digest('SHA-512', input))
  }
  let out = ''
  for (let offset = 0; offset < 30; offset += 5) out += chunk(data, offset)
  return out
}

function chunk(h: Uint8Array, o: number): string {
  const n = (h[o] * 2 ** 32 + h[o + 1] * 2 ** 24 + h[o + 2] * 2 ** 16 + h[o + 3] * 2 ** 8 + h[o + 4]) % 100000
  return n.toString().padStart(5, '0')
}
