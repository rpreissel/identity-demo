import type { ExtendKcContext } from 'keycloakify/login'

/**
 * The orchestrator's own pages - the page ids and attributes the extension's WebFormRenderer sets
 * (docs/adr/ADR-057-keycloakify-einziges-login-theme.md). Every page carries `texts`, its wordings
 * as a plain map (../texts.ts).
 */
export type KcContextExtension = {
  themeName: string
  properties: Record<string, string | undefined>
  /** Only on the orchestrator's own pages, not on Keycloak's. */
  texts?: Record<string, string>
}

/** What WebFormRenderer.toolForm sets on every tool page; the tool's renderer factory adds the rest. */
type ToolPage = {
  toolId: string
  /** The page's heading - not `title`, which Keycloak sets itself to its login title on every page. */
  pageTitle: string
  hint?: string
}

/** Raw JSON of demo.persons (AbstractWebToolRendererFactory.demoPersonsJson); absent without demo values. */
type WithPersons = { demoPersonsJson?: string }

export type KcContextExtensionPerPage = {
  'tool-password-auth.ftl': ToolPage & { demoPassword?: string }
  /** replaces: the account already has a password, this run changes it. */
  'tool-password-enroll.ftl': ToolPage & { demoPassword?: string; replaces?: boolean }
  'tool-password-lookup.ftl': ToolPage & WithPersons & { demoPassword?: string }
  'tool-email-auth.ftl': ToolPage & { demoTan?: string }
  'tool-email-lookup.ftl': ToolPage & WithPersons & { step: string; demoTan?: string; addressAgain?: boolean }
  'tool-sms-auth.ftl': ToolPage & { demoTan?: string }
  'tool-sms-enroll.ftl': ToolPage & WithPersons & { step: string; demoTan?: string; replaces?: boolean; askConsent?: boolean }
  'tool-sms-lookup.ftl': ToolPage & WithPersons & { step: string; demoTan?: string }
  'tool-ident-eid.ftl': ToolPage & WithPersons & { cardPage: boolean }
  'tool-ident-fsc.ftl': ToolPage & WithPersons & { personalienPage: boolean }
  'tool-ident-kvnr.ftl': ToolPage & WithPersons
  'tool-auth-invite.ftl': ToolPage & { demoInvitationsJson?: string }
  /** Without jumpUrl the last attempt failed; the page then offers a fresh case. */
  'tool-ident-nect.ftl': ToolPage & { jumpUrl?: string }
  'tool-qr-enroll.ftl': ToolPage
  'tool-qr-wait.ftl': ToolPage &
    ({ step: 'waitForApp'; pairingCode: string; deepLink: string; qrDataUri: string; statusUrl: string } | { step: 'enterCode' })
  'orchestrator-manage-methods.ftl': {
    // methodName: what the method is called (the orchestrator's tool catalog), for a method without a label of its own
    // changeable: the orchestrator says this method can be changed in place
    methods: { id: string; method: string; label?: string; methodName: string; changeable?: boolean }[]
  }
  'orchestrator-tool.ftl': {
    toolId: string
    pageTitle?: string
    hint?: string
    /** One entry per stepData.missingFields, value always "". */
    fields: Record<string, string>
  }
  'orchestrator-confirm.ftl': {
    pageTitle?: string
    description?: string
    confirmLabel?: string
    cancelLabel?: string
  }
  /** Nothing of its own - the message is Keycloak's (kcContext.message). */
  'orchestrator-error.ftl': Record<string, unknown>
  'orchestrator-select.ftl': {
    pageTitle?: string
    description?: string
    options: string[]
    optionLabels: Record<string, string>
    offerRegistration?: boolean
  }
}

export type KcContext = ExtendKcContext<KcContextExtension, KcContextExtensionPerPage>

/** The kcContext of one page. */
export type PageContext<P extends KcContext['pageId']> = Extract<KcContext, { pageId: P }>
