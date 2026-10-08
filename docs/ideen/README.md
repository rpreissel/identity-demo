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
  (Passkey, OTP) sollen gewöhnliche Tools des Orchestrators werden. Im Web überlässt die Anzeige
  dabei Keycloak die Arbeit.
- [Black-Box-Contract-Tests](black-box-contract-tests.md): Eine Testsuite, die nur über HTTP von
  außen prüft. Sie soll zeigen, ob sich eine Neuentwicklung genauso verhält wie die heutige
  Implementierung.
- [Abschied von alten Fassungen](tool-versionen.md): Eine alte Fassung eines Tools beobachten und
  ausbauen und alten Clients sagen, dass sie aktualisieren müssen. Die zweite Fassung selbst ist
  gebaut (`enroll-sms@2`, ADR-51).
