import * as Signal from '@privacyresearch/libsignal-protocol-typescript'
import type { AsyncCurveType } from '@privacyresearch/libsignal-protocol-typescript'

let curvePromise: Promise<AsyncCurveType> | null = null

/** The library's XEdDSA / Curve25519 implementation (CJS default export interop differs by bundler). */
export function signalCurve(): Promise<AsyncCurveType> {
  if (!curvePromise) {
    curvePromise = (async () => {
      const d = (Signal as unknown as { default: unknown }).default as
        | (() => Promise<{ Curve: { async: AsyncCurveType } }>)
        | { default: () => Promise<{ Curve: { async: AsyncCurveType } }> }
      const factory = typeof d === 'function' ? d : d.default
      return (await factory()).Curve.async
    })()
  }
  return curvePromise
}

/** XEdDSA signature by a Signal identity key. */
export async function signWithIdentity(privKey: ArrayBuffer, message: Uint8Array): Promise<Uint8Array> {
  const c = await signalCurve()
  const ab = new ArrayBuffer(message.byteLength)
  new Uint8Array(ab).set(message)
  return new Uint8Array(await c.calculateSignature(privKey, ab))
}

/** The library resolves for a valid signature and throws for an invalid one. */
export async function verifyIdentitySignature(pubKey: ArrayBuffer, message: Uint8Array, signature: Uint8Array): Promise<boolean> {
  const c = await signalCurve()
  const msg = new ArrayBuffer(message.byteLength)
  new Uint8Array(msg).set(message)
  const sig = new ArrayBuffer(signature.byteLength)
  new Uint8Array(sig).set(signature)
  try {
    const invalid = await (c.verifySignature(pubKey, msg, sig) as unknown as Promise<boolean>)
    return invalid !== true
  } catch {
    return false
  }
}
