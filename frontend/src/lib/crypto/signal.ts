import {
  SessionBuilder, SessionCipher, SignalProtocolAddress,
} from '@privacyresearch/libsignal-protocol-typescript'
import type { DeviceBundle, EnvelopeWire } from '../api/types'
import { binaryStringToBytes, bytesToBinaryString, fromBase64, toArrayBuffer, toBase64 } from './bytes'
import type { KeyDirectory } from './directory'
import { displayableFingerprint } from './fingerprint'
import { IdentityChangedError, SignalProtocolStore } from './signalStore'

export interface DeviceTarget {
  userId: string
  deviceId: string
}

/**
 * One-to-one encryption with the Signal Protocol (X3DH + Double Ratchet) via
 * @privacyresearch/libsignal-protocol-typescript. Every device pair has its own session; a
 * message is encrypted once per recipient device. Encryption happens only here, on the client.
 */
export class SignalService {
  constructor(
    readonly store: SignalProtocolStore,
    private readonly directory: KeyDirectory,
    private readonly self: { userId: string; deviceId: string },
  ) {}

  static address(deviceId: string): SignalProtocolAddress {
    return new SignalProtocolAddress(deviceId, 1)
  }

  /** Builds sessions (X3DH) for any target devices that do not have one yet. */
  async ensureSessions(targets: DeviceTarget[]): Promise<void> {
    const missingByUser = new Map<string, Set<string>>()
    for (const t of targets) {
      if (!(await this.store.hasSession(t.deviceId))) {
        if (!missingByUser.has(t.userId)) missingByUser.set(t.userId, new Set())
        missingByUser.get(t.userId)!.add(t.deviceId)
      }
    }
    for (const [userId, devices] of missingByUser) {
      const bundle = await this.directory.claimBundle(userId, false, [...devices])
      for (const d of bundle.devices) {
        if (devices.has(d.deviceId)) await this.processBundle(d)
      }
    }
  }

  async processBundle(d: DeviceBundle): Promise<void> {
    const builder = new SessionBuilder(this.store, SignalService.address(d.deviceId))
    // processPreKey verifies the signed prekey signature against the identity key and runs X3DH.
    try {
      await builder.processPreKey({
        registrationId: d.registrationId,
        identityKey: toArrayBuffer(fromBase64(d.identityKey)),
        signedPreKey: {
          keyId: d.signedPreKey.keyId,
          publicKey: toArrayBuffer(fromBase64(d.signedPreKey.publicKey)),
          signature: toArrayBuffer(fromBase64(d.signedPreKey.signature)),
        },
        // No one-time prekey left on the server → X3DH without OPK (protocol-supported fallback).
        preKey: d.preKey ? { keyId: d.preKey.keyId, publicKey: toArrayBuffer(fromBase64(d.preKey.publicKey)) } : undefined,
      })
    } catch (e) {
      if (e instanceof Error && /untrusted identity/i.test(e.message)) throw new IdentityChangedError(d.deviceId)
      throw e
    }
  }

  async encrypt(targets: DeviceTarget[], plaintext: Uint8Array): Promise<EnvelopeWire[]> {
    await this.ensureSessions(targets)
    const out: EnvelopeWire[] = []
    for (const t of targets) {
      const cipher = new SessionCipher(this.store, SignalService.address(t.deviceId))
      const msg = await cipher.encrypt(toArrayBuffer(plaintext))
      if (msg.body === undefined) throw new Error('encryption produced no body')
      out.push({ deviceId: t.deviceId, ciphertext: toBase64(binaryStringToBytes(msg.body)), signalType: msg.type })
    }
    return out
  }

  /** Decrypts an envelope sent by {@code senderDeviceId}. Type 3 = PreKeyWhisperMessage (session setup). */
  async decrypt(senderDeviceId: string, ciphertext: Uint8Array, signalType: number): Promise<Uint8Array> {
    const cipher = new SessionCipher(this.store, SignalService.address(senderDeviceId))
    const body = bytesToBinaryString(ciphertext)
    const pt = signalType === 3
      ? await cipher.decryptPreKeyWhisperMessage(body, 'binary')
      : await cipher.decryptWhisperMessage(body, 'binary')
    return new Uint8Array(pt)
  }

  /** 60-digit safety number for (me, my device) vs (them, their device). */
  async safetyNumber(remoteUserId: string, remoteIdentityKey: Uint8Array): Promise<string> {
    const me = await this.store.getIdentityKeyPair()
    if (!me) throw new Error('no local identity')
    return displayableFingerprint(this.self.userId, me.pubKey, remoteUserId, toArrayBuffer(remoteIdentityKey))
  }
}
