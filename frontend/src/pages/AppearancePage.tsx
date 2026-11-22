import { Check, Monitor, Moon, Sun } from 'lucide-react'
import type { CSSProperties } from 'react'
import { cssVars, isDark, PATTERNS, paletteOf, setAppearance, THEMES, useAppearance, type Mode, type Pattern } from '../lib/theme'
import { PageShell } from './ProfilePage'

const MODES: { id: Mode; name: string; icon: typeof Sun }[] = [
  { id: 'system', name: 'System', icon: Monitor }, { id: 'light', name: 'Light', icon: Sun }, { id: 'dark', name: 'Dark', icon: Moon },
]

/** Chat theme settings: light/dark mode, colour theme and wallpaper pattern (saved on this device only). */
export function AppearancePage() {
  const a = useAppearance()
  const dark = isDark(a)

  return (
    <PageShell title="Chat theme">
      <Preview style={cssVars(paletteOf(a))} pattern={a.pattern} />

      <Section title="Appearance">
        <div className="grid grid-cols-3 gap-2" role="radiogroup" aria-label="Appearance">
          {MODES.map(({ id, name, icon: Icon }) => (
            <button key={id} type="button" role="radio" aria-checked={a.mode === id} onClick={() => setAppearance({ mode: id })}
              className={`flex flex-col items-center gap-1 rounded-xl border px-3 py-3 text-sm transition ${a.mode === id
                ? 'border-[var(--accent)] bg-[var(--accent)]/10 text-slate-900 dark:text-slate-100'
                : 'border-slate-200 text-slate-600 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800'}`}>
              <Icon size={18} /> {name}
            </button>
          ))}
        </div>
      </Section>

      <Section title="Theme">
        <div className="grid grid-cols-3 gap-3" role="radiogroup" aria-label="Chat theme">
          {THEMES.map((t) => {
            const selected = a.theme === t.id
            const p = dark ? t.dark : t.light
            return (
              <button key={t.id} type="button" role="radio" aria-checked={selected} aria-label={t.name} onClick={() => setAppearance({ theme: t.id })}
                className={`overflow-hidden rounded-xl border-2 text-left transition ${selected ? 'border-[var(--accent)]' : 'border-transparent hover:border-slate-300 dark:hover:border-slate-600'}`}>
                <div className="chat-wallpaper relative h-20 p-2" data-pattern={a.pattern} style={cssVars(p) as CSSProperties}>
                  <div className="mb-1 h-3 w-3/5 rounded-full bg-white/90 dark:bg-slate-700" />
                  <div className="ml-auto h-3 w-1/2 rounded-full" style={{ background: p.out }} />
                  <div className="absolute bottom-2 right-2 h-5 w-5 rounded-full" style={{ background: p.accent }} />
                  {selected && <Check size={16} className="absolute left-2 top-2 rounded-full bg-white p-0.5 text-slate-900" />}
                </div>
                <div className="bg-white px-2 py-1 text-xs font-medium text-slate-700 dark:bg-slate-800 dark:text-slate-200">{t.name}</div>
              </button>
            )
          })}
        </div>
      </Section>

      <Section title="Wallpaper pattern">
        <div className="grid grid-cols-4 gap-2" role="radiogroup" aria-label="Wallpaper pattern">
          {PATTERNS.map((pt) => (
            <button key={pt.id} type="button" role="radio" aria-checked={a.pattern === pt.id} onClick={() => setAppearance({ pattern: pt.id as Pattern })}
              className={`overflow-hidden rounded-xl border-2 transition ${a.pattern === pt.id ? 'border-[var(--accent)]' : 'border-transparent hover:border-slate-300 dark:hover:border-slate-600'}`}>
              <div className="chat-wallpaper h-12" data-pattern={pt.id} />
              <div className="bg-white px-2 py-1 text-xs font-medium text-slate-700 dark:bg-slate-800 dark:text-slate-200">{pt.name}</div>
            </button>
          ))}
        </div>
      </Section>

      <p className="mt-6 text-xs text-slate-500">Saved on this device only; your theme is never sent to the server.</p>
    </PageShell>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="mt-6">
      <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">{title}</h2>
      {children}
    </section>
  )
}

/** A miniature conversation using the current theme. */
function Preview({ style, pattern }: { style: Record<string, string>; pattern: Pattern }) {
  return (
    <div className="overflow-hidden rounded-xl border border-slate-200 dark:border-slate-800" aria-label="Theme preview">
      <div className="chat-wallpaper space-y-1.5 p-4" data-pattern={pattern} style={style as CSSProperties}>
        <div className="max-w-[70%] rounded-2xl rounded-bl-md bg-white px-3 py-1.5 text-sm text-slate-900 shadow-sm dark:bg-slate-800 dark:text-slate-100">
          <div className="text-xs font-semibold text-[var(--accent-text)]">Alex</div>
          Are we still on for tomorrow?
        </div>
        <div className="ml-auto w-fit max-w-[70%] rounded-2xl rounded-br-md bg-[var(--bubble-out)] px-3 py-1.5 text-sm text-[var(--bubble-out-text)] shadow-sm">
          Yes! See you at 10 🙂
        </div>
        <div className="flex justify-end pt-1">
          <span className="rounded-full bg-[var(--accent)] px-3 py-1 text-xs font-medium text-white">Send</span>
        </div>
      </div>
    </div>
  )
}
