# ADR-28: Demo-Werte lassen sich abschalten

**Status:** umgesetzt.

**Entscheidung.** Die API-Antworten können einen Block mit Demo-Werten enthalten (`DemoInfo`). Nur
eine einzige Stelle erzeugt diesen Block: die Bean `DemoDisclosure`. Ob es diese Bean gibt,
entscheidet die Einstellung `demo.mode` beim Start. Eine ArchUnit-Regel stellt sicher, dass
`DemoDisclosure` die einzige Stelle bleibt.

> **Nachtrag 2026-09-26:** Der eigene Schalter `demo.disclosure` ist entfallen. Er folgte ohnehin
> dem Demomodus. Niemand braucht einen Demomodus ohne Demo-Werte oder einen Betrieb mit ihnen. Ein
> Schalter weniger heißt auch eine Prüfung weniger in `ProductionModeCheck`: Außerhalb des
> Demomodus gibt es die Bean, die Demo-Werte baut, gar nicht.

## Vorher

Damit man die Demo ohne echte Daten durchspielen kann, liefert die API Hilfswerte mit. Im
`demo`-Block der Antwort stehen:

- die TAN im Klartext,
- das feste Demo-Passwort,
- von jeder Testperson die KVNR (Krankenversichertennummer), Name, Adresse und Freischaltcode.

Dass diese Werte „nie Teil des Produktionsvertrags" sind, stand nur als Kommentar an
`tool_spi.DEMO_DATA_KEY`. Zwei Stellen im Code bauten den Block aber ohne jede Bedingung. Eine
Installation, die diese Werte nicht ausliefern darf, hatte also keine Möglichkeit, sie abzuschalten.

## Alternative: ein Schalter an den beiden Stellen

Kürzer wäre gewesen, an beiden Stellen `if (!demoEnabled) return null` einzubauen. Dagegen spricht:
Man müsste dann mit einem Test absichern, dass wirklich jede Stelle den Schalter prüft. Außerdem
könnte eine dritte Stelle den Weg ohne Bedingung wieder einführen. Die gewählte Lösung ist eine Bean,
die es nur abhängig von der Property `demo.mode` gibt (`@ConditionalOnProperty`, zwei Umsetzungen in
`DemoDisclosure.kt`). Damit gibt es gar keinen Weg, auf dem die Werte nach außen gelangen könnten.

## Kosten

Es gibt einen Umweg mehr: Die beiden Aufrufer bauen das `DemoInfo` nicht mehr selbst, sondern
übergeben ihre Bestandteile. Dazu kommt eine ArchUnit-Regel.

## Nicht entschieden

Voreingestellt bleiben die Werte eingeschaltet. Das Projekt ist eine Demo, und die Werte sind ihr
Zweck. Entschieden ist nur, dass man sie mit einer Einstellung abschalten kann.

Mehr dazu in [05-api.md](../05-api.md), Abschnitt 1 (`demo`).
