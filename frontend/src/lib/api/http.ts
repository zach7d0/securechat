import type {
  Account, Accepted, CommitView, HistoryTransfer, Conversation, Device, DeviceIdentity, HistoryMessage, KeyStatus, PresenceView, Profile,
  ReceiptView, SendBody, SessionTokens, UserBundle, WelcomeView,
} from './types'

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly extras: Record<string, unknown>

  constructor(p: { status: number; code?: string; detail?: string; [k: string]: unknown }) {
    super(p.detail ?? p.code ?? `HTTP ${p.status}`)
    this.status = p.status
    this.code = p.code ?? 'ERROR'
    this.extras = p
  }
}

export interface TokenStorage {
  load(): SessionTokens | null
  save(t: SessionTokens | null): void
}

export class MemoryTokenStorage implements TokenStorage {
  private t: SessionTokens | null = null
  load() {
    return this.t
  }
  save(t: SessionTokens | null) {
    this.t = t
  }
}

/**
 * REST client. Access tokens are short-lived (5 min); a 401 triggers one single-flight refresh
 * (rotating refresh token). Only ciphertext and public keys are ever sent.
 */
export class ApiClient {
  onSessionExpired?: () => void
  private refreshing: Promise<void> | null = null

  // Wrapped: calling a stored reference to window.fetch as a method throws "Illegal invocation" in browsers.
  constructor(private readonly base: string, readonly tokens: TokenStorage,
    private readonly fetchImpl: typeof fetch = (input, init) => fetch(input, init)) {}

  // ------------------------------------------------------------------ plumbing

  private async raw(method: string, path: string, body?: unknown, auth = true): Promise<Response> {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    const t = this.tokens.load()
    if (auth && t) headers.Authorization = `Bearer ${t.accessToken}`
    return this.fetchImpl(this.base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  }

  async request<T>(method: string, path: string, body?: unknown, auth = true): Promise<T> {
    let res = await this.raw(method, path, body, auth)
    if (res.status === 401 && auth && this.tokens.load()) {
      await this.refresh()
      res = await this.raw(method, path, body, auth)
    }
    if (!res.ok) {
      let p: Record<string, unknown> = {}
      try {
        p = await res.json()
      } catch {
        /* no body */
      }
      throw new ApiError({ ...p, status: res.status })
    }
    if (res.status === 204) return undefined as T
    const text = await res.text()
    return (text ? JSON.parse(text) : undefined) as T
  }

  /** Rotates the refresh token (single flight). Failure ends the session. */
  async refresh(): Promise<void> {
    if (!this.refreshing) {
      this.refreshing = (async () => {
        const t = this.tokens.load()
        if (!t) throw new ApiError({ status: 401, code: 'UNAUTHENTICATED' })
        const res = await this.raw('POST', '/api/v1/auth/refresh', { refreshToken: t.refreshToken }, false)
        if (!res.ok) {
          this.tokens.save(null)
          this.onSessionExpired?.()
          throw new ApiError({ status: res.status, code: 'SESSION_EXPIRED' })
        }
        this.tokens.save(await res.json())
      })().finally(() => {
        this.refreshing = null
      })
    }
    return this.refreshing
  }

  /** A valid access token, refreshed when it expires within 60 s. */
  async accessToken(): Promise<string> {
    const t = this.tokens.load()
    if (!t) throw new ApiError({ status: 401, code: 'UNAUTHENTICATED' })
    if (Date.parse(t.accessTokenExpiresAt) - Date.now() < 60_000) await this.refresh()
    return this.tokens.load()!.accessToken
  }

  // ------------------------------------------------------------------ auth

  async register(email: string, username: string, password: string, displayName?: string): Promise<SessionTokens> {
    const s = await this.request<SessionTokens>('POST', '/api/v1/auth/register', { email, username, password, displayName }, false)
    this.tokens.save(s)
    return s
  }

  async login(login: string, password: string): Promise<SessionTokens> {
    const s = await this.request<SessionTokens>('POST', '/api/v1/auth/login', { login, password }, false)
    this.tokens.save(s)
    return s
  }

  /** Binds the session to a registered device of this user (device-bound access token). */
  async bindDevice(deviceId: string): Promise<SessionTokens> {
    const s = await this.request<SessionTokens>('POST', '/api/v1/auth/device-session', { deviceId })
    this.tokens.save(s)
    return s
  }

  async logout(): Promise<void> {
    const t = this.tokens.load()
    this.tokens.save(null)
    if (t) await this.request('POST', '/api/v1/auth/logout', { refreshToken: t.refreshToken }, false).catch(() => undefined)
  }

  // ------------------------------------------------------------------ users & devices

  me = () => this.request<Profile>('GET', '/api/v1/users/me')
  updateMe = (p: { displayName?: string; bio?: string }) => this.request<Profile>('PATCH', '/api/v1/users/me', p)
  account = () => this.request<Account>('GET', '/api/v1/auth/account')
  changeEmail = (email: string, currentPassword: string) =>
    this.request<Account>('PUT', '/api/v1/auth/account/email', { email, currentPassword })
  changePassword = (currentPassword: string, newPassword: string) =>
    this.request<{ otherSessionsRevoked: number }>('PUT', '/api/v1/auth/account/password', { currentPassword, newPassword })
  searchUsers = (q: string) => this.request<Profile[]>('GET', `/api/v1/users/search?q=${encodeURIComponent(q)}`)
  profiles = (ids: string[]) => this.request<Profile[]>('GET', `/api/v1/users?ids=${ids.join(',')}`)
  registerDevice = (deviceId: string, deviceName: string) =>
    this.request<Device>('POST', '/api/v1/devices', { deviceId, deviceName, platform: 'WEB', deviceType: 'BROWSER' })
  devices = () => this.request<Device[]>('GET', '/api/v1/devices')
  revokeDevice = (id: string) => this.request<Device>('DELETE', `/api/v1/devices/${id}`)
  /** Revokes the device if still active and hides it from the owner's list (the id can never be reused). */
  removeDevice = (id: string) => this.request<void>('POST', `/api/v1/devices/${id}/remove`)

  // ------------------------------------------------------------------ device-to-device history transfer (ciphertext relay)

  requestTransfer = (ephemeralPublicKey: string, signature: string, window?: { from: string; to: string }) =>
    this.request<HistoryTransfer>('POST', '/api/v1/devices/transfers', { ephemeralPublicKey, signature,
      ...(window ? { windowFrom: window.from, windowTo: window.to } : {}) })
  transfers = () => this.request<HistoryTransfer[]>('GET', '/api/v1/devices/transfers')
  approveTransfer = (id: string) => this.request<HistoryTransfer>('POST', `/api/v1/devices/transfers/${id}/approve`)
  declineTransfer = (id: string) => this.request<void>('POST', `/api/v1/devices/transfers/${id}/decline`)
  putTransferChunk = (id: string, seq: number, data: string) => this.request<void>('PUT', `/api/v1/devices/transfers/${id}/chunks/${seq}`, { data })
  completeTransfer = (id: string, manifest: string, signature: string, chunkCount: number) =>
    this.request<HistoryTransfer>('POST', `/api/v1/devices/transfers/${id}/complete`, { manifest, signature, chunkCount })
  // ------------------------------------------------------------------ group history shared with new members (ciphertext relay)

  /** Delete for everyone: removes my message from server history (≤ 24 h; `at` = its server timestamp). */
  deleteMessage = (conversationId: string, messageId: string, at: string) =>
    this.request<void>('DELETE', `/api/v1/conversations/${conversationId}/messages/${messageId}?at=${encodeURIComponent(at)}`)
  setDisappearing = (conversationId: string, seconds: number) =>
    this.request<Conversation>('PUT', `/api/v1/conversations/${conversationId}/disappearing`, { seconds })
  createHistoryGrant = (conversationId: string, scope: string) =>
    this.request<{ id: string }>('POST', `/api/v1/groups/${conversationId}/history-grants`, { scope })
  historyGrant = (conversationId: string, grantId: string) =>
    this.request<{ id: string; scope: string; grantedBy: string; claimed: boolean; expiresAt: string }>('GET',
      `/api/v1/groups/${conversationId}/history-grants/${grantId}`)
  claimHistoryGrant = (conversationId: string, grantId: string) =>
    this.request<{ shareId: string }>('POST', `/api/v1/groups/${conversationId}/history-grants/${grantId}/claim`)
  createHistoryShare = (conversationId: string) => this.request<{ id: string }>('POST', `/api/v1/groups/${conversationId}/history-shares`)
  putHistoryShareChunk = (conversationId: string, shareId: string, seq: number, data: string) =>
    this.request<void>('PUT', `/api/v1/groups/${conversationId}/history-shares/${shareId}/chunks/${seq}`, { data })
  completeHistoryShare = (conversationId: string, shareId: string, chunkCount: number) =>
    this.request<void>('POST', `/api/v1/groups/${conversationId}/history-shares/${shareId}/complete`, { chunkCount })
  getHistoryShareChunk = (conversationId: string, shareId: string, seq: number) =>
    this.request<{ data: string }>('GET', `/api/v1/groups/${conversationId}/history-shares/${shareId}/chunks/${seq}`)

  getTransferChunk = (id: string, seq: number) => this.request<{ data: string }>('GET', `/api/v1/devices/transfers/${id}/chunks/${seq}`)
  finishTransfer = (id: string) => this.request<void>('DELETE', `/api/v1/devices/transfers/${id}`)

  // ------------------------------------------------------------------ keys (public material only)

  uploadIdentity = (identityKey: string, registrationId: number, mlsSignatureKey: string, mlsSignatureBinding: string) =>
    this.request<void>('PUT', '/api/v1/keys/identity', { identityKey, registrationId, mlsSignatureKey, mlsSignatureBinding })
  uploadSignedPreKey = (keyId: number, publicKey: string, signature: string) =>
    this.request<void>('POST', '/api/v1/keys/signed-prekeys', { keyId, publicKey, signature })
  uploadPreKeys = (keys: { keyId: number; publicKey: string }[]) =>
    this.request<{ stored: number }>('POST', '/api/v1/keys/one-time-prekeys', { keys })
  uploadKeyPackages = (keyPackages: { id: string; keyPackage: string; lastResort: boolean }[]) =>
    this.request<{ stored: number }>('POST', '/api/v1/keys/mls-key-packages', { keyPackages })
  keyStatus = () => this.request<KeyStatus>('GET', '/api/v1/keys/me/status')
  identities = (userId: string) => this.request<DeviceIdentity[]>('GET', `/api/v1/keys/users/${userId}/identities`)
  claimBundle = (userId: string, includeMls: boolean, deviceIds?: string[]) =>
    this.request<UserBundle>('POST', `/api/v1/keys/users/${userId}/bundles:claim`, { includeMls, deviceIds })

  // ------------------------------------------------------------------ conversations & groups

  conversations = () => this.request<Conversation[]>('GET', '/api/v1/conversations')
  conversation = (id: string) => this.request<Conversation>('GET', `/api/v1/conversations/${id}`)
  createDirect = (peerUserId: string) => this.request<Conversation>('POST', '/api/v1/conversations/direct', { peerUserId })
  createGroup = (name: string, description?: string) => this.request<Conversation>('POST', '/api/v1/groups', { name, description })
  updateGroup = (id: string, p: { name?: string; description?: string }) => this.request<Conversation>('PATCH', `/api/v1/groups/${id}`, p)
  addMembers = (id: string, body: { userIds: string[]; commit: string; epoch: number; welcomes: { deviceId: string; keyPackageId?: string; welcome: string }[];
    role?: 'ADMIN' | 'MEMBER' }) =>
    this.request<Conversation>('POST', `/api/v1/groups/${id}/members`, body)
  removeMember = (id: string, userId: string, body: { commit: string; epoch: number }) =>
    this.request<Conversation>('DELETE', `/api/v1/groups/${id}/members/${userId}`, body)
  leaveGroup = (id: string) => this.request<void>('POST', `/api/v1/groups/${id}/leave`)
  setRole = (id: string, userId: string, role: 'ADMIN' | 'MEMBER') =>
    this.request<Conversation>('PUT', `/api/v1/groups/${id}/members/${userId}/role`, { role })
  commit = (id: string, body: { commit: string; epoch: number; welcomes?: { deviceId: string; keyPackageId?: string; welcome: string }[]; kind: string }) =>
    this.request<{ epoch: number }>('POST', `/api/v1/groups/${id}/commits`, body)
  commits = (id: string, fromEpoch: number) => this.request<CommitView[]>('GET', `/api/v1/groups/${id}/commits?fromEpoch=${fromEpoch}`)
  welcomes = () => this.request<WelcomeView[]>('GET', '/api/v1/groups/welcomes')
  consumeWelcome = (id: string) => this.request<void>('DELETE', `/api/v1/groups/welcomes/${id}`)

  // ------------------------------------------------------------------ messages, receipts, presence

  sendMessage = (conversationId: string, body: SendBody) =>
    this.request<Accepted>('POST', `/api/v1/conversations/${conversationId}/messages`, body)
  history = (conversationId: string, before?: string, limit = 50) =>
    this.request<{ messages: HistoryMessage[]; nextCursor: string | null }>('GET',
      `/api/v1/conversations/${conversationId}/messages?limit=${limit}${before ? `&before=${encodeURIComponent(before)}` : ''}`)
  receipts = (messageId: string) => this.request<ReceiptView[]>('GET', `/api/v1/messages/${messageId}/receipts`)
  presence = (userIds: string[]) => this.request<PresenceView[]>('GET', `/api/v1/presence?userIds=${userIds.join(',')}`)
}
