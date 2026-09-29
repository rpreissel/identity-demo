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

Eine Regel ohne einen dieser Einträge muss als `Lücke` markiert sein, mit dem bd-Issue, das sie
schließen soll.

---

## Kanal und Journey

- **I-1 Ein beendeter Kanal (`LOGGED_OUT`, `EXPIRED`) bleibt in diesem Zustand – er wird weder wieder `AUTHENTICATED` noch bekommt er eine neue Journey.**
  - Mechanismus: `type:LiveChannel` (`JourneyService` startet, bewegt und beendet Journeys nur auf diesem Typ, seine Fabrik lehnt einen beendeten Kanal ab), `test:CancelLogoutIntegrationTest`, `test:ModelBasedJourneyTest`, `sql:ck_channel_session_ended_without_login`, `test:DatabaseInvariantConstraintTest`
- **I-2 Eine verbrauchte, abgebrochene oder fehlgeschlagene Journey nimmt keine Tool-Ergebnisse mehr an.**
  - Mechanismus: `type:RunningJourney` (`JourneyService` nimmt nur diesen Typ an, seine einzige Fabrik lehnt eine beendete Journey ab), `archunit:OrchestratorArchitectureTest` (außerhalb des Journey-Pakets kommt niemand sonst an `AuthJourney`), `test:CancelLogoutIntegrationTest`, `test:DeviceBindingIntegrationTest`, `test:ModelBasedJourneyTest`, `sql:ck_tool_session_status` (eine abgeschlossene ToolSession ist `DONE`)
- **I-3 Ein Kanal hat höchstens eine laufende (`STARTED`) Journey.**
  - Mechanismus: `test:ModelBasedJourneyTest`, `type:JourneyService` (`start` bricht die laufende Kette ab), `sql:ux_journey_running_per_channel` (berechnete Spalte statt partiellem Index), `test:DatabaseInvariantConstraintTest`
- **I-4 Ein `AUTHENTICATED`-Kanal hat Evidenz mit mindestens einem Faktor.**
  - Mechanismus: `type:ChannelState` (`isLoggedIn`: ein Abbruch kehrt nur dann zu `AUTHENTICATED` zurück, wenn der Kanal vorher angemeldet war – keine Strategie benennt ihren Rückfall selbst), `test:ModelBasedJourneyTest`, `test:ConfirmPeerLoginFlowIntegrationTest`, `sql:ck_channel_session_authenticated_with_evidence`, `test:DatabaseInvariantConstraintTest`
- **I-5 Ein Kanal wechselt nie still das Konto; ein anderes Konto ist ein Fehler, kein Umbinden.**
  - Mechanismus: `test:KcChannelIntegrationTest`

- **I-22 Eine abgelaufene Anmeldung wird nie still verlängert; jede Abmeldung verwirft die Tokens.**
  - Mechanismus: `test:TokenServiceTest`, `test:KcTokenProviderTest`, `test:CancelLogoutIntegrationTest`, `type:SessionExpiredException`
- **I-23 `AUTHENTICATED` erzeugt eine Keycloak-Sitzung, und der Kanal überlebt sie nie; die Sitzungsdauer bestimmt Keycloak ([ADR-43](adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md)).**
  - Mechanismus: `type:AppLoginSession` (der App-Kanal holt beim Übergang nach `AUTHENTICATED` sein erstes Token; jedes Token, auch die Erneuerung bei einer Journey-Interaktion, setzt `expiresAt` auf das gemeldete Sitzungsfenster), `type:SessionRefusedException` (lehnt Keycloak ab, rollt der Übergang zurück), `type:ChannelSessionEndedException` (eine Sitzung, die sich nicht mehr erneuern lässt, beendet den Kanal, und das bleibt), `type:SessionEnd` (Web-Kanal: Keycloak meldet am Ende des Durchlaufs das späteste Sitzungsende), `test:ModelBasedJourneyTest` (Sitzung altert, läuft ab, Abmeldung in Keycloak; nach jedem Schritt geprüft), `test:AppLoginSessionIntegrationTest`, `test:SessionRefusedIntegrationTest`, `test:KcChannelIntegrationTest`, `test:SessionEndTest`
  - Lücke: Web-Kanal zwischen letztem Journey-Schritt und Ende des Keycloak-Durchlaufs; verlorene Meldungen (`restore-data`, Abmeldung) sind nur best effort; Issue `DPoP-demo-oe06`.
- **I-24 Zu einem Kanal gehört genau eine Keycloak-Sitzung: Sie wird einmal geöffnet und nie ersetzt; ein Step-up läuft in derselben Sitzung.**
  - Mechanismus: `type:KcTokenProvider` (nur ohne bekannte `keycloakSessionId` wird eine Sitzung geöffnet, danach setzt der Grant per `session_id` genau diese fort), `type:AccountTokenGrantType` (setzt nur eine gültige, eigene Sitzung desselben Nutzers fort, sonst Ablehnung), `test:ModelBasedJourneyTest`, `test:KcTokenProviderTest`, `test:TokenServiceTest`, `test:AppLoginSessionIntegrationTest`

## DPoP und Zugang

- **I-6 Jeder HTTP-Handler ist per DPoP an einen Kanal gebunden (`@BindingKey`) oder mit seinem eigenen Schutz benannt.**
  - Mechanismus: `archunit:ApiBoundaryArchitectureTest`, `type:BindingKey`
- **I-7 Ein DPoP-Proof gilt nur einmal.**
  - Mechanismus: `test:DpopValidatorTest`, `type:DpopReplayProtectionService`
- **I-8 Ein Kanal ist an genau einen Schlüssel gebunden, APP- und Keycloak-Kanal schließen sich aus.**
  - Mechanismus: `sql:ck_channel_session_binding_key`, `type:ChannelAccessGuard`

## Konto und Identität

- **I-9 Ein Ankerwert gehört höchstens einem Konto; je Konto höchstens ein Anker je Art.**
  - Mechanismus: `sql:ux_anchor_value`, `sql:ux_anchor_account_type`, `test:AccountServiceDbTest`
- **I-10 Nur `JourneyActionExecutor` befragt `IdentityResolver` – aufgelöst wird nur dort, wo auch gebunden wird.**
  - Mechanismus: `archunit:OrchestratorArchitectureTest`
- **I-11 Der Inhaber kann nur die E-Mail-Adresse selbst zurücknehmen, keinen Identitätsanker.**
  - Mechanismus: `type:AnchorRule`, `test:AttributeRulesTest`, `test:AccountServiceDbTest`, `test:ManageMethodsIntegrationTest`
- **I-12 Ein Korrelationsschritt (`ident-kvnr`) verrät nicht, ob eine fremde Nummer existiert.**
  - Mechanismus: `type:ToolOutcome` (`Failed.Identification` verlangt `attemptedPersonId`; die Drosselbuchung ist ein erschöpfendes `when` über die Varianten), `test:IdentKvnrToolHandlerTest`, `test:IdentEidAssignmentIntegrationTest`
- **I-13 Je Konto höchstens eine aktive Instanz einer Singleton-Methode (z. B. Passwort).**
  - Mechanismus: `test:ModelBasedJourneyTest`, `sql:ux_auth_method_active_singleton`, `test:DatabaseInvariantConstraintTest` (prüft auch, dass die Methodenliste im SQL zu den Deskriptoren passt)
- **I-14 Kein Gerätelink zeigt auf ein gelöschtes Konto.**
  - Mechanismus: `test:ModelBasedJourneyTest`; jede Kontolöschung, auch die einer abgebrochenen Registrierung, läuft über `AccountDeletionService`, das die Links mitnimmt
  - Lücke: kein Fremdschlüssel (Schemas je Modul, ADR-16); Issue `DPoP-demo-hwc6`.

- **I-26 Ein angemeldeter Kanal arbeitet nie mit einem Konto im Aufbau (ohne Anmeldeverfahren); `REGISTERING` wird nur abgeleitet, nie gespeichert ([ADR-46](adr/ADR-046-konto-im-aufbau.md)).**
  - Mechanismus: `test:ModelBasedJourneyTest`; keine Registrierung endet ohne dauerhaftes Verfahren, und `ChannelState.shownWith` leitet den Zustand ab
  - Umgekehrt gilt: Ein eingerichtetes Konto ist anmeldefähig, auch mit offenen Pflichten (Identität, zweiter Faktor, Stufe). Mechanismus: `test:RegisterEnrollFirstFlowIntegrationTest` (Anmeldung ohne Person hinter dem Konto)
- **I-27 Anmeldung und Keycloak-Suche finden kein Konto im Aufbau ([ADR-46](adr/ADR-046-konto-im-aufbau.md)).**
  - Mechanismus: `test:AccountServiceTest`, `test:KcAccountLookupIntegrationTest`; `AccountService.resolveByAnchor` liefert nur Konten mit Verfahren
- **I-28 Ein eingerichtetes Konto fällt nie in den Aufbau zurück: Eine Verfahrensinstanz wird deaktiviert, nie gelöscht; gelöscht wird nur das ganze Konto ([ADR-46](adr/ADR-046-konto-im-aufbau.md)).**
  - Mechanismus: `type:AccountAuthMethodRepository` (kennt kein `delete`), `type:AccountProfile` (`isSetUp` zählt deaktivierte Instanzen mit), `sql:fk_auth_method_account` (Instanzen gehen nur mit dem Konto), `test:ModelBasedJourneyTest`
- **I-29 Ein Kanal gehört höchstens einem Subjekt: einem Konto oder einer Einladung, nie beiden. Seine Evidenz gehört demselben Subjekt ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)).**
  - Mechanismus: `sql:ck_channel_session_one_subject`, `sql:ck_auth_evidence_one_subject`, `test:AuthInviteIntegrationTest`
- **I-30 Die Evidenz einer Einladung wandert in keinen späteren Anmeldedurchlauf ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)).**
  - Mechanismus: `test:AuthInviteIntegrationTest` (`restore-data` bleibt für einen Einladungs-Kanal leer)

- **I-21 Was eine ersetzte Instanz nachwies und die neue nicht, gilt nicht mehr; jede Passwort-Instanz trägt ihren eigenen Nachweis.**
  - Mechanismus: `test:AccountServiceDbTest`, `test:MgmtPasswordIntegrationTest`

## Keycloak

- **I-15 Ein Konto ist genau ein Keycloak-Nutzer, und kein Nutzer steht für ein anderes Konto.**
  - Mechanismus: `type:OrchestratorUser` (die Nutzer-Id ist `f:<Komponente>:<accountId>`, berechnet statt gesucht; es gibt keine Kopie, die ein anderes Konto tragen könnte), `test:OrchestratorUserTest`, `test:KcAccountLookupIntegrationTest`
- **I-31 Eine Einladung ist in Keycloak nie ein Konto: Sie hat einen Nutzer eigener Art, und keine Anmeldung setzt die Sitzung des jeweils anderen fort ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)).**
  - Mechanismus: `type:InvitationUser` (Nutzer-Id aus der eigenen Federation, `f:<UUID>:<Id>`; das Konto-Attribut ist immer leer), `test:InvitationUserTest`, `test:LoginCompletionTest`
- **I-16 Jeder Keycloak-Client des Orchestrators signiert mit seinem eigenen Schlüssel.**
  - Mechanismus: `test:OrchestratorClientAssertionSignerTest`
- **I-17 Das Vertrauen in ein selbstsigniertes Keycloak-Zertifikat gilt nie JVM-weit.**
  - Mechanismus: `test:KeycloakHttpTest`, `type:KeycloakHttp`

## Verfahren und Fremdsysteme

- **I-18 Ein QR-Login meldet einen Browser erst mit dem Bestätigungscode aus der App an, und nur einmal.**
  - Mechanismus: `test:AuthQrFlowIntegrationTest`, `type:QrLoginBrowserSide`
- **I-19 Kein Code und kein Empfänger landet auf der Konsole oder im Log.**
  - Mechanismus: `archunit:OrchestratorArchitectureTest` (kein `println`, kein `System.out`), `test:NoSecretsInLogIntegrationTest` (fängt jedes Log-Ereignis eines Versand- und Prüfdurchlaufs samt abgelehnter Eingabe ab); die Wertobjekte in `tool_api.values` nennen den abgelehnten Wert nicht, und der 400-Handler loggt nur Typ und Ort
- **I-20 Der Kern erreicht simulierte Fremdsysteme nur über benannte Kanten oder Ports.**
  - Mechanismus: `archunit:SimulationBoundaryArchitectureTest`, `type:PersonMasterData`
- **I-25 Ein Tool-Modul zählt Versuche nur in seinem eigenen Namensraum, und kein Code wird ohne das Versandbudget seines Moduls verschickt ([ADR-44](adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md)).**
  - Mechanismus: `type:AttemptBudget` (der Namensraum folgt aus der Klasse, nicht aus einem Argument), `archunit:AttemptBudgetArchitectureTest`, `test:AccountThrottleIntegrationTest`
