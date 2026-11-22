import { Compass, Lock, MessageSquarePlus, Pencil, ShieldCheck, Timer, Users } from 'lucide-react'
import { useNavigate } from 'react-router'
import { restartTour } from '../components/OnboardingTour'
import { onboardingEnabled, setTourPrefs, tourPrefs, useOnboarding } from '../lib/onboarding'
import { useApp } from '../state/app'
import { PageShell } from './ProfilePage'

/** Settings → Help & tour: replay the welcome tour, choose whether it starts by itself, quick how-tos. */
export function HelpPage() {
  useOnboarding()
  const me = useApp((s) => s.session!.user)
  const navigate = useNavigate()
  const prefs = tourPrefs(me.userId)
  const on = onboardingEnabled()

  return (
    <PageShell title="Help & tour">
      <section>
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">Welcome tour</h2>
        {on ? (
          <div className="rounded-xl border border-slate-200 p-4 dark:border-slate-700">
            <p className="text-sm text-slate-600 dark:text-slate-300">A short walk-through of starting a chat, creating a group and the main features.</p>
            <button type="button" onClick={() => restartTour(me.userId, navigate)} data-testid="restart-tour"
              className="mt-3 inline-flex items-center gap-2 rounded-lg bg-[var(--accent)] px-4 py-2 text-sm font-medium text-white hover:opacity-90">
              <Compass size={16} /> Start the tour again
            </button>
            <label className="mt-4 flex items-center justify-between gap-4 border-t border-slate-100 pt-4 text-sm dark:border-slate-700">
              <span>
                <span className="block font-medium text-slate-800 dark:text-slate-100">Show the tour automatically</span>
                <span className="text-xs text-slate-500">For new accounts, and on a new device before you have any chats. Saved on this device.</span>
              </span>
              <input type="checkbox" role="switch" checked={prefs.auto} aria-label="Show the tour automatically"
                onChange={(e) => setTourPrefs(me.userId, { auto: e.target.checked })} className="h-5 w-9 accent-[var(--accent)]" />
            </label>
          </div>
        ) : (
          <p className="rounded-xl border border-slate-200 p-4 text-sm text-slate-500 dark:border-slate-700">The welcome tour is turned off on this server.</p>
        )}
      </section>

      <section className="mt-6">
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">How to</h2>
        <ul className="divide-y divide-slate-100 rounded-xl border border-slate-200 text-sm text-slate-600 dark:divide-slate-700 dark:border-slate-700 dark:text-slate-300">
          {[
            [<MessageSquarePlus size={16} />, 'Start a chat', 'In the chat list click the new-chat button and search a username. Your friend must have an account first.'],
            [<Users size={16} />, 'Create a group', 'Click the group button, name the group and add people. You become the owner.'],
            [<Pencil size={16} />, 'Edit or delete a message', 'Hover a message and click ▾ (long-press on a phone). Edit within 1 hour; delete for everyone within 24 hours; delete for me any time.'],
            [<Timer size={16} />, 'Disappearing messages', 'Click ⏱ in the chat header and choose 5 minutes … 30 days. Everyone in the chat is told.'],
            [<ShieldCheck size={16} />, 'Verify a contact', 'Click the shield in the chat header and compare the safety number with your contact.'],
            [<Lock size={16} />, 'Lost a device?', 'Devices & security → revoke it. Change your password there too.'],
          ].map(([icon, title, text]) => (
            <li key={title as string} className="flex gap-3 p-3">
              <span className="mt-0.5 text-[var(--accent)]">{icon}</span>
              <span><span className="block font-medium text-slate-800 dark:text-slate-100">{title}</span>{text}</span>
            </li>
          ))}
        </ul>
      </section>
    </PageShell>
  )
}
