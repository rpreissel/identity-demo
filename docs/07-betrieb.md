# Betrieb

Dieses Kapitel richtet sich an alle, die das System betreiben oder seine Zusagen für den Betrieb
verstehen wollen. Es beschreibt:

- den Fehlervertrag, also welche Fehlerantworten ein Client bekommen kann,
- was das System über Transaktionen zusagt,
- wie lange welche Daten aufbewahrt werden,
- was außerhalb des Demomodus gesetzt sein muss,
- die Sperren und Mengenbegrenzungen gegen Ausprobieren und Massenversand,
- wie man den Zustand des Systems beobachtet.

Was es nur im Demomodus gibt, steht am Ende in Abschnitt 8. Begriffe wie Kanal, Journey oder Tool
erklärt das [Glossar](glossar/glossar.md).

> **Einschränkung ([ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md)):**
> Produktionsreif ist nur der Backend-Kern. Die Frontends und die Ausführungsumgebung sind
> Vorführrahmen und werden später gehärtet. Zur Ausführungsumgebung gehören `compose.yml`,
> `openshift/`, der Admin-Zugang, die H2-Konsole, der Startmodus von Keycloak und TLS zwischen den
> Containern. Bis dahin läuft keine Instanz mit echten Personendaten.

---

## 1) Fehlervertrag

Der Fehlervertrag legt fest, mit welchem HTTP-Status das Backend auf welche Art von Fehler antwortet.
Clients können sich darauf verlassen. Die üblichen Fehlerantworten:

- `400 Bad Request`: Der Inhalt ist ungültig, oder die Anfrage ist formal falsch.
- `401 Unauthorized`: Der DPoP-Proof fehlt oder ist ungültig, oder das Backend vertraut dem Kanal
  nicht. Ein DPoP-Proof ist eine signierte Bestätigung des Clients, dass er den Schlüssel besitzt, an
  den die Sitzung gebunden ist ([DPoP-Bindung](09-dpop.md)). Bei DPoP- und Geräte-Proofs nennt der
  Antworttext nur einen festen Grund (`DpopFailure`). Ein Beispiel ist `IAT_IN_FUTURE`, wenn die Uhr
  des Clients vorgeht. Bei der Peer-Auth-Assertion von Keycloak nennt die Antwort gar keinen Grund.
  Eine Assertion ist hier ein signierter Nachweis, mit dem sich Keycloak beim Orchestrator ausweist.
  Schlüssel-Id, Aussteller, Algorithmus und die Werte der Claims stehen nur im Log.
- `403 Forbidden`: Die Bindung passt nicht, oder eine Regel verbietet die Aktion.
- `404 Not Found`: Die Sitzung oder der Vorgang ist unbekannt.
- `409 Conflict`: Der Zustandswechsel ist unzulässig, oder die Aktion ist nicht erlaubt. Dasselbe
  gilt für einen zweiten gleichzeitigen Vorgang auf derselben `ChannelSession` und für
  widersprüchliche Angaben zum Konto.
- `410 Gone`: Der Vorgang ist abgelaufen, schon verbraucht oder wurde nach zu vielen Fehlversuchen
  abgebrochen.
- `422 Unprocessable Entity`: Die Anfrage ist fachlich nicht verarbeitbar, obwohl der Nutzer nichts
  falsch eingegeben hat. Beispiele sind eine unbekannte `enrollmentRef` oder eine fehlende
  Einrichtung.
- `423 Locked`: Das Konto ist nach zu vielen fehlgeschlagenen Anmeldeversuchen gesperrt
  (`ACCOUNT_LOCKED`, `OrchestratorException.accountLocked()`, siehe Abschnitt 4).
- `429 Too Many Requests`: Eine Mengenbegrenzung ist erreicht. Das Konto ist dabei nicht gesperrt
  (`OrchestratorException.tooManyRequests()`). Ein Beispiel ist `ChannelCreationRateLimitService`,
  der je `bindingKeyRef` zählt (Abschnitt 4).
- `500 Internal Server Error`: Eine interne Annahme ist verletzt, oder etwas ist unerwartet
  fehlgeschlagen, zum Beispiel weil die Datenbank nicht erreichbar ist (`INTERNAL_ERROR`). Die Antwort
  enthält eine feste Text-Referenz. Die Einzelheiten stehen nur im Log. Jede Exception, für die es
  keine eigene Regel gibt, wird so beantwortet (`OrchestratorExceptionHandler.handleUnexpected`). Ausgenommen
  sind nur die eigenen Web-Fehler von Spring, die ihren Status selbst mitbringen: unbekannter Pfad
  (`404`), falsche Methode (`405`) und falscher Inhaltstyp (`415`).

**Form und Quelle.** Jede Fehlerantwort hat dieselbe Form, `ErrorResponse`:
`{"error": "<CODE>", "text": {"key": …, "args": …}}`. Der Text ist eine Referenz auf einen
übersetzbaren Text, wie in [05-api.md](05-api.md) im Abschnitt „Texte“ beschrieben. In dieser Form
steht die Fehlerantwort auch im API-Vertrag, als `default`-Antwort jeder Operation.

Welcher Code zu welchem Status gehört, legt das Enum `ErrorCode` fest (`orchestrator/domain`). Die
Liste der Codes im Vertrag wird daraus erzeugt. Code und Status lassen sich deshalb nicht unabhängig
voneinander wählen. Clients entscheiden anhand des Felds `error`, nie anhand des Textes. Sie müssen
außerdem mit Codes rechnen, die sie noch nicht kennen.

**Welche Exception wozu führt.** Die zentrale Fehlerbehandlung verlässt sich auf folgende Regel im
Code:

- `require` bzw. `IllegalArgumentException` wird nur verwendet, wenn eine **Eingabe des Clients**
  abgelehnt wird. Das ergibt `400`. Einen eigenen Text für den Nutzer hat nur
  `InvalidInputException`, etwa „Das Passwort ist zu kurz“. Jede andere Ablehnung bekommt den festen
  Text „Die Eingabe ist ungültig.“. Ihre eigentliche Meldung steht nur im Log.
- `check`, `checkNotNull` und `error()` werden verwendet, wenn eine **interne Annahme** verletzt ist.
  Das ergibt `500` mit festem Text. So sieht eine interne Prüfung nicht wie ein fachlicher Konflikt
  aus und verrät keine internen Details.
- Ein echter fachlicher Konflikt wird immer ausdrücklich gemeldet, mit
  `OrchestratorException.invalidState(...)`.

**Keine Texte von Frameworks in Fehlerantworten.** Im Feld `text` steht immer ein eigener Text des
Projekts (`Text("…")`). Es steht dort nie die Meldung einer Exception aus Spring, Hibernate, Jackson
oder einer anderen Bibliothek. Solche Meldungen nennen oft Klassen, Spalten, Constraints oder interne
IDs. `OrchestratorExceptionHandler` sorgt dafür:

- Ein Body, der sich nicht lesen lässt, wird zu „Die Anfrage ist nicht lesbar.“.
- Bei einem falsch geformten Wert im Pfad oder in der Query nennt die Antwort nur den Namen des
  Parameters.
- Eine Constraint-Verletzung beim Binden eines Kontos wird zu einem festen Konflikttext.
- Alles andere wird mit `500` und festem Text beantwortet.

Die Originalmeldung geht jeweils nur ins Log.

Ausdrücklich **kein** Fehler sind fehlende Pflichtfelder und Fehlversuche, nach denen noch weitere
Versuche erlaubt sind. Sie liefern `200` und ein `next`, also die Angabe, was der Client als
Nächstes tun soll. Die Regel für Wiederholungen steht in der [Orchestrierung](04-orchestrierung.md).

## 2) Konsistenzregeln

Diese Regeln sorgen dafür, dass die gespeicherten Daten immer zueinander passen, auch wenn mehrere
Anfragen gleichzeitig eintreffen oder ein Schritt scheitert.

- Je `ChannelSession` darf höchstens eine **laufende** `AuthJourney` existieren. Eine
  `ChannelSession` ist die Sitzung eines Kanals, also einer App oder eines Browsers. Eine
  `AuthJourney` ist ein geführter Ablauf mit mehreren Schritten, etwa eine Registrierung oder eine
  Anmeldung. Eine Journey, die auf eine Sub-Journey wartet, ist `SUSPENDED` und zählt nicht mit
  ([Orchestrierung](04-orchestrierung.md)).
- Eine `AuthJourney` darf nur in gültige Folgezustände wechseln. Das gilt für ihren Lebenszyklus und
  für den Zustand ihres Intents (`JourneyState`). Der Intent ist das Ziel der Journey, zum Beispiel
  „registrieren“ oder „anmelden“.
- `AppTokenSession` wird nur aktualisiert, wenn eine Journey erfolgreich abgeschlossen ist.
- Jede Identifizierung, jeder Widerruf und jedes eingerichtete oder deaktivierte Verfahren erzeugt
  einen Eintrag im Änderungsprotokoll des Kontos (`account.change_log`,
  [ADR-39](adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md)). Jeder Übergang einer Journey erzeugt
  einen Eintrag im Journey-Trace, dem Ablaufprotokoll der Journeys.
- **Eine Transaktion für alles:** Wenn ein Tool einen Schritt erfolgreich abschließt, meldet es dem
  Orchestrator ein `ToolOutcome.Completed` ([Orchestrierung](04-orchestrierung.md)). Der Orchestrator
  speichert dann in einer einzigen Transaktion:
  - den neuen Zustand der Journey,
  - den Eintrag am Konto,
  - das Claim-Log (die Protokollierung der bestätigten Angaben, der Claims),
  - den Nachweis der Sitzung (`SessionEvidence`).

  Entweder gelingt alles oder nichts. Das Tool-Modul speichert seine eigenen Tool- und
  Einrichtungsdaten allerdings schon vorher beim `PATCH`, in einer eigenen Transaktion. Scheitert
  danach der Schritt in der Journey, bleibt die Zeile des Moduls zwar stehen. Sie wird aber nicht als
  Credential (Anmeldeverfahren) des Kontos aktiviert.
- Auch neue Konten, Claim-Log, Identifizierungs-Log, Anker und eingerichtete Verfahren liegen in
  dieser Transaktion. Ein Anker ist ein eindeutiges Merkmal, über das ein Konto einer Person
  zugeordnet ist ([Glossar](glossar/glossar.md)). Ein Konto wird nicht vorab in einer eigenen
  Transaktion (`REQUIRES_NEW`) festgeschrieben.

  Wenn zwei Vorgänge gleichzeitig dasselbe Konto binden, gewinnt einer. Der unterlegene wird
  vollständig zurückgerollt und erhält `409 INVALID_STATE_TRANSITION`. Einen automatischen neuen
  Versuch gibt es nicht. Verstöße gegen die Eindeutigkeit von `ux_anchor_value` und
  `ux_anchor_account_type` übersetzt das Backend gezielt in diese Antwort, auch wenn sie erst beim
  Schreiben oder Festschreiben auffallen. Unbekannte Integritätsfehler bleiben Serverfehler.
- Nicht transaktional ist der SMS-Versand, denn er wirkt nach außen. Ein Zurückrollen macht eine
  bereits verschickte SMS nicht rückgängig. Das betrifft nur die Zustellung, nicht die Konsistenz der
  Daten: Die zugehörige Zeile mit `issuedTanHash` wird mit zurückgerollt. Der Code in der SMS passt
  danach zu nichts mehr.

## 3) Aufbewahrung und Löschung

Die Daten einer Sitzung enthalten Personenbezug, zum Beispiel KVNR, Name und Telefonnummer. Außerdem
enthalten sie Werte, die aus Geheimnissen abgeleitet sind (`issuedTanHash`). Nach dem Ende des
Vorgangs liest das System sie nie wieder. Deshalb löscht es sie aktiv, statt sie aufzubewahren.

Die folgende Liste nennt für jede Art von Daten, wann die Frist beginnt, wie lang sie ist und warum.
Die Fristen sind Richtwerte. Sie sind als Voreinstellung gedacht, nicht als Vorgabe für die
Compliance.

- **`QrLoginRequest`**
  - *Frist beginnt mit:* `expiresAt`
  - *Richtwert:* 24 h
  - *Grund:* Das ist die Kopplungsanfrage des QR-Logins. Nach ihrem Ablauf (5 Min.) ist sie
    wirkungslos. `AuthQrRetentionJob` räumt sie mit auf.
- **`DpopProofReplay`**
  - *Frist beginnt mit:* `expiresAt`
  - *Richtwert:* sofort (minütlich)
  - *Grund:* Der Schutz vor wiederholt eingereichten Proofs gilt nur in dem Zeitfenster, in dem ein
    Proof überhaupt angenommen wird.
- **`orchestrator.tool_session`**
  - *Frist beginnt mit:* `expiresAt`
  - *Richtwert:* 24 h (`tool-session.retention`)
  - *Grund:* Die Zeile enthält den Lebenszyklus und die Arbeitsdaten des Tools (Spalte `data`,
    [ADR-49](adr/ADR-049-arbeitsdaten-der-tools-am-orchestrator.md)), also Personenbezug und
    Code-Hashes, seit [ADR-53](adr/ADR-053-arbeitsdaten-und-app-tokens-verschluesselt.md)
    verschlüsselt unter dem Datenschlüssel ihres Tages. Bei einer KOBIL-Einrichtung liegen dort während der Einrichtung PIN und
    Entsperrgeheimnis im Klartext. Danach werden sie geleert
    ([ADR-22](adr/ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md)).
- **`AuthJourney`**
  - *Frist beginnt mit:* `consumedAt` / `expiresAt`
  - *Richtwert:* 7 Tage
  - *Grund:* Der Support soll einen Vorgang bei Rückfragen noch zuordnen können.
- **`AppTokenSession`**
  - *Frist beginnt mit:* Abmeldung / Ende der `ChannelSession`
  - *Richtwert:* sofort
  - *Grund:* Sie enthält die Tokens selbst als Zwischenspeicher, verschlüsselt unter dem
    Hauptschlüssel des Kontos ([ADR-53](adr/ADR-053-arbeitsdaten-und-app-tokens-verschluesselt.md)).
    Die Abmeldung leert sie.
- **`orchestrator.data_key`** (Datenschlüssel der Arbeitsdaten, einer je Tag)
  - *Frist beginnt mit:* `retire_after`, dem Tagesende plus 7 Tage plus `tool-session.retention`
  - *Richtwert:* sofort danach, stündlich (`RetentionJob`)
  - *Grund:* Die Arbeitsdaten in `orchestrator.tool_session.data` liegen verschlüsselt unter dem
    Schlüssel ihres Tages (ADR-53). Nach der Frist kann keine Zeile ihn mehr brauchen; ihn zu löschen
    macht auch das unlesbar, was in Sicherungen noch unter ihm liegt.
- **`ChannelSession`**
  - *Frist beginnt mit:* `expiresAt` / `LOGGED_OUT`
  - *Richtwert:* 14 Tage
  - *Grund:* `JourneyTraceEntry` fragt das Ablaufprotokoll über die Menge der Kanäle ab, auch beim
    Löschen eines Kontos (siehe unten). Die Kanäle länger aufzubewahren als das Protokoll selbst
    (14 Tage) bringt deshalb nichts. Weil bei jedem App-Start eine Sitzung entsteht, ist das eine
    große Tabelle. Gelöscht wird deshalb in Stapeln, mit einer Anweisung je Tabelle.
- **`JourneyTraceEntry`**
  - *Frist beginnt mit:* `createdAt`
  - *Richtwert:* 14 Tage
  - *Grund:* Das ist das Ablaufprotokoll für Fehlersuche, Support und Demo, NICHT das
    Änderungsprotokoll. Es ist mit Abstand die Tabelle mit den meisten Daten, denn sie hat eine Zeile
    je Schritt. 14 Tage decken Support-Fälle ab. Eine längere Frist lässt sich mit dem Zweck nicht
    begründen.
- **`account.change_log`**
  - *Frist beginnt mit:* Löschung des Kontos (`ACCOUNT_DELETED`)
  - *Richtwert:* 10 Jahre (`account.change-log.retention-years`, von der Datenschutzbeauftragten zu
    bestätigen)
  - *Grund:* Das Änderungsprotokoll weist nach, dass und wie ein Konto identifiziert wurde und welche
    Verfahren es hatte. Es enthält keine Werte und keine Fremdschlüssel und überlebt die Löschung des
    Kontos bewusst ([ADR-39](adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md)).
    `ChangeLogRetention` räumt es nach Ablauf der Frist ab.
  - *Suche:* Man sucht über Name, Vorname und Geburtsdatum (`ChangeLogSearch`). Der Suchschlüssel
    ist ein HMAC, also ein mit einem Geheimnis berechneter Prüfwert. Das Geheimnis
    `CHANGE_LOG_LOOKUP_SECRET` ist außerhalb des Demomodus Pflicht. Es muss so lange aufbewahrt
    werden wie das Protokoll. Ohne `CHANGE_LOG_LOOKUP_SECRET` gilt ein öffentlicher Demo-Wert.
    `ProductionModeCheck` lehnt diesen außerhalb des Demomodus ab. Das Modul `account` selbst kennt
    den Demomodus nicht.
  - *Wechsel des Geheimnisses:* Jeder Eintrag merkt sich die Id des Geheimnisses, mit dem er
    berechnet wurde (`lookup_key_id`). Zum Wechseln tragen Sie das alte Geheimnis unter
    `account.change-log.previous-lookup-secrets.<alte Id>` ein und setzen das neue mit einer neuen Id
    (`CHANGE_LOG_LOOKUP_KEY_ID`). Die Suche verwendet dann alle Geheimnisse. Das alte darf erst
    entfernt werden, wenn kein Eintrag mehr mit seiner Id gespeichert ist. Sonst verweigert `ProductionModeCheck`
    außerhalb des Demomodus den Start.
- **`account.sign_in_log`**
  - *Frist beginnt mit:* dem Ereignis
  - *Richtwert:* 6 Monate (`account.sign-in-log.retention-months`)
  - *Grund:* Das Anmeldeprotokoll hält fest, wer sich wann womit angemeldet hat, dazu Fehlversuche,
    Sperren und Abmeldungen. Es dient der Aufklärung, wenn jemand ein Konto übernommen hat. Weil es
    Verhaltensdaten sind, ist die Frist kurz, und das Protokoll wird mit dem Konto gelöscht (ADR-39,
    Nachtrag). `SignInLogRetention` räumt es ab, in Stapeln zu 500 Zeilen, jeden Stapel in einer
    eigenen Transaktion.
- **`*Enrollment` (Credentials der Module)**
  - *Frist beginnt mit:* —
  - *Richtwert:* kein Aufräumen mit der Sitzung
  - *Grund:* Ein eingerichtetes Anmeldeverfahren lebt, bis das Konto gelöscht wird. Es ist über
    `account.auth_method` erreichbar, auch wenn es deaktiviert ist.
- **`account.*` (Anker, Anmeldeverfahren, Claim-, Identifizierungs- und Widerrufs-Log)**
  - *Frist beginnt mit:* —
  - *Richtwert:* kein Aufräumen mit der Sitzung
  - *Grund:* Diese Daten gehören dem Konto und werden mit ihm gelöscht, mit allen Werten. Nur
    `account.change_log` überlebt die Löschung (ADR-39).
- **`account.claim_batch_key` (Datenschlüssel einer Gruppe von Angaben)**
  - *Frist beginnt mit:* dem Schreiben der Gruppe (`expires_at`)
  - *Richtwert:* keine Frist. Je Attribut konfigurierbar (`account.claims.retention.<Attribut>`,
    ISO-Dauer, etwa `family_name: P365D`), nicht für Ankerattribute.
  - *Grund:* Die Werte im Claim-Log liegen verschlüsselt
    ([ADR-52](adr/ADR-052-umschlagverschluesselung-des-claim-logs.md)). Läuft die Frist ab, widerruft
    `ClaimBatchKeyRetention` die Angaben der Gruppe (`RETENTION_POLICY`) und löscht den
    Datenschlüssel, täglich, je Gruppe in einer eigenen Transaktion. Die Zeilen in `account.claim`
    bleiben als Metadaten. Dasselbe geschieht sofort, wenn ein Widerruf die letzte gültige Angabe
    einer Gruppe trifft. Sicherungen dieser Tabelle und der Kontozeile dürfen höchstens so lange
    aufbewahrt werden wie die von `account.claim`, sonst bleibt der „gelöschte“ Wert dort lesbar.
- **Konto im Aufbau** (noch kein Anmeldeverfahren)
  - *Frist beginnt mit:* `createdAt`, sobald kein offener Kanal mehr damit arbeitet
  - *Richtwert:* frühestens nach 1 h, stündlich (`RetentionJob`)
  - *Grund:* Das ist eine Registrierung, die ohne Abbruch endete, etwa weil die App geschlossen
    wurde. Bei einem echten Abbruch verwirft die Registrierung ihr Konto selbst. Der Job räumt den
    Rest ab, vollständig über `AccountDeletionService`
    ([ADR-46](adr/ADR-046-konto-im-aufbau.md)).
- **`DeviceAccountLink`**
  - *Frist beginnt mit:* —
  - *Richtwert:* kein Aufräumen mit der Sitzung
  - *Grund:* Die Zeile hält fest, zu welchem Konto ein Gerät gehört (`bindingKeyRef -> accountId`).
    Sie überlebt bewusst jede einzelne `ChannelSession`
    ([DPoP-Bindung](09-dpop.md) Abschnitt 3).
- **`RateLimitRecord`**
  - *Frist beginnt mit:* letzte Änderung des Zählers
  - *Richtwert:* 7 Tage
  - *Grund:* Das ist weit länger als das längste Zählfenster und die längste Sperre (15 Min.). Ein
    Aufräumlauf löscht nie eine Zeile, deren Sperre noch läuft. Die Frist gilt für die Zähler aller
    Bereiche: `ACCOUNT`, `PERSON`, `BINDING_KEY`, `ADMIN` und die Versandlimits der Module
    (Abschnitt 4).
- **`kobil.*` (Fremdsystem)**
  - *Frist beginnt mit:* —
  - *Richtwert:* **kein** Aufräumen durch uns
  - *Grund:* `kobil` simuliert den externen Dienstleister KOBIL und unterliegt nicht unseren
    Aufbewahrungsregeln. Dass die Simulation ihre Daten überhaupt speichert, ist nötig und keine
    Bequemlichkeit. Sonst würde nach jedem Neustart jede `auth_kobil.enrollment`-Zeile auf einen
    Nutzer zeigen, den es beim Anbieter nicht mehr gibt.
- **`nect.*`, `personenverzeichnis.*` (Fremdsysteme)**
  - *Frist beginnt mit:* —
  - *Richtwert:* **kein** Aufräumen durch uns
  - *Grund:* Das sind simulierte Fremdsysteme wie `kobil`. Auch die Briefe des
    Personenverzeichnisses mit den Freischaltcodes im Klartext bleiben dort, so wie ein Brief aus
    Papier beim Empfänger bleibt.

Die Tabellen verweisen aufeinander. Beim Aufräumen gelten dafür diese Regeln:

- **Besitzkette** (`ChannelSession` → `AuthJourney` → `orchestrator.tool_session` mit den
  Arbeitsdaten des Tools): Die Kette wird von innen nach außen aufgeräumt. Weil die Fristen von innen
  nach außen länger werden, ergibt sich diese Reihenfolge von selbst. Die Arbeitsdaten eines Tools
  werden mit ihrer Zeile gelöscht. Kein Modul räumt sie selbst auf.

  `ToolSessionCoverageTest` prüft gegen das tatsächliche Datenbankschema, dass kein Modul eine eigene
  `*_tool_session`-Tabelle mitbringt. Eine solche Tabelle würde niemand aufräumen.
- **Andere kurzlebige Daten der Module:** Manche Module halten außerhalb einer Tool-Sitzung kurz
  Daten fest. Heute ist das nur `auth_qr.login_request`. Solche Daten räumt das Modul selbst auf
  (`ToolSessionSweeper`). Frist und Intervall stehen an einer Stelle (`tool-session.retention`). Ein
  gemeinsamer Zeitplaner (`ToolSessionRetentionDriver`) startet das Aufräumen. Scheitert ein
  Aufräumlauf, laufen die übrigen trotzdem.
- **Das Änderungsprotokoll verweist auf keine andere Tabelle:** `account.change_log` speichert die
  `accountId` als historischen Wert, nicht als Fremdschlüssel. Denn das Protokoll muss die Löschung
  des Kontos überleben. Eine Id, die auf kein Konto mehr zeigt, ist deshalb erwartet und kein Fehler.
- **Bei KOBIL betrifft das Widerrufen eines Verfahrens auch den Anbieter.** `KobilEnrollmentCleanup`
  löscht nicht nur unsere Zeile, sondern entfernt auch den Nutzer beim Anbieter. Sonst bliebe dort
  ein gebundenes Gerät stehen, von dem bei uns niemand mehr weiß. Dieser zweite Aufruf wirkt nach
  außen. Er liegt deshalb außerhalb der Transaktion, genau wie der simulierte SMS-Versand. Unsere
  Zeile verschwindet in jedem Fall.
- **Objekte des Kontos werden beim Aufräumen der Sitzungen nie gelöscht:** Die Credentials der Module
  (`*_enrollment`), `account.auth_method`, `account.change_log` (IDENTIFIED) und `DeviceAccountLink`
  gehören dem Konto bzw. dem Gerät, nicht der Sitzung.
- **Wird ein Konto gelöscht, räumt das zusätzlich zwei Sitzungstabellen für diese `accountId` auf**,
  obwohl keine von beiden einen Fremdschlüssel auf `account` hat:
  - `orchestrator.journey_trace`, und zwar über **zwei** Schlüssel: das Konto **und** seine
    `ChannelSession`s. Denn Einträge aus der Zeit, bevor die Sitzung einem Konto zugeordnet war,
    haben `account_id = NULL`.
  - `orchestrator.rate_limit`, aber nur den Bereich `ACCOUNT`. Würden auch `BINDING_KEY` oder die
    Versandlimits gelöscht, ließen sich diese Zähler durch eine neue Registrierung zurücksetzen. Die
    Versandlimits werden ohnehin je Nummer oder Adresse gezählt, nicht je Konto.

  `AccountDeletionService.deleteAccount` erledigt das ausdrücklich und unabhängig von den Fristen
  oben.
- **`WEB`-Kanäle haben dieselbe Aufbewahrungsfrist wie alle anderen.** Die Abmeldung im Web-Kanal
  (der Anmeldung im Browser) gehört Keycloak ([05-api.md](05-api.md) Abschnitt 3b). Keycloak meldet
  sie dem Orchestrator (`SignInLogEventListener` → `KeycloakChannelService.signedOutAtKeycloak`). Das
  beendet sofort die noch laufenden Kanäle dieser Sitzung, sowohl den Web- als auch den App-Kanal.
  Bei einem Vorgangszugang (ADR-48) beendet es die Web-Kanäle der Einladung. Der Orchestrator fragt
  Keycloak dafür nicht ab.
- **Ein angemeldeter Kanal lebt so lange wie seine Keycloak-Sitzung**
  ([02-domaenenmodell.md](02-domaenenmodell.md) Abschnitt 3, „Lebensdauer“). Die Aufbewahrungsfrist
  oben beginnt entsprechend früher.
- **Die Fristen des Realms stehen in der Migration**, nicht in den Voreinstellungen von Keycloak
  (keycloak-migrations, `V5__realm_lifetimes.kc.kts`). Ein Realm ist der Bereich in Keycloak, in dem
  Nutzer, Clients und Einstellungen dieser Anwendung liegen. Die Werte:
  - AccessToken 5 Minuten,
  - SSO idle 30 Minuten (Sitzung ohne Aktivität),
  - SSO max 10 Stunden (Sitzung insgesamt),
  - `sslRequired=external`.

  Die ersten beiden Werte gleichen den Fristen von `TokenService` im Standardprofil.

  Ein erreichtes Sicherheitsniveau loa2 gilt 30 Minuten (`loa-max-age` des LoA-2-Subflows). Danach
  übernimmt Keycloak es nicht mehr aus der SSO-Sitzung, und eine Anfrage mit `acr_values=2`
  verlangt einen frischen Nachweis. Das Niveau loa1 gilt für die ganze Sitzung. Für Nachweise der
  Orchestrator-Tools gilt dieselbe Frist im Orchestrator selbst (`identity.policy.loa2-max-age`, siehe
  [Orchestrierung](04-orchestrierung.md) Abschnitt 4, „Ein Nachweis über loa1 altert“). Ändern Sie
  beide Werte immer zusammen.

## 3a) Keycloak-Federation: Aufräumen nach einer Löschung

Keycloak hält keine eigene Kopie der Konten. Es liest sie beim Orchestrator nach. Nur zwei Ereignisse
muss der Orchestrator Keycloak aktiv melden: eine Löschung und das Ende einer Einladung
([05-api.md](05-api.md) Abschnitt 3b, „Keycloak liest die Konten – keine Spiegelung“).

Nach einer Löschung muss Keycloak die Reste des Kontos entfernen. Dieser Aufruf darf nicht spurlos
verloren gehen, wenn er fehlschlägt. Deshalb läuft er über die **Event Publication Registry** von
Spring Modulith ([ADR-29](adr/ADR-029-event-publication-registry-statt-eigener-outbox.md)). Die
Registry speichert jedes Ereignis in einer Tabelle, bis es erfolgreich verarbeitet ist. So
funktioniert es:

- Der Listener ist ein `@ApplicationModuleListener`. Bevor die Transaktion der Löschung
  festgeschrieben wird, schreibt Modulith eine Zeile nach `orchestrator.event_publication`.
- Die Zeile wird erst abgeschlossen, wenn die Methode des Listeners ohne Fehler zurückkehrt. Deshalb
  fängt der Listener Fehler **nicht** ab. Die Exception zeigt der Registry an, dass das Ereignis
  noch nicht erledigt ist.
- Zugestellt wird auf dem gemeinsamen Async-Pool von Spring. Jede Löschung ist unabhängig von jeder
  anderen und darf wiederholt werden. Eine eigene Warteschlange mit fester Reihenfolge ist deshalb
  nicht nötig.
- Offene Zeilen werden nach fünf Minuten erneut zugestellt (`spring.modulith.events.staleness.*`),
  ebenso beim Neustart. Im Profil `keycloak` geschieht das erst nach den Keycloak-Migrationen
  (`KeycloakMigrationRunnerStartup`), nicht parallel dazu. Denn wenn der Demomodus das Realm neu
  aufbaut, liefe eine Löschung sonst gegen ein Realm, das gerade umgebaut wird.
- Der Orchestrator ruft Keycloak mit einem zwischengespeicherten Token des Admin-Clients auf. Lehnt
  Keycloak dieses Token mit 401 ab, etwa nach einem Neuaufbau des Realms, holt `KeycloakAdminClient`
  einmal ein neues Token und wiederholt den Aufruf.
- Die Zeile enthält den Status, die Zahl der Zustellversuche und den Zeitpunkt der letzten
  Wiederholung.

Mit dieser Abfrage sehen Sie, was noch offen ist:

```sql
SELECT event_type, listener_id, publication_date, completion_attempts, status
FROM orchestrator.event_publication
WHERE completion_date IS NULL
ORDER BY publication_date;
```

Zwei Dinge sollten Sie wissen:

- `spring.modulith.events.jdbc.schema: orchestrator` ist zwingend. Ohne diese Einstellung sucht die
  Registry die Tabelle im Standardschema. Sie findet sie dort nicht und schreibt dann nichts, ohne
  jede Fehlermeldung. `EventPublicationRegistryTest` prüft deshalb, dass ein fehlschlagender Listener
  wirklich eine offene Zeile hinterlässt.
- Die Tabelle legt Flyway an (`orchestrator/V15__event_publication.sql`), nicht Modulith. Die Datei
  ist unverändert aus dem Jar `spring-modulith-events-jdbc` übernommen. Beim Wechsel auf eine neue
  Version von Modulith muss man sie mit der Datei im neuen Jar vergleichen.

`KeycloakSessionLogoutListener` nutzt die Registry bewusst nicht. Eine Sitzung in Keycloak, die nicht
beendet wurde, läuft nach wenigen Minuten von selbst ab. Die Sitzungen eines gelöschten Kontos laufen
dagegen nicht von selbst ab. Nur der zweite Fall braucht deshalb eine Wiederholung.

## 3b) Das System läuft als eine einzige Instanz

Das Backend geht davon aus, dass genau eine Instanz läuft. Diese Annahme steht an drei voneinander
unabhängigen Stellen im Code:

- **Die geplanten Jobs** laufen ohne Sperre und ohne Wahl einer führenden Instanz. Bei mehreren
  Instanzen liefe jeder Lauf mehrfach parallel. Alle Jobs sind idempotent, das heißt: Ein zweiter
  Lauf löscht, was der erste übrig ließ, oder er löscht nichts. Gleichzeitige Löschläufe auf
  denselben Zeilen sind aber nicht erprobt. Die Liste der Jobs steht in `SCHEDULED_JOBS`
  (`DeploymentTopology.kt`). `ScheduledJobsTest` prüft, dass sie mit den `@Scheduled`-Methoden
  übereinstimmt:
  - `RetentionJob`: Sitzungen, Journeys, Ablaufprotokoll und Zähler (stündlich),
  - `ToolSessionRetentionDriver`: andere kurzlebige Daten der Module, heute die
    QR-Kopplungsanfragen (stündlich),
  - `DpopReplayProtectionService`: Schutz vor wiederholt eingereichten DPoP-Proofs (minütlich),
  - `ChangeLogRetention`: Änderungsprotokoll gelöschter Konten (täglich),
  - `SignInLogRetention`: Anmeldeprotokoll (täglich),
  - `ClaimBatchKeyRetention`: Datenschlüssel abgelaufener Claim-Gruppen (täglich),
  - `RetentionClassKeys`: Tagesschlüssel der Arbeitsdaten für heute und morgen vorab anlegen
    (beim Start und stündlich).
- `identity.secrets.otp-pepper` ist standardmäßig leer. Der Pepper ist ein geheimer Zusatzwert, mit
  dem das Backend SMS- und E-Mail-Codes vor dem Speichern hasht. Ist er leer, würfelt das Backend ihn
  bei jedem Start neu. Zwei Instanzen könnten dann die Codes der jeweils anderen nicht prüfen. Und
  jede Instanz hätte eigene Zähler für die Versandlimits.
- `RestoreDataCodec` erzeugt sein Signaturgeheimnis je Prozess. Ein RestoreData-Token der einen
  Instanz kann die andere deshalb nicht lesen.

Unkritisch für mehrere Instanzen sind dagegen:

- die Zwischenspeicher im `KeycloakAdminClient`, denn jede Instanz holt ihr eigenes Token,
- `KeycloakAccountRemovalListener`, denn er entfernt nur gelöschte Konten aus Keycloak, und das darf
  auch doppelt geschehen.

Beim Lesen des Codes fällt diese Annahme nicht auf. Sie zeigt sich erst, wenn eine zweite Instanz
läuft, und dann als TAN-Prüfung, die gelegentlich fehlschlägt. Deshalb steht sie ausdrücklich in der
Konfiguration:

```yaml
deployment:
  instances: single   # oder: multiple
```

Der Wert `multiple` schaltet nichts frei. Er beschreibt die Umgebung. `DeploymentTopologyCheck`
prüft beim Start, ob der Code für diese Umgebung geeignet ist. Wenn nicht, bricht der Start ab und
nennt in einer Liste, was fehlt. Sobald die Voraussetzungen erfüllt sind, also eine gemeinsame Sperre
für die Jobs und ein fest gesetzter Pepper, ist diese Prüfung die Stelle, an der man sie lockert.

Für einen Betrieb mit mehreren Instanzen unter hoher Last reicht das allein nicht. Dazu gehören auch
PostgreSQL statt H2, eine Sperre für die Keycloak-Migrationen beim Start und eine Lösung für die
Reihenfolge der Änderungen aus dem Personenverzeichnis. Die vollständige Liste steht in
[14-stand-und-weg-zur-produktion.md](14-stand-und-weg-zur-produktion.md) Abschnitt 7.

## 3c) Außerhalb des Demomodus: was gesetzt sein muss

`demo.mode=false` bedeutet: Hier dürfen echte Personendaten liegen
([ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md)). In diesem Fall bricht
`ProductionModeCheck` den Start ab, solange noch eine Demo-Voreinstellung übrig ist. Die Prüfung nennt
dann alle offenen Punkte auf einmal. Diese Punkte müssen erfüllt sein:

- `demo.admin.password` ist gesetzt und nicht `admin`. Es liegt als Hash vor (`{bcrypt}…`,
  `{argon2}…`, `{scrypt}…` oder `{pbkdf2}…`), nicht im Klartext und nicht als `{noop}`.
- Das Profil `keycloak` ist aktiv (`SPRING_PROFILES_ACTIVE=keycloak`). Ohne dieses Profil gäbe der
  App-Kanal unsignierte Mock-Tokens aus, die jeder fälschen kann.
- `spring.h2.console.enabled=false`.
- `springdoc.api-docs.enabled=false`. Die Voreinstellung folgt `demo.mode`. Sonst zeigten Swagger-UI
  und `/v3/api-docs` jedem ohne Anmeldung alle Endpunkte. Der API-Vertrag liegt ohnehin in `api/`.
- `identity.secrets.otp-pepper`, `account.change-log.lookup-secret` und
  `identity.secrets.master-kek` haben mindestens 32 Zeichen. Die letzten beiden sind nicht der
  öffentliche Demo-Wert. Außerdem ist für jede Id eines Suchschlüssels im Änderungsprotokoll ein
  Geheimnis konfiguriert (Abschnitt 3, `account.change_log`).
- `identity.secrets.master-kek` (`MASTER_KEK`) ist der Umschlagschlüssel der Claim-Verschlüsselung
  ([ADR-52](adr/ADR-052-umschlagverschluesselung-des-claim-logs.md)). Er packt den Hauptschlüssel
  jedes Kontos ein und muss über Neustarts und Instanzen hinweg fest sein. Zum Wechseln den alten
  Wert unter `identity.secrets.previous-master-keks.<alte Version>` eintragen und neuen Wert und
  neue Version (`MASTER_KEK_VERSION`) setzen. Jedes Konto merkt sich die Version, mit der sein
  Hauptschlüssel eingepackt ist (`kek_version`). Der alte Wert darf erst entfernt werden, wenn kein
  Konto mehr seine Version trägt und kein Tagesschlüssel in `orchestrator.data_key` (ADR-53). Sonst
  verweigert `ProductionModeCheck` außerhalb des Demomodus den Start; im Demomodus warnt er. Im Produktivbetrieb gehört dieser Schlüssel in ein KMS oder HSM
  hinter dem Port `MasterKeyWrapper` (`DPoP-demo-61kp`).
- Der Orchestrator erreicht Keycloak über https (`keycloak-migrate.base-url`) und prüft dessen
  Zertifikat (kein `trustSelfSignedCertificate`).
- Keycloak erreicht den Orchestrator über https (`orchestratorBaseUrl` der Keycloak-Einrichtung). Über
  diesen Weg holt Keycloak die Schlüssel des Orchestrators. Mit ihnen meldet sich der Orchestrator bei
  Keycloak an (auch als Master-Realm-Client der Migration), und mit ihnen signiert er seine Antworten.
  Keycloak muss das Zertifikat des Orchestrators prüfen können.

Außerhalb des Demomodus gibt es außerdem nicht:

- die Demo-Oberflächen (`@DemoSurface`), also die Mocks der Fremdsysteme und die Kontenverwaltung mit
  Demo-Reset,
- das Zurücksetzen der Datenbank durch Flyway,
- die Demo-Personen.

Auch Admin-Anmeldungen haben eine Sperre: Fünf falsche Passwörter für einen Benutzernamen sperren ihn
für 15 Minuten (429). Die Sperre gilt dann auch für das richtige Passwort.

**Hinter einem Reverse-Proxy.** Der Orchestrator prüft in DPoP-Proofs, Geräte-Proofs und bei
Peer-Auth das Feld `htu`, also die Adresse, an die der Client seine Anfrage gerichtet hat. Hinter
einem Reverse-Proxy braucht diese Prüfung `server.forward-headers-strategy`. Nur so sind Schema, Host
und Port die, die der Client verwendet hat. Das ist nur sicher, wenn ein vertrauenswürdiger Proxy die
Header `X-Forwarded-*` jedes Mal überschreibt. Sonst kann ein Client sie selbst setzen. Verglichen
wird nach RFC 9449: Schema und Host ohne Beachtung von Groß- und Kleinschreibung, der Pfad exakt
(`htuMatches`).

## 4) Kontosperre, Mengenbegrenzung und Versandlimit (Schutz vor Ausprobieren und Massenversand)

Ein Angreifer könnte versuchen, Passwörter oder Codes durch Ausprobieren zu erraten. Er könnte auch
massenhaft SMS oder E-Mails an fremde Empfänger auslösen. Gegen beides zählt das Backend Versuche und
setzt Grenzen. Dieser Abschnitt beschreibt, was gezählt wird und was beim Überschreiten passiert.

**Das gemeinsame Zählwerk.** Alle Zähler beruhen auf `RateLimitRecord` (Entität, Primärschlüssel
`(scope, subject)`) und `RateLimitCounter`
(`src/main/kotlin/com/example/identity/core/orchestrator/session/`). Das Feld `scope` trennt die
Zählbereiche voneinander: einerseits die Bereiche des Orchestrators (`RateLimitScope`), andererseits
die Mengenbegrenzungen der Tool-Module (mit ihrem Namensraum, etwa `auth_sms.SmsSendLimit`). Alle
nutzen denselben Mechanismus, aber nie dieselben Schlüssel. Der Orchestrator stellt nur das Zählwerk
bereit. Grenzen, Schlüssel und das Zurücksetzen legt fest, wem die Sache gehört
([ADR-44](adr/ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md)).

### Die Zähler des Orchestrators

Im Orchestrator bleiben nur Zähler, die absichtlich über mehrere Tools hinweg gelten oder zu keinem
Tool gehören. Jeder hat einen eigenen `@Service` mit eigenen Grenzen:

- **`AccountLockoutService`**
  - *Bereich:* `ACCOUNT`
  - *Zählt:* fehlgeschlagene Anmeldeversuche an einem Konto
  - *Antwort, wenn die Grenze überschritten ist:* `423 Locked` (`ACCOUNT_LOCKED`) bei der Anmeldung
    an einem bekannten Konto (KNOWN_ACCOUNT_AUTH). Bei der Anmeldung, die das Konto erst über eine
    Angabe wie die E-Mail-Adresse sucht (ACCOUNT_LOOKUP_AUTH), steckt die Sperre in der gewöhnlichen
    Antwort „E-Mail oder Code ungültig“. Sonst ließe sich an der Antwort ablesen, ob ein Konto
    existiert.
- **`PersonLockoutService`**
  - *Bereich:* `PERSON`
  - *Zählt:* fehlgeschlagene Identifizierungsversuche für eine Person und falsche
    Einmalkennwörter:
    - Bei `ident-fsc` rät ein Angreifer ein Geheimnis (den Freischaltcode), und ein Treffer
      übernimmt das Konto.
    - Bei `auth-invite-lookup` gehört das Einmalkennwort einer Person (ADR-48).

    Der Zähler zählt nur dort, wo der Versuch überhaupt eine Person benennt. `ident-eid` bestätigt
    nur die Ausweiskarte (ADR-18) und findet dabei niemanden. Die PIN-Versuche dort begrenzt das
    Versuchsbudget der Journey.
  - *Antwort, wenn die Grenze überschritten ist:* immer in der gewöhnlichen Fehlerantwort, nie als
    eigener Fehler
- **`ChannelCreationRateLimitService`**
  - *Bereich:* `BINDING_KEY`
  - *Zählt:* eröffnete Kanäle je DPoP-Schlüssel (gleitendes Zeitfenster, jeder Versuch zählt)
  - *Antwort, wenn die Grenze überschritten ist:* `429 Too Many Requests`
- **`AdminLoginRateLimitFilter`**
  - *Bereich:* `ADMIN`
  - *Zählt:* falsche Passwörter je Admin-Benutzername
  - *Antwort, wenn die Grenze überschritten ist:* `429 Too Many Requests` (Abschnitt 3c)

Diese vier sind die **Sperren** bzw. sperrenähnlichen Zähler des Orchestrators. Die ersten beiden
gelten für Rateversuche gegen ein Konto oder eine Person, über alle Tools und Kanäle hinweg. Nur ein
Erfolg setzt sie zurück. Manche Tools finden ihr Subjekt (das Konto oder die Person) selbst, nämlich
die Anmeldung per Lookup und die Identifizierung. Diese Tools lesen die Sperren über den Port
`Lockouts`. Schreiben kann sie nur der Orchestrator, und zwar aus dem `Failed`-Ergebnis des Tools.

**Restrisiko: parallele Versuche.** `ACCOUNT` und `PERSON` werden vor einem Versuch nur gelesen. Gezählt
wird erst nach dem Ergebnis. Treffen Versuche gleichzeitig über mehrere Kanäle ein, bestehen deshalb
alle die Prüfung, bevor der fünfte Fehlversuch zählt. Die Grenze von fünf Fehlversuchen je
15 Minuten gilt dann nur ungefähr.

Praktisch betrifft das nur das Raten von Passwörtern:

- Codes sind schon durch das Budget der Journey und das Versandlimit begrenzt.
- Freischaltcode und Einmalkennwort sind zu lang zum Raten.
- Ein Passwort ist durch `PasswordPolicy` und die Rechenkosten von Argon2 geschützt.

Das ist bewusst so gelassen. Den Versuch vorab zu buchen, würde den Port `Lockouts` ändern. Außerdem
bräuchte es ein Zurückbuchen für Zwischenschritte ohne Prüfung. Bevor eine Passwortanmeldung per
Lookup produktiv eingesetzt wird, gehört der Versuch vorab gebucht
([14](14-stand-und-weg-zur-produktion.md) Abschnitt 5). Bis dahin begrenzt eine Ratenbegrenzung je
Absender am Eingang (Proxy oder WAF) solche Häufungen gleichzeitiger Anfragen (`DPoP-demo-164n.29`).

### Die Versandlimits der Tool-Module

Etwas anderes sind die **Mengenbegrenzungen** der Tool-Module. Sie begrenzen keinen Rateversuch,
sondern den Versand von Codes. Die Module zählen über den Port `RateLimits` (`tool_api.ratelimit`) in
ihrem eigenen Namensraum ([Tool-Architektur](03-tool-architektur.md) Abschnitt 7):

- **`SmsSendLimit`** (`auth_sms`) und **`EmailSendLimit`** (`auth_email`)
  - *Zählt:* **versendete** TANs und Codes je Mobilnummer bzw. je E-Mail-Adresse. Gezählt wird über
    alle Tools des Moduls hinweg (`enroll-sms`, `auth-sms`, `auth-sms-lookup` bzw. `confirm-email`,
    `auth-email`, `auth-email-lookup`), in einem gleitenden Zeitfenster: 3 in 10 Min.

    Nummer und Adresse werden vor dem Zählen genauso vereinheitlicht wie beim Versand selbst. Bei
    der Rufnummer übernimmt das `PhoneNumber`: Trennzeichen fallen weg, `00` wird zu `+`. So sind
    `+49-170…` und `0049170…` dieselbe Nummer. Die E-Mail-Adresse wird klein geschrieben, und
    Leerzeichen am Rand fallen weg. In der Tabelle steht der Schlüssel nur als HMAC-SHA256 mit dem
    OTP-Pepper, nicht im Klartext.
  - *Zurückgesetzt:* sobald ein Code dieser Nummer oder Adresse richtig eingegeben wurde. Wer die
    Codes selbst angefordert hat, bekommt sie also. Wer dagegen massenhaft Nachrichten an einen
    Fremden auslösen will, gibt nie einen richtigen Code ein. Sein Zähler wird also nie zurückgesetzt.
  - *Antwort, wenn die Grenze überschritten ist:* beim Einrichten (`enroll-sms`, `confirm-email`) und
    bei der Anmeldung mit bekanntem Konto `429 Too Many Requests`. Beim Einrichten hat der Nutzer
    Nummer und Adresse selbst eingegeben. Das kostet keinen Versuch der Journey, denn geraten wurde
    nichts. Bei der Anmeldung über die E-Mail-Adresse antwortet das Tool dagegen wie bei einer
    unbekannten Adresse. So lässt sich nicht ablesen, ob ein Konto existiert.

### Weitere Regeln

- **Warum das zusätzlich zum Versuchsbudget der Journey nötig ist:** Das Versuchsbudget
  (`AuthJourney.attemptBudget`, [Orchestrierung](04-orchestrierung.md) Abschnitt 7) zählt nur
  innerhalb *einer* Journey. Mit einem neuen Kanal kann ein Client aber jederzeit neu beginnen.
  `ACCOUNT` und `PERSON` begrenzen deshalb falsche Rateversuche über Journeys hinweg. Die
  Versandlimits begrenzen zusätzlich das bloße *erneute Versenden*. Das ist nie ein falscher
  Rateversuch und löst deshalb nie `recordFailure` aus. Ohne die Versandlimits könnte man über
  `auth-sms-lookup`, `auth-email-lookup`, `enroll-sms` und `confirm-email` beliebig viele SMS und
  E-Mails an fremde Empfänger auslösen.
- Fehlschläge beim Einrichten zählen weder bei `AccountLockoutService` noch bei
  `PersonLockoutService` (`ToolJourneyService.chargeRateLimits`, `Failed.NothingGuessed -> Unit`).
  Denn beim Einrichten errät niemand ein vorhandenes Credential. Den Versand *während* des
  Einrichtens begrenzen die Versandlimits (siehe oben).
- Die Grenzwerte:
  - bei Fehlversuchen `MAX_FAILURES = 5` und `LOCKOUT_DURATION = 15 Minuten`,
  - beim Eröffnen von Kanälen 20 in 5 Minuten,
  - beim Versand 3 in 10 Minuten.
- Ist eine Sperre abgelaufen, beginnt der nächste Fehlversuch wieder bei eins. Sonst könnte ein
  Angreifer mit einem einzigen falschen Versuch je Sperrdauer ein fremdes Konto, eine Person oder den
  Admin-Zugang dauerhaft gesperrt halten.
- Jeder Zähler wird mit einer einzigen atomaren `UPDATE`-Anweisung erhöht. Dabei gilt die
  Zeilensperre, die diese Anweisung selbst setzt. Der Zähler wird nie erst gelesen und dann
  geschrieben. Sonst wäre die tatsächliche Grenze „Grenze × Zahl gleichzeitiger Anfragen“, und die
  Sperre ließe sich umgehen. Fehlt die Zeile für einen Zähler noch, läuft es so: zuerst das
  `UPDATE`. Hat es keine Zeile getroffen, wird die Zeile angelegt und das `UPDATE` wiederholt
  (`RateLimitRecordInitializer`, in einer eigenen Transaktion).
- Eine erfolgreiche Anmeldung oder Identifizierung setzt den jeweiligen Zähler zurück
  (`recordSuccess`). Ein richtig eingegebener Code setzt das Versandlimit seiner Nummer oder Adresse
  zurück. Der Zähler für das Eröffnen von Kanälen wird nie zurückgesetzt. Er ist ein reines
  gleitendes Zeitfenster.
- Zur Aufbewahrung der Zähler siehe die Liste in Abschnitt 3.

## 5) QR-Login: Sicherheit des Pairing-Codes

Beim QR-Login meldet man sich auf der Website an, indem man einen QR-Code mit der App bestätigt. Wie
Pairing-Code und Bestätigungscode davor schützen, dass jemand einen fremden QR-Code bestätigt, Codes
errät oder sich doppelt anmeldet, steht beim Verfahren:
[verfahren/qr.md](verfahren/qr.md), „Sicherheit des Pairing-Codes“.

## 6) Datenbankschema

Das Schema liegt in `src/main/resources/db/migration/<modul>/`, ein Ordner je Modul. Weitere
Informationen stehen an diesen Stellen:

- die Regeln für Schema und Migrationen in
  [`db/migration/KONVENTIONEN.md`](../src/main/resources/db/migration/KONVENTIONEN.md) (siehe auch
  ADR-14 und ADR-16 in [12-entscheidungen.md](12-entscheidungen.md)),
- die Persistenz insgesamt in [08-projektrahmen.md](08-projektrahmen.md) Abschnitt 4,
- ein Diagramm der wichtigsten Tabellen in [02-domaenenmodell.md](02-domaenenmodell.md) Abschnitt 7.

Für den Betrieb gilt außerdem:

- **Ein Migrationsfehler bricht den Start ab.** Außerhalb des Demomodus ist die H2-Datei die
  Betriebsdatenbank. Ein Migrationsfehler darf dort nie Konten und Änderungsprotokoll löschen. Flyway
  bricht den Start deshalb ab, und das ist so gewollt. Wie die Datenbank im Demomodus zurückgesetzt
  wird, steht in Abschnitt 8.
- **Die Migrationen sind eine Ausgangsbasis ohne Produktivdaten.** Ab dem ersten produktiven Einsatz
  dürfen sie nur noch ergänzen, nicht mehr ändern. Tabellen mit 10 Millionen Zeilen oder mehr werden
  in wiederholbaren Portionen umgestellt.
- **Aufbewahrung:** Jede Aufräumabfrage ist eine einzige SQL-Anweisung über viele Zeilen. Sie hat
  einen Index auf der Spalte, an der sie den Stichtag misst.

## 7) Zustand und Kennzahlen (Actuator)

Spring Boot Actuator stellt Endpunkte bereit, über die man sieht, ob die Anwendung läuft und wie es
ihr geht. Diese Endpunkte liegen auf einem **eigenen Management-Port** (`MANAGEMENT_PORT`, Standard
`9080`). Kein Service und keine Route führt diesen Port nach außen. Auf dem öffentlichen Port `8080`
gibt es `/actuator` nicht. Freigegeben sind nur zwei Endpunkte:

- **`/actuator/health`** mit den Probes `/actuator/health/liveness` und
  `/actuator/health/readiness`. Die OpenShift-Probes nutzen sie. `readiness` meldet erst dann
  „bereit“, wenn alle Startschritte erledigt sind, also auch die Keycloak-Migrationen. In demselben
  Zeitfenster beantwortet `ReadinessGateFilter` die API mit `503`. Außerdem meldet `readiness` nur
  „bereit“, wenn die Datenbank erreichbar ist.
  - **Keycloak** steht als eigene Komponente `keycloak` in `/actuator/health`, aber nicht in
    `readiness`. Denn der App-Kanal braucht Keycloak nicht. Würde jede Instanz bei einem Ausfall von
    Keycloak vom Lastverteiler keine Anfragen mehr bekommen, würde aus einem Teilausfall ein
    Totalausfall. Die Komponente ist für den Alarm da, nicht für die Lastverteilung.
- **`/actuator/prometheus`** mit diesen Kennzahlen:
  - **`identity.ratelimit.blocked`** (je `scope`): wie oft eine Sperre oder Mengenbegrenzung eine
    Anfrage abgewiesen hat (Abschnitt 4). Ein Anstieg bedeutet einen Angriff oder einen Fehler.
  - **`identity.retention.deleted`** (je `table`): wie viele Zeilen die Aufräumläufe gelöscht haben
    (Abschnitt 3). Bleibt die Linie über Tage flach, löscht ein Lauf nicht mehr.
  - **`identity.events.incomplete`**: Ereignisse in `orchestrator.event_publication`, die noch nicht
    abgeschlossen sind (Abschnitt 3a). Einige offene sind normal. Wächst die Zahl nur noch, scheitert
    ein Listener an jedem Ereignis, zum Beispiel das Aufräumen in Keycloak nach einer Kontolöschung.
  - **`http.client.requests`** (je `client.name`): Dauer und Ergebnis jedes Aufrufs an Keycloak.

**Logs.** Jede Log-Zeile, die während einer Anfrage geschrieben wird, enthält eine Anfrage-Id.
Wenn der Pfad der Anfrage sie nennt, enthält sie außerdem die `channelSessionId` bzw. `toolSessionId`
(`LoggingContextFilter`, MDC). Es sind nur Ids aus der URL, nichts Persönliches. Lokal stehen die Ids
in eckigen Klammern vor der Meldung. Im Betrieb schreibt der Orchestrator strukturiert als ECS-JSON
(`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, gesetzt in `openshift/identity-demo.yaml`). Die Ids stehen
dann in eigenen Feldern.

## 8) Im Demomodus

Was hier steht, gilt nur mit `demo.mode=true`
([ADR-36](adr/ADR-036-niveaus-und-ihre-nachweise.md)). Was außerhalb des Demomodus verlangt wird,
steht in Abschnitt 3c.

- **Verwaiste Keycloak-Sitzungen beim Start.** Manchmal hat der Orchestrator beim Start kein einziges
  Konto, etwa nach einem frischen Volume oder einer neu aufgesetzten Datenbank. Dann gehört jede
  Sitzung in Keycloak zu einem Konto, das es nicht mehr gibt. Der Orchestrator meldet in diesem Fall
  in Keycloak alle Sitzungen ab (`KeycloakOrphanSessionsAtStart`).
- **Datenbank zurücksetzen.** Passt eine lokale H2-Datei nicht mehr zu den Migrationen, löscht
  `orchestrator.schema.FlywayResetConfig` sie beim Start und baut sie neu auf. Sie müssen
  `rm -rf data/` also nicht von Hand ausführen. Außerhalb des Demomodus gibt es diese Klasse gar nicht
  (Abschnitt 6). Die Demo-Personen (`demo_seed`) werden außerhalb des Demomodus nicht migriert.
- **Server-Status.** Die Willkommensseite zeigt unter „Server-Status“ denselben Zustand und dieselben
  Kennzahlen wie in Abschnitt 7 (`GET /orchestrator/demo/server-info`, Block `operations`). Der
  Browser erreicht den Management-Port nicht. Deshalb liest das Backend die Werte aus und gibt sie
  weiter. Außerhalb des Demomodus fehlt dieser Block. Denn der Endpunkt verlangt keine Anmeldung,
  und der Management-Port ist mit Absicht nicht nach außen geroutet.
