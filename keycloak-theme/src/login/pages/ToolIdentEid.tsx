import type { PageContext } from '../KcContext'
import { DemoPersonPicker } from '../components/DemoPersonPicker'
import { Field } from '../components/Field'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/** `tool-ident-eid.ftl`: the (simulated) ID card read (step card), then the eID PIN (step pin). */
export function ToolIdentEid({ kcContext }: { kcContext: PageContext<'tool-ident-eid.ftl'> }) {
  const { pageTitle: title, hint, step, demoPersonsJson } = kcContext
  return (
    <ToolForm kcContext={kcContext} title={title} hint={hint}>
      {step === 'card' && (
        <>
          <p className="orc-hint">{t('Demo-Modus: Das Auslesen der Karte wird simuliert.')}</p>
          <DemoPersonPicker
            personsJson={demoPersonsJson}
            fields={{ familyName: 'familyName', givenNames: 'givenNames', birthDate: 'birthDate', streetAddress: 'streetAddress', postalCode: 'postalCode', locality: 'locality' }}
          />
          <div className="orc-grid-2">
            <Field id="familyName" label={t('Nachname')} />
            <Field id="givenNames" label={t('Vorname')} />
          </div>
          <Field id="birthDate" type="date" label={t('Geburtsdatum')} />
          {/* The card has street and house number in one field (Street). */}
          <Field id="streetAddress" label={t('Straße und Hausnummer')} />
          <div className="orc-grid-2">
            <Field id="postalCode" label={t('PLZ')} />
            <Field id="locality" label={t('Ort')} />
          </div>
        </>
      )}
      {step === 'pin' && <Field id="pin" label={t('eID-PIN')} defaultValue="123456" />}
    </ToolForm>
  )
}
