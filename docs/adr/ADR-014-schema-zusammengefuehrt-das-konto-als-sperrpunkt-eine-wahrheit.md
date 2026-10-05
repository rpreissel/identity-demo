# ADR-14: Das Konto als gemeinsame Sperre, jeder Fakt an genau einer Stelle

**Status:** umgesetzt.

**Kontext**: Ein Konto besteht aus vielen Teilen: seinen **Ankern** (Angaben, über die es sich
eindeutig wiederfinden lässt, etwa die bestätigte E-Mail-Adresse), seinen Anmeldeverfahren und der
Geschichte seiner Angaben (siehe [Glossar](../glossar/glossar.md)). Mehrere Vorgänge können
gleichzeitig dasselbe Konto ändern wollen. Dann muss einer warten oder abgewiesen werden, sonst
überschreiben sie sich gegenseitig. Außerdem soll jede Information nur an einer Stelle stehen. Steht
sie an zwei Stellen, können die beiden Stellen voneinander abweichen. Früher war beides nicht sauber
gelöst. Diese ADR legt fest, wie das Kontomodell in der Datenbank aufgebaut ist.

**Entscheidung**: Das Kontomodell folgt zwei Regeln.

- **Die Kontozeile enthält nur die Identität des Kontos und dient als Sperre.** Die Tabelle
  `account.account` hat nur die Spalten `id`, `created_at` und `version`. Wer den aktuellen Zustand
  eines Kontos ändert, lädt diese Zeile mit `OPTIMISTIC_FORCE_INCREMENT` (`AccountRepository`). Damit
  wird beim Speichern die Versionsnummer der Zeile erhöht. Schreiben zwei Vorgänge gleichzeitig,
  bemerkt der zweite, dass sich die Version inzwischen geändert hat, und bekommt
  `409 CONCURRENT_MODIFICATION`.
- **Jeder Fakt steht an genau einer Stelle.** Es gibt zwei Arten von Tabellen:
  - Der **aktuelle Zustand**. Wer ihn ändert, erhöht die Version des Kontos. Dazu gehört
    `account.anchor`. Das ist der einzige Speicherort der Kennungen, die das Konto selbst führt, etwa
    PersonId, Mitgliedsnummer und bestätigte E-Mail-Adresse. Außerdem gehört `account.auth_method`
    dazu, mit einer Zeile je eingerichtetem Verfahren; der `EnrollmentRef` steht dort in eigenen
    Spalten.
  - Die **Historie**. Hier kommen Zeilen nur hinzu, und die Version des Kontos ändert sich nie. Dazu
    gehören `account.claim` (wer was wann bestätigt hat), `account.change_log` (IDENTIFIED) (jede
    Identifizierung) und `account.retraction` (Widerrufe, siehe
    [ADR-12](ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md)).

Welche Attribute das Konto selbst führt und welche es nur liest, beschreibt
[Domänenmodell](../02-domaenenmodell.md), Abschnitt 6 (`AttributeType.authority`).

**Erwogene Alternative**: Das Modell so lassen, wie es war. Dort standen Listen als JSON in der
Kontozeile (`identifications`, `authentication_methods`). Neben den Ankern gab es außerdem
abgeleitete Spalten (`person_id`, `email`, `email_confirmed_at`).

**Warum diese**: Rechnet man mit zehn Millionen Konten und mehr und mit langer Laufzeit, dann
lassen sich JSON-Listen nicht abfragen, etwa „alle Konten mit Verfahren X“. Sie lassen sich auch
nicht günstig schreiben, denn jede Änderung schreibt die ganze Zeile neu. Und jede abgeleitete
Spalte neben einem Anker ist eine zweite Stelle, die für denselben Fakt Eindeutigkeit garantieren
müsste. Genau diesen Fehler musste ein früherer Review-Befund (A3) schon einmal beheben. Mit einer
Zeile je Fakt bedeuten „wird zu einem gültigen Wert zusammengefasst“ und „ist ein Anker“ dasselbe.

**Kosten**: Um ein Kontoprofil zu lesen, braucht der Server drei Lesezugriffe über einen Index statt
einem. Die E-Mail-Adresse im Profil steht in normalisierter Form. Die ursprüngliche Schreibweise
steht nur noch im Log der Angaben (Claim-Log).

**Rückblick**: Das Modell kam zusammen mit einer neuen Ausgangsbasis für die Migrationen. Diese
Ausgangsbasis fasste 37 aufeinander aufbauende Migrationen zusammen. Wie die Migrationen heute
aufgeteilt sind, regelt [ADR-16](ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md).
