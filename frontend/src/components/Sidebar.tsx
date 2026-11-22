import { Compass, Lock, LogOut, MessageSquarePlus, Palette, Search, Settings, User, Users, Wifi, WifiOff } from 'lucide-react'
import { useEffect, useState } from 'react'
import { onboardingEnabled, useOnboarding } from '../lib/onboarding'
import { OPEN_DIALOG_EVENT } from './OnboardingTour'
import { Link, NavLink, useLocation } from 'react-router'
import { shortDate } from '../lib/format'
import { useApp, useEngineTick } from '../state/app'
import { CreateGroupDialog, NewChatDialog } from './Dialogs'
import { StatusIcon } from './MessageBubble'
import { Avatar } from './ui'
import { useHistorySentCount } from './HistoryTransferPanel'

export function Sidebar() {
  useEngineTick()
  const { session, logout } = useApp()
  const engine = session!.engine
  const me = session!.user
  // On phones the sidebar is a full screen of its own: hide it whenever another page is open.
  const onHome = useLocation().pathname === '/'
  const [q, setQ] = useState('')
  const [dialog, setDialog] = useState<'chat' | 'group' | null>(null)
  const [menu, setMenu] = useState(false)
  useOnboarding()
  useEffect(() => {   // the welcome tour's "Start a chat" / "Create a group" buttons
    const open = (e: Event) => setDialog((e as CustomEvent<'chat' | 'group'>).detail)
    window.addEventListener(OPEN_DIALOG_EVENT, open)
    return () => window.removeEventListener(OPEN_DIALOG_EVENT, open)
  }, [])
  const status = engine.connectionStatus()
  const historySent = useHistorySentCount()
  const conversations = engine.conversations().filter((c) => {
    const s = q.trim().toLowerCase()
    return !s || c.title.toLowerCase().includes(s) || c.members.some((m) => (engine.profile(m.userId)?.username ?? '').includes(s))
  })

  return (
    <aside className={`${onHome ? 'flex' : 'hidden md:flex'} w-full flex-col border-r border-slate-200 bg-white md:w-[360px] dark:border-slate-800 dark:bg-slate-900`}>
      <header className="flex items-center gap-3 bg-slate-50 px-4 py-3 dark:bg-slate-900">
        <Avatar name={me.displayName} size={38} />
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-semibold text-slate-900 dark:text-slate-100">{me.displayName}</div>
          <div className={`flex items-center gap-1 text-xs ${status === 'online' ? 'text-emerald-600' : 'text-amber-600'}`}>
            {status === 'online' ? <Wifi size={12} /> : <WifiOff size={12} />}
            {status === 'online' ? 'Connected' : status === 'connecting' ? 'Connecting…' : 'Offline — reconnecting'}
          </div>
        </div>
        <button data-tour="new-chat" onClick={() => setDialog('chat')} className="rounded-full p-2 text-slate-600 hover:bg-slate-200 dark:text-slate-300 dark:hover:bg-slate-800" title="New chat" aria-label="New chat"><MessageSquarePlus size={20} /></button>
        <button data-tour="new-group" onClick={() => setDialog('group')} className="rounded-full p-2 text-slate-600 hover:bg-slate-200 dark:text-slate-300 dark:hover:bg-slate-800" title="New group" aria-label="New group"><Users size={20} /></button>
        <div className="relative">
          <button data-tour="menu" onClick={() => setMenu((m) => !m)} className="rounded-full p-2 text-slate-600 hover:bg-slate-200 dark:text-slate-300 dark:hover:bg-slate-800" aria-label="Menu"><Settings size={20} /></button>
          {menu && (
            <div className="absolute right-0 z-30 mt-1 w-48 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-lg dark:border-slate-700 dark:bg-slate-800" onMouseLeave={() => setMenu(false)}>
              <Link to="/profile" className="flex items-center gap-2 px-4 py-2.5 text-sm text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-700"><User size={16} /> Profile</Link>
              <Link to="/security" className="flex items-center gap-2 px-4 py-2.5 text-sm text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-700"><Lock size={16} /> Devices & security</Link>
              <Link to="/appearance" className="flex items-center gap-2 px-4 py-2.5 text-sm text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-700"><Palette size={16} /> Chat theme</Link>
              {onboardingEnabled() && <Link to="/help" className="flex items-center gap-2 px-4 py-2.5 text-sm text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-700"><Compass size={16} /> Help & tour</Link>}
              <button onClick={() => void logout()} className="flex w-full items-center gap-2 px-4 py-2.5 text-sm text-rose-600 hover:bg-slate-100 dark:hover:bg-slate-700"><LogOut size={16} /> Log out</button>
            </div>
          )}
        </div>
      </header>
      {historySent > 0 && (
        <Link to="/security" data-testid="transfer-banner" className="block bg-amber-100 px-4 py-2 text-xs font-medium text-amber-900 hover:bg-amber-200 dark:bg-amber-900/50 dark:text-amber-100">
          Chat history was sent to a new device — not you? Review
        </Link>
      )}
      <div className="px-3 py-2">
        <label className="flex items-center gap-2 rounded-lg bg-slate-100 px-3 py-2 dark:bg-slate-800">
          <Search size={16} className="text-slate-500" />
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search conversations"
            className="w-full bg-transparent text-sm text-slate-900 outline-none placeholder:text-slate-500 dark:text-slate-100" />
        </label>
      </div>
      <nav data-tour="chat-list" className="flex-1 overflow-y-auto">
        {conversations.length === 0 && (
          <div className="px-6 py-10 text-center text-sm text-slate-500">
            {q ? 'No matching conversations' : 'No conversations yet. Start a new chat or create a group.'}
          </div>
        )}
        {conversations.map((c) => {
          const peer = c.type === 'DIRECT' ? c.members.find((m) => m.userId !== me.userId) : undefined
          const online = peer ? engine.presence(peer.userId)?.status === 'ONLINE' : undefined
          const typing = engine.typingUsers(c.id).length > 0
          const last = c.lastMessage
          return (
            <NavLink key={c.id} to={`/c/${c.id}`}
              className={({ isActive }) => `flex items-center gap-3 px-4 py-3 transition ${isActive ? 'bg-slate-100 dark:bg-slate-800' : 'hover:bg-slate-50 dark:hover:bg-slate-800/60'}`}>
              <Avatar name={c.title} size={48} group={c.type === 'GROUP'} online={online} />
              <div className="min-w-0 flex-1 border-b border-slate-100 pb-3 dark:border-slate-800">
                <div className="flex items-baseline justify-between gap-2">
                  <span className="truncate font-medium text-slate-900 dark:text-slate-100">{c.title}</span>
                  <span className={`shrink-0 text-xs ${c.unread ? 'font-semibold text-emerald-600' : 'text-slate-500'}`}>{shortDate(last?.serverAt ?? last?.sentAt ?? c.updatedAt)}</span>
                </div>
                <div className="mt-0.5 flex items-center gap-1.5">
                  {last?.direction === 'out' && <StatusIcon m={last} />}
                  <span className={`truncate text-sm ${typing ? 'text-emerald-600' : 'text-slate-500'}`}>
                    {typing ? 'typing…' : last ? (last.deleted ? (last.direction === 'out' ? '🚫 You deleted this message' : '🚫 This message was deleted') : last.body ?? '🔒 Encrypted message') : <span className="inline-flex items-center gap-1"><Lock size={11} /> End-to-end encrypted</span>}
                  </span>
                  {c.unread > 0 && <span className="ml-auto flex h-5 min-w-5 items-center justify-center rounded-full bg-emerald-600 px-1.5 text-[11px] font-semibold text-white">{c.unread}</span>}
                </div>
              </div>
            </NavLink>
          )
        })}
      </nav>
      {dialog === 'chat' && <NewChatDialog onClose={() => setDialog(null)} />}
      {dialog === 'group' && <CreateGroupDialog onClose={() => setDialog(null)} />}
    </aside>
  )
}
