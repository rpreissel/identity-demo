# ADR-52: Umschlagverschlüsselung des Claim-Logs mit einem Hauptschlüssel je Konto

**Status:** umgesetzt 2026-10-06 (Issue `DPoP-demo-bo1w`). Ergänzt
[ADR-12](ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md): Der Widerruf bleibt eine
eigene Zeile, macht einen Wert jetzt aber auch kryptografisch unlesbar.

**Entscheidung.** Die Werte im Claim-Log (`account.claim`) liegen verschlüsselt. Zu einem Konto gibt
es genau einen Hauptschlüssel. Darunter hat jede Gruppe von Angaben einen eigenen Datenschlüssel.
Ein Datenschlüssel, der gelöscht wird, macht genau die Werte seiner Gruppe dauerhaft unlesbar. Die
Zeilen bleiben als Metadaten stehen. Das ist das übliche Muster „kryptografisches Löschen“.

Die Schlüssel bilden drei Stufen:

```
Umschlagschlüssel (KEK)       Konfiguration (Demo) oder KMS/HSM (Produktion), einer für alle Konten
  └─ Hauptschlüssel je Konto   account.master_key.wrapped_master_key (seit ADR-55 eigene Tabelle), eingepackt mit dem KEK
       └─ Datenschlüssel        account.claim_batch_key, einer je Gruppe, eingepackt mit dem Hauptschlüssel
```

- **Gruppe** (`claim_batch_id`): die Angaben, die ein Aufruf von `recordClaims` gemeinsam schreibt,
  getrennt nach Aufbewahrungsregel. Ein eID-Lauf mit sieben Angaben ist eine Gruppe, solange für
  alle dieselbe Regel gilt. Die Zeile in `claim_batch_key` trägt `expires_at` schon beim Schreiben.
- **Wert** (`claim_value`): AES-256-GCM unter dem Datenschlüssel der Gruppe, mit Konto und Gruppe
  als zusätzlich geprüften Daten. Ein Chiffrat lässt sich nicht an eine andere Gruppe hängen.
- **Gleichheit** (`value_digest`): ein HMAC-SHA256 des normalisierten Werts unter einem aus dem
  Hauptschlüssel abgeleiteten Schlüssel. Nur damit prüft das System „schon so protokolliert“ und
  „widerrufen“. Beides bleibt reines SQL. Weil der Schlüssel je Konto verschieden ist, lässt sich
  über den Digest nicht über Konten hinweg suchen. Gesucht wird weiterhin nur über
  `account.anchor`, das im Klartext bleibt (ADR-12, ADR-19).
- **Widerruf** (`account.retraction`): trägt denselben Digest statt des normalisierten Werts. Die
  zweite lesbare Kopie, die ADR-12 als Preis nannte, gibt es nicht mehr. Ein Widerruf nach
  abgelaufener Frist nennt zusätzlich seine Gruppe (`claim_batch_id`) und trifft nur deren Zeilen.
  Derselbe Wert in einer jüngeren Gruppe, etwa aus einer erneuten Identifizierung, behält seine
  eigene Frist. Alle anderen Widerrufe gelten wie bisher für jede ältere Zeile mit diesem Digest.

Zwei Vorgänge löschen Datenschlüssel:

- Ein Widerruf, nach dem keine Angabe der Gruppe mehr gilt, löscht ihren Schlüssel sofort
  (`ClaimLedger.retract`). Beim Widerruf einer einzelnen E-Mail-Adresse ist das der Normalfall, weil
  `confirm-email` eine Gruppe mit einer Zeile schreibt.
- Eine abgelaufene Aufbewahrungsfrist (`account.claims.retention.<Attribut>`,
  [07-betrieb.md](../07-betrieb.md) Abschnitt 3): `ClaimBatchKeyRetention` schreibt je Angabe der
  Gruppe einen Widerruf mit der Quelle `RETENTION_POLICY` und löscht den Schlüssel, beides in einer
  Transaktion. So laufen logischer und kryptografischer Zustand nie auseinander. Ankerattribute
  dürfen keine Frist haben: Ihr Wert liegt auch in `account.anchor`, und ein Konto, dessen Anker
  verfällt, erkennt seinen Inhaber nicht mehr.

Der Hauptschlüssel entsteht mit dem Konto, oder schon für die Journey, die es anlegt, und liegt in
`account.master_key` (seit [ADR-55](ADR-055-hauptschluessel-je-journey-verfahrensgeheimnisse-versiegelt.md); die
erste Fassung hielt ihn als Spalte an der Kontozeile). Mit dem Konto wird er gesichert,
wiederhergestellt und gelöscht. Eine Kontolöschung nimmt damit alle Werte des Kontos auf einmal mit,
auch in Sicherungen, die die Kontozeile nicht mehr enthalten.

**Wo der Umschlagschlüssel liegt.** Hinter dem Port `MasterKeyWrapper` (Modul `account`), in
einem Schlüsseldienst, der ihn nie herausgibt; in der Demo ist das die Simulation aus
[ADR-54](ADR-054-schluesseldienst-simuliert.md) (`KmsKekWrapper`). Die erste Fassung dieser
Entscheidung hielt den KEK als Geheimnis in der Konfiguration (`identity.secrets.master-kek`); das
ist durch ADR-54 abgelöst. Ein Produktivbetrieb tauscht
den Adapter gegen ein KMS oder HSM. Die Daten in der Datenbank ändern sich dadurch nicht, nur die
Zeilen in `account.master_key` werden neu eingepackt.

**Warum die mittlere Stufe.** Ein Hauptschlüssel je Konto ist mehr als Ordnung. Er entkoppelt den
Durchsatz des KMS oder HSM vom Lesen der Angaben: Nach außen geht nur das Auspacken des
Hauptschlüssels, alle Datenschlüssel des Kontos packt die Anwendung lokal aus. Das zählt, weil
`KeycloakAccountViews` bei jedem Konto-Lesen durch Keycloak Vor- und Nachnamen aus dem Claim-Log
liest, also praktisch bei jeder Token-Erneuerung
([14-stand-und-weg-zur-produktion.md](../14-stand-und-weg-zur-produktion.md) Abschnitt 7). Mit
einem Zwischenspeicher je Instanz, der zum KMS-Adapter gehört, sinkt die Rate nach außen auf die Zahl
der aktiven Konten statt der Lesevorgänge. Dazu kommen: Den KEK zu erneuern packt eine Zeile je
Konto neu ein, nicht drei bis fünf Datenschlüssel und keine Angabe. Entweicht ein Schlüssel aus dem
Speicher, betrifft das ein Konto.

**Alternativen.**

- *Nur die Werte verschlüsseln, den normalisierten Wert im Klartext lassen.* Einfacher, aber die
  Widerrufszeile und `normalized_value` hielten weiter E-Mail-Adressen und Namen im Klartext. Dann
  wäre nur die Hälfte gewonnen. Verworfen.
- *Den normalisierten Wert ebenfalls verschlüsseln und im Code vergleichen.* Dedup und Widerruf
  müssten alle Angaben eines Kontos entschlüsseln. `findEstablished` wäre kein SQL mehr, und die
  Widerrufszeile bräuchte trotzdem eine Form, auf die sich vergleichen lässt. Der Digest je Konto
  leistet dasselbe ohne Entschlüsseln. Verworfen.
- *Ein Datenschlüssel je Zeile.* Passt zur Einheit des Widerrufs, ergibt aber sieben Schlüssel je
  eID-Lauf. Eine Gruppe je Aufruf und Regel fasst zusammen, was fachlich zusammengehört. Verworfen.
- *Ein Datenschlüssel je Aufbewahrungsklasse.* Gröber und billiger, aber jeder vorzeitige Widerruf
  einer einzelnen Zeile verlangt eine Neuverschlüsselung der Klasse. Für das Claim-Log verworfen;
  für die kurzlebigen Arbeitsdaten der Tools (`ToolSessionDataCodec`, ADR-49) bleibt es der
  passende Weg, falls sie je verschlüsselt werden.
- *Hauptschlüssel ableiten statt speichern* (aus einem Wurzelschlüssel und der Konto-Id). Spart die
  Spalte, aber der Wurzelschlüssel müsste die Anwendung erreichen, und ein abgeleiteter Schlüssel
  lässt sich nicht je Konto erneuern. Zurückgestellt.
- *Datenschlüssel direkt mit dem KEK einpacken, ohne Hauptschlüssel.* Dann wäre jedes Auspacken ein
  Aufruf nach außen, bei jedem Lesen von Namen durch Keycloak. Verworfen, siehe oben.

**Folgen und Kosten.**

- Jedes Lesen eines Werts liest die Kontozeile (nur die Schlüsselspalten, über eine Projektion, damit
  die Zeile nicht in den Persistenzkontext gerät und die optimistische Sperre weiter greift) und die
  Schlüsselzeile der Gruppe. Bei den Mengen aus 14-stand ist das Auspacken lokal und fällt nicht ins
  Gewicht.
- Kryptografisches Löschen ist nicht von selbst ein Löschen im Sinne der DSGVO. Sicherungen oder
  Replikate können Chiffrat und Datenschlüssel zusammen enthalten. Die Sicherungen von
  `claim_batch_key` und der Kontozeile dürfen höchstens so lange aufbewahrt werden wie die von
  `account.claim`.
- `claim_batch_key` und die Schlüsselspalte am Konto sind Stellen, deren Verlust alle Konten zugleich
  trifft. Sie brauchen strengere Zusagen für Wiederherstellung als eine gewöhnliche Tabelle. Der
  Verlust des KEK wäre der schlimmste Fall; dort gelten die Schutzmechanismen des Schlüsseldienstes.
- Tests, die Werte lesen, gehen über `AccountService.establishedClaimValues` oder `ClaimCrypto`,
  nicht mehr über SQL auf `claim_value`.
- Keine Datenübernahme: Es gab keine Produktivdaten. Eine bestehende Demo-Datenbank scheitert an
  V39 und wird im Demomodus neu aufgebaut (`FlywayResetConfig`).

**Offen.** Der Adapter für den echten Schlüsseldienst (Vault Transit, ADR-56, `DPoP-demo-lmt9`); den
Zwischenspeicher je Instanz hat ADR-54 umgesetzt. Ob die
Werte in `account.anchor` einen eigenen Schutz brauchen, entscheidet dieses ADR nicht; dort gilt
weiter das harte Löschen aus ADR-12.
