import type { DemoPerson } from '../../types'
import { t } from '../../texts'
import { EmailEntryForm } from '../shared/EmailEntryForm'

interface EmailLookupFormProps {
  onSubmit: (email: string) => void
  error?: string
  /** Demo-only: every register person, offered as a picker that fills the email field; the first is prefilled. */
  demoPersons?: DemoPerson[]
}

/**
 * toolId=auth-sms-lookup / step=auth: "Login ohne DPoP". Resolves the account by email and sends
 * a TAN to its enrolled phone number.
 */
export function EmailLookupForm(props: EmailLookupFormProps) {
  return (
    <EmailEntryForm
      {...props}
      copy={{
        formId: 'sms-lookup',
        title: t('Mit E-Mail-Adresse und SMS-Code anmelden'),
        intro: t('Geben Sie die E-Mail-Adresse Ihres Kontos ein, um eine TAN an die hinterlegte Telefonnummer zu erhalten.'),
        submit: t('TAN anfordern'),
      }}
    />
  )
}
