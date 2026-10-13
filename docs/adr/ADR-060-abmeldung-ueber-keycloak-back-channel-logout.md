# ADR-60: Die Abmeldung meldet Keycloak per Back-Channel Logout, eine Journey dafür gibt es nicht

**Status:** entschieden und umgesetzt 2026-10-12 (Epic `DPoP-demo-j2xd`, Spike `DPoP-demo-vw1v`).
Hervorgegangen aus der Idee [Abmeldung nur über Keycloak](../ideen/logout-ueber-keycloak.md).
Ergänzt [ADR-43](ADR-043-kanal-lebt-nicht-laenger-als-die-keycloak-sitzung.md) Abschnitt 3 und
ersetzt die Meldung der Abmeldung aus [ADR-39](ADR-039-was-eine-kontoloeschung-ueberlebt.md).

**Worum es geht.** Die Abmeldung lief in den beiden [Kanälen](../glossar/glossar.md) auf zwei
Wegen. In der App war sie eine eigene Journey (`LOGOUT`) mit einer Rückfrage auf dem Server. Auf der
Website war sie Keycloaks eigener Logout. Davon erfuhr der Orchestrator über einen Event-Listener
der Extension (`SignInLogEventListener`), der jedes Event `LOGOUT` mit einer Peer-Auth-Assertion an
`POST .../kc/{accounts|invitations}/{id}/sign-outs` meldete. Eine Abmeldung über die Admin-API von
Keycloak, etwa in der Admin-Konsole, erzeugt kein solches Event. Davon erfuhr der Orchestrator nichts,
und ein App-Kanal blieb bis zur nächsten Token-Erneuerung angemeldet.

Daneben lief der Orchestrator auch ohne Keycloak, im Standardprofil mit einem simulierten
Token-Dienst. Dort gab es keinen Web-Kanal, und jede Regel zur Sitzung brauchte einen zweiten Weg.

**Entscheidung.**

1. **Keycloak meldet jedes Sitzungsende per OIDC Back-Channel Logout.** Die Clients
   `identity-demo-web` und `orchestrator-app-token` nennen
   `POST /orchestrator/api/v1/kc/backchannel-logout` als `backchannel.logout.url`, mit
   `backchannel.logout.session.required` (Keycloak-Migration V9). Keycloak ruft die Adresse für jede
   beendete Sitzung, in der einer der beiden Clients angemeldet war, gleich wer abgemeldet hat. Das
   signierte `logout_token` ist der einzige Ausweis des Aufrufs.
2. **Der Orchestrator prüft das Token vollständig** (`LogoutTokenValidator`, Spezifikation Abschnitt
   2.6): Signatur mit einem Signaturschlüssel des Realms (`.../protocol/openid-connect/certs`), `typ`
   `logout+jwt`, `iss` gleich der öffentlichen Realm-Adresse, `aud` einer der beiden Clients, `iat`
   und `exp` im Fenster, das Event `http://schemas.openid.net/event/backchannel-logout`, eine `sid`,
   kein `nonce`. Die `jti` wird einmal verbraucht (Präfix `bcl:`). Ein abgelehntes Token beantwortet er
   mit `400` und `invalid_request`, ohne Grund; der Grund steht im Log, als Regel ohne Werte aus dem
   Token. Ein Signaturschlüssel, der einen Algorithmus nennt, gilt nur für diesen. Das Token wird
   nur im Body angenommen, nie in der Adresse.
3. **Die `sid` allein bestimmt, was endet.** `KeycloakChannelService.signedOutAtKeycloak(sid)`
   sucht die Web-Kanäle mit dieser `durableKeycloakSessionId`, die App-Anmeldungen mit dieser
   `AppTokenSession.keycloakSessionId` und die Nachweise der Sitzung (ADR-59). Jeder laufende Kanal
   dieser Sitzung endet, gleich für wen er arbeitet. Für die Zeile im Anmeldeprotokoll ohne
   laufenden Kanal ergibt sich daraus das Konto oder die Einladung. `sub` liest der Orchestrator nicht: Es ist die interne Kennung von
   Keycloak (`f:<componentId>:<externalId>`). Eine Sitzung, die der Orchestrator nicht kennt, ist
   nichts zu protokollieren.
4. **Die Abmeldung ist keine Journey.** Der Intent `LOGOUT`, sein Zustand und
   `POST .../channels/{id}/logouts` entfallen. Die App meldet sich mit `DELETE .../channels/{id}` ab,
   nach einer Rückfrage in ihrer eigenen Oberfläche. Der Orchestrator beendet den Kanal selbst, in
   derselben Transaktion, und lässt die Keycloak-Sitzung nach dem Commit beenden
   (`KeycloakSessionLogoutListener`). Der Back-Channel bestätigt das danach nur noch. Umgekehrt
   ruft ein Ende, das Keycloak selbst gemeldet hat, Keycloak nicht noch einmal an
   (`ChannelSessionLifecycle.end(..., endedAtKeycloak = true)`): Keycloak wartet in diesem Moment
   noch auf die Antwort des Back-Channels.
5. **Die Abmeldung steht genau einmal im Anmeldeprotokoll.** Ein Kanal, der als `LOGGED_OUT` endet,
   schreibt sie selbst. Eine Web-Sitzung ohne laufenden Kanal schreibt `signedOutAtKeycloak`, außer
   ein Kanal dieser Sitzung endete schon als `LOGGED_OUT` oder eine App-Anmeldung hält sie. Die
   `AppTokenSession` behält dafür ihre `keycloakSessionId`, wenn die Abmeldung ihre Tokens leert.
6. **Der Orchestrator läuft immer mit Keycloak.** `keycloak` ist das Default-Profil
   (`spring.profiles.default`). Der simulierte Token-Dienst (`MockTokenProvider`) und
   `AlwaysReadyState` liegen in `src/testFixtures`. Dieses Source-Set kommt nie ins ausgelieferte Jar,
   nur in die Backend-Tests und in den Server der Browsertests (`./gradlew bootRunE2e`).

**Warum.**

- **Ein Weg statt zwei, und ein Standard statt einer eigenen Meldekette.** Der Back-Channel braucht
  keinen Code in Keycloak, kein Event, keine Peer-Auth-Assertion. Was ADR-39 für die Abmeldung
  eingeführt hatte, entfällt.
- **Auch die Admin-API meldet.** Der Spike hat gezeigt, dass Keycloak den Back-Channel auch für
  `DELETE .../sessions/{id}` und `POST .../users/{id}/logout` aufruft. Die Lücke, dass eine in der
  Admin-Konsole beendete App-Sitzung den Kanal weiterleben ließ, ist damit geschlossen.
- **Die Rückfrage ist Bedienung.** Eine einzige Ja/Nein-Frage braucht keinen Zustand auf dem Server.
  Die Website hatte nie eine.
- **Ohne Keycloak lief nur die halbe Anwendung.** Wer die Demo ohne Keycloak startete, sah keinen
  Web-Kanal. Und jede Sitzungsregel brauchte einen Sonderfall für den simulierten Token-Dienst.

**Erwogene Alternativen.**

- **Den Back-Channel zum einzigen Auslöser machen, auch für die App.** Der Orchestrator hätte bei
  einer App-Abmeldung nur Keycloak gefragt und auf den Rückruf gewartet. Verworfen: Keycloak ruft den
  Back-Channel einmal und wiederholt nicht. Ein verlorener Aufruf hätte den Kanal bis zur nächsten
  Erneuerung leben lassen. So endet der Kanal sicher, und der Rückruf ist idempotent.
- **`sub` zerlegen statt über `sid` auflösen.** Verworfen: Der Orchestrator müsste die Kennungen der
  beiden Federations kennen, also ein Detail von Keycloak.
- **Die Browsertests auf Keycloak umstellen** (`frontend/e2e` nach `frontend/e2e-keycloak`).
  Verworfen: Das CI hat kein Keycloak, die Tests liefen dann nicht mehr bei jedem Push. Der
  Test-Ersatz in `src/testFixtures` hält sie dort, ohne dass das ausgelieferte Jar einen Weg ohne
  Keycloak kennt.

**Preis.**

- **Kein Start ohne Keycloak.** Lokal braucht jeder Start `podman compose up keycloak`.
- **Der Abmelde-Endpunkt ist offen.** Er hat keinen DPoP-Beweis und keine Peer-Auth-Assertion. Er
  vertraut allein der Signatur des Realms. Seine Antworten sagen nicht, warum ein Token abgelehnt
  wurde, und er liegt unter `/kc/`, außerhalb des veröffentlichten Vertrags (ADR-50).
- **Zustellung ohne Garantie.** Ist der Orchestrator gerade nicht erreichbar oder noch nicht bereit
  (`ReadinessGateFilter`), geht die Meldung verloren. Dann gilt, was ADR-43 Abschnitt 5 für
  verlorene Meldungen sagt. Eine Abmeldung, die die App selbst anstößt, ist nicht betroffen, sie
  beendet ihren Kanal selbst. Beendet dagegen die Admin-Konsole eine App-Sitzung, lebt der Kanal
  bei verlorener Meldung bis zur nächsten Erneuerung weiter.
- **Kein Eintrag im Journey-Trace.** Die Abmeldung steht im Anmeldeprotokoll, im Journey-Trace nur
  als Ereignis `LOGGED_OUT` des Kanals.
- **Wer abgemeldet hat, sagt das `logout_token` nicht.** Das Anmeldeprotokoll schreibt weiter
  `endedBy = HOLDER`.
