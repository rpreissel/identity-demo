# ADR-36: Niveaus und ihre Nachweise – was nur behauptet ist, läuft nur im Demomodus

**Status:** entschieden und umgesetzt (2026-09-25).

Jedes Anmelde- oder Identifizierungsverfahren vergibt ein **Niveau** (Sicherheitsniveau): `loa1`,
`loa2` oder `loa3`. Das Niveau sagt, wie sehr einer Anmeldung vertraut wird, und davon hängt ab,
was der Nutzer danach tun darf (siehe [Glossar](../glossar/glossar.md)). Ein Niveau ist nur so viel
wert wie der Nachweis dahinter. Manche Verfahren in dieser Demo vergeben aber ein Niveau, das der
Server gar nicht selbst nachprüfen kann. Diese ADR legt fest, wie mit solchen Verfahren umgegangen
wird.

**Entscheidung**: Ein Verfahren vergibt nur das Niveau, das der Server selbst nachprüfen kann oder
das ein Fremdsystem über seinen Port zusagt. Ein Port ist die fest definierte Schnittstelle zu einem
Fremdsystem ([ADR-35](ADR-035-betriebsanspruch-backend-kern-produktionsreif.md)).

Ein Verfahren, das mehr vergibt, als es beweisen kann, erklärt das selbst, und zwar mit
`ToolModule.demoOnly` samt Begründung. Solche Verfahren sind nur im **Demomodus**
(`demo.mode=true`) verfügbar. Mit `demo.mode=false` bietet der Kern sie nicht an und lässt sie nicht
aktivieren. Auch keine Einstellung des Betreibers schaltet sie wieder ein
(`ToolAvailabilityService`).

**Anlass**: `auth-device` und `enroll-device` vergeben loa2. Der Grund: Der Nachweis des Geräts
meldet nicht nur, dass die App den Schlüssel besitzt, sondern auch eine Nutzerverifikation, also
dass der Nutzer PIN oder Biometrie eingegeben hat. Diese Meldung ist aber nur ein Feld in einem JWT
(einem signierten Datenpaket), das die App selbst signiert. Eine manipulierte App kann dort melden,
was sie will. Der zweite Faktor ist damit nur behauptet, nicht bewiesen.

Aus demselben Grund sind auch die Verfahren `demoOnly`, deren Niveau auf einer **simulierten
Gegenstelle** beruht:

- `enroll-kobil` und `auth-kobil` (Gegenstelle `kobil`),
- `ident-eid` (simulierte Kartenlesung),
- `ident-nect` (Gegenstelle `nect`).

Der Vertrag des Ports beschreibt, was ein echtes System zusagen müsste. In dieser Instanz sagt es
aber niemand zu. Wird das Verfahren an das echte System angeschlossen, ist es wieder allgemein
verfügbar, und die Erklärung entfällt.

**Warum eine Erklärung am Verfahren und kein Modus in seinen Werten**: Erwogen wurde, den
Gerätefaktor außerhalb des Demomodus auf `{possession}`/loa1 herabzustufen. Dann wären `maxAcr` und
`factorTypes` vom Modus abhängig. Die Richtlinie für Sicherheitsniveaus (Policy) wählt aber nach
genau diesen Werten aus, welche Verfahren sie anbietet. Werte, die je nach Modus anders lauten,
müsste man überall mitbedenken.

Die Erklärung am Verfahren lässt die Werte unverändert. Die Ausnahme wird zu einer einzigen Zeile,
die sich prüfen lässt. Ein Gerätefaktor mit echter Plattform-Attestation wäre ein eigenes Verfahren
ohne `demoOnly`, kein Modus dieses einen Verfahrens. Bei einer Attestation bestätigt die Plattform
selbst, dass Schlüssel und App echt sind, etwa bei WebAuthn mit geprüfter Attestierung.

**Verhältnis zu ADR-35** („kein demo-only im Kern“): Die Sicherheitsaussagen des Kerns gelten für
`demo.mode=false`. Dort ist jedes `demoOnly`-Verfahren nachweislich abgeschaltet
(`DemoOnlyToolAvailabilityTest`). Im Demomodus läuft der Kern mit Ausnahmen. Diese sind aber benannt
und einzeln begründet, nicht unerklärt.

**Der Demomodus**: Er wird über `demo.mode` geschaltet (Umgebungsvariable `DEMO_MODE`,
Voreinstellung `true` für diese Demo-Instanz). Nur im Demomodus gibt es:

- die Oberflächen der simulierten Fremdsysteme (Personenverzeichnis, KOBIL, Nect), die ohne
  Anmeldung erreichbar sind,
- der Demo-Schalter für die Anmeldung auf `loa1`.

Diese Teile sind mit `@DemoSurface` markiert oder liegen im Modul `demo_mode`. Das verlangt ein
ArchUnit-Test. Nur im Demomodus darf außerdem eine geänderte Migration das Realm neu aufbauen.

Zusätzlich schaltet der Demomodus zwei Dinge: die `demoOnly`-Verfahren und die Demo-Werte in
Antworten ([ADR-28](ADR-028-demo-werte-abschaltbar.md)). Jede Instanz mit echten Personen läuft mit
`demo.mode=false`.

**Niveaus und was sie tragen** (Stand dieser Entscheidung):

- **loa1:** ein Faktor, den der Server selbst prüft. Das ist eine SMS-TAN (Besitz der Nummer), ein
  Passwort oder ein Code an die E-Mail-Adresse (Wissen). Zwei verschiedene Arten von Faktoren
  zusammen ergeben nach der Policy loa2 (docs/04-orchestrierung.md #4).
- **loa2 aus einem einzelnen Verfahren:**
  - `ident-fsc`: Die Person besitzt einen Code, der ihr per Post geschickt wurde. Dass der Code
    nur einmal gilt und abläuft, sagt das Personenverzeichnis über seinen Port zu
    ([ADR-31](ADR-031-freischaltcode-liegt-im-fremdsystem.md)).
  - `auth-kobil`/`enroll-kobil`: **nur Demomodus.** Das Ergebnis käme vom KOBIL-Server über den
    Port, die Gegenstelle ist hier aber simuliert. Was ein echtes KOBIL zusagen muss, steht in den
    [Port-Verträgen](../port-vertraege.md).
  - `auth-qr`/`auth-qr-lookup`: die Bestätigung aus einer App-Sitzung, die selbst gerade erst
    loa2 nachgewiesen hat (`CONFIRM_PEER_LOGIN`).
  - `ident-kvnr`: kein eigener Nachweis. Das Verfahren zählt nur zusammen mit einer Identität, die
    schon vorher nachgewiesen wurde (`requires`, ADR-18).
  - `auth-device`/`enroll-device`: **nur Demomodus**, siehe oben.
- **loa3:** `ident-eid` und `ident-nect`, **nur Demomodus.** Das Ergebnis muss auf dem Server vom
  eID-Server oder von Nect kommen, nie aus Angaben des Clients (Vertrag des Ports, ADR-35). In
  dieser Instanz sind beide simuliert. Mit `demo.mode=false` erreicht deshalb derzeit kein
  Verfahren loa3.

**Zusätzlich** (unabhängig vom Modus): Der Geräteschlüssel muss ein anderer sein als der
DPoP-Schlüssel des Kanals, sonst lehnt `enroll-device` ab (`EnrollDeviceFlow`). Der DPoP-Schlüssel
ist der Schlüssel, an den die Tokens des Kanals gebunden sind. Ein zweiter Faktor, der derselbe
Schlüssel wie diese Kanalbindung ist, fügt keine Sicherheit hinzu.
