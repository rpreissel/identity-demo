# Tool-Architektur

Ein *Tool* ist ein konkretes Verfahren, um jemanden zu identifizieren, ein Anmeldeverfahren
einzurichten oder sich anzumelden (`ident-fsc`, `enroll-sms`, `auth-sms`). Dieses Dokument
beschreibt, wie Tools sich selbst beschreiben und was sie über die Grenze ihres Moduls melden.

Was der Orchestrator mit diesen Meldungen macht, steht in [04-orchestrierung.md](04-orchestrierung.md).

---

## Einstieg: Zusammenspiel an einem Schritt

Aus Sicht des Backends ist der Orchestrator ein Modulith mit eigenen Tool-Modulen; einzelne Module
geben Arbeit ihrerseits an externe Dienste ab:

```mermaid
flowchart LR
  subgraph App["App"]
    NE["Orchestrator-Engine"]
    UI1["SMS-UI"]
    UI2["Passwort-UI"]
    UI3["Geräte-UI"]
  end

  subgraph Backend["Orchestrator-Modulith"]
    O["Orchestrator<br/>next / stepData / Journey"]
    AC["account"]
    M1["auth_sms"]
    M2["auth_password"]
    M3["auth_device"]
  end

  KC["Keycloak"]
  EXT1["externer SMS-Versand"]
  KC ~~~ EXT1

  NE --> O
  O -->|"Sitzung öffnen, Token"| KC
  KC -.->|liest Konto nach| O
  O --> AC
  AC -.->|AccountDeleted| O

  UI1 --> M1
  UI2 --> M2
  UI3 --> M3

  M1 -.-> EXT1
```

So arbeiten der Orchestrator und ein Tool-Modul wie `auth_sms` bei einem konkreten Schritt
zusammen:

```mermaid
sequenceDiagram
  participant TC as ToolController (Methodenmodul)
  participant TH as ToolHandler
  participant JS as JourneyService
  participant IS as IntentStrategy
  participant AC as account
  participant KC as Keycloak

  TC->>TH: Eingabe verarbeiten (z.B. TAN prüfen)
  TH-->>TC: ToolOutcome.Completed

  TC->>JS: applyOutcome(context, ToolOutcome.Completed)
  JS->>IS: transition(state, Completed(tool, outcome), ctx) : Transition
  IS-->>JS: Perform(Action)
  JS->>AC: Action über JourneyActionExecutor ausführen (Konto finden/anlegen, Verfahren eintragen)
  AC-->>JS: JourneyContext aktualisiert
  JS->>IS: transition(state, ActionCompleted, ctx) : Transition
  IS-->>JS: Transition (z.B. nächster Schritt)
  opt Transition.Authenticated im App-Kanal
    JS->>KC: erstes Token (öffnet die Keycloak-Sitzung) bzw. nach Step-up neues acr in dieselbe
    KC-->>JS: Token und Sitzungsfenster, neue Frist des Kanals
  end
  JS-->>TC: ChannelResponse (next/stepData)
```

Keycloak kommt in einem Schritt nur vor, wenn er die Anmeldung eines App-Kanals abschließt: Dann
öffnet der Übergang nach `AUTHENTICATED` die Keycloak-Sitzung
([ADR-43](adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md)). Konten spiegelt
Keycloak nicht, es liest ein Konto
bei Bedarf selbst beim Orchestrator nach ([ADR-38](adr/ADR-038-keycloak-liest-konten.md)). Das Modul
`account` spricht nie selbst mit Keycloak. Nur wenn ein Konto gelöscht wird, meldet es
`AccountDeleted`, und nach dem Commit räumt der Orchestrator (`KeycloakAccountRemovalListener`) die
Daten ab, die Keycloak selbst zu diesem Konto hält, etwa Sitzungen
([07-betrieb.md](07-betrieb.md) Abschnitt 3a).

Was der `ToolHandler` intern tut, um zu diesem `ToolOutcome` zu kommen, gehört bewusst nicht zu
diesem Bild. Er arbeitet mit eigener Fachlogik auf einem eigenen Schema (`ToolDB`), auf das nichts
außerhalb des Moduls zugreift.

Für dich als Backend-Entwickler heißt das:

- **Dein Modul bleibt dein Modul.** Ein Tool-Modul wie `auth_sms` hat sein eigenes Schema
  (`ToolDB`), auf das nichts von außen zugreift. Du kannst dort die Fachlogik ändern, ohne die
  Journey oder andere Module überhaupt lesen zu müssen.
- **Der Vertrag ist klein und stabil.** Dein `ToolHandler` liefert nur ein `ToolOutcome`. Was das
  für die Journey, ihren Zustand und den nächsten Schritt bedeutet, entscheidet allein die
  `IntentStrategy`. Beim Schreiben eines Tools musst du also nie die ganze Zustandsmaschine im
  Kopf haben.
- **App- und Web-Kanal sind für dich gleich.** Journey, Tool und `next` funktionieren für beide
  Zugänge gleich ([05-api.md](05-api.md)); du schreibst keine Sonderfälle für einen einzelnen Kanal
  in dein Modul.
- **Den Ablauf zum Ausstellen der Tokens musst du nicht bauen.** Das übliche OIDC mit Keycloak ist
  auf dem Server einmal umgesetzt. Dein Modul liefert nur das Ergebnis eines Verfahrens, nie selbst
  ein Token.

---

## 1) Tool-Katalog

`ToolSession` ist die dritte und kurzlebigste Ebene (`ChannelSession` → `AuthJourney` →
`ToolSession`). Sie steht für genau einen Durchlauf eines Tools und hält nur technische Daten zu
dessen Lebenszyklus. `toolId` (z. B. `ident-fsc`, `enroll-sms`, `auth-sms`) bezeichnet Art und
Methode in einem einzigen Namen. Die `toolId` wird nicht gespeichert, sondern aus der Route
abgeleitet; über sie werden Handler und Datenklasse des Moduls ausgewählt.

Der Tool-Katalog ist **keine zentral gepflegte Tabelle**. Er entsteht aus den Angaben, die die Module
über sich selbst machen (`ToolDescriptor`, Abschnitt 2). In den Begriffen des
[Glossars](glossar/glossar.md): Tools der Rolle `IDENTIFICATION` sind Identifizierungsmittel, die
Methoden der Rollen `ENROLLMENT` und `IDENTIFIED_AUTH`/`LOOKUP_AUTH` Authentisierungsmittel
([Orchestrierung](04-orchestrierung.md), Abschnitt „Begriffe“).

| toolId | role | method | factorTypes | maxAcr | allowsMultipleInstances |
|---|---|---|---|---|---|
| `ident-fsc` | `IDENTIFICATION` | `fsc` | `{possession}` | `loa2` | — |
| `ident-eid` | `IDENTIFICATION` | `eid` | `{possession,knowledge}` | `loa3` | — |
| `ident-nect` | `IDENTIFICATION` | `nect` | `{possession,knowledge,inherence}` | `loa3` | — |
| `ident-kvnr` | `CORRELATION` | `kvnr` | `{}` | `loa2` | — |
| `confirm-email` | `ATTESTATION` | `email` | `{}` | `loa1` | — |
| `enroll-sms` / `auth-sms` | `ENROLLMENT` / `IDENTIFIED_AUTH` | `sms` | `{possession}` | `loa1` | `false` |
| `enroll-password` / `auth-password` | `ENROLLMENT` / `IDENTIFIED_AUTH` | `password` | `{knowledge}` | `loa1` | `false` |
| `enroll-email` / `auth-email` | `ENROLLMENT` / `IDENTIFIED_AUTH` | `email` | `{knowledge}` | `loa1` | `false` |
| `auth-sms-lookup` / `auth-password-lookup` / `auth-email-lookup` | `LOOKUP_AUTH` | `sms`/`password`/`email` | wie das Gegenstück ohne `-lookup` | `loa1` | `false` |
| `enroll-device` / `auth-device` | `ENROLLMENT` / `IDENTIFIED_AUTH` | `device` | `{possession,knowledge,inherence}` | `loa2` | `true` |
| `enroll-kobil` / `auth-kobil` | `ENROLLMENT` / `IDENTIFIED_AUTH` | `kobil` | `{possession,knowledge,inherence}` | `loa2` | `true` |
| `enroll-qr` / `auth-qr` / `auth-qr-lookup` | `ENROLLMENT` / `IDENTIFIED_AUTH` / `LOOKUP_AUTH` | `qr` | `{}` / `{possession,knowledge}` / `{possession,knowledge}` | `loa1` / `loa2` / `loa2` | `false` |
| `confirm-qr-login` | `PEER_APPROVAL` | `qr` | `{}` | `loa2` | — |

Die Entscheidungen dahinter:

- Jedes Modul liefert Kategorie, Methode, Faktortyp und Niveau selbst; es gibt keinen zentral zu
  pflegenden Katalog.
- `method` wird **nicht** aus der `toolId` herausgelesen. `enroll-sms` und `auth-sms` melden
  dieselbe `method`; darüber findet ein Tool zum Anmelden die passende Zeile in
  `account.auth_method`.
- `factorTypes` ist eine **Menge**, weil ein Verfahren mehrere Faktoren zugleich erbringen kann:
  `enroll-device`/`auth-device` und `enroll-kobil`/`auth-kobil` deklarieren bis zu drei
  Faktortypen und erbringen je Durchlauf zwei davon mit `loa2` (ein an das Gerät gebundenes
  Credential plus System-PIN oder Biometrie).
- `requires` wird gegen die zusammengeführten Claims des Kontos geprüft
  (`AccountProfile.establishedClaims`, also Angaben abzüglich der Widerrufe, ADR-12). Die Prüfung
  läuft zweimal: bei der Auswahl der Kandidaten und noch einmal beim Start des Tools
  (`ToolJourneyService.validatePreconditions`). Sonst ließe sich die Kandidatenliste durch
  einen direkten Aufruf umgehen. Drei Tools nutzen `requires` heute: `enroll-password` und
  `enroll-email` verlangen `ClaimRequirement(EMAIL, PROVEN)`, `ident-kvnr` die bestätigten
  Identitätsattribute `FAMILY_NAME`, `GIVEN_NAMES` und `BIRTH_DATE` (ADR-18).
- `requires` entscheidet **nicht nur über das Angebot, sondern gilt dauerhaft** (ADR-24): Was ein
  Credential brauchte, um zu entstehen, braucht es auch, um weiter zu bestehen. Fällt die Angabe
  weg, fällt das Credential mit – und mit ihm alles, was daran hängt. Das wird aus denselben
  Deklarationen ermittelt (`JourneyActionExecutor.dependentsOfLostClaims`). Eine Abhängigkeit
  zwischen zwei Verfahren braucht deshalb keine eigenen Begriffe: Ein Modul schreibt beim
  Einrichten einen Claim, ein anderes verlangt ihn, und das Protokoll der Claims erledigt den
  Rest. `enroll-password` schreibt dafür `PASSWORD_EXISTS`. Heute fragt das niemand ab, aber so
  ließe sich eine solche Abhängigkeit ausdrücken.
- Die **Verfügbarkeit** wird auf zwei unabhängigen Ebenen bestimmt, beide als Mengen von
  `toolId`s. Der Client gibt beim Anlegen des Kanals an, welche Tools er darstellen kann
  (`availableTools`, fest für den ganzen Kanal). Der Betreiber kann zusätzlich jedes Tool **je
  Kanaltyp** (App oder Web) zur Laufzeit sperren (`ToolAvailabilityService`, ADR-32). Bei jeder
  Anfrage zählt nur, was in beiden Mengen steht (`JourneyRouting.availableToolsOf`); das wird an
  drei Stellen geprüft. Bleibt nichts übrig, bricht die Journey genauso ab
  (`exhausted`/Cancel), als hätte der Nutzer alle Kandidaten abgelehnt.
- Auch die **Reihenfolge** legt der Betreiber je Kanaltyp fest: eine Rangfolge der Tools, die jede
  Auswahl in diesem Kanal übernimmt. Sie wirkt nur innerhalb einer Rolle (`MethodRole`), denn
  jede Auswahl zeigt nur Tools einer Rolle; die Admin-Seite gruppiert entsprechend. Tools ohne
  Rang stehen dahinter, sortiert nach Rolle und Verfahren. Sortiert wird erst beim Ausliefern der
  Auswahl (`JourneyRouting.stepFor`), nicht im gespeicherten Angebot der Journey. Eine geänderte
  Reihenfolge gilt so schon für den nächsten Bildschirm einer laufenden Journey (ADR-32).
- `role=ATTESTATION` (`confirm-email`) kennzeichnet den Nachweis, ein Attribut des Kontos zu
  kontrollieren: kein Credential, keine Identität. Wann ein Tool diese Rolle hat, steht in
  Abschnitt 2 („`ATTEST`").
- `role=LOOKUP_AUTH` kennzeichnet die `-lookup`-Varianten. Sie haben dieselbe `method` wie ihr
  Gegenstück mit `IDENTIFIED_AUTH`, finden das Konto aber über eine eingegebene E-Mail-Adresse
  statt über den Kanal. Ohne diese Unterscheidung wäre die Auswahl der Kandidaten mehrdeutig.
- `role=CORRELATION` (`ident-kvnr`) kennzeichnet einen Schritt, der nur zuordnet. Für sich beweist
  er nichts: Eine eingetippte KVNR oder Partnernummer ist kein Nachweis. Er ist nie Kandidat einer
  (erneuten) Identifizierung, denn `forIdentification` und `reIdentCandidates` prüfen die Rolle,
  nicht die Kategorie. Starten lässt er sich erst, wenn die Identität bereits bestätigt ist
  (`requires`, ADR-18). `factorTypes = {}` folgt aus dieser Rolle, definiert sie aber nicht.
- `allowsMultipleInstances=true` (`device`, `kobil`): Mehrere aktive Einträge derselben Methode
  dürfen gleichzeitig bestehen, einer je physischem Gerät. Sonst gilt die Regel, dass ein neues
  Einrichten den alten Eintrag ersetzt. Das ist eine reine **Regel fürs Speichern**: Sie sagt nur,
  ob ein neues Einrichten das alte ersetzt.
- `keyBinding` (`device`, `kobil`): Das Credential liegt als nicht exportierbarer Schlüssel auf
  genau einem Gerät und kann anderswo gar nicht existieren. Daraus folgt die **Regel für Angebot
  und Widerruf**: `AuthPolicy.candidateTools` bietet nur den Eintrag an, der zum anfragenden Gerät
  passt. `usableByCaller` prüft zusätzlich, dass das Gerät laut `DeviceAccountLink` noch mit diesem
  Konto verknüpft ist. Und wird das Gerät neu verknüpft, widerruft `JourneyActionExecutor` genau die Credentials,
  die auf diesem Schlüssel liegen.
- `instanceDisclosure` (heute bei `device` und `kobil`) beantwortet die nächste Frage: Was darf
  über den Eintrag auf diesem Schlüssel **angezeigt** werden? Auch hier liefert das Modul die
  Regel, nicht die Detaildaten selbst. Die gehören allein dem Modul und enthalten Hashes und
  Bindungsschlüssel. So kann der Orchestrator sagen, wodurch dieses Gerät sonst noch bekannt ist
  (`device-link.boundCredentials`), ohne einen einzigen Schlüssel der Detaildaten zu kennen.
  Der Orchestrator liest dafür keine privaten Konstanten der Module: Ein solcher Zugriff ließe sich
  kompilieren, weil der Compiler `internal const val` direkt einsetzt, und hinterließe keine
  Abhängigkeit zwischen den Modulen, die `ApplicationModules.verify` beanstanden könnte.
- `keyBinding` ist bewusst ein `CallerKeyBinding?` und kein Schalter neben einer
  überschreibbaren Funktion: Wer die Eigenschaft deklariert, liefert damit **zwingend** die Regel
  mit, nach der sich die Einträge unterscheiden. (Aus demselben Grund trägt
  `AttributeAuthority.Local` seine `AnchorRule` selbst, [12-entscheidungen.md](12-entscheidungen.md)
  ADR-14.) Ein Schalter ohne Regel (jeder Eintrag läge auf jedem Schlüssel) oder eine Regel ohne
  Schalter (sie würde nie abgefragt) lassen sich so gar nicht ausdrücken. Es braucht also keine
  Prüfung, die beides zusammenhält.
- `keyBinding` ist **getrennt** von `allowsMultipleInstances`, obwohl `device` und `kobil` heute
  beides bejahen. Das eine aus dem anderen abzuleiten, ginge nur gut, solange jede Methode mit
  mehreren Einträgen auch an einen Schlüssel gebunden ist. Eine künftige Methode, die lediglich
  mehrere Einträge nebeneinander erlaubt, bekäme sonst unbemerkt die Bedeutung eines
  Geräteschlüssels, die sie nie beansprucht hat.
- `enroll-kobil`/`auth-kobil` binden das Gerät nicht selbst, sondern über den externen
  Dienstleister KOBIL. Es ist das erste Verfahren, dessen Nachweis **nicht über den Client läuft**:
  Der Client überbringt nur eine Einmalkennung (OTP); die Bestätigung holt sich das Backend selbst
  beim Anbieter ab (Abschnitt 7 in [Abläufe](06-ablaeufe.md)). Der Besitz des Geräts ist damit
  stärker belegt als bei jedem anderen Tool; das Zugangsmittel ist es nicht (nächster Punkt).
- Beim KOBIL-Verfahren liegt der PIN **im Backend des Tools**, nicht beim Nutzer. Er wird beim
  Einrichten dort erzeugt und bei jeder Anmeldung an den Client herausgegeben, nachdem dieser
  sich lokal entsperrt hat: per Gerätegeheimnis mit Biometrie-Schutz oder per Passwort des Kontos
  (ADR-21). Dieses Entsperren ist die `userVerification` des Verfahrens, kein zweiter Nachweis. Es
  nutzt die im Projekt üblichen Namen: `pin` für Wissen, `biometric` für Inhärenz – dieselben
  Werte wie bei `auth-device`. (Ein amr-Eintrag `password` wäre nicht nur ein neuer Name, sondern
  falsch: amr-Werte und Methodennamen teilen sich einen Namensraum, und `JourneyRecorder` würde
  dem Durchlauf die echte Passwort-Methode des Kontos anhängen.)
- Beide Wege zum Entsperren sind optional. Welche es für ein bestimmtes Credential gibt,
  **berechnet der Server**: Biometrie nur, wenn der Nutzer ihr beim Einrichten zugestimmt hat
  (dann gibt es `unlock_secret_hash`, sonst ist die Spalte NULL), das Passwort nur, solange das
  Konto eines hat. `auth-kobil` nennt in `stepData` (`unlockOptions`) nur die Wege, die es
  tatsächlich gibt, statt beide anzubieten und einen davon ins Leere laufen zu lassen.
- `kobil` deklariert dieselben `factorTypes` und dasselbe `maxAcr` wie `device` und hat damit
  **dieselbe Ausnahme** von der Regel „nur nachweisbare Faktoren melden"
  ([Orchestrierung](04-orchestrierung.md) Abschnitt 8): Wie entsperrt wurde, gibt in beiden Fällen
  der Client selbst an. Beides wird bewusst gleich behandelt, statt für dasselbe Zugangsmittel eine
  zweite, strengere Regel einzuführen.
- `keyBinding` liest beim KOBIL-Verfahren den **DPoP-Schlüssel** des Kanals
  (`kobilBindingKeyRef`), nicht die Gerätekennung von KOBIL. Wenn das Verfahren angeboten wird,
  ist der Schlüssel das Einzige, was bekannt ist; die Kennung erfährt der Server erst nach dem
  Einlösen. Beide stehen deshalb unter getrennten, eigens benannten Schlüsseln in den
  `instanceDetails`: Sie beantworten verschiedene Fragen zu verschiedenen Zeitpunkten.
- `enroll-qr`/`auth-qr`/`auth-qr-lookup` folgen demselben Muster wie `sms`, `password` und `email`:
  einrichten, anmelden und anmelden über die E-Mail-Adresse. Eine Besonderheit gibt es:
  `enroll-qr` ist eine reine Zustimmung (Opt-in) ohne Geheimnis (`factorTypes = {}`); ob diese
  Zustimmung vorliegt, prüft erst `confirm-qr-login`.
- `auth-qr`/`auth-qr-lookup` deklarieren `factorTypes = {possession, knowledge}`. Das Handy, das
  die Anmeldung bestätigt, muss laut `ConfirmPeerLoginStrategy.gate()` vorher selbst frisch loa2
  nachgewiesen haben. Das ist MFA aus einem einzigen Verfahren wie bei `ident-eid` und passt zu
  `maxAcr=loa2`.
- `confirm-qr-login` hat die Rolle `MethodRole.PEER_APPROVAL` (Kategorie `SIDE_ACTION`), denn
  keine der übrigen Rollen passt auf „bestätigt, was jemand anderes tut".

Zentral bleibt nur, was ein Modul nicht wissen *kann*: welches Niveau sich aus einer
**Kombination** von Nachweisen ergibt und welches Niveau eine Ressource verlangt. Das ist Sache der
`AuthPolicy` ([Orchestrierung](04-orchestrierung.md)).

---

### Was `ident-nect` von Nect bekommt

`ident-nect` leitet zum simulierten Identifizierungsdienst Nect weiter (Sprungseite `/nect/`) und holt
das Ergebnis danach selbst ab (`NectIdent.redeem`). Nur im App-Kanal. Nect veröffentlicht keine
Feldliste; was es weitergeben **kann**, begrenzt das Dokument selbst:

Quellen: Online-Ausweis nach [§18 PAuswG](https://www.gesetze-im-internet.de/pauswg/__18.html), Reisepass nach [ICAO 9303](https://www.icao.int/publications/doc-series/doc-9303) (DG1), EUDI-Wallet nach dem [PID-Rulebook](https://github.com/eu-digital-identity-wallet/eudi-doc-attestation-rulebooks-catalog/blob/main/rulebooks/pid/pid-rulebook.md).

- **Auswahl der Daten**
  - *Online-Ausweis:* je Zugriffsrecht
  - *Reisepass:* keine – der Chip wird ganz gelesen
  - *EUDI-Wallet:* je Attribut; der Nutzer darf ablehnen
- **Name, Vorname, Geburtsdatum**
  - *Online-Ausweis:* ✓
  - *Reisepass:* ✓ in MRZ-Schreibweise (`MUELLER`, ggf. gekürzt)
  - *EUDI-Wallet:* ✓
- **Anschrift**
  - *Online-Ausweis:* ✓, Straße und Hausnummer in einem Feld
  - *Reisepass:* ✗
  - *EUDI-Wallet:* optional
- **Anker**
  - *Online-Ausweis:* Pseudonym der Karte – je Diensteanbieter verschieden, bei Nect also Nects eigenes (`NECT_RESTRICTED_ID`, nicht das von `ident-eid`)
  - *Reisepass:* keiner – die Dokumentnummer wird nicht angefordert (§ 20 PAuswG / § 16 PassG)
  - *EUDI-Wallet:* ✗ – die PID trägt kein Pseudonym
- **Niveau / Faktortypen**
  - *Online-Ausweis:* `loa3`, Besitz + Wissen
  - *Reisepass:* `loa2`, Besitz + Biometrie (Lichtbildabgleich)
  - *EUDI-Wallet:* `loa3`, Besitz + Wissen

`ident-nect` fragt dasselbe an wie `ident-eid`: Name, Vorname, Geburtsdatum, Anschrift und den Anker
des Dokuments. Nect gibt nur weiter, was angefragt **und** vom Dokument lieferbar ist. Eine Person im
Personenverzeichnis findet `ident-nect` nicht; die Zuordnung folgt wie nach `ident-eid` über
`ident-kvnr`. Offen sind der Web-Kanal, die echte Anbindung und ein Anker für den Reisepass.

---

## 2) `ToolDescriptor` und `ToolOutcome`

Jedes Tool bringt eine eigene Descriptor-Bean mit (`object EnrollSmsDescriptor : ToolDescriptor`, je
Modul in `Descriptors.kt`), statt dass der Handler das Interface selbst implementiert. Der
Descriptor ist eine reine Selbstbeschreibung ohne Abhängigkeiten, getrennt von der Fachlogik in
`internal`. Der Orchestrator sammelt die Descriptors beim Start ein und bildet daraus den Katalog
aus Abschnitt 1:

| Feld | Bedeutung |
|---|---|
| `toolId` | z. B. `"auth-sms"` – frei vergeben, nie aus `role` und `method` abgeleitet (öffentlicher API-Vertrag) |
| `method` | z. B. `"sms"` – verbindet `enroll-sms`, `auth-sms` und `auth-sms-lookup` |
| `role` | `IDENTIFICATION` \| `CORRELATION` \| `ATTESTATION` \| `ENROLLMENT` \| `IDENTIFIED_AUTH` \| `LOOKUP_AUTH` \| `PEER_APPROVAL`; die Kategorie (`role.category`: `IDENT`/`ATTEST`/`ENROLL`/`AUTH`/`SIDE_ACTION`) wird direkt daraus gelesen und nicht auf dem Descriptor wiederholt |
| `factorTypes`, `maxAcr` | feste Obergrenzen dieses Tools |
| `claims` | welche Attribute das Tool mit welcher `ClaimSource` bezeugen darf; ein Durchlauf meldet nie mehr |
| `startStep` | erster Schritt eines neuen Durchlaufs, standardmäßig aus der Rolle abgeleitet (`role.defaultStartStep`) |
| `requires`, `allowsMultipleInstances`, `keyBinding`, `instanceDisclosure` | standardmäßig leere Menge, `false`, `null` bzw. `null` |

`(method, role)` ist der eindeutige Schlüssel für „das konkrete Verfahren dieser Art für dieses
Credential". `(method, role.category)` allein reicht nicht, weil sich `IDENTIFIED_AUTH` und
`LOOKUP_AUTH` die Kategorie `AUTH` teilen. `ToolHandlerRegistry` lehnt beim Einsammeln der
Descriptors ein doppeltes Paar `(method, role)` ab, statt unbemerkt einen der beiden zu nehmen.

`tool_api` kennt **keine** konkreten Methoden. Jedes Modul deklariert seine eigene Konstante (z. B.
in `auth_sms/Descriptors.kt`: `internal const val SMS_METHOD = "sms"`), damit der Katalog ohne
zentrale Liste auskommt. Die `toolId` wird bewusst *nicht* aus `(method, role)` abgeleitet: Sie ist
öffentlicher API-Vertrag (URL-Pfade, Frontend-Routing), auch wenn die heutigen Werte dem Muster
`{role-präfix}-{method}[-lookup]` folgen.

`maxAcr` und `factorTypes` sind fest und dienen der Vorauswahl: Kann dieses Tool eine Lücke
überhaupt schließen? Was ein konkreter Durchlauf tatsächlich erreicht hat, meldet `Completed` – nie
mehr, als der Descriptor zulässt.

Über die Grenze eines Moduls geht nur ein `ToolOutcome`: Das Verfahren läuft noch, ist
abgeschlossen oder ist fehlgeschlagen.

| Variante | Bedeutung |
|---|---|
| `InProgress(nextStep, stepData, demo)` | läuft weiter; `stepData` ist für den Client bestimmt und wird unverändert weitergegeben, `demo` enthält nur Demo-Werte (ADR-28) |
| `Failed.*(reason, …)` | Versuch fehlgeschlagen, eine Variante je Rolle (siehe unten); wie es mit weiteren Versuchen weitergeht, steht in [Orchestrierung](04-orchestrierung.md) |
| `Completed.Identified(claims, ...)` | Identität festgestellt; höchstens ein `PERSON_ID`-Claim (eine Partnernummer). Verfahren, die nur bezeugen, was sie lesen (`ident-eid`, `ident-nect`), liefern keinen |
| `Completed.Attested(claims)` | Attribut bestätigt; kein `enrollmentRef`, `amr` leer, kein eigenes Niveau (Abschnitt „ATTEST" unten) |
| `Completed.Enrolled(enrollmentRef, ...)` | Verfahren eingerichtet |
| `Completed.Authenticated(accountId?, ...)` | Nachweis erbracht; `accountId` setzen nur die `-lookup`-Tools |
| `Completed.Approved(...)` | Ein `PEER_APPROVAL`-Tool (`confirm-qr-login`) hat eine fremde Anfrage bestätigt |

Ein Fehlschlag nennt über seine Variante, gegen wen der Versuch lief – davon hängt ab, welche
Sperre nach zu vielen Versuchen greift. Das Subjekt ist ein Pflichtfeld; „niemand“ ist ein
ausdrückliches `null`, kein vergessener Standardwert:

- **`IdentifiedAuth(reason)`** (`IDENTIFIED_AUTH`): gegen das Konto, das der Kanal schon kennt.
- **`LookupAuth(reason, attemptedAccountId)`** (`LOOKUP_AUTH`): gegen das Konto, das die Eingabe
  ergab, oder `null`.
- **`Identification(reason, attemptedPersonId)`** (`IDENTIFICATION`, `CORRELATION`): gegen die
  Person, die die Eingabe ergab, oder `null`.
- **`NothingGuessed(reason)`** (`ENROLLMENT`, `ATTESTATION`, `PEER_APPROVAL`): Kein Geheimnis eines
  bestehenden Kontos wurde geraten; es zählt keine Sperre.

Eine Variante, die nicht zur Rolle des Tools passt, weist der Orchestrator als Vertragsfehler
des Moduls ab, bevor er etwas bucht; das gilt für `Failed` wie für `Completed`.

Jeder gemeldete Claim wird vor der Verarbeitung geprüft (`Claim.validateValue`): Er darf nicht leer
sein, ein `PERSON_ID` muss eine Partnernummer sein (`tool_api.values.Partnernr`, `P` und neun Ziffern) und
ein Geburtsdatum ein ISO-Datum.

Jede `Completed`-Variante trägt außerdem `amr` (die nachgewiesenen Methoden, für
`AuthEvidence.currentAmr`), `achievedAcr` und `factorTypes` (eine Teilmenge der
`ToolDescriptor.factorTypes`). Die Variante *ist* die Kategorie und legt fest, was der
Orchestrator tut.

### `ATTEST`: ein Attribut bestätigen ist weder Identifizierung noch Anmeldung

`confirm-email` weist nach, dass jemand eine Adresse **kontrolliert**: Dort kommt ein Code an. Das
ist weder „wer bist du" (`IDENT`) noch „weise ein Mittel nach" (`AUTH`) noch „richte ein Mittel ein"
(`ENROLL`). Deshalb gibt es die Rolle `MethodRole.ATTESTATION` mit dem Ergebnis
`ToolOutcome.Completed.Attested`: Claims ja, `enrollmentRef` nein, `amr` leer und kein eigenes
Niveau. Der Anker wird unter dem Niveau geschrieben, das die Sitzung schon nachgewiesen hat; eine
bestätigte Adresse hebt das Niveau des Kanals nie an.

**Welche Rolle für ein neues Tool?** Es entscheidet, was der Nachweis beweist:

- **`IDENT`**: wer die Person ist. Der Nachweis zählt auf der Achse IDENTITY und hebt das IAL;
  Name, Vorname und Geburtsdatum sind Pflicht (siehe unten). Gleich, ob das Personenverzeichnis für
  die Werte einsteht (`ident-fsc`) oder das Verfahren selbst (`ident-eid`, `ident-nect` lesen das
  Dokument). `CORRELATION` (`ident-kvnr`) gehört dazu, beweist aber nichts (ADR-18).
- **`ATTEST`**: der Inhaber kontrolliert ein Attribut, das dem Konto als Anker gehört
  (`AttributeAuthority.Local`) und das auch ohne das eigene Verfahren gebraucht wird: Die
  Lookup-Tools finden das Konto über die E-Mail-Adresse, `enroll-password` und `enroll-email`
  setzen sie voraus.
- **`ENROLL`**: das Attribut gehört dem Verfahren (`AttributeAuthority.MethodModule`) und entsteht
  und vergeht mit ihm. So die Mobilnummer: `enroll-sms` beweist die Kontrolle mit derselben TAN,
  mit der es das Verfahren einrichtet, und nichts anderes hängt an der Nummer. Deshalb gibt es kein
  `confirm-phone`. Sobald ein Lookup oder ein anderes Verfahren die Nummer braucht, wird sie wie die
  E-Mail-Adresse ein Anker (ADR-17).

`ATTEST` unter `IDENT` einzuordnen, wäre falsch: `CandidateTools.forIdentification` und
`DefaultAuthPolicy.reIdentCandidates` böten das Tool dann als Identifizierung an, und über
`enrolledUnderAcr` stiege die Obergrenze der danach eingerichteten Verfahren (ADR-5). Welche
Variante zu welcher Rolle passt, prüft der Orchestrator bei jedem Ergebnis (`Completed.fits`,
`Failed.fits`).

Die Kontaktdaten des Personenverzeichnisses (E-Mail-Adresse, Mobilnummer) sind **kein** Attribut
des Kontos: Der Port `PersonDirectory` gibt sie nicht heraus, nur die Demo-Vorbelegung liest sie
(`DemoPersonDirectory`). Die E-Mail-Adresse des Kontos stammt allein aus `confirm-email`. Was davon
in Tokens steht: [API](05-api.md), „ID-Token-Claims".

Über die KVNR lässt sich keine Kontrolle nachweisen, nur die Zugehörigkeit zur Person. Sie gehört
also zu `IDENT`; ein `attest-kvnr` gibt es nicht.

**Jedes Identifizierungsverfahren liefert Name, Vorname und Geburtsdatum.** Danach wird eine Person
im Änderungsprotokoll wiedergefunden, auch nach der Löschung ihres Kontos (ADR-39).
`ToolHandlerRegistry` verweigert den Start, wenn ein Verfahren der Rolle `IDENTIFICATION` eines der
drei nicht deklariert.

`ToolCategory.SIDE_ACTION` benennt, was `PEER_APPROVAL`-Tools gemeinsam haben: Sie tragen nichts
zu ACR und AMR des *eigenen* Kanals bei, werden nie vorausgewählt, um eine Lücke zu schließen, und
nur ausdrücklich per `intent` gestartet. Bewusst heißt die Kategorie **nicht** `MISC` oder `OTHER`:
Das würde die Vollständigkeit aushebeln, die `ToolCategory` als abgeschlossenes `enum` sichert.
`AuthPolicy.candidateTools` und `enrollmentCandidates` haben einen eigenen Zweig für
`SIDE_ACTION`, der nichts anbietet. Lehnt jemand eine fremde Anfrage ab, braucht das **kein**
eigenes `ToolOutcome`; `Failed(reason = "Vom Nutzer abgelehnt")` genügt.

- `InProgress.stepData` ist **für den Client bestimmt** (z. B. `missingFields`); `Completed` und
  `Failed` sind **für den Orchestrator bestimmt** und werden nie direkt an den Client
  weitergegeben.
- `amr` und `achievedAcr` liefert jedes Tool selbst, weil dasselbe Verfahren je nach Durchlauf
  unterschiedliche Niveaus erreichen kann.
- `Completed.Authenticated.accountId` setzen nur die `-lookup`-Tools, die das Konto selbst finden.
  Die gewöhnlichen `auth-*`-Tools kennen es schon über den Kanal.
- Jedes Tool hat einen eigenen Controller, der seinen Handler direkt und typisiert aufruft, statt
  über eine allgemeine `Map<String, Any?>`. Es gibt keine Verteilung anhand der `toolId` zur
  Laufzeit ([Projektrahmen](08-projektrahmen.md) A11: „Lesbarkeit hat Vorrang vor einer maximal
  generischen API-Anbindung"). Dieser Controller liegt im selben Modul wie sein Handler (Abschnitt 4).
  Die `EnrollmentRef` wird im Controller aufgelöst und geprüft, bevor der Handler aufgerufen wird;
  der Handler bekommt also nie einen Parameter, der `null` sein könnte.
- **Die bestätigte E-Mail-Adresse ist ein Attribut des Kontos, kein Credential eines Moduls.**
  `confirm-email` liefert den `EMAIL`-Claim über `Completed.Attested`; die Journey übernimmt ihn per
  `AccountService.recordClaims`. `enroll-email` baut darauf auf
  (`requires ClaimRequirement(EMAIL, PROVEN)`) und schreibt selbst keinen Claim: Die Kontrolle
  über die Adresse ist schon bewiesen, ein zweiter Nachweis brächte nichts. `auth-email-lookup`,
  `auth-sms-lookup` und `auth-password-lookup` finden das Konto dagegen über die
  `resolveAccountByEmail`-Erweiterung von `AccountDirectory`; sie liefert nur eine Konto-ID, kein
  Profil. `confirm-email` prüft selbst **nicht**, ob die Adresse schon zu einem Konto gehört: Vor
  der Eingabe des Codes ist sie nur eingetippt, nicht bewiesen. Wem die bestätigte Adresse gehört,
  entscheidet danach die zentrale Auflösung. Gehört sie zu einem anderen Konto, geht das
  vorläufige Konto darin auf, sofern die bestätigte Identität dazu passt
  ([12-entscheidungen.md](12-entscheidungen.md) ADR-20).

---

## 3) Modulinterner Aufbau: das `Flow`-Muster (optional)

Ein Methodenmodul darf sich intern frei organisieren. Nur das `ToolOutcome` verlässt das Modul, nie
ein interner Zustand. Ein mögliches, aber nicht vorgeschriebenes Muster ist ein reiner `Flow`: Er
kennt nur seinen eigenen `State` und leitet aus `(State, Input)` eine `Decision` ab. Die enthält den
nächsten Zustand, Effekte (z. B. „TAN senden") und ein neutrales `FlowOutcome`
(`InProgress`/`Completed`/`Failed`). Der Handler übersetzt das `FlowOutcome` dann in ein
`ToolOutcome`.

---

## 4) Wo der Controller lebt: `tool_api` als Modulgrenze

Der `@RestController` eines Tools liegt **im selben Modul wie sein Handler** (z. B.
`ident_fsc.api.v1.IdentFscToolController`, `auth_sms.api.v1.AuthSmsToolController`), nicht im
`orchestrator`. Der Orchestrator kennt kein Methodenmodul beim Namen: `orchestrator/ModuleMetadata.kt`
deklariert `allowedDependencies = ["tool_api", "account", "texts", "demo_mode"]`,
also kein einziges Methodenmodul (`id_*`, `auth_*`).

Möglich macht das das gemeinsame Modul `tool_api` (`allowedDependencies = ["texts"]`), das beide
Seiten kennen dürfen. Die Selbstbeschreibung eines Tools (Abschnitt 2) liegt in seiner Wurzel, die
Ports liegen in Unterpaketen nach Thema ([Projektrahmen](08-projektrahmen.md) M8):

- **`ToolJourney`** – die Journey, so weit ein Tool-Controller sie sieht: Tool starten, Kontext
  laden, Ergebnis anwenden, Journey abbrechen. Implementiert von `ToolJourneyService` im
  `orchestrator` und per Konstruktor übergeben.
- **`Lockouts`** – die Sperren des Orchestrators nach Rateversuchen (`isLockedOut`,
  `isIdentLockedOut`), zum Lesen für Tools, die ihr Subjekt selbst auflösen (Lookup- und
  IDENT-Tools). Geschrieben werden sie nur vom Orchestrator, aus dem `Failed`-Ergebnis des Tools.
  Implementiert von `LockoutsService`.
- **`AccountDirectory`** / **`PersonDirectory`** / **`DeviceProofs`** – schmale Schnittstellen zum
  Lesen und Prüfen von Daten zu Konto, Person und Gerätenachweis (z. B. findet `auth-sms-lookup`
  darüber ein Konto anhand der E-Mail-Adresse). Implementiert von `AccountService` (`account`),
  `Personenverzeichnis` (`personenverzeichnis`) bzw. `DeviceProofValidator` (`orchestrator`),
  jeweils direkt im Domänenservice, ohne eigene Adapterklasse. Den Freischaltcode prüft `ident_fsc`
  ebenfalls über einen Port, `ActivationCodes` (`digest`, `isValid`), den `Freischaltcodes`
  implementiert (ADR-31, Nachtrag). Das Personenverzeichnis ist damit nur über Ports erreichbar; an
  ihnen wechselt die Sprache vom Deutsch des Registers ins Englische unseres Codes.
  `PersonDirectory` löst eine KVNR (`findPersonIdByKvnr`) oder eine Partnernummer
  (`findPersonIdByPartnernr`) zur PersonId auf, gleicht Personalien ab (`matchesMasterData`,
  `matchesPersonalDetails`) und gibt die Versicherungsnummer (`insuranceNumberOf`) und den
  Anzeigenamen (`displayName`) heraus – nie die übrigen Stammdaten.
- **`IdentityResolver`** – beantwortet, ob bestätigte Claims zu einem bestehenden Konto gehören
  (`resolve`, `attestedIdentityMatches`). Implementiert von `IdentityMatchingService` (`account`).
- **`KeycloakToolCalls`** – Tool-Aufrufe, die Keycloak für ein Konto macht, das es schon kennt,
  ohne Kanal und Journey: sein eigenes Passwortformular (`MgmtPasswordController` in
  `auth_password`). Der Port prüft, dass die Peer-Auth-Assertion genau dieses Konto nennt, und
  bucht das Ergebnis wie in einer Journey: einen Nachweis auf die Kontosperre, ein Einrichten als
  Claims und Verfahrensinstanz. Implementiert von `KeycloakToolCallsService` (`orchestrator`). Ein
  Modul, das eine Anfrage wegen des Kontozustands ablehnt, wirft `InvalidStateException` (`409`).
- **`SmsCredentialPort`** / **`PasswordCredentialPort`** / **`QrCredentialPort`** – richten ein
  Credential für Aufrufer ein, die das Konto schon kennen und keine Tool-Sitzung haben (etwa die
  Demo-Startdaten); implementiert in `auth_sms`, `auth_password` bzw. `auth_qr`.
- **`AttemptBudget`** / **`AttemptBudgets`** – das Zählwerk des Orchestrators, geliehen an die
  Module ([ADR-44](adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md)). Ein Modul
  leitet je Budget eine Klasse von `AttemptBudget` ab und legt dort Grenze und Zeitfenster fest;
  welchen Schlüssel es zählt und wann es zurücksetzt, entscheidet sein Handler. So begrenzen
  `auth_sms` (`SmsSendBudget`) und `auth_email` (`EmailSendBudget`) selbst, wie viele Codes sie an
  eine Nummer oder Adresse schicken. Der Namensraum eines Budgets folgt aus Modul und Klasse, ein
  Modul kann also nur in seinem eigenen zählen. Ein Budget ist keine Sperre: Es begrenzt den
  Versand, nicht das Raten ([Betrieb](07-betrieb.md) Abschnitt 4). `AttemptBudgets` implementiert
  `ModuleAttemptBudgets` (`orchestrator`); es speichert den Schlüssel nur als HMAC. Ein Modul, das
  selbst etwas ablehnt, wirft dafür `TooManyRequestsException` (`tool_api`, `429`), aber nur, wo
  die Ablehnung nichts verrät.
- **`PersonChanged`** – kein Port, sondern ein Event: die Änderungsmeldung des
  Personenverzeichnisses (Partnernummer, geänderte Attributarten, neue KVNR und
  Versicherungsnummer). Es liegt hier, damit `personenverzeichnis` es veröffentlichen und
  `account` darauf reagieren kann (`PersonChangeListener`), ohne dass die beiden Module einander
  kennen (ADR-34).
- **`EnrollmentCleanup`** – die umgekehrte Richtung: eine Schnittstelle, über die in einem
  Methodenmodul geschrieben wird, wenn ein Konto gelöscht wird ([API](05-api.md), „Konto
  löschen"). Jedes Modul mit eigener, langlebiger Credential-Tabelle (`auth_sms.enrollment`,
  `auth_password.enrollment`, `auth_device.enrollment`, `auth_qr.enrollment`,
  `auth_kobil.enrollment`; der Tabellenname ist `EnrollmentRef.type`) bringt dafür eine
  `@Component`-Implementierung mit, die über `enrollmentType` angesprochen wird. `auth_email`
  braucht keine, weil die bestätigte E-Mail-Adresse dem Modul `account` gehört (Abschnitt 2).
  `AccountDeletionService` (`orchestrator`) sammelt alle `EnrollmentCleanup`-Beans ein, so wie
  `ToolHandlerRegistry` die `ToolDescriptor`-Beans. Weder `orchestrator` noch `account` müssen dafür
  ein Methodenmodul beim Namen kennen.
- **Envelope-DTOs** (`ChannelResponse`, `ChannelBlock`, `ActiveMethodView`, `Next`, `DemoInfo`) –
  das gemeinsame Antwortformat, das jeder Tool-Controller zurückgibt.
- **`LeaveToolController`** – der einzige allgemeine Tool-Controller. Die `toolId` steht nur als
  Pfadparameter darin, eine Logik eines bestimmten Tools hat er nicht: Er verlässt ein Tool, als
  Ablehnen (`DELETE`) oder als Zurück zur Auswahl (`POST …/back`). Ein anderes Tool startet er nie;
  das aktiviert der Client danach selbst. Er liegt im Orchestrator
  (`orchestrator.api.v1.tool`), weder in einem Methodenmodul noch in `tool_api`: Was danach kommt,
  entscheidet der Zustand der Journey, und `tool_api` ist der Vertrag zwischen den Modulen, keine
  Web-Schicht.

Beide Seiten zeigen auf dieselbe Schnittstelle, nie direkt aufeinander: Ein Methodenmodul ruft
Methoden von `ToolJourney` auf, ohne `ToolJourneyService` oder den `orchestrator` zu kennen; der
`orchestrator` liest nie einen Handler eines Methodenmoduls. Die HTTP-Pfade
(`/orchestrator/api/v1/tools/...`) hängen nicht davon ab, in welchem Modul ein Controller liegt, denn Spring ordnet Anfragen nach
`@RequestMapping` zu, nicht nach dem Kotlin-Package. Details zur Modulliste und zur Richtung der
Abhängigkeiten: [Projektrahmen](08-projektrahmen.md) Abschnitt 3.
