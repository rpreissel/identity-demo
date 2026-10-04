# Sicherheitsaudit (2026-10-03)

Umfang: nur Sicherheit. Der Backend-Kern (`core/`, `contract/`, `tools/`), die Keycloak-Erweiterung
samt Realm-Migrationen, dazu Frontend, Simulationen, Deployment und Abhängigkeiten, soweit sie den
Kern berühren. Maßstab ist [ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md):
Der Kern soll produktionsreif sein, jede Sicherheitszusage gilt ohne unbenannte Annahme an die
Umgebung. Architektur und Codequalität waren nicht Gegenstand; dafür gilt weiter die
[vierte Bewertung](review-2026-09-29-vierte-bewertung.md).

Grundlage: fünf getrennte Prüfungen (Eingang, DPoP und Kanal; Journey, Policy und Identität;
Tool-Module; Keycloak-Erweiterung und -Anbindung; Frontend, Deployment und Abhängigkeiten) mit
vollständigem Lesen der zentralen Klassen und Nachschlagen im Quelltext von Keycloak 26.6.4 und
Spring Security 7.1.0. Jeden Befund ab „mittel“ habe ich selbst am Code nachgeprüft, SA-1
zusätzlich mit einem Testlauf. Ausgangspunkt waren der
[Lesepfad Sicherheit](16-lesepfad-sicherheit.md) und die dort geführten offenen Flanken; was dort
schon steht, wiederholt dieses Dokument nicht.

**Eine Prüfung ist unvollständig.** Der Bericht zu Journey, Policy und Identität brach nach dem
ersten Befund ab (Abschnitt 7). Angekündigt waren dort sieben Befunde und zwei Hinweise; in diesem
Dokument stehen davon nur SA-1 und, im Ansatz, SA-9.

Kürzel: `SA-n` sind Befunde dieses Audits. `S-`, `K-`, `A-` verweisen auf die vierte Bewertung.

## 1. Urteil in drei Sätzen

- **Zwei Befunde sind „hoch“, beide sind kurze Wege an einer zentralen Zusage vorbei.** Das
  Aufbrauchen des Versuchsbudgets wird zurückgerollt: Die Journey bleibt verwendbar, der
  Sperrzähler steht still, ein ausgegebener Code ist nicht mehr auf drei Versuche begrenzt (SA-1,
  durch einen Testlauf belegt). Und Keycloaks eigene Aktion „Passwort ändern“ ist im Realm
  eingeschaltet geblieben: Sie ersetzt das Passwort eines Kontos aus einer `loa1`-Sitzung heraus
  und legt, wenn der Orchestrator ablehnt, ein zweites Passwort in Keycloak an (SA-2).
- **Die Lücken der Keycloak-Seite liegen nicht in der Erweiterung, sondern in dem, was die
  Migration bei Keycloaks Voreinstellungen belässt.** Required Actions, die Realm-Flows, die
  eingebauten Clients und `offline_access` (SA-2, SA-3, SA-7). Die vierte Bewertung hat „Realm
  V1–V6“ als tragfähig geführt, ohne diese Voreinstellungen anzusehen.
- **Drei dokumentierte Zusagen gelten nicht so, wie sie dastehen.** Die Peer-Auth-Assertion bindet
  weder Body noch Query, ADR-7 verspricht Integrität auch über http (SA-4). Das `acr` im Token
  altert nicht, I-32 und 04 §8 versprechen es (SA-5). Die Sperre des Admin-Logins lässt sich mit
  einer abweichenden Schreibweise des Headers umgehen (SA-6). Die Tool-Module selbst und die
  Grenze zwischen Demo und Kern haben gehalten.

## 2. Regressionsprüfung der vierten Bewertung

| Befund | Hält? | Anmerkung |
|---|---|---|
| S-1 Nachweisalter | teilweise | Gilt für Entscheidungen des Orchestrators und den Resume-Pfad. Am Token gilt es nicht (SA-5). |
| S-3 Log-Details | teilweise | Gilt für `OrchestratorException`. Werte aus Proof- und Assertion-Headern erreichen das Log weiter ungefiltert (SA-11). |
| S-4 `ServerInfoController` | ja | `operations` fehlt außerhalb des Demomodus. |
| S-5 `availableTools` | ja | Beide Kanäle schneiden gegen den Katalog; der Body wird aber vorher unbegrenzt gelesen (SA-10). |
| S-6 `targetAcr` | ja | Geprüft, bevor sich am Kanal etwas ändert. |
| K-1 Fehlversuch bei Orchestrator-Fehler | teilweise | Signierte 4xx/5xx zählen nicht mehr. Transportfehler zählen weiter (SA-12). |
| K-2 Nect-Retry | nur im Authenticator | Die Verfahrensverwaltung ruft weder `activationFields` noch `actionFields`; dort besteht K-2 als Teil von K-3 fort. |
| K-4 Pfadsegmente | ja | `segment()` deckt jeden Pfadteil aus Formulardaten. |
| A-1 Abmeldung der Einladung | ja | Beide Federationen melden; der Query-Wert `kcSessionId` ist ungebunden (SA-4). |
| A-2, K-5 Subjekt im Upsert | ja | Fremdes Subjekt `409`, eine Einladung bindet nie über den Upsert. |
| Signierfilter | ja | Signiert nur, was `PeerAuthValidator.verify` annimmt; der Test dazu mockt den Validator (SA-21). |

Die Lesepfad-Aussage „Zeitlimits 3 s/10 s beidseitig, JWKS mit Größenlimit“ (K-4/K-11 alt) stimmt
für das Profil `keycloak` nicht (SA-17).

## 3. Befunde

Reihenfolge nach Schwere. Jeder Punkt nennt Fundstelle, Folge und Gegenmaßnahme. Entscheidungen,
die den Vertrag oder das Modell ändern, sind als solche markiert und dem Inhaber vorzulegen.

### 3.1 Hoch

- **SA-1 Das Aufbrauchen des Versuchsbudgets wird zurückgerollt.**
  `JourneyService.chargeAttempt` (`journey/JourneyService.kt:526-545`) zieht einen Versuch ab,
  setzt die Journey bei null auf `FAILED`, speichert und wirft `OrchestratorException.processAborted`.
  `JourneyService` (`:67`) und `ToolJourneyService` (`:58`) sind
  `@Transactional(noRollbackFor = [ChannelSessionEndedException::class])`; die
  `OrchestratorException` ist dort nicht genannt, Spring rollt also zurück. Verloren geht alles,
  was diese Transaktion geschrieben hat: der letzte Abzug, der Zustand `FAILED`, der Eintrag im
  Sperrzähler (`RateLimitCounter` läuft in der Transaktion des Aufrufers mit), der Eintrag im
  Anmeldeprotokoll und der Trace `TOOL_FAILED`. Der Client sieht `410`, in der Datenbank ist die
  Journey unverändert. `Transition.Abort` (`:426-430`) hat dasselbe Muster.
  `ChannelService.kt:146-147` kennt das Problem und nimmt die Ausnahme dort ausdrücklich aus.
  - Beleg: Ein temporärer Integrationstest (fünf falsche TANs auf eine ToolSession von `auth-sms`)
    ergab die Antworten `200, 200, 410, 410, 410`, danach `auth_journey.lifecycle = STARTED`,
    `attempt_budget = 1` und `rate_limit` (`ACCOUNT`) `failed_count = 2`.
  - Folge: Die Journey nimmt nach dem `410` weiter Eingaben an. Ein ausgegebener Code (TAN,
    E-Mail-Code, Passwort, Freischaltcode, Einmalkennwort, eID-PIN) ist nicht auf drei Versuche
    begrenzt, sondern nur durch seine Gültigkeit; die Konto- und Personensperre löst nie aus, weil
    je Journey höchstens zwei Fehlversuche gezählt werden; die Versuche stehen weder im
    Anmeldeprotokoll noch im Trace. Nicht betroffen ist der QR-Bestätigungscode, der seine Anfrage
    in der Transaktion des Tools verbrennt.
  - Gebrochene Zusagen: I-2, 04 §7, 07 §4, Lesepfad Station 6 („höchstens drei Rateversuche“).
  - Warum die Tests es nicht sehen: `JourneyFallbackChainIntegrationTest` und
    `AuthQrFlowIntegrationTest` prüfen nur den Status `410` der dritten Anfrage, nie eine vierte
    und nie den Zustand in der Datenbank. Die Prüfung der Tool-Module ist derselben Annahme
    gefolgt und hat aus dem `410` auf `FAILED` geschlossen.
  - Fix: Das Ende muss festgeschrieben werden. Entweder eine eigene Ausnahme für die erschöpfte
    oder abgebrochene Journey, die beide Klassen in `noRollbackFor` nennen, oder ein
    abschließender `Step`, aus dem erst die Grenze das `410` macht. Zusätzliche Linie
    (Entscheidung des Inhabers): Ein Tool entwertet seinen Code nach n falschen Eingaben selbst.
  - Test: drei falsche TANs, dann `lifecycle = 'FAILED'` und `failed_count = 3`; eine vierte
    Anfrage mit der richtigen TAN bleibt `410`, der Kanal wird nicht `AUTHENTICATED`. Dasselbe für
    `Transition.Abort`.
- **SA-2 Keycloaks eigene Aktion „Passwort ändern“ ersetzt das Konto-Passwort aus einer
  `loa1`-Sitzung und legt bei Ablehnung ein Passwort in Keycloak an.**
  Die Migration registriert nur die eigene Required Action (`V1__realm.kc.kts:653-667`);
  Keycloaks Voreinstellungen bleiben eingeschaltet. `UPDATE_PASSWORD` ist in jedem neuen Realm
  aktiv, von jedem Client per `kc_action` auslösbar (`UpdatePassword.initiatedActionSupport`:
  `SUPPORTED`) und fragt nur das neue Passwort ab, nicht das bisherige; Bedingung ist allein eine
  Anmeldung in den letzten fünf Minuten. `OrchestratorStorageProvider.updateCredential`
  (`federation/OrchestratorStorageProvider.java:160-172`) reicht das neue Passwort an
  `MgmtPasswordController.set` (`tools/auth_password/api/v1/MgmtPasswordController.kt:86-107`)
  durch; der prüft kein Niveau.
  - Folge A: Wer einen `loa1`-Faktor eines Kontos hält oder kurz an einem angemeldeten Browser
    sitzt, setzt ein neues Passwort und hat damit den zweiten Faktor für `loa2` selbst erzeugt:
    Verfahren verwalten, Konto löschen, QR-Anmeldung bestätigen. Genau das schließt
    `IntentStrategy.kt:10-17` aus, und 04 verlangt für die Verfahrensverwaltung `loa2`.
  - Folge B: Antwortet der Orchestrator mit einem Fehler (kein Passwort eingerichtet,
    `PasswordPolicy`, Ausfall), gibt `updateCredential` `false` zurück. Keycloak fällt dann auf
    seinen lokalen Passwort-Provider zurück (`UserCredentialManager.updateCredential`), speichert
    das Passwort bei sich und meldet Erfolg; `UserCredentialManager.isValid` fragt die lokalen
    Provider auch dann, wenn die Federation „ungültig“ sagt. Dieses Passwort gilt an der Policy
    und an der Kontosperre des Orchestrators vorbei, auch für einen Einladungs-Nutzer, der laut
    `InvitationStorageProvider` kein Credential hat. 06 §4 verspricht für die Änderung über
    Keycloak dieselben Passwortregeln.
  - Fix: In der Migration jede Required Action abschalten, die dem Orchestrator nicht gehört
    (`UPDATE_PASSWORD`, `CONFIGURE_TOTP`, `UPDATE_PROFILE`, `UPDATE_EMAIL`, `VERIFY_EMAIL`,
    WebAuthn-Registrierung, `delete_credential`, Recovery Codes). `updateCredential` gibt für ein
    Passwort nie `false` zurück, sondern wirft, damit Keycloak nicht lokal speichert.
    Entscheidung des Inhabers: Soll es eine Passwortänderung über Keycloak überhaupt geben? Der
    Endpunkt `set` kann das Niveau der Sitzung nicht kennen; sauber wäre, `CredentialInputUpdater`
    zu streichen und die Änderung nur über die Verfahrensverwaltung zu führen.
  - Tests: Migrationstest „nur die eigene Required Action ist aktiv“;
    `OrchestratorStorageProviderTest` „`updateCredential` wirft bei 400, 409 und `IOException`“;
    `e2e-keycloak` „`kc_action=UPDATE_PASSWORD` endet mit Fehler“.

### 3.2 Mittel

- **SA-3 Die eingebauten Anmeldewege des Realms führen am Orchestrator vorbei.**
  Der Orchestrator-Flow ist nur als Override am Browser-Client gebunden
  (`V1__realm.kc.kts:381`); `browserFlow` und `directGrantFlow` des Realms sind Keycloaks
  eingebaute. Keine Migration fasst die eingebauten Clients an: `admin-cli` ist in jedem Realm
  öffentlich mit Direct Access Grants, `account-console` ein öffentlicher Client mit dem
  Standard-Flow. `OrchestratorStorageProvider` bedient Suche und Passwortprüfung für jeden Flow.
  - Folge: Der Passwort-Grant über `admin-cli` und die Anmeldung an der Account-Konsole liefern
    eine Keycloak-Sitzung und Tokens, ohne Kanal, Journey und Anmeldeprotokoll, ohne die
    Tool-Sperre des Betreibers (ADR-32) und ohne den LoA1-Schalter (ADR-42). Die so entstandene
    SSO-Sitzung genügt danach `auth-cookie` im Orchestrator-Flow für Anfragen ohne `acr_values`.
    Zusammen mit SA-2 Folge B ist das lokal gespeicherte Passwort eine vollständige Anmeldung.
  - Fix: `browserFlow` des Realms auf den Orchestrator-Flow binden, `directGrantFlow` auf einen
    Flow, der immer ablehnt; Direct Grants an `admin-cli` und die Clients `account` und
    `account-console` in diesem Realm abschalten, oder in einer ADR begründen, warum sie bleiben.
  - Tests: Migrationstest für die Flow-Bindungen und `admin-cli`; `e2e-keycloak` „Passwort-Grant
    wird abgelehnt“.
- **SA-4 Die Peer-Auth-Assertion bindet weder Body noch Query.**
  `PeerAuthAssertionSigner.sign` (`:33-42`) signiert Methode, Adresse und `channel_binding`;
  `PeerAuthValidator` (`:112-138`) prüft genau das, `htuMatches` (`dpop/RequestUrls.kt:35`)
  schneidet die Query ab. Sicherheitsrelevant im Body des Upserts: `subject`, `amr`,
  `restoreData`, `targetAcr`; in der Query: `kcSessionId` und `sessionExpiresAt` bei
  `restore-data`, `kcSessionId` bei der Abmeldung, `email`/`username` bei der Kontosuche.
  - Folge: Wer aktiv auf der Strecke Keycloak → Orchestrator sitzt, lässt die Assertion stehen und
    tauscht den Body. `KeycloakChannelService.upsertChannel` (`:136-198`) bindet einen frischen
    Kanal an das genannte Subjekt und bucht native Faktoren ungekappt; die Antwort ist korrekt
    signiert, die Erweiterung glaubt ihr. Ebenso lässt sich die Kanalfrist (ADR-43) über die
    Query verschieben.
  - Gebrochene Zusage: ADR-7 („kann sie aber weder fälschen noch einer anderen Anfrage
    unterschieben“, ausdrücklich für den Hop über `http://`) und Lesepfad Station 4 („Integrität
    tragen die Signaturen“). Außerhalb des Demomodus verlangt `ProductionModeCheck` https in beide
    Richtungen; mit durchgehendem TLS ist der Weg zu.
  - Fix: `body_sha256` (auch für den leeren Body) und die Query in die Assertion aufnehmen und
    beides prüfen, spiegelbildlich zur Antwortsignatur; ein fehlender Claim ist eine Ablehnung.
    Sonst ADR-7 und Station 4 berichtigen: Die Assertion authentisiert den Aufrufer, Integrität
    braucht TLS.
  - Test: `PeerAuthRoundTripTest` „gültige Assertion mit geändertem Body → 401, Kanal unverändert“
    und „mit geändertem `kcSessionId` → 401“.
- **SA-5 Das `acr` im Token altert nicht.**
  `KeycloakTokenProvider.tokenFor` (`session/KeycloakTokenProvider.kt:56-62`) erneuert über das
  RefreshToken, solange eines da ist; `resolveAcr` wird nur in `requestAccountToken` gefragt.
  `SessionEvidenceService.invalidateCachedTokens` (`:77-84`) löscht das RefreshToken nur bei einer
  Evidenzänderung, und Altern ist keine. In Keycloak kopiert `OrchestratorAcrAmrMapper.setClaim`
  die Sitzungsnotiz in jedes Token; `OrchestratorNotes.applyAuthData` überschreibt sie nur, wenn
  die Antwort ein `acr` trägt.
  - Folge: Nach 30 Minuten meldet `GET /channels/{id}` `loa1`, das Token sagt weiter `loa2`, bis
    zum Ende der Sitzung (10 Stunden). Im Web-Kanal gilt dasselbe über das RefreshToken der
    Anwendung; stellt der Resume-Schritt nichts wieder her, bleibt die alte Notiz stehen.
    Eine Anwendung sieht nur das Token, und `DPoP-demo-mea0` verweist sie genau dorthin.
  - Gebrochene Zusage: 04 §8 („Nach 30 Minuten meldet ein App-Kanal `loa1`“), I-32. Der
    Mock-`TokenService` im Standardprofil rechnet neu und altert.
  - Fix: App-Kanal: `AppTokenSession` merkt sich das zuletzt gemeldete `acr`; weicht `resolveAcr`
    ab, läuft die Erneuerung über den Grant statt über das RefreshToken. Web-Kanal: Entscheidung
    des Inhabers. Entweder schreibt der Resume-Schritt die Notiz immer, oder der Mapper begrenzt
    ein Niveau über `loa1` am Zeitpunkt des Nachweises, oder die Doku sagt, dass das `acr` den
    Moment der Anmeldung beschreibt und Anwendungen `auth_time` prüfen müssen.
  - Test: `KeycloakTokenProviderTest` mit gestellter Uhr (31 Minuten): erwartet den Grant mit
    `loa1`, nicht den Refresh.
- **SA-6 Die Sperre des Admin-Logins greift bei abweichender Schreibweise des Headers nicht.**
  `AdminLoginRateLimitFilter.basicUserOf` (`admin/AdminLoginRateLimitFilter.kt:39-44`) erkennt
  nur `Basic ` mit Leerzeichen und lässt alles andere ungezählt durch. Springs
  `BasicAuthenticationConverter` prüft nur auf `Basic` und überspringt das sechste Zeichen, was
  immer dort steht.
  - Folge: Ein Header mit einem anderen Trennzeichen wird von Spring normal geprüft, vom Filter
    aber weder gesperrt noch gezählt. Das Raten ist unbegrenzt, und das richtige Passwort kommt
    auch während einer Sperre durch. Umgekehrt kann jeder den Betreiber mit fünf Anfragen je
    15 Minuten aussperren; das war bekannt und gewollt, wiegt aber schwerer, wenn die Sperre den
    Angreifer nicht trifft. Dazu ist der Filter „prüfen, dann zählen“: Parallele Anfragen
    passieren alle die Prüfung, bevor der erste Fehlversuch gebucht ist (07 §4 schließt dieses
    Muster für alle Zähler aus).
  - Fix: den Header nicht ein zweites Mal selbst lesen, sondern Springs Converter im Filter
    verwenden oder Sperre und Zählung in den Authentifizierungsweg legen; den Versuch vor der
    Prüfung in einem `UPDATE` buchen.
  - Test: `AdminIntegrationTest` mit beiden Schreibweisen: der sechste Fehlversuch ist `429`,
    das richtige Passwort während der Sperre ebenfalls; 20 parallele Fehlversuche ergeben
    höchstens fünf `401`.
- **SA-7 `offline_access` steht dem öffentlichen Browser-Client offen.**
  Der Client wird ohne Scope-Listen angelegt (`V1__realm.kc.kts:367-402`) und erhält die
  optionalen Scopes des Realms; föderierte Nutzer erben die Default-Rolle `offline_access`.
  - Folge: Ein Offline-Token unterliegt nicht den Fristen aus V5 (SSO idle 30 Minuten, max
    10 Stunden), übersteht die Abmeldung und das Sitzungsende des Orchestrators (ADR-43) und
    behält das `acr` seiner Entstehung (SA-5).
  - Fix: `offline_access` aus den optionalen Scopes der drei Projekt-Clients nehmen oder die
    Rolle aus den Default-Rollen des Realms.
  - Nicht verifiziert: ein Glied der Kette (Abschnitt 7). Ein Aufruf von
    `optionalClientScopes` an einem migrierten Realm entscheidet es.
- **SA-8 Keycloak 26.6.4 ist von einer als kritisch geführten Meldung betroffen; ob sie hier
  greift, ist offen.** `gradle/libs.versions.toml:26`, `keycloak-extension/Dockerfile:15`,
  `compose.yml:28`, `openshift/local-up.sh:14`. OSV führt für 26.6.4 GHSA-4gv3-mc9p-5wqc
  (CVE-2026-18963, Umgehung im Reset-Credentials-Flow, behoben in 26.6.6) und
  GHSA-wcvj-vpvw-9rr5 (Parameter Pollution bei breiten Redirect-URIs; die Voraussetzung ist mit
  `…/*` in `application-keycloak.yml:80,120` erfüllt). Keine Migration setzt
  `resetPasswordAllowed`; ob die Umgehung diesen Schalter braucht, ließ sich nicht klären. Weil
  `updateCredential` ein in Keycloak gesetztes Passwort zum Konto durchreicht (SA-2), wäre die
  Folge eine Kontoübernahme. Die Meldungen stammen aus der OSV-Abfrage, nicht aus eigener
  Kenntnis.
  - Fix: mindestens 26.6.6 an allen vier Stellen; Redirect-URIs auf die echten Rücksprungpfade
    verengen; `resetPasswordAllowed = false` ausdrücklich in die Migration.
- **SA-9 (unvollständig geprüft) Ein Konto übernimmt einen Platzhalter, ohne dass dessen
  nachgewiesene Identität zu ihm passen muss.** `JourneyActionExecutor.accountOf`
  (`journey/JourneyActionExecutor.kt:145-158`) und `AccountMerge.decide`
  (`domain/journey/AccountRules.kt:78-83`): Ist das Konto der Sitzung nicht verwerfbar und das
  aufgelöste verwerfbar, folgt `AbsorbResolved` und `AccountService.absorbDisposableAccount`
  überträgt Anker und Claims des Platzhalters. Der Zweig `Unresolved` verlangt dagegen
  `attestationFits`, die Korrelation `checkCorrelation`, der Adresswechsel
  `checkAttestationMove`. Die Asymmetrie steht im Code. Das Szenario, die Rolle von
  `AnchorDecision` beim Übertragen und die Schwere stammen aus dem abgebrochenen Bericht und sind
  nicht zu Ende geprüft; dort hieß es „mittel, hoch sobald eID und Nect nicht mehr `demoOnly`
  sind“.
  - Fix (Richtung): vor `AbsorbResolved` dieselbe Passprüfung wie im Zweig `Unresolved`; ein
    Platzhalter mit einer anderen nachgewiesenen Person ist `IdentityConflictException`.
    Vorher den Befund zu Ende prüfen.

### 3.3 Niedrig

- **SA-10 Kein Limit für Anfragekörper; die Keycloak-Endpunkte lesen den Body vor der Prüfung der
  Assertion.** In `application.yml` steht kein Größenlimit. `KeycloakChannelController.kt:68-78`:
  Spring löst `@RequestBody` auf, bevor die Methode `peerAuthValidator.validate` ruft;
  `availableTools`, `amr`, `restoreData` tragen nur `@NotEmpty`. Ohne jede Berechtigung lässt sich
  ein sehr großer Body vollständig einlesen, bevor `401` kommt; im App-Kanal genügt ein
  kostenloser DPoP-Schlüssel. Fix: ein früher Filter, der `Content-Length` über etwa 64 KB
  ablehnt und gestückelte Bodies kappt; `@Size` an Listen und Zeichenketten; die Assertion vor dem
  Body prüfen (Argument-Resolver wie `@BindingKey`). Test: 1 MB auf beiden Endpunkten → `413`.
- **SA-11 Werte aus Proof- und Assertion-Headern erreichen das Log ungefiltert (S-3
  unvollständig).** `OrchestratorExceptionHandler.kt:36,46` loggt `e.message` roh; hinein fließen
  `alg` (`DpopValidator.kt:80`, `PeerAuthValidator.kt:75`), `kid` (`PeerAuthValidator.kt:85`) und
  `userVerification` (`DeviceProofValidator.kt:85,144`). `OrchestratorException.loggable` gilt
  nur für `OrchestratorException`. Eine Anfrage ohne Berechtigung schreibt so Zeilenumbrüche und
  bis zu einigen Kilobyte ins Log. Fix: die Meldungen von `DpopValidationException` und
  `PeerAuthValidationException` durch `loggable` führen. Test: `NoSecretsInLogIntegrationTest` mit
  `kid` und `alg`, die `\n` enthalten.
- **SA-12 Der Fix zu K-1 deckt Transportfehler nicht.** `OrchestratorAuthenticator.java:72-75,
  136-139` und `OrchestratorUpdateAuthenticator.java:63-66`: Verbindungsfehler, Zeitüberschreitung
  und unsignierte Antwort sind `IOException`, aber keine `OrchestratorApiException`, und enden in
  `context.failure(INTERNAL_ERROR)`. Keycloak bucht auch `FAILED` dem angemeldeten Nutzer als
  Fehlversuch (`DefaultAuthenticationFlow.processResult`, `AuthenticationProcessor.logFailure`).
  Die Sperre wirkt nur in Keycloaks nativen Formularen, also im Modus nach ADR-42 und in den
  eingebauten Flows (SA-3). Fix: bei Transportfehlern `context.challenge(errorForm(…))`, nie
  `failure()`. Test: gemockter Kontext, `IOException` → weder `failure` noch `failureChallenge`.
- **SA-13 `demo.mode=false` startet ohne das Profil `keycloak` und gibt unsignierte Tokens aus.**
  `ProductionModeCheck.violations` (`:41-67`) prüft kein Profil; eine leere Keycloak-Adresse
  besteht die https-Prüfungen. `MockTokenProvider` ist `@Profile("!keycloak")`, `TokenService`
  stellt ein `PlainJWT` (`alg=none`) aus. Ein Deployment, das `SPRING_PROFILES_ACTIVE=keycloak`
  vergisst, läuft mit Tokens, die jeder fälschen kann; 14 §3 („Start verweigert unsichere
  Konfiguration“) gilt für diesen Fall nicht. Fix: Verstoß in `ProductionModeCheck`, wenn kein
  echter `TokenProvider` aktiv ist, oder `@OnlyInDemoMode` am Mock. Test in
  `ProductionModeCheckTest`.
- **SA-14 (Umgebung) Auf OpenShift ist die „nur localhost“-Grenze der H2-Konsole über
  `X-Forwarded-For` zu umgehen; schärfer als `DPoP-demo-9msv` beschreibt.** Spring Boot schaltet
  auf Kubernetes die Auswertung der Forward-Header ein; Tomcats `RemoteIpValve` vertraut in der
  Voreinstellung allen privaten Adressbereichen; H2 prüft nur `getRemoteAddr()` auf Loopback.
  `openshift/identity-demo.yaml` setzt kein `DEMO_MODE`, die Konsole ist an und über `openChain`
  offen. Ein Client mit privater Adresse (Intranet, anderer Pod) erreicht so die Konsole mit `sa`
  und leerem Passwort: die ganze Datenbank samt `node_signing_key` und KOBIL-PINs. Compose und
  `local-up.sh` sind nicht betroffen. Jedes Glied ist am Code oder Bytecode gelesen, die Kette
  nicht ausgeführt. Fix: `/h2-console/**` hinter die Admin-Kette (in 9msv schon vorgesehen) oder
  `SPRING_H2_CONSOLE_ENABLED=false` im Manifest; `server.tomcat.remoteip.internal-proxies` nur
  auf den Router.

### 3.4 Hinweise

- **SA-15 `ProductionModeCheck` nimmt `{noop}…` als Hash.** `:44` prüft nur auf `{`;
  `DEMO_ADMIN_PASSWORD={noop}admin` startet außerhalb des Demomodus. Fix: Positivliste
  (`{bcrypt}`, `{argon2}`, `{scrypt}`, `{pbkdf2}`).
- **SA-16 `LoginCompletion` und der Rang der Niveaus.** `LoginCompletion.java:50`,
  `OrchestratorNotes.java:193-198`: `acrRank("loa3")` ist -1 (nur `isKnownAcr` hat `loa3`
  gelernt); eine Antwort mit `loa3` wird im LoA-1- und LoA-2-Subflow mit `INTERNAL_ERROR`
  abgelehnt. Ein unbekanntes `targetAcr` in der Konfiguration vergleicht -1 mit -1, und jede
  Antwort besteht. Fix: `loa3` als Rang 3; ein unbekanntes Ziel ist eine Ablehnung. Zwei Fälle in
  `LoginCompletionTest`. Erreichbarkeit von `loa3` im Subflow ist angenommen (`DPoP-demo-wzcm`).
- **SA-17 Das JWKS-Größenlimit gilt im Profil `keycloak` nicht; Abruf unter einer Sperre ohne
  negativen Cache.** `KeycloakJwkSource.kt:54-55`: Mit `KeycloakHttp` liest `getText` den ganzen
  Body, das Limit von 64 KB steht nur im Ersatzzweig. `:39-58`: Ein gescheiterter Abruf lässt
  `cachedAt` stehen; solange Keycloak nicht antwortet, versucht jede Anfrage mit unbekanntem `kid`
  den Abruf erneut, unter der gemeinsamen Sperre, und endet als `500`. Fix: Limit auch dort; den
  Fehlschlag merken; den IO-Fehler als `PeerAuthValidationException` melden. Lesepfad Station 4
  berichtigen.
- **SA-18 Der JWK-Thumbprint wird über die Kodierung des Clients berechnet.**
  `JwkThumbprintService.kt:32-59`: Nimbus' Base64-Decoder duldet abweichende Schreibweisen, ein
  Schlüssel hat damit mehrere Thumbprints. Gefundene Folge: Die Prüfung „das Geräte-Credential ist
  nicht der DPoP-Schlüssel des Kanals“ (`EnrollDeviceFlow.kt:33`) lässt sich mit demselben
  Schlüssel in anderer Kodierung bestehen. Kein Replay und kein Zugriff über Kanäle hinweg. Fix:
  die Koordinaten aus dem geparsten Schlüssel neu kodieren, bevor gehasht wird.
- **SA-19 Endpunkte des App-Kanals nehmen eine Peer-Auth-Bindung an.** `ChannelController.kt:52,
  76-81` akzeptiert jedes `@BindingKey`; eine Keycloak-Assertion legte einen App-Kanal mit
  `bindingKeyRef = "kc:…"` an. Nur mit Keycloaks Schlüssel möglich und danach unbrauchbar; I-8
  wäre mit einer Variante „nur DPoP“ sauberer.
- **SA-20 Bootstrap nimmt jede `jwks-url`.** `MigrationClientBootstrapFactory.java:47-53,
  101-104`; compose nutzt http. Der Schlüssel dahinter authentisiert einen Client mit
  `create-realm`. `ProductionModeCheck` sieht diese Einstellung auf Keycloaks Seite nicht. Fix:
  Die Erweiterung lehnt alles außer https ab, solange keine Entwickler-Option gesetzt ist.
- **SA-21 Kleinere Punkte der Keycloak-Seite.** `OrchestratorClient.java:311,346` puffert die
  ganze Antwort vor der Signaturprüfung (Größenlimit fehlt). `KeycloakResponseSigningIntegrationTest`
  mockt `PeerAuthValidator`; der Fall „gefälschte Assertion“ besteht, weil der Mock wirft, ein
  Fall mit dem echten Validator fehlt. Redirect-URIs mit `…/*` (`application-keycloak.yml:85,119`).
- **SA-22 Arbeitsdaten von `ident-fsc` liegen seit ADR-49 im Klartext in
  `orchestrator.tool_session.data`.** `IdentFscToolSession` trägt KVNR, Partnernummer, Namen und
  Geburtsdatum; `ident-fsc` ist nicht `demoOnly`. Die Daten bleiben bis etwa 24 Stunden nach
  Ablauf der ToolSession (`tool-session.retention`), auch nach `DONE`. Keine neue Fähigkeit für
  einen Angreifer, aber mehr Fläche unter `DPoP-demo-bo1w`. Entscheidung des Inhabers: die im
  ADR-49 vorgesehene Verschlüsselung am Codec vorziehen oder den Ort in ADR-49 und Station 12
  nennen; Arbeitsdaten bei `DONE` leeren.
- **SA-23 Der KOBIL-PIN reist in `stepData`, nicht im Demo-Block.** `KobilOtpStep.kobilPin`,
  `KobilActivationStep.pin/unlockSecret` werden unabhängig vom Demomodus ausgegeben. Das ist
  gewollt (ADR-21, ADR-22) und an Gerätebindung und Entsperrung geknüpft; Station 13 („keine
  Klartext-Codes in Antworten“) nennt die Ausnahme nicht.
- **SA-24 CI und Lieferkette.** Actions sind per Tag statt per SHA eingebunden, auch die, die ein
  Geheimnis und `id-token: write` erhalten (`claude.yml:35`, `claude-code-review.yml:36`);
  `ci.yml` ohne `permissions`; `gradle-wrapper.properties` ohne `distributionSha256Sum`;
  `dependabot.yml` deckt `keycloak-theme`, `site` und Docker nicht; `npm audit` läuft nur für
  `frontend/`; `init-local:50-66` schreibt entschlüsselte Werte unmaskiert nach
  `~/.gradle/init.d/`. Kein CVE-Tor für Gradle.
- **SA-25 (Umgebung) Keine NetworkPolicy.** `openshift/identity-demo.yaml` hält 9080, 9000 und
  8081 aus dem Service heraus, über die Pod-Adresse sind sie aus anderen Pods erreichbar.
  Öffentliche Route mit `admin/admin` am Orchestrator (`DPoP-demo-x25a`).

## 4. Abhängigkeiten (S-9 der vierten Bewertung)

Versionen aus den Jars unter `build/podman/`, abgefragt gegen OSV.dev; `npm audit` in `frontend/`,
`keycloak-theme/`, `site/`. Die genannten Meldungen sind so wiedergegeben, wie OSV sie liefert.

| Komponente | Version | Stand |
|---|---|---|
| Keycloak (Server, SPI) | 26.6.4 | betroffen, behoben in 26.6.6 (SA-8) |
| tomcat-embed-core | 11.0.22 | drei Meldungen, behoben in 11.0.25; hier nicht erreichbar (keine Container-Constraints, kein DIGEST/FORM) |
| Jackson 3 (Orchestrator) | 3.1.4 | Meldungen, behoben in 3.1.7; die geprüften nicht erreichbar, sieben nicht einzeln geprüft |
| Jackson 2 (springdoc) | 2.21.4 | Meldungen, behoben in 2.21.7; nur im Demomodus |
| Jackson 2 (Erweiterung, eingepackt) | 2.19.0 | 15 Meldungen; kein Default-Typing, nicht erreichbar; anheben |
| bcprov-jdk18on | 1.81 | vier Meldungen, behoben in 1.85; nur für Argon2 im Einsatz |
| log4j-api | 2.25.4 | eine Meldung; nur die Brücke zu slf4j |
| Spring Boot / Framework / Security | 4.1.0 / 7.0.8 / 7.1.0 | kein Treffer |
| Modulith, springdoc, nimbus-jose-jwt, H2, Hibernate, Flyway | 2.1.1 / 3.1.1 / 10.10 / 2.4.240 / 7.4.1 / 12.4.0 | kein Treffer |
| keycloak-admin-client, resteasy, Kotlin | 26.0.12 / 6.2.15 / 2.4.20 | kein Treffer |
| `frontend`, `keycloak-theme` (npm) | Lockfile | `npm audit`: 0 |
| `site` (npm: vitepress, mermaid) | Lockfile | 8 Meldungen, nur Build |

Empfehlung: den aktuellen Patch von Boot 4.1.x nehmen und prüfen, dass er Tomcat ≥ 11.0.25 und
Jackson ≥ 3.1.7/2.21.7 bringt; ein CVE-Tor für Gradle in die CI (OSV-Scanner oder
dependency-check).

## 5. Reihenfolge

Die zwei Entscheidungen des Inhabers, die SA-2 und SA-5 brauchten, sind gefallen (Abschnitt 8).

**Phase 1 – die zwei hohen Befunde**

1. SA-1 Ende der Journey festschreiben, Test mit vierter Anfrage und Datenbankzustand.
2. SA-2 Required Actions abschalten, `updateCredential` wirft; SA-3 Realm-Flows und eingebaute
   Clients; SA-7 `offline_access`. Ein Migrationsskript, ein Migrationstest.
3. SA-8 Keycloak auf 26.6.6 oder neuer.

**Phase 2 – gebrochene Zusagen**

4. SA-4 Body und Query in die Assertion, oder ADR-7 berichtigen.
5. SA-5 Doku berichtigen: 04 §8, I-32, Lesepfad Station 5, `DPoP-demo-mea0` (Abschnitt 8).
6. SA-6 Admin-Sperre über Springs eigenen Converter, Zählung als ein `UPDATE`.
7. SA-9 zu Ende prüfen und schließen; die abgebrochene Prüfung nachholen (Abschnitt 7).

**Phase 3 – Härtung**

8. SA-10 Body-Limit; SA-11 Log-Filter; SA-12 Transportfehler; SA-13 und SA-15 im
   `ProductionModeCheck`; SA-14 H2-Konsole hinter die Admin-Kette.
9. SA-16 bis SA-21.

**Phase 4 – Umgebung und Aufräumen**

10. SA-22 bis SA-25, Abhängigkeiten (Abschnitt 4).

Nachzuziehen in der Doku: Lesepfad Stationen 1, 4, 5, 6, 13 und die Tabelle in Abschnitt 15;
ADR-7; 04 §8; 07 §4.

## 6. Geprüft und in Ordnung

- **DPoP.** `typ` je Validator fest, nur ES256/384/512, nur EC, private und symmetrische Schlüssel
  abgelehnt, `iat`-Fenster richtig, der Replay-Eintrag überlebt die Gültigkeit des Proofs,
  `INSERT` in eigener Transaktion. DPoP, Geräte-Proof und Peer-Auth stehen nicht füreinander ein.
  Host-Header-Tricks bringen nichts, weil der Client sein `htu` selbst signiert.
- **Kanalbindung.** Jede `channelSessionId`, `toolSessionId` und `methodInstanceId` wird auf ihren
  Kanal aufgelöst und mit der Bindung des Aufrufers verglichen; abgelaufene Kanäle fallen bei der
  Suche heraus, beendete an `LiveChannel`. Der Token-Endpunkt gilt nur für lebende,
  angemeldete App-Kanäle; das erste Token entsteht im Übergang, parallele Abrufe öffnen keine
  zweite Sitzung.
- **Spring-Security-Ketten.** Matcher und MVC nutzen dieselbe Pfadauflösung; `;`, `//` und
  kodierte Punkte lehnt die Firewall ab; alle Controller unter `/orchestrator/admin` sind gedeckt.
  `csrf.disable()` ist richtig, weil kein Endpunkt eine Cookie-Sitzung nutzt.
- **Grenze zwischen Demo und Kern.** Alle fünf Simulations-Controller und die Demo-Controller
  tragen `@DemoSurface`; mit `demo.mode=false` gibt es keinen HTTP-Weg, Freischaltcodes,
  Einladungen, Nect-Ergebnisse oder KOBIL-Aktivierungen zu erzeugen oder Postausgänge zu lesen.
  Seed-Daten sind außerhalb des Demomodus ausgeschlossen, `demoOnly`-Tools abgeschaltet. Werte
  wie `no` oder `1` für `DEMO_MODE` lassen den Start scheitern statt halb als Demo zu laufen.
- **Tool-Module.** Passwort: Argon2id, Dummy-Hash, Rehash, Länge vor dem Hashen begrenzt. SMS und
  E-Mail: Codes als HMAC mit Pepper, Vergleich in konstanter Zeit, Versandlimit auf der
  normalisierten Adresse, einheitliche Antworten. QR: Zustandswechsel als bedingtes `UPDATE`,
  drei falsche Bestätigungscodes verbrennen die Anfrage, erwartetes Konto gebunden. Gerät: Proof
  an die ToolSession-Adresse gebunden. KOBIL: Typprüfung der Enrollment-Referenz vorhanden, PIN
  nur nach Entsperrung und nur in der einen Antwort. Einladung, Freischaltcode, KVNR: einheitliche
  Ablehnung, Zähler an der richtigen Person. Nect: Präfix mit Schrägstrich hinter `/realms/`,
  leere Liste lehnt ab, Fall an die ToolSession gebunden. Die Umbauten der letzten Tage
  (ADR-49, Tool als Deklaration) haben keine Prüfung verloren; die Zusage „drei Versuche“ hängt
  allerdings an SA-1.
- **ADR-49.** Ein Modul liest nur seine eigenen Arbeitsdaten (Typname mit Modulkennung); keine
  polymorphe Deserialisierung; Codes nur als Hash; gleichzeitige Schreiber scheitern an
  `@Version`.
- **Keycloak-Erweiterung.** Antwortprüfung mit festem `typ`, `req`, `status`, `body_sha256`, auch
  bei Fehlern und `304`. Der HTTP-Client folgt keinen Weiterleitungen. Eigener Grant: nur
  vertraulicher Client mit Attribut, Positivliste für `acr`/`amr`, Fortsetzung nur für denselben
  Nutzer. QR-Status nur mit Keycloaks Cookie. Kontolöschung verlangt `manage-users` und räumt
  auch Offline-Sitzungen. Federation: höchstens ein Treffer bei exaktem Namen, Ausfall wirft,
  Attribute nur lesbar. Templates escapen; `?no_esc` nur an `kcSanitize` und dem bekannten
  Demo-Picker. Kein Weg für Login-CSRF über die Action-URL gefunden (K-8).
- **Frontend.** Kein `innerHTML`, kein `eval`, kein `postMessage`-Handler, kein Service Worker,
  keine Tokens in der Konsole; PKCE S256; Schlüssel nicht exportierbar. Fehlendes `state`/`nonce`
  und CSP sind bekannt (`DPoP-demo-dm2j`).
- **Deployment.** Dockerfile mit eigenem Nutzer, ohne Geheimnisse in den Schichten; keine
  Geheimnisse im Repository; in der CI kein `pull_request_target`, keine fremde Eingabe in `run:`.

## 7. Nicht verifiziert und nicht geprüft

- **Die Prüfung von Journey, Policy und Identität ist unvollständig.** Ihr Bericht wurde beim
  Schreiben des zweiten Befunds von den Sicherheitsfiltern des Modells gestoppt und nicht
  wiederholt. Angekündigt waren ein hoher, drei mittlere und drei niedrige Befunde sowie zwei
  Hinweise; angekommen sind SA-1 und der Anfang von SA-9. Fünf Befunde und zwei Hinweise dieses
  Bereichs (Policy, Kontozuordnung, Sperren, Löschung, Aufbewahrung) fehlen also. Dieser Bereich
  braucht einen zweiten Durchgang.
- SA-1 ist ausgeführt (temporärer Test, wieder entfernt). Alles andere ist gelesen, nicht gegen
  ein laufendes System gespielt; insbesondere kein Befund gegen ein laufendes Keycloak.
- SA-2: Die Faktorkombination, die nach dem Passwortwechsel `loa2` ergibt, ist aus
  `DefaultAuthPolicy.combinedAcr` gelesen. SA-3: welches `acr` Keycloaks eigener Mapper ohne die
  Notiz des Orchestrators schreibt, ist angenommen.
- SA-4: dass der Upsert mit einem nativen Faktor bei Floor `loa1` `authenticated` erreicht, ist
  nicht bis in die Strategie verfolgt.
- SA-7: Der JPA-Adapter, der beim Anlegen des Clients die optionalen Scopes zuweist, lag nicht in
  den lokalen Quellen.
- SA-8 und Abschnitt 4: Meldungen aus OSV; Anwendbarkeit nur für die genannten geprüft.
  Nicht geprüft: Betriebssystempakete der Basis-Images, Gradle-Plugins, Testabhängigkeiten.
- SA-14: das Verhalten des Routers beim Anhängen der Client-Adresse ist angenommen.
- `demo.mode=false` wurde nicht ausgeführt; die Aussagen zur Demo-Grenze stammen aus dem Lesen
  der Bedingungen an den Beans.

## 8. Entscheidungen des Inhabers (2026-10-03)

- **SA-2: Es gibt keine Passwortänderung über Keycloak.** Ein Passwort wird nur über die
  Verfahrensverwaltung des Orchestrators geändert, hinter deren Niveauprüfung. Daraus folgt:
  `OrchestratorStorageProvider` verliert `CredentialInputUpdater`, der Endpunkt
  `MgmtPasswordController.set` und `OrchestratorClient.setPassword` entfallen, die Migration
  schaltet `UPDATE_PASSWORD` und die übrigen fremden Required Actions ab. Damit entfällt auch das
  Zurücksetzen eines Passworts aus Keycloaks Admin-Konsole; 06 §4 ist nachzuziehen.
- **SA-5: Das `acr` im Token hat Keycloaks Bedeutung.** Es beschreibt die Anmeldung, nicht das
  laufend aktuelle Niveau. So verhält sich Keycloak selbst: `AcrProtocolMapper.getAcr` liest das
  Niveau aus der Notiz der Client-Sitzung, ein Refresh schreibt denselben Wert erneut, und
  `loa-max-age` wirkt erst beim nächsten Anmeldedurchlauf. Der Code bleibt, wie er ist; berichtigt
  wird die Doku. 04 §8 und I-32 sagen künftig: Die Alterung gilt für Entscheidungen des
  Orchestrators und für jeden neuen Durchlauf; eine Anwendung, die ein frisches `loa2` braucht,
  fragt mit `acr_values` neu an oder prüft `auth_time`. SA-5 ist damit kein Fehler im Code mehr,
  sondern eine falsche Zusage in der Doku. In Kauf genommen ist, dass `GET /channels/{id}` nach
  30 Minuten `loa1` meldet, während das Token desselben Kanals weiter `loa2` trägt.

## 9. Stand der Umsetzung (2026-10-03)

Issues unter dem Epic `DPoP-demo-164n`, je Befund eines. `./gradlew test` und
`./gradlew :keycloak-extension:test` laufen grün. Gegen den compose-Stack (Keycloak 26.7.5) geprüft
am 2026-10-04: `e2e-keycloak` 16 von 16 grün (beide Themes, QR, Vorgangszugang, also auch
`body_sha256` und das neue Template); V7 lief durch, und die Admin-API zeigt `browserFlow`
`orchestrator-browser`, `directGrantFlow` mit `deny-access-authenticator`, `resetPasswordAllowed`
aus, als einzige Required Action `orchestrator-manage-methods`, `account`/`account-console` aus,
`admin-cli` ohne Direct Grant (ein Passwort-Grant antwortet `unauthorized_client`) und kein
`offline_access` an den drei Projekt-Clients. Gegen einen Cluster ist nichts gespielt.

**Erledigt**

- ~~SA-1~~ Das Ende einer Journey ist `JourneyEndedException`, die jede Transaktionsgrenze in
  `noRollbackFor` nennt; `FAILED`, Abzug und Sperrzähler bleiben. Test:
  `JourneyFallbackChainIntegrationTest` (vierte Anfrage mit richtiger TAN, Zustand in der Datenbank).
- ~~SA-2, SA-3, SA-7~~ `V7__locked_down_defaults`: fremde Required Actions aus, Realm-Browser-Flow
  auf den Orchestrator, Direct Grant auf einen Flow, der ablehnt, `admin-cli` ohne Passwort-Grant,
  Account-Konsole aus, `offline_access` aus den Projekt-Clients, „Passwort vergessen“ aus. Die
  Federation wirft bei jeder Passwortänderung (`ReadOnlyException`); der Endpunkt
  `enroll-password/mgmt` ist entfernt, `api/published/v1.yaml` neu eingefroren. Tests:
  `LockedDownDefaultsMigrationTest`, `OrchestratorStorageProviderTest`. ADR-38 Nachtrag.
- ~~SA-8~~ Keycloak 26.7.5: Version 26.6.6 gibt es weder auf Maven Central noch auf quay.io. Das
  Login-Template ist von 26.7.5 neu kopiert.
- ~~SA-5~~ Doku berichtigt (04 §8, I-32, Lesepfad Station 5).
- ~~SA-6~~ Die Sperre liest den Header mit Springs eigenem Parser und bucht jeden Versuch vor der
  Prüfung in einem `UPDATE`. Test: `AdminIntegrationTest` (`BasicX…`, 20 parallele Versuche).
- ~~SA-9~~ `AccountMerge` übernimmt einen Platzhalter nur, wenn das Konto der Sitzung dessen
  Identität annehmen darf (`attestationFits`). Test: `AccountRulesTest`.
- ~~SA-10~~ `RequestBodyLimitFilter` (64 KB; `413`, ohne Längenangabe `400`). Test:
  `RequestBodyLimitIntegrationTest`.
- ~~SA-11~~ Meldungen aus Proof und Assertion gehen gefiltert ins Log
  (`OrchestratorExceptionHandlerTest`).
- ~~SA-12~~ Die Authenticatoren rufen nie `failure()`; ein Fehler zeigt eine Seite
  (`NoBruteForceBookingTest`).
- ~~SA-13, SA-15~~ `ProductionModeCheck`: nur echte Hash-Encoder, kein Start mit
  `MockTokenProvider` außerhalb des Demomodus.
- ~~SA-14~~ OpenShift-Manifest schaltet die H2-Konsole ab; 08 erklärt es.
- ~~SA-16~~ `loa3` hat Rang 3; ein unbekanntes Ziel lässt nichts durch (`LoginCompletionTest`).
- ~~SA-17~~ JWKS: Größenlimit auch über `KeycloakHttp`, höchstens ein Abruf je 30 s auch bei
  Fehlern, der letzte gute Satz bleibt (`KeycloakJwkSourceBackoffTest`).
- ~~SA-18~~ Thumbprint über den neu kodierten Schlüssel (`JwkThumbprintServiceTest`).
- ~~SA-20~~ Bootstrap: `jwks-url` nur über https oder Loopback, sonst mit ausdrücklicher Option
  (`MigrationClientJwksUrlTest`; compose setzt sie).
- ~~SA-23~~ Lesepfad Station 13 nennt die Ausnahme für den KOBIL-PIN.
- Abhängigkeiten: Boot 4.1.1, Tomcat 11.0.26, Jackson 3.1.7 und 2.21.7 (auch in der Erweiterung),
  BouncyCastle 1.85.2.

**Teilweise**

- SA-21: Antworten an die Erweiterung sind auf 1 MB begrenzt. Offen: Test des Signierfilters mit
  echtem Validator; Redirect-URIs ohne Wildcard (ändert das Realm-Setup und damit einen Neuaufbau).
- SA-24: Actions per SHA, `permissions` in `ci.yml`, Prüfsumme der Gradle-Distribution, Dependabot
  für `keycloak-theme`, `site` und Docker. Offen: CVE-Tor für Gradle, `init-local`.

**Nach Entscheidung des Inhabers (2026-10-04) erledigt**

- ~~SA-4~~ Die Assertion bindet die Query (`htu`) und den Body (`body_sha256`);
  `PeerAuthBodyCaptureFilter` liest den Body einmal mit. Tests: `PeerAuthValidatorTest`,
  `PeerAuthBodyCaptureFilterTest`. ADR-7 und Lesepfad Station 4 nachgezogen.
- ~~SA-19~~ `@BindingKey(dpopOnly = true)` an `/app/channels` und `device-link`
  (`DpopBindingKeyResolverTest`).
- ~~SA-22~~ Der Abschluss einer Tool-Sitzung leert ihre Arbeitsdaten; ADR-49 und Station 12 nennen
  den Ort (`ToolSessionDataClearedIntegrationTest`).

**Offen**

- SA-25 NetworkPolicy für OpenShift.
- Passwortwechsel (`DPoP-demo-164n.27`): `enroll-password` wird bei aktivem Passwort nicht angeboten;
  ein Wechsel geht heute nur über Entfernen und neu Einrichten. Zu entscheiden: Ersetzen anbieten,
  darauf eine Required Action `orchestrator-change-password` und der Anstoß durch den Admin per
  `execute-actions-email`.

## 10. Zweiter Durchgang: Journey, Policy, Identität (2026-10-04)

Der erste Bericht zu diesem Bereich war abgebrochen (Abschnitt 7). Dieser Durchgang hat die
zentralen Klassen selbst gelesen: `DefaultAuthPolicy`, `CredentialRules`, `AccountRules`,
`ManageAuthMethodsStrategy`, die schreibenden Teile von `JourneyService` und
`JourneyActionExecutor`, `ToolJourneyService`, `ChannelService`, `AccountLockoutService`,
`PersonLockoutService`, `AccountDeletionService`, `AnchorDecision`, `IdentityMatchingService`,
`RetentionJob`. Die fünf verlorenen Befunde des ersten Berichts lassen sich nicht wiederherstellen;
was hier steht, ist neu gefunden.

**Befunde**

- ~~SA-26 (mittel) Die Kontosperre galt für Verfahren mit bekanntem Konto nur beim Aktivieren.~~
  `ToolJourneyService.beginActivation` prüfte `assertNotLocked`, jede spätere Eingabe in derselben
  Tool-Sitzung nicht. Belegt mit einem Testlauf: drei Passwort-Sitzungen geöffnet, fünf Fehlversuche
  sperren das Konto (`locked_until` gesetzt), danach meldet die dritte Sitzung mit dem richtigen
  Passwort `AUTHENTICATED`. Damit galt weder „gesperrt auch für das richtige Passwort“ (07 §3c, §4)
  noch die Grenze von fünf Versuchen: Jede vorher geöffnete Sitzung brachte drei weitere.
  Erledigt: `ToolJourneyService.loadCurrent` prüft die Sperre bei jedem schreibenden Aufruf eines
  `KNOWN_ACCOUNT_AUTH`-Tools. Test: `AccountRateLimitIntegrationTest`. Folge: Auch „Zurück“ und
  „Abbrechen“ in einer solchen Sitzung antworten während der Sperre mit `423`.
- **SA-27 (niedrig) Die Kontosperre ist „prüfen, dann zählen“.** Lookup-Tools fragen
  `Lockouts.isLockedOut` im Controller, gezählt wird erst in `applyOutcome`; bei bekanntem Konto
  ebenso. Parallele Versuche über mehrere Kanäle passieren alle die Prüfung, bevor der fünfte zählt.
  07 §4 schließt dieses Muster für alle Zähler aus. Entscheidung des Inhabers (2026-10-04): als
  Restrisiko geführt (07 §4, 14 §5). Den Versuch vorab zu buchen änderte den Port `Lockouts` und
  brächte ein Verfügbarkeitsrisiko beim Zurückbuchen; praktisch betroffen ist nur das Raten von
  Passwörtern, die `PasswordPolicy` und Argon2 schützen. Vor einer produktiven Passwortanmeldung per
  Lookup nachzuholen. `DPoP-demo-164n.29`.
- **Hinweis** Ein in der Verfahrensverwaltung angebotenes Einrichten wird bei Abschluss nicht erneut
  gegen `loa2` geprüft (`ManageAuthMethodsStrategy`, Zustand `Enrolling`). Altert der Nachweis
  währenddessen, wird das neue Verfahren unter dem dann gültigen Niveau eingetragen
  (`levelToWriteUnder`), also eher zu niedrig als zu hoch; kein Weg zu mehr Niveau.
- **Hinweis** A-5 der vierten Bewertung besteht weiter: Ein erfolgreicher Vorgangszugang setzt den
  Personenzähler nicht zurück.

**Geprüft und in Ordnung**

- Niveau: Alterung über `recent`, MFA je Achse (Identität und Anmeldung mischen sich nie),
  NIST-Grenze `loa2` für Kombinationen, Deckel `enrolledUnderAcr`, `proofLevel` nimmt bei mehreren
  Instanzen den niedrigsten Deckel.
- Verfahren entfernen und Angaben zurücknehmen: Schutz gegen Selbstaussperrung über
  `MethodDependencies` samt abhängiger Verfahren; das Gate `loa2` in derselben Transition.
- Konto löschen: Niveau unmittelbar vor dem Löschen neu geprüft; Kanäle abgemeldet; nur
  Konto-Zähler zurückgesetzt, nicht die der Person.
- Kontozuordnung: fremder Anker ist Konflikt, nie Übernahme; widersprüchliche Anker sind Konflikt;
  Korrelation und Adresswechsel verlangen passende Identität; Platzhalter seit SA-9 ebenso.
- Tool-Sitzungen: Nur `RUNNING` und nicht abgelaufen ist verwendbar
  (`SessionManagementService.findToolSessionById`), nur die aktuelle schreibt (`isCurrent`).
- Gerät: Neuverknüpfung nur nach Rückfrage, widerruft die Credentials des früheren Kontos auf
  diesem Schlüssel.
- Aufbewahrung: Reihenfolge von innen nach außen, Zähler mit laufender Sperre bleiben.
