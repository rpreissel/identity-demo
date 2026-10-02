import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { useHashTab } from './useHashTab'

const TABS = ['demo', 'journeytrace'] as const

describe('useHashTab', () => {
  afterEach(() => {
    window.location.hash = ''
  })

  it.each([
    ['journeytrace', 'journeytrace'],
    ['settings', 'demo'],
  ])('opens the tab for the hash %s as %s', (hash, tab) => {
    window.location.hash = hash

    const { result } = renderHook(() => useHashTab(TABS, 'demo'))

    expect(result.current[0]).toBe(tab)
  })

  it('writes the selected tab to the hash', () => {
    const { result } = renderHook(() => useHashTab(TABS, 'demo'))

    act(() => result.current[1]('journeytrace'))

    expect(window.location.hash).toBe('#journeytrace')
    expect(result.current[0]).toBe('journeytrace')
  })

  it('writes the fallback tab as no hash at all', () => {
    window.location.hash = 'journeytrace'
    const { result } = renderHook(() => useHashTab(TABS, 'demo'))

    act(() => result.current[1]('demo'))

    expect(window.location.hash).toBe('')
    expect(result.current[0]).toBe('demo')
  })
})
