# Glossar nach englischen Begriffen

Der Code ist englisch, die Doku deutsch. Dieses Register führt die englischen Namen aus Code, API
und Konfiguration alphabetisch auf, jeweils mit dem deutschen Begriff und einer kurzen deutschen
Beschreibung. Ausführlich steht jeder Begriff im [Glossar](glossar.md); der Link am Ende eines
Eintrags führt zum Buchstaben dort.

---

## A

- **`AAL`** → AAL und IAL: die Frage „dieselbe Person wie beim letzten Mal?“, beantwortet durch
  Anmeldeverfahren. [Glossar](glossar.md#a)
- **`AccountAnchor`** (Tabelle `account.anchor`) → **Anker**: ein Attribut, über das ein Konto
  eindeutig wiedergefunden wird. [Glossar](glossar.md#a)
- **`AccountAuthMethod`** → **Anmeldeverfahren**: was im Konto eingerichtet ist und eine Anmeldung
  ermöglicht. [Glossar](glossar.md#a)
- **`AccountClaim`** (Tabelle `account.claim`) → **Bestätigte Angabe**: ein Attribut mit Wert, Quelle
  und dem Niveau, unter dem es entstand. [Glossar](glossar.md#b)
- **`AccountProfile.isSetUp`** → **Eingerichtet** / **im Aufbau**: ob ein Konto schon ein
  Anmeldeverfahren hat. [Glossar](glossar.md#e)
- **`AccountRetraction`** (Tabelle `account.retraction`) → **Widerruf**: nimmt eine bestätigte Angabe
  zurück. [Glossar](glossar.md#w)
- **`acr`** → **Niveau**: wie sehr einer Anmeldung vertraut wird, Werte `loa1` bis `loa3`.
  [Glossar](glossar.md#n)
- **`acrFloor`** (`ChannelSession.acrFloor`) → **Untergrenze des Kanals**: gilt für jede Journey auf
  dem Kanal. [Glossar](glossar.md#u)
- **`amr`** → die Verfahren, die der **Nachweis** der Sitzung enthält. [Glossar](glossar.md#n)
- **`APP`** (`ChannelType.APP`) → **App-Kanal**: der Kanal der App, jede Anfrage mit DPoP.
  [Glossar](glossar.md#a)
- **`attemptBudget`** → **Versuchsbudget**: die Fehlversuche einer Journey über alle Tools hinweg.
  [Glossar](glossar.md#v)
- **`AttemptThrottle`** → **Sperre** und **Drossel**: zählt Fehlversuche und Mengen, sperrt nach fünf
  Fehlversuchen für 15 Minuten. [Glossar](glossar.md#s)
- **`attest`**, **`ATTESTATION`** → **Bestätigen**: ein Attribut als geprüft melden, ohne das Niveau
  zu heben. [Glossar](glossar.md#b)
- **`AuthContext`** → **Tokens der App**: die Tokens des App-Kanals samt Keycloak-Sitzung.
  [Glossar](glossar.md#t)
- **`authData`** → was jede Antwort an den **Web-Kanal** über Subjekt, Niveau und Verfahren
  mitgibt. [Glossar](glossar.md#w)
- **`AuthEvidence`** → **Nachweis**: was in der laufenden Sitzung bewiesen wurde.
  [Glossar](glossar.md#n)
- **`AuthIntent`** → **Intent** / **Ziel**: was der Nutzer erreichen will, samt Strategie.
  [Glossar](glossar.md#i)
- **`AuthJourney`** → **Journey**: ein laufender Durchlauf zu einem Intent. [Glossar](glossar.md#j)

## B

- **`binding_key_ref`**, **`@BindingKey`** → **Bindungsschlüssel**: woran der Orchestrator eine
  Anfrage einem Kanal zuordnet. [Glossar](glossar.md#b)

## C

- **`ChangeLog`** (Tabelle `account.change_log`) → **Änderungsprotokoll**: welche Angabe wann
  geändert wurde, ohne Werte, zehn Jahre. [Glossar](glossar.md#a)
- **`ChannelSession`** → **Kanal**: die kurzlebige Verbindung eines Clients zum Orchestrator.
  [Glossar](glossar.md#k)
- **`ChannelState`** → die Zustände eines **Kanals** (`ANONYMOUS`, `AUTHENTICATED`, …).
  [Glossar](glossar.md#k)
- **`ChannelType`** → **App-Kanal** (`APP`) oder **Web-Kanal** (`KEYCLOAK`). [Glossar](glossar.md#a)
- **`claim`** → siehe `AccountClaim`.
- **`CORRELATION`** → **Korrelation**: ordnet eine schon bescheinigte Identität einem Datensatz zu,
  ohne selbst etwas zu beweisen. [Glossar](glossar.md#k)

## D

- **`demo.mode`**, **`DemoMode`**, **`@DemoSurface`** → **Demomodus**: schaltet alles ein, was nur
  zur Vorführung dient. [Glossar](glossar.md#d)
- **`DeviceAccountLink`** → **Geräteverknüpfung**: welches Gerät zu welchem Konto gehört.
  [Glossar](glossar.md#g)
- **`DPoP`** → **DPoP**: Nachweis, dass eine Anfrage vom Besitzer eines Schlüssels kommt.
  [Glossar](glossar.md#d)

## E

- **`elevated-level-max-age`** (`identity.policy.…`) → wie lange ein **Nachweis** über `loa1` trägt,
  30 Minuten. [Glossar](glossar.md#n)
- **`enrolledUnderAcr`** → eine der **Obergrenzen eines Verfahrens**: das Niveau der Sitzung, in der
  es eingerichtet wurde. [Glossar](glossar.md#o)
- **`ENROLLMENT`** → **Methodenrolle** eines Tools, das ein Anmeldeverfahren einrichtet.
  [Glossar](glossar.md#m)
- **`EvidenceTrail`** (Tabelle `orchestrator.auth_evidence`) → gespeicherter **Nachweis** eines
  Kanals. [Glossar](glossar.md#n)

## F

- **`FactorType`** (`KNOWLEDGE`, `POSSESSION`, `INHERENCE`) → **Faktortyp**: Wissen, Besitz,
  Biometrie. [Glossar](glossar.md#f)

## I

- **`IAL`** → AAL und IAL: die Frage „wer ist das?“, beantwortet durch eine Identifizierung.
  [Glossar](glossar.md#a)
- **`IDENTIFICATION`** → **Identifizierung**: ein Tool, das bestätigt, wer jemand ist.
  [Glossar](glossar.md#i)
- **`IDENTIFIED_AUTH`** → **Methodenrolle** einer Anmeldung, deren Konto schon bekannt ist.
  [Glossar](glossar.md#m)
- **`Invitation`** → **Einladung**: vom Personenverzeichnis per Brief ausgestellt, Grundlage des
  Vorgangszugangs. [Glossar](glossar.md#e)

## J

- **`JourneyState`** → **Zustand**: wo eine Journey gerade steht. [Glossar](glossar.md#z)

## K

- **`KEYCLOAK`** (`ChannelType.KEYCLOAK`) → **Web-Kanal**: Keycloak führt die Anmeldung, der
  Orchestrator entscheidet. [Glossar](glossar.md#w)

## L

- **`loa1`**, **`loa2`**, **`loa3`** → die Werte des **Niveaus**. [Glossar](glossar.md#n)
- **`LOOKUP_AUTH`** → **Methodenrolle** einer Anmeldung, die das Konto erst findet.
  [Glossar](glossar.md#m)

## M

- **`maxAcr`** → eine der **Obergrenzen eines Verfahrens**: das höchste Niveau, das es technisch
  hergibt. [Glossar](glossar.md#o)
- **`method`** → **Methode** / **Anmeldeverfahren**. [Glossar](glossar.md#a)
- **`MethodRole`** → **Methodenrolle**: was ein Tool fachlich tut. [Glossar](glossar.md#m)

## N

- **`next`** → die Adresse des nächsten Schritts in jeder Antwort. [Glossar](glossar.md#n)
- **`next.step`** → **Schritt** innerhalb eines Tools. [Glossar](glossar.md#s)

## O

- **`Orchestrator`** → **Orchestrator**: der Server, der die Schritte bestimmt und das Niveau
  berechnet. [Glossar](glossar.md#o)

## P

- **`PEER_APPROVAL`** → **Methodenrolle** einer Freigabe für einen anderen Kanal, etwa die
  QR-Bestätigung. [Glossar](glossar.md#m)
- **`peer-auth`** → **Peer-Auth**: wie Keycloak und Orchestrator einander ausweisen.
  [Glossar](glossar.md#p)
- **`process`** (Claim im Token) → der **Vorgang**, für den ein Vorgangszugang gilt.
  [Glossar](glossar.md#v)
- **`provenAt`** → der Zeitpunkt eines **Nachweises**; über `loa1` zählen nur die letzten
  30 Minuten. [Glossar](glossar.md#n)

## R

- **`RestoreData`** → **RestoreData**: gibt die Nachweise eines früheren Durchlaufs derselben
  Keycloak-Sitzung weiter. [Glossar](glossar.md#r)

## S

- **`SignInLog`** (Tabelle `account.sign_in_log`) → **Anmeldeprotokoll**: Anmeldungen,
  Fehlversuche, Sperren, Abmeldungen. [Glossar](glossar.md#a)
- **`STEP_UP`** → **Step-up**: hebt das Niveau eines angemeldeten Kanals. [Glossar](glossar.md#s)
- **`Subject`** → **Subjekt**: wem ein angemeldeter Kanal gehört, Konto oder Einladung.
  [Glossar](glossar.md#s)
- **`sub-journey`**, **`Transition.RequireSubJourney`** → **Sub-Journey**: eine Journey, die eine
  andere unterbricht. [Glossar](glossar.md#s)

## T

- **`targetAcr`** → **Ziel eines Durchlaufs**: das Niveau, das ein Step-up erreichen soll.
  [Glossar](glossar.md#u)
- **`tool_api`** → die Modulgrenze, über die die Verfahren am **Orchestrator** hängen.
  [Glossar](glossar.md#o)
- **`ToolDescriptor`** → wie sich ein **Tool** selbst beschreibt: Rolle, Verfahren, Faktortypen,
  Obergrenze. [Glossar](glossar.md#t)
- **`toolId`** → **Tool**: ein einzelner Ablauf, etwa `enroll-sms`. [Glossar](glossar.md#t)
- **`ToolOutcome`** → das Ergebnis, das ein **Tool** dem Orchestrator meldet. [Glossar](glossar.md#t)
- **`ToolSession`**, **`toolSessionId`** → **Tool-Durchlauf**: ein gestartetes Tool.
  [Glossar](glossar.md#t)

---

## Tool-Kennungen

Das Präfix sagt, was ein Tool tut: `ident-` identifiziert, `enroll-` richtet ein Verfahren ein,
`auth-` meldet an, `confirm-` bestätigt. `…-lookup` meldet an, ohne dass das Konto vorher bekannt
ist. Ausführlich: [03-tool-architektur](../03-tool-architektur.md) Abschnitt 1.

- **`auth-device`**, **`enroll-device`** → Anmeldung mit dem Schlüssel eines Geräts.
- **`auth-email`**, **`auth-email-lookup`**, **`enroll-email`** → Anmeldung per Code an die
  E-Mail-Adresse.
- **`auth-invite`** → **Vorgangszugang** mit Einmalkennwort, nur im Web-Kanal.
- **`auth-kobil`**, **`enroll-kobil`** → Anmeldung mit der KOBIL-App.
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
