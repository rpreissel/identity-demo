# Lesepfad Sicherheit

Ein Weg durch Doku und Code für alle, die das System angreifen oder abnehmen sollen. Jede Station
nennt, worum es geht, wo die Regel steht, welche Codestellen sie tragen, welche Härtungen es gibt
und welche Flanken offen sind. Die Stationen folgen dem Weg einer Anfrage von außen nach innen.

So ist das Dokument zu lesen:

- **Codeverweise** zeigen auf die Zeile zum Stand 2026-10-03. Zeilen wandern; maßgeblich ist der
  genannte Name (Klasse, Funktion).
- **Härtungen** sind umgesetzt und, wo angegeben, per Test oder Invariante gesichert.
- **Offene Flanken** tragen eine Schwere (mittel, niedrig, Hinweis), „bewusst“, wenn eine ADR sie
  in Kauf nimmt, und das Issue (`bd show <id>`). Die Kürzel (S-, K-, A-, SA-) stammen aus den
  Bewertungen; was davon offen ist, steht mit Herkunft in [offene-befunde.md](offene-befunde.md).
- Die Gesamtliste aller offenen Flanken steht am Ende ([Abschnitt 15](#15-offene-flanken-auf-einen-blick)).

Der Maßstab ist [ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md): Der
Backend-Kern (`core/`, `contract/`, `tools/`) soll produktionsreif sein, und jede
Sicherheitszusage gilt ohne unbenannte Annahme an die Umgebung. Simulierte Fremdsysteme, Frontends
und die Ausführungsumgebung haben einen geringeren Anspruch
([14-stand-und-weg-zur-produktion.md](14-stand-und-weg-zur-produktion.md) Abschnitt 2).

---

## 0) Vorab lesen (eine Stunde)

1. [01-ueberblick.md](01-ueberblick.md): Begriffe (Kanal, Journey, Tool, Evidenz, Anker, `loa1`
   bis `loa3`).
2. [invarianten.md](invarianten.md): die Regeln, auf die sich der Kern verlässt, je mit dem
   Mechanismus, der sie erzwingt. Sicherheitsrelevant vor allem I-1 bis I-8, I-15 bis I-19, I-22
   bis I-25, I-30 bis I-32.
3. [14-stand-und-weg-zur-produktion.md](14-stand-und-weg-zur-produktion.md) Abschnitte 4 und 5:
   was Demo ist und was vor echten Personendaten fehlt.
4. [offene-befunde.md](offene-befunde.md): alle noch offenen Befunde der Bewertungen, die
   Restrisiken und die offenen Entscheidungen.

### Vertrauensgrenzen

| Grenze | Wer spricht | Schutz | Station |
|---|---|---|---|
| App ↔ Orchestrator | Browser-App | DPoP-Proof je Anfrage, Kanal an Schlüssel gebunden | 2, 3 |
| Browser ↔ Keycloak ↔ Orchestrator | Keycloak-Erweiterung | signierte Peer-Auth-Assertion, signierte Antwort | 4 |
| Orchestrator → Keycloak | Orchestrator | `private_key_jwt` je Client, eigener Grant | 4, 10 |
| Orchestrator → Fremdsysteme | Ports | Port-Verträge, heute simuliert | 8 |
| Betrieb → Orchestrator | Admin | HTTP Basic, Sperre nach Fehlversuchen | 1, 13 |

---

## 1) Eingang: Welcher Endpunkt ist wie geschützt

**Worum es geht.** Jeder HTTP-Handler ist entweder per DPoP oder Peer-Auth an einen Kanal gebunden
(`@BindingKey`) oder nennt seinen eigenen Schutz (I-6). Spring Security schützt nur die
Admin-Pfade; alles andere ist dort offen und wird in den Handlern geprüft.

**Doku:** [invarianten.md](invarianten.md) I-6; [05-api.md](05-api.md);
[07-betrieb.md](07-betrieb.md) Abschnitt 3c.

**Code:**

- [`AdminSecurityConfig.adminChain`](../src/main/kotlin/com/example/identity/core/orchestrator/admin/AdminSecurityConfig.kt#L32)
  `/orchestrator/admin/**`, Rolle ADMIN, HTTP Basic, zustandslos;
  [`openChain`](../src/main/kotlin/com/example/identity/core/orchestrator/admin/AdminSecurityConfig.kt#L48)
  `permitAll` für den Rest; [`adminUsers`](../src/main/kotlin/com/example/identity/core/orchestrator/admin/AdminSecurityConfig.kt#L56).
- [`AdminLoginRateLimitFilter`](../src/main/kotlin/com/example/identity/core/orchestrator/admin/AdminLoginRateLimitFilter.kt#L20):
  fünf Versuche je Benutzername und 15 Minuten, danach `429`, auch für das richtige Passwort. Den
  Namen liest Springs eigener Parser, jeder Versuch wird vor der Prüfung in einem `UPDATE` gebucht
  (SA-6, `AdminIntegrationTest`).
- [`RequestBodyLimitFilter`](../src/main/kotlin/com/example/identity/core/orchestrator/RequestBodyLimitFilter.kt):
  höchstens 64 KB je Anfrage, bevor etwas den Body liest (SA-10, `RequestBodyLimitIntegrationTest`).
- [`DpopBindingKeyResolver.bindingKeyOf`](../src/main/kotlin/com/example/identity/core/orchestrator/api/v1/DpopBindingKeyResolver.kt#L52):
  DPoP-Header → Thumbprint; sonst Peer-Auth-Assertion → `kc:<channel_binding>`;
  `keycloakOnly` lehnt DPoP ab, `dpopOnly` (App-Kanal anlegen, Geräteverknüpfung) eine Assertion
  (SA-19, `DpopBindingKeyResolverTest`).
- [`ToolContextResolver.resolveArgument`](../src/main/kotlin/com/example/identity/core/orchestrator/api/v1/ToolContextResolver.kt#L45):
  die `toolId` kommt aus dem Controller, nie vom Client.
- [`ReadinessGateFilter`](../src/main/kotlin/com/example/identity/core/orchestrator/ReadinessGateFilter.kt#L16):
  503, bis der Dienst bereit ist.

**Härtungen:**

- Ein Endpunkt ohne `@BindingKey` und ohne benannten Schutz lässt den Build scheitern
  (`ApiBoundaryArchitectureTest`).
- Keine CORS-Konfiguration: Spring lässt nur dieselbe Herkunft zu.
- Actuator auf eigenem Port (`MANAGEMENT_PORT`, 9080), nur `health` und `prometheus`.
- „Try it out“ in Swagger-UI ist abgeschaltet. Swagger-UI und `/v3/api-docs` gibt es nur im
  Demomodus (`springdoc.api-docs.enabled: ${demo.mode}`); `ProductionModeCheck` lehnt ein
  Überschreiben ab.
- [`ServerInfoController`](../src/main/kotlin/com/example/identity/core/orchestrator/admin/ServerInfoController.kt#L83)
  ist ohne Anmeldung erreichbar, weil der Web-Kanal daraus seine Keycloak-Adresse liest. Zustand,
  Zähler und Latenzen (`operations`) gibt er nur im Demomodus heraus (`ServerInfoControllerTest`).

**Offene Flanken:**

- **Hinweis** [`DemoSessionsController`](../src/main/kotlin/com/example/identity/core/orchestrator/admin/DemoSessionsController.kt#L19)
  und die Demo-Schalter für LoA1 und Login-Theme sind ohne Anmeldung erreichbar; sie existieren
  nur im Demomodus.
- **Hinweis** Die H2-Konsole ist im Demomodus über `openChain` offen; allein H2s Voreinstellung
  `web-allow-others=false` beschränkt sie auf localhost (`DPoP-demo-9msv`). Außerhalb des
  Demomodus verlangt `ProductionModeCheck`, dass sie aus ist.
- **Hinweis** Welche Tools der Betreiber gesperrt hat (`disabledTools`), nennt `server-info` auch
  außerhalb des Demomodus.

---

## 2) DPoP im App-Kanal

**Worum es geht.** Jede Anfrage der App trägt einen frischen, signierten Proof. Der Thumbprint
seines Schlüssels (`binding_key_ref`) bindet den Kanal; ein Proof gilt genau einmal.

**Doku:** [09-dpop.md](09-dpop.md) (ganz); [invarianten.md](invarianten.md) I-7, I-8.

**Code:**

- [`DpopValidator.validate`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/DpopValidator.kt#L26),
  [`validateHeader`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/DpopValidator.kt#L74)
  (`typ=dpop+jwt`, nur ES256/384/512),
  [`validateSignature`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/DpopValidator.kt#L84)
  (nur EC, kein privater Schlüssel im `jwk`),
  [`validateClaims`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/DpopValidator.kt#L98)
  (`htm`, `htu`, `iat`-Fenster, `jti`).
- [`RequestUrls.htuMatches`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/RequestUrls.kt#L26)
  nach RFC 9449; [`buildRequestUrl`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/RequestUrls.kt#L11)
  hängt hinter einem Proxy an `forward-headers-strategy`.
- [`DpopReplayProtectionService.validateAndStore`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/DpopReplayProtectionService.kt#L26)
  und [`DpopProofReplayRepository.insert`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/DpopProofReplay.kt#L38):
  das `INSERT` ist die Prüfung, in eigener Transaktion.
- [`JwkThumbprintService.computeBase64UrlThumbprint`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/JwkThumbprintService.kt#L16) (RFC 7638).
- [`DeviceProofValidator`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/DeviceProofValidator.kt#L28):
  eigener `typ=device-proof+jwt`, damit Kanal- und Geräte-Proof nicht füreinander einstehen.
- Frontend: [`generateDpopKeyPair`](../frontend/src/dpop.ts#L54) (`extractable=false`, IndexedDB),
  [`createDpopProof`](../frontend/src/dpop.ts#L119).

**Härtungen:**

- `alg=none` und HMAC sind ausgeschlossen, `typ` ist fest; Fehlercodes sind fest und verraten
  keine Einzelheiten.
- Fenster für `iat`: 60 s Alter, 30 s Vorlauf (D-7, D-8).
- Replay-Schlüssel ist SHA-256(`thumbprint:jti`) mit fester Länge, ein ausdrückliches `INSERT`
  statt `save` (`DpopReplayProtectionDbTest`, nacheinander und gleichzeitig).
- Ein Geräte-Credential darf nicht der DPoP-Schlüssel des Kanals sein
  ([`EnrollDeviceFlow`](../src/main/kotlin/com/example/identity/tools/auth_device/internal/enrolldevice/EnrollDeviceFlow.kt#L30)).
- Der Thumbprint wird über den neu kodierten Schlüssel berechnet, nicht über die Schreibweise des
  Clients: Ein Schlüssel hat genau einen Thumbprint (SA-18, `JwkThumbprintServiceTest`).

**Offene Flanken:**

- **Bewusst** Kein Server-Nonce: Wer den Schlüssel kurz nutzen kann, berechnet Proofs für rund
  90 s im Voraus ([09-dpop.md](09-dpop.md) Abschnitt 2).
- **Nicht anwendbar** `ath`: Anfragen an den Orchestrator tragen kein Access Token; DPoP bindet
  hier den Kanal, nicht ein Token.
- **Bewusst** Die Keycloak-Tokens aus `GET …/token` sind nicht an den DPoP-Schlüssel gebunden
  (kein `cnf.jkt`, [ADR-9](adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md),
  [09-dpop.md](09-dpop.md) Abschnitt 4).
- **Niedrig** Jeder syntaktisch gültige Proof schreibt eine Zeile in die Replay-Tabelle, bevor
  Kanal oder Drossel greifen; Schlüssel kosten nichts (`DPoP-demo-9ppv.1`).
- **Umgebung** `htu` hinter einem Proxy ist nur sicher, wenn ein vertrauenswürdiger Proxy
  `X-Forwarded-*` immer überschreibt (`DPoP-demo-ai4x`).

---

## 3) Kanal, Bindung, Lebensdauer

**Worum es geht.** Ein Kanal gehört genau einem Schlüssel (App) oder einem Keycloak-Flow (Web),
höchstens einem Subjekt, und lebt nie länger als seine Keycloak-Sitzung.

**Doku:** [09-dpop.md](09-dpop.md) Abschnitt 3; [02-domaenenmodell.md](02-domaenenmodell.md);
[ADR-43](adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md);
[invarianten.md](invarianten.md) I-1, I-5, I-22 bis I-24.

**Code:**

- [`ChannelSession`](../src/main/kotlin/com/example/identity/core/orchestrator/session/ChannelSession.kt#L25):
  `bindingKeyRef` (App), `channelBinding` (Web), `expiresAt`, `@Version`.
- [`DeviceChannelAccessGuard`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ChannelAccessGuard.kt#L35)
  vergleicht die Bindung in konstanter Zeit;
  [`requireLiveChannel`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ChannelAccessGuard.kt#L24)
  und [`LiveChannel`](../src/main/kotlin/com/example/identity/core/orchestrator/session/LiveChannel.kt#L13)
  lassen keinen beendeten Kanal schreiben.
- [`SessionManagementService.createChannelSession`](../src/main/kotlin/com/example/identity/core/orchestrator/session/SessionManagementService.kt#L34):
  immer neu, nie über den Schlüssel gesucht;
  [`findChannelSessionById`](../src/main/kotlin/com/example/identity/core/orchestrator/session/SessionManagementService.kt#L70)
  behandelt abgelaufene wie nicht vorhandene.
- [`ChannelCreationRateLimitService`](../src/main/kotlin/com/example/identity/core/orchestrator/session/ChannelCreationRateLimitService.kt#L17):
  20 Kanäle je 5 Minuten je Schlüssel.
- [`JourneyActionExecutor.linkDeviceTo`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyActionExecutor.kt#L341):
  neu verknüpft nur nach Rückfrage und widerruft dabei jedes an den Schlüssel gebundene Credential.
- [`KeycloakChannelService.signedOutAtKeycloak`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/KeycloakChannelService.kt#L64):
  eine Abmeldung in Keycloak beendet Web- und App-Kanäle der Sitzung.

**Härtungen:**

- CHECK `ck_channel_session_binding_key`: nur der App-Kanal trägt einen Schlüssel (I-8);
  `ck_channel_session_one_subject`: Konto oder Einladung, nie beides (I-5).
- Ein Kontowechsel auf einem bestehenden Kanal ist `409`, kein stiller Wechsel.
- Die Geräteverknüpfung entsteht erst mit dem ersten eingerichteten Verfahren; der bloße Besitz
  des DPoP-Schlüssels gilt nie als Anmeldung.

**Offene Flanken:**

- **Niedrig** Der Web-Kanal ist nach dem letzten Schritt `AUTHENTICATED`, bevor Keycloak die
  Sitzung anlegt; Abmeldemeldung und `restore-data` sind „best effort“ (`DPoP-demo-oe06`).
- **Niedrig** I-14 (kein Gerätelink auf ein gelöschtes Konto) ist ohne Fremdschlüssel
  (`DPoP-demo-hwc6`).

---

## 4) Web-Kanal: Keycloak und Orchestrator

**Worum es geht.** Keycloak spricht ohne mTLS mit dem Orchestrator. Jede Anfrage trägt eine
signierte Assertion, die Methode, Adresse und Kanal bindet; jede Antwort ist signiert und an
Anfrage, Status und Body gebunden. Keycloak schließt eine Anmeldung nur mit dem Subjekt und dem
Niveau ab, die der Orchestrator nennt.

**Doku:** [ADR-7](adr/ADR-007-web-kanal-ohne-mtls-signierte-request-assertion-statt.md);
[05-api.md](05-api.md) (Keycloak-Endpunkte); [04-orchestrierung.md](04-orchestrierung.md)
Abschnitt 5 „RestoreData als erster Übergang“; [invarianten.md](invarianten.md) I-15, I-16, I-31.

**Code im Orchestrator:**

- [`PeerAuthValidator.validate`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/PeerAuthValidator.kt#L36):
  `typ=peer-auth+jwt`, nur ES256, `kid` aus Keycloaks JWKS, `iss`, `aud`, `htm`, `htu` samt Query,
  `body_sha256`, `iat`, `jti` mit Replay-Schutz, `channel_binding` Pflicht. Den Body liest
  [`PeerAuthBodyCaptureFilter`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/PeerAuthRequestBinding.kt)
  einmal mit, bevor etwas ihn parst.
- [`KeycloakJwkSource.find`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/KeycloakJwkSource.kt#L31):
  Größenlimit, Zeitlimits; ohne `jwks-uri` wird jede Assertion abgelehnt (fail-closed).
- [`KeycloakChannelAccessGuard`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ChannelAccessGuard.kt#L72):
  `channel_binding` muss zum Kanal passen; eine bekannte Kanal-Id allein reicht nicht.
- [`KeycloakChannelService.upsertChannel`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/KeycloakChannelService.kt#L102):
  fremdes Subjekt `409`, RestoreData nur für dasselbe Subjekt, unbekannte native Tools scheitern.
- [`KeycloakChannelService.restoreData`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/KeycloakChannelService.kt#L239)
  kappt die Kanalfrist an der Keycloak-Sitzung; für Einladungen gibt es nichts heraus (I-30).
- [`RestoreDataCodec.decode`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/RestoreDataCodec.kt#L51):
  HMAC, `sub` = Keycloak-Sitzung, Ablauf; jeder Fehler ergibt `null`.
- [`KeycloakResponseSigner.sign`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/KeycloakResponseSigning.kt#L42):
  `req`, `status`, `body_sha256`, 60 s;
  [`KeycloakResponseSigningFilter`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/KeycloakResponseSigning.kt#L75)
  signiert nur Antworten auf Assertionen, die
  [`PeerAuthValidator.verify`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/PeerAuthValidator.kt#L54)
  annimmt (alles außer der Einmaligkeit).
- [`KeycloakSignOutController`](../src/main/kotlin/com/example/identity/core/orchestrator/api/v1/keycloak/KeycloakSignOutController.kt#L32):
  `channel_binding` muss das adressierte Konto oder die Einladung sein.

**Code in der Keycloak-Erweiterung:**

- [`PeerAuthAssertionSigner`](../keycloak-extension/src/main/java/com/example/identity/kcext/client/PeerAuthAssertionSigner.java#L21);
  Schlüssel als Geheimnis der Komponente
  ([`OrchestratorSettings.ensureSigningKey`](../keycloak-extension/src/main/java/com/example/identity/kcext/client/OrchestratorSettings.java#L100)).
- [`OrchestratorClient.send`](../keycloak-extension/src/main/java/com/example/identity/kcext/client/OrchestratorClient.java#L323)
  prüft jede Antwort, bevor es den Status auswertet;
  [`OrchestratorResponseVerifier.checkClaims`](../keycloak-extension/src/main/java/com/example/identity/kcext/client/OrchestratorResponseVerifier.java#L94).
- [`OrchestratorAuthenticator.handleResponse`](../keycloak-extension/src/main/java/com/example/identity/kcext/login/OrchestratorAuthenticator.java#L142):
  der Nutzer kommt nur aus dem Subjekt, das der Orchestrator nennt.
- [`LoginCompletion.judge`](../keycloak-extension/src/main/java/com/example/identity/kcext/login/LoginCompletion.java#L37):
  fertig nur mit Subjekt, demselben Subjekt und `acr ≥` Ziel des Subflows.
- [`OrchestratorResumeAuthenticator.authenticate`](../keycloak-extension/src/main/java/com/example/identity/kcext/login/OrchestratorResumeAuthenticator.java#L40):
  RestoreData höchstens einmal je Auth-Session, Floor auf das angefragte Niveau, keine Einladungen.
  Scheitert der Resume, läuft eine normale Anmeldung: Ein Fehler gewährt nichts.
- [`OrchestratorNotes.stashRestoreDataAtFlowEnd`](../keycloak-extension/src/main/java/com/example/identity/kcext/login/OrchestratorNotes.java#L153).
- [`QrWaitStatusResourceProvider.status`](../keycloak-extension/src/main/java/com/example/identity/kcext/resource/QrWaitStatusResourceProvider.java#L74):
  ohne Anmeldung, aber nur mit Keycloaks signiertem `AUTH_SESSION_ID`-Cookie, nur
  `waiting`/`ready`.
- [`OrchestratorStorageProvider.isValid`](../keycloak-extension/src/main/java/com/example/identity/kcext/federation/OrchestratorStorageProvider.java#L146):
  Ausfall wirft statt `false`; Nutzer sind nur lesbar (I-15).

**Härtungen:**

- Antworten sind auch bei Fehlern und `304` signiert; die Erweiterung prüft vor jeder
  Auswertung (`OrchestratorResponseVerifierTest`, `PeerAuthRoundTripTest`).
- Jeder Keycloak-Client des Orchestrators hat seinen eigenen Schlüssel (I-16); das Vertrauen in
  ein selbstsigniertes Zertifikat gilt nie JVM-weit (I-17, `KeycloakHttp`).
- Orchestrator-Fehler zählen nicht als Fehlversuch für Keycloaks Brute-Force-Schutz, auch
  Ausfälle und unsignierte Antworten nicht: Die Authenticatoren rufen nie `failure()` (K-1, SA-12;
  `ApiFailureTest`, `NoBruteForceBookingTest`).
- `LoginCompletion` kennt `loa3`, und ein unbekanntes Ziel-Niveau lässt nichts durch (SA-16,
  `LoginCompletionTest`).
- Das Realm schließt Keycloaks eigene Wege
  ([`V7__locked_down_defaults`](../keycloak-migrations/src/main/resources/keycloak-migrations/V7__locked_down_defaults.kc.kts),
  SA-2, SA-3, SA-7): nur die eigene Required Action, kein „Passwort ändern“ oder „Passwort
  vergessen“; der Browser-Flow des Realms ist der des Orchestrators, Direct Grants lehnt ein eigener
  Flow ab, `admin-cli` ohne Passwort-Grant, Account-Konsole aus, kein `offline_access` an den
  Projekt-Clients (`LockedDownDefaultsMigrationTest`, gegen compose geprüft). Die Federation lehnt
  jede Passwortänderung ab, statt sie Keycloak lokal speichern zu lassen
  (`OrchestratorStorageProviderTest`, ADR-38 Nachtrag).
- Der Bootstrap-Client mit `create-realm` holt seine `jwks-url` nur über https oder Loopback
  (SA-20, `MigrationClientJwksUrlTest`).
- Ein Nachweis über `loa1` altert nach 30 Minuten, auch über den Resume-Pfad (S-1, erledigt;
  I-32).
- Zeitlimits 3 s/10 s beidseitig; JWKS mit Größenlimit auch über `KeycloakHttp`, höchstens ein Abruf
  je 30 s auch bei Fehlern, der letzte gute Satz bleibt (SA-17, `KeycloakJwkSourceBackoffTest`).
  Antworten an die Erweiterung liest diese höchstens bis 1 MB.
- Kein Signatur-Orakel: Eine gefälschte Assertion oder ein getauschter Body bekommt eine
  unsignierte Antwort (`KeycloakResponseSigningFilterTest` mit echtem Validator).
- Ein unbekanntes `targetAcr` ist `400`, bevor sich am Kanal etwas ändert, nie still `none`
  (`KeycloakChannelIntegrationTest`).
- `availableTools` wird gegen den Katalog geschnitten
  ([`ChannelService.catalogToolsOf`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ChannelService.kt#L104),
  `ChannelToolDeclarationIntegrationTest`).
- Formulardaten (`toolId`, `methodInstanceId`) werden nur als einfaches Pfadsegment
  `[A-Za-z0-9._~-]` übernommen, ohne `.` und `..`
  ([`OrchestratorClient.segment`](../keycloak-extension/src/main/java/com/example/identity/kcext/client/OrchestratorClient.java#L357),
  `OrchestratorClientSegmentTest`); sie verschieben die signierte Adresse nicht.

**Offene Flanken:**

- **Niedrig** Fehlerpfade des Authenticators zeigen teils Keycloaks generische Seite
  (`DPoP-demo-rdns`).
- **Hinweis** Antwort-JWKS mit Nimbus-Voreinstellungen, kein `outageTolerant` (K-6).
- **Hinweis** JSON per `?no_esc` in `<script>` der Demo-Personenauswahl (K-7,
  `DPoP-demo-9ppv.11`); nur Seed-Daten im Demomodus.
- **Hinweis** Jeder GET auf die Action-URL wird Tool-Eingabe (K-8); braucht Code und Cookie.
- **Hinweis** Peer-Auth-Fenster 300 s im Profil `keycloak` statt nur in der Variante `host`
  (`DPoP-demo-9ppv.13`).
- **Hinweis** Die Kanal-Id des Web-Kanals ist aus der Keycloak-Tab-Id abgeleitet
  ([`OrchestratorNotes.channelSessionId`](../keycloak-extension/src/main/java/com/example/identity/kcext/login/OrchestratorNotes.java#L77)),
  also vorhersagbar. Zugriff verlangt trotzdem eine signierte Assertion mit passendem
  `channel_binding` (`DPoP-demo-gxis`).
- **Hinweis** QR-Status-Endpunkt ohne Mindestintervall (`DPoP-demo-9ppv.10`).
- **Bewusst** Native Keycloak-Faktoren im `amr` gelten ungekappt
  ([`AcrLevels.HIGHEST`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/KeycloakChannelService.kt#L146));
  Keycloak ist hier vertrauenswürdig.
- **Umgebung** Keycloak ↔ Orchestrator läuft in compose über http (`DPoP-demo-ai4x`). Integrität
  tragen die Signaturen in beide Richtungen, Body und Query eingeschlossen; Vertraulichkeit nicht.

---

## 5) Niveaus: Was ein Nachweis wert ist

**Worum es geht.** Das Niveau (`acr`) wird nie gespeichert, sondern je Lesen aus der Evidenz
berechnet. Drei Obergrenzen verhindern, dass sich ein Verfahren selbst aufwertet: was das Tool
kann (`maxAcr`), unter welchem Niveau es eingerichtet wurde (`enrolledUnderAcr`) und die
NIST-Grenze für kombinierte Faktoren.

**Doku:** [04-orchestrierung.md](04-orchestrierung.md) Abschnitt 8;
[ADR-5](adr/ADR-005-drei-obergrenzen-fuer-das-sicherheitsniveau.md);
[ADR-36](adr/ADR-036-niveaus-und-ihre-nachweise.md); [invarianten.md](invarianten.md) I-4, I-21,
I-32.

**Code:**

- [`AcrLevel`](../src/main/kotlin/com/example/identity/contract/tool_api/claims/AcrLevel.kt#L10):
  Unbekanntes hat Rang 0.
- [`DefaultAuthPolicy.resolveAcr`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/policy/DefaultAuthPolicy.kt#L31)
  (Alterung), [`isSatisfied`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/policy/DefaultAuthPolicy.kt#L60),
  [`cappedAcr`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/policy/DefaultAuthPolicy.kt#L168),
  [`combinedAcr`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/policy/DefaultAuthPolicy.kt#L190),
  [`authCandidates`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/policy/DefaultAuthPolicy.kt#L109).
- [`Tool.staysWithin`](../src/main/kotlin/com/example/identity/contract/tool_api/Tool.kt#L246)
  und [`ToolJourneyService.checkStaysWithin`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ToolJourneyService.kt#L367):
  ein Ergebnis über `maxAcr` oder den Faktortypen des Tools ist ein harter Fehler.
- [`CredentialRules.levelToWriteUnder`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/journey/CredentialRules.kt#L28)
  und [`proofLevel`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/journey/CredentialRules.kt#L37);
  angewandt in [`performAdoptCredential`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyActionExecutor.kt#L225)
  und [`performAcceptProof`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyActionExecutor.kt#L283).
- Ausgang zu Keycloak: [`ChannelResponseAssembler.authDataFor`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ChannelResponseAssembler.kt#L70),
  [`KeycloakTokenProvider.requestAccountToken`](../src/main/kotlin/com/example/identity/core/orchestrator/session/KeycloakTokenProvider.kt#L91);
  in Keycloak [`AccountTokenGrantType.process`](../keycloak-extension/src/main/java/com/example/identity/kcext/grant/AccountTokenGrantType.java#L61),
  [`AccountTokenClaims`](../keycloak-extension/src/main/java/com/example/identity/kcext/grant/AccountTokenClaims.java#L11),
  [`OrchestratorAcrAmrMapper.setClaim`](../keycloak-extension/src/main/java/com/example/identity/kcext/token/OrchestratorAcrAmrMapper.java#L69).

**Härtungen:**

- Identifizierung und Anmeldefaktoren werden nie zu einer MFA-Stufe kombiniert; ab `loa3` muss
  die MFA innerhalb einer Achse liegen.
- Gerätegebundene Verfahren werden nur auf dem verknüpften Gerät angeboten
  ([`Tool.usableByCaller`](../src/main/kotlin/com/example/identity/contract/tool_api/Tool.kt#L254)).
- Der Grant nimmt nur bekannte `acr` und `amr` nach `[a-z0-9_-]+`, nur für einen vertraulichen
  Client mit eigenem Attribut, und setzt eine Sitzung nur für denselben Nutzer fort.
- `DefaultAuthPolicyTest` (Alter 29/31 min, unbekanntes Alter), `ModelBasedJourneyTest`.

**Offene Flanken:**

- **Hinweis** Ein abgebrochener Step-up lässt den Kanal auf dem bisherigen Niveau angemeldet. Das
  ist richtig, schützt aber nur, wenn die anfragende Anwendung `acr` gegen ihre Anforderung prüft
  (`DPoP-demo-mea0`).
- **Bewusst** Das `acr` im Token altert nicht; es beschreibt wie bei Keycloak die Anmeldung. Wer ein
  frisches `loa2` braucht, fragt mit `acr_values` neu an oder prüft `auth_time` (04 §8, [offene Befunde](offene-befunde.md) Abschnitt 6).
- **Offen (Entscheidung)** `loa3` im Web-Realm (`DPoP-demo-wzcm`); Aufwerten eines Verfahrens nach
  erneuter Identifizierung (`DPoP-demo-wyp3`).

---

## 6) Journey: nur das angebotene Tool, nur einmal

**Worum es geht.** Der Client wählt kein Verfahren frei. Er aktiviert nur, was der aktuelle
Zustand anbietet, schreibt nur in den gerade aktiven Schritt, und ein Ergebnis zählt genau einmal.

**Doku:** [04-orchestrierung.md](04-orchestrierung.md) Abschnitte 4, 5 und 7;
[ADR-32](adr/ADR-032-tool-sperre-und-reihenfolge-je-kanal.md);
[journeys/](journeys/); [invarianten.md](invarianten.md) I-2, I-3.

**Code:**

- [`JourneyService.activate`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyService.kt#L270)
  lehnt ein nicht angebotenes Tool ab;
  [`JourneyState.activatable`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/journey/state/JourneyState.kt#L70) =
  angeboten minus abgelehnt, geschnitten mit den verfügbaren.
- [`isCurrent`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyService.kt#L293),
  [`applyOutcome`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyService.kt#L306),
  [`chargeAttempt`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyService.kt#L526)
  (Budget 3), [`fallBack`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyService.kt#L555)
  (ein abgebrochener Step-up wird nie `AUTHENTICATED`).
- [`ToolJourneyService.beginActivation`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ToolJourneyService.kt#L100),
  [`validatePreconditions`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ToolJourneyService.kt#L132),
  [`loadCurrent`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ToolJourneyService.kt#L154),
  [`applyOutcome`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ToolJourneyService.kt#L233)
  (ToolSession danach `DONE`).
- [`ToolAvailabilityService`](../src/main/kotlin/com/example/identity/core/orchestrator/tool/ToolAvailabilityService.kt#L21):
  Sperre je Fassung und Reihenfolge je Tool, beides je Kanal; `demoOnly`-Tools gibt es außerhalb des
  Demomodus in keiner Fassung.
- [`RunningJourney`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/RunningJourney.kt#L16):
  nur eine gestartete, nicht abgelaufene Journey ist verwendbar.

**Härtungen:**

- Ein Angebot darf veralten, die Ausführung prüft aktuell
  ([04-orchestrierung.md](04-orchestrierung.md) Abschnitt 5).
- Ein Ergebnis muss zur Rolle des Tools passen (`chargeRateLimits`, `applyOutcome`).
- Nach drei Fehlversuchen ist die Journey `FAILED` und nimmt nichts mehr an (I-2). Das Ende wird
  festgeschrieben, nicht mit der Antwort `410` zurückgerollt (`JourneyEndedException`, SA-1,
  `JourneyFallbackChainIntegrationTest`). Damit hat
  jeder ausgegebene Code (TAN, E-Mail-Code, eID-PIN, Bestätigungscode) höchstens drei
  Rateversuche.

**Offene Flanken:**

- **Niedrig** Kein Test für zwei gleichzeitige `PATCH` auf dieselbe ToolSession; geschützt ist es
  über `@Version` (`DPoP-demo-df48`).
- **Niedrig** „`auth-invite` nur im Web-Kanal“ ist eine umschaltbare Voreinstellung, keine
  Eigenschaft des Tools; die dritte Linie antwortet 500 statt 409 (A-4).

---

## 7) Identität und Kontozuordnung

**Worum es geht.** Ein Konto wird nur über Anker gefunden (Ausweiskennung, Partnernummer,
bestätigte E-Mail). Ein Ankerwert gehört höchstens einem Konto; ein Konflikt ist eine Abweisung,
nie eine Zusammenführung. Nur ein vorläufiges Konto geht im gefundenen auf.

**Doku:** [02-domaenenmodell.md](02-domaenenmodell.md);
[ADR-11](adr/ADR-011-kontouebergreifender-person-id-konflikt-ist-abweisung-merge-nie.md),
[ADR-19](adr/ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md),
[ADR-20](adr/ADR-020-ein-vorlaeufiges-konto-geht-im-gefundenen-auf-statt.md);
[invarianten.md](invarianten.md) I-9 bis I-13.

**Code:**

- [`AnchorDecision.decide`](../src/main/kotlin/com/example/identity/core/account/domain/AnchorDecision.kt#L39):
  fremder Anker → `IdentityConflictException`, unveränderliche Anker bleiben, Untergrenze des
  Niveaus; [`AnchorRegistry.bind`](../src/main/kotlin/com/example/identity/core/account/application/AnchorRegistry.kt#L26)
  schreibt als einziger.
- [`IdentityMatchingService.resolve`](../src/main/kotlin/com/example/identity/core/account/application/IdentityMatchingService.kt#L48),
  [`attestedIdentityMatches`](../src/main/kotlin/com/example/identity/core/account/application/IdentityMatchingService.kt#L67),
  [`resolveByAnchor`](../src/main/kotlin/com/example/identity/core/account/application/IdentityMatchingService.kt#L102).
- [`AccountMerge.decide`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/journey/AccountRules.kt#L78),
  [`accountOfProof`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/journey/AccountRules.kt#L131),
  [`performRecordIdentification`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyActionExecutor.kt#L108),
  [`AccountService.absorbDisposableAccount`](../src/main/kotlin/com/example/identity/core/account/AccountService.kt#L172).

**Härtungen:**

- Nur `JourneyActionExecutor` befragt den `IdentityResolver`: aufgelöst wird nur, wo auch
  gebunden wird (I-10).
- `ident-kvnr` verrät nicht, ob eine fremde Nummer existiert, und belastet die fremde Person
  trotzdem (I-12).
- Bei Namensgleichheit muss zusätzlich die Adresse passen.

**Offene Flanken:**

- **Niedrig** Lookup-Tools: Demo-TAN und, mit echtem Anbieter, die Versandlatenz verraten, ob
  eine Adresse ein Konto hat (`DPoP-demo-36xz`).
- **Hinweis, bewusst** `auth-invite` und `ident-fsc`: Eine unbekannte Nummer kostet nichts, eine
  bekannte antwortet messbar anders (S-7, `DPoP-demo-36xz`).
- **Hinweis** I-10 gilt per Regel nur für den Resolver, nicht für `IdentityMatchingService`
  (`DPoP-demo-9ppv.23`).

---

## 8) Die Verfahren im Einzelnen

**Doku:** [06-ablaeufe.md](06-ablaeufe.md); [07-betrieb.md](07-betrieb.md) Abschnitt 5 (QR);
[port-vertraege.md](port-vertraege.md).

**Passwort** ([`PasswordHasher.matches`](../src/main/kotlin/com/example/identity/tools/auth_password/internal/PasswordHasher.kt#L26)):
Argon2id mit OWASP-Parametern, Rehash nach erfolgreicher Prüfung, Dummy-Hash bei fehlendem
Passwort gegen Zeitmessung, höchstens 128 Zeichen
([`PasswordPolicy`](../src/main/kotlin/com/example/identity/tools/auth_password/internal/PasswordPolicy.kt#L12)).
Die Lookup-Anmeldung antwortet einheitlich „E-Mail oder Passwort ungültig“
([`AuthPasswordLookupToolHandler.patch`](../src/main/kotlin/com/example/identity/tools/auth_password/internal/authpasswordlookup/AuthPasswordLookupToolHandler.kt#L42)).

**SMS und E-Mail** ([`TanGenerator`](../src/main/kotlin/com/example/identity/tools/auth_sms/internal/TanGenerator.kt#L21),
[`matches`](../src/main/kotlin/com/example/identity/tools/auth_sms/internal/TanGenerator.kt#L40),
[`EmailCodeGenerator.matches`](../src/main/kotlin/com/example/identity/tools/auth_email/internal/EmailCodeGenerator.kt#L40)):
sechs Ziffern aus `SecureRandom`, gespeichert als HMAC-SHA256 mit Pepper, 5 Minuten gültig,
Vergleich in konstanter Zeit. Eine falsche TAN
([`WrongTan`](../src/main/kotlin/com/example/identity/tools/auth_sms/internal/authsms/AuthSmsToolHandler.kt#L68))
entwertet die TAN nicht; das Journey-Budget begrenzt die Versuche je TAN auf drei. Versandlimit
3 je 10 Minuten je Nummer oder Adresse
([`SmsSendLimit`](../src/main/kotlin/com/example/identity/tools/auth_sms/internal/SmsSendLimit.kt#L16),
[`EmailSendLimit`](../src/main/kotlin/com/example/identity/tools/auth_email/internal/EmailSendLimit.kt#L15)).

**QR-Login** ([`PairingCodeGenerator`](../src/main/kotlin/com/example/identity/tools/auth_qr/internal/PairingCodeGenerator.kt#L10)):
Pairing-Code mit etwa 40 Bit, 5 Minuten; Bestätigungscode aus der App, im Browser einzutippen
(I-18, gegen untergeschobene QR-Codes). Zustandswechsel als bedingtes `UPDATE`
([`completeIfConfirmed`](../src/main/kotlin/com/example/identity/tools/auth_qr/internal/QrLoginRequestRepository.kt#L51),
[`countWrongConfirmation`](../src/main/kotlin/com/example/identity/tools/auth_qr/internal/QrLoginRequestRepository.kt#L65)):
ein Gewinner, drei falsche Codes verbrennen die Anfrage.
[`ConfirmQrLoginToolHandler.patch`](../src/main/kotlin/com/example/identity/tools/auth_qr/internal/confirmqrlogin/ConfirmQrLoginToolHandler.kt#L53)
verlangt das eingeschaltete QR-Verfahren und das erwartete Konto;
[`QrLoginBrowserSide.advance`](../src/main/kotlin/com/example/identity/tools/auth_qr/internal/QrLoginBrowserSide.kt#L46)
lehnt eine Wiederholung nach `COMPLETED` ab.

**Gerät** ([`AuthDeviceToolHandler`](../src/main/kotlin/com/example/identity/tools/auth_device/internal/authdevice/AuthDeviceToolHandler.kt#L26)):
signierter Geräte-Proof, `htu` an die einmalige ToolSession-Adresse gebunden statt
Server-Challenge, Replay-Schutz. Die Benutzerverifikation behauptet nur der Client; deshalb ist das
Verfahren `demoOnly`.

**KOBIL** ([ADR-21](adr/ADR-021-der-kobil-pin-liegt-im-backend-und-das.md),
[ADR-22](adr/ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md)): Der PIN liegt im
Klartext im Backend ([`KobilEnrollment`](../src/main/kotlin/com/example/identity/tools/auth_kobil/internal/KobilEnrollment.kt#L18)),
das Entsperrgeheimnis (256 Bit) nur als Hash
([`KobilSecrets.matches`](../src/main/kotlin/com/example/identity/tools/auth_kobil/internal/KobilSecrets.kt#L39)).
[`releasePin`](../src/main/kotlin/com/example/identity/tools/auth_kobil/internal/authkobil/AuthKobilToolHandler.kt#L74)
gibt den PIN für 120 s und nur in dieser einen Antwort frei.

**Vorgangszugang** ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md),
[`AuthInviteToolHandler.patch`](../src/main/kotlin/com/example/identity/tools/auth_invite/internal/AuthInviteToolHandler.kt#L38),
[`Einladungen.redeem`](../src/main/kotlin/com/example/identity/simulation/personenverzeichnis/Einladungen.kt#L109)):
etwa 59 Bit, nie gespeichert, Id = SHA-256 über Person, Kennwort und Vorgang, Vergleich in
konstanter Zeit, gesperrt, unbekannt und falsch antworten gleich.

**Freischaltcode** ([`IdentFscToolHandler.patch`](../src/main/kotlin/com/example/identity/tools/ident_fsc/internal/IdentFscToolHandler.kt#L46)):
erst Personendaten, dann Code; eine Ablehnung für alle Fälle.

**eID, KVNR, Nect** ([`IdentKvnrToolHandler.patch`](../src/main/kotlin/com/example/identity/tools/ident_kvnr/internal/IdentKvnrToolHandler.kt#L42),
[`IdentNectToolHandler.patch`](../src/main/kotlin/com/example/identity/tools/ident_nect/internal/IdentNectToolHandler.kt#L76),
[`acceptedReturnUri`](../src/main/kotlin/com/example/identity/tools/ident_nect/internal/IdentNectToolHandler.kt#L111)):
Die Nect-Rücksprungadresse muss mit einem konfigurierten Präfix beginnen (leer = nichts erlaubt,
[ADR-47](adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md)); der Fall gehört der ToolSession
und wird serverseitig genau einmal eingelöst. eID und Nect sind Simulationen und `demoOnly`.

**Offene Flanken:**

- **Bewusst** KOBIL-PIN im Klartext (ADR-22); das Entsperrgeheimnis im Browser liegt im
  `localStorage` (Station 11).
- **Niedrig** `ident-nect`: `retry` ohne Budget, Nect-Fälle ohne Aufbewahrung (S-2, kein Issue).
- **Hinweis, bewusst** Keycloaks Action-URL samt Aktionscode liegt in zwei Tabellen und geht an
  das Fremdsystem (S-8).
- **Hinweis** Der Freischaltcode ist im simulierten Personenverzeichnis ungesalzen mit SHA-256
  gespeichert ([`Freischaltcodes.hash`](../src/main/kotlin/com/example/identity/simulation/personenverzeichnis/Freischaltcodes.kt#L121));
  rund 40 Bit sind aus einem Dump offline ratbar. Der Port-Vertrag sollte die Anforderung an ein
  echtes System nennen (`DPoP-demo-4xnr`).
- **Hinweis** Die eID-Simulation prüft eine feste Mock-PIN
  ([`IdentEidFlow`](../src/main/kotlin/com/example/identity/tools/ident_eid/internal/IdentEidFlow.kt#L132));
  nur im Demomodus verfügbar.

---

## 9) Zähler, Sperren, Versandlimits

**Worum es geht.** Der Orchestrator führt alle Zähler; die Module bestimmen die Regeln, jedes nur
in seinem eigenen Namensraum. Jeder Zähler ist ein einziges `UPDATE`.

**Doku:** [ADR-44](adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md);
[07-betrieb.md](07-betrieb.md) Abschnitt 4; [invarianten.md](invarianten.md) I-25.

**Code:**

- [`RateLimit`](../src/main/kotlin/com/example/identity/contract/tool_api/ratelimit/RateLimit.kt#L25)
  (Namensraum aus der Klasse), [`ModuleRateLimits`](../src/main/kotlin/com/example/identity/core/orchestrator/session/ModuleRateLimits.kt#L20)
  (Schlüssel als HMAC unter dem Pepper).
- [`RateLimitCounter.recordFailure`](../src/main/kotlin/com/example/identity/core/orchestrator/session/RateLimitCounter.kt#L35),
  [`recordWindowedAttempt`](../src/main/kotlin/com/example/identity/core/orchestrator/session/RateLimitCounter.kt#L62)
  (fail-closed), [`RateLimitRecordRepository.incrementFailure`](../src/main/kotlin/com/example/identity/core/orchestrator/session/RateLimitRecordRepository.kt#L51).
- [`AccountLockoutService`](../src/main/kotlin/com/example/identity/core/orchestrator/session/AccountLockoutService.kt#L22),
  [`PersonLockoutService`](../src/main/kotlin/com/example/identity/core/orchestrator/session/PersonLockoutService.kt#L16):
  je 5 Fehlversuche, 15 Minuten.
- [`ToolJourneyService.chargeRateLimits`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ToolJourneyService.kt#L266).

**Härtungen:**

- Lookup-Tools falten die Sperre in ihre gewöhnliche Ablehnung: Eine Sperre verrät kein Konto.
- Bei bekanntem Konto prüft der Orchestrator die Sperre bei jedem Versuch, nicht nur beim Aktivieren:
  Eine vorher geöffnete Sitzung rät nicht weiter und meldet auch mit dem richtigen Passwort nicht an
  ([`ToolJourneyService.loadCurrent`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ToolJourneyService.kt),
  SA-26, `AccountRateLimitIntegrationTest`).
- Eine Kontolöschung setzt nur die Konto-Zähler zurück, nicht die der Person: Löschen ist kein
  Weg, ein Budget zu erneuern.
- `RateLimitArchitectureTest`, `AttemptLockoutExpiryDbTest`, `AccountRateLimitIntegrationTest`.

**Offene Flanken:**

- **Niedrig** Ein erfolgreicher Vorgangszugang setzt den Personenzähler nicht zurück (A-5, kein
  Issue).
- **Niedrig, bewusst** Die Kontosperre ist „prüfen, dann zählen“: Parallele Versuche über mehrere
  Kanäle passieren die Prüfung, bevor der fünfte zählt. Restrisiko vor einer produktiven
  Passwortanmeldung per Lookup ([07-betrieb.md](07-betrieb.md) Abschnitt 4, SA-27, `DPoP-demo-164n.29`).
- **Niedrig** Fehlgeschlagene QR-Suchen haben keinen eigenen Zähler
  ([07-betrieb.md](07-betrieb.md) Abschnitt 5).

---

## 10) Geheimnisse, Schlüssel, Logs

**Doku:** [14-stand-und-weg-zur-produktion.md](14-stand-und-weg-zur-produktion.md) Abschnitt 5;
[invarianten.md](invarianten.md) I-16, I-17, I-19.

**Code:**

- [`NodeSigningKey`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/NodeSigningKey.kt#L23):
  die privaten Schlüssel des Orchestrators (Client-Assertions, Antwortsignatur) liegen in der
  Datenbank; [`OrchestratorClientAssertionSigner`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/OrchestratorClientAssertionSigner.kt#L25)
  hat einen Schlüssel je Client.
- [`RestoreDataCodec`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/RestoreDataCodec.kt#L51):
  HMAC-Schlüssel je Start zufällig; ein Neustart entwertet alle RestoreData.
- [`PersonLookupKey`](../src/main/kotlin/com/example/identity/core/account/application/PersonLookupKey.kt#L29):
  Suchschlüssel im Änderungsprotokoll als Hash mit Geheimnis, nie Klartext.

**Härtungen:**

- Kein Code und kein Empfänger im Log (I-19, `NoSecretsInLogIntegrationTest`); Wertobjekte
  melden abgelehnte Werte ohne Rohwert.
- Kein `println`/`System.out` (ArchUnit).
- Abgelehnte DPoP-Proofs und Peer-Auth-Assertions erreichen das Log ebenso gefiltert, auch `alg`
  und `kid` aus dem Header (SA-11, `OrchestratorExceptionHandlerTest`).
- Was ein Client in Pfad oder Query schickt (`intent`, `toolId`, `nativeToolId`), erreicht das Log
  nur ohne Steuer- und Zeilentrennzeichen und auf 200 Zeichen begrenzt
  ([`OrchestratorException.loggable`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/OrchestratorException.kt#L22),
  `OrchestratorExceptionTest`).
- `ProductionModeCheck` verlangt Pepper und Lookup-Geheimnis mit mindestens 32 Zeichen.

**Offene Flanken:**

- **Offen (Entscheidung)** Schlüsselverwaltung mit KMS/HSM, Rotation, Widerruf
  (`DPoP-demo-61kp`); personenbezogene Spalten sind unverschlüsselt (`DPoP-demo-bo1w`).
- **Betrieb** Mehrere Instanzen brauchen einen festen Pepper und eine gemeinsame Sperre für
  geplante Aufgaben (`DPoP-demo-g7np`). Ein leerer Pepper bedeutet einen zufälligen je Start.

---

## 11) Frontend

**Worum es geht.** Das Frontend ist Demo-Anspruch (ADR-35, Bereich 3). Die Schlüssel sind nicht
exportierbar, liegen aber in den Daten des Browsers, nicht in Hardware.

**Doku:** [09-dpop.md](09-dpop.md) Abschnitt 2 „Wie sicher die Schlüssel im Browser sind“;
[10-frontend.md](10-frontend.md).

**Code:**

- [`dpop.ts`](../frontend/src/dpop.ts#L54) und [`deviceKey.ts`](../frontend/src/deviceKey.ts#L78):
  P-256, `extractable=false`, IndexedDB.
- [`session.ts`](../frontend/src/session.ts#L10): `channelSessionId` im `localStorage`.
- [`WebChannelView.tsx`](../frontend/src/components/WebChannelView.tsx#L22): Tokens des
  Web-Kanals im `sessionStorage`; [`webOidc.ts`](../frontend/src/webOidc.ts#L90): PKCE S256.
- [`kobilUnlockSecret.ts`](../frontend/src/kobilUnlockSecret.ts#L12): Entsperrgeheimnis im
  `localStorage`.
- [`adminAuth.ts`](../frontend/src/adminAuth.ts#L14): Basic-Zugangsdaten im `sessionStorage`.

**Offene Flanken:**

- **Niedrig** Keine Content-Security-Policy; Refresh-Token des Web-Kanals im Browser; kein
  `state`/`nonce` und keine `iss`-Prüfung im OIDC-Rückruf, nur PKCE (`DPoP-demo-dm2j`).
- **Bewusst** Entsperrgeheimnis und Admin-Zugang sind für jedes Skript der Seite lesbar; ein
  Produktivsystem bräuchte eine native App mit Hardware-Schlüsselspeicher.

---

## 12) Löschung und Aufbewahrung

**Doku:** [07-betrieb.md](07-betrieb.md) Abschnitt 3;
[ADR-39](adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md);
[ADR-46](adr/ADR-046-konto-im-aufbau.md).

**Code:**

- [`performDeleteAccount`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyActionExecutor.kt#L359)
  prüft das verlangte Niveau unmittelbar vor dem Löschen erneut.
- [`AccountDeletionService.deleteAccount`](../src/main/kotlin/com/example/identity/core/orchestrator/session/AccountDeletionService.kt#L40):
  Credentials, Gerätelinks, Kanäle, Tokens, Evidenz, Trace; Keycloak-Nutzer per Ereignis.
- [`ChangeLog`](../src/main/kotlin/com/example/identity/core/account/application/ChangeLog.kt#L34):
  überlebt die Löschung, hält Ereignisse, nie Werte.
- [`RetentionJob.cleanup`](../src/main/kotlin/com/example/identity/core/orchestrator/retention/RetentionJob.kt#L51).

**Offene Flanken:**

- **Offen (Entscheidung)** Aufbewahrungsfristen sind Richtwerte, mit Datenschutz festzulegen.
- **Niedrig** Nect-Fälle werden nie geräumt (S-2).
- **Hinweis** Arbeitsdaten einer Tool-Sitzung liegen unverschlüsselt in
  `orchestrator.tool_session.data`, bei `ident-fsc` mit Personendaten. Der Abschluss leert sie; eine
  nicht abgeschlossene Sitzung behält sie bis `tool-session.retention` (ADR-49, `DPoP-demo-bo1w`).

---

## 13) Außerhalb des Demomodus

**Worum es geht.** `demo.mode=false` heißt: Hier dürfen echte Personendaten liegen. Dann gibt es
keine Demo-Oberflächen, keine `demoOnly`-Verfahren und keine Klartext-Codes in Antworten, und der
Start bricht ab, solange eine Demo-Voreinstellung übrig ist.

**Doku:** [07-betrieb.md](07-betrieb.md) Abschnitt 3c;
[ADR-28](adr/ADR-028-demo-werte-abschaltbar.md);
[14-stand-und-weg-zur-produktion.md](14-stand-und-weg-zur-produktion.md) Abschnitte 4 und 5.

**Code:**

- [`DemoMode`](../src/main/kotlin/com/example/identity/demo/demo_mode/DemoMode.kt#L18)
  mit `@OnlyInDemoMode`/`@OutsideDemoMode`;
  [`@DemoSurface`](../src/main/kotlin/com/example/identity/demo/demo_mode/DemoSurface.kt#L13).
- [`ProductionModeCheck.violations`](../src/main/kotlin/com/example/identity/core/orchestrator/ProductionModeCheck.kt#L40):
  Admin-Passwort als Hash, H2-Konsole aus, Pepper und Lookup-Geheimnis lang genug, Keycloak über
  https mit geprüftem Zertifikat, keine API-Beschreibung (`springdoc.api-docs.enabled`).
- [`WithheldDemoDisclosure`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/DemoDisclosure.kt#L65):
  außerhalb des Demomodus gibt es keinen Baustein, der Demo-Werte in Antworten schreibt.
  Eine gewollte Ausnahme ist der KOBIL-PIN: Er steht in `stepData`, nicht im Demo-Block, weil die
  App ihn ans SDK weiterreicht (ADR-21, ADR-22); er kommt nur nach der Entsperrung auf dem
  verknüpften Gerät.

**Härtungen:**

- `ProductionModeCheck` nimmt als Admin-Passwort nur echte Hashes (`{bcrypt}`, `{argon2}`,
  `{scrypt}`, `{pbkdf2}`, kein `{noop}`) und startet nicht ohne das Profil `keycloak`, das sonst
  unsignierte Mock-Tokens ausgäbe (SA-13, SA-15).
- Auf OpenShift ist die H2-Konsole aus (SA-14,
  [08-projektrahmen.md](08-projektrahmen.md), „H2-Konsole: nur beim Host-Start“).
- `ProductionModeCheckTest`, `DemoModeSwitchTest`.

**Offene Flanken:**

- **Hinweis** Fehlt `demo.mode`, gilt der Demomodus (`matchIfMissing = true`, `application.yml`
  `${DEMO_MODE:true}`). Ein Deployment, das die Variable vergisst, läuft still als Demo
  (`DPoP-demo-davx`).
- **Hinweis** `ProductionModeCheck` prüft das Keycloak-Admin-Passwort nicht
  (`DPoP-demo-9ppv.3`); Keycloak läuft mit `start-dev` (`DPoP-demo-9msv`); Admin-Geheimnis auf
  OpenShift (`DPoP-demo-x25a`).
- **Betrieb** H2 statt PostgreSQL (`DPoP-demo-pi55`); Laufzeit-Image nicht gepinnt
  (`DPoP-demo-9ppv.5`). Die Abhängigkeiten prüft die CI gegen OSV (Abschnitt 14); Keycloak selbst
  nicht, seine Version steht im Versionskatalog und in den Image-Tags.

---

## 14) Womit man prüft

- `./gradlew test` enthält die Sicherheitstests; Einstieg: `DpopValidatorTest`,
  `DpopReplayProtectionDbTest`, `PeerAuthValidatorTest`, `PeerAuthRoundTripTest`,
  `DefaultAuthPolicyTest`, `ModelBasedJourneyTest`, `NoSecretsInLogIntegrationTest`,
  `AuthQrFlowIntegrationTest`, `ProductionModeCheckTest`.
- Architekturregeln: `ApiBoundaryArchitectureTest` (I-6), `RateLimitArchitectureTest` (I-25),
  `DemoModeSwitchTest`, `InvariantRegisterTest` (jede Invariante hat ihren Mechanismus).
- `./gradlew :keycloak-extension:test`: `LoginCompletionTest`, `OrchestratorResponseVerifierTest`.
- CI: CodeQL über alle drei Module, `npm audit`, OSV-Scanner über ein SBOM je ausgeliefertem
  Artefakt (Orchestrator, Keycloak-Erweiterung; nur Laufzeit-Abhängigkeiten); Actions per SHA.
- Gegen den compose-Stack: `npm run test:e2e:keycloak` (nicht in der CI).
- Ausführen und eigene Angriffe: [13-ausfuehren.md](13-ausfuehren.md).

---

## 15) Offene Flanken auf einen Blick

| Flanke | Schwere | Station | Issue |
|---|---|---|---|
| Replay-Tabelle wächst vor Kanal- und Drosselprüfung | niedrig | 2 | `DPoP-demo-9ppv.1` |
| Web-Kanal `AUTHENTICATED` vor der Keycloak-Sitzung; Abmeldung best effort | niedrig | 3 | `DPoP-demo-oe06` |
| Gerätelink ohne Fremdschlüssel (I-14) | niedrig | 3 | `DPoP-demo-hwc6` |
| Fehlerpfade des Authenticators | niedrig | 4 | `DPoP-demo-rdns` |
| Lookup-Orakel über Demo-TAN und Versandlatenz | niedrig | 7 | `DPoP-demo-36xz` |
| Nect-`retry` ohne Budget, Fälle ohne Aufbewahrung (S-2) | niedrig | 8, 12 | – |
| Personenzähler nach erfolgreichem Vorgangszugang (A-5) | niedrig | 9 | – |
| Kontosperre prüft vor, zählt nach dem Versuch (SA-27) | bewusst | 9 | `DPoP-demo-164n.29` |
| CSP, Refresh-Token und `state` im Frontend | niedrig | 11 | `DPoP-demo-dm2j` |
| Web-Kanal-Id aus der Tab-Id abgeleitet | Hinweis | 4 | `DPoP-demo-gxis` |
| Peer-Auth-Fenster 300 s im Profil | Hinweis | 4 | `DPoP-demo-9ppv.13` |
| QR-Status ohne Mindestintervall | Hinweis | 4 | `DPoP-demo-9ppv.10` |
| JSON in `<script>` | Hinweis | 4 | `DPoP-demo-9ppv.11` |
| `acr`-Prüfung der anfragenden Anwendung | Hinweis | 5 | `DPoP-demo-mea0` |
| Freischaltcode-Hash ohne Salz (Simulation, Port-Vertrag) | Hinweis | 8 | `DPoP-demo-4xnr` |
| Fehlendes `demo.mode` = Demomodus | Hinweis | 13 | `DPoP-demo-davx` |
| Keycloak-Admin-Passwort nicht im Startcheck | Hinweis | 13 | `DPoP-demo-9ppv.3` |
| Kein DPoP-Nonce | bewusst | 2 | – |
| Tokens nicht an DPoP gebunden | bewusst | 2 | ADR-9 |
| KOBIL-PIN im Klartext | bewusst | 8 | ADR-22 |
| Schlüssel in der Datenbank, keine Rotation | Entscheidung | 10 | `DPoP-demo-61kp` |
| Personenbezogene Spalten unverschlüsselt | Entscheidung | 10 | `DPoP-demo-bo1w` |
| TLS Keycloak ↔ Orchestrator, Proxy-Header | Umgebung | 2, 4 | `DPoP-demo-ai4x` |
| Keycloak `start-dev`, Secrets, PostgreSQL | Umgebung | 13 | `DPoP-demo-9msv`, `x25a`, `pi55` |
