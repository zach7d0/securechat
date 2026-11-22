import { Activity, BarChart3, Bell, ClipboardList, Gauge, KeyRound, LogOut, MessageSquareText, Settings, Shield, Users } from 'lucide-react'
import { useEffect, useState } from 'react'
import { BrowserRouter, Navigate, NavLink, Route, Routes } from 'react-router'
import { api, can, loadSession, R, saveSession, setOnExpired, type Session } from './api'
import { AdminsPage, AuditPage, ConfigPage, DashboardPage, HealthPage, MessagesPage, ReportsPage, UserDetailPage, UsersPage } from './pages'
import { Btn, Err, input } from './ui'

export function App() {
  const [session, setSession] = useState<Session | null>(loadSession())
  const [notice, setNotice] = useState<string | null>(null)
  useEffect(() => {
    setOnExpired(() => {
      setSession(null)
      setNotice('Your admin session expired. Please sign in again.')
    })
    if (!session) return
    const t = setTimeout(() => { saveSession(null); setSession(null); setNotice('Admin session expired.') }, Date.parse(session.expiresAt) - Date.now())
    return () => clearTimeout(t)
  }, [session])
  const set = (s: Session | null) => {
    saveSession(s)
    setSession(s)
  }
  if (!session || session.stage !== 'FULL') return <AuthFlow session={session} onSession={set} notice={notice} />
  return (
    <BrowserRouter>
      <Shell session={session} onLogout={() => set(null)}>
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          {can(session.roles, R.USERS_READ) && <Route path="/users" element={<UsersPage />} />}
          {can(session.roles, R.USERS_READ) && <Route path="/users/:id" element={<UserDetailPage roles={session.roles} />} />}
          {can(session.roles, R.TRACE) && <Route path="/messages" element={<MessagesPage />} />}
          {can(session.roles, R.REPORTS) && <Route path="/reports" element={<ReportsPage />} />}
          {can(session.roles, R.ALERTS) && <Route path="/health" element={<HealthPage roles={session.roles} />} />}
          {can(session.roles, R.AUDIT) && <Route path="/audit" element={<AuditPage />} />}
          {can(session.roles, R.SUPER) && <Route path="/admins" element={<AdminsPage />} />}
          {can(session.roles, R.OPS) && <Route path="/config" element={<ConfigPage />} />}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Shell>
    </BrowserRouter>
  )
}

function Shell({ session, onLogout, children }: { session: Session; onLogout: () => void; children: React.ReactNode }) {
  const nav = [
    { to: '/', label: 'Dashboard', icon: Gauge, roles: null },
    { to: '/users', label: 'Users', icon: Users, roles: R.USERS_READ },
    { to: '/messages', label: 'Message trace', icon: MessageSquareText, roles: R.TRACE },
    { to: '/reports', label: 'Reports', icon: BarChart3, roles: R.REPORTS },
    { to: '/health', label: 'Health & alerts', icon: Bell, roles: R.ALERTS },
    { to: '/audit', label: 'Audit log', icon: ClipboardList, roles: R.AUDIT },
    { to: '/admins', label: 'Admins', icon: Shield, roles: R.SUPER },
    { to: '/config', label: 'Configuration', icon: Settings, roles: R.OPS },
  ].filter((n) => !n.roles || can(session.roles, n.roles))
  return (
    <div className="flex h-full">
      <aside className="flex w-60 shrink-0 flex-col bg-slate-900 text-slate-200">
        <div className="flex items-center gap-2 px-5 py-4 text-white"><Activity size={20} className="text-indigo-400" /><span className="font-semibold">SecureChat Admin</span></div>
        <nav className="flex-1 space-y-0.5 px-2">
          {nav.map((n) => (
            <NavLink key={n.to} to={n.to} end={n.to === '/'} className={({ isActive }) => `flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm ${isActive ? 'bg-slate-800 text-white' : 'hover:bg-slate-800/60'}`}>
              <n.icon size={16} /> {n.label}
            </NavLink>
          ))}
        </nav>
        <div className="border-t border-slate-800 p-4 text-xs">
          <div className="font-medium text-white">{session.displayName}</div>
          <div className="mb-3 text-slate-400">{session.roles.join(', ')}</div>
          <button onClick={onLogout} className="flex items-center gap-1.5 text-slate-300 hover:text-white"><LogOut size={14} /> Sign out</button>
        </div>
      </aside>
      <main className="flex-1 overflow-y-auto p-6">
        <div className="mb-4 rounded-lg border border-indigo-200 bg-indigo-50 px-3 py-2 text-xs text-indigo-900">
          Operational metadata only. Messages are end-to-end encrypted: no screen can show message content or key material.
        </div>
        {children}
      </main>
    </div>
  )
}

function AuthFlow({ session, onSession, notice }: { session: Session | null; onSession: (s: Session | null) => void; notice: string | null }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [totp, setTotp] = useState('')
  const [needCode, setNeedCode] = useState(false)
  const [newPassword, setNewPassword] = useState('')
  const [mfa, setMfa] = useState<{ secret: string; otpauthUri: string } | null>(null)
  const [error, setError] = useState<string | null>(notice)
  const [busy, setBusy] = useState(false)
  const run = async (f: () => Promise<void>) => {
    setBusy(true)
    setError(null)
    try {
      await f()
    } catch (e) {
      const code = (e as { code?: string }).code
      if (code === 'MFA_REQUIRED') setNeedCode(true)
      setError(code === 'MFA_REQUIRED' ? 'Enter the code from your authenticator app' : code === 'RATE_LIMITED'
        ? 'Too many attempts. The account is temporarily locked.' : code === 'ACCOUNT_LOCKED'
          ? 'This account is locked. Ask a super admin to unlock it.' : (e as Error).message)
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="flex min-h-full items-center justify-center p-6">
      <div className="w-full max-w-sm rounded-2xl bg-white p-6 shadow">
        <div className="mb-5 flex items-center gap-2"><Shield className="text-indigo-600" /><h1 className="text-lg font-semibold">SecureChat Admin</h1></div>
        {!session && (
          <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); void run(async () => onSession(await api<Session>('POST', '/auth/login', { username, password, totp: totp || undefined }))) }}>
            <input className={input} placeholder="Admin username" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required />
            <input className={input} type="password" placeholder="Password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
            {needCode && <input className={input} inputMode="numeric" placeholder="6-digit authenticator code" value={totp} onChange={(e) => setTotp(e.target.value)} />}
            <Err>{error}</Err>
            <Btn className="w-full justify-center" disabled={busy}>Sign in</Btn>
          </form>
        )}
        {session?.stage === 'PASSWORD_CHANGE' && (
          <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); void run(async () => onSession(await api<Session>('POST', '/auth/password', { currentPassword: password, newPassword }, session.token))) }}>
            <p className="text-sm text-slate-600">Choose a new password (12+ characters) before continuing.</p>
            <input className={input} type="password" placeholder="Current password" value={password} onChange={(e) => setPassword(e.target.value)} required />
            <input className={input} type="password" placeholder="New password" autoComplete="new-password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} required minLength={12} />
            <Err>{error}</Err>
            <Btn className="w-full justify-center" disabled={busy}>Change password</Btn>
          </form>
        )}
        {session?.stage === 'MFA_ENROLLMENT' && (
          <div className="space-y-3">
            <p className="text-sm text-slate-600"><KeyRound size={14} className="mr-1 inline" />Multi-factor authentication is required for admin accounts.</p>
            {!mfa ? (
              <Btn onClick={() => void run(async () => setMfa(await api('POST', '/mfa/setup', undefined, session.token)))}>Set up authenticator</Btn>
            ) : (
              <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); void run(async () => onSession(await api<Session>('POST', '/mfa/verify', { code: totp }, session.token))) }}>
                <div className="rounded-lg bg-slate-50 p-3 text-xs">
                  <div className="mb-1 text-slate-500">Add this key to your authenticator app (TOTP, SHA-1, 6 digits, 30 s):</div>
                  <code className="break-all font-mono text-sm">{mfa.secret}</code>
                </div>
                <input className={input} inputMode="numeric" placeholder="6-digit code" value={totp} onChange={(e) => setTotp(e.target.value)} required />
                <Err>{error}</Err>
                <Btn className="w-full justify-center" disabled={busy}>Verify and enable</Btn>
              </form>
            )}
            <Err>{!mfa ? error : null}</Err>
          </div>
        )}
        {session && <button className="mt-4 text-xs text-slate-500 underline" onClick={() => onSession(null)}>Cancel and sign out</button>}
      </div>
    </div>
  )
}
