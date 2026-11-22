import * as M from 'ts-mls'
import type { DeviceBundle } from '../api/types'
import { concat, equalBytes, fromBase64, fromUtf8, toArrayBuffer, toBase64, utf8, uuid } from './bytes'
import { verifyIdentitySignature } from './curve'
import type { KeyDirectory } from './directory'
import type { KeyStore } from './keystore'
import type { SignalProtocolStore } from './signalStore'

/** RFC 9420 ciphersuite 0x0001: X25519 / AES-128-GCM / SHA-256 / Ed25519. */
export const MLS_CIPHERSUITE = 'MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519'

type ClientState = M.ClientState
type CiphersuiteImpl = Awaited<ReturnType<typeof M.getCiphersuiteImpl>>

let implPromise: Promise<CiphersuiteImpl> | null = null
function cipherSuite(): Promise<CiphersuiteImpl> {
  if (!implPromise) implPromise = M.getCiphersuiteImpl(M.getCiphersuiteFromName(MLS_CIPHERSUITE))
  return implPromise
}

/**
 * Bytes signed by a device's Signal identity key to bind its long-term MLS signature key:
 * "securechat-mls-binding-v1:" || userId || ":" || deviceId || ":" || mlsSignaturePublicKey
 */
export function bindingMessage(userId: string, deviceId: string, mlsSignatureKey: Uint8Array): Uint8Array {
  return concat(utf8(`securechat-mls-binding-v1:${userId}:${deviceId}:`), mlsSignatureKey)
}

export function credentialIdentity(userId: string, deviceId: string): Uint8Array {
  return utf8(`${userId}:${deviceId}`)
}

export function parseCredential(c: M.Credential): { userId: string; deviceId: string } | null {
  if (c.credentialType !== 'basic') return null
  const [userId, deviceId, ...rest] = fromUtf8(c.identity).split(':')
  return userId && deviceId && rest.length === 0 ? { userId, deviceId } : null
}

export interface StagedCommit {
  commit: string
  welcome?: string
  epoch: number
  newState: ClientState
}

export type ProcessResult =
  | { kind: 'application'; plaintext: Uint8Array; epoch: number }
  | { kind: 'commit'; epoch: number; removedSelf: boolean }
  | { kind: 'stale'; epoch: number }

interface StoredKeyPackage {
  publicPackage: Uint8Array
  privatePackage: M.PrivateKeyPackage
  lastResort: boolean
}

/**
 * Group encryption with MLS (RFC 9420) via ts-mls. The server only stores and orders Commits and
 * routes Welcomes; group secrets exist only in client state. Every leaf credential is checked by
 * {@link validateCredential}: its MLS signature key must be the one bound (XEdDSA signature) to that
 * device's Signal identity key, which is the key covered by safety numbers.
 */
export class MlsService {
  constructor(
    private readonly ks: KeyStore,
    private readonly directory: KeyDirectory,
    private readonly signalStore: SignalProtocolStore,
    private readonly self: { userId: string; deviceId: string },
  ) {}

  private config(): M.ClientConfig {
    return {
      keyRetentionConfig: M.defaultKeyRetentionConfig,
      lifetimeConfig: M.defaultLifetimeConfig,
      keyPackageEqualityConfig: M.defaultKeyPackageEqualityConfig,
      paddingConfig: M.defaultPaddingConfig,
      authService: { validateCredential: (c, spk) => this.validateCredential(c, spk) },
    }
  }

  /** Authentication Service: binds MLS leaves to Signal device identities. */
  async validateCredential(credential: M.Credential, signaturePublicKey: Uint8Array): Promise<boolean> {
    const who = parseCredential(credential)
    if (!who) return false
    let ids = await this.directory.identities(who.userId)
    let device = ids.find((d) => d.deviceId === who.deviceId)
    if (!device) {
      ids = await this.directory.identities(who.userId, true)
      device = ids.find((d) => d.deviceId === who.deviceId)
    }
    if (!device) return false
    if (!equalBytes(fromBase64(device.mlsSignatureKey), signaturePublicKey)) return false
    const identityKey = fromBase64(device.identityKey)
    const ok = await verifyIdentitySignature(toArrayBuffer(identityKey),
      bindingMessage(who.userId, who.deviceId, signaturePublicKey), fromBase64(device.mlsSignatureBinding))
    if (!ok) return false
    // Must match the identity we already trust for that device (TOFU / verified safety number).
    const trusted = await this.signalStore.trustedIdentity(who.deviceId)
    if (trusted && !equalBytes(trusted, identityKey)) return false
    if (!trusted) await this.signalStore.saveIdentity(who.deviceId, toArrayBuffer(identityKey))
    return true
  }

  async ensureSignatureKey(): Promise<{ publicKey: Uint8Array; signKey: Uint8Array }> {
    const existing = await this.ks.get<{ publicKey: Uint8Array; signKey: Uint8Array }>('mls', 'signatureKey')
    if (existing) return existing
    const impl = await cipherSuite()
    const kp = await impl.signature.keygen()
    await this.ks.put('mls', 'signatureKey', kp)
    return kp
  }

  private credential(): M.Credential {
    return { credentialType: 'basic', identity: credentialIdentity(this.self.userId, this.self.deviceId) }
  }

  private async newKeyPackage() {
    const impl = await cipherSuite()
    return M.generateKeyPackageWithKey(this.credential(), M.defaultCapabilities(), M.defaultLifetime, [],
      await this.ensureSignatureKey(), impl)
  }

  /** Generates key packages; private halves stay local, keyed by a client-chosen id. */
  async generateKeyPackages(count: number, lastResort = false): Promise<{ id: string; keyPackage: string; lastResort: boolean }[]> {
    const out = []
    for (let i = 0; i < count; i++) {
      const kp = await this.newKeyPackage()
      const id = uuid()
      const encoded = M.encodeMlsMessage({ keyPackage: kp.publicPackage, wireformat: 'mls_key_package', version: 'mls10' })
      await this.ks.put('mlskp', id, { publicPackage: encoded, privatePackage: kp.privatePackage, lastResort } satisfies StoredKeyPackage)
      out.push({ id, keyPackage: toBase64(encoded), lastResort })
    }
    return out
  }

  async hasGroup(conversationId: string): Promise<boolean> {
    return (await this.ks.get('mlsstate', conversationId)) !== undefined
  }

  private async load(conversationId: string): Promise<ClientState> {
    const bytes = await this.ks.get<Uint8Array>('mlsstate', conversationId)
    if (!bytes) throw new Error('no MLS state for conversation')
    const decoded = M.decodeGroupState(bytes, 0)
    if (!decoded) throw new Error('corrupt MLS state')
    return { ...decoded[0], clientConfig: this.config() }
  }

  private async save(conversationId: string, state: ClientState): Promise<void> {
    await this.ks.put('mlsstate', conversationId, M.encodeGroupState(state))
  }

  async epoch(conversationId: string): Promise<number> {
    return Number((await this.load(conversationId)).groupContext.epoch)
  }

  async createGroup(conversationId: string, mlsGroupIdB64: string): Promise<void> {
    const impl = await cipherSuite()
    const kp = await this.newKeyPackage()
    const state = await M.createGroup(fromBase64(mlsGroupIdB64), kp.publicPackage, kp.privatePackage, [], impl, this.config())
    await this.save(conversationId, state)
  }

  /** Leaf members as (leafIndex, userId, deviceId). */
  async members(conversationId: string): Promise<{ leafIndex: number; userId: string; deviceId: string }[]> {
    const state = await this.load(conversationId)
    const out: { leafIndex: number; userId: string; deviceId: string }[] = []
    state.ratchetTree.forEach((node, i) => {
      if (node?.nodeType === 'leaf') {
        const who = parseCredential(node.leaf.credential)
        if (who) out.push({ leafIndex: i / 2, ...who })
      }
    })
    return out
  }

  /** Builds (but does not apply) a Commit adding the given devices. Apply after the server accepts it. */
  async prepareAdd(conversationId: string, devices: { userId: string; bundle: DeviceBundle }[]): Promise<StagedCommit> {
    const impl = await cipherSuite()
    const state = await this.load(conversationId)
    const proposals: M.Proposal[] = []
    for (const { userId, bundle } of devices) {
      if (!bundle.mlsKeyPackage) throw new Error(`device ${bundle.deviceId} has no key package`)
      const decoded = M.decodeMlsMessage(fromBase64(bundle.mlsKeyPackage.keyPackage), 0)?.[0]
      if (!decoded || decoded.wireformat !== 'mls_key_package') throw new Error('invalid key package')
      const who = parseCredential(decoded.keyPackage.leafNode.credential)
      if (!who || who.userId !== userId || who.deviceId !== bundle.deviceId) throw new Error('key package credential mismatch')
      proposals.push({ proposalType: 'add', add: { keyPackage: decoded.keyPackage } })
    }
    const res = await M.createCommit({ state, cipherSuite: impl }, { extraProposals: proposals, ratchetTreeExtension: true })
    return {
      commit: toBase64(M.encodeMlsMessage(res.commit)),
      welcome: res.welcome ? toBase64(M.encodeMlsMessage({ welcome: res.welcome, wireformat: 'mls_welcome', version: 'mls10' })) : undefined,
      epoch: Number(state.groupContext.epoch),
      newState: res.newState,
    }
  }

  /** Builds a Commit removing every leaf of {@code userId}. TreeKEM rotates the group secrets. */
  async prepareRemove(conversationId: string, userId: string): Promise<StagedCommit> {
    const impl = await cipherSuite()
    const state = await this.load(conversationId)
    const leaves = (await this.members(conversationId)).filter((m) => m.userId === userId)
    if (leaves.length === 0) throw new Error('user is not in the MLS group')
    const res = await M.createCommit({ state, cipherSuite: impl }, {
      extraProposals: leaves.map((l) => ({ proposalType: 'remove', remove: { removed: l.leafIndex } }) as M.Proposal),
    })
    return { commit: toBase64(M.encodeMlsMessage(res.commit)), epoch: Number(state.groupContext.epoch), newState: res.newState }
  }

  /**
   * One Commit reconciling the tree with the authoritative device list: Add proposals for new
   * devices of current members, Remove proposals for leaves of revoked devices / departed members.
   */
  async prepareSync(conversationId: string, adds: { userId: string; bundle: DeviceBundle }[], removeLeaves: number[]): Promise<StagedCommit> {
    const impl = await cipherSuite()
    const state = await this.load(conversationId)
    const proposals: M.Proposal[] = []
    for (const { userId, bundle } of adds) {
      if (!bundle.mlsKeyPackage) continue
      const decoded = M.decodeMlsMessage(fromBase64(bundle.mlsKeyPackage.keyPackage), 0)?.[0]
      if (!decoded || decoded.wireformat !== 'mls_key_package') throw new Error('invalid key package')
      const who = parseCredential(decoded.keyPackage.leafNode.credential)
      if (!who || who.userId !== userId || who.deviceId !== bundle.deviceId) throw new Error('key package credential mismatch')
      proposals.push({ proposalType: 'add', add: { keyPackage: decoded.keyPackage } })
    }
    for (const leaf of removeLeaves) proposals.push({ proposalType: 'remove', remove: { removed: leaf } } as M.Proposal)
    const res = await M.createCommit({ state, cipherSuite: impl }, { extraProposals: proposals, ratchetTreeExtension: true })
    return {
      commit: toBase64(M.encodeMlsMessage(res.commit)),
      welcome: res.welcome ? toBase64(M.encodeMlsMessage({ welcome: res.welcome, wireformat: 'mls_welcome', version: 'mls10' })) : undefined,
      epoch: Number(state.groupContext.epoch),
      newState: res.newState,
    }
  }

  /** Empty Commit with a path update: rotates this member's leaf secret and the epoch secrets. */
  async prepareUpdate(conversationId: string): Promise<StagedCommit> {
    const impl = await cipherSuite()
    const state = await this.load(conversationId)
    const res = await M.createCommit({ state, cipherSuite: impl }, {})
    return { commit: toBase64(M.encodeMlsMessage(res.commit)), epoch: Number(state.groupContext.epoch), newState: res.newState }
  }

  async applyStaged(conversationId: string, staged: StagedCommit): Promise<void> {
    await this.save(conversationId, staged.newState)
    await this.ks.delete('mlspending', conversationId)
  }

  /**
   * Persist a staged commit BEFORE submitting it: if the tab dies after the server accepted it,
   * {@link recoverPending} applies it (our own commit cannot be re-processed from the log).
   */
  async savePending(conversationId: string, staged: StagedCommit): Promise<void> {
    await this.ks.put('mlspending', conversationId, { epoch: staged.epoch, state: M.encodeGroupState(staged.newState) })
  }

  async discardPending(conversationId: string): Promise<void> {
    await this.ks.delete('mlspending', conversationId)
  }

  /** Applies a persisted own commit iff the server log shows our device committed at that epoch. */
  async recoverPending(conversationId: string, serverCommitAt: (epoch: number) => Promise<string | null>, myDeviceId: string): Promise<boolean> {
    const p = await this.ks.get<{ epoch: number; state: Uint8Array }>('mlspending', conversationId)
    if (!p) return false
    if (!(await this.hasGroup(conversationId)) || (await this.epoch(conversationId)) !== p.epoch) {
      await this.discardPending(conversationId)
      return false
    }
    const sender = await serverCommitAt(p.epoch)
    if (sender === myDeviceId) {
      await this.ks.put('mlsstate', conversationId, p.state)
      await this.discardPending(conversationId)
      return true
    }
    if (sender !== null) await this.discardPending(conversationId)   // someone else won the epoch
    return false
  }

  async joinFromWelcome(conversationId: string, welcomeB64: string, keyPackageId?: string): Promise<number> {
    const impl = await cipherSuite()
    const decoded = M.decodeMlsMessage(fromBase64(welcomeB64), 0)?.[0]
    if (!decoded || decoded.wireformat !== 'mls_welcome') throw new Error('invalid welcome')
    const candidates = keyPackageId ? [keyPackageId] : await this.ks.keys('mlskp')
    let lastError: unknown = new Error('no matching key package')
    for (const id of candidates) {
      const rec = await this.ks.get<StoredKeyPackage>('mlskp', id)
      if (!rec) continue
      const kp = M.decodeMlsMessage(rec.publicPackage, 0)?.[0]
      if (!kp || kp.wireformat !== 'mls_key_package') continue
      try {
        const state = await M.joinGroup(decoded.welcome, kp.keyPackage, rec.privatePackage, M.emptyPskIndex, impl,
          undefined, undefined, this.config())
        await this.save(conversationId, state)
        if (!rec.lastResort) await this.ks.delete('mlskp', id)
        return Number(state.groupContext.epoch)
      } catch (e) {
        lastError = e
      }
    }
    throw lastError
  }

  async encrypt(conversationId: string, plaintext: Uint8Array): Promise<{ ciphertext: string; epoch: number }> {
    const impl = await cipherSuite()
    const state = await this.load(conversationId)
    const res = await M.createApplicationMessage(state, plaintext, impl)
    await this.save(conversationId, res.newState)
    return {
      ciphertext: toBase64(M.encodeMlsMessage({ privateMessage: res.privateMessage, wireformat: 'mls_private_message', version: 'mls10' })),
      epoch: Number(state.groupContext.epoch),
    }
  }

  /** Processes an application message or Commit in server (seq) order. */
  async process(conversationId: string, ciphertextB64: string): Promise<ProcessResult> {
    const impl = await cipherSuite()
    const decoded = M.decodeMlsMessage(fromBase64(ciphertextB64), 0)?.[0]
    if (!decoded || decoded.wireformat !== 'mls_private_message') throw new Error('unsupported MLS wire format')
    const state = await this.load(conversationId)
    const current = state.groupContext.epoch
    if (decoded.privateMessage.epoch < current) {
      // Late message from a recent epoch: ts-mls keeps receiver secrets of the last few epochs
      // (keyRetentionConfig). Older than that, or from before we joined → not decryptable (stale).
      try {
        const late = await M.processPrivateMessage(state, decoded.privateMessage, M.emptyPskIndex, impl)
        if (late.kind === 'applicationMessage') {
          await this.save(conversationId, late.newState)
          return { kind: 'application', plaintext: late.message, epoch: Number(decoded.privateMessage.epoch) }
        }
      } catch {
        /* fall through */
      }
      return { kind: 'stale', epoch: Number(current) }
    }
    const res = await M.processPrivateMessage(state, decoded.privateMessage, M.emptyPskIndex, impl)
    await this.save(conversationId, res.newState)
    const epoch = Number(res.newState.groupContext.epoch)
    if (res.kind === 'applicationMessage') return { kind: 'application', plaintext: res.message, epoch }
    return { kind: 'commit', epoch, removedSelf: res.newState.groupActiveState.kind === 'removedFromGroup' }
  }

  async forget(conversationId: string): Promise<void> {
    await this.ks.delete('mlsstate', conversationId)
  }
}
