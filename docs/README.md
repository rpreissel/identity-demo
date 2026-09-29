# Dokumentation – Übersicht

Neu hier? Beginnen Sie mit dem [Überblick](01-ueberblick.md). Er erklärt, worum es geht, wer
beteiligt ist und die wichtigsten Begriffe, und nennt Lesepfade je Rolle.

Die Doku beschreibt das Zielbild. Weichen Code und Doku voneinander ab, gilt die Doku.

---

## Einstieg

- **[01-ueberblick.md](01-ueberblick.md)**: Worum geht es, wer ist beteiligt, was bedeuten die
  Begriffe, und was lese ich als Nächstes?
- **[11-beispiel-story.md](11-beispiel-story.md)**: Wie sieht das für eine einzelne Person aus,
  von der Registrierung über Login, Step-up und QR-Login bis zur Löschung?
- **[00-agent-quickstart.md](00-agent-quickstart.md)**: Was muss ein KI-Agent wissen, bevor er
  gezielt einzelne Kapitel öffnet?

## Fachliches Modell

- **[02-domaenenmodell.md](02-domaenenmodell.md)**: Welche Entitäten, Zustände und Tabellen gibt
  es, und nach welchen Regeln wird gespeichert?
- **[04-orchestrierung.md](04-orchestrierung.md)**: Wer entscheidet, wie es weitergeht? Ziele
  (Intents), Journeys, `AuthPolicy`, Sicherheitsniveaus.
- **[06-ablaeufe.md](06-ablaeufe.md)**: Wie laufen die einzelnen Verfahren Schritt für Schritt ab
  (Freischaltcode, SMS, Passwort, E-Mail, Gerät, eID/KVNR, KOBIL)?

## Technik

- **[03-tool-architektur.md](03-tool-architektur.md)**: Wie bindet man ein neues Verfahren an?
  Tool-Katalog, Selbstbeschreibung, `ToolOutcome`, Modulgrenzen.
- **[05-api.md](05-api.md)**: Wie sprechen App und Keycloak mit dem Orchestrator?
- **[09-dpop.md](09-dpop.md)**: Wie wird ein Kanal an den Schlüssel des Geräts gebunden?
- **[10-frontend.md](10-frontend.md)**: Was muss die Oberfläche leisten, und wie folgt sie `next`?
- **[08-projektrahmen.md](08-projektrahmen.md)**: Welche Module gibt es, wie hängen sie zusammen,
  welche Technik und Versionen werden genutzt?

## Betrieb und Ausführen

- **[13-ausfuehren.md](13-ausfuehren.md)**: Wie baue, starte und teste ich das System, auch in
  Containern?
- **[07-betrieb.md](07-betrieb.md)**: Welche Fehler meldet das System, was ist transaktional
  zugesagt, wie lange werden Daten aufbewahrt, was muss außerhalb des Demomodus gesetzt sein?

## Entscheidungen

- **[12-entscheidungen.md](12-entscheidungen.md)**: Warum ist das so? Der Index aller
  Architekturentscheidungen.
- **[adr/](adr/)**: Eine Datei je Entscheidung, mit erwogener Alternative und ihrem Preis.
- **[review-2026-09-29-vierte-bewertung.md](review-2026-09-29-vierte-bewertung.md)**: Wo steht
  der Kern heute? Befunde zu Sicherheit, Keycloak, Architektur und Codequalität mit
  Gegenmaßnahmen und Reihenfolge.

## Nachschlagen

- **[journeys/](journeys/)**: Wie läuft genau ein Ziel ab? Ein Zustandsdiagramm je Intent, gegen den
  Code geprüft.
- **[invarianten.md](invarianten.md)**: Auf welche Regeln verlässt sich der Kern, und womit ist
  jede gesichert?
- **[port-vertraege.md](port-vertraege.md)**: Was muss ein echtes Fremdsystem zusagen, bevor es ein
  simuliertes ersetzt?
- **[glossar/glossar.md](glossar/glossar.md)**: Was bedeuten die Fachbegriffe zu Authentifizierung,
  Identifizierung und Gerätebindung? Externes Glossar, unverändert übernommen.
- **[glossar/abgleich.md](glossar/abgleich.md)**: Wie heißen die Begriffe des Glossars in diesem
  Projekt, und wo weicht es bewusst ab?
- Die wichtigsten Begriffe des Projekts und ihre Namen im Code stehen im
  [Überblick](01-ueberblick.md), Abschnitt 3.

## Ideen

- **[ideen/](ideen/)**: Welche Überlegungen sind noch nicht entschieden? Lesen, bevor man ein
  größeres Redesign neu durchdenkt.
