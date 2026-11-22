import { Lock } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError } from '../lib/api/http'
import { useApp } from '../state/app'
import { Button, ErrorText } from '../components/ui'

export function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const { login, register, error: sessionError } = useApp()
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(sessionError)

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (mode === 'login') await login(username, password)
      else await register(username, password, email, displayName || undefined)
      navigate('/')
    } catch (err) {
      setError(err instanceof ApiError
        ? (err.code === 'INVALID_CREDENTIALS' ? 'Wrong username or password' : err.code === 'RATE_LIMITED' ? 'Too many attempts, try again later' : err.message)
        : err instanceof Error ? err.message : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }

  const field = 'w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-sm text-slate-900 outline-none focus:border-emerald-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100'
  return (
    <div className="flex min-h-full items-center justify-center px-4 py-10">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex flex-col items-center text-center">
          <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-2xl bg-emerald-600 text-white shadow-lg"><Lock size={26} /></div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">SecureChat</h1>
          <p className="mt-1 text-sm text-slate-500">End-to-end encrypted messaging</p>
        </div>
        <form onSubmit={submit} className="space-y-3 rounded-2xl bg-white p-6 shadow-sm dark:bg-slate-900">
          <h2 className="text-lg font-semibold text-slate-900 dark:text-slate-100">{mode === 'login' ? 'Sign in' : 'Create account'}</h2>
          <input className={field} placeholder={mode === 'login' ? 'Username or email' : 'Username'} autoComplete="username" value={username} required
            minLength={3} maxLength={mode === 'login' ? 320 : 32} pattern={mode === 'login' ? undefined : '[A-Za-z0-9_.]{3,32}'}
            title="3-32 letters, digits, '_' or '.'" onChange={(e) => setUsername(e.target.value)} />
          {mode === 'register' && (
            <input className={field} type="email" placeholder="Email" autoComplete="email" required maxLength={320} value={email} onChange={(e) => setEmail(e.target.value)} />
          )}
          {mode === 'register' && (
            <input className={field} placeholder="Display name (optional)" maxLength={64} value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
          )}
          <input className={field} type="password" placeholder={mode === 'login' ? 'Password' : 'Password (min. 10 characters)'} required
            minLength={mode === 'login' ? undefined : 10} maxLength={128}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'} value={password} onChange={(e) => setPassword(e.target.value)} />
          <ErrorText>{error}</ErrorText>
          <Button type="submit" className="w-full" disabled={busy}>
            {busy ? (mode === 'register' ? 'Generating keys…' : 'Signing in…') : mode === 'login' ? 'Sign in' : 'Create account'}
          </Button>
          {mode === 'register' && <p className="text-xs text-slate-500">Your encryption keys are generated in this browser and never leave it.</p>}
        </form>
        <p className="mt-4 text-center text-sm text-slate-600 dark:text-slate-400">
          {mode === 'login' ? <>New here? <Link to="/register" className="font-medium text-emerald-600">Create an account</Link></>
            : <>Already registered? <Link to="/login" className="font-medium text-emerald-600">Sign in</Link></>}
        </p>
      </div>
    </div>
  )
}
