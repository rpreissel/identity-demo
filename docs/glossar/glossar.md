# Glossar

Hier stehen die Begriffe dieses Projekts in alphabetischer Reihenfolge. Jeder Eintrag erklärt zuerst
in ganzen Sätzen, was gemeint ist und wozu es dient. Darunter steht, unter welchen Namen der Begriff
im Code vorkommt („Im Code“) und wo er ausführlich beschrieben ist („Mehr dazu“). Wenn Sie von einem
Namen im Code ausgehen, finden Sie ihn im [Register nach englischen Begriffen](glossar-englisch.md).

Allgemeine Fachbegriffe zu Authentifizierung, Identifizierung und Gerätebindung erklärt das
[externe Glossar](externes-glossar.md). Wie seine Begriffe in diesem Projekt heißen und wo das
Projekt bewusst davon abweicht, zeigt der [Abgleich](abgleich-externes-glossar.md). Für den Einstieg
genügen die wichtigsten Begriffe im [Überblick](../01-ueberblick.md), Abschnitt 3.

---

## A

- **AAL und IAL**: Das Sicherheitsniveau einer Anmeldung setzt sich aus den Antworten auf zwei
  Fragen zusammen. AAL fragt: Ist das dieselbe Person wie beim letzten Mal? Diese Frage beantworten
  Anmeldeverfahren wie Passwort oder SMS. IAL fragt: Wer ist diese Person wirklich? Diese Frage
  beantwortet eine Identifizierung, etwa mit dem Online-Ausweis. Beide Antworten werden getrennt
  bewertet. Deshalb zählen eine Identifizierung und ein Passwort nie zusammen als zwei Faktoren.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 4.
- **Ablehnen**: Der Nutzer lehnt ein angebotenes Tool ab, oder das Tool scheitert endgültig. Was
  danach passiert, hängt vom Zustand der Journey ab. Mehr dazu unter Ausweichzustand und
  Pflichten.
- **Aktion**: Eine Aktion ist eine Änderung an gespeicherten Daten, die ein Übergang auslösen will,
  etwa ein Konto anlegen, eine Angabe schreiben oder ein Gerät verknüpfen. Die Strategie beschreibt
  die Aktion nur. Ausgeführt wird sie an einer einzigen Stelle, damit Änderungen am Konto nicht über
  den ganzen Code verstreut sind.
  *Im Code:* `Action`, ausgeführt vom `JourneyActionExecutor`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 8.
- **`amr`**: Die Liste der Verfahren, mit denen sich ein Nutzer in der laufenden Sitzung angemeldet
  hat, etwa „sms, password“. Die Liste steht im Token neben dem Niveau (`acr`). So kann ein
  Fachdienst sehen, wie die Anmeldung zustande kam.
  *Im Code:* „Authentication Methods References“ nach RFC 8176; `SessionEvidence.amr`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 4.
- **Änderungsprotokoll**: Das Änderungsprotokoll hält fest, welche Angabe eines Kontos wann und von
  wem geändert wurde. Die Werte selbst speichert es nicht. Es bleibt zehn Jahre erhalten, auch wenn
  das Konto gelöscht wird. So lässt sich eine Änderung später noch nachweisen.
  *Im Code:* `ChangeLog`, Tabelle `account.change_log`.
  *Mehr dazu:* [ADR-39](../adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md).
- **Angabe**: Eine Information über den Kontoinhaber, etwa Name, E-Mail-Adresse oder Geburtsdatum.
  Zu jeder Angabe merkt sich das Konto, wer sie geliefert hat und für ihre Richtigkeit
  verantwortlich ist (die Quelle) und wie verlässlich sie deshalb ist (die Stufe). Auf Englisch
  heißt eine solche Angabe auch Claim.
  *Im Code:* `AccountClaim`, Tabelle `account.claim`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6.
- **Angebot**: Die Tools, die eine Journey dem Nutzer in ihrem aktuellen Zustand tatsächlich zur
  Wahl stellt. Das sind die Kandidaten ohne die schon abgelehnten und ohne die gerade nicht
  verfügbaren Tools. Enthält das Angebot mehr als ein Tool, zeigt der Client eine Auswahlseite.
  *Im Code:* `Offer`, `activatable()`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 1.
- **Anker**: Eine Angabe, über die sich ein Konto eindeutig wiederfinden lässt, etwa die
  Partnernummer, die Kennung eines Online-Ausweises oder eine bestätigte E-Mail-Adresse. Derselbe
  Wert kann nie zu zwei Konten gehören. Ein Konto wird nur über Anker gefunden, nie über Name und
  Geburtsdatum.
  *Im Code:* `AccountAnchor`, Tabelle `account.anchor`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6,
  [ADR-19](../adr/ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md).
- **Anmeldeprotokoll**: Das Anmeldeprotokoll hält für jedes Konto fest, wann sich jemand angemeldet
  und abgemeldet hat, mit welchem Verfahren und auf welchem Niveau. Dazu kommen jeder Fehlversuch
  und jede Sperre. Damit lässt sich klären, ob jemand Fremdes das Konto benutzt hat. Weil das
  Protokoll zeigt, wie sich eine Person verhält, wird es nur sechs Monate aufbewahrt und mit dem
  Konto gelöscht. Wer sich mit einem Einmalkennwort anmeldet, hat kein Konto. Sein Eintrag wird
  dann der Einladung zugeordnet.
  *Im Code:* `SignInLog`, Tabelle `account.sign_in_log`.
  *Mehr dazu:* [ADR-39](../adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md), Nachtrag.
- **Anmeldeverfahren**, kurz **Verfahren**: Ein Anmeldeverfahren ist etwas, das im Konto eingerichtet
  ist und mit dem sich der Inhaber später anmelden kann. Beispiele sind ein Passwort, eine
  Handynummer für SMS oder ein Schlüssel auf dem Smartphone. Ein Verfahren wird nie gelöscht, nur
  abgeschaltet. So bleibt nachvollziehbar, was mit ihm bewiesen wurde.
  *Im Code:* `AccountAuthMethod`. Im Code heißt ein Verfahren oft kurz „Methode“ (`method`, etwa
  `sms`, `password`, `device`, `kobil`). Zu einem Verfahren gehören meist zwei Tools: `enroll-…` zum
  Einrichten und `auth-…` zum Anmelden.
  *Mehr dazu:* [03-tool-architektur](../03-tool-architektur.md) Abschnitt 1. Zu jedem Verfahren
  gibt es eine eigene Seite unter [verfahren/](../verfahren/README.md).
- **Anmeldung**: Ein Nutzer mit Konto beweist, dass er es ist, und erreicht dabei ein bestimmtes
  Sicherheitsniveau. In der App meldet er sich meist über sein verknüpftes Gerät an (Schnellzugang),
  auf der Website über Keycloak. Wer kein verknüpftes Gerät hat, meldet sich über seine
  E-Mail-Adresse an.
  *Im Code:* Intents `FAST_ACCESS`, `LOOKUP_LOGIN`, `WEB_SELECT_METHOD`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 2.
- **App-Kanal**: Die Verbindung der Smartphone-App zum Orchestrator. Die App weist sich bei jeder
  Anfrage mit einem Schlüssel aus, der das Gerät nie verlässt (DPoP). Ihre Tokens bekommt sie vom
  Orchestrator. Das Gegenstück ist der Web-Kanal.
  *Im Code:* `ChannelType.APP`.
  *Mehr dazu:* [05-api](../05-api.md) Abschnitt 3a.
- **Auswahlseite**: Die Seite, auf der der Nutzer zwischen mehreren angebotenen Tools wählt, etwa
  „SMS oder Passwort“. Enthält das Angebot nur ein Tool, entfällt die Seite.
  *Im Code:* Schritt `selectMethod`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 1.
- **Ausweichzustand** und **Ausweichkette**: Ein Ausweichzustand ist ein Zustand der Journey, in dem
  Ablehnen zum nächsten, etwas aufwendigeren Weg führt. Mehrere solche Zustände hintereinander
  bilden eine Ausweichkette, vom bequemsten Weg bis zum aufwendigsten. So ist der Schnellzugang
  aufgebaut: erst das Gerät, dann andere Verfahren, zuletzt eine neue Identifizierung. Das Gegenstück
  ist der Pflichtzustand (siehe Pflichten).
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 3.
- **Authentisierung und Authentifizierung**: Zwei Wörter für die beiden Seiten einer Anmeldung, die
  das externe Glossar trennt. Der Nutzer *authentisiert* sich, indem er einen Beweis liefert. Der
  Server *authentifiziert* ihn, indem er den Beweis prüft. Dieses Projekt sagt für beides
  „Authentifizierung“ oder einfach „Anmeldung“. Das ist eine bewusste Vereinfachung.
  *Mehr dazu:* [Abgleich mit dem externen Glossar](abgleich-externes-glossar.md).
- **AuthPolicy**: Die Regeln, nach denen der Orchestrator aus den Nachweisen einer Sitzung das
  Sicherheitsniveau berechnet. Sie legen fest, wie viel jedes Verfahren zählt, wann zwei Verfahren
  zusammen mehr ergeben und welche Verfahren als Nächstes helfen würden. Nur an dieser Stelle wird
  das Niveau berechnet.
  *Im Code:* `AuthPolicy`, `DefaultAuthPolicy`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 4.

## B

- **Bestätigen**: Ein Tool prüft, ob eine Angabe stimmt, und meldet sie danach als geprüft. Es
  schickt dazu zum Beispiel einen Code an die E-Mail-Adresse. Das ist weder eine Anmeldung noch eine
  Identifizierung und erhöht das Sicherheitsniveau nicht. Eine Angabe zu bestätigen und sie einer
  Person zuzuordnen sind zwei getrennte Schritte.
  *Im Code:* Tool-Rolle `ATTESTATION`, Ergebnis `ToolOutcome.Completed.Attested`.
  *Mehr dazu:* [ADR-17](../adr/ADR-017-adresse-bestaetigen-und-e-mail-login-einrichten-sind.md),
  [ADR-18](../adr/ADR-018-bestaetigen-und-zuordnen-sind-zwei-akte.md).
- **Bestätigungscode**: Ein kurzer Code, der beim QR-Login den Browser freischaltet. Die App zeigt
  ihn nach der Freigabe an, und der Nutzer tippt ihn im Browser ein. Erst damit ist der Browser
  angemeldet. So kann niemand einen fremden Browser freigeben, ohne selbst davor zu sitzen.
  *Mehr dazu:* [Verfahren `qr`](../verfahren/qr.md).
- **Bindungsschlüssel**: Das Merkmal, an dem der Orchestrator erkennt, zu welchem Kanal eine Anfrage
  gehört. In der App ist es der Fingerabdruck des DPoP-Schlüssels. Im Web-Kanal ist es die
  Kanalbindung, die Keycloak in seine signierte Anfrage schreibt.
  *Im Code:* `binding_key_ref`, `@BindingKey`.
  *Mehr dazu:* [09-dpop](../09-dpop.md) Abschnitt 3.

## D

- **Demomodus**: Ein Schalter, der alles einschaltet, was nur zum Vorführen gedacht ist. Dazu
  gehören simulierte Fremdsysteme mit Beispieldaten, eine Funktion zum Zurücksetzen und sichtbare
  Codes. Im echten Betrieb ist der Demomodus aus. Dann weigert sich die Anwendung zu starten, solange
  unsichere Einstellungen gesetzt sind.
  *Im Code:* `demo.mode`, `DemoMode`, `@DemoSurface`, `ProductionModeCheck`.
  *Mehr dazu:* [ADR-28](../adr/ADR-028-demo-werte-abschaltbar.md).
- **DPoP**: Ein Standard (RFC 9449), mit dem eine Anfrage belegt, dass sie vom Besitzer eines
  bestimmten Schlüssels kommt. Wer eine Anfrage abfängt, kann sie deshalb nicht nachmachen.
  *Mehr dazu:* [09-dpop](../09-dpop.md).
- **DPoP-Proof**: Der Beleg, den die App bei jeder einzelnen Anfrage mitschickt, signiert mit ihrem
  Schlüssel. Er gilt nur für diese eine Anfrage und nur kurze Zeit. Nicht zu verwechseln mit dem
  Nachweis, der beschreibt, was die Sitzung insgesamt bewiesen hat.
  *Mehr dazu:* [09-dpop](../09-dpop.md) Abschnitt 1.

## E

- **Eingerichtet** und **im Aufbau**: Die beiden Zustände eines Kontos, je nachdem, ob man sich
  schon darin anmelden kann. Ein Konto ist eingerichtet, sobald es ein Anmeldeverfahren hat, denn
  erst dann ist eine Anmeldung möglich. Vorher ist es im Aufbau, etwa mitten in einer Registrierung.
  Ein eingerichtetes Konto fällt nie in den Aufbau zurück.
  *Im Code:* `AccountProfile.isSetUp`.
  *Mehr dazu:* [ADR-46](../adr/ADR-046-konto-im-aufbau.md).
- **Einladung**: Ein Brief des Personenverzeichnisses, der eine Person zu einem bestimmten Vorgang
  einlädt, etwa zu einer Beitragsrückerstattung. Der Brief enthält ein Einmalkennwort. Damit kann
  sich die Person auf der Website anmelden, auch wenn sie kein Konto hat. Die Einladung gehört dem
  Personenverzeichnis. Der Orchestrator merkt sich nur ihre Kennung.
  *Im Code:* `Invitation`, Tool `auth-invite-lookup`.
  *Mehr dazu:* [ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
- **Einmalkennwort**: Das Kennwort im Einladungsbrief. Zusammen mit der KVNR oder der Partnernummer
  öffnet es den Zugang zu genau einem Vorgang. Trotz des Namens lässt es sich mehrmals benutzen, bis
  die Frist abläuft oder der Vorgang abgeschlossen ist. „Einmal“ heißt hier also „für einen
  Vorgang“. Gespeichert wird nie das Kennwort selbst, nur ein daraus berechneter Wert.
  *Mehr dazu:* [ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
- **Einstiegs-Intent**: siehe Intent.
- **Erneute Identifizierung**: Ein Ausweg, wenn ein Nutzer mit seinen Verfahren das geforderte
  Niveau nicht erreicht. Erreicht keines seiner aktiven Verfahren das Niveau, bietet die Journey an,
  sich noch einmal zu identifizieren. Sie fragt dabei immer erst nach. Die Identifizierung bestätigt
  nur das Konto, das schon bekannt ist, und übernimmt nie ein anderes. Ein Konto, das noch nie
  identifiziert wurde, bekommt auf diesem Weg zum ersten Mal eine Identität.
  *Im Code:* Intent `RE_IDENTIFY`, nur als Sub-Journey.
  *Mehr dazu:* [journeys/re-identify](../journeys/re-identify.md).

## F

- **Faktorart**: siehe Faktortyp.
- **Faktortyp**: Die Art eines Beweises bei der Anmeldung. Es gibt drei Arten: etwas, das man weiß
  (Passwort), etwas, das man hat (Smartphone), und etwas, das man ist (Fingerabdruck). Für das
  Niveau `loa2` braucht es zwei verschiedene Arten. Zwei Verfahren derselben Art reichen nicht.
  *Im Code:* `FactorType` mit `KNOWLEDGE`, `POSSESSION`, `INHERENCE`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 4.
- **Feature-Flag**: Ein Schalter, mit dem der Betreiber ein Verhalten umstellt, während die
  Anwendung läuft, ohne sie neu zu starten. Beispiele:
  - ob die Registrierung mit dem Einrichten eines Verfahrens beginnt statt mit der Identifizierung,
  - ob Keycloak für `loa1` selbst nach dem Passwort fragt.

  *Im Code:* `FeatureFlagService`. Die Schalter, die eine Strategie liest, nennt `JourneyFeatureFlag`
  (`register-enroll-first`). Den Schalter für Keycloak nennt `KeycloakFeatureFlags`
  (`keycloak-loa1-password`).
  *Mehr dazu:* [ADR-42](../adr/ADR-042-loa1-anmeldung-umschalten.md).
- **Freischaltcode**: Ein Code, den die Versicherung per Brief schickt und mit dem sich eine Person
  identifizieren kann. Der Code liegt beim Personenverzeichnis. Der Orchestrator fragt ihn dort ab,
  statt eine eigene Kopie zu halten.
  *Im Code:* Tool `ident-fsc`.
  *Mehr dazu:* [ADR-31](../adr/ADR-031-freischaltcode-liegt-im-fremdsystem.md).
- **Fremdsystem**: Ein System außerhalb des Orchestrators, das seine Daten selbst verwaltet, etwa das
  Personenverzeichnis, der Identifizierungsdienst Nect oder KOBIL. In diesem Projekt sind diese
  Systeme simuliert. Der Orchestrator spricht mit ihnen nur über fest vereinbarte Schnittstellen.
  So lässt sich eine Simulation später durch das echte System ersetzen.
  *Mehr dazu:* [port-vertraege](../port-vertraege.md).

## G

- **Geräte-Proof**: Der Beleg, mit dem die App bei der Anmeldung per Gerät zeigt, dass sie den
  Geräteschlüssel besitzt. Wie der DPoP-Proof gilt er nur für eine einzelne Anfrage.
  *Im Code:* `device-proof+jwt`, `DeviceProofs`, Tool `auth-device`.
  *Mehr dazu:* [Verfahren `device`](../verfahren/device.md).
- **Gerätebindung**: Ein Anmeldeverfahren, das fest an ein bestimmtes Smartphone gebunden ist. Beim
  Einrichten erzeugt das Gerät einen Schlüssel, der es nie verlässt. Bei einer späteren Anmeldung
  beweist die Person mit diesem Schlüssel, dass sie dieses Gerät besitzt. Meist entsperrt der Nutzer
  den Schlüssel mit PIN oder Fingerabdruck. Dann deckt ein einziges Verfahren schon zwei
  Faktortypen ab. In diesem Projekt gibt es zwei solche Verfahren: den Geräteschlüssel der App und
  KOBIL. Nicht zu verwechseln mit der Geräteverknüpfung: Sie erkennt das Gerät nur wieder und zählt
  nicht als Anmeldung.
  *Im Code:* Verfahren `device` (Tools `enroll-device`, `auth-device`) und `kobil` (`enroll-kobil`,
  `auth-kobil`). Ob ein solches Verfahren auf dem anfragenden Gerät nutzbar ist, prüft
  `Tool.usableByCaller`.
  *Mehr dazu:* [09-dpop](../09-dpop.md) Abschnitt 3,
  [Verfahren `device`](../verfahren/device.md) und [`kobil`](../verfahren/kobil.md),
  [ADR-21](../adr/ADR-021-der-kobil-pin-liegt-im-backend-und-das.md).
- **Geräteverknüpfung**: Die Geräteverknüpfung merkt sich, welches Smartphone zu welchem Konto
  gehört. Das Gerät wird an seinem DPoP-Schlüssel erkannt. So erkennt die App ihren Nutzer beim
  nächsten Start wieder. Die Verknüpfung allein ist aber keine Anmeldung. Anmelden muss sich der
  Nutzer trotzdem. Nicht zu verwechseln mit der Gerätebindung, die ein Anmeldeverfahren ist.
  *Im Code:* `DeviceAccountLink`.
  *Mehr dazu:* [ADR-3](../adr/ADR-003-channelsession-bewusst-kurzlebig-geraete-identitaet-in-deviceaccountlink.md).

## I

- **Identifizierung**: Ein Schritt, der feststellt, wer jemand wirklich ist. Dafür gibt es drei
  Identifizierungsverfahren:
  - den Freischaltcode aus dem Brief,
  - den Online-Ausweis,
  - Nect mit Personalausweis, Reisepass oder EUDI-Wallet.

  Danach ist das Konto einer Person im Personenverzeichnis zugeordnet, und das Sicherheitsniveau
  kann steigen. Das externe Glossar nennt ein solches Verfahren „Identifizierungsmittel“.
  *Im Code:* Tool-Rolle `IDENTIFICATION`, Tools `ident-fsc`, `ident-eid`, `ident-nect`.
  *Mehr dazu:* [03-tool-architektur](../03-tool-architektur.md) Abschnitt 1,
  [Abgleich mit dem externen Glossar](abgleich-externes-glossar.md).
- **Identifizierungsverfahren**: siehe Identifizierung.
- **Intent**: Das Anliegen, mit dem ein Nutzer kommt, etwa sich registrieren, sich anmelden oder
  sein Niveau erhöhen. Zu jedem Intent gehört ein fester Ablauf, den der Orchestrator steuert. Mit
  einigen Intents beginnt eine neue Sitzung. Sie heißen Einstiegs-Intents, etwa Schnellzugang oder
  Registrierung. Andere Intents, etwa Konto löschen, laufen nur in einer Sitzung, in der der Nutzer
  schon angemeldet ist.
  *Im Code:* `AuthIntent`, etwa `REGISTER`, `FAST_ACCESS`, `STEP_UP` und `WEB_SELECT_METHOD`
  (Anmeldung auf der Website).
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 2.
- **Interessent**, **Partner**, **Versicherter**: Die drei Rollen, die ein Konto haben kann. Ein
  Interessent ist noch keiner Person im Personenverzeichnis zugeordnet. Ein Partner ist der
  Versicherung als Person bekannt, er hat also eine Partnernummer, ist aber nicht bei ihr versichert.
  Ein Versicherter hat außerdem eine Mitgliedsnummer. Die Rolle wird nicht gespeichert, sondern aus
  den Ankern abgeleitet.
  *Im Code:* Der Interessent heißt englisch `prospect`.
  *Mehr dazu:* [ADR-34](../adr/ADR-034-personenverzeichnis-meldet-aenderungen.md).
- **Invarianten**: Regeln, auf die sich der Kern der Anwendung jederzeit verlassen kann, etwa „ein
  Kanal gehört höchstens einem Subjekt“. Im Register der Invarianten steht zu jeder Regel, wodurch
  sie gesichert ist: durch den Typ, durch eine Regel in der Datenbank oder durch einen Test.
  *Mehr dazu:* [invarianten](../invarianten.md).

## J

- **Journey**: Ein laufender Ablauf zu einem Intent, etwa eine Registrierung von der Identifizierung
  bis zum fertigen Konto. Eine Journey besteht aus mehreren Schritten und nutzt dafür ein oder
  mehrere Tools. Auf einem Kanal läuft immer genau eine Journey.
  *Im Code:* `AuthJourney`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 3, Diagramme unter
  [journeys](../journeys/).
- **Journey-Protokoll**: Das Journey-Protokoll hält jeden Schritt einer Journey fest: welches Tool
  angeboten, gewählt oder abgelehnt wurde und welches Niveau am Ende erreicht war. Damit lässt sich
  später beantworten, warum ein Nutzer einen bestimmten Weg genommen hat.
  *Im Code:* `JourneyTraceEntry`, in der Oberfläche „Journey-Trace“.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 3.

## K

- **Kanal**: Eine Verbindung eines Nutzers zum Orchestrator, entweder über die App oder über die
  Website. Ein Kanal besteht nur kurze Zeit. Ist der Nutzer angemeldet, endet der Kanal spätestens
  mit seiner Sitzung in Keycloak.
  *Im Code:* `ChannelSession`, Zustände `ANONYMOUS`, `AUTHENTICATED`, `STEP_UP_REQUIRED`,
  `STEP_UP_IN_PROGRESS`, `LOGGED_OUT`, `EXPIRED` (`REGISTERING` wird nur angezeigt).
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 3,
  [ADR-43](../adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md).
- **Kanalbindung**: Eine Kennung, mit der Keycloak jede Anfrage an den Orchestrator einem bestimmten
  Anmeldevorgang zuordnet. Keycloak signiert sie mit. So kann niemand eine Anfrage auf einen fremden
  Kanal umlenken.
  *Im Code:* `channel_binding`, `channelBinding`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 1.
- **Kandidaten**: Die Tools, die eine Journey dem Nutzer an einer bestimmten Stelle zur Auswahl
  anbieten kann, etwa „SMS oder Passwort“. Welche Tools das sind, ergibt sich aus der Art der Tools,
  nicht aus ihren Namen. Ein neues Tool erscheint so von selbst an der richtigen Stelle.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 1.
- **Kartendaten**: Die Daten, die beim Online-Ausweis die Karte selbst liefert: Name, Geburtsdatum,
  Anschrift und ein Pseudonym, das an die Karte gebunden ist. Der Nutzer tippt sie nicht ein. Erst
  nachdem die Karte sie geliefert hat, fragt das Tool nach der PIN.
  *Mehr dazu:* [Verfahren `eid`](../verfahren/eid.md).
- **Keycloak**: Ein verbreitetes Produkt für Anmeldung und Zugangsverwaltung. In diesem Projekt führt
  es auf der Website die Anmeldung und stellt die Tokens aus. Welche Schritte nötig sind, fragt
  Keycloak beim Orchestrator. Keycloak hält keine eigene Kopie der Konten, sondern liest sie bei
  Bedarf beim Orchestrator nach.
  *Mehr dazu:* [05-api](../05-api.md) Abschnitt 3b,
  [ADR-38](../adr/ADR-038-keycloak-liest-konten.md).
- **Keycloak-Sitzung**: Die Sitzung, die Keycloak für einen angemeldeten Nutzer führt. Sie endet nach
  30 Minuten ohne Aktivität, spätestens aber nach 10 Stunden. Auch die Anmeldung in der App hat eine
  solche Sitzung. Der Kanal besteht nie länger als sie.
  *Im Code:* in Keycloak `UserSessionModel`, beim Orchestrator `keycloakSessionId`.
  *Mehr dazu:* [ADR-43](../adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md),
  [07-betrieb](../07-betrieb.md) Abschnitt 3.
- **KOBIL**: Ein externer Anbieter einer App, die als Anmeldeverfahren mit Gerätebindung dient. In
  diesem Projekt ist KOBIL simuliert. Die PIN für das KOBIL-Verfahren verwahrt der Orchestrator
  selbst. Der Nutzer gibt sie nie ein.
  *Im Code:* Tools `enroll-kobil`, `auth-kobil`.
  *Mehr dazu:* [Verfahren `kobil`](../verfahren/kobil.md),
  [ADR-21](../adr/ADR-021-der-kobil-pin-liegt-im-backend-und-das.md).
- **Konto**: Das Konto ist das, worin sich ein Nutzer anmeldet. Es enthält seine Anmeldeverfahren,
  seine Angaben und seine Anker. Eigene Stammdaten wie Name oder Anschrift hält es nicht, die liegen
  im Personenverzeichnis. Ein Konto kann einer Person zugeordnet sein. Ist es das nicht, gehört es
  einem Interessenten.
  *Im Code:* `Account`, Tabelle `account.account`. Die Lesesicht für den Orchestrator heißt
  `AccountProfile`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6.
- **Korrelation**: Die Korrelation ordnet eine schon festgestellte Identität einem Datensatz im
  Personenverzeichnis zu, etwa über die KVNR. Sie beweist selbst nichts und erhöht das Niveau nicht.
  *Im Code:* Tool-Rolle `CORRELATION`, Tool `ident-kvnr`.
  *Mehr dazu:* [ADR-18](../adr/ADR-018-bestaetigen-und-zuordnen-sind-zwei-akte.md).
- **KVNR**, die Krankenversichertennummer: Die Nummer auf der Gesundheitskarte. Nur Versicherte
  haben eine. Eigentlich bleibt ihr fester Teil ein Leben lang gleich. Im Personenverzeichnis lässt
  sie sich trotzdem ändern, weil eine KVNR in manchen Fällen versehentlich doppelt vergeben wurde.
  Ein Konto wird nicht über die KVNR gefunden.
  *Im Code:* Tool `ident-kvnr`. Im Konto ist die KVNR eine Angabe, kein Anker.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6,
  [ADR-34](../adr/ADR-034-personenverzeichnis-meldet-aenderungen.md).

## L

- **Lebenszyklus einer Journey**: Der Lebenszyklus sagt, ob eine Journey gerade läuft, pausiert
  (weil eine Sub-Journey läuft), fertig ist, abgebrochen wurde oder gescheitert ist. Anders als der
  Zustand sagt er nichts darüber, an welcher Stelle des Ablaufs die Journey steht.
  *Im Code:* `JourneyLifecycle`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 3.

## M

- **Mehrstufige Authentifizierung** und **MFA**: Zwei Arten, mit mehr als einem Faktor anzumelden,
  die das Projekt bewusst unterscheidet. Mehrstufig heißt: zwei Verfahren, die einzeln geprüft
  werden, etwa SMS und Passwort. MFA heißt in diesem Projekt nur: ein einziges Verfahren mit zwei
  verknüpften Faktoren, etwa ein Geräteschlüssel, der mit einer PIN entsperrt wird. Beides kann
  `loa2` erreichen.
  *Mehr dazu:* [Abgleich](abgleich-externes-glossar.md),
  [04-orchestrierung](../04-orchestrierung.md) Abschnitt 4.
- **Mengenbegrenzung**: Eine Mengenbegrenzung legt fest, wie oft etwas in einem Zeitraum passieren
  darf, etwa wie viele SMS an dieselbe Nummer gehen. Sie erschwert Missbrauch und begrenzt Kosten.
  Anders als eine Sperre richtet sie sich nicht gegen das Raten eines Passworts, sondern gegen die
  Menge. Wer die Grenze überschreitet, bekommt die Antwort „zu viele Anfragen“ (`429`). Die Grenzen
  legt jedes Modul selbst fest. Gezählt wird an einer zentralen Stelle.
  *Im Code:* `RateLimit`, `RateLimits` (Paket `tool_api.ratelimit`). Das Zählwerk ist der
  `RateLimitCounter`, mit einer Zeile `RateLimitRecord` je Bereich (`RateLimitScope`) in der
  Tabelle `rate_limit`. Metrik `identity.ratelimit.blocked`.
  *Mehr dazu:* [ADR-44](../adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md),
  [07-betrieb](../07-betrieb.md) Abschnitt 4.
- **Methode**: Das Wort, mit dem der Code ein Anmeldeverfahren bezeichnet (`method`). Siehe
  Anmeldeverfahren.
- **Mindestniveau für einen Anker**: Das Niveau, das eine Sitzung mindestens haben muss, um einen
  Anker zu setzen oder zu ändern. Ändern verlangt immer `loa2`, denn wer einen Anker auf einen
  anderen Wert ändern kann, kann damit ein fremdes Konto übernehmen. Nur eine E-Mail-Adresse darf
  schon mit `loa1` zum ersten Mal gesetzt werden, weil eine Registrierung sie früh braucht.
  *Im Code:* `AnchorAcrFloor` mit `establish` und `replace`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6.
- **Mitgliedsnummer**, auch **Versicherungsnummer** genannt: Die achtstellige Nummer, unter der ein
  Versicherter bei der Versicherung geführt wird. Nur Versicherte haben eine. Sie kann sich ändern
  oder wegfallen.
  *Im Code:* `MemberNumber`, Anker `MEMBER_NUMBER`. Im Token und in Keycloak heißt sie weiterhin
  `versnr`, so wie im Vokabular des Personenverzeichnisses.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6,
  [ADR-34](../adr/ADR-034-personenverzeichnis-meldet-aenderungen.md).
- **Modul** und **Tool-Modul**: Die Anwendung ist in Module aufgeteilt. Module reden nur über fest
  vereinbarte Schnittstellen miteinander, und ein Test sorgt dafür, dass diese Grenzen eingehalten
  werden. Ein Tool-Modul enthält die Tools eines Verfahrens, etwa alles zu SMS. Den Orchestrator
  kennt es nur über die Schnittstelle `tool_api`.
  *Im Code:* Pakete unter `tools/` (etwa `tools/auth_sms`), je Modul eine Klasse mit
  `@ApplicationModule` (etwa `SmsToolModule`).
  *Mehr dazu:* [08-projektrahmen](../08-projektrahmen.md) Abschnitt 3.

## N

- **Nachweis**: Was ein Nutzer in seiner laufenden Sitzung bewiesen hat, etwa „Passwort richtig vor
  5 Minuten, SMS-Code richtig vor 2 Minuten“. Daraus berechnet der Orchestrator das
  Sicherheitsniveau. Ein Nachweis veraltet: Für ein Niveau über `loa1` zählen nur Beweise aus den
  letzten 30 Minuten.
  *Im Code:* `SessionEvidence` mit je einem `MethodEvidence` pro Verfahren in `methods`, gespeichert
  als `SessionEvidenceRecord`. Die Frist heißt `identity.policy.loa2-max-age`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 4.
- **Nect**: Ein externer Dienst, der Personen anhand von Personalausweis, Reisepass oder EUDI-Wallet
  identifiziert. Dabei vergleicht er auch ein Selfie mit dem Passbild. Der Nutzer wird dafür zu Nect
  weitergeleitet und kommt danach zurück. In diesem Projekt ist Nect simuliert.
  *Im Code:* Tool `ident-nect`.
  *Mehr dazu:* [Verfahren `nect`](../verfahren/nect.md),
  [ADR-47](../adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md).
- **`next`**: Die Angabe, mit der der Orchestrator dem Client in jeder Antwort sagt, welcher Schritt
  als Nächstes kommt. Der Client folgt dieser Angabe und entscheidet selbst nichts. So liegt die
  ganze Ablauflogik an einer Stelle.
  *Mehr dazu:* [ADR-6](../adr/ADR-006-next-als-reine-adresse-feste-routing-tabelle-statt.md).
- **Niveau**, ausführlich **Sicherheitsniveau**: Das Niveau sagt, wie sehr einer Anmeldung vertraut
  wird. Es gibt drei Stufen:
  - `loa1` heißt: ein Verfahren, etwa ein Passwort.
  - `loa2` heißt: zwei Verfahren verschiedener Art oder eine Identifizierung.
  - `loa3` erreicht nur eine starke Identifizierung, etwa mit dem Online-Ausweis.

  Manche Funktionen verlangen ein bestimmtes Niveau. Anmeldeverfahren verwalten und das Konto
  löschen verlangen zum Beispiel `loa2`, bei einem nie identifizierten Konto `loa1`. Die Skala folgt
  der Richtlinie NIST 800-63. Sie liegt nah an den eIDAS-Niveaus niedrig, substanziell und hoch, ist
  ihnen aber nicht gleich.
  *Im Code:* `acr`, Werte `loa1`, `loa2`, `loa3`. Die Schwelle für Verwalten und Löschen heißt
  `selfServiceAcrFloor`.
  *Mehr dazu:* [01-ueberblick](../01-ueberblick.md) Abschnitt 9.

## O

- **Obergrenzen eines Verfahrens**: Grenzen dafür, wie viel Vertrauen ein Verfahren höchstens
  liefern kann. Ein Verfahren liefert nie mehr Vertrauen, als es technisch hergibt. Und es liefert
  nie mehr, als die Sitzung hatte, in der es eingerichtet wurde. So kann niemand in einer schwach
  gesicherten Sitzung ein Verfahren einrichten und sich damit später ein höheres Niveau verschaffen.
  *Im Code:* `maxAcr`, `enrolledUnderAcr`.
  *Mehr dazu:* [ADR-5](../adr/ADR-005-drei-obergrenzen-fuer-das-sicherheitsniveau.md).
- **Online-Ausweis** (eID): Die Ausweisfunktion des Personalausweises. Die Karte liefert die
  Kartendaten, und der Nutzer bestätigt mit seiner PIN. Das ist die stärkste Identifizierung im
  Projekt und führt zu `loa3`. Außer ihr erreicht das nur Nect mit Personalausweis oder EUDI-Wallet.
  In diesem Projekt ist der Online-Ausweis simuliert.
  *Im Code:* Tool `ident-eid`.
  *Mehr dazu:* [Verfahren `eid`](../verfahren/eid.md).
- **Orchestrator**: Der Server dieses Projekts. Er entscheidet, welche Schritte ein Nutzer bei
  Registrierung und Anmeldung durchläuft. Außerdem ist er die einzige Stelle, die das
  Sicherheitsniveau berechnet. Die einzelnen Verfahren sind eigene Module, die er über eine feste
  Schnittstelle einbindet.
  *Im Code:* Die Schnittstelle heißt `tool_api`.
  *Mehr dazu:* [01-ueberblick](../01-ueberblick.md) Abschnitt 2.

## P

- **Pairing-Code**: Eine kurze Zeichenfolge, die der Browser beim QR-Login neben dem QR-Code zeigt.
  Wer den QR-Code nicht scannen kann, tippt stattdessen diesen Code in der App ein.
  *Mehr dazu:* [Verfahren `qr`](../verfahren/qr.md).
- **Partnernummer**: Die Nummer, unter der eine Person im Personenverzeichnis geführt wird. Sie
  besteht aus einem `P` und neun Ziffern. Jede Person hat eine, und sie ändert sich nie.
  *Im Code:* `PartnerNumber` für das Format. Als Anker heißt sie `PERSON_ID`, in Feldern und Tokens
  `personId`, weil sie die Person bezeichnet.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6.
- **Peer-Auth**: Die Art, wie sich Keycloak und Orchestrator gegenseitig ausweisen. Jede Anfrage von
  Keycloak ist signiert und nennt die Kanalbindung. Jede Antwort des Orchestrators auf eine gültige
  Anfrage ist ebenfalls signiert und an genau diese Anfrage gebunden. So kann sich niemand
  dazwischenschalten.
  *Mehr dazu:* [ADR-7](../adr/ADR-007-web-kanal-ohne-mtls-signierte-request-assertion-statt.md).
- **Person**: Ein Mensch, wie ihn das Personenverzeichnis kennt, mit Partnernummer und Stammdaten.
  Ein Konto kann einer Person zugeordnet sein. Diese Zuordnung entsteht durch eine Identifizierung.
  *Im Code:* `personId`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6.
- **Personenverzeichnis**: Das System, in dem die Versicherung die Stammdaten ihrer Personen führt:
  Partnernummer, Name, Geburtsdatum, Anschrift und bei Versicherten auch Mitgliedsnummer und KVNR.
  Es verschickt Freischaltcodes und Einladungen und meldet Änderungen an den Orchestrator. In diesem
  Projekt ist es simuliert.
  *Mehr dazu:* [ADR-34](../adr/ADR-034-personenverzeichnis-meldet-aenderungen.md).
- **Pflichten** und **Pflichtzustand**: Pflichten sind Dinge, die eine Journey noch verlangt, bevor
  sie fertig ist, etwa eine bestätigte E-Mail-Adresse oder ein zweites Verfahren anderer Art. Sie
  gehören zur Journey, nicht zum Konto. Ein Konto kann also schon benutzbar sein, obwohl bei einer
  Registrierung noch etwas offen ist. Solange eine Pflicht offen ist, steht die Journey in einem
  Pflichtzustand. Lehnt der Nutzer dort ein Tool ab, kommt das ganze Angebot zurück, auch das
  abgelehnte Tool. Weiter geht es erst, wenn die Pflicht erfüllt ist. Das Gegenstück ist der
  Ausweichzustand.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitte 1 und 8.
- **Pflichtzustand**: siehe Pflichten.
- **Port**: Eine fest vereinbarte Schnittstelle, über die der Orchestrator ein Fremdsystem fragt,
  etwa „Ist dieser Freischaltcode gültig?“. Der Orchestrator kennt nur den Port, nicht das System
  dahinter. So lässt sich eine Simulation gegen das echte System austauschen.
  *Mehr dazu:* [port-vertraege](../port-vertraege.md).

## Q

- **QR-Login**: Eine Anmeldung auf der Website mit Hilfe der App. Der Browser zeigt einen QR-Code.
  Der Nutzer scannt ihn mit der App, in der er schon angemeldet ist, und gibt die Anmeldung dort
  frei. Danach tippt er den Bestätigungscode im Browser ein.
  *Im Code:* Tools `auth-qr`, `auth-qr-lookup`. Die Freigabe in der App heißt `approve-qr`, der
  Intent dazu `CONFIRM_PEER_LOGIN`.
  *Mehr dazu:* [Verfahren `qr`](../verfahren/qr.md).
- **Quelle**: Die Quelle einer Angabe sagt, wer sie geliefert hat und für ihre Richtigkeit
  verantwortlich ist: das Personenverzeichnis, ein Prüfverfahren wie der Online-Ausweis oder der
  Nutzer selbst. Aus der Quelle folgt, wie verlässlich
  die Angabe ist. Auch ein Widerruf nennt seine Quelle, etwa „vom Personenverzeichnis gemeldet“.
  Nicht zu verwechseln mit der Quelle eines Nachweises.
  *Im Code:* `ClaimSource`. Beim Widerruf `RetractionSource`, Spalte `claim_source`.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6,
  [ADR-12](../adr/ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md).
- **Quelle eines Nachweises**: Sie sagt, wer ein Verfahren in der Sitzung geprüft hat. Das ist
  entweder der Orchestrator mit einem eigenen Tool oder Keycloak mit einem eigenen Anmeldeschritt,
  etwa seinem Passwortformular. Ein Nachweis des Orchestrators zählt nie weniger als eine Meldung
  von Keycloak. Nicht zu verwechseln mit der Quelle einer Angabe.
  *Im Code:* `AmrSource` mit `orchestrator` und `kc`.
  *Mehr dazu:* [05-api](../05-api.md) Abschnitt 3b.

## R

- **Realm**: Ein abgeschlossener Bereich in Keycloak mit eigenen Nutzern, Clients und
  Anmeldeabläufen. Dieses Projekt legt beim Start ein Realm namens „Demo“ an. Seine Einstellungen
  hält es in Migrationen fest, also in Skripten, die die Einstellungen Schritt für Schritt anlegen.
  *Im Code:* Modul `keycloak-migrations`.
  *Mehr dazu:* [ADR-25](../adr/ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md).
- **Registrierung**: Ein neuer Nutzer legt ein Konto an. Normalerweise identifiziert er sich zuerst.
  Dann bestätigt er seine E-Mail-Adresse und richtet so lange Verfahren ein, bis das verlangte Niveau
  erreicht ist. Ein Schalter lässt die Registrierung auch mit dem Einrichten eines Verfahrens
  beginnen (Experiment „Enrollment zuerst“). Die Identifizierung wird dann nur noch angeboten.
  *Im Code:* Intent `REGISTER`, Varianten `RegisterStrategy` und `RegisterEnrollFirstStrategy`.
  *Mehr dazu:* [journeys/register](../journeys/register.md).
- **RestoreData**: Ein signierter Datensatz, den Keycloak aufbewahrt. Er enthält, was ein Nutzer in
  einem früheren Anmeldevorgang derselben Sitzung schon bewiesen hat. Beim nächsten Vorgang gibt
  Keycloak den Datensatz an den Orchestrator zurück, damit der Nutzer nicht alles noch einmal
  beweisen muss. Der Datensatz sagt auch, wann der Beweis erbracht wurde. Ältere Beweise zählen nur
  noch für `loa1`.
  *Mehr dazu:* [05-api](../05-api.md) Abschnitt 3b.

## S

- **Schnellzugang**: Der übliche Weg, sich in der App anzumelden. Er soll den Nutzer auf diesem Gerät
  so schnell wie möglich anmelden und dafür sorgen, dass es auch beim nächsten Mal klappt. Er
  probiert zuerst das Bequemste und weicht dann Schritt für Schritt auf aufwendigere Wege aus.
  *Im Code:* Intent `FAST_ACCESS`.
  *Mehr dazu:* [journeys/fast-access](../journeys/fast-access.md).
- **Schritt**: Ein einzelner Bildschirm innerhalb eines Tools, etwa „TAN eingeben“.
  *Im Code:* `next.step`.
- **Sperre**: Eine Sperre verhindert, dass jemand Passwörter oder Codes durchprobiert. Nach fünf
  falschen Versuchen wird ein Konto oder eine Person für 15 Minuten gesperrt. Wo das Konto schon
  bekannt ist, sagt die Antwort „gesperrt“ (`423`). Wo erst nach dem Konto gesucht wird, sieht die
  Antwort aus wie bei einer falschen Eingabe. Sonst würde sie verraten, dass es das Konto gibt.
  *Im Code:* `AccountLockoutService`, `PersonLockoutService`, gezählt vom `RateLimitCounter`.
  *Mehr dazu:* [ADR-44](../adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md),
  [07-betrieb](../07-betrieb.md) Abschnitt 4.
- **Standardprofil**: Die Betriebsart, in der der Orchestrator ohne Keycloak läuft, etwa beim
  Entwickeln. Dann gibt es nur den App-Kanal, und die Tokens stellt der Orchestrator selbst aus. Mit
  dem Profil `keycloak` kommen Keycloak und der Web-Kanal dazu.
  *Mehr dazu:* [13-ausfuehren](../13-ausfuehren.md).
- **Step-up**: Ein schon angemeldeter Nutzer beweist zusätzlich etwas, um ein höheres Niveau zu
  erreichen, etwa bevor er sein Konto löschen darf. Er bleibt dabei in derselben Sitzung. Ein
  Vorgangszugang lässt sich auf diese Weise nicht aufwerten.
  *Im Code:* Intent `STEP_UP`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 2.
- **Strategie**: Die Regeln eines Intents. Eine Strategie bekommt den aktuellen Zustand und das, was
  gerade passiert ist, und entscheidet, wie es weitergeht. Sie ändert selbst keine Daten, sondern
  beschreibt nur den Übergang.
  *Im Code:* `IntentStrategy`, etwa `RegisterStrategy`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 8.
- **Stufe einer Angabe**: Die Stufe sagt, wie verlässlich eine Angabe ist. Es gibt drei Stufen:
  - *belegt*: Die Angabe stammt aus den Stammdaten der Versicherung.
  - *nachgewiesen*: Ein Verfahren hat die Angabe geprüft.
  - *behauptet*: Der Nutzer hat die Angabe nur selbst eingegeben.

  Gibt es mehrere Werte, gilt der verlässlichste. Bei Gleichstand gilt der jüngste.
  *Im Code:* `ClaimTrust` mit `AUTHORITATIVE`, `PROVEN`, `SELF_REPORTED`. Das externe Glossar sagt
  für die ersten beiden Stufen „bescheinigt“, für die letzte „unbescheinigt“.
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 6.
- **Sub-Journey**: Eine Journey, die eine andere Journey unterbricht und danach zu ihr zurückkehrt.
  Ein Beispiel: Wer seine Anmeldeverfahren verwalten will, muss zwischendurch einen Step-up machen.
  Danach geht es an derselben Stelle weiter.
  *Im Code:* `Transition.RequireSubJourney`. Die unterbrochene Journey ist so lange `SUSPENDED`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 7.
- **Subjekt**: Das Subjekt ist das, dem ein angemeldeter Kanal gehört: ein Konto oder, beim
  Vorgangszugang, eine Einladung. Ein Kanal gehört nie beiden, und sein Subjekt wechselt nie
  unbemerkt.
  *Im Code:* `Subject`. Im Austausch mit Keycloak heißt es `authData.subject`.
  *Mehr dazu:* [ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).

## T

- **Token**: Ein signierter Beleg über die Anmeldung, den ein Nutzer danach bei Fachdiensten
  vorlegt. Er nennt das Konto oder die Einladung, das Niveau (`acr`) und die Verfahren (`amr`).
  Von den Attributen des Nutzers enthält das AccessToken nur Partnernummer (`person_id`) und
  Mitgliedsnummer (`versnr`). Alle übrigen, etwa Name, E-Mail-Adresse und Geburtsdatum, stehen nur
  im ID-Token.
  Ein Token gilt nur kurz. Ein Refresh-Token holt einen neuen Token, solange die Sitzung läuft.
  *Mehr dazu:* [05-api](../05-api.md) Abschnitt 3a,
  [ADR-9](../adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md).
- **Tokens der App**: Die Tokens, mit denen die angemeldete App andere Dienste aufrufen kann. Sie
  beruhen auf dem, was der Nutzer bewiesen hat, und sind an seine Sitzung in Keycloak gebunden.
  *Im Code:* `AppTokenSession`. Ausgegeben und verlängert werden sie vom `AppTokenIssuer`.
  *Mehr dazu:* [ADR-9](../adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md).
- **Tool**: Ein abgeschlossener Arbeitsschritt, den der Nutzer durchläuft, etwa „SMS einrichten“,
  „mit Passwort anmelden“ oder „mit dem Online-Ausweis identifizieren“. Jedes Tool beschreibt selbst,
  was es kann:
  - welches Verfahren es betrifft,
  - welche Rolle es hat,
  - welche Faktortypen es beweist,
  - welches Niveau es höchstens liefert,
  - was vorher erfüllt sein muss.

  Aus diesen Beschreibungen stellt der Orchestrator das Angebot zusammen.
  *Im Code:* `toolId` (etwa `enroll-sms`). Die Beschreibung heißt `Tool`, das Verfahren mit allen
  seinen Tools `ToolModule`.
  *Mehr dazu:* [03-tool-architektur](../03-tool-architektur.md) Abschnitte 1 und 2.
- **Tool-Beschreibung**: siehe Tool.
- **Tool-Durchlauf**: Ein einmal gestartetes Tool, das oft nur wenige Minuten besteht, etwa das
  Warten auf eine eingegebene TAN. Ein Durchlauf hat eine eigene Kennung (eine UUID). Sie ist nicht
  dieselbe wie die Kennung des Tools selbst.
  *Im Code:* `ToolSession`, `toolSessionId` (nicht `toolId`).
  *Mehr dazu:* [02-domaenenmodell](../02-domaenenmodell.md) Abschnitt 1.
- **Tool-Rolle**: Die Rolle sagt, was ein Tool fachlich tut. Mögliche Rollen sind:
  - identifizieren,
  - zuordnen,
  - ein Verfahren einrichten,
  - ein bekanntes Konto anmelden,
  - ein Konto anhand der Eingabe suchen und anmelden,
  - eine Anmeldung in einem anderen Kanal freigeben,
  - eine Angabe bestätigen.

  Aus der Rolle folgt, ob und wie ein Ergebnis das Sicherheitsniveau erhöht.
  *Im Code:* `ToolRole` mit `IDENTIFICATION`, `CORRELATION`, `ENROLLMENT`, `KNOWN_ACCOUNT_AUTH`,
  `ACCOUNT_LOOKUP_AUTH`, `PEER_APPROVAL`, `ATTESTATION`.
  *Mehr dazu:* [03-tool-architektur](../03-tool-architektur.md) Abschnitte 1 und 4.
- **Tool-Sperre**: Eine Einstellung, mit der der Betreiber ein Tool für den App-Kanal oder den
  Web-Kanal abschaltet. Dort legt er auch die Reihenfolge fest, in der Tools angeboten werden. Ein
  gesperrtes Tool erscheint in keinem Angebot.
  *Im Code:* Tabelle `orchestrator.tool_availability`.
  *Mehr dazu:* [ADR-32](../adr/ADR-032-tool-sperre-und-reihenfolge-je-kanal.md).

## U

- **Übergang**: Der Schritt von einem Zustand der Journey zum nächsten. Ausgelöst wird er durch ein
  Ereignis, etwa „Tool abgeschlossen“. Ein Übergang kann weiterführen, eine Aktion verlangen, eine
  Sub-Journey starten, die Anmeldung abschließen oder die Journey abbrechen.
  *Im Code:* `Transition`, ausgelöst durch ein `JourneyEvent`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 8.
- **Untergrenze des Kanals** und **Ziel eines Durchlaufs**: Zwei Niveaus, mit denen der
  Orchestrator rechnet. Die Untergrenze ist das Niveau, unter das ein Kanal nie fallen darf, solange
  er besteht. Das Ziel ist das Niveau, das ein einzelner Step-up erreichen soll. Gerechnet wird
  immer mit dem höheren der beiden Werte.
  *Im Code:* `ChannelSession.acrFloor`, `targetAcr`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 4.

## V

- **Versandlimit**: Eine Mengenbegrenzung dafür, wie viele Codes an dieselbe Telefonnummer oder
  E-Mail-Adresse gehen dürfen. Gezählt wird je Nummer oder Adresse, nicht je Konto. So kann auch
  jemand ohne Konto niemanden mit sehr vielen Nachrichten belästigen.
  *Im Code:* `SmsSendLimit`, `EmailSendLimit`.
  *Mehr dazu:* [ADR-44](../adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md).
- **Versuchsbudget**: Die Zahl der Fehlversuche, die ein Nutzer in einer Journey insgesamt hat, über
  alle Tools hinweg. Sind sie aufgebraucht, endet die ganze Journey, und der Nutzer muss neu
  beginnen.
  *Im Code:* `attemptBudget`. Die Antwort ist dann `410`.
  *Mehr dazu:* [04-orchestrierung](../04-orchestrierung.md) Abschnitt 7.
- **Verwerfbar**: So heißt ein Konto, das keiner Person zugeordnet ist und in dem nie ein
  Anmeldeverfahren eingerichtet wurde, etwa ein Rest aus einer abgebrochenen Registrierung. Nur ein
  solches Konto darf gelöscht oder mit einem anderen Konto zusammengeführt werden. ADR-20 nennt es
  „vorläufig“.
  *Im Code:* `AccountProfile.isDisposable`.
  *Mehr dazu:* [ADR-20](../adr/ADR-020-ein-vorlaeufiges-konto-geht-im-gefundenen-auf-statt.md).
- **Vorgangszugang**: Eine Anmeldung auf der Website mit dem Einmalkennwort aus einer Einladung, auch
  ohne Konto. Er gilt nur für den einen Vorgang, zu dem eingeladen wurde. Die Tokens nennen diesen
  Vorgang. Den Vorgangszugang gibt es nur auf der Website, und er lässt sich nicht aufwerten.
  *Im Code:* Tool `auth-invite-lookup`, Token-Claim `process`.
  *Mehr dazu:* [ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).

## W

- **Web-Kanal**: Die Verbindung eines Nutzers über die Website. Hier führt Keycloak die Anmeldung
  und fragt den Orchestrator, welche Schritte nötig sind. Die Tokens stellt Keycloak aus. Das
  Gegenstück ist der App-Kanal.
  *Im Code:* `ChannelType.WEB`. Die Anbindung an Keycloak ist nach der Technik benannt: Klassen
  `Keycloak…` (etwa `KeycloakChannelService`), Pfade `/kc/…`.
  *Mehr dazu:* [05-api](../05-api.md) Abschnitt 3b,
  [ADR-8](../adr/ADR-008-keycloak-fuehrt-seine-eigenen-nativen-schritte-selbst-statt.md).
- **Widerruf**: Ein Widerruf nimmt eine Angabe zurück, etwa wenn das Personenverzeichnis meldet, dass
  eine Mitgliedsnummer nicht mehr gilt. Die alte Angabe wird dabei nicht gelöscht. Der Widerruf ist
  ein eigener Eintrag mit eigener Quelle. So bleibt nachvollziehbar, was wann galt.
  *Im Code:* `AccountRetraction`, Tabelle `account.retraction`.
  *Mehr dazu:* [ADR-12](../adr/ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md).

## Z

- **Ziel eines Durchlaufs**: siehe Untergrenze des Kanals.
- **Zustand**: Die Stelle, an der eine Journey gerade steht, etwa „wartet auf die TAN“. Jeder Intent
  hat eine feste, abgeschlossene Liste möglicher Zustände. So ist klar, welcher Schritt von wo aus
  möglich ist.
  *Im Code:* `JourneyState`.
  *Mehr dazu:* [ADR-2](../adr/ADR-002-zustand-statt-vererbung-bei-authjourney.md).
