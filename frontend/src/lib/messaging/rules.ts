/**
 * Time rules for editing, deleting and disappearing messages (WhatsApp style). All windows are measured with
 * SERVER timestamps (when the message was accepted), never a sender's clock.
 */
export const EDIT_WINDOW_MS = 60 * 60 * 1000            // edit your message within 1 hour
export const DELETE_WINDOW_MS = 24 * 60 * 60 * 1000     // delete for everyone within 24 hours
const SKEW_MS = 2 * 60 * 1000                            // tolerance for receivers (network / queueing delay)

/** Disappearing-message timers (seconds) in menu order; must match the server's allowed set. */
export const DISAPPEARING_OPTIONS: { seconds: number; label: string }[] = [
  { seconds: 300, label: '5 minutes' }, { seconds: 900, label: '15 minutes' }, { seconds: 3600, label: '1 hour' },
  { seconds: 10800, label: '3 hours' }, { seconds: 36000, label: '10 hours' }, { seconds: 86400, label: '1 day' },
  { seconds: 259200, label: '3 days' }, { seconds: 604800, label: '7 days' }, { seconds: 2592000, label: '30 days' },
]
export const timerLabel = (seconds?: number | null) =>
  DISAPPEARING_OPTIONS.find((o) => o.seconds === seconds)?.label ?? 'Off'
export const isAllowedTimer = (seconds: unknown): seconds is number =>
  typeof seconds === 'number' && DISAPPEARING_OPTIONS.some((o) => o.seconds === seconds)

interface Msg { direction: string; senderUserId: string; body: string | null; serverAt?: string; sentAt: string; id?: string; deleted?: boolean }
const sentAtMs = (m: Msg) => Date.parse(m.serverAt ?? m.sentAt)

/** The sender may still edit this message (own, text, delivered to the server, ≤ 1 h old). */
export const canEdit = (m: Msg, now = Date.now()) =>
  m.direction === 'out' && !!m.id && !m.deleted && m.body !== null && now - sentAtMs(m) <= EDIT_WINDOW_MS

/** The sender may still delete this message for everyone (own, on the server, ≤ 24 h old). */
export const canDeleteForEveryone = (m: Msg, now = Date.now()) =>
  m.direction === 'out' && !!m.id && !m.deleted && now - sentAtMs(m) <= DELETE_WINDOW_MS

/** A received edit/delete notice is honoured only from the original sender and inside the window (+ skew). */
export function acceptEdit(target: Msg, fromUserId: string, noticeServerAt: string): boolean {
  return fromUserId === target.senderUserId && !target.deleted && target.body !== null
    && Date.parse(noticeServerAt) - sentAtMs(target) <= EDIT_WINDOW_MS + SKEW_MS
}
export function acceptDelete(target: Msg, fromUserId: string, noticeServerAt: string): boolean {
  return fromUserId === target.senderUserId && Date.parse(noticeServerAt) - sentAtMs(target) <= DELETE_WINDOW_MS + SKEW_MS
}
