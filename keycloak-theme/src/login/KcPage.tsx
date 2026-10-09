import { lazy, Suspense } from 'react'
import DefaultPage from 'keycloakify/login/DefaultPage'
import Template from './KcTemplate'
import type { KcContext } from './KcContext'
import { useI18n } from './i18n'
import { setTexts } from '../texts'
import { bundledTexts } from './bundledTexts'
import { OrchestratorConfirm } from './pages/OrchestratorConfirm'
import { OrchestratorError } from './pages/OrchestratorError'
import { OrchestratorManageMethods } from './pages/OrchestratorManageMethods'
import { OrchestratorSelect } from './pages/OrchestratorSelect'
import { OrchestratorTool } from './pages/OrchestratorTool'
import { ToolAuthInvite } from './pages/ToolAuthInvite'
import { ToolEmailAuth } from './pages/ToolEmailAuth'
import { ToolEmailLookup } from './pages/ToolEmailLookup'
import { ToolIdentEid } from './pages/ToolIdentEid'
import { ToolIdentFsc } from './pages/ToolIdentFsc'
import { ToolIdentKvnr } from './pages/ToolIdentKvnr'
import { ToolIdentNect } from './pages/ToolIdentNect'
import { ToolPasswordAuth } from './pages/ToolPasswordAuth'
import { ToolPasswordEnroll } from './pages/ToolPasswordEnroll'
import { ToolPasswordLookup } from './pages/ToolPasswordLookup'
import { ToolQrEnroll } from './pages/ToolQrEnroll'
import { ToolQrWait } from './pages/ToolQrWait'
import { ToolSmsAuth } from './pages/ToolSmsAuth'
import { ToolSmsEnroll } from './pages/ToolSmsEnroll'
import { ToolSmsLookup } from './pages/ToolSmsLookup'
import './theme.css'

const UserProfileFormFields = lazy(() => import('keycloakify/login/UserProfileFormFields'))

/** Keycloak's own pages in our look: their PatternFly classes mapped onto the theme's. */
const KC_CLASSES = {
  kcFormGroupClass: 'orc-field',
  kcFormButtonsClass: 'orc-actions',
  kcFormOptionsWrapperClass: 'orc-form-options',
  kcButtonClass: 'orc-button',
  kcButtonPrimaryClass: 'orc-button-primary',
  kcInputGroup: 'orc-input-group',
  kcInputErrorMessageClass: 'orc-hint orc-hint-error',
  kcFormPasswordVisibilityButtonClass: 'orc-visibility',
  kcCheckboxInputClass: 'orc-checkbox',
}

/**
 * One component per page id. The orchestrator's own pages are rebuilt here; any page id without
 * its own component - Keycloak's built-in pages such as login-page-expired.ftl - falls back to
 * Keycloakify's default page, framed by our KcTemplate and styled through KC_CLASSES.
 */
export default function KcPage({ kcContext }: { kcContext: KcContext }) {
  // Keycloak's own pages carry no texts from the extension: the bundle in the login's language.
  setTexts(kcContext.texts ?? bundledTexts(kcContext.locale?.currentLanguageTag))
  const { i18n } = useI18n({ kcContext })
  return (
    <Suspense>
      {(() => {
        switch (kcContext.pageId) {
          case 'orchestrator-confirm.ftl':
            return <OrchestratorConfirm kcContext={kcContext} />
          case 'orchestrator-error.ftl':
            return <OrchestratorError kcContext={kcContext} />
          case 'orchestrator-manage-methods.ftl':
            return <OrchestratorManageMethods kcContext={kcContext} />
          case 'orchestrator-select.ftl':
            return <OrchestratorSelect kcContext={kcContext} />
          case 'orchestrator-tool.ftl':
            return <OrchestratorTool kcContext={kcContext} />
          case 'tool-auth-invite.ftl':
            return <ToolAuthInvite kcContext={kcContext} />
          case 'tool-email-auth.ftl':
            return <ToolEmailAuth kcContext={kcContext} />
          case 'tool-email-lookup.ftl':
            return <ToolEmailLookup kcContext={kcContext} />
          case 'tool-ident-eid.ftl':
            return <ToolIdentEid kcContext={kcContext} />
          case 'tool-ident-fsc.ftl':
            return <ToolIdentFsc kcContext={kcContext} />
          case 'tool-ident-kvnr.ftl':
            return <ToolIdentKvnr kcContext={kcContext} />
          case 'tool-ident-nect.ftl':
            return <ToolIdentNect kcContext={kcContext} />
          case 'tool-password-auth.ftl':
            return <ToolPasswordAuth kcContext={kcContext} />
          case 'tool-password-enroll.ftl':
            return <ToolPasswordEnroll kcContext={kcContext} />
          case 'tool-password-lookup.ftl':
            return <ToolPasswordLookup kcContext={kcContext} />
          case 'tool-qr-enroll.ftl':
            return <ToolQrEnroll kcContext={kcContext} />
          case 'tool-qr-wait.ftl':
            return <ToolQrWait kcContext={kcContext} />
          case 'tool-sms-auth.ftl':
            return <ToolSmsAuth kcContext={kcContext} />
          case 'tool-sms-enroll.ftl':
            return <ToolSmsEnroll kcContext={kcContext} />
          case 'tool-sms-lookup.ftl':
            return <ToolSmsLookup kcContext={kcContext} />
          default:
            return (
              <DefaultPage
                kcContext={kcContext}
                i18n={i18n}
                classes={KC_CLASSES}
                Template={Template}
                doUseDefaultCss={false}
                UserProfileFormFields={UserProfileFormFields}
                doMakeUserConfirmPassword={true}
              />
            )
        }
      })()}
    </Suspense>
  )
}
