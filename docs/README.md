# Dokumentation – Übersicht

Neu hier? Beginnen Sie mit dem [Überblick](01-ueberblick.md). Er erklärt, worum es geht, das
Zielbild mit seinen Komponenten und die wichtigsten Begriffe, und nennt Lesepfade je Rolle.

Die Doku beschreibt das Zielbild. Weichen Code und Doku voneinander ab, gilt die Doku.

Die Doku gibt es auch als Website mit Suche: <https://rpreissel.github.io/identity-demo/>.

---

## Einstieg

- **[Überblick](01-ueberblick.md)**: Worum geht es, was bedeuten die Begriffe, und was lese ich als
  Nächstes?
- **[Das Zielbild](01-ueberblick.md#2-das-zielbild-komponenten-und-zusammenspiel)**: Welche
  Komponenten gibt es, wo liegen die Vertrauensgrenzen, wem gehören welche Daten, und wie laufen
  App, Website und Vorgangszugang ab?
- **[Beispiel-Story](11-beispiel-story.md)**: Wie sieht das für eine einzelne Person aus, von der
  Registrierung über Login, Step-up und QR-Login bis zur Löschung?
- **[Beispiel für Backend-Entwickler](15-beispiel-neues-verfahren-backend.md)**: Was berührt ein
  neues Verfahren im Server und im Web-Kanal? Ein Bank-Ident und eine Einmalcode-App werden
  angebunden: Tools, Keycloak-Erweiterung, Login-Theme, Tests.
- **[Beispiel für App-Entwickler](17-beispiel-neues-verfahren-app.md)**: Dieselben zwei Verfahren in
  der App: Schritte darstellen, Weiterleitung, Fassungen, Tests.
- **[Schnelleinstieg für Agents](00-agent-quickstart.md)**: Was muss ein KI-Agent wissen, bevor er
  gezielt einzelne Kapitel öffnet?

## Videos

Das **Erklärvideo**, gut fünf Minuten, gesprochen: Konzepte, Sicherheitsniveaus, Aufbau des Codes,
Umsetzungsstand, Vor- und Nachteile.

[Erklärvideo (mp4)](media/erklaervideo.mp4)

Das **Demo-Video**, knapp acht Minuten, gesprochen und mit Untertiteln: die Aufgaben der
Willkommensseite im Browser. Registrieren, QR-Code und Passwort einrichten, auf der Website anmelden und
für die Gesundheitsdaten mit der App bestätigen, den Namen ändern, einen Vorgang mit Einmalkennwort
erledigen, der Journey-Trace und das Löschen des Kontos.

[Demo-Video (mp4)](media/demo.mp4)

## Fachliches Modell

- **[Domänenmodell](02-domaenenmodell.md)**: Welche Entitäten, Zustände und Tabellen gibt es, und
  nach welchen Regeln wird gespeichert?
- **[Orchestrierung und Policy](04-orchestrierung.md)**: Wer entscheidet, wie es weitergeht?
  Intents, Journeys, `AuthPolicy`, Sicherheitsniveaus.
- **[Verfahren: Datenmodell und Übersicht](06-ablaeufe.md)**: Welches Datenmodell teilen alle
  Verfahren (Konto, Anker, Claims, Änderungsprotokoll, eingerichtete Verfahren)?
- **[Die Verfahren](verfahren/README.md)**: Wie läuft jedes einzelne Verfahren Schritt für Schritt
  ab? Je Verfahren eine Seite: Freischaltcode, eID, Nect, KVNR, SMS, Passwort, E-Mail, Gerät, KOBIL,
  QR-Code, Einmalkennwort.

## Technik

- **[Tool-Architektur](03-tool-architektur.md)**: Wie bindet man ein neues Verfahren an?
  Selbstbeschreibung, `ToolOutcome`, Rollen, Angebot, Modulgrenzen, Tool-Katalog.
- **[API](05-api.md)**: Wie sprechen App und Keycloak mit dem Orchestrator? Erst die allgemeinen
  Mechanismen, dann für Tool-Entwickler, dann für Orchestrator-Entwickler (App- und Web-Kanal).
- **[DPoP-Bindung](09-dpop.md)**: Wie wird ein Kanal an den Schlüssel des Geräts gebunden?
- **[Frontend](10-frontend.md)**: Was muss die Oberfläche leisten, und wie folgt sie `next`?
- **[Projektrahmen](08-projektrahmen.md)**: Welche Module gibt es, wie hängen sie zusammen, welche
  Technik und Versionen werden genutzt?

## Betrieb und Ausführen

- **[Ausführen und bauen](13-ausfuehren.md)**: Wie baue, starte und teste ich das System, auch in
  Containern?
- **[Betrieb](07-betrieb.md)**: Welche Fehler meldet das System, was ist transaktional zugesagt, wie
  lange werden Daten aufbewahrt, was muss außerhalb des Demomodus gesetzt sein, und wie beobachtet
  man den Zustand?

## Entscheidungen

- **[Architekturentscheidungen](12-entscheidungen.md)**: Warum ist das so? Der Index aller
  Architekturentscheidungen.
- **[ADR](adr/)**: Eine Datei je Entscheidung, mit erwogener Alternative und ihrem Preis.
- **[Lesepfad Sicherheit](16-lesepfad-sicherheit.md)**: Wo greife ich an? Ein Weg durch Doku und
  Code mit Links auf die kritischen Stellen, die Härtungen und die offenen Flanken.
- **[Offene Befunde](offene-befunde.md)**: Was ist aus den Bewertungen noch offen? Befunde zu
  Sicherheit, Keycloak, Architektur und Codequalität, Restrisiken und offene Entscheidungen.

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
  steht hinter einem Namen im Code (`AuthJourney`, `acrFloor`, `auth-invite-lookup`)? Englisch sortiert,
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
