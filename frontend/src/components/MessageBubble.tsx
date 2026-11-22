import { AlertTriangle, Ban, Check, CheckCheck, ChevronDown, Clock, History, Lock, Pencil, RotateCw, Timer, Trash2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { canEdit } from '../lib/messaging/rules'
import { useLongPress } from './MessageActions'
import { Link } from 'react-router'
import type { LocalMessage } from '../lib/messaging/engine'
import { timeOf } from '../lib/format'

export function StatusIcon({ m }: { m: LocalMessage }) {
  switch (m.status) {
    case 'pending': return <Clock size={14} className="text-slate-400" aria-label="sending" />
    case 'queued': return <Clock size={14} className="text-amber-500" aria-label="queued (offline)" />
    case 'sent': return <Check size={14} className="text-slate-400" aria-label="sent" />
    case 'delivered': return <CheckCheck size={14} className="text-slate-400" aria-label="delivered" />
    case 'read': return <CheckCheck size={14} className="text-sky-500" aria-label="read" />
    case 'failed': return <AlertTriangle size={14} className="text-rose-500" aria-label="failed" />
  }
}

export function MessageBubble({ m, senderName, sharedByName, showSender, groupSize, onRetry, onEdit, onDelete }: {
  m: LocalMessage
  senderName?: string
  /** Display name of the admin who shared this message (group history share). */
  sharedByName?: string
  showSender?: boolean
  groupSize?: number
  onRetry?: () => void
  onEdit?: () => void
  onDelete?: () => void
}) {
  const [menu, setMenu] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)
  const longPress = useLongPress(() => setMenu(true))
  useEffect(() => {
    if (!menu) return
    const away = (e: PointerEvent) => { if (!menuRef.current?.contains(e.target as Node)) setMenu(false) }
    document.addEventListener('pointerdown', away)
    return () => document.removeEventListener('pointerdown', away)
  }, [menu])
  if (m.direction === 'system') {
    return (
      <div className="my-2 flex justify-center">
        <span className="inline-flex items-center gap-1.5 rounded-lg bg-amber-50 px-3 py-1 text-xs text-amber-900 shadow-sm dark:bg-slate-800 dark:text-amber-200">
          <Lock size={11} /> {m.body}
        </span>
      </div>
    )
  }
  const out = m.direction === 'out'
  const actions = !m.deleted && (onEdit || onDelete) && m.status !== 'pending' && m.status !== 'queued'
  const editable = !!onEdit && canEdit(m)
  return (
    <div className={`flex ${out ? 'justify-end' : 'justify-start'} px-3 sm:px-8`}>
      <div {...(actions ? longPress : {})} onContextMenu={actions ? (e) => { e.preventDefault(); setMenu(true) } : undefined}
        className={`group relative my-0.5 max-w-[85%] rounded-2xl px-3 py-1.5 shadow-sm sm:max-w-[65%] ${out
        ? 'rounded-br-md bg-[var(--bubble-out)] text-[var(--bubble-out-text)]'
        : 'rounded-bl-md bg-white text-slate-900 dark:bg-slate-800 dark:text-slate-100'}`}>
        {actions && (
          <button type="button" aria-label="Message options" onClick={() => setMenu((v) => !v)}
            className={`absolute right-1 top-1 rounded-full p-0.5 text-slate-500 transition hover:bg-black/5 dark:text-slate-300 ${menu ? 'opacity-100' : 'opacity-0 group-hover:opacity-100 focus:opacity-100'}`}>
            <ChevronDown size={16} />
          </button>
        )}
        {menu && (
          <div ref={menuRef} role="menu" className={`absolute top-7 z-20 w-40 rounded-xl border border-slate-200 bg-white py-1 text-sm shadow-xl dark:border-slate-700 dark:bg-slate-800 ${out ? 'right-1' : 'left-1'}`}>
            {editable && <button type="button" role="menuitem" onClick={() => { setMenu(false); onEdit!() }}
              className="flex w-full items-center gap-2 px-3 py-2 text-left text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-700"><Pencil size={14} /> Edit</button>}
            {onDelete && <button type="button" role="menuitem" onClick={() => { setMenu(false); onDelete() }}
              className="flex w-full items-center gap-2 px-3 py-2 text-left text-rose-600 hover:bg-slate-100 dark:hover:bg-slate-700"><Trash2 size={14} /> Delete</button>}
          </div>
        )}
        {showSender && !out && <div className="mb-0.5 text-xs font-semibold text-[var(--accent-text)]">{senderName}</div>}
        {m.deleted ? (
          <p className="flex items-center gap-1.5 text-sm italic text-slate-500"><Ban size={13} /> {out ? 'You deleted this message' : 'This message was deleted'}</p>
        ) : m.body !== null ? (
          <p className="whitespace-pre-wrap break-words text-[15px] leading-snug">{m.body}</p>
        ) : (
          <p className="flex items-center gap-1.5 text-sm italic text-slate-500"><Lock size={13} /> {m.error ?? 'Encrypted message'}</p>
        )}
        {m.body === null && m.error?.startsWith('Sent before this device was added') && (
          <Link to="/security" className="text-xs font-medium text-[var(--accent-text)] underline">Transfer history</Link>
        )}
        {m.sharedBy && (
          <div className="mt-0.5 flex items-center gap-1 text-[11px] italic text-slate-500 dark:text-slate-400"
            title="Shared when you joined; vouched for by this admin, not by the original sender">
            <History size={11} /> shared by {sharedByName ?? 'an admin'}
          </div>
        )}
        <div className="mt-0.5 flex items-center justify-end gap-1 text-[11px] text-slate-500 dark:text-slate-400">
          {groupSize !== undefined && out && !['pending', 'queued', 'failed'].includes(m.status) && groupSize > 1 && (
            <span title="read by">{m.readBy.length}/{groupSize} read ·</span>
          )}
          {m.expiresAt && <Timer size={11} aria-label="disappearing message" />}
          {m.editedAt && !m.deleted && <span className="italic">edited ·</span>}
          <span>{timeOf(m.sentAt)}</span>
          {out && <StatusIcon m={m} />}
        </div>
        {m.status === 'queued' && <div className="mt-1 text-xs text-amber-600">Offline — will send when reconnected</div>}
        {m.status === 'failed' && (
          <div className="mt-1 flex items-center gap-2 text-xs text-rose-600">
            <span>{m.error}</span>
            {onRetry && <button onClick={onRetry} className="inline-flex items-center gap-1 font-medium underline"><RotateCw size={12} /> Retry</button>}
          </div>
        )}
      </div>
    </div>
  )
}
