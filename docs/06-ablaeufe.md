# Verfahren: Datenmodell und Übersicht

Dieses Kapitel zeigt, wie die Tools die Bausteine aus
[03-tool-architektur.md](03-tool-architektur.md) und [04-orchestrierung.md](04-orchestrierung.md)
konkret nutzen. Es beschreibt das Datenmodell, das alle Verfahren gemeinsam nutzen, und die
Entscheidungen dahinter. Ein **Verfahren** ist ein Weg, sich zu identifizieren oder anzumelden,
etwa SMS, Passwort oder der Online-Ausweis.

Wie ein einzelnes Verfahren abläuft, welche Aufrufe es gibt und welche Fehler auftreten können,
steht auf der eigenen Seite des Verfahrens (siehe Abschnitt 2). Ein durchgehendes Beispiel mit allen
Aufrufen steht in [05-api.md](05-api.md) Abschnitt 2.

---

## 1) Datenmodell der Verfahren

```mermaid
classDiagram
  class Account {
    long id
    Instant createdAt
    long version
  }
  class AccountAnchor {
    AttributeType attributeType
    string normalizedValue
    Instant establishedAt
  }
  class AccountClaim {
    AttributeType attributeType
    string value
    string claimSource
    string establishedAcr
  }
  class ChangeLogEntry {
    string changeType "IDENTIFIED, ..."
    string subject "Verfahren"
    string acr
    Map details "type, version, Rolle, Tool, Anbieter, Vorgang, Version, Hash"
    Instant occurredAt
  }
  class AccountAuthMethod {
    UUID id
    string method
    bool active
    string enrolledUnderAcr
    string enrollmentType
    string enrollmentId
    json details
  }

  Account "1" --> "0..*" AccountAnchor : aktueller Wert je Ankertyp
  Account "1" --> "0..*" AccountClaim : Protokoll der Claims (nur anfügen)
  Account "1" --> "0..*" ChangeLogEntry : Änderungsprotokoll (nur anfügen, überlebt das Konto)
  Account "1" --> "0..*" AccountAuthMethod : eingerichtete Verfahren
```

Das Diagramm zeigt, was zu einem Konto (`Account`) gehört:

- die **Anker** (`AccountAnchor`): Merkmale, über die sich ein Konto eindeutig wiederfinden lässt,
  etwa die Partnernummer. Je Ankertyp gibt es einen aktuellen Wert.
- die **Angaben** (`AccountClaim`): Informationen über den Kontoinhaber mit ihrer Quelle. Sie werden
  als Protokoll geführt, an das nur angefügt wird.
- das **Änderungsprotokoll** (`ChangeLogEntry`): Es hält fest, was wann geändert wurde. Auch hier
  wird nur angefügt, und es bleibt nach dem Löschen des Kontos erhalten.
- die **eingerichteten Verfahren** (`AccountAuthMethod`).

Die eigentlichen Zugangsdaten eines Verfahrens (das Credential, etwa die Telefonnummer bei SMS)
liegen nicht im Konto, sondern im Datenbankschema des Moduls, etwa in `auth_sms.enrollment`
([Verfahren `sms`](verfahren/sms.md)). `AccountAuthMethod` verweist darauf über die `EnrollmentRef`,
die aus `enrollmentType` und `enrollmentId` besteht.

Für dieses Modell gelten die folgenden Entscheidungen:

- **`enrolledUnderAcr` ist ein eigenes Feld, nicht nur ein Eintrag fürs Protokoll.** Das Feld hält
  fest, welches Sicherheitsniveau die Sitzung hatte, in der das Verfahren eingerichtet wurde. Meldet
  sich jemand später mit einem `auth-*`-Tool an, ist das erreichte Niveau (`achievedAcr`) durch
  diesen Wert begrenzt ([Orchestrierung](04-orchestrierung.md) Abschnitt 8). Ohne diese Regel
  könnte sich das Niveau nach und nach erhöhen: Ein Verfahren, das in einer schwach gesicherten
  Sitzung eingerichtet wurde, würde dauerhaft ein höheres Niveau liefern, als je nachgewiesen wurde.
  Den Wert kennt nur der Orchestrator, nie das Modul.
- **Die `EnrollmentRef` steht in echten Spalten, nicht irgendwo in `details`.** Die Spalten
  `account.auth_method.enrollment_type`/`enrollment_id` sind die einzige Verknüpfung zwischen Konto
  und Credential. Sie sind indiziert und lassen sich in beide Richtungen abfragen. Das braucht man
  beim Löschen und beim Widerruf. Die Credential-Tabellen der Module haben bewusst keine
  `account_id`. Der Grund: Ein Credential entsteht im Tool-Handler, also zu einem Zeitpunkt, zu dem
  der Orchestrator das Konto noch nicht kennt.
- **Eine Zeile je eingerichtetem Verfahren statt einer JSON-Liste im Konto.** Lesen schreibt nie.
  Eine Änderung sperrt nur die Zeile des Kontos, indem sie deren Version erhöht. Deaktivierte
  Einträge haben einen Wert in `deactivated_at`. Ein CHECK-Constraint in der Datenbank sorgt dafür,
  dass `active` und `deactivated_at` zueinander passen.
- **Welche Nachweise beim Einrichten vorlagen, steht nur im Änderungsprotokoll.** Das Ereignis
  `METHOD_ADDED` enthält in `details` drei Dinge:
  - die Nachweise der Sitzung (`amr`),
  - den Kanal (`channel`),
  - das Tool, mit dem das Verfahren eingerichtet wurde, in der Fassung, die der Client verwendet hat
    (`tool`: `enroll-sms@1`, ADR-51).

  Diese Werte stehen nur dort, weil nur das Änderungsprotokoll erhalten bleibt, wenn ein Verfahren
  deaktiviert oder das Konto gelöscht wird (ADR-39). Auf die Auswahl der angebotenen Tools
  (Kandidaten) und auf die Berechnung des Niveaus (ACR) haben sie keinen Einfluss. Dafür zählt
  allein `enrolledUnderAcr`. Das Feld `details` von `AccountAuthMethod` enthält nur, was das
  zuständige Modul selbst wieder liest, etwa eine Schlüssel-Referenz oder die KOBIL-Gerätekennung.
- **Die bestätigte E-Mail-Adresse ist der EMAIL-Anker.** Sie ist weder eine Spalte im `Account`
  noch ein Credential eines Moduls. Jedes Konto hat höchstens eine, genau wie bei `personId`.
  Bestätigt wird sie mit dem Tool `confirm-email`. Danach dient dieselbe Adresse zwei Zwecken:
  - als Anmeldeverfahren (`enroll-email`/`auth-email`, `EnrollmentRef` =
    `EMAIL_ANCHOR_ENROLLMENT`),
  - zum Finden des Kontos, wenn sich jemand über die E-Mail-Adresse anmeldet.

  Die Regel `UNIQUE(attribute_type, normalized_value)` verhindert, dass dieselbe Adresse in
  irgendeiner Schreibweise zweimal vergeben wird ([Verfahren `email`](verfahren/email.md)).
- **Der Orchestrator liest keine Moduldaten.** Ein Tool hat Arbeitsdaten, bei SMS etwa die Nummer,
  den Hash der TAN und die Ablaufzeit. Der Orchestrator speichert sie zwar als JSON an seiner
  Tool-Sitzung (`orchestrator.tool_session.data`,
  [ADR-49](adr/ADR-049-arbeitsdaten-der-tools-am-orchestrator.md)). Aber nur das Modul kennt ihren
  Aufbau und liest sie.

**Regel für das Identifizierungs-Ereignis im Änderungsprotokoll.** Wenn sich jemand identifiziert,
schreibt der Orchestrator ein Ereignis `IDENTIFIED` in das Änderungsprotokoll (`account.change_log`,
[ADR-39](adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md)). Dieses Ereignis belegt, **dass und
wie** geprüft wurde, aber nicht **was** geprüft wurde.

- Hinein gehören: die Rolle, die Belege der Prüfung (`provider`, `providerTxId`), die Version des
  Verfahrens und ein Hash über die geprüften Merkmale. Nur diese Felder werden übernommen, und zwar
  gezielt nach ihrem Namen (`JourneyRecorder`).
- Nicht hinein gehören: KVNR, Name, eine Dokument- oder Ausweisnummer (§ 20 PAuswG) oder
  Geheimnisse.

Ein Durchlauf kann **zwei** solche Zeilen hinterlassen. Denn ADR-18 teilt die Identifizierung in
zwei Schritte: bestätigen, wer jemand ist (`ident-eid`), und diese Identität einem Datensatz
im Personenverzeichnis zuordnen (`ident-kvnr`). Beide Schritte werden protokolliert. Die Zuordnung ist
dabei besonders wichtig, denn in diesem Moment entsteht der `PERSON_ID`-Anker.

Welcher der beiden Schritte eine Zeile war, steht als `role` in `details` (`IDENTIFICATION` oder
`CORRELATION`). Man leitet es nicht aus dem Namen des Verfahrens ab. Der Grund: Ein zuordnender
Schritt übernimmt das Niveau der Bestätigung, auf der er aufbaut. Eine Zeile „kvnr / loa2“ ohne
weiteren Hinweis sähe deshalb so aus, als hätte das Verfahren `kvnr` dieses Niveau allein erreicht.
Alle Zeilen desselben Durchlaufs haben dieselbe `journeyId`.

---

## 2) Die Verfahren

Jedes Verfahren hat eine eigene Seite. Sie beschreibt die Tools, den Ablauf, das Datenmodell, die
Aufrufe und die Fehlerfälle ([Übersicht](verfahren/README.md)):

- **Identifizieren und zuordnen:**
  - [`fsc`](verfahren/fsc.md): Freischaltcode,
  - [`eid`](verfahren/eid.md): Online-Ausweis,
  - [`nect`](verfahren/nect.md): Identifizierungsdienst Nect,
  - [`kvnr`](verfahren/kvnr.md): Zuordnung über KVNR oder Partnernummer.
- **Anmelden mit Wissen oder Code:**
  - [`sms`](verfahren/sms.md),
  - [`password`](verfahren/password.md),
  - [`email`](verfahren/email.md), auch die bestätigte Adresse selbst.
- **Anmelden mit einem Gerät:**
  - [`device`](verfahren/device.md): Geräteschlüssel,
  - [`kobil`](verfahren/kobil.md): Gerätebindung über KOBIL,
  - [`qr`](verfahren/qr.md): Anmeldung auf der Website, die im Handy bestätigt wird.
- **Vorgangszugang:**
  - [`invite`](verfahren/invite.md): Einmalkennwort aus einer Einladung.
