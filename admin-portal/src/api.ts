/** Admin API client. The admin token lives in sessionStorage only (cleared when the tab closes). */
export interface Session {
  token: string
  expiresAt: string
  stage: 'FULL' | 'MFA_ENROLLMENT' | 'PASSWORD_CHANGE'
  roles: string[]
  username: string
  displayName: string
  mfaEnabled: boolean
}

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message)
  }
}

const KEY = 'securechat.admin.session'

export function loadSession(): Session | null {
  try {
    const s = JSON.parse(sessionStorage.getItem(KEY) ?? 'null') as Session | null
    return s && Date.parse(s.expiresAt) > Date.now() ? s : null
  } catch {
    return null
  }
}

export function saveSession(s: Session | null) {
  if (s) sessionStorage.setItem(KEY, JSON.stringify(s))
  else sessionStorage.removeItem(KEY)
}

let onExpired: () => void = () => undefined
export function setOnExpired(f: () => void) {
  onExpired = f
}

export async function api<T>(method: string, path: string, body?: unknown, token = loadSession()?.token): Promise<T> {
  const res = await fetch(`/api/admin/v1${path}`, {
    method,
    headers: { Accept: 'application/json', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (res.status === 401 && token && !path.startsWith('/auth/login')) {
    saveSession(null)
    onExpired()
  }
  if (!res.ok) {
    let p: { code?: string; detail?: string } = {}
    try {
      p = await res.json()
    } catch {
      /* empty */
    }
    throw new ApiError(res.status, p.code ?? 'ERROR', p.detail ?? `HTTP ${res.status}`)
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

export const can = (roles: string[], allowed: string[]) => roles.some((r) => allowed.includes(r))
export const R = {
  USERS_READ: ['SUPER_ADMIN', 'OPERATIONS_ADMIN', 'SUPPORT_ADMIN', 'SECURITY_ADMIN'],
  USERS_ACT: ['SUPER_ADMIN', 'SECURITY_ADMIN'],
  TRACE: ['SUPER_ADMIN', 'OPERATIONS_ADMIN', 'SUPPORT_ADMIN'],
  REPORTS: ['SUPER_ADMIN', 'OPERATIONS_ADMIN', 'REPORTING_ADMIN'],
  OPS: ['SUPER_ADMIN', 'OPERATIONS_ADMIN'],
  ALERTS: ['SUPER_ADMIN', 'OPERATIONS_ADMIN', 'SECURITY_ADMIN'],
  AUDIT: ['SUPER_ADMIN', 'SECURITY_ADMIN'],
  SUPER: ['SUPER_ADMIN'],
}

export const fmt = (iso?: string | null) => (iso ? new Date(iso).toLocaleString() : '—')
