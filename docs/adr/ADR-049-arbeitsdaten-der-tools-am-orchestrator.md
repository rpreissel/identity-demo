# ADR-49: Die Arbeitsdaten der Tools liegen als JSON an der Tool-Sitzung des Orchestrators

**Status:** umgesetzt 2026-10-03 (Issue `DPoP-demo-x69i`).

**Entscheidung.** Ein [Tool](../glossar/glossar.md) ist ein abgeschlossener Arbeitsschritt, den ein
Nutzer durchläuft, etwa „SMS einrichten“. Jedes Mal, wenn ein Tool gestartet wird, entsteht eine
Tool-Sitzung (ein [Tool-Durchlauf](../glossar/glossar.md)). Sie lebt oft nur wenige Minuten. In
dieser Zeit muss sich das Tool einiges merken, zum Beispiel:

- den Hash des Codes, den es ausgegeben hat,
- die Ablaufzeit,
- die Nummer, die der Nutzer eingegeben hat,
- die Aktivierungsdaten von KOBIL.

Diese Arbeitsdaten speichert ein Tool über den Port `tool_api.ToolSessionData`. Ein Port ist eine
fest vereinbarte Schnittstelle; das Tool weiß dadurch nicht, wo die Daten am Ende liegen. Der
[Orchestrator](../glossar/glossar.md), also der Server, der die Abläufe steuert, führt zu jedem
Durchlauf ohnehin eine Zeile in `orchestrator.tool_session`. In deren Spalte `data` legt er die
Arbeitsdaten als JSON ab. Die Spalte `data_type` nennt Modul und Klasse des Zustands, zum Beispiel
`auth_sms.AuthSmsToolSession`. Ein Tool hält seinen Zustand als einfache, unveränderliche
Datenklasse. Nach jeder Änderung ersetzt es den Zustand als Ganzes (`save`).

Der Orchestrator stellt dem Tool dabei nur den Speicher bereit. Das ist dasselbe Muster wie bei
den Zählern ([ADR-44](ADR-044-zaehlwerk-im-orchestrator-regeln-in-den-modulen.md)). Welche Daten zu welchem Modul
gehören, bestimmt die Klasse des Zustands: Ein Modul liest nur Zustände, die es selbst deklariert
hat. Findet es einen fremden Typ vor, ist das ein Vertragsfehler.

**Warum.** Bisher brachte jedes Tool drei eigene Teile mit: eine JPA-Entität, ein Repository und
eine Tabelle `<modul>.<rolle>_tool_session`. Jedes Modul hatte dazu einen eigenen Aufräumjob. Das
ergab 23 Entitäten, 23 Tabellen und zehn Jobs. Alle 23 Repositories taten dasselbe: Sie lasen und
schrieben nur über die `tool_session_id` und löschten nach Alter. Genau das kann die Zeile im
Orchestrator schon. Sie hat dieselbe Id, eine Ablaufzeit, einen Status, eine Versionsnummer und ein
eigenes Aufräumen. Für ein neues Tool schreibt man jetzt nur noch eine Datenklasse statt drei
Dateien und einer Migration.

**Folgen.**

- **Aufräumen:** Die Arbeitsdaten werden mit ihrer Zeile gelöscht. `RetentionJob` löscht
  `orchestrator.tool_session` nach der Frist `tool-session.retention`. Die Arbeitsdaten selbst
  leert schon der Abschluss eines Durchlaufs (`DONE`, `ABANDONED`,
  `SessionManagementService.endToolSession`). Das ist wichtig, denn manche Arbeitsdaten sind
  Personendaten, etwa bei `ident-fsc` die KVNR, die Partnernummer, der Name und das Geburtsdatum.
  Bis zum Abschluss liegen sie in `orchestrator.tool_session.data`, verschlüsselt unter dem
  Datenschlüssel ihres Tages ([ADR-53](ADR-053-arbeitsdaten-und-app-tokens-verschluesselt.md)).
  Eine Sitzung, die nie abgeschlossen wird, behält sie bis zum Ende der
  Aufbewahrungsfrist. Die Module räumen nur noch eigene kurzlebige Daten auf, die keine
  Tool-Sitzung sind (`auth_qr.login_request`, `ToolSessionSweeper`).
- **Gleichzeitige Schreiber:** Die Versionsnummer der Zeile (`@Version`) schützt auch die
  Arbeitsdaten. Schicken zwei Anfragen gleichzeitig ein PATCH an dieselbe Sitzung, bekommt die
  zweite `409`. Ohne diesen Schutz würde die spätere Anfrage die frühere unbemerkt überschreiben.
- **Rollierende Deploys:** Während eines Updates laufen alte und neue Server nebeneinander. Damit
  das klappt, liest `ToolSessionDataCodec` tolerant: Unbekannte Felder überspringt er, fehlende
  bekommen ihren Kotlin-Standardwert. Ein Zustand gibt deshalb jedem Feld, das später hinzukommt,
  einen Standardwert.
- **Ids** sind zeitlich sortiert (UUIDv7). So stehen neue Zeilen am Ende des Index.
- **Skalierung:** Bei 10 Mio. Nutzern entstehen rund 5 Mio. Tool-Sitzungen am Tag. In der Spitze
  sind das etwa 2.000 Schreibvorgänge pro Sekunde über den Primärschlüssel. Das ist dieselbe Last
  wie vorher, nur in einer Tabelle statt in 24. Beim Wechsel auf PostgreSQL (`DPoP-demo-pi55`) wird
  diese eine Tabelle nach Tag partitioniert. Aufgeräumt wird dann per `DROP PARTITION` statt
  per `DELETE`.
- **Verschlüsselung:** `ToolSessionDataService` ist die einzige Stelle, die Zustände schreibt und
  liest. Dort liegt die Verschlüsselung mit einem Datenschlüssel je Tag
  ([ADR-53](ADR-053-arbeitsdaten-und-app-tokens-verschluesselt.md)); der Codec serialisiert nur.
  Bis dahin gilt [ADR-22](ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md): Die
  KOBIL-Aktivierungsdaten liegen während der Einrichtung im Klartext und werden danach geleert.
- **Schema je Modul:** Die Arbeitsdaten liegen nicht mehr im Datenbankschema ihres Moduls. Das ist
  gewollt. Sie gehören zur Tool-Sitzung, und ihr Inhalt bleibt trotzdem Sache des Moduls.
  `ToolSessionCoverageTest` achtet darauf, dass kein Modul wieder eine eigene
  `*_tool_session`-Tabelle anlegt.

**Erwogene Alternativen.**

- **Eine gemeinsame Tabelle `orchestrator.tool_session_data`.** Verworfen. Das wäre eine zweite
  Zeile mit derselben Id, mit eigenem Aufräumen und später eigenen Partitionen. Gewonnen wäre damit
  nichts.
- **Eine gleich gebaute Tabelle in jedem Modulschema.** Damit bliebe die Regel „ein Schema je Modul“
  eingehalten. Es bräuchte aber elf Tabellen, elf Migrationen und elffaches Partitionieren. Genau
  das hat ADR-44 bei den Zählern verworfen.
- **Ein Schlüssel-Wert-Speicher (Redis mit TTL).** Das bleibt möglich, denn der Port
  `ToolSessionData` verbirgt, wo die Daten liegen. Heute würde es aber einen zweiten Speicher neben
  der Datenbank bedeuten, ohne gemeinsame Transaktion mit der Journey.
