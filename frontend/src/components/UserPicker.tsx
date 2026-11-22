import { Check, Search } from 'lucide-react'
import { useEffect, useState } from 'react'
import type { Profile } from '../lib/api/types'
import { api } from '../state/app'
import { Avatar } from './ui'

/** Debounced username search; single or multi select. */
export function UserPicker({ onPick, selected = [], exclude = [], multi }: {
  onPick: (u: Profile) => void
  selected?: string[]
  exclude?: string[]
  multi?: boolean
}) {
  const [q, setQ] = useState('')
  const [results, setResults] = useState<Profile[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (q.trim().length < 2) {
      setResults([])
      return
    }
    const t = setTimeout(() => {
      api.searchUsers(q.trim()).then((r) => {
        setResults(r.filter((u) => !exclude.includes(u.userId)))
        setError(null)
      }).catch(() => setError('Search failed'))
    }, 250)
    return () => clearTimeout(t)
  }, [q, exclude.join(',')]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div>
      <label className="flex items-center gap-2 rounded-lg bg-slate-100 px-3 py-2 dark:bg-slate-800">
        <Search size={16} className="text-slate-500" />
        <input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search by username"
          className="w-full bg-transparent text-sm text-slate-900 outline-none placeholder:text-slate-500 dark:text-slate-100" />
      </label>
      {error && <p className="mt-2 text-sm text-rose-600">{error}</p>}
      <ul className="mt-3 max-h-72 space-y-1 overflow-y-auto">
        {results.map((u) => {
          const isSel = selected.includes(u.userId)
          return (
            <li key={u.userId}>
              <button onClick={() => onPick(u)} className="flex w-full items-center gap-3 rounded-lg px-2 py-2 text-left hover:bg-slate-100 dark:hover:bg-slate-800">
                <Avatar name={u.displayName} size={36} />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium text-slate-900 dark:text-slate-100">{u.displayName}</div>
                  <div className="truncate text-xs text-slate-500">@{u.username}</div>
                </div>
                {multi && <span className={`flex h-5 w-5 items-center justify-center rounded-full border ${isSel ? 'border-emerald-600 bg-emerald-600 text-white' : 'border-slate-300'}`}>{isSel && <Check size={12} />}</span>}
              </button>
            </li>
          )
        })}
        {q.trim().length >= 2 && results.length === 0 && !error && <li className="px-2 py-3 text-sm text-slate-500">No users found</li>}
      </ul>
    </div>
  )
}
