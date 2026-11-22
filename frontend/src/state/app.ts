import { create } from 'zustand'
import { ApiClient, type TokenStorage } from '../lib/api/http'
import type { SessionTokens } from '../lib/api/types'
import { IndexedDbKeyStore } from '../lib/crypto/keystore'
import { setTourPrefs } from '../lib/onboarding'
import { establishSession, signIn, type Session, type SessionDeps } from '../lib/session'

/**
 * Refresh token in localStorage so the session survives reloads. Trade-off (docs/E2EE_CLIENT.md §3):
 * XSS could read it — mitigated by a strict CSP, rotation on every use and family revocation on reuse.
 */
class LocalTokenStorage implements TokenStorage {
  load(): SessionTokens | null {
    try {
      const raw = localStorage.getItem('securechat.tokens')
      return raw ? (JSON.parse(raw) as SessionTokens) : null
    } catch {
      return null
    }
  }
  save(t: SessionTokens | null): void {
    try {
      if (t) localStorage.setItem('securechat.tokens', JSON.stringify(t))
      else localStorage.removeItem('securechat.tokens')
    } catch {
      /* storage unavailable */
    }
  }
}

export const api = new ApiClient('', new LocalTokenStorage())

function deps(): SessionDeps {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const ua = navigator.userAgent
  const browser = /Firefox/.test(ua) ? 'Firefox' : /Edg\//.test(ua) ? 'Edge' : /Chrome/.test(ua) ? 'Chrome' : /Safari/.test(ua) ? 'Safari' : 'Browser'
  return {
    api,
    openStore: (userId) => IndexedDbKeyStore.open(`securechat-${userId}`),
    wsUrl: `${proto}//${location.host}/ws`,
    deviceName: `${browser} on ${navigator.platform || 'web'}`,
    onTerminated: (reason) => {
      useApp.getState().session?.engine.stop()
      api.tokens.save(null)
      useApp.setState({ session: null, error: reason })
    },
  }
}

interface AppState {
  session: Session | null
  booting: boolean
  error: string | null
  tick: number
  restore: () => Promise<void>
  login: (login: string, password: string) => Promise<void>
  register: (username: string, password: string, email: string, displayName?: string) => Promise<void>
  logout: () => Promise<void>
}

let unsubscribe: (() => void) | null = null

async function activate(set: (p: Partial<AppState>) => void, get: () => AppState, s: Session): Promise<void> {
  unsubscribe?.()
  unsubscribe = s.engine.on(() => set({ tick: get().tick + 1 }))
  set({ session: s, error: null })
  try {
    await navigator.storage?.persist?.()   // ask the browser not to evict the key store
  } catch {
    /* best effort */
  }
  await s.engine.start()
}

async function refreshOwnProfile(set: (p: Partial<AppState>) => void, get: () => AppState, expected: string): Promise<void> {
  for (const delay of [1000, 2000, 4000, 8000]) {
    await new Promise((r) => setTimeout(r, delay))
    const s = get().session
    if (!s) return
    try {
      const me = await api.me()
      s.engine.rememberProfile(me)
      set({ session: { ...s, user: me } })
      if (me.displayName === expected) return
    } catch {
      /* try again */
    }
  }
}

export const useApp = create<AppState>((set, get) => ({
  session: null,
  booting: true,
  error: null,
  tick: 0,

  restore: async () => {
    try {
      if (!api.tokens.load()) return
      await activate(set, get, await establishSession(deps()))
    } catch (e) {
      console.warn('session restore failed', e)
      api.tokens.save(null)
    } finally {
      set({ booting: false })
    }
  },

  login: async (login, password) => {
    await activate(set, get, await signIn(deps(), 'login', login, password))
  },

  register: async (username, password, email, displayName) => {
    await activate(set, get, await signIn(deps(), 'register', username, password, { email, displayName }))
    const uid = get().session?.user.userId
    if (uid) setTourPrefs(uid, { newAccount: true })   // open the welcome tour right after sign-up
    // The profile is created from the registration event a moment later; until then it shows the username.
    // Re-read it a few times so the chosen display name appears without a reload.
    if (displayName?.trim()) void refreshOwnProfile(set, get, displayName.trim())
  },

  logout: async () => {
    get().session?.engine.stop()
    unsubscribe?.()
    unsubscribe = null
    await api.logout()
    set({ session: null })
  },
}))

api.onSessionExpired = () => {
  const s = useApp.getState().session
  if (s) {
    s.engine.stop()
    useApp.setState({ session: null, error: 'Your session expired. Please sign in again.' })
  }
}

/** Re-render on engine changes. */
export function useEngineTick(): number {
  return useApp((s) => s.tick)
}
