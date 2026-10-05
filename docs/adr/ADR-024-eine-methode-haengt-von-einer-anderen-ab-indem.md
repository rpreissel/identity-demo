# ADR-24: Ein Verfahren hängt von einem anderen ab, indem es dessen Angabe verlangt

**Status:** umgesetzt.

**Kontext**: Ein **Anmeldeverfahren** ist etwas, mit dem sich der Kontoinhaber anmelden kann, etwa
ein Passwort oder ein Code per E-Mail. Ein Konto sammelt außerdem **Angaben** über seinen Inhaber,
etwa die bestätigte E-Mail-Adresse (siehe [Glossar](../glossar/glossar.md)). Manche Verfahren
funktionieren nur, wenn eine bestimmte Angabe vorhanden ist. Das Passwort setzt zum Beispiel eine
bestätigte E-Mail-Adresse voraus. Die Frage ist: Wie drückt man solche Abhängigkeiten aus? Und was
passiert mit einem Verfahren, wenn die Angabe, die es braucht, später wegfällt?

**Entscheidung**: Abhängigkeiten zwischen Verfahren brauchen keine eigenen Begriffe. Ein Modul
schreibt beim Einrichten eine Angabe, ein anderes verlangt sie per `ClaimRequirement`. Die Liste
dieser Anforderungen eines Tools heißt `requires`. Sie entscheidet nicht nur darüber, ob ein Tool
**angeboten** wird, sondern gilt **dauerhaft**: Fällt die Angabe weg, wird auch das Credential
entfernt, das sie verlangt hat. Das setzt sich fort über alles, was seinerseits davon abhängt, bis sich
nichts mehr ändert (`CredentialRules.dependentsOfLostClaims`, früher im `JourneyActionExecutor`).

Heute wird das für die bestätigte Adresse genutzt: `enroll-password` und `enroll-email` verlangen
`ClaimRequirement(EMAIL, PROVEN)`. Wird die Adresse zurückgenommen, entfallen also auch Passwort und
E-Mail-Login.

Damit eine bestätigte Adresse überhaupt verloren gehen kann, lässt sie sich **direkt** zurücknehmen
(`AccountService.retractAttribute`, `DELETE /channels/{id}/attributes/{attribute}`). Das ist einer der
Auslöser in der Liste von [ADR-12](ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md). Vorher
konnte kein Widerruf die Adresse betreffen. Denn `confirm-email` schreibt seine Angabe ohne
`auth_method_id`, und EMAIL gehört dem Konto selbst.

`enroll-password` behauptet zusätzlich die Angabe `PASSWORD_EXISTS`, die derzeit niemand verlangt.
Sie bleibt deklariert, weil sich damit eine Abhängigkeit vom Passwort ausdrücken ließe. Beim nächsten
Bedarf müsste man dann keinen zweiten Mechanismus bauen.

Die Vorhersage, was bei einem Widerruf mit entfällt, muss genau mit dem Schreiben übereinstimmen.
Welche Typen von Angaben mit einer widerrufenen Instanz entfallen, ermittelt deshalb
`AccountService.claimedTypesOf`. Es nutzt dafür dieselbe Abfrage und denselben `MethodModule`-Filter
wie der Widerruf selbst.

**Erwogene Alternative**: Eine eigene Eigenschaft in der Beschreibung des Tools,
`dependsOnMethods: Set<String>`, die Namen von Verfahren nennt. Sie wurde zuerst so gebaut und dann
wieder zurückgenommen. Sie hätte neben dem Modell der Angaben eine zweite Art eingeführt,
Abhängigkeiten auszudrücken. Und sie hätte die Abhängigkeit über die Adresse gar nicht erfasst, weil
dort kein Verfahren beteiligt ist.

**Begründung**: Das Log der Angaben (Claim-Log) verwaltet ohnehin genau die Lebensdauer, um die es
geht. Eine Abhängigkeit als verlangte Angabe auszudrücken, braucht deshalb keinen neuen Mechanismus.
Und es wirkt auch dort, wo kein Verfahren wegfällt, sondern ein Attribut.

**Folgen und Kosten**:
- `requires` bedeutet mehr als früher, und jede Anforderung hat diese Bedeutung. Das ist an drei
  Stellen unkritisch:
  - Für `EMAIL`, weil die Adresse nur über den ausdrücklichen Endpunkt verloren gehen kann.
  - Beim Ersetzen eines **Ankers** (einer Angabe, über die sich ein Konto eindeutig wiederfinden
    lässt). Hier wird im selben Schritt der neue Wert behauptet, und deshalb löst das Ersetzen nichts
    aus.
  - Bei der Anforderung von `ident-kvnr` (`FAMILY_NAME`/`GIVEN_NAMES`/`BIRTH_DATE`, siehe
    [ADR-18](ADR-018-bestaetigen-und-zuordnen-sind-zwei-akte.md)). Sie betrifft nur das Angebot, kein
    Credential.
- Ein Attribut zurückzunehmen hat weite Folgen. Das ist gewollt und wird vorher geprüft: Die Prüfung
  des Mindestniveaus rechnet mit allem, was mit entfällt. Eine Ablehnung nennt es beim Namen.
- Mit `PASSWORD_EXISTS` enthält `AttributeType` eine Aussage, die nichts über die Person sagt, sondern
  etwas über die Credentials des Kontos.

**Geschichte**: `enroll-kobil` verlangte zunächst `PASSWORD_EXISTS`. Für eine Bindung an KOBIL war
damit ein Passwort im Konto Pflicht. Das war fachlich falsch: Bei jeder Registrierung, die noch kein
Passwort hatte, ließ sich KOBIL dadurch nicht einrichten. Der Mechanismus blieb, aber diese
Abhängigkeit wurde entfernt. Stattdessen gilt jetzt, was
[ADR-21](ADR-021-der-kobil-pin-liegt-im-backend-und-das.md) beschreibt. Seit ADR-17 verlangt auch
`enroll-email` die bestätigte Adresse.
