# ADR-17: Adresse bestätigen und E-Mail-Login einrichten sind zwei Schritte

**Status:** umgesetzt.

**Kontext**: Ein **Tool** ist ein abgeschlossener Arbeitsschritt, den der Nutzer durchläuft, etwa
„SMS einrichten“. Ein **Anmeldeverfahren** ist etwas, mit dem sich der Kontoinhaber später anmelden
kann, etwa ein Passwort oder ein Code per E-Mail (siehe [Glossar](../glossar/glossar.md)). Früher hat
ein einziges Tool zwei Dinge auf einmal getan: Es hat die E-Mail-Adresse bestätigt und zugleich die
Anmeldung per E-Mail-Code eingerichtet. Die Adresse brauchen aber auch andere Teile des Systems,
etwa die Anmeldung mit Passwort. Entfernte der Nutzer das E-Mail-Verfahren, verschwand mit ihm die
Adresse. Diese ADR trennt die beiden Schritte.

## Entscheidung

Eine E-Mail-Adresse zu bestätigen ist ein eigenes Tool, `confirm-email`. Es hat die Rolle
`ATTESTATION` (Bestätigen) und ein eigenes Ergebnis, `ToolOutcome.Completed.Attested`. Dieses
Ergebnis enthält Angaben (Claims), aber keinen `enrollmentRef`, kein `amr`, kein eigenes Niveau und
kein eingerichtetes Verfahren. Die bestätigte Adresse wird damit Teil des Kontos und ist kein
Verfahren.

`enroll-email` richtet danach nur noch die Anmeldung per E-Mail-Code ein. Es setzt eine bestätigte
Adresse voraus (`ClaimRequirement(EMAIL, PROVEN)`). Deshalb braucht es keinen eigenen Austausch von
Codes mehr: Schon die Aktivierung schließt es ab. Wie eine solche Abhängigkeit über verlangte Angaben
funktioniert, beschreibt [ADR-24](ADR-024-eine-methode-haengt-von-einer-anderen-ab-indem.md).

Die Registrierung läuft in dieser Reihenfolge (`RegisterStrategy`):

1. Adresse bestätigen (`RegisterState.ConfirmingEmail`).
2. Ein Anmeldeverfahren einrichten (`Enrolling`).
3. Unter einer Bedingung zusätzlich ein Verfahren anderer Art einrichten, etwa ein Passwort oder
   eine Gerätebindung (`RegisterState.SecondFactorKindObligation`). Die Bedingung: Das Konto könnte
   `loa2` sonst nicht erreichen, und seine Verfahren decken nur eine Faktorart ab. Eine **Faktorart**
   ist die Art eines Beweises: etwas, das man weiß, etwas, das man hat, oder etwas, das man ist.

Diese Pflicht zur zweiten Faktorart gilt nach derselben Regel auf beiden Kanälen und im Experiment
„Erst Anmeldeverfahren einrichten“. Beschrieben ist sie in der
[Orchestrierung](../04-orchestrierung.md#eine-dritte-pflicht-auf-einen-intent-begrenzt).

## Begründung

Die Adresse gehört dem Konto, nicht einem Verfahren (`AttributeType.authority` ist
`AttributeAuthority.Local`). Drei Verfahren finden das Konto über die Adresse, wenn man sich mit der
E-Mail-Adresse anmeldet. Und `enroll-password` setzt sie voraus. Sie gehört also zur Grundausstattung
des Kontos. Weil Bestätigen und Einrichten jetzt getrennt sind, verschwindet die Adresse nicht mehr,
wenn der Nutzer das E-Mail-Verfahren entfernt.

Früher entstand beim Bestätigen der Adresse nebenbei ein Wissensfaktor. Seit das nicht mehr so ist,
braucht die Registrierung eine eigene Regel für `loa2`: ein Verfahren anderer Art, aber nur dann,
wenn das Konto `loa2` sonst nicht erreicht. Welche Tools dafür in Frage kommen, entscheiden die
Faktorarten im Katalog, nicht ein fest genannter Verfahrensname. Ein Geräteschlüssel deckt Besitz,
Wissen und Biometrie zugleich ab und genügt dafür allein.

**Erwogene Alternative:** Alles beim Alten lassen und die Verbindung nur dokumentieren. Dann würde
`enroll-email` weiterhin die Adresse bestätigen *und* das Verfahren anlegen.

## Folgen

- Die Registrierung hat einen Schritt mehr, und der Katalog hat ein Tool mehr.
- Die frühere Ungleichheit „Passwort nur im Web“ ist weg.

**Nachtrag 2026-09-28: warum die Mobilnummer kein Anker ist.** E-Mail-Adresse und Mobilnummer sind
gleichartig: Beide weist man mit einem zugeschickten Code nach. Der Unterschied liegt darin, wer sie
braucht. Die Adresse brauchen die Lookup-Tools (Tools, die ein Konto anhand der Eingabe suchen), die
Suche in Keycloak und `enroll-password` als Voraussetzung. Sie muss also bestehen bleiben, auch wenn
das E-Mail-Verfahren entfernt wird. Deshalb ist sie ein **Anker** des Kontos, also eine Angabe, über
die sich das Konto eindeutig wiederfinden lässt. Die Nummer braucht nur das SMS-Verfahren. Wird es
entfernt, wird auch die Nummer zurückgenommen (`AttributeAuthority.MethodModule`). Braucht künftig
ein Lookup oder ein anderes Verfahren die Nummer, wird sie wie die Adresse ein Anker und bekommt ein
eigenes Tool `confirm-phone`.

## Geschichte

- Die erste Fassung nannte die Reihenfolge „Adresse, Passwort, Besitzfaktor“. Der Ablauf war aber
  immer: Adresse, Verfahren, Passwort. Die Passwortpflicht prüft erst am Ende, ob das Konto `loa2`
  sonst erreicht.
- Seit 2026-09-23 prüft auch `RegisterEnrollFirstStrategy` dieselbe Bedingung für `loa2`. Vorher
  fragte sie ohne Bedingung nach dem Passwort. Am Verhalten änderte das nichts. Dort wird ohne
  Identifizierung jedes Verfahren auf `loa1` eingerichtet. Und `enrolledUnderAcr` verhindert, dass
  ein zweites Verfahren das Niveau anhebt (siehe
  [ADR-5](ADR-005-drei-obergrenzen-fuer-das-sicherheitsniveau.md)). Dort ist `loa2` also allein mit
  den eingerichteten Verfahren nie erreichbar, und das Passwort wird weiterhin immer verlangt.
- Mit der Einführung mussten mehrere Integrationstests ihre Erwartung ändern
  (`sms + email` → `sms + password`).
- Bis 2026-09-26 hieß die Pflicht Passwortpflicht (`PasswordObligation`) und bot nur
  `enroll-password` an. Seitdem bietet sie jedes Verfahren an, das eine fehlende Faktorart beiträgt,
  in der App also auch Gerätebindung und KOBIL.
