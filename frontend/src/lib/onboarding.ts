/**
 * Onboarding (welcome tour) state.
 *
 * - Operator switch: `ONBOARDING_ENABLED` in .env → served by the frontend container as /config.json
 *   (`{"onboarding":"true"|"false"}`). Missing or unreadable config = enabled.
 * - Per user, per device (localStorage, never sent to the server): whether the tour was finished/skipped,
 *   and whether it may start automatically.
 */
import { useSyncExternalStore } from 'react'

export interface TourPrefs {
  /** Finished or skipped on this device. */
  done: boolean
  /** May start by itself (new account / first login here with no chats). */
  auto: boolean
  /** Set at sign-up so the tour opens right after registration. */
  newAccount?: boolean
}

const DEFAULTS: TourPrefs = { done: false, auto: true }
const key = (userId: string) => `securechat.onboarding.${userId}`

let enabled = true
let running = false
const listeners = new Set<() => void>()
let version = 0
const notify = () => { version++; listeners.forEach((l) => l()) }
const subscribe = (l: () => void) => { listeners.add(l); return () => { listeners.delete(l) } }

/** Reads the operator switch once at start-up. */
export async function loadOnboardingConfig(fetcher: typeof fetch = globalThis.fetch): Promise<boolean> {
  try {
    const r = await fetcher('/config.json', { cache: 'no-store' })
    if (r.ok) enabled = parseEnabled(await r.json())
  } catch { /* dev server / no config: keep the default (enabled) */ }
  notify()
  return enabled
}
export function parseEnabled(cfg: unknown): boolean {
  const v = (cfg as { onboarding?: unknown } | null)?.onboarding
  return !(v === false || (typeof v === 'string' && ['false', '0', 'off', 'no'].includes(v.trim().toLowerCase())))
}
export const onboardingEnabled = () => enabled

export function tourPrefs(userId: string): TourPrefs {
  try {
    return { ...DEFAULTS, ...(JSON.parse(globalThis.localStorage?.getItem(key(userId)) ?? 'null') as Partial<TourPrefs> | null) }
  } catch {
    return { ...DEFAULTS }
  }
}
export function setTourPrefs(userId: string, patch: Partial<TourPrefs>): void {
  globalThis.localStorage?.setItem(key(userId), JSON.stringify({ ...tourPrefs(userId), ...patch }))
  notify()
}

/** Start automatically? Only for someone new: just signed up, or first time here with no chats yet. */
export function shouldAutoStart(p: TourPrefs, conversationCount: number, featureOn = enabled): boolean {
  return featureOn && p.auto && !p.done && (!!p.newAccount || conversationCount === 0)
}

export function startTour(): void { if (enabled) { running = true; notify() } }
export function endTour(userId: string): void {
  running = false
  setTourPrefs(userId, { done: true, newAccount: false })
}
export const tourRunning = () => running

/** Re-renders on any onboarding change. */
export function useOnboarding() {
  return useSyncExternalStore(subscribe, () => version)
}
