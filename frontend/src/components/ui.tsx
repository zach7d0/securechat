import { X } from 'lucide-react'
import type { ReactNode } from 'react'

const palette = ['bg-emerald-600', 'bg-sky-600', 'bg-violet-600', 'bg-amber-600', 'bg-rose-600', 'bg-teal-600', 'bg-indigo-600']

export function Avatar({ name, size = 40, online, group }: { name: string; size?: number; online?: boolean; group?: boolean }) {
  const hash = [...name].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7)
  const initials = name.split(/\s+/).map((p) => p[0]).join('').slice(0, 2).toUpperCase() || '?'
  return (
    <div className="relative shrink-0" style={{ width: size, height: size }}>
      <div className={`${palette[hash % palette.length]} ${group ? 'rounded-2xl' : 'rounded-full'} flex h-full w-full items-center justify-center font-semibold text-white`}
        style={{ fontSize: size * 0.38 }}>
        {initials}
      </div>
      {online !== undefined && (
        <span className={`absolute right-0 bottom-0 h-3 w-3 rounded-full border-2 border-white dark:border-slate-900 ${online ? 'bg-emerald-500' : 'bg-slate-400'}`}
          aria-label={online ? 'online' : 'offline'} />
      )}
    </div>
  )
}

export function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 p-0 sm:items-center sm:p-4" onClick={onClose} role="dialog" aria-modal="true">
      <div className={`max-h-[90vh] w-full overflow-hidden rounded-t-2xl bg-white shadow-xl sm:rounded-2xl dark:bg-slate-900 ${wide ? 'sm:max-w-2xl' : 'sm:max-w-md'} flex flex-col`}
        onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4 dark:border-slate-800">
          <h2 className="text-lg font-semibold text-slate-900 dark:text-slate-100">{title}</h2>
          <button onClick={onClose} className="rounded-full p-1.5 text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-800" aria-label="Close"><X size={18} /></button>
        </div>
        <div className="overflow-y-auto p-5">{children}</div>
      </div>
    </div>
  )
}

export function Button({ children, variant = 'primary', ...props }: React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'ghost' | 'danger' }) {
  const styles = {
    primary: 'bg-emerald-600 text-white hover:bg-emerald-700 disabled:bg-emerald-600/50',
    ghost: 'bg-transparent text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800',
    danger: 'bg-rose-600 text-white hover:bg-rose-700 disabled:bg-rose-600/50',
  }[variant]
  return <button {...props} className={`inline-flex items-center justify-center gap-2 rounded-lg px-4 py-2 text-sm font-medium transition disabled:cursor-not-allowed ${styles} ${props.className ?? ''}`}>{children}</button>
}

export function E2EEBadge({ protocol }: { protocol: 'Signal' | 'MLS' }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-2 py-0.5 text-[11px] font-medium text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300"
      title={protocol === 'Signal' ? 'End-to-end encrypted with the Signal Protocol' : 'End-to-end encrypted with MLS (RFC 9420)'}>
      <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor" aria-hidden><path d="M12 1a5 5 0 0 0-5 5v3H5v14h14V9h-2V6a5 5 0 0 0-5-5Zm-3 8V6a3 3 0 1 1 6 0v3H9Z" /></svg>
      E2EE · {protocol}
    </span>
  )
}

export function ErrorText({ children }: { children: ReactNode }) {
  return children ? <p className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:bg-rose-950/50 dark:text-rose-300">{children}</p> : null
}
