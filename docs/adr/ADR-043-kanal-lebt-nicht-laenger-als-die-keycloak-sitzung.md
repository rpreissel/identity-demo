# ADR-43: Ein angemeldeter Kanal hat genau eine Keycloak-Sitzung und lebt nicht länger als sie

**Status:** umgesetzt 2026-09.

**Entscheidung.** `AUTHENTICATED` erzeugt eine Keycloak-Sitzung. Eine `ChannelSession` überlebt nie
die Keycloak-Sitzung, zu der sie gehört. Wie lange die Sitzung dauert, bestimmt Keycloak (SSO idle,
SSO max); der Orchestrator übernimmt diese Frist. Verlängern kann er sie nur so, wie jeder Client
es kann: mit einem Refresh, solange der Nutzer aktiv ist, nie an Keycloak vorbei. Die festen
Lebensdauern der Kanäle (App 24 Stunden, Web 30 Minuten) gelten nur bis zur Anmeldung.

Zu einer `ChannelSession` gehört genau eine Keycloak-Sitzung. Eine zweite wird nie geöffnet, auch
nicht, wenn die erste abgelaufen ist: Dann endet der Kanal. Ein Step-up bleibt möglich; er hebt
`acr` und `amr` in derselben Sitzung an.

Im Standardprofil ohne Keycloak spielt der simulierte Token-Dienst (`TokenService`) Keycloaks
Rolle: Seine Sitzung beginnt ebenso mit `AUTHENTICATED`, ihr Fenster ist 30 Minuten Leerlauf.

**Warum.** Bisher bekam der App-Kanal seine Keycloak-Sitzung erst beim ersten Abruf eines Tokens
und lebte danach 24 Stunden, unabhängig davon, ob Keycloak die Sitzung schon beendet hatte. Ein
Kanal konnte so `AUTHENTICATED` sein, ohne dass es irgendwo eine Anmeldung gab, und eine Abmeldung
in Keycloak erreichte nur den Web-Kanal. Wer die Sitzungen in Keycloak sieht, soll dieselben
Anmeldungen sehen wie im Orchestrator.

**Erwogene Alternativen.**

- **Die Sitzung weiter erst beim ersten Token anlegen,** den Kanal aber mit dem Token enden lassen.
  Verworfen: Zwischen Anmeldung und erstem Abruf gäbe es weiter einen angemeldeten Kanal ohne
  Sitzung, und ein Client, der nie ein Token holt, hielte ihn 24 Stunden.
- **Den Orchestrator bei Keycloak nachfragen lassen,** ob die Sitzung noch lebt, bei jedem Zugriff.
  Verworfen: ein Netzaufruf je Anfrage, und die Frist kennt Keycloak ohnehin schon, wenn es ein
  Token ausstellt.
- **Für den Web-Kanal nur eine Obergrenze festschreiben** (Kanal-TTL ≤ SSO idle des Realms).
  Verworfen: Das hält bei einer neuen Anmeldung, nicht aber beim Step-up auf eine alte Sitzung kurz
  vor ihrem SSO max; dann endet die Sitzung vor dem Kanal.

**Preis.** Der Übergang nach `AUTHENTICATED` im App-Kanal und gelegentlich eine Journey-Interaktion
enthalten einen Aufruf bei Keycloak, in der Transaktion der Journey (wie schon der Token-Abruf,
`OrchestratorArchitectureTest`). Ist Keycloak nicht erreichbar, kann sich niemand in der App
anmelden.

## 1) App-Kanal: Die Sitzung entsteht mit der Anmeldung

- **Eine Stelle.** Ein Kanal wird nur in `JourneyService.finish` `AUTHENTICATED`. Dort holt der
  App-Kanal sein erstes Token (`AppTokenIssuer.tokenFor`), und das legt die Keycloak-Sitzung an
  (Grant aus [ADR-9](ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md)).
- **Lehnt Keycloak ab,** wird der Kanal nicht `AUTHENTICATED`: `SessionRefusedException` wird zu
  `409` („Die Anmeldung konnte nicht abgeschlossen werden“), und die Transaktion rollt den ganzen
  Übergang zurück. Die Journey steht, wo sie vor dem letzten Schritt stand; der Nutzer wählt das
  Verfahren noch einmal. Ist Keycloak gar nicht erreichbar, ist die Antwort `500`, mit derselben
  Wirkung.
- **Eine Sitzung je Kanal, geöffnet genau einmal.** Nur der erste Grant-Aufruf einer Anmeldung
  öffnet eine Sitzung; ihre Id (`sid` des Tokens) steht danach in `AppTokenSession.keycloakSessionId`.
  Jeder spätere Aufruf nennt sie als `session_id`, und der Grant setzt genau diese Sitzung fort:
  Er lehnt ab, wenn sie nicht mehr gilt, einem anderen Nutzer gehört oder nicht von ihm stammt
  (etwa eine Sitzung des Web-Kanals). Ein Step-up verwirft die zwischengespeicherten Tokens, nicht
  die Sitzung und nicht ihr Fenster. Lehnt Keycloak die Fortsetzung ab oder ist das Fenster
  vorbei, endet der Kanal als `EXPIRED`, auch mitten im Abschluss eines Step-ups (`410`).
- **Nicht je Konto.** Vorher suchte der Grant irgendeine von ihm angelegte Sitzung des Kontos und
  legte eine neue an, wenn keine mehr lebte. Damit teilten sich alle App-Kanäle eines Kontos eine
  Sitzung: Die Abmeldung auf einem Gerät beendete sie für alle, ein Step-up auf einem Gerät hob das
  `acr` der Tokens des anderen, und nach einem Step-up konnte still eine zweite Sitzung entstehen.
- **Ohne Keycloak** vergibt `TokenService` beim ersten Token eine eigene Sitzungs-Id
  (`mock-session-…`) und verhält sich sonst gleich.

## 2) Die Frist folgt dem Sitzungsfenster

- **Ab `AUTHENTICATED`** ist `ChannelSession.expiresAt` das Ende des Fensters, das der Token-Dienst
  meldet: Keycloaks `refresh_expires_in`, also das Minimum aus SSO idle und dem Rest von SSO max.
  `AppTokenIssuer` setzt es bei jedem Token neu, beim ersten wie bei jeder Erneuerung.
- **Nach dieser Frist** wird der Kanal abgewiesen wie jeder abgelaufene (`404`), ohne Aufruf bei
  Keycloak. Die Regel, dass eine abgelaufene Anmeldung nie neu ausgestellt wird (I-22), bleibt.
- **Jede Journey-Interaktion kann erneuern.** Solange der Kanal angemeldet ist
  (`ChannelState.isLoggedIn`, also auch während eines Step-ups), darf jede Interaktion mit seiner
  Journey das Token per Refresh erneuern: eine Journey starten, ein Tool aktivieren, ein
  Tool-Schritt, zurück, ein anderes Verfahren, eine Antwort. Das verlängert Keycloaks Leerlauf-Fenster
  und schiebt `expiresAt` des Kanals mit. Die eine Stelle ist `JourneyService` (`keepSessionAlive`),
  nicht die einzelnen Controller. Der Abbruch zählt nicht: Er läuft auch, wenn Keycloak eine
  Abmeldung meldet.
- **Nicht bei jeder Interaktion.** Erneuert wird erst, wenn ein Viertel des Fensters seit dem letzten
  Token verbraucht ist (`AppTokenIssuer.RENEWAL_WINDOW_SHARE`). Ein aktiver Nutzer hat so nie weniger
  als drei Viertel des Leerlauf-Fensters vor sich, und es kostet wenige Keycloak-Aufrufe je Fenster
  statt einen je Anfrage; bei den 30 Minuten SSO idle des Realms ist das eine Erneuerung alle
  7,5 Minuten. Davor bleibt das bestehende Fenster.
- **Lehnt Keycloak die Erneuerung ab** oder ist das Fenster vorbei, endet der Kanal wie beim
  Token-Abruf: `EXPIRED`, Antwort `410`. Damit dieses Ende trotz Fehlerantwort gespeichert wird,
  nennen die Dienste auf dem Weg (`JourneyService`, `ChannelService`, `ToolJourneyService`)
  `ChannelSessionEndedException` in `noRollbackFor`.

## 3) Abmeldung in Keycloak beendet beide Kanäle

Meldet Keycloak eine Abmeldung (`SignInLogEventListener` → `KcChannelService.signedOutAtKeycloak`),
enden alle noch laufenden Kanäle dieser Sitzung: die Web-Kanäle mit dieser `durableKcSessionId` und
der App-Kanal, dessen `AppTokenSession.keycloakSessionId` sie nennt.

## 4) Web-Kanal: Keycloak meldet das Sitzungsende

Im Web-Kanal legt Keycloak die Sitzung selbst an, am Ende des Anmeldedurchlaufs. An dieser Stelle
ruft die Extension ohnehin `GET .../restore-data` auf. Sie schickt jetzt `sessionExpiresAt` mit:
das späteste Ende der Sitzung ohne weitere Aktivität, berechnet aus den Realm-Werten (Leerlauf ab
dem letzten Auffrischen, Höchstdauer ab dem Beginn; `SessionEnd`). Der Orchestrator setzt
`expiresAt` auf das Minimum aus seiner Frist und diesem Wert. Die Extension rechnet mit den Werten
ohne „Angemeldet bleiben“; die sind nie kürzer, der Kanal endet also höchstens zu früh.

Gewählt, weil es mit dem einen Aufruf auskommt, den es am Ende jedes Durchlaufs schon gibt. Nach
diesem Aufruf braucht niemand den Web-Kanal mehr als für einen folgenden Schritt desselben
Durchlaufs; jeder weitere Durchlauf (Step-up) legt einen neuen Kanal an.

## 5) Was nicht garantiert ist

- **Web-Kanal zwischen Anmeldung und Ende des Durchlaufs.** Die Journey meldet `AUTHENTICATED`, bevor
  Keycloak die Sitzung anlegt. Bricht der Nutzer genau dazwischen ab, bleibt der Kanal bis zu seiner
  Frist von 30 Minuten angemeldet, ohne dass es eine Sitzung gibt.
- **Verlorene Meldungen.** `restore-data` und die Meldung einer Abmeldung sind Best-Effort. Fällt
  `restore-data` aus, gilt für den Web-Kanal nur seine feste Frist. Geht eine Abmeldung verloren,
  endet der App-Kanal erst bei der nächsten Erneuerung (Token-Abruf oder Journey-Interaktion;
  Keycloak lehnt sie ab) oder mit seiner Frist.

Beides ist in [invarianten.md](../invarianten.md) als Lücke der Regel I-23 geführt.
