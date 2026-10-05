# ADR-32: Tool-Sperre und Reihenfolge je Kanaltyp

**Status:** umgesetzt; die Sperre gilt seit [ADR-51](ADR-051-versionen-als-pfadsegment.md) je
Fassung.

Worum es geht: Ein **Tool** ist ein einzelner Baustein eines Ablaufs, etwa „per SMS anmelden“ oder
„mit Freischaltcode identifizieren“. Ein Nutzer ist entweder über die App oder über die Website mit
dem Orchestrator verbunden. Diese Verbindung heißt **Kanal**, und App oder Web ist ihr **Kanaltyp**
(siehe [Glossar](../glossar/glossar.md)). Der Betreiber kann auf einer Admin-Seite einzelne Tools
sperren und festlegen, in welcher Reihenfolge Tools zur Auswahl angezeigt werden. Diese ADR legt
fest, dass beides je Kanaltyp eingestellt wird.

**Entscheidung**: Der Betreiber sperrt Tools nicht mehr für alle Kanäle zugleich, sondern je
Kanaltyp (App, Web). Außerdem legt er je Kanaltyp eine Rangfolge der Tools fest.

- Die Sperre liegt in `orchestrator.tool_availability` mit dem Schlüssel `(tool, channel)`. Dabei
  ist `tool` eine bestimmte Fassung eines Tools (`enroll-sms@2`).
- Die Rangfolge liegt je Tool in `orchestrator.tool_order`. Sie ist für alle Fassungen gleich, weil
  ein Kanal jedes Tool nur in einer Fassung anbietet.
- Eine Sperre für alle Kanäle bedeutet: in beiden Kanälen gesperrt.

## Warum eine Ebene statt zwei

Man hätte eine Sperre für alle Kanäle neben der Sperre je Kanal behalten können. Das hätte zwei
Schalter für dieselbe Frage ergeben. Man müsste sie in der Oberfläche und beim Lesen des Codes
auseinanderhalten. Der seltene Fall „überall sperren“ kostet jetzt nur zwei Klicks.

## Eine Rangfolge je Kanal, nicht je Auswahlart

Jede Auswahl in einem Kanal verwendet dieselbe Rangfolge. Eine Auswahl zeigt ohnehin nur die Tools
einer einzigen **Rolle** (`ToolRole`). Die Rollen sind, in dieser Reihenfolge:

1. Identifizieren
2. Person im Personenverzeichnis zuordnen
3. Einrichten
4. Anmelden bei bekanntem Konto
5. Anmelden über eine E-Mail-Adresse (`*-lookup`)
6. E-Mail bestätigen
7. Anmeldung auf der Website bestätigen

In dieser Reihenfolge zeigt die Admin-Seite die Gruppen, so wie ein Nutzer ihnen begegnet. Eine
Rangfolge zwischen den Rollen hätte keine Wirkung. Die Admin-Seite gruppiert deshalb je Kanal nach
Rolle, und die Pfeile ▲/▼ tauschen nur innerhalb einer Gruppe. Gespeichert wird trotzdem eine Liste
je Kanal.

Tools, die noch nicht eingeordnet sind, stehen hinter den eingeordneten. Sie folgen der
voreingestellten Reihenfolge (erst Rolle, dann Verfahren), die auch die Admin-Seite anzeigt. Vorher
war es die zufällige Reihenfolge, in der Spring die Beans registriert.

## Sortiert wird beim Ausliefern

Die Optionen sortiert `JourneyRouting.stepFor`, nicht die Auswahl der Kandidaten. Der Grund: Eine
**Journey** ist ein geführter Ablauf mit mehreren Schritten. Was eine Journey anbietet
(`Offer.offered`), wird zu Beginn festgelegt und ändert sich während ihrer Lebensdauer nicht mehr
(docs/04-orchestrierung.md). Würde man dort sortieren, gälte eine geänderte Reihenfolge erst für neue
Journeys. So gilt sie ab dem nächsten Bildschirm, genau wie die Sperre.

## Verhältnis zur Client-Deklaration

Unverändert gilt: Welche Tools ein Client darstellen kann, gibt er selbst an (`availableTools`). Die
Sperre durch den Betreiber ist eine zweite Ebene, unabhängig davon. Angeboten wird nur, was beide
Ebenen zulassen.

## Folgen

- Der Kanaltyp wurde dafür als `orchestrator.domain.ChannelType` (damals `kernel`) zu den
  gemeinsamen Begriffen verschoben. Vorher hieß er `ChannelSession.Channel`. Sonst hätte das
  Paket `tool` vom Paket `session` abgehangen, und es wäre ein Zyklus zwischen den Paketen entstanden.
- `V3__orchestrator.sql` wurde direkt geändert (ADR-16).

## Voreinstellung

Die Demo bringt für jeden Kanal eine Voreinstellung mit (`demo.tool-defaults` in `application.yml`).
Sie enthält Reihenfolge und Sperren und ist aus einer Konfiguration übernommen, die auf der
Admin-Seite eingestellt wurde. `ToolDefaultsInitializer` wendet sie beim Start an, wenn es noch keine
Einstellungen für die Tools gibt. Bei einer dauerhaft gespeicherten Datenbank bleiben eigene
Änderungen also über einen Neustart hinweg erhalten. „Demo zurücksetzen“ stellt genau diese
Voreinstellung wieder her.

**Wer was entscheidet** (entschieden 2026-09-26):

- **Maßgeblich ist die Tabelle `orchestrator.tool_availability`.** Die Voreinstellung aus der
  yml-Datei wird nie gelesen, um über eine Anfrage zu entscheiden. Sie ist nur der Stand beim ersten
  Start und nach „Demo zurücksetzen“.
- **Welche Tools ein Kanal überhaupt anbietet, gibt der Client an** (`availableTools`). Die Tabelle
  sperrt oder ordnet nur innerhalb dieser Menge.
- **`demoOnly`-Tools** gibt es nur im Demomodus. Das ist eine Eigenschaft des Tools, kein Schalter
  auf der Admin-Seite.
- **`orchestrator.feature_flag` bleibt ein eigener Speicher.** Dort stehen benannte Schalter, die
  keine Tools betreffen: „Enrollment zuerst“, Login-Theme und Anmeldung auf `loa1`. Beides in eine
  Tabelle zu legen, würde zwei verschiedene Dinge unter einen Schlüssel zwingen.
