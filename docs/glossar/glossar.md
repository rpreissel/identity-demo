# Glossar

Die Begriffe dieses Projekts, alphabetisch. Hinter jedem Begriff steht in Klammern sein Name im
Code, danach eine knappe Erklärung und die Stelle, an der er ausführlich beschrieben ist. Die Doku
ist deutsch, der Code englisch; wer von einem Namen im Code ausgeht, findet ihn im
[Register nach englischen Begriffen](glossar-englisch.md).

Fachbegriffe zu Authentifizierung, Identifizierung und Gerätebindung im allgemeinen Sinn definiert
das [externe Glossar](externes-glossar.md); wie seine Begriffe hier heißen und wo das Projekt bewusst
abweicht, zeigt der [Abgleich](abgleich-externes-glossar.md). Zum Einstieg genügen die wichtigsten
Begriffe im [Überblick](../01-ueberblick.md), Abschnitt 3.

---

## A

- **AAL** und **IAL**: die beiden Fragen hinter einem Niveau nach NIST 800-63. AAL: Ist das dieselbe
  Person wie beim letzten Mal (Anmeldeverfahren)? IAL: Wer ist das (Identifizierung)? Beide werden
  getrennt berechnet und erst am Ende zu einem `acr` zusammengefasst; eine Identifizierung und ein
  Anmeldeverfahren ergeben nie gemeinsam einen Mehr-Faktor-Bonus.
  [04-orchestrierung](../04-orchestrierung.md) Abschnitt 8.
- **Anker** (`AccountAnchor`, Tabelle `account.anchor`): ein Attribut, über das ein Konto eindeutig
  wiedergefunden wird, etwa die Partnernummer, die Kennung eines Ausweises oder die bestätigte
  E-Mail-Adresse. Ein Ankerwert gehört höchstens einem Konto.
  [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6, [ADR-19](../adr/ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md).
- **Anmeldeprotokoll** (`SignInLog`, Tabelle `account.sign_in_log`): wer sich wann womit und auf
  welchem Niveau angemeldet hat, Fehlversuche, Sperren und Abmeldungen. Verhaltensdaten: kurz
  aufbewahrt und mit dem Konto gelöscht; Zeilen eines Vorgangszugangs gehören der Einladung.
  [ADR-39](../adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md), Nachtrag.
- **Anmeldeverfahren**, im Code **Methode** (`AccountAuthMethod`, `method`): was im Konto
  eingerichtet ist und eine Anmeldung ermöglicht, etwa `sms`, `password`, `device`, `kobil`. Zu
  einem Verfahren gehören meist zwei Tools, eines zum Einrichten (`enroll-…`) und eines zum Anmelden
  (`auth-…`). Eine Instanz wird deaktiviert, nie gelöscht.
  [03-tool-architektur](../03-tool-architektur.md) Abschnitt 1.
- **App-Kanal** (`ChannelType.APP`): der Kanal der App. Jede Anfrage trägt einen DPoP-Nachweis; die
  App bekommt ihre Tokens vom Orchestrator. Gegenstück: Web-Kanal.
  [05-api](../05-api.md) Abschnitt 2.
- **Änderungsprotokoll** (`ChangeLog`, Tabelle `account.change_log`): welche Angabe wann von wem
  geändert wurde, ohne die Werte selbst. Überlebt die Löschung des Kontos, zehn Jahre.
  [ADR-39](../adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md).

## B

- **Bestätigen** (`attest`, `ToolOutcome.Completed.Attested`, Rolle `ATTESTATION`): ein Attribut als
  geprüft melden, etwa die E-Mail-Adresse. Weder Identifizierung noch Anmeldung und hebt kein
  Niveau. Bestätigen und Zuordnen sind zwei Schritte.
  [ADR-17](../adr/ADR-017-adresse-bestaetigen-und-e-mail-login-einrichten-sind.md),
  [ADR-18](../adr/ADR-018-bestaetigen-und-zuordnen-sind-zwei-akte.md).
- **Bestätigte Angabe**, auch **Claim** (`AccountClaim`, Tabelle `account.claim`): ein Eintrag im
  Konto, dass ein Attribut einen bestimmten Wert hat, mit der Quelle, die dafür einsteht, und dem
  Niveau, unter dem er entstand. [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6.
- **Bindungsschlüssel** (`binding_key_ref`, `@BindingKey`): woran der Orchestrator eine Anfrage
  einem Kanal zuordnet. Im App-Kanal der Fingerabdruck des DPoP-Schlüssels, im Web-Kanal der Anker
  der signierten Anfrage von Keycloak. [09-dpop](../09-dpop.md) Abschnitt 3.

## D

- **Demomodus** (`demo.mode`, `DemoMode`, `@DemoSurface`): schaltet alles ein, was nur zur
  Vorführung dient: simulierte Fremdsysteme mit Beispieldaten, Zurücksetzen, sichtbare Codes.
  Außerhalb des Demomodus verweigert der Start unsichere Einstellungen (`ProductionModeCheck`).
  [ADR-28](../adr/ADR-028-demo-werte-abschaltbar.md).
- **DPoP**: Nachweis nach RFC 9449, dass eine Anfrage vom Besitzer eines bestimmten Schlüssels kommt.
  Jede Anfrage des App-Kanals trägt einen frischen, nur einmal gültigen Nachweis.
  [09-dpop](../09-dpop.md).

## E

- **Einladung** (`Invitation`, Tool `auth-invite`): das Personenverzeichnis lädt eine Person per
  Brief zu einem **Vorgang** ein. Sie gehört dem Personenverzeichnis; der Orchestrator hält nur ihre
  Kennung als Subjekt. [ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
- **Einmalkennwort**: das Kennwort im Einladungsbrief. Mit Versicherungs- oder Partnernummer
  zusammen öffnet es einen Vorgangszugang, bis zur Frist oder bis der Vorgang abgeschlossen ist.
  Gespeichert wird es nie, nur ein Hash. [ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
- **Eingerichtet** und **im Aufbau** (`AccountProfile.isSetUp`): ein Konto ist eingerichtet, sobald
  es ein Anmeldeverfahren hat, vorher im Aufbau. Abgeleitet, nicht gespeichert; zurück in den
  Aufbau führt kein Weg. [ADR-46](../adr/ADR-046-konto-im-aufbau.md).

## F

- **Faktortyp** (`FactorType`: `KNOWLEDGE`, `POSSESSION`, `INHERENCE`): Wissen, Besitz, Biometrie.
  Für `loa2` braucht es zwei verschiedene Faktortypen; gezählt werden Typen, nicht Tools.
  [04-orchestrierung](../04-orchestrierung.md) Abschnitt 8.
- **Freischaltcode** (Tool `ident-fsc`): ein per Brief verschickter Code, mit dem eine Person sich
  identifiziert. Er liegt im Personenverzeichnis; `ident-fsc` fragt es über einen Port.
  [ADR-31](../adr/ADR-031-freischaltcode-liegt-im-fremdsystem.md).
- **Fremdsystem**: ein System außerhalb des Orchestrators, das seine Daten selbst besitzt, etwa das
  Personenverzeichnis, Nect oder KOBIL. Im Projekt simuliert; der Kern erreicht es nur über einen
  Port. [port-vertraege](../port-vertraege.md).

## G

- **Geräteverknüpfung** (`DeviceAccountLink`): welches Gerät, erkannt am DPoP-Schlüssel, zu welchem
  Konto gehört. Sie überdauert den kurzlebigen Kanal, zählt aber selbst nicht als Anmeldung.
  [ADR-3](../adr/ADR-003-channelsession-bewusst-kurzlebig-geraete-identitaet-in-deviceaccountlink.md).

## I

- **Identifizierung** (Rolle `IDENTIFICATION`, Tools `ident-fsc`, `ident-eid`, `ident-nect`): ein
  Tool, das bestätigt, wer jemand ist, und das Konto einer Person im Personenverzeichnis zuordnet.
  Hebt die IAL-Seite des Niveaus. [03-tool-architektur](../03-tool-architektur.md) Abschnitt 1.
- **Intent**, deutsch **Ziel** (`AuthIntent`): was der Nutzer erreichen will, samt der Strategie,
  die ihn dorthin führt, etwa `FAST_ACCESS`, `REGISTER`, `STEP_UP`.
  [04-orchestrierung](../04-orchestrierung.md) Abschnitt 2.
- **Interessent**, **Partner**, **Versicherter**: die drei Rollen eines Kontos, abgeleitet aus seinen
  Ankern, nicht gespeichert. Ohne zugeordnete Person Interessent, mit Partnernummer Partner, mit
  Versicherungsnummer Versicherter.
  [ADR-34](../adr/ADR-034-personenverzeichnis-meldet-aenderungen.md).

## J

- **Journey** (`AuthJourney`): ein laufender Durchlauf zu einem Intent. Je Kanal ist genau eine
  Journey aktiv; sie nutzt ein oder mehrere Tools. [04-orchestrierung](../04-orchestrierung.md)
  Abschnitt 3, Diagramme je Intent unter [journeys](../journeys/).

## K

- **Kanal** (`ChannelSession`): die Verbindung eines Clients, App oder Website, zum Orchestrator.
  Bewusst kurzlebig; ein angemeldeter Kanal hat genau eine Keycloak-Sitzung und lebt nicht länger
  als sie. Zustände: `ANONYMOUS`, `AUTHENTICATED`, `STEP_UP_REQUIRED`, `STEP_UP_IN_PROGRESS`,
  `LOGGED_OUT`, `EXPIRED`; `REGISTERING` wird nur angezeigt, nie gespeichert.
  [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 3,
  [ADR-43](../adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md).
- **Kandidaten**: die Tools, die eine Journey in ihrem Zustand anbietet. Die Strategie wählt sie über
  die Rolle eines Tools, nie über seinen Namen. [04-orchestrierung](../04-orchestrierung.md)
  Abschnitt 1.
- **Korrelation** (Rolle `CORRELATION`, Tool `ident-kvnr`): ordnet eine schon bescheinigte Identität
  einem Datensatz im Personenverzeichnis zu. Beweist selbst nichts und hebt kein Niveau.
  [ADR-18](../adr/ADR-018-bestaetigen-und-zuordnen-sind-zwei-akte.md).

## M

- **Methode**: siehe Anmeldeverfahren.
- **Methodenrolle** (`MethodRole`): was ein Tool fachlich tut: `IDENTIFICATION`, `CORRELATION`,
  `ENROLLMENT`, `IDENTIFIED_AUTH` (Anmeldung eines schon bekannten Kontos), `LOOKUP_AUTH` (Anmeldung,
  die das Konto erst findet), `PEER_APPROVAL` (Freigabe für einen anderen Kanal), `ATTESTATION`.
  Die Rolle bestimmt, ob und wie ein Ergebnis das Niveau hebt.
  [03-tool-architektur](../03-tool-architektur.md) Abschnitt 2.

## N

- **Nachweis** (`AuthEvidence`, gespeichert als `EvidenceTrail`, daraus `acr` und `amr`): was in der
  laufenden Sitzung bewiesen wurde, je Verfahren mit Niveau, Faktortypen und Zeitpunkt. Über `loa1`
  zählen nur Nachweise der letzten 30 Minuten.
  [04-orchestrierung](../04-orchestrierung.md) Abschnitt 8.
- **`next`**: die Adresse des nächsten Schritts in jeder Antwort. Der Client folgt ihr und
  entscheidet selbst nichts. [ADR-6](../adr/ADR-006-next-als-reine-adresse-feste-routing-tabelle-statt.md).
- **Niveau** (`acr`, Werte `loa1`, `loa2`, `loa3`): wie sehr einer Anmeldung vertraut wird. `loa1`
  ein Verfahren, `loa2` zwei Faktortypen oder eine Identifizierung, `loa3` nur über eine starke
  Identifizierung. [01-ueberblick](../01-ueberblick.md) Abschnitt 9.

## O

- **Obergrenzen eines Verfahrens** (`maxAcr`, `enrolledUnderAcr`): das höchste Niveau, das ein
  Verfahren technisch hergibt, und das Niveau der Sitzung, in der es eingerichtet wurde. Ein Verfahren
  liefert nie mehr als beide. [ADR-5](../adr/ADR-005-drei-obergrenzen-fuer-das-sicherheitsniveau.md).
- **Orchestrator**: der Server dieses Projekts. Er entscheidet, welche Schritte ein Nutzer
  durchläuft, und ist die einzige Stelle, die das Niveau berechnet. Die Verfahren hängen als Module
  über `tool_api` an ihm. [01-ueberblick](../01-ueberblick.md) Abschnitt 2.

## P

- **Peer-Auth**: wie Keycloak und Orchestrator einander ausweisen. Jede Anfrage von Keycloak ist ein
  signiertes JWT mit dem Anker ihres Kanals, jede Antwort des Orchestrators ist signiert und an die
  Anfrage gebunden. [ADR-7](../adr/ADR-007-web-kanal-ohne-mtls-signierte-request-assertion-statt.md).
- **Personenverzeichnis**: die simulierte Stammdatenhaltung der Versicherung mit Partnernummer,
  Name, Geburtsdatum, Anschrift, Versicherungs- und Krankenversichertennummer (KVNR). Stellt
  Freischaltcodes und Einladungen aus und meldet Änderungen als Ereignis.
  [ADR-34](../adr/ADR-034-personenverzeichnis-meldet-aenderungen.md).
- **Pflichten**: was eine Journey noch verlangt, bevor sie endet, etwa eine bestätigte Adresse oder
  ein zweiter Faktortyp. Sie sind Zustände der Journey, keine Eigenschaften des Kontos.
  [04-orchestrierung](../04-orchestrierung.md) Abschnitt 8.

## R

- **RestoreData**: ein signiertes Token, mit dem Keycloak die Nachweise eines früheren Durchlaufs
  derselben Keycloak-Sitzung an einen neuen Kanal weitergibt. An die Sitzung gebunden; die Nachweise
  behalten ihren Zeitpunkt. [05-api](../05-api.md) Abschnitt 3.

## S

- **Schritt** (`next.step`): ein Schritt innerhalb eines Tools, etwa die Eingabe der TAN.
- **Sperre** und **Drossel** (`AttemptThrottle`): fünf Fehlversuche sperren ein Konto oder eine
  Person für 15 Minuten. Die Anmeldung eines bekannten Kontos antwortet dann `423`; eine Anmeldung,
  die das Konto erst sucht, antwortet wie bei einer falschen Eingabe, damit sich nicht ablesen lässt,
  ob es das Konto gibt. Mengenbegrenzungen antworten `429`, ohne zu sperren. Die Regeln stehen in den
  Modulen, das Zählwerk im Orchestrator.
  [ADR-44](../adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md),
  [07-betrieb](../07-betrieb.md) Abschnitt 4.
- **Step-up** (`STEP_UP`): hebt das Niveau eines angemeldeten Kanals in derselben Keycloak-Sitzung.
  Läuft oft als Sub-Journey. Ein Vorgangszugang wird nie aufgewertet.
  [04-orchestrierung](../04-orchestrierung.md) Abschnitt 2.
- **Sub-Journey** (`Transition.RequireSubJourney`): eine Journey, die eine andere unterbricht und
  nach ihrem Ende fortsetzen lässt, etwa ein Step-up vor dem Verwalten der Verfahren. Die
  übergeordnete ist solange `SUSPENDED`. [04-orchestrierung](../04-orchestrierung.md) Abschnitt 6.
- **Subjekt** (`Subject`): wem ein angemeldeter Kanal gehört, einem Konto oder einer Einladung, nie
  beidem. Die Anfrage von Keycloak nennt es ebenso wie die Antwort des Orchestrators
  (`authData.subject`). [ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).

## T

- **Tool** (`toolId`, etwa `enroll-sms`): ein einzelner Ablauf, den der Nutzer durchläuft, zum
  Identifizieren, Einrichten, Anmelden oder Bestätigen. Beschreibt sich selbst über seinen
  `ToolDescriptor`. [03-tool-architektur](../03-tool-architektur.md).
- **Tool-Durchlauf** (`ToolSession`, `toolSessionId`): ein gestartetes Tool, oft nur Minuten. Hält
  nur den Lebenszyklus; die Fachdaten bleiben im Modul des Tools. Nicht zu verwechseln mit der
  `toolId`. [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 1.
- **Tokens der App** (`AuthContext`): die Tokens des App-Kanals, gekoppelt an dessen Nachweis und
  an seine Keycloak-Sitzung. [ADR-9](../adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md).

## U

- **Untergrenze des Kanals** (`ChannelSession.acrFloor`) und **Ziel eines Durchlaufs**
  (`targetAcr`): die dauerhafte Untergrenze gilt für jede Journey auf dem Kanal, das Ziel nur für
  einen Step-up. Gerechnet wird mit dem höheren Wert.
  [04-orchestrierung](../04-orchestrierung.md) Abschnitt 8.

## V

- **Versandbudget**: wie viele Codes ein Modul an eine Nummer oder Adresse verschicken darf. Hängt an
  Nummer oder Adresse, nicht am Konto. [ADR-44](../adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md).
- **Versuchsbudget** (`attemptBudget`): wie viele Fehlversuche eine Journey insgesamt hat, über alle
  Tools hinweg. Ist es aufgebraucht, endet die ganze Journey (`410`).
  [04-orchestrierung](../04-orchestrierung.md) Abschnitt 7.
- **Vorgangszugang**: die Anmeldung mit Einmalkennwort auf der Website, auch ohne Konto. Die Tokens
  tragen den Vorgang (`process`) und gelten nur für ihn; nur im Web-Kanal, nie aufgewertet.
  [ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).

## W

- **Web-Kanal** (`ChannelType.KEYCLOAK`): der Kanal der Website. Keycloak führt die Anmeldung und
  fragt den Orchestrator über Peer-Auth; die Tokens stellt Keycloak aus. Gegenstück: App-Kanal.
  [05-api](../05-api.md) Abschnitt 3, [ADR-8](../adr/ADR-008-keycloak-fuehrt-seine-eigenen-nativen-schritte-selbst-statt.md).
- **Widerruf** (`AccountRetraction`, Tabelle `account.retraction`): nimmt eine bestätigte Angabe
  zurück, als eigene Zeile mit eigenem Vertrauensanker.
  [ADR-12](../adr/ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md).

## Z

- **Ziel**: siehe Intent; für das angestrebte Niveau eines Step-ups siehe Untergrenze.
- **Zustand** (`JourneyState`): wo eine Journey gerade steht, samt der dort geltenden Angaben. Jeder
  Intent hat seine eigene, abgeschlossene Menge von Zuständen.
  [ADR-2](../adr/ADR-002-zustand-statt-vererbung-bei-authjourney.md).
