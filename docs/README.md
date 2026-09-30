# Dokumentation – Übersicht

Neu hier? Beginnen Sie mit dem [Überblick](01-ueberblick.md). Er erklärt, worum es geht, wer
beteiligt ist und die wichtigsten Begriffe, und nennt Lesepfade je Rolle.

Die Doku beschreibt das Zielbild. Weichen Code und Doku voneinander ab, gilt die Doku.

Die Doku gibt es auch als Website mit Suche: <https://rpreissel.github.io/identity-demo/>.

---

## Demo-Video

Knapp sieben Minuten, mit Untertiteln: registrieren, per QR-Code anmelden, Sitzungen in Keycloak,
Namensänderung, Journey-Trace und das Löschen des Kontos.

[Demo-Video ansehen (mp4)](media/demo.mp4)

## Einstieg

- **[Überblick](01-ueberblick.md)**: Worum geht es, wer ist beteiligt, was bedeuten die Begriffe,
  und was lese ich als Nächstes?
- **[Beispiel-Story](11-beispiel-story.md)**: Wie sieht das für eine einzelne Person aus, von der
  Registrierung über Login, Step-up und QR-Login bis zur Löschung?
- **[Schnelleinstieg für Agents](00-agent-quickstart.md)**: Was muss ein KI-Agent wissen, bevor er
  gezielt einzelne Kapitel öffnet?

## Fachliches Modell

- **[Domänenmodell](02-domaenenmodell.md)**: Welche Entitäten, Zustände und Tabellen gibt es, und
  nach welchen Regeln wird gespeichert?
- **[Orchestrierung und Policy](04-orchestrierung.md)**: Wer entscheidet, wie es weitergeht?
  Intents, Journeys, `AuthPolicy`, Sicherheitsniveaus.
- **[Konkrete Abläufe](06-ablaeufe.md)**: Wie laufen die einzelnen Verfahren Schritt für Schritt ab
  (Freischaltcode, SMS, Passwort, E-Mail, Gerät, eID/KVNR, KOBIL, QR-Code, Einmalkennwort)?

## Technik

- **[Tool-Architektur](03-tool-architektur.md)**: Wie bindet man ein neues Verfahren an?
  Tool-Katalog, Selbstbeschreibung, `ToolOutcome`, Modulgrenzen.
- **[API](05-api.md)**: Wie sprechen App und Keycloak mit dem Orchestrator?
- **[DPoP-Bindung](09-dpop.md)**: Wie wird ein Kanal an den Schlüssel des Geräts gebunden?
- **[Frontend](10-frontend.md)**: Was muss die Oberfläche leisten, und wie folgt sie `next`?
- **[Projektrahmen](08-projektrahmen.md)**: Welche Module gibt es, wie hängen sie zusammen, welche
  Technik und Versionen werden genutzt?

## Betrieb und Ausführen

- **[Ausführen und bauen](13-ausfuehren.md)**: Wie baue, starte und teste ich das System, auch in
  Containern?
- **[Betrieb](07-betrieb.md)**: Welche Fehler meldet das System, was ist transaktional zugesagt, wie
  lange werden Daten aufbewahrt, was muss außerhalb des Demomodus gesetzt sein?

## Entscheidungen

- **[Architekturentscheidungen](12-entscheidungen.md)**: Warum ist das so? Der Index aller
  Architekturentscheidungen.
- **[ADR](adr/)**: Eine Datei je Entscheidung, mit erwogener Alternative und ihrem Preis.
- **[Vierte Bewertung (2026-09-29)](review-2026-09-29-vierte-bewertung.md)**: Wo steht der Kern
  heute? Befunde zu Sicherheit, Keycloak, Architektur und Codequalität mit Gegenmaßnahmen und
  Reihenfolge.

## Nachschlagen

- **[Journeys](journeys/)**: Wie läuft genau ein Intent ab? Ein Zustandsdiagramm je Intent, gegen den
  Code geprüft.
- **[Invarianten](invarianten.md)**: Auf welche Regeln verlässt sich der Kern, und womit ist jede
  gesichert?
- **[Port-Verträge](port-vertraege.md)**: Was muss ein echtes Fremdsystem zusagen, bevor es ein
  simuliertes ersetzt?
- **[Glossar](glossar/glossar.md)**: Was bedeutet ein Begriff des Projekts, wie heißt er im Code,
  und wo steht er ausführlich? Alphabetisch.
- **[Glossar nach englischen Begriffen](glossar/glossar-englisch.md)**: Welcher deutsche Begriff
  steht hinter einem Namen im Code (`AuthJourney`, `acrFloor`, `auth-invite`)? Englisch sortiert,
  deutsch beschrieben.
- **[Externes Glossar](glossar/externes-glossar.md)**: Was bedeuten die Fachbegriffe zu
  Authentifizierung, Identifizierung und Gerätebindung? Externes Glossar, unverändert übernommen.
- **[Abgleich mit dem externen Glossar](glossar/abgleich-externes-glossar.md)**: Wie heißen die
  Begriffe des externen Glossars in diesem Projekt, und wo weicht es bewusst ab?
- Die wichtigsten Begriffe zum Einstieg stehen im [Überblick](01-ueberblick.md), Abschnitt 3.

## Stand und Ausblick

- **[Stand und Weg zur Produktion](14-stand-und-weg-zur-produktion.md)**: Was soll das Projekt
  leisten, was ist produktionsreif, was nur Demo, was fehlt vor echten Personendaten, und wie geht
  es weiter?

## Ideen

- **[Ideen](ideen/)**: Welche Überlegungen sind noch nicht entschieden? Lesen, bevor man ein
  größeres Redesign neu durchdenkt.
