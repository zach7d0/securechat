import { ShieldCheck } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import type { Profile } from '../lib/api/types'
import { groupDigits } from '../lib/format'
import type { SafetyNumberView } from '../lib/messaging/engine'
import { useApp } from '../state/app'
import { UserPicker } from './UserPicker'
import { Avatar, Button, ErrorText, Modal } from './ui'

export function NewChatDialog({ onClose }: { onClose: () => void }) {
  const engine = useApp((s) => s.session!.engine)
  const me = useApp((s) => s.session!.user)
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  return (
    <Modal title="New chat" onClose={onClose}>
      <UserPicker exclude={[me.userId]} onPick={async (u) => {
        try {
          engine.rememberProfile(u)
          const c = await engine.openDirect(u.userId)
          onClose()
          navigate(`/c/${c.id}`)
        } catch (e) {
          setError(e instanceof Error ? e.message : 'Could not start chat')
        }
      }} />
      <div className="mt-3"><ErrorText>{error}</ErrorText></div>
    </Modal>
  )
}

export function CreateGroupDialog({ onClose }: { onClose: () => void }) {
  const engine = useApp((s) => s.session!.engine)
  const me = useApp((s) => s.session!.user)
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [members, setMembers] = useState<Profile[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const toggle = (u: Profile) => setMembers((m) => (m.some((x) => x.userId === u.userId) ? m.filter((x) => x.userId !== u.userId) : [...m, u]))

  return (
    <Modal title="Create group" onClose={onClose}>
      <div className="space-y-3">
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={64} placeholder="Group name"
          className="w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 text-sm text-slate-900 outline-none focus:border-emerald-500 dark:border-slate-700 dark:text-slate-100" />
        <input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={256} placeholder="Description (optional)"
          className="w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 text-sm text-slate-900 outline-none focus:border-emerald-500 dark:border-slate-700 dark:text-slate-100" />
        {members.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {members.map((m) => (
              <button key={m.userId} onClick={() => toggle(m)} className="rounded-full bg-emerald-100 px-3 py-1 text-xs text-emerald-800 dark:bg-emerald-900/50 dark:text-emerald-200">{m.displayName} ✕</button>
            ))}
          </div>
        )}
        <UserPicker multi exclude={[me.userId]} selected={members.map((m) => m.userId)} onPick={toggle} />
        <p className="text-xs text-slate-500">Group messages use MLS. Keys rotate whenever members join or leave, so removed members cannot read new messages.</p>
        <ErrorText>{error}</ErrorText>
        <Button className="w-full" disabled={!name.trim() || busy} onClick={async () => {
          setBusy(true)
          setError(null)
          try {
            members.forEach((m) => engine.rememberProfile(m))
            const c = await engine.createGroup(name.trim(), members.map((m) => m.userId), description.trim() || undefined)
            onClose()
            navigate(`/c/${c.id}`)
          } catch (e) {
            setError(e instanceof Error ? e.message : 'Could not create group')
          } finally {
            setBusy(false)
          }
        }}>{busy ? 'Creating…' : `Create group${members.length ? ` with ${members.length}` : ''}`}</Button>
      </div>
    </Modal>
  )
}

export function SafetyNumberDialog({ conversationId, onClose }: { conversationId: string; onClose: () => void }) {
  const engine = useApp((s) => s.session!.engine)
  const [items, setItems] = useState<SafetyNumberView[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const warning = engine.conversation(conversationId)?.identityWarning
  const load = () => engine.safetyNumbers(conversationId).then(setItems).catch((e) => setError(String(e)))
  useEffect(() => {
    void load()
  }, [conversationId]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <Modal title="Verify security" onClose={onClose} wide>
      <p className="mb-4 text-sm text-slate-600 dark:text-slate-400">
        Compare these numbers with your contacts in person or over a trusted channel. If they match, nobody is intercepting
        your end-to-end encrypted messages. Each device has its own number.
      </p>
      {warning && (
        <div className="mb-4 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950/40 dark:text-amber-200">
          A safety number in this conversation changed. This happens when someone reinstalls or switches devices — or when
          someone is attempting to intercept messages. Verify before accepting.
          <div className="mt-2"><Button variant="primary" onClick={async () => { await engine.approveNewIdentities(conversationId); await load() }}>Accept new safety numbers</Button></div>
        </div>
      )}
      <ErrorText>{error}</ErrorText>
      {!items && <p className="text-sm text-slate-500">Loading…</p>}
      <ul className="space-y-4">
        {items?.map((s) => (
          <li key={s.deviceId} className="rounded-xl border border-slate-200 p-4 dark:border-slate-800">
            <div className="mb-3 flex items-center gap-3">
              <Avatar name={s.displayName} size={32} />
              <div className="flex-1">
                <div className="text-sm font-medium text-slate-900 dark:text-slate-100">{s.displayName}</div>
                <div className="font-mono text-[11px] text-slate-500">device {s.deviceId.slice(0, 8)}</div>
              </div>
              {s.verified ? (
                <span className="inline-flex items-center gap-1 text-sm font-medium text-emerald-600"><ShieldCheck size={16} /> Verified</span>
              ) : (
                <Button variant="ghost" onClick={async () => { await engine.markVerified(s.deviceId, s.userId); await load() }}>Mark as verified</Button>
              )}
            </div>
            <div className="grid grid-cols-4 gap-2 font-mono text-sm tracking-wider text-slate-800 sm:grid-cols-6 dark:text-slate-200">
              {groupDigits(s.safetyNumber).map((g, i) => <span key={i}>{g}</span>)}
            </div>
          </li>
        ))}
        {items?.length === 0 && <li className="text-sm text-slate-500">No other devices yet.</li>}
      </ul>
    </Modal>
  )
}
