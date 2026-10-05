# Ideen

In diesem Ordner stehen Überlegungen, über die noch nicht entschieden ist. Jedes Dokument sagt
am Anfang, welches Problem es betrachtet, warum das wichtig ist und wie der Stand ist. Lesen Sie
die passende Idee, bevor Sie ein größeres Redesign neu durchdenken. Oft ist ein Teil der Fragen
dort schon durchgespielt.

Begriffe wie Journey, Tool oder Niveau erklärt das [Glossar](../glossar/glossar.md).

- [Verfahren aufwerten](reidentify-methoden-upgrade.md): Ein Anmeldeverfahren soll mehr zählen
  dürfen, nachdem sich der Nutzer erneut oder erstmals identifiziert hat (Sub-Journey
  `RE_IDENTIFY`).
- [Umschlagverschlüsselung](verschluesselung-differenzierte-aufbewahrung.md): Mit einer
  Umschlagverschlüsselung lassen sich einzelne Daten eines Kontos unterschiedlich lange
  aufbewahren und gezielt unlesbar machen, etwa nach einem Widerruf.
- [Verfahren ändern](verfahren-aendern.md): Ein eingerichtetes Verfahren ersetzen, etwa durch ein
  neues Passwort oder eine neue Telefonnummer. Dazu verlangt die Verwaltung einen frischen
  Nachweis, wenn der letzte älter als fünf Minuten ist. Diese Idee ist inzwischen umgesetzt.
- [Native Verfahren als Tools](native-verfahren-als-tools.md): Keycloaks eigene Verfahren
  (Passkey, OTP) sollen gewöhnliche Tools des Orchestrators werden. Im Web überlässt die Anzeige
  dabei Keycloak die Arbeit.
- [Black-Box-Contract-Tests](black-box-contract-tests.md): Eine Testsuite, die nur über HTTP von
  außen prüft. Sie soll zeigen, ob sich eine Neuentwicklung genauso verhält wie die heutige
  Implementierung.
- [Zweite Fassung eines Tools](tool-versionen.md): Ein Tool in zwei Fassungen nebeneinander
  führen, die alte später abschalten und ausbauen und alte Clients auf ein Update hinweisen. Wie
  die Versionierung selbst funktioniert, steht in ADR-51.
