/** Wire types for the SecureChat REST/WebSocket APIs (see backend/platform/contracts/README.md). */

export interface SessionTokens {
  userId: string
  username: string
  deviceId: string | null
  accessToken: string
  accessTokenExpiresAt: string
  refreshToken: string
  refreshTokenExpiresAt: string
}

export interface Profile {
  userId: string
  username: string
  displayName: string
  avatarUrl?: string | null
  bio?: string | null
}

/** The caller's own account (auth-service). Email is private: never part of Profile. */
export interface Account {
  userId: string
  username: string
  email: string
}

export interface Device {
  id: string
  userId: string
  deviceName: string | null
  deviceType: string | null
  platform: string
  status: 'ACTIVE' | 'REVOKED'
  createdAt: string
  revokedAt?: string | null
  lastAuthenticatedAt?: string | null
  lastSeenAt?: string | null
  removedAt?: string | null
}

export interface DeviceIdentity {
  deviceId: string
  registrationId: number
  identityKey: string
  mlsSignatureKey: string
  mlsSignatureBinding: string
  keyVersion: number
  createdAt: string
}

export interface DeviceBundle {
  deviceId: string
  registrationId: number
  identityKey: string
  mlsSignatureKey: string
  mlsSignatureBinding: string
  signedPreKey: { keyId: number; publicKey: string; signature: string }
  preKey?: { keyId: number; publicKey: string } | null
  mlsKeyPackage?: { id: string; keyPackage: string; lastResort: boolean } | null
}

export interface UserBundle {
  userId: string
  devices: DeviceBundle[]
}

export interface KeyStatus {
  identity: boolean
  oneTimePreKeys: number
  mlsKeyPackages: number
  lastResortKeyPackage: boolean
  signedPreKeyId: number | null
  signedPreKeyCreatedAt: string | null
}

export interface Member {
  userId: string
  role: 'OWNER' | 'ADMIN' | 'MEMBER'
  joinedAt: string
  joinedEpoch: number
}

export interface Conversation {
  id: string
  type: 'DIRECT' | 'GROUP'
  displayName: string | null
  createdBy: string
  membershipVersion: number
  createdAt: string
  updatedAt: string
  members: Member[]
  group?: { description: string | null; avatarUrl: string | null; mlsGroupId: string; epoch: number; groupVersion: number } | null
  myRole: Member['role']
  myJoinedEpoch: number
  /** Disappearing-messages timer in seconds (null/absent = off). */
  disappearingSeconds?: number | null
}

export interface CommitView {
  epoch: number
  commit: string
  senderUserId: string
  senderDeviceId: string
  kind: string
  createdAt: string
}

export interface WelcomeView {
  id: string
  conversationId: string
  keyPackageId: string | null
  welcome: string
  epoch: number
  createdAt: string
}

export interface EnvelopeWire {
  deviceId: string
  signalType: number
  ciphertext: string
}

export interface SendBody {
  clientMessageId: string
  protocol: 'SIGNAL' | 'MLS'
  envelopes?: EnvelopeWire[]
  ciphertext?: string
  epoch?: number
  /** Server-side expiry of the stored ciphertext (disappearing messages). */
  expiresInSeconds?: number
}

export interface Accepted {
  messageId: string
  conversationId?: string
  clientMessageId: string
  createdAt: string
  status: 'ACCEPTED'
  duplicate?: boolean
}

/** A delivery pushed by the gateway (one per recipient device). */
export interface DeliveredMessage {
  deliveryId: string
  messageId: string
  conversationId: string
  senderUserId: string
  senderDeviceId: string
  protocol: 'SIGNAL' | 'MLS'
  messageType: string
  signalType?: number
  epoch?: number
  ciphertext: string
  createdAt: string
}

export interface HistoryMessage {
  messageId: string
  conversationId: string
  senderUserId: string
  senderDeviceId: string
  clientMessageId: string
  createdAt: string
  protocol: 'SIGNAL' | 'MLS'
  messageType: string
  epoch: number | null
  ciphertext: string | null
  envelope: { signalType: number; ciphertext: string } | null
}

export interface PresenceView {
  userId: string
  status: 'ONLINE' | 'OFFLINE' | 'UNKNOWN'
  lastSeen: string | null
  activeDevices: number
}

export interface ReceiptView {
  recipientUserId: string
  recipientDeviceId: string
  status: 'PENDING' | 'DELIVERED' | 'READ' | 'FAILED'
  deliveredAt: string | null
  readAt: string | null
}

export interface HistoryTransfer {
  id: string
  targetDeviceId: string
  targetDeviceName: string | null
  sourceDeviceId: string | null
  ephemeralPublicKey: string
  requestSignature: string
  status: 'REQUESTED' | 'APPROVED' | 'READY' | 'DECLINED' | 'COMPLETED' | 'EXPIRED'
  manifest: string | null
  manifestSignature: string | null
  totalBytes: number
  chunkCount: number | null
  createdAt: string
  expiresAt: string
  windowFrom?: string | null
  windowTo?: string | null
}
