import type { ReactNode } from 'react'

export function Card({ title, children, actions }: { title?: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
      {(title || actions) && (
        <div className="mb-3 flex items-center justify-between gap-3">
          {title && <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">{title}</h2>}
          {actions}
        </div>
      )}
      {children}
    </section>
  )
}

export function Btn({ children, variant = 'primary', ...p }: React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'ghost' | 'danger' }) {
  const s = { primary: 'bg-indigo-600 text-white hover:bg-indigo-700', ghost: 'bg-white text-slate-700 border border-slate-300 hover:bg-slate-50',
    danger: 'bg-rose-600 text-white hover:bg-rose-700' }[variant]
  return <button {...p} className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium disabled:opacity-40 ${s} ${p.className ?? ''}`}>{children}</button>
}

export const input = 'w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm outline-none focus:border-indigo-500'

export function Err({ children }: { children: ReactNode }) {
  return children ? <p className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">{children}</p> : null
}

export function Badge({ children, tone = 'slate' }: { children: ReactNode; tone?: 'green' | 'red' | 'amber' | 'slate' | 'blue' }) {
  const t = { green: 'bg-emerald-100 text-emerald-800', red: 'bg-rose-100 text-rose-800', amber: 'bg-amber-100 text-amber-800',
    slate: 'bg-slate-100 text-slate-700', blue: 'bg-sky-100 text-sky-800' }[tone]
  return <span className={`inline-block rounded px-1.5 py-0.5 text-[11px] font-semibold ${t}`}>{children}</span>
}

export function statusTone(s?: string): 'green' | 'red' | 'amber' | 'slate' | 'blue' {
  if (!s) return 'slate'
  if (['UP', 'ACTIVE', 'DELIVERED', 'READ', 'RESOLVED'].includes(s)) return 'green'
  if (['DOWN', 'REVOKED', 'SUSPENDED', 'FAILED', 'critical', 'OPEN'].includes(s)) return 'red'
  if (['PENDING', 'warning', 'ACKNOWLEDGED', 'UNKNOWN'].includes(s)) return 'amber'
  return 'blue'
}

export function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <div className="text-2xl font-semibold text-slate-900">{value ?? '—'}</div>
      <div className="text-xs text-slate-500">{label}</div>
    </div>
  )
}

export function Table({ head, rows, empty = 'Nothing to show' }: { head: string[]; rows: ReactNode[][]; empty?: string }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead><tr className="border-b border-slate-200 text-xs uppercase text-slate-500">{head.map((h) => <th key={h} className="px-2 py-2 font-medium">{h}</th>)}</tr></thead>
        <tbody>
          {rows.length === 0 && <tr><td colSpan={head.length} className="px-2 py-6 text-center text-slate-500">{empty}</td></tr>}
          {rows.map((r, i) => <tr key={i} className="border-b border-slate-100 last:border-0">{r.map((c, j) => <td key={j} className="px-2 py-2 align-top">{c}</td>)}</tr>)}
        </tbody>
      </table>
    </div>
  )
}
