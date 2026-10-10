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
