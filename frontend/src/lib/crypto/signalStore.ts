import type { Direction, KeyPairType, SessionRecordType, StorageType } from '@privacyresearch/libsignal-protocol-typescript'
import { equalBytes, toBase64 } from './bytes'
import type { KeyStore } from './keystore'

export class IdentityChangedError extends Error {
  constructor(public readonly address: string) {
    super(`Identity key changed for ${address}`)
  }
}

const S = 'signal'

/**
 * libsignal StorageType backed by the encrypted key store. Signal addresses use the server device
 * id as the name (device id 1), so identity trust is tracked per device.
 * Trust model: trust-on-first-use; a changed identity is never silently accepted.
 */
export class SignalProtocolStore implements StorageType {
  constructor(private readonly ks: KeyStore) {}

  async getIdentityKeyPair(): Promise<KeyPairType | undefined> {
    return this.ks.get<KeyPairType>(S, 'identityKey')
  }

  async getLocalRegistrationId(): Promise<number | undefined> {
    return this.ks.get<number>(S, 'registrationId')
  }

  async setLocalIdentity(identity: KeyPairType, registrationId: number): Promise<void> {
    await this.ks.put(S, 'identityKey', identity)
    await this.ks.put(S, 'registrationId', registrationId)
  }

  /** {@code identifier} is the address name (= remote device id). */
  async isTrustedIdentity(identifier: string, identityKey: ArrayBuffer, _direction: Direction): Promise<boolean> {
    const known = await this.ks.get<ArrayBuffer>(S, `trusted:${nameOf(identifier)}`)
    return !known || equalBytes(known, identityKey)
  }

  async saveIdentity(encodedAddress: string, publicKey: ArrayBuffer): Promise<boolean> {
    const name = nameOf(encodedAddress)
    const known = await this.ks.get<ArrayBuffer>(S, `trusted:${name}`)
    if (known && !equalBytes(known, publicKey)) throw new IdentityChangedError(name)
    if (!known) await this.ks.put(S, `trusted:${name}`, publicKey)
    return false
  }

  /** Explicit user approval of a new identity for a device (drops the old session). */
  async approveIdentity(deviceId: string, publicKey: ArrayBuffer): Promise<void> {
    await this.ks.put(S, `trusted:${deviceId}`, publicKey)
    await this.ks.delete(S, `session:${deviceId}.1`)
  }

  async trustedIdentity(deviceId: string): Promise<ArrayBuffer | undefined> {
    return this.ks.get<ArrayBuffer>(S, `trusted:${deviceId}`)
  }

  async loadPreKey(keyId: string | number): Promise<KeyPairType | undefined> {
    return this.ks.get<KeyPairType>(S, `prekey:${keyId}`)
  }

  async storePreKey(keyId: string | number, keyPair: KeyPairType): Promise<void> {
    await this.ks.put(S, `prekey:${keyId}`, keyPair)
  }

  async removePreKey(keyId: string | number): Promise<void> {
    await this.ks.delete(S, `prekey:${keyId}`)
  }

  async loadSignedPreKey(keyId: string | number): Promise<KeyPairType | undefined> {
    return this.ks.get<KeyPairType>(S, `signed:${keyId}`)
  }

  async storeSignedPreKey(keyId: string | number, keyPair: KeyPairType): Promise<void> {
    await this.ks.put(S, `signed:${keyId}`, keyPair)
  }

  async removeSignedPreKey(keyId: string | number): Promise<void> {
    await this.ks.delete(S, `signed:${keyId}`)
  }

  async loadSession(encodedAddress: string): Promise<SessionRecordType | undefined> {
    return this.ks.get<SessionRecordType>(S, `session:${encodedAddress}`)
  }

  async storeSession(encodedAddress: string, record: SessionRecordType): Promise<void> {
    await this.ks.put(S, `session:${encodedAddress}`, record)
  }

  async hasSession(deviceId: string): Promise<boolean> {
    return (await this.loadSession(`${deviceId}.1`)) !== undefined
  }

  async fingerprintOf(deviceId: string): Promise<string | undefined> {
    const k = await this.trustedIdentity(deviceId)
    return k ? toBase64(k) : undefined
  }
}

function nameOf(addressOrName: string): string {
  const i = addressOrName.lastIndexOf('.')
  return i > 0 && /^\d+$/.test(addressOrName.slice(i + 1)) ? addressOrName.slice(0, i) : addressOrName
}
