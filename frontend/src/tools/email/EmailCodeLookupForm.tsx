import type { DemoPerson } from '../../types'
import { t } from '../../texts'
import { EmailEntryForm } from '../shared/EmailEntryForm'

interface EmailCodeLookupFormProps {
  onSubmit: (email: string) => void
  error?: string
  /** Demo-only: every register person, offered as a picker that fills the email field; the first is prefilled. */
  demoPersons?: DemoPerson[]
}

/**
 * toolId=auth-email-lookup / step=auth: "Login ohne DPoP". Resolves the account by email and
 * sends a confirmation code to that address.
 */
export function EmailCodeLookupForm(props: EmailCodeLookupFormProps) {
  return (
    <EmailEntryForm
      {...props}
      copy={{
        formId: 'email-lookup',
        title: t('Mit E-Mail-Code anmelden'),
        intro: t('Geben Sie die E-Mail-Adresse Ihres Kontos ein, um einen Bestätigungscode an diese Adresse zu erhalten.'),
        submit: t('Code anfordern'),
      }}
    />
  )
}
