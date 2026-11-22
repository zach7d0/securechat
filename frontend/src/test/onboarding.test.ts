import { beforeEach, describe, expect, it } from 'vitest'
import { endTour, loadOnboardingConfig, onboardingEnabled, parseEnabled, setTourPrefs, shouldAutoStart, startTour, tourPrefs, tourRunning } from '../lib/onboarding'

const mem = new Map<string, string>()
globalThis.localStorage = {
  getItem: (k: string) => mem.get(k) ?? null, setItem: (k: string, v: string) => void mem.set(k, v),
  removeItem: (k: string) => void mem.delete(k), clear: () => mem.clear(), key: () => null, length: 0,
} as Storage
const json = (body: unknown, ok = true) => (async () => ({ ok, json: async () => body })) as unknown as typeof fetch

describe('onboarding', () => {
  beforeEach(() => mem.clear())

  it('operator switch: only an explicit false/0/off/no disables it; errors keep it on', async () => {
    expect(parseEnabled({ onboarding: 'true' })).toBe(true)
    for (const v of ['false', 'FALSE', '0', 'off', 'no', false]) expect(parseEnabled({ onboarding: v })).toBe(false)
    expect(parseEnabled({ onboarding: '${SECURECHAT_ONBOARDING}' })).toBe(true)
    expect(parseEnabled(null)).toBe(true)
    expect(await loadOnboardingConfig(json({ onboarding: 'false' }))).toBe(false)
    expect(await loadOnboardingConfig((async () => { throw new Error('offline') }) as unknown as typeof fetch)).toBe(false)  // unchanged
    expect(await loadOnboardingConfig(json({ onboarding: 'true' }))).toBe(true)
    expect(await loadOnboardingConfig(json({}, false))).toBe(true)
  })

  it('auto-starts only for new people, once', () => {
    const p = tourPrefs('u1')
    expect(p).toEqual({ done: false, auto: true })
    expect(shouldAutoStart(p, 0, true)).toBe(true)                    // first time here, no chats
    expect(shouldAutoStart(p, 3, true)).toBe(false)                   // existing user on a new device
    expect(shouldAutoStart({ ...p, newAccount: true }, 2, true)).toBe(true)   // just signed up (already invited by alice)
    expect(shouldAutoStart(p, 0, false)).toBe(false)                  // turned off by the operator
    expect(shouldAutoStart({ ...p, auto: false }, 0, true)).toBe(false)       // turned off by the user
    expect(shouldAutoStart({ ...p, done: true }, 0, true)).toBe(false)
  })

  it('finishing or skipping is remembered per user; restart works', () => {
    setTourPrefs('u1', { newAccount: true })
    startTour()
    expect(tourRunning()).toBe(onboardingEnabled())
    endTour('u1')
    expect(tourRunning()).toBe(false)
    expect(tourPrefs('u1')).toMatchObject({ done: true, newAccount: false })
    expect(tourPrefs('u2').done).toBe(false)
    setTourPrefs('u1', { done: false })
    startTour()
    expect(tourRunning()).toBe(true)
  })
})
