import { Check, Timer, Trash2, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import type { LocalMessage } from '../lib/messaging/engine'
import { canDeleteForEveryone, DISAPPEARING_OPTIONS } from '../lib/messaging/rules'

/** Popover listing the disappearing-message timers (anchored to the header button). */
export function DisappearingPicker({ current, onPick, onClose }: {
  current: number | null | undefined
  onPick: (seconds: number) => void
  onClose: () => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const away = (e: PointerEvent) => { if (!ref.current?.contains(e.target as Node)) onClose() }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('pointerdown', away)
    document.addEventListener('keydown', esc)
    return () => { document.removeEventListener('pointerdown', away); document.removeEventListener('keydown', esc) }
  }, [onClose])
  const options = [{ seconds: 0, label: 'Off' }, ...DISAPPEARING_OPTIONS]
  return (
    <div ref={ref} role="dialog" aria-label="Disappearing messages"
      className="absolute right-2 top-full z-30 mt-1 w-64 rounded-xl border border-slate-200 bg-white p-2 shadow-xl dark:border-slate-700 dark:bg-slate-800">
      <div className="flex items-center gap-2 px-2 pb-1 pt-1 text-sm font-semibold text-slate-900 dark:text-slate-100"><Timer size={16} /> Disappearing messages</div>
      <p className="px-2 pb-2 text-xs text-slate-500">New messages disappear from every device and the server this long after they are sent. Everyone in the chat is told.</p>
      {options.map((o) => {
        const on = (current ?? 0) === o.seconds
        return (
          <button key={o.seconds} type="button" role="radio" aria-checked={on} onClick={() => onPick(o.seconds)}
            className="flex w-full items-center justify-between rounded-lg px-2 py-1.5 text-left text-sm text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-700">
            {o.label}{on && <Check size={15} className="text-[var(--accent)]" />}
          </button>
        )
      })}
    </div>
  )
}

/** "Delete message?" dialog — for everyone only for your own messages within 24 h. */
export function DeleteDialog({ m, onEveryone, onMe, onClose }: {
  m: LocalMessage
  onEveryone: () => void
  onMe: () => void
  onClose: () => void
}) {
  const everyone = canDeleteForEveryone(m)
  useEffect(() => {
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', esc)
    return () => document.removeEventListener('keydown', esc)
  }, [onClose])
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div role="dialog" aria-label="Delete message" onClick={(e) => e.stopPropagation()}
        className="w-full max-w-sm rounded-2xl bg-white p-5 shadow-2xl dark:bg-slate-800">
        <h3 className="flex items-center gap-2 text-base font-semibold text-slate-900 dark:text-slate-100"><Trash2 size={18} /> Delete message?</h3>
        <p className="mt-1 text-sm text-slate-500">
          {everyone ? 'Delete for everyone removes it from every device and the server.'
            : m.direction === 'out' ? 'Messages can be deleted for everyone only within 24 hours of sending.' : 'This removes it from this device only.'}
        </p>
        <div className="mt-4 flex flex-col gap-2">
          {everyone && <button type="button" onClick={onEveryone} className="rounded-lg bg-rose-600 px-3 py-2 text-sm font-medium text-white hover:bg-rose-700">Delete for everyone</button>}
          <button type="button" onClick={onMe} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 dark:border-slate-600 dark:text-slate-200 dark:hover:bg-slate-700">Delete for me</button>
          <button type="button" onClick={onClose} className="inline-flex items-center justify-center gap-1 rounded-lg px-3 py-2 text-sm text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700"><X size={14} /> Cancel</button>
        </div>
      </div>
    </div>
  )
}

/** Small hover / long-press menu on a message bubble. */
export function useLongPress(onLong: () => void, ms = 500) {
  const [timer, setTimer] = useState<ReturnType<typeof setTimeout> | null>(null)
  const start = () => setTimer(setTimeout(onLong, ms))
  const clear = () => { if (timer) clearTimeout(timer); setTimer(null) }
  return { onTouchStart: start, onTouchEnd: clear, onTouchMove: clear }
}
