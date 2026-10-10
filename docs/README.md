# Dokumentation – Übersicht

Neu hier? Beginnen Sie mit dem [Überblick](01-ueberblick.md). Er erklärt, worum es im Projekt
geht. Er beschreibt das Zielbild mit seinen Komponenten und die wichtigsten Begriffe. Außerdem
schlägt er für jede Rolle eine Reihenfolge zum Lesen vor.

Die Doku beschreibt das Zielbild, also den Zustand, den das System erreichen soll. Wenn Code und
Doku voneinander abweichen, gilt die Doku.

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
- **[Beispiel für Backend-Entwickler](15-beispiel-neues-verfahren-backend.md)**: Was muss man im
  Server und im Web-Kanal ändern, um ein neues Verfahren einzubauen? Das Beispiel bindet eine
  Identifizierung über die Bank (Bank-Ident) und eine App für Einmalcodes an. Es zeigt die Tools, die
  Keycloak-Erweiterung, das Login-Theme und die Tests.
- **[Beispiel für App-Entwickler](17-beispiel-neues-verfahren-app.md)**: Dieselben zwei Verfahren in
  der App. Es zeigt, wie die App die Schritte darstellt, wie die Weiterleitung funktioniert, wie sie mit
  verschiedenen Fassungen (Versionen) eines Tools umgeht und wie man testet.
- **[Schnelleinstieg für Agents](00-agent-quickstart.md)**: Was muss ein KI-Agent wissen, bevor er
  gezielt einzelne Kapitel öffnet?

## Videos

Das **Erklärvideo** dauert gut fünf Minuten und ist gesprochen. Es erklärt die Konzepte, die
Sicherheitsniveaus, den Aufbau des Codes, den Stand der Umsetzung sowie Vor- und Nachteile.

[Erklärvideo (mp4)](media/erklaervideo.mp4)

Das **Demo-Video** dauert knapp acht Minuten, ist gesprochen und hat Untertitel. Es führt die
Aufgaben der Willkommensseite im Browser vor:

- registrieren, QR-Code und Passwort einrichten,
- auf der Website anmelden und für die Gesundheitsdaten mit der App bestätigen,
- den Namen ändern,
- einen Vorgang mit Einmalkennwort erledigen,
- den Journey-Trace ansehen, also die Aufzeichnung der Schritte einer Journey,
- das Konto löschen.

[Demo-Video (mp4)](media/demo.mp4)

## Fachliches Modell

- **[Domänenmodell](02-domaenenmodell.md)**: Welche Entitäten, Zustände und Tabellen gibt es, und
  nach welchen Regeln wird gespeichert?
- **[Orchestrierung und Policy](04-orchestrierung.md)**: Wer entscheidet, wie es weitergeht?
  Intents, Journeys, `AuthPolicy`, Sicherheitsniveaus.
- **[Verfahren: Datenmodell und Übersicht](06-ablaeufe.md)**: Welche Daten nutzen alle Verfahren
  gemeinsam? Dazu gehören Konto, Anker, Angaben (Claims), Änderungsprotokoll und eingerichtete
  Verfahren.
- **[Die Verfahren](verfahren/README.md)**: Wie läuft jedes einzelne Verfahren Schritt für Schritt
  ab? Je Verfahren eine Seite: Freischaltcode, eID, Nect, KVNR, SMS, Passwort, E-Mail, Gerät, KOBIL,
  QR-Code, Einmalkennwort.

## Technik

- **[Tool-Architektur](03-tool-architektur.md)**: Wie bindet man ein neues Verfahren an? Es geht um
  die Selbstbeschreibung der Tools, ihr Ergebnis (`ToolOutcome`), ihre Rollen, das Angebot an den
  Nutzer, die Grenzen der Module und den Tool-Katalog.
- **[API](05-api.md)**: Wie sprechen App und Keycloak mit dem Orchestrator? Erst die allgemeinen
  Mechanismen, dann für Tool-Entwickler, dann für Orchestrator-Entwickler (App- und Web-Kanal).
- **[DPoP-Bindung](09-dpop.md)**: Wie wird ein Kanal an den Schlüssel des Geräts gebunden?
- **[Frontend](10-frontend.md)**: Was muss die Oberfläche leisten, und wie folgt sie `next`?
- **[Projektrahmen](08-projektrahmen.md)**: Welche Module gibt es, welche Abhängigkeiten haben sie
  untereinander, und welche Technik und Versionen werden genutzt?

## Betrieb und Ausführen

- **[Ausführen und bauen](13-ausfuehren.md)**: Wie baue, starte und teste ich das System, auch in
  Containern?
- **[Betrieb](07-betrieb.md)**: Welche Fehler meldet das System, was ist transaktional zugesagt, wie
  lange werden Daten aufbewahrt, was muss außerhalb des Demomodus gesetzt sein, und wie beobachtet
  man den Zustand?

## Entscheidungen

- **[Architekturentscheidungen](12-entscheidungen.md)**: Warum ist das so? Der Index aller
  Architekturentscheidungen.
- **[ADR](adr/)**: Eine Datei je Architekturentscheidung (Architecture Decision Record). Jede nennt
  auch die erwogene Alternative und ihre Nachteile.
- **[Lesepfad Sicherheit](16-lesepfad-sicherheit.md)**: Wo wäre das System angreifbar? Ein Weg durch
  Doku und Code mit Links auf die kritischen Stellen, die Schutzmaßnahmen und die noch offenen
  Schwachstellen.
- **[Offene Befunde](offene-befunde.md)**: Was ist aus den Bewertungen noch offen? Befunde zu
  Sicherheit, Keycloak, Architektur und Codequalität, Restrisiken und offene Entscheidungen.
- **[Audit 2026-10-09](review-2026-10-09-audit.md)**: Was hat das jüngste Audit nach ADR-58 und
  ADR-59 gefunden? Befunde zu Sicherheit und Architektur, Vorschläge zur Vereinfachung des Codes.

## Nachschlagen

- **[Journeys](journeys/)**: Wie läuft genau ein Intent ab? Für jeden Intent gibt es ein
  Zustandsdiagramm, das ein Test gegen den Code prüft.
- **[Invarianten](invarianten.md)**: Auf welche festen Regeln verlässt sich der Kern, und welcher
  Test oder Mechanismus sichert jede davon?
- **[Port-Verträge](port-vertraege.md)**: Was muss ein echtes Fremdsystem zusagen, bevor es ein
  simuliertes ersetzt?
- **[Glossar](glossar/glossar.md)**: Was bedeutet ein Begriff des Projekts, wie heißt er im Code,
  und wo steht er ausführlich? Alphabetisch.
- **[Glossar nach englischen Begriffen](glossar/glossar-englisch.md)**: Welcher deutsche Begriff
  gehört zu einem Namen im Code, etwa `AuthJourney`, `acrFloor` oder `auth-invite-lookup`? Nach den
  englischen Namen sortiert, auf Deutsch beschrieben.
- **[Externes Glossar](glossar/externes-glossar.md)**: Was bedeuten die Fachbegriffe zu
  Authentifizierung, Identifizierung und Gerätebindung? Externes Glossar, unverändert übernommen.
- **[Abgleich mit dem externen Glossar](glossar/abgleich-externes-glossar.md)**: Wie heißen die
  Begriffe des externen Glossars in diesem Projekt, und wo weicht es bewusst ab?
- Die wichtigsten Begriffe zum Einstieg stehen im [Überblick](01-ueberblick.md), Abschnitt 3.

## Stand und Ausblick

- **[Stand und Weg zur Produktion](14-stand-und-weg-zur-produktion.md)**: Was soll das Projekt
  leisten? Was ist bereit für den echten Betrieb, was ist nur Demo? Was fehlt noch, bevor echte
  Personendaten verarbeitet werden, und wie geht es weiter? Was müsste sich bei 20 Millionen Konten
  und 1 Million Anmeldungen am Tag ändern?

## Ideen

- **[Ideen](ideen/)**: Welche Überlegungen sind noch nicht entschieden? Lesen Sie diese Seiten,
  bevor Sie einen größeren Umbau neu durchdenken.
