# Glossar

Die Begriffe dieses Projekts, alphabetisch. Jeder Eintrag sagt zuerst in ganzen Sätzen, was gemeint
ist und wozu es dient. Darunter stehen die Namen, unter denen der Begriff im Code vorkommt, und wo er
ausführlich beschrieben ist. Wer von einem Namen im Code ausgeht, findet ihn im
[Register nach englischen Begriffen](glossar-englisch.md).

Allgemeine Fachbegriffe zu Authentifizierung, Identifizierung und Gerätebindung erklärt das
[externe Glossar](externes-glossar.md); wie seine Begriffe hier heißen und wo das Projekt bewusst
abweicht, zeigt der [Abgleich](abgleich-externes-glossar.md). Für den Einstieg reichen die
wichtigsten Begriffe im [Überblick](../01-ueberblick.md), Abschnitt 3.

---

## A

- **AAL und IAL**: Zwei Fragen, aus denen das Sicherheitsniveau einer Anmeldung entsteht. AAL fragt:
  Ist das dieselbe Person wie beim letzten Mal? Das beantworten Anmeldeverfahren wie Passwort oder
  SMS. IAL fragt: Wer ist diese Person wirklich? Das beantwortet eine Identifizierung, etwa mit dem
  Online-Ausweis. Beide Antworten werden getrennt bewertet; eine Identifizierung und ein Passwort
  zählen deshalb nie zusammen als zwei Faktoren.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 4.
- **Ablehnen**: Der Nutzer verwirft ein angebotenes Tool, oder das Tool scheitert endgültig. Was
  danach passiert, hängt vom Zustand ab; siehe Ausweichzustand und Pflichten.
- **Aktion**: Was ein Übergang an Daten ändern will, etwa ein Konto anlegen, eine Angabe schreiben
  oder ein Gerät verknüpfen. Die Strategie beschreibt die Aktion nur; ausgeführt wird sie an einer
  einzigen Stelle, damit Änderungen am Konto nicht über den Code verstreut sind.
  *Im Code:* `Action`, ausgeführt vom `JourneyActionExecutor`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 8.
- **`amr`**: Die Liste der Verfahren, mit denen sich ein Nutzer in der laufenden Sitzung angemeldet
  hat, etwa „sms, password“. Sie steht neben dem Niveau (`acr`) im Token, damit ein Fachdienst sehen
  kann, wie die Anmeldung zustande kam.
  *Im Code:* „Authentication Methods References“ nach RFC 8176; `SessionEvidence.amr`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 4.
- **Änderungsprotokoll**: Hält fest, welche Angabe eines Kontos wann und von wem geändert wurde, ohne
  die Werte selbst. Es bleibt zehn Jahre erhalten, auch wenn das Konto gelöscht wird, damit sich
  eine Änderung später noch nachweisen lässt.
  *Im Code:* `ChangeLog`, Tabelle `account.change_log`.
  *Mehr dazu:* [ADR-39](../adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md).
- **Angabe**: Eine Information über den Kontoinhaber, etwa Name, E-Mail-Adresse oder Geburtsdatum.
  Zu jeder Angabe merkt sich das Konto, wer dafür einsteht (die Quelle) und wie verlässlich sie
  deshalb ist (die Stufe). Auf englisch heißt eine solche Angabe auch Claim.
  *Im Code:* `AccountClaim`, Tabelle `account.claim`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6.
- **Angebot**: Die Tools, die ein Zustand dem Nutzer gerade tatsächlich anbietet: die Kandidaten ohne
  die schon abgelehnten und ohne die gerade nicht verfügbaren. Hat das Angebot mehr als einen Eintrag,
  zeigt der Client eine Auswahlseite.
  *Im Code:* `Offer`, `activatable()`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 1.
- **Anker**: Eine Angabe, über die sich ein Konto eindeutig wiederfinden lässt, etwa die
  Partnernummer, die Kennung eines Online-Ausweises oder eine bestätigte E-Mail-Adresse. Derselbe
  Wert kann nie zu zwei Konten gehören. Gefunden wird ein Konto nur über Anker, nie über Name und
  Geburtsdatum.
  *Im Code:* `AccountAnchor`, Tabelle `account.anchor`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6,
  [ADR-19](../adr/ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md).
- **Anmeldeprotokoll**: Hier steht für jedes Konto, wann sich jemand angemeldet und abgemeldet hat,
  mit welchem Verfahren und auf welchem Niveau, dazu jeder Fehlversuch und jede Sperre. Damit lässt
  sich klären, ob jemand Fremdes das Konto benutzt hat. Weil das Protokoll zeigt, wie sich eine
  Person verhält, wird es nur sechs Monate aufbewahrt und mit dem Konto gelöscht. Wer sich mit einem
  Einmalkennwort anmeldet, hat kein Konto; sein Eintrag hängt dann an der Einladung.
  *Im Code:* `SignInLog`, Tabelle `account.sign_in_log`.
  *Mehr dazu:* [ADR-39](../adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md), Nachtrag.
- **Anmeldeverfahren**, kurz **Verfahren**: Etwas, das im Konto eingerichtet ist und mit dem sich
  der Inhaber später anmelden kann, etwa ein Passwort, eine Handynummer für SMS oder ein Schlüssel auf
  dem Smartphone. Ein Verfahren wird nie gelöscht, nur abgeschaltet, damit nachvollziehbar bleibt,
  was mit ihm bewiesen wurde.
  *Im Code:* `AccountAuthMethod`, im Code oft kurz „Methode“ (`method`, etwa `sms`, `password`,
  `device`, `kobil`). Zu einem Verfahren gehören meist zwei Tools: `enroll-…` zum Einrichten,
  `auth-…` zum Anmelden.
  *Mehr dazu:* [03-tool-architektur](../03-tool-architektur.md) Abschnitt 1, je Verfahren eine
  Seite unter [verfahren/](../verfahren/README.md).
- **Anmeldung**: Ein Nutzer mit Konto beweist, dass er es ist, und erreicht dabei ein Niveau. In der
  App meldet er sich meist über sein verknüpftes Gerät an (Schnellzugang), auf der Website über
  Keycloak. Wer kein verknüpftes Gerät hat, meldet sich über seine E-Mail-Adresse an.
  *Im Code:* Intents `FAST_ACCESS`, `LOOKUP_LOGIN`, `WEB_SELECT_METHOD`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 2.
- **App-Kanal**: Die Verbindung der Smartphone-App zum Orchestrator. Die App weist jede Anfrage mit
  einem Schlüssel aus, der das Gerät nie verlässt (DPoP), und bekommt ihre Tokens vom Orchestrator.
  Das Gegenstück ist der Web-Kanal.
  *Im Code:* `ChannelType.APP`.
  *Mehr dazu:* [05-api](../05-api.md) Abschnitt 3a.
- **Auswahlseite**: Die Seite, auf der der Nutzer zwischen mehreren angebotenen Tools wählt, etwa
  „SMS oder Passwort“. Gibt es nur ein Tool im Angebot, entfällt sie.
  *Im Code:* Schritt `selectMethod`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 1.
- **Ausweichzustand** und **Ausweichkette**: In einem Ausweichzustand führt Ablehnen zum nächsten,
  etwas aufwendigeren Weg. Mehrere solche Zustände hintereinander bilden eine Ausweichkette, vom
  bequemsten Weg bis zum aufwendigsten. So ist der Schnellzugang aufgebaut: erst das Gerät, dann
  andere Verfahren, zuletzt eine neue Identifizierung. Das Gegenstück ist der Pflichtzustand (siehe
  Pflichten).
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 3.
- **Authentisierung und Authentifizierung**: Das externe Glossar trennt beides: Der Nutzer
  *authentisiert* sich, indem er einen Beweis liefert; der Server *authentifiziert* ihn, indem er den
  Beweis prüft. Dieses Projekt sagt für beides „Authentifizierung“ oder einfach „Anmeldung“. Das ist
  eine bewusste Vereinfachung.
  *Mehr dazu:* [Abgleich mit dem externen Glossar](abgleich-externes-glossar.md).
- **AuthPolicy**: Die Regeln, nach denen der Orchestrator aus den Nachweisen einer Sitzung das
  Sicherheitsniveau berechnet: welches Verfahren wie viel zählt, wann zwei Verfahren zusammen mehr
  ergeben und welche Verfahren als nächstes helfen würden. Nur hier wird das Niveau berechnet.
  *Im Code:* `AuthPolicy`, `DefaultAuthPolicy`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 4.

## B

- **Bestätigen**: Ein Tool prüft, dass eine Angabe stimmt, etwa indem es einen Code an die
  E-Mail-Adresse schickt, und meldet sie danach als geprüft. Das ist weder eine Anmeldung noch eine
  Identifizierung und erhöht das Sicherheitsniveau nicht. Eine Angabe zu bestätigen und sie einer
  Person zuzuordnen sind zwei getrennte Schritte.
  *Im Code:* Tool-Rolle `ATTESTATION`, Ergebnis `ToolOutcome.Completed.Attested`.
  *Mehr dazu:* [ADR-17](../adr/ADR-017-adresse-bestaetigen-und-e-mail-login-einrichten-sind.md),
  [ADR-18](../adr/ADR-018-bestaetigen-und-zuordnen-sind-zwei-akte.md).
- **Bestätigungscode**: Beim QR-Login zeigt die App nach der Freigabe einen kurzen Code, den der
  Nutzer im Browser eintippt. Erst damit ist der Browser angemeldet; so kann niemand einen fremden
  Browser freigeben, ohne vor ihm zu sitzen.
  *Mehr dazu:* [Verfahren `qr`](../verfahren/qr.md).
- **Bindungsschlüssel**: Das Merkmal, an dem der Orchestrator erkennt, zu welchem Kanal eine Anfrage
  gehört. In der App ist es der Fingerabdruck des DPoP-Schlüssels, im Web-Kanal die Kanalbindung,
  die Keycloak in seine signierte Anfrage schreibt.
  *Im Code:* `binding_key_ref`, `@BindingKey`.
  *Mehr dazu:* [09-dpop](../09-dpop.md) Abschnitt 3.

## D

- **Demomodus**: Ein Schalter, der alles einschaltet, was nur zum Vorführen gedacht ist: simulierte
  Fremdsysteme mit Beispieldaten, eine Funktion zum Zurücksetzen, sichtbare Codes. Im echten Betrieb
  ist er aus; dann weigert sich die Anwendung zu starten, solange unsichere Einstellungen gesetzt
  sind.
  *Im Code:* `demo.mode`, `DemoMode`, `@DemoSurface`, `ProductionModeCheck`.
  *Mehr dazu:* [ADR-28](../adr/ADR-028-demo-werte-abschaltbar.md).
- **DPoP**: Ein Standard (RFC 9449), mit dem eine Anfrage belegt, dass sie vom Besitzer eines
  bestimmten Schlüssels kommt. Wer eine Anfrage abfängt, kann sie deshalb nicht nachmachen.
  *Mehr dazu:* [09-dpop](../09-dpop.md).
- **DPoP-Proof**: Der Beleg, den die App bei jeder einzelnen Anfrage mitschickt, mit ihrem Schlüssel
  signiert. Er gilt nur für diese eine Anfrage und nur kurz. Nicht zu verwechseln mit dem Nachweis,
  der beschreibt, was die Sitzung insgesamt bewiesen hat.
  *Mehr dazu:* [09-dpop](../09-dpop.md) Abschnitt 1.

## E

- **Eingerichtet** und **im Aufbau**: Ein Konto ist eingerichtet, sobald es ein Anmeldeverfahren
  hat, denn erst dann kann man sich darin anmelden. Vorher ist es im Aufbau, etwa mitten in einer
  Registrierung. Ein eingerichtetes Konto fällt nie in den Aufbau zurück.
  *Im Code:* `AccountProfile.isSetUp`.
  *Mehr dazu:* [ADR-46](../adr/ADR-046-konto-im-aufbau.md).
- **Einladung**: Ein Brief des Personenverzeichnisses, der eine Person zu einem bestimmten Vorgang
  einlädt, etwa einer Beitragsrückerstattung. Er enthält ein Einmalkennwort, mit dem sich die Person
  auf der Website anmelden kann, auch ohne Konto. Die Einladung gehört dem Personenverzeichnis; der
  Orchestrator merkt sich nur ihre Kennung.
  *Im Code:* `Invitation`, Tool `auth-invite-lookup`.
  *Mehr dazu:* [ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
- **Einmalkennwort**: Das Kennwort im Einladungsbrief. Zusammen mit der KVNR oder der
  Partnernummer öffnet es den Zugang zu genau einem Vorgang. Trotz des Namens lässt es sich mehrmals
  benutzen, bis die Frist abläuft oder der Vorgang abgeschlossen ist; „einmal“ heißt hier „für einen
  Vorgang“. Gespeichert wird nie das Kennwort, nur ein daraus berechneter Wert.
  *Mehr dazu:* [ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
- **Einstiegs-Intent**: siehe Intent.
- **Erneute Identifizierung**: Erreicht kein aktives Verfahren das geforderte Niveau, bietet die
  Journey an, sich noch einmal zu identifizieren. Sie fragt immer erst nach. Die Identifizierung
  bestätigt nur das Konto, das schon bekannt ist, und übernimmt nie ein anderes. Ein Konto, das nie
  identifiziert wurde, bekommt so zum ersten Mal eine Identität.
  *Im Code:* Intent `RE_IDENTIFY`, nur als Sub-Journey.
  *Mehr dazu:* [journeys/re-identify](../journeys/re-identify.md).

## F

- **Faktorart**: siehe Faktortyp.
- **Faktortyp**: Die Art eines Beweises: etwas, das man weiß (Passwort), etwas, das man hat
  (Smartphone), oder etwas, das man ist (Fingerabdruck). Für das Niveau `loa2` braucht es zwei
  verschiedene Arten; zwei Verfahren derselben Art reichen nicht.
  *Im Code:* `FactorType` mit `KNOWLEDGE`, `POSSESSION`, `INHERENCE`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 4.
- **Feature-Flag**: Ein Schalter, mit dem der Betreiber zur Laufzeit ein Verhalten umstellt, ohne neu
  zu starten. Beispiele: ob die Registrierung mit dem Einrichten eines Verfahrens beginnt statt mit der
  Identifizierung, welches Aussehen die Keycloak-Anmeldeseite hat, und ob Keycloak für `loa1` selbst
  nach dem Passwort fragt.
  *Im Code:* `FeatureFlagService`; was eine Strategie liest, nennt `JourneyFeatureFlag`
  (`register-enroll-first`), die Schalter für Keycloak `KeycloakFeatureFlags`
  (`keycloak-login-keycloakify`, `keycloak-loa1-password`).
  *Mehr dazu:* [ADR-41](../adr/ADR-041-keycloakify-neben-freemarker.md),
  [ADR-42](../adr/ADR-042-loa1-anmeldung-umschalten.md).
- **Freischaltcode**: Ein Code, den die Versicherung per Brief schickt und mit dem sich eine Person
  identifiziert. Der Code liegt beim Personenverzeichnis; der Orchestrator fragt dort nach, statt
  eine eigene Kopie zu halten.
  *Im Code:* Tool `ident-fsc`.
  *Mehr dazu:* [ADR-31](../adr/ADR-031-freischaltcode-liegt-im-fremdsystem.md).
- **Fremdsystem**: Ein System außerhalb des Orchestrators, das seine Daten selbst verwaltet, etwa das
  Personenverzeichnis, der Identifizierungsdienst Nect oder KOBIL. In diesem Projekt sind sie
  simuliert. Der Orchestrator spricht mit ihnen nur über festgelegte Schnittstellen, damit sich eine
  Simulation später durch das echte System ersetzen lässt.
  *Mehr dazu:* [port-vertraege](../port-vertraege.md).

## G

- **Geräte-Proof**: Der Beleg, mit dem die App bei der Anmeldung per Gerät zeigt, dass sie den
  Geräteschlüssel besitzt. Wie der DPoP-Proof gilt er nur für eine einzelne Anfrage.
  *Im Code:* `device-proof+jwt`, `DeviceProofs`, Tool `auth-device`.
  *Mehr dazu:* [Verfahren `device`](../verfahren/device.md).
- **Gerätebindung**: Ein Anmeldeverfahren, das fest an ein bestimmtes Smartphone gebunden ist. Beim
  Einrichten erzeugt das Gerät einen Schlüssel, der es nie verlässt; wer sich später anmeldet, beweist
  damit, dass er dieses Gerät in der Hand hat. Meist entsperrt der Nutzer den Schlüssel mit PIN oder
  Fingerabdruck, sodass ein einziges Verfahren schon zwei Faktortypen abdeckt. In diesem Projekt gibt
  es zwei solche Verfahren: den Geräteschlüssel der App und KOBIL. Nicht zu verwechseln mit der
  Geräteverknüpfung, die das Gerät nur wiedererkennt und nicht als Anmeldung zählt.
  *Im Code:* Verfahren `device` (Tools `enroll-device`, `auth-device`) und `kobil` (`enroll-kobil`,
  `auth-kobil`); ob ein solches Verfahren auf dem anfragenden Gerät nutzbar ist, prüft
  `Tool.usableByCaller`.
  *Mehr dazu:* [09-dpop](../09-dpop.md) Abschnitt 3,
  [Verfahren `device`](../verfahren/device.md) und [`kobil`](../verfahren/kobil.md), [ADR-21](../adr/ADR-021-der-kobil-pin-liegt-im-backend-und-das.md).
- **Geräteverknüpfung**: Merkt sich, welches Smartphone zu welchem Konto gehört, erkannt an seinem
  DPoP-Schlüssel. So erkennt die App ihren Nutzer beim nächsten Start wieder. Die Verknüpfung
  allein ist keine Anmeldung; anmelden muss sich der Nutzer trotzdem. Nicht zu verwechseln mit der
  Gerätebindung, die ein Anmeldeverfahren ist.
  *Im Code:* `DeviceAccountLink`.
  *Mehr dazu:* [ADR-3](../adr/ADR-003-channelsession-bewusst-kurzlebig-geraete-identitaet-in-deviceaccountlink.md).

## I

- **Identifizierung**: Ein Schritt, der feststellt, wer jemand wirklich ist. Dafür gibt es drei
  Identifizierungsverfahren: den Freischaltcode aus dem Brief, den Online-Ausweis und Nect mit
  Personalausweis, Reisepass oder EUDI-Wallet. Danach ist das Konto einer Person im
  Personenverzeichnis zugeordnet, und das Sicherheitsniveau kann steigen. Das externe Glossar nennt
  ein solches Verfahren „Identifizierungsmittel“.
  *Im Code:* Tool-Rolle `IDENTIFICATION`, Tools `ident-fsc`, `ident-eid`, `ident-nect`.
  *Mehr dazu:* [03-tool-architektur](../03-tool-architektur.md) Abschnitt 1,
  [Abgleich mit dem externen Glossar](abgleich-externes-glossar.md).
- **Identifizierungsverfahren**: siehe Identifizierung.
- **Intent**: Das Anliegen, mit dem ein Nutzer kommt, etwa sich registrieren, sich anmelden oder
  sein Niveau erhöhen. Zu jedem Intent gehört ein fester Ablauf, den der Orchestrator steuert. Mit
  einigen Intents beginnt eine neue Sitzung (Einstiegs-Intents, etwa Schnellzugang oder
  Registrierung); andere, etwa Konto löschen, laufen nur in einer Sitzung, die schon angemeldet ist.
  *Im Code:* `AuthIntent`, etwa `REGISTER`, `FAST_ACCESS`, `STEP_UP` und `WEB_SELECT_METHOD`
  (Anmeldung auf der Website).
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 2.
- **Interessent**, **Partner**, **Versicherter**: Die drei Rollen eines Kontos. Ein Interessent ist
  noch keiner Person im Personenverzeichnis zugeordnet. Ein Partner ist der Versicherung als Person
  bekannt (er hat eine Partnernummer), aber nicht bei ihr versichert. Ein Versicherter hat außerdem
  eine Mitgliedsnummer. Die Rolle wird nicht gespeichert, sondern aus den Ankern abgeleitet.
  *Im Code:* Der Interessent heißt englisch `prospect`.
  *Mehr dazu:* [ADR-34](../adr/ADR-034-personenverzeichnis-meldet-aenderungen.md).
- **Invarianten**: Die Regeln, auf die sich der Kern jederzeit verlässt, etwa „ein Kanal gehört
  höchstens einem Subjekt“. Zu jeder Regel steht im Register, wodurch sie gesichert ist: durch den
  Typ, eine Datenbankregel oder einen Test.
  *Mehr dazu:* [invarianten](../invarianten.md).

## J

- **Journey**: Ein laufender Ablauf zu einem Intent, etwa eine Registrierung von der Identifizierung
  bis zum fertigen Konto. Eine Journey besteht aus mehreren Schritten und nutzt dafür ein oder mehrere
  Tools. Auf einem Kanal läuft immer genau eine.
  *Im Code:* `AuthJourney`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 3, Diagramme unter
  [journeys](../journeys/).
- **Journey-Protokoll**: Hält jeden Schritt einer Journey fest: welches Tool angeboten, gewählt oder
  abgelehnt wurde und welches Niveau am Ende erreicht war. Damit lässt sich später beantworten, warum
  ein Nutzer einen bestimmten Weg genommen hat.
  *Im Code:* `JourneyTraceEntry`, in der Oberfläche „Journey-Trace“.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 3.

## K

- **Kanal**: Eine Verbindung eines Nutzers zum Orchestrator, entweder über die App oder über die
  Website. Ein Kanal lebt nur kurz; ist der Nutzer angemeldet, endet der Kanal spätestens mit seiner
  Sitzung in Keycloak.
  *Im Code:* `ChannelSession`, Zustände `ANONYMOUS`, `AUTHENTICATED`, `STEP_UP_REQUIRED`,
  `STEP_UP_IN_PROGRESS`, `LOGGED_OUT`, `EXPIRED` (`REGISTERING` wird nur angezeigt).
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 3,
  [ADR-43](../adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md).
- **Kanalbindung**: Die Kennung, mit der Keycloak jede Anfrage an den Orchestrator einem bestimmten
  Anmeldevorgang zuordnet. Keycloak signiert sie mit; so kann niemand eine Anfrage auf einen fremden
  Kanal umlenken.
  *Im Code:* `channel_binding`, `channelBinding`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 1.
- **Kandidaten**: Die Tools, die eine Journey dem Nutzer an einer Stelle zur Auswahl anbietet, etwa
  „SMS oder Passwort“. Welche es sind, ergibt sich aus der Art der Tools, nicht aus ihren Namen; ein
  neues Tool erscheint so von selbst an der richtigen Stelle.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 1.
- **Kartendaten**: Beim Online-Ausweis die Daten, die die Karte selbst liefert: Name, Geburtsdatum,
  Anschrift und ein an die Karte gebundenes Pseudonym. Der Nutzer tippt sie nicht ein; erst danach
  fragt das Tool nach der PIN.
  *Mehr dazu:* [Verfahren `eid`](../verfahren/eid.md).
- **Keycloak**: Das Produkt, das auf der Website die Anmeldung führt und die Tokens ausstellt. Welche
  Schritte nötig sind, fragt Keycloak den Orchestrator. Keycloak hält keine Kopie der Konten, sondern
  liest sie bei Bedarf beim Orchestrator nach.
  *Mehr dazu:* [05-api](../05-api.md) Abschnitt 3b,
  [ADR-38](../adr/ADR-038-keycloak-liest-konten.md).
- **Keycloak-Sitzung**: Die Sitzung, die Keycloak für einen angemeldeten Nutzer führt. Sie endet nach
  30 Minuten ohne Aktivität, spätestens nach 10 Stunden. Auch die Anmeldung in der App hat eine
  solche Sitzung; der Kanal lebt nie länger als sie.
  *Im Code:* in Keycloak `UserSessionModel`, beim Orchestrator `keycloakSessionId`.
  *Mehr dazu:* [ADR-43](../adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md),
  [07-betrieb](../07-betrieb.md) Abschnitt 3.
- **KOBIL**: Ein externer Anbieter einer App, die als Anmeldeverfahren mit Gerätebindung dient. In
  diesem Projekt ist er simuliert. Die PIN für das KOBIL-Verfahren verwahrt der Orchestrator selbst;
  der Nutzer gibt sie nie ein.
  *Im Code:* Tools `enroll-kobil`, `auth-kobil`.
  *Mehr dazu:* [Verfahren `kobil`](../verfahren/kobil.md),
  [ADR-21](../adr/ADR-021-der-kobil-pin-liegt-im-backend-und-das.md).
- **Konto**: Das, worin sich ein Nutzer anmeldet. Es hält seine Anmeldeverfahren, seine Angaben und
  seine Anker, aber keine eigenen Stammdaten; die liegen im Personenverzeichnis. Ein Konto kann einer
  Person zugeordnet sein oder nicht (dann gehört es einem Interessenten).
  *Im Code:* `Account`, Tabelle `account.account`; die Lesesicht für den Orchestrator heißt
  `AccountProfile`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6.
- **Korrelation**: Ordnet eine schon festgestellte Identität einem Datensatz im Personenverzeichnis
  zu, etwa über die KVNR. Sie beweist selbst nichts und erhöht das Niveau nicht.
  *Im Code:* Tool-Rolle `CORRELATION`, Tool `ident-kvnr`.
  *Mehr dazu:* [ADR-18](../adr/ADR-018-bestaetigen-und-zuordnen-sind-zwei-akte.md).
- **KVNR**, die Krankenversichertennummer: Die Nummer auf der Gesundheitskarte. Sie gibt es nur für
  Versicherte. Eigentlich bleibt ihr fester Teil ein Leben lang gleich; im Personenverzeichnis lässt
  sie sich trotzdem ändern, weil es Fälle gibt, in denen eine KVNR versehentlich doppelt vergeben
  wurde. Ein Konto wird nicht über die KVNR gefunden.
  *Im Code:* Tool `ident-kvnr`; im Konto eine Angabe, kein Anker.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6,
  [ADR-34](../adr/ADR-034-personenverzeichnis-meldet-aenderungen.md).

## L

- **Lebenszyklus einer Journey**: Ob eine Journey gerade läuft, pausiert (weil eine Sub-Journey
  läuft), fertig ist, abgebrochen wurde oder gescheitert ist. Anders als der Zustand sagt er nichts
  darüber, an welcher Stelle die Journey steht.
  *Im Code:* `JourneyLifecycle`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 3.

## M

- **Mehrstufige Authentifizierung** und **MFA**: Mehrstufig heißt: zwei Verfahren, die einzeln
  geprüft werden, etwa SMS und Passwort. MFA heißt hier nur: ein einziges Verfahren mit zwei
  verknüpften Faktoren, etwa ein Geräteschlüssel, der mit PIN entsperrt wird. Beides kann `loa2`
  erreichen.
  *Mehr dazu:* [Abgleich](abgleich-externes-glossar.md),
  [04-orchestrierung](../04-orchestrierung.md) Abschnitt 4.
- **Mengenbegrenzung**: Legt fest, wie oft etwas in einem Zeitraum passieren darf, etwa wie viele
  SMS an dieselbe Nummer gehen. Sie bremst Missbrauch und Kosten. Anders als eine Sperre trifft sie
  nicht das Raten eines Passworts, sondern die Menge; wer sie überschreitet, bekommt die Antwort
  „zu viele Anfragen“ (`429`). Die Grenzen legt jedes Modul selbst fest, gezählt wird zentral.
  *Im Code:* `RateLimit`, `RateLimits` (Paket `tool_api.ratelimit`); das Zählwerk `RateLimitCounter`
  mit einer Zeile `RateLimitRecord` je Bereich (`RateLimitScope`) in der Tabelle `rate_limit`;
  Metrik `identity.ratelimit.blocked`.
  *Mehr dazu:* [ADR-44](../adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md),
  [07-betrieb](../07-betrieb.md) Abschnitt 4.
- **Methode**: Das Wort, mit dem der Code ein Anmeldeverfahren bezeichnet (`method`). Siehe
  Anmeldeverfahren.
- **Mindestniveau für einen Anker**: Welches Niveau eine Sitzung haben muss, um einen Anker zu
  setzen oder zu ändern. Ändern verlangt immer `loa2`, denn wer einen Anker umbiegt, kann ein
  fremdes Konto übernehmen. Nur eine E-Mail-Adresse darf schon mit `loa1` zum ersten Mal gesetzt
  werden, weil eine Registrierung sie früh braucht.
  *Im Code:* `AnchorAcrFloor` mit `establish` und `replace`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6.
- **Mitgliedsnummer**, auch **Versicherungsnummer** genannt: Die achtstellige Nummer, unter der ein
  Versicherter bei der Versicherung geführt wird. Nur Versicherte haben eine; sie kann sich ändern
  oder entfallen.
  *Im Code:* `MemberNumber`, Anker `MEMBER_NUMBER`. Im Token und in Keycloak heißt sie weiter
  `versnr`, im Vokabular des Personenverzeichnisses.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6,
  [ADR-34](../adr/ADR-034-personenverzeichnis-meldet-aenderungen.md).
- **Modul** und **Tool-Modul**: Die Anwendung ist in Module geteilt, die nur über festgelegte
  Schnittstellen miteinander reden; ein Test hält diese Grenzen ein. Ein Tool-Modul enthält die Tools
  eines Verfahrens, etwa alles zu SMS, und kennt den Orchestrator nur über `tool_api`.
  *Im Code:* Pakete unter `tools/` (etwa `tools/auth_sms`), je Modul eine Klasse mit `@ApplicationModule` (etwa `SmsToolModule`).
  *Mehr dazu:* [08-projektrahmen](../08-projektrahmen.md) Abschnitt 3.

## N

- **Nachweis**: Was ein Nutzer in seiner laufenden Sitzung bewiesen hat, etwa „Passwort richtig vor
  5 Minuten, SMS-Code richtig vor 2 Minuten“. Daraus berechnet der Orchestrator das Sicherheitsniveau.
  Ein Nachweis veraltet: Für ein Niveau über `loa1` zählen nur Beweise der letzten 30 Minuten.
  *Im Code:* `SessionEvidence` mit je einem `MethodEvidence` pro Verfahren in `methods`; gespeichert als
  `SessionEvidenceRecord`. Die Frist heißt `identity.policy.loa2-max-age`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 4.
- **Nect**: Ein externer Dienst, der Personen anhand von Personalausweis, Reisepass oder EUDI-Wallet
  identifiziert; geprüft wird dabei auch ein Selfie gegen das Passbild. Der Nutzer wird dafür zu Nect
  weitergeleitet und kommt danach zurück. In diesem Projekt ist Nect simuliert.
  *Im Code:* Tool `ident-nect`.
  *Mehr dazu:* [Verfahren `nect`](../verfahren/nect.md),
  [ADR-47](../adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md).
- **`next`**: In jeder Antwort sagt der Orchestrator dem Client, welcher Schritt als nächstes kommt.
  Der Client folgt dieser Angabe und entscheidet selbst nichts; so steckt die ganze Ablauflogik an
  einer Stelle.
  *Mehr dazu:* [ADR-6](../adr/ADR-006-next-als-reine-adresse-feste-routing-tabelle-statt.md).
- **Niveau**, ausführlich **Sicherheitsniveau**: Wie sehr einer Anmeldung vertraut wird. `loa1`
  heißt: ein Verfahren, etwa ein Passwort. `loa2` heißt: zwei Verfahren verschiedener Art oder eine
  Identifizierung. `loa3` erreicht nur eine starke Identifizierung, etwa mit dem Online-Ausweis.
  Manche Funktionen verlangen ein bestimmtes Niveau: Verfahren verwalten und das Konto löschen etwa
  `loa2`, bei einem nie identifizierten Konto `loa1`. Die Skala folgt
  NIST 800-63; sie liegt nah an den eIDAS-Niveaus niedrig, substanziell und hoch, ist ihnen aber
  nicht gleich.
  *Im Code:* `acr`, Werte `loa1`, `loa2`, `loa3`; die Schwelle für Verwalten und Löschen heißt
  `selfServiceAcrFloor`.
  *Mehr dazu:* [01-ueberblick](../01-ueberblick.md) Abschnitt 9.

## O

- **Obergrenzen eines Verfahrens**: Ein Verfahren kann nie mehr Vertrauen liefern, als es technisch
  hergibt, und nie mehr, als die Sitzung hatte, in der es eingerichtet wurde. So kann niemand in einer
  schwach gesicherten Sitzung ein Verfahren einrichten und sich damit später ein höheres Niveau
  verschaffen.
  *Im Code:* `maxAcr`, `enrolledUnderAcr`.
  *Mehr dazu:* [ADR-5](../adr/ADR-005-drei-obergrenzen-fuer-das-sicherheitsniveau.md).
- **Online-Ausweis** (eID): Die Ausweisfunktion des Personalausweises. Die Karte liefert die
  Kartendaten, der Nutzer bestätigt mit seiner PIN. Das ist die stärkste Identifizierung im Projekt
  und führt zu `loa3`; sonst erreicht das nur Nect mit Personalausweis oder EUDI-Wallet. In diesem
  Projekt ist sie simuliert.
  *Im Code:* Tool `ident-eid`.
  *Mehr dazu:* [Verfahren `eid`](../verfahren/eid.md).
- **Orchestrator**: Der Server dieses Projekts. Er entscheidet, welche Schritte ein Nutzer bei
  Registrierung und Anmeldung durchläuft, und berechnet als einzige Stelle das Sicherheitsniveau. Die
  einzelnen Verfahren sind eigene Module, die er über eine feste Schnittstelle einbindet.
  *Im Code:* Die Schnittstelle heißt `tool_api`.
  *Mehr dazu:* [01-ueberblick](../01-ueberblick.md) Abschnitt 2.

## P

- **Pairing-Code**: Beim QR-Login die kurze Zeichenfolge, die der Browser neben dem QR-Code zeigt. Wer
  den QR-Code nicht scannen kann, tippt ihn in der App ein.
  *Mehr dazu:* [Verfahren `qr`](../verfahren/qr.md).
- **Partnernummer**: Die Nummer, unter der eine Person im Personenverzeichnis geführt wird: `P` und
  neun Ziffern. Jede Person hat eine, und sie ändert sich nie.
  *Im Code:* `PartnerNumber` für das Format; als Anker `PERSON_ID`, in Feldern und Tokens `personId`,
  weil sie die Person bezeichnet.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6.
- **Peer-Auth**: Wie sich Keycloak und Orchestrator gegenseitig ausweisen. Jede Anfrage von Keycloak
  ist signiert und nennt die Kanalbindung; jede Antwort des Orchestrators auf eine gültige Anfrage
  ist ebenfalls signiert und an genau diese Anfrage gebunden. So kann sich niemand dazwischenschalten.
  *Mehr dazu:* [ADR-7](../adr/ADR-007-web-kanal-ohne-mtls-signierte-request-assertion-statt.md).
- **Person**: Ein Mensch, wie ihn das Personenverzeichnis kennt, mit Partnernummer und Stammdaten.
  Ein Konto kann einer Person zugeordnet sein; die Zuordnung entsteht durch eine Identifizierung.
  *Im Code:* `personId`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6.
- **Personenverzeichnis**: Die Stammdaten der Versicherung: Personen mit Partnernummer, Name,
  Geburtsdatum, Anschrift und, bei Versicherten, Mitgliedsnummer und KVNR. Es verschickt
  Freischaltcodes und Einladungen und meldet Änderungen an den Orchestrator. In diesem Projekt ist es
  simuliert.
  *Mehr dazu:* [ADR-34](../adr/ADR-034-personenverzeichnis-meldet-aenderungen.md).
- **Pflichten** und **Pflichtzustand**: Pflichten sind, was eine Journey noch verlangt, bevor sie
  fertig ist, etwa eine bestätigte E-Mail-Adresse oder ein zweites Verfahren anderer Art. Sie gehören
  zur Journey, nicht zum Konto: Ein Konto kann schon benutzbar sein, obwohl eine Registrierung noch
  etwas offen hat. Solange eine Pflicht offen ist, steht die Journey in einem Pflichtzustand: Lehnt
  der Nutzer ein Tool ab, kommt das ganze Angebot zurück, auch das abgelehnte Tool; weiter geht es
  nur, wenn die Pflicht erfüllt ist. Das Gegenstück ist der Ausweichzustand.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitte 1 und 8.
- **Pflichtzustand**: siehe Pflichten.
- **Port**: Eine fest vereinbarte Schnittstelle, über die der Orchestrator ein Fremdsystem fragt,
  etwa „Ist dieser Freischaltcode gültig?“. Der Orchestrator kennt nur den Port, nicht das System
  dahinter; so lässt sich eine Simulation gegen das echte System tauschen.
  *Mehr dazu:* [port-vertraege](../port-vertraege.md).

## Q

- **QR-Login**: Anmeldung auf der Website mit Hilfe der App. Der Browser zeigt einen QR-Code, der
  Nutzer scannt ihn mit der schon angemeldeten App und gibt die Anmeldung dort frei. Danach tippt er
  den Bestätigungscode im Browser ein.
  *Im Code:* Tools `auth-qr`, `auth-qr-lookup`; die Freigabe in der App `approve-qr`, Intent
  `CONFIRM_PEER_LOGIN`.
  *Mehr dazu:* [Verfahren `qr`](../verfahren/qr.md).
- **Quelle**: Wer für eine Angabe einsteht: das Personenverzeichnis, ein Prüfverfahren wie der
  Online-Ausweis oder der Nutzer selbst. Aus der Quelle folgt, wie verlässlich die Angabe ist. Auch
  ein Widerruf nennt seine Quelle, etwa „vom Personenverzeichnis gemeldet“. Nicht zu verwechseln mit
  der Quelle eines Nachweises.
  *Im Code:* `ClaimSource`; beim Widerruf `RetractionSource`, Spalte `claim_source`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6,
  [ADR-12](../adr/ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md).
- **Quelle eines Nachweises**: Wer ein Verfahren in der Sitzung geprüft hat: der Orchestrator mit
  einem eigenen Tool oder Keycloak mit einem eigenen Anmeldeschritt, etwa seinem Passwortformular.
  Ein Nachweis des Orchestrators wiegt nie weniger als eine Meldung von Keycloak. Nicht zu
  verwechseln mit der Quelle einer Angabe.
  *Im Code:* `AmrSource` mit `orchestrator` und `kc`.
  *Mehr dazu:* [05-api](../05-api.md) Abschnitt 3b.

## R

- **Realm**: Ein abgeschlossener Bereich in Keycloak mit eigenen Nutzern, Clients und
  Anmeldeabläufen. Dieses Projekt legt beim Start ein Realm namens „Demo“ an und hält seine
  Einstellungen in Migrationen fest.
  *Im Code:* Modul `keycloak-migrations`.
  *Mehr dazu:* [ADR-25](../adr/ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md).
- **Registrierung**: Ein neuer Nutzer legt ein Konto an. Normalerweise identifiziert er sich zuerst,
  bestätigt dann seine E-Mail-Adresse und richtet so lange Verfahren ein, bis das verlangte Niveau
  erreicht ist. Ein Schalter lässt die Registrierung auch mit dem Einrichten eines Verfahrens
  beginnen (Experiment „Enrollment zuerst“); die Identifizierung wird dann nur noch angeboten.
  *Im Code:* Intent `REGISTER`, Varianten `RegisterStrategy` und `RegisterEnrollFirstStrategy`.
  *Mehr dazu:* [journeys/register](../journeys/register.md).
- **RestoreData**: Ein von Keycloak aufbewahrter, signierter Zettel mit dem, was ein Nutzer in einem
  früheren Anmeldevorgang derselben Sitzung schon bewiesen hat. Beim nächsten Vorgang gibt Keycloak
  ihn zurück, damit der Nutzer nicht alles noch einmal beweisen muss. Der Zettel sagt auch, wann der
  Beweis war; ältere Beweise zählen nur noch für `loa1`.
  *Mehr dazu:* [05-api](../05-api.md) Abschnitt 3b.

## S

- **Schnellzugang**: Der übliche Weg, sich in der App anzumelden: so schnell wie möglich auf diesem
  Gerät, und so, dass es auch beim nächsten Mal klappt. Er probiert erst das Bequemste und weicht
  dann schrittweise aus.
  *Im Code:* Intent `FAST_ACCESS`.
  *Mehr dazu:* [journeys/fast-access](../journeys/fast-access.md).
- **Schritt**: Ein einzelner Bildschirm innerhalb eines Tools, etwa „TAN eingeben“.
  *Im Code:* `next.step`.
- **Sperre**: Nach fünf falschen Versuchen wird ein Konto oder eine Person für 15 Minuten gesperrt,
  damit niemand Passwörter oder Codes durchprobieren kann. Wo das Konto schon bekannt ist, sagt die
  Antwort „gesperrt“ (`423`). Wo erst nach dem Konto gesucht wird, sieht die Antwort aus wie bei einer
  falschen Eingabe; sonst verriete sie, dass es das Konto gibt.
  *Im Code:* `AccountLockoutService`, `PersonLockoutService`, gezählt vom `RateLimitCounter`.
  *Mehr dazu:* [ADR-44](../adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md),
  [07-betrieb](../07-betrieb.md) Abschnitt 4.
- **Standardprofil**: Der Orchestrator ohne Keycloak, etwa beim Entwickeln. Dann gibt es nur den
  App-Kanal; die Tokens stellt der Orchestrator selbst aus. Mit dem Profil `keycloak` kommen
  Keycloak und der Web-Kanal dazu.
  *Mehr dazu:* [13-ausfuehren](../13-ausfuehren.md).
- **Step-up**: Ein schon angemeldeter Nutzer beweist noch etwas, um ein höheres Niveau zu erreichen,
  etwa bevor er sein Konto löschen darf. Er bleibt dabei in derselben Sitzung. Ein Vorgangszugang
  lässt sich so nicht aufwerten.
  *Im Code:* Intent `STEP_UP`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 2.
- **Strategie**: Die Regeln eines Intents: Sie bekommt den Zustand und das, was gerade passiert ist,
  und entscheidet, wie es weitergeht. Sie ändert selbst nichts, sondern beschreibt nur den Übergang.
  *Im Code:* `IntentStrategy`, etwa `RegisterStrategy`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 8.
- **Stufe einer Angabe**: Wie verlässlich eine Angabe ist, in drei Stufen: *belegt* stammt aus den
  Stammdaten der Versicherung, *nachgewiesen* hat ein Verfahren geprüft, *behauptet* hat nur der
  Nutzer selbst eingegeben. Gibt es mehrere Werte, gilt der verlässlichste, bei Gleichstand der
  jüngste.
  *Im Code:* `ClaimTrust` mit `AUTHORITATIVE`, `PROVEN`, `SELF_REPORTED`. Das externe Glossar sagt
  für die ersten beiden „bescheinigt“, für die letzte „unbescheinigt“.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6.
- **Sub-Journey**: Eine Journey, die eine andere unterbricht und danach die Rückkehr erlaubt. Beispiel:
  Wer seine Anmeldeverfahren verwalten will, muss zwischendurch einen Step-up machen; danach geht es
  an derselben Stelle weiter.
  *Im Code:* `Transition.RequireSubJourney`; die unterbrochene Journey ist solange `SUSPENDED`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 7.
- **Subjekt**: Wem ein angemeldeter Kanal gehört: einem Konto oder, beim Vorgangszugang, einer
  Einladung. Nie beidem, und nie wechselt es unbemerkt.
  *Im Code:* `Subject`; im Austausch mit Keycloak `authData.subject`.
  *Mehr dazu:* [ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).

## T

- **Token**: Ein signierter Ausweis, den ein Nutzer nach der Anmeldung bei Fachdiensten vorzeigt. Er
  nennt das Konto oder die Einladung, das Niveau (`acr`) und die Verfahren (`amr`) und gilt nur
  kurz; ein Refresh-Token holt einen neuen, solange die Sitzung läuft.
  *Mehr dazu:* [05-api](../05-api.md) Abschnitt 3a,
  [ADR-9](../adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md).
- **Tokens der App**: Die Zugangsschlüssel, mit denen die angemeldete App andere Dienste aufrufen
  kann. Sie hängen an dem, was der Nutzer bewiesen hat, und an seiner Sitzung in Keycloak.
  *Im Code:* `AppTokenSession`; ausgegeben und verlängert von `AppTokenIssuer`.
  *Mehr dazu:* [ADR-9](../adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md).
- **Tool**: Ein abgeschlossener Arbeitsschritt, den der Nutzer durchläuft, etwa „SMS einrichten“,
  „mit Passwort anmelden“ oder „mit dem Online-Ausweis identifizieren“. Jedes Tool beschreibt selbst,
  was es kann: welches Verfahren es betrifft, welche Rolle es hat, welche Faktortypen es beweist,
  welches Niveau es höchstens liefert und was vorher erfüllt sein muss. Daraus stellt der
  Orchestrator das Angebot zusammen.
  *Im Code:* `toolId` (etwa `enroll-sms`); die Beschreibung heißt `Tool`, das Verfahren mit allen seinen Tools `ToolModule`.
  *Mehr dazu:* [03-tool-architektur](../03-tool-architektur.md) Abschnitte 1 und 2.
- **Tool-Beschreibung**: siehe Tool.
- **Tool-Durchlauf**: Ein einmal gestartetes Tool, das oft nur wenige Minuten lebt, etwa das Warten
  auf eine eingegebene TAN. Seine Kennung ist eine UUID, eine andere als die des Tools selbst.
  *Im Code:* `ToolSession`, `toolSessionId` (nicht `toolId`).
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 1.
- **Tool-Rolle**: Was ein Tool fachlich tut: identifizieren, zuordnen, ein Verfahren einrichten, ein
  bekanntes Konto anmelden, ein Konto anhand der Eingabe suchen und anmelden, eine Anmeldung in einem
  anderen Kanal freigeben oder eine Angabe bestätigen. Aus der Rolle folgt, ob und wie ein Ergebnis
  das Sicherheitsniveau erhöht.
  *Im Code:* `ToolRole` mit `IDENTIFICATION`, `CORRELATION`, `ENROLLMENT`, `KNOWN_ACCOUNT_AUTH`,
  `ACCOUNT_LOOKUP_AUTH`, `PEER_APPROVAL`, `ATTESTATION`.
  *Mehr dazu:* [03-tool-architektur](../03-tool-architektur.md) Abschnitte 1 und 4.
- **Tool-Sperre**: Der Betreiber kann ein Tool für den App-Kanal oder den Web-Kanal abschalten und
  die Reihenfolge festlegen, in der Tools angeboten werden. Ein gesperrtes Tool erscheint in keinem
  Angebot.
  *Im Code:* Tabelle `orchestrator.tool_availability`.
  *Mehr dazu:* [ADR-32](../adr/ADR-032-tool-sperre-und-reihenfolge-je-kanal.md).

## U

- **Übergang**: Der Schritt von einem Zustand der Journey zum nächsten, ausgelöst durch ein Ereignis,
  etwa „Tool abgeschlossen“. Ein Übergang kann weiterführen, eine Aktion verlangen, eine Sub-Journey
  starten, die Anmeldung abschließen oder abbrechen.
  *Im Code:* `Transition`, ausgelöst durch ein `JourneyEvent`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 8.
- **Untergrenze des Kanals** und **Ziel eines Durchlaufs**: Die Untergrenze ist das Niveau, unter das
  ein Kanal nie fallen darf, solange er besteht. Das Ziel ist das Niveau, das ein einzelner Step-up
  erreichen soll. Gerechnet wird immer mit dem höheren der beiden Werte.
  *Im Code:* `ChannelSession.acrFloor`, `targetAcr`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 4.

## V

- **Versandlimit**: Eine Mengenbegrenzung dafür, wie viele Codes an dieselbe Telefonnummer oder
  E-Mail-Adresse gehen dürfen. Sie hängt an der Nummer oder Adresse, nicht am Konto, damit auch
  Unbekannte niemanden mit Nachrichten überschwemmen können.
  *Im Code:* `SmsSendLimit`, `EmailSendLimit`.
  *Mehr dazu:* [ADR-44](../adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md).
- **Versuchsbudget**: Wie viele Fehlversuche ein Nutzer in einer Journey insgesamt hat, über alle
  Tools hinweg. Sind sie aufgebraucht, endet die ganze Journey, und er muss neu beginnen.
  *Im Code:* `attemptBudget`; die Antwort ist `410`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 7.
- **Verwerfbar**: Ein Konto, das keiner Person zugeordnet ist und in dem nie ein Anmeldeverfahren
  eingerichtet wurde, etwa ein Rest aus einer abgebrochenen Registrierung. Nur ein solches Konto darf
  gelöscht oder in einem anderen Konto aufgehen. ADR-20 nennt es „vorläufig“.
  *Im Code:* `AccountProfile.isDisposable`.
  *Mehr dazu:* [ADR-20](../adr/ADR-020-ein-vorlaeufiges-konto-geht-im-gefundenen-auf-statt.md).
- **Vorgangszugang**: Die Anmeldung auf der Website mit einem Einmalkennwort aus einer Einladung,
  auch ohne Konto. Er gilt nur für den einen Vorgang, für den eingeladen wurde; die Tokens nennen
  diesen Vorgang. Es gibt ihn nur auf der Website, und er lässt sich nicht aufwerten.
  *Im Code:* Tool `auth-invite-lookup`, Token-Claim `process`.
  *Mehr dazu:* [ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).

## W

- **Web-Kanal**: Die Verbindung über die Website. Hier führt Keycloak die Anmeldung und fragt den
  Orchestrator, welche Schritte nötig sind; die Tokens stellt Keycloak aus. Das Gegenstück ist der
  App-Kanal.
  *Im Code:* `ChannelType.WEB`. Die Anbindung an Keycloak heißt nach der Technik: Klassen `Keycloak…`
  (etwa `KeycloakChannelService`), Pfade `/kc/…`.
  *Mehr dazu:* [05-api](../05-api.md) Abschnitt 3b,
  [ADR-8](../adr/ADR-008-keycloak-fuehrt-seine-eigenen-nativen-schritte-selbst-statt.md).
- **Widerruf**: Nimmt eine Angabe zurück, etwa wenn das Personenverzeichnis meldet, dass eine
  Mitgliedsnummer nicht mehr gilt. Die alte Angabe wird dabei nicht gelöscht; der Widerruf ist ein
  eigener Eintrag mit eigener Quelle, damit nachvollziehbar bleibt, was wann galt.
  *Im Code:* `AccountRetraction`, Tabelle `account.retraction`.
  *Mehr dazu:* [ADR-12](../adr/ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md).

## Z

- **Ziel eines Durchlaufs**: siehe Untergrenze des Kanals.
- **Zustand**: Wo eine Journey gerade steht, etwa „wartet auf die TAN“. Jeder Intent hat eine
  feste, abgeschlossene Liste möglicher Zustände; so ist klar, welcher Schritt von wo aus möglich
  ist.
  *Im Code:* `JourneyState`.
  *Mehr dazu:* [ADR-2](../adr/ADR-002-zustand-statt-vererbung-bei-authjourney.md).
