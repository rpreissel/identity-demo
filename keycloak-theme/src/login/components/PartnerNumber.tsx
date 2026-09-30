import { t } from '../../texts'

/** The Partnernummer, folded away: the KVNR comes first, the Partnernummer only counts without one (ADR-34). */
export function PartnerNumber() {
  return (
    <details className="orc-field orc-details">
      <summary>{t('Ich habe keine Versichertennummer')}</summary>
      <label htmlFor="partnerNumber">{t('Partnernummer')}</label>
      <input type="text" id="partnerNumber" name="partnerNumber" placeholder="P000000000" autoComplete="off" />
    </details>
  )
}
