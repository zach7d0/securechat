import { History, RefreshCw, ShieldAlert } from 'lucide-react'
import { useEffect, useState } from 'react'
import { api, useApp, useEngineTick } from '../state/app'
import { Button, ErrorText } from './ui'

/**
 * Automatic device-to-device history sync. A new device asks for the last month on first start (and for
 * earlier months when "Load older messages" reaches them); another device of the same account answers
 * automatically. Every device that sent history shows who received it, with a one-click revoke.
 */
export function HistoryTransferPanel() {
  useEngineTick()
  const session = useApp((s) => s.session!)
  const engine = session.engine
  const pending = engine.pendingHistorySync()
  const sent = engine.historySentNotices()
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)

  useEffect(() => {
    void engine.refreshTransfers()
  }, [engine])

  const run = async (id: string, f: () => Promise<unknown>) => {
    setBusy(id)
    setError(null)
    try {
      await f()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed')
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="mb-6 rounded-xl border border-slate-200 p-4 text-sm dark:border-slate-800" data-testid="history-transfer">
      <div className="mb-1 flex items-center gap-2 font-semibold text-slate-800 dark:text-slate-200"><History size={16} /> Message history</div>
      <p className="mb-3 text-xs text-slate-500">
        A new device automatically gets the last month of messages from your other devices (end-to-end encrypted for that
        device only; the server cannot read it). “Load older messages” fetches earlier months the same way.
      </p>
      <ErrorText>{error}</ErrorText>
      {note && <p className="mb-2 text-xs text-emerald-700 dark:text-emerald-400">{note}</p>}

      {sent.filter((n) => !n.dismissed).map((n) => (
        <div key={n.id} className="mb-3 rounded-lg bg-amber-50 p-3 dark:bg-amber-950/40" data-testid="history-sent">
          <div className="flex items-center gap-1.5 font-medium text-amber-900 dark:text-amber-200">
            <ShieldAlert size={14} /> {n.messages} messages were sent to your new device “{n.targetDeviceName ?? n.targetDeviceId.slice(0, 8)}”
          </div>
          <p className="mt-1 text-xs text-amber-900 dark:text-amber-200">{new Date(n.at).toLocaleString()}. Not you? Revoke it now.</p>
          <div className="mt-2 flex gap-2">
            <Button variant="danger" disabled={busy === n.id} onClick={() => run(n.id, async () => {
              await api.revokeDevice(n.targetDeviceId)
              await engine.dismissHistoryNotice(n.id)
              setNote('Device revoked — it is signed out and gets no new messages')
            })}>Not me — revoke</Button>
            <Button variant="ghost" disabled={busy === n.id} onClick={() => run(n.id, () => engine.dismissHistoryNotice(n.id))}>That was me</Button>
          </div>
        </div>
      ))}

      {pending.length > 0 ? (
        <div className="mb-3 rounded-lg bg-slate-50 p-3 dark:bg-slate-800" data-testid="history-pending">
          Syncing history from your other devices… ({pending.length} request{pending.length === 1 ? '' : 's'}). This finishes
          automatically when one of them is online.
        </div>
      ) : (
        <Button variant="ghost" disabled={busy === 'sync'} onClick={() => run('sync', async () => {
          await engine.requestHistory()
          setNote('Asked your other devices for the last month')
        })}><RefreshCw size={16} /> Sync last month again</Button>
      )}
    </div>
  )
}

/** For the sidebar banner: history was sent to one of my devices and not acknowledged yet. */
export function useHistorySentCount(): number {
  useEngineTick()
  const session = useApp((s) => s.session)
  if (!session) return 0
  return session.engine.historySentNotices().filter((n) => !n.dismissed).length
}
