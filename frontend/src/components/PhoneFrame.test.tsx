import { fireEvent, render, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { PhoneFrame, StepActions, StepNav } from './PhoneFrame'

function Form({ onSubmit }: { onSubmit: () => void }) {
  return (
    <form
      id="test-form"
      onSubmit={(event) => {
        event.preventDefault()
        onSubmit()
      }}
    >
      <input aria-label="Feld" />
      <StepActions>
        <button type="submit" form="test-form">
          Weiter
        </button>
      </StepActions>
    </form>
  )
}

describe('StepActions', () => {
  it('renders a step action in the phone bar, not in the sheet', () => {
    const { container } = render(
      <PhoneFrame title="Demo">
        <Form onSubmit={() => {}} />
      </PhoneFrame>,
    )

    const button = within(container).getByRole('button', { name: 'Weiter' })
    expect(container.querySelector('.phone__bar')).toContainElement(button)
    expect(container.querySelector('.phone__sheet')).not.toContainElement(button)
  })

  it('a submit button in the phone bar still submits its form', () => {
    const onSubmit = vi.fn()
    const { container } = render(
      <PhoneFrame title="Demo">
        <Form onSubmit={onSubmit} />
      </PhoneFrame>,
    )

    fireEvent.click(within(container).getByRole('button', { name: 'Weiter' }))

    expect(onSubmit).toHaveBeenCalledOnce()
  })

  it('puts the ways out on top of the sheet and the main action in the bar', () => {
    const { container } = render(
      <PhoneFrame title="Demo">
        <StepNav>
          <button className="back">Zurück</button>
        </StepNav>
        <Form onSubmit={() => {}} />
      </PhoneFrame>,
    )

    const back = within(container).getByRole('button', { name: 'Zurück' })
    expect(container.querySelector('.phone__nav')).toContainElement(back)
    expect(container.querySelector('.phone__bar')).not.toContainElement(back)
  })

  it('a question in the footer takes the place of the step actions', () => {
    const { container } = render(
      <PhoneFrame title="Demo" footer={<div className="phone__bar-question">Wirklich?</div>}>
        <Form onSubmit={() => {}} />
      </PhoneFrame>,
    )
    expect(container.querySelector('.phone__bar')).toHaveClass('phone__bar--footer')
  })

  it('stays where it is written without a phone frame', () => {
    const { container } = render(<Form onSubmit={() => {}} />)
    expect(container.querySelector('form')).toContainElement(within(container).getByRole('button', { name: 'Weiter' }))
  })
})

describe('tool form sources', () => {
  it('every submit button of a tool form names its form, since the bar is outside it', () => {
    const sources = import.meta.glob('../tools/**/*.tsx', { query: '?raw', import: 'default', eager: true }) as Record<string, string>
    let checked = 0
    for (const [file, source] of Object.entries(sources)) {
      for (const tag of source.match(/<button[^>]*type="submit"[^>]*>/g) ?? []) {
        expect(tag, file).toMatch(/\bform="[^"]+"/)
        checked++
      }
    }
    expect(checked).toBeGreaterThan(10)
  })
})
