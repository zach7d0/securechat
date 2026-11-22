/**
 * Appearance: light/dark mode and chat themes (wallpaper, outgoing bubble, accent colour).
 * Stored per device in localStorage only — never sent to the server.
 */
import { useSyncExternalStore } from 'react'

export type Mode = 'system' | 'light' | 'dark'
export type Pattern = 'dots' | 'grid' | 'diagonal' | 'plain'

interface Palette {
  wall: string        // chat wallpaper background
  pattern: string     // wallpaper pattern ink
  out: string         // outgoing bubble
  outText: string
  accent: string      // send button, highlights
  accentHover: string
  accentText: string  // sender names, links on light surfaces
}

export interface ChatTheme {
  id: string
  name: string
  light: Palette
  dark: Palette
}

export const THEMES: ChatTheme[] = [
  {
    id: 'emerald', name: 'Emerald',
    light: { wall: '#efeae2', pattern: 'rgba(0,0,0,0.05)', out: '#d1fae5', outText: '#0f172a', accent: '#059669', accentHover: '#047857', accentText: '#047857' },
    dark: { wall: '#0d1418', pattern: 'rgba(255,255,255,0.04)', out: '#065f46', outText: '#f8fafc', accent: '#10b981', accentHover: '#059669', accentText: '#34d399' },
  },
  {
    id: 'ocean', name: 'Ocean',
    light: { wall: '#e6f0f7', pattern: 'rgba(3,105,161,0.07)', out: '#cfe8fc', outText: '#0f172a', accent: '#0284c7', accentHover: '#0369a1', accentText: '#0369a1' },
    dark: { wall: '#0b1622', pattern: 'rgba(125,211,252,0.05)', out: '#0c4a6e', outText: '#f8fafc', accent: '#0ea5e9', accentHover: '#0284c7', accentText: '#7dd3fc' },
  },
  {
    id: 'sunset', name: 'Sunset',
    light: { wall: '#fbeee4', pattern: 'rgba(194,65,12,0.06)', out: '#fed7aa', outText: '#1c1917', accent: '#ea580c', accentHover: '#c2410c', accentText: '#c2410c' },
    dark: { wall: '#1a120d', pattern: 'rgba(253,186,116,0.05)', out: '#7c2d12', outText: '#fff7ed', accent: '#f97316', accentHover: '#ea580c', accentText: '#fdba74' },
  },
  {
    id: 'lavender', name: 'Lavender',
    light: { wall: '#efeaf7', pattern: 'rgba(109,40,217,0.06)', out: '#e9d5ff', outText: '#1e1b4b', accent: '#7c3aed', accentHover: '#6d28d9', accentText: '#6d28d9' },
    dark: { wall: '#130f1e', pattern: 'rgba(196,181,253,0.05)', out: '#4c1d95', outText: '#faf5ff', accent: '#8b5cf6', accentHover: '#7c3aed', accentText: '#c4b5fd' },
  },
  {
    id: 'rose', name: 'Rose',
    light: { wall: '#f9e9ec', pattern: 'rgba(190,18,60,0.06)', out: '#fecdd3', outText: '#1f1013', accent: '#e11d48', accentHover: '#be123c', accentText: '#be123c' },
    dark: { wall: '#1b0f13', pattern: 'rgba(253,164,175,0.05)', out: '#881337', outText: '#fff1f2', accent: '#f43f5e', accentHover: '#e11d48', accentText: '#fda4af' },
  },
  {
    id: 'graphite', name: 'Graphite',
    light: { wall: '#e8eaed', pattern: 'rgba(15,23,42,0.06)', out: '#dbe2ea', outText: '#0f172a', accent: '#475569', accentHover: '#334155', accentText: '#334155' },
    dark: { wall: '#0f1115', pattern: 'rgba(226,232,240,0.04)', out: '#334155', outText: '#f8fafc', accent: '#64748b', accentHover: '#475569', accentText: '#cbd5e1' },
  },
]

export const PATTERNS: { id: Pattern; name: string }[] = [
  { id: 'dots', name: 'Dots' }, { id: 'grid', name: 'Grid' }, { id: 'diagonal', name: 'Diagonal' }, { id: 'plain', name: 'Plain' },
]

export interface Appearance {
  mode: Mode
  theme: string
  pattern: Pattern
}

const KEY = 'securechat.appearance'
const DEFAULT: Appearance = { mode: 'system', theme: 'emerald', pattern: 'dots' }

function load(): Appearance {
  try {
    const v = JSON.parse(globalThis.localStorage?.getItem(KEY) ?? 'null') as Partial<Appearance> | null
    return {
      mode: v?.mode === 'light' || v?.mode === 'dark' || v?.mode === 'system' ? v.mode : DEFAULT.mode,
      theme: THEMES.some((t) => t.id === v?.theme) ? v!.theme! : DEFAULT.theme,
      pattern: PATTERNS.some((p) => p.id === v?.pattern) ? v!.pattern! : DEFAULT.pattern,
    }
  } catch {
    return DEFAULT
  }
}

let current: Appearance = load()
const listeners = new Set<() => void>()
const media = globalThis.matchMedia?.('(prefers-color-scheme: dark)')

export function isDark(a: Appearance = current): boolean {
  return a.mode === 'dark' || (a.mode === 'system' && !!media?.matches)
}

export function themeById(id: string): ChatTheme {
  return THEMES.find((t) => t.id === id) ?? THEMES[0]
}

export function paletteOf(a: Appearance, dark = isDark(a)): Palette {
  const t = themeById(a.theme)
  return dark ? t.dark : t.light
}

/** CSS variables for a palette (also used to render previews of other themes). */
export function cssVars(p: Palette): Record<string, string> {
  return {
    '--chat-wall': p.wall, '--chat-pattern': p.pattern, '--bubble-out': p.out, '--bubble-out-text': p.outText,
    '--accent': p.accent, '--accent-hover': p.accentHover, '--accent-text': p.accentText,
  }
}

function apply(): void {
  const root = globalThis.document?.documentElement
  if (!root) return
  const dark = isDark()
  root.classList.toggle('dark', dark)
  root.style.colorScheme = dark ? 'dark' : 'light'
  root.dataset.pattern = current.pattern
  for (const [k, v] of Object.entries(cssVars(paletteOf(current, dark)))) root.style.setProperty(k, v)
}

export function setAppearance(patch: Partial<Appearance>): void {
  current = { ...current, ...patch }
  try {
    globalThis.localStorage?.setItem(KEY, JSON.stringify(current))
  } catch {
    /* storage disabled: still applies for this session */
  }
  apply()
  for (const l of listeners) l()
}

/** Call once before the first render (avoids a flash of the wrong theme). */
export function initAppearance(): void {
  apply()
  media?.addEventListener?.('change', () => {
    if (current.mode === 'system') {
      apply()
      for (const l of listeners) l()
    }
  })
}

const subscribe = (l: () => void) => {
  listeners.add(l)
  return () => listeners.delete(l)
}

export function useAppearance(): Appearance {
  return useSyncExternalStore(subscribe, () => current)
}
