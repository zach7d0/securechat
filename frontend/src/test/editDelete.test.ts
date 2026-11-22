import { describe, expect, it } from 'vitest'
import { uuid } from '../lib/crypto/bytes'
import { decodePayload, encodePayload, type Payload } from '../lib/crypto/payload'
import { acceptDelete, acceptEdit, canDeleteForEveryone, canEdit, DISAPPEARING_OPTIONS, isAllowedTimer, timerLabel } from '../lib/messaging/rules'

const T0 = Date.parse('2026-11-20T10:00:00.000Z')
const iso = (ms: number) => new Date(ms).toISOString()
const mine = { direction: 'out', senderUserId: 'me', body: 'hello', id: 'm1', serverAt: iso(T0), sentAt: iso(T0 - 500) }
const theirs = { ...mine, direction: 'in', senderUserId: 'bob' }

describe('edit window (1 hour, own messages)', () => {
  it('allows editing my own message up to 1 hour after the server accepted it', () => {
    expect(canEdit(mine, T0 + 59 * 60_000)).toBe(true)
    expect(canEdit(mine, T0 + 61 * 60_000)).toBe(false)
    expect(canEdit(theirs, T0)).toBe(false)
    expect(canEdit({ ...mine, id: undefined }, T0)).toBe(false)     // not on the server yet
    expect(canEdit({ ...mine, deleted: true }, T0)).toBe(false)
  })
  it('receivers accept edits only from the original sender, inside the window (server time)', () => {
    expect(acceptEdit(theirs, 'bob', iso(T0 + 30 * 60_000))).toBe(true)
    expect(acceptEdit(theirs, 'eve', iso(T0 + 1000))).toBe(false)
    expect(acceptEdit(theirs, 'bob', iso(T0 + 2 * 3_600_000))).toBe(false)
    expect(acceptEdit({ ...theirs, deleted: true }, 'bob', iso(T0 + 1000))).toBe(false)
  })
})

describe('delete for everyone (24 hours, own messages)', () => {
  it('only my own messages, up to 24 hours', () => {
    expect(canDeleteForEveryone(mine, T0 + 23 * 3_600_000)).toBe(true)
    expect(canDeleteForEveryone(mine, T0 + 25 * 3_600_000)).toBe(false)
    expect(canDeleteForEveryone(theirs, T0)).toBe(false)
  })
  it('receivers ignore deletes from anyone but the sender or after the window', () => {
    expect(acceptDelete(theirs, 'bob', iso(T0 + 3_600_000))).toBe(true)
    expect(acceptDelete(theirs, 'eve', iso(T0 + 1000))).toBe(false)
    expect(acceptDelete(theirs, 'bob', iso(T0 + 26 * 3_600_000))).toBe(false)
  })
})

describe('disappearing timers', () => {
  it('has exactly the 9 options, labelled', () => {
    expect(DISAPPEARING_OPTIONS.map((o) => o.seconds)).toEqual([300, 900, 3600, 10800, 36000, 86400, 259200, 604800, 2592000])
    expect(timerLabel(10800)).toBe('3 hours')
    expect(timerLabel(null)).toBe('Off')
    expect(isAllowedTimer(42)).toBe(false)
  })
})

describe('payloads', () => {
  const base = { v: 1 as const, sentAt: iso(T0), clientMessageId: uuid() }
  it('round-trips edit / delete / timer and text with a timer', () => {
    for (const p of [{ ...base, kind: 'edit', target: 'm1', body: 'fixed' }, { ...base, kind: 'delete', target: 'm1' },
      { ...base, kind: 'timer', seconds: 3600 }, { ...base, kind: 'timer', seconds: 0 }, { ...base, kind: 'text', body: 'hi', expiresIn: 300 }] as Payload[]) {
      expect(decodePayload(encodePayload(p))).toEqual(p)
    }
  })
  it('rejects malformed ones', () => {
    for (const bad of [{ ...base, kind: 'edit', target: 'm1', body: '' }, { ...base, kind: 'timer', seconds: 42 },
      { ...base, kind: 'text', body: 'hi', expiresIn: 1 }, { ...base, kind: 'delete' }]) {
      expect(() => decodePayload(encodePayload(bad as unknown as Payload))).toThrow()
    }
  })
})
