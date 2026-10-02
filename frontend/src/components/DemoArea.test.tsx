import { render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DemoArea, DemoProvider } from './DemoArea'

function renderArea() {
  return render(
    <DemoProvider>
      {(targets) => <DemoArea targets={targets} intro={{ id: 'test', title: 'Einführung', body: <p>Text</p> }} />}
    </DemoProvider>,
  )
}

function introOpen(container: HTMLElement): boolean {
  return (container.querySelector('.demo-background details') as HTMLDetailsElement).open
}

describe('DemoArea intro', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('is open on the first visit', () => {
    const { container } = renderArea()

    expect(introOpen(container)).toBe(true)
  })

  it('is closed on the next visit', () => {
    renderArea().unmount()

    const { container } = renderArea()

    expect(introOpen(container)).toBe(false)
  })

  it('stays closed and does not break when the browser refuses storage', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })

    const { container } = renderArea()

    expect(introOpen(container)).toBe(false)
  })
})
