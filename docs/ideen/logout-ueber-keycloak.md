# Idee: Abmeldung nur über Keycloak, keine `LOGOUT`-Journey mehr

**Worum es geht.** Die Abmeldung läuft heute in den beiden Kanälen auf verschiedenen Wegen. In der
App ist sie eine eigene Journey (`AuthIntent.LOGOUT`) mit einer Rückfrage. Im Web ist sie Keycloaks
eigener Logout, und der Orchestrator erfährt davon über einen selbst gebauten Event-Listener. Die
Idee: Beide Kanäle melden sich über Keycloak ab. Der Orchestrator erfährt das Ende nur noch über den
standardisierten [OIDC Back-Channel Logout](https://openid.net/specs/openid-connect-backchannel-1_0.html).
Die Abmeldung steht dann nur noch im Audit-Log (SignInLog), eine Journey gibt es dafür nicht mehr.

**Warum das wichtig ist.** Ein Weg statt zwei, und dieser Weg ist Keycloaks Standard statt einer
eigenen Meldekette mit eigener Vertrauensgrenze (ADR-39). Eine Abmeldung an anderer Stelle (Admin-Konsole,
Account-Konsole, ein weiterer Client derselben Sitzung) erreicht den Orchestrator auf demselben Weg.

**Stand: umgesetzt** (2026-10-12, Epic `DPoP-demo-j2xd`), entschieden in
[ADR-60](../adr/ADR-060-abmeldung-ueber-keycloak-back-channel-logout.md). Die ADR gilt; dieses
Dokument bleibt als Abwägung mit dem Spike (Abschnitt 7) stehen. Eine Abweichung vom Vorschlag in
Abschnitt 3: Die Browsertests in `frontend/e2e` ziehen nicht nach `frontend/e2e-keycloak`. Sie
laufen mit dem Test-Ersatz aus `src/testFixtures` (`bootRunE2e`), weil das CI kein Keycloak hat.

---

## 1) Heute

**App.** `AppChannelApp` ruft `POST /channels/{id}/logouts`. `ChannelService.startLogout` startet die
Journey `LOGOUT` (bis ADR-60 im Journey-Katalog). Sie stellt eine Rückfrage. Nach Zustimmung folgt
`Transition.Logout`, und `ChannelSessionLifecycle.end` beendet den Kanal, schreibt die Abmeldung ins
SignInLog und veröffentlicht `KeycloakSessionEnded`. Nach dem Commit beendet
`KeycloakSessionLogoutListener` die Keycloak-Sitzung über die Admin-API.

**Web.** `WebChannelView.logout` verwirft die Tokens und leitet auf Keycloaks
`end_session_endpoint`. Keycloak erzeugt das Event `LOGOUT`. `SignInLogEventListener` in der
Extension meldet es nach dem Commit an `KeycloakSignOutController`. Von dort ruft der Orchestrator
`KeycloakChannelService.signedOutAtKeycloak`, das laufende Journeys abbricht, die Kanäle der Sitzung
beendet und die Abmeldung schreibt ([ADR-43](../adr/ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md)
Abschnitt 3).

## 2) Der Vorschlag

- **Web:** Der Web-Client in Keycloak bekommt eine `backchannel_logout_url` auf den Orchestrator,
  mit `backchannel_logout_session_required`, damit das Token die `sid` trägt. Der Logout im Browser
  bleibt, wie er ist.
- **App:** Die App hat keinen eigenen Keycloak-Client, ihre Sitzung gehört dem Client
  `orchestrator-app-token` ([ADR-9](../adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md)).
  „Über Keycloak“ heißt hier: Die App ruft einen schlichten Abmelde-Endpunkt des Orchestrators. Der
  Orchestrator beendet die Sitzung über die Admin-API (`KeycloakAdminClient.logoutSession`, gibt es
  schon). Keycloak benachrichtigt daraufhin über den Backchannel. Derselbe Client bekommt dafür ebenfalls
  eine `backchannel_logout_url`.
- **Ein Eingang:** Ein neuer Endpunkt nimmt das `logout_token` an, prüft es (Signatur gegen das
  JWKS des Realms, `iss`, `aud`, `events`, `sid`, `jti`) und ruft `signedOutAtKeycloak` mit der `sid`
  auf.
- **Rückfrage:** „Wirklich abmelden?“ wird ein Dialog im UI, in beiden Kanälen gleich.

## 3) Voraussetzung: Standardprofil streichen

Ohne Keycloak gibt es keinen Backchannel. Bliebe das Standardprofil, gäbe es weiter zwei Wege zum
Ende eines Kanals. Das Profil zu streichen, lohnt sich auch für sich:

- Ohne Keycloak läuft nur die halbe Anwendung, der Web-Kanal fehlt ganz
  ([13-ausfuehren.md](../13-ausfuehren.md)).
- Die Weichen `@Profile("keycloak")` / `@Profile("!keycloak")` entfallen, ebenso die Prüfung auf
  `MockTokenProvider` in `ProductionModeCheck` und die Sätze „ohne Keycloak gilt …“ in der Doku.

Was bleiben sollte: ein Ersatz für Keycloak in den Tests. Die rund 40 Integrationstests laufen heute
ohne Keycloak, und das CI baut nur mit `./gradlew build`. `MockTokenProvider` zieht nach `src/test`.
Für die Abmeldung ruft der Test-Ersatz `signedOutAtKeycloak` direkt, so wie es der Backchannel täte.
Die Browsertests in `frontend/e2e` ziehen nach `frontend/e2e-keycloak`. Der Preis: Lokal braucht
jeder Start Podman (`podman compose up keycloak`, `bootRunKc`).

## 4) Offene Punkte

Nach Gewicht geordnet.

1. ~~**Bekommt die App-Sitzung überhaupt einen Backchannel?**~~ Ja. Der eigene Grant legt eine
   Client-Sitzung für `orchestrator-app-token` an (Spike, Abschnitt 7).
2. ~~**Löst die Admin-API einen Backchannel aus?**~~ Ja, sowohl `DELETE …/sessions/{id}` als auch
   `POST …/users/{id}/logout` (Spike, Abschnitt 7).
3. **Kein Aufruf an Keycloak in der eigenen Transaktion.** Keycloak ruft den Backchannel noch
   während des Admin-Aufrufs zurück, synchron (im Spike dauerte `DELETE` so lange wie der
   Endpunkt). Hält der Orchestrator dabei die Zeile des Kanals, wartet der
   Rückruf auf die Sperre und der Admin-Aufruf auf den Rückruf. Der Aufruf läuft deshalb wie heute
   nach dem Commit (`KeycloakSessionLogoutListener`).
4. **Zustellung ohne Wiederholung.** Keycloak ruft den Backchannel einmal. Ist der Endpunkt nicht
   erreichbar, gelingt die Abmeldung trotzdem, Keycloak protokolliert nur einen Fehler (Spike). Geht er verloren, lebt
   der App-Kanal bis zur nächsten Erneuerung oder bis zu seiner Frist (ADR-43 Abschnitt 5). Heute
   endet er in derselben Transaktion. Deshalb sollte der Orchestrator einen Kanal, dessen Abmeldung
   er selbst anstößt, auch selbst beenden. Der Backchannel ist für ihn dann eine Bestätigung, nicht
   der Auslöser. Für das Web ändert sich nichts, auch der heutige Listener liefert ohne Garantie.
5. **Doppelte Meldung.** Beendet der Orchestrator selbst (App-Abmeldung, `DELETE_ACCOUNT`,
   `CONFIRM_PEER_LOGIN`, alle über `Transition.Logout`), kommt die Meldung danach noch einmal über
   den Backchannel. `signedOutAtKeycloak` deckt das heute schon ab: Für ein gelöschtes Konto bricht
   es ab, und eine App-Sitzung, deren `AppTokenSession` die `sid` noch nennt, gilt als schon
   geschrieben. `ChannelSessionLifecycle.end` leert die Tokens, behält aber die Zeile. Ein Test muss
   das festhalten, denn ohne die Zeile entstünde ein zweiter Eintrag, fälschlich als `WEB`.
6. **Vom Token zum Konto.** `sub` im `logout_token` ist die Keycloak-Kennung
   `f:<componentId>:<externalId>`. Diese Zerlegung macht heute die Extension (`StorageId`). Besser
   löst der Orchestrator nur über `sid` auf: Kanäle (`durableKeycloakSessionId`), `AppTokenSession`
   und die Nachweise je Sitzung ([ADR-59](../adr/ADR-059-nachweise-je-keycloak-sitzung-im-orchestrator.md))
   kennen sie. Für eine Web-Sitzung ohne laufenden Kanal ist zu prüfen, ob die Nachweise je Sitzung
   das Konto oder die Einladung ([ADR-48](../adr/ADR-048-vorgangszugang-mit-einmalkennwort.md))
   lange genug kennen.
7. **Wer abgemeldet hat.** Das `logout_token` unterscheidet nicht zwischen Nutzer, Admin und
   Orchestrator. Heute steht im SignInLog immer `endedBy = HOLDER`, es geht also nichts verloren.
   Soll das Audit-Log das später unterscheiden, muss der Orchestrator seine eigenen Abmeldungen
   vorher notieren.

## 5) Bewusst in Kauf genommen

- **Kein Eintrag im Journey-Trace.** Die Journey war eine einzige Ja/Nein-Frage, dort gibt es wenig
  zu sehen. Für „wann hat sich wer abgemeldet“ ist das SignInLog die richtige Quelle. Der Support
  sieht ein Abmelden mitten in einer Journey dann nur noch neben dem Trace. Abhilfe bei Bedarf: Die
  Trace-Ansicht zeigt den Eintrag aus dem SignInLog mit an.
- **Rückfrage nur im UI.** Eine Rückfrage vor der Abmeldung ist reine Bedienung, kein Zustand des
  Servers. Das Web hat heute gar keine.
- **Ablauf ohne Backchannel.** Läuft die Sitzung ab (SSO idle/max), ruft Keycloak keinen
  Backchannel auf. Das ist heute auch so und durch das Sitzungsfenster geregelt (ADR-43
  Abschnitt 2).

## 6) Was wegfällt, was bleibt

**Fällt weg:**

- Journey und Endpunkt: `AuthIntent.LOGOUT`, `LogoutStrategy`, `ChannelService.startLogout`,
  `POST /channels/{id}/logouts`, `startLogout` im Frontend und `journeys/logout.md`
  samt Tests und Diagramm.
- Die alte Meldekette: `SignInLogEventListener`, `OrchestratorClient.reportSignOut` und
  `KeycloakSignOutController` samt der Assertion nach ADR-39.
- Das Standardprofil (Abschnitt 3).

**Bleibt:**

- `Transition.Logout` für `DELETE_ACCOUNT` und `CONFIRM_PEER_LOGIN`.
- Die harte Abmeldung (`ChannelService.logout`) als Abmelde-Endpunkt der App.
- `KeycloakSessionLogoutListener` als Auslöser über die Admin-API.
- `signedOutAtKeycloak` als einziger Ort, an dem eine von Keycloak gemeldete Abmeldung ankommt.

## 7) Spike gegen das Realm (2026-10-12)

Aufbau: Compose-Stack (Keycloak 26.6.4), an beiden Clients per Admin-API eine
`backchannel_logout_url` auf einen Endpunkt, der nur protokolliert, mit
`backchannel_logout_session_required`. Der Orchestrator blieb unverändert. Danach wurde die
Einstellung wieder entfernt.

| Fall | Auslöser | Backchannel |
|---|---|---|
| Web, Abmeldung im Browser | `end_session_endpoint` (Event `LOGOUT`) | ja, an `identity-demo-web` |
| Web, Einladung beendet | Orchestrator `POST …/users/{id}/logout` (kein Event `LOGOUT`) | ja, an `identity-demo-web` |
| App, Journey `LOGOUT` | Orchestrator `DELETE …/sessions/{id}` | ja, an `orchestrator-app-token` |
| App, Sitzung direkt beendet | Admin-API `DELETE …/sessions/{id}` | ja, an `orchestrator-app-token` |

Befunde:

- Das `logout_token` ist `typ: logout+jwt`, RS256 mit dem Realm-Schlüssel, mit `iss`, `aud` (der
  Client), `sub`, `sid`, `jti`, `iat`, `exp` (zwei Minuten) und `events`. Die `sid` ist die
  Keycloak-Sitzungskennung, also dieselbe wie in `AppTokenSession.keycloakSessionId` und
  `durableKeycloakSessionId`. Punkt 6 lässt sich damit wie vorgeschlagen über `sid` lösen.
- `sub` hat die Form `f:<componentId>:<externalId>`, für Konten mit der Kontonummer, für
  Einladungen mit einem Hash.
- Der Aufruf ist synchron: Mit einem Endpunkt, der drei Sekunden wartet, dauerte `DELETE
  …/sessions/{id}` drei Sekunden (Punkt 3).
- Ist der Endpunkt nicht erreichbar, antwortet `DELETE` trotzdem mit `204`. Keycloak protokolliert
  den Fehler (`ResourceAdminManager.logoutClientSessionWithBackchannelLogoutUrl`) und wiederholt
  nicht (Punkt 4).
- Nebenbefund zur Lücke von heute: Wer eine App-Sitzung in der Admin-Konsole beendet, erzeugt kein
  Event `LOGOUT`. Der Orchestrator erfährt davon heute nichts, der App-Kanal lebt bis zur nächsten
  Erneuerung weiter. Mit dem Backchannel wäre das geschlossen.

Damit steht dem Vorschlag von Keycloaks Seite nichts entgegen. Offen bleiben die Punkte 3 bis 7,
die alle auf der Seite des Orchestrators liegen.
