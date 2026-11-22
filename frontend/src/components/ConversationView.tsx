import { AlertTriangle, ArrowLeft, Check, History, Info, Keyboard, Loader2, Pencil, SendHorizontal, ShieldCheck, Smile, Timer, UserPlus, X } from 'lucide-react'
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'
import { insertAt } from '../lib/emoji'
import { dayLabel, lastSeen } from '../lib/format'
import { useApp, useEngineTick } from '../state/app'
import { SafetyNumberDialog } from './Dialogs'
import { EmojiPicker } from './EmojiPicker'
import { DeleteDialog, DisappearingPicker } from './MessageActions'
import { timerLabel } from '../lib/messaging/rules'
import type { LocalMessage } from '../lib/messaging/engine'
import { GroupMembersPanel } from './GroupMembersPanel'
import { MessageBubble } from './MessageBubble'
import { Avatar, E2EEBadge } from './ui'

/** Messages shown initially (at least), and one "Load older" step = one month. */
const MIN_VISIBLE = 60
const MONTH = 30 * 86_400_000
const shortDay = (t: number) => new Date(t).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })

export function ConversationView() {
  const { id = '' } = useParams()
  useEngineTick()
  const engine = useApp((s) => s.session!.engine)
  const me = useApp((s) => s.session!.user)
  const conv = engine.conversation(id)
  const [showInfo, setShowInfo] = useState(false)
  const [infoAdding, setInfoAdding] = useState(false)
  const [infoKey, setInfoKey] = useState(0)   // remount the panel so the header icon always opens the add form
  const [showSafety, setShowSafety] = useState(false)
  const [editing, setEditing] = useState<LocalMessage | null>(null)
  const [deleting, setDeleting] = useState<LocalMessage | null>(null)
  const [timerOpen, setTimerOpen] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const act = (f: () => Promise<void>) => { setActionError(null); void f().catch((e) => setActionError(e instanceof Error ? e.message : 'Failed')) }
  const [from, setFrom] = useState(() => Date.now() - MONTH)
  const [historyDone, setHistoryDone] = useState(false)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const scroller = useRef<HTMLDivElement>(null)
  /** scrollHeight before an older page was added: keep the reader's place instead of jumping to the bottom */
  const keepPlace = useRef<number | null>(null)
  const all = engine.messages(id)
  const inWindow = all.filter((m) => Date.parse(m.sentAt) >= from)
  const visible = inWindow.length >= MIN_VISIBLE ? inWindow : all.slice(Math.max(0, all.length - MIN_VISIBLE))
  const typing = engine.typingUsers(id)
  const older = engine.olderInfo(id)
  const syncing = engine.pendingHistorySync().length > 0

  useEffect(() => {
    setFrom(Date.now() - MONTH)
    setHistoryDone(false)
    setShowInfo(false)
    setEditing(null)
    setDeleting(null)
    setTimerOpen(false)
  }, [id, engine])

  const loadOlder = async () => {
    const el = scroller.current
    keepPlace.current = el ? el.scrollHeight - el.scrollTop : null
    const next = Math.min(from, visible.length ? Date.parse(visible[0].sentAt) : from) - MONTH
    setFrom(next)
    setLoadingOlder(true)
    try {
      const r = await engine.loadOlder(id, next)
      setHistoryDone(r.done)
    } finally {
      setLoadingOlder(false)
    }
  }

  useEffect(() => {
    if (document.visibilityState === 'visible') void engine.markRead(id)
  }, [id, all.length, engine])

  useLayoutEffect(() => {
    const el = scroller.current
    if (!el) return
    if (keepPlace.current !== null) {
      el.scrollTop = el.scrollHeight - keepPlace.current   // older messages were added above: stay where the reader was
      if (!loadingOlder) keepPlace.current = null
    } else el.scrollTop = el.scrollHeight
  }, [id, all.length, typing.length, from, loadingOlder])

  const names = useMemo(() => new Map(conv?.members.map((m) => [m.userId, engine.displayName(m.userId)]) ?? []), [conv?.members, engine])

  if (!conv) {
    return <div className="flex flex-1 items-center justify-center text-slate-500">Conversation not found</div>
  }

  const myJoin = conv.type === 'GROUP' ? Date.parse(conv.members.find((m) => m.userId === me.userId)?.joinedAt ?? '') || 0 : 0
  const joinedLater = myJoin > Date.parse(conv.createdAt) + 60_000
  const firstAfterJoin = joinedLater ? visible.findIndex((m) => m.direction !== 'system' && !m.sharedBy && Date.parse(m.sentAt) >= myJoin) : -1
  const hasShared = visible.some((m) => m.sharedBy)
  const joinNotice = hasShared ? 'Earlier messages above were shared with you when you joined'
    : "You can't see messages from before you joined"
  const peer = conv.type === 'DIRECT' ? conv.members.find((m) => m.userId !== me.userId) : undefined
  const peerPresence = peer ? engine.presence(peer.userId) : undefined
  const subtitle = typing.length > 0
    ? `${typing.map((u) => names.get(u) ?? 'someone').join(', ')} typing…`
    : peer ? (peerPresence?.status === 'ONLINE' ? 'online' : peerPresence?.status === 'UNKNOWN' ? 'status unknown' : lastSeen(peerPresence?.lastSeen ?? undefined))
      : `${conv.members.length} members`

  return (
    <div className="relative flex min-w-0 flex-1">
      <section className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center gap-3 border-b border-slate-200 bg-white px-3 py-2.5 dark:border-slate-800 dark:bg-slate-900">
          <Link to="/" className="rounded-full p-1.5 text-slate-600 hover:bg-slate-100 md:hidden dark:text-slate-300 dark:hover:bg-slate-800" aria-label="Back"><ArrowLeft size={20} /></Link>
          <button className="flex min-w-0 flex-1 items-center gap-3 text-left" onClick={() => conv.type === 'GROUP' && setShowInfo((v) => !v)}>
            <Avatar name={conv.title} size={40} group={conv.type === 'GROUP'} online={peer ? peerPresence?.status === 'ONLINE' : undefined} />
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <h2 className="truncate font-semibold text-slate-900 dark:text-slate-100">{conv.title}</h2>
                <E2EEBadge protocol={conv.protocol} />
              </div>
              <p className={`truncate text-xs ${typing.length ? 'text-[var(--accent-text)]' : 'text-slate-500'}`}>
                {subtitle}{conv.disappearingSeconds ? <span className="ml-1.5 inline-flex items-center gap-0.5 align-middle" title="Disappearing messages"><Timer size={11} /> {timerLabel(conv.disappearingSeconds)}</span> : null}
              </p>
            </div>
          </button>
          <div className="relative">
            <button onClick={() => setTimerOpen((v) => !v)} aria-label="Disappearing messages" title={`Disappearing messages: ${timerLabel(conv.disappearingSeconds)}`}
              className={`rounded-full p-2 hover:bg-slate-100 dark:hover:bg-slate-800 ${conv.disappearingSeconds ? 'text-[var(--accent)]' : 'text-slate-600 dark:text-slate-300'}`}>
              <Timer size={20} />
            </button>
            {timerOpen && <DisappearingPicker current={conv.disappearingSeconds} onClose={() => setTimerOpen(false)}
              onPick={(sec) => { setTimerOpen(false); act(() => engine.setDisappearing(id, sec)) }} />}
          </div>
          <button onClick={() => setShowSafety(true)} className={`rounded-full p-2 hover:bg-slate-100 dark:hover:bg-slate-800 ${conv.identityWarning ? 'text-amber-500' : 'text-slate-600 dark:text-slate-300'}`}
            title="Verify safety numbers" aria-label="Verify safety numbers">
            {conv.identityWarning ? <AlertTriangle size={20} /> : <ShieldCheck size={20} />}
          </button>
          {conv.type === 'GROUP' && (conv.myRole === 'OWNER' || conv.myRole === 'ADMIN') && (
            <button onClick={() => { setInfoAdding(true); setShowInfo(true); setInfoKey((k) => k + 1) }}
              className="rounded-full p-2 text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"
              title="Add member" aria-label="Add member"><UserPlus size={20} /></button>
          )}
          {conv.type === 'GROUP' && (
            <button onClick={() => { setInfoAdding(false); setShowInfo((v) => !v) }} className="rounded-full p-2 text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800" aria-label="Group info"><Info size={20} /></button>
          )}
        </header>

        {!conv.groupReady && (
          <div className="bg-sky-50 px-4 py-2 text-sm text-sky-900 dark:bg-sky-950/40 dark:text-sky-200" data-testid="group-keys-banner">
            Waiting for the group keys for this device (an online member adds it automatically)…
            {engine.joinError(id) && <div className="mt-1 text-xs text-rose-700 dark:text-rose-300">Last attempt failed: {engine.joinError(id)}</div>}
          </div>
        )}
        {conv.identityWarning && (
          <button onClick={() => setShowSafety(true)} className="bg-amber-50 px-4 py-2 text-left text-sm text-amber-900 dark:bg-amber-950/40 dark:text-amber-200">
            ⚠ A safety number changed in this conversation. Tap to verify.
          </button>
        )}

        <div ref={scroller} className="chat-wallpaper flex-1 overflow-y-auto py-3">
          <div className="mx-auto mb-3 w-fit max-w-[90%] rounded-lg bg-amber-50/90 px-3 py-1.5 text-center text-xs text-amber-900 shadow-sm dark:bg-slate-800 dark:text-amber-200">
            🔒 Messages are end-to-end encrypted with {conv.protocol === 'Signal' ? 'the Signal Protocol' : 'MLS'}. The server only sees ciphertext.
          </div>
          {(all.length > visible.length || !historyDone) && (
            <div className="mb-2 text-center">
              <button onClick={() => void loadOlder()} disabled={loadingOlder}
                className="inline-flex items-center gap-1.5 rounded-full bg-white px-3 py-1 text-xs text-slate-600 shadow-sm disabled:opacity-70 dark:bg-slate-800 dark:text-slate-300">
                {loadingOlder ? <><Loader2 size={12} className="animate-spin" /> Loading {shortDay(from)} – {shortDay(from + MONTH)}…</> : 'Load older messages'}
              </button>
            </div>
          )}
          {(syncing || (older?.syncing ?? 0) > 0) && (
            <div className="mx-auto mb-2 flex w-fit max-w-[90%] items-center gap-1.5 rounded-lg bg-sky-50/95 px-3 py-1.5 text-xs text-sky-900 shadow-sm dark:bg-slate-800 dark:text-sky-200">
              <History size={12} />
              {syncing ? 'Syncing older messages from your other device…'
                : `${older!.syncing} older message${older!.syncing === 1 ? ' is' : 's are'} only on your other devices — they sync automatically when one is online`}
            </div>
          )}
          {joinedLater && firstAfterJoin < 0 && historyDone && <JoinNotice text={joinNotice} />}
          {visible.map((m, i) => {
            const prev = visible[i - 1]
            const newDay = !prev || new Date(prev.sentAt).toDateString() !== new Date(m.sentAt).toDateString()
            return (
              <div key={m.key}>
                {i === firstAfterJoin && <JoinNotice text={joinNotice} />}
                {newDay && <div className="my-3 text-center"><span className="rounded-lg bg-white/90 px-3 py-1 text-xs text-slate-600 shadow-sm dark:bg-slate-800 dark:text-slate-300">{dayLabel(m.sentAt)}</span></div>}
                <MessageBubble m={m} senderName={names.get(m.senderUserId) ?? formerName(engine.displayName(m.senderUserId))}
                  sharedByName={m.sharedBy ? engine.displayName(m.sharedBy) : undefined}
                  showSender={conv.type === 'GROUP' && (prev?.senderUserId !== m.senderUserId || newDay || prev?.direction === 'system')}
                  groupSize={conv.type === 'GROUP' ? conv.members.length - 1 : undefined}
                  onRetry={() => void engine.retry(id, m.key)}
                  onEdit={() => setEditing(m)} onDelete={() => setDeleting(m)} />
              </div>
            )
          })}
          {typing.length > 0 && (
            <div className="px-3 sm:px-8"><span className="inline-flex gap-1 rounded-2xl bg-white px-3 py-2.5 shadow-sm dark:bg-slate-800" aria-label="typing">
              {[0, 1, 2].map((d) => <span key={d} className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-400" style={{ animationDelay: `${d * 150}ms` }} />)}
            </span></div>
          )}
        </div>
        {actionError && <div className="flex items-center justify-between bg-rose-50 px-4 py-2 text-sm text-rose-800 dark:bg-rose-950/40 dark:text-rose-200">
          {actionError}<button type="button" aria-label="Dismiss" onClick={() => setActionError(null)}><X size={14} /></button></div>}
        <Composer conversationId={id} editing={editing} onEditDone={() => setEditing(null)} onError={setActionError} />
        {deleting && <DeleteDialog m={deleting} onClose={() => setDeleting(null)}
          onEveryone={() => { const m = deleting; setDeleting(null); act(() => engine.deleteForEveryone(id, m.key)) }}
          onMe={() => { const m = deleting; setDeleting(null); act(() => engine.deleteForMe(id, m.key)) }} />}
      </section>
      {showInfo && conv.type === 'GROUP' && <GroupMembersPanel key={infoKey} conv={conv} startAdding={infoAdding} onClose={() => setShowInfo(false)} />}
      {showSafety && <SafetyNumberDialog conversationId={id} onClose={() => setShowSafety(false)} />}
    </div>
  )
}

/** A sender no longer in the group whose profile is unknown keeps the previous "Former member" label. */
const formerName = (n: string) => (n === 'Unknown user' ? 'Former member' : n)

const MAX_MESSAGE = 8000

function Composer({ conversationId, editing, onEditDone, onError }: {
  conversationId: string
  editing: LocalMessage | null
  onEditDone: () => void
  onError: (msg: string) => void
}) {
  const engine = useApp((s) => s.session!.engine)
  const [text, setText] = useState('')
  const [emojiOpen, setEmojiOpen] = useState(false)
  const input = useRef<HTMLTextAreaElement>(null)
  const emojiButton = useRef<HTMLButtonElement>(null)
  const selection = useRef({ start: 0, end: 0 })
  const typingSent = useRef(0)
  const stopTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const onChange = (v: string) => {
    setText(v)
    const now = Date.now()
    if (v && now - typingSent.current > 3000) {
      engine.sendTyping(conversationId, true)
      typingSent.current = now
    }
    if (stopTimer.current) clearTimeout(stopTimer.current)
    stopTimer.current = setTimeout(() => {
      engine.sendTyping(conversationId, false)
      typingSent.current = 0
    }, 4000)
  }

  /** Remember the caret so an emoji lands where the user was typing, even after focus moved to the picker. */
  const rememberCaret = () => {
    const el = input.current
    if (el) selection.current = { start: el.selectionStart, end: el.selectionEnd }
  }

  const addEmoji = (emoji: string) => {
    const { start, end } = selection.current
    const r = insertAt(text, emoji, start, end, MAX_MESSAGE)
    selection.current = { start: r.caret, end: r.caret }
    onChange(r.text)
    // on touch devices keep the on-screen keyboard closed while the picker is open (WhatsApp-like)
    if (!globalThis.matchMedia?.('(pointer: coarse)').matches) {
      requestAnimationFrame(() => input.current?.setSelectionRange(r.caret, r.caret))
    }
  }

  const closeEmoji = useCallback(() => setEmojiOpen(false), [])

  // entering edit mode: prefill with the message text; leaving it: clear
  const draft = useRef('')
  useEffect(() => {
    if (editing) {
      draft.current = text
      setText(editing.body ?? '')
      requestAnimationFrame(() => input.current?.focus())
    } else {
      setText(draft.current)
      draft.current = ''
    }
  }, [editing?.key]) // eslint-disable-line react-hooks/exhaustive-deps

  const saveEdit = () => {
    if (!editing) return
    const body = text.trim()
    const m = editing
    onEditDone()
    if (body && body !== m.body) void engine.editMessage(conversationId, m.key, body).catch((e) => onError(e instanceof Error ? e.message : 'Edit failed'))
  }

  const submit = () => {
    if (editing) return saveEdit()
    const body = text.trim()
    if (!body) return
    setText('')
    selection.current = { start: 0, end: 0 }
    setEmojiOpen(false)
    engine.sendTyping(conversationId, false)
    typingSent.current = 0
    void engine.send(conversationId, body)
  }

  return (
    <>
    {editing && (
      <div className="flex items-center gap-2 border-t border-slate-200 bg-slate-50 px-4 py-1.5 text-xs text-slate-600 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300">
        <Pencil size={12} /> <span className="font-medium">Editing message</span>
        <span className="truncate text-slate-400">{editing.body}</span>
        <button type="button" aria-label="Cancel edit" onClick={onEditDone} className="ml-auto rounded-full p-0.5 hover:bg-slate-200 dark:hover:bg-slate-700"><X size={14} /></button>
      </div>
    )}
    <form className="relative flex items-end gap-2 border-t border-slate-200 bg-slate-50 px-3 py-2.5 dark:border-slate-800 dark:bg-slate-900"
      onSubmit={(e) => { e.preventDefault(); submit() }}>
      {emojiOpen && <EmojiPicker onPick={addEmoji} onClose={closeEmoji} anchor={emojiButton} />}
      <button ref={emojiButton} type="button" aria-label={emojiOpen ? 'Show keyboard' : 'Emoji'} aria-expanded={emojiOpen}
        onClick={() => {
          rememberCaret()
          setEmojiOpen((o) => !o)
          if (emojiOpen) input.current?.focus()
        }}
        className={`flex h-[42px] w-[42px] shrink-0 items-center justify-center rounded-full transition hover:bg-slate-200 dark:hover:bg-slate-800 ${emojiOpen
          ? 'text-[var(--accent)]' : 'text-slate-500 dark:text-slate-400'}`}>
        {emojiOpen ? <Keyboard size={22} /> : <Smile size={22} />}
      </button>
      <textarea ref={input} value={text} rows={1} maxLength={MAX_MESSAGE} placeholder="Message"
        onChange={(e) => { onChange(e.target.value); rememberCaret() }}
        onSelect={rememberCaret} onFocus={() => { if (globalThis.matchMedia?.('(pointer: coarse)').matches) setEmojiOpen(false) }}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit() }
          if (e.key === 'Escape' && editing) { e.preventDefault(); onEditDone() }
        }}
        className="max-h-40 min-h-[42px] flex-1 resize-none rounded-2xl border border-slate-200 bg-white px-4 py-2.5 text-[15px] text-slate-900 outline-none focus:border-[var(--accent)] dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100" />
      <button type="submit" disabled={!text.trim()} aria-label={editing ? 'Save edit' : 'Send'}
        className="flex h-[42px] w-[42px] shrink-0 items-center justify-center rounded-full bg-[var(--accent)] text-white transition hover:bg-[var(--accent-hover)] disabled:opacity-40">
        {editing ? <Check size={18} /> : <SendHorizontal size={18} />}
      </button>
    </form>
    </>
  )
}

function JoinNotice({ text }: { text: string }) {
  return (
    <div className="my-3 flex justify-center px-3" data-testid="join-notice">
      <span className="rounded-lg bg-slate-100/95 px-3 py-1 text-xs text-slate-600 shadow-sm dark:bg-slate-800 dark:text-slate-300">{text}</span>
    </div>
  )
}
