import { fromBase64, fromUtf8, toBase64, utf8 } from './bytes'
import { gunzip, gzip, sha256Hex } from './transfer'

/**
 * Group history shared with newly added members. The archive is gzip(JSON) sealed with AES-256-GCM
 * (WebCrypto) under a fresh random key; the associated data binds it to the conversation and share
 * id, so ciphertext cannot be replayed into another group or share. The key itself is only ever
 * sent inside the group's MLS epoch (see HistorySharePayload).
 */
const IV_BYTES = 12

function aad(conversationId: string, shareId: string): Uint8Array {
  return utf8(`securechat-history-share-v1:${conversationId}:${shareId}`)
}

export async function sealShare(archive: unknown, conversationId: string, shareId: string):
  Promise<{ ciphertext: Uint8Array; key: string; sha256: string }> {
  const raw = crypto.getRandomValues(new Uint8Array(32))
  const key = await crypto.subtle.importKey('raw', raw as BufferSource, 'AES-GCM', false, ['encrypt'])
  const iv = crypto.getRandomValues(new Uint8Array(IV_BYTES))
  const plain = await gzip(utf8(JSON.stringify(archive)))
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: iv as BufferSource,
    additionalData: aad(conversationId, shareId) as BufferSource }, key, plain as BufferSource))
  const ciphertext = new Uint8Array(IV_BYTES + ct.length)
  ciphertext.set(iv)
  ciphertext.set(ct, IV_BYTES)
  return { ciphertext, key: toBase64(raw), sha256: await sha256Hex(ciphertext) }
}

export async function openShare<T>(ciphertext: Uint8Array, keyB64: string, expectedSha256: string, conversationId: string,
  shareId: string): Promise<T> {
  if ((await sha256Hex(ciphertext)) !== expectedSha256) throw new Error('history archive hash mismatch')
  const raw = fromBase64(keyB64)
  if (raw.length !== 32) throw new Error('invalid history key')
  const key = await crypto.subtle.importKey('raw', raw as BufferSource, 'AES-GCM', false, ['decrypt'])
  const plain = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: ciphertext.subarray(0, IV_BYTES) as BufferSource,
    additionalData: aad(conversationId, shareId) as BufferSource }, key, ciphertext.subarray(IV_BYTES) as BufferSource))
  return JSON.parse(fromUtf8(await gunzip(plain))) as T
}

/** What an admin lets new members see when inviting them. */
export type ShareScope = 'none' | 'last100' | '10m' | '1h' | '10h' | '1d' | '7d' | '30d' | 'all'
export type GrantScope = Exclude<ShareScope, 'none'>
export const RECENT_SHARE_LIMIT = 100

export const SHARE_SCOPES: { value: ShareScope; label: string }[] = [
  { value: 'none', label: "Don't share" },
  { value: 'last100', label: 'Last 100 messages' },
  { value: '10m', label: 'Last 10 minutes' },
  { value: '1h', label: 'Last 1 hour' },
  { value: '10h', label: 'Last 10 hours' },
  { value: '1d', label: 'Last 1 day' },
  { value: '7d', label: 'Last 7 days' },
  { value: '30d', label: 'Last 30 days' },
  { value: 'all', label: 'All history' },
]

const SCOPE_MS: Partial<Record<GrantScope, number>> = {
  '10m': 10 * 60_000, '1h': 3_600_000, '10h': 36_000_000, '1d': 86_400_000, '7d': 7 * 86_400_000, '30d': 30 * 86_400_000,
}

/** Start of the shared time window (null = no time bound: "last 100" or "all"). */
export function scopeWindowFrom(scope: GrantScope, grantedAt: number): number | null {
  const ms = SCOPE_MS[scope]
  return ms === undefined ? null : grantedAt - ms
}

/** Messages (sorted oldest → newest) that a grant allows to be shared. */
export function selectForScope<T extends { sentAt: string }>(messages: T[], scope: GrantScope, grantedAt: number): T[] {
  const from = scopeWindowFrom(scope, grantedAt)
  let out = messages.filter((m) => Date.parse(m.sentAt) <= grantedAt && (from === null || Date.parse(m.sentAt) >= from))
  if (scope === 'last100') out = out.slice(-RECENT_SHARE_LIMIT)
  return out
}

export const scopeLabel = (scope: ShareScope) => SHARE_SCOPES.find((s) => s.value === scope)?.label.toLowerCase() ?? scope
