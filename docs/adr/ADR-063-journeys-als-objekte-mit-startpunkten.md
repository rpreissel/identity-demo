# ADR-63: Journeys als Objekte mit Startpunkten statt `AuthIntent`-Enum und Strategien

**Status:** entschieden und umgesetzt 2026-10-16 (Issue `DPoP-demo-mflv`).

**Worum es geht.** Jede Journey wurde fünfmal geführt:

- als Eintrag im Enum `AuthIntent`,
- als Strategie-Klasse (`IntentStrategy`) mit `override val intent`,
- als `@Bean` in `DomainBeans`,
- in einer Testliste,
- in den Zuordnungen `strategiesByIntent`/`rootsByIntent` samt Prüfung, dass keine fehlt.

Intent und Strategie waren 1:1. Die geschlossene Menge nutzte kein Code, es gab kein `when` über alle
Intents. Die Eigenschaften des Enums waren schwer lesbar: `entry: Entry?`, `startableWhenLoggedIn`
und `linksDeviceWithoutAsking`. Zwei davon wirkten nur auf dem App-Kanal und waren bei
`WEB_SELECT_METHOD` bedeutungslos. Außerdem passte „Intent“ nicht auf `FRESH_PROOF` und
`RE_IDENTIFY`, die der Server einschiebt.

**Entscheidung.**

- Eine Journey ist ein `object`, das `Journey<S>` umsetzt: `name`, `startPoints`, `stateRoots`,
  `initialState`, `transition` (`FastAccessJourney`, `RegisterJourney`, …). Ein Lauf ist weiter
  `AuthJourney`. Er hält seine Journey als `kind`, gespeichert unter ihrem Namen (`JourneyConverter`).
- `domain.Journeys` listet alle Journeys. Hier wird eine neue eingetragen. Beim Laden prüft die
  Liste:
  - Jeder Name ist eindeutig.
  - Jede Journey nennt mindestens einen Startpunkt.
  - `OnlyAsSubJourney` steht allein.
- Startpunkte als kleine DSL: `OpensAppChannel(startsWith, onSuccess)`, `OpensWebChannel`,
  `StartableWhenAuthenticated`, `OnlyAsSubJourney`. Was nur für das App-Gerät gilt, steht nur im
  App-Startpunkt. `startsWith` ist `LINKED_TO_DEVICE` oder `NONE`. `onSuccess` ist
  `LINK_WITHOUT_ASKING`, `ASK_BEFORE_LINKING` oder `LEAVE_UNCHANGED`.
- Ob ein Erfolg das Gerät verknüpft, entscheidet der Startpunkt, mit dem die App den Kanal eröffnet
  hat (`ChannelSession.appOpening`), nicht die einzelne Journey. Das gilt auch für Sub-Journeys und
  für Journeys, die auf dem angemeldeten Kanal starten.
- Die generische Maschine (`JourneyService`, `JourneyStateCodec`) kennt nur die Schnittstelle und
  `Journeys.all`. Konkrete Journeys nennt nur, wer sie startet: Endpunkte und Journeys, die eine
  Sub-Journey anfordern.

**Verhaltensänderung.** Fall: Jemand lehnt in `LOOKUP_LOGIN` „Gerät merken“ ab und richtet danach
im selben Kanal ein Verfahren ein. Bisher wurde das Gerät dabei still verknüpft, denn
`MANAGE_AUTH_METHODS` hatte `linksDeviceWithoutAsking = true`. Jetzt bleibt es unverknüpft.

**Warum.**

- Eine Journey steht an einer Stelle statt an fünf.
- Die Zuordnungsschicht zwischen Intent und Strategie entfällt.
- Startpunkte und Verhalten stehen nebeneinander.
- Die Tabelle kennt kein `null` und keine Booleans.
- Eine neue Journey kombiniert vorhandene Startpunkte, ein neuer Ort zum Starten wird ein neuer
  Startpunkt.
- Spring ist nicht beteiligt.

**Erwogene Alternativen.**

- **Das Enum behalten und nur die Eigenschaften je Kanal typisieren.** Verworfen: Die doppelte
  Führung bliebe bestehen.
- **Journeys als Spring-Beans sammeln.** Verworfen: Die Journeys sind zustandslos, eine Liste in der
  Domäne genügt.

**Preis.**

- Eindeutigkeit und Vollständigkeit der Namen prüft der Start, nicht der Compiler.
- Die Übersicht aller Journeys steht nicht mehr in einer Datei. Ersatz sind `Journeys.all`,
  `JourneysTest` und die Tabelle in [04-orchestrierung.md](../04-orchestrierung.md) Abschnitt 2.
- Der Paketname `domain.journey.strategy` ist geblieben.
