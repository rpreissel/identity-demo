# Invarianten und womit sie gesichert sind

Eine **Invariante** ist eine Regel, die im System immer gelten muss, egal welcher Weg durch den
Code gerade läuft. Dieses Register sammelt die Regeln, auf die sich der Backend-Kern verlässt. Der
Backend-Kern ist der Teil des Servers, der produktionsreif sein soll (siehe
[ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md)).

Zu jeder Regel nennt das Register den **Mechanismus**, der sie erzwingt, also einen Test, eine
Architekturregel, einen Datentyp oder eine Datenbankregel. Eine Regel ohne Mechanismus gilt nur
per Absprache unter den Entwicklern. Sie steht trotzdem hier, und zwar als sichtbare **Lücke**.
So verschwindet sie nicht unbemerkt in einem Code-Kommentar.

`InvariantRegisterTest` liest diese Datei und prüft, ob jeder genannte Mechanismus wirklich
existiert. Deshalb stehen die Mechanismen in einer festen Form, jeweils in Backticks:

- **`test:<Klasse>`** – ein Test unter `src/test`. Das kann ein Unit-Test, ein Integrationstest
  oder ein modellbasierter Test sein.
- **`archunit:<Klasse>`** – eine Architekturregel unter `src/test`. Sie prüft, welcher Code welchen
  anderen Code benutzen darf.
- **`type:<Klasse>`** – ein Typ unter `src/main`. Er ist so gebaut, dass sich der verbotene Zustand
  im Code gar nicht erst ausdrücken lässt.
- **`sql:<Name>`** – ein benannter Constraint oder Index in einer Flyway-Migration, also eine
  Regel, die die Datenbank selbst durchsetzt.

In Klammern hinter einem Mechanismus steht, was er zur Regel beiträgt. Hat eine Regel keinen dieser
Einträge, muss sie als `Lücke` markiert sein. Dazu gehört das bd-Issue, das die Lücke schließen
soll. Auch eine Regel mit Mechanismus kann zusätzlich eine Lücke nennen, wenn der Mechanismus sie
nur teilweise absichert.

Jede Regel hat unter ihrer Überschrift eine Zeile **Worum es geht**. Sie erklärt in Alltagssprache,
was die Regel bedeutet und welcher Schaden ohne sie droht. Diese Zeile soll ohne Kenntnis des Codes
verständlich sein. Einige Wörter haben hier eine feste Bedeutung (ausführlicher im
[Glossar](glossar/glossar.md)):

- **Orchestrator:** der Server dieses Projekts. Er entscheidet, welche Schritte ein Nutzer bei
  Registrierung und Anmeldung durchläuft.
- **Keycloak:** das Produkt, das auf der Website die Anmeldung führt und die Tokens ausstellt.
- **Kanal:** eine Verbindung eines Clients zum Orchestrator. Der App-Kanal spricht direkt mit dem
  Orchestrator, der Web-Kanal über Keycloak.
- **Intent:** das Anliegen, mit dem ein Nutzer kommt, etwa „anmelden“ oder „SMS einrichten“.
- **Tool:** ein abgeschlossener Arbeitsschritt, etwa „mit Passwort anmelden“.
- **Journey:** ein Durchlauf zu einem Intent. Er setzt sich aus Tools zusammen.
- **Evidenz:** die gesammelten Nachweise einer Anmeldung, also welche Verfahren mit welchen
  Faktoren bestanden wurden. Daraus folgt das **Niveau** (`loa1` bis `loa3`), also wie sehr der
  Anmeldung vertraut wird.
- **Anker:** ein Wert, über den ein Konto eindeutig wiedergefunden wird, etwa die Partnernummer,
  die Ausweiskennung oder eine bestätigte E-Mail-Adresse.
- **Einladung:** ein Vorgangszugang für eine Person ohne Konto. Sie bekommt per Brief ein Kennwort
  für genau einen Vorgang ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)).
- **DPoP:** ein Standard, mit dem jede Anfrage der App belegt, dass sie vom Besitzer eines
  bestimmten Schlüssels kommt. Den Beleg je Anfrage nennt man **DPoP-Proof**.

**Die Nummern bleiben fest.** Tests, Migrationen und ADRs zitieren sie (`I-3`, `I-23`, …). Die
Abschnitte sind nach Thema geordnet, nicht nach Nummer. Eine neue Regel bekommt die nächste freie
Nummer. Die Nummer einer zusammengelegten oder gestrichenen Regel wird nie neu vergeben. Diese
Nummern stehen in der Liste am Ende.

---

## Kanal und Journey

- **I-1 Ein beendeter Kanal (`LOGGED_OUT`, `EXPIRED`) bleibt in diesem Zustand – er wird weder wieder `AUTHENTICATED` noch bekommt er eine neue Journey.**
  - Worum es geht: Ein abgemeldeter oder abgelaufener Kanal lässt sich nicht wieder aktivieren. Wer weitermachen will, braucht einen neuen Kanal. So wird ein alter, womöglich gestohlener Kanal nach der Abmeldung nicht wieder brauchbar.
  - Mechanismus: `type:LiveChannel` (`JourneyService` startet, bewegt und beendet Journeys nur auf diesem Typ, und seine Fabrik lehnt einen beendeten Kanal ab), `sql:ck_channel_session_ended_without_login` (ein beendeter Kanal hat weder Tokens der App noch Evidenz), `test:DatabaseInvariantConstraintTest`, `test:CancelLogoutIntegrationTest`, `test:ModelBasedJourneyTest`
- **I-2 Eine verbrauchte, abgebrochene oder fehlgeschlagene Journey nimmt keine Tool-Ergebnisse mehr an.**
  - Worum es geht: Späte oder wiederholte Ergebnisse eines Tools nimmt der Orchestrator nicht mehr an. Ein nachgereichter SMS-Code macht zum Beispiel einen abgebrochenen Login nicht nachträglich doch noch erfolgreich.
  - Mechanismus: `type:RunningJourney` (`JourneyService` nimmt nur diesen Typ an, und seine einzige Fabrik lehnt eine beendete Journey ab), `archunit:OrchestratorArchitectureTest` (außerhalb des Journey-Pakets kann kein Code `AuthJourney` verwenden), `sql:ck_tool_session_status` (ein abgeschlossener Tool-Durchlauf, die ToolSession, steht auf `DONE`), `test:CancelLogoutIntegrationTest`, `test:DeviceBindingIntegrationTest`, `test:ModelBasedJourneyTest`
- **I-3 Ein Kanal hat höchstens eine laufende (`STARTED`) Journey.**
  - Worum es geht: Startet der Nutzer etwas Neues, bricht der Orchestrator den laufenden Durchlauf ab. Zwei parallele Durchläufe könnten sich gegenseitig überschreiben, und es wäre unklar, welcher gilt.
  - Mechanismus: `type:JourneyService` (`start` bricht die laufende Kette ab), `sql:ux_journey_running_per_channel` (eine berechnete Spalte statt eines partiellen Index), `test:DatabaseInvariantConstraintTest`, `test:ModelBasedJourneyTest`
- **I-4 Ein `AUTHENTICATED`-Kanal hat Evidenz mit mindestens einem Faktor.**
  - Worum es geht: „Angemeldet“ heißt: Es gibt einen bestandenen Nachweis. Kein Weg markiert einen Kanal ohne Nachweis als angemeldet, auch kein Abbruch, nach dem der Kanal in einen früheren Zustand zurückfällt.
  - Mechanismus: `type:ChannelState` (`isLoggedIn`: Ein Abbruch kehrt nur dann zu `AUTHENTICATED` zurück, wenn der Kanal vorher angemeldet war. Keine Strategie legt ihren Rückfall selbst fest.), `sql:ck_channel_session_authenticated_with_evidence`, `test:DatabaseInvariantConstraintTest`, `test:ModelBasedJourneyTest`, `test:ConfirmPeerLoginFlowIntegrationTest`

## Sitzung und Tokens

- **I-22 Eine abgelaufene Anmeldung wird nie still verlängert; jede Abmeldung verwirft die Tokens.**
  - Worum es geht: Nach Ablauf stellt das System nicht heimlich neue Tokens aus. Der Nutzer muss sich neu anmelden. Nach einer Abmeldung taugt kein altes Token mehr.
  - Mechanismus: `type:SessionExpiredException`, `test:TokenServiceTest`, `test:KeycloakTokenProviderTest`, `test:CancelLogoutIntegrationTest`
- **I-23 `AUTHENTICATED` erzeugt eine Keycloak-Sitzung, und der Kanal überlebt sie nie; die Sitzungsdauer bestimmt Keycloak ([ADR-43](adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md)).**
  - Worum es geht: Keycloak bestimmt, wie lange eine Anmeldung gilt. Der Kanal darf nicht angemeldet bleiben, wenn die Sitzung in Keycloak schon abgelaufen oder abgemeldet ist. Sonst gälten für dieselbe Anmeldung zwei verschiedene Ablaufzeiten.
  - Mechanismus: `type:AppTokenIssuer` (der App-Kanal holt beim Übergang nach `AUTHENTICATED` sein erstes Token. Jedes Token, auch die Erneuerung bei einer Interaktion in der Journey, setzt `expiresAt` auf das Sitzungsfenster, das Keycloak meldet.), `type:SessionRefusedException` (lehnt Keycloak ab, wird der Übergang zurückgerollt), `type:ChannelSessionEndedException` (eine Sitzung, die sich nicht mehr erneuern lässt, beendet den Kanal endgültig), `type:SessionEnd` (im Web-Kanal meldet Keycloak am Ende des Durchlaufs das späteste Sitzungsende), `test:ModelBasedJourneyTest` (die Sitzung altert, läuft ab oder wird in Keycloak abgemeldet; der Test prüft nach jedem Schritt), `test:AppTokenIssuerIntegrationTest`, `test:SessionRefusedIntegrationTest`, `test:KeycloakChannelIntegrationTest`, `test:SessionEndTest`
  - Lücke: Im Web-Kanal ist die Zeit zwischen dem letzten Schritt der Journey und dem Ende des Keycloak-Durchlaufs nicht abgesichert. Meldungen, die verloren gehen können (`flow-end`, Abmeldung), werden nur nach bestem Bemühen („best effort“) zugestellt. Issue `DPoP-demo-oe06`.
- **I-24 Zu einem Kanal gehört genau eine Keycloak-Sitzung: Sie wird einmal geöffnet und nie ersetzt; ein Step-up läuft in derselben Sitzung.**
  - Worum es geht: Die Sitzung wird einmal geöffnet und danach nur fortgesetzt. Das gilt auch für einen Step-up, also wenn sich der Nutzer auf ein höheres Niveau hochstuft. So ist eine Abmeldung in Keycloak eindeutig, und keine vergessene Nebensitzung bleibt übrig.
  - Mechanismus: `type:KeycloakTokenProvider` (nur wenn noch keine `keycloakSessionId` bekannt ist, wird eine Sitzung geöffnet. Danach setzt der Grant, also die Token-Anfrage bei Keycloak, per `session_id` genau diese Sitzung fort.), `type:AccountTokenGrantType` (setzt nur eine gültige, eigene Sitzung desselben Nutzers fort und lehnt sonst ab), `test:AccountTokenSessionTest`, `test:ModelBasedJourneyTest`, `test:KeycloakTokenProviderTest`, `test:TokenServiceTest`, `test:AppTokenIssuerIntegrationTest`
- **I-32 Ein Niveau über loa1 beruht nur auf Nachweisen der letzten 30 Minuten; ein wiederhergestellter Nachweis wird dadurch nicht jünger ([04-orchestrierung](04-orchestrierung.md) Abschnitt 4).**
  - Worum es geht: Ein zweiter Faktor von heute Morgen reicht am Nachmittag nicht mehr für `loa2`. Wer mehr will, muss ihn frisch bestätigen. Wird ein Nachweis in einen neuen Anmeldedurchlauf übernommen, behält er seinen alten Zeitstempel. Sonst könnte man einen alten Nachweis als frisch ausgeben, indem man ihn einfach weitergibt. Die Regel gilt für das Niveau, das der Orchestrator meldet, und für jeden neuen Durchlauf. Das `acr` eines schon ausgestellten Tokens (das Niveau, das im Token steht) altert dagegen nicht. Es beschreibt wie bei Keycloak üblich die Anmeldung (04 Abschnitt 8).
  - Mechanismus: `test:DefaultAuthPolicyTest`, `type:KeycloakSessionEvidence` (eine übernommene Zeile behält `proven_at`), `test:KeycloakChannelIntegrationTest`

## Subjekt eines Kanals: Konto oder Einladung

- **I-5 Ein Kanal gehört höchstens einem Subjekt – einem Konto oder einer Einladung, nie beiden – und wechselt es nie still: ein anderes Subjekt ist ein Fehler, kein Umbinden. Seine Evidenz gehört demselben Subjekt ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)).**
  - Worum es geht: Ein Kanal gehört genau einem Subjekt, also einem Konto oder einer Einladung. Meldet sich auf dem Kanal plötzlich ein anderes Konto an, ordnet der Orchestrator den Kanal nicht still dem neuen Konto zu, sondern lehnt ab. Sonst könnten Nachweise von Person A beim Konto von Person B landen.
  - Mechanismus: `sql:ck_channel_session_one_subject`, `sql:ck_session_evidence_one_subject`, `sql:ck_sign_in_log_one_subject`, `test:DatabaseInvariantConstraintTest`, `test:KeycloakChannelIntegrationTest`, `test:AuthInviteIntegrationTest` (auch: Nennt Keycloak ein anderes Subjekt, folgt `409`)
- **I-30 Die Evidenz einer Einladung wandert in keinen späteren Anmeldedurchlauf ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)).**
  - Worum es geht: Das Kennwort einer Einladung taugt nur für den einen Vorgang. Sein Nachweis wird nicht in eine spätere Anmeldung mit einem Konto übernommen.
  - Mechanismus: `test:AuthInviteIntegrationTest` (`flow-end` legt für einen Kanal mit Einladung nichts ab)

## Konto im Aufbau ([ADR-46](adr/ADR-046-konto-im-aufbau.md))

- **I-26 Ein angemeldeter Kanal arbeitet nie mit einem Konto im Aufbau (ohne Anmeldeverfahren); `REGISTERING` wird nur abgeleitet, nie gespeichert.**
  - Worum es geht: Ein Konto ist „im Aufbau“, solange noch kein Anmeldeverfahren eingerichtet ist, also mitten in der Registrierung. Ein angemeldeter Kanal arbeitet nie mit so einem Konto. Den Zustand „registriert gerade“ berechnet das System, statt ihn zu speichern. Deshalb kann er nicht veralten.
  - Mechanismus: `type:ChannelState` (`shownWith` leitet den Zustand ab), `test:ModelBasedJourneyTest` (keine Registrierung endet ohne ein dauerhaftes Verfahren)
- **I-27 Anmeldung und Keycloak-Suche finden kein Konto im Aufbau – aber jedes eingerichtete, auch mit offenen Pflichten (Identität, zweiter Faktor, Niveau).**
  - Worum es geht: Halbfertige Konten tauchen bei der Anmeldung und bei der Nutzersuche nicht auf. Ein eingerichtetes Konto kann sich dagegen immer anmelden, auch wenn noch die Identifizierung oder der zweite Faktor fehlt.
  - Mechanismus: `type:AccountService` (`resolveByAnchor` liefert nur Konten mit Verfahren), `test:AccountServiceTest`, `test:KeycloakAccountLookupIntegrationTest`, `test:RegisterEnrollFirstFlowIntegrationTest` (Anmeldung, ohne dass schon eine Person hinter dem Konto steht)
- **I-28 Ein eingerichtetes Konto fällt nie in den Aufbau zurück: Eine Verfahrensinstanz wird deaktiviert, nie gelöscht; gelöscht wird nur das ganze Konto.**
  - Worum es geht: Einmal eingerichtet, immer eingerichtet. Würde das letzte Verfahren gelöscht, fänden Anmeldung und Nutzersuche das Konto plötzlich nicht mehr. Deshalb wird ein Verfahren nur deaktiviert.
  - Mechanismus: `type:AccountAuthMethodRepository` (kennt kein `delete`), `type:AccountProfile` (`isSetUp` zählt deaktivierte Instanzen mit), `sql:fk_auth_method_account` (Instanzen verschwinden nur zusammen mit dem Konto), `test:ModelBasedJourneyTest`

## Konto, Anker und Verfahren

- **I-9 Ein Ankerwert gehört höchstens einem Konto; je Konto höchstens ein Anker je Art.**
  - Worum es geht: Über einen Anker (Partnernummer, Ausweiskennung, bestätigte E-Mail) findet das System ein Konto eindeutig wieder. Zeigte ein Anker auf zwei Konten, wäre unklar, welches gemeint ist.
  - Mechanismus: `sql:ux_anchor_value`, `sql:ux_anchor_account_type`, `test:AccountServiceDbTest`
- **I-10 Nur `JourneyActionExecutor` befragt `IdentityResolver` – aufgelöst wird nur dort, wo auch gebunden wird.**
  - Worum es geht: Die Frage „Welche Person ist das?“ stellt nur die Stelle, die die Antwort auch an das Konto bindet. Würde eine andere Stelle fragen und selbst handeln, entstünden Zuordnungen ohne die zentrale Prüfung.
  - Mechanismus: `archunit:OrchestratorArchitectureTest`
  - Lücke: Die Regel überwacht nur die Schnittstelle, nicht den `IdentityMatchingService` dahinter. Issue `DPoP-demo-9ppv.23`.
- **I-11 Der Inhaber kann nur die E-Mail-Adresse selbst zurücknehmen, keinen Identitätsanker.**
  - Worum es geht: Eine bestätigte E-Mail-Adresse kann der Inhaber selbst entfernen. Identitätsanker wie die Partnernummer oder die Ausweiskennung stammen aus einer Prüfung durch Dritte, etwa durch das Personenverzeichnis oder den Ausweis. Diese Anker kann der Nutzer nicht zurücknehmen.
  - Mechanismus: `type:AnchorRule`, `test:AttributeRulesTest`, `test:AccountServiceDbTest`, `test:ManageMethodsIntegrationTest`
- **I-12 Ein Korrelationsschritt (`ident-kvnr`) verrät nicht, ob eine fremde Nummer existiert.**
  - Worum es geht: Ein Korrelationsschritt ordnet einem Konto eine weitere Angabe zu, hier die Krankenversichertennummer. Wer eine Nummer eintippt, die nicht zu ihm passt, erfährt nicht, ob sie überhaupt vergeben ist. Sonst ließen sich Nummern durchprobieren. Der Fehlversuch zählt bei der Person, die getroffen werden sollte. So wirkt die Begrenzung der Versuche für diese Person.
  - Mechanismus: `type:ToolOutcome` (`Failed.Identification` verlangt `attemptedPersonId`, und die Buchung des Fehlversuchs ist ein erschöpfendes `when` über alle Varianten), `test:IdentKvnrToolHandlerTest`, `test:IdentEidAssignmentIntegrationTest`
- **I-13 Je Konto höchstens ein aktiver Eintrag eines Singleton-Verfahrens (z. B. Passwort).**
  - Worum es geht: Ein Singleton-Verfahren darf ein Konto nur einmal haben, etwa das Passwort. Zwei gleichzeitig gültige Passwörter wären verwirrend und eine unnötige Angriffsfläche. Geräte darf man dagegen mehrere haben.
  - Mechanismus: `sql:ux_auth_method_active_singleton`, `test:DatabaseInvariantConstraintTest` (prüft auch, dass die Liste der Verfahren im SQL zu den Beschreibungen der Verfahren im Code passt), `test:ModelBasedJourneyTest`
- **I-21 Was eine ersetzte Instanz nachwies und die neue nicht, gilt nicht mehr; jede Passwort-Instanz hat ihren eigenen Nachweis.**
  - Worum es geht: Wer seine SMS-Nummer wechselt, hat danach nur noch die neue Nummer als bestätigt. Die alte zählt nicht mehr. Ein neues Passwort erbt nichts vom alten.
  - Mechanismus: `test:AccountServiceDbTest`
- **I-14 Kein Gerätelink zeigt auf ein gelöschtes Konto.**
  - Worum es geht: Wird ein Konto gelöscht, verschwinden auch die Verknüpfungen seiner Geräte. Sonst würde ein Gerät noch auf ein Konto verweisen, das es nicht mehr gibt.
  - Mechanismus: `type:AccountDeletionService` (jede Kontolöschung läuft hierüber, auch die einer abgebrochenen Registrierung, und nimmt die Links mit), `test:ModelBasedJourneyTest`
  - Lücke: Es gibt keinen Fremdschlüssel in der Datenbank, weil jedes Modul ein eigenes Schema hat (ADR-16). Issue `DPoP-demo-hwc6`.

## DPoP und Zugang

- **I-6 Jeder HTTP-Handler ist per DPoP an einen Kanal gebunden (`@BindingKey`) oder mit seinem eigenen Schutz benannt.**
  - Worum es geht: Jeder Endpunkt ist entweder an den DPoP-Schlüssel eines Kanals gebunden, oder er nennt ausdrücklich seinen anderen Schutz, etwa den Admin-Login. So fällt ein vergessener, ungeschützter Endpunkt im Test auf.
  - Mechanismus: `type:BindingKey`, `archunit:ApiBoundaryArchitectureTest`
- **I-7 Ein DPoP-Proof gilt nur einmal.**
  - Worum es geht: Jede Anfrage enthält ihren eigenen signierten DPoP-Proof. Wer eine Anfrage abfängt und noch einmal abschickt, wird abgewiesen. Das ist der Schutz vor einem Replay, also dem Wiedereinspielen einer alten Anfrage.
  - Mechanismus: `type:DpopReplayProtectionService`, `test:DpopValidatorTest`
- **I-8 Ein Kanal ist an genau einen Schlüssel gebunden, App- und Web-Kanal schließen sich aus.**
  - Worum es geht: Ein App-Kanal hat genau einen Geräteschlüssel. Ein Web-Kanal hat beim Orchestrator keinen, denn dort spricht Keycloak mit dem Orchestrator. Beides zugleich gibt es nicht.
  - Mechanismus: `sql:ck_channel_session_binding_key` (genau der APP-Kanal hat einen Schlüssel), `type:ChannelAccessGuard`, `test:DatabaseInvariantConstraintTest`

## Keycloak-Anbindung

- **I-15 Ein Konto ist genau ein Keycloak-Nutzer, und kein Nutzer steht für ein anderes Konto.**
  - Worum es geht: Die Nutzerkennung in Keycloak wird aus der Kontonummer berechnet, nicht gesucht oder kopiert. Deshalb kann nie ein Keycloak-Nutzer versehentlich für ein anderes Konto stehen.
  - Mechanismus: `type:OrchestratorUser` (die Nutzer-Id ist `f:<Komponente>:<accountId>`, berechnet statt gesucht. Es gibt keine Kopie, die zu einem anderen Konto gehören könnte.), `test:OrchestratorUserTest`, `test:KeycloakAccountLookupIntegrationTest`
- **I-31 Eine Einladung ist in Keycloak nie ein Konto: Sie hat einen Nutzer eigener Art, und keine Anmeldung setzt die Sitzung des jeweils anderen fort ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)).**
  - Worum es geht: Einladungen haben in Keycloak eine eigene Art von Nutzer, mit eigener Kennung und ohne Bezug zu einem Konto. Eine Anmeldung mit Konto setzt nie die Sitzung einer Einladung fort, und umgekehrt.
  - Mechanismus: `type:InvitationUser` (die Nutzer-Id kommt aus der eigenen Federation, also der eigenen Nutzerquelle in Keycloak, und lautet `f:<UUID>:<Id>`. Das Konto-Attribut ist immer leer.), `test:InvitationUserTest`, `test:LoginCompletionTest`
- **I-16 Jeder Keycloak-Client des Orchestrators signiert mit seinem eigenen Schlüssel.**
  - Worum es geht: Der Orchestrator spricht Keycloak über mehrere Clients an. Gerät ein Schlüssel in falsche Hände, sind die anderen Clients nicht betroffen.
  - Mechanismus: `test:OrchestratorClientAssertionSignerTest`
- **I-17 Das Vertrauen in ein selbstsigniertes Keycloak-Zertifikat gilt nie JVM-weit.**
  - Worum es geht: Im Demo-Betrieb hat Keycloak ein selbstsigniertes Zertifikat. Das Vertrauen in dieses Zertifikat gilt nur für diese eine Verbindung. Sonst würde jede andere HTTPS-Verbindung im selben Prozess auch gefälschte Zertifikate annehmen.
  - Mechanismus: `type:KeycloakHttp`, `test:KeycloakHttpTest`

## Verfahren, Fremdsysteme und Protokoll

- **I-18 Ein QR-Login meldet einen Browser erst mit dem Bestätigungscode aus der App an, und nur einmal.**
  - Worum es geht: Der Browser zeigt einen QR-Code, und die App bestätigt ihn. Die App zeigt dann einen Code an. Erst wenn der Nutzer diesen Code im Browser eintippt, ist der Browser angemeldet. So kann ein Angreifer einem Opfer nicht seinen eigenen QR-Code unterschieben.
  - Mechanismus: `type:QrLoginBrowserSide`, `test:AuthQrFlowIntegrationTest`
- **I-25 Ein Tool-Modul zählt Versuche nur in seinem eigenen Namensraum, und kein Code wird ohne das Versandlimit seines Moduls verschickt ([ADR-44](adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md)).**
  - Worum es geht: Ein Modul wie SMS führt seine Zähler für Fehlversuche und Versand in einem eigenen Bereich und kann fremde Zähler nicht ändern. Ein Code wird nur verschickt, wenn das Versandlimit es erlaubt. Das begrenzt Kosten und Missbrauch, etwa das massenhafte Verschicken von SMS an ein Opfer („SMS-Bombing“).
  - Mechanismus: `type:RateLimit` (der Namensraum folgt aus der Klasse, nicht aus einem Argument), `archunit:RateLimitArchitectureTest`, `test:AccountRateLimitIntegrationTest`
- **I-20 Der Kern erreicht simulierte Fremdsysteme nur über benannte Kanten oder Ports.**
  - Worum es geht: Das Personenverzeichnis, Nect, KOBIL und die anderen Fremdsysteme sind für den Kern nur über Ports erreichbar, also über feste Schnittstellen. So lassen sich die Simulationen später durch die echten Systeme ersetzen, ohne dass der Kern es merkt.
  - Mechanismus: `type:PersonMasterData`, `archunit:SimulationBoundaryArchitectureTest`
- **I-19 Kein Code und kein Empfänger wird auf der Konsole oder im Log ausgegeben.**
  - Worum es geht: Einmalcodes, TANs, Telefonnummern und E-Mail-Adressen erscheinen nie auf der Konsole oder im Log, auch nicht bei einer abgelehnten Eingabe. Logs werden von vielen gelesen und lange aufbewahrt. Sie wären sonst ein Datenleck.
  - Mechanismus: `archunit:OrchestratorArchitectureTest` (kein `println`, kein `System.out`), `test:NoSecretsInLogIntegrationTest` (fängt jedes Log-Ereignis eines Durchlaufs mit Versand und Prüfung ab, auch bei abgelehnter Eingabe). Außerdem nennen die Wertobjekte in `tool_api.values` den abgelehnten Wert nicht, und der Handler für `400`-Fehler loggt nur Typ und Ort. `test:OrchestratorExceptionTest` (was ein Client in Pfad oder Query schickt, erreicht das Log ohne Steuerzeichen und in der Länge begrenzt. Es kann also keine Logzeile fälschen.)

## Nicht mehr vergebene Nummern

- I-29 (ein Subjekt je Kanal und Evidenz) ist in **I-5** aufgegangen.
