import type { PageContext } from '../KcContext'
import { EmailThenCode } from '../components/EmailThenCode'

/** `tool-email-lookup.ftl`: signing in by e-mail address, and confirming the address during registration. */
export function ToolEmailLookup({ kcContext }: { kcContext: PageContext<'tool-email-lookup.ftl'> }) {
  return <EmailThenCode kcContext={kcContext} />
}
