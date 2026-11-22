import { KeyHelper } from '@privacyresearch/libsignal-protocol-typescript'
import type { DeviceBundle, DeviceIdentity, UserBundle } from '../lib/api/types'
import { toBase64, uuid } from '../lib/crypto/bytes'
import { signWithIdentity } from '../lib/crypto/curve'
import type { KeyDirectory } from '../lib/crypto/directory'
import { MemoryKeyStore } from '../lib/crypto/keystore'
import { bindingMessage, MlsService } from '../lib/crypto/mls'
import { SignalProtocolStore } from '../lib/crypto/signalStore'
import { SignalService } from '../lib/crypto/signal'

/**
 * In-memory stand-in for the server's public key directory. Like the real server it only ever
 * holds PUBLIC material, which lets tests assert the "server" cannot decrypt.
 */
export class FakeDirectory implements KeyDirectory {
  devices = new Map<string, { userId: string; bundle: Omit<DeviceBundle, 'preKey' | 'mlsKeyPackage'>; preKeys: DeviceBundle['preKey'][]; keyPackages: NonNullable<DeviceBundle['mlsKeyPackage']>[] }>()

  async claimBundle(userId: string, includeMls: boolean, deviceIds?: string[]): Promise<UserBundle> {
    const devices: DeviceBundle[] = []
    for (const d of this.devices.values()) {
      if (d.userId !== userId) continue
      if (deviceIds && deviceIds.length > 0 && !deviceIds.includes(d.bundle.deviceId)) continue
      const kp = includeMls ? d.keyPackages.shift() : undefined
      if (includeMls && !kp) continue
      devices.push({ ...d.bundle, preKey: d.preKeys.shift(), mlsKeyPackage: kp })
    }
    return { userId, devices }
  }

  async identities(userId: string): Promise<DeviceIdentity[]> {
    return [...this.devices.values()].filter((d) => d.userId === userId).map((d) => ({
      deviceId: d.bundle.deviceId, registrationId: d.bundle.registrationId, identityKey: d.bundle.identityKey,
      mlsSignatureKey: d.bundle.mlsSignatureKey, mlsSignatureBinding: d.bundle.mlsSignatureBinding, keyVersion: 1, createdAt: '',
    }))
  }

  /** Everything the server could know. */
  publicDump(): string {
    return JSON.stringify([...this.devices.values()])
  }
}

export interface TestDevice {
  name: string
  userId: string
  deviceId: string
  ks: MemoryKeyStore
  store: SignalProtocolStore
  signal: SignalService
  mls: MlsService
}

export async function makeDevice(dir: FakeDirectory, name: string, opts: { preKeys?: number; keyPackages?: number; userId?: string } = {}): Promise<TestDevice> {
  const userId = opts.userId ?? uuid()
  const deviceId = uuid()
  const ks = new MemoryKeyStore()
  const store = new SignalProtocolStore(ks)
  const identity = await KeyHelper.generateIdentityKeyPair()
  const registrationId = KeyHelper.generateRegistrationId()
  await store.setLocalIdentity(identity, registrationId)
  const spk = await KeyHelper.generateSignedPreKey(identity, 1)
  await store.storeSignedPreKey(1, spk.keyPair)
  const preKeys = []
  for (let i = 1; i <= (opts.preKeys ?? 5); i++) {
    const pk = await KeyHelper.generatePreKey(i)
    await store.storePreKey(i, pk.keyPair)
    preKeys.push({ keyId: i, publicKey: toBase64(pk.keyPair.pubKey) })
  }
  const self = { userId, deviceId }
  const mls = new MlsService(ks, dir, store, self)
  const sig = await mls.ensureSignatureKey()
  const binding = await signWithIdentity(identity.privKey, bindingMessage(userId, deviceId, sig.publicKey))
  const keyPackages = await mls.generateKeyPackages(opts.keyPackages ?? 3)
  dir.devices.set(deviceId, {
    userId,
    bundle: {
      deviceId, registrationId, identityKey: toBase64(identity.pubKey),
      mlsSignatureKey: toBase64(sig.publicKey), mlsSignatureBinding: toBase64(binding),
      signedPreKey: { keyId: 1, publicKey: toBase64(spk.keyPair.pubKey), signature: toBase64(spk.signature) },
    },
    preKeys,
    keyPackages,
  })
  return { name, userId, deviceId, ks, store, signal: new SignalService(store, dir, self), mls }
}
