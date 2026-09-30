# Überblick

Die Einführung in dieses Projekt: worum es geht, wer beteiligt ist, die wichtigsten Begriffe und
die tragenden Ideen in Kurzform. Jeder Abschnitt verweist auf das Kapitel, das ihn ausführt. Am
Ende steht, was Sie je nach Rolle als Nächstes lesen.

---

## 1) Worum es geht

Eine Krankenversicherung bietet eine **App** und eine **Website** an. Beide nutzen dieselben
Konten. Wer neu ist, **registriert** sich einmal, indem er sich ausweist (etwa mit einem
Freischaltcode per Brief oder dem Online-Ausweis), und richtet dabei Anmeldeverfahren ein: SMS,
Passwort, E-Mail, einen Geräteschlüssel. Danach **meldet** er sich mit diesen Verfahren **an**.
Für einzelne Vorgänge geht es auch ohne Konto: Die Versicherung schickt einen Brief mit einem
**Einmalkennwort**, und die Anmeldung damit gilt nur für diesen einen Vorgang.

Nicht jede Aktion verlangt dasselbe Vertrauen. Das System kennt drei **Sicherheitsniveaus**
(`loa1` bis `loa3`). Verlangt eine Aktion mehr, als die laufende Anmeldung nachgewiesen hat, muss
der Nutzer einen weiteren Nachweis erbringen: den **Step-up**.

```mermaid
flowchart LR
  N["Neuer Nutzer"] -- einmalig --> R["Registrierung"]
  R --> L
  B["Wiederkehrender Nutzer"] --> L["Login"]
  E["Person mit Brief"] -- "ohne Konto, Website" --> V["Einmalkennwort"]
  L -- "öffnet die Keycloak-Sitzung" --> T["AccessToken"]
  V -- "Token nur für diesen Vorgang" --> T
  T -- "direkt, ohne Orchestrator" --> F["Fachdienste"]
```

Das Ziel ist immer ein `AccessToken`, mit dem App oder Website anschließend die Fachdienste der
Versicherung **direkt** aufrufen. Die Registrierung ist kein Selbstzweck, sondern die einmalige
Voraussetzung für die Anmeldung. Das Token stellt Keycloak im üblichen OIDC-Ablauf aus; für die
App wickelt der Orchestrator diesen Ablauf auf dem Server ab, sodass die App keine eigene Logik zum
Erneuern der Tokens braucht.

Wie das für eine einzelne Person aussieht – Registrierung, Login, Step-up, QR-Login im Browser,
Löschung –, erzählt [11-beispiel-story.md](11-beispiel-story.md).

---

## 2) Wer beteiligt ist

```mermaid
flowchart LR
  App["App"] -- "DPoP" --> O["Orchestrator"]
  Web["Website (Browser)"] --> KC["Keycloak"]
  KC -- "von Server zu Server" --> O
  O -->|"Sitzung öffnen, Token"| KC
  O --> PV["Personenverzeichnis<br/>(simuliert)"]
  O --> EXT["Nect, KOBIL, Online-Ausweis,<br/>SMS- und Mail-Versand (simuliert)"]
```

- **App**: spricht direkt mit dem Orchestrator. Jede Anfrage trägt einen DPoP-Beweis, sodass der
  Orchestrator das Gerät an seinem Schlüssel wiedererkennt ([09-dpop.md](09-dpop.md)). In der Demo
  eine React-Oberfläche im Browser.
- **Website**: der Browser meldet sich bei **Keycloak** an und spricht nie direkt mit dem
  Orchestrator.
- **Keycloak**: stellt die Tokens aus und führt die Anmeldung auf der Website. Welche Verfahren
  angeboten werden und ob ein Nachweis reicht, fragt eine eigene Keycloak-Erweiterung beim
  Orchestrator nach. Echt, läuft als Container.
- **Orchestrator**: der Kern dieses Projekts. Er entscheidet, welche Schritte ein Nutzer
  durchläuft, führt die Konten und bewertet die Nachweise.
- **Personenverzeichnis**: die Stammdaten der Versicherung (Personen, Mitgliedsnummer, KVNR);
  stellt die Freischaltcodes und die Einladungen mit Einmalkennwort aus. Simuliert.
- **Externe Dienste**: Nect (Identifizierung per Ausweis, Reisepass, EUDI-Wallet), KOBIL
  (Gerätebindung), der Online-Ausweis (eID) sowie der Versand von SMS und E-Mail. Alle simuliert.
  Was ein echtes System zusagen müsste, steht in [port-vertraege.md](port-vertraege.md).

Verfahren, deren Niveau nur auf einer Simulation beruht, laufen ausschließlich im **Demomodus**
([ADR-36](adr/ADR-036-niveaus-und-ihre-nachweise.md)).

---

## 3) Die wichtigsten Begriffe

Die Doku ist deutsch, der Code englisch. Hinter jedem Begriff steht in Klammern sein Name im Code.

**Sitzungen und Abläufe**

- **Kanal** (`ChannelSession`): die Verbindung eines Clients, App oder Website, zum Orchestrator.
  Bewusst kurzlebig ([ADR-3](adr/ADR-003-channelsession-bewusst-kurzlebig-geraete-identitaet-in-deviceaccountlink.md)).
- **Intent** (`AuthIntent`): was der Nutzer erreichen will, samt dem Weg dorthin, etwa `FAST_ACCESS`,
  `REGISTER` oder `STEP_UP`. Die Liste steht in [04-orchestrierung.md](04-orchestrierung.md)
  Abschnitt 2.
- **Journey** (`AuthJourney`): ein laufender Durchlauf zu einem Intent; er nutzt ein oder mehrere
  Tools.
- **Zustand** (`JourneyState`): wo die Journey gerade steht, samt der Angaben dazu. Jeder Intent hat
  seine eigene, abgeschlossene Menge von Zuständen.
- **Tool** (`toolId`, z. B. `enroll-sms`): ein einzelner Schritt zum Identifizieren, Einrichten
  oder Anmelden.
- **Tool-Durchlauf** (`ToolSession`, `toolSessionId`): ein gestartetes Tool, z. B. eine
  TAN-Eingabe. Seine Kennung ist eine UUID, nicht zu verwechseln mit der `toolId`.

**Konto und Nachweise**

- **Anmeldeverfahren** (`AccountAuthMethod`, im Code „Methode“): was im Konto eingerichtet ist und
  eine Anmeldung ermöglicht, etwa Passwort oder SMS. Zu einem Verfahren gehören meist zwei Tools,
  eines zum Einrichten (`enroll-…`), eines zum Anmelden (`auth-…`).
- **Identifizierung**: ein Tool, das bestätigt, wer jemand ist (`ident-fsc`, `ident-eid`,
  `ident-nect`).
- **Niveau**, ausführlich **Sicherheitsniveau** (`acr`, Werte `loa1`, `loa2`, `loa3`): wie sehr einer
  Anmeldung vertraut wird.
- **Nachweis** (`SessionEvidence`, daraus `acr` und `amr`): was in der laufenden Sitzung bewiesen
  wurde.
- **Tokens der App** (`AppTokenSession`): die Tokens des App-Kanals, gekoppelt an dessen Nachweis.
- **Angabe** (`AccountClaim`, Tabelle `account.claim`): ein Eintrag im Konto, dass ein Attribut einen
  bestimmten Wert hat, mit der Quelle, die dafür einsteht, und einer Stufe: *belegt*, *nachgewiesen*
  oder *behauptet* (`ClaimTrust`).
- **Bestätigen** (`attest`, `ToolOutcome.Completed.Attested`): ein Attribut als geprüft melden,
  etwa die E-Mail-Adresse.
- **Widerruf** (`AccountRetraction`, Tabelle `account.retraction`): eine Angabe
  zurücknehmen.
- **Anker** (`AccountAnchor`, Tabelle `account.anchor`): ein Attribut, über das ein Konto
  eindeutig wiedergefunden wird, etwa die Partnernummer oder die bestätigte E-Mail-Adresse.
- **Rolle** (nicht gespeichert, abgeleitet aus den Ankern): ein Konto ohne zugeordnete Person ist
  ein **Interessent**, mit Partnernummer ein **Partner**, mit Mitgliedsnummer (auch
  Mitgliedsnummer genannt) ein **Versicherter** ([ADR-34](adr/ADR-034-personenverzeichnis-meldet-aenderungen.md)).
- **Mindestniveau für einen Anker** (`AnchorRule.acrFloor`): welches Niveau nötig ist, um einen
  Anker zu schreiben.
- **Obergrenze eines Verfahrens** (`maxAcr`, `enrolledUnderAcr`): das höchste Niveau, das ein
  Verfahren technisch hergibt bzw. unter dem es eingerichtet wurde.
- **Einladung** und **Einmalkennwort** (`auth-invite`, [ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)):
  Das Personenverzeichnis lädt eine Person per Brief zu einem **Vorgang** ein. Mit Mitglieds-
  oder Partnernummer und dem Einmalkennwort meldet sie sich auf der Website an, auch ohne Konto; die
  Tokens tragen den Vorgang (`process`) und gelten nur für ihn. Das Kennwort gilt bis zur Frist oder
  bis der Vorgang abgeschlossen ist.
- **Subjekt** (`Subject`): wem ein angemeldeter Kanal gehört, einem Konto oder einer Einladung; nie
  beidem.
- **Geräteverknüpfung** (`DeviceAccountLink`, `binding_key_ref`): welches Gerät zu welchem Konto
  gehört, erkannt am DPoP-Schlüssel. Sie zählt nicht als Anmeldung.

Alle Begriffe des Projekts, auch die hier nicht genannten, stehen im [Glossar](glossar/glossar.md).
Das [externe Glossar](glossar/externes-glossar.md) ist ein fremdes Nachschlagewerk; wie seine
Begriffe hier heißen, zeigt der [Abgleich](glossar/abgleich-externes-glossar.md).

---

## 4) Drei Sitzungsebenen

Die Sitzungen sind ineinander geschachtelt, von lang- zu kurzlebig:

- **`ChannelSession`**: der Kanal. Er überdauert einzelne Journeys, ist aber kurzlebig; dauerhaft
  bleibt nur die Geräteverknüpfung. Mit der Anmeldung öffnet er genau eine Keycloak-Sitzung und
  lebt von da an nicht länger als sie; wie lange, bestimmt Keycloak
  ([ADR-43](adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md)).
- **`AuthJourney`**: ein Durchlauf zu einem Intent, solange er läuft.
- **`ToolSession`**: ein einzelnes Tool, oft nur Minuten. Sie hält nur den Lebenszyklus; die
  Fachdaten (TAN, Freischaltcode) bleiben im Modul des Tools.

Eine Registrierung läuft zum Beispiel so: `ident-fsc` -> `confirm-email` -> `enroll-sms` ->
`enroll-password`. Nach dem Identifizieren bestätigt der Nutzer seine E-Mail-Adresse und richtet so
lange Verfahren ein, bis das verlangte Niveau erreicht ist. SMS allein reicht nur für `loa1`,
deshalb folgt ein Verfahren anderer Art, hier das Passwort; in der App stünde auch die Bindung an das
Gerät zur Wahl.

Details: [02-domaenenmodell.md](02-domaenenmodell.md)

---

## 5) Tools beschreiben sich selbst

Jedes Modul bringt die Beschreibung seiner Tools selbst mit: Kategorie, Verfahren, Faktortyp und
das höchste erreichbare Niveau. Es gibt keine zentral gepflegte Liste, die man beim Hinzufügen
eines Verfahrens vergessen könnte.

Über die Grenze eines Moduls geht nur ein `ToolOutcome`: Das Tool läuft noch, ist abgeschlossen
oder ist fehlgeschlagen. Was ein Tool geprüft hat, meldet es als Angabe (`Claim`).

Details: [03-tool-architektur.md](03-tool-architektur.md)

---

## 6) Der Client folgt `next`, er entscheidet nicht

Jede Antwort enthält ein `next`-Objekt: eine reine Adresse, entweder auf ein Tool oder auf eine
Seite des Orchestrators (Auswahl, Abschluss). Der Client ordnet `next` über eine feste
Routing-Tabelle einem Endpunkt zu und entscheidet nie selbst, welches Verfahren als Nächstes
kommt. Was ein Schritt zum Anzeigen braucht – fehlende Felder, Auswahl, Fehlergründe –, steht in
`stepData`.

Details: [05-api.md](05-api.md), für die Oberfläche [10-frontend.md](10-frontend.md)

---

## 7) Der Orchestrator entscheidet, die Module melden nur ihr Ergebnis

Nach jedem abgeschlossenen Tool entscheidet der Orchestrator, wie es weitergeht. Er verarbeitet
das Ergebnis (Konto anlegen, Verfahren einrichten, Nachweis übernehmen) und fragt dann die
`AuthPolicy`, ob die Nachweise für das verlangte Niveau reichen. Nur die Policy weiß, was eine
*Kombination* von Nachweisen bedeutet; ein Modul kennt nur sich selbst.

Für jeden Intent gibt es ein Zustandsdiagramm in [journeys/](journeys/); ein Test prüft, dass es zum
Code passt.

Details: [04-orchestrierung.md](04-orchestrierung.md)

---

## 8) Zwei Kanäle, eine Tool-API

**App – der Orchestrator führt.**

1. Die App legt per `POST /app/channels` einen Kanal an; jede Anfrage trägt einen DPoP-Beweis.
2. Der Orchestrator startet eine Journey zum Intent des Kanals (meist `FAST_ACCESS`) und bietet die
   Verfahren an, die ihr Zustand zulässt.
3. Ist die Anmeldung erfolgreich, holt der Orchestrator die Tokens bei Keycloak; der Kanal ist
   `AUTHENTICATED`.
4. Braucht die App später ein höheres Niveau, fordert sie es per
   `POST /channels/{channelSessionId}/step-ups` an. Reicht der Nachweis nicht, beginnt eine
   Journey `STEP_UP`, und die Antwort nennt gleich den nächsten Schritt.

**Website – Keycloak führt.**

1. Keycloaks Anmeldung ruft bei Bedarf über eine eigene Erweiterung den Orchestrator auf, von
   Server zu Server. Sie weist sich mit einer signierten Assertion aus statt mit DPoP.
2. Der Orchestrator startet die Journey `WEB_SELECT_METHOD` und bietet alle im Web nutzbaren Tools
   in einem Auswahlschritt an.
3. Keycloak zeigt das passende Formular und reicht die Eingaben an dieselben Tool-Endpunkte weiter,
   die auch die App nutzt.
4. Nach Erfolg übernimmt Keycloak Konto, `acr` und `amr` in seine Sitzung und stellt damit das
   Token aus.

Nur der Einstieg unterscheidet sich; danach nutzen beide Kanäle dieselben Tool-Endpunkte.

Details: [05-api.md](05-api.md) Abschnitte 2 und 3

---

## 9) Sicherheitsniveaus

- **`loa1`**: ein einzelnes Verfahren, etwa SMS oder Passwort.
- **`loa2`**: zwei Faktortypen – zwei Verfahren verschiedener Art (SMS plus Passwort), ein
  Verfahren mit zwei Faktoren (Geräteschlüssel mit PIN oder Biometrie) oder eine Identifizierung
  in derselben Sitzung. Verfahren verwalten und das Konto löschen verlangen `loa2`; bei einem nie
  identifizierten Konto reicht `loa1`.
- **`loa3`**: nur über eine starke Identifizierung (Online-Ausweis, Nect).

Das Niveau ist zweifach nach oben begrenzt
([ADR-5](adr/ADR-005-drei-obergrenzen-fuer-das-sicherheitsniveau.md)): Ein Verfahren liefert nie
mehr, als es technisch hergibt, und nie mehr, als die Sitzung bei seiner Einrichtung nachgewiesen
hatte. So kann niemand in einer schwach gesicherten Sitzung ein Verfahren einrichten und sich
damit dauerhaft ein höheres Niveau verschaffen.

Details: [04-orchestrierung.md](04-orchestrierung.md) Abschnitt 8

---

## 10) Wie der Code aufgebaut ist

Das Backend ist ein Spring-Modulith in Kotlin (`src/main/kotlin/com/example/identity/`). Die Module
liegen in fünf Gruppen, der Ordner sagt also schon, was ein Modul ist
([Projektrahmen](08-projektrahmen.md) Abschnitt 3):

- **`core/`**: `orchestrator` (Kanäle, Journeys, Policy, die REST-API der Kanäle und die
  Schnittstelle für Keycloak) und `account` (Konten, Angaben, Anker, Anmeldeverfahren).
- **`contract/`**: `tool_api`, der Vertrag zwischen Orchestrator und Verfahren, und `texts`.
- **`tools/`**: die Verfahren, je ein Modul mit eigenen Tool-Endpunkten (`ident_*`, `auth_*`). Sie
  erreichen den Orchestrator nur über `tool_api`.
- **`simulation/`**: die simulierten Fremdsysteme `personenverzeichnis`, `nect`, `kobil`, `sms`,
  `mail`.
- **`demo/`**: was es nur in der Demo gibt, `demo_mode` und `demo_seed`.

Daneben: `frontend/` (React), `keycloak-extension/` (Java-Erweiterung für Keycloak),
`keycloak-theme/` (Anmeldeseiten), `keycloak-migrations/` (Realm-Einrichtung), `api/` (der
API-Vertrag, aus dem Code erzeugt, [ADR-26](adr/ADR-026-api-vertrag-wird-generiert.md)).

**Der fachliche Kern** liegt in `orchestrator` und `account` jeweils im Paket `domain`, ohne
Framework, per ArchUnit geprüft
([ADR-40](adr/ADR-040-fachkern-im-paket-domain.md),
[08-projektrahmen.md](08-projektrahmen.md) Abschnitt 3). Wer die Regeln verstehen will, beginnt
dort:

- `orchestrator/domain/`: das Vokabular (`AuthIntent`, `AcrLevels`, `ToolCatalog`, Fehlercodes).
- `orchestrator/domain/journey/`: `IntentStrategy` mit `Transition` und `Action`, darunter die
  Zustände (`state/`) und die Strategie je Intent (`strategy/`), dazu `AccountRules.kt` und
  `CredentialRules.kt`.
- `orchestrator/domain/policy/`: `AuthPolicy`, `DefaultAuthPolicy`, `SessionEvidence`.
- `account/domain/`: `AnchorDecision`, `ClaimValues`, `PassportForm`.

Außen herum liegt die Technik: `JourneyService` und `JourneyActionExecutor` lesen, fragen die
Regel und schreiben; in `account` übernehmen das `application` (`ClaimLedger`, `AnchorRegistry`,
`ChangeLog`, `IdentityMatchingService`) und `infrastructure` (Entities, Repositories).

Wie Sie das System bauen, starten und testen, steht in [13-ausfuehren.md](13-ausfuehren.md).

---

## 11) Wie es weitergeht

Die Nummern der Kapitel geben keine Leserichtung vor. Je nach Rolle:

- **Fachexperte, Reviewer**: [11-beispiel-story.md](11-beispiel-story.md) ->
  [04-orchestrierung.md](04-orchestrierung.md) „Einstieg für Fachexperten“ -> die Diagramme in
  [journeys/](journeys/) -> [12-entscheidungen.md](12-entscheidungen.md) für das Warum.
- **Backend-Entwickler**: [03-tool-architektur.md](03-tool-architektur.md) „Einstieg:
  Zusammenspiel an einem Schritt“ -> [02-domaenenmodell.md](02-domaenenmodell.md) ->
  [04-orchestrierung.md](04-orchestrierung.md) -> [06-ablaeufe.md](06-ablaeufe.md) ->
  [09-dpop.md](09-dpop.md) -> [08-projektrahmen.md](08-projektrahmen.md); vor Änderungen am Kern
  [invarianten.md](invarianten.md).
- **Frontend-Entwickler**: [10-frontend.md](10-frontend.md) „Einstieg: Wie `next` die App
  steuert“ -> [05-api.md](05-api.md). Die Interna des Backends verbirgt die API.
- **Betrieb**: [13-ausfuehren.md](13-ausfuehren.md) -> [07-betrieb.md](07-betrieb.md) ->
  [port-vertraege.md](port-vertraege.md), bevor ein simuliertes System durch ein echtes ersetzt
  wird.
- **KI-Agent**: [00-agent-quickstart.md](00-agent-quickstart.md), danach nur die Kapitel, die die
  Aufgabe braucht.

Alle Dokumente auf einer Seite: [README.md](README.md).
