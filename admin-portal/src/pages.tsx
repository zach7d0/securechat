import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { api, can, fmt, R } from './api'
import { Badge, Btn, Card, Err, input, Stat, statusTone, Table } from './ui'

type J = Record<string, any> // eslint-disable-line @typescript-eslint/no-explicit-any

function useLoad<T>(fn: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const reload = () => fn().then((d) => { setData(d); setError(null) }).catch((e) => setError((e as Error).message))
  useEffect(() => { void reload() }, deps) // eslint-disable-line react-hooks/exhaustive-deps
  return { data, error, reload }
}

/** Latency shown in seconds with 3 decimals (= millisecond precision); the API returns milliseconds. */
const sec = (v: unknown) => (v === null || v === undefined || v === '' || Number.isNaN(Number(v)) ? '—'
  : (Number(v) / 1000).toLocaleString(undefined, { minimumFractionDigits: 3, maximumFractionDigits: 3 }))

const H = ({ children }: { children: React.ReactNode }) => <h1 className="mb-4 text-xl font-semibold text-slate-900">{children}</h1>

// ------------------------------------------------------------------ dashboard

export function DashboardPage() {
  const { data: d, error } = useLoad(() => api<J>('GET', '/dashboard'))
  const m = d?.messages24h ?? {}
  return (
    <div className="space-y-5">
      <H>System dashboard</H>
      <Err>{error}</Err>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat label="Registered users" value={d?.accounts?.total} />
        <Stat label="Online users" value={d?.presence?.status === 'UNKNOWN' ? 'unknown' : d?.presence?.onlineUsers} />
        <Stat label="Active devices" value={d?.users?.activeDevices} />
        <Stat label="Services up" value={d ? `${d.servicesUp}/${d.servicesTotal}` : null} />
        <Stat label="Messages accepted (24 h)" value={m.accepted} />
        <Stat label="Device deliveries (24 h)" value={m.delivered_devices} />
        <Stat label="Pending device deliveries" value={m.pending_devices} />
        <Stat label="Failed device deliveries (24 h)" value={m.failed_devices} />
      </div>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat label="Conversations" value={d?.conversations?.conversations} />
        <Stat label="Groups" value={d?.conversations?.groups} />
        <Stat label="Suspended accounts" value={d?.accounts?.suspended} />
        <Stat label="Avg delivery latency (s)" value={sec(m.avg_delivery_latency_ms)} />
      </div>
    </div>
  )
}

// ------------------------------------------------------------------ users

export function UsersPage() {
  const [q, setQ] = useState('')
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const { data, error } = useLoad(() => api<J>('GET', `/users?q=${encodeURIComponent(query)}&page=${page}&size=25`), [query, page])
  const pages = data ? Math.max(1, Math.ceil(data.total / 25)) : 1
  return (
    <div className="space-y-4">
      <H>Registered users</H>
      <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); setPage(0); setQuery(q.trim()) }}>
        <input className={input} placeholder="Search username, display name or user id" value={q} onChange={(e) => setQ(e.target.value)} maxLength={64} />
        <Btn>Search</Btn>
      </form>
      <Err>{error}</Err>
      <Card title={`${data?.total ?? 0} users`}>
        <Table head={['User', 'Email', 'Status', 'Registered']} rows={(data?.items ?? []).map((u: J) => [
          <Link to={`/users/${u.userId}`} className="font-medium text-indigo-700 hover:underline">{u.displayName} <span className="text-slate-500">@{u.username}</span></Link>,
          u.email ?? '—', <Badge tone={statusTone(u.status)}>{u.status}</Badge>, fmt(u.createdAt)])} />
        <div className="mt-3 flex items-center justify-end gap-2 text-sm">
          <Btn variant="ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Btn>
          <span>Page {page + 1} / {pages}</span>
          <Btn variant="ghost" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next</Btn>
        </div>
      </Card>
    </div>
  )
}

export function UserDetailPage({ roles }: { roles: string[] }) {
  const { id = '' } = useParams()
  const { data, error, reload } = useLoad(() => api<J>('GET', `/users/${id}`), [id])
  const [actionError, setActionError] = useState<string | null>(null)
  const act = async (path: string) => {
    try {
      await api('POST', path)
      setActionError(null)
      await reload()
    } catch (e) {
      setActionError((e as Error).message)
    }
  }
  const acc = data?.account
  const allowed = can(roles, R.USERS_ACT)
  return (
    <div className="space-y-4">
      <H>{data?.profile?.displayName ?? 'User'} <span className="text-base font-normal text-slate-500">@{data?.profile?.username}</span></H>
      <Err>{error ?? actionError}</Err>
      <Card title="Account" actions={allowed && acc && (acc.status === 'ACTIVE'
        ? <Btn variant="danger" onClick={() => void act(`/users/${id}/suspend`)}>Suspend account</Btn>
        : <Btn onClick={() => void act(`/users/${id}/reactivate`)}>Reactivate</Btn>)}>
        <dl className="grid grid-cols-2 gap-3 text-sm md:grid-cols-4">
          <div><dt className="text-slate-500">Status</dt><dd><Badge tone={statusTone(acc?.status)}>{acc?.status ?? '—'}</Badge></dd></div>
          <div><dt className="text-slate-500">Email</dt><dd>{acc?.email ?? '—'}</dd></div>
          <div><dt className="text-slate-500">Registered</dt><dd>{fmt(acc?.createdAt)}</dd></div>
          <div><dt className="text-slate-500">Active sessions</dt><dd>{acc?.activeSessions ?? '—'}</dd></div>
          <div className="col-span-2 md:col-span-4"><dt className="text-slate-500">User id</dt><dd className="font-mono text-xs">{id}</dd></div>
        </dl>
      </Card>
      <Card title="Registered devices (public-key status only)">
        <Table head={['Device', 'Platform', 'Status', 'Registered / last seen', 'Key status', '']} rows={(data?.devices ?? []).map((d: J) => [
          <div><div className="font-medium">{d.deviceName ?? '—'}</div><div className="font-mono text-[11px] text-slate-500">{d.id}</div></div>,
          d.platform, <Badge tone={statusTone(d.status)}>{d.status}</Badge>,
          <div className="text-xs">{fmt(d.createdAt)}<br />{fmt(d.lastSeenAt)}</div>,
          d.keys ? <div className="text-xs">identity {d.keys.identity ? '✓' : '✗'} · v{d.keys.keyVersion}<br />{d.keys.oneTimePreKeys} prekeys · {d.keys.mlsKeyPackages} key packages</div> : '—',
          allowed && d.status === 'ACTIVE' ? <Btn variant="ghost" onClick={() => void act(`/users/${id}/devices/${d.id}/revoke`)}>Revoke</Btn> : null])} />
      </Card>
    </div>
  )
}

// ------------------------------------------------------------------ message trace

export function MessagesPage() {
  const [messageId, setMessageId] = useState('')
  const [conversationId, setConversationId] = useState('')
  const [trace, setTrace] = useState<J | null>(null)
  const [list, setList] = useState<J[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const lookup = async (id: string) => {
    try {
      setTrace(await api<J>('GET', `/messages/${id.trim()}/trace`))
      setError(null)
    } catch (e) {
      setTrace(null)
      setError((e as Error).message)
    }
  }
  return (
    <div className="space-y-4">
      <H>Message trace</H>
      <div className="grid gap-3 md:grid-cols-2">
        <Card title="By message id">
          <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); void lookup(messageId) }}>
            <input className={input} placeholder="message id (UUID)" value={messageId} onChange={(e) => setMessageId(e.target.value)} />
            <Btn>Trace</Btn>
          </form>
        </Card>
        <Card title="By conversation (last 24 h)">
          <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); void api<J[]>('GET', `/messages?conversationId=${conversationId.trim()}`).then(setList).catch((x) => setError(x.message)) }}>
            <input className={input} placeholder="conversation id (UUID)" value={conversationId} onChange={(e) => setConversationId(e.target.value)} />
            <Btn>Search</Btn>
          </form>
        </Card>
      </div>
      <Err>{error}</Err>
      {list && (
        <Card title={`${list.length} messages`}>
          <Table head={['Message', 'Accepted', 'Protocol', 'Devices (delivered / pending / failed)']} rows={list.map((m) => [
            <button className="font-mono text-xs text-indigo-700 hover:underline" onClick={() => void lookup(m.message_id)}>{m.message_id}</button>,
            fmt(m.accepted_at), m.protocol, `${m.delivered_devices} / ${m.pending_devices} / ${m.failed_devices} of ${m.expected_devices ?? '?'}`])} />
        </Card>
      )}
      {trace && (
        <>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
            <Stat label="Expected devices" value={trace.expected_devices} />
            <Stat label="Delivered" value={trace.delivered_devices} />
            <Stat label="Read" value={trace.read_devices} />
            <Stat label="Pending (not failure)" value={trace.pending_devices} />
            <Stat label="Failed" value={trace.failed_devices} />
          </div>
          <Card title="Lifecycle milestones">
            <Table head={['Milestone', 'Time', 'Service', 'Device', 'Error', 'Correlation']} rows={(trace.milestones ?? []).map((m: J) => [
              <Badge tone={statusTone(m.milestone === 'FAILED' ? 'FAILED' : m.milestone === 'DELIVERY_PENDING' ? 'PENDING' : 'UP')}>{m.milestone}</Badge>,
              fmt(m.event_time), m.service, <span className="font-mono text-[11px]">{m.device_id ?? '—'}</span>, m.error_category ?? '—',
              <span className="font-mono text-[11px]">{m.correlation_id}</span>])} />
          </Card>
          <Card title="Per-device delivery">
            <Table head={['Device', 'User', 'Status', 'Pending since', 'Delivered', 'Read', 'Failure']} rows={(trace.devices ?? []).map((d: J) => [
              <span className="font-mono text-[11px]">{d.device_id}</span>, <span className="font-mono text-[11px]">{d.user_id ?? '—'}</span>,
              <Badge tone={statusTone(d.status)}>{d.status}</Badge>, fmt(d.pending_at), fmt(d.delivered_at), fmt(d.read_at), d.failure_reason ?? '—'])} />
            <p className="mt-2 text-xs text-slate-500">Projection from lifecycle events. Authoritative per-device state from Delivery Service:
              {' '}{(trace.authoritativeReceipts ?? []).map((r: J) => `${r.deviceId?.slice(0, 8)}=${r.status}`).join(', ') || '—'}</p>
          </Card>
        </>
      )}
    </div>
  )
}

// ------------------------------------------------------------------ reports

const PRESETS: { label: string; hours: number }[] = [
  { label: 'Last 24 h', hours: 24 }, { label: 'Last 7 days', hours: 24 * 7 }, { label: 'Last 30 days', hours: 24 * 30 },
]

/** Date ⇄ value of an <input type="datetime-local"> (browser local time, minute precision). */
function toLocalInput(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

function presetRange(hours: number) {
  const to = new Date()
  return { from: toLocalInput(new Date(to.getTime() - hours * 3600_000)), to: toLocalInput(to) }
}

export function ReportsPage() {
  const [draft, setDraft] = useState(() => presetRange(24))
  const [range, setRange] = useState(draft)
  const [rangeError, setRangeError] = useState<string | null>(null)
  const q = `?from=${encodeURIComponent(new Date(range.from).toISOString())}&to=${encodeURIComponent(new Date(range.to).toISOString())}`
  const { data: s, error } = useLoad(() => api<J>('GET', `/reports/summary${q}`), [range])
  const { data: hourly } = useLoad(() => api<J[]>('GET', `/reports/hourly${q}`), [range])
  const max = Math.max(1, ...(hourly ?? []).map((h) => Number(h.accepted_count)))
  const apply = (r: { from: string; to: string }) => {
    const f = new Date(r.from).getTime()
    const t = new Date(r.to).getTime()
    if (!r.from || !r.to || Number.isNaN(f) || Number.isNaN(t)) return setRangeError('Choose both a start and an end date/time')
    if (f >= t) return setRangeError('Start must be before end')
    if (t - f > 92 * 24 * 3600_000) return setRangeError('Range can be at most 92 days')
    setRangeError(null)
    setDraft(r)
    setRange({ ...r }) // new object → re-fetch even when the window is unchanged
  }
  return (
    <div className="space-y-4">
      <H>Reports ({new Date(range.from).toLocaleString()} → {new Date(range.to).toLocaleString()})</H>
      <Card title="Time range">
        <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); apply(draft) }}>
          <label className="text-sm">From<input type="datetime-local" className={`${input} block`} value={draft.from} max={draft.to}
            onChange={(e) => setDraft({ ...draft, from: e.target.value })} required /></label>
          <label className="text-sm">To<input type="datetime-local" className={`${input} block`} value={draft.to} min={draft.from}
            onChange={(e) => setDraft({ ...draft, to: e.target.value })} required /></label>
          <Btn>Retrieve</Btn>
          <div className="flex gap-1">
            {PRESETS.map((p) => <Btn key={p.label} type="button" variant="ghost" onClick={() => apply(presetRange(p.hours))}>{p.label}</Btn>)}
          </div>
        </form>
        <p className="mt-2 text-xs text-slate-500">Times are in your local time zone. Totals are counted in whole hours; failure categories cover the last 30 days at most (device-level retention). "Pending" is always the current backlog.</p>
      </Card>
      <Err>{rangeError}</Err>
      <Err>{error}</Err>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-6">
        <Stat label="Accepted" value={s?.accepted} />
        <Stat label="Delivered (devices)" value={s?.delivered_devices} />
        <Stat label="Read (devices)" value={s?.read_devices} />
        <Stat label="Pending (devices)" value={s?.pending_devices} />
        <Stat label="Failed (devices)" value={s?.failed_devices} />
        <Stat label="Avg / max latency (s)" value={s ? `${sec(s.avg_delivery_latency_ms)} / ${sec(s.max_delivery_latency_ms)}` : null} />
      </div>
      <Card title="Failure categories">
        <Table head={['Category', 'Count']} rows={(s?.failure_categories ?? []).map((f: J) => [f.category, f.count])} empty="No failures" />
      </Card>
      <Card title="Hourly">
        <Table head={['Hour', 'Accepted', '', 'Delivered', 'Read', 'Failed', 'Avg latency (s)']} rows={(hourly ?? []).map((h) => [
          fmt(h.bucket_hour), h.accepted_count,
          <div className="h-2 w-40 rounded bg-slate-100"><div className="h-2 rounded bg-indigo-500" style={{ width: `${(Number(h.accepted_count) / max) * 100}%` }} /></div>,
          h.delivered_device_count, h.read_device_count, h.failed_device_count, sec(h.avg_delivery_latency_ms)])} />
      </Card>
    </div>
  )
}

// ------------------------------------------------------------------ health, alerts, incidents

export function HealthPage({ roles }: { roles: string[] }) {
  const { data: health, error, reload } = useLoad(() => api<J[]>('GET', '/health'))
  const { data: alerts, error: alertError } = useLoad(() => api<J>('GET', '/alerts'))
  const incidents = useLoad(() => api<J[]>('GET', '/incidents'))
  const [title, setTitle] = useState('')
  const [severity, setSeverity] = useState('warning')
  const [note, setNote] = useState<Record<string, string>>({})
  const ops = can(roles, R.OPS)
  const [actError, setActError] = useState<string | null>(null)
  const act = (f: () => Promise<unknown>) => void f().then(() => { setActError(null); return incidents.reload() }).catch((e) => setActError((e as Error).message))
  return (
    <div className="space-y-4">
      <H>Service health, alerts & incidents</H>
      <Err>{error}</Err>
      <Card title="Services" actions={<Btn variant="ghost" onClick={() => void reload()}>Refresh</Btn>}>
        <div className="grid grid-cols-1 gap-2 md:grid-cols-3">
          {(health ?? []).map((h) => (
            <div key={h.service} className="rounded-lg border border-slate-200 p-3">
              <div className="flex items-center justify-between"><span className="font-medium">{h.service}</span><Badge tone={statusTone(h.status)}>{h.status}</Badge></div>
              <div className="mt-1 text-xs text-slate-500">{Object.entries(h.components ?? {}).map(([k, v]) => `${k}: ${v}`).join(' · ')} · {h.latencyMs} ms</div>
            </div>
          ))}
        </div>
      </Card>
      <Card title="Firing alerts (Prometheus)">
        <Err>{alertError}</Err>
        <Table head={['Alert', 'Severity', 'State', 'Since', 'Summary', '']} rows={(alerts?.alerts ?? []).map((a: J) => [
          a.labels?.alertname, <Badge tone={statusTone(a.labels?.severity)}>{a.labels?.severity}</Badge>, a.state, fmt(a.activeAt), a.annotations?.summary,
          ops ? <Btn variant="ghost" onClick={() => act(() => api('POST', '/incidents', { title: a.annotations?.summary ?? a.labels?.alertname, severity: a.labels?.severity ?? 'warning', source: a.labels?.alertname }))}>Open incident</Btn> : null])}
          empty="No alerts firing" />
      </Card>
      <Card title="Incidents">
        <Err>{actError}</Err>
        {ops && (
          <form className="mb-3 flex gap-2" onSubmit={(e) => { e.preventDefault(); act(() => api('POST', '/incidents', { title, severity })); setTitle('') }}>
            <input className={input} placeholder="New incident title" value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} />
            <select className={`${input} w-32`} value={severity} onChange={(e) => setSeverity(e.target.value)}>
              <option>critical</option><option>warning</option><option>info</option>
            </select>
            <Btn>Create</Btn>
          </form>
        )}
        <Table head={['Incident', 'Severity', 'Status', 'Opened', 'Notes', '']} rows={(incidents.data ?? []).map((i) => [
          <div><div className="font-medium">{i.title}</div><div className="text-xs text-slate-500">{i.source}</div></div>,
          <Badge tone={statusTone(i.severity)}>{i.severity}</Badge>, <Badge tone={statusTone(i.status)}>{i.status}</Badge>, fmt(i.created_at),
          <div className="space-y-1 text-xs">{(i.notes ?? []).map((n: J, k: number) => <div key={k}><b>{n.admin_username}</b>: {n.note}</div>)}
            {ops && i.status !== 'RESOLVED' && (
              <form className="flex gap-1" onSubmit={(e) => { e.preventDefault(); act(() => api('POST', `/incidents/${i.id}/notes`, { note: note[i.id] })); setNote({ ...note, [i.id]: '' }) }}>
                <input className="w-40 rounded border border-slate-300 px-2 py-0.5" placeholder="add note" value={note[i.id] ?? ''} onChange={(e) => setNote({ ...note, [i.id]: e.target.value })} required />
              </form>
            )}</div>,
          ops ? <div className="flex gap-1">
            {i.status === 'OPEN' && <Btn variant="ghost" onClick={() => act(() => api('POST', `/incidents/${i.id}/acknowledge`))}>Ack</Btn>}
            {i.status !== 'RESOLVED' && <Btn variant="ghost" onClick={() => act(() => api('POST', `/incidents/${i.id}/resolve`))}>Resolve</Btn>}
          </div> : null])} />
      </Card>
    </div>
  )
}

// ------------------------------------------------------------------ audit, admins, config

export function AuditPage() {
  const [action, setAction] = useState('')
  const [page, setPage] = useState(0)
  const { data, error } = useLoad(() => api<J[]>('GET', `/audit?page=${page}&size=50${action ? `&action=${action}` : ''}`), [action, page])
  return (
    <div className="space-y-4">
      <H>Admin audit log</H>
      <select className={`${input} w-64`} value={action} onChange={(e) => { setPage(0); setAction(e.target.value) }}>
        <option value="">All actions</option>
        {['ADMIN_LOGIN', 'USER_SEARCH', 'USER_VIEW', 'USER_SUSPEND', 'USER_REACTIVATE', 'DEVICE_REVOKE', 'MESSAGE_TRACE', 'MESSAGE_SEARCH',
          'ADMIN_CREATED', 'ADMIN_ROLES_CHANGED', 'ADMIN_STATUS_CHANGED', 'ADMIN_LOCK', 'ADMIN_UNLOCK', 'ADMIN_DELETED', 'ADMIN_MFA_ENABLE', 'ADMIN_PASSWORD_CHANGE', 'INCIDENT_CREATE'].map((a) => <option key={a}>{a}</option>)}
      </select>
      <Err>{error}</Err>
      <Card>
        <Table head={['Time', 'Admin', 'Action', 'Target', 'Outcome', 'Details', 'IP']} rows={(data ?? []).map((a) => [
          fmt(a.at), a.admin_username ?? '—', a.action, a.target_id ? `${a.target_type}:${String(a.target_id).slice(0, 8)}` : a.target_type ?? '—',
          <Badge tone={a.outcome === 'SUCCESS' ? 'green' : 'red'}>{a.outcome}</Badge>, <span className="text-xs">{a.details ?? ''}</span>, a.client_ip ?? '—'])} />
        <div className="mt-3 flex justify-end gap-2">
          <Btn variant="ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</Btn>
          <Btn variant="ghost" disabled={(data?.length ?? 0) < 50} onClick={() => setPage(page + 1)}>Older</Btn>
        </div>
      </Card>
    </div>
  )
}

const ROLES = ['SUPER_ADMIN', 'OPERATIONS_ADMIN', 'SUPPORT_ADMIN', 'SECURITY_ADMIN', 'REPORTING_ADMIN']

export function AdminsPage() {
  const { data, error, reload } = useLoad(() => api<J[]>('GET', '/admins'))
  const empty = { username: '', displayName: '', password: '', roles: [] as string[], mfaRequired: true }
  const [form, setForm] = useState(empty)
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const toggle = (r: string) => setForm({ ...form, roles: form.roles.includes(r) ? form.roles.filter((x) => x !== r) : [...form.roles, r] })
  const run = (f: () => Promise<unknown>) => void f().then(() => { setMsg(null); void reload() }).catch((e) => setMsg((e as Error).message))
  return (
    <div className="space-y-4">
      <H>Administrators</H>
      <Err>{error ?? msg}</Err>
      <Card title="Create admin (must change password at first login)">
        <form className="grid gap-2 md:grid-cols-3" onSubmit={(e) => { e.preventDefault(); run(() => api('POST', '/admins', form).then(() => setForm(empty))) }}>
          <input className={input} placeholder="username" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} required />
          <input className={input} placeholder="display name" value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
          <input className={input} type="password" placeholder="initial password (12+)" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} required minLength={12} />
          <div className="flex flex-wrap gap-3 text-sm md:col-span-2">
            {ROLES.map((r) => <label key={r} className="flex items-center gap-1"><input type="checkbox" checked={form.roles.includes(r)} onChange={() => toggle(r)} /> {r}</label>)}
          </div>
          <div className="text-sm md:col-span-3">
            <label className="flex items-center gap-2"><input type="checkbox" checked={form.mfaRequired}
              onChange={(e) => setForm({ ...form, mfaRequired: e.target.checked })} /> Require MFA (authenticator app enrolment at first login)</label>
            {!form.mfaRequired && <p className="mt-1 text-xs text-amber-700">Without MFA this admin signs in with a password only. Not recommended, especially for SUPER_ADMIN or SECURITY_ADMIN.</p>}
          </div>
          <Btn disabled={form.roles.length === 0}>Create admin</Btn>
        </form>
      </Card>
      <Card>
        <Table head={['Admin', 'Roles', 'MFA', 'Status', 'Lock', 'Last login', '']} rows={(data ?? []).map((a) => [
          <div><div className="font-medium">{a.display_name}</div><div className="text-xs text-slate-500">{a.username}</div></div>,
          <div className="flex flex-wrap gap-1">{String(a.roles).split(',').filter(Boolean).map((r) => <Badge key={r} tone="blue">{r}</Badge>)}</div>,
          a.mfa_enabled ? <Badge tone="green">ENROLLED</Badge>
            : a.mfa_required ? <Badge tone="amber">REQUIRED</Badge> : <Badge tone="slate">OFF</Badge>,
          <Badge tone={statusTone(a.status)}>{a.status}</Badge>,
          a.locked_manually ? <Badge tone="red">LOCKED</Badge>
            : a.locked ? <span title="Too many failed logins"><Badge tone="amber">LOCKED until {fmt(a.locked_until)}</Badge></span>
              : <span className="text-xs text-slate-500">{a.failed_attempts ? `${a.failed_attempts} failed` : '—'}</span>,
          fmt(a.last_login_at),
          <div className="flex gap-1">
            <Btn variant="ghost" onClick={() => run(() => api('PUT', `/admins/${a.id}/status`, { status: a.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE' }))}>
              {a.status === 'ACTIVE' ? 'Disable' : 'Enable'}</Btn>
            {a.locked
              ? <Btn variant="ghost" onClick={() => run(() => api('POST', `/admins/${a.id}/unlock`))}>Unlock</Btn>
              : <Btn variant="danger" onClick={() => run(() => api('POST', `/admins/${a.id}/lock`))}>Lock</Btn>}
            {confirmDelete === a.id
              ? <>
                <Btn variant="danger" onClick={() => { setConfirmDelete(null); run(() => api('DELETE', `/admins/${a.id}`)) }}>Confirm delete</Btn>
                <Btn variant="ghost" onClick={() => setConfirmDelete(null)}>Cancel</Btn>
              </>
              : <Btn variant="ghost" onClick={() => setConfirmDelete(a.id)}>Delete</Btn>}
          </div>])} />
      </Card>
    </div>
  )
}

export function ConfigPage() {
  const { data, error } = useLoad(() => api<J>('GET', '/config'))
  return (
    <div className="space-y-4">
      <H>Operational configuration (read-only)</H>
      <Err>{error}</Err>
      <Card>
        <Table head={['Setting', 'Value']} rows={Object.entries(data ?? {}).map(([k, v]) => [k, <code className="text-xs">{typeof v === 'object' ? JSON.stringify(v) : String(v)}</code>])} />
      </Card>
    </div>
  )
}
