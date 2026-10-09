# Lesepfad Sicherheit

Dieser Lesepfad führt durch Doku und Code. Er richtet sich an alle, die das System angreifen
(etwa bei einem Penetrationstest) oder seine Sicherheit abnehmen sollen. Er ist in Stationen
gegliedert. Jede Station nennt:

- worum es geht,
- wo die Regel beschrieben ist,
- welche Codestellen sie umsetzen,
- welche Härtungen es gibt, also zusätzliche Schutzmaßnahmen,
- welche Punkte noch offen sind.

Die Stationen folgen dem Weg einer Anfrage von außen nach innen.

So ist das Dokument zu lesen:

- **Codeverweise** zeigen auf die Zeile zum Stand 2026-10-03. Zeilennummern verschieben sich mit
  der Zeit. Maßgeblich ist deshalb der genannte Name (Klasse, Funktion).
- **Härtungen** sind umgesetzt. Wo ein Test oder eine Invariante genannt ist, sichert er sie ab.
- **Offene Punkte** sind bekannte Schwachstellen und Restrisiken. Jeder hat eine Schwere:
  mittel, niedrig oder Hinweis. „Bewusst“ steht dabei, wenn eine Architekturentscheidung (ADR) das
  Risiko in Kauf nimmt. Dazu kommt das Issue, das Sie mit `bd show <id>` ansehen können. Die
  Kürzel (S-, K-, A-, SA-) stammen aus den Bewertungen des Projekts. Welche davon noch offen sind
  und woher sie kommen, steht in [offene-befunde.md](offene-befunde.md).
- Die Liste aller offenen Punkte steht am Ende
  ([Abschnitt 15](#15-offene-flanken-auf-einen-blick)).

Der Maßstab ist [ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md): Der
Backend-Kern (`core/`, `contract/`, `tools/`) soll produktionsreif sein. Jede Sicherheitszusage
muss gelten, ohne dass sie stillschweigend etwas von der Umgebung voraussetzt. Für simulierte
Fremdsysteme, die Frontends und die Ausführungsumgebung gilt ein geringerer Anspruch
([14-stand-und-weg-zur-produktion.md](14-stand-und-weg-zur-produktion.md) Abschnitt 2).

---

## 0) Vorab lesen (eine Stunde)

1. [01-ueberblick.md](01-ueberblick.md): die Grundbegriffe. Dazu gehören Kanal, Journey, Tool,
   Evidenz, Anker und die Sicherheitsniveaus `loa1` bis `loa3`.
2. [invarianten.md](invarianten.md): die Regeln, auf die sich der Kern verlässt, jeweils mit dem
   Mechanismus, der sie erzwingt. Für die Sicherheit sind vor allem diese wichtig: I-1 bis I-8,
   I-15 bis I-19, I-22 bis I-25 und I-30 bis I-32.
3. [14-stand-und-weg-zur-produktion.md](14-stand-und-weg-zur-produktion.md), Abschnitte 4 und 5:
   was nur für die Demo gedacht ist und was noch fehlt, bevor echte Personendaten verarbeitet
   werden dürfen.
4. [offene-befunde.md](offene-befunde.md): alle noch offenen Befunde der Bewertungen, die
   Restrisiken und die offenen Entscheidungen.

Einige Begriffe kommen in diesem Lesepfad ständig vor (mehr im [Glossar](glossar/glossar.md)):

- **Orchestrator:** der Server dieses Projekts. Er steuert Registrierung und Anmeldung.
- **Keycloak:** das Produkt, das auf der Website die Anmeldung führt und Tokens ausstellt. Eine
  eigene Keycloak-Erweiterung verbindet Keycloak mit dem Orchestrator.
- **Kanal:** eine Verbindung eines Nutzers zum Orchestrator. Der App-Kanal spricht direkt mit dem
  Orchestrator, der Web-Kanal über Keycloak.
- **DPoP:** ein Standard (RFC 9449), mit dem jede Anfrage belegt, dass sie vom Besitzer eines
  bestimmten Schlüssels kommt. Den Beleg je Anfrage nennt man **DPoP-Proof**.
- **Peer-Auth:** wie sich Keycloak und Orchestrator gegenseitig ausweisen. Keycloak signiert jede
  Anfrage (die **Assertion**), der Orchestrator signiert jede Antwort.
- **Niveau** (`acr`): wie sehr einer Anmeldung vertraut wird, von `loa1` bis `loa3`.

### Vertrauensgrenzen

Eine Vertrauensgrenze ist eine Stelle, an der Daten von einem Beteiligten zu einem anderen
übergehen, dem er nicht einfach vertrauen darf. Die Tabelle zeigt, wie jede Grenze geschützt ist
und in welcher Station sie behandelt wird.

| Grenze | Wer spricht | Schutz | Station |
|---|---|---|---|
| App ↔ Orchestrator | Browser-App | DPoP-Proof je Anfrage, Kanal an Schlüssel gebunden | 2, 3 |
| Browser ↔ Keycloak ↔ Orchestrator | Keycloak-Erweiterung | signierte Peer-Auth-Assertion, signierte Antwort | 4 |
| Orchestrator → Keycloak | Orchestrator | `private_key_jwt` je Client, eigener Grant (eigene Art der Token-Anfrage) | 4, 10 |
| Orchestrator → Fremdsysteme | Ports | Port-Verträge (feste Schnittstellen), heute simuliert | 8 |
| Betrieb → Orchestrator | Admin | HTTP Basic, Sperre nach Fehlversuchen | 1, 13 |

---

## 1) Eingang: Welcher Endpunkt ist wie geschützt

**Worum es geht.** Jede Anfrage von außen trifft zuerst auf einen HTTP-Handler. Jeder Handler ist
entweder an einen Kanal gebunden (`@BindingKey`), per DPoP oder per Peer-Auth. Oder er nennt
ausdrücklich seinen eigenen Schutz (I-6). Spring Security schützt nur die Admin-Pfade. Alle
anderen Pfade lässt Spring Security durch, und die Handler prüfen sie selbst.

**Doku:** [invarianten.md](invarianten.md) I-6; [05-api.md](05-api.md);
[07-betrieb.md](07-betrieb.md) Abschnitt 3c.

**Code:**

- [`AdminSecurityConfig.adminChain`](../src/main/kotlin/com/example/identity/core/orchestrator/admin/AdminSecurityConfig.kt#L32)
  schützt `/orchestrator/admin/**`: Rolle ADMIN, HTTP Basic, ohne Sitzung (zustandslos).
  [`openChain`](../src/main/kotlin/com/example/identity/core/orchestrator/admin/AdminSecurityConfig.kt#L48)
  setzt `permitAll` für alle anderen Pfade. Die Admin-Zugänge stehen in
  [`adminUsers`](../src/main/kotlin/com/example/identity/core/orchestrator/admin/AdminSecurityConfig.kt#L56).
- [`AdminLoginRateLimitFilter`](../src/main/kotlin/com/example/identity/core/orchestrator/admin/AdminLoginRateLimitFilter.kt#L20)
  erlaubt fünf Versuche je Benutzername und 15 Minuten. Danach antwortet er mit `429`, auch für das
  richtige Passwort. Den Benutzernamen liest der Parser von Spring selbst. Jeder Versuch wird vor
  der Prüfung in einem `UPDATE` gezählt (SA-6, `AdminIntegrationTest`).
- [`RequestBodyLimitFilter`](../src/main/kotlin/com/example/identity/core/orchestrator/RequestBodyLimitFilter.kt)
  lässt höchstens 64 KB je Anfrage zu. Er prüft das, bevor irgendein anderer Code den Body liest
  (SA-10, `RequestBodyLimitIntegrationTest`).
- [`DpopBindingKeyResolver.bindingKeyOf`](../src/main/kotlin/com/example/identity/core/orchestrator/api/v1/DpopBindingKeyResolver.kt#L52)
  ermittelt, an welchen Schlüssel eine Anfrage gebunden ist. Aus einem DPoP-Header wird der
  Thumbprint, also ein Fingerabdruck des Schlüssels. Sonst wird aus einer Peer-Auth-Assertion
  `kc:<channel_binding>`. Endpunkte mit `keycloakOnly` lehnen DPoP ab. Endpunkte mit `dpopOnly`
  (App-Kanal anlegen, Geräteverknüpfung) lehnen eine Assertion ab (SA-19,
  `DpopBindingKeyResolverTest`).
- [`ToolContextResolver.resolveArgument`](../src/main/kotlin/com/example/identity/core/orchestrator/api/v1/ToolContextResolver.kt#L45):
  Die `toolId` kommt aus dem Controller, nie vom Client.
- [`ReadinessGateFilter`](../src/main/kotlin/com/example/identity/core/orchestrator/ReadinessGateFilter.kt#L16)
  antwortet mit 503, bis der Dienst bereit ist.

**Härtungen:**

- Ein Endpunkt ohne `@BindingKey` und ohne benannten Schutz lässt den Build scheitern
  (`ApiBoundaryArchitectureTest`).
- Es gibt keine CORS-Konfiguration. Spring lässt deshalb nur Anfragen von derselben Herkunft zu.
- Der Actuator (Endpunkte für Zustand und Messwerte) läuft auf einem eigenen Port
  (`MANAGEMENT_PORT`, 9080) und bietet nur `health` und `prometheus` an.
- „Try it out“ in der Swagger-UI ist abgeschaltet. Die Swagger-UI und `/v3/api-docs` gibt es nur
  im Demomodus (`springdoc.api-docs.enabled: ${demo.mode}`). `ProductionModeCheck` lehnt es ab,
  diese Einstellung zu überschreiben. Der Demomodus ist der Schalter, der alles nur zum Vorführen
  Gedachte einschaltet.
- [`ServerInfoController`](../src/main/kotlin/com/example/identity/core/orchestrator/admin/ServerInfoController.kt#L83)
  ist ohne Anmeldung erreichbar, weil der Web-Kanal daraus seine Keycloak-Adresse liest. Zustand,
  Zähler und Latenzen (`operations`) gibt er nur im Demomodus heraus (`ServerInfoControllerTest`).

**Offene Punkte:**

- **Hinweis** [`DemoSessionsController`](../src/main/kotlin/com/example/identity/core/orchestrator/admin/DemoSessionsController.kt#L19)
  und der Demo-Schalter für LoA1 sind ohne Anmeldung erreichbar. Es gibt sie
  aber nur im Demomodus.
- **Hinweis** Die H2-Konsole ist im Demomodus über `openChain` offen. Nur die Voreinstellung von H2,
  `web-allow-others=false`, beschränkt sie auf localhost (`DPoP-demo-9msv`). Außerhalb des
  Demomodus verlangt `ProductionModeCheck`, dass sie ausgeschaltet ist.
- **Hinweis** `server-info` nennt auch außerhalb des Demomodus, welche Tools der Betreiber gesperrt
  hat (`disabledTools`).

---

## 2) DPoP im App-Kanal

**Worum es geht.** Jede Anfrage der App enthält einen frischen, signierten DPoP-Proof. Der
Thumbprint des Schlüssels (`binding_key_ref`) bindet den Kanal an diesen Schlüssel. Ein Proof gilt
genau einmal.

**Doku:** [09-dpop.md](09-dpop.md) (ganz); [invarianten.md](invarianten.md) I-7, I-8.

**Code:**

- Die Prüfung des Proofs:
  [`DpopValidator.validate`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/DpopValidator.kt#L26),
  dazu [`validateHeader`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/DpopValidator.kt#L74)
  (`typ=dpop+jwt`, nur ES256/384/512),
  [`validateSignature`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/DpopValidator.kt#L84)
  (nur EC, kein privater Schlüssel im `jwk`) und
  [`validateClaims`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/DpopValidator.kt#L98)
  (`htm`, `htu`, Zeitfenster für `iat`, `jti`).
- [`RequestUrls.htuMatches`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/RequestUrls.kt#L26)
  vergleicht die Adresse nach RFC 9449.
  [`buildRequestUrl`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/RequestUrls.kt#L11)
  ist hinter einem Proxy von der Einstellung `forward-headers-strategy` abhängig.
- [`DpopReplayProtectionService.validateAndStore`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/DpopReplayProtectionService.kt#L26)
  und [`DpopProofReplayRepository.insert`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/DpopProofReplay.kt#L38)
  schützen vor Replay, also vor dem Wiedereinspielen eines Proofs. Das `INSERT` selbst ist die
  Prüfung: Scheitert es, war der Proof schon da. Es läuft in einer eigenen Transaktion.
- [`JwkThumbprintService.computeBase64UrlThumbprint`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/JwkThumbprintService.kt#L16)
  berechnet den Thumbprint nach RFC 7638.
- [`DeviceProofValidator`](../src/main/kotlin/com/example/identity/core/orchestrator/dpop/DeviceProofValidator.kt#L28)
  verlangt einen eigenen Typ `typ=device-proof+jwt`. So kann ein Kanal-Proof nie als Geräte-Proof
  gelten und umgekehrt.
- Im Frontend: [`generateDpopKeyPair`](../frontend/src/dpop.ts#L54) (`extractable=false`,
  IndexedDB) und [`createDpopProof`](../frontend/src/dpop.ts#L119).

**Härtungen:**

- `alg=none` und HMAC sind als Signaturverfahren ausgeschlossen, und `typ` ist fest vorgegeben. Die
  Fehlercodes sind fest und verraten keine Einzelheiten.
- Das Zeitfenster für `iat` erlaubt 60 s Alter und 30 s Vorlauf (D-7, D-8).
- Der Schlüssel in der Replay-Tabelle ist SHA-256(`thumbprint:jti`) und hat damit eine feste Länge.
  Gespeichert wird mit einem ausdrücklichen `INSERT` statt mit `save`
  (`DpopReplayProtectionDbTest`, nacheinander und gleichzeitig).
- Ein Geräte-Credential darf nicht der DPoP-Schlüssel des Kanals sein
  ([`EnrollDeviceFlow`](../src/main/kotlin/com/example/identity/tools/auth_device/internal/enrolldevice/EnrollDeviceFlow.kt#L30)).
- Der Thumbprint wird über den neu kodierten Schlüssel berechnet, nicht über die Schreibweise des
  Clients. Dadurch hat ein Schlüssel genau einen Thumbprint (SA-18, `JwkThumbprintServiceTest`).

**Offene Punkte:**

- **Bewusst** Der Server verlangt keine Nonce (keine Zufallszahl vom Server im Proof). Wer den
  Schlüssel kurz benutzen kann, kann deshalb Proofs für rund 90 s im Voraus berechnen
  ([09-dpop.md](09-dpop.md) Abschnitt 2).
- **Nicht anwendbar** `ath`: Anfragen an den Orchestrator enthalten kein Access Token. DPoP bindet
  hier den Kanal an den Schlüssel, nicht ein Token.
- **Bewusst** Die Keycloak-Tokens aus `GET …/token` sind nicht an den DPoP-Schlüssel gebunden
  (kein `cnf.jkt`, [ADR-9](adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md),
  [09-dpop.md](09-dpop.md) Abschnitt 4).
- **Niedrig** Jeder syntaktisch gültige Proof schreibt eine Zeile in die Replay-Tabelle. Das
  geschieht, bevor der Orchestrator den Kanal oder die Drosselung prüft. Neue Schlüssel kosten einen
  Angreifer nichts (`DPoP-demo-9ppv.1`).
- **Umgebung** Die Prüfung von `htu` hinter einem Proxy ist nur sicher, wenn ein
  vertrauenswürdiger Proxy die Header `X-Forwarded-*` immer überschreibt (`DPoP-demo-ai4x`).

---

## 3) Kanal, Bindung, Lebensdauer

**Worum es geht.** Ein Kanal gehört genau einem Schlüssel (App) oder genau einem Anmeldevorgang
in Keycloak (Web). Er gehört höchstens einem Subjekt, also einem Konto oder einer Einladung. Und
er besteht nie länger als seine Sitzung in Keycloak.

**Doku:** [09-dpop.md](09-dpop.md) Abschnitt 3; [02-domaenenmodell.md](02-domaenenmodell.md);
[ADR-43](adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md);
[invarianten.md](invarianten.md) I-1, I-5, I-22 bis I-24.

**Code:**

- [`ChannelSession`](../src/main/kotlin/com/example/identity/core/orchestrator/session/ChannelSession.kt#L25)
  enthält `bindingKeyRef` (App), `channelBinding` (Web), `expiresAt` und `@Version`.
- [`DeviceChannelAccessGuard`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ChannelAccessGuard.kt#L35)
  vergleicht die Bindung in konstanter Zeit, damit die Dauer des Vergleichs nichts verrät.
  [`requireLiveChannel`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ChannelAccessGuard.kt#L24)
  und [`LiveChannel`](../src/main/kotlin/com/example/identity/core/orchestrator/session/LiveChannel.kt#L13)
  verhindern, dass ein beendeter Kanal noch etwas schreibt.
- [`SessionManagementService.createChannelSession`](../src/main/kotlin/com/example/identity/core/orchestrator/session/SessionManagementService.kt#L34)
  legt immer einen neuen Kanal an und sucht nie einen vorhandenen über den Schlüssel.
  [`findChannelSessionById`](../src/main/kotlin/com/example/identity/core/orchestrator/session/SessionManagementService.kt#L70)
  behandelt einen abgelaufenen Kanal wie einen, den es nicht gibt.
- [`ChannelCreationRateLimitService`](../src/main/kotlin/com/example/identity/core/orchestrator/session/ChannelCreationRateLimitService.kt#L17)
  erlaubt 20 Kanäle je 5 Minuten je Schlüssel.
- [`JourneyActionExecutor.linkDeviceTo`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyActionExecutor.kt#L341)
  verknüpft ein Gerät nur nach Rückfrage neu. Dabei widerruft er jedes Credential, das an den
  Schlüssel gebunden ist.
- [`KeycloakChannelService.signedOutAtKeycloak`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/KeycloakChannelService.kt#L64):
  Eine Abmeldung in Keycloak beendet die Web- und App-Kanäle dieser Sitzung.

**Härtungen:**

- Die Datenbankregel (CHECK) `ck_channel_session_binding_key` sorgt dafür, dass nur der App-Kanal
  einen Schlüssel hat (I-8). `ck_channel_session_one_subject` sorgt dafür, dass ein Kanal einem
  Konto oder einer Einladung gehört, nie beidem (I-5).
- Will ein bestehender Kanal das Konto wechseln, antwortet der Orchestrator mit `409`. Es gibt
  keinen stillen Wechsel.
- Die Verknüpfung eines Geräts mit einem Konto entsteht erst mit dem ersten eingerichteten
  Verfahren. Der bloße Besitz des DPoP-Schlüssels gilt nie als Anmeldung.

**Offene Punkte:**

- **Niedrig** Der Web-Kanal steht nach dem letzten Schritt schon auf `AUTHENTICATED`, bevor
  Keycloak die Sitzung anlegt. Die Meldung einer Abmeldung und `restore-data` werden nur nach
  bestem Bemühen („best effort“) zugestellt (`DPoP-demo-oe06`).
- **Niedrig** I-14 (kein Gerätelink auf ein gelöschtes Konto) ist nicht durch einen Fremdschlüssel
  in der Datenbank abgesichert (`DPoP-demo-hwc6`).

---

## 4) Web-Kanal: Keycloak und Orchestrator

**Worum es geht.** Keycloak spricht ohne mTLS (gegenseitige Zertifikatsprüfung) mit dem
Orchestrator. Stattdessen enthält jede Anfrage eine signierte Assertion. Sie bindet die Methode,
die Adresse und den Kanal. Jede Antwort des Orchestrators ist ebenfalls signiert und an Anfrage,
Status und Body gebunden. Keycloak schließt eine Anmeldung nur mit dem Subjekt und dem Niveau ab,
die der Orchestrator nennt.

**Doku:** [ADR-7](adr/ADR-007-web-kanal-ohne-mtls-signierte-request-assertion-statt.md);
[05-api.md](05-api.md) Abschnitt 3b (Keycloak-Endpunkte); [04-orchestrierung.md](04-orchestrierung.md)
Abschnitt 5 „RestoreData als erster Übergang“; [invarianten.md](invarianten.md) I-15, I-16, I-31.

**Code im Orchestrator:**

- [`PeerAuthValidator.validate`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/PeerAuthValidator.kt#L36)
  prüft die Assertion: `typ=peer-auth+jwt`, nur ES256, `kid` aus dem JWKS von Keycloak (der Liste
  seiner öffentlichen Schlüssel), `iss`, `aud`, `htm`, `htu` samt Query, `body_sha256`, `iat`,
  `jti` mit Replay-Schutz. `channel_binding` ist Pflicht. Den Body liest
  [`PeerAuthBodyCaptureFilter`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/PeerAuthRequestBinding.kt)
  einmal mit, bevor ein anderer Code ihn auswertet.
- [`KeycloakJwkSource.find`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/KeycloakJwkSource.kt#L31)
  lädt die Schlüssel mit Größenlimit und Zeitlimits. Ist keine `jwks-uri` eingestellt, lehnt er
  jede Assertion ab („fail-closed“: im Zweifel ablehnen).
- [`KeycloakChannelAccessGuard`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ChannelAccessGuard.kt#L72):
  Das `channel_binding` muss zum Kanal passen. Eine bekannte Kanal-Id allein reicht nicht.
- [`KeycloakChannelService.upsertChannel`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/KeycloakChannelService.kt#L102):
  Ein fremdes Subjekt ergibt `409`. RestoreData (ein signierter Zettel mit früheren Nachweisen
  derselben Sitzung) gilt nur für dasselbe Subjekt. Unbekannte native Tools scheitern.
- [`KeycloakChannelService.restoreData`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/KeycloakChannelService.kt#L239)
  kürzt die Frist des Kanals auf das Ende der Keycloak-Sitzung. Für Einladungen gibt er nichts
  heraus (I-30).
- [`RestoreDataCodec.decode`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/RestoreDataCodec.kt#L51)
  prüft HMAC, `sub` (muss die Keycloak-Sitzung sein) und Ablauf. Jeder Fehler ergibt `null`.
- [`KeycloakResponseSigner.sign`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/KeycloakResponseSigning.kt#L42)
  signiert die Antwort mit `req`, `status` und `body_sha256`, gültig für 60 s.
  [`KeycloakResponseSigningFilter`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/KeycloakResponseSigning.kt#L75)
  signiert nur Antworten auf Assertions, die
  [`PeerAuthValidator.verify`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/PeerAuthValidator.kt#L54)
  annimmt. Diese Prüfung umfasst alles außer der Einmaligkeit.
- [`KeycloakSignOutController`](../src/main/kotlin/com/example/identity/core/orchestrator/api/v1/keycloak/KeycloakSignOutController.kt#L32):
  Das `channel_binding` muss das adressierte Konto oder die adressierte Einladung sein.

**Code in der Keycloak-Erweiterung:**

- [`PeerAuthAssertionSigner`](../keycloak-extension/src/main/java/com/example/identity/kcext/client/PeerAuthAssertionSigner.java#L21)
  signiert die Assertions. Der Schlüssel ist als Geheimnis der Komponente gespeichert
  ([`OrchestratorSettings.ensureSigningKey`](../keycloak-extension/src/main/java/com/example/identity/kcext/client/OrchestratorSettings.java#L100)).
- [`OrchestratorClient.send`](../keycloak-extension/src/main/java/com/example/identity/kcext/client/OrchestratorClient.java#L323)
  prüft jede Antwort, bevor es den Status auswertet. Die Prüfung der Inhalte steht in
  [`OrchestratorResponseVerifier.checkClaims`](../keycloak-extension/src/main/java/com/example/identity/kcext/client/OrchestratorResponseVerifier.java#L94).
- [`OrchestratorAuthenticator.handleResponse`](../keycloak-extension/src/main/java/com/example/identity/kcext/login/OrchestratorAuthenticator.java#L142):
  Welcher Nutzer angemeldet wird, ergibt sich nur aus dem Subjekt, das der Orchestrator nennt.
- [`LoginCompletion.judge`](../keycloak-extension/src/main/java/com/example/identity/kcext/login/LoginCompletion.java#L37):
  Eine Anmeldung ist nur fertig, wenn es ein Subjekt gibt, es dasselbe Subjekt ist und das `acr`
  mindestens das Ziel des Subflows erreicht (`acr ≥` Ziel).
- [`OrchestratorResumeAuthenticator.authenticate`](../keycloak-extension/src/main/java/com/example/identity/kcext/login/OrchestratorResumeAuthenticator.java#L40)
  nimmt RestoreData höchstens einmal je Auth-Session an. Er setzt das angefragte Niveau als
  Untergrenze und nimmt keine Einladungen an. Scheitert die Wiederaufnahme, läuft eine normale
  Anmeldung. Ein Fehler gewährt also nichts.
- [`OrchestratorNotes.stashRestoreDataAtFlowEnd`](../keycloak-extension/src/main/java/com/example/identity/kcext/login/OrchestratorNotes.java#L153).
- [`QrWaitStatusResourceProvider.status`](../keycloak-extension/src/main/java/com/example/identity/kcext/resource/QrWaitStatusResourceProvider.java#L74)
  ist ohne Anmeldung erreichbar. Er verlangt aber das signierte Cookie `AUTH_SESSION_ID` von
  Keycloak und antwortet nur mit `waiting` oder `ready`.
- [`OrchestratorStorageProvider.isValid`](../keycloak-extension/src/main/java/com/example/identity/kcext/federation/OrchestratorStorageProvider.java#L146):
  Ist der Orchestrator nicht erreichbar, wirft er einen Fehler, statt `false` zu liefern. Keycloak
  kann die Nutzer nur lesen, nicht ändern (I-15).

**Härtungen:**

- Antworten sind auch bei Fehlern und bei `304` signiert. Die Erweiterung prüft die Signatur vor
  jeder Auswertung (`OrchestratorResponseVerifierTest`, `PeerAuthRoundTripTest`).
- Jeder Keycloak-Client des Orchestrators hat seinen eigenen Schlüssel (I-16). Das Vertrauen in ein
  selbstsigniertes Zertifikat gilt nie für die ganze JVM (I-17, `KeycloakHttp`).
- Fehler des Orchestrators zählen nicht als Fehlversuch für den Brute-Force-Schutz von Keycloak.
  Das gilt auch für Ausfälle und unsignierte Antworten. Die Authenticatoren rufen nie `failure()`
  auf (K-1, SA-12; `ApiFailureTest`, `NoBruteForceBookingTest`).
- `LoginCompletion` kennt `loa3`. Ein unbekanntes Ziel-Niveau lässt keine Anmeldung durch (SA-16,
  `LoginCompletionTest`).
- Das Realm schließt die eigenen Anmeldewege von Keycloak
  ([`V7__locked_down_defaults`](../keycloak-migrations/src/main/resources/keycloak-migrations/V7__locked_down_defaults.kc.kts),
  SA-2, SA-3, SA-7). Im Einzelnen:
  - Es gibt nur die eigene Required Action, kein „Passwort ändern“ und kein „Passwort vergessen“.
  - Der Browser-Flow des Realms ist der des Orchestrators.
  - Direct Grants lehnt ein eigener Flow ab.
  - `admin-cli` hat keinen Passwort-Grant.
  - Die Account-Konsole ist aus.
  - Die Projekt-Clients haben kein `offline_access`.

  Das prüft `LockedDownDefaultsMigrationTest` gegen den compose-Stack. Die Federation (die
  Nutzerquelle, über die Keycloak Konten beim Orchestrator liest) lehnt jede Passwortänderung ab,
  statt Keycloak das Passwort lokal speichern zu lassen (`OrchestratorStorageProviderTest`, ADR-38
  Nachtrag).
- Der Bootstrap-Client mit dem Recht `create-realm` holt seine `jwks-url` nur über https oder über
  Loopback (SA-20, `MigrationClientJwksUrlTest`).
- Ein Niveau über `loa1` beruht nur auf Nachweisen der letzten 30 Minuten. Das gilt auch, wenn
  eine Sitzung über den Weg der Wiederaufnahme fortgesetzt wird (S-1, erledigt; I-32).
- Zeitlimits von 3 s/10 s in beide Richtungen. Das JWKS wird auch über `KeycloakHttp` mit
  Größenlimit geladen, höchstens einmal je 30 s, auch bei Fehlern. Der letzte gültige Satz
  Schlüssel bleibt erhalten (SA-17, `KeycloakJwkSourceBackoffTest`). Antworten an die Erweiterung
  liest diese höchstens bis 1 MB.
- Der Orchestrator signiert nichts Beliebiges, das ein Angreifer ihm vorlegt (kein
  „Signatur-Orakel“). Eine gefälschte Assertion oder ein ausgetauschter Body bekommt eine
  unsignierte Antwort (`KeycloakResponseSigningFilterTest` mit echtem Validator).
- Ein unbekanntes `targetAcr` ergibt `400`, bevor sich am Kanal etwas ändert. Es wird nie still zu
  `none` (`KeycloakChannelIntegrationTest`).
- `availableTools` wird mit dem Katalog abgeglichen, nur Tools aus dem Katalog bleiben übrig
  ([`ChannelService.catalogToolsOf`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ChannelService.kt#L104),
  `ChannelToolDeclarationIntegrationTest`).
- Formulardaten (`toolId`, `methodInstanceId`) werden nur als einfaches Pfadsegment
  `[A-Za-z0-9._~-]` übernommen, ohne `.` und `..`
  ([`OrchestratorClient.segment`](../keycloak-extension/src/main/java/com/example/identity/kcext/client/OrchestratorClient.java#L357),
  `OrchestratorClientSegmentTest`). Sie können die signierte Adresse also nicht verändern.

**Offene Punkte:**

- **Niedrig** Bei Fehlern zeigt der Authenticator teilweise die allgemeine Fehlerseite von Keycloak
  (`DPoP-demo-rdns`).
- **Hinweis** Das JWKS für die Prüfung der Antworten nutzt die Voreinstellungen der Bibliothek
  Nimbus, ohne `outageTolerant` (K-6).
- **Hinweis** Jeder GET auf die Action-URL (die Adresse, an die das Anmeldeformular geschickt wird)
  wird zur Eingabe für das Tool (K-8). Dafür braucht man Aktionscode und Cookie.
- **Hinweis** Das Zeitfenster für Peer-Auth beträgt 300 s im ganzen Profil `keycloak` statt nur in
  der Variante `host` (`DPoP-demo-9ppv.13`).
- **Hinweis** Die Kanal-Id des Web-Kanals wird aus der Tab-Id von Keycloak abgeleitet
  ([`OrchestratorNotes.channelSessionId`](../keycloak-extension/src/main/java/com/example/identity/kcext/login/OrchestratorNotes.java#L77))
  und ist damit vorhersagbar. Für einen Zugriff braucht man trotzdem eine signierte Assertion mit
  passendem `channel_binding` (`DPoP-demo-gxis`).
- **Hinweis** Der Endpunkt für den QR-Status hat kein Mindestintervall (`DPoP-demo-9ppv.10`).
- **Bewusst** Anmeldefaktoren, die Keycloak selbst prüft (native Faktoren im `amr`), gelten ohne
  Obergrenze
  ([`AcrLevels.HIGHEST`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/KeycloakChannelService.kt#L146)).
  Keycloak gilt hier als vertrauenswürdig.
- **Umgebung** Keycloak und Orchestrator sprechen im compose-Stack über http (`DPoP-demo-ai4x`). Die
  Signaturen in beide Richtungen sichern die Integrität, einschließlich Body und Query. Die
  Vertraulichkeit sichern sie nicht.

---

## 5) Niveaus: Was ein Nachweis wert ist

**Worum es geht.** Das Niveau (`acr`) speichert der Orchestrator nie. Er berechnet es bei jedem
Lesen neu aus der Evidenz, also aus den gesammelten Nachweisen. Drei Obergrenzen verhindern, dass
sich ein Verfahren selbst aufwertet:

- was das Tool höchstens liefern kann (`maxAcr`),
- unter welchem Niveau das Verfahren eingerichtet wurde (`enrolledUnderAcr`),
- die Grenze nach NIST für kombinierte Faktoren.

**Doku:** [04-orchestrierung.md](04-orchestrierung.md) Abschnitt 4;
[ADR-5](adr/ADR-005-drei-obergrenzen-fuer-das-sicherheitsniveau.md);
[ADR-36](adr/ADR-036-niveaus-und-ihre-nachweise.md); [invarianten.md](invarianten.md) I-4, I-21,
I-32.

**Code:**

- [`AcrLevel`](../src/main/kotlin/com/example/identity/contract/tool_api/claims/AcrLevel.kt#L10):
  Ein unbekanntes Niveau hat Rang 0.
- In der Richtlinie für Sicherheitsniveaus:
  [`DefaultAuthPolicy.resolveAcr`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/policy/DefaultAuthPolicy.kt#L31)
  (Alterung), [`isSatisfied`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/policy/DefaultAuthPolicy.kt#L60),
  [`cappedAcr`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/policy/DefaultAuthPolicy.kt#L168),
  [`combinedAcr`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/policy/DefaultAuthPolicy.kt#L190),
  [`authCandidates`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/policy/DefaultAuthPolicy.kt#L109).
- [`Tool.staysWithin`](../src/main/kotlin/com/example/identity/contract/tool_api/Tool.kt#L246)
  und [`ToolJourneyService.checkStaysWithin`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ToolJourneyService.kt#L367):
  Ein Ergebnis über `maxAcr` oder außerhalb der Faktortypen des Tools ist ein harter Fehler.
- [`CredentialRules.levelToWriteUnder`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/journey/CredentialRules.kt#L28)
  und [`proofLevel`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/journey/CredentialRules.kt#L37).
  Angewandt werden sie in [`performAdoptCredential`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyActionExecutor.kt#L225)
  und [`performAcceptProof`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyActionExecutor.kt#L283).
- Weitergabe an Keycloak: [`ChannelResponseAssembler.authDataFor`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ChannelResponseAssembler.kt#L70)
  und [`KeycloakTokenProvider.requestAccountToken`](../src/main/kotlin/com/example/identity/core/orchestrator/session/KeycloakTokenProvider.kt#L91).
  In Keycloak: [`AccountTokenGrantType.process`](../keycloak-extension/src/main/java/com/example/identity/kcext/grant/AccountTokenGrantType.java#L61),
  [`AccountTokenClaims`](../keycloak-extension/src/main/java/com/example/identity/kcext/grant/AccountTokenClaims.java#L11),
  [`OrchestratorAcrAmrMapper.setClaim`](../keycloak-extension/src/main/java/com/example/identity/kcext/token/OrchestratorAcrAmrMapper.java#L69).

**Härtungen:**

- Identifizierung und Anmeldefaktoren werden nie zu einer Stufe der Mehr-Faktor-Anmeldung (MFA)
  zusammengerechnet. Ab `loa3` muss die MFA innerhalb einer Achse liegen, also nur aus
  Identifizierung oder nur aus Anmeldefaktoren bestehen.
- Gerätegebundene Verfahren werden nur auf dem verknüpften Gerät angeboten
  ([`Tool.usableByCaller`](../src/main/kotlin/com/example/identity/contract/tool_api/Tool.kt#L254)).
- Der eigene Grant in Keycloak nimmt nur bekannte Werte für `acr` und `amr` an, die dem Muster
  `[a-z0-9_-]+` folgen. Er gilt nur für einen vertraulichen Client mit einem eigenen Attribut. Eine
  Sitzung setzt er nur für denselben Nutzer fort.
- Tests: `DefaultAuthPolicyTest` (Alter 29/31 min, unbekanntes Alter), `ModelBasedJourneyTest`.

**Offene Punkte:**

- **Hinweis** Bricht ein Nutzer einen Step-up ab (das Hochstufen auf ein höheres Niveau), bleibt der
  Kanal auf dem bisherigen Niveau angemeldet. Das ist richtig. Es schützt aber nur, wenn die
  anfragende Anwendung das `acr` gegen ihre Anforderung prüft (`DPoP-demo-mea0`).
- **Bewusst** Das `acr` im Token altert nicht. Es beschreibt wie bei Keycloak üblich die Anmeldung.
  Wer ein frisches `loa2` braucht, fragt mit `acr_values` neu an oder prüft `auth_time` (04 §4,
  [offene Befunde](offene-befunde.md) Abschnitt 6).
- **Offen (Entscheidung)** `loa3` im Web-Realm (`DPoP-demo-wzcm`). Außerdem: ob ein Verfahren nach
  erneuter Identifizierung aufgewertet wird (`DPoP-demo-wyp3`).

---

## 6) Journey: nur das angebotene Tool, nur einmal

**Worum es geht.** Eine Journey ist ein geführter Ablauf aus mehreren Schritten, etwa eine
Anmeldung. Der Client kann dabei kein Verfahren frei wählen. Er kann nur ein Tool aktivieren, das
der aktuelle Zustand anbietet. Er schreibt nur in den gerade aktiven Schritt. Und ein Ergebnis
zählt genau einmal.

**Doku:** [04-orchestrierung.md](04-orchestrierung.md) Abschnitte 4, 5 und 7;
[ADR-32](adr/ADR-032-tool-sperre-und-reihenfolge-je-kanal.md);
[journeys/](journeys/); [invarianten.md](invarianten.md) I-2, I-3.

**Code:**

- [`JourneyService.activate`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyService.kt#L270)
  lehnt ein Tool ab, das nicht angeboten ist.
  [`JourneyState.activatable`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/journey/state/JourneyState.kt#L70)
  berechnet, was aktivierbar ist: die angebotenen Tools ohne die abgelehnten, und davon nur die
  verfügbaren.
- [`isCurrent`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyService.kt#L293),
  [`applyOutcome`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyService.kt#L306),
  [`chargeAttempt`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyService.kt#L526)
  (Budget von 3 Versuchen) und [`fallBack`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyService.kt#L555)
  (ein abgebrochener Step-up wird nie `AUTHENTICATED`).
- [`ToolJourneyService.beginActivation`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ToolJourneyService.kt#L100),
  [`validatePreconditions`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ToolJourneyService.kt#L132),
  [`loadCurrent`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ToolJourneyService.kt#L154)
  und [`applyOutcome`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ToolJourneyService.kt#L233)
  (danach steht der Tool-Durchlauf, die ToolSession, auf `DONE`).
- [`ToolAvailabilityService`](../src/main/kotlin/com/example/identity/core/orchestrator/tool/ToolAvailabilityService.kt#L21)
  verwaltet die Sperre je Fassung und die Reihenfolge je Tool, beides je Kanal. `demoOnly`-Tools
  gibt es außerhalb des Demomodus in keiner Fassung.
- [`RunningJourney`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/RunningJourney.kt#L16):
  Nur eine gestartete, nicht abgelaufene Journey lässt sich verwenden.

**Härtungen:**

- Ein Angebot darf veralten. Bei der Ausführung prüft der Orchestrator aber erneut mit dem
  aktuellen Stand ([04-orchestrierung.md](04-orchestrierung.md) Abschnitt 8).
- Ein Ergebnis muss zur Rolle des Tools passen (`chargeRateLimits`, `applyOutcome`).
- Nach drei Fehlversuchen steht die Journey auf `FAILED` und nimmt nichts mehr an (I-2). Dieses
  Ende wird dauerhaft gespeichert und nicht mit der Antwort `410` zurückgerollt
  (`JourneyEndedException`, SA-1, `JourneyFallbackChainIntegrationTest`). Damit hat jeder
  ausgegebene Code (TAN, E-Mail-Code, eID-PIN, Bestätigungscode) höchstens drei Rateversuche.

**Offene Punkte:**

- **Niedrig** Es gibt keinen Test für zwei gleichzeitige `PATCH` auf dieselbe ToolSession.
  Geschützt ist der Fall über `@Version` (`DPoP-demo-df48`).
- **Niedrig** „`auth-invite` nur im Web-Kanal“ ist eine umschaltbare Voreinstellung, keine feste
  Eigenschaft des Tools. Die dritte Prüfstelle antwortet mit 500 statt 409 (A-4).

---

## 7) Identität und Kontozuordnung

**Worum es geht.** Ein Konto wird nur über Anker gefunden. Anker sind Werte, die ein Konto eindeutig
bezeichnen: die Ausweiskennung, die Partnernummer oder eine bestätigte E-Mail-Adresse. Ein
Ankerwert gehört höchstens einem Konto. Bei einem Konflikt lehnt der Orchestrator ab, er führt
Konten nie zusammen. Nur ein vorläufiges Konto wird in das gefundene Konto übernommen.

**Doku:** [02-domaenenmodell.md](02-domaenenmodell.md);
[ADR-11](adr/ADR-011-kontouebergreifender-person-id-konflikt-ist-abweisung-merge-nie.md),
[ADR-19](adr/ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md),
[ADR-20](adr/ADR-020-ein-vorlaeufiges-konto-geht-im-gefundenen-auf-statt.md);
[invarianten.md](invarianten.md) I-9 bis I-13.

**Code:**

- [`AnchorDecision.decide`](../src/main/kotlin/com/example/identity/core/account/domain/AnchorDecision.kt#L39):
  Ein Anker, der schon einem anderen Konto gehört, führt zu `IdentityConflictException`.
  Unveränderliche Anker bleiben, und es gilt eine Untergrenze für das Niveau.
  [`AnchorRegistry.bind`](../src/main/kotlin/com/example/identity/core/account/application/AnchorRegistry.kt#L26)
  ist die einzige Stelle, die Anker schreibt.
- [`IdentityMatchingService.resolve`](../src/main/kotlin/com/example/identity/core/account/application/IdentityMatchingService.kt#L48),
  [`attestedIdentityMatches`](../src/main/kotlin/com/example/identity/core/account/application/IdentityMatchingService.kt#L67),
  [`resolveByAnchor`](../src/main/kotlin/com/example/identity/core/account/application/IdentityMatchingService.kt#L102).
- [`AccountMerge.decide`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/journey/AccountRules.kt#L78),
  [`accountOfProof`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/journey/AccountRules.kt#L131),
  [`performRecordIdentification`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyActionExecutor.kt#L108),
  [`AccountService.absorbDisposableAccount`](../src/main/kotlin/com/example/identity/core/account/AccountService.kt#L172).

**Härtungen:**

- Nur `JourneyActionExecutor` befragt den `IdentityResolver`. Eine Person wird also nur dort
  ermittelt, wo sie auch an das Konto gebunden wird (I-10).
- `ident-kvnr` verrät nicht, ob eine fremde Nummer existiert. Der Fehlversuch zählt trotzdem bei
  der fremden Person (I-12).
- Haben zwei Personen denselben Namen, muss zusätzlich die Adresse passen.

**Offene Punkte:**

- **Niedrig** Lookup-Tools suchen das Konto anhand der Eingabe. Bei ihnen verraten die Demo-TAN und,
  mit einem echten Anbieter, die Dauer des Versands, ob zu einer Adresse ein Konto existiert
  (`DPoP-demo-36xz`).
- **Hinweis, bewusst** `auth-invite` und `ident-fsc`: Eine unbekannte Nummer kostet nichts, eine
  bekannte antwortet messbar anders (S-7, `DPoP-demo-36xz`).
- **Hinweis** I-10 gilt laut Architekturregel nur für den Resolver, nicht für den
  `IdentityMatchingService` (`DPoP-demo-9ppv.23`).

---

## 8) Die Verfahren im Einzelnen

Diese Station geht die einzelnen Anmelde- und Identifizierungsverfahren durch und nennt jeweils
die wichtigsten Schutzmaßnahmen.

**Doku:** [06-ablaeufe.md](06-ablaeufe.md) und je Verfahren eine Seite unter
[verfahren/](verfahren/README.md); [verfahren/qr.md](verfahren/qr.md), „Sicherheit des Pairing-Codes“;
[port-vertraege.md](port-vertraege.md).

**Passwort** ([`PasswordHasher.matches`](../src/main/kotlin/com/example/identity/tools/auth_password/internal/PasswordHasher.kt#L26)):
Passwörter werden mit Argon2id und den Parametern nach OWASP gehasht. Nach einer erfolgreichen
Prüfung wird neu gehasht, falls nötig. Fehlt ein Passwort, prüft der Code gegen einen Dummy-Hash,
damit die Antwortzeit nichts verrät. Ein Passwort hat höchstens 128 Zeichen
([`PasswordPolicy`](../src/main/kotlin/com/example/identity/tools/auth_password/internal/PasswordPolicy.kt#L12)).
Die Anmeldung per Lookup antwortet immer gleich mit „E-Mail oder Passwort ungültig“
([`AuthPasswordLookupToolHandler.patch`](../src/main/kotlin/com/example/identity/tools/auth_password/internal/authpasswordlookup/AuthPasswordLookupToolHandler.kt#L42)).

**SMS und E-Mail** ([`TanGenerator`](../src/main/kotlin/com/example/identity/tools/auth_sms/internal/TanGenerator.kt#L21),
[`matches`](../src/main/kotlin/com/example/identity/tools/auth_sms/internal/TanGenerator.kt#L40),
[`EmailCodeGenerator.matches`](../src/main/kotlin/com/example/identity/tools/auth_email/internal/EmailCodeGenerator.kt#L40)):
Der Code hat sechs Ziffern aus `SecureRandom`. Gespeichert wird er als HMAC-SHA256 mit einem
Pepper (einem geheimen Zusatzwert des Servers). Er ist 5 Minuten gültig, und der Vergleich läuft in
konstanter Zeit. Eine falsche TAN
([`WrongTan`](../src/main/kotlin/com/example/identity/tools/auth_sms/internal/authsms/AuthSmsToolHandler.kt#L68))
macht die TAN nicht ungültig. Das Budget der Journey begrenzt die Versuche je TAN aber auf drei.
Versendet werden höchstens 3 Codes je 10 Minuten je Nummer oder Adresse
([`SmsSendLimit`](../src/main/kotlin/com/example/identity/tools/auth_sms/internal/SmsSendLimit.kt#L16),
[`EmailSendLimit`](../src/main/kotlin/com/example/identity/tools/auth_email/internal/EmailSendLimit.kt#L15)).

**QR-Login** ([`PairingCodeGenerator`](../src/main/kotlin/com/example/identity/tools/auth_qr/internal/PairingCodeGenerator.kt#L10)):
Der Pairing-Code hat etwa 40 Bit und gilt 5 Minuten. Die App zeigt einen Bestätigungscode an, den
der Nutzer im Browser eintippt (I-18). Das schützt davor, dass ein Angreifer einem Opfer seinen
eigenen QR-Code unterschiebt. Jeder Wechsel des Zustands ist ein bedingtes `UPDATE`
([`completeIfConfirmed`](../src/main/kotlin/com/example/identity/tools/auth_qr/internal/QrLoginRequestRepository.kt#L51),
[`countWrongConfirmation`](../src/main/kotlin/com/example/identity/tools/auth_qr/internal/QrLoginRequestRepository.kt#L65)).
So gewinnt immer nur eine Anfrage, und nach drei falschen Codes ist die Anfrage ungültig.
[`ConfirmQrLoginToolHandler.patch`](../src/main/kotlin/com/example/identity/tools/auth_qr/internal/confirmqrlogin/ConfirmQrLoginToolHandler.kt#L53)
verlangt, dass das QR-Verfahren eingeschaltet ist und das erwartete Konto bestätigt.
[`QrLoginBrowserSide.advance`](../src/main/kotlin/com/example/identity/tools/auth_qr/internal/QrLoginBrowserSide.kt#L46)
lehnt eine Wiederholung nach `COMPLETED` ab.

**Gerät** ([`AuthDeviceToolHandler`](../src/main/kotlin/com/example/identity/tools/auth_device/internal/authdevice/AuthDeviceToolHandler.kt#L26)):
Das Gerät schickt einen signierten Geräte-Proof. Statt einer Zufallsaufgabe vom Server
(Server-Challenge) ist `htu` an die einmalige Adresse der ToolSession gebunden. Dazu kommt ein
Replay-Schutz. Dass der Nutzer sich am Gerät verifiziert hat (etwa per Fingerabdruck), behauptet nur
der Client. Deshalb ist das Verfahren `demoOnly`.

**KOBIL** ([ADR-21](adr/ADR-021-der-kobil-pin-liegt-im-backend-und-das.md),
[ADR-22](adr/ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md)): Der PIN liegt im
Backend, versiegelt unter dem Hauptschlüssel der Journey, die ihn eingerichtet hat
([`KobilEnrollment`](../src/main/kotlin/com/example/identity/tools/auth_kobil/internal/KobilEnrollment.kt),
`KobilPins`, ADR-55); der Server kann ihn lesen (ADR-22).
Das Entsperrgeheimnis (256 Bit) liegt dort nur als Hash
([`KobilSecrets.matches`](../src/main/kotlin/com/example/identity/tools/auth_kobil/internal/KobilSecrets.kt#L39)).
[`releasePin`](../src/main/kotlin/com/example/identity/tools/auth_kobil/internal/authkobil/AuthKobilToolHandler.kt#L74)
gibt die PIN für 120 s frei, und zwar nur in dieser einen Antwort.

**Vorgangszugang** ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md),
[`AuthInviteToolHandler.patch`](../src/main/kotlin/com/example/identity/tools/auth_invite/internal/AuthInviteToolHandler.kt#L38),
[`Einladungen.redeem`](../src/main/kotlin/com/example/identity/simulation/personenverzeichnis/Einladungen.kt#L109)):
Das Einmalkennwort hat etwa 59 Bit und wird nie gespeichert. Die Id ist ein SHA-256 über Person,
Kennwort und Vorgang. Der Vergleich läuft in konstanter Zeit. Ein gesperrter, ein unbekannter und
ein falscher Zugang bekommen dieselbe Antwort.

**Freischaltcode** ([`IdentFscToolHandler.patch`](../src/main/kotlin/com/example/identity/tools/ident_fsc/internal/IdentFscToolHandler.kt#L46)):
Der Nutzer gibt erst seine Personendaten ein, dann den Code. Für alle Fehlerfälle gibt es dieselbe
Ablehnung.

**eID, KVNR, Nect** ([`IdentKvnrToolHandler.patch`](../src/main/kotlin/com/example/identity/tools/ident_kvnr/internal/IdentKvnrToolHandler.kt#L42),
[`IdentNectToolHandler.patch`](../src/main/kotlin/com/example/identity/tools/ident_nect/internal/IdentNectToolHandler.kt#L76),
[`acceptedReturnUri`](../src/main/kotlin/com/example/identity/tools/ident_nect/internal/IdentNectToolHandler.kt#L111)):
Die Rücksprungadresse für Nect muss mit einem eingestellten Präfix beginnen. Ist keins eingestellt,
ist nichts erlaubt ([ADR-47](adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md)). Der Nect-Fall
gehört zur ToolSession und wird auf dem Server genau einmal eingelöst. eID und Nect sind
Simulationen und `demoOnly`.

**Offene Punkte:**

- **Bewusst** Der KOBIL-PIN ist für den Server lesbar, nicht gehasht (ADR-22); gespeichert versiegelt (ADR-55). Das Entsperrgeheimnis liegt im Browser
  im `localStorage` (Station 11).
- **Niedrig** `ident-nect`: `retry` hat kein Budget, und Nect-Fälle werden nicht aufgeräumt (S-2,
  kein Issue).
- **Hinweis, bewusst** Die Action-URL von Keycloak samt Aktionscode wird in zwei Tabellen
  gespeichert und an das Fremdsystem geschickt (S-8).
- **Hinweis** Der Freischaltcode ist im simulierten Personenverzeichnis als SHA-256 ohne Salt
  gespeichert ([`Freischaltcodes.hash`](../src/main/kotlin/com/example/identity/simulation/personenverzeichnis/Freischaltcodes.kt#L121)).
  Bei rund 40 Bit lässt er sich aus einer Kopie der Datenbank offline erraten. Der Port-Vertrag
  sollte nennen, was ein echtes System hier leisten muss (`DPoP-demo-4xnr`).
- **Hinweis** Die eID-Simulation prüft eine feste Test-PIN
  ([`IdentEidFlow`](../src/main/kotlin/com/example/identity/tools/ident_eid/internal/IdentEidFlow.kt#L132)).
  Sie ist nur im Demomodus verfügbar.

---

## 9) Zähler, Sperren, Versandlimits

**Worum es geht.** Zähler begrenzen, wie oft jemand etwas versuchen oder verschicken darf. Der
Orchestrator führt alle Zähler. Die Module legen die Regeln fest, jedes nur in seinem eigenen
Namensraum. Jede Änderung an einem Zähler ist ein einziges `UPDATE`.

**Doku:** [ADR-44](adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md);
[07-betrieb.md](07-betrieb.md) Abschnitt 4; [invarianten.md](invarianten.md) I-25.

**Code:**

- [`RateLimit`](../src/main/kotlin/com/example/identity/contract/tool_api/ratelimit/RateLimit.kt#L25)
  (der Namensraum folgt aus der Klasse) und [`ModuleRateLimits`](../src/main/kotlin/com/example/identity/core/orchestrator/session/ModuleRateLimits.kt#L20)
  (die Schlüssel sind HMACs mit dem Pepper).
- [`RateLimitCounter.recordFailure`](../src/main/kotlin/com/example/identity/core/orchestrator/session/RateLimitCounter.kt#L35),
  [`recordWindowedAttempt`](../src/main/kotlin/com/example/identity/core/orchestrator/session/RateLimitCounter.kt#L62)
  (lehnt im Zweifel ab, „fail-closed“) und [`RateLimitRecordRepository.incrementFailure`](../src/main/kotlin/com/example/identity/core/orchestrator/session/RateLimitRecordRepository.kt#L51).
- [`AccountLockoutService`](../src/main/kotlin/com/example/identity/core/orchestrator/session/AccountLockoutService.kt#L22)
  und [`PersonLockoutService`](../src/main/kotlin/com/example/identity/core/orchestrator/session/PersonLockoutService.kt#L16)
  sperren jeweils nach 5 Fehlversuchen für 15 Minuten.
- [`ToolJourneyService.chargeRateLimits`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ToolJourneyService.kt#L266).

**Härtungen:**

- Lookup-Tools melden eine Sperre wie eine gewöhnliche Ablehnung. Eine Sperre verrät deshalb nicht,
  dass es ein Konto gibt.
- Ist das Konto bekannt, prüft der Orchestrator die Sperre bei jedem Versuch, nicht nur beim
  Aktivieren. Eine vorher geöffnete Sitzung kann deshalb nicht weiter raten und meldet sich auch mit
  dem richtigen Passwort nicht an
  ([`ToolJourneyService.loadCurrent`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/ToolJourneyService.kt),
  SA-26, `AccountRateLimitIntegrationTest`).
- Löscht jemand sein Konto, werden nur die Zähler des Kontos zurückgesetzt, nicht die der Person.
  Wer sein Konto löscht, bekommt also keine neuen Versuche.
- Tests: `RateLimitArchitectureTest`, `AttemptLockoutExpiryDbTest`, `AccountRateLimitIntegrationTest`.

**Offene Punkte:**

- **Niedrig** Ein erfolgreicher Vorgangszugang setzt den Zähler der Person nicht zurück (A-5, kein
  Issue).
- **Niedrig, bewusst** Die Kontosperre prüft zuerst und zählt danach. Parallele Versuche über
  mehrere Kanäle kommen deshalb alle durch die Prüfung, bevor der fünfte Versuch gezählt ist. Das
  bleibt ein Restrisiko, bis eine Passwortanmeldung per Lookup produktiv geht
  ([07-betrieb.md](07-betrieb.md) Abschnitt 4, SA-27, `DPoP-demo-164n.29`).
- **Niedrig** Fehlgeschlagene QR-Suchen haben keinen eigenen Zähler
  ([verfahren/qr.md](verfahren/qr.md), „Sicherheit des Pairing-Codes“).

---

## 10) Geheimnisse, Schlüssel, Logs

Diese Station zeigt, wo Schlüssel und Geheimnisse liegen und wie das System verhindert, dass
Geheimnisse ins Log gelangen.

**Doku:** [14-stand-und-weg-zur-produktion.md](14-stand-und-weg-zur-produktion.md) Abschnitt 5;
[invarianten.md](invarianten.md) I-16, I-17, I-19.

**Was wie gespeichert ist:**

- Versiegelt unter dem Datenschlüssel der Claim-Gruppe: `account.claim.claim_value` (ADR-52).
- Versiegelt unter dem Tagesschlüssel: `orchestrator.tool_session.data` (ADR-53).
- Versiegelt unter dem Hauptschlüssel des Kontos: App-Tokens (`orchestrator.app_token_session`),
  `account.auth_method.label` und `reference`, `account.sign_in_log.details` (ADR-53, ADR-55).
- Versiegelt unter dem Hauptschlüssel der Journey, den das Konto übernimmt:
  `auth_sms.enrollment.phone_number`, `auth_kobil.enrollment.pin` (ADR-55).
- Als HMAC: `account.claim.value_digest`, `account.retraction.value_digest` (ADR-52),
  `account.change_log.lookup_key`.
- Als Hash mit Pepper: Passwörter, OTP- und QR-Codes, KOBIL-Entsperrgeheimnis, Freischaltcode.
- Lesbar: `account.anchor` (ADR-52), die Simulationen (`personenverzeichnis`, `nect`, `kobil`).
- In der Demo liegen die versiegelten Werte lesbar hinter einem Kopf, der den Schlüssel nennt;
  die Schlüssel selbst bleiben eingepackt (ADR-55).

**Code:**

- [`KmsNodeKeys`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/KmsNodeKeys.kt#L24):
  Die privaten Schlüssel des Orchestrators (für Client-Assertions und die Signatur der Antworten)
  liegen im Schlüsseldienst, der signiert und sie nie herausgibt (ADR-54); in der Demo ist das die
  Simulation `kms`.
  [`OrchestratorClientAssertionSigner`](../src/main/kotlin/com/example/identity/core/orchestrator/keycloak/OrchestratorClientAssertionSigner.kt#L25)
  hat einen Schlüssel je Client.
- [`RestoreDataCodec`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/RestoreDataCodec.kt#L51):
  Der HMAC-Schlüssel wird bei jedem Start zufällig erzeugt. Ein Neustart macht deshalb alle
  RestoreData ungültig.
- [`PersonLookupKey`](../src/main/kotlin/com/example/identity/core/account/application/PersonLookupKey.kt#L29):
  Suchschlüssel im Änderungsprotokoll werden als Hash mit Geheimnis gespeichert, nie im Klartext.

**Härtungen:**

- Kein Code und kein Empfänger wird ins Log geschrieben (I-19, `NoSecretsInLogIntegrationTest`). Wertobjekte
  melden abgelehnte Werte, ohne den Rohwert zu nennen.
- Kein `println`/`System.out` (geprüft per ArchUnit).
- Auch Angaben aus abgelehnten DPoP-Proofs und Peer-Auth-Assertions erreichen das Log nur
  gefiltert, auch `alg` und `kid` aus dem Header (SA-11, `OrchestratorExceptionHandlerTest`).
- Was ein Client in Pfad oder Query schickt (`intent`, `toolId`, `nativeToolId`), erreicht das Log
  nur ohne Steuer- und Zeilentrennzeichen und auf 200 Zeichen begrenzt
  ([`OrchestratorException.loggable`](../src/main/kotlin/com/example/identity/core/orchestrator/domain/OrchestratorException.kt#L22),
  `OrchestratorExceptionTest`).
- `ProductionModeCheck` verlangt für Pepper und Lookup-Geheimnis mindestens 32 Zeichen.

**Offene Punkte:**

- **Offen (Umsetzung)** Adapter für den echten Schlüsseldienst Vault Transit (ADR-56,
  `DPoP-demo-lmt9`). Lesbar bleiben `account.anchor` (ADR-52) und die Simulationen (ADR-53, ADR-55).
- **Betrieb** Mehrere Instanzen brauchen einen festen Pepper und eine gemeinsame Sperre für
  geplante Aufgaben (`DPoP-demo-g7np`). Ein leerer Pepper bedeutet: bei jedem Start ein neuer,
  zufälliger.

---

## 11) Frontend

**Worum es geht.** Für das Frontend gilt nur der Anspruch einer Demo (ADR-35, Bereich 3). Die
Schlüssel lassen sich nicht exportieren. Sie liegen aber in den Daten des Browsers, nicht in einem
Hardware-Speicher.

**Doku:** [09-dpop.md](09-dpop.md) Abschnitt 2 „Wie sicher die Schlüssel im Browser sind“;
[10-frontend.md](10-frontend.md).

**Code:**

- [`dpop.ts`](../frontend/src/dpop.ts#L54) und [`deviceKey.ts`](../frontend/src/deviceKey.ts#L78):
  P-256, `extractable=false`, IndexedDB.
- [`session.ts`](../frontend/src/session.ts#L10): `channelSessionId` im `localStorage`.
- [`WebChannelView.tsx`](../frontend/src/components/WebChannelView.tsx#L22): Tokens des
  Web-Kanals im `sessionStorage`. [`webOidc.ts`](../frontend/src/webOidc.ts#L90): PKCE S256.
- [`kobilUnlockSecret.ts`](../frontend/src/kobilUnlockSecret.ts#L12): Entsperrgeheimnis im
  `localStorage`.
- [`adminAuth.ts`](../frontend/src/adminAuth.ts#L14): Basic-Zugangsdaten im `sessionStorage`.

**Offene Punkte:**

- **Niedrig** Es gibt keine Content-Security-Policy. Das Refresh-Token des Web-Kanals liegt im
  Browser. Beim OIDC-Rückruf (der Rückkehr von Keycloak nach der Anmeldung) gibt es kein
  `state`/`nonce` und keine Prüfung von `iss`, nur PKCE (`DPoP-demo-dm2j`).
- **Bewusst** Das Entsperrgeheimnis und der Admin-Zugang sind für jedes Skript der Seite lesbar.
  Ein Produktivsystem bräuchte eine native App mit einem Schlüsselspeicher in Hardware.

---

## 12) Löschung und Aufbewahrung

Diese Station zeigt, was beim Löschen eines Kontos passiert und wie lange Daten aufbewahrt werden.

**Doku:** [07-betrieb.md](07-betrieb.md) Abschnitt 3;
[ADR-39](adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md);
[ADR-46](adr/ADR-046-konto-im-aufbau.md).

**Code:**

- [`performDeleteAccount`](../src/main/kotlin/com/example/identity/core/orchestrator/journey/JourneyActionExecutor.kt#L359)
  prüft das verlangte Niveau unmittelbar vor dem Löschen noch einmal.
- [`AccountDeletionService.deleteAccount`](../src/main/kotlin/com/example/identity/core/orchestrator/session/AccountDeletionService.kt#L40)
  löscht Credentials, Gerätelinks, Kanäle, Tokens, Evidenz und das Journey-Protokoll (Trace). Den
  Keycloak-Nutzer entfernt ein Ereignis.
- [`ChangeLog`](../src/main/kotlin/com/example/identity/core/account/application/ChangeLog.kt#L34)
  bleibt nach der Löschung erhalten. Es enthält Ereignisse, aber nie Werte.
- [`RetentionJob.cleanup`](../src/main/kotlin/com/example/identity/core/orchestrator/retention/RetentionJob.kt#L51).

**Offene Punkte:**

- **Offen (Entscheidung)** Die Aufbewahrungsfristen sind Richtwerte. Sie müssen mit dem Datenschutz
  festgelegt werden.
- **Niedrig** Nect-Fälle werden nie aufgeräumt (S-2).
- **Hinweis** Die Arbeitsdaten eines Tool-Durchlaufs liegen in `orchestrator.tool_session.data`,
  bei `ident-fsc` mit Personendaten, verschlüsselt unter dem Datenschlüssel ihres Tages (ADR-53).
  Der Abschluss des Durchlaufs leert sie. Ein nicht abgeschlossener Durchlauf behält sie bis zum
  Ablauf von `tool-session.retention` (ADR-49).

---

## 13) Außerhalb des Demomodus

**Worum es geht.** `demo.mode=false` heißt: Hier dürfen echte Personendaten liegen. Dann gibt es
keine Demo-Oberflächen, keine `demoOnly`-Verfahren und keine Codes im Klartext in Antworten. Der
Start bricht ab, solange noch eine Demo-Voreinstellung gesetzt ist.

**Doku:** [07-betrieb.md](07-betrieb.md) Abschnitt 3c;
[ADR-28](adr/ADR-028-demo-werte-abschaltbar.md);
[14-stand-und-weg-zur-produktion.md](14-stand-und-weg-zur-produktion.md) Abschnitte 4 und 5.

**Code:**

- [`DemoMode`](../src/main/kotlin/com/example/identity/demo/demo_mode/DemoMode.kt#L18)
  mit `@OnlyInDemoMode`/`@OutsideDemoMode`;
  [`@DemoSurface`](../src/main/kotlin/com/example/identity/demo/demo_mode/DemoSurface.kt#L13).
- [`ProductionModeCheck.violations`](../src/main/kotlin/com/example/identity/core/orchestrator/ProductionModeCheck.kt#L40)
  prüft beim Start:
  - das Admin-Passwort ist als Hash hinterlegt,
  - die H2-Konsole ist aus,
  - Pepper und Lookup-Geheimnis sind lang genug und nicht der Demo-Wert, der Schlüsseldienst ist
    nicht die Simulation (ADR-54), die Verschlüsselung ist eingeschaltet (ADR-55), und jeder
    gespeicherte Schlüssel trägt eine KEK-Version, die der Dienst noch auspackt,
  - Keycloak wird über https mit geprüftem Zertifikat angesprochen,
  - es gibt keine API-Beschreibung (`springdoc.api-docs.enabled`).
- [`WithheldDemoDisclosure`](../src/main/kotlin/com/example/identity/core/orchestrator/channel/DemoDisclosure.kt#L65):
  Außerhalb des Demomodus gibt es keinen Baustein, der Demo-Werte in Antworten schreibt. Eine
  gewollte Ausnahme ist die KOBIL-PIN. Sie steht in `stepData`, nicht im Demo-Block, weil die App sie
  an das SDK weitergibt (ADR-21, ADR-22). Sie wird nur nach der Entsperrung auf dem verknüpften Gerät
  ausgeliefert.

**Härtungen:**

- `ProductionModeCheck` akzeptiert als Admin-Passwort nur echte Hashes (`{bcrypt}`, `{argon2}`,
  `{scrypt}`, `{pbkdf2}`, kein `{noop}`). Außerdem startet der Dienst nicht ohne das Profil
  `keycloak`. Ohne dieses Profil würde er unsignierte Mock-Tokens ausgeben (SA-13, SA-15).
- Auf OpenShift ist die H2-Konsole aus (SA-14,
  [13-ausfuehren.md](13-ausfuehren.md) Abschnitt 3, „H2-Konsole: nur beim Host-Start“).
- Tests: `ProductionModeCheckTest`, `DemoModeSwitchTest`.

**Offene Punkte:**

- **Hinweis** Fehlt `demo.mode`, gilt der Demomodus (`matchIfMissing = true`, `application.yml`
  `${DEMO_MODE:true}`). Ein Deployment, das die Variable vergisst, läuft also unbemerkt als Demo
  (`DPoP-demo-davx`).
- **Hinweis** `ProductionModeCheck` prüft das Admin-Passwort von Keycloak nicht
  (`DPoP-demo-9ppv.3`). Keycloak läuft mit `start-dev` (`DPoP-demo-9msv`). Das Admin-Geheimnis auf
  OpenShift ist offen (`DPoP-demo-x25a`).
- **Betrieb** H2 statt PostgreSQL (`DPoP-demo-pi55`). Das Laufzeit-Image ist nicht auf eine feste
  Version gepinnt (`DPoP-demo-9ppv.5`). Die Abhängigkeiten prüft die CI gegen die
  Schwachstellen-Datenbank OSV (Abschnitt 14). Keycloak selbst prüft sie nicht. Seine Version steht
  im Versionskatalog und in den Image-Tags.

---

## 14) Womit man prüft

Diese Station nennt die Tests und Werkzeuge, mit denen Sie die Sicherheitsaussagen selbst
nachprüfen können.

- `./gradlew test` enthält die Sicherheitstests. Gute Einstiegspunkte sind: `DpopValidatorTest`,
  `DpopReplayProtectionDbTest`, `PeerAuthValidatorTest`, `PeerAuthRoundTripTest`,
  `DefaultAuthPolicyTest`, `ModelBasedJourneyTest`, `NoSecretsInLogIntegrationTest`,
  `AuthQrFlowIntegrationTest`, `ProductionModeCheckTest`.
- Architekturregeln: `ApiBoundaryArchitectureTest` (I-6), `RateLimitArchitectureTest` (I-25),
  `DemoModeSwitchTest` und `InvariantRegisterTest` (jede Invariante hat ihren Mechanismus).
- `./gradlew :keycloak-extension:test`: `LoginCompletionTest`, `OrchestratorResponseVerifierTest`.
- In der CI laufen:
  - CodeQL über alle drei Module,
  - `npm audit`,
  - der OSV-Scanner über ein SBOM (eine Liste aller Abhängigkeiten) je ausgeliefertem Artefakt
    (Orchestrator, Keycloak-Erweiterung; nur Abhängigkeiten zur Laufzeit),
  - GitHub Actions, die per SHA auf eine feste Version gepinnt sind.
- Gegen den compose-Stack: `npm run test:e2e:keycloak` (läuft nicht in der CI).
- Wie Sie das System starten und eigene Angriffe ausprobieren, steht in
  [13-ausfuehren.md](13-ausfuehren.md).

---

## 15) Offene Flanken auf einen Blick

Diese Tabelle fasst alle offenen Punkte der Stationen zusammen.

| Offener Punkt | Schwere | Station | Issue |
|---|---|---|---|
| Replay-Tabelle wächst vor der Prüfung von Kanal und Drosselung | niedrig | 2 | `DPoP-demo-9ppv.1` |
| Web-Kanal ist `AUTHENTICATED` vor der Keycloak-Sitzung; Abmeldung nur best effort | niedrig | 3 | `DPoP-demo-oe06` |
| Gerätelink ohne Fremdschlüssel (I-14) | niedrig | 3 | `DPoP-demo-hwc6` |
| Fehlerpfade des Authenticators | niedrig | 4 | `DPoP-demo-rdns` |
| Lookup verrät über Demo-TAN und Versanddauer, ob ein Konto existiert | niedrig | 7 | `DPoP-demo-36xz` |
| Nect-`retry` ohne Budget, Fälle werden nicht aufgeräumt (S-2) | niedrig | 8, 12 | – |
| Personenzähler nach erfolgreichem Vorgangszugang (A-5) | niedrig | 9 | – |
| Kontosperre prüft vor dem Versuch, zählt danach (SA-27) | bewusst | 9 | `DPoP-demo-164n.29` |
| CSP, Refresh-Token und `state` im Frontend | niedrig | 11 | `DPoP-demo-dm2j` |
| Id des Web-Kanals aus der Tab-Id abgeleitet | Hinweis | 4 | `DPoP-demo-gxis` |
| Peer-Auth-Zeitfenster 300 s im Profil | Hinweis | 4 | `DPoP-demo-9ppv.13` |
| QR-Status ohne Mindestintervall | Hinweis | 4 | `DPoP-demo-9ppv.10` |
| `acr`-Prüfung der anfragenden Anwendung | Hinweis | 5 | `DPoP-demo-mea0` |
| Hash des Freischaltcodes ohne Salt (Simulation, Port-Vertrag) | Hinweis | 8 | `DPoP-demo-4xnr` |
| Fehlendes `demo.mode` bedeutet Demomodus | Hinweis | 13 | `DPoP-demo-davx` |
| Admin-Passwort von Keycloak nicht im Startcheck | Hinweis | 13 | `DPoP-demo-9ppv.3` |
| Keine DPoP-Nonce | bewusst | 2 | – |
| Tokens nicht an DPoP gebunden | bewusst | 2 | ADR-9 |
| KOBIL-PIN für den Server lesbar, nicht gehasht | bewusst | 8 | ADR-22, ADR-55 |
| Schlüsseldienst nur simuliert, Adapter für Vault Transit fehlt | Umgebung | 10 | ADR-56, `DPoP-demo-lmt9` |
| `account.anchor` und Simulationen lesbar | Entscheidung | 10 | ADR-52, ADR-55 |
| TLS zwischen Keycloak und Orchestrator, Proxy-Header | Umgebung | 2, 4 | `DPoP-demo-ai4x` |
| Keycloak `start-dev`, Secrets, PostgreSQL | Umgebung | 13 | `DPoP-demo-9msv`, `x25a`, `pi55` |
