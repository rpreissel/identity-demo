# ADR-2: Zustand statt Vererbung bei `AuthJourney`

**Status:** umgesetzt.

**Kontext**: Eine **Journey** ist ein laufender Ablauf mit mehreren Schritten, etwa eine
Registrierung oder eine Anmeldung. Jede Journey gehört zu einem **Intent**, also dem Anliegen, mit
dem der Nutzer kommt (sich registrieren, sich anmelden, sein Sicherheitsniveau erhöhen). Beide
Begriffe erklärt auch das [Glossar](../glossar/glossar.md). Je nach Intent muss sich eine Journey
ganz verschiedene Dinge merken. Die Frage ist, wie diese unterschiedlichen Daten in der Datenbank
abgelegt werden.

**Entscheidung**: Die Journey (`AuthJourney`) ist eine flache Entität. Sie hat keine Unterklassen und
keine eigene Tabelle je Intent. Was sich je Intent unterscheidet, steckt in zwei Feldern: `stateType`
sagt, um welche Art von Zustand es sich handelt (Typkennung), und `state` enthält den Zustand selbst
als JSON. Mehr dazu in [Domänenmodell](../02-domaenenmodell.md) Abschnitt 2.

**Erwogene Alternative**: Eine Tabelle je Intent. Oder eine gemeinsame Tabelle für alle Unterklassen
(Single-Table-Vererbung), in der jedes Feld eines Intents eine eigene Spalte ist.

**Warum diese**: Die Intents brauchen sehr unterschiedliche Felder. `REGISTER` hat zum Beispiel andere
Zwischenzustände als `STEP_UP`. Das Verhalten dazu gehört in Services, etwa in die Richtlinie für
Sicherheitsniveaus (`AuthPolicy`) und in den Tool-Katalog. Eine Spalte je Feld ergäbe eine breite
Tabelle, deren Felder überwiegend leer wären. Nach `stateType` lässt sich trotzdem in der Datenbank
suchen.

**Kosten**: Den Inhalt von `state` kann die Datenbank nicht auswerten. Constraints und
Fremdschlüssel auf einzelne Felder im JSON sind nicht möglich. Dass die Daten zusammenpassen, muss
deshalb die Strategie jedes Intents (`IntentStrategy`) selbst sicherstellen.

---
