import { describe, expect, it } from 'vitest'
import { formatDays, SKILL_LEVEL_LABELS } from './labels'

describe('formatDays', () => {
  it('says day or days and keeps halves', () => {
    expect(formatDays(1)).toBe('1 day')
    expect(formatDays(2)).toBe('2 days')
    expect(formatDays(2.5)).toBe('2.5 days')
    expect(formatDays(0)).toBe('0 days')
    expect(formatDays(-1)).toBe('-1 days')
  })

  it('shows Unlimited without a number', () => {
    expect(formatDays(null)).toBe('Unlimited')
    expect(formatDays(undefined)).toBe('Unlimited')
  })
})

describe('SKILL_LEVEL_LABELS', () => {
  it('names the five levels', () => {
    expect(SKILL_LEVEL_LABELS[1]).toBe('1 · Aware')
    expect(SKILL_LEVEL_LABELS[5]).toBe('5 · Expert')
  })
})
