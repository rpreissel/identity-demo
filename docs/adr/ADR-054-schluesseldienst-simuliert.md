# ADR-54: Ein Schlüsseldienst hinter allen Schlüsseln, in der Demo simuliert

**Status:** umgesetzt 2026-10-06 (Issue `DPoP-demo-ugpz`). Löst das Zielbild aus
[ADR-52](ADR-052-umschlagverschluesselung-des-claim-logs.md) ein und ersetzt den Demo-Kompromiss aus
[ADR-22](ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md) und
[ADR-25](ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md) für den Signaturschlüssel
des Orchestrators.

**Entscheidung.** Alle Schlüssel, die etwas einpacken oder signieren, liegen in einem
Schlüsseldienst (KMS), nicht in der Konfiguration und nicht in unseren Tabellen. Der Dienst gibt
Schlüsselmaterial nie heraus. Er bietet drei Operationen: einpacken, auspacken, signieren, dazu
Rotation und das Zurückziehen alter Versionen. Jede Antwort trägt die Version des benutzten
Schlüssels, und jede Zeile, die etwas Eingepacktes oder Signiertes hält, speichert diese Version.

Zwei Schlüssel liegen dort:

- **`identity-kek`** (AES-256): der Umschlagschlüssel. Er packt den Hauptschlüssel jedes Kontos
  (ADR-52) und die Tagesschlüssel der Arbeitsdaten (ADR-53) ein. Die Konfiguration
  `identity.secrets.master-kek` entfällt.
- **Ein ECDSA-P-256-Schlüssel je Signaturzweck**: `keycloak-response` für die Antwortsignatur
  gegenüber Keycloak (ADR-7) und `keycloak-client-auth:<Client>` je Client für `private_key_jwt`
  (ADR-9, ADR-25). Der Orchestrator sendet die Signatureingabe und erhält die Signatur. Die Tabelle
  `orchestrator.node_signing_key` ist gelöscht (Migration V42). Das JWKS nennt jede Version, die
  der Dienst noch verifiziert, mit `kid = <Zweck>-v<Version>`. Keycloak findet nach einer Rotation
  den neuen Schlüssel über die `kid`, auf beiden Seiten ohne Neustart.

**Der Port.** `tool_api.kms.KeyService` ist die Schnittstelle, die `account` und `orchestrator`
sehen: benannte Schlüssel mit Versionen, `encrypt` und `decrypt` mit einem Kontext, `sign`,
`publicKey`, `ensureKey`, `findKey`, `latestVersion` und die Angabe, ob der Dienst simuliert ist.
Der Kontext (bei Vault `context`) bindet jedes Chiffrat an seinen Zweck: `account-master-key` und
`orchestrator-data-key` lassen sich nicht vertauschen. Die Kernmodule kennen die Simulation nicht,
so wie sie das Personenverzeichnis nur über Ports erreichen.

**Die Demo simuliert den Dienst.** Das Modul `kms` (`simulation/kms`, Fremdsystem wie `kobil` und
`nect`) implementiert den Port in `KmsTransit`, hält die Schlüssel in einem eigenen Schema `kms`
und bietet dazu `rotate` und `retireBelow`. Im Demomodus zeigt `/mock-kms/keys` die Schlüssel und ihre
Versionen; `POST /mock-kms/keys/{name}/rotation` rotiert, `POST .../retirement?below=N` zieht
Versionen zurück und vernichtet ihr Material. Ein Tester sieht so, dass neue Konten die neue
Version tragen, alte weiter lesbar bleiben, und was ein zurückgezogener Schlüssel bedeutet.

Die Simulation ist ein Fremdsystem, kein Sicherheitsgewinn: Ihr Material liegt in derselben
Datenbank wie die Daten. `ProductionModeCheck` verweigert deshalb außerhalb des Demomodus den
Start, solange der Schlüsseldienst die Simulation ist, so wie er den Mock-Token-Provider ablehnt.

**Wie unser Code den Dienst sieht.**

- `MasterKeyWrapper` (Modul `account`) hat als Adapter `KmsKekWrapper` über den Port.
  `DataKeyWrapping` und `AccountDataCipher` im Wurzelpaket reichen das Ein- und Auspacken an den
  Orchestrator weiter (ADR-53). Ein echter Dienst ist eine weitere Bean, die `KeyService`
  implementiert; `KmsKekWrapper`, `KmsNodeKeys` und die Tabellen bleiben, wie sie sind.
- Schlüssel entstehen in eigenen Transaktionen (`ensureKey`), beim Start für jeden bekannten Zweck.
  Legen zwei Instanzen denselben Schlüssel gleichzeitig an, übernimmt die zweite den der ersten.
  In der Transaktion eines Aufrufers darf kein Schlüssel entstehen: Sie hielte die Sperre, während
  sie auf Keycloak wartet, und jede andere Anfrage liefe hinein.
- `KmsNodeKeys` (Orchestrator) liefert einen Nimbus-`JWSSigner`, der im Dienst signiert, und die
  öffentlichen Schlüssel aller gültigen Versionen für das JWKS. Der Dienst antwortet DER, der JWS
  braucht `R || S`; die Umcodierung ist Sache des Signierers.
- `ClaimCrypto` hält ausgepackte Hauptschlüssel je Instanz im Speicher
  (`identity.secrets.master-key-cache-ttl`, Vorgabe fünf Minuten, höchstens
  `master-key-cache-size` Einträge); ein gelöschtes Konto verlässt den Speicher sofort. Das ist der
  Zwischenspeicher, den ADR-52 für einen Dienst außerhalb des Prozesses verlangt: Die Rate zum
  Dienst folgt der Zahl der aktiven Konten, nicht der Lesevorgänge. Eine im Dienst zurückgezogene
  Version wirkt auf einer Instanz deshalb erst nach Ablauf dieser Frist. Tagesschlüssel hält
  `RetentionClassKeys` ohnehin.
- `ProductionModeCheck` prüft, dass jede in `account.master_key` und `orchestrator.data_key`
  gespeicherte KEK-Version im Dienst noch auspackt.

**Was ein echter Dienst ändert.** Nichts an den Tabellen und nichts an den Versionen. `KeyService`
ist die Schnittstelle, die ein Adapter für Vault Transit, ein Cloud-KMS oder ein HSM implementieren
muss: `encrypt` und `decrypt` mit Version und Kontext, `sign` mit Version, die Liste der gültigen
Versionen und die öffentlichen Schlüssel. Vault Transit bietet genau das (`transit/encrypt`, `transit/decrypt`,
`transit/sign` mit `marshaling_algorithm=jws`, `transit/keys/<name>`). Der Adapter braucht
Zeitlimits, einen Leistungsschalter und die Zugangsdaten des Dienstes, sonst nichts Neues.

**Alternativen.**

- *Den Umschlagschlüssel in der Konfiguration lassen und nur das Signieren in den Dienst legen.*
  Zwei Orte für Schlüssel, zwei Wechselverfahren. Verworfen; ADR-52 hatte die Konfiguration
  ausdrücklich als Übergang beschrieben.
- *Den Signaturschlüssel nur einpacken statt im Dienst zu signieren.* Der private Schlüssel wäre
  beim Signieren im Prozess, also wie heute, nur mit einem Umschlag drum. Ein Dienst, der signiert,
  gibt den Schlüssel nie heraus. Verworfen.
- *Einen echten Vault im Compose-Stack.* Realistischer, aber ein weiterer Container, ein Token, eine
  Einrichtung, und Tests bräuchten den Container. Die Simulation zeigt dasselbe Verhalten mit
  denselben Begriffen und läuft in jedem Test. Zurückgestellt, bis ein Betreiber den Dienst nennt.

**Folgen und Kosten.**

- Beim ersten Start legt die Simulation ihre Schlüssel an; bestehende Konten aus einer Datenbank
  vor dieser Entscheidung lassen sich nicht mehr auspacken. Ihre Zeilen nennen die Version ohne
  das Präfix `v`, der Adapter weist sie mit einem klaren Fehler ab, und `ProductionModeCheck`
  meldet sie als verwaiste Version. Die Demo-Datenbank wird neu aufgebaut.
  Keycloak-seitig ändert sich nichts: Es lädt die JWKS beim ersten unbekannten `kid` neu.
- Das Signieren ist ein Aufruf in die Simulation je Antwort an Keycloak und je Client-Assertion.
  Mit einem echten Dienst ist das ein Netzaufruf je Keycloak-Anfrage; die Antwortsignatur ist der
  häufigste Fall (ADR-7). Das ist der Preis dafür, dass der Schlüssel den Dienst nie verlässt.
- Zieht ein Tester im Demomodus eine Version zurück, die noch Konten trägt, sind deren Angaben
  unlesbar, und `ProductionModeCheck` warnt beim nächsten Start. Das ist gewollt: Es zeigt, was
  ein verlorener Schlüssel bedeutet.
