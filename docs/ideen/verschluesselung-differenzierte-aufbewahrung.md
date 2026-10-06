# Idee: Umschlagverschlüsselung für unterschiedliche Aufbewahrung und Widerrufe

**Worum es geht.** Zu einem Konto speichert das System viele Angaben über den Kontoinhaber, etwa
seine E-Mail-Adresse oder die Daten aus dem Online-Ausweis (eID-Daten). Solche Angaben heißen im
Code Claims. Für einzelne Angaben können unterschiedliche Regeln gelten. Ein Beispiel: Die
Bestätigung einer E-Mail-Adresse wird zurückgenommen, oder eID-Daten dürfen höchstens ein Jahr
aufbewahrt werden. Die Frage dieses Dokuments ist: Gibt es eine Technik, mit der man je Konto
**einen** Schlüssel hat und trotzdem einzelne Angaben gezielt und dauerhaft unlesbar machen kann?

**Warum das wichtig ist.** Heute stehen alle personenbezogenen Daten im Klartext in der Datenbank.
Eine zurückgenommene Angabe bleibt sogar für immer lesbar gespeichert (siehe „Heutiger Stand“
unten). Wer Aufbewahrungsfristen und Widerrufe ernst nimmt, braucht einen Weg, einzelne Angaben
wirklich zu entfernen, ohne die übrigen Daten des Kontos anzufassen.

> **Stand: offen, nicht entschieden** (Issue `DPoP-demo-bo1w`). Das ist ein Vorschlag zur
> Diskussion, keine Freigabe zur Umsetzung.
>
> Er betrifft [02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 6 (`AccountClaim`,
> `AccountAnchor`, `AccountRetraction`) und die Regeln zur Aufbewahrung in
> [07-betrieb.md](../07-betrieb.md).
>
> Im Projekt werden gespeicherte Daten derzeit **nicht** verschlüsselt. Alle Spalten mit
> personenbezogenen Daten stehen im Klartext (`db/migration/<modul>/`). Der vorhandene
> kryptografische Code beschränkt sich auf drei Dinge:
>
> - das Hashen von Passwörtern (seit 2026-09 mit Argon2id, vorher mit PBKDF2),
> - HMAC für TAN, E-Mail-Code, QR-Bestätigungscode, Zähler und Suchschlüssel im
>   Änderungsprotokoll,
> - das Erzeugen von EC-Schlüsseln für die Assertions von Keycloak.
>
> Eine Regel „eID-Daten höchstens ein Jahr“ steht nirgends in der Doku. Sie dient hier nur als
> Beispiel.

## Kontext

Die Frage lautet: Gibt es eine Technik, mit der man je Konto **einen** Schlüssel hat und trotzdem
einzelne Daten mit unterschiedlichen Aufbewahrungsfristen oder Regeln für Widerrufe schützen kann?
Zwei konkrete Beispiele: Die Bestätigung einer E-Mail-Adresse wird zurückgenommen. eID-Daten dürfen
höchstens ein Jahr aufbewahrt werden. Wo und wie der Schlüssel des Kontos selbst aufbewahrt wird,
gehört ausdrücklich **nicht** zur Frage. Abschnitt 6 skizziert dafür trotzdem ein Zielbild, weil
es sonst nicht ohne Weiteres zu einem Vorschlag passt, der große Mengen tragen soll.

Zum Verständnis drei Begriffe aus dem Modell der Konten (mehr im [Glossar](../glossar/glossar.md)):

- Eine **Angabe** (Claim, `AccountClaim`) ist eine Information über den Kontoinhaber. Angaben
  werden nur ergänzt, nie überschrieben.
- Ein **Anker** (`AccountAnchor`) ist eine Angabe, über die sich ein Konto eindeutig wiederfinden
  lässt, etwa die E-Mail-Adresse.
- Ein **Widerruf** (`AccountRetraction`) nimmt eine Angabe zurück. Die alte Angabe wird dabei
  nicht gelöscht. Der Widerruf ist eine eigene Zeile.

**Heutiger Stand (geprüft):**

- Gespeicherte Daten werden derzeit **nicht** verschlüsselt.
  - Alle Spalten mit personenbezogenen Daten (`account.claim.claim_value`,
    `account.anchor.normalized_value`, `personenverzeichnis.person.*`) sind `VARCHAR`
    beziehungsweise `DATE` im Klartext (`db/migration/<modul>/`).
  - Die Arbeitsdaten der Tools, etwa die gelesenen eID-Daten von `ident-eid`, liegen als JSON im
    Klartext in `orchestrator.tool_session.data` (ADR-49). Für diese Arbeitsdaten wäre
    `ToolSessionDataCodec` die eine Stelle, an der eine Verschlüsselung ansetzen würde.
  - Dazu kommt das simulierte Nect: `nect.ident_case.result` hält die ausgelesenen Ausweisdaten
    eines Vorgangs als JSON im Klartext.
  - Kryptografisch gibt es nur Argon2id (Hash des Passworts), HMAC (TAN, E-Mail-Code,
    QR-Bestätigungscode, Zähler, Suchschlüssel im Änderungsprotokoll) und das Erzeugen von
    EC-Schlüsseln für die Assertions an Keycloak.
  - `NodeSigningKey.privateKeyJwk` liegt ausdrücklich im Klartext (Rahmen der Demo, ADR-22,
    `orchestrator/kc/NodeSigningKey.kt`).
- Eine Regel „eID-Daten höchstens ein Jahr“ steht nirgends in der Doku. Sie ist ein angenommenes
  Beispiel. Die Arbeitsdaten von `ident-eid` fallen heute unter die allgemeine Frist von
  24 Stunden für `orchestrator.tool_session` ([07-betrieb.md](../07-betrieb.md) Abschnitt 3).
- Ein Widerruf wirkt heute nur logisch. `AccountRetraction` macht Zeilen in `AccountClaim` anhand
  der Zeitpunkte ungültig. Was gilt, ergibt sich als „Angaben minus Widerrufe“
  ([02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 6). Tatsächlich gelöscht wird aber
  nur der `AccountAnchor` (ADR-12). Die zugehörige Zeile in `AccountClaim` bleibt für immer im
  Klartext liegen. Die Frist dafür ist „noch nicht entschieden“
  ([07-betrieb.md](../07-betrieb.md) Abschnitt 3).
- Gelöscht wird heute immer hart, also mit SQL-`DELETE`. Das erledigen geplante `*RetentionJob`s
  auf Spalten mit Index, die den Stichtag enthalten ([07-betrieb.md](../07-betrieb.md)
  Abschnitt 3). Ein Löschen, das Zeilen nur als gelöscht markiert, gibt es nirgends.

Ziel dieses Dokuments ist es, das Konzept **Umschlagverschlüsselung mit kryptografischem Löschen**
zu erklären. Es überträgt das Konzept genau auf das bestehende Modell aus Claims, Ankern und
Widerrufen und spielt zwei konkrete Fälle durch.

---

## 1) Die Technik: Umschlagverschlüsselung und kryptografisches Löschen

Die Daten werden nicht direkt mit einem einzigen, langlebigen Schlüssel des Kontos verschlüsselt.
Stattdessen bekommt jede Einheit von Daten ihren **eigenen Datenschlüssel** (Data Encryption Key,
DEK). Dieser Datenschlüssel wird seinerseits mit dem **Hauptschlüssel des Kontos** (Account Master
Key, AMK) verschlüsselt. Man sagt dazu auch: Der Datenschlüssel wird eingepackt. Einen
Hauptschlüssel gibt es genau einmal je Konto. Im Englischen heißt das Verfahren *Envelope
Encryption*.

**Warum ein einziger Schlüssel je Konto nicht reicht.** Mit einem symmetrischen Schlüssel kann man
genau die Daten lesen, die man auch durch sein Löschen unlesbar macht. Teilen sich der EMAIL-Claim
und die eID-Claims denselben Schlüssel, kann man die verschlüsselte E-Mail-Adresse nicht dauerhaft
unlesbar machen, ohne auch die eID-Daten zu zerstören. Die Alternative wäre, vorher alles andere
neu zu verschlüsseln. Dafür müsste man wieder jede andere Zeile finden und ändern.

Mit einem Datenschlüssel je Einheit löst sich das. Löscht man **einen** kleinen Datenschlüssel
(etwa 32 Byte), wird genau der damit verschlüsselte Inhalt dauerhaft unlesbar. Daraus folgt:

- Andere Zeilen bleiben unberührt.
- Nichts muss neu verschlüsselt werden.
- Es braucht kein `DELETE` auf der eigentlichen Zeile. Diese Zeile ist möglicherweise groß,
  indiziert und über Fremdschlüssel verknüpft. Ein `DELETE` müsste sie finden und auch aus
  Sicherungen und dem Transaktionslog (WAL) entfernen.

Das ist das übliche Muster „kryptografisches Löschen“ (*Crypto-Shredding* oder *Crypto Erasure*).
Statt der Daten löscht man einen winzigen Schlüssel.

---

## 2) Wie fein: wofür es je einen Datenschlüssel gibt

Es gibt drei Möglichkeiten, wie fein man die Datenschlüssel schneidet. Hier sind sie am
bestehenden Modell bewertet ([02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 6):

- **(a) Ein Datenschlüssel je `AttributeType`**, zum Beispiel ein Schlüssel für alle EMAIL-Claims
  eines Kontos. Das ist zu grob.

  `account.claim` wird nur ergänzt. Ein Konto hat deshalb oft mehrere historische Zeilen desselben
  Typs, etwa eine alte, eine korrigierte, eine zurückgenommene und eine neu bestätigte
  E-Mail-Adresse. Ein Widerruf entkräftet nur Claims, die *vor* ihm liegen. Ein danach neu
  bestätigter Wert gilt wieder (ADR-12). Ein gemeinsamer Datenschlüssel würde beim Löschen auch
  gültige Claims desselben Typs zerstören. **Als Hauptweg verworfen.**
- **(b) Ein Datenschlüssel je Zeile in `AccountClaim`.** Das passt zur Einheit von
  `AccountRetraction`, ist aber unnötig fein.

  Ein Lauf von `ident-eid` schreibt sieben Zeilen in einem Aufruf von `recordClaims`:
  `EID_RESTRICTED_ID`, `FAMILY_NAME`, `GIVEN_NAMES`, `BIRTH_DATE`, `STREET_ADDRESS`,
  `POSTAL_CODE` und `LOCALITY`. Das wären sieben Datenschlüssel für einen einzigen fachlichen
  Vorgang. **Zugunsten von (b') verworfen.**
- **(b') Ein Datenschlüssel je Gruppe von Claims**, wobei eine Gruppe genau eine Transaktion von
  `recordClaims` ist.

  Dafür gibt es ein neues, schmales Feld `claim_batch_id` (UUID). Es wird einmal je Aufruf von
  `recordClaims` erzeugt und an alle Zeilen gehängt, die in diesem Aufruf gespeichert werden.

  Vorhandene Felder eignen sich dafür nicht:
  - `AccountClaim.authMethodId` eignet sich NICHT. Bei Claims aus einer Identifizierung
    (`ident-eid`) ist es laut Kommentar im Code immer `null` (`AccountClaim.kt:46-49`, „claims from
    identification tools produce no credential“).
  - Auch `claimSource` allein reicht nicht. `ClaimSource(toolId.value)` ist für jeden Lauf von
    `ident-eid` gleich (`"ident-eid"`). Eine erneute Identifizierung Jahre später würde damit
    fälschlich mit dem ersten Lauf zusammengeworfen.

  Ein Datenschlüssel je Gruppe fasst genau zusammen, was fachlich ein Vorgang ist. Bei der eID
  sind das etwa siebenmal weniger Schlüssel. Trotzdem behält jedes Auslesen der Karte seine eigene
  Frist von einem Jahr.

  Der Preis: Muss ein einzelnes Attribut einer Gruppe vorzeitig und für sich allein ungültig
  werden, müssen die übrigen Zeilen der Gruppe neu verschlüsselt werden. Im bestehenden Modell der
  Widerrufe ist das aber ein Randfall. Das Ersetzen eines Ankers (ADR-12) betrifft nur die lokal
  verankerten Attribute (`PERSON_ID`, `MEMBER_NUMBER`, `EID_RESTRICTED_ID`, `EMAIL`). Die übrigen
  eID-Felder gehören dem Personenverzeichnis und stehen nur als Historie im Log. Sie werden nicht
  einzeln widerrufen. **Als Hauptweg für `account.claim` empfohlen.**
- **(c) Ein Datenschlüssel je Aufbewahrungsklasse.** Eine Aufbewahrungsklasse ist ein Wert aus
  einem kleinen Enum, zum Beispiel `EID_RESTRICTED` oder `STANDARD_CLAIM`. Das ist gröber und
  billiger zu verwalten. Sobald aber eine Zeile der Klasse vorzeitig gelöscht werden muss, braucht
  es regelmäßig eine Neuverschlüsselung.

  **Als pragmatischer Weg für die kurzlebigen Arbeitsdaten der Tools mit personenbezogenen Daten
  empfohlen.** Gemeint sind etwa die Arbeitsdaten von `ident-eid` in
  `orchestrator.tool_session.data`. Der Ansatzpunkt dort ist `ToolSessionDataCodec`. In diesen
  Daten fällt ohnehin alles innerhalb von etwa 24 Stunden weg. Selbst ein Schlüssel je Gruppe wäre
  dort unnötig viel Aufwand.

**Warum an die bestehende Einteilung anknüpfen, statt eine neue zu erfinden:**
[02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 6 und ADR-12 haben schon entschieden,
was die Einheit eines Fakts ist. Die Gruppe von Claims ist keine neue Einteilung. Sie macht nur
sichtbar, welche Zeilen `recordClaims` ohnehin in einer Transaktion gemeinsam schreibt.

**`account.anchor` bleibt unverschlüsselt, und zwar bewusst, nicht aus Nachlässigkeit**
(Abschnitt 3a).

---

## 3a) Suchen: `AccountAnchor` bleibt die unverschlüsselte, abgeleitete Tabelle, die sie schon ist

Umschlagverschlüsselung löst die Vertraulichkeit, nicht die Suche. Verschlüsselte Werte lassen sich
nicht über einen Index auf Gleichheit finden. Normalerweise wäre das ein eigenes Problem. Man
bräuchte dafür einen „Blind Index“, also einen durchsuchbaren Hashwert, der mit einem gemeinsamen
geheimen Zusatzwert (Pepper) gebildet wird. Hier lohnt sich aber zuerst ein Blick darauf, wie die
Aufgaben im Code tatsächlich verteilt sind:

- **`AccountAnchor.resolveByAnchor`** (in `AccountService`, Schnittstelle `AccountDirectory`) liest
  **ausschließlich** über `AccountAnchorRepository`, nie aus dem Claim-Log.
  - `AccountAnchor` ist laut Doku schon die abgeleitete Tabelle, mit der Konten gefunden werden und
    die die Eindeutigkeit sichert ([02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 6).
    Im Aufbau ist sie getrennt vom historischen Log in `AccountClaim`.
  - Sie enthält nur vier Attributtypen (`PERSON_ID`, `MEMBER_NUMBER`, `EID_RESTRICTED_ID`,
    `EMAIL`). Das sind Kennungen, Pseudonyme und eine E-Mail-Adresse. Die eigentlich sensiblen
    Inhalte der eID (Name, Geburtsdatum, Adresse) enthält sie nicht. Diese liegen ausschließlich im
    Claim-Log.
- **Für `AccountAnchor` ist der Widerruf schon gelöst, ganz ohne Kryptografie.** Nach ADR-12 wird
  die Zeile des Ankers beim Widerruf tatsächlich gelöscht. Das ist stärker als kryptografisches
  Löschen: Es bleibt kein verschlüsselter Inhalt liegen, der mit einem später gestohlenen Schlüssel
  wieder lesbar würde. Die Zeile ist einfach weg. Für den Anker war der ganze Aufwand der
  Verschlüsselung also nie nötig. Nötig ist er für das **Claim-Log**, das beim Widerruf gerade
  *nicht* gelöscht wird ([07-betrieb.md](../07-betrieb.md) Abschnitt 3).

**Die Folge:** `AccountAnchor` bleibt im Klartext, ohne Blind Index und ohne zweiten gemeinsamen
Pepper. Die Suche ist damit kein zusätzlicher Baustein, sondern gar kein Problem mehr. Die
bestehende Trennung erfüllt die Anforderung schon:

- der Anker: durchsuchbar, schmal und schon hart löschbar,
- das Claim-Log: historisch, breit und bisher ohne Frist aufbewahrt.

**Offene Frage, aber keine Frage der Kryptografie:** Gilt „eID-Daten höchstens ein Jahr“ auch für
die Zeile des Ankers `EID_RESTRICTED_ID` selbst? Das ist das Merkmal, an dem ein Interessent
wiedererkannt wird. Falls ja, genügt ein weiterer `*RetentionJob`, der diese Zeile nach einem Jahr
hart löscht. Ein Nutzer, der vor mehr als einem Jahr mit der eID identifiziert wurde, würde dann
aber auf einem neuen Gerät nicht mehr ohne erneute Prüfung der eID wiedererkannt. Das ist eine
Produktentscheidung, die hier nicht getroffen wird.

**Die Suche in `recordClaims`**, die bereits geltende Angaben erkennt (`findEstablished`), ist
davon nicht betroffen. Dort ist das Konto **schon bekannt**, denn es wird als Parameter übergeben.
Man muss also nicht erst entschlüsseln, um das Konto zu finden. Der Vergleich kann im
Anwendungscode geschehen, nachdem die wenigen bestehenden Claims dieses Kontos entschlüsselt
wurden.

---

## 3) Warum es trotzdem „ein Schlüssel je Konto“ ist

Aus Sicht des Kontos gibt es genau **einen** Schlüssel: den Hauptschlüssel des Kontos. Wo und wie
er selbst aufbewahrt wird, ist nicht Teil dieser Frage. Abschnitt 6 skizziert dafür ein Zielbild
(eingepackt mit einem Schlüssel aus einem KMS oder HSM) und sagt, was die Demo stattdessen tut.
Entschieden ist das nicht.

Der Hauptschlüssel hat nur eine Aufgabe: jeden Datenschlüssel einzupacken, also zu verschlüsseln.
Falls das Konzept umgesetzt wird, sieht das so aus:

- `account.claim` bekommt je Gruppe einen eingepackten Datenschlüssel. Er liegt in der Tabelle
  `claim_batch_key` (Abschnitt 5). `claim_value` und `normalized_value` werden verschlüsselt
  gespeichert.
- `account.anchor` bleibt unverändert im Klartext (Abschnitt 3a).
- Für den Weg über Aufbewahrungsklassen gibt es eine neue kleine Tabelle
  `account.retention_class_key` (`account_id`, `retention_class`, `wrapped_dek`, `nonce`).

Es gibt also genau ein Geheimnis, das einem Konto gehört: den Hauptschlüssel. Die Schicht der
Datenschlüssel ist ein internes Detail des Verschlüsselungsdienstes. Das Ein- und Auspacken sowie
das Ver- und Entschlüsseln erledigt reines `javax.crypto` (AES-256-GCM). Das ist dieselbe
Paketfamilie, die das Projekt schon für HMAC nutzt. Es braucht also keine neue Abhängigkeit.

---

## 4) Zwei durchgespielte Fälle

**Fall 1: Die Bestätigung einer E-Mail-Adresse wird zurückgenommen.**

Heute schreibt das System eine `AccountRetraction` und löscht den `AccountAnchor` für EMAIL
(ADR-12). Die Zeilen in `AccountClaim` für EMAIL bleiben aber ohne Frist im Klartext liegen
([07-betrieb.md](../07-betrieb.md) Abschnitt 3, Frist „noch nicht entschieden“).

Ein Aufruf von `recordClaims` für EMAIL ist meist eine Gruppe mit nur einer Zeile. Die Einteilung in
Gruppen kostet hier also nichts. Mit einem Datenschlüssel je Gruppe löscht derselbe Widerruf
zusätzlich den Datenschlüssel dieser Gruppe. Das geschieht sofort oder nach einer Frist für Audit
und Karenz.

Die Zeile selbst kann erhalten bleiben. `attribute_type`, `claim_source`, `established_acr` und die
Zeitstempel bleiben als unverschlüsselte Angaben für das Audit stehen. Nur `claim_value` und
`normalized_value` werden dauerhaft unlesbar. Das schließt genau die Lücke, die ADR-12 offen lässt.
Man muss dafür nicht auf eine Entscheidung über das massenhafte Löschen von Zeilen warten.

**Fall 2: eID-Daten, höchstens ein Jahr.**

Ein Lauf von `ident-eid` schreibt sieben Claims mit gemeinsamer `claim_batch_id` in einem Aufruf
von `recordClaims`. Hier reicht es **nicht**, nur den Datenschlüssel zu löschen. Der Grund:

- Was aktuell gilt, berechnet das bestehende Modell als „Angaben minus Widerrufe“
  ([02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 6). Es nutzt dafür allein die Zeilen
  in `AccountRetraction`. Ob sich ein Wert noch lesen lässt, spielt dabei keine Rolle.
- Ohne Widerruf hielte die Logik, die gültige Werte bestimmt, die Claims also weiter für gültig,
  obwohl sie längst unlesbar sind. Zu dieser Logik gehören `findEstablished`,
  `AccountProfile.establishedClaims` und die Prüfung auf doppelte Angaben in `recordClaims`.
- Logischer und kryptografischer Zustand würden dann unbemerkt voneinander abweichen. An Stellen,
  die davon ausgehen, dass ein Wert vorhanden ist, könnte das Entschlüsseln fehlschlagen.

Der `RetentionJob` muss deshalb **in einer Transaktion** zwei Dinge tun:

- (a) für jeden betroffenen Attributtyp der Gruppe eine Zeile in `AccountRetraction` schreiben,
- (b) die Zeile in `claim_batch_key` löschen.

Für (a) fehlt derzeit ein passender Wert in `RetractionSource`. Die bestehenden drei Werte sind
`ACCOUNT_MANAGEMENT`, `PERSON_DIRECTORY` und `OPERATOR`. Letzterer steht laut Kommentar im Code
ausdrücklich für „a human operator, with a reason“. Keiner der drei deckt einen automatischen
Widerruf nach Ablauf einer Frist ab. Ein vierter Wert (`RETENTION_POLICY`) wäre nötig. Sonst würde
ein geplanter Job bei der Frage „wer widerruft“ fälschlich als Eingriff eines Menschen erscheinen.

Eine spätere erneute Identifizierung (neue Karte, neue `claim_batch_id`) bekommt ihre eigene,
unabhängige Frist. Im Kern ist das derselbe Aufbau, der schon im Betrieb läuft (`*RetentionJob`,
[07-betrieb.md](../07-betrieb.md) Abschnitt 3). Neu ist nur, dass der Job hier auch die Widerrufe
einträgt. Bei einem vom Nutzer ausgelösten Widerruf (Fall 1 oben) schreibt sie der auslösende
Vorgang selbst.

---

## 5) Was neu wäre und was bleibt

**Neu** (falls es umgesetzt wird):

- Ein neues Feld `claim_batch_id` (UUID) in `account.claim`, einmal je Aufruf von `recordClaims`
  erzeugt. Für alte Daten darf es leer sein.
- Eine Tabelle `account.claim_batch_key` (`account_id`, `claim_batch_id`, `wrapped_dek`, `nonce`).
  Die Alternative wären zwei zusätzliche Spalten direkt in `account.claim`. Eine eigene Tabelle
  passt besser zur 1:n-Beziehung zwischen Gruppe und Zeilen.
- Ein kleiner `ClaimCryptoService` im Modul `account` (`javax.crypto.Cipher`, AES-256-GCM), nach
  dem Vorbild von `PasswordHasher.kt`.
- Für die Arbeitsdaten der Tools: eine Tabelle `account.retention_class_key` und das Nachschlagen
  darin in den betroffenen Modulen.
- Ein neuer Wert `RetractionSource.RETENTION_POLICY`. Damit erscheint ein automatischer Widerruf
  nach Ablauf einer Frist nicht fälschlich als Eingriff eines Menschen (`OPERATOR`) (Abschnitt 4).

**Unverändert übernommen:**

- Ein eigenes Schema je Modul. Alles bleibt im Schema `account`.
- Das bestehende Muster der `*RetentionJob`s. Das Löschen von Datenschlüsseln ist nur ein weiterer
  Job, der nach einem Stichtag arbeitet.
- Die Trennung zwischen `AccountClaim` (wird nur ergänzt) und `AccountAnchor` (abgeleitete
  Tabelle) sowie die Einheit von `AccountRetraction` (ADR-12). Der Datenschlüssel baut genau auf
  der schon vorhandenen Zeile auf.

---

## 6) Aufbewahrung des Hauptschlüssels: Zielbild und Demo

Wo und wie der **Hauptschlüssel selbst** aufbewahrt, geschützt und erneuert wird, ist eine eigene
Frage. Sie ist hier nicht entschieden, aber auch nicht mehr ausgeklammert. Ein Hauptschlüssel im
Klartext in der Konfiguration der Anwendung wäre kaum besser als der heutige Zustand. Zum
Vergleich: Auch der Schlüssel `NodeSigningKey.privateKeyJwk` liegt im Klartext (ADR-22).

### Die vier üblichen Wege, kurz

- **KMS (Key Management Service):** ein zentraler Dienst, etwa AWS KMS, Azure Key Vault oder
  HashiCorp Vault Transit. Der Schlüssel verlässt den Dienst nie. Die Anwendung schickt ihm etwas
  zum Ein- oder Auspacken und bekommt das Ergebnis zurück. Zugriff läuft über Rechte und wird
  protokolliert. Jeder Aufruf geht über das Netz (etwa 10 bis 20 ms) und zählt gegen ein Limit je
  Sekunde (bei AWS KMS je nach Region etwa 5.500 bis 50.000). Erneuern ist eingebaut: Eine neue
  Version packt neu ein, alte Versionen packen weiter aus.
- **HSM (Hardware Security Module):** ein zertifiziertes Gerät, aus dem der Schlüssel physisch
  nicht auslesbar ist, auch nicht für Administratoren. Angesprochen über PKCS#11 oder einen
  Netzdienst. Oft regulatorisch gefordert, teuer in Anschaffung und Betrieb, mit begrenztem
  Durchsatz von einigen hundert bis einigen tausend Operationen je Sekunde. Erneuern ist eine
  Zeremonie mit mehreren Personen. Viele KMS-Dienste laufen intern selbst auf HSMs.
- **Geheimnis im Anwendungsprozess:** Der Schlüssel liegt im Arbeitsspeicher und kommt beim Start
  aus einer Umgebungsvariable, einer Konfiguration oder einem Kubernetes-Secret. Schnell und ohne
  neue Infrastruktur. Wer den Prozess, die Konfiguration oder einen Speicherabzug hat, hat den
  Schlüssel. Erneuern muss man selbst bauen. So hält das Projekt heute den Pepper für Einmalcodes
  (`identity.secrets.otp-pepper`, [07-betrieb.md](../07-betrieb.md) Abschnitt 3b).
- **Aufteilung nach Shamir:** Der Schlüssel wird in n Teile zerlegt, von denen k beliebige ihn
  wiederherstellen, etwa 3 von 5. Das ist kein Aufbewahrungsort, sondern ein Zugriffsverfahren:
  Niemand allein kann den Schlüssel freischalten. Nach dem Zusammensetzen liegt er wieder im
  Prozess. Gut gegen einen einzelnen Innentäter und für die Wiederherstellung, schlecht für einen
  Betrieb, in dem Instanzen automatisch starten.

Die Wege schließen sich nicht aus. Vault etwa schützt seinen eigenen Hauptschlüssel nach Shamir und
stellt sich der Anwendung als KMS dar.

### Zielbild: drei Stufen

```
Umschlagschlüssel (KEK)      im KMS oder HSM, verlässt ihn nie, einer für alle Konten
  └─ Hauptschlüssel je Konto  in der Datenbank, eingepackt mit dem KEK, einer je Konto
       └─ Datenschlüssel        in claim_batch_key, eingepackt mit dem Hauptschlüssel, einer je Gruppe
```

Der Hauptschlüssel je Konto liegt eingepackt als Spalte an der Kontozeile (`account.account`,
etwa `wrapped_master_key` und `kek_version`). Eine eigene Tabelle braucht es dafür nicht. So wird
er mit dem Konto gesichert, wiederhergestellt und gelöscht, und es entsteht keine zweite Stelle,
von der alles abhängt (Abschnitt 7).

Die mittlere Stufe ist kein Selbstzweck. Sie verdient ihren Platz aus vier Gründen, die alle erst
bei großen Mengen zählen (Abschnitt 8):

1. **Sie entkoppelt den Durchsatz des KMS oder HSM vom Lesen der Claims.** Ein Aufruf nach außen
   ist nur nötig, um den Hauptschlüssel eines Kontos auszupacken. Danach packt die Anwendung alle
   Datenschlüssel dieses Kontos lokal mit AES aus. Die Last auf dem KMS wächst mit der Zahl der
   Konten, die gerade aktiv sind, nicht mit der Zahl der Zugriffe auf Claims. Ein langsames HSM im
   eigenen Rechenzentrum reicht damit aus.
2. **Der ausgepackte Hauptschlüssel lässt sich je Konto zwischenspeichern,** in einem begrenzten
   Speicher je Instanz (etwa Caffeine mit Obergrenze und Verfallszeit von wenigen Minuten), nie in
   einem gemeinsamen Speicher wie Redis. Bei mehreren Instanzen packt jede Instanz selbst aus. Das
   kostet im schlechtesten Fall so viele Aufrufe mehr, wie es Instanzen gibt, und bleibt ungefährlich.
   Entweicht ein Eintrag aus dem Speicher, betrifft das genau ein Konto.
3. **Den KEK zu erneuern bleibt bezahlbar.** Neu eingepackt werden nur die Hauptschlüssel, also eine
   Zeile je Konto. Ohne die mittlere Stufe wären es alle Datenschlüssel, also drei- bis fünfmal so
   viele Zeilen (Abschnitt 8). Mit einem KMS, das alte Versionen behält, kann das sogar träge
   geschehen, beim nächsten Schreiben je Konto.
4. **Ein Konto zu löschen ist ein Löschen einer Zeile.** Fällt die Kontozeile mit dem eingepackten
   Hauptschlüssel, sind alle Gruppen dieses Kontos auf einmal unlesbar, auch in Sicherungen, die
   die Zeile nicht mehr enthalten. Das ergänzt das heutige harte Löschen des Kontos.

**Zwei Alternativen, geprüft und zurückgestellt:**

- *Keine mittlere Stufe, Datenschlüssel direkt mit dem KEK eingepackt* (das klassische
  `GenerateDataKey` von AWS). Dann ist jedes Auspacken eines Datenschlüssels ein Aufruf nach außen.
  Das ist genau der Engpass aus Abschnitt 8. Man müsste stattdessen Datenschlüssel
  zwischenspeichern, also dasselbe tun wie oben, nur mit drei- bis fünfmal so vielen Einträgen.
- *Hauptschlüssel ableiten statt speichern* (HKDF aus einem Wurzelschlüssel und der `account_id`).
  Spart die Spalte am Konto. Dafür muss der Wurzelschlüssel die Anwendung erreichen, also den KMS
  oder das HSM verlassen, oder der Dienst muss Ableitung mit Kontext anbieten (Vault Transit kann
  das). Außerdem lässt sich ein abgeleiteter Schlüssel nicht je Konto erneuern. Als Hauptweg
  verworfen, als spätere Vereinfachung möglich.

### Was im Code dafür nötig ist

Ein kleiner Port im Modul `account`, etwa `MasterKeyWrapper` mit `wrap(accountId, key)` und
`unwrap(accountId, wrapped)`. Die `account_id` geht als zusätzlicher Kontext in die Verschlüsselung
ein (AES-GCM *associated data* beziehungsweise der *encryption context* des KMS). Ein eingepackter
Hauptschlüssel lässt sich so nicht an ein anderes Konto umhängen.

- **Adapter für die Demo:** ein Geheimnis aus der Konfiguration (`identity.secrets.master-kek`).
  Der KEK liegt dann im Prozess. Das ist für die Demo in Ordnung und hält den Datenbankinhalt ohne
  Konfiguration unlesbar. Vorbild ist **nicht** der Pepper, sondern
  `account.change-log.lookup-secret`: ein öffentlicher Demo-Wert als Vorgabe in `application.yml`,
  über eine Umgebungsvariable ersetzbar. Die Produktionsprüfung in [07-betrieb.md](../07-betrieb.md)
  Abschnitt 4 bekommt eine weitere Zeile: mindestens 32 Zeichen und nicht der Demo-Wert. Das
  Vorbild bringt auch schon das Verfahren für den Wechsel mit alter und neuer Id mit. Es lässt sich
  für `kek_version` übernehmen.

  Warum der Pepper anders bleibt: `identity.secrets.otp-pepper` schützt Codes, die fünf Minuten
  leben, und Zähler für Versandlimits. Ein neuer Zufallswert je Start kostet nur die Codes, die
  gerade unterwegs sind. Der KEK schützt Daten, die Jahre leben. Würde er je Start gewürfelt,
  wären alle Claims nach einem Neustart unlesbar. Beide Geheimnisse bleiben unter
  `identity.secrets`, und außerhalb des Demomodus müssen beide gesetzt sein.
- **Adapter für den Produktivbetrieb:** ein KMS oder HSM. Der Adapter ist der einzige Ort, der
  darüber Bescheid weiß. Die Hauptschlüssel und Datenschlüssel sehen in beiden Fällen gleich aus.
  Eine Umstellung von Demo auf KMS packt die eine Spalte je Konto neu ein, mehr nicht.

**Erneuern:**

- *Den KEK:* neue Version im KMS, alte Versionen bleiben zum Auspacken. Neu eingepackt wird je
  Konto beim nächsten Schreiben oder in einem Lauf in Portionen (Abschnitt 8). Die Spalte
  `kek_version` sagt, welche Version welches Konto noch braucht.
- *Den Hauptschlüssel eines Kontos:* neuer Schlüssel, die wenigen Datenschlüssel des Kontos neu
  einpacken, ein Aufruf nach außen. Das geht träge und ist für ein einzelnes Konto billig, etwa
  nach einem Vorfall oder nach einem festen Zeitraum.
- *Einen Datenschlüssel:* wird nicht erneuert, sondern gelöscht. Das ist der Zweck der ganzen
  Konstruktion.

---

## 7) Abwägungen, offen gesagt

- **Kryptografisches Löschen ist nicht von selbst ein Löschen im Sinne der DSGVO.** Sicherungen,
  Transaktionslog oder Replikate können den verschlüsselten Inhalt *und* den Datenschlüssel
  zusammen enthalten. Beispiele sind eine nächtliche Sicherung von vor dem Löschen des Schlüssels
  oder ein Replikat, das noch nicht auf dem neuesten Stand ist. Dann lässt sich der „gelöschte“
  Wert wiederherstellen, bis diese Kopie verfallen ist. Die Sicherungen der Tabelle mit den
  Datenschlüsseln müssen deshalb höchstens so lange aufbewahrt werden wie die der Tabelle mit den
  Daten. Das ist eine echte Abhängigkeit im Betrieb, kein Detail.
- **Rechenaufwand:** Bei jedem Zugriff auf einen Claim wird einmal ver- oder entschlüsselt.
  AES-GCM ist schnell. Im Umfang der Demo fällt das nicht ins Gewicht.
- **Eine neue Stelle, von der alles abhängt:** Geht die Tabelle mit den Datenschlüsseln verloren,
  werden auf einmal *alle* Claims *aller* Konten unlesbar. Das kann passieren, wenn sie beschädigt,
  versehentlich massenhaft gelöscht oder unvollständig wiederhergestellt wird. Das ist ein
  härterer Fehlerfall als beim heutigen Klartext. Diese Tabelle braucht eine eigene, entsprechend
  strenge Sicherung.
- **Rahmen der Demo:** Der Vorschlag sieht keine Anbindung an ein KMS vor, aber den Port dafür
  (Abschnitt 6). Die Demo beschreibt die kleinste umsetzbare Variante: `javax.crypto`, keine neue
  Abhängigkeit, der KEK aus der Konfiguration, das Muster der `RetentionJob`s wiederverwendet. Für
  einen Produktivbetrieb muss der Adapter für KMS oder HSM vor dem ersten echten Einsatz stehen.
  Die Daten in der Datenbank ändern sich dadurch nicht.

---

## 8) Mengen im Produktivbetrieb

Dieser Abschnitt ist eine grobe Schätzung, keine belastbare Planung der Kapazität. Als Maßstab
dient das Mengengerüst aus [14-stand-und-weg-zur-produktion.md](../14-stand-und-weg-zur-produktion.md)
Abschnitt 7: 20 Millionen Konten, 1 Million Anmeldungen am Tag, in der Spitze 100 bis 300
Anmeldungen je Sekunde. Angenommen sind dazu 3 bis 5 Gruppen von Claims je Konto über seine
Lebensdauer.

- **Wachstum der Schlüsseltabelle:** 20 Millionen Konten mal 3 bis 5 Gruppen ergeben **60 bis
  100 Millionen Zeilen** in `claim_batch_key`, also 12 bis 20 GB bei etwa 150 bis 200 Byte je
  Zeile. Dazu kommt der eingepackte Hauptschlüssel als Spalte an jeder der 20 Millionen
  Kontozeilen, etwa 2 GB. Für sich genommen ist das überschaubar. `claim_batch_key` ist aber eine
  neue Tabelle, die bei jedem Zugriff auf einen Claim mitgelesen wird.
- **Wo Claims tatsächlich entschlüsselt werden, und wie oft.** Das ist für die Last entscheidend,
  und es ist nicht die Anmeldung selbst:
  - `AccountProfile.establishedClaims` enthält nur Attributtyp und Vertrauensstufe. Beides steht
    unverschlüsselt in der Zeile (Abschnitt 4). Die Journey liest also bei jedem Schritt das
    Profil, ohne etwas zu entschlüsseln.
  - **`KeycloakAccountViews.viewOf` liest bei jedem Konto-Lesen durch Keycloak die Werte der
    gespiegelten Claims** (`establishedClaimValues`, Vor- und Nachname). Keycloak liest das Konto
    praktisch bei jeder Erneuerung eines Tokens neu
    ([14-stand-und-weg-zur-produktion.md](../14-stand-und-weg-zur-produktion.md) Abschnitt 7,
    Engpass 1). Das sind geschätzt **70 bis 1.400 Lesevorgänge je Sekunde**, und jeder davon würde
    entschlüsseln. Das ist der heiße Pfad.
  - `recordClaims` entschlüsselt die bestehenden Claims desselben Typs für die Prüfung auf
    doppelte Angaben. Das geschieht je Identifizierung oder Bestätigung, also selten.
  - Eine Auskunft nach Art. 15 DSGVO liest alle Gruppen eines Kontos. Das ist die Ausnahme und
    betrifft ein Konto.
- **Der eigentliche Engpass ist nicht das Verschlüsseln, sondern das Auspacken mit dem KEK.**
  - AES-256-GCM fällt bei jeder realistischen Last nicht ins Gewicht.
  - Ginge für jedes Lesen von Claim-Werten ein Aufruf an das KMS oder HSM, wären das die 70 bis
    1.400 Aufrufe je Sekunde von oben, jeder mit 10 bis 20 ms. Ein KMS in der Cloud trüge das
    (Limit etwa 5.500 je Sekunde und mehr, Kosten bei AWS etwa 0,03 USD je 10.000 Aufrufe, also
    im Bereich von einigen hundert bis einigen tausend USD im Jahr). Ein HSM im eigenen
    Rechenzentrum mit einigen hundert Operationen je Sekunde trüge es nicht.
  - **Mit dem Zielbild aus Abschnitt 6** geht der Aufruf nach außen nur einmal je Konto und
    Verfallszeit des Zwischenspeichers. Bei 5 Minuten Verfallszeit und einem Token, das 300
    Sekunden gilt, liegt die Rate nahe bei der Zahl der Anmeldungen: im Mittel etwa 12, in der
    Spitze 100 bis 300 Aufrufe je Sekunde. Das trägt auch ein HSM. Die Verfallszeit des
    Zwischenspeichers und die Laufzeit des Tokens gehören dabei zusammen betrachtet. Wird der
    Engpass 1 aus 14-stand (Keycloak liest seltener) behoben, sinkt die Rate weiter.
  - Der Zwischenspeicher je Instanz bleibt klein: Bei 400.000 gleichzeitigen Sitzungen sind das
    höchstens 400.000 Einträge zu etwa 100 Byte, also rund 40 MB.
- **Viele einzelne Schlüssel beim Lesen der ganzen Historie** sind unkritisch. Bei K Gruppen braucht
  es K lokale Auspackvorgänge, etwa für die Auskunft nach Art. 15 DSGVO. K ist klein, und es
  geht um ein Konto. Im Normalfall, wenn nur der aktuelle Wert gebraucht wird, liest das System
  ohnehin `AccountAnchor` (Abschnitt 3a) und nicht das Claim-Log.
- **`RetentionJob` darf das wachsende Claim-Log nicht per Join durchsuchen.**
  - `expires_at` muss **schon beim Schreiben** in `recordClaims` direkt in `claim_batch_key`
    gespeichert werden. Die Attributtypen der Gruppe und ihre Aufbewahrungsregel sind zu diesem
    Zeitpunkt bekannt.
  - Der Job bleibt dann ein einfaches `DELETE ... WHERE expires_at < now()` über einen Index, in
    Portionen, wie beim bestehenden Muster.
  - Daraus folgt eine Regel, die erzwungen werden muss: Eine Gruppe darf nur Attributtypen mit
    **derselben** Aufbewahrungsregel enthalten. Sonst gilt beim Löschen für alle Zeilen der Gruppe
    die längste Frist.
- **Bei Produktivmengen wird der mögliche Schaden konkret.** Geht `claim_batch_key` mit 60 bis
  100 Millionen Zeilen verloren, trifft das nicht ein Konto, sondern alle zugleich. Das kommt eher
  einem Totalausfall gleich als dem Verlust einer gewöhnlichen Tabelle. Die Tabelle braucht
  eigene, strengere Zusagen für Verfügbarkeit und Wiederherstellung, bei denen fast kein
  Datenverlust erlaubt ist. Dasselbe gilt für die Spalte mit dem Hauptschlüssel an der Kontozeile.
  Dass sie an der Kontozeile liegt und nicht in einer weiteren Tabelle, hält die Zahl solcher
  Stellen bei zwei. Ein Verlust des KEK im KMS oder HSM wäre der dritte und schlimmste Fall. Dort
  braucht es die Mechanismen des Dienstes gegen versehentliches Löschen (Wartefristen, mehrere
  Personen).
- **Erneuern bleibt bei großen Mengen bezahlbar, und zwar auf jeder Stufe.**
  - *KEK:* neu eingepackt werden 20 Millionen Hauptschlüssel, nicht 60 bis 100 Millionen
    Datenschlüssel und nicht die Claims. Bei einem Aufruf je Konto und etwa 5.000 Aufrufen je
    Sekunde dauert ein vollständiger Lauf gut eine Stunde, in Portionen über Tage verteilt oder
    träge beim nächsten Schreiben. Mit einem einzigen Schlüssel ohne Umschläge müsste man beim
    Erneuern alle Daten neu verschlüsseln. Das wäre ein Vorhaben über mehrere Tage.
  - *Hauptschlüssel eines Kontos:* ein Aufruf nach außen und 3 bis 5 lokale Einpackvorgänge.
  - Hier lohnt sich die Schicht der Datenschlüssel und die mittlere Stufe bei echten Mengen also
    nicht nur in der Theorie.

---

## Betroffene Dateien (nur als Hinweis für eine spätere Umsetzung)

- `src/main/kotlin/com/example/identity/core/account/infrastructure/AccountClaim.kt` (neues Feld
  `claim_batch_id`)
- `src/main/kotlin/com/example/identity/core/account/AccountService.kt` (`recordClaims`: je Aufruf
  eine `claim_batch_id` erzeugen)
- `src/main/kotlin/com/example/identity/core/account/infrastructure/AccountAnchor.kt`
  (unverändert; Bezugspunkt für die Abgrenzung in Abschnitt 3a)
- `src/main/kotlin/com/example/identity/core/account/infrastructure/AccountRetraction.kt`
- `src/main/kotlin/com/example/identity/core/account/infrastructure/Account.kt` (neue Spalten
  `wrapped_master_key`, `kek_version`)
- `src/main/kotlin/com/example/identity/core/orchestrator/keycloak/KeycloakAccountViews.kt`
  (heißer Pfad für das Entschlüsseln, Abschnitt 8)
- neuer Port `MasterKeyWrapper` im Modul `account` mit Demo-Adapter aus der Konfiguration
  (`identity.secrets.master-kek`, Prüfung in `DeploymentTopology`)
- `docs/adr/` (neues ADR für diese Entscheidung, eingetragen in `docs/12-entscheidungen.md`)
- `docs/07-betrieb.md` (bestehendes Muster der `*RetentionJob`s erweitern)

## Nächster Schritt

Wird das Vorhaben weiterverfolgt, sind diese Anschlussfragen zu klären. Zu jeder steht eine
Empfehlung, keine Entscheidung.

1. **Welcher Dienst hält den KEK im Produktivbetrieb (Abschnitt 6)?** Empfehlung: Vault Transit als
   erste Wahl. Es läuft im eigenen Rechenzentrum, sichert seinen eigenen Hauptschlüssel nach Shamir
   und kann wahlweise auf einem HSM aufsetzen. Ein KMS in der Cloud nur, wenn das System ohnehin
   dort läuft. Das entscheidet der Betreiber. Dieses Dokument hält nur fest, was der Adapter
   können muss: Einpacken und Auspacken mit Kontext, Versionen, mindestens 300 Aufrufe je Sekunde.
2. **Datenschlüssel je Gruppe für *alle* Attributtypen oder nur für die sensiblen (`EID_*`,
   `EMAIL`)?** Empfehlung: alle. Eine Unterscheidung nach Typ bringt zwei Codepfade, und ein
   Claim ohne Datenschlüssel müsste beim Lesen anders behandelt werden. Der Mehraufwand für Werte
   wie `PERSON_ID` ist ein lokaler AES-Aufruf. Einzige Ausnahme bleibt `account.anchor`
   (Abschnitt 3a).
3. **Wie werden bestehende Claims im Klartext umgestellt?** Bisher gar nicht nötig: Es gibt keine
   produktiven Daten, und die Demo-Datenbank wird neu aufgebaut. Die neuen Spalten und Tabellen
   kommen als gewöhnliche Flyway-Migration. Sollte das Vorhaben erst nach einem Produktivstart
   umgesetzt werden, gilt das Muster aus [offene-befunde.md](../offene-befunde.md) Abschnitt 8:
   in wiederholbaren Portionen, mit einer Lesephase, in der ein leeres `wrapped_dek` Klartext
   bedeutet.
4. **Soll die Frist von einem Jahr für eID-Daten auch die Zeile des Ankers `EID_RESTRICTED_ID`
   treffen (Abschnitt 3a)?** Empfehlung: nein, der Anker bleibt. Ein Nutzer, der auf einem neuen
   Gerät nicht mehr wiedererkannt wird, müsste die eID erneut auslesen. Das ist ein Supportfall
   für die Kasse und ein Ärgernis für den Nutzer. Der Anker ist ein Pseudonym ohne Namen oder
   Adresse. Die sensiblen Werte im Claim-Log fallen nach einem Jahr trotzdem weg. Das ist eine
   Produktentscheidung.
5. **Wie wird `RetractionSource.RETENTION_POLICY` eingeführt (Abschnitt 4)?** Empfehlung: in
   derselben Änderung wie der erste `RetentionJob`, der Datenschlüssel löscht. Der Job schreibt den
   Widerruf mit dieser Quelle und löscht den Schlüssel in einer Transaktion. Als Grund trägt er die
   Aufbewahrungsregel ein, etwa `EID_MAX_AGE_1Y`. So sieht ein Prüfer später, welche Regel gewirkt
   hat.
