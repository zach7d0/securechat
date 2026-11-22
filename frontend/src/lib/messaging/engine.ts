import { ApiError, type ApiClient } from '../api/http'
import type { Accepted, Conversation, DeliveredMessage, DeviceBundle, HistoryTransfer, PresenceView, Profile, SendBody } from '../api/types'
import { fromBase64, toArrayBuffer, toBase64, uuid } from '../crypto/bytes'
import * as Transfer from '../crypto/transfer'
import { openShare, scopeLabel, scopeWindowFrom, sealShare, selectForScope, type GrantScope, type ShareScope } from '../crypto/historyShare'
import { ApiKeyDirectory, type KeyDirectory } from '../crypto/directory'
import { replenishKeys } from '../crypto/keyManager'
import type { KeyStore } from '../crypto/keystore'
import { MlsService, type StagedCommit } from '../crypto/mls'
import { KeyedMutex } from '../crypto/mutex'
import { decodePayload, encodePayload, type DeletePayload, type EditPayload, type HistoryGrantPayload, type HistorySharePayload, type TimerPayload } from '../crypto/payload'
import { acceptDelete, acceptEdit, canDeleteForEveryone, canEdit, isAllowedTimer, timerLabel } from './rules'
import { SignalService, type DeviceTarget } from '../crypto/signal'
import { IdentityChangedError, SignalProtocolStore } from '../crypto/signalStore'
import { ChatSocket, type SocketStatus } from './socket'

export type MessageStatus = 'queued' | 'pending' | 'sent' | 'delivered' | 'read' | 'failed'

export interface LocalMessage {
  key: string
  clientMessageId: string
  id?: string
  conversationId: string
  senderUserId: string
  senderDeviceId: string
  direction: 'in' | 'out' | 'system'
  body: string | null
  error?: string
  sentAt: string
  serverAt?: string
  status: MessageStatus
  deliveredTo: string[]
  readBy: string[]
  readSent?: boolean
  /** Set on messages imported from a group history share: the admin who shared (and vouches for) them. */
  sharedBy?: string
  /** Disappearing timer (seconds) in force when sent, and when this copy must be removed. */
  expiresIn?: number
  expiresAt?: string
  /** Server time of the last accepted edit (shown as "edited"). */
  editedAt?: string
  /** Deleted for everyone by its sender: shown as a tombstone. */
  deleted?: boolean
}

export interface AddMembersResult {
  /** Number of earlier messages shared with the new members (0 when nothing to share / not requested). */
  shared: number
  /** Set when the members were added but sharing history failed. */
  shareError?: string
  /** History was granted but this device doesn't hold it all: another member's device will share it. */
  pendingFromOthers?: boolean
}

/** What "Load older messages" found for one conversation. */
export interface OlderInfo {
  /** Messages from before this device existed: requested automatically from the user's other devices. */
  syncing: number
  /** Group messages from before the user joined (never readable unless shared). */
  beforeJoin: number
  done: boolean
}

export interface HistorySentNotice {
  id: string
  targetDeviceId: string
  targetDeviceName: string | null
  messages: number
  at: string
  dismissed?: boolean
}

/** A history share this device made, kept so the key can be re-sent to the invitee's later devices. */
interface SharedRecord {
  conversationId: string
  payload: HistorySharePayload
  sentTo: string[]
  expiresAt: string
}

const DAY = 86_400_000
const MONTH = 30 * DAY
const SKEW = 10 * 60_000
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

export interface ConversationSummary extends Conversation {
  title: string
  lastMessage?: LocalMessage
  unread: number
  protocol: 'Signal' | 'MLS'
  identityWarning: boolean
  groupReady: boolean
}

export interface SafetyNumberView {
  userId: string
  displayName: string
  deviceId: string
  safetyNumber: string
  verified: boolean
}

export interface EngineOptions {
  api: ApiClient
  store: KeyStore
  wsUrl: string
  me: { userId: string; username: string; deviceId: string }
  WebSocketImpl?: typeof WebSocket
  directory?: KeyDirectory
  log?: (msg: string, err?: unknown) => void
  onTerminated?: (reason: string) => void
}

type Listener = (topic: string, conversationId?: string) => void

const NETWORK = (e: unknown) => e instanceof TypeError || (e as { code?: string })?.code === 'ACK_TIMEOUT'

/**
 * Client messaging engine (UI-independent; also drives the headless system test).
 * - All encryption/decryption happens here, on the device. The server sees ciphertext only.
 * - Every delivery is processed exactly once per device (dedupe by message id), stored in the
 *   encrypted local store, and only then acknowledged (DELIVERED); READ is sent when displayed.
 * - Per-conversation mutex: ratchet/MLS state is never advanced concurrently.
 * - Outgoing messages survive offline periods (queued, retried with the same clientMessageId).
 */
export class ChatEngine {
  readonly signal: SignalService
  readonly mls: MlsService
  readonly signalStore: SignalProtocolStore
  private readonly directory: KeyDirectory
  private readonly mutex = new KeyedMutex()
  private readonly socket: ChatSocket
  private readonly listeners = new Set<Listener>()
  private readonly pendingAcks = new Map<string, { resolve: (a: Accepted) => void; reject: (e: unknown) => void }>()
  private convs = new Map<string, Conversation>()
  private profilesMap = new Map<string, Profile>()
  private msgs = new Map<string, LocalMessage[]>()
  private presenceMap = new Map<string, PresenceView>()
  private typingMap = new Map<string, Map<string, number>>()
  private identityWarnings = new Set<string>()
  private deliveredQueue: string[] = []
  private status: SocketStatus = 'offline'
  private started = false
  private flushing = false
  private transferTimer: ReturnType<typeof setInterval> | null = null
  private sweepTimer: ReturnType<typeof setInterval> | null = null
  private autoAnswered = new Set<string>()
  private sentNotices = new Map<string, HistorySentNotice>()
  private older = new Map<string, OlderInfo>()
  private transferList: HistoryTransfer[] = []
  private receiving = new Set<string>()

  constructor(private readonly o: EngineOptions) {
    this.directory = o.directory ?? new ApiKeyDirectory(o.api)
    this.signalStore = new SignalProtocolStore(o.store)
    this.signal = new SignalService(this.signalStore, this.directory, o.me)
    this.mls = new MlsService(o.store, this.directory, this.signalStore, o.me)
    this.socket = new ChatSocket({
      url: o.wsUrl,
      getToken: () => o.api.accessToken(),
      onFrame: (f) => void this.onFrame(f),
      onStatus: (s) => {
        this.status = s
        this.emit('status')
      },
      onTerminated: () => o.onTerminated?.('This device was revoked or the account was suspended.'),
      WebSocketImpl: o.WebSocketImpl,
    })
  }

  get me() {
    return this.o.me
  }

  // ------------------------------------------------------------------ lifecycle

  async start(): Promise<void> {
    if (this.started) return
    this.started = true
    await this.loadLocalMessages()
    await this.refreshConversations()
    await this.processWelcomes()
    for (const c of this.convs.values()) {
      if (c.type === 'GROUP') await this.mutex.run(c.id, () => this.syncCommitsLocked(c.id)).catch((e) => this.log('commit sync failed', e))
    }
    this.socket.start()
    void replenishKeys(this.o.api, this.o.store, this.signalStore, this.mls).catch((e) => this.log('key replenishment failed', e))
    void this.refreshPresence()
    for (const k of await this.o.store.keys('historySent')) {
      const n = await this.o.store.get<HistorySentNotice>('historySent', k)
      if (n) this.sentNotices.set(k, n)
    }
    void this.refreshTransfers()
    this.transferTimer = setInterval(() => {
      void this.refreshTransfers()
      void this.joinWaitingGroups()
    }, 15_000)
    void this.sweepExpired()
    this.sweepTimer = setInterval(() => void this.sweepExpired(), 30_000)
    void this.autoSyncNewDevice().catch((e) => this.log('automatic history sync failed', e))
  }

  stop(): void {
    this.started = false
    if (this.transferTimer) clearInterval(this.transferTimer)
    if (this.sweepTimer) clearInterval(this.sweepTimer)
    this.socket.stop()
    for (const p of this.pendingAcks.values()) p.reject(new Error('stopped'))
    this.pendingAcks.clear()
  }

  on(listener: Listener): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  // ------------------------------------------------------------------ snapshots for the UI

  connectionStatus(): SocketStatus {
    return this.status
  }

  conversations(): ConversationSummary[] {
    return [...this.convs.values()].map((c) => this.summary(c)).sort((a, b) => {
      const ta = a.lastMessage?.serverAt ?? a.lastMessage?.sentAt ?? a.updatedAt
      const tb = b.lastMessage?.serverAt ?? b.lastMessage?.sentAt ?? b.updatedAt
      return tb.localeCompare(ta)
    })
  }

  conversation(id: string): ConversationSummary | undefined {
    const c = this.convs.get(id)
    return c ? this.summary(c) : undefined
  }

  messages(conversationId: string): LocalMessage[] {
    return [...(this.msgs.get(conversationId) ?? [])]
  }

  presence(userId: string): PresenceView | undefined {
    return this.presenceMap.get(userId)
  }

  profile(userId: string): Profile | undefined {
    return this.profilesMap.get(userId)
  }

  displayName(userId: string): string {
    const p = this.profilesMap.get(userId)
    return p?.displayName || p?.username || 'Unknown user'
  }

  typingUsers(conversationId: string): string[] {
    const m = this.typingMap.get(conversationId)
    if (!m) return []
    const now = Date.now()
    return [...m.entries()].filter(([, exp]) => exp > now).map(([u]) => u)
  }

  private summary(c: Conversation): ConversationSummary {
    const list = this.msgs.get(c.id) ?? []
    const visible = list.filter((m) => m.direction !== 'system')
    const peer = c.type === 'DIRECT' ? c.members.find((m) => m.userId !== this.o.me.userId) : undefined
    return {
      ...c,
      title: c.type === 'GROUP' ? (c.displayName ?? 'Group') : peer ? this.displayName(peer.userId) : 'Direct chat',
      lastMessage: visible[visible.length - 1],
      unread: list.filter((m) => m.direction === 'in' && !m.readSent).length,
      protocol: c.type === 'DIRECT' ? 'Signal' : 'MLS',
      identityWarning: this.identityWarnings.has(c.id),
      groupReady: c.type !== 'GROUP' || this.groupReady.has(c.id),
    }
  }

  private groupReady = new Set<string>()

  // ------------------------------------------------------------------ conversations & profiles

  async refreshConversations(): Promise<void> {
    const list = await this.o.api.conversations()
    const ids = new Set(list.map((c) => c.id))
    for (const id of this.convs.keys()) if (!ids.has(id)) this.convs.delete(id)
    for (const c of list) this.convs.set(c.id, c)
    for (const c of list) if (c.type === 'GROUP' && (await this.mls.hasGroup(c.id))) this.groupReady.add(c.id)
    await this.loadProfiles(list.flatMap((c) => c.members.map((m) => m.userId)))
    this.emit('conversations')
  }

  async loadProfiles(userIds: string[]): Promise<void> {
    const missing = [...new Set(userIds)].filter((u) => !this.profilesMap.has(u))
    for (let i = 0; i < missing.length; i += 100) {
      for (const p of await this.o.api.profiles(missing.slice(i, i + 100))) this.profilesMap.set(p.userId, p)
    }
  }

  rememberProfile(p: Profile): void {
    this.profilesMap.set(p.userId, p)
  }

  private async reloadConversation(id: string): Promise<void> {
    try {
      const c = await this.o.api.conversation(id)
      this.convs.set(id, c)
      await this.loadProfiles(c.members.map((m) => m.userId))
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        // no longer a member (removed / left): drop the conversation and any group secrets
        this.convs.delete(id)
        this.groupReady.delete(id)
        if (await this.mls.hasGroup(id)) {
          await this.mls.forget(id)
          this.addSystem(id, 'You are no longer a member of this group')
        }
      } else throw e
    }
    this.emit('conversations', id)
  }

  async openDirect(userId: string): Promise<ConversationSummary> {
    const c = await this.o.api.createDirect(userId)
    this.convs.set(c.id, c)
    await this.loadProfiles(c.members.map((m) => m.userId))
    this.emit('conversations')
    void this.refreshPresence()
    return this.summary(c)
  }

  // ------------------------------------------------------------------ groups (MLS)

  /** Creates the group, the local MLS group (epoch 0), then adds members with a Commit. */
  async createGroup(name: string, memberUserIds: string[], description?: string): Promise<ConversationSummary> {
    const c = await this.o.api.createGroup(name, description)
    await this.mutex.run(c.id, () => this.mls.createGroup(c.id, c.group!.mlsGroupId))
    this.groupReady.add(c.id)
    this.convs.set(c.id, c)
    this.addSystem(c.id, 'Group created · end-to-end encrypted with MLS')
    if (memberUserIds.length > 0) await this.addMembers(c.id, memberUserIds)
    await this.refreshAfterChange(c.id)
    return this.summary(this.convs.get(c.id)!)
  }

  async addMembers(conversationId: string, userIds: string[], opts: { shareHistory?: ShareScope; role?: 'ADMIN' | 'MEMBER' } = {}): Promise<AddMembersResult> {
    await this.mutex.run(conversationId, async () => {
      for (let attempt = 0; ; attempt++) {
        await this.syncCommitsLocked(conversationId)
        const devices: { userId: string; bundle: DeviceBundle }[] = []
        for (const u of userIds) {
          const b = await this.directory.claimBundle(u, true)
          const ready = b.devices.filter((d) => d.mlsKeyPackage)
          if (ready.length === 0) throw new Error('That user has no device ready for group encryption yet')
          for (const d of ready) devices.push({ userId: u, bundle: d })
        }
        const staged = await this.mls.prepareAdd(conversationId, devices)
        await this.mls.savePending(conversationId, staged)
        try {
          await this.o.api.addMembers(conversationId, {
            userIds, commit: staged.commit, epoch: staged.epoch, role: opts.role ?? 'MEMBER',
            welcomes: devices.map((d) => ({ deviceId: d.bundle.deviceId, keyPackageId: d.bundle.mlsKeyPackage!.id, welcome: staged.welcome! })),
          })
          await this.mls.applyStaged(conversationId, staged)
          this.addSystem(conversationId, `Added ${userIds.map((u) => this.displayName(u)).join(', ')}${opts.role === 'ADMIN' ? ' as admin' : ''}`
            + ` · keys rotated (epoch ${staged.epoch + 1})`)
          break
        } catch (e) {
          await this.mls.discardPending(conversationId)
          if (attempt === 0 && e instanceof ApiError && e.code === 'EPOCH_CONFLICT') continue
          throw e
        }
      }
    })
    await this.refreshAfterChange(conversationId)
    const scope = opts.shareHistory ?? 'none'
    if (scope === 'none') return { shared: 0 }
    try {
      return await this.grantHistory(conversationId, userIds, scope)
    } catch (e) {
      const shareError = e instanceof Error ? e.message : 'unknown error'
      this.log('history share failed', e)
      this.addSystem(conversationId, `Members added, but chat history could not be shared: ${shareError}`)
      return { shared: 0, shareError }
    }
  }

  // ------------------------------------------------------------------ group history shared with new members

  /**
   * Admin, right after the Add commit: authorise sharing (server grant + MLS-authenticated grant message
   * in the new epoch). This device fulfils it when it holds that history; otherwise any other member's
   * device that does will (first to claim the grant on the server wins).
   */
  private async grantHistory(conversationId: string, userIds: string[], scope: GrantScope): Promise<AddMembersResult> {
    const conv = this.convs.get(conversationId)
    if (!conv || conv.type !== 'GROUP') throw new Error('history can only be shared in groups')
    const { id: grantId } = await retry429(() => this.o.api.createHistoryGrant(conversationId, scope))
    const grantedAt = new Date().toISOString()
    const clientMessageId = uuid()
    const grant: HistoryGrantPayload = { v: 1, kind: 'history-grant', grantId, scope, forUserIds: userIds, grantedAt, sentAt: grantedAt,
      clientMessageId }
    const ack = await retry429(() => this.mutex.run(conversationId, () => this.encryptAndTransmit(conv, clientMessageId, encodePayload(grant))))
    await this.markSeen(ack.messageId)
    this.addSystem(conversationId, `You allowed ${userIds.map((u) => this.displayName(u)).join(', ')} to see ${scopeLabel(scope)} of earlier messages`)
    // the inviter is online right now: share whatever this device has within the scope, immediately
    if ((await this.coverage(conversationId, grant)) !== 'none') return { shared: await this.fulfilGrant(conversationId, grant) }
    return { shared: 0, pendingFromOthers: true }   // this device has nothing in scope: another member's device may share
  }

  private readable(conversationId: string): LocalMessage[] {
    return (this.msgs.get(conversationId) ?? []).filter((m) => m.direction !== 'system' && m.body !== null && !m.error)
  }

  private async deviceCreatedAt(): Promise<number> {
    return Date.parse((await this.o.store.get<{ createdAt: string }>('meta', 'device'))?.createdAt ?? '1970-01-01T00:00:00Z')
  }

  /** Does this device hold all ("full"), some ("partial") or none of the history a grant covers? */
  private async coverage(conversationId: string, g: HistoryGrantPayload): Promise<'full' | 'partial' | 'none'> {
    const conv = this.convs.get(conversationId)
    const grantedAt = Date.parse(g.grantedAt)
    const inScope = selectForScope(this.readable(conversationId), g.scope, grantedAt)
    if (!conv || inScope.length === 0) return 'none'
    const joined = Date.parse(conv.members.find((m) => m.userId === this.o.me.userId)?.joinedAt ?? conv.createdAt)
    // this device has everything since it existed and the user was a member, plus whatever it imported
    const holdsSince = Math.min(Math.max(await this.deviceCreatedAt(), joined), Date.parse(inScope[0].sentAt))
    const groupStart = Date.parse(conv.createdAt) + 60_000
    const from = scopeWindowFrom(g.scope, grantedAt)
    if (g.scope === 'last100' && inScope.length >= 100) return 'full'
    // nothing existed before the group did: a window reaching further back starts at the group's creation
    const start = from === null ? groupStart : Math.max(from, groupStart)
    return holdsSince <= start ? 'full' : 'partial'
  }

  /** Claim the grant (first device wins), encrypt the history in scope, upload, announce in the epoch. */
  private async fulfilGrant(conversationId: string, g: HistoryGrantPayload, delayMs = 0): Promise<number> {
    const conv = this.convs.get(conversationId)
    if (!conv) return 0
    if (delayMs > 0) {
      await sleep(delayMs)
      if ((await retry429(() => this.o.api.historyGrant(conversationId, g.grantId)).catch(() => ({ claimed: true }))).claimed) return 0
    }
    let shareId: string
    try {
      shareId = (await retry429(() => this.o.api.claimHistoryGrant(conversationId, g.grantId))).shareId
    } catch (e) {
      if (e instanceof ApiError && (e.status === 409 || e.status === 404)) return 0   // someone else shares it / expired
      throw e
    }
    const messages = selectForScope(this.readable(conversationId), g.scope, Date.parse(g.grantedAt))
    const archive = {
      v: 1, conversationId, grantId: g.grantId, sharedBy: this.o.me.userId, createdAt: new Date().toISOString(),
      // receipts of other members are not shared; only who said what and when
      messages: messages.map((m) => ({ key: m.key, id: m.id, clientMessageId: m.clientMessageId, conversationId, senderUserId: m.senderUserId,
        senderDeviceId: m.senderDeviceId, body: m.body, sentAt: m.sentAt, serverAt: m.serverAt })),
    }
    const { ciphertext, key, sha256 } = await sealShare(archive, conversationId, shareId)
    const CHUNK = 150 * 1024
    let chunkCount = 0
    for (let o = 0; o < ciphertext.length; o += CHUNK) {
      const seq = chunkCount++
      await retry429(() => this.o.api.putHistoryShareChunk(conversationId, shareId, seq, toBase64(ciphertext.subarray(o, o + CHUNK))))
    }
    await retry429(() => this.o.api.completeHistoryShare(conversationId, shareId, chunkCount))
    const clientMessageId = uuid()
    const payload: HistorySharePayload = { v: 1, kind: 'history-share', shareId, grantId: g.grantId, scope: g.scope, grantedAt: g.grantedAt,
      key, sha256, chunkCount, messageCount: messages.length, forUserIds: g.forUserIds, sentAt: new Date().toISOString(), clientMessageId }
    const sentTo = (await this.mls.members(conversationId).catch(() => [])).filter((l) => g.forUserIds.includes(l.userId)).map((l) => l.deviceId)
    await this.o.store.put('myShare', shareId, { conversationId, payload, sentTo,
      expiresAt: new Date(Date.now() + 7 * DAY).toISOString() } satisfies SharedRecord)
    const ack = await retry429(() => this.mutex.run(conversationId, () => this.encryptAndTransmit(conv, clientMessageId, encodePayload(payload))))
    await this.markSeen(ack.messageId)
    this.addSystem(conversationId, `You shared ${messages.length} earlier message${messages.length === 1 ? '' : 's'} with `
      + `${g.forUserIds.map((u) => this.displayName(u)).join(', ')}`)
    return messages.length
  }

  private isGroupAdmin(conversationId: string, userId: string): boolean {
    const role = this.convs.get(conversationId)?.members.find((m) => m.userId === userId)?.role
    return role === 'OWNER' || role === 'ADMIN'
  }

  /** A history-grant arrived in a group (MLS-authenticated sender). */
  private async onHistoryGrant(conversationId: string, senderUserId: string, g: HistoryGrantPayload): Promise<void> {
    await this.reloadConversation(conversationId).catch(() => undefined)
    if (!this.isGroupAdmin(conversationId, senderUserId)) {
      this.log(`ignoring history grant from non-admin ${senderUserId}`)
      return
    }
    if (g.forUserIds.includes(this.o.me.userId)) {
      await this.o.store.put('historyGrant', g.grantId, { conversationId, scope: g.scope, grantedAt: g.grantedAt, grantedBy: senderUserId })
      this.addSystem(conversationId, `${this.displayName(senderUserId)} is sharing ${scopeLabel(g.scope)} of earlier messages with you…`)
      const waiting = await this.o.store.get<{ sharedBy: string; p: HistorySharePayload }>('pendingShare', g.grantId)
      if (waiting) {
        await this.o.store.delete('pendingShare', g.grantId)
        await this.receiveHistoryShare(conversationId, waiting.sharedBy, waiting.p)
      }
      return
    }
    this.addSystem(conversationId, `${senderUserId === this.o.me.userId ? 'You' : this.displayName(senderUserId)} allowed `
      + `${g.forUserIds.map((u) => this.displayName(u)).join(', ')} to see ${scopeLabel(g.scope)} of earlier messages`)
    // the inviter shares immediately; other members only step in if it had nothing (grant still unclaimed)
    if ((await this.coverage(conversationId, g)) === 'none') return
    void this.fulfilGrant(conversationId, g, 20_000 + Math.random() * 10_000).catch((e) => this.log('history share failed', e))
  }

  /** New member: fetch, verify and decrypt a share, then import the messages its grant allows. */
  private async receiveHistoryShare(conversationId: string, sharedBy: string, p: HistorySharePayload): Promise<void> {
    if (await this.o.store.get('historyShare', p.shareId)) return
    let grant: { scope: GrantScope; grantedAt: string } | undefined
    if (p.grantId) grant = await this.o.store.get<{ conversationId: string; scope: GrantScope; grantedAt: string }>('historyGrant', p.grantId)
    if (!grant && p.scope && p.grantedAt) {
      // this device joined after the grant message (e.g. opened later): the inviter (an admin) re-sent it
      if (!this.convs.get(conversationId)?.members.length) await this.reloadConversation(conversationId).catch(() => undefined)
      if (this.isGroupAdmin(conversationId, sharedBy)) grant = { scope: p.scope, grantedAt: p.grantedAt }
    }
    if (!grant) {
      if (p.grantId) {
        // the grant message may still be on its way: keep the share until it arrives
        await this.o.store.put('pendingShare', p.grantId, { sharedBy, p })
        return
      }
      if (!this.isGroupAdmin(conversationId, sharedBy)) {   // a share without any grant: only from an admin
        this.log(`ignoring ungranted history share from non-admin ${sharedBy}`)
        return
      }
    }
    try {
      const parts: Uint8Array[] = []
      for (let i = 0; i < p.chunkCount; i++) {
        parts.push(fromBase64((await retry429(() => this.o.api.getHistoryShareChunk(conversationId, p.shareId, i))).data))
      }
      const ciphertext = new Uint8Array(parts.reduce((n, x) => n + x.length, 0))
      let o = 0
      for (const x of parts) {
        ciphertext.set(x, o)
        o += x.length
      }
      const archive = await openShare<{ v: number; conversationId: string; messages: LocalMessage[] }>(ciphertext, p.key, p.sha256,
        conversationId, p.shareId)
      if (archive.conversationId !== conversationId) throw new Error('archive is for another conversation')
      let incoming = (archive.messages ?? []).filter((m) => m && m.conversationId === conversationId && typeof m.sentAt === 'string')
        .sort((a, b) => Date.parse(a.sentAt) - Date.parse(b.sentAt))
      // only what the admin allowed, whatever the sharing member sent
      if (grant) incoming = selectForScope(incoming, grant.scope, Date.parse(grant.grantedAt))
      const imported = await this.mutex.run(conversationId, () => this.importMessages(incoming, { sharedBy }))
      await this.o.store.put('historyShare', p.shareId, 1)
      await this.loadProfiles([...new Set(incoming.map((m) => m.senderUserId).filter((u) => typeof u === 'string'))]).catch(() => undefined)
      this.addSystem(conversationId, `${this.displayName(sharedBy)} shared ${imported} earlier message${imported === 1 ? '' : 's'} with you`)
    } catch (e) {
      this.log('history share import failed', e)
      this.addSystem(conversationId, `${this.displayName(sharedBy)} shared chat history, but it could not be opened on this device`)
    }
  }

  /**
   * Imports decrypted messages (own-device transfer or group share). Never overwrites a message this
   * device can already read; replaces "sent before…" placeholders in place.
   */
  private async importMessages(messages: LocalMessage[], opts: { sharedBy?: string } = {}): Promise<number> {
    let imported = 0
    for (const raw of messages) {
      if (typeof raw.conversationId !== 'string' || typeof raw.key !== 'string' || typeof raw.senderUserId !== 'string'
        || typeof raw.sentAt !== 'string' || (raw.body !== null && typeof raw.body !== 'string')) continue
      const existing = (this.msgs.get(raw.conversationId) ?? []).find((x) => x.key === raw.key || (!!raw.id && x.id === raw.id))
      if (existing && (existing.body !== null || existing.deleted)) continue
      if (raw.id && await this.o.store.get('hidden', raw.id)) continue                       // deleted for me
      if (raw.expiresAt && Date.parse(raw.expiresAt) <= Date.now()) continue                // already disappeared
      const mine = raw.senderUserId === this.o.me.userId
      const msg: LocalMessage = {
        ...raw, key: existing?.key ?? raw.key, direction: mine ? 'out' : 'in', status: mine ? 'sent' : 'delivered',
        deliveredTo: Array.isArray(raw.deliveredTo) ? raw.deliveredTo : [], readBy: Array.isArray(raw.readBy) ? raw.readBy : [],
        readSent: true, error: undefined, ...(opts.sharedBy ? { sharedBy: opts.sharedBy } : {}),
      }
      await this.saveMessage(msg)
      if (msg.id) await this.markSeen(msg.id)
      imported++
    }
    return imported
  }

  /** Owner only (enforced by the server): promote a member to admin or demote an admin. */
  async setMemberRole(conversationId: string, userId: string, role: 'ADMIN' | 'MEMBER'): Promise<void> {
    const c = await this.o.api.setRole(conversationId, userId, role)
    this.convs.set(conversationId, c)
    this.addSystem(conversationId, role === 'ADMIN' ? `You made ${this.displayName(userId)} an admin` : `${this.displayName(userId)} is no longer an admin`)
    this.emit('conversations')
  }

  /** Owner/admin: rename the group or change its description (metadata only, not encrypted content). */
  async updateGroupInfo(conversationId: string, p: { name?: string; description?: string }): Promise<void> {
    const c = await this.o.api.updateGroup(conversationId, p)
    this.convs.set(conversationId, c)
    this.emit('conversations')
  }

  async removeMember(conversationId: string, userId: string): Promise<void> {
    await this.mutex.run(conversationId, async () => {
      for (let attempt = 0; ; attempt++) {
        await this.syncCommitsLocked(conversationId)
        const staged = await this.mls.prepareRemove(conversationId, userId)
        await this.mls.savePending(conversationId, staged)
        try {
          await this.o.api.removeMember(conversationId, userId, { commit: staged.commit, epoch: staged.epoch })
          await this.mls.applyStaged(conversationId, staged)
          this.addSystem(conversationId, `Removed ${this.displayName(userId)} · keys rotated (epoch ${staged.epoch + 1})`)
          break
        } catch (e) {
          await this.mls.discardPending(conversationId)
          if (attempt === 0 && e instanceof ApiError && e.code === 'EPOCH_CONFLICT') continue
          throw e
        }
      }
    })
    await this.refreshAfterChange(conversationId)
  }

  /**
   * Refresh after a change the server already accepted (and we already applied locally). Best effort:
   * a transient failure here (e.g. HTTP 429) must not report the completed change as failed — a retry
   * would then try to add someone who is already in the group. The membership event refreshes again.
   */
  private async refreshAfterChange(conversationId: string): Promise<void> {
    await this.reloadConversation(conversationId).catch((e) => this.log(`refresh of ${conversationId} after change failed`, e))
  }

  async leaveGroup(conversationId: string): Promise<void> {
    await this.o.api.leaveGroup(conversationId)
    await this.mls.forget(conversationId)
    this.groupReady.delete(conversationId)
    this.convs.delete(conversationId)
    this.emit('conversations')
  }

  /** Key update: an empty commit with a fresh path secret (post-compromise security). */
  async rotateGroupKeys(conversationId: string): Promise<void> {
    await this.mutex.run(conversationId, async () => {
      await this.syncCommitsLocked(conversationId)
      await this.submitCommit(conversationId, await this.mls.prepareUpdate(conversationId), 'UPDATE', [])
      this.addSystem(conversationId, 'Group keys rotated')
    })
  }

  /**
   * Makes the MLS tree match the authoritative membership: adds new devices of members, removes
   * leaves of revoked devices and of users who left. Any online member may do it; the server's
   * epoch compare-and-set lets exactly one concurrent attempt win.
   */
  async reconcileDevices(conversationId: string): Promise<boolean> {
    return this.mutex.run(conversationId, async () => {
      const conv = this.convs.get(conversationId)
      if (!conv || conv.type !== 'GROUP' || !(await this.mls.hasGroup(conversationId))) return false
      await this.syncCommitsLocked(conversationId)
      if (!(await this.mls.hasGroup(conversationId))) return false
      const members = new Set(conv.members.map((m) => m.userId))
      const expected = new Map<string, Set<string>>()
      for (const u of members) expected.set(u, new Set((await this.directory.identities(u, true)).map((d) => d.deviceId)))
      const leaves = await this.mls.members(conversationId)
      const inTree = new Set(leaves.map((l) => l.deviceId))
      const removeLeaves = leaves.filter((l) => l.deviceId !== this.o.me.deviceId
        && (!members.has(l.userId) || !expected.get(l.userId)?.has(l.deviceId))).map((l) => l.leafIndex)
      const adds: { userId: string; bundle: DeviceBundle }[] = []
      for (const [u, ds] of expected) {
        const missing = [...ds].filter((d) => !inTree.has(d))
        if (missing.length === 0) continue
        for (const b of (await this.directory.claimBundle(u, true, missing)).devices) if (b.mlsKeyPackage) adds.push({ userId: u, bundle: b })
      }
      if (adds.length === 0 && removeLeaves.length === 0) return false
      try {
        await this.submitCommit(conversationId, await this.mls.prepareSync(conversationId, adds, removeLeaves), 'DEVICE_SYNC',
          adds.map((a) => ({ deviceId: a.bundle.deviceId, keyPackageId: a.bundle.mlsKeyPackage!.id })))
        this.addSystem(conversationId, 'Group devices updated · keys rotated')
        return true
      } catch (e) {
        if (e instanceof ApiError && e.code === 'EPOCH_CONFLICT') return false  // someone else reconciled first
        throw e
      }
    })
  }

  private async submitCommit(conversationId: string, staged: StagedCommit, kind: string,
    welcomeFor: { deviceId: string; keyPackageId: string }[]): Promise<void> {
    const before = await this.leafDevices(conversationId)
    await this.mls.savePending(conversationId, staged)
    try {
      await this.o.api.commit(conversationId, {
        commit: staged.commit, epoch: staged.epoch, kind,
        welcomes: staged.welcome ? welcomeFor.map((w) => ({ ...w, welcome: staged.welcome! })) : [],
      })
      await this.mls.applyStaged(conversationId, staged)
    } catch (e) {
      await this.mls.discardPending(conversationId)
      throw e
    }
    this.afterTreeChange(conversationId, before)
  }

  private async leafDevices(conversationId: string): Promise<Set<string>> {
    if (!(await this.mls.hasGroup(conversationId).catch(() => false))) return new Set()
    return new Set((await this.mls.members(conversationId).catch(() => [])).map((l) => l.deviceId))
  }

  /**
   * The group tree changed. If a device of someone I shared history with has joined since (e.g. the new
   * member opened another browser after being added), re-send the share key in the CURRENT epoch so that
   * device can open it too. Runs after the lock is released (sending takes the lock).
   */
  private afterTreeChange(conversationId: string, before: Set<string>): void {
    setTimeout(() => void this.reannounceShares(conversationId, before).catch((e) => this.log('history re-share failed', e)), 0)
  }

  private async reannounceShares(conversationId: string, before: Set<string>): Promise<void> {
    const mine: { key: string; s: SharedRecord }[] = []
    for (const k of await this.o.store.keys('myShare')) {
      const r = await this.o.store.get<SharedRecord>('myShare', k)
      if (!r) continue
      if (Date.parse(r.expiresAt) < Date.now()) await this.o.store.delete('myShare', k)
      else if (r.conversationId === conversationId) mine.push({ key: k, s: r })
    }
    if (mine.length === 0) return
    const conv = this.convs.get(conversationId)
    if (!conv || !(await this.mls.hasGroup(conversationId))) return
    const joined = (await this.mls.members(conversationId)).filter((l) => !before.has(l.deviceId))
    for (const { s: r } of mine) {
      const forThem = joined.filter((l) => r.payload.forUserIds.includes(l.userId) && !r.sentTo.includes(l.deviceId))
      if (before.size === 0 || forThem.length === 0) continue
      const clientMessageId = uuid()
      const payload: HistorySharePayload = { ...r.payload, sentAt: new Date().toISOString(), clientMessageId }
      const ack = await retry429(() => this.mutex.run(conversationId, () => this.encryptAndTransmit(conv, clientMessageId, encodePayload(payload))))
      await this.markSeen(ack.messageId)
      r.sentTo.push(...forThem.map((l) => l.deviceId))
      await this.o.store.put('myShare', r.payload.shareId, r)
      this.log(`re-sent history share ${r.payload.shareId} for ${forThem.length} newly joined device(s)`)
    }
  }

  /** Applies server commits in epoch order (caller holds the conversation lock). */
  private async syncCommitsLocked(conversationId: string): Promise<void> {
    const before = await this.leafDevices(conversationId)
    try {
      await this.applyServerCommits(conversationId)
    } finally {
      this.afterTreeChange(conversationId, before)
    }
  }

  private async applyServerCommits(conversationId: string): Promise<void> {
    if (!(await this.mls.hasGroup(conversationId))) return
    const commitSender = async (epoch: number) =>
      (await this.o.api.commits(conversationId, epoch)).find((c) => c.epoch === epoch)?.senderDeviceId ?? null
    await this.mls.recoverPending(conversationId, commitSender, this.o.me.deviceId)
    for (;;) {
      const epoch = await this.mls.epoch(conversationId)
      let commits
      try {
        commits = await this.o.api.commits(conversationId, epoch)
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) {
          // no longer a member (removed while offline)
          await this.mls.forget(conversationId)
          this.groupReady.delete(conversationId)
          this.convs.delete(conversationId)
          this.emit('conversations')
          return
        }
        throw e
      }
      const next = commits.filter((c) => c.epoch >= epoch).sort((a, b) => a.epoch - b.epoch)
      if (next.length === 0 || next[0].epoch !== epoch) return
      for (const c of next) {
        if ((await this.mls.epoch(conversationId)) !== c.epoch) break
        if (c.senderDeviceId === this.o.me.deviceId) {
          this.log(`own commit at epoch ${c.epoch} is not in local state; group needs a re-add`)
          return
        }
        const r = await this.mls.process(conversationId, c.commit)
        if (r.kind === 'commit') {
          if (r.removedSelf) {
            this.addSystem(conversationId, 'You were removed from this group')
            await this.mls.forget(conversationId)
            this.groupReady.delete(conversationId)
            return
          }
          this.addSystem(conversationId, `Group membership changed · keys rotated (epoch ${r.epoch})`)
        }
      }
      if (next.length < 100) return
    }
  }

  private async onPeerDeviceOnline(userId: string, deviceId: string): Promise<void> {
    for (const c of this.convs.values()) {
      if (c.type !== 'GROUP' || !c.members.some((m) => m.userId === userId) || !(await this.mls.hasGroup(c.id))) continue
      if ((await this.mls.members(c.id)).some((l) => l.deviceId === deviceId)) continue
      // jitter so several online members don't race for the same epoch (losers get EPOCH_CONFLICT and stop)
      setTimeout(() => void this.reconcileDevices(c.id).catch((e) => this.log('reconcile failed', e)), 500 + Math.random() * 2500)
    }
  }

  /** Why accepting a group invitation (MLS Welcome) failed on this device, per conversation. */
  private joinErrors = new Map<string, string>()
  private reportedJoinFailures = new Set<string>()

  private noteJoinFailure(conversationId: string, welcomeId: string, e: unknown): void {
    const why = e instanceof Error ? `${e.name !== 'Error' ? e.name + ': ' : ''}${e.message}` : String(e)
    this.log(`welcome ${welcomeId} for ${conversationId} failed`, e)
    this.joinErrors.set(conversationId, why)
    if (!this.reportedJoinFailures.has(welcomeId)) {
      this.reportedJoinFailures.add(welcomeId)
      this.addSystem(conversationId, `Couldn't open the group invitation on this device: ${why}`)
    }
    this.emit('conversations', conversationId)
  }

  /** Last reason this device failed to join a group (shown in the "waiting for keys" banner). */
  joinError(conversationId: string): string | undefined {
    return this.joinErrors.get(conversationId)
  }

  /** Groups listed for me whose keys this device doesn't have yet: look for a Welcome. */
  private async joinWaitingGroups(): Promise<void> {
    for (const c of this.convs.values()) {
      if (c.type === 'GROUP' && !(await this.mls.hasGroup(c.id))) {
        await this.processWelcomes()
        return
      }
    }
  }

  async processWelcomes(): Promise<void> {
    let welcomes
    try {
      welcomes = await retry429(() => this.o.api.welcomes())
    } catch (e) {
      this.log('fetching welcomes failed', e)
      return
    }
    for (const w of welcomes) {
      await this.mutex.run(w.conversationId, async () => {
        if (!(await this.mls.hasGroup(w.conversationId))) {
          await this.mls.joinFromWelcome(w.conversationId, w.welcome, w.keyPackageId ?? undefined)
          this.groupReady.add(w.conversationId)
          this.addSystem(w.conversationId, `You joined · end-to-end encrypted with MLS (epoch ${w.epoch})`)
          await this.syncCommitsLocked(w.conversationId)
          this.socket.send({ type: 'sync' })   // replay messages deferred while this device had no group keys
        }
        await this.o.api.consumeWelcome(w.id)
        this.joinErrors.delete(w.conversationId)
      }).catch((e) => this.noteJoinFailure(w.conversationId, w.id, e))
      if (!this.convs.has(w.conversationId)) await this.reloadConversation(w.conversationId).catch(() => undefined)
    }
    this.emit('conversations')
  }

  // ------------------------------------------------------------------ sending

  async send(conversationId: string, text: string): Promise<LocalMessage> {
    if (!this.convs.has(conversationId)) throw new Error('unknown conversation')
    const clientMessageId = uuid()
    const local: LocalMessage = {
      key: `${this.o.me.deviceId}:${clientMessageId}`, clientMessageId, conversationId, senderUserId: this.o.me.userId,
      senderDeviceId: this.o.me.deviceId, direction: 'out', body: text, sentAt: new Date().toISOString(), status: 'pending',
      deliveredTo: [], readBy: [], ...(this.convs.get(conversationId)?.disappearingSeconds
        ? { expiresIn: this.convs.get(conversationId)!.disappearingSeconds! } : {}),
    }
    await this.saveMessage(local)
    await this.transmitLocal(local)
    return local
  }

  /** Encrypts at transmission time (so a queued message never holds stale ciphertext). */
  private async transmitLocal(local: LocalMessage): Promise<void> {
    const conv = this.convs.get(local.conversationId)
    if (!conv || local.body === null) return
    const plaintext = encodePayload({ v: 1, kind: 'text', body: local.body, sentAt: local.sentAt, clientMessageId: local.clientMessageId,
      ...(local.expiresIn ? { expiresIn: local.expiresIn } : {}) })
    try {
      // a rate limit is transient: back off and resend (idempotent by clientMessageId) instead of failing the message
      const ack = await retry429(() => this.mutex.run(conv.id, () => this.encryptAndTransmit(conv, local.clientMessageId, plaintext, local.expiresIn)))
      Object.assign(local, { id: ack.messageId, serverAt: ack.createdAt, status: 'sent' as const, error: undefined,
        ...(local.expiresIn ? { expiresAt: new Date(Date.parse(ack.createdAt) + local.expiresIn * 1000).toISOString() } : {}) })
      await this.markSeen(ack.messageId)
    } catch (e) {
      if (NETWORK(e)) {
        local.status = 'queued'   // offline: retried with the same clientMessageId when the socket is back
      } else {
        local.status = 'failed'
        local.error = e instanceof IdentityChangedError ? 'Safety number changed — verify before sending'
          : e instanceof Error ? e.message : 'Failed to send'
        if (e instanceof IdentityChangedError) this.identityWarnings.add(conv.id)
      }
    }
    await this.saveMessage(local)
  }

  async retry(conversationId: string, key: string): Promise<void> {
    const m = this.msgs.get(conversationId)?.find((x) => x.key === key)
    if (!m || (m.status !== 'failed' && m.status !== 'queued')) return
    m.status = 'pending'
    await this.saveMessage(m)
    await this.transmitLocal(m)
  }

  private async flushQueue(): Promise<void> {
    if (this.flushing) return
    this.flushing = true
    try {
      for (const list of this.msgs.values()) {
        for (const m of list.filter((x) => x.status === 'queued' || x.status === 'pending')) {
          if (!this.socket.isOpen) return
          await this.transmitLocal(m)
        }
      }
    } finally {
      this.flushing = false
    }
  }

  private async encryptAndTransmit(conv: Conversation, clientMessageId: string, plaintext: Uint8Array, expiresIn?: number): Promise<Accepted> {
    for (let attempt = 0; ; attempt++) {
      let body: SendBody
      if (conv.type === 'DIRECT') {
        const targets = await this.directTargets(conv, attempt > 0)
        if (targets.length === 0) throw new Error('Recipient has no registered device yet')
        body = { clientMessageId, protocol: 'SIGNAL', envelopes: await this.signal.encrypt(targets, plaintext) }
        if (expiresIn) body.expiresInSeconds = expiresIn
      } else {
        if (!(await this.mls.hasGroup(conv.id))) await this.processWelcomesFor(conv.id)   // a Welcome may be waiting
        await this.syncCommitsLocked(conv.id)
        if (!(await this.mls.hasGroup(conv.id))) {
          const why = this.joinErrors.get(conv.id)
          throw new Error(why ? `Group keys are not available on this device: the invitation could not be opened (${why})`
            : 'Group keys are not available on this device yet')
        }
        const { ciphertext, epoch } = await this.mls.encrypt(conv.id, plaintext)
        body = { clientMessageId, protocol: 'MLS', ciphertext, epoch }
        if (expiresIn) body.expiresInSeconds = expiresIn
      }
      try {
        return await this.transmit(conv.id, body)
      } catch (e) {
        const code = e instanceof ApiError ? e.code : (e as { code?: string })?.code
        if (attempt < 2 && code === 'DEVICE_LIST_STALE') continue
        if (attempt < 2 && code === 'EPOCH_MISMATCH') continue
        throw e
      }
    }
  }

  /** WebSocket when connected (ACCEPTED only after durable persistence), REST otherwise; both idempotent. */
  private async transmit(conversationId: string, body: SendBody): Promise<Accepted> {
    if (this.socket.isOpen) {
      try {
        return await new Promise<Accepted>((resolve, reject) => {
          this.pendingAcks.set(body.clientMessageId, { resolve, reject })
          this.socket.send({ type: 'send', conversationId, ...body })
          setTimeout(() => reject(Object.assign(new Error('ack timeout'), { code: 'ACK_TIMEOUT' })), 10_000)
        }).finally(() => this.pendingAcks.delete(body.clientMessageId))
      } catch (e) {
        if ((e as { code?: string }).code !== 'ACK_TIMEOUT') throw e
      }
    }
    return this.o.api.sendMessage(conversationId, body)
  }

  /** Every active device of the other member, plus my own other devices (multi-device sync). */
  private async directTargets(conv: Conversation, fresh: boolean): Promise<DeviceTarget[]> {
    const out: DeviceTarget[] = []
    for (const m of conv.members) {
      for (const d of await this.directory.identities(m.userId, fresh)) {
        if (d.deviceId !== this.o.me.deviceId) out.push({ userId: m.userId, deviceId: d.deviceId })
      }
    }
    return out
  }

  // ------------------------------------------------------------------ edit, delete, disappearing messages

  private find(conversationId: string, key: string): LocalMessage | undefined {
    return this.msgs.get(conversationId)?.find((x) => x.key === key)
  }

  /** Sends an encrypted control notice in the chat (expires with the chat's timer like any message). */
  private async sendNotice(conversationId: string, payload: EditPayload | DeletePayload | TimerPayload): Promise<void> {
    const conv = this.convs.get(conversationId)
    if (!conv) throw new Error('unknown conversation')
    const timer = conv.disappearingSeconds ?? undefined
    const ack = await retry429(() => this.mutex.run(conversationId, () =>
      this.encryptAndTransmit(conv, payload.clientMessageId, encodePayload(payload), timer || undefined)))
    await this.markSeen(ack.messageId)
  }

  /** Edit my own text message (within 1 hour of sending). */
  async editMessage(conversationId: string, key: string, body: string): Promise<void> {
    const m = this.find(conversationId, key)
    const text = body.trim()
    if (!m || !canEdit(m)) throw new Error('This message can no longer be edited (only your own messages, within 1 hour)')
    if (!text || text === m.body) return
    await this.sendNotice(conversationId, { v: 1, kind: 'edit', target: m.id!, body: text, sentAt: new Date().toISOString(),
      clientMessageId: uuid() })
    await this.saveMessage({ ...m, body: text, editedAt: new Date().toISOString() })
  }

  /** Delete my own message for everyone (within 24 hours): server copy removed, every device shows a tombstone. */
  async deleteForEveryone(conversationId: string, key: string): Promise<void> {
    const m = this.find(conversationId, key)
    if (!m || !canDeleteForEveryone(m)) throw new Error('Only your own messages can be deleted for everyone, within 24 hours')
    await this.o.api.deleteMessage(conversationId, m.id!, m.serverAt ?? m.sentAt).catch((e) => {
      // only "message not found" means it is already gone (a retry); anything else must surface
      if (!(e instanceof ApiError && e.status === 404 && e.message === 'message not found')) throw e
    })
    await this.sendNotice(conversationId, { v: 1, kind: 'delete', target: m.id!, sentAt: new Date().toISOString(), clientMessageId: uuid() })
    await this.saveMessage({ ...m, body: null, deleted: true, error: undefined, editedAt: undefined })
  }

  /** Remove a message from this device only (any message, any age). */
  async deleteForMe(conversationId: string, key: string): Promise<void> {
    const m = this.find(conversationId, key)
    if (!m) return
    await this.removeLocal(m)
    if (m.id) await this.o.store.put('hidden', m.id, 1)   // never re-imported by history sync
  }

  private async removeLocal(m: LocalMessage): Promise<void> {
    const list = this.msgs.get(m.conversationId) ?? []
    this.msgs.set(m.conversationId, list.filter((x) => x.key !== m.key))
    await this.o.store.delete('msg', `${m.conversationId}:${m.key}`)
    if (m.id) await this.markSeen(m.id)
    this.emit('messages', m.conversationId)
  }

  /** Turn disappearing messages on (one of the allowed timers) or off (0) for a chat; everyone is told. */
  async setDisappearing(conversationId: string, seconds: number): Promise<void> {
    if (seconds !== 0 && !isAllowedTimer(seconds)) throw new Error('unsupported timer')
    const c = await this.o.api.setDisappearing(conversationId, seconds)
    this.convs.set(conversationId, c)
    await this.sendNotice(conversationId, { v: 1, kind: 'timer', seconds, sentAt: new Date().toISOString(), clientMessageId: uuid() })
    this.addSystem(conversationId, seconds ? `You set disappearing messages to ${timerLabel(seconds)}` : 'You turned off disappearing messages')
    this.emit('conversations', conversationId)
  }

  /** Edit/delete notices that arrived before the message they refer to (applied when it arrives). */
  private pendingNotices = new Map<string, { from: string; at: string; notice: EditPayload | DeletePayload }[]>()

  private async applyNotice(conversationId: string, from: string, at: string, n: EditPayload | DeletePayload | TimerPayload, mine: boolean) {
    if (n.kind === 'timer') {
      const c = this.convs.get(conversationId)
      if (c) this.convs.set(conversationId, { ...c, disappearingSeconds: n.seconds || null })
      const who = mine ? 'You' : this.displayName(from)
      this.addSystem(conversationId, n.seconds ? `${who} set disappearing messages to ${timerLabel(n.seconds)}`
        : `${who} turned off disappearing messages`)
      this.emit('conversations', conversationId)
      return
    }
    const target = (this.msgs.get(conversationId) ?? []).find((x) => x.id === n.target)
    if (!target) {
      const list = this.pendingNotices.get(n.target) ?? []
      this.pendingNotices.set(n.target, [...list, { from, at, notice: n }])
      return
    }
    if (n.kind === 'edit' && acceptEdit(target, from, at)) {
      await this.saveMessage({ ...target, body: n.body, editedAt: at })
    } else if (n.kind === 'delete' && acceptDelete(target, from, at)) {
      await this.saveMessage({ ...target, body: null, deleted: true, error: undefined, editedAt: undefined })
    } else {
      this.log(`ignored ${n.kind} notice from ${from} for ${n.target} (not the sender, or outside the time window)`)
    }
  }

  private async applyPendingNotices(conversationId: string, messageId: string): Promise<void> {
    const waiting = this.pendingNotices.get(messageId)
    if (!waiting) return
    this.pendingNotices.delete(messageId)
    for (const w of waiting) await this.applyNotice(conversationId, w.from, w.at, w.notice, false)
  }

  /** Removes messages whose disappearing timer has run out (every 30 s and at start). */
  private async sweepExpired(): Promise<void> {
    const now = Date.now()
    for (const list of [...this.msgs.values()]) {
      for (const m of list.filter((x) => x.expiresAt && Date.parse(x.expiresAt) <= now)) await this.removeLocal(m)
    }
  }

  // ------------------------------------------------------------------ receiving

  private async onDelivery(m: DeliveredMessage): Promise<void> {
    let notReady = false
    if (!this.convs.has(m.conversationId)) await this.reloadConversation(m.conversationId).catch(() => undefined)
    await this.mutex.run(m.conversationId, async () => {
      if (await this.o.store.get('seen', m.messageId)) return  // duplicate delivery: just re-acknowledge
      let body: string | null = null
      let error: string | undefined
      let sentAt = m.createdAt
      let control: HistorySharePayload | HistoryGrantPayload | null = null
      let notice: EditPayload | DeletePayload | TimerPayload | null = null
      let expiresIn: number | undefined
      try {
        let plaintext: Uint8Array | null = null
        if (m.protocol === 'SIGNAL') {
          plaintext = await this.signal.decrypt(m.senderDeviceId, fromBase64(m.ciphertext), m.signalType ?? 1)
        } else {
          if (!(await this.mls.hasGroup(m.conversationId))) await this.processWelcomesFor(m.conversationId)
          if (!(await this.mls.hasGroup(m.conversationId))) {
            // not joined yet (Welcome not here yet): leave it undelivered — no bubble, no ACK. It is replayed
            // after this device joins (see processWelcomes), instead of becoming "Unable to decrypt".
            this.log(`deferring ${m.messageId}: group keys not on this device yet`)
            notReady = true
            return
          }
          if ((m.epoch ?? 0) > (await this.mls.epoch(m.conversationId))) await this.syncCommitsLocked(m.conversationId)
          const r = await this.mls.process(m.conversationId, m.ciphertext)
          if (r.kind === 'stale') {
            // from before this device joined: not readable here by design (history comes via share / sync)
            await this.markSeen(m.messageId)
            return
          }
          if (r.kind === 'application') plaintext = r.plaintext
        }
        if (plaintext) {
          const p = decodePayload(plaintext)
          if (p.kind === 'history-share' || p.kind === 'history-grant') {
            if (m.protocol !== 'MLS') throw new Error('history shares are only valid in groups')
            control = p
          } else if (p.kind === 'edit' || p.kind === 'delete' || p.kind === 'timer') {
            notice = p
          } else {
            body = p.body
            sentAt = p.sentAt
            expiresIn = p.expiresIn
          }
        }
      } catch (e) {
        this.log(`decrypt ${m.messageId} failed`, e)
        if (e instanceof IdentityChangedError) this.identityWarnings.add(m.conversationId)
        error = e instanceof IdentityChangedError ? 'Safety number changed — message could not be verified' : 'Unable to decrypt this message'
      }
      const mine = m.senderUserId === this.o.me.userId
      if (control?.kind === 'history-grant') {
        const g = control
        void this.onHistoryGrant(m.conversationId, m.senderUserId, g).catch((e) => this.log('history grant failed', e))
        await this.markSeen(m.messageId)
        return
      }
      if (control) {
        // a control message, not a chat bubble
        const p = control
        const forMe = p.forUserIds.includes(this.o.me.userId) && !mine
        if (forMe) {
          void this.receiveHistoryShare(m.conversationId, m.senderUserId, p)
        } else {
          const who = mine ? 'You' : this.displayName(m.senderUserId)
          this.addSystem(m.conversationId, `${who} shared ${p.messageCount} earlier message${p.messageCount === 1 ? '' : 's'} with `
            + `${p.forUserIds.map((u) => this.displayName(u)).join(', ')}`)
        }
        await this.markSeen(m.messageId)
        return
      }
      if (notice) {
        // edit / delete-for-everyone / timer change: a control message, never a bubble
        await this.applyNotice(m.conversationId, m.senderUserId, m.createdAt, notice, mine)
        await this.markSeen(m.messageId)
        return
      }
      const expiresAt = expiresIn ? new Date(Date.parse(m.createdAt) + expiresIn * 1000).toISOString() : undefined
      if (expiresAt && Date.parse(expiresAt) <= Date.now()) {
        await this.markSeen(m.messageId)   // already expired (device was offline): never shown
        return
      }
      await this.saveMessage({
        key: m.messageId, clientMessageId: m.messageId, id: m.messageId, conversationId: m.conversationId,
        senderUserId: m.senderUserId, senderDeviceId: m.senderDeviceId, direction: mine ? 'out' : 'in', body, error,
        sentAt, serverAt: m.createdAt, status: mine ? 'sent' : 'delivered', deliveredTo: [], readBy: [], readSent: mine,
        ...(expiresAt ? { expiresIn, expiresAt } : {}),
      })
      await this.applyPendingNotices(m.conversationId, m.messageId)
      await this.markSeen(m.messageId)   // durable locally BEFORE acknowledging
    })
    if (notReady) return
    this.deliveredQueue.push(m.messageId)
    this.flushDelivered()
  }

  private async processWelcomesFor(conversationId: string): Promise<void> {
    const ws = (await retry429(() => this.o.api.welcomes())).filter((w) => w.conversationId === conversationId)
    for (const w of ws) {
      try {
        await this.mls.joinFromWelcome(w.conversationId, w.welcome, w.keyPackageId ?? undefined)
        this.groupReady.add(w.conversationId)
        await this.o.api.consumeWelcome(w.id)
        this.socket.send({ type: 'sync' })   // replay messages deferred while this device had no group keys
        this.joinErrors.delete(w.conversationId)
      } catch (e) {
        this.noteJoinFailure(w.conversationId, w.id, e)
      }
    }
  }

  private async markSeen(messageId: string): Promise<void> {
    await this.o.store.put('seen', messageId, 1)
  }

  /**
   * "Load older messages": fetch one more month of server history (default; or back to {@code until}).
   * - group messages from before I joined are not shown (counted for a single "can't see" notice);
   * - messages from before this device existed are requested automatically from my other devices
   *   (no per-message placeholders; they appear when the transfer arrives);
   * - newer ones this device somehow missed get a placeholder and trigger a resync.
   */
  async loadOlder(conversationId: string, until?: number): Promise<OlderInfo> {
    const meta = (await this.o.store.get<{ cursor: string | null; done: boolean; target?: number }>('history', conversationId))
      ?? { cursor: null, done: false }
    const prev = this.older.get(conversationId) ?? { syncing: 0, beforeJoin: 0, done: false }
    if (meta.done) {
      const info = { ...prev, done: true }
      this.older.set(conversationId, info)
      return info
    }
    const target = until ?? (meta.target ?? Date.now()) - MONTH
    const deviceCreated = await this.deviceCreatedAt()
    const conv = this.convs.get(conversationId)
    const joined = conv?.type === 'GROUP' ? Date.parse(conv.members.find((m) => m.userId === this.o.me.userId)?.joinedAt ?? '') || 0 : 0
    let cursor = meta.cursor
    let done = false
    let oldest = Date.now()
    let syncing = 0
    let beforeJoin = 0
    let preFrom = Infinity
    let preTo = 0
    let missedSinceDevice = false
    for (let pages = 0; pages < 40; pages++) {
      const page = await retry429(() => this.o.api.history(conversationId, cursor ?? undefined, 100))
      for (const h of page.messages) {
        const at = Date.parse(h.createdAt)
        oldest = Math.min(oldest, at)
        if (await this.o.store.get('seen', h.messageId)) continue
        if ((this.msgs.get(conversationId) ?? []).some((x) => x.id === h.messageId)) continue
        if (joined && at < joined) {
          beforeJoin++
          continue
        }
        // Only messages older than this device are inherently unreadable here (keys never leave a device).
        if (at < deviceCreated || (h.protocol === 'SIGNAL' && !h.envelope)) {
          syncing++
          preFrom = Math.min(preFrom, at)
          preTo = Math.max(preTo, at)
          continue
        }
        // A newer one that this device has not received is a delivery gap: ask for a resync.
        missedSinceDevice = true
        await this.saveMessage({
          key: h.messageId, clientMessageId: h.clientMessageId, id: h.messageId, conversationId, senderUserId: h.senderUserId,
          senderDeviceId: h.senderDeviceId, direction: h.senderUserId === this.o.me.userId ? 'out' : 'in', body: null,
          error: 'Not received on this device yet — resyncing', sentAt: h.createdAt, serverAt: h.createdAt,
          status: 'sent', deliveredTo: [], readBy: [], readSent: true,
        })
      }
      cursor = page.nextCursor
      if (!cursor) {
        done = true
        break
      }
      if (oldest <= target) break
    }
    if (missedSinceDevice) this.socket.send({ type: 'sync' })   // Delivery Service replays anything still pending
    await this.o.store.put('history', conversationId, { cursor, done, target })
    // ask my other devices for the months that this device cannot read (one month per request)
    // pad by 10 min: server timestamps (used here) and senders' clocks (used by the answering device) differ slightly
    if (syncing > 0) void this.requestWindows(preFrom - SKEW, Math.min(Date.now(), preTo + SKEW)).catch((e) => this.log('history request failed', e))
    const info: OlderInfo = { syncing: prev.syncing + syncing, beforeJoin: prev.beforeJoin + beforeJoin, done }
    this.older.set(conversationId, info)
    this.emit('messages', conversationId)
    return info
  }

  /** What earlier "Load older" calls found (for the notices at the top of a conversation). */
  olderInfo(conversationId: string): OlderInfo | undefined {
    return this.older.get(conversationId)
  }

  async markRead(conversationId: string): Promise<void> {
    const unread = (this.msgs.get(conversationId) ?? []).filter((m) => m.direction === 'in' && m.id && !m.readSent)
    if (unread.length === 0) return
    if (!this.socket.send({ type: 'ack', status: 'READ', messageIds: unread.map((m) => m.id) })) return
    for (const m of unread) {
      m.readSent = true
      await this.saveMessage(m)
    }
    this.emit('conversations', conversationId)
  }

  sendTyping(conversationId: string, typing: boolean): void {
    this.socket.send({ type: 'typing', conversationId, state: typing ? 'start' : 'stop' })
  }

  private flushDelivered(): void {
    if (this.deliveredQueue.length === 0) return
    const ids = this.deliveredQueue.splice(0, this.deliveredQueue.length)
    for (let i = 0; i < ids.length; i += 200) {
      if (!this.socket.send({ type: 'ack', status: 'DELIVERED', messageIds: ids.slice(i, i + 200) })) {
        // not acknowledged: Delivery Service redelivers on reconnect and we dedupe by message id
        return
      }
    }
  }

  private async refreshPresence(): Promise<void> {
    const users = new Set<string>()
    for (const c of this.convs.values()) for (const m of c.members) if (m.userId !== this.o.me.userId) users.add(m.userId)
    if (users.size === 0) return
    try {
      for (const p of await this.o.api.presence([...users].slice(0, 200))) this.presenceMap.set(p.userId, p)
      this.emit('presence')
    } catch (e) {
      this.log('presence failed', e)
    }
  }

  /** User-level status from per-device receipts: delivered/read once ≥ 1 device of EVERY other member. */
  private recomputeStatus(m: LocalMessage): void {
    if (m.direction !== 'out' || ['pending', 'queued', 'failed'].includes(m.status)) return
    const others = (this.convs.get(m.conversationId)?.members ?? []).map((x) => x.userId).filter((u) => u !== this.o.me.userId)
    if (others.length > 0 && others.every((u) => m.readBy.includes(u))) m.status = 'read'
    else if (others.length > 0 && others.every((u) => m.deliveredTo.includes(u) || m.readBy.includes(u))) m.status = 'delivered'
    else m.status = 'sent'
  }

  private applyReceipt(conversationId: string, messageId: string, userId: string, status: string): void {
    const m = (this.msgs.get(conversationId) ?? []).find((x) => x.id === messageId)
    if (!m || userId === this.o.me.userId) return
    if ((status === 'DELIVERED' || status === 'READ') && !m.deliveredTo.includes(userId)) m.deliveredTo.push(userId)
    if (status === 'READ' && !m.readBy.includes(userId)) m.readBy.push(userId)
    this.recomputeStatus(m)
    void this.saveMessage(m)
  }

  /** After reconnecting, receipts that happened while offline are fetched (sender-only API). */
  private async resyncReceipts(): Promise<void> {
    const week = Date.now() - 7 * 24 * 3600 * 1000
    const open = [...this.msgs.values()].flat().filter((m) => m.direction === 'out' && m.id && m.senderDeviceId === this.o.me.deviceId
      && (m.status === 'sent' || m.status === 'delivered') && Date.parse(m.sentAt) > week).slice(-30)
    for (const m of open) {
      try {
        for (const r of await this.o.api.receipts(m.id!)) this.applyReceipt(m.conversationId, m.id!, r.recipientUserId, r.status)
      } catch {
        /* message receipts not available */
      }
    }
  }

  // ------------------------------------------------------------------ frames

  private async onFrame(f: Record<string, unknown>): Promise<void> {
    try {
      switch (f.type) {
        case 'ready':
          await this.refreshConversations()
          await this.processWelcomes()
          this.socket.send({ type: 'sync' })
          void this.flushQueue()
          void this.resyncReceipts()
          void this.refreshPresence()
          for (const c of this.convs.values()) if (c.type === 'GROUP') void this.reconcileDevices(c.id).catch((e) => this.log('reconcile failed', e))
          break
        case 'accepted':
          this.pendingAcks.get(String(f.clientMessageId))?.resolve(f as unknown as Accepted)
          break
        case 'error': {
          const p = this.pendingAcks.get(String(f.clientMessageId))
          if (p) p.reject(new ApiError({ status: 409, code: String(f.code), detail: String(f.message), ...f }))
          else this.log(`server error frame ${String(f.code)}`)
          break
        }
        case 'message':
          await this.onDelivery(f as unknown as DeliveredMessage)
          break
        case 'receipt':
          this.applyReceipt(String(f.conversationId), String(f.messageId), String(f.userId), String(f.status))
          this.emit('messages', String(f.conversationId))
          break
        case 'typing': {
          const conv = String(f.conversationId)
          if (!this.typingMap.has(conv)) this.typingMap.set(conv, new Map())
          if (f.state === 'start') this.typingMap.get(conv)!.set(String(f.userId), Date.now() + 6000)
          else this.typingMap.get(conv)!.delete(String(f.userId))
          this.emit('typing', conv)
          setTimeout(() => this.emit('typing', conv), 6100)
          break
        }
        case 'presence':
          this.presenceMap.set(String(f.userId), {
            userId: String(f.userId), status: f.online ? 'ONLINE' : 'OFFLINE', lastSeen: (f.lastSeen as string) ?? null, activeDevices: 0,
          })
          this.emit('presence')
          break
        case 'membership': {
          const id = String(f.conversationId)
          // back off on rate limits instead of dropping the event (a missed "you were added" left the
          // device waiting for group keys until the next message arrived)
          await retry429(() => this.reloadConversation(id))
          // also on 'commit': a DEVICE_SYNC commit may have just added THIS device (its Welcome is waiting)
          if (f.event === 'member.added' || f.event === 'created'
            || (this.convs.get(id)?.type === 'GROUP' && !(await this.mls.hasGroup(id)))) await this.processWelcomes()
          if (this.convs.get(id)?.type === 'GROUP') {
            await this.mutex.run(id, () => this.syncCommitsLocked(id)).catch((e) => this.log('commit sync failed', e))
            if (f.event === 'member.removed' || f.event === 'member.added') {
              // jitter so concurrent members don't all race for the same epoch
              setTimeout(() => void this.reconcileDevices(id).catch((e) => this.log('reconcile failed', e)), Math.random() * 1500)
            }
          }
          break
        }
        case 'device': {
          // a device of a peer (or mine) came online: if it belongs in one of my groups but isn't in the
          // MLS tree yet (e.g. a new browser after being invited), add it now instead of on my next reconnect
          if (f.event === 'connected') void this.onPeerDeviceOnline(String(f.userId), String(f.deviceId))
          break
        }
        case 'prekeys_low':
          await replenishKeys(this.o.api, this.o.store, this.signalStore, this.mls)
          break
        case 'revoked':
          this.o.onTerminated?.(String(f.reason ?? 'revoked'))
          break
      }
    } catch (e) {
      this.log(`frame ${String(f.type)} failed`, e)
    }
  }

  // ------------------------------------------------------------------ device-to-device history transfer

  /** Transfers this device takes part in (own requests + requests from other devices it may approve). */
  historyTransfers(): HistoryTransfer[] {
    return [...this.transferList]
  }

  async refreshTransfers(): Promise<void> {
    try {
      this.transferList = await this.o.api.transfers()
    } catch {
      return
    }
    this.emit('transfers')
    for (const t of this.transferList) {
      // another of my devices asks for history: answer automatically (no manual approval)
      if (t.status === 'REQUESTED' && t.targetDeviceId !== this.o.me.deviceId && !this.autoAnswered.has(t.id)) {
        this.autoAnswered.add(t.id)
        void this.autoSendHistory(t).catch((e) => this.log('automatic history send failed', e))
      }
      if (t.status === 'READY' && t.targetDeviceId === this.o.me.deviceId && !this.receiving.has(t.id)) {
        this.receiving.add(t.id)
        void this.receiveHistory(t).catch((e) => {
          this.log('history import failed', e)
          this.receiving.delete(t.id)
        })
      }
    }
  }

  /**
   * Ask my other devices for the history in [from, to] (default: the last month). They answer
   * automatically; the window is bound into the signed request so the server cannot widen it.
   */
  async requestHistory(window?: { from: number; to: number }): Promise<HistoryTransfer> {
    const to = Math.floor(window?.to ?? Date.now())
    const from = Math.floor(Math.max(window?.from ?? to - MONTH, to - MONTH))
    const eph = await Transfer.newEphemeralKey()
    const identity = (await this.signalStore.getIdentityKeyPair())!
    const sig = await Transfer.sign(identity.privKey, Transfer.requestMessage(this.o.me.userId, this.o.me.deviceId, eph.publicKey, { from, to }))
    const t = await retry429(() => this.o.api.requestTransfer(toBase64(eph.publicKey), toBase64(sig),
      { from: new Date(from).toISOString(), to: new Date(to).toISOString() }))
    await this.o.store.put('transfer', t.id, { privateKey: eph.privateKey, from, to })   // private key never leaves this device
    const asked = (await this.o.store.get<{ from: number; to: number }[]>('meta', 'syncWindows')) ?? []
    await this.o.store.put('meta', 'syncWindows', [...asked, { from, to }].slice(-50))
    await this.refreshTransfers()
    return t
  }

  /** A message is in a requested window by its send time or its server time (clocks differ slightly). */
  private static inWindow(m: LocalMessage, w?: { from: number; to: number }): boolean {
    if (!w) return true
    const at = [Date.parse(m.sentAt), m.serverAt ? Date.parse(m.serverAt) : NaN]
    return at.some((t) => t >= w.from && t <= w.to)
  }

  private static windowOf(t: HistoryTransfer): { from: number; to: number } | undefined {
    return t.windowFrom && t.windowTo ? { from: Date.parse(t.windowFrom), to: Date.parse(t.windowTo) } : undefined
  }

  /** Has this device asked for (a window covering) [from, to] already? */
  private async alreadyAsked(from: number, to: number): Promise<boolean> {
    const asked = (await this.o.store.get<{ from: number; to: number }[]>('meta', 'syncWindows')) ?? []
    return asked.some((w) => w.from <= from + 60_000 && w.to >= to - 60_000)
  }

  /** Do I have another active device that could answer a history request? */
  private async hasOtherDevice(): Promise<boolean> {
    const devices = await retry429(() => this.o.api.devices()).catch(() => [])
    return devices.some((d) => d.status === 'ACTIVE' && d.id !== this.o.me.deviceId)
  }

  /** Request one month at a time, newest first, for [from, to]. */
  private async requestWindows(from: number, to: number): Promise<void> {
    if (!(await this.hasOtherDevice())) return
    for (let end = to; end > from; end -= MONTH) {
      const start = Math.max(from, end - MONTH)
      if (await this.alreadyAsked(start, end)) continue
      await this.requestHistory({ from: start, to: end }).catch((e) => this.log('history request failed', e))
    }
  }

  /** First start of a new device: fetch the last month from my other devices automatically. */
  private async autoSyncNewDevice(): Promise<void> {
    if (await this.o.store.get('meta', 'historySync')) return
    await this.o.store.put('meta', 'historySync', { at: new Date().toISOString() })
    if (Date.now() - (await this.deviceCreatedAt()) > DAY) return   // an existing device: it already has its history
    await this.requestWindows(Date.now() - MONTH, Date.now())
  }

  /**
   * Another of my devices asked: verify its signed request and send my history for the window. Only a
   * device that actually holds messages in the window answers; the first to claim (server CAS) sends.
   */
  private async autoSendHistory(t: HistoryTransfer): Promise<void> {
    const w = ChatEngine.windowOf(t)
    const holds = [...this.msgs.values()].flat().some((m) => m.direction !== 'system' && m.body !== null && ChatEngine.inWindow(m, w))
    if (!holds) return
    try {
      await this.approveHistory(t.id)
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) return   // another of my devices answered first
      throw e
    }
    const r = await this.sendHistory(t.id)
    const notice: HistorySentNotice = { id: t.id, targetDeviceId: t.targetDeviceId, targetDeviceName: t.targetDeviceName,
      messages: r.messages, at: new Date().toISOString() }
    await this.o.store.put('historySent', t.id, notice)
    this.sentNotices.set(t.id, notice)
    this.emit('transfers')
  }

  /** "Chat history was sent to new device X — not you? revoke" notices on this device. */
  historySentNotices(): HistorySentNotice[] {
    return [...this.sentNotices.values()].sort((a, b) => b.at.localeCompare(a.at))
  }

  async dismissHistoryNotice(id: string): Promise<void> {
    const n = this.sentNotices.get(id)
    if (!n) return
    n.dismissed = true
    await this.o.store.put('historySent', id, n)
    this.emit('transfers')
  }

  /** My transfers waiting for another device (this device is the requester). */
  pendingHistorySync(): HistoryTransfer[] {
    return this.transferList.filter((t) => t.targetDeviceId === this.o.me.deviceId && ['REQUESTED', 'APPROVED', 'READY'].includes(t.status))
  }

  private async identityOf(deviceId: string): Promise<Uint8Array> {
    // cached directory first; refresh only for a device not seen yet (new device, or revocation check below)
    let d = (await retry429(() => this.directory.identities(this.o.me.userId))).find((x) => x.deviceId === deviceId)
    if (!d) d = (await retry429(() => this.directory.identities(this.o.me.userId, true))).find((x) => x.deviceId === deviceId)
    if (!d) throw new Error('device is not an active device of this account')
    return fromBase64(d.identityKey)
  }

  /** The 20-digit code both devices must show identically (null until a source device approved). */
  async transferCode(t: HistoryTransfer): Promise<string | null> {
    if (!t.sourceDeviceId) return null
    const target = t.targetDeviceId === this.o.me.deviceId ? new Uint8Array((await this.signalStore.getIdentityKeyPair())!.pubKey)
      : await this.identityOf(t.targetDeviceId)
    const source = t.sourceDeviceId === this.o.me.deviceId ? new Uint8Array((await this.signalStore.getIdentityKeyPair())!.pubKey)
      : await this.identityOf(t.sourceDeviceId)
    return Transfer.comparisonCode(t.id, target, fromBase64(t.ephemeralPublicKey), source)
  }

  /** Old device: claim the request (verifies the new device's signature) and return the code to compare. */
  async approveHistory(id: string): Promise<string> {
    const req = this.transferList.find((x) => x.id === id) ?? (await this.o.api.transfers()).find((x) => x.id === id)
    if (!req) throw new Error('transfer not found')
    const targetKey = await this.identityOf(req.targetDeviceId)
    const ok = await Transfer.verify(targetKey, Transfer.requestMessage(this.o.me.userId, req.targetDeviceId, fromBase64(req.ephemeralPublicKey),
      ChatEngine.windowOf(req)), fromBase64(req.requestSignature))
    if (!ok) {
      await this.o.api.declineTransfer(id).catch(() => undefined)
      throw new Error('The request signature is invalid — declined')
    }
    const t = await this.o.api.approveTransfer(id)
    await this.refreshTransfers()
    return (await this.transferCode(t))!
  }

  async declineHistory(id: string): Promise<void> {
    await this.o.api.declineTransfer(id)
    await this.refreshTransfers()
  }

  /**
   * Old device, after the user confirmed both codes match: encrypt the local history for the new
   * device (HPKE to its signed ephemeral key), upload ciphertext chunks, sign the manifest.
   */
  async sendHistory(id: string): Promise<{ messages: number; bytes: number }> {
    const t = (await this.o.api.transfers()).find((x) => x.id === id)
    if (!t || t.status !== 'APPROVED' || t.sourceDeviceId !== this.o.me.deviceId) throw new Error('transfer is not approved by this device')
    const targetKey = await this.identityOf(t.targetDeviceId)
    const w = ChatEngine.windowOf(t)
    if (!(await Transfer.verify(targetKey, Transfer.requestMessage(this.o.me.userId, t.targetDeviceId, fromBase64(t.ephemeralPublicKey), w),
      fromBase64(t.requestSignature)))) throw new Error('request signature invalid')
    const messages = [...this.msgs.values()].flat().filter((m) => m.direction !== 'system' && m.body !== null && ChatEngine.inWindow(m, w))
      .map((m) => ({ ...m, readSent: true }))
    const archive = { v: 1, sourceDeviceId: this.o.me.deviceId, createdAt: new Date().toISOString(), messages }
    const { ciphertext, enc } = await Transfer.sealArchive(archive, fromBase64(t.ephemeralPublicKey), t.id)
    const CHUNK = 150 * 1024
    let chunks = 0
    for (let o = 0; o < ciphertext.length; o += CHUNK) {
      const seq = chunks++
      await retry429(() => this.o.api.putTransferChunk(t.id, seq, toBase64(ciphertext.subarray(o, o + CHUNK))))
    }
    const manifest = JSON.stringify({ v: 1, transferId: t.id, sourceDeviceId: this.o.me.deviceId, targetDeviceId: t.targetDeviceId,
      enc: toBase64(enc), ciphertextSha256: await Transfer.sha256Hex(ciphertext), totalBytes: ciphertext.length, chunkCount: chunks,
      messageCount: messages.length, createdAt: archive.createdAt })
    const identity = (await this.signalStore.getIdentityKeyPair())!
    const sig = await Transfer.sign(identity.privKey, Transfer.manifestMessage(t.id, manifest))
    await retry429(() => this.o.api.completeTransfer(t.id, manifest, toBase64(sig), chunks))
    await this.refreshTransfers()
    return { messages: messages.length, bytes: ciphertext.length }
  }

  /** New device: verify the source's signature and hash, decrypt, import, delete server copy. */
  private async receiveHistory(t: HistoryTransfer): Promise<number> {
    const local = await this.o.store.get<{ privateKey: Uint8Array }>('transfer', t.id)
    if (!local || !t.manifest || !t.manifestSignature || !t.sourceDeviceId) throw new Error('transfer incomplete')
    const sourceKey = await this.identityOf(t.sourceDeviceId)
    if (!(await Transfer.verify(sourceKey, Transfer.manifestMessage(t.id, t.manifest), fromBase64(t.manifestSignature)))) {
      throw new Error('manifest signature invalid — history rejected')
    }
    const m = JSON.parse(t.manifest) as { transferId: string; targetDeviceId: string; sourceDeviceId: string; enc: string;
      ciphertextSha256: string; chunkCount: number }
    if (m.transferId !== t.id || m.targetDeviceId !== this.o.me.deviceId || m.sourceDeviceId !== t.sourceDeviceId) throw new Error('manifest mismatch')
    const parts: Uint8Array[] = []
    for (let i = 0; i < m.chunkCount; i++) parts.push(fromBase64((await retry429(() => this.o.api.getTransferChunk(t.id, i))).data))
    const ciphertext = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
    let o = 0
    for (const p of parts) {
      ciphertext.set(p, o)
      o += p.length
    }
    if ((await Transfer.sha256Hex(ciphertext)) !== m.ciphertextSha256) throw new Error('archive hash mismatch')
    const archive = await Transfer.openArchive<{ v: number; messages: LocalMessage[] }>(ciphertext, fromBase64(m.enc), local.privateKey, t.id)
    const imported = await this.importMessages(archive.messages ?? [])
    for (const [c, info] of this.older) this.older.set(c, { ...info, syncing: 0 })   // the older months have arrived
    await retry429(() => this.o.api.finishTransfer(t.id))
    await this.o.store.delete('transfer', t.id)
    for (const c of new Set(archive.messages.map((x) => x.conversationId))) {
      if (this.convs.has(c)) this.addSystem(c, `History imported from your other device (${imported} messages)`)
    }
    await this.refreshTransfers()
    this.emit('conversations')
    return imported
  }

  // ------------------------------------------------------------------ verification

  async safetyNumbers(conversationId: string): Promise<SafetyNumberView[]> {
    const conv = this.convs.get(conversationId)
    if (!conv) return []
    const out: SafetyNumberView[] = []
    for (const m of conv.members) {
      if (m.userId === this.o.me.userId) continue
      for (const d of await this.directory.identities(m.userId, true)) {
        const verified = (await this.o.store.get<string>('verified', d.deviceId)) === d.identityKey
        out.push({ userId: m.userId, displayName: this.displayName(m.userId), deviceId: d.deviceId,
          safetyNumber: await this.signal.safetyNumber(m.userId, fromBase64(d.identityKey)), verified })
      }
    }
    return out
  }

  async markVerified(deviceId: string, userId: string): Promise<void> {
    const d = (await this.directory.identities(userId, true)).find((x) => x.deviceId === deviceId)
    if (d) await this.o.store.put('verified', deviceId, d.identityKey)
  }

  /** User accepted a changed safety number: trust the device's new identity key. */
  async approveNewIdentities(conversationId: string): Promise<void> {
    const conv = this.convs.get(conversationId)
    if (!conv) return
    for (const m of conv.members) {
      for (const d of await this.directory.identities(m.userId, true)) {
        if (d.deviceId === this.o.me.deviceId) continue
        const key = toArrayBuffer(fromBase64(d.identityKey))
        if (!(await this.signalStore.isTrustedIdentity(d.deviceId, key, 1 as never))) {
          await this.signalStore.approveIdentity(d.deviceId, key)
          await this.o.store.delete('verified', d.deviceId)
        }
      }
    }
    this.identityWarnings.delete(conversationId)
    this.emit('conversations', conversationId)
  }

  // ------------------------------------------------------------------ local storage

  private async loadLocalMessages(): Promise<void> {
    for (const key of await this.o.store.keys('msg')) {
      const m = await this.o.store.get<LocalMessage>('msg', key)
      if (!m) continue
      if (m.status === 'pending') m.status = 'queued'   // interrupted send: retry on connect
      if (!this.msgs.has(m.conversationId)) this.msgs.set(m.conversationId, [])
      this.msgs.get(m.conversationId)!.push(m)
    }
    for (const list of this.msgs.values()) list.sort(byTime)
  }

  private async saveMessage(m: LocalMessage): Promise<void> {
    const list = this.msgs.get(m.conversationId) ?? []
    const i = list.findIndex((x) => x.key === m.key || (!!m.id && x.id === m.id))
    if (i >= 0) list[i] = m
    else list.push(m)
    list.sort(byTime)
    this.msgs.set(m.conversationId, list)
    await this.o.store.put('msg', `${m.conversationId}:${m.key}`, m)
    this.emit('messages', m.conversationId)
  }

  private addSystem(conversationId: string, text: string): void {
    const id = uuid()
    void this.saveMessage({
      key: `system:${id}`, clientMessageId: id, conversationId, senderUserId: this.o.me.userId, senderDeviceId: 'system',
      direction: 'system', body: text, sentAt: new Date().toISOString(), status: 'sent', deliveredTo: [], readBy: [], readSent: true,
    })
  }

  private emit(topic: string, conversationId?: string): void {
    for (const l of this.listeners) l(topic, conversationId)
  }

  private log(msg: string, err?: unknown): void {
    ;(this.o.log ?? ((m, e) => console.warn('[securechat]', m, e ?? '')))(msg, err)
  }
}

function byTime(a: LocalMessage, b: LocalMessage): number {
  return (a.serverAt ?? a.sentAt).localeCompare(b.serverAt ?? b.sentAt)
}

/** Retry a call that the server rate-limited (HTTP 429) or that hit a transient 5xx, with backoff. */
async function retry429<T>(f: () => Promise<T>, attempts = 6): Promise<T> {
  for (let i = 0; ; i++) {
    try {
      return await f()
    } catch (e) {
      const st = (e as { status?: number }).status ?? 0
      if (i >= attempts - 1 || !(st === 429 || st === 502 || st === 503)) throw e
      await new Promise((r) => setTimeout(r, Math.min(8000, 500 * 2 ** i) + Math.random() * 250))
    }
  }
}
