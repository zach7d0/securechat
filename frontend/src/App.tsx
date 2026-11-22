import { Lock, MessageCircle } from 'lucide-react'
import { useEffect, useState } from 'react'
import { BrowserRouter, Navigate, Outlet, Route, Routes, useLocation } from 'react-router'
import { ConversationView } from './components/ConversationView'
import { Sidebar } from './components/Sidebar'
import { OnboardingTour } from './components/OnboardingTour'
import { AppearancePage } from './pages/AppearancePage'
import { HelpPage } from './pages/HelpPage'
import { AuthPage } from './pages/AuthPage'
import { ProfilePage } from './pages/ProfilePage'
import { SecurityPage } from './pages/SecurityPage'
import { useApp } from './state/app'

/** Settings pages open full-width (no conversation list beside them); their ← returns to the chats. */
const FULL_PAGE_ROUTES = ['/profile', '/security', '/appearance', '/help']

function Protected() {
  const session = useApp((s) => s.session)
  const { pathname } = useLocation()
  if (!session) return <Navigate to="/login" replace />
  return (
    <div className="flex h-full overflow-hidden md:p-0">
      {!FULL_PAGE_ROUTES.includes(pathname) && <Sidebar />}
      <Outlet />
      <OnboardingTour />
    </div>
  )
}

function Welcome() {
  return (
    <div className="hidden flex-1 flex-col items-center justify-center gap-3 bg-slate-50 text-center md:flex dark:bg-slate-950">
      <MessageCircle size={56} className="text-emerald-600" />
      <h2 className="text-xl font-semibold text-slate-800 dark:text-slate-200">SecureChat</h2>
      <p className="max-w-sm text-sm text-slate-500">Select a conversation or start a new one.</p>
      <p className="inline-flex items-center gap-1 text-xs text-slate-400"><Lock size={12} /> Your messages are end-to-end encrypted</p>
    </div>
  )
}

/**
 * Only one tab may run the engine: two tabs processing the same messages would advance the same
 * Signal/MLS ratchets twice and corrupt state. Uses the Web Locks API where available.
 */
function useSingleTab(): 'pending' | 'owner' | 'blocked' {
  const [state, setState] = useState<'pending' | 'owner' | 'blocked'>(navigator.locks ? 'pending' : 'owner')
  useEffect(() => {
    if (!navigator.locks) return
    let release: (() => void) | undefined
    void navigator.locks.request('securechat-engine', { ifAvailable: true }, (lock) => {
      if (!lock) {
        setState('blocked')
        return
      }
      setState('owner')
      return new Promise<void>((r) => { release = r })
    })
    return () => release?.()
  }, [])
  return state
}

export function App() {
  const tab = useSingleTab()
  const { booting, restore } = useApp()
  useEffect(() => {
    if (tab === 'owner') void restore()
  }, [tab, restore])

  if (tab === 'blocked') {
    return <Centered>SecureChat is open in another tab. Close it to continue here.</Centered>
  }
  if (tab === 'pending' || booting) {
    return <Centered>Unlocking your encrypted session…</Centered>
  }
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<AuthPage mode="login" />} />
        <Route path="/register" element={<AuthPage mode="register" />} />
        <Route element={<Protected />}>
          <Route index element={<Welcome />} />
          <Route path="/c/:id" element={<ConversationView />} />
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/security" element={<SecurityPage />} />
          <Route path="/appearance" element={<AppearancePage />} />
          <Route path="/help" element={<HelpPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}

function Centered({ children }: { children: React.ReactNode }) {
  return <div className="flex h-full items-center justify-center p-6 text-center text-sm text-slate-500">{children}</div>
}
