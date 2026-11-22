import { describe, expect, it } from 'vitest'
import { EMOJI_CATEGORIES, insertAt, loadRecentEmoji, pushRecentEmoji, searchEmoji } from '../lib/emoji'

class MemStorage {
  m = new Map<string, string>()
  getItem(k: string) { return this.m.get(k) ?? null }
  setItem(k: string, v: string) { this.m.set(k, v) }
}

describe('emoji catalogue', () => {
  it('parses every category into single emoji tokens', () => {
    expect(EMOJI_CATEGORIES.length).toBeGreaterThanOrEqual(8)
    for (const c of EMOJI_CATEGORIES) {
      expect(c.emojis.length).toBeGreaterThan(20)
      for (const e of c.emojis) {
        expect(e.char).not.toMatch(/[\sa-z]/)
        expect([...new Intl.Segmenter().segment(e.char)]).toHaveLength(1) // one grapheme (ZWJ/flags/keycaps included)
      }
    }
  })

  it('searches by keyword prefix, all words must match', () => {
    expect(searchEmoji('thumb')).toContain('👍')
    expect(searchEmoji('heart red')).toEqual(['❤️'])
    expect(searchEmoji('LOL')).toContain('😂')
    expect(searchEmoji('flags')).toContain('🇸🇬')
    expect(searchEmoji('zzzqqq')).toEqual([])
    expect(searchEmoji('   ')).toEqual([])
  })
})

describe('insertAt', () => {
  it('inserts at the caret / replaces the selection', () => {
    expect(insertAt('hello world', '😀', 5, 5)).toEqual({ text: 'hello😀 world', caret: 7 })
    expect(insertAt('hello world', '👍', 6, 11)).toEqual({ text: 'hello 👍', caret: 8 })
    expect(insertAt('', '🎉', 3, 9)).toEqual({ text: '🎉', caret: 2 })
  })

  it('refuses to exceed the max length', () => {
    expect(insertAt('abc', '😀', 3, 3, 4)).toEqual({ text: 'abc', caret: 3 })
  })
})

describe('recent emoji', () => {
  it('keeps most-recent first, de-duplicated, capped', () => {
    const s = new MemStorage()
    pushRecentEmoji('😀', s)
    pushRecentEmoji('👍', s)
    expect(pushRecentEmoji('😀', s)).toEqual(['😀', '👍'])
    for (let i = 0; i < 40; i++) pushRecentEmoji(String.fromCodePoint(0x1f600 + i), s)
    expect(loadRecentEmoji(s)).toHaveLength(32)
  })

  it('ignores corrupt storage', () => {
    const s = new MemStorage()
    s.setItem('securechat.recentEmoji', '{not json')
    expect(loadRecentEmoji(s)).toEqual([])
  })
})
