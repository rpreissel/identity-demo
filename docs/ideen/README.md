# Ideen

Überlegungen, die noch nicht entschieden sind. Lesen, bevor man ein größeres Redesign neu
durchdenkt.

- [Verfahren aufwerten](reidentify-methoden-upgrade.md): Anmeldeverfahren nach erneuter oder
  erstmaliger Identifizierung (`RE_IDENTIFY`) aufwerten.
- [Umschlagverschlüsselung](verschluesselung-differenzierte-aufbewahrung.md): Unterschiedliche
  Aufbewahrung und Widerrufe per Umschlagverschlüsselung.
- [Verfahren ändern](verfahren-aendern.md): Ein eingerichtetes Verfahren ersetzen (neues
  Passwort, neue Nummer) und in der Verwaltung einen frischen Nachweis verlangen, wenn der letzte
  älter als fünf Minuten ist.
- [Native Verfahren als Tools](native-verfahren-als-tools.md): Keycloaks eigene Verfahren
  (Passkey, OTP) als Tools des Orchestrators führen; im Web delegiert die Anzeige an Keycloak.
- [Black-Box-Contract-Tests](black-box-contract-tests.md): Eine Testsuite, die nur über HTTP
  prüft, ob sich eine Reimplementierung wie die heutige Implementierung verhält.
- [Orchestrator und Tools einzeln versionieren](tool-versionen.md): Beide tragen ihre Version als
  Segment im Pfad. Ein Tool läuft in zwei Fassungen nebeneinander, wenn mehrere App-Versionen im
  Einsatz sind; eine neue Orchestrator-Version ist ein Pflichtupdate.
