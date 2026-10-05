# ADR-10: Interessent ist Konto-Zustand, kein eigener AuthIntent

**Status:** umgesetzt.

**Kontext**: Ein **Intent** (`AuthIntent`) ist das Anliegen, mit dem ein Nutzer kommt, etwa sich
registrieren oder sich anmelden. Zu jedem Intent gehört ein fester Ablauf, die **Journey**. Ein
**Interessent** ist jemand, dessen Konto keiner Person im Personenverzeichnis zugeordnet ist, also
den Stammdaten der Versicherung. Das passiert etwa, wenn eine Identifizierung keinen passenden
Datensatz findet. Die Begriffe erklärt auch das [Glossar](../glossar/glossar.md). Die Frage war, ob
der Interessent einen eigenen Intent mit eigenem Ablauf bekommt oder ob er nur ein Zustand des Kontos
ist.

**Entscheidung**: Es gibt keinen eigenen `AuthIntent.INTERESSENT`. Ein Interessent ist ein Konto, das
nur über bestätigte Angaben (Claims) identifiziert ist und keine zugeordnete `person_id` hat. Das
beschreibt, wie eine Identifizierung ausgegangen ist. Ein Ziel, das man wählen könnte, ist es nicht.

Die Journey `REGISTER` verzweigt nach dem Ergebnis der Suche nach dem Konto (`Resolution`). Das gilt
auch für jeden anderen Intent, der eine Identifizierung durchläuft. Es gibt zwei Ergebnisse:

- `ExistingAccount`: Ein **Anker** passt, also eine Angabe, über die sich ein Konto eindeutig
  wiederfinden lässt. Dann wird die Journey wie bisher an dieses Konto gebunden.
- `Unresolved`: Kein Anker passt. Dann geht es mit dem Konto ohne `person_id` weiter.

Seit ADR-19 gibt es keinen dritten Ausgang mehr.

**Erwogene Alternative**: Ein eigener `AuthIntent` mit eigener Journey, eigenen Zuständen und eigener
Strategie. Das wäre begründet, wenn für Interessenten andere Regeln gälten.

**Warum diese**: `AuthIntent` benennt nach seiner eigenen Definition
([AuthIntent.kt](../../src/main/kotlin/com/example/identity/core/orchestrator/domain/AuthIntent.kt))
ein Ziel samt Strategie. Er beschreibt nie, was aus einem Durchlauf geworden ist. Der Ablauf der
Journey ist für beide Ausgänge gleich aufgebaut; nur die Suche nach dem Konto unterscheidet sich.

Die Behandlung von `Action.RecordIdentification` deckt den Fall `personId == null` schon heute ab,
und zwar als Verzweigung innerhalb der bestehenden Journey. Bei `REGISTER` ist das im Experiment
„Erst Anmeldeverfahren einrichten“ der Fall (siehe
[register-enroll-first.md](../journeys/register-enroll-first.md)). Ein eigener Intent müsste außerdem
jede künftige Verzweigung doppelt führen, etwa `STEP_UP` und `RE_IDENTIFY` für Konten von
Interessenten.

Was einen bestätigten Interessenten ausmacht und wie er einer Person zugeordnet wird, legt
[ADR-18](ADR-018-bestaetigen-und-zuordnen-sind-zwei-akte.md) fest. Seit
[ADR-34](ADR-034-personenverzeichnis-meldet-aenderungen.md) ist der Interessent eine von drei Rollen
eines Kontos, neben Versichertem und Partner. Alle drei Rollen ergeben sich aus den Ankern, und keine
ist ein Intent.

**Kosten**: Was nur für Interessenten gilt, steht als Verzweigung in den bestehenden Strategien (wie
bei `ConfirmDeviceRebind`) und nicht in einer eigenen Strategie. Die Strategien enthalten dadurch mehr
Fallunterscheidungen.

---
