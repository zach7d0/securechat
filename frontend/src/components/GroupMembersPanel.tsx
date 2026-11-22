import { Crown, KeyRound, LogOut, Pencil, Shield, ShieldMinus, ShieldPlus, UserMinus, UserPlus, X } from 'lucide-react'
import { useNavigate } from 'react-router'
import { useState } from 'react'
import { SHARE_SCOPES, type ShareScope } from '../lib/crypto/historyShare'
import type { ConversationSummary } from '../lib/messaging/engine'
import { lastSeen } from '../lib/format'
import { useApp, useEngineTick } from '../state/app'
import { UserPicker } from './UserPicker'
import { Avatar, Button, ErrorText } from './ui'

export function GroupMembersPanel({ conv, onClose, startAdding = false }: { conv: ConversationSummary; onClose: () => void; startAdding?: boolean }) {
  useEngineTick()
  const engine = useApp((s) => s.session!.engine)
  const me = useApp((s) => s.session!.user)
  const [adding, setAdding] = useState(startAdding)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [share, setShare] = useState<ShareScope>('none')
  const [notice, setNotice] = useState<string | null>(null)
  const isAdmin = conv.myRole === 'OWNER' || conv.myRole === 'ADMIN'
  const isOwner = conv.myRole === 'OWNER'
  const [inviteRole, setInviteRole] = useState<'MEMBER' | 'ADMIN'>('MEMBER')
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(conv.title)
  const [description, setDescription] = useState(conv.group?.description ?? '')
  const navigate = useNavigate()

  const run = async (key: string, fn: () => Promise<void>) => {
    setBusy(key)
    setError(null)
    try {
      await fn()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Operation failed')
    } finally {
      setBusy(null)
    }
  }

  return (
    <aside className="absolute inset-0 z-20 flex flex-col border-l border-slate-200 bg-white md:static md:w-80 dark:border-slate-800 dark:bg-slate-900">
      <header className="flex items-center justify-between border-b border-slate-200 px-4 py-3 dark:border-slate-800">
        <div>
          <h3 className="font-semibold text-slate-900 dark:text-slate-100">Group info</h3>
          <p className="text-xs text-slate-500">{conv.members.length} members · MLS epoch {conv.group?.epoch}</p>
        </div>
        <button onClick={onClose} className="rounded-full p-1.5 text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-800" aria-label="Close"><X size={18} /></button>
      </header>
      <div className="flex-1 overflow-y-auto p-4">
        <div className="mb-4 flex flex-col items-center text-center">
          <Avatar name={conv.title} size={72} group />
          {editing ? (
            <form className="mt-2 w-full space-y-2 text-left" onSubmit={(e) => {
              e.preventDefault()
              void run('edit', async () => {
                await engine.updateGroupInfo(conv.id, { name: name.trim(), description: description.trim() })
                setEditing(false)
              })
            }}>
              <input value={name} maxLength={150} required aria-label="Group name" onChange={(e) => setName(e.target.value)}
                className="w-full rounded-lg border border-slate-300 bg-transparent px-3 py-1.5 text-sm text-slate-900 outline-none focus:border-emerald-500 dark:border-slate-700 dark:text-slate-100" />
              <textarea value={description} maxLength={1000} rows={2} placeholder="Description (optional)" aria-label="Group description"
                onChange={(e) => setDescription(e.target.value)}
                className="w-full resize-none rounded-lg border border-slate-300 bg-transparent px-3 py-1.5 text-sm text-slate-900 outline-none focus:border-emerald-500 dark:border-slate-700 dark:text-slate-100" />
              <p className="text-[11px] text-slate-500">Group name and description are visible to the server (not end-to-end encrypted).</p>
              <div className="flex gap-2">
                <Button type="submit" disabled={busy !== null || !name.trim()}>Save</Button>
                <Button type="button" variant="ghost" onClick={() => { setEditing(false); setName(conv.title); setDescription(conv.group?.description ?? '') }}>Cancel</Button>
              </div>
            </form>
          ) : (
            <>
              <h4 className="mt-2 flex items-center gap-1 text-lg font-semibold text-slate-900 dark:text-slate-100">
                {conv.title}
                {isAdmin && (
                  <button type="button" onClick={() => { setName(conv.title); setDescription(conv.group?.description ?? ''); setEditing(true) }}
                    className="rounded-full p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600 dark:hover:bg-slate-800" aria-label="Edit group" title="Edit group name and description">
                    <Pencil size={14} />
                  </button>
                )}
              </h4>
              {conv.group?.description && <p className="text-sm text-slate-500">{conv.group.description}</p>}
            </>
          )}
          <p className="mt-1 text-xs text-slate-500">Your role: <RoleLabel role={conv.myRole} /></p>
          <div className="mt-4 flex justify-center gap-6">
            <button type="button" disabled={!isAdmin} aria-expanded={adding} onClick={() => setAdding((v) => !v)}
              title={isAdmin ? 'Invite a member' : 'Only group owners and admins can add members'}
              className="group flex flex-col items-center gap-1 text-xs font-medium text-emerald-700 disabled:cursor-not-allowed disabled:text-slate-400 dark:text-emerald-400">
              <span className={`flex h-12 w-12 items-center justify-center rounded-full border transition ${adding
                ? 'border-emerald-600 bg-emerald-600 text-white'
                : 'border-slate-200 bg-white group-enabled:group-hover:bg-emerald-50 dark:border-slate-700 dark:bg-slate-800'}`}>
                <UserPlus size={20} />
              </span>
              Add member
            </button>
          </div>
          {!isAdmin && <p className="mt-2 text-xs text-slate-500">Only group owners and admins can add members.</p>}
        </div>
        <ErrorText>{error}</ErrorText>
        {notice && <p className="mt-2 text-sm text-emerald-600">{notice}</p>}
        {isAdmin && (
          <div className="my-3">
            {adding ? (
              <div className="rounded-xl border border-slate-200 p-3 dark:border-slate-800">
                <fieldset className="mb-3 text-sm">
                  <legend className="mb-1 font-medium text-slate-700 dark:text-slate-300">Role</legend>
                  {isOwner ? (['MEMBER', 'ADMIN'] as const).map((r) => (
                    <label key={r} className="mr-4 inline-flex items-center gap-2 py-0.5 text-slate-700 dark:text-slate-300">
                      <input type="radio" name="invite-role" value={r} checked={inviteRole === r} onChange={() => setInviteRole(r)} />
                      {r === 'ADMIN' ? 'Admin' : 'Member'}
                    </label>
                  )) : <p className="text-slate-600 dark:text-slate-400">Member <span className="text-xs text-slate-500">(only the owner can add admins)</span></p>}
                </fieldset>
                <label className="mb-3 block text-sm">
                  <span className="mb-1 block font-medium text-slate-700 dark:text-slate-300">Earlier messages the new member can see</span>
                  <select value={share} onChange={(e) => setShare(e.target.value as ShareScope)} aria-label="History to share"
                    className="w-full rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-900 outline-none focus:border-emerald-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100">
                    {SHARE_SCOPES.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                  <span className="mt-1 block text-xs text-slate-500">
                    {share === 'none' ? "They will see “You can't see messages from before you joined”."
                      : 'Your device — or another member\'s, if yours doesn\'t have them — re-encrypts those messages for the group. The group is told.'}
                  </span>
                </label>
                <UserPicker exclude={conv.members.map((m) => m.userId)} onPick={(u) => run(u.userId, async () => {
                  engine.rememberProfile(u)
                  setNotice(null)
                  const r = await engine.addMembers(conv.id, [u.userId], { shareHistory: share, role: isOwner ? inviteRole : 'MEMBER' })
                  setAdding(false)
                  setShare('none')
                  setInviteRole('MEMBER')
                  if (r.shareError) setError(`Member added, but history could not be shared: ${r.shareError}`)
                  else if (share !== 'none') setNotice(r.shared > 0 ? `Shared ${r.shared} earlier message${r.shared === 1 ? '' : 's'}`
                    : r.pendingFromOthers ? "Another member's device will share the history (this device doesn't have all of it)"
                      : 'No earlier messages to share')
                })} />
                <Button variant="ghost" className="mt-2 w-full" onClick={() => setAdding(false)}>Cancel</Button>
              </div>
            ) : null}
          </div>
        )}
        <ul className="space-y-1">
          {conv.members.map((m) => {
            const p = engine.presence(m.userId)
            const self = m.userId === me.userId
            const name = engine.displayName(m.userId)
            const online = p?.status === 'ONLINE'
            return (
              <li key={m.userId} className="flex items-center gap-3 rounded-lg px-2 py-2">
                <Avatar name={name} size={36} online={self ? undefined : online} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-1 truncate text-sm font-medium text-slate-900 dark:text-slate-100">
                    {self ? 'You' : name}
                    {m.role === 'OWNER' && <Crown size={12} className="text-amber-500" aria-label="owner" />}
                    {m.role === 'ADMIN' && <Shield size={12} className="text-sky-500" aria-label="admin" />}
                  </div>
                  <div className="truncate text-xs text-slate-500">
                    <RoleLabel role={m.role} /> · @{engine.profile(m.userId)?.username ?? '…'}{!self && ` · ${online ? 'online' : lastSeen(p?.lastSeen ?? undefined)}`}
                  </div>
                </div>
                {isOwner && !self && m.role !== 'OWNER' && (
                  <button disabled={busy !== null} onClick={() => run(`role:${m.userId}`, () => engine.setMemberRole(conv.id, m.userId, m.role === 'ADMIN' ? 'MEMBER' : 'ADMIN'))}
                    className="rounded-full p-1.5 text-sky-600 hover:bg-sky-50 disabled:opacity-40 dark:hover:bg-sky-950/40"
                    title={m.role === 'ADMIN' ? 'Remove admin (make member)' : 'Make admin'} aria-label={m.role === 'ADMIN' ? `Remove admin from ${name}` : `Make ${name} admin`}>
                    {m.role === 'ADMIN' ? <ShieldMinus size={16} /> : <ShieldPlus size={16} />}
                  </button>
                )}
                {isAdmin && !self && m.role !== 'OWNER' && (isOwner || m.role === 'MEMBER') && (
                  <button disabled={busy !== null} onClick={() => run(m.userId, () => engine.removeMember(conv.id, m.userId))}
                    className="rounded-full p-1.5 text-rose-600 hover:bg-rose-50 disabled:opacity-40 dark:hover:bg-rose-950/40" title="Remove (rotates group keys)">
                    <UserMinus size={16} />
                  </button>
                )}
              </li>
            )
          })}
        </ul>
        {busy && busy !== 'edit' && !busy.startsWith('role:') && <p className="mt-3 text-center text-xs text-slate-500">Rotating group keys…</p>}
        <div className="mt-6 space-y-2 border-t border-slate-200 pt-4 dark:border-slate-800">
          <Button variant="ghost" className="w-full justify-start" disabled={busy !== null}
            onClick={() => run('rotate', () => engine.rotateGroupKeys(conv.id))}><KeyRound size={16} /> Rotate group keys</Button>
          <Button variant="ghost" className="w-full justify-start text-rose-600" disabled={busy !== null}
            onClick={() => run('leave', async () => { await engine.leaveGroup(conv.id); navigate('/') })}><LogOut size={16} /> Leave group</Button>
        </div>
      </div>
    </aside>
  )
}

function RoleLabel({ role }: { role?: string | null }) {
  if (role === 'OWNER') return <span className="font-medium text-amber-600 dark:text-amber-400">Owner</span>
  if (role === 'ADMIN') return <span className="font-medium text-sky-600 dark:text-sky-400">Admin</span>
  return <span>Member</span>
}
