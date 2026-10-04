# Schnelleinstieg für Agents

Die erste Datei einer Sitzung. Sie sagt, wo die Regeln stehen, wo man was findet und wie man prüft;
den Rest liest man nur bei Bedarf.

## 1) Das Projekt

`identity-demo` zeigt Registrierung und Anmeldung, abgesichert mit DPoP und ausgerichtet auf Keycloak:
ein Spring-Boot-Modulith in Kotlin mit Frontend in React/TypeScript. Der Orchestrator steuert die
Journeys; die Verfahren hängen als eigene Module nur über Schnittstellen (`tool_api`)
an ihm.

## 2) Wo die Regeln stehen

- **Arbeitsregeln für Agents:** [AGENTS.md](../AGENTS.md) (Doku-Regeln, Git, Aufgabenverwaltung
  mit `bd`).
- **Fachliche Regeln:** `docs/`. Die Doku beschreibt das Zielbild; weichen Code und Doku
  voneinander ab, hat die Doku Vorrang.
- **Architekturregeln:** [08-projektrahmen.md](08-projektrahmen.md) Abschnitt 3 (Modulgrenzen,
  [Fachkern und Technik](08-projektrahmen.md#fachkern-und-technik)); Begründungen im Index
  [12-entscheidungen.md](12-entscheidungen.md) → `adr/ADR-NNN-*.md`.
- **Invarianten:** [invarianten.md](invarianten.md).

## 3) Wo man was findet

- **Einstieg, Zielbild und Begriffe:** [01-ueberblick.md](01-ueberblick.md) (Zielbild: Abschnitt 2).
- **Karte aller Kapitel und Lesepfade je Rolle:** [README.md](README.md).
- **Bauen, starten, testen:** [13-ausfuehren.md](13-ausfuehren.md).
- **Ein einzelner Ablauf:** `journeys/<intent>.md` statt des ganzen Kapitels 04.
- **Wo man im Code zu lesen anfängt:** [08-projektrahmen.md](08-projektrahmen.md#fachkern-und-technik),
  „Wo man zu lesen anfängt“.

Nur die Kapitel öffnen, die man für die Aufgabe braucht.

## 4) Prüfen

```bash
./gradlew test                        # Backend: Unit-, Integrations- und Architekturtests
./gradlew quickTest                   # Zwischenstand: ohne Specs mit Spring-Kontext
./gradlew :test --tests '*NameTest'   # eine Testklasse (mit `:`, sonst sucht auch keycloak-extension)
./gradlew :keycloak-extension:test    # Keycloak-Erweiterung
./gradlew build                       # alles bauen, alle Tests
```

Im Verzeichnis `frontend`: `npm test` (Vitest), `npx tsc -b` (Typen; Vitest prüft keine),
`npm run lint`, `npm run test:e2e` (Playwright, startet selbst ein Backend auf Port 8091).
Einzelheiten: [13-ausfuehren.md](13-ausfuehren.md) Abschnitt 7.

## 5) Stolperstellen

- **Tests lesen die Doku.** `JourneyDiagramsTest` prüft die Zustandsdiagramme in `journeys/` gegen
  den Code, `InvariantRegisterTest` liest [invarianten.md](invarianten.md). Wer dort etwas ändert,
  lässt die Tests laufen.
- **API-Vertrag.** Ändert sich eine Route oder ein DTO, zuerst `./gradlew updateOpenApiSnapshot`,
  danach `./gradlew generateFrontendApiTypes` ([05-api.md](05-api.md) Abschnitt 4).
  `checkPublishedApiCompatibility` schlägt fehl, wenn der Vertrag den veröffentlichten Stand bricht.
- **Nutzertexte.** Die deutsche Vorlage steht im Code (`Text("…")`, im Frontend `t("…")`); die
  ausgelieferten Sprachdateien schreibt `/translate-texts` (ADR-33). Ist `TextTranslationsTest`
  rot, diesen Skill ausführen, nicht die Sprachdateien von Hand ändern.
- **Architekturtests.** `ApplicationModules.verify()`, `OrchestratorArchitectureTest` und
  `AccountArchitectureTest` lassen den Build scheitern, wenn eine Abhängigkeit die Modul- oder
  Paketgrenzen verletzt ([08-projektrahmen.md](08-projektrahmen.md) Abschnitte 3 und 7).
- **Lange Läufe.** `./gradlew bootRun` endet nicht von selbst: lieber einen Integrationstest
  schreiben oder im Hintergrund starten. Nie zwei Gradle-Läufe gleichzeitig im selben Verzeichnis.
- **Podman auf macOS** bindet nur `/Users` ein; keine Bind-Mounts aus `/tmp`
  ([13-ausfuehren.md](13-ausfuehren.md) Abschnitt 1).
