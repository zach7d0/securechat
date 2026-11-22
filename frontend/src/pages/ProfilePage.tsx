import { ArrowLeft } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { api, useApp } from '../state/app'
import { Avatar, Button, ErrorText } from '../components/ui'

export function ProfilePage() {
  const session = useApp((s) => s.session!)
  const [name, setName] = useState(session.user.displayName)
  const [msg, setMsg] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  return (
    <PageShell title="Profile">
      <div className="flex flex-col items-center gap-3 py-4">
        <Avatar name={name || session.user.username} size={96} />
        <div className="text-sm text-slate-500">@{session.user.username}</div>
      </div>
      <label className="block text-sm font-medium text-slate-700 dark:text-slate-300">Display name</label>
      <input value={name} maxLength={64} onChange={(e) => setName(e.target.value)}
        className="mt-1 w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 text-sm text-slate-900 outline-none focus:border-emerald-500 dark:border-slate-700 dark:text-slate-100" />
      <div className="mt-3 flex items-center gap-3">
        <Button disabled={!name.trim() || name === session.user.displayName} onClick={async () => {
          try {
            const u = await api.updateMe({ displayName: name.trim() })
            session.engine.rememberProfile(u)
            useApp.setState({ session: { ...session, user: u } })
            setMsg('Saved')
            setError(null)
          } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed')
          }
        }}>Save</Button>
        {msg && <span className="text-sm text-emerald-600">{msg}</span>}
      </div>
      <div className="mt-3"><ErrorText>{error}</ErrorText></div>
      <EmailSection />
      <PasswordSection username={session.user.username} />
      <dl className="mt-8 space-y-2 text-sm">
        <div><dt className="text-slate-500">User ID</dt><dd className="font-mono text-xs text-slate-800 dark:text-slate-200">{session.user.userId}</dd></div>
        <div><dt className="text-slate-500">This device</dt><dd className="font-mono text-xs text-slate-800 dark:text-slate-200">{session.deviceId}</dd></div>
      </dl>
    </PageShell>
  )
}

const inputCls = 'mt-1 w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 text-sm text-slate-900 outline-none focus:border-emerald-500 dark:border-slate-700 dark:text-slate-100'

/** Private to the owner: shows the account email and lets the user change it after re-entering their password. */
function EmailSection() {
  const [email, setEmail] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  const [password, setPassword] = useState('')
  const [editing, setEditing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    api.account().then((a) => alive && setEmail(a.email)).catch((e) => alive && setError(e instanceof Error ? e.message : 'Failed to load email'))
    return () => { alive = false }
  }, [])

  const save = async () => {
    setBusy(true)
    try {
      const a = await api.changeEmail(draft.trim(), password)
      setEmail(a.email)
      setEditing(false)
      setPassword('')
      setMsg('Email updated')
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="mt-8 border-t border-slate-200 pt-6 dark:border-slate-800">
      <label className="block text-sm font-medium text-slate-700 dark:text-slate-300">Email</label>
      <p className="text-xs text-slate-500">Only visible to you. Used to sign in.</p>
      {!editing ? (
        <div className="mt-2 flex items-center gap-3">
          <span className="text-sm text-slate-900 dark:text-slate-100">{email ?? '…'}</span>
          <Button variant="ghost" disabled={email === null} onClick={() => { setDraft(email ?? ''); setEditing(true); setMsg(null); setError(null) }}>Change</Button>
          {msg && <span className="text-sm text-emerald-600">{msg}</span>}
        </div>
      ) : (
        <form className="mt-2 space-y-3" onSubmit={(e) => { e.preventDefault(); void save() }}>
          <input type="email" value={draft} maxLength={320} required autoComplete="email" aria-label="New email"
            onChange={(e) => setDraft(e.target.value)} className={inputCls} />
          <input type="password" value={password} maxLength={128} required autoComplete="current-password"
            placeholder="Current password" aria-label="Current password" onChange={(e) => setPassword(e.target.value)} className={inputCls} />
          <div className="flex items-center gap-3">
            <Button type="submit" disabled={busy || !draft.trim() || !password || draft.trim().toLowerCase() === email}>Save email</Button>
            <Button type="button" variant="ghost" onClick={() => { setEditing(false); setPassword(''); setError(null) }}>Cancel</Button>
          </div>
        </form>
      )}
      <div className="mt-3"><ErrorText>{error}</ErrorText></div>
    </section>
  )
}

/** Change password: current password re-checked server-side; other devices are signed out. */
function PasswordSection({ username }: { username: string }) {
  const [open, setOpen] = useState(false)
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const reset = () => { setCurrent(''); setNext(''); setConfirm(''); setError(null) }
  const problem = next.length > 0 && next.length < 10 ? 'At least 10 characters'
    : next.length > 0 && next.toLowerCase().includes(username.toLowerCase()) ? 'Must not contain your username'
      : confirm.length > 0 && confirm !== next ? 'Passwords do not match'
        : next.length > 0 && next === current ? 'Must differ from the current password' : null

  const save = async () => {
    setBusy(true)
    try {
      const r = await api.changePassword(current, next)
      reset()
      setOpen(false)
      setMsg(r.otherSessionsRevoked > 0
        ? `Password changed. Signed out of ${r.otherSessionsRevoked} other session${r.otherSessionsRevoked === 1 ? '' : 's'}.`
        : 'Password changed')
    } catch (e) {
      const code = (e as { code?: string }).code
      setError(code === 'INVALID_CREDENTIALS' ? 'Current password is incorrect'
        : code === 'RATE_LIMITED' ? 'Too many attempts, try again later' : e instanceof Error ? e.message : 'Failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="mt-8 border-t border-slate-200 pt-6 dark:border-slate-800">
      <label className="block text-sm font-medium text-slate-700 dark:text-slate-300">Password</label>
      {!open ? (
        <div className="mt-2 flex items-center gap-3">
          <Button variant="ghost" onClick={() => { reset(); setMsg(null); setOpen(true) }}>Change password</Button>
          {msg && <span className="text-sm text-emerald-600">{msg}</span>}
        </div>
      ) : (
        <form className="mt-2 space-y-3" onSubmit={(e) => { e.preventDefault(); if (!problem) void save() }}>
          {/* hidden username helps password managers update the right entry */}
          <input type="text" autoComplete="username" value={username} readOnly hidden />
          <input type="password" value={current} maxLength={128} required autoComplete="current-password"
            placeholder="Current password" aria-label="Current password" onChange={(e) => setCurrent(e.target.value)} className={inputCls} />
          <input type="password" value={next} minLength={10} maxLength={128} required autoComplete="new-password"
            placeholder="New password (min. 10 characters)" aria-label="New password" onChange={(e) => setNext(e.target.value)} className={inputCls} />
          <input type="password" value={confirm} maxLength={128} required autoComplete="new-password"
            placeholder="Confirm new password" aria-label="Confirm new password" onChange={(e) => setConfirm(e.target.value)} className={inputCls} />
          {problem && <p className="text-xs text-rose-600">{problem}</p>}
          <p className="text-xs text-slate-500">Your other devices will be signed out. Your messages and encryption keys are not affected.</p>
          <div className="flex items-center gap-3">
            <Button type="submit" disabled={busy || !current || !next || next !== confirm || !!problem}>Save password</Button>
            <Button type="button" variant="ghost" onClick={() => { reset(); setOpen(false) }}>Cancel</Button>
          </div>
        </form>
      )}
      <div className="mt-3"><ErrorText>{error}</ErrorText></div>
    </section>
  )
}

export function PageShell({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="flex min-w-0 flex-1 flex-col bg-white dark:bg-slate-900">
      <header className="flex items-center gap-3 border-b border-slate-200 px-4 py-3 dark:border-slate-800">
        <Link to="/" className="rounded-full p-1.5 text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800" aria-label="Back"><ArrowLeft size={20} /></Link>
        <h1 className="text-lg font-semibold text-slate-900 dark:text-slate-100">{title}</h1>
      </header>
      <div className="flex-1 overflow-y-auto">
        <div className="mx-auto w-full max-w-2xl p-5 sm:p-8">{children}</div>
      </div>
    </div>
  )
}
