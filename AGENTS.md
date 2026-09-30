# Projektspezifische Agent-Anweisungen

## Projekt auf einen Blick

- **Projekt**: `identity-demo` (Spring Boot Modulith + React/TypeScript).
- **Arbeitsverzeichnis**: das Wurzelverzeichnis dieses Repositorys
- **Maßgebliche fachliche Quelle**: `docs/` (das Zielbild hat Vorrang vor dem aktuellen Code).

## Wo man anfängt

Immer zuerst [docs/00-agent-quickstart.md](docs/00-agent-quickstart.md) lesen. Von dort führen
Verweise weiter; die Inhalte stehen jeweils nur an einer Stelle:

- [docs/01-ueberblick.md](docs/01-ueberblick.md): Einstieg und Begriffe.
- [docs/README.md](docs/README.md): Karte der Doku, Lesepfade je Rolle.
- [docs/13-ausfuehren.md](docs/13-ausfuehren.md): bauen, starten, testen.
- [docs/08-projektrahmen.md](docs/08-projektrahmen.md) Abschnitt 3: Modulgrenzen, Fachkern und Technik.

## Dokumentationsregeln

- Neue Anforderungen in das **thematisch passende** Dokument einarbeiten.
- Den Wortlaut eines Prompts nicht zitieren, sondern in geordnete Aussagen umsetzen.
- Widersprechen sich Code und Doku, gilt das dokumentierte Zielbild.

## Kommentarregeln

Ein Kommentar hilft, den Code zu verstehen, er ersetzt nicht die Doku. Deshalb:

- **Was und warum, im Präsens.** Ein Kommentar sagt, was etwas ist und warum es so sein muss,
  nicht, wie es dazu kam. Keine Geschichte („früher“, „seit“, „nicht mehr“), keine
  Herkunftsangaben aus Reviews.
- **Kurz.** Ein Klassen- oder Funktionskommentar hat in der Regel höchstens fünf Zeilen. Wer mehr
  braucht, schreibt die Begründung in eine ADR oder ins passende Kapitel und verlinkt sie.
- **Nur die eigene Regel.** Regeln, die an anderer Stelle gelten, werden verlinkt, nicht
  nacherzählt, und fremde Methoden werden nicht aufgezählt: Solche Listen veralten unbemerkt.
- **Absicherung gehört in den Test.** Warum ein Zweig etwas ausdrücklich *nicht* tut, sagt ein
  Satz im Code und ein Testname, keine Verteidigungsrede im Kommentar.
- **Nichts Selbstverständliches.** Was Code, Name oder Typ schon sagen, und wie ein Framework
  funktioniert, steht nicht im Kommentar.
- **Ruhiger Stil.** Kurze Sätze. Betonung durch Satzbau, nicht durch Großbuchstaben. Wenige
  Einschübe mit Gedankenstrich oder Klammer.
- **Was bleibt:** das Warum hinter einer Sicherheitsregel, eine zwingende Reihenfolge, eine nicht
  offensichtliche Annahme, die Rolle einer Klasse im Zusammenspiel.

## Testregeln

- **given, when, then.** In einer `BehaviorSpec` beschreibt `given` die Ausgangslage (Zustand,
  Daten), `` `when` `` die eine Handlung (Aufruf, Ereignis) und `then` das erwartete Ergebnis. Die
  Handlung steht im `when` und läuft dort einmal; ein `then` prüft nur.
- **Ohne Handlung kein `when`.** Wer nur Eigenschaften oder Konstanten prüft (etwa
  `AttributeRulesTest`), schreibt `given` und `then`.
- **Strategie-Tests.** `given` ist der Journey-Zustand samt Kontext, `when` das `JourneyEvent`,
  `then` die erwartete `Transition`. Ein Folgeschritt, etwa `ActionCompleted` nach einem
  `Perform`, ist ein eigenes `when` unter demselben `given`; Kotest erlaubt kein `when` im `when`.
  Ein anderer Kontext ist ein eigenes `given`.
- **Erwartete Ausnahme.** Das `when` fängt sie mit `runCatching { … }` auf, das `then` prüft sie
  mit `shouldThrow { result.getOrThrow() }`. Sonst bräche schon der Aufbau des Containers ab.
- **Specs mit Spring-Kontext.** `IntegrationTestSupport` leert die Datenbank per Voreinstellung vor
  jedem `then`; eine Handlung im `when` wäre dann schon gelöscht. Neue Specs setzen
  `override val resetPerWhen = true` (geleert wird vor jedem `when`) und stellen Stubs, die das
  `when` braucht, mit `beforeScenario { … }` statt `beforeEach` bereit. Ältere Specs handeln noch
  im `then`; sie werden nach und nach umgestellt.

## Arbeitsregeln (kurz)

- Nichts global installieren ohne ausdrückliche Freigabe.
- Kein Push und keine anderen Remote-Operationen ohne ausdrückliche Anweisung.
- Vor einem Commit nur die gewollten Dateien vormerken (`git add`) und Status und Diff prüfen.
- Lang laufende Prozesse (`bootRun`) möglichst durch Tests ersetzen oder im Hintergrund starten.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:46cd31e7 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/core-concepts/sync-concepts.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   bd dolt push
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->

<!-- BEGIN BEADS CODEX SETUP: generated by bd setup codex -->
## Beads Issue Tracker

Use Beads (`bd`) for durable task tracking in repositories that include it. Use the `beads` skill at `.agents/skills/beads/SKILL.md` (project install) or `~/.agents/skills/beads/SKILL.md` (global install) for Beads workflow guidance, then use the `bd` CLI for issue operations.

### Quick Reference

```bash
bd ready                # Find available work
bd show <id>            # View issue details
bd update <id> --claim  # Claim work
bd close <id>           # Complete work
bd prime                # Refresh Beads context
```

### Rules

- Use `bd` for all task tracking; do not create markdown TODO lists.
- Run `bd prime` when Beads context is missing or stale. Codex 0.129.0+ can load Beads context automatically through native hooks; use `/hooks` to inspect or toggle them.
- Keep persistent project memory in Beads via `bd remember`; do not create ad hoc memory files.

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/core-concepts/sync-concepts.md for details and anti-patterns.
<!-- END BEADS CODEX SETUP -->
