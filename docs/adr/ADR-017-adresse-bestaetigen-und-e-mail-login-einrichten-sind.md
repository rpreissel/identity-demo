# ADR-17: Adresse bestätigen und E-Mail-Login einrichten sind zwei Schritte

**Status:** umgesetzt.

## Entscheidung

Eine E-Mail-Adresse zu bestätigen ist ein eigenes Tool, `confirm-email`. Es hat die Rolle
`ATTESTATION` und ein eigenes Ergebnis,
`ToolOutcome.Completed.Attested`: mit Claims, aber ohne `enrollmentRef`, ohne `amr`, ohne eigenes
Niveau und ohne eingerichtetes Verfahren. Die bestätigte Adresse wird damit Teil des Kontos und kein Verfahren.

`enroll-email` richtet danach nur noch die Anmeldung per E-Mail-Code ein. Es setzt eine bestätigte
Adresse voraus (`ClaimRequirement(EMAIL, PROVEN)`) und braucht deshalb keinen eigenen Code-Austausch
mehr: Die Aktivierung schließt es bereits ab. Wie diese Abhängigkeit über verlangte Angaben
funktioniert, beschreibt [ADR-24](ADR-024-eine-methode-haengt-von-einer-anderen-ab-indem.md).

Die Registrierung läuft in dieser Reihenfolge (`RegisterStrategy`):

1. Adresse bestätigen (`RegisterState.ConfirmingEmail`),
2. ein Anmeldeverfahren einrichten (`Enrolling`),
3. falls das Konto sonst `loa2` nicht erreichen könnte und seine Verfahren nur eine Faktorart
   abdecken, zusätzlich ein Verfahren anderer Art, etwa ein Passwort oder eine Gerätebindung
   (`RegisterState.SecondFactorKindObligation`).

Diese Pflicht zur zweiten Faktorart gilt auf beiden Kanälen und im Experiment „Erst
Anmeldeverfahren einrichten“ nach derselben Regel. Sie ist in der
[Orchestrierung](../04-orchestrierung.md#eine-dritte-pflicht-auf-einen-intent-begrenzt) beschrieben.

## Begründung

Die Adresse gehört dem Konto, nicht einem Verfahren (`AttributeType.authority` ist
`AttributeAuthority.Local`). Drei Verfahren finden das Konto über sie, wenn man sich mit der
E-Mail-Adresse anmeldet, und `enroll-password` setzt sie voraus. Sie gehört also zur Grundausstattung
des Kontos. Weil Bestätigen und Einrichten getrennt sind, nimmt das Entfernen des E-Mail-Verfahrens
die Adresse nicht mehr mit.

Früher entstand der Wissensfaktor nebenbei, wenn man die Adresse bestätigte. Seit das nicht mehr so
ist, braucht die Registrierung eine eigene Regel für `loa2`: ein Verfahren anderer Art, aber nur
dann, wenn das Konto `loa2` sonst nicht erreicht. Welche Tools das sind, entscheiden die Faktorarten
im Katalog, nicht ein fest genannter Verfahrensname. Ein Geräteschlüssel deckt Besitz, Wissen und
Biometrie zugleich ab und genügt dafür allein.

**Erwogene Alternative:** alles beim Alten lassen und die Verbindung nur dokumentieren, also
`enroll-email` bestätigt die Adresse *und* legt das Verfahren an.

## Folgen

- Ein Schritt mehr in der Registrierung und ein Tool mehr im Katalog.
- Die frühere Ungleichheit „Passwort nur im Web“ ist weg.

**Nachtrag 2026-09-28: warum die Mobilnummer kein Anker ist.** E-Mail-Adresse und Mobilnummer sind
gleichartig: Beide weist man mit einem zugeschickten Code nach. Der Unterschied ist, wer davon
abhängt. An der Adresse hängen die Lookup-Tools, die Keycloak-Suche und die Voraussetzung von
`enroll-password`; sie muss das E-Mail-Verfahren überleben und ist deshalb ein Anker des Kontos. An
der Nummer hängt nur das SMS-Verfahren; sie geht mit ihm (`AttributeAuthority.MethodModule`).
Braucht künftig ein Lookup oder ein anderes Verfahren die Nummer, wird sie wie die Adresse ein Anker
mit eigenem `confirm-phone`.

## Geschichte

- Die erste Fassung nannte die Reihenfolge „Adresse, Passwort, Besitzfaktor“. Der Ablauf war aber
  immer Adresse, Verfahren, Passwort; die Passwortpflicht prüft erst am Ende, ob das Konto `loa2`
  sonst erreicht.
- Seit 2026-09-23 prüft auch `RegisterEnrollFirstStrategy` dieselbe Bedingung für `loa2`, vorher
  fragte sie ohne Bedingung. Am Verhalten änderte das nichts: Dort wird ohne Identifizierung jedes
  Verfahren auf `loa1` eingerichtet, und die Anhebung durch ein zweites Verfahren begrenzt
  `enrolledUnderAcr` ([ADR-5](ADR-005-drei-obergrenzen-fuer-das-sicherheitsniveau.md)). `loa2` ist
  dort also aus eigener Kraft nie erreichbar, und das Passwort wird weiter immer verlangt.
- Mit der Einführung mussten mehrere Integrationstests ihre Erwartung ändern
  (`sms + email` → `sms + password`).
- Bis 2026-09-26 hieß die Pflicht Passwortpflicht (`PasswordObligation`) und bot nur
  `enroll-password` an. Seitdem bietet sie jedes Verfahren an, das eine fehlende Faktorart beiträgt,
  in der App also auch Gerätebindung und KOBIL.
