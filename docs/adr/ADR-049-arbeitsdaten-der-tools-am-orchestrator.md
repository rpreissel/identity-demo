# ADR-49: Die Arbeitsdaten der Tools liegen als JSON an der Tool-Sitzung des Orchestrators

**Status:** umgesetzt 2026-10-03 (Issue `DPoP-demo-x69i`).

**Entscheidung.** Was ein Tool während eines Durchlaufs festhält (ausgegebener Code-Hash, Ablaufzeit,
eingegebene Nummer, KOBIL-Aktivierungsdaten …), speichert es über den Port
`tool_api.ToolSessionData`. Der Orchestrator legt es als JSON in die Spalte `data` der Zeile
`orchestrator.tool_session`, die es zu jedem Durchlauf ohnehin gibt; `data_type` nennt Modul und
Klasse des Zustands (`auth_sms.AuthSmsToolSession`). Ein Tool hält seinen Zustand als einfache,
unveränderliche Datenklasse und ersetzt ihn nach jeder Änderung ganz (`save`).

Wie bei den Zählern ([ADR-44](ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md)) leiht der
Orchestrator nur den Speicher. Den Namensraum bestimmt die Klasse des Zustands: Ein Modul liest nur
Zustände, die es selbst deklariert; ein fremder Typ ist ein Vertragsfehler.

**Warum.** Bisher brachte jedes Tool eine JPA-Entität, ein Repository und eine Tabelle
`<modul>.<rolle>_tool_session` mit, jedes Modul dazu einen Aufräumjob: 23 Entitäten, 23 Tabellen,
zehn Jobs. Alle 23 Repositories wurden nur über die `tool_session_id` gelesen und geschrieben und
nach Alter gelöscht – genau das, was die Zeile im Orchestrator schon kann: gleiche Id, Ablaufzeit,
Status, Versionsnummer, eigenes Aufräumen. Ein neues Tool schreibt jetzt eine Datenklasse statt drei
Dateien und einer Migration.

**Folgen.**

- **Aufräumen** fällt mit der Zeile: `RetentionJob` löscht `orchestrator.tool_session` nach
  `tool-session.retention`. Module räumen nur noch eigene kurzlebige Daten auf, die keine
  Tool-Sitzung sind (`auth_qr.login_request`, `ToolSessionSweeper`).
- **Gleichzeitige Schreiber:** Die `@Version` der Zeile gilt auch für die Daten. Zwei parallele
  PATCHes derselben Sitzung enden für den zweiten mit `409`, statt dass der letzte still gewinnt.
- **Rollierende Deploys:** `ToolSessionDataCodec` liest tolerant (unbekannte Felder werden
  übersprungen, fehlende bekommen ihren Kotlin-Standardwert). Ein Zustand gibt deshalb jedem Feld,
  das er später bekommt, einen Standardwert.
- **Ids** sind zeitlich sortiert (UUIDv7), damit neue Zeilen am Ende des Index landen.
- **Skalierung:** Bei 10 Mio. Nutzern rund 5 Mio. Tool-Sitzungen am Tag, in der Spitze etwa 2.000
  Schreibvorgänge pro Sekunde über den Primärschlüssel – dieselbe Last wie vorher, nur in einer
  statt 24 Tabellen. Beim Wechsel auf PostgreSQL (`DPoP-demo-pi55`) wird diese eine Tabelle nach
  Tag partitioniert und per `DROP PARTITION` statt `DELETE` aufgeräumt.
- **Verschlüsselung:** Der Codec ist die einzige Stelle, die Zustände schreibt und liest; dort setzt
  eine spätere Verschlüsselung an
  ([Idee Umschlagverschlüsselung](../ideen/verschluesselung-differenzierte-aufbewahrung.md)).
  Bis dahin gilt [ADR-22](ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md): Die
  KOBIL-Aktivierungsdaten liegen während der Einrichtung im Klartext und werden danach geleert.
- **Schema je Modul:** Die Arbeitsdaten liegen nicht mehr im Schema ihres Moduls. Das ist gewollt:
  Sie sind Teil der Tool-Sitzung, und ihr Inhalt bleibt Sache des Moduls.
  `ToolSessionCoverageTest` wacht darüber, dass kein Modul wieder eine eigene `*_tool_session`-Tabelle anlegt.

**Erwogene Alternativen.**

- **Eine gemeinsame Tabelle `orchestrator.tool_session_data`.** Verworfen: eine zweite Zeile mit
  derselben Id, eigenem Aufräumen und später eigenen Partitionen, ohne etwas zu gewinnen.
- **Eine gleich gebaute Tabelle je Modulschema.** Hält „ein Schema je Modul“ ein, braucht aber elf
  Tabellen, elf Migrationen und elffaches Partitionieren – das, was ADR-44 bei den Zählern verworfen hat.
- **Ein Schlüssel-Wert-Speicher (Redis mit TTL).** Bleibt möglich: Der Port `ToolSessionData`
  verbirgt, wo die Daten liegen. Heute würde er einen zweiten Speicher neben der Datenbank bedeuten,
  ohne Transaktion mit der Journey.
