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
gehört ausdrücklich **nicht** zur Frage.

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
er selbst aufbewahrt wird, ist bewusst ausgeklammert. Möglich wären etwa ein KMS, ein HSM, eine
Ableitung aus einer Passphrase oder eine Aufteilung nach Shamir. Das ist als offene Anschlussfrage
vermerkt, aber nicht entworfen (Abschnitt 6).

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

## 6) Ausdrücklich ausgeklammert

Wo und wie der **Hauptschlüssel selbst** aufbewahrt, geschützt und regelmäßig erneuert wird, ist
eine eigene Anschlussfrage. Möglich wären etwa ein KMS, ein HSM, ein Geheimnis im
Anwendungsprozess oder eine Aufteilung nach Shamir. Die Frage ist bewusst ausgeklammert und nicht
Teil dieses Vorschlags. Sie ist aber praktisch wichtig: Ein Hauptschlüssel im Klartext in der
Konfiguration der Anwendung wäre kaum besser als der heutige Zustand. Zum Vergleich: Auch der
Schlüssel `NodeSigningKey.privateKeyJwk` liegt im Klartext (ADR-22).

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
- **Rahmen der Demo:** Der Vorschlag sieht bewusst keine Anbindung an ein KMS vor. Er beschreibt
  die kleinste umsetzbare Variante: `javax.crypto`, keine neue Abhängigkeit, das Muster der
  `RetentionJob`s wiederverwendet. Für einen Produktivbetrieb müsste die Aufbewahrung des
  Hauptschlüssels (Abschnitt 6) vor dem ersten echten Einsatz geklärt sein.

---

## 8) Mengen im Produktivbetrieb

Dieser Abschnitt ist eine grobe Schätzung, keine belastbare Planung der Kapazität. Als Maßstab
dient eine große gesetzliche Krankenkasse mit etwa 11 Millionen Versicherten.

- **Wachstum der Schlüsseltabelle:** Ein Konto hat über seine Lebensdauer etwa 3 bis 5 Gruppen von
  Claims. Das ergibt **30 bis 55 Millionen Zeilen** in `claim_batch_key`, also 6 bis 10 GB bei
  etwa 150 bis 200 Byte je Zeile. Für sich genommen ist das überschaubar. Es ist aber eine neue
  Tabelle, die bei jedem Zugriff auf einen Claim mitgelesen wird.
- **Der eigentliche Engpass ist nicht das Verschlüsseln, sondern das Auspacken mit dem
  Hauptschlüssel.**
  - AES-256-GCM fällt bei jeder realistischen Last nicht ins Gewicht.
  - Liegt der Hauptschlüssel aber in einem KMS oder HSM, kostet jedes Entschlüsseln des
    Hauptschlüssels einen Aufruf über das Netz (etwa 10 bis 20 ms). Geschieht das bei jedem Zugriff
    auf einen Claim, kommen die Grenzen des KMS für Anfragen je Sekunde dazu (bei AWS KMS etwa
    5.500 bis 10.000 je Schlüssel) und Kosten je Aufruf. Bei Millionen Anmeldungen am Tag könnte
    das der größte Kostenpunkt werden.
  - **Abhilfe:** Den Hauptschlüssel einmal je Sitzung oder Anfrage entschlüsseln und im
    Arbeitsspeicher vorhalten, nie speichern und nur kurz behalten. Die Last auf dem KMS wächst
    dann mit der Zahl der Sitzungen und nicht mit der Zahl der Zugriffe auf Claims.
- **Viele einzelne Schlüssel beim Lesen der ganzen Historie** sind unkritisch. Bei K Gruppen braucht
  es K Auspackvorgänge, etwa für eine Auskunft nach Art. 15 DSGVO. Dabei geht es aber nur um ein
  Konto, und K ist klein. Im Normalfall, wenn nur der aktuelle Wert gebraucht wird, liest das
  System ohnehin `AccountAnchor` (Abschnitt 3a) und nicht das Claim-Log. Das teure Lesen vieler
  Gruppen ist die Ausnahme und gehört nicht zum Ablauf der Anmeldung.
- **`RetentionJob` darf das wachsende Claim-Log nicht per Join durchsuchen.**
  - `expires_at` muss **schon beim Schreiben** in `recordClaims` direkt in `claim_batch_key`
    gespeichert werden. Die Attributtypen der Gruppe und ihre Aufbewahrungsregel sind zu diesem
    Zeitpunkt bekannt.
  - Der Job bleibt dann ein einfaches `DELETE ... WHERE expires_at < now()` über einen Index, wie
    beim bestehenden Muster.
  - Daraus folgt eine Regel, die erzwungen werden muss: Eine Gruppe darf nur Attributtypen mit
    **derselben** Aufbewahrungsregel enthalten. Sonst gilt beim Löschen für alle Zeilen der Gruppe
    die längste Frist.
- **Bei Produktivmengen wird der mögliche Schaden konkret.** Geht die Schlüsseltabelle mit 30 bis
  55 Millionen Zeilen verloren, trifft das nicht ein Konto, sondern alle zugleich. Das kommt eher
  einem Totalausfall gleich als dem Verlust einer gewöhnlichen Tabelle. Die Tabelle braucht
  eigene, strengere Zusagen für Verfügbarkeit und Wiederherstellung, bei denen fast kein
  Datenverlust erlaubt ist. Sie gehört eher in einen eigenen, überwachten Datenspeicher als
  einfach als weitere Tabelle in das Schema `account`.
- **Ein echter Vorteil bei großen Mengen: Den Hauptschlüssel zu erneuern wird billig.** Dabei
  ändert sich nur die kleine Schlüsseltabelle. Die Datenschlüssel werden mit dem neuen
  Hauptschlüssel neu eingepackt. Die 30 bis 55 Millionen Zeilen mit personenbezogenen Daten in
  `AccountClaim` bleiben unverändert. Mit einem einzigen Schlüssel ohne Datenschlüssel müsste man
  beim Erneuern alle Daten neu verschlüsseln. Bei Produktivmengen wäre das ein Vorhaben über
  mehrere Tage. Hier lohnt sich die Schicht der Datenschlüssel bei echten Mengen also nicht nur in
  der Theorie.

---

## Betroffene Dateien (nur als Hinweis für eine spätere Umsetzung)

- `src/main/kotlin/com/example/identity/core/account/infrastructure/AccountClaim.kt` (neues Feld
  `claim_batch_id`)
- `src/main/kotlin/com/example/identity/core/account/AccountService.kt` (`recordClaims`: je Aufruf
  eine `claim_batch_id` erzeugen)
- `src/main/kotlin/com/example/identity/core/account/infrastructure/AccountAnchor.kt`
  (unverändert; Bezugspunkt für die Abgrenzung in Abschnitt 3a)
- `src/main/kotlin/com/example/identity/core/account/infrastructure/AccountRetraction.kt`
- `docs/adr/` (neues ADR für diese Entscheidung, eingetragen in `docs/12-entscheidungen.md`)
- `docs/07-betrieb.md` (bestehendes Muster der `*RetentionJob`s erweitern)

## Nächster Schritt

Wird das Vorhaben weiterverfolgt, sind diese Anschlussfragen zu klären:

1. Wie wird der Hauptschlüssel aufbewahrt (Abschnitt 6)?
2. Gibt es Datenschlüssel je Gruppe für *alle* Attributtypen oder nur für die sensiblen (`EID_*`,
   `EMAIL`)?
3. Wie werden bestehende Claims im Klartext umgestellt?
4. Soll die Frist von einem Jahr für eID-Daten auch die Zeile des Ankers `EID_RESTRICTED_ID`
   treffen, mit den Folgen für das Wiedererkennen (Abschnitt 3a)?
5. Wie wird `RetractionSource.RETENTION_POLICY` für automatische Widerrufe nach Ablauf einer Frist
   eingeführt (Abschnitt 4)?
