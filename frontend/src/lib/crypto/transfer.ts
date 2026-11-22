import { Aes256Gcm, CipherSuite, DhkemX25519HkdfSha256, HkdfSha256 } from '@hpke/core'
import { concat, fromUtf8, toArrayBuffer, utf8 } from './bytes'
import { signWithIdentity, verifyIdentitySignature } from './curve'

/**
 * Device-to-device history transfer primitives. Only standard constructions from maintained
 * libraries: HPKE (RFC 9180, @hpke/core) base mode DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 /
 * AES-256-GCM for confidentiality, XEdDSA (libsignal) signatures by the devices' identity keys for
 * authenticity, SHA-256/512 (WebCrypto) for integrity and the comparison code.
 */
const suite = new CipherSuite({ kem: new DhkemX25519HkdfSha256(), kdf: new HkdfSha256(), aead: new Aes256Gcm() })

export interface EphemeralKey {
  publicKey: Uint8Array
  privateKey: Uint8Array
}

export async function newEphemeralKey(): Promise<EphemeralKey> {
  const kp = await suite.kem.generateKeyPair()
  return {
    publicKey: new Uint8Array(await suite.kem.serializePublicKey(kp.publicKey)),
    privateKey: new Uint8Array(await suite.kem.serializePrivateKey(kp.privateKey)),
  }
}

/**
 * Bytes the requesting (target) device signs: binds its ephemeral key to user + device and, for automatic
 * sync, to the requested time window (epoch milliseconds, so server-side timestamp formatting cannot
 * break or widen it).
 */
export function requestMessage(userId: string, targetDeviceId: string, ephemeralPublicKey: Uint8Array,
  window?: { from: number; to: number }): Uint8Array {
  if (!window) return concat(utf8(`securechat-history-request-v1:${userId}:${targetDeviceId}:`), ephemeralPublicKey)
  return concat(utf8(`securechat-history-request-v2:${userId}:${targetDeviceId}:${window.from}:${window.to}:`), ephemeralPublicKey)
}

export function manifestMessage(transferId: string, manifestJson: string): Uint8Array {
  return concat(utf8(`securechat-history-manifest-v1:${transferId}:`), utf8(manifestJson))
}

export const sign = (identityPrivateKey: ArrayBuffer, msg: Uint8Array) => signWithIdentity(identityPrivateKey, msg)
export const verify = (identityPublicKey: Uint8Array, msg: Uint8Array, sig: Uint8Array) =>
  verifyIdentitySignature(toArrayBuffer(identityPublicKey), msg, sig)

/**
 * 20-digit comparison code shown on BOTH devices before anything is sent. It binds the transfer id,
 * both identity keys and the ephemeral key, so a server that substitutes either device's key (to
 * read the history or to inject forged history) produces a different code. 20 digits ≈ 66 bits, far
 * beyond what a server could grind.
 */
export async function comparisonCode(transferId: string, targetIdentityKey: Uint8Array, ephemeralPublicKey: Uint8Array,
  sourceIdentityKey: Uint8Array): Promise<string> {
  const input = concat(utf8(`securechat-history-code-v1:${transferId}:`), targetIdentityKey, ephemeralPublicKey, sourceIdentityKey)
  const h = new Uint8Array(await crypto.subtle.digest('SHA-512', input as BufferSource))
  const groups: string[] = []
  for (let o = 0; o < 20; o += 5) {
    const n = (h[o] * 2 ** 32 + h[o + 1] * 2 ** 24 + h[o + 2] * 2 ** 16 + h[o + 3] * 2 ** 8 + h[o + 4]) % 100000
    groups.push(n.toString().padStart(5, '0'))
  }
  return groups.join(' ')
}

export async function sha256Hex(b: Uint8Array): Promise<string> {
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', b as BufferSource))].map((x) => x.toString(16).padStart(2, '0')).join('')
}

async function pipe(data: Uint8Array, stream: CompressionStream | DecompressionStream): Promise<Uint8Array> {
  const out = new Blob([data as BlobPart]).stream().pipeThrough(stream)
  return new Uint8Array(await new Response(out).arrayBuffer())
}

export const gzip = (data: Uint8Array) => pipe(data, new CompressionStream('gzip'))
export const gunzip = (data: Uint8Array) => pipe(data, new DecompressionStream('gzip'))

/** gzip(JSON) then HPKE-seal to the target's ephemeral key. */
export async function sealArchive(archive: unknown, recipientPublicKey: Uint8Array, transferId: string): Promise<{ ciphertext: Uint8Array; enc: Uint8Array }> {
  const plain = await pipe(utf8(JSON.stringify(archive)), new CompressionStream('gzip'))
  const pk = await suite.kem.deserializePublicKey(recipientPublicKey)
  const info = utf8(`securechat-history-archive-v1:${transferId}`)
  const r = await suite.seal({ recipientPublicKey: pk, info }, plain)
  return { ciphertext: new Uint8Array(r.ct), enc: new Uint8Array(r.enc) }
}

export async function openArchive<T>(ciphertext: Uint8Array, enc: Uint8Array, recipientPrivateKey: Uint8Array, transferId: string): Promise<T> {
  const sk = await suite.kem.deserializePrivateKey(recipientPrivateKey)
  const info = utf8(`securechat-history-archive-v1:${transferId}`)
  const plain = new Uint8Array(await suite.open({ recipientKey: sk, enc, info }, ciphertext))
  return JSON.parse(fromUtf8(await pipe(plain, new DecompressionStream('gzip')))) as T
}
