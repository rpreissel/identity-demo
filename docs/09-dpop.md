# DPoP-Bindung

Dieses Kapitel beschreibt, wie die App ihre Anfragen an den Orchestrator absichert. Dafür nutzt sie
**DPoP** (RFC 9449), ein Standardverfahren, mit dem jede Anfrage beweist, von welchem Gerät sie
kommt. Der Orchestrator ist der Server dieses Projekts, der die Abläufe steuert.

### Die Idee in Kürze

Wer eine Anfrage im Netz abfängt, soll sie nicht nachmachen oder für sich nutzen können. DPoP löst
das in drei Schritten:

1. **Ein Schlüsselpaar auf dem Gerät.** Die App erzeugt einmalig ein Schlüsselpaar: einen privaten
   Schlüssel, der das Gerät nie verlässt, und einen öffentlichen Schlüssel, den jeder sehen darf.
2. **Jede Anfrage ist signiert.** Zu jeder Anfrage schickt die App einen kleinen, mit dem privaten
   Schlüssel signierten Beleg mit, den **DPoP-Proof**. Er nennt unter anderem die HTTP-Methode und
   die Adresse der Anfrage, eine einmalige Kennung und den Zeitpunkt, zu dem er entstand. Er gilt
   deshalb nur für diese eine Anfrage und nur kurz.
3. **Die Sitzung ist an den Schlüssel gebunden.** Der Server merkt sich, zu welchem Schlüssel eine Sitzung
   gehört. Eine spätere Anfrage an diese Sitzung nimmt er nur an, wenn ihr Proof mit genau diesem
   Schlüssel signiert ist. Wer nur eine abgefangene Anfrage hat, aber nicht den privaten Schlüssel,
   kann deshalb keine gültige Anfrage an diese Sitzung schicken.

Im Standard wird typischerweise das Zugangs-Token an den Schlüssel gebunden. In diesem Projekt ist
es die Sitzung der App beim Orchestrator, der **Kanal** (`ChannelSession`), also die Verbindung
eines Nutzers zum Orchestrator. Die Tokens von Keycloak, die die App am Ende bekommt, sind dagegen
nicht an den Schlüssel gebunden. Abschnitt 4 erklärt, warum.

### Was der Schlüssel bedeutet und was nicht

Aus dem öffentlichen Schlüssel berechnet der Server einen Fingerabdruck, den `binding_key_ref`.
Dieser Wert beweist nur, welches **Gerät** gerade spricht. Er ist bewusst **kein** Schlüssel, über
den der Server eine `ChannelSession` findet oder wiederverwendet
([02-domaenenmodell.md](02-domaenenmodell.md)). Eine bestimmte Sitzung erkennt der Server an ihrer
`channelSessionId`. Diese Kennung muss sich der Client selbst merken.

---

## 1) Prinzip

Das Frontend erzeugt beim ersten Start ein Schlüsselpaar (ECDSA P-256). Es speichert das Paar im
Browser, in der Browser-Datenbank IndexedDB. Den öffentlichen Schlüssel schickt es in jedem
DPoP-Proof mit, und zwar im Format JWK (JSON Web Key). Das Backend berechnet daraus einen
JWK-Thumbprint nach RFC 7638, also einen eindeutigen Fingerabdruck des Schlüssels. Diesen
Fingerabdruck führt es fachlich als `binding_key_ref`.

Zu den Begriffen: Der DPoP-Thumbprint heißt fachlich überall `binding_key_ref`. Nur die
kryptografische Berechnung im Paket `orchestrator/dpop` verwendet die Begriffe aus RFC 7638
(`JwkThumbprintService`).

Alle Anfragen des App-Kanals enthalten den Header `DPoP: <proof>`.

---

## 2) Anforderungen

| ID | Anforderung | Kriterium |
|----|-------------|-----------|
| D-1 | Das Frontend erzeugt ein Schlüsselpaar, das sich für DPoP eignet. | Ein asymmetrisches Schlüsselpaar (ECDSA P-256), erzeugt über die Web Crypto API des Browsers |
| D-2 | Das DPoP-Schlüsselpaar wird im Browser gespeichert. | Es bleibt erhalten, auch wenn die Seite neu geladen wird |
| D-3 | Der private DPoP-Schlüssel lässt sich nicht exportieren. | Er wird mit `extractable=false` erzeugt. Der öffentliche Schlüssel (JWK) bleibt exportierbar, weil er in den Proof-Header muss |
| D-4 | *Nur für die Demo:* Der öffentliche DPoP-Schlüssel ist im Frontend sichtbar. | Die Oberfläche zeigt den `jwk`-Teil an. In der Demo-Spalte erscheint er als JWK-Thumbprint ([10-frontend.md](10-frontend.md) Abschnitt 6, FE-14) |
| D-5 | Alle Aufrufe des App-Zugangs sind mit DPoP abgesichert. | Der Header `DPoP` enthält ein gültiges DPoP-Proof-JWT |
| D-6 | Ein DPoP-Proof lässt sich nicht wiederverwenden. | Wird dieselbe Kombination aus JWK-Thumbprint und `jti` (der einmaligen Kennung des Proofs) erneut benutzt, antwortet der Server mit `401` |
| D-7 | Ein DPoP-Proof gilt nur begrenzte Zeit, gemessen an seinem Ausstellungszeitpunkt `iat`. | Proofs mit zu altem `iat` weist der Server mit `401` ab |
| D-8 | Das Zeitfenster für `iat` ist einstellbar. | `max-age-seconds` und `max-clock-skew-seconds` stehen in `application.yml`, und die Prüfung verwendet sie |

### Wie der Server wiederholte Proofs erkennt (D-6)

Damit niemand einen abgefangenen Proof ein zweites Mal einsetzen kann, merkt sich der Server jeden
benutzten Proof in der Tabelle `orchestrator.dpop_proof_replay`. Das Einfügen mit dem
Primärschlüssel **ist** dabei die Prüfung: Der Server liest nicht erst nach und schreibt dann.
Scheitert das Einfügen, weil die Zeile schon da ist, war der Proof schon einmal da. Weil die Prüfung
in der Datenbank steckt, überlebt sie einen Neustart und gilt für alle Instanzen gemeinsam.

Der Primärschlüssel ist SHA-256(`thumbprint:jti`) und hat damit eine feste Länge (`VARCHAR(64)`).
Ein `jti`, das der Client frei wählt, kann so weder die Länge des Schlüssels überschreiten noch den
Index aufblähen. In diesen Index schreibt das System häufiger als in jeden anderen.

Das Einfügen ist ein ausdrückliches `INSERT` (`DpopProofReplayRepository.insert`), nicht
`save`/`saveAndFlush`. Der Grund: Bei einer selbst vergebenen Id liest Spring Data zuerst nach. Gibt
es die Zeile schon, macht es aus dem Schreiben ein `UPDATE`, und ein wiederholter Proof käme ohne
Fehler durch. `DpopReplayProtectionDbTest` prüft das Einfügen deshalb gegen die echte Tabelle, einmal
nacheinander und einmal gleichzeitig. Derselbe Schutz gilt für die Geräte-Proofs (die Belege des
Anmeldeverfahrens `device`) und für die Peer-Auth-Assertions von Keycloak, also die signierten
Anfragen, mit denen sich Keycloak beim Orchestrator ausweist.

### Kein Server-Nonce

**Kein Server-Nonce (`DPoP-Nonce`, RFC 9449 Abschnitt 8) – das ist bewusst so.** Ein Nonce wäre ein
Wert, den der Server vorgibt und den der nächste Proof enthalten muss. Diesen Wert gibt der Server
hier nicht vor. Ein Proof ist deshalb nicht nur in dem Moment gültig, in dem er entsteht.

Was das bedeutet: Wer den privaten Schlüssel kurz benutzen kann, etwa Schadcode im Browser, kann
Proofs im Voraus berechnen und später einsetzen. Das reicht für die nächsten anderthalb Minuten
(`max-age-seconds` 60 plus 30 Sekunden Uhrenabweichung), und jeder Proof ist nur einmal nutzbar
(D-6). Solange es keinen Nonce gibt, bleibt `max-age-seconds` deshalb knapp. Die Toleranz für ein
`iat` in der Zukunft (`max-clock-skew-seconds`) fängt Uhren ab, die gegenüber dem Server vorgehen.

Ein Nonce würde dieses Fenster auf einen einzigen Hin- und Rückweg verkürzen. Er kostet aber bei
jeder Anfrage eine zusätzliche Antwort mit `use_dpop_nonce` und einen gemeinsamen Nonce-Speicher für
alle Instanzen. Man kann ihn später nachrüsten, ohne dass sich der Vertrag mit den Clients ändert:
Sie müssen nur den Header zurückgeben.

### Grenze für den Produktivbetrieb

Für den Produktivbetrieb bleibt eine Grenze bei der Skalierung: Die Tabelle erhält für jede
angemeldete Anfrage eine neue Zeile. Abgelaufene Einträge löscht ein geplanter Job jede Minute. Ob
man die Tabelle nach Zeit partitioniert oder durch einen dauerhaften Schlüssel-Wert-Speicher
ersetzt, ist eine Entscheidung über die Infrastruktur. Sie ist bewusst zurückgestellt.

### Wie sicher die Schlüssel im Browser sind

Diese Demo läuft im Browser, und das begrenzt, wie gut ihre Schlüssel geschützt sind. Zwei Schlüssel
entstehen über die Web Crypto API und liegen in IndexedDB: der DPoP-Schlüssel und der Schlüssel des
Anmeldeverfahrens `device`. Mit `extractable=false` kann kein Skript den privaten Schlüssel
auslesen, auch kein fremdes. Das Schutzniveau eines **Secure Element** oder **TPM** (eigene
Sicherheitshardware, siehe [externes Glossar](glossar/externes-glossar.md)) erreicht das aber
nicht. Der Schlüssel liegt in den Daten des Browsers, nicht in eigener Hardware. Und die Prüfung per
PIN oder Biometrie simuliert die Demo nur.

Noch schwächer geschützt ist das Entsperrgeheimnis von KOBIL, einem externen Anbieter eines
Anmeldeverfahrens. Es liegt im `localStorage` des Browsers (`frontend/src/kobilUnlockSecret.ts`) und
ist dort für jedes Skript der Seite lesbar. Auf einem echten Gerät läge es im Schlüsselspeicher
hinter einer biometrischen Abfrage.

Das ist eine Demo, kein System für den Produktivbetrieb. Ein Produktivsystem bräuchte an dieser
Stelle eine native App mit einem Schlüsselspeicher in Hardware.

---

## 3) Bindung an die ChannelSession

Dieser Abschnitt beschreibt, wie der DPoP-Schlüssel mit den Sitzungen und dem Konto zusammenhängt.
Wichtig ist dabei die Trennung: Der Schlüssel zeigt, welches Gerät spricht. Welche Sitzung gemeint
ist und welchem Konto das Gerät gehört, steht jeweils an anderer Stelle.

### Kanal und Bindung

- Der Einstieg in den Kanal (`POST /orchestrator/api/v1/app/channels`) legt **immer** eine neue
  `ChannelSession` an. Er sucht nie über den `binding_key_ref` nach einer bestehenden. Eine bereits
  laufende Sitzung setzt der Client mit `GET /orchestrator/api/v1/channels/{channelSessionId}` fort.
  Dafür braucht er die `channelSessionId`, die er sich gemerkt hat ([05-api.md](05-api.md)).
- Bei jeder Anfrage an eine bestimmte `channelSessionId` prüft der Server, ob
  `ChannelSession.bindingKeyRef` zu dem Wert passt, den er aus dem aktuellen DPoP-Proof berechnet.
  Passt er nicht, antwortet der Server mit `403` (Bindung passt nicht, siehe
  [07-betrieb.md](07-betrieb.md)). Das gilt für `GET`, `PATCH`, `cancel` und `logout` gleichermaßen.
- Zu einem `binding_key_ref` können mit der Zeit mehrere `ChannelSession`s entstehen. Jeder Einstieg
  ohne bekannte `channelSessionId` legt eine neue an, zum Beispiel nach dem Abmelden oder wenn der
  Client seine gemerkte ID verloren hat. Eine feste 1:1-Beziehung gibt es nicht.
- Wechselt der Ablauf zwischen Registrierung und Anmeldung, bleibt der Kanal derselbe. Innerhalb
  EINER `ChannelSession` bleibt die `channelSessionId` gleich, nur der Vorgang dahinter wechselt.

### Geräteverknüpfung

- **Wozu es sie gibt.** Ein bereits registriertes Gerät soll nicht jedes Mal erneut die
  Identifizierung mit Freischaltcode (`ident-fsc`) durchlaufen müssen. Dafür gibt es die
  **Geräteverknüpfung** `DeviceAccountLink`. Sie merkt sich, zu welchem Konto ein Gerät gehört
  (`binding_key_ref -> accountId`, [02-domaenenmodell.md](02-domaenenmodell.md)).

  Die Geräteverknüpfung erkennt das Gerät nur wieder. Sie zählt bewusst nicht als Anmeldung, also
  auch nicht als Faktor Besitz. Davon zu unterscheiden ist die „Gerätebindung“: Mit diesem Wort
  meint diese Doku nur das Einrichten eines Anmeldeverfahrens, das an das Gerät gebunden ist
  (`device`, `kobil`).

  Der Datensatz ist unabhängig von der einzelnen `ChannelSession`. Der Einstieg in den Kanal liest
  ihn und trägt die `accountId` gleich in die neue `ChannelSession` ein. Es geht dann um eine
  Anmeldung statt um eine Registrierung.
- **Wann die Verknüpfung entsteht, ist bewusst gewählt.** Sie entsteht weder, wenn der Kanal
  `AUTHENTICATED` erreicht, noch bei `Identified`. Sie entsteht oder ändert sich, sobald
  `Completed.Enrolled` das erste Anmeldeverfahren anlegt ([Orchestrierung](04-orchestrierung.md)
  Abschnitt 8). Sie wartet also nicht, bis der Kanal sein eigenes Ziel-Niveau `requiredAcr`
  erreicht.

  Der Grund: Ein Kanal, der zum Beispiel `loa2` verlangt, ist nach einem einzigen `loa1`-Verfahren
  noch nicht fertig. Bricht die Sitzung danach ab, soll eine neue Anmeldung auf dem Gerät trotzdem
  gleich das vorhandene Verfahren anbieten. Sie soll nicht warten, bis `loa2` erreicht ist, denn
  dieses Ziel gilt nur für diesen einen Kanal.

  Nach einer bloßen Identifizierung (`Completed.Identified`) entsteht dagegen **keine**
  Verknüpfung. Ohne eingerichtetes Anmeldeverfahren hätte ein neuer Kanal nichts, womit er die
  Identität erneut zuverlässig prüfen kann. Allein der Besitz des DPoP-Schlüssels würde dann als
  Anmeldung gelten. Ein Kanal ohne Verknüpfung durchläuft nach einer abgebrochenen Registrierung
  deshalb bewusst wieder das ganze `ident-fsc`.
- **Will dieser Ablauf verknüpfen, und gibt es hier ein Gerät?** Ob eine Geräteverknüpfung
  entsteht, legt keine einzelne `Action` fest, also keine einzelne Datenänderung der Journey.
  Innerhalb eines Intents (des Anliegens, mit dem der Nutzer kommt) ändert sich die Antwort nie. Ein Schalter an
  jeder Action wäre deshalb eine Konstante des Intents, die man überall neu und womöglich falsch
  setzen könnte. Stattdessen gibt es zwei unabhängige Fragen. Jede wird dort beantwortet, wo die
  nötige Information liegt, und beide werden an genau einer Stelle zusammengeführt:
  - *Will dieser Ablauf verknüpfen?* Das sagt `AuthIntent.bindsDeviceImplicitly`. Nur
    `LOOKUP_LOGIN` will es nicht, weil genau diesen Intent Leute wählen, die nicht wiedererkannt
    werden wollen. Er fragt stattdessen nach (`OfferBinding` → `Perform(LinkDevice, …)`). Die Frage
    nach dem Kanal kann diese Eigenschaft nicht mitbeantworten: `REGISTER` läuft auf APP *und* WEB
    und wäre dann keine Konstante mehr.
  - *Gibt es hier ein Gerät?* Das ergibt sich aus dem Kanal, und der Executor prüft es an einer Stelle. Die
    Prüfung gilt auch für den ausdrücklichen Weg, denn auf einem Web-Kanal gibt es auch nach einer
    Zustimmung nichts zu verknüpfen.
- **Neu verknüpft wird nur mit Zustimmung. Das stellt der Aufbau sicher, nicht die Sorgfalt im
  Einzelfall.** Es gibt zwei Wege zu einer Geräteverknüpfung, und nur einer darf ein Gerät neu
  verknüpfen:
  - Der *implizite* Weg: Ein Ablauf war erfolgreich, und `AuthIntent.bindsDeviceImplicitly` ist
    gesetzt. Dieser Weg verknüpft nur, wenn der Schlüssel noch frei ist oder schon auf dasselbe
    Konto zeigt. Zeigt er auf ein fremdes Konto, geschieht gar nichts. Der implizite Weg verknüpft
    ein Gerät nie still neu und widerruft nie fremde Credentials (Zugangsdaten eines
    Anmeldeverfahrens). Wer einen Ablauf erfolgreich abschließt, ist damit einverstanden, von
    *diesem* Konto wiedererkannt zu werden. Er ist nicht damit einverstanden, das Gerät einem
    anderen Konto wegzunehmen.
  - Der *ausdrückliche* Weg (`Action.LinkDevice` nach einer Rückfrage) darf neu verknüpfen und
    widerruft dabei. Unterschieden wird also danach, **ob etwas zerstört wird**, nicht nach der
    Strategie.

  Ob unbemerkt neu verknüpft wird, hängt damit nicht davon ab, ob die jeweilige Strategie (die
  Regeln eines Intents) an diesen Fall gedacht hat. An welcher Stelle eine Strategie die Rückfrage
  stellt, hängt davon ab, wann bei ihr eine Identität feststeht. Beispiele: [`REGISTER`](journeys/register.md)
  und das [Experiment „Erst Anmeldeverfahren einrichten“](journeys/register-enroll-first.md).
- **`DeviceAccountLink` ist immer 1:1.** Ein `binding_key_ref` zeigt zu jedem Zeitpunkt auf
  höchstens ein Konto. Was passiert, wenn sich auf einem bereits verknüpften Gerät jemand anderes
  neu ausweist (`intent=register`, „Zweitaccount“, siehe [`REGISTER`](journeys/register.md))?
  - Die Verknüpfung wird nicht unbemerkt überschrieben. Sie wird erst nach einer Rückfrage
    übertragen (`RegisterState.ConfirmDeviceRebind`).
  - Stimmt der Nutzer zu, wird zusätzlich **jedes** Credential des bisherigen Kontos deaktiviert, das
    an diesen Schlüssel gebunden ist. Sein Datensatz im Tool-Modul wird gelöscht
    (`AccountDeletionService.revokeMethod`). Heute sind das `device` und `kobil`.
    `JourneyActionExecutor` findet sie über `Tool.boundToCallerKey`, nie über eine Liste von
    `toolId`s.
  - Lehnt der Nutzer ab, bricht die Journey ab, und die alte Verknüpfung bleibt bestehen.

  Unabhängig davon gilt ohnehin bei der Auswahl der Kandidaten: Ein gerätegebundenes Credential
  (`Tool.usableByCaller`, [03-tool-architektur.md](03-tool-architektur.md)) ist nur nutzbar, solange
  `DeviceAccountLink` für seinen Schlüssel noch auf genau das Konto zeigt, dem es gehört.

### Drei Schlüssel

Im Zusammenhang mit dem Gerät gibt es **drei Schlüssel mit drei Aufgaben, die man nicht verwechseln
darf:**

1. Der **DPoP-Schlüssel des Kanals** bindet die Anfragen an diesen Kanal. An dem daraus berechneten
   Wert (`bindingKeyRef`) verweisen `DeviceAccountLink` und jedes gerätegebundene Credential
   (`AuthMethodView.boundKeyRef`).
2. Das **Credential von `auth_device`** ist ein eigenes, nicht exportierbares Schlüsselpaar. Damit
   signiert der Client den Geräte-Proof, der zeigt, dass er das Gerät besitzt.
3. Das **Entsperrgeheimnis von KOBIL** ist kein Schlüssel im kryptografischen Sinn, sondern ein
   Geheimnis. Die App verwahrt es: auf einem echten Gerät hinter Biometrie, in dieser Demo im
   `localStorage` (siehe Abschnitt 2). Sie legt es vor, damit das Backend die KOBIL-PIN freigibt
   ([Verfahren `kobil`](verfahren/kobil.md)).

Nur der erste Schlüssel bindet den Kanal. Die beiden anderen sind Credentials. Beim dritten liegt
die eigentliche Bestätigung des Geräts nicht bei uns, sondern beim Anbieter. Dessen eigene
Gerätekennung liefert `GET .../app/channels/device-link` in `boundCredentials` mit, neben dem
Schlüssel des `device`-Credentials.

### Aufräumen im Client

- **Wird das Gerät neu verknüpft, muss auch der Client aufräumen.** Auf dem Server widerruft
  `JourneyActionExecutor.linkDeviceTo` jedes Credential des bisherigen Kontos, das an den Schlüssel
  gebunden ist. Für `device` reicht das: Der Schlüssel im Browser wird dadurch wertlos, und jeder
  Versuch damit scheitert. Für `kobil` reicht es nicht. Dort liegt im Browser ein **Geheimnis**, das
  die PIN freigeben würde. Der Client löscht es deshalb, sobald `device-link` kein
  `kobil`-Credential mehr aufführt (`tools/kobil/localData.ts`). So bleibt kein Geheimnis im Browser
  liegen, das nichts mehr freigibt.

---

## 4) Wo die Bindung endet

Die DPoP-Bindung schützt die Aufrufe, die die App über den App-Kanal an den Orchestrator schickt.
Das gilt bis einschließlich `GET …/token`. Die Keycloak-Tokens, die dieser Endpunkt ausgibt, sind
dagegen **nicht** an den DPoP-Schlüssel gebunden. Dasselbe gilt für die Tokens, die der Web-Kanal
direkt von Keycloak bekommt. Diese Tokens enthalten kein `cnf.jkt` (das Feld, in dem ein gebundenes
Token den Fingerabdruck seines Schlüssels nennt). Ein Resource-Server, also ein Dienst, der die
Tokens annimmt, verlangt zu ihnen keinen DPoP-Proof. Wer ein solches AccessToken unbefugt erlangt, kann es
deshalb bis zu seinem Ablauf bei jedem Resource-Server verwenden, der Keycloak-Tokens annimmt.

Das ist entschieden, nicht übersehen ([ADR-9](adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md)):

- **Was gebunden bleibt:** die Sitzung selbst. Ein neues Token gibt es nur über `GET …/token` mit
  gültigem DPoP-Proof. Das RefreshToken verlässt das Backend nie.
- **Was den Schaden begrenzt:** die kurze Laufzeit des AccessTokens und das Ende der
  Keycloak-Sitzung beim Abmelden oder Ablauf (`JourneyService.endSession`).
- **Was eine Bindung bräuchte:** Keycloak müsste DPoP für den Grant (die Art, wie das Token
  angefordert wird) unterstützen und in jedes Token `cnf.jkt` mit dem Thumbprint des Kanals
  schreiben. Außerdem müssten die Resource-Server den Proof prüfen. Das betrifft jeden Dienst, der
  die Tokens annimmt, nicht nur dieses Projekt.
