# Glossar nach englischen Begriffen

Der Code ist englisch, die Doku deutsch. Dieses Register führt die englischen Namen aus Code, API
und Konfiguration alphabetisch auf, jeweils mit dem deutschen Begriff und einer kurzen deutschen
Beschreibung. Ausführlich steht jeder Begriff im [Glossar](glossar.md); der Link am Ende eines
Eintrags führt zum Buchstaben dort.

---

## A

- **`AAL`** → AAL und IAL: die Frage „dieselbe Person wie beim letzten Mal?“, beantwortet durch
  Anmeldeverfahren. [Glossar](glossar.md#a)
- **`ACCOUNT_LOOKUP_AUTH`** → **Tool-Rolle** einer Anmeldung mit Kontosuche: das Tool findet das
  Konto erst. [Glossar](glossar.md#t)
- **`AccountAnchor`** (Tabelle `account.anchor`) → **Anker**: ein Attribut, über das ein Konto
  eindeutig wiedergefunden wird. [Glossar](glossar.md#a)
- **`AccountAuthMethod`** → **Anmeldeverfahren**: was im Konto eingerichtet ist und eine Anmeldung
  ermöglicht. [Glossar](glossar.md#a)
- **`AccountClaim`** (Tabelle `account.claim`) → **Angabe**: ein Attribut mit Wert, Quelle, Stufe
  und dem Niveau, unter dem sie entstand. [Glossar](glossar.md#a)
- **`AccountLockoutService`**, **`PersonLockoutService`** → **Sperre** nach fünf Fehlversuchen für ein
  Konto bzw. eine Person. [Glossar](glossar.md#s)
- **`AccountProfile.isSetUp`** → **Eingerichtet** / **im Aufbau**: ob ein Konto schon ein
  Anmeldeverfahren hat. [Glossar](glossar.md#e)
- **`AccountRetraction`** (Tabelle `account.retraction`) → **Widerruf**: nimmt eine Angabe zurück. [Glossar](glossar.md#w)
- **`acr`** → **Niveau** (Sicherheitsniveau): wie sehr einer Anmeldung vertraut wird, Werte `loa1` bis `loa3`.
  [Glossar](glossar.md#n)
- **`acrFloor`** (`ChannelSession.acrFloor`) → **Untergrenze des Kanals**: gilt für jede Journey auf
  dem Kanal. [Glossar](glossar.md#u)
- **`Action`** → **Aktion**: was ein Übergang an Daten ändern will; ausgeführt vom
  `JourneyActionExecutor`. [Glossar](glossar.md#a)
- **`amr`** → die Verfahren, die der **Nachweis** der Sitzung enthält. [Glossar](glossar.md#n)
- **`AmrSource`** (`orchestrator`, `kc`) → **Quelle eines Nachweises**. [Glossar](glossar.md#q)
- **`AnchorAcrFloor`** (`establish`, `replace`) → **Mindestniveau für einen Anker**. [Glossar](glossar.md#m)
- **`APP`** (`ChannelType.APP`) → **App-Kanal**: der Kanal der App, jede Anfrage mit DPoP.
  [Glossar](glossar.md#a)
- **`AppTokenIssuer`** → gibt die **Tokens der App** aus und verlängert die Sitzung dahinter.
  [Glossar](glossar.md#t)
- **`AppTokenSession`** (Tabelle `orchestrator.app_token_session`) → **Tokens der App**: die Tokens
  des App-Kanals samt Keycloak-Sitzung. [Glossar](glossar.md#t)
- **`attemptBudget`** → **Versuchsbudget**: die Fehlversuche einer Journey über alle Tools hinweg.
  [Glossar](glossar.md#v)
- **`attest`**, **`ATTESTATION`** → **Bestätigen**: ein Attribut als geprüft melden, ohne das Niveau
  zu heben. [Glossar](glossar.md#b)
- **`authData`** → was jede Antwort an den **Web-Kanal** über Subjekt, Niveau und Verfahren
  mitgibt. [Glossar](glossar.md#w)
- **`AuthIntent`** → **Intent**: was der Nutzer erreichen will, samt Strategie.
  [Glossar](glossar.md#i)
- **`AuthJourney`** → **Journey**: ein laufender Durchlauf zu einem Intent. [Glossar](glossar.md#j)
- **`AuthPolicy`**, **`DefaultAuthPolicy`** → **AuthPolicy**: die Regeln, nach denen das
  Sicherheitsniveau berechnet wird. [Glossar](glossar.md#a)
- **`RateLimitRecord`** → **Sperre** und **Mengenbegrenzung**: zählt Fehlversuche und Mengen, sperrt nach fünf
  Fehlversuchen für 15 Minuten. [Glossar](glossar.md#s)

## B

- **`binding_key_ref`**, **`@BindingKey`** → **Bindungsschlüssel**: woran der Orchestrator eine
  Anfrage einem Kanal zuordnet. [Glossar](glossar.md#b)

## C

- **`ChangeLog`** (Tabelle `account.change_log`) → **Änderungsprotokoll**: welche Angabe wann
  geändert wurde, ohne Werte, zehn Jahre. [Glossar](glossar.md#a)
- **`channel_binding`**, **`channelBinding`** → **Kanalbindung**: woran eine signierte Anfrage von
  Keycloak ihren Kanal erkennt. [Glossar](glossar.md#k)
- **`ChannelSession`** → **Kanal**: die kurzlebige Verbindung eines Clients zum Orchestrator.
  [Glossar](glossar.md#k)
- **`ChannelState`** → die Zustände eines **Kanals** (`ANONYMOUS`, `AUTHENTICATED`, …).
  [Glossar](glossar.md#k)
- **`ChannelType`** → **App-Kanal** (`APP`) oder **Web-Kanal** (`WEB`). [Glossar](glossar.md#a)
- **`claim`** → siehe `AccountClaim`.
- **`ClaimSource`** (Spalte `claim_source`) → **Quelle**: wer für eine Angabe einsteht.
  [Glossar](glossar.md#q)
- **`ClaimTrust`** (`AUTHORITATIVE`, `PROVEN`, `SELF_REPORTED`) → **Stufe einer Angabe**: belegt,
  nachgewiesen, behauptet. [Glossar](glossar.md#s)
- **`CONFIRM_PEER_LOGIN`** → der Intent, mit dem die App einen **QR-Login** freigibt.
  [Glossar](glossar.md#q)
- **`CORRELATION`** → **Korrelation**: ordnet eine schon bescheinigte Identität einem Datensatz zu,
  ohne selbst etwas zu beweisen. [Glossar](glossar.md#k)

## D

- **`DELETE_ACCOUNT`** → der Intent zum Löschen des **Kontos**. [Glossar](glossar.md#k)
- **`demo.mode`**, **`DemoMode`**, **`@DemoSurface`** → **Demomodus**: schaltet alles ein, was nur
  zur Vorführung dient. [Glossar](glossar.md#d)
- **`DeviceAccountLink`** → **Geräteverknüpfung**: welches Gerät zu welchem Konto gehört.
  [Glossar](glossar.md#g)
- **`DeviceProofs`**, **`device-proof+jwt`** → **Geräte-Proof**: belegt den Besitz des
  Geräteschlüssels für eine Anfrage. [Glossar](glossar.md#g)
- **`DPoP`** → **DPoP**: Verfahren, mit dem eine Anfrage belegt, dass sie vom Besitzer eines
  Schlüssels kommt; jede Anfrage trägt einen **DPoP-Proof**. [Glossar](glossar.md#d)

## E

- **`EmailSendLimit`** → **Versandlimit** von `auth_email`: wie viele Codes an eine Adresse gehen.
  [Glossar](glossar.md#v)
- **`enrolledUnderAcr`** → eine der **Obergrenzen eines Verfahrens**: das Niveau der Sitzung, in der
  es eingerichtet wurde. [Glossar](glossar.md#o)
- **`ENROLLMENT`** → **Tool-Rolle** eines Tools, das ein Anmeldeverfahren einrichtet.
  [Glossar](glossar.md#t)

## F

- **`FactorType`** (`KNOWLEDGE`, `POSSESSION`, `INHERENCE`) → **Faktortyp**: Wissen, Besitz,
  Biometrie. [Glossar](glossar.md#f)
- **`FAST_ACCESS`** → **Schnellzugang**: die übliche Anmeldung in der App. [Glossar](glossar.md#s)
- **`FeatureFlags`**, **`FeatureFlagService`** → **Feature-Flag** (etwa `register-enroll-first`,
  `keycloak-login-keycloakify`, `keycloak-loa1-password`). [Glossar](glossar.md#f)

## I

- **`IAL`** → AAL und IAL: die Frage „wer ist das?“, beantwortet durch eine Identifizierung.
  [Glossar](glossar.md#a)
- **`IDENTIFICATION`** → **Identifizierung**: ein Tool, das bestätigt, wer jemand ist.
  [Glossar](glossar.md#i)
- **`IntentStrategy`** → **Strategie** eines Intents, etwa `RegisterStrategy`. [Glossar](glossar.md#s)
- **`Invitation`** → **Einladung**: vom Personenverzeichnis per Brief ausgestellt, Grundlage des
  Vorgangszugangs. [Glossar](glossar.md#e)
- **`isDisposable`** (`AccountProfile.isDisposable`) → **verwerfbar**: ein Konto ohne Person, auf
  dem nie ein Anmeldeverfahren eingerichtet wurde. [Glossar](glossar.md#v)

## J

- **`JourneyActionExecutor`** → führt die **Aktion** eines Übergangs aus. [Glossar](glossar.md#a)
- **`JourneyEvent`** → das Ereignis, das einen **Übergang** auslöst. [Glossar](glossar.md#u)
- **`JourneyLifecycle`** → **Lebenszyklus einer Journey**. [Glossar](glossar.md#l)
- **`JourneyState`** → **Zustand**: wo eine Journey gerade steht. [Glossar](glossar.md#z)
- **`JourneyTraceEntry`** → ein Eintrag im **Journey-Protokoll**. [Glossar](glossar.md#j)

## K

- **`Kc…`** (etwa `KcChannelService`), Pfade **`/kc/…`** → die Fassade des Orchestrators für den
  **Web-Kanal**, benannt nach der Technik (Keycloak). [Glossar](glossar.md#w)
- **`keycloak-migrations`** → das Modul, das das **Realm** anlegt und pflegt. [Glossar](glossar.md#r)
- **`keycloakSessionId`**, **`UserSessionModel`** → **Keycloak-Sitzung**. [Glossar](glossar.md#k)
- **`KNOWN_ACCOUNT_AUTH`** → **Tool-Rolle** der Anmeldung eines bekannten Kontos.
  [Glossar](glossar.md#t)
- **`kvnr`** → **KVNR**, die Krankenversichertennummer. [Glossar](glossar.md#k)

## L

- **`loa1`**, **`loa2`**, **`loa3`** → die Werte des **Niveaus**. [Glossar](glossar.md#n)
- **`loa2-max-age`** (`identity.policy.…`) → wie lange ein **Nachweis** über `loa1` trägt,
  30 Minuten. [Glossar](glossar.md#n)
- **`LOGOUT`** → der Intent zum Abmelden mit Bestätigung. [Glossar](glossar.md#k)
- **`LOOKUP_LOGIN`** → **Anmeldung** eines Kontos ohne verknüpftes Gerät, über die
  E-Mail-Adresse. [Glossar](glossar.md#a)

## M

- **`MANAGE_AUTH_METHODS`** → der Intent zum Verwalten der **Anmeldeverfahren**.
  [Glossar](glossar.md#a)
- **`maxAcr`** → eine der **Obergrenzen eines Verfahrens**: das höchste Niveau, das es technisch
  hergibt. [Glossar](glossar.md#o)
- **`MemberNumber`**, **`MEMBER_NUMBER`** → **Mitgliedsnummer**, auch Versicherungsnummer genannt.
  [Glossar](glossar.md#m)
- **`method`** → **Anmeldeverfahren**; „Methode“ nur als Name im Code. [Glossar](glossar.md#a)
- **`MethodEvidence`**, **`MethodEvidenceRecord`** → ein Verfahren im **Nachweis** der Sitzung,
  gespeichert im Feld `methods`. [Glossar](glossar.md#n)
- **`ModuleMetadata`** → beschreibt ein **Modul** und die Module, die es benutzen darf.
  [Glossar](glossar.md#m)

## N

- **`next`** → die Adresse des nächsten Schritts in jeder Antwort. [Glossar](glossar.md#n)
- **`next.step`** → **Schritt** innerhalb eines Tools. [Glossar](glossar.md#s)

## O

- **`Offer`**, **`activatable()`** → **Angebot**: die Tools, die ein Zustand gerade anbietet.
  [Glossar](glossar.md#a)
- **`Orchestrator`** → **Orchestrator**: der Server, der die Schritte bestimmt und das Niveau
  berechnet. [Glossar](glossar.md#o)
- **`orchestrator.tool_availability`** → **Tool-Sperre** und Reihenfolge je Kanal.
  [Glossar](glossar.md#t)

## P

- **`PartnerNumber`**, **`partnerNumber`** → **Partnernummer**: die Kennung einer Person im
  Personenverzeichnis, im Konto der Anker `PERSON_ID`. [Glossar](glossar.md#i)
- **`peer-auth`** → **Peer-Auth**: wie Keycloak und Orchestrator einander ausweisen.
  [Glossar](glossar.md#p)
- **`PEER_APPROVAL`** → **Tool-Rolle** einer Freigabe für einen anderen Kanal, etwa die
  QR-Bestätigung. [Glossar](glossar.md#t)
- **`PERSON_ID`**, **`personId`** → **Partnernummer**: so heißt die Kennung der Person als Anker, in
  Feldern und Tokens. [Glossar](glossar.md#p)
- **`process`** (Claim im Token) → der **Vorgang**, für den ein Vorgangszugang gilt.
  [Glossar](glossar.md#v)
- **`ProductionModeCheck`** → prüft beim Start außerhalb des **Demomodus** die Einstellungen.
  [Glossar](glossar.md#d)
- **`prospect`** → **Interessent**: ein Konto ohne zugeordnete Person. [Glossar](glossar.md#i)
- **`provenAt`** → der Zeitpunkt eines **Nachweises**; über `loa1` zählen nur die letzten
  30 Minuten. [Glossar](glossar.md#n)

## R

- **`RateLimit`**, **`RateLimits`** (Paket `tool_api.ratelimit`) → **Mengenbegrenzung**: wie oft
  ein Modul etwas in einem Zeitfenster tun darf. [Glossar](glossar.md#m)
- **`RateLimitCounter`** → das Zählwerk der **Mengenbegrenzung** und der **Sperre**.
  [Glossar](glossar.md#m)
- **`RateLimitRecord`** (Tabelle `rate_limit`) → eine Zeile des Zählwerks je Bereich und Schlüssel.
  [Glossar](glossar.md#m)
- **`RateLimitScope`** → der Bereich eines Zählers (`ACCOUNT`, `PERSON`, `BINDING_KEY`, `ADMIN`).
  [Glossar](glossar.md#m)
- **`RE_IDENTIFY`** → erneute **Identifizierung** als Sub-Journey. [Glossar](glossar.md#i)
- **`REGISTER`** → **Registrierung**. [Glossar](glossar.md#r)
- **`RestoreData`** → **RestoreData**: gibt die Nachweise eines früheren Durchlaufs derselben
  Keycloak-Sitzung weiter. [Glossar](glossar.md#r)
- **`RetractionSource`** (Spalte `claim_source` des Widerrufs) → die **Quelle** eines **Widerrufs**.
  [Glossar](glossar.md#q)

## S

- **`selectMethod`** → der Schritt der **Auswahlseite**. [Glossar](glossar.md#a)
- **`SessionEvidence`** → **Nachweis**: was in der laufenden Sitzung bewiesen wurde.
  [Glossar](glossar.md#n)
- **`SessionEvidenceRecord`** (Tabelle `orchestrator.session_evidence`) → gespeicherter **Nachweis**
  eines Kanals. [Glossar](glossar.md#n)
- **`SignInLog`** (Tabelle `account.sign_in_log`) → **Anmeldeprotokoll**: Anmeldungen,
  Fehlversuche, Sperren, Abmeldungen. [Glossar](glossar.md#a)
- **`SmsSendLimit`** → **Versandlimit** von `auth_sms`: wie viele TANs an eine Nummer gehen.
  [Glossar](glossar.md#v)
- **`STEP_UP`** → **Step-up**: hebt das Niveau eines angemeldeten Kanals. [Glossar](glossar.md#s)
- **`sub-journey`**, **`Transition.RequireSubJourney`** → **Sub-Journey**: eine Journey, die eine
  andere unterbricht. [Glossar](glossar.md#s)
- **`Subject`** → **Subjekt**: wem ein angemeldeter Kanal gehört, Konto oder Einladung.
  [Glossar](glossar.md#s)

## T

- **`targetAcr`** → **Ziel eines Durchlaufs**: das Niveau, das ein Step-up erreichen soll.
  [Glossar](glossar.md#u)
- **`tool_api`** → die Modulgrenze, über die die Verfahren am **Orchestrator** hängen.
  [Glossar](glossar.md#o)
- **`ToolDescriptor`** → wie sich ein **Tool** selbst beschreibt: Rolle, Verfahren, Faktortypen,
  Obergrenze. [Glossar](glossar.md#t)
- **`toolId`** → **Tool**: ein einzelner Ablauf, etwa `enroll-sms`. [Glossar](glossar.md#t)
- **`ToolOutcome`** → das Ergebnis, das ein **Tool** dem Orchestrator meldet. [Glossar](glossar.md#t)
- **`ToolRole`** → **Tool-Rolle**: was ein Tool fachlich tut. [Glossar](glossar.md#t)
- **`ToolSession`**, **`toolSessionId`** → **Tool-Durchlauf**: ein gestartetes Tool.
  [Glossar](glossar.md#t)
- **`Transition`** → **Übergang** von einem Zustand zum nächsten. [Glossar](glossar.md#u)

## U

- **`usableByCaller`** (`ToolDescriptor.usableByCaller`) → prüft, ob ein Verfahren mit
  **Gerätebindung** auf dem anfragenden Gerät nutzbar ist. [Glossar](glossar.md#g)

## V

- **`versnr`** (ID-Claim, Keycloak-Attribut) → **Mitgliedsnummer**, im Vokabular des
  Personenverzeichnisses. [Glossar](glossar.md#m)

## W

- **`WEB`** (`ChannelType.WEB`) → **Web-Kanal**: Keycloak führt die Anmeldung, der Orchestrator
  entscheidet. [Glossar](glossar.md#w)
- **`WEB_SELECT_METHOD`** (`web_select_method`) → der **Intent** für Anmeldung und Step-up im
  Web-Kanal. [Glossar](glossar.md#i)

---

## Tool-Kennungen

Das Präfix sagt, was ein Tool tut: `ident-` identifiziert, `enroll-` richtet ein Verfahren ein,
`auth-` meldet an, `confirm-` bestätigt. `…-lookup` meldet an, ohne dass das Konto vorher bekannt
ist. Ausführlich: [03-tool-architektur](../03-tool-architektur.md) Abschnitt 1.

- **`auth-device`**, **`enroll-device`** → **Gerätebindung**: Anmeldung mit dem Schlüssel eines Geräts.
- **`auth-email`**, **`auth-email-lookup`**, **`enroll-email`** → Anmeldung per Code an die
  E-Mail-Adresse.
- **`auth-invite`** → **Vorgangszugang** mit Einmalkennwort, nur im Web-Kanal.
- **`auth-kobil`**, **`enroll-kobil`** → **Gerätebindung**: Anmeldung mit der KOBIL-App.
- **`auth-password`**, **`auth-password-lookup`**, **`enroll-password`** → Anmeldung mit Passwort.
- **`auth-qr`**, **`auth-qr-lookup`**, **`enroll-qr`** → Anmeldung im Browser, bestätigt in der App
  per QR-Code.
- **`auth-sms`**, **`auth-sms-lookup`**, **`enroll-sms`** → Anmeldung per TAN an die Handynummer.
- **`confirm-email`** → **Bestätigen** der E-Mail-Adresse.
- **`confirm-qr-login`** → die Freigabe eines QR-Logins in der App.
- **`ident-eid`** → **Identifizierung** mit dem Online-Ausweis.
- **`ident-fsc`** → **Identifizierung** mit dem **Freischaltcode** per Brief.
- **`ident-kvnr`** → **Korrelation** über die Krankenversichertennummer.
- **`ident-nect`** → **Identifizierung** über Nect (Ausweis, Reisepass, EUDI-Wallet).
