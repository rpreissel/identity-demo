import { t } from '../texts'
import { useEffect, useState } from 'react'
import { fetchRegistrationOrder, setRegistrationOrder } from '../api.ts'
import { SettingRow } from './SettingRow'

/**
 * REGISTER's "Enrollment zuerst" experiment (docs/journeys/register-enroll-first.md, `RegisterEnrollFirstStrategy`):
 * a global runtime toggle between the ident-first status quo and the alternative order, for the
 * next brand-new REGISTER journey - a journey already in progress keeps whichever order it started
 * with. No DPoP, behind the admin login like every operator endpoint.
 */
export function AdminRegistrationOrderView() {
  const [enrollFirst, setEnrollFirst] = useState<boolean | null>(null)
  const [error, setError] = useState('')

  function reload() {
    fetchRegistrationOrder()
      .then((state) => setEnrollFirst(state.enrollFirst))
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }

  useEffect(reload, [])

  async function choose(order: 'ident' | 'enroll') {
    try {
      setError('')
      await setRegistrationOrder(order === 'enroll')
      reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  return (
    <SettingRow
      title={t('Reihenfolge der Registrierung')}
      hint={t(
        'Ob ein neues Konto zuerst identifiziert wird oder zuerst ein Anmeldeverfahren bekommt und die Identifikation am Ende anbietet. ' +
          'Gilt für die nächste neu gestartete Registrierung; eine laufende behält ihre Reihenfolge.',
      )}
      choices={[
        { value: 'ident', label: t('Identifikation zuerst') },
        { value: 'enroll', label: t('Enrollment zuerst') },
      ]}
      value={enrollFirst === null ? null : enrollFirst ? 'enroll' : 'ident'}
      onChange={choose}
    >
      {error && <span className="error-text">{error}</span>}
    </SettingRow>
  )
}
