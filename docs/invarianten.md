# Invarianten und womit sie gesichert sind

Dieses Register sammelt die Regeln, auf die sich der Backend-Kern verlässt, und nennt je Regel den
**Mechanismus**, der sie erzwingt. Eine Regel ohne Mechanismus gilt nur per Konvention – sie steht
hier trotzdem, als sichtbare **Lücke**, damit sie nicht im Kommentar verschwindet.

`InvariantRegisterTest` liest diese Datei und prüft, dass jeder genannte Mechanismus existiert. Die
Mechanismen stehen deshalb in einer festen Form, je in Backticks:

- **`test:<Klasse>`** – ein Test (Unit, Integration, modellbasiert) unter `src/test`.
- **`archunit:<Klasse>`** – eine Architekturregel unter `src/test`.
- **`type:<Klasse>`** – ein Typ unter `src/main`, der die falsche Form unausdrückbar macht.
- **`sql:<Name>`** – ein benannter Constraint oder Index in einer Flyway-Migration.

Was ein Mechanismus zur Regel beiträgt, steht in Klammern dahinter. Eine Regel ohne einen dieser
Einträge muss als `Lücke` markiert sein, mit dem bd-Issue, das sie schließen soll; eine Regel mit
Mechanismus kann zusätzlich eine Lücke nennen, wenn sie nur teilweise gesichert ist.

Jede Regel hat unter der Überschrift eine Zeile **Worum es geht**: in Alltagssprache, was die Regel
bedeutet und welcher Schaden ohne sie droht. Sie soll ohne Kenntnis des Codes verständlich sein.
Einige Wörter haben hier eine feste Bedeutung:

- **Kanal:** eine Verbindung eines Clients zum Orchestrator; der App-Kanal spricht direkt mit ihm,
  der Web-Kanal über Keycloak.
- **Journey:** ein Durchlauf zu einem Intent („anmelden“, „SMS einrichten“), zusammengesetzt aus Tools.
- **Evidenz:** die gesammelten Nachweise einer Anmeldung – welche Verfahren mit welchen Faktoren
  bestanden wurden; daraus folgt das Niveau `loa1` bis `loa3`.
- **Anker:** ein Wert, über den ein Konto eindeutig wiedergefunden wird (Partnernummer,
  Ausweiskennung, bestätigte E-Mail-Adresse).
- **Einladung:** Vorgangszugang für eine Person ohne Konto, mit einem Kennwort per Brief für genau
  einen Vorgang ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)).

**Nummern sind stabil.** Tests, Migrationen und ADRs zitieren sie (`I-3`, `I-23`, …). Die Abschnitte
ordnen nach Thema, nicht nach Nummer; eine neue Regel bekommt die nächste freie Nummer, eine
zusammengelegte oder gestrichene wird nie neu vergeben (Liste am Ende).

---

## Kanal und Journey

- **I-1 Ein beendeter Kanal (`LOGGED_OUT`, `EXPIRED`) bleibt in diesem Zustand – er wird weder wieder `AUTHENTICATED` noch bekommt er eine neue Journey.**
  - Worum es geht: Ein abgemeldeter oder abgelaufener Kanal lässt sich nicht wiederbeleben; wer weitermachen will, braucht einen neuen Kanal. So wird ein alter, womöglich gestohlener Kanal nach der Abmeldung nicht wieder brauchbar.
  - Mechanismus: `type:LiveChannel` (`JourneyService` startet, bewegt und beendet Journeys nur auf diesem Typ, seine Fabrik lehnt einen beendeten Kanal ab), `sql:ck_channel_session_ended_without_login` (ein beendeter Kanal trägt weder Anmeldedurchlauf noch Evidenz), `test:DatabaseInvariantConstraintTest`, `test:CancelLogoutIntegrationTest`, `test:ModelBasedJourneyTest`
- **I-2 Eine verbrauchte, abgebrochene oder fehlgeschlagene Journey nimmt keine Tool-Ergebnisse mehr an.**
  - Worum es geht: Späte oder wiederholte Tool-Ergebnisse laufen ins Leere. Ein nachgereichter SMS-Code macht einen abgebrochenen Login nicht nachträglich doch noch erfolgreich.
  - Mechanismus: `type:RunningJourney` (`JourneyService` nimmt nur diesen Typ an, seine einzige Fabrik lehnt eine beendete Journey ab), `archunit:OrchestratorArchitectureTest` (außerhalb des Journey-Pakets kommt niemand sonst an `AuthJourney`), `sql:ck_tool_session_status` (eine abgeschlossene ToolSession ist `DONE`), `test:CancelLogoutIntegrationTest`, `test:DeviceBindingIntegrationTest`, `test:ModelBasedJourneyTest`
- **I-3 Ein Kanal hat höchstens eine laufende (`STARTED`) Journey.**
  - Worum es geht: Startet der Nutzer etwas Neues, wird der laufende Durchlauf abgebrochen. Zwei parallele Durchläufe könnten sich gegenseitig überschreiben, und es wäre unklar, welcher gilt.
  - Mechanismus: `type:JourneyService` (`start` bricht die laufende Kette ab), `sql:ux_journey_running_per_channel` (berechnete Spalte statt partiellem Index), `test:DatabaseInvariantConstraintTest`, `test:ModelBasedJourneyTest`
- **I-4 Ein `AUTHENTICATED`-Kanal hat Evidenz mit mindestens einem Faktor.**
  - Worum es geht: Angemeldet heißt: Es gibt einen bestandenen Nachweis. Kein Weg, auch kein Abbruch mit Rückfall, markiert einen Kanal ohne Nachweis als angemeldet.
  - Mechanismus: `type:ChannelState` (`isLoggedIn`: ein Abbruch kehrt nur dann zu `AUTHENTICATED` zurück, wenn der Kanal vorher angemeldet war – keine Strategie benennt ihren Rückfall selbst), `sql:ck_channel_session_authenticated_with_evidence`, `test:DatabaseInvariantConstraintTest`, `test:ModelBasedJourneyTest`, `test:ConfirmPeerLoginFlowIntegrationTest`

## Sitzung und Tokens

- **I-22 Eine abgelaufene Anmeldung wird nie still verlängert; jede Abmeldung verwirft die Tokens.**
  - Worum es geht: Statt heimlich neue Tokens auszustellen, muss sich der Nutzer nach Ablauf neu anmelden. Nach einer Abmeldung taugt kein altes Token mehr.
  - Mechanismus: `type:SessionExpiredException`, `test:TokenServiceTest`, `test:KcTokenProviderTest`, `test:CancelLogoutIntegrationTest`
- **I-23 `AUTHENTICATED` erzeugt eine Keycloak-Sitzung, und der Kanal überlebt sie nie; die Sitzungsdauer bestimmt Keycloak ([ADR-43](adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md)).**
  - Worum es geht: Keycloak führt die Uhr der Anmeldung. Der Kanal darf nicht angemeldet bleiben, wenn die Keycloak-Sitzung schon abgelaufen oder abgemeldet ist – sonst liefen zwei Uhren auseinander.
  - Mechanismus: `type:AppTokenIssuer` (der App-Kanal holt beim Übergang nach `AUTHENTICATED` sein erstes Token; jedes Token, auch die Erneuerung bei einer Journey-Interaktion, setzt `expiresAt` auf das gemeldete Sitzungsfenster), `type:SessionRefusedException` (lehnt Keycloak ab, rollt der Übergang zurück), `type:ChannelSessionEndedException` (eine Sitzung, die sich nicht mehr erneuern lässt, beendet den Kanal, und das bleibt), `type:SessionEnd` (Web-Kanal: Keycloak meldet am Ende des Durchlaufs das späteste Sitzungsende), `test:ModelBasedJourneyTest` (Sitzung altert, läuft ab, Abmeldung in Keycloak; nach jedem Schritt geprüft), `test:AppTokenIssuerIntegrationTest`, `test:SessionRefusedIntegrationTest`, `test:KcChannelIntegrationTest`, `test:SessionEndTest`
  - Lücke: Web-Kanal zwischen letztem Journey-Schritt und Ende des Keycloak-Durchlaufs; verlorene Meldungen (`restore-data`, Abmeldung) sind nur best effort; Issue `DPoP-demo-oe06`.
- **I-24 Zu einem Kanal gehört genau eine Keycloak-Sitzung: Sie wird einmal geöffnet und nie ersetzt; ein Step-up läuft in derselben Sitzung.**
  - Worum es geht: Die Sitzung wird einmal geöffnet und danach nur fortgesetzt, auch beim Hochstufen auf ein höheres Niveau. So ist eine Abmeldung in Keycloak eindeutig, und keine vergessene Nebensitzung bleibt übrig.
  - Mechanismus: `type:KcTokenProvider` (nur ohne bekannte `keycloakSessionId` wird eine Sitzung geöffnet, danach setzt der Grant per `session_id` genau diese fort), `type:AccountTokenGrantType` (setzt nur eine gültige, eigene Sitzung desselben Nutzers fort, sonst Ablehnung), `test:ModelBasedJourneyTest`, `test:KcTokenProviderTest`, `test:TokenServiceTest`, `test:AppTokenIssuerIntegrationTest`
- **I-32 Ein Niveau über loa1 beruht nur auf Nachweisen der letzten 30 Minuten; ein wiederhergestellter Nachweis wird dadurch nicht jünger ([04-orchestrierung](04-orchestrierung.md) Abschnitt 8).**
  - Worum es geht: Ein zweiter Faktor von heute Morgen reicht am Nachmittag nicht mehr für `loa2`; wer mehr will, muss ihn frisch bestätigen. Wird ein Nachweis in einen neuen Anmeldedurchlauf übernommen, behält er seinen alten Zeitstempel – sonst ließe er sich durch bloßes Weiterreichen beliebig verjüngen.
  - Mechanismus: `test:DefaultAuthPolicyTest`, `test:RestoreDataCodecTest`, `test:KcChannelIntegrationTest`

## Subjekt eines Kanals: Konto oder Einladung

- **I-5 Ein Kanal gehört höchstens einem Subjekt – einem Konto oder einer Einladung, nie beiden – und wechselt es nie still: ein anderes Subjekt ist ein Fehler, kein Umbinden. Seine Evidenz gehört demselben Subjekt ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)).**
  - Worum es geht: Ein Kanal hängt an genau einem „Wer“. Meldet sich darauf plötzlich ein anderes Konto an, wird nicht still umgehängt, sondern abgelehnt. Sonst könnten Nachweise von Person A beim Konto von Person B landen.
  - Mechanismus: `sql:ck_channel_session_one_subject`, `sql:ck_session_evidence_one_subject`, `sql:ck_sign_in_log_one_subject`, `test:KcChannelIntegrationTest`, `test:AuthInviteIntegrationTest` (auch: Keycloak nennt ein anderes Subjekt → `409`)
- **I-30 Die Evidenz einer Einladung wandert in keinen späteren Anmeldedurchlauf ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)).**
  - Worum es geht: Das Einladungs-Kennwort taugt nur für den einen Vorgang. Sein Nachweis wird nicht in eine spätere Konto-Anmeldung übernommen.
  - Mechanismus: `test:AuthInviteIntegrationTest` (`restore-data` bleibt für einen Einladungs-Kanal leer)

## Konto im Aufbau ([ADR-46](adr/ADR-046-konto-im-aufbau.md))

- **I-26 Ein angemeldeter Kanal arbeitet nie mit einem Konto im Aufbau (ohne Anmeldeverfahren); `REGISTERING` wird nur abgeleitet, nie gespeichert.**
  - Worum es geht: Ein Konto ist „im Aufbau“, solange noch kein Anmeldeverfahren eingerichtet ist, also mitten in der Registrierung. Ein angemeldeter Kanal hängt nie an so einem Konto. „Registriert gerade“ wird berechnet statt gespeichert und kann deshalb nicht veralten.
  - Mechanismus: `type:ChannelState` (`shownWith` leitet den Zustand ab), `test:ModelBasedJourneyTest` (keine Registrierung endet ohne dauerhaftes Verfahren)
- **I-27 Anmeldung und Keycloak-Suche finden kein Konto im Aufbau – aber jedes eingerichtete, auch mit offenen Pflichten (Identität, zweiter Faktor, Niveau).**
  - Worum es geht: Halbfertige Konten tauchen bei Anmeldung und Nutzersuche nicht auf. Ein eingerichtetes Konto ist dagegen immer anmeldefähig, auch wenn noch Identifizierung oder zweiter Faktor fehlen.
  - Mechanismus: `type:AccountService` (`resolveByAnchor` liefert nur Konten mit Verfahren), `test:AccountServiceTest`, `test:KcAccountLookupIntegrationTest`, `test:RegisterEnrollFirstFlowIntegrationTest` (Anmeldung ohne Person hinter dem Konto)
- **I-28 Ein eingerichtetes Konto fällt nie in den Aufbau zurück: Eine Verfahrensinstanz wird deaktiviert, nie gelöscht; gelöscht wird nur das ganze Konto.**
  - Worum es geht: Einmal eingerichtet, immer eingerichtet. Würde das letzte Verfahren gelöscht, wäre das Konto plötzlich wieder „unsichtbar“; deshalb wird ein Verfahren nur deaktiviert.
  - Mechanismus: `type:AccountAuthMethodRepository` (kennt kein `delete`), `type:AccountProfile` (`isSetUp` zählt deaktivierte Instanzen mit), `sql:fk_auth_method_account` (Instanzen gehen nur mit dem Konto), `test:ModelBasedJourneyTest`

## Konto, Anker und Verfahren

- **I-9 Ein Ankerwert gehört höchstens einem Konto; je Konto höchstens ein Anker je Art.**
  - Worum es geht: Ein Anker (Partnernummer, Ausweiskennung, bestätigte E-Mail) findet ein Konto eindeutig wieder. Zeigte er auf zwei Konten, wäre unklar, welches gemeint ist.
  - Mechanismus: `sql:ux_anchor_value`, `sql:ux_anchor_account_type`, `test:AccountServiceDbTest`
- **I-10 Nur `JourneyActionExecutor` befragt `IdentityResolver` – aufgelöst wird nur dort, wo auch gebunden wird.**
  - Worum es geht: Die Frage „welche Person ist das?“ stellt nur die Stelle, die die Antwort auch ans Konto bindet. Fragt jemand anders und handelt auf eigene Faust, entstehen Zuordnungen am zentralen Schutz vorbei.
  - Mechanismus: `archunit:OrchestratorArchitectureTest`
  - Lücke: Die Regel überwacht nur das Interface, nicht `IdentityMatchingService` dahinter; Issue `DPoP-demo-9ppv.23`.
- **I-11 Der Inhaber kann nur die E-Mail-Adresse selbst zurücknehmen, keinen Identitätsanker.**
  - Worum es geht: Eine bestätigte E-Mail-Adresse kann der Inhaber selbst entfernen. Für Identitätsanker wie Partnernummer oder Ausweiskennung stehen Dritte ein (Personenverzeichnis, Ausweis); die nimmt der Nutzer nicht zurück.
  - Mechanismus: `type:AnchorRule`, `test:AttributeRulesTest`, `test:AccountServiceDbTest`, `test:ManageMethodsIntegrationTest`
- **I-12 Ein Korrelationsschritt (`ident-kvnr`) verrät nicht, ob eine fremde Nummer existiert.**
  - Worum es geht: Wer eine Krankenversichertennummer eintippt, die nicht zu ihm passt, erfährt nicht, ob sie überhaupt vergeben ist – sonst ließen sich Nummern durchprobieren. Der Fehlversuch zählt bei der Person, die getroffen werden sollte, sodass deren Mengenbegrenzung greift.
  - Mechanismus: `type:ToolOutcome` (`Failed.Identification` verlangt `attemptedPersonId`; die Drosselbuchung ist ein erschöpfendes `when` über die Varianten), `test:IdentKvnrToolHandlerTest`, `test:IdentEidAssignmentIntegrationTest`
- **I-13 Je Konto höchstens ein aktiver Eintrag eines Singleton-Verfahrens (z. B. Passwort).**
  - Worum es geht: Zwei gleichzeitig gültige Passwörter wären verwirrend und eine unnötige Angriffsfläche. Geräte darf man dagegen mehrere haben.
  - Mechanismus: `sql:ux_auth_method_active_singleton`, `test:DatabaseInvariantConstraintTest` (prüft auch, dass die Liste der Verfahren im SQL zu den Deskriptoren passt), `test:ModelBasedJourneyTest`
- **I-21 Was eine ersetzte Instanz nachwies und die neue nicht, gilt nicht mehr; jede Passwort-Instanz trägt ihren eigenen Nachweis.**
  - Worum es geht: Wer seine SMS-Nummer wechselt, hat danach nur noch die neue als bestätigt; die alte zählt nicht mehr. Ein neues Passwort erbt nichts vom alten.
  - Mechanismus: `test:AccountServiceDbTest`, `test:MgmtPasswordIntegrationTest`
- **I-14 Kein Gerätelink zeigt auf ein gelöschtes Konto.**
  - Worum es geht: Wird ein Konto gelöscht, verschwinden die Verknüpfungen seiner Geräte mit. Sonst würde ein Gerät ein Konto „wiedererkennen“, das es nicht mehr gibt.
  - Mechanismus: `type:AccountDeletionService` (jede Kontolöschung, auch die einer abgebrochenen Registrierung, läuft hierüber und nimmt die Links mit), `test:ModelBasedJourneyTest`
  - Lücke: kein Fremdschlüssel (Schemas je Modul, ADR-16); Issue `DPoP-demo-hwc6`.

## DPoP und Zugang

- **I-6 Jeder HTTP-Handler ist per DPoP an einen Kanal gebunden (`@BindingKey`) oder mit seinem eigenen Schutz benannt.**
  - Worum es geht: Jeder Endpunkt ist entweder an den DPoP-Schlüssel eines Kanals gebunden oder nennt ausdrücklich seinen anderen Schutz, etwa den Admin-Login. Ein vergessener, ungeschützter Endpunkt fällt so im Test auf.
  - Mechanismus: `type:BindingKey`, `archunit:ApiBoundaryArchitectureTest`
- **I-7 Ein DPoP-Proof gilt nur einmal.**
  - Worum es geht: Jede Anfrage trägt einen eigenen signierten DPoP-Proof. Wer eine Anfrage abfängt und nochmal abschickt, wird abgewiesen (Schutz vor Replay).
  - Mechanismus: `type:DpopReplayProtectionService`, `test:DpopValidatorTest`
- **I-8 Ein Kanal ist an genau einen Schlüssel gebunden, APP- und Keycloak-Kanal schließen sich aus.**
  - Worum es geht: Ein App-Kanal hat genau einen Geräteschlüssel. Ein Keycloak-Kanal hat beim Orchestrator keinen, denn dort spricht Keycloak. Beides zugleich gibt es nicht.
  - Mechanismus: `sql:ck_channel_session_binding_key` (genau der APP-Kanal trägt einen Schlüssel), `type:ChannelAccessGuard`

## Keycloak-Anbindung

- **I-15 Ein Konto ist genau ein Keycloak-Nutzer, und kein Nutzer steht für ein anderes Konto.**
  - Worum es geht: Die Keycloak-Nutzerkennung wird aus der Kontonummer berechnet, nicht gesucht oder kopiert. Deshalb kann nie ein Keycloak-Nutzer versehentlich für ein anderes Konto stehen.
  - Mechanismus: `type:OrchestratorUser` (die Nutzer-Id ist `f:<Komponente>:<accountId>`, berechnet statt gesucht; es gibt keine Kopie, die ein anderes Konto tragen könnte), `test:OrchestratorUserTest`, `test:KcAccountLookupIntegrationTest`
- **I-31 Eine Einladung ist in Keycloak nie ein Konto: Sie hat einen Nutzer eigener Art, und keine Anmeldung setzt die Sitzung des jeweils anderen fort ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)).**
  - Worum es geht: Einladungen haben in Keycloak eine eigene Nutzerart mit eigener Kennung und ohne Kontobezug. Eine Konto-Anmeldung setzt nie die Sitzung einer Einladung fort und umgekehrt.
  - Mechanismus: `type:InvitationUser` (Nutzer-Id aus der eigenen Federation, `f:<UUID>:<Id>`; das Konto-Attribut ist immer leer), `test:InvitationUserTest`, `test:LoginCompletionTest`
- **I-16 Jeder Keycloak-Client des Orchestrators signiert mit seinem eigenen Schlüssel.**
  - Worum es geht: Der Orchestrator spricht Keycloak über mehrere Clients an. Fällt ein Schlüssel in falsche Hände, sind die anderen Clients nicht betroffen.
  - Mechanismus: `test:OrchestratorClientAssertionSignerTest`
- **I-17 Das Vertrauen in ein selbstsigniertes Keycloak-Zertifikat gilt nie JVM-weit.**
  - Worum es geht: Im Demo-Betrieb hat Keycloak ein selbstsigniertes Zertifikat. Das Vertrauen gilt nur für diese Verbindung; sonst würde jede andere HTTPS-Verbindung auch gefälschte Zertifikate annehmen.
  - Mechanismus: `type:KeycloakHttp`, `test:KeycloakHttpTest`

## Verfahren, Fremdsysteme und Protokoll

- **I-18 Ein QR-Login meldet einen Browser erst mit dem Bestätigungscode aus der App an, und nur einmal.**
  - Worum es geht: Der Browser zeigt einen QR-Code, die App bestätigt, und erst der in der App angezeigte Code, im Browser eingetippt, meldet den Browser an. So kann ein Angreifer einem Opfer nicht seinen eigenen QR-Code unterschieben.
  - Mechanismus: `type:QrLoginBrowserSide`, `test:AuthQrFlowIntegrationTest`
- **I-25 Ein Tool-Modul zählt Versuche nur in seinem eigenen Namensraum, und kein Code wird ohne das Versandlimit seines Moduls verschickt ([ADR-44](adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md)).**
  - Worum es geht: Ein Modul wie SMS führt seine Fehlversuchs- und Versandzähler in einem eigenen Bereich und kommt an fremde nicht heran. Ohne Zustimmung des Versandlimits geht kein Code raus – das begrenzt Kosten und Missbrauch wie SMS-Bombing.
  - Mechanismus: `type:RateLimit` (der Namensraum folgt aus der Klasse, nicht aus einem Argument), `archunit:RateLimitArchitectureTest`, `test:AccountRateLimitIntegrationTest`
- **I-20 Der Kern erreicht simulierte Fremdsysteme nur über benannte Kanten oder Ports.**
  - Worum es geht: Personenverzeichnis, Nect, KOBIL und Co. sind hinter Ports versteckt. So lassen sich die Simulationen später durch die echten Systeme ersetzen, ohne dass der Kern es merkt.
  - Mechanismus: `type:PersonMasterData`, `archunit:SimulationBoundaryArchitectureTest`
- **I-19 Kein Code und kein Empfänger landet auf der Konsole oder im Log.**
  - Worum es geht: Einmalcodes, TANs, Telefonnummern und E-Mail-Adressen erscheinen nie auf der Konsole oder im Log, auch nicht bei abgelehnter Eingabe. Logs werden breit gelesen und lange aufbewahrt und wären sonst ein Datenleck.
  - Mechanismus: `archunit:OrchestratorArchitectureTest` (kein `println`, kein `System.out`), `test:NoSecretsInLogIntegrationTest` (fängt jedes Log-Ereignis eines Versand- und Prüfdurchlaufs samt abgelehnter Eingabe ab); die Wertobjekte in `tool_api.values` nennen den abgelehnten Wert nicht, und der 400-Handler loggt nur Typ und Ort

## Nicht mehr vergebene Nummern

- I-29 (ein Subjekt je Kanal und Evidenz) ist in **I-5** aufgegangen.
