import { KeyRound, Laptop, Monitor, ShieldCheck, Smartphone, Trash2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import type { Device, KeyStatus } from '../lib/api/types'
import { toBase64 } from '../lib/crypto/bytes'
import { lastSeen, shortDate } from '../lib/format'
import { api, useApp } from '../state/app'
import { Button, ErrorText } from '../components/ui'
import { HistoryTransferPanel } from '../components/HistoryTransferPanel'
import { PageShell } from './ProfilePage'

export function SecurityPage() {
  const session = useApp((s) => s.session!)
  const logout = useApp((s) => s.logout)
  const [devices, setDevices] = useState<Device[]>([])
  const [counts, setCounts] = useState<KeyStatus | null>(null)
  const [fingerprint, setFingerprint] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [confirm, setConfirm] = useState<{ id: string; action: 'revoke' | 'remove' } | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  const act = async (id: string, action: 'revoke' | 'remove') => {
    setBusy(id)
    setConfirm(null)
    try {
      if (action === 'revoke') await api.revokeDevice(id)
      else await api.removeDevice(id)
      setError(null)
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed')
    } finally {
      setBusy(null)
    }
  }

  const load = async () => {
    try {
      setDevices(await api.devices())
      setCounts(await api.keyStatus())
      const id = await session.engine.signalStore.getIdentityKeyPair()
      if (id) {
        const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', id.pubKey))
        setFingerprint(toBase64(digest).replace(/[^A-Za-z0-9]/g, '').slice(0, 32).match(/.{4}/g)!.join(' '))
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load')
    }
  }
  useEffect(() => {
    void load()
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <PageShell title="Devices & security">
      <section className="mb-6 rounded-xl bg-emerald-50 p-4 text-sm text-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-200">
        <div className="mb-1 flex items-center gap-2 font-semibold"><ShieldCheck size={16} /> End-to-end encryption</div>
        One-to-one chats use the Signal Protocol (X3DH + Double Ratchet); groups use MLS (RFC 9420). Private keys are generated
        and stored only in this browser (IndexedDB, encrypted under a non-extractable key). The server stores ciphertext only.
      </section>

      <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">This device</h2>
      <div className="mb-6 rounded-xl border border-slate-200 p-4 text-sm dark:border-slate-800">
        <div className="flex items-center gap-2 text-slate-800 dark:text-slate-200"><KeyRound size={16} /> Identity key fingerprint</div>
        <div className="mt-2 font-mono text-sm tracking-wider text-slate-900 dark:text-slate-100">{fingerprint || '…'}</div>
        {counts && (
          <div className="mt-3 grid grid-cols-3 gap-2 text-center">
            <Stat label="One-time prekeys" value={counts.oneTimePreKeys} />
            <Stat label="MLS key packages" value={counts.mlsKeyPackages} />
            <Stat label="Signed prekey" value={counts.signedPreKeyCreatedAt ? shortDate(counts.signedPreKeyCreatedAt) : '—'} />
          </div>
        )}
        <p className="mt-3 text-xs text-slate-500">Prekeys are replenished automatically and the signed prekey rotates weekly.</p>
      </div>

      <HistoryTransferPanel />

      <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">Your devices</h2>
      <ErrorText>{error}</ErrorText>
      <ul className="mb-6 divide-y divide-slate-200 rounded-xl border border-slate-200 dark:divide-slate-800 dark:border-slate-800">
        {devices.map((d) => {
          const current = d.id === session.deviceId
          const Icon = d.platform === 'IOS' || d.platform === 'ANDROID' ? Smartphone : d.platform === 'DESKTOP' ? Monitor : Laptop
          const pending = confirm?.id === d.id ? confirm.action : null
          return (
            <li key={d.id} className={`flex flex-wrap items-center gap-3 p-4 ${d.status === 'REVOKED' ? 'opacity-70' : ''}`}>
              <Icon size={20} className="text-slate-500" />
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium text-slate-900 dark:text-slate-100">
                  {d.deviceName ?? d.platform}
                  {current && <span className="ml-1 rounded bg-emerald-100 px-1.5 py-0.5 text-[10px] font-semibold text-emerald-800 dark:bg-emerald-900/50 dark:text-emerald-300">THIS DEVICE</span>}
                  {d.status === 'REVOKED' && <span className="ml-1 rounded bg-rose-100 px-1.5 py-0.5 text-[10px] font-semibold text-rose-800 dark:bg-rose-900/50 dark:text-rose-300">REVOKED</span>}
                </div>
                <div className="text-xs text-slate-500">
                  {d.platform} · {d.revokedAt ? `revoked ${shortDate(d.revokedAt)}` : `registered ${shortDate(d.createdAt)}`}
                  {d.status === 'ACTIVE' && !current && d.lastSeenAt && <> · {lastSeen(d.lastSeenAt)}</>} · id {d.id.slice(0, 8)}
                </div>
              </div>
              {!current && (pending ? (
                <div className="flex items-center gap-2">
                  <span className="text-xs text-slate-600 dark:text-slate-400">
                    {pending === 'revoke' ? 'Sign this device out for good?' : d.status === 'ACTIVE' ? 'Sign out and remove from list?' : 'Remove from list?'}
                  </span>
                  <Button variant="danger" disabled={busy === d.id} onClick={() => void act(d.id, pending)}>
                    {pending === 'revoke' ? 'Revoke' : 'Remove'}</Button>
                  <Button variant="ghost" onClick={() => setConfirm(null)}>Cancel</Button>
                </div>
              ) : (
                <div className="flex items-center gap-1">
                  {d.status === 'ACTIVE' && (
                    <Button variant="ghost" disabled={busy === d.id} onClick={() => setConfirm({ id: d.id, action: 'revoke' })}>Revoke</Button>
                  )}
                  <Button variant="ghost" disabled={busy === d.id} aria-label={`Remove ${d.deviceName ?? d.platform}`}
                    onClick={() => setConfirm({ id: d.id, action: 'remove' })}><Trash2 size={16} /> Remove</Button>
                </div>
              ))}
            </li>
          )
        })}
      </ul>
      <p className="-mt-4 mb-6 text-xs text-slate-500">
        Revoke signs a device out and stops it receiving new messages; it stays listed as revoked. Remove also takes it off this list.
        A revoked or removed device can never be re-enabled; sign in again on it to register it as a new device.
      </p>

      <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">Danger zone</h2>
      <div className="rounded-xl border border-rose-200 p-4 dark:border-rose-900">
        <p className="mb-3 text-sm text-slate-600 dark:text-slate-400">
          Remove this device: revokes it on the server and deletes its keys and message history from this browser. This cannot be undone.
        </p>
        <Button variant="danger" onClick={async () => {
          if (!window.confirm('Remove this device and delete its keys and local history?')) return
          await api.removeDevice(session.deviceId).catch(() => undefined)
          session.engine.stop()
          await session.store.destroy()
          await logout()
        }}><Trash2 size={16} /> Remove this device</Button>
      </div>
    </PageShell>
  )
}

function Stat({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded-lg bg-slate-50 p-2 dark:bg-slate-800">
      <div className="text-lg font-semibold text-slate-900 dark:text-slate-100">{value}</div>
      <div className="text-[11px] text-slate-500">{label}</div>
    </div>
  )
}
