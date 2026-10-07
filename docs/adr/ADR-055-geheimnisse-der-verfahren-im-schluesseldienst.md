# ADR-55: Ein Hauptschlüssel je Journey, versiegelte Verfahrensgeheimnisse, lesbare Umschlagköpfe

**Status:** umgesetzt 2026-10-07 (Issue `DPoP-demo-wbuk`). Baut auf
[ADR-52](ADR-052-umschlagverschluesselung-des-claim-logs.md) und
[ADR-54](ADR-054-schluesseldienst-simuliert.md) auf und schließt die Liste aus
[ADR-53](ADR-053-arbeitsdaten-und-app-tokens-verschluesselt.md) bis auf `account.anchor`.

**Entscheidung.** Drei Dinge, die zusammengehören.

**1. Der Hauptschlüssel ist eine eigene Tabelle, und eine Journey bekommt ihn vor dem Konto.**
`account.master_key` hält je Schlüssel den eingepackten Wert, die KEK-Version und das Konto, dem
er gehört. Ein Konto hat einen **primären** Schlüssel, unter dem Claims (ADR-52) und App-Tokens
(ADR-53) liegen. Ein Verfahrensmodul schreibt seine Zeile aber, bevor das Konto zwingend
existiert: Der Orchestrator legt ein Konto beim Übernehmen des Verfahrens auch nachträglich an.
Deshalb bekommt eine Journey ohne Konto einen eigenen Schlüssel, sobald ein Tool ihn anfordert
(`ToolContext.masterKey()`), und der Kanal merkt sich ihn (`channel_session.journey_key_id`).
Bindet die Journey ein Konto, übernimmt es den Schlüssel: als primären, wenn es noch keinen hat,
sonst als weiteren, unter dem bleibt, was vorher versiegelt wurde. Ein Konto, das aus einer
Identifizierung entsteht, bekommt den Journey-Schlüssel direkt als primären.

Nur ein Tool, das ein Geheimnis speichert, fordert den Schlüssel an. Eine Anmeldung oder
Identifizierung erzeugt keinen; es gibt also keinen Schlüssel je Login. Schlüssel, die nie ein
Konto übernahm, löscht `RetentionJob` mit der Frist der Kanalsitzungen.

**2. Mobilnummer, verwahrte PIN, Bezeichnung und Referenz eines Verfahrens, Einzelheiten einer
Anmeldung liegen versiegelt.**

| Wert | Tabelle | Schlüssel | Zweck |
|---|---|---|---|
| Mobilnummer | `auth_sms.enrollment.phone_number` | der Schlüssel aus dem Tool-Kontext, die Zeile nennt ihn (`key_id`) | `auth-sms:phone-number` |
| verwahrte PIN ([ADR-21](ADR-021-der-kobil-pin-liegt-im-backend-und-das.md)) | `auth_kobil.enrollment.pin` | wie oben | `auth-kobil:pin` |
| Bezeichnung, Referenz eines Verfahrens | `account.auth_method.label`, `reference` | primärer Schlüssel des Kontos | `auth-method:label`, `auth-method:reference` |
| Einzelheiten einer Anmeldung | `account.sign_in_log.details` | primärer Schlüssel des Kontos; Einträge einer Einladung ohne Konto bleiben lesbar | `sign-in-log:details` |

Die Verfahrensmodule erreichen den Schlüssel über den Port `tool_api.kms.AccountSealing`
(`forKey`), den `AccountDataCipher` im Konto-Modul implementiert. Eine Zeile liest ihren Wert über
den Schlüssel, den sie nennt; ein Konto muss dafür nicht bekannt sein. Mit dem Konto gehen alle
seine Schlüssel, und mit ihnen alles, was darunter liegt.

**3. Außerhalb der Demo liegt in der Spalte nur das Chiffrat; die Demo tut so als ob und sagt
dazu, womit.** Mit `identity.encryption.enabled=true` hält die Spalte das nackte Chiffrat
(AES-256-GCM, der Schlüsselbezug ist in die Zusatzdaten eingebunden) und ein Digest die 64
Hex-Zeichen des HMAC, sonst nichts. Die Demo läuft mit `false`: Werte und Digests liegen lesbar in
den Tabellen, und ein kurzer Kopf nennt den Schlüssel, unter dem sie sonst lägen: Art und die
ersten acht Zeichen der Kennung, dann der Prüfwert. Jeder Teil ist benannt, den Aufbau muss sich
niemand merken.

| Kopf | Schlüssel | Beispiel |
|---|---|---|
| `[konto 3f9a2b1c pruefwert a17e03c9]` | Hauptschlüssel des Kontos, `account.master_key` | Mobilnummer, PIN, App-Token, Bezeichnung |
| `[gruppe 7c1d0e2a pruefwert 5bd2f810]` | Datenschlüssel der Claim-Gruppe, `account.claim_batch_key` | Claim-Wert |
| `[tag TOOL_SESSION:2026-10-07 pruefwert 9e4c1a77]` | Datenschlüssel des Tages, `orchestrator.data_key` | Arbeitsdaten eines Tools |
| `[verschluesselt mit konto 3f9a2b1c]` | der Wert dahinter ist auch in der Demo verschlüsselt, mit diesem Hauptschlüssel | Gruppenschlüssel in `claim_batch_key.wrapped_dek` |
| `[konto 3f9a2b1c]family_name=muster` | Digest: der normalisierte Wert selbst, vergleichbar in SQL | `claim.value_digest` |
| `[ohne]` | kein Schlüssel, die Zeile gehört keinem Konto | Anmeldeprotokoll einer Einladung |

`pruefwert` ist ein Prüfwert unter dem Schlüssel (acht Hex-Zeichen eines HMAC über Kopf,
Zusatzdaten und Wert). Die Anwendung liest einen lesbaren Wert also nur, wenn der Schlüssel
stimmt, und ein falscher Schlüssel scheitert mit derselben Ausnahme wie beim echten Chiffrat. Die
Schlüssel werden in beiden Modi gleich angelegt, eingepackt, übernommen und rotiert; Datenschlüssel
und Hauptschlüssel bleiben immer eingepackt. In der Konsole findet man den Schlüssel einer Zeile
mit `CAST(key_id AS VARCHAR) LIKE '3f9a2b1c%'`.

**Der Modus gehört zur Datenbank.** Die versiegelten Spalten sind so breit, wie der Modus es
braucht: Flyway setzt die Breiten als Platzhalter ein (`${secret_width}`, `${digest_width}` und
weitere aus `ClaimEncryptionKeys.schemaPlaceholders`), und die erste Migration schreibt den Modus
nach `orchestrator.encryption_mode`. Vor jeder weiteren Migration vergleicht `EncryptionModeGuard`
diese Zeile mit dem laufenden Schalter und bricht ab, ohne etwas zu ändern, wenn sie abweicht: Ein
umgelegter Schalter auf vorhandenen Daten machte jeden Digest unvergleichbar und jeden Wert
unlesbar. Die Demo-Wiederherstellung, die eine kaputte H2-Datei löscht, lässt diesen Abbruch durch.
Die Tests laufen mit eingeschalteter Verschlüsselung; `ProductionModeCheck` lehnt den Schalter
außerhalb des Demomodus ab.

**Alternativen.**

- *Reihenfolge ändern: vor jedem Einrichtungs-Tool ein Konto im Aufbau anlegen (ADR-46).* Griffe
  in den Journey-Kern und alle Einrichtungs-Controller ein und erzeugte je begonnener Einrichtung
  ein Konto. Verworfen zugunsten des Journey-Schlüssels, der nur das Schlüsselmodell berührt.
- *Ein Modulschlüssel je Verfahren im Schlüsseldienst.* Kleiner, aber die Werte hingen nicht am
  Konto: Löschen des Kontos ließe sie lesbar, und die Versionsprüfung beim Start sähe sie nicht.
  Verworfen.
- *Die Nummer aus dem Claim-Log lesen.* Die Module dürfen das Konto nur über Ports erreichen, und
  ein Widerruf im Claim-Log darf das Verfahren nicht still abschalten. Verworfen.

**Folgen und Kosten.**

- `account.account` trägt keine Schlüsselspalten mehr; `ClaimCrypto` liest den Schlüssel über die
  Kennung und hält ihn je Instanz im Speicher (ADR-54). Batch-Schlüssel gehören weiter dem Konto.
- Die versiegelten Spalten sind binär; in der Demo um den Kopf breiter. Tests lesen über die
  Dienste (`SmsNumbers`, `KobilPins`, `AccountService`, `SignInLog`).
- Ein Konto, das im Lauf einer Journey mit einem anderen verschmilzt (`absorbDisposableAccount`),
  verliert seine Schlüssel mit der Löschung. Das ist unschädlich: Ein verwerfbares Konto hat kein
  Verfahren und damit nichts, was unter ihnen läge.
- `account.anchor` bleibt im Klartext, weil darüber gesucht wird (ADR-52).
