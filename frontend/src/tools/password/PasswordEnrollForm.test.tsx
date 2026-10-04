import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { PasswordEnrollForm } from './PasswordEnrollForm'

describe('PasswordEnrollForm', () => {
  it('sets up a password by default', () => {
    render(<PasswordEnrollForm onSubmit={vi.fn()} />)

    expect(screen.getByRole('heading', { name: 'Passwort einrichten' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Einrichten' })).toBeInTheDocument()
  })

  it('says that the password is changed when the step replaces an active one', () => {
    render(<PasswordEnrollForm onSubmit={vi.fn()} replaces />)

    expect(screen.getByRole('heading', { name: 'Passwort ändern' })).toBeInTheDocument()
    expect(screen.getByLabelText('Neues Passwort')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ändern' })).toBeInTheDocument()
  })

  it('keeps saying so after a failed attempt, whose step carries no such note', () => {
    const { rerender } = render(<PasswordEnrollForm onSubmit={vi.fn()} replaces />)

    rerender(<PasswordEnrollForm onSubmit={vi.fn()} error="zu kurz" />)

    expect(screen.getByRole('heading', { name: 'Passwort ändern' })).toBeInTheDocument()
  })
})
