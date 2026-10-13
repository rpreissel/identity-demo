# Ideen

In diesem Ordner stehen Überlegungen, über die noch nicht entschieden ist. Jedes Dokument sagt
am Anfang, welches Problem es betrachtet, warum das wichtig ist und wie der Stand ist. Lesen Sie
die passende Idee, bevor Sie ein größeres Redesign neu durchdenken. Oft ist ein Teil der Fragen
dort schon durchgespielt.

Begriffe wie Journey, Tool oder Niveau erklärt das [Glossar](../glossar/glossar.md).

- [Verfahren aufwerten](reidentify-methoden-upgrade.md): Ein Anmeldeverfahren soll mehr zählen
  dürfen, nachdem sich der Nutzer erneut oder erstmals identifiziert hat (Sub-Journey
  `RE_IDENTIFY`).
- [Native Verfahren als Tools](native-verfahren-als-tools.md): Keycloaks eigene Verfahren
  (Passkey, OTP) sollten gewöhnliche Tools des Orchestrators werden, die Keycloaks Seiten nutzen.
  Verworfen nach einem Spike, siehe
  [ADR-58](../adr/ADR-058-keycloak-fuehrt-keine-eigenen-anmeldeschritte.md).
- [Black-Box-Contract-Tests](black-box-contract-tests.md): Eine Testsuite, die nur über HTTP von
  außen prüft. Sie soll zeigen, ob sich eine Neuentwicklung genauso verhält wie die heutige
  Implementierung.
- [Verfahren als externe Module](verfahren-als-externe-module.md): Ein Verfahren soll in einem
  eigenen Repository entstehen und als fertiges Artefakt in Orchestrator, Keycloak-Erweiterung,
  Login-Theme und App eingebunden werden.
- [Abschied von alten Fassungen](tool-versionen.md): Eine alte Fassung eines Tools beobachten und
  ausbauen und alten Clients sagen, dass sie aktualisieren müssen. Die zweite Fassung selbst ist
  gebaut (`enroll-sms@2`, ADR-51).
- [Keycloak behalten oder eigener Provider](keycloak-oder-eigener-provider.md): Keycloak ist nur
  noch OIDC-Fassade und Token-Aussteller. Ob das die Kopplung wert ist, hängt daran, ob weitere
  Clients, auch Apps von Partnern, dieselben Konten nutzen sollen. „Alles nach Keycloak“ ist abgehakt.
  Abschnitt 7 zeigt, wie Partner-Apps die Anmeldung erreichen: OIDC, Bestätigung durch die
  Versicherungs-App auf demselben Gerät (Basis `device`, nicht `qr`), Passkey, CIBA.
- [Die App als native Hülle](app-als-native-huelle.md): Ein dünnes macOS-Programm ohne Xcode zeigt
  `/app/` in einer `WKWebView`. Es signiert mit Schlüsseln in der Secure Enclave und mit echtem
  Touch ID. Oberfläche und Backend bleiben unverändert.
- [Abmeldung nur über Keycloak](logout-ueber-keycloak.md): Beide Kanäle melden sich über Keycloak
  ab, der Orchestrator erfährt es per OIDC Back-Channel Logout. Die Journey `LOGOUT` und die eigene
  Meldekette der Extension fallen weg, die Abmeldung steht nur im SignInLog; das Standardprofil
  ohne Keycloak entfällt. Umgesetzt, siehe
  [ADR-60](../adr/ADR-060-abmeldung-ueber-keycloak-back-channel-logout.md).
