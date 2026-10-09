import { createGetKcContextMock } from 'keycloakify/login/KcContext'
import type { KcContext, KcContextExtension, KcContextExtensionPerPage } from './KcContext'
import { bundledTexts } from './bundledTexts'

/**
 * Pages without Keycloak, for `npm run dev` and tests: `?page=orchestrator-tool.ftl` picks the
 * page, `?lang=en` the language, the realm is the demo's own. Values mirror what WebFormRenderer
 * sets - `texts` read straight from the theme's messages (keycloak-theme/messages), as Keycloak would.
 */
const PERSONS =
  '[{"givenNames":"Erika","familyName":"Mustermann","email":"erika@example.org","phoneNumber":"+49 170 0000002","kvnr":"A123456780","personId":"P000000001","birthDate":"1964-08-12","streetAddress":"Heidestraße 17","postalCode":"51147","locality":"Köln","fscCode":"ABCD-1234"}]'

const { getKcContextMock } = createGetKcContextMock({
  kcContextExtension: { themeName: 'orchestrator-keycloakify', properties: {} } as KcContextExtension,
  kcContextExtensionPerPage: {} as KcContextExtensionPerPage,
  overrides: { realm: { name: 'Demo', displayName: 'Demo' } },
  overridesPerPage: {
    'orchestrator-select.ftl': {
      pageTitle: 'Anmeldung bei Demo',
      options: ['auth-qr-lookup', 'auth-sms-lookup', 'auth-password-lookup'],
      optionLabels: { 'auth-qr-lookup': 'Mit der App anmelden', 'auth-sms-lookup': 'SMS', 'auth-password-lookup': 'Passwort' },
      offerRegistration: true,
    },
    'orchestrator-confirm.ftl': {
      pageTitle: 'Konto wirklich löschen?',
      description: 'Das Konto und alle Anmeldeverfahren werden endgültig gelöscht.',
      confirmLabel: 'Ja, löschen',
      cancelLabel: 'Nein, behalten',
    },
    'orchestrator-error.ftl': {
      message: { type: 'error', summary: 'Die Anmeldung ist gerade nicht möglich.' },
    },
    'tool-password-lookup.ftl': { toolId: 'auth-password-lookup', pageTitle: 'Passwort', hint: 'E-Mail-Adresse und Passwort', demoPassword: 'demo1234', demoPersonsJson: PERSONS },
    'tool-password-enroll.ftl': { toolId: 'enroll-password', pageTitle: 'Passwort', hint: 'Eigenes Passwort festlegen', demoPassword: 'Demo1234!' },
    'tool-email-auth.ftl': { toolId: 'auth-email', pageTitle: 'E-Mail', hint: 'Code an die bestätigte E-Mail-Adresse', demoTan: '482913' },
    'tool-email-lookup.ftl': { toolId: 'confirm-email', pageTitle: 'E-Mail', hint: 'E-Mail-Adresse + Bestätigungscode', step: 'codeInput', demoTan: '482913', addressAgain: true, demoPersonsJson: PERSONS },
    'tool-qr-enroll.ftl': { toolId: 'enroll-qr', pageTitle: 'QR-Login', hint: 'Web-Login per QR-Code erlauben' },
    'tool-ident-kvnr.ftl': { toolId: 'ident-kvnr', pageTitle: 'Versichertennummer', hint: 'Konto der eigenen Person im Personenverzeichnis zuordnen', demoPersonsJson: PERSONS },
    'tool-sms-enroll.ftl': { toolId: 'enroll-sms', pageTitle: 'SMS', hint: 'Code per SMS', step: 'enroll', demoTan: '123456', demoPersonsJson: PERSONS, askConsent: true },
    'tool-ident-fsc.ftl': { toolId: 'ident-fsc', pageTitle: 'Freischaltcode', personalienPage: true, demoPersonsJson: PERSONS },
    'tool-auth-invite.ftl': { toolId: 'auth-invite-lookup', pageTitle: 'Einmalkennwort' },
    'tool-ident-eid.ftl': { toolId: 'ident-eid', pageTitle: 'Online-Ausweis', hint: 'Mit dem Personalausweis', cardPage: true, demoPersonsJson: PERSONS },
    'tool-ident-nect.ftl': { toolId: 'ident-nect', pageTitle: 'Nect', hint: 'Ausweis, Reisepass oder EUDI-Wallet bei Nect (simuliert)', jumpUrl: 'http://localhost:8080/nect/?case=5b1c2d3e-0000-4000-8000-000000000001' },
    'tool-qr-wait.ftl': {
      toolId: 'auth-qr-lookup',
      pageTitle: 'Mit der App anmelden',
      step: 'waitForApp',
      pairingCode: 'K7Q2-M9XD',
      deepLink: 'http://localhost:8080/app/?intent=confirm_peer_login&pairingCode=K7Q2-M9XD',
      statusUrl: '/realms/Demo/orchestrator-qr/status?client_id=identity-demo-web&tab_id=preview',
      qrDataUri: 'data:image/svg+xml;utf8,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 10 10%22><rect width=%2210%22 height=%2210%22 fill=%22%23eee%22/><rect x=%221%22 y=%221%22 width=%223%22 height=%223%22/><rect x=%226%22 y=%221%22 width=%223%22 height=%223%22/><rect x=%221%22 y=%226%22 width=%223%22 height=%223%22/></svg>',
    },
    'orchestrator-manage-methods.ftl': {
      methods: [
        { id: '1', method: 'password', methodName: 'Passwort', changeable: true },
        { id: '2', method: 'sms', label: 'SMS an +49 151 ••• 67', methodName: 'SMS' },
      ],
    },
    'orchestrator-tool.ftl': {
      toolId: 'auth-example',
      fields: { email: '', code: '' },
    },
  },
})

export function mockContext(): KcContext {
  const query = new URLSearchParams(window.location.search)
  const pageId = query.get('page') ?? 'orchestrator-select.ftl'
  const texts = bundledTexts(query.get('lang') ?? 'de')
  // `?user=erika@example.org`: the login already knows who signs in, as on Keycloak's own pages
  // during a step-up or after the username step.
  const user = query.get('user')
  const auth = user ? { attemptedUsername: user, showUsername: true, showResetCredentials: false } : undefined
  return getKcContextMock({ pageId: pageId as KcContext['pageId'], overrides: { texts, ...(auth && { auth }) } }) as KcContext
}
