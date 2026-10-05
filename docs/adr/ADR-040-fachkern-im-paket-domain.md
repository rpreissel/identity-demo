# ADR-40: Der fachliche Kern liegt im Paket `domain`, ohne Framework, per ArchUnit geprüft

**Status:** umgesetzt 2026-09-26.

**Entscheidung.** Wer verstehen will, nach welchen Regeln der Orchestrator entscheidet, soll diese
Regeln an einer Stelle finden. Sie sollen nicht zwischen Datenbankzugriff, Spring-Konfiguration und
Logging verteilt sein. Deshalb gilt:

In den Modulen `orchestrator` und `account` liegt alles, was ein Entwickler lesen muss, um die Regeln
zu verstehen, in einem Paket `domain`. Dieses Paket benutzt kein Framework, also kein Spring, kein
JPA, kein Jackson und kein Logging. Es hängt außerdem von nichts anderem im eigenen Modul ab. Eine
Konvention allein reicht dafür nicht. Deshalb prüft ArchUnit beides, ein Werkzeug, das
Architekturregeln als Tests ausdrückt (`OrchestratorArchitectureTest`, `AccountArchitectureTest`).

## Was wo liegt

- **`orchestrator.domain`** (früher `kernel`, [ADR-27](ADR-027-gemeinsame-typen-im-kernel-paket.md)):
  - das Vokabular: `AuthIntent`, die Niveaus (ACR), der Kanaltyp, die Fehlercodes und
    `ToolCatalog`;
  - `domain.journey`: `IntentStrategy` mit `Transition` und `Action`, die Zustände (`state`) und die
    Strategien (`strategy`). Dazu kommen die Regeln der handelnden Phase:
    - `AccountRules.kt`: welches Konto eine Aktion beschreibt (ADR-18/20),
    - `CredentialRules.kt`: das Niveau eines Nachweises (ADR-5), das implizite Binden eines Geräts
      und abhängige Verfahren;
  - `domain.policy`: `AuthPolicy`, `DefaultAuthPolicy` und `SessionEvidence`.
- **`account.domain`**: `AnchorDecision` (wann ein Anker gebunden, ersetzt oder abgelehnt wird,
  ADR-11/19), `normalizeClaimValue` und `ClaimKey` sowie `passportForm` (`PassportForm.kt`).
- **Außen herum** bleibt die Technik:
  - `JourneyActionExecutor` und `JourneyService` lesen die Daten, fragen die Regel und schreiben das
    Ergebnis.
  - `account.application` enthält Protokolle und Register, `account.infrastructure` die Entities und
    Repositories.
  - `DomainBeans` im Wurzelpaket des Orchestrators legt Strategien und Policy als Spring-Beans an.
    Die Klasse listet an einer Stelle, welche Strategien es gibt.

## Was es kostet und was bewusst unterblieb

- **Serialisierung außerhalb des Kerns.** Die Zustände einer Journey haben keine
  Jackson-Annotationen. Stattdessen leitet `JourneyStateCodec` den Typnamen aus den
  `sealed`-Hierarchien ab. `JourneyStateCodecTest` stellt sicher, dass die gespeicherten Namen
  gleich bleiben. Die Frage eines wartenden Zustands heißt fachlich `Question`, in der
  Schnittstelle nach außen aber weiter `Prompt`. Der Vertrag bleibt also unverändert.
- **Kein Adapter für `AccountDirectory`.** `AccountService` implementiert diesen Port weiter selbst.
  Er wird an mehreren Stellen als Port benutzt, ein Adapter hätte nur umgeleitet.
- **Kein Umbau aller Executor-Methoden auf Zeugen-Typen** (`LiveChannel`, `RunningJourney`).
  Stattdessen stehen die Annahmen „nach dem Speichern nie null“ als Zugriffe neben den Entities
  (`ChannelSession.id`, `AuthJourney.requireIntent()` …). Die übrigen `checkNotNull` sind fachliche
  Vorbedingungen und bleiben.
- **`orchestrator.session`, `journey` und `channel` bleiben nach Thema geordnet**, nicht nach
  `application`/`infrastructure`. Seit der Kern in `domain` liegt, enthalten sie nur noch Technik.
  Das Konto-Modul ist klein genug für die Dreiteilung.
- **Das Versuchsbudget** („minus eins, bei null scheitern“) ist keine eigene Regelklasse wert.
- **Die Zustandsdiagramme bleiben handgeschrieben.** Ein Versuch, sie aus den Strategien zu
  erzeugen, brachte unbrauchbare Bilder. Ob ein Übergang vorkommt, hängt von einem Kontext ab, den
  jede ausgeführte Aktion verändert. Ein Generator kann an die Pfeile außerdem nur Beispielnamen
  schreiben, nicht die Bedingungen („Nachweis reicht für das geforderte Niveau“). Geprüft werden
  stattdessen die Zustände in beide Richtungen (`JourneyDiagramsTest`): Jeder gezeichnete Knoten
  existiert im Code oder ist im Diagramm ausdrücklich deklariert. Und jeder Zustand eines Intents
  ist gezeichnet. Der Test fand beim ersten Lauf zwei Abweichungen: `EnrollFirstStart` fehlte, und
  statt der Sub-Journey `RE_IDENTIFY` stand ein Hilfsknoten im Diagramm.
- **`IntentStrategy.kt` ist in fünf Dateien geteilt** (`IntentStrategy`, `JourneyContext`,
  `JourneyEvent`, `Transition`, `Action`). Wer nachschlägt, was eine `Transition` ist, muss so nicht
  die anderen vier mitlesen.
