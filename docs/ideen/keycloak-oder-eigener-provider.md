# Idee: Keycloak behalten oder Token-Ausgabe in den Orchestrator holen?

**Worum es geht.** Seit [ADR-38](../adr/ADR-038-keycloak-liest-konten.md),
[ADR-58](../adr/ADR-058-keycloak-fuehrt-keine-eigenen-anmeldeschritte.md) und
[ADR-59](../adr/ADR-059-nachweise-je-keycloak-sitzung-im-orchestrator.md) tut Keycloak nur noch
wenig: Es spricht OIDC mit der Website, hält die Sitzung, stellt Tokens aus und erneuert sie. Keine
Credentials, keine Kontodaten, keine eigenen Anmeldeschritte. Die Frage liegt nahe, ob diese schmale
Rolle den Preis der Kopplung noch wert ist, oder ob der Orchestrator die Token-Ausgabe gleich selbst
übernehmen soll. Die umgekehrte Frage, ob nicht alles nach Keycloak gehört, ist ebenfalls zu
beantworten.

**Warum das wichtig ist.** Extension, Theme, Migrationen und die Keycloak-Anbindung im Backend
wachsen mit jedem Verfahren weiter. Wer hier investiert, sollte wissen, ob der Weg Bestand hat. Die
Antwort hängt an einer Anforderung, die das Repository heute nicht festlegt: ob neben der eigenen
Website weitere Clients, auch Dritt-Apps, dieselben Konten nutzen sollen.

**Stand: Abwägung, nicht entschieden** (Stand 2026-10-11). Gehört zu Kapitel 14, Abschnitt 6,
Schritt 2 („mit Entwicklern Architektur abstimmen“).

---

## 1) „Alles nach Keycloak“: empirisch gescheitert

Diesen Weg hat das Projekt gelebt und zurückgebaut (ADR-8 → ADR-58). Die Gründe tragen weiter:

- Keycloaks Flow-Engine ist seiten- und browserzentriert. Die App braucht eine API-getriebene
  Zustandsmaschine (`next`), keine Redirect-Seiten. Dafür hat Keycloak kein Modell.
- Journeys, `AuthPolicy`, das Zusammenfassen der Nachweise zu `acr`/`amr`, Anker und Angaben,
  Journey-Trace, Verschlüsselung je Konto, Aufbewahrung: All das wäre Code auf privaten SPIs in
  Keycloak, mit Infinispan statt Datenbank, schwer testbar und bei jedem Release zu prüfen. Der
  Spike `DPoP-demo-i9ni` hat gezeigt, dass schon das Umhüllen eines einzelnen nativen Verfahrens an
  Keycloak-Interna hängt ([Native Verfahren als Tools](native-verfahren-als-tools.md)).
- Millionen Konten in Keycloaks Nutzer-Datenbank sind genau das, was ADR-38 vermeidet.

Diese Richtung ist abgehakt. Der Orchestrator bleibt der Ort für Journeys, Verfahren, Niveaus und
Konten. Offen ist nur die Rolle von Keycloak.

## 2) Was Keycloak heute kostet

Grob gemessen (Stand 2026-10-11):

| Teil | Umfang |
|---|---|
| `keycloak-extension` (Grant, Authenticator, Federation, Mapper, Event-Listener) | rund 5.700 Zeilen auf `keycloak-server-spi-private` |
| `keycloak-theme` (Keycloakify), faktisch ein zweites Frontend für die Tool-Seiten | rund 1.450 Zeilen |
| `keycloak-migrations` | rund 1.800 Zeilen |
| Keycloak-Anbindung im Backend | rund 2.700 von rund 35.000 Zeilen |

Dazu die strukturellen Kosten, die in der Doku schon stehen:

- **Zwei Zustandshalter**, die zusammenpassen müssen: `ChannelSession` und Keycloak-Sitzung
  ([ADR-43](../adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md), mit den Lücken
  der Regel I-23), Nachweise je Keycloak-Sitzung (ADR-59).
- **Aufrufe in beide Richtungen**, jede mit eigener Vertrauensgrenze (Peer-Auth-Assertion,
  `private_key_jwt`, signierte Antworten). Drei der Engpässe in
  [14-stand-und-weg-zur-produktion.md](../14-stand-und-weg-zur-produktion.md) Abschnitt 7 entstehen
  daraus: die Federation liest bei fast jeder Token-Erneuerung, Keycloak ruft in einer offenen
  Transaktion zurück, die Signatur wird doppelt geprüft.
- **DPoP endet an `GET …/token`** ([ADR-9](../adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md)).
  Die Tokens von Keycloak sind Bearer-Tokens ohne `cnf.jkt`.
- Zwei Deployables, ein Keycloak-Cluster mit Sitzungsspeicher, Neuaufbau des Realms bei einer
  geänderten Migration.

## 3) Der Gegenentwurf: OIDC-Provider im Orchestrator

Mit Spring Authorization Server kämen Discovery, Authorization Code mit PKCE, Token- und
Refresh-Endpunkt, JWKS mit Rotation, Client-Registry, Introspection, Revocation und Logout fertig.

- **Gewinn:** ein Zustand, eine Sitzung, ein Frontend für die Tool-Seiten (das Theme entfällt),
  keine Peer-Auth, kein Rückruf in einer Transaktion, keine private SPI. Und Tokens nach RFC 9449
  an den Geräteschlüssel gebunden, für App und Web durchgängig.
- **Aufwand:** eine Sitzungsverwaltung für den Web-Kanal (Cookie, Leerlauf- und Höchstdauer,
  Back- und Front-Channel-Logout). `ChannelSession` und der simulierte `TokenService` sind der Keim
  davon. Netto weniger Code als Extension, Theme, Migrationen und Anbindung zusammen.
- **Verlust:** ein zertifiziertes Standardprodukt als Token-Aussteller mit Admin-Konsole,
  Account-Konsole, Clustering-Erfahrung und Brokering zu anderen Identity-Providern. Dazu das
  Argument „unverändertes Keycloak“ gegenüber Audit und Betrieb.

## 4) Die entscheidende Frage: Wie viele Clients?

**Nur die eigene Website und die eigene App.** Dann ist der Fall für Keycloak schwach. Es liefert
eine schmale Funktion, die eine Bibliothek abdeckt, zum Preis eines zweiten Systems, einer privaten
SPI und ungebundener Web-Tokens. Ausnahme: Keycloak ist bei der Versicherung ohnehin der zentrale
Identity-Provider, dem die Fachdienste vertrauen. Dann ist Keycloak der Integrationsvertrag der
Organisation, und der Orchestrator ist der Authenticator dahinter.

**Weitere Clients, auch Apps von Partnern.** Gemeint sind Anwendungen anderer Organisationen, die
sich mit den Konten dieser Versicherung anmelden, etwa ein Apotheken- oder Fitness-Partner. Es
bleibt eine Kasse, ein Kontobestand, ein Realm; ein Mandantenmodell ist nicht gemeint. Dann kippt
die Abwägung klar zugunsten von Keycloak. Die Kosten aus Abschnitt 2 sind fix, der Nutzen wächst
mit jedem Client:

| Bedarf bei mehreren Clients | Keycloak | Eigener Provider |
|---|---|---|
| Client-Verwaltung: Redirect-URIs, Secrets oder `private_key_jwt`, Rotation | Admin-Konsole und API | Protokoll ja, Verwaltung selbst |
| Consent („App X darf Ihre Versichertennummer lesen“), Widerruf durch den Nutzer | fertig, inkl. Account-Konsole | Seite und Speicher selbst |
| Scopes und Claims je Client, Audience, Laufzeiten je Client | Client Scopes, Mapper | selbst |
| SSO über alle Clients | Kern von Keycloak | Sitzungsverwaltung selbst |
| Logout an alle Clients (Back- und Front-Channel) | fertig | teils, Rest selbst |
| Step-up je Client über `acr_values` | Conditional-LoA-Subflows `orchestrator-loa-1/2` existieren | selbst |
| Client Policies (PKCE erzwingen, keine Platzhalter, FAPI) | fertig | selbst |
| Token Exchange, Dynamic Client Registration, SAML für Altpartner | fertig | teils oder nicht |
| Audit und Ereignisse je Client | fertig | selbst |

Der wichtigste Punkt ist das SSO: Nur die erste Anmeldung läuft durch eine Journey. Jeder weitere
Client bekommt sein Token aus der Keycloak-Sitzung, ohne den Orchestrator. Der Orchestrator steht
damit nur auf dem Pfad der Erstanmeldung und des Step-ups, nicht auf dem jedes Client-Logins. Beim
eigenen Provider läge all das im Orchestrator.

Die Rollen wären dann endgültig klar: **Keycloak ist der Identity-Provider der Organisation**
(Fassade für alle Relying Parties, Sitzung, Consent, Client-Governance). **Der Orchestrator ist das
Authentifizierungs-Backend dahinter** (Journeys, Verfahren, Niveaus, Konten). Dritt-Apps sehen ihn
nie.

## 5) Empfehlung

Die Frage nach den Clients bei Schritt 2 in Kapitel 14 ausdrücklich stellen, bevor weiter in
Extension und Theme investiert wird. Kommen weitere Clients, bleibt Keycloak, und die Investition
geht in die Kopplung und in die Härtung des Realms (Abschnitt 6). Bleibt es bei Website und App und
ist Keycloak nicht ohnehin der Identity-Provider der Organisation, lohnt ein Spike mit Spring
Authorization Server im Profil ohne Keycloak: `TokenService` durch den Provider ersetzen, den
Web-Kanal über ihn anmelden und messen, was an Extension, Theme und Anbindung wegfällt.

**Zwischenweg, nur als Migrationspfad.** Der Orchestrator wird OIDC-Provider, und Keycloak brokert
zu ihm (Standardmechanismus, keine private SPI). Das ist reversibel, bricht aber ADR-7 („Der
Browser spricht nie mit dem Orchestrator“) und macht aus zwei Hops drei. Als Zielbild nicht
geeignet.

## 6) Was mit Partner-Clients konkret folgt

Gilt, wenn Partner-Apps kommen. Die Punkte sind heute nicht als Issues erfasst.

1. **Partner-Apps gehen ausschließlich über OIDC**, auch native Apps über den System-Browser. Die
   Kanal-API mit DPoP und `tool_api` bleibt First-Party-Vertrag
   ([ADR-50](../adr/ADR-050-api-versionierung-umschlag-und-tool.md)). Ein Partner bekommt keine
   Journey-API und keine eingebettete Anmeldung in seiner eigenen Oberfläche. Das als Regel
   festschreiben, sonst entsteht Druck, die Kanal-API zu öffnen.
2. **Das identitätsarme AccessToken** (Migration `V8__access_token_identity_only`) passt:
   Partner-Clients bekommen `sub`, `acr` und `amr`, mehr nur über Scope und Consent. Weil der
   Partner eine fremde Organisation ist, muss der Nutzer jeder Weitergabe zustimmen; Consent ist
   für Partner-Clients Pflicht, nicht Option. Das `sub` ist heute für alle Clients dasselbe
   (`f:<UUID>:<accountId>`). Ob Partner eine je Client verschiedene Kennung bekommen sollen
   (Pairwise Subject, damit Partner Nutzer nicht untereinander abgleichen können), ist zu
   entscheiden; Keycloak bringt den Mapper dafür mit.
3. **Die zwei Kopplungen werden dringender.** Engpass 1 (Federation liest bei fast jeder
   Token-Erneuerung) multipliziert sich mit Clients und Sitzungen; den Cache an die Token-Laufzeit
   anpassen wird zur Vorbedingung. Engpass 2 (Rückruf in offener Transaktion) ebenso.
4. **SA-21** (Platzhalter in den Redirect-URIs, `DPoP-demo-164n.21`) muss vor dem ersten
   Dritt-Client weg. Client Policies (PKCE erzwingen, vertrauliche Clients nur mit
   `private_key_jwt`) gehören als Migration ins Realm
   ([ADR-25](../adr/ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md)).
5. **Consent-Speicher:** Keycloak legt Zustimmungen im föderierten Speicher ab. `AccountRemoval`
   räumt ihn bei der Kontolöschung über `preRemove` mit ab; das ist schon abgedeckt.
6. **Die DPoP-Lücke im Web schließt sich innerhalb von Keycloak**, nicht durch Ersatz: Keycloak
   bringt DPoP für OIDC-Clients mit (Feature-Schalter; Reifegrad in der eingesetzten Version
   prüfen).
7. **Nicht weiter investieren** in alles, was Funktionen des Identity-Providers im Orchestrator
   nachbildet. `TokenService` bleibt Testersatz.

**Offen bleibt:**

- **Vertrauensstufen der Clients.** Ein Partner-Client ist etwas anderes als die eigene Website:
  welche Scopes, welche höchste `acr`, welche Laufzeiten. Das ist Realm-Policy, nicht
  Orchestrator-Logik. Heute gibt es einen Browser-Client und die Clients des Orchestrators; ein
  Modell mit Client-Klassen fehlt.
- **`loa3` im Web-Realm** (`DPoP-demo-wzcm`) wird mit Partner-Clients zur echten Frage: Darf ein
  Partner `loa3` anfordern, und sieht er im Token, welche Verfahren (`amr`) der Nutzer benutzt
  hat?
- **Abmeldung und Widerruf.** Meldet sich der Nutzer auf der Website ab, enden heute beide Kanäle
  (ADR-43). Mit Partner-Clients muss die Abmeldung auch den Partner erreichen (Back-Channel-Logout)
  und der Nutzer eine Zustimmung einzeln widerrufen können, ohne die Sitzung zu verlieren.

## 7) Wie Partner-Apps die Anmeldung erreichen

Gilt, wenn Partner-Clients kommen. Vier Wege, alle über Keycloak als Identity-Provider; der
Orchestrator bleibt dahinter. Welcher passt, hängt davon ab, wo der Nutzer gerade ist und ob ein
Browser im Spiel sein darf.

### Weg 1: Partner-Website, Standard-OIDC

Authorization Code mit PKCE gegen Keycloak, Consent Pflicht. Keycloak zeigt die Anmeldung, der
`OrchestratorAuthenticator` führt dieselbe Journey wie auf der eigenen Website
([`WEB_SELECT_METHOD`](../journeys/web-select-method.md)). Ist der Nutzer im selben Browser schon
angemeldet: SSO, nur die Consent-Seite. Niveau je Partner über `acr_values` und die bestehenden
Conditional-LoA-Subflows. Nötig ist nur Realm-Konfiguration (Client, Consent, Scopes), keine neue
Mechanik.

### Weg 2: Partner-App über den System-Browser

Der Standardweg für native Dritt-Apps (RFC 8252): `ASWebAuthenticationSession` oder Custom Tabs
öffnen Keycloak, Ablauf wie Weg 1, Rückkehr per Redirect-URI. Eingebettete WebViews sind
ausgeschlossen. Der Haken: kein SSO mit der Versicherungs-App, denn deren Anmeldung lebt auf dem
DPoP-Kanal, nicht als Browser-Cookie. Der Nutzer meldet sich im Browser mit seinen Verfahren an.
Weg 3 nimmt ihm das ab.

### Weg 3: Die Versicherungs-App bestätigt auf demselben Gerät

**Ablauf.** Für die Partner-App ist das Weg 2; nur die Login-Seite von Keycloak hat einen Button
mehr: „Mit der Versicherungs-App bestätigen“. Er ist ein App-Link, der das Betriebssystem die
Versicherungs-App öffnen lässt. Dort bestätigt der Nutzer mit Biometrie. Ein Rücksprung-Link führt
in den Browser zurück, Keycloak schließt die Anmeldung ab und gibt der Partner-App den Code.

**Basis ist `device`, nicht `qr`.** Das Verfahren [`qr`](../verfahren/qr.md) ist für zwei Geräte
gebaut: QR-Code als optischer Transport, Pairing-Code als Tipp-Ersatz, sechsstelliger
Bestätigungscode in Gegenrichtung als Schutz vor untergeschobenen Anfragen. Alle drei ersetzen
einen gemeinsamen Kanal, den es auf einem Gerät gibt. Die Grundlage für Weg 3 ist deshalb das
Verfahren [`device`](../verfahren/device.md), auf das Web ausgedehnt: Die App bestätigt nicht mit
einem abgetippten Code, sondern signiert eine Challenge der Browser-Anmeldung mit dem
Geräteschlüssel, den `enroll-device` beim Konto eingetragen hat. Das ist dieselbe Technik wie
`auth-device` in der App, nur kommt die Challenge aus einer fremden Sitzung.

```
Browser (Keycloak)            Versicherungs-App                Orchestrator
  │ Tool auth-device-web ───────────────────────────────────────▶ │ Challenge, an die
  │ App-Link …/approve?c=<challenge>                               │ Tool-Sitzung gebunden
  │────── OS öffnet App ──────────▶ │                               │
  │                                 │ Biometrie, signiert Challenge │
  │                                 │── approve-device (DPoP) ─────▶│ Signatur gegen den
  │                                 │◀─ Rücksprung-Token ───────────│ Schlüssel des Kontos
  │◀───── Rücksprung-Link ──────────│                               │
  │ Cookie → Tool-Sitzung, PATCH {token} ──────────────────────────▶│ Completed.Authenticated
```

| | `qr` als Basis | `device` als Basis |
|---|---|---|
| Was die App beweist | jemand hat in einer angemeldeten App bestätigt | Besitz des beim Konto eingetragenen Schlüssels, per Signatur über eine Challenge des Servers |
| Bindung an die Browser-Anmeldung | Pairing-Code, menschenlesbar, 5 Minuten | Challenge, an die Tool-Sitzung gebunden |
| Schutz vor untergeschobenen Anfragen | Bestätigungscode, vom Nutzer getippt | Rücksprung-Token, nur mit dem Auth-Session-Cookie einlösbar |
| Faktoren | `possession, knowledge` über den Umweg, dass die App frisch `loa2` haben muss | `possession, inherence` direkt, wie `auth-device` |
| Journey in der App | `CONFIRM_PEER_LOGIN` mit Step-up | nur die Biometrie-Abfrage; der Schlüssel ist der Nachweis |

**Warum das Sicherheitsargument hält.** Der Rücksprung-Token liefert das Ergebnis nur an einen
Browser, der das Auth-Session-Cookie der ursprünglichen Anmeldung hat; Keycloak findet darüber die
Tool-Sitzung, wie heute der `status`-Endpunkt ([ADR-45](../adr/ADR-045-qr-warteseite-fragt-im-hintergrund.md)).
Schiebt ein Angreifer dem Opfer den App-Link seiner eigenen Anmeldung unter, bestätigt die App des
Opfers zwar, aber der Rücksprung öffnet den Browser auf dem Gerät des Opfers, wo das Cookie des
Angreifers fehlt. Der Token ist dann verbraucht. Anders als beim getippten Code gibt es nichts, was
das Opfer auf Nachfrage weitergeben könnte.

**QR wird ein Transport.** Für zwei Geräte liefert der QR-Code dieselbe Challenge. Dort bleibt der
Bestätigungscode in Gegenrichtung nötig, weil kein Cookie den Rückweg bindet; auf zwei Geräten ist
er keine Krücke, sondern die einzige Bindung. Ergebnis: ein Verfahren, zwei Transporte, Codes nur
dort, wo es keine stärkere Bindung gibt. `approve-qr` geht darin auf.

**Was bleibt: der Rücksprung in den Browser.** OIDC kennt keine Übergabe zwischen zwei Apps auf
einem Gerät. Jede App, die als Authenticator dient (BankID, itsme, AusweisApp), löst das mit Links
und Plattform-Sonderfällen: Auf iOS hat `ASWebAuthenticationSession` je nach
`prefersEphemeralWebBrowserSession` einen eigenen Cookie-Speicher, und ein Universal Link öffnet
Safari, nicht das Sheet; Android Custom Tabs teilen Cookies mit Chrome und sind unkritisch. Das ist
kein Mangel der Basis, aber ein Leitfaden für Partner (nicht-ephemere Session, Universal Link als
`redirect_uri`, Sheet beim Callback schließen). Kommt der Rücksprung nicht an, pollt die Warteseite
wie heute und bietet den Code-Pfad an; der Cross-Device-Weg springt ein.

**Regeln.** Nur verifizierte Links (Universal Links, App Links mit `assetlinks.json`); ein eigenes
URL-Schema kann jede fremde App registrieren und die Challenge abfangen. Token einmalig, kurz
gültig, begrenzte Versuche, wie heute bei `QrLoginBrowserSide`.

**Was zu bauen wäre.** Im Theme der Button mit App-Link, nur auf mobilen Browsern. In der App ein
Tool `approve-device`, das die Challenge aus dem Link signiert und den Rücksprung-Link anbietet. In
der Extension ein Endpunkt neben `orchestrator-qr/status`, der den Rücksprung-Token über das Cookie
der Tool-Sitzung zuordnet und den Flow fortsetzt. Im Orchestrator ein Tool `auth-device-web`, das
Challenge und Token hält und die Signatur gegen `enroll-device` prüft. Realm: Partner als Client.
K-8 in [offene-befunde.md](../offene-befunde.md) ist mitzuprüfen, denn der Endpunkt ist ein weiterer
`GET`, der zur Tool-Eingabe wird.

### Alternative ohne Übergabe: Passkey, von der App eingerichtet

Ein Passkey mit der Keycloak-Domain als RP-ID, den die App beim Einrichten anlegt. Der Browser auf
demselben Gerät, auch der System-Browser einer Partner-App, nutzt ihn direkt mit Biometrie: kein
App-Link, kein Rücksprung, reines WebAuthn. Keycloak-seitig ist das der Passkey-Spike aus
[ADR-58](../adr/ADR-058-keycloak-fuehrt-keine-eigenen-anmeldeschritte.md) (eigenes Tool,
webauthn4j). Preis: Es bestätigt das Betriebssystem, nicht die App; keine Binding-Message, kein
Step-up-Dialog, und synchronisierte Passkeys sind schwächer an das Gerät gebunden als ein Schlüssel
in der Secure Enclave. Für einen Partner-Login auf `loa2` reicht das; wo die App vor der
Bestätigung etwas anzeigen soll, nicht. Beides schließt sich nicht aus: `device` im Web ist die
Grundlage, der Passkey der Komfortweg ohne Übergabe.

### Weg 4: Ohne Browser, CIBA

Für Partner-Apps ohne Browser-Umweg, Callcenter oder Terminals. Keycloak bringt CIBA (Client
Initiated Backchannel Authentication) nativ mit: Der Partner ruft den Backchannel-Endpunkt mit
`login_hint` und `binding_message` („Apotheke Müller, 12,40 €“). Keycloak ruft über seinen
Authentication Channel Provider (Standard-SPI, HTTP, von Keycloak signiert) den Orchestrator, der
die Anfrage in der Versicherungs-App zeigt (Push bei einer nativen App, in der Demo Polling). Der
Nutzer bestätigt, der Orchestrator meldet an Keycloaks Callback, Keycloak gibt das Token an den
Partner (Poll oder Ping). Das Vertrauensmodell ist das heutige (Keycloak → Orchestrator signiert,
[ADR-7](../adr/ADR-007-web-kanal-ohne-mtls-signierte-request-assertion-statt.md)). Offen: Woher
kennt der Partner den `login_hint`? Üblich ist die E-Mail-Adresse, die der Nutzer beim Partner
eingibt; die Federation löst sie auf. Für Partner-Websites ist CIBA nicht gedacht, dort gilt Weg 1.

### Reihenfolge

1. Weg 1 und 2: reine Konfiguration, deckt den Großteil ab. Als Spike gegen das Realm zeigt das
   sofort, ob Consent, Scopes und das identitätsarme Token für einen Fremd-Client reichen.
2. Weg 3 auf Basis `device`, dazu der Passkey-Spike.
3. CIBA, wenn Partner ohne Browser kommen.

**Verworfen:** Weg 3 auf Basis `qr` (Codes als Krücke für einen Kanal, den es auf einem Gerät
gibt); Token Exchange aus der Versicherungs-App heraus, bei dem die App Tokens mit Partner-Audience
weiterreicht (Consent läge in der Journey statt in Keycloak, die App würde Token-Verteiler für
Dritte); die Kanal-API für Partner zu öffnen (Abschnitt 6, Punkt 1).
