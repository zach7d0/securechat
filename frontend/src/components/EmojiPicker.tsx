import { Clock, Search, X } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { EMOJI_CATEGORIES, loadRecentEmoji, pushRecentEmoji, searchEmoji } from '../lib/emoji'

/**
 * WhatsApp-style emoji panel that opens above the composer: category tabs, search, and a
 * "Recent" row kept in this browser's localStorage. Stays open so several emoji can be added;
 * closes on Escape, on outside click, or via the toggle button.
 */
export function EmojiPicker({ onPick, onClose, anchor }: {
  onPick: (emoji: string) => void
  onClose: () => void
  /** The toggle button: clicks on it are not treated as "outside" (it toggles itself). */
  anchor: React.RefObject<HTMLElement | null>
}) {
  const [query, setQuery] = useState('')
  const [recent, setRecent] = useState(loadRecentEmoji)
  const [active, setActive] = useState(recent.length ? 'recent' : EMOJI_CATEGORIES[0].id)
  const panel = useRef<HTMLDivElement>(null)
  const scroller = useRef<HTMLDivElement>(null)
  const sections = useRef<Record<string, HTMLElement | null>>({})

  useEffect(() => {
    const onDown = (e: PointerEvent) => {
      const t = e.target as Node
      if (!panel.current?.contains(t) && !anchor.current?.contains(t)) onClose()
    }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('pointerdown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [onClose, anchor])

  const results = useMemo(() => searchEmoji(query), [query])

  const pick = (c: string) => {
    setRecent(pushRecentEmoji(c))
    onPick(c)
  }

  const jump = (id: string) => {
    setQuery('')
    setActive(id)
    requestAnimationFrame(() => sections.current[id]?.scrollIntoView({ block: 'start' }))
  }

  // highlight the tab of the section currently at the top of the scroll area
  const onScroll = () => {
    const top = scroller.current?.getBoundingClientRect().top ?? 0
    let current = active
    for (const [id, el] of Object.entries(sections.current)) {
      if (el && el.getBoundingClientRect().top - top <= 8) current = id
    }
    if (current !== active) setActive(current)
  }

  const tabs = [...(recent.length ? [{ id: 'recent', label: 'Recent', icon: '' }] : []), ...EMOJI_CATEGORIES]

  return (
    <div ref={panel} role="dialog" aria-label="Emoji picker"
      className="absolute bottom-full left-2 z-20 mb-2 flex h-[360px] w-[min(352px,calc(100vw-1rem))] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xl dark:border-slate-700 dark:bg-slate-800">
      <div className="flex items-center gap-0.5 border-b border-slate-200 px-1.5 pt-1.5 dark:border-slate-700">
        {tabs.map((t) => (
          <button key={t.id} type="button" title={t.label} aria-label={t.label} onClick={() => jump(t.id)}
            className={`flex h-8 flex-1 items-center justify-center rounded-t-md border-b-2 text-lg transition ${active === t.id && !query
              ? 'border-emerald-500' : 'border-transparent opacity-60 hover:opacity-100'}`}>
            {t.id === 'recent' ? <Clock size={16} className="text-slate-500 dark:text-slate-300" /> : t.icon}
          </button>
        ))}
      </div>
      <div className="relative px-2 py-2">
        <Search size={14} className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-slate-400" />
        <input autoFocus={!globalThis.matchMedia?.('(pointer: coarse)').matches} value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search emoji" aria-label="Search emoji"
          className="w-full rounded-full border border-slate-200 bg-slate-50 py-1.5 pl-8 pr-8 text-sm text-slate-900 outline-none focus:border-emerald-500 dark:border-slate-600 dark:bg-slate-900 dark:text-slate-100" />
        {query && (
          <button type="button" aria-label="Clear search" onClick={() => setQuery('')}
            className="absolute right-4 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"><X size={14} /></button>
        )}
      </div>
      <div ref={scroller} onScroll={onScroll} className="flex-1 overflow-y-auto px-2 pb-2">
        {query ? (
          results.length ? <Grid emojis={results} onPick={pick} />
            : <p className="py-8 text-center text-sm text-slate-500">No emoji found</p>
        ) : (
          <>
            {recent.length > 0 && (
              <section ref={(el) => { sections.current.recent = el }}>
                <Heading>Recent</Heading>
                <Grid emojis={recent} onPick={pick} />
              </section>
            )}
            {EMOJI_CATEGORIES.map((c) => (
              <section key={c.id} ref={(el) => { sections.current[c.id] = el }}>
                <Heading>{c.label}</Heading>
                <Grid emojis={c.emojis.map((e) => e.char)} onPick={pick} />
              </section>
            ))}
          </>
        )}
      </div>
    </div>
  )
}

function Heading({ children }: { children: React.ReactNode }) {
  return <h3 className="sticky top-0 z-10 bg-white/95 px-1 py-1 text-xs font-medium text-slate-500 dark:bg-slate-800/95 dark:text-slate-400">{children}</h3>
}

function Grid({ emojis, onPick }: { emojis: string[]; onPick: (e: string) => void }) {
  return (
    <div className="grid grid-cols-8 gap-0.5">
      {emojis.map((e, i) => (
        <button key={`${e}-${i}`} type="button" onClick={() => onPick(e)} aria-label={e}
          className="flex aspect-square items-center justify-center rounded-lg text-[22px] leading-none transition hover:bg-slate-100 dark:hover:bg-slate-700">
          {e}
        </button>
      ))}
    </div>
  )
}
