export function timeOf(iso?: string): string {
  if (!iso) return ''
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

export function shortDate(iso?: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) return timeOf(iso)
  const diff = (now.getTime() - d.getTime()) / 86_400_000
  if (diff < 7) return d.toLocaleDateString([], { weekday: 'short' })
  return d.toLocaleDateString([], { day: 'numeric', month: 'short' })
}

export function dayLabel(iso: string): string {
  const d = new Date(iso)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) return 'Today'
  const y = new Date(now)
  y.setDate(now.getDate() - 1)
  if (d.toDateString() === y.toDateString()) return 'Yesterday'
  return d.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
}

export function lastSeen(iso?: string): string {
  if (!iso) return 'offline'
  return `last seen ${shortDate(iso)}${new Date(iso).toDateString() === new Date().toDateString() ? '' : ' ' + timeOf(iso)}`
}

/** 60-digit safety number in 12 groups of 5. */
export function groupDigits(n: string): string[] {
  return n.match(/.{1,5}/g) ?? []
}
