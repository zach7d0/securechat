import { ChevronLeft, ChevronRight, Info, Lock, MessageSquarePlus, Pencil, Settings, ShieldCheck, Smile, Timer, Users, X } from 'lucide-react'
import { useCallback, useEffect, useLayoutEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { endTour, onboardingEnabled, setTourPrefs, shouldAutoStart, startTour, tourPrefs, tourRunning, useOnboarding } from '../lib/onboarding'
import { useApp } from '../state/app'

/** Opens the sidebar's "New chat" / "New group" dialog from anywhere (the tour's last step). */
export const OPEN_DIALOG_EVENT = 'securechat:open-dialog'
export const openSidebarDialog = (which: 'chat' | 'group') => window.dispatchEvent(new CustomEvent(OPEN_DIALOG_EVENT, { detail: which }))

interface Step {
  /** `data-tour` value of the element to highlight; none = centred card. */
  target?: string
  title: string
  body: ReactNode
}

const Tip = ({ icon, children }: { icon: ReactNode; children: ReactNode }) => (
  <li className="flex gap-2"><span className="mt-0.5 shrink-0 text-[var(--accent)]">{icon}</span><span>{children}</span></li>
)

function steps(name: string, origin: string): Step[] {
  return [
    {
      title: `Welcome to SecureChat, ${name}! 👋`,
      body: <>
        <p>Everything you send is <b>end-to-end encrypted</b>: it is locked on your device and only the people in the chat can
          unlock it. Not even the server can read it.</p>
        <p className="mt-2">This short tour shows you how to start a chat, create a group and use the main features.</p>
      </>,
    },
    {
      target: 'new-chat',
      title: 'Start a one-to-one chat',
      body: <>
        <p>Click <MessageSquarePlus size={14} className="inline align-text-bottom" /> and search for a person by their <b>username</b>,
          for example <b>alice</b>, then pick them and say hi.</p>
        <p className="mt-2"><b>Your friend needs an account first.</b> Ask them to sign up at
          <span className="my-1 block whitespace-nowrap font-mono text-[11px] text-slate-700 dark:text-slate-200">{origin}/register</span>
          and tell you their username. Or ask <b>alice</b> to start the chat with you: it appears in your list automatically.</p>
      </>,
    },
    {
      target: 'new-group',
      title: 'Create a group',
      body: <>
        <p>Click <Users size={14} className="inline align-text-bottom" />, give the group a name and add people (for example <b>alice</b> and <b>bob</b>).</p>
        <p className="mt-2">You become the <b>owner</b>: you can invite people as admin or member, choose how much earlier history new members
          see, rename the group and remove members. Group messages are encrypted with MLS.</p>
      </>,
    },
    {
      target: 'chat-list',
      title: 'Your chats',
      body: <>
        <p>All your chats are listed here, newest first, with unread counts. When <b>alice</b> messages you or adds you to a group, it
          shows up here on its own.</p>
        <p className="mt-2">Ticks show your message status: ✓ sent, ✓✓ delivered, <span className="text-sky-500">✓✓</span> read.</p>
      </>,
    },
    {
      title: 'Inside a chat',
      body: <ul className="space-y-1.5">
        <Tip icon={<Smile size={15} />}>Add emoji with the smiley next to the message box.</Tip>
        <Tip icon={<Pencil size={15} />}>Hover over a message and click <b>▾</b> (or long-press on a phone) to <b>edit</b> it within 1 hour or
          <b> delete</b> it: for everyone within 24 hours, for you any time.</Tip>
        <Tip icon={<Timer size={15} />}>Turn on <b>disappearing messages</b> with ⏱ in the chat header: 5 minutes up to 30 days.</Tip>
        <Tip icon={<ShieldCheck size={15} />}>Compare <b>safety numbers</b> with the shield to make sure you are talking to the right person.</Tip>
        <Tip icon={<Info size={15} />}>In groups, ⓘ opens group info: members, roles, invite and rename.</Tip>
      </ul>,
    },
    {
      target: 'menu',
      title: 'Settings',
      body: <>
        <p>Open <Settings size={14} className="inline align-text-bottom" /> for your <b>profile</b>, <b>devices & security</b> (revoke a
          lost device, change password), <b>chat theme</b> and <b>Help & tour</b>.</p>
        <p className="mt-2">You can start this tour again any time from <b>Help & tour</b>.</p>
      </>,
    },
    {
      title: "You're all set 🎉",
      body: <p>Start your first conversation now. Remember: your friend needs a SecureChat account, so share the sign-up link with
        them, or ask <b>alice</b> to invite you.</p>,
    },
  ]
}

/** Measures the highlighted element (re-measured on resize/scroll). */
function useTargetRect(target: string | undefined, step: number) {
  const [rect, setRect] = useState<DOMRect | null>(null)
  useLayoutEffect(() => {
    const measure = () => {
      const el = target ? document.querySelector<HTMLElement>(`[data-tour="${target}"]`) : null
      const r = el?.getBoundingClientRect()
      setRect(r && r.width > 0 && r.height > 0 ? r : null)   // hidden (e.g. phone layout) → centred card
    }
    measure()
    window.addEventListener('resize', measure)
    window.addEventListener('scroll', measure, true)
    return () => { window.removeEventListener('resize', measure); window.removeEventListener('scroll', measure, true) }
  }, [target, step])
  return rect
}

/** Starts the tour for new users and renders it while it runs. Mounted once inside the signed-in layout. */
export function OnboardingTour() {
  useOnboarding()
  const session = useApp((s) => s.session)
  const navigate = useNavigate()
  const [i, setI] = useState(0)
  const userId = session?.user.userId

  // auto-start: once the conversation list has loaded, only for someone new
  useEffect(() => {
    if (!session || !onboardingEnabled() || tourRunning()) return
    const t = setTimeout(() => {
      if (shouldAutoStart(tourPrefs(session.user.userId), session.engine.conversations().length)) {
        navigate('/')
        startTour()
      }
    }, 1200)
    return () => clearTimeout(t)
  }, [session, navigate])

  useEffect(() => { if (tourRunning()) setI(0) }, [tourRunning()])  // eslint-disable-line react-hooks/exhaustive-deps

  const list = steps(session?.user.displayName ?? 'there', window.location.origin)
  const step = list[Math.min(i, list.length - 1)]
  const rect = useTargetRect(tourRunning() ? step.target : undefined, i)
  const finish = useCallback(() => { if (userId) endTour(userId) }, [userId])

  useEffect(() => {
    if (!tourRunning()) return
    const keys = (e: KeyboardEvent) => {
      if (e.key === 'Escape') finish()
      if (e.key === 'ArrowRight') setI((n) => Math.min(n + 1, list.length - 1))
      if (e.key === 'ArrowLeft') setI((n) => Math.max(n - 1, 0))
    }
    document.addEventListener('keydown', keys)
    return () => document.removeEventListener('keydown', keys)
  }, [finish, list.length, tourRunning()])  // eslint-disable-line react-hooks/exhaustive-deps

  if (!session || !tourRunning()) return null
  const last = i === list.length - 1
  const pad = 6
  // card next to the highlighted element: below it, else above it, else beside it (tall targets); no target → centred
  const W = window.innerWidth, H = window.innerHeight, CARD = 340, ROOM = 320
  const clampX = (x: number) => Math.max(12, Math.min(x, W - CARD - 12))
  const cardStyle: React.CSSProperties = !rect ? {}
    : rect.bottom + ROOM < H ? { position: 'fixed', left: clampX(rect.left + rect.width / 2 - CARD / 2), top: rect.bottom + pad + 10 }
    : rect.top - ROOM > 0 ? { position: 'fixed', left: clampX(rect.left + rect.width / 2 - CARD / 2), bottom: H - rect.top + pad + 10 }
    : { position: 'fixed', left: rect.right + pad + 16 + CARD < W ? rect.right + pad + 16 : clampX(rect.left + 16),
        top: Math.max(12, Math.min(rect.top + 24, H - ROOM)) }

  return (
    <div className="fixed inset-0 z-[60]" role="dialog" aria-modal="true" aria-label="Welcome tour" data-testid="onboarding-tour">
      {rect ? (
        <div className="pointer-events-none fixed rounded-xl ring-2 ring-[var(--accent)] transition-all duration-200"
          style={{ left: rect.left - pad, top: rect.top - pad, width: rect.width + pad * 2, height: rect.height + pad * 2,
            boxShadow: '0 0 0 9999px rgba(15, 23, 42, 0.6)' }} />
      ) : <div className="fixed inset-0 bg-slate-900/60" />}
      <div className={rect ? '' : 'fixed inset-0 flex items-center justify-center p-4'}>
        <div style={cardStyle} className="w-[340px] max-w-[calc(100vw-24px)] rounded-2xl bg-white p-5 text-sm text-slate-600 shadow-2xl dark:bg-slate-800 dark:text-slate-300">
          <div className="flex items-start justify-between gap-2">
            <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100">{step.title}</h3>
            <button type="button" onClick={finish} aria-label="Close tour" className="rounded-full p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700"><X size={16} /></button>
          </div>
          <div className="mt-2 leading-relaxed">{step.body}</div>
          {last && (
            <div className="mt-4 grid grid-cols-2 gap-2">
              <button type="button" onClick={() => { finish(); openSidebarDialog('chat') }}
                className="inline-flex items-center justify-center gap-1.5 rounded-lg bg-[var(--accent)] px-3 py-2 font-medium text-white hover:opacity-90"><MessageSquarePlus size={15} /> Start a chat</button>
              <button type="button" onClick={() => { finish(); openSidebarDialog('group') }}
                className="inline-flex items-center justify-center gap-1.5 rounded-lg border border-slate-300 px-3 py-2 font-medium text-slate-700 hover:bg-slate-50 dark:border-slate-600 dark:text-slate-200 dark:hover:bg-slate-700"><Users size={15} /> Create a group</button>
            </div>
          )}
          <div className="mt-4 flex items-center justify-between">
            <div className="flex gap-1" aria-label={`Step ${i + 1} of ${list.length}`}>
              {list.map((_, n) => <span key={n} className={`h-1.5 rounded-full transition-all ${n === i ? 'w-4 bg-[var(--accent)]' : 'w-1.5 bg-slate-300 dark:bg-slate-600'}`} />)}
            </div>
            <div className="flex items-center gap-1">
              {i === 0 && <button type="button" onClick={finish} className="rounded-lg px-2.5 py-1.5 text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">Skip</button>}
              {i > 0 && <button type="button" onClick={() => setI(i - 1)} className="inline-flex items-center rounded-lg px-2 py-1.5 text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-700"><ChevronLeft size={15} /> Back</button>}
              {last
                ? <button type="button" onClick={finish} className="rounded-lg px-3 py-1.5 font-medium text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-700">Finish</button>
                : <button type="button" onClick={() => setI(i + 1)} className="inline-flex items-center rounded-lg bg-[var(--accent)] px-3 py-1.5 font-medium text-white hover:opacity-90">{i === 0 ? 'Start tour' : 'Next'} <ChevronRight size={15} /></button>}
            </div>
          </div>
          <p className="mt-3 flex items-center gap-1 text-[11px] text-slate-400"><Lock size={10} /> Restart any time: ⚙ → Help & tour</p>
        </div>
      </div>
    </div>
  )
}

/** Help & tour settings page content (restart, automatic start). */
export function restartTour(userId: string, navigate: (to: string) => void) {
  setTourPrefs(userId, { done: false })
  navigate('/')
  startTour()
}
