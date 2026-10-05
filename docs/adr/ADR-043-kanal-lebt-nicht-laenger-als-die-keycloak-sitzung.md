# ADR-43: Ein angemeldeter Kanal hat genau eine Keycloak-Sitzung und lebt nicht länger als sie

**Status:** umgesetzt 2026-09.

**Worum es geht.** Ein [Kanal](../glossar/glossar.md) ist die Verbindung eines Nutzers zum
Orchestrator, dem Server dieses Projekts, entweder über die App oder über die Webseite. Im Code
heißt er `ChannelSession`. Hat sich der Nutzer erfolgreich angemeldet, steht der Kanal im Zustand
`AUTHENTICATED`. Parallel dazu führt Keycloak, der Anmeldeserver, eine eigene
[Keycloak-Sitzung](../glossar/glossar.md) für diesen Nutzer. Sie endet nach einer Zeit ohne
Aktivität („SSO idle“) und spätestens nach einer Höchstdauer („SSO max“). Die Frage ist, wie beide
Lebensdauern zusammenhängen: Darf ein Kanal noch als angemeldet gelten, wenn Keycloak die Sitzung
längst beendet hat?

**Entscheidung.** Der Übergang nach `AUTHENTICATED` erzeugt eine Keycloak-Sitzung. Eine
`ChannelSession` lebt nie länger als die Keycloak-Sitzung, zu der sie gehört. Wie lange die Sitzung
dauert, bestimmt Keycloak (SSO idle, SSO max), und der Orchestrator übernimmt diese Frist. Verlängern
kann er sie nur so, wie jeder Client es kann: mit einem Refresh, also dem Erneuern des Tokens,
solange der Nutzer aktiv ist. Er verlängert sie nie, ohne Keycloak zu fragen. Die festen
Lebensdauern der Kanäle (App 24 Stunden, Web 30 Minuten) gelten nur bis zur Anmeldung.

Zu einer `ChannelSession` gehört genau eine Keycloak-Sitzung. Eine zweite wird nie geöffnet, auch
nicht, wenn die erste abgelaufen ist. Dann endet der Kanal. Ein Step-up bleibt möglich. Ein Step-up
hebt eine bestehende Anmeldung auf ein höheres Sicherheitsniveau. Er hebt dabei `acr` und `amr` in
derselben Sitzung an. (`acr` ist das erreichte Niveau, `amr` die Liste der benutzten Verfahren.)

Im Standardprofil ohne Keycloak übernimmt der simulierte Token-Dienst (`TokenService`) die Rolle
von Keycloak. Seine Sitzung beginnt ebenfalls mit `AUTHENTICATED`, ihr Fenster ist 30 Minuten
Leerlauf.

**Warum.** Bisher bekam der App-Kanal seine Keycloak-Sitzung erst, wenn er zum ersten Mal ein Token
abrief. Danach lebte er 24 Stunden, auch wenn Keycloak die Sitzung schon beendet hatte. Ein Kanal
konnte so `AUTHENTICATED` sein, ohne dass es irgendwo eine Anmeldung gab. Und eine Abmeldung in
Keycloak erreichte nur den Web-Kanal. Ziel ist: Wer die Sitzungen in Keycloak ansieht, soll dieselben
Anmeldungen sehen wie im Orchestrator.

**Erwogene Alternativen.**

- **Die Sitzung weiterhin erst beim ersten Token anlegen,** den Kanal aber mit dem Token enden
  lassen. Verworfen: Zwischen Anmeldung und erstem Abruf gäbe es weiter einen angemeldeten Kanal ohne
  Sitzung. Und ein Client, der nie ein Token holt, hielte ihn 24 Stunden lang.
- **Den Orchestrator bei jedem Zugriff bei Keycloak nachfragen lassen,** ob die Sitzung noch lebt.
  Verworfen: Das kostet einen Netzaufruf je Anfrage. Außerdem nennt Keycloak die Frist ohnehin schon,
  wenn es ein Token ausstellt.
- **Für den Web-Kanal nur eine Obergrenze festschreiben** (Lebensdauer des Kanals ≤ SSO idle des
  Realms). Verworfen: Das genügt bei einer neuen Anmeldung. Es genügt aber nicht bei einem Step-up
  auf eine alte Sitzung, die kurz vor ihrem SSO max steht. Dann endet die Sitzung vor dem Kanal.

**Preis.** Der Übergang nach `AUTHENTICATED` im App-Kanal und gelegentlich eine Interaktion mit der
Journey (dem geführten Ablauf, den der Nutzer gerade durchläuft) rufen Keycloak auf. Dieser Aufruf
liegt in der Transaktion der Journey, wie schon der Token-Abruf (`OrchestratorArchitectureTest`).
Ist Keycloak nicht erreichbar, kann sich deshalb niemand in der App anmelden.

## 1) App-Kanal: Die Sitzung entsteht mit der Anmeldung

Im App-Kanal legt der Orchestrator die Keycloak-Sitzung selbst an, und zwar genau im Moment der
Anmeldung.

- **Eine Stelle.** Ein Kanal wird nur in `JourneyService.finish` `AUTHENTICATED`. Dort holt der
  App-Kanal sein erstes Token (`AppTokenIssuer.tokenFor`), und das legt die Keycloak-Sitzung an.
  Dafür dient ein eigener Grant, also eine eigene Art, bei Keycloak ein Token anzufordern
  ([ADR-9](ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md)).
- **Lehnt Keycloak ab,** wird der Kanal nicht `AUTHENTICATED`. Aus `SessionRefusedException` wird
  die Antwort `409` („Die Anmeldung konnte nicht abgeschlossen werden“), und die Transaktion macht
  den ganzen Übergang rückgängig. Die Journey steht dann wieder dort, wo sie vor dem letzten Schritt
  stand, und der Nutzer wählt das Verfahren noch einmal. Ist Keycloak gar nicht erreichbar, lautet
  die Antwort `500`, mit derselben Wirkung.
- **Eine Sitzung je Kanal, genau einmal geöffnet.** Nur der erste Grant-Aufruf einer Anmeldung
  öffnet eine Sitzung. Ihre Kennung (`sid` des Tokens) steht danach in
  `AppTokenSession.keycloakSessionId`. Jeder spätere Aufruf nennt sie als `session_id`, und der Grant
  setzt genau diese Sitzung fort. Er lehnt ab, wenn die Sitzung nicht mehr gilt, einem anderen
  Nutzer gehört oder nicht von ihm stammt (etwa eine Sitzung des Web-Kanals). Ein Step-up verwirft
  die zwischengespeicherten Tokens, aber nicht die Sitzung und nicht ihr Fenster. Lehnt Keycloak die
  Fortsetzung ab oder ist das Fenster vorbei, endet der Kanal als `EXPIRED`. Das gilt auch mitten im
  Abschluss eines Step-ups (Antwort `410`).
- **Nicht je Konto.** Vorher suchte der Grant irgendeine von ihm angelegte Sitzung des Kontos. Lebte
  keine mehr, legte er eine neue an. Damit teilten sich alle App-Kanäle eines Kontos eine Sitzung.
  Das hatte drei Folgen:
  - Die Abmeldung auf einem Gerät beendete die Sitzung für alle Geräte.
  - Ein Step-up auf einem Gerät hob das `acr` der Tokens auf dem anderen Gerät.
  - Nach einem Step-up konnte unbemerkt eine zweite Sitzung entstehen.
- **Ohne Keycloak** vergibt `TokenService` beim ersten Token eine eigene Sitzungskennung
  (`mock-session-…`) und verhält sich sonst gleich.

## 2) Die Frist folgt dem Sitzungsfenster

Sobald der Kanal angemeldet ist, richtet sich sein Ablaufzeitpunkt nach dem Fenster der
Keycloak-Sitzung. Dieses Fenster lässt sich durch Aktivität verlängern.

- **Ab `AUTHENTICATED`** ist `ChannelSession.expiresAt` das Ende des Fensters, das der Token-Dienst
  meldet. Bei Keycloak ist das `refresh_expires_in`, also der kleinere Wert aus SSO idle und der
  Restzeit bis SSO max. `AppTokenIssuer` setzt diesen Wert bei jedem Token neu, beim ersten und bei
  jeder Erneuerung.
- **Nach dieser Frist** weist der Orchestrator den Kanal ab wie jeden abgelaufenen Kanal (`404`),
  ohne Keycloak zu fragen. Die Regel, dass eine abgelaufene Anmeldung nie neu ausgestellt wird
  (I-22), bleibt bestehen.
- **Jede Journey-Interaktion kann erneuern.** Solange der Kanal angemeldet ist
  (`ChannelState.isLoggedIn`, also auch während eines Step-ups), darf jede Interaktion mit seiner
  Journey das Token per Refresh erneuern. Interaktionen sind zum Beispiel:
  - eine Journey starten,
  - ein Tool aktivieren (ein Tool ist ein einzelner Arbeitsschritt wie „SMS-Code eingeben“),
  - einen Tool-Schritt ausführen,
  - zurückgehen,
  - ein anderes Verfahren wählen,
  - eine Antwort geben.

  Das verlängert das Leerlauf-Fenster von Keycloak und verschiebt `expiresAt` des Kanals
  entsprechend. Diese
  Logik steht an einer Stelle, in `JourneyService` (`keepSessionAlive`), nicht in den einzelnen
  Controllern. Der Abbruch zählt nicht als Interaktion, denn er läuft auch dann, wenn Keycloak eine
  Abmeldung meldet.
- **Nicht bei jeder Interaktion.** Der Orchestrator erneuert erst, wenn seit dem letzten Token ein
  Viertel des Fensters verbraucht ist (`AppTokenIssuer.RENEWAL_WINDOW_SHARE`). Vorher bleibt das
  bestehende Fenster. So hat ein aktiver Nutzer nie weniger als drei Viertel des Leerlauf-Fensters
  vor sich. Gleichzeitig kostet das nur wenige Aufrufe bei Keycloak je Fenster statt einen je
  Anfrage. Bei den 30 Minuten SSO idle des Realms ist das eine Erneuerung alle 7,5 Minuten.
- **Lehnt Keycloak die Erneuerung ab** oder ist das Fenster vorbei, endet der Kanal wie beim
  Token-Abruf: Zustand `EXPIRED`, Antwort `410`. Dieses Ende soll gespeichert werden, obwohl die
  Antwort ein Fehler ist. Deshalb führen die beteiligten Dienste (`JourneyService`,
  `ChannelService`, `ToolJourneyService`) `ChannelSessionEndedException` in `noRollbackFor`.

## 3) Abmeldung in Keycloak beendet beide Kanäle

Meldet sich ein Nutzer in Keycloak ab, meldet Keycloak das an den Orchestrator
(`SignInLogEventListener` → `KeycloakChannelService.signedOutAtKeycloak`). Dann enden alle noch
laufenden Kanäle dieser Sitzung:

- die Web-Kanäle mit dieser `durableKeycloakSessionId`,
- der App-Kanal, dessen `AppTokenSession.keycloakSessionId` diese Sitzung nennt.

## 4) Web-Kanal: Keycloak meldet das Sitzungsende

Im Web-Kanal legt Keycloak die Sitzung selbst an, am Ende des Anmeldedurchlaufs. Der Orchestrator
erfährt das Ende der Sitzung deshalb von Keycloak. Genau an dieser Stelle ruft die Extension (der
Code des Projekts, der in Keycloak läuft) ohnehin `GET .../restore-data` auf. Bei diesem Aufruf
schickt sie jetzt zusätzlich `sessionExpiresAt` mit. Das ist das späteste Ende der Sitzung, wenn
keine weitere Aktivität kommt. Die Extension berechnet es aus den Werten des Realms (`SessionEnd`):

- der Leerlauf zählt ab dem letzten Auffrischen,
- die Höchstdauer zählt ab dem Beginn.

Der Orchestrator setzt `expiresAt` auf den kleineren Wert aus seiner eigenen Frist und diesem Wert.
Die Extension rechnet mit den Werten ohne „Angemeldet bleiben“. Diese Werte sind nie kürzer. Der
Kanal endet also höchstens zu früh, nie zu spät.

Diese Lösung ist gewählt, weil sie mit dem einen Aufruf auskommt, den es am Ende jedes Durchlaufs
schon gibt. Nach diesem Aufruf braucht niemand den Web-Kanal mehr, außer für einen folgenden Schritt
desselben Durchlaufs. Jeder weitere Durchlauf (etwa ein Step-up) legt einen neuen Kanal an.

## 5) Was nicht garantiert ist

Zwei Fälle deckt die Regel nicht vollständig ab:

- **Web-Kanal zwischen Anmeldung und Ende des Durchlaufs.** Die Journey meldet `AUTHENTICATED`, bevor
  Keycloak die Sitzung anlegt. Bricht der Nutzer genau dazwischen ab, bleibt der Kanal bis zu seiner
  Frist von 30 Minuten angemeldet, obwohl es keine Sitzung gibt.
- **Verlorene Meldungen.** `restore-data` und die Meldung einer Abmeldung sind Best-Effort, also ohne
  Garantie, dass sie ankommen. Fällt `restore-data` aus, gilt für den Web-Kanal nur seine feste
  Frist. Geht eine Abmeldung verloren, endet der App-Kanal erst bei der nächsten Erneuerung (beim
  Token-Abruf oder bei einer Journey-Interaktion, denn Keycloak lehnt sie dann ab) oder mit seiner
  Frist.

Beides ist in [invarianten.md](../invarianten.md) als Lücke der Regel I-23 geführt.
