import { fromUtf8, utf8 } from './bytes'

/** Versioned plaintext format, only ever visible on clients. */
export interface TextPayload {
  v: 1
  kind: 'text'
  body: string
  sentAt: string
  clientMessageId: string
  /** Disappearing-message timer (seconds) in force when it was sent; the message expires sent + expiresIn. */
  expiresIn?: number
}

/** Edit of the sender's own earlier text message (`target` = its server message id). */
export interface EditPayload { v: 1; kind: 'edit'; target: string; body: string; sentAt: string; clientMessageId: string }
/** "Delete for everyone" of the sender's own earlier message. */
export interface DeletePayload { v: 1; kind: 'delete'; target: string; sentAt: string; clientMessageId: string }
/** Disappearing-messages timer changed for this chat (0 = off). */
export interface TimerPayload { v: 1; kind: 'timer'; seconds: number; sentAt: string; clientMessageId: string }

/**
 * Group-only control message sent right after adding members: tells the new members where the
 * encrypted history archive is and carries its key. Travels inside the new MLS epoch, so only the
 * group (including the new members) can read it.
 */
export interface HistorySharePayload {
  v: 1
  kind: 'history-share'
  shareId: string
  /** The admin grant this share fulfils (absent in shares made by older clients). */
  grantId?: string
  /** Scope of the grant, so a device that joins after the grant message can still apply it. */
  scope?: 'last100' | '10m' | '1h' | '10h' | '1d' | '7d' | '30d' | 'all'
  grantedAt?: string
  key: string            // base64 AES-256-GCM key
  sha256: string         // hex SHA-256 of the full ciphertext
  chunkCount: number
  messageCount: number
  forUserIds: string[]
  sentAt: string
  clientMessageId: string
}

/**
 * Group-only control message from an OWNER/ADMIN right after adding members: authorises sharing earlier
 * history with them (scope chosen at invite). Any member device holding that history may fulfil it.
 */
export interface HistoryGrantPayload {
  v: 1
  kind: 'history-grant'
  grantId: string
  scope: 'last100' | '10m' | '1h' | '10h' | '1d' | '7d' | '30d' | 'all'
  forUserIds: string[]
  grantedAt: string
  sentAt: string
  clientMessageId: string
}

export type Payload = TextPayload | HistorySharePayload | HistoryGrantPayload | EditPayload | DeletePayload | TimerPayload

const TIMERS: number[] = [300, 900, 3600, 10800, 36000, 86400, 259200, 604800, 2592000]
const GRANT_SCOPES: string[] = ['last100', '10m', '1h', '10h', '1d', '7d', '30d', 'all']

const BLOCK = 64

/**
 * Pads to a multiple of 64 bytes (ISO/IEC 7816-4: 0x80 then zeros) so ciphertext length only
 * reveals a size bucket. Padding is applied before, and removed after, the protocol's AEAD.
 */
export function encodePayload(p: Payload): Uint8Array {
  const raw = utf8(JSON.stringify(p))
  const total = Math.ceil((raw.length + 1) / BLOCK) * BLOCK
  const out = new Uint8Array(total)
  out.set(raw)
  out[raw.length] = 0x80
  return out
}

export function decodePayload(bytes: Uint8Array): Payload {
  let end = bytes.length - 1
  while (end >= 0 && bytes[end] === 0) end--
  if (end < 0 || bytes[end] !== 0x80) throw new Error('invalid padding')
  const p = JSON.parse(fromUtf8(bytes.subarray(0, end))) as Payload
  if (p.v !== 1) throw new Error('unsupported payload')
  if (p.kind === 'text' && typeof p.body === 'string' && (p.expiresIn === undefined || TIMERS.includes(p.expiresIn))) return p
  if (p.kind === 'edit' && typeof p.target === 'string' && typeof p.body === 'string' && p.body.length > 0) return p
  if (p.kind === 'delete' && typeof p.target === 'string') return p
  if (p.kind === 'timer' && (p.seconds === 0 || TIMERS.includes(p.seconds))) return p
  if (p.kind === 'history-share' && typeof p.shareId === 'string' && typeof p.key === 'string' && typeof p.sha256 === 'string'
    && Number.isInteger(p.chunkCount) && p.chunkCount > 0 && Number.isInteger(p.messageCount) && Array.isArray(p.forUserIds)
    && p.forUserIds.every((u) => typeof u === 'string') && (p.grantId === undefined || typeof p.grantId === 'string')
    && (p.scope === undefined || GRANT_SCOPES.includes(p.scope))
    && (p.grantedAt === undefined || (typeof p.grantedAt === 'string' && !Number.isNaN(Date.parse(p.grantedAt))))) return p
  if (p.kind === 'history-grant' && typeof p.grantId === 'string' && GRANT_SCOPES.includes(p.scope) && typeof p.grantedAt === 'string'
    && !Number.isNaN(Date.parse(p.grantedAt)) && Array.isArray(p.forUserIds) && p.forUserIds.every((u) => typeof u === 'string')) return p
  throw new Error('unsupported payload')
}
