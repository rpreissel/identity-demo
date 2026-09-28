import { useState } from 'react'
import type { PageContext } from '../KcContext'
import { DemoPersonPicker } from '../components/DemoPersonPicker'
import { Field } from '../components/Field'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/**
 * `tool-ident-eid.ftl`: the (simulated) ID card read first (cardPage), then the eID PIN. "Zurück" on
 * the PIN page shows the card again, like "Angaben ändern" in the App; only "Zurück" on the card
 * leaves the tool.
 */
export function ToolIdentEid({ kcContext }: { kcContext: PageContext<'tool-ident-eid.ftl'> }) {
  const { pageTitle: title, hint, cardPage, demoPersonsJson } = kcContext
  const [editing, setEditing] = useState(false)

  if (cardPage || editing) {
    // key: a view of its own, so the test person picker is built anew and fills these fields.
    return (
      <ToolForm key="card" kcContext={kcContext} title={title} hint={hint} onBack={editing ? () => setEditing(false) : undefined}>
        <p className="orc-hint">{t('Demo-Modus: Das Auslesen der Karte wird simuliert.')}</p>
        <DemoPersonPicker
          personsJson={demoPersonsJson}
          fields={{
            familyName: 'familyName',
            givenNames: 'givenNames',
            birthDate: 'birthDate',
            streetAddress: 'streetAddress',
            postalCode: 'postalCode',
            locality: 'locality',
            restrictedId: 'restrictedId',
          }}
        />
        <div className="orc-grid-2">
          <Field id="familyName" label={t('Nachname')} required />
          <Field id="givenNames" label={t('Vorname')} required />
        </div>
        <Field id="birthDate" type="date" label={t('Geburtsdatum')} required />
        {/* The card has street and house number in one field (Street). */}
        <Field id="streetAddress" label={t('Straße und Hausnummer')} required />
        <div className="orc-grid-2">
          <Field id="postalCode" label={t('PLZ')} required />
          <Field id="locality" label={t('Ort')} required />
        </div>
        {/* Editable although a real card brings it fixed: the only way to try a second card of the same person (ADR-19). */}
        <Field id="restrictedId" label={t('Restricted-ID (kartengebunden)')} required />
      </ToolForm>
    )
  }
  return (
    <ToolForm
      key="pin"
      kcContext={kcContext}
      title={title}
      hint={t('Geben Sie Ihre sechsstellige eID-PIN ein.')}
      submitLabel={t('Identifizieren')}
      onBack={() => setEditing(true)}
    >
      <Field id="pin" label={t('eID-PIN')} defaultValue="123456" required />
    </ToolForm>
  )
}
