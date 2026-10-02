# Projektrahmen

Dieses Kapitel beschreibt die Aufgabenstellung, die Modulstruktur und die technischen
Rahmenbedingungen der Anwendung. Den Einstieg in das Projekt gibt der [Überblick](01-ueberblick.md).

---

## 1) Aufgabenstellung

Gebaut wird eine lauffähige Anwendung mit **Spring Boot Modulith**. Sie zeigt, wie Registrierung und
Anmeldung mit DPoP abgesichert werden. Das System besteht aus:

- Einem Frontend in React und TypeScript, das DPoP-Proofs erzeugt und mit dem Backend spricht.
- Einem `orchestrator`, der den Zustand von Sitzungen und Journeys verwaltet und die fachlichen Regeln
  durchsetzt (Richtlinie, Wiederholungen, DPoP-Bindung), ohne die Tool-Module zu kennen.
- Mehreren fachlichen Modulen (`ident_fsc`, `ident_eid`, `ident_nect`, `ident_kvnr`, `auth_sms`, `auth_password`, `auth_email`, `auth_device`, `auth_qr`, `auth_kobil`, `auth_invite`),
  die ihre eigenen Tool-Endpunkte mitbringen und den Orchestrator ausschließlich über
  `tool_api` erreichen ([Tool-Architektur](03-tool-architektur.md) Abschnitt 4).
- Zwei Datenmodulen (`account`, `personenverzeichnis`), die Konto- bzw. Personendaten halten und
  ebenfalls Teile von `tool_api` implementieren.
- Einer H2-Datenbank, deren Schema Flyway-Migrationen aufbauen.

### Qualitätsziele

| Priorität | Ziel | Beschreibung |
|-----------|------|--------------|
| 1 | Modularität | Klare fachliche Module mit definierten Abhängigkeiten |
| 2 | Verifizierbarkeit | Architektur- und Modulstruktur automatisiert prüfbar |
| 3 | Aktualität | Verwendung aktueller Versionen des Spring-Ökosystems |
| 4 | Entwicklerfreundlichkeit | Sofort ausführbar über den Gradle Wrapper |

---

## 2) Kontextabgrenzung (C4 System Context)

```
┌─────────────────────────────────────────────┐
│              Externe Nutzer /               │
│              Klienten-Systeme               │
└───────────────────┬─────────────────────────┘
                    │ HTTP / REST
                    ▼
┌─────────────────────────────────────────────┐
│           Identity-Demo Applikation         │
│  (Spring Boot Modulith, Port 8080)          │
└─────────────────────────────────────────────┘
```

- **Name**: `identity-demo`
- **Typ**: Spring Boot Webanwendung
- **Schnittstelle nach außen**: HTTP/REST (Tomcat auf Port 8080)

---

## 3) Module

Die Module liegen unter `com.example.identity`, gruppiert nach ihrer Rolle. Eine Gruppe ist nur ein
Ordner, kein Modul: Spring Modulith erkennt Module an `@ApplicationModule` in ihrer
`ModuleMetadata.kt` (`spring.modulith.detection-strategy: explicitly-annotated`). Die Modul-ID ist
das letzte Paketsegment und zugleich Datenbankschema, Flyway-Ordner `db/migration/<id>/`,
OpenAPI-Datei `api/modules/<id>.yaml` und bei den Simulationen das Text-Bundle. Wer im Code nach
der ID eines Moduls fragt, fragt `ModuleId` (`tool_api`); `ModulithStructureTest` prüft Gruppe
und Namen.

```
com.example.identity
├── core/         orchestrator, account
├── contract/     tool_api, texts
├── tools/        ident_fsc, ident_eid, ident_kvnr, ident_nect,
│                 auth_sms, auth_email, auth_password, auth_qr, auth_device, auth_kobil,
│                 auth_invite
├── simulation/   personenverzeichnis, kobil, nect, sms, mail
└── demo/         demo_mode, demo_seed
```

### Kern (`core`)

- **M1** `orchestrator` — Verwaltet den Zustand von Sitzungen und Journeys, die Regeln und die Wiederholungen; stellt die REST-API der Kanäle bereit und implementiert die `tool_api`-Ports `ToolJourney`, `Lockouts`, `KeycloakToolCalls`, `DeviceProofs` und `RateLimits`
- **M1a** `orchestrator.domain` — Kein eigenes Modulith-Modul, sondern das unterste Paket **innerhalb** von `orchestrator`: die gemeinsamen Begriffe (`AuthIntent`, `AmrSource`, `AcrLevels`, `OrchestratorException`, `FeatureFlagProvider`, `ToolCatalog`), dazu der fachliche Kern – die Journey-Zustände, `IntentStrategy` mit `Transition`/`Action`, `AuthPolicy` und `SessionEvidence`. Weil alle anderen Pakete des Orchestrators von ihm abhängen dürfen, es selbst aber von keinem, bleiben die Pakete des Orchestrators frei von Zyklen: `AmrSource` sagt zum Beispiel, woher ein Nachweis stammt (eine Frage der Richtlinie), und liegt deshalb hier und nicht neben der JPA-Entität, die den Nachweis speichert. Regel, Inhalt und Lesereihenfolge: [Fachkern und Technik](#fachkern-und-technik)
- **M4** `account` — Konten, Identifikationen und Anmeldeverfahren; implementiert die `tool_api`-Ports `AccountDirectory` und `IdentityResolver`

### Verträge (`contract`)

- **M8** `tool_api` — Der Vertrag zwischen Orchestrator und Tool-Modulen, einzige Abhängigkeit `texts`, als Modulith-Modul `OPEN`. In der Wurzel der Tool-Lebenszyklus: die Selbstbeschreibung (`ToolDescriptor`, `ToolOutcome`, `StepData`) und die Journey aus Sicht des Tools (`ToolJourney`, `Lockouts`). Darunter nach Thema: `claims` (Claims, `AcrLevel`, Ankerregeln), `values` (E-Mail, Mobilnummer, KVNR, Mitgliedsnummer, Partnernummer), `directory` (Ports zu Konto und Person), `credentials` (Ports, die ein Tool-Modul anbietet), `device` (Geräte-Proof), `envelope` (Antwortformen `ChannelResponse`, `Next`), `ratelimit` und `retention` (Zählwerk und Aufbewahrung des Orchestrators). Enthält keine Bean und keinen Controller: ein Vertrag, keine Web-Schicht ([Tool-Architektur](03-tool-architektur.md) Abschnitt 4)
- **M18** `texts` — Bibliothek für mehrsprachige Nutzertexte (ADR-33): `Text` (deutsche Vorlage im Code, ausgeliefert als Referenz) und `TextBundle` (Sprachdateien per ETag). `allowedDependencies = []`; jedes Modul mit Nutzertexten deklariert diese Abhängigkeit, auch die simulierten Fremdsysteme

### Verfahren (`tools`)

Je ein Modul mit eigenen Tool-Endpunkten; es erreicht den Orchestrator nur über `tool_api`.

- **M2** `ident_fsc` — Identifizierung (Tool `ident-fsc`); eigener `@RestController`; prüft den Freischaltcode beim Personenverzeichnis über den Port `ActivationCodes` (ADR-31, Nachtrag)
- **M10** `ident_eid` — Zweite Identifizierung (Tool `ident-eid`, Mock der Online-Ausweisfunktion); bestätigt nur die Kartendaten, löst niemanden auf (ADR-18); eigener `@RestController`
- **M10a** `ident_kvnr` — Zuordnung einer bestätigten Identität zu ihrer Person im Personenverzeichnis (per KVNR, sonst Partnernummer) (Tool `ident-kvnr`, ADR-18); eigener `@RestController`
- **M16** `ident_nect` — Identifizierung über Nect (Tool `ident-nect`): Der Nutzer wechselt auf die Seite von Nect und kommt mit einer Vorgangsnummer zurück; das Ergebnis holt das Backend selbst ab. Bestätigt wie `ident_eid` nur die Daten des Dokuments (ADR-18); `amr` je Verfahren `nect-eid`/`nect-epass`/`nect-eudi`. Erlaubte Abhängigkeit zum Fremdsystem: `nect`
- **M3** `auth_sms` — SMS-Verfahren (Tools `enroll-sms`, `auth-sms`, `auth-sms-lookup`); eigene `@RestController`
- **M7** `auth_email` — E-Mail-Verfahren (Tools `confirm-email`, `enroll-email`, `auth-email`, `auth-email-lookup`) mit eigenem `EmailCodeGenerator`. Abhängigkeit nur auf `tool_api`: liest Account-IDs und Ankerwerte über `AccountDirectory`, liefert `EMAIL`-Claims; kein direkter Zugriff auf `account`
- **M6** `auth_password` — Passwort-Verfahren (Tools `enroll-password`, `auth-password`, `auth-password-lookup`); setzt über `ToolDescriptor.requires` eine bestätigte Adresse voraus (`ClaimRequirement(EMAIL, PROVEN)`) ([Tool-Architektur](03-tool-architektur.md)). Dazu die zustandslosen Endpunkte für Keycloaks eigenes Passwortformular (`MgmtPasswordController`, Port `KeycloakToolCalls`)
- **M11** `auth_qr` — Anmeldung per QR-Code auf der Website, bestätigt in der App (Tools `enroll-qr`, `auth-qr`, `auth-qr-lookup`, `confirm-qr-login`, [`CONFIRM_PEER_LOGIN`](journeys/confirm-peer-login.md)); speichert `QrLoginRequest` selbst, kein Zugriff auf `account`; eigene `@RestController`
- **M23** `auth_invite` — Anmeldung mit Einmalkennwort für einen Vorgang (Tool `auth-invite`, [ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)): KVNR oder Partnernummer und das Kennwort aus dem Brief. Fragt die Einladungen des Personenverzeichnisses über den Port `Invitations`; meldet die Einladung als Subjekt, nie ein Konto. Eigener `@RestController`, Abhängigkeiten nur `tool_api` und `texts`
- **M12** `auth_device` — Geräteschlüssel als eigenes Anmeldeverfahren (Tools `enroll-device`, `auth-device`); eigene `@RestController`, keine Abhängigkeit von `account`
- **M14** `auth_kobil` — Gerätebindung über den externen Dienstleister KOBIL (Tools `enroll-kobil`, `auth-kobil`, [Abläufe](06-ablaeufe.md) Abschnitt 7); im Backend verwahrter PIN (ADR-21/ADR-22), PIN-Freigabe als eigene Unterressource; eigene `@RestController`, keine `account`-Abhängigkeit — aber eine ausdrücklich erlaubte Abhängigkeit zum Fremdsystem `kobil` (wie `ident_nect`)

### Simulierte Fremdsysteme (`simulation`)

Stellvertreter für Systeme, die es im Betrieb außerhalb gäbe (ADR-35). Ein Verfahren erreicht sie
nur entlang einer benannten Kante, das Personenverzeichnis nur über Ports
(`SimulationBoundaryArchitectureTest`).

- **M5** `personenverzeichnis` — Simuliertes **Personenverzeichnis** (Fremdsystem): verwaltet `Person`-Entitäten (Schlüssel ist die Partnernummer, `P` und neun Ziffern, zufällig vergeben; Mitgliedsnummer nur für Versicherte, KVNR nur zusammen mit ihr, beide änderbar) mit Anschrift, E-Mail-Adresse und Mobilnummer, stellt Freischaltcodes (ADR-31) und Einladungen mit Einmalkennwort (ADR-48) aus und meldet Änderungen als `PersonChanged` (ADR-34), beendete Einladungen als `InvitationEnded`. Schnittstellen: die `tool_api`-Ports `PersonDirectory`, `PersonMasterData` (Stammdaten für die Token-Claims in Keycloak), `ActivationCodes` (Klasse `Freischaltcodes`, für `ident_fsc`) und `Invitations` (Klasse `Einladungen`, für `auth_invite`) sowie die HTTP-Schnittstelle `/mock-personenverzeichnis/*` für die Seite `/personenverzeichnis/`; die Demo-Personen liest der Orchestrator über den eigenen Port `DemoPersonDirectory`
- **M15** `kobil` — Simuliertes **Fremdsystem**, kein Tool-Modul: Abhängigkeiten nur `texts` und `demo_mode` (kennt weder `tool_api` noch die Journey), eigenes Schema, zwei Schnittstellen — die HTTP-Schnittstelle `/mock-kobil/*` für die App (Gegenstück zum MC SDK) und `KobilSsms` für unser Backend. Untersteht nicht unserer Aufbewahrung
- **M17** `nect` — Simulierter **Identifizierungsdienst** Nect, kein Tool-Modul: Abhängigkeiten nur `texts` und `demo_mode`, eigenes Schema, zwei Schnittstellen — `NectIdent` für unser Backend und die HTTP-Schnittstelle `/mock-nect/*` für die Sprungseite `/nect/` (eID, Reisepass, EUDI-Wallet). Untersteht nicht unserer Aufbewahrung
- **M19** `sms` — Simulierter **SMS-Anbieter** mit Postausgang (`SmsGateway`) für `auth_sms`; im Demomodus liest die Seite `/briefkasten/` ihn über `/mock-sms/outbox`. Einzige Abhängigkeit `demo_mode`, kein eigenes Schema
- **M20** `mail` — Simulierter **Mailserver** mit Postausgang (`MailServer`) für `auth_email`; im Demomodus liest die Seite `/briefkasten/` ihn über `/mock-mail/outbox`. Einzige Abhängigkeit `demo_mode`, kein eigenes Schema

### Nur für die Demo (`demo`)

- **M21** `demo_mode` — Der eine Schalter `demo.mode` (ADR-36): als Bean `DemoMode`, als Bedingungen `OnlyInDemoMode`/`OutsideDemoMode` und als Markierung `DemoSurface` für die unauthentifizierten Oberflächen der simulierten Fremdsysteme. Nur hier wird die Property gelesen (`DemoModeSwitchTest`), also mit einer Voreinstellung für alle
- **M13** `demo_seed` — Nur für die Demo, und nur Daten: die Testpersonen mit ihren Freischaltcodes und Briefen (`db/migration/demo_seed/V16__testdata.sql`), außerhalb des Demomodus nicht migriert. Konten legt es nicht an. Eine Testperson registriert sich wie jeder andere, in der App oder auf der Website; erst danach hat sie ein Konto. Ohne Code und ohne Abhängigkeiten; `ModuleMetadata` hält nur die Modulgrenze fest

### Außerhalb der Anwendung

- **M22** `kcmigrate` — Keycloak-Realm-Migrationen im Gradle-Projekt `keycloak-migrations` (Paket `com.example.identity.kcmigrate`). Eine Bibliothek, kein Modulith-Modul: Sie trägt keine `@ApplicationModule`, ihre Grenze sichert `OrchestratorArchitectureTest` (nur `core.orchestrator.keycloak` und `ActiveSessions` benutzen sie)
- `keycloak-extension` (Paket `com.example.identity.kcext`) — Die Keycloak-Erweiterung, als eigenes Jar in Keycloak geladen; keine Abhängigkeit der Anwendung

### Modulabhängigkeiten (C4 Component View)

```
         ┌─────────────┐
         │   Browser   │
         └──────┬──────┘
                │ HTTP / REST (ein Port, ein Pfadraum: /orchestrator/api/v1/...)
                ▼
┌────────────────────────────────────────────────────────────────────────┐
│  @RestController - verteilt über mehrere Module, URLs unverändert      │
│                                                                        │
│   core/orchestrator      tools/ident_fsc / ident_eid / ident_nect /    │
│   (Channel-Endpunkte,    ident_kvnr / auth_sms / auth_password /       │
│    Journey/Policy)       auth_email / auth_device / auth_qr /          │
│                          auth_kobil / auth_invite (eigene Endpunkte)   │
│                                                                        │
│         orchestrator.api.v1.tool.LeaveToolController (generisch)       │
└───────────────────────────────┬────────────────────────────────────────┘
                                │ implementiert / ruft auf
                                ▼
                  ┌───────────────────────────────────┐
                  │         contract/tool_api         │
                  │ ToolDescriptor, ToolOutcome,      │
                  │ ToolJourney, Lockouts, directory/ │
                  │ device/ envelope/ claims/ values/ │
                  └────────────────▲──────────────────┘
                                   │ implementieren die Ports
              ┌────────────────────┼─────────────────────┐
              │                    │                     │
     ┌─────────────────┐   ┌──────────────┐   ┌──────────────────────────┐
     │core/orchestrator│   │ core/account │   │simulation/               │
     │ (ToolJourney,   │   │(AccountDir-  │   │ personenverzeichnis      │
     │  DeviceProofs)  │   │  ectory)     │   │ (PersonDirectory)        │
     └─────────────────┘   └──────────────┘   └──────────────────────────┘
```

- Kein Tool-Modul verweist auf den `orchestrator` und umgekehrt (`core/orchestrator/ModuleMetadata.kt`: `allowedDependencies = ["tool_api", "account", "texts", "demo_mode"]`). Die einzige gemeinsame Abhängigkeit ist `tool_api` — ein Tool-Modul kennt nur dessen Interfaces, nie eine konkrete Klasse des Orchestrators.
- Die HTTP-Pfade (`/orchestrator/api/v1/tools/...`) sind unabhängig vom Kotlin-Paket des jeweiligen `@RestController` (`ident_fsc.api.v1`, `ident_eid.api.v1`, `ident_kvnr.api.v1`, `auth_sms.api.v1`, `auth_password.api.v1`, `auth_email.api.v1`, `auth_device.api.v1`, `auth_qr.api.v1`, `auth_kobil.api.v1`, `auth_invite.api.v1`, `ident_nect.api.v1`) — Spring leitet nach `@RequestMapping` weiter, nicht nach Paket. Ausnahmen: `kobil.api.v1`, `nect.api.v1`, `personenverzeichnis.api.v1`, `sms.api.v1` und `mail.api.v1` liegen bewusst NICHT unter `/orchestrator/api`, sondern unter `/mock-kobil`, `/mock-nect`, `/mock-personenverzeichnis`, `/mock-sms` bzw. `/mock-mail` — sie sind die Fremdsysteme, nicht diese Anwendung.
- Die Tool-Module sind voneinander und von `account` entkoppelt, einschließlich `auth_email`. Abhängigkeiten zu simulierten Fremdsystemen sind ausdrücklich erlaubt, nicht nur geduldet: `auth_kobil → kobil`, `ident_nect → nect` (nur `NectIdent`); das Personenverzeichnis nur über Ports (ADR-31, Nachtrag). Konten werden über `tool_api.AccountDirectory` nachgeschlagen. Geschrieben wird nur über Claims im `ToolOutcome`, die die Journey übernimmt. Hilfsfunktionen, die ein Konto über die E-Mail-Adresse suchen, sind Kotlin-Erweiterungsfunktionen des Ports.
- `auth_sms` versteckt seine internen Datenbank-IDs hinter einer undurchsichtigen `EnrollmentRef` ([06-ablaeufe.md](06-ablaeufe.md)).
- Die Grenzen zwischen den Paketen sichert `@ApplicationModule(allowedDependencies = ...)` je Modul ab, und `ModulithStructureTest` prüft sie („each module depends only on what it declares“); eine unerlaubte Abhängigkeit lässt den Build scheitern. Da Kotlin keine Annotationen an Paketen kennt, trägt je eine `ModuleMetadata.kt` die Deklaration (`@ApplicationModule` ist `@Target({PACKAGE, TYPE})`); ein `package-info.java` ist nicht nötig.
- Das Frontend kommuniziert ausschließlich über HTTP mit der Applikation als Ganzes; welches Modul einen Endpunkt implementiert, ist für es nicht sichtbar.

### Anforderungen an die Modulstruktur

- **M-1** — Jedes Modul hat ein eigenes Paket in einer der Gruppen `core`, `contract`, `tools`, `simulation`, `demo`; seine ID ist das letzte Paketsegment.
  - *Kriterium:* Paketstruktur `com.example.identity.<gruppe>.<modul>`, `@ApplicationModule(id = "<modul>")`; geprüft in `ModulithStructureTest`
- **M-2** — Jedes Modul mit Laufzeitlogik stellt sie als Spring-Bean bereit.
  - *Kriterium:* `@Service`/`@Component`/`@RestController` im Modul; `texts` und `tool_api` sind reine Verträge ohne Bean (`ToolApiArchitectureTest`)
- **M-3** — Tool-Module und Orchestrator sind nur über die gemeinsame SPI `tool_api` verbunden, nie direkt.
  - *Kriterium:* Konstruktor-Injection nur mit `tool_api`-Interfaces (`ToolJourney`, `Lockouts`, `KeycloakToolCalls`, `AccountDirectory`, `PersonDirectory`, `ActivationCodes`, `Invitations`, `DeviceProofs`, `RateLimits`); kein Tool-Modul importiert `orchestrator` und umgekehrt. Benannte Ausnahmen, jeweils zu einem simulierten Fremdsystem: `auth_kobil → kobil`, `ident_nect → nect`, `auth_sms → sms`, `auth_email → mail` (simulierter SMS-Anbieter und Mailserver mit Postausgang)
- **M-4** — Die Modulstruktur ist verifizierbar.
  - *Kriterium:* `ApplicationModules.verify()` in Tests
- **M-5** — In `orchestrator` und `account` liegen die fachlichen Regeln im Paket `domain`, frei von Framework und Technik.
  - *Kriterium:* `OrchestratorArchitectureTest` und `AccountArchitectureTest` (Abschnitt [Fachkern und Technik](#fachkern-und-technik))

### Fachkern und Technik

In den beiden Modulen mit den meisten Regeln, `orchestrator` und `account`, ist getrennt, was
entschieden wird und was dafür gelesen und geschrieben wird. Die Begründung steht in
[ADR-40](adr/ADR-040-fachkern-im-paket-domain.md).

**Die Regel.** Alles, was ein Entwickler lesen muss, um die fachlichen Regeln zu verstehen, liegt
im Paket `domain` des Moduls. Dieses Paket

- benutzt kein Framework: kein Spring, kein JPA/Hibernate, kein Jackson, kein Logging;
- hängt von nichts anderem im eigenen Modul ab. Alles andere im Modul darf `domain` benutzen, nie
  umgekehrt.

Verträge anderer Module darf `domain` benutzen (`tool_api`, `texts`, im Orchestrator
auch die Lesesicht `account.AccountProfile`). Beide Regeln prüft ArchUnit:
`OrchestratorArchitectureTest` für `orchestrator.domain`, `AccountArchitectureTest` für
`account.domain`. Ein Verstoß lässt den Build scheitern.

**Was im Orchestrator wo liegt.**

- `orchestrator.domain` (M1a): das Vokabular – `AuthIntent`, `AcrLevels`, `AmrSource`,
  `ChannelType`, `ErrorCode`, `OrchestratorException`, `ToolCatalog`.
- `orchestrator.domain.journey`: `IntentStrategy`, `JourneyContext`, `JourneyEvent`, `Transition`,
  `Action`; darunter `state` (die Zustände je Intent) und `strategy` (eine Strategie je Intent).
  Dazu die Regeln für das Ausführen von Aktionen:
  - `AccountRules.kt`: welches Konto eine Aktion betrifft (ADR-18/20), damit eine Sitzung nie an
    ein Konto gerät, das sie nicht nachgewiesen hat;
  - `CredentialRules.kt`: auf welchem Niveau ein Nachweis zählt (ADR-5), wann ein Gerät
    stillschweigend verknüpft wird und welche Verfahren mit einem anderen wegfallen.
- `orchestrator.domain.policy`: `AuthPolicy`, `DefaultAuthPolicy`, `SessionEvidence`,
  `ClaimRequirements`.
- Technik außen herum, weiter nach Thema geordnet: `journey` (`JourneyService`,
  `JourneyActionExecutor`, `JourneyContextFactory`, `JourneyRouting`, die Entität `AuthJourney`,
  `JourneyStateCodec`) und daneben unter anderem `session`, `channel`, `api.v1`, `keycloak`, `dpop`,
  `retention`.
- `DomainBeans` im Wurzelpaket legt die Strategien und `DefaultAuthPolicy` als Spring-Beans an.
  Es ist die eine Stelle, die aufzählt, welche Strategien es gibt, und die einzige Klasse außerhalb
  von `domain.policy`, die `DefaultAuthPolicy` kennt; alle anderen benutzen `AuthPolicy`.
- Fachlich heißt der Kanal **Web** (`ChannelType.WEB`, `WEB_SELECT_METHOD`, „Web-Kanal“), als
  Gegenstück zur App. Die Technik, die ihn bedient, heißt **Keycloak**: Klassen `Keycloak…`, Paket
  `keycloak`, Konfiguration `keycloak.peer-auth`, gleich ob Keycloak uns aufruft (Keycloak-Fassade)
  oder wir Keycloak.
  `kc` steht nur noch dort, wo es nach außen festliegt: in den Pfaden `/kc/…` und Feldern wie
  `kcSessionId` des veröffentlichten Vertrags, im `amr`-Wert `kc` und in der Keycloak-Erweiterung
  (Paket `kcext`).

**Was im Konto-Modul wo liegt.**

- `account.domain`: `AnchorDecision` (wann ein Anker gebunden, ersetzt oder abgelehnt wird,
  ADR-11/19), `normalizeClaimValue` und `ClaimKey` (`ClaimValues.kt`), die Schreibweise, in der Namen
  verglichen werden – so, wie der Chip im Pass sie schreibt (`PassportForm.kt`).
- `account.application`: die Dienste, die diese Regeln anwenden – `AnchorRegistry`, `ClaimLedger`,
  `ChangeLog`, `IdentityMatchingService`, `PersonChangeListener`, `PersonLookupKey`.
- `account.infrastructure`: Entitäten und Repositories (`Account`, `AccountAnchor`, `AccountClaim`,
  `AccountAuthMethod`, `AccountRetraction`, `ChangeLogEntry`, `SignInLogEntry`) und
  `AttributeTypeConverter`.
- Wurzelpaket: `AccountService` implementiert den Port `AccountDirectory` und ist der Eingang für
  die anderen Module; dazu Lesesichten wie `AccountProfile`.

**Technik liest, fragt die Regel, schreibt.** Nach diesem Muster arbeitet die Technik in beiden
Modulen:

- Im Orchestrator treibt `JourneyService` jeden Übergang durch vier Phasen: lesen
  (`JourneyContextFactory`), entscheiden (`IntentStrategy`), ausführen (`JourneyActionExecutor`),
  `next` ableiten (`JourneyRouting`) ([Orchestrierung](04-orchestrierung.md) Abschnitt 5, „Die vier
  Phasen eines Übergangs“). Eine Strategie bekommt nur den lesenden `JourneyContext` und gibt eine
  `Transition` zurück; sie wirkt nie selbst. `JourneyActionExecutor` liest Konto und Kanal, fragt
  `AccountRules.kt` und `CredentialRules.kt` (etwa `IdentificationTarget.forUnresolved`,
  `proofLevel`, `levelToWriteUnder`) und schreibt das Ergebnis.
- Im Konto-Modul nimmt `AccountService` den Auftrag an und gibt ihn an `account.application`
  weiter. `AnchorRegistry` liest dort die vorhandenen Anker, fragt `AnchorDecision.decide` und
  schreibt; `ClaimLedger` und `IdentityMatchingService` vergleichen Werte über
  `normalizeClaimValue`.

Die Regel bekommt jede Tatsache als Wert (oder als Funktion, wenn das Nachschlagen teuer ist und
nur in einem Zweig gebraucht wird). Deshalb lässt sie sich ohne Datenbank und ohne Spring testen.

**Die Uhr ist eine Tatsache wie jede andere.** Die Anwendung liest die Zeit nur aus der einen
`java.time.Clock`-Bean (`ClockConfig` im Wurzelpaket `com.example.identity`, kein Modul: der Typ
kommt aus dem JDK, eine Abhängigkeit zwischen Modulen entsteht nicht). Dienste bekommen sie per
Konstruktor; Entitäten und Regeln bekommen den Zeitpunkt vom Aufrufer (`isExpiredAt(now)`,
`touch(now)`, `createdAt` im Konstruktor). So lassen sich Ablauf, Proof-Fenster, Zählfenster und
Aufbewahrung mit einer gestellten Uhr prüfen, ohne zu warten.

**Ids und Werte sind Wertklassen.** Jede eindeutige Id hat einen eigenen Typ (`AccountId`,
`ChannelSessionId`, `ToolSessionId`, `InvitationId` in `tool_api.ids`, `JourneyId` und
`SessionEvidenceId` im Fachkern des Orchestrators; die Person-Id ist die `PartnerNumber`), ebenso
jeder Wert mit eigenem Format (`Email`, `PhoneNumber`, `Kvnr`, `MemberNumber`, `AcrLevel`). Sie
gelten überall, auch in Entitäten, Repositories, DTOs und Controller-Parametern; im JSON und in der
Datenbank steht der nackte Wert. Erzeugt wird eine Wertklasse auf genau zwei Wegen: der
Konstruktor nimmt einen Wert in Normalform und prüft ihn im `init`-Block, `parse(raw)` nimmt Text
von außen, normalisiert ihn und liefert `null`, wenn er nicht passt. `parse` gibt es nur, wo
Nutzereingaben ankommen. Ausgepackt (`.value`) wird nur an einer Grenze: im Repository, wo JPA den
nackten Wert braucht (Primärschlüssel `Long` des Kontos, Id-Listen), und dort, wo ein Fremdsystem
oder ein Zähler einen `String` erwartet.

Weil Kotlin Wertklassen auf der JVM auflöst, brauchen sie an einigen Stellen eine Hilfe; jede steht
an einer Stelle und ist dort begründet:

- **JPA.** UUID- und String-Ids speichert Hibernate direkt, `AccountId` über `AccountIdConverter`.
  Deshalb nimmt eine Repository-Methode `AccountId?`, und Id-Listen gehen ausgepackt an die Abfrage
  (offen bei Spring Data: spring-data-commons#2868). Der Id-Typ eines Repositorys bleibt primitiv;
  gesucht wird über eine abgeleitete Methode wie `findByToolSessionId`.
- **OpenAPI.** `ValueClassOpenApiConfig` nimmt den Hash aus Methodennamen (`activate-Ab3dE_f`) und gibt
  Pfadparametern das Schema ihres Werts. swagger-core braucht das Jackson-2-Kotlin-Modul.
- **Architekturtests.** Eine Regel über Methodennamen vergleicht den Namen ohne diesen Hash, sonst
  trifft sie nichts mehr.
- **MockK.** `any()` erzeugt Wertklassen über ihren Konstruktor; `ProjectConfig` registriert für die
  Klassen mit Formatprüfung einen gültigen Platzhalter.

**Wo man zu lesen anfängt.**

1. Eine Strategie unter `orchestrator/domain/journey/strategy`, etwa `StepUpStrategy.kt`, mit
   ihren Zuständen unter `domain/journey/state` (`StepUpState.kt`). Das Zustandsdiagramm dazu
   steht in [journeys/step-up.md](journeys/step-up.md).
2. `AccountRules.kt` und `CredentialRules.kt` unter `domain/journey`: was beim Ausführen einer
   Aktion gilt.
3. `DefaultAuthPolicy` unter `domain/policy`: welches Niveau eine Sammlung von Nachweisen ergibt.
4. Für das Konto-Modul `AnchorDecision.kt` und `ClaimValues.kt` unter `account/domain`.
5. Erst danach die Technik: `JourneyService`, `JourneyActionExecutor`, `AnchorRegistry`.

---

## 4) Persistenz

- Als Datenbank dient **H2**: im Betrieb als Datei unter `./data/identitydb`, im Testprofil **im Arbeitsspeicher**.
- Das Schema wird mit **Flyway**-Migrationen aufgebaut, der Zugriff erfolgt über **Spring Data JPA**.
- **Ein Datenbankschema je Modul** (`account`, `orchestrator`, `auth_sms`, …): Jede Tabelle liegt im
  Schema ihres Moduls, Fremdschlüssel nur innerhalb eines Schemas
  ([12-entscheidungen.md](12-entscheidungen.md) ADR-16). Der Flyway-Verlauf bleibt in `PUBLIC`.
- Auch `kobil` hat ein eigenes Schema, obwohl es kein Modul dieser Anwendung ist, sondern ein
  simuliertes Fremdsystem. Gerade deshalb ist die Trennung wichtig: Läge es im Schema von
  `auth_kobil`, könnte das Tool an der Schnittstelle vorbei nachsehen, und die Demo würde den echten
  Ablauf nicht mehr zeigen.
- Im Modul `personenverzeichnis` existiert eine `Person`-Entität mit `id` (die Partnernummer), `versnr` (eindeutig, nur Versicherte), `kvnr` (eindeutig, nur zusammen mit `versnr`), `name`, `vorname`, `strasse`, `hausnummer`, `plz`, `ort`, `geburtsdatum`. Dazu `freischaltcode` (nur Hash, Ablauf, Widerruf) und `brief` (der simulierte Brief mit dem Klartext, ADR-31).
- Im Demomodus spielt beim Start eine Flyway-Migration Testpersonen und gültige Freischaltcodes ein
  (`demo_seed`).

Die Session- und Tool-Entitäten beschreibt [02-domaenenmodell.md](02-domaenenmodell.md) (Tabellenmodell
in Abschnitt 7), Aufbewahrung und Löschung [07-betrieb.md](07-betrieb.md).

### H2-Konsole: nur beim Host-Start

Die H2-Konsole unter `/h2-console` ist bewusst eingeschaltet, aber `web-allow-others` bleibt
`false` (Begründung im Kommentar in `application.yml`). Spring Security schützt nur
`/orchestrator/admin/**` (`AdminSecurityConfig`), dieser Pfad bleibt offen. Ihn schützt deshalb allein die Prüfung von H2, ob die Anfrage vom eigenen Rechner kommt, und
dahinter liegen Passwort-Hashes, Geräteschlüssel und alle Sitzungen.

Diese Prüfung vergleicht die Absenderadresse. Bei `./gradlew bootRun` ist das `127.0.0.1`, und die
Konsole funktioniert. **Im Container (`compose.yml`) geht sie nicht:** Dort erreicht die Anfrage den
Orchestrator über die Portweiterleitung `8080:8080` mit der Adresse des Container-Netzes. Für H2 ist
das eine Verbindung von außen, und H2 lehnt sie ab mit *„remote connections ('webAllowOthers') are
disabled on this server“*. Der Schutz wirkt also wie vorgesehen.

Wer in die Datenbank sehen will, startet deshalb den Orchestrator direkt auf dem Rechner und lässt
nur Keycloak über Compose laufen. Die Variante `host` (Voreinstellung von `KEYCLOAK_SETUP_VARIANT`)
richtet Keycloak dafür bereits auf `host.containers.internal:8080` aus. Wer die Daten eines Laufs im
Container braucht, kopiert die Datei aus dem gestoppten Volume `orchestrator-data` heraus.
`web-allow-others` einzuschalten kommt nicht in Frage: Der Port ist auf dem Rechner nach außen
freigegeben, und jeder, der ihn erreicht, bekäme vollen Lese- und Schreibzugriff.

- **P-1** — H2 als Datei im Betrieb und im Arbeitsspeicher für Tests.
  - *Kriterium:* `application.yml` und `application-test.yml` entsprechend konfiguriert
- **P-2** — Das Schema baut Flyway auf, mit einem Migrationsordner je Modul.
  - *Kriterium:* `src/main/resources/db/migration/<modul>/`; `ModuleMigrationLocations` findet die Ordner selbst
- **P-3** — Auf Personen wird über Spring Data JPA zugegriffen.
  - *Kriterium:* `PersonRepository extends JpaRepository`
- **P-4** — Die Adresse einer Person ist in einzelne Attribute aufgeteilt.
  - *Kriterium:* Entität enthält `strasse`, `hausnummer`, `plz`, `ort`. Bestätigt wird die Straße dagegen als **eine** Zeile mit Hausnummer (`AttributeType.STREET_ADDRESS`), so wie eID und PID sie liefern; das Personenverzeichnis setzt `strassenzeile` an seiner Schnittstelle zusammen
- **P-5** — Testdaten werden beim Start eingespielt.
  - *Kriterium:* Flyway-Migration oder Initialisierungsroutine vorhanden
- **P-6** — Freischaltcodes zum Testen stehen beim Start zur Verfügung.
  - *Kriterium:* Eine Flyway-Migration legt gültige Freischaltcodes für die Testpersonen an

---

## 5) Architekturbeschränkungen

| ID | Beschränkung | Begründung |
|----|--------------|------------|
| A1 | Build-Tool: Gradle mit Kotlin-DSL | Einheitliche, typsichere Build-Konfiguration |
| A2 | Gradle Wrapper muss enthalten sein | Reproduzierbarkeit ohne lokale Gradle-Installation |
| A3 | JVM-Version 21 (Ziel des Bytecodes), Kotlin 2.4.20 | Voraussetzung für Spring Boot 4.x; Kotlin als Implementierungssprache |
| A4 | Aktuelle Spring Boot-Version verwenden | Sicherheit und Aktualität |
| A5 | Versionen zentral in `gradle/libs.versions.toml` pflegen | Zentrale Versionsverwaltung, konsistente Abhängigkeiten |
| A6 | Frontend-Build ist in den Gradle-Build integriert | Einheitlicher Build-Prozess für Backend und Frontend |
| A7 | Das gebaute Frontend landet in `src/main/resources/static` | Spring Boot liefert das Frontend als statische Ressource aus |
| A8 | Datenbank: H2 (als Datei im Betrieb, im Arbeitsspeicher in Tests) | Einfache lokale Entwicklung und schnelle Tests |
| A9 | Schemaverwaltung mit Flyway | Versionierter und reproduzierbarer Datenbankaufbau |
| A10 | Datenzugriff mit Spring Data JPA | Standardisierte Persistenzschicht |
| A11 | Lesbarkeit hat Vorrang vor einer maximal generischen API-Anbindung | Endpunkte, DTOs und Handler bleiben tool-spezifisch explizit (`ident-fsc`, `enroll-sms`, `auth-sms`) |

---

## 6) Lösungsstrategie und Versionen

- **Framework**: Spring Boot mit eingebettetem Tomcat
- **Sprache**: Kotlin als Backend-Implementierungssprache
- **Modularisierung**: Spring Modulith zur Architekturverifikation
- **Build**: Gradle mit Kotlin-DSL (`build.gradle.kts`, `settings.gradle.kts`)
- **Versionsverwaltung**: Gradle Version Catalog in `gradle/libs.versions.toml`
- **Persistenz**: H2 + Spring Data JPA + Flyway
- **Frontend**: React + TypeScript mit Vite
- **Frontend-Integration**: Vite-Build schreibt in `src/main/resources/static`; Gradle führt `npm install` und `npm run build` aus
- **Test**: Kotest auf der JUnit-Plattform mit Spring Boot Test, MockK und Spring Modulith Test-Starter

| Komponente | Version |
|------------|---------|
| Spring Boot | `4.1.0` |
| Spring Modulith | `2.1.1` |
| Dependency Management Plugin | `1.1.7` |
| Gradle (Wrapper) | `9.7.0` |
| Kotlin | `2.4.0` |
| JVM Target | `21` |
| React | `19.3.0` |
| React DOM | `19.3.0` |
| TypeScript | `7.0.2` |
| Vite | `8.3.0` |
| H2 | (von Spring Boot verwaltet) |
| Flyway | (von Spring Boot verwaltet) |

---

## 7) Build und Verifikation

- `./gradlew build` baut Backend und Frontend und führt alle Tests aus.
- `./gradlew bootRun` startet die Anwendung auf Port 8080. Der Befehl läuft, bis man ihn beendet; zum Prüfen eignen sich Integrationstests besser.
- Integrationstests starten den eingebetteten Server auf einem zufälligen Port und prüfen den Ablauf einer DPoP-gesicherten Sitzung.
- `ApplicationModules.verify()` prüft, ob die erlaubten Abhängigkeiten **zwischen** den Modulen eingehalten werden.
- `OrchestratorArchitectureTest` prüft die Schichtung innerhalb von `orchestrator`, die Modulith nicht sieht:
  - Die Teilpakete müssen zyklenfrei sein (`slices().beFreeOfCycles()`). Dafür liegen die gemeinsamen Begriffe im untersten Paket `domain` (M1a und [ADR-27](adr/ADR-027-gemeinsame-typen-im-kernel-paket.md)).
  - `orchestrator.domain` benutzt kein Framework und hängt von nichts anderem im Orchestrator ab (Abschnitt 3, [Fachkern und Technik](#fachkern-und-technik)); dasselbe prüft `AccountArchitectureTest` für `account.domain`.
  - Aus einer offenen Transaktion darf kein Keycloak-Aufruf herausgehen. Sonst hält die Transaktion Zeilensperren so lange, wie der fremde Dienst zum Antworten braucht. Einzige Ausnahme ist `KeycloakTokenProvider`: dort ist das Token die Antwort selbst.
  - Nichts außerhalb von `api` hängt an `api.v1`. Dort stehen nur Routen, Request-DTOs, Parameterbindung und die OpenAPI-Beschreibung. Die Kanal-Services, die Zugriffsprüfungen (`ChannelAccessGuard`), `DemoDisclosure` und die Antwortformen liegen darunter in `orchestrator/channel`. Die Antwortformen sind wie `ChannelResponse` in `tool_api` unversioniert, weil es eine globale Version gibt ([API](05-api.md) Abschnitt 1). Ein v2 könnte damit neben v1 stehen, ohne v1 zu importieren.
  - Nur `DemoDisclosure` erzeugt ein `DemoInfo`. Damit entfernt `demo.mode=false` die Klartext-TANs aus jeder Antwort, statt sie an einer von mehreren Stellen zu filtern ([ADR-28](adr/ADR-028-demo-werte-abschaltbar.md)).
- `ClockArchitectureTest` prüft, dass außer `ClockConfig` keine Klasse der Anwendung die Systemuhr selbst liest (`Instant.now()`, `LocalDate.now()`, `System.currentTimeMillis()`, `Clock.system*()`, `Date()`), siehe Abschnitt 3, [Fachkern und Technik](#fachkern-und-technik).
- `ToolSessionCoverageTest` prüft gegen das tatsächliche Schema, dass ein Aufräumlauf jede `*_tool_session`-Tabelle leert ([Betrieb](07-betrieb.md) Abschnitt 3).
- `EventPublicationRegistryTest` prüft, dass ein fehlschlagender `@ApplicationModuleListener` eine offene Zeile hinterlässt ([Betrieb](07-betrieb.md) Abschnitt 3a).
- `checkOpenApiSnapshot` und `generateFrontendApiTypes` halten den API-Vertrag und die daraus erzeugten Frontend-Typen deckungsgleich ([API](05-api.md) Abschnitt 1).
- `checkPublishedApiCompatibility` vergleicht den Vertrag mit dem veröffentlichten Stand `api/published/v1.yaml` und schlägt bei einem Bruch fehl; `ContractScopeTest`, `StepDataExamplesTest` und `DiscriminatorMappingTest` prüfen Umfang, Beispiele und Diskriminatoren des Vertrags ([API](05-api.md) Abschnitt 1).
- Die CI führt zusätzlich `tsc -b` aus (vitest prüft keine Typen), dazu `oxlint`, `npm audit` und die
  Playwright-Tests; ein eigener Workflow prüft den Code mit CodeQL.

### Vorbedingungen in Integrationstests

Die Registrierung (Identifizierung → E-Mail-Bestätigung → Anmeldeverfahren einrichten) wird nur dort
per HTTP Schritt für Schritt durchlaufen, wo sie selbst geprüft wird: `RegistrationFlowIntegrationTest`,
`RequiredActionIntegrationTest` (Reihenfolge der Pflichten) und `JourneyTraceIntegrationTest` (das
Journey-Trace entsteht nur durch einen echten Durchlauf).

Alle anderen Testklassen brauchen nur ihr *Ergebnis*: „ein Konto mit SMS und Passwort, mit diesem
Gerät verknüpft“. Das stellt `AccountFixtures` (im Testcode) über die Dienste der Fachmodule her,
nicht über SQL. So gelten dieselben Regeln wie im echten Betrieb (Mindestniveaus der Anker, Ersetzen
eines vorhandenen Verfahrens, Herkunft der Claims).

Einstiegspunkte in `IntegrationTestSupport`:

| Hilfsfunktion | Vorbedingung |
| --- | --- |
| `seedRegisteredAccount()` | Konto existiert (sms + Passwort, bestätigte Adresse, Gerät verknüpft), kein Kanal |
| `loginAsSeededAccount()` | dazu ein angemeldeter loa2-Kanal (`amr = [sms, password]`) |
| `registerAndAuthenticate()` | echte Registrierung, dadurch zusätzlich mit einem eigenen `fsc`-Nachweis |

Die letzten beiden unterscheiden sich fachlich: Ein nur angemeldeter Kanal hat keinen eigenen
Nachweis einer Identifizierung. Tests, die einen solchen brauchen (etwa das Entfernen eines
Verfahrens, das sonst der Nachweis der aktuellen Anmeldung wäre), müssen `registerAndAuthenticate()`
verwenden.
