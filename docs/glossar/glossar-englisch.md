# Glossar nach englischen Begriffen

Der Code ist auf Englisch geschrieben, die Doku auf Deutsch. Dieses Register hilft, wenn Sie im
Code auf einen englischen Namen stoßen und den deutschen Begriff dazu suchen. Es führt die
englischen Namen aus Code, API und Konfiguration alphabetisch auf. Zu jedem Namen nennt es den
deutschen Begriff und beschreibt ihn kurz. Ausführlich erklärt ist jeder Begriff im
[Glossar](glossar.md). Der Link am Ende eines Eintrags führt zum passenden Buchstaben dort.

---

## A

- **`AAL`** → AAL und IAL: die Frage „Ist das dieselbe Person wie beim letzten Mal?“. Sie wird
  durch Anmeldeverfahren beantwortet. [Glossar](glossar.md#a)
- **`AccountAnchor`** (Tabelle `account.anchor`) → **Anker**: eine Angabe, über die sich ein Konto
  eindeutig wiederfinden lässt. [Glossar](glossar.md#a)
- **`AccountAuthMethod`** → **Anmeldeverfahren**: etwas, das im Konto eingerichtet ist und mit
  dem sich der Inhaber anmelden kann. [Glossar](glossar.md#a)
- **`AccountClaim`** (Tabelle `account.claim`) → **Angabe**: eine Information über den
  Kontoinhaber. Gespeichert werden ihr Wert, ihre Quelle, ihre Stufe und das Niveau, unter dem sie
  entstand. [Glossar](glossar.md#a)
- **`AccountLockoutService`**, **`PersonLockoutService`** → **Sperre** eines Kontos oder einer Person
  nach fünf Fehlversuchen. [Glossar](glossar.md#s)
- **`ACCOUNT_LOOKUP_AUTH`** → **Tool-Rolle** einer Anmeldung mit Kontosuche: Das Tool sucht
  zuerst das Konto anhand der Eingabe und meldet es dann an. [Glossar](glossar.md#t)
- **`AccountProfile.isSetUp`** → **Eingerichtet** / **im Aufbau**: ob ein Konto schon ein
  Anmeldeverfahren hat. [Glossar](glossar.md#e)
- **`AccountRetraction`** (Tabelle `account.retraction`) → **Widerruf**: nimmt eine Angabe zurück.
  [Glossar](glossar.md#w)
- **`acr`** → **Niveau** (Sicherheitsniveau): wie sehr einer Anmeldung vertraut wird. Die
  Werte reichen von `loa1` bis `loa3`. [Glossar](glossar.md#n)
- **`acrFloor`** (`ChannelSession.acrFloor`) → **Untergrenze des Kanals**: das Niveau, unter das
  der Kanal nicht fallen darf. Sie gilt für jede Journey auf dem Kanal. [Glossar](glossar.md#u)
- **`Action`** → **Aktion**: eine Änderung an gespeicherten Daten, die ein Übergang
  auslösen will. Ausgeführt wird sie vom `JourneyActionExecutor`. [Glossar](glossar.md#a)
- **`amr`** → die Liste der Verfahren, die der **Nachweis** der Sitzung
  enthält. [Glossar](glossar.md#n)
- **`AmrSource`** (`orchestrator`, `kc`) → **Quelle eines Nachweises**: wer ein Verfahren in der
  Sitzung geprüft hat. [Glossar](glossar.md#q)
- **`AnchorAcrFloor`** (`establish`, `replace`) → **Mindestniveau für einen Anker**: welches
  Niveau nötig ist, um einen Anker zu setzen oder zu ändern. [Glossar](glossar.md#m)
- **`APP`** (`ChannelType.APP`) → **App-Kanal**: der Kanal der App. Jede Anfrage ist
  dort mit DPoP gesichert.
  [Glossar](glossar.md#a)
- **`AppTokenIssuer`** → gibt die **Tokens der App** aus und verlängert die
  zugehörige Sitzung.
  [Glossar](glossar.md#t)
- **`AppTokenSession`** (Tabelle `orchestrator.app_token_session`) → **Tokens der App**: die Tokens
  des App-Kanals zusammen mit der zugehörigen Keycloak-Sitzung. [Glossar](glossar.md#t)
- **`attemptBudget`** → **Versuchsbudget**: wie viele Fehlversuche eine Journey
  über alle Tools hinweg erlaubt.
  [Glossar](glossar.md#v)
- **`attest`**, **`ATTESTATION`** → **Bestätigen**: eine Angabe prüfen und als geprüft
  melden. Das Niveau steigt dadurch nicht. [Glossar](glossar.md#b)
- **`authData`** → die Daten, die jede Antwort an den **Web-Kanal** mitschickt: Subjekt,
  Niveau und Verfahren. [Glossar](glossar.md#w)
- **`AuthIntent`** → **Intent**: was der Nutzer erreichen will. Zu jedem Intent
  gehört eine Strategie.
  [Glossar](glossar.md#i)
- **`AuthJourney`** → **Journey**: ein laufender Ablauf zu einem Intent. [Glossar](glossar.md#j)
- **`AuthPolicy`**, **`DefaultAuthPolicy`** → **AuthPolicy**: die Regeln, nach denen das
  Sicherheitsniveau berechnet wird. [Glossar](glossar.md#a)

## B

- **`binding_key_ref`**, **`@BindingKey`** → **Bindungsschlüssel**: das Merkmal, an dem der
  Orchestrator erkennt, zu welchem Kanal eine Anfrage gehört. [Glossar](glossar.md#b)

## C

- **`ChangeLog`** (Tabelle `account.change_log`) → **Änderungsprotokoll**: hält fest, welche
  Angabe wann geändert wurde. Die Werte selbst enthält es nicht. Es wird zehn Jahre aufbewahrt. [Glossar](glossar.md#a)
- **`channel_binding`**, **`channelBinding`** → **Kanalbindung**: die Kennung in einer signierten
  Anfrage von Keycloak, an der der Orchestrator den zugehörigen Kanal erkennt. [Glossar](glossar.md#k)
- **`ChannelSession`** → **Kanal**: die Verbindung eines Clients zum Orchestrator, die
  nur kurze Zeit besteht.
  [Glossar](glossar.md#k)
- **`ChannelState`** → die Zustände eines **Kanals** (`ANONYMOUS`, `AUTHENTICATED`, …).
  [Glossar](glossar.md#k)
- **`ChannelType`** → **App-Kanal** (`APP`) oder **Web-Kanal** (`WEB`). [Glossar](glossar.md#a)
- **`claim`** → siehe `AccountClaim`.
- **`ClaimSource`** (Spalte `claim_source`) → **Quelle**: wer eine Angabe geliefert hat und für
  ihre Richtigkeit verantwortlich ist.
  [Glossar](glossar.md#q)
- **`ClaimTrust`** (`AUTHORITATIVE`, `PROVEN`, `SELF_REPORTED`) → **Stufe einer Angabe**: belegt,
  nachgewiesen, behauptet. [Glossar](glossar.md#s)
- **`CONFIRM_PEER_LOGIN`** → der Intent, mit dem die App einen **QR-Login** freigibt.
  [Glossar](glossar.md#q)
- **`CORRELATION`** → **Korrelation**: ordnet eine schon festgestellte Identität einem
  Datensatz im Personenverzeichnis zu. Sie beweist selbst nichts. [Glossar](glossar.md#k)

## D

- **`DELETE_ACCOUNT`** → der Intent zum Löschen des **Kontos**. [Glossar](glossar.md#k)
- **`demo.mode`**, **`DemoMode`**, **`@DemoSurface`** → **Demomodus**: ein Schalter, der alles
  einschaltet, was nur zur Vorführung dient. [Glossar](glossar.md#d)
- **`DeviceAccountLink`** → **Geräteverknüpfung**: merkt sich, welches Gerät zu welchem
  Konto gehört.
  [Glossar](glossar.md#g)
- **`DeviceProofs`**, **`device-proof+jwt`** → **Geräte-Proof**: belegt für eine einzelne
  Anfrage, dass die App den Geräteschlüssel besitzt. [Glossar](glossar.md#g)
- **`DPoP`** → **DPoP**: ein Standard, mit dem eine Anfrage belegt, dass sie vom Besitzer
  eines bestimmten Schlüssels kommt. Jede Anfrage enthält dafür einen **DPoP-Proof**. [Glossar](glossar.md#d)

## E

- **`EmailSendLimit`** → **Versandlimit** des Moduls `auth_email`: wie viele Codes an
  eine E-Mail-Adresse gehen dürfen.
  [Glossar](glossar.md#v)
- **`enrolledUnderAcr`** → eine der **Obergrenzen eines Verfahrens**: das Niveau, das die
  Sitzung hatte, in der das Verfahren eingerichtet wurde. [Glossar](glossar.md#o)
- **`ENROLLMENT`** → **Tool-Rolle** eines Tools, das ein Anmeldeverfahren einrichtet.
  [Glossar](glossar.md#t)

## F

- **`FactorType`** (`KNOWLEDGE`, `POSSESSION`, `INHERENCE`) → **Faktortyp**: die Art eines
  Beweises, also Wissen, Besitz oder Biometrie. [Glossar](glossar.md#f)
- **`FAST_ACCESS`** → **Schnellzugang**: die übliche Anmeldung in der App. [Glossar](glossar.md#s)
- **`JourneyFeatureFlag`**, **`KeycloakFeatureFlags`**, **`FeatureFlagService`** → **Feature-Flag**:
  ein Schalter, mit dem der Betreiber ein Verhalten im laufenden Betrieb umstellt. Beispiele sind
  `register-enroll-first`, `keycloak-login-keycloakify` und `keycloak-loa1-password`.
  [Glossar](glossar.md#f)

## I

- **`IAL`** → AAL und IAL: die Frage „Wer ist diese Person wirklich?“. Sie wird durch eine
  Identifizierung beantwortet.
  [Glossar](glossar.md#a)
- **`IDENTIFICATION`** → **Identifizierung**: die Tool-Rolle eines Tools, das feststellt,
  wer jemand wirklich ist.
  [Glossar](glossar.md#i)
- **`IntentStrategy`** → **Strategie** eines Intents, etwa `RegisterStrategy`. [Glossar](glossar.md#s)
- **`Invitation`** → **Einladung**: ein Brief des Personenverzeichnisses. Er ist die
  Grundlage für den Vorgangszugang. [Glossar](glossar.md#e)
- **`isDisposable`** (`AccountProfile.isDisposable`) → **verwerfbar**: ein Konto, das keiner
  Person zugeordnet ist und in dem nie ein Anmeldeverfahren eingerichtet wurde. [Glossar](glossar.md#v)

## J

- **`JourneyActionExecutor`** → führt die **Aktion** eines Übergangs aus. [Glossar](glossar.md#a)
- **`JourneyEvent`** → das Ereignis, das einen **Übergang** auslöst. [Glossar](glossar.md#u)
- **`JourneyLifecycle`** → **Lebenszyklus einer Journey**: ob eine Journey läuft,
  pausiert, fertig ist, abgebrochen wurde oder gescheitert ist. [Glossar](glossar.md#l)
- **`JourneyState`** → **Zustand**: wo eine Journey gerade steht. [Glossar](glossar.md#z)
- **`JourneyTraceEntry`** → ein Eintrag im **Journey-Protokoll**. [Glossar](glossar.md#j)

## K

- **`Keycloak…`** (etwa `KeycloakChannelService`), Pfade **`/kc/…`** → die Klassen und Pfade, über
  die Keycloak im **Web-Kanal** mit dem Orchestrator spricht. Sie sind nach der Technik (Keycloak)
  benannt. [Glossar](glossar.md#w)
- **`keycloak-migrations`** → das Modul, das das **Realm** in Keycloak anlegt und
  seine Einstellungen pflegt. [Glossar](glossar.md#r)
- **`keycloakSessionId`**, **`UserSessionModel`** → **Keycloak-Sitzung**: die Sitzung,
  die Keycloak für einen angemeldeten Nutzer führt. [Glossar](glossar.md#k)
- **`KNOWN_ACCOUNT_AUTH`** → **Tool-Rolle** eines Tools, das ein schon bekanntes Konto
  anmeldet.
  [Glossar](glossar.md#t)
- **`kvnr`** → **KVNR**, die Krankenversichertennummer. [Glossar](glossar.md#k)

## L

- **`loa1`**, **`loa2`**, **`loa3`** → die Werte des **Niveaus**. [Glossar](glossar.md#n)
- **`loa2-max-age`** (`identity.policy.…`) → wie lange ein **Nachweis** für ein Niveau über
  `loa1` zählt, nämlich 30 Minuten. [Glossar](glossar.md#n)
- **`LOGOUT`** → der **Intent** zum Abmelden, bei dem der Nutzer das Abmelden bestätigt. [Glossar](glossar.md#i)
- **`LOOKUP_LOGIN`** → **Anmeldung** über die E-Mail-Adresse, wenn kein Gerät mit
  dem Konto verknüpft ist. [Glossar](glossar.md#a)

## M

- **`MANAGE_AUTH_METHODS`** → der Intent zum Verwalten der **Anmeldeverfahren**.
  [Glossar](glossar.md#a)
- **`maxAcr`** → eine der **Obergrenzen eines Verfahrens**: das höchste Niveau, das es technisch
  hergibt. [Glossar](glossar.md#o)
- **`MemberNumber`**, **`MEMBER_NUMBER`** → **Mitgliedsnummer**, auch Versicherungsnummer genannt.
  [Glossar](glossar.md#m)
- **`method`** → **Anmeldeverfahren**. „Methode“ ist nur der Name im Code. [Glossar](glossar.md#a)
- **`MethodEvidence`**, **`MethodEvidenceRecord`** → der Eintrag für ein einzelnes Verfahren
  im **Nachweis** der Sitzung, gespeichert im Feld `methods`. [Glossar](glossar.md#n)
- **`@ApplicationModule`** (an `AccountModule`, `SmsToolModule` …) → beschreibt ein **Modul**
  und legt fest, welche anderen Module es benutzen darf. [Glossar](glossar.md#m)

## N

- **`next`** → die Angabe in jeder Antwort, welcher Schritt als Nächstes
  kommt. [Glossar](glossar.md#n)
- **`next.step`** → **Schritt**: ein einzelner Bildschirm innerhalb eines Tools. [Glossar](glossar.md#s)

## O

- **`Offer`**, **`activatable()`** → **Angebot**: die Tools, die eine Journey im aktuellen
  Zustand zur Wahl stellt.
  [Glossar](glossar.md#a)
- **`Orchestrator`** → **Orchestrator**: der Server, der die Schritte eines Nutzers
  bestimmt und das Niveau berechnet. [Glossar](glossar.md#o)
- **`orchestrator.tool_availability`** → **Tool-Sperre**: welche Tools je Kanal abgeschaltet sind
  und in welcher Reihenfolge sie angeboten werden.
  [Glossar](glossar.md#t)

## P

- **`PartnerNumber`**, **`partnerNumber`** → **Partnernummer**: die Nummer einer Person im
  Personenverzeichnis. Im Konto ist sie der Anker `PERSON_ID`. [Glossar](glossar.md#p)
- **`peer-auth`** → **Peer-Auth**: wie sich Keycloak und Orchestrator gegenseitig
  ausweisen.
  [Glossar](glossar.md#p)
- **`PEER_APPROVAL`** → **Tool-Rolle** eines Tools, das eine Anmeldung in einem anderen Kanal
  freigibt, etwa die Bestätigung beim QR-Login. [Glossar](glossar.md#t)
- **`PERSON_ID`**, **`personId`** → **Partnernummer**: Unter diesen Namen steht sie als Anker, in
  Feldern und in Tokens. [Glossar](glossar.md#p)
- **`process`** (Claim im Token) → der **Vorgang**, für den ein Vorgangszugang gilt.
  [Glossar](glossar.md#v)
- **`ProductionModeCheck`** → prüft beim Start die Einstellungen, wenn der **Demomodus**
  aus ist.
  [Glossar](glossar.md#d)
- **`prospect`** → **Interessent**: die Rolle eines Kontos, dem noch keine Person
  zugeordnet ist. [Glossar](glossar.md#i)
- **`provenAt`** → der Zeitpunkt eines **Nachweises**. Für ein Niveau über `loa1` zählen nur
  Nachweise aus den letzten 30 Minuten. [Glossar](glossar.md#n)

## R

- **`RateLimit`**, **`RateLimits`** (Paket `tool_api.ratelimit`) → **Mengenbegrenzung**: wie oft
  etwas in einem Zeitraum passieren darf. Jedes Modul legt seine Grenzen selbst fest. [Glossar](glossar.md#m)
- **`RateLimitCounter`** → das Zählwerk der **Mengenbegrenzung** und der **Sperre**.
  [Glossar](glossar.md#m)
- **`RateLimitRecord`** (Tabelle `rate_limit`) → eine Zeile des Zählwerks, je Bereich und
  Schlüssel eine.
  [Glossar](glossar.md#m)
- **`RateLimitScope`** → der Bereich eines Zählers (`ACCOUNT`, `PERSON`, `BINDING_KEY`, `ADMIN`).
  [Glossar](glossar.md#m)
- **`RE_IDENTIFY`** → **Erneute Identifizierung**, nur als Sub-Journey. [Glossar](glossar.md#e)
- **`REGISTER`** → **Registrierung**. [Glossar](glossar.md#r)
- **`RegisterEnrollFirstStrategy`** → die Variante „Enrollment zuerst“ der **Registrierung**.
  [Glossar](glossar.md#r)
- **`RestoreData`** → **RestoreData**: ein signierter Datensatz, mit dem Keycloak die
  Nachweise eines früheren Anmeldevorgangs derselben Keycloak-Sitzung weitergibt. [Glossar](glossar.md#r)
- **`RetractionSource`** (Spalte `claim_source` des Widerrufs) → die **Quelle** eines **Widerrufs**.
  [Glossar](glossar.md#q)

## S

- **`selectMethod`** → der Schritt der **Auswahlseite**. [Glossar](glossar.md#a)
- **`selfServiceAcrFloor`** → das **Niveau**, das zum Verwalten der
  Verfahren und zum Löschen des Kontos nötig ist: `loa2`, bei einem nie identifizierten Konto
  `loa1`. [Glossar](glossar.md#n)
- **`SessionEvidence`** → **Nachweis**: was in der laufenden Sitzung bewiesen wurde.
  [Glossar](glossar.md#n)
- **`SessionEvidenceRecord`** (Tabelle `orchestrator.session_evidence`) → der gespeicherte
  **Nachweis** eines Kanals. [Glossar](glossar.md#n)
- **`SignInLog`** (Tabelle `account.sign_in_log`) → **Anmeldeprotokoll**: hält Anmeldungen,
  Fehlversuche, Sperren und Abmeldungen fest. [Glossar](glossar.md#a)
- **`SmsSendLimit`** → **Versandlimit** des Moduls `auth_sms`: wie viele TANs an eine
  Telefonnummer gehen dürfen.
  [Glossar](glossar.md#v)
- **`STEP_UP`** → **Step-up**: erhöht das Niveau eines Kanals, auf dem der Nutzer
  schon angemeldet ist. [Glossar](glossar.md#s)
- **`sub-journey`**, **`Transition.RequireSubJourney`** → **Sub-Journey**: eine Journey, die eine
  andere unterbricht und danach zu ihr zurückkehrt. [Glossar](glossar.md#s)
- **`Subject`** → **Subjekt**: das, dem ein angemeldeter Kanal gehört, also ein
  Konto oder eine Einladung.
  [Glossar](glossar.md#s)

## T

- **`targetAcr`** → **Ziel eines Durchlaufs**: das Niveau, das ein Step-up erreichen soll.
  [Glossar](glossar.md#u)
- **`tool_api`** → die Schnittstelle zwischen den Modulen der Verfahren und dem
  **Orchestrator**.
  [Glossar](glossar.md#o)
- **`ToolModule`**, **`Tool`** → die Beschreibung, mit der ein **Verfahren** sich und seine
  **Tools** selbst beschreibt. Methode, Faktortypen und Obergrenze stehen einmal je Verfahren
  (`ToolModule`). Für jede Rolle gibt es ein eigenes Tool (`Tool`).
  [Glossar](glossar.md#t)
- **`toolId`** → **Tool**: die Kennung eines einzelnen Arbeitsschritts, etwa `enroll-sms`. [Glossar](glossar.md#t)
- **`ToolOutcome`** → das Ergebnis, das ein **Tool** dem Orchestrator meldet. [Glossar](glossar.md#t)
- **`ToolRole`** → **Tool-Rolle**: was ein Tool fachlich tut. [Glossar](glossar.md#t)
- **`ToolSession`**, **`toolSessionId`** → **Tool-Durchlauf**: ein einmal gestartetes Tool und seine
  Kennung.
  [Glossar](glossar.md#t)
- **`Transition`** → **Übergang** von einem Zustand zum nächsten. [Glossar](glossar.md#u)

## U

- **`usableByCaller`** (`Tool.usableByCaller`) → prüft, ob ein Verfahren
  mit **Gerätebindung** auf dem anfragenden Gerät nutzbar ist. [Glossar](glossar.md#g)

## V

- **`versnr`** (ID-Claim, Keycloak-Attribut) → **Mitgliedsnummer**. `versnr` ist der
  Name aus dem Vokabular des Personenverzeichnisses. [Glossar](glossar.md#m)

## W

- **`WEB`** (`ChannelType.WEB`) → **Web-Kanal**: die Verbindung über die Website. Keycloak
  führt dort die Anmeldung, der Orchestrator entscheidet über die Schritte. [Glossar](glossar.md#w)
- **`WEB_SELECT_METHOD`** (`web_select_method`) → der **Intent** für Anmeldung und Step-up im
  Web-Kanal. [Glossar](glossar.md#i)

---

## Tool-Kennungen

Jedes Tool hat eine Kennung. Ihr Anfang (das Präfix) sagt, was das Tool tut:

- `ident-` identifiziert eine Person,
- `enroll-` richtet ein Anmeldeverfahren ein,
- `auth-` meldet an,
- `confirm-` bestätigt eine Angabe.

Endet die Kennung auf `…-lookup`, meldet das Tool an, ohne dass das Konto vorher bekannt ist. Es
sucht das Konto erst anhand der Eingabe. Ausführlich steht das in
[03-tool-architektur](../03-tool-architektur.md), Abschnitte 1 und 2.

Die Tools im Einzelnen:

- **`auth-device`**, **`enroll-device`** → **Gerätebindung**: Anmeldung mit dem
  Schlüssel eines Geräts.
- **`auth-email`**, **`auth-email-lookup`**, **`enroll-email`** → Anmeldung mit einem Code, der an
  die E-Mail-Adresse geschickt wird.
- **`auth-invite-lookup`** → **Vorgangszugang** mit dem Einmalkennwort aus einer
  Einladung, nur im Web-Kanal.
- **`auth-kobil`**, **`enroll-kobil`** → **Gerätebindung**: Anmeldung mit der KOBIL-App.
- **`auth-password`**, **`auth-password-lookup`**, **`enroll-password`** → Anmeldung mit Passwort.
- **`auth-qr`**, **`auth-qr-lookup`**, **`enroll-qr`** → Anmeldung im Browser. Der Nutzer
  bestätigt sie in der App, indem er einen QR-Code scannt.
- **`auth-sms`**, **`auth-sms-lookup`**, **`enroll-sms`** → Anmeldung mit einer TAN, die per
  SMS an die Handynummer geschickt wird.
- **`confirm-email`** → **Bestätigen** der E-Mail-Adresse.
- **`approve-qr`** → die Freigabe eines QR-Logins in der App.
- **`ident-eid`** → **Identifizierung** mit dem Online-Ausweis.
- **`ident-fsc`** → **Identifizierung** mit dem **Freischaltcode** aus einem Brief.
- **`ident-kvnr`** → **Korrelation** über die Krankenversichertennummer
  (KVNR): ordnet eine schon festgestellte Identität einer Person im Personenverzeichnis zu.
- **`ident-nect`** → **Identifizierung** über Nect (Ausweis, Reisepass, EUDI-Wallet).
