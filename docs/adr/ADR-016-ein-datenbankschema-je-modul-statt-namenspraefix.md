# ADR-16: Ein Schema und ein Migrationsordner je Modul

**Status:** umgesetzt.

**Kontext**: Die Anwendung ist in **Module** geteilt, die nur über festgelegte Schnittstellen
miteinander reden. Ein Beispiel ist das Modul für SMS (`auth_sms`), ein anderes der Orchestrator
selbst (siehe [Glossar](../glossar/glossar.md)). Im Code prüft ein Test diese
Grenzen. In der Datenbank lagen aber früher alle Tabellen nebeneinander, und eine einzige
Migrationsdatei legte sie alle an. (Eine **Migration** ist ein SQL-Skript, das das
Datenbankschema Schritt für Schritt aufbaut oder ändert.) Diese ADR legt fest, wie die Modulgrenzen
auch in der Datenbank sichtbar werden.

**Entscheidung**: Die Modulgrenze gilt auch in der Datenbank, und zwar in zweifacher Hinsicht.

- **Ein Datenbankschema je Modul.** Jede Tabelle liegt im Schema ihres Moduls, etwa
  `account.anchor`, `auth_sms.enrollment` oder `orchestrator.channel_session`. Tabellen, Indizes und
  Constraints haben kein Modulpräfix im Namen (`ux_anchor_value`). Fremdschlüssel gibt es nur
  innerhalb eines Schemas. Ein Verweis in ein anderes Modul ist eine einfache Spalte mit Index.
- **Ein Migrationsordner je Modul.** Jedes Modul bringt sein Schema selbst mit, im Ordner
  `db/migration/<modul>/`. Anfangs ist das meist eine einzige Datei; spätere Änderungen kommen als
  weitere Dateien dazu. Im obersten Verzeichnis der Migrationen liegt keine SQL-Datei. Die Ordner
  findet `ModuleMigrationLocations` beim Start selbst; eine gepflegte Liste gibt es nicht.

Die gemeinsamen Regeln für Namen, Typen, Versionsnummern und deren Reihenfolge stehen in
[`db/migration/KONVENTIONEN.md`](../../src/main/resources/db/migration/KONVENTIONEN.md).

**Erwogene Alternativen**:

- Alle Tabellen nebeneinander, mit dem Modulnamen als Präfix (`auth_sms_enrollment`,
  `orchestrator_channel_session`). So war es früher.
- Eine gemeinsame Migrationsdatei für alle Module. So war es bis zur Aufteilung: `V1__schema.sql`
  legte auf 589 Zeilen die Tabellen aller Module an.
- Die gemeinsame Datei als Grundbestand stehen lassen und nur neue Migrationen in Modulordner legen.
  Dann wäre keine bestehende Datenbank ungültig geworden. Aber fast das ganze Schema wäre weiter in
  einer gemeinsamen Datei geblieben.

**Warum diese**: Ein Präfix ist eine Vereinbarung, an die sich jemand halten muss. Ein Schema ist
eine Struktur, die sich nicht umgehen lässt. Eine Tabelle kann so nicht versehentlich im falschen
Modul entstehen. Die wichtigste Regel dieses Aufbaus lautet: Fremdschlüssel nur innerhalb eines
Moduls. Sie steht damit in den Tabellendefinitionen selbst.

Dasselbe gilt für die Migrationen. Jedes Modul hat seine eigenen Controller und seine eigene Logik
zum Aufräumen. Also gehören auch seine Tabellen in seinen eigenen Ordner. Solange es eine gemeinsame
Datei gab, musste jedes neue Tool-Modul eine Datei ändern, die allen Modulen gehörte. Soll ein Modul
später ein eigener Dienst werden, ist klar, wo die Grenze verläuft.

**Kosten**:

- Jede Abfrage, jede `@Table`-Annotation und jedes Verwaltungswerkzeug muss das Schema mit angeben.
  Ein `SELECT ... FROM anchor` ohne Schema findet nichts, weil der Suchpfad auf `PUBLIC` steht. Dort
  liegt bewusst auch `flyway_schema_history`.
- Ein Tabellenname ist nur noch innerhalb eines Schemas eindeutig. Sechs Module haben zum Beispiel
  eine Tabelle `enroll_tool_session`.
- Die Versionsnummern laufen trotzdem über alle Module hinweg durch, weil Flyway (das Werkzeug, das
  die Migrationen ausführt) eine einzige Historie führt.

**Rückblick**: Das Schema wurde dreimal neu aufgesetzt:

1. Zuerst ersetzte ein Neubau die Migrationen des ursprünglichen Codes (ADR-4).
2. Dann fasste eine zweite Ausgangsbasis 37 Migrationen zusammen und bereinigte das Kontomodell
   (siehe [ADR-14](ADR-014-schema-zusammengefuehrt-das-konto-als-sperrpunkt-eine-wahrheit.md)).
3. Zuletzt wurde die gemeinsame Datei in einen Ordner je Modul aufgeteilt. Das war früher eine eigene
   Entscheidung, ADR-30.

Dabei ändern sich die Prüfsummen von Migrationen, die schon angewendet wurden. Deshalb muss jede
Datenbank mit altem Stand neu angelegt werden. Lokal übernimmt das `FlywayResetConfig`: Es löscht
eine H2-Datei, die nicht mehr passt, und baut sie neu auf. Die Testdaten kommen aus
`demo_seed/V16__testdata.sql`. Mit Produktivdaten wäre keiner dieser Schritte vertretbar.
