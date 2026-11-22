import { KeyHelper } from '@privacyresearch/libsignal-protocol-typescript'
import type { ApiClient } from '../api/http'
import { toBase64, uuid } from './bytes'
import { signWithIdentity } from './curve'
import type { KeyStore } from './keystore'
import { bindingMessage, type MlsService } from './mls'
import type { SignalProtocolStore } from './signalStore'

export interface DeviceRecord {
  userId: string
  deviceId: string
  registrationId: number
  createdAt: string
  /** true once every public key was uploaded (provisioning is resumable). */
  published: boolean
}

export const PREKEY_BATCH = 100
export const PREKEY_LOW = 20
export const KEY_PACKAGE_BATCH = 20
export const KEY_PACKAGE_LOW = 5
export const SIGNED_PREKEY_MAX_AGE_MS = 7 * 24 * 3600 * 1000

export async function loadDevice(ks: KeyStore): Promise<DeviceRecord | undefined> {
  return ks.get<DeviceRecord>('meta', 'device')
}

/**
 * Device bootstrap (design doc §3.2), entirely client-side:
 *  1. generate the device id, Signal identity key pair and registration id locally;
 *  2. persist the private material in the encrypted local key store FIRST;
 *  3. register the device (User Service) and bind the session to it (Auth Service);
 *  4. upload PUBLIC material only: identity key + MLS signature key with its identity binding,
 *     signed prekey, one-time prekeys and MLS key packages (Key Management Service).
 * Private keys never leave this function's key store.
 */
export async function provisionDevice(api: ApiClient, ks: KeyStore, signal: SignalProtocolStore,
  makeMls: (deviceId: string) => MlsService, userId: string, deviceName: string): Promise<DeviceRecord> {
  let record = await loadDevice(ks)
  if (!record) {
    const identity = await KeyHelper.generateIdentityKeyPair()
    let registrationId = KeyHelper.generateRegistrationId()
    while (registrationId < 1 || registrationId > 16380) registrationId = KeyHelper.generateRegistrationId()
    await signal.setLocalIdentity(identity, registrationId)
    record = { userId, deviceId: uuid(), registrationId, createdAt: new Date().toISOString(), published: false }
    await ks.put('meta', 'device', record)
  }
  if (record.published) return record
  await api.registerDevice(record.deviceId, deviceName)
  await api.bindDevice(record.deviceId)
  const identity = (await signal.getIdentityKeyPair())!
  const mls = makeMls(record.deviceId)
  const sig = await mls.ensureSignatureKey()
  const binding = await signWithIdentity(identity.privKey, bindingMessage(userId, record.deviceId, sig.publicKey))
  await api.uploadIdentity(toBase64(identity.pubKey), record.registrationId, toBase64(sig.publicKey), toBase64(binding))
  await rotateSignedPreKey(api, ks, signal)
  await uploadPreKeys(api, ks, signal, PREKEY_BATCH)
  await api.uploadKeyPackages([...await mls.generateKeyPackages(KEY_PACKAGE_BATCH), ...await mls.generateKeyPackages(1, true)])
  record = { ...record, published: true }
  await ks.put('meta', 'device', record)
  return record
}

export async function rotateSignedPreKey(api: ApiClient, ks: KeyStore, signal: SignalProtocolStore): Promise<void> {
  const identity = await signal.getIdentityKeyPair()
  if (!identity) throw new Error('no identity')
  const keyId = ((await ks.get<number>('meta', 'nextSignedPreKeyId')) ?? 1)
  const spk = await KeyHelper.generateSignedPreKey(identity, keyId)
  await signal.storeSignedPreKey(keyId, spk.keyPair)
  await ks.put('meta', 'nextSignedPreKeyId', keyId + 1)
  await api.uploadSignedPreKey(keyId, toBase64(spk.keyPair.pubKey), toBase64(spk.signature))
  const history = ((await ks.get<{ keyId: number; createdAt: number }[]>('meta', 'signedPreKeys')) ?? [])
  history.push({ keyId, createdAt: Date.now() })
  // Keep superseded signed prekeys 30 days so in-flight session setups still decrypt, then delete.
  const keep = history.filter((h) => h.keyId === keyId || Date.now() - h.createdAt < 30 * 24 * 3600 * 1000)
  for (const h of history) if (!keep.includes(h)) await signal.removeSignedPreKey(h.keyId)
  await ks.put('meta', 'signedPreKeys', keep)
}

export async function uploadPreKeys(api: ApiClient, ks: KeyStore, signal: SignalProtocolStore, count: number): Promise<void> {
  const start = (await ks.get<number>('meta', 'nextPreKeyId')) ?? 1
  const batch = []
  for (let id = start; id < start + count; id++) {
    const pk = await KeyHelper.generatePreKey(id)
    await signal.storePreKey(id, pk.keyPair)
    batch.push({ keyId: id, publicKey: toBase64(pk.keyPair.pubKey) })
  }
  await ks.put('meta', 'nextPreKeyId', start + count)
  await api.uploadPreKeys(batch)
}

/** Replenishment + signed prekey rotation; on start and on the server's "prekeys_low" signal. */
export async function replenishKeys(api: ApiClient, ks: KeyStore, signal: SignalProtocolStore, mls: MlsService): Promise<void> {
  const s = await api.keyStatus()
  if (s.oneTimePreKeys < PREKEY_LOW) await uploadPreKeys(api, ks, signal, PREKEY_BATCH)
  if (s.mlsKeyPackages < KEY_PACKAGE_LOW) await api.uploadKeyPackages(await mls.generateKeyPackages(KEY_PACKAGE_BATCH))
  if (!s.lastResortKeyPackage) await api.uploadKeyPackages(await mls.generateKeyPackages(1, true))
  const created = s.signedPreKeyCreatedAt ? Date.parse(s.signedPreKeyCreatedAt) : 0
  if (Date.now() - created > SIGNED_PREKEY_MAX_AGE_MS) await rotateSignedPreKey(api, ks, signal)
}
