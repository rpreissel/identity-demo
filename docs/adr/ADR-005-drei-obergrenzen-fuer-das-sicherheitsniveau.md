# ADR-5: Zwei Obergrenzen für das Sicherheitsniveau

**Status:** umgesetzt.

**Kontext**: Das **Sicherheitsniveau** (kurz Niveau, im Code `acr`) sagt, wie sehr der Server einer
Anmeldung vertraut. Es gibt die Stufen `loa1` bis `loa3` (siehe [Glossar](../glossar/glossar.md)).
Ein Nutzer erreicht ein Niveau, indem er sich mit seinen **Anmeldeverfahren** anmeldet, etwa mit
Passwort oder SMS. Diese Verfahren richtet er vorher in seinem Konto ein. Das Problem: Wenn der Server
nur darauf schaut, was in der laufenden Sitzung bewiesen wurde, könnte jemand in einer schwach
gesicherten Sitzung ein neues Verfahren einrichten. Mit diesem Verfahren käme er später auf ein
höheres Niveau, als ihm eigentlich zusteht. Diese ADR legt fest, wie der Server das verhindert.

**Entscheidung**: Das Sicherheitsniveau, das ein Verfahren in einer Sitzung liefert, ist an zwei
Stellen nach oben begrenzt:

- **Beim Einrichten** hält jedes Verfahren fest, welches Niveau die Sitzung damals nachgewiesen
  hatte. Der Wert steht in `account.auth_method.enrolled_under_acr`; gesetzt wird er im
  `JourneyActionExecutor`. Mehr als dieses Niveau kann das Verfahren später nie beitragen.
- **Beim Anmelden** zählt der kleinere von zwei Werten: das, was das Verfahren in dieser Sitzung
  tatsächlich nachgewiesen hat, und sein `enrolledUnderAcr`. Das rechnet `performAcceptProof` aus.

Die Identifizierung wirkt dabei über die erste Grenze. (Eine **Identifizierung** stellt fest, wer
jemand wirklich ist, etwa mit dem Online-Ausweis.) Wer sich nur schwach identifiziert hat, richtet
jedes Verfahren in einer Sitzung mit niedrigem Niveau ein. Genau dieses Niveau steht dann in
`enrolledUnderAcr`. Eine eigene Grenze für das ganze Konto, die sich aus der Identifizierung ergibt,
gibt es nicht.

Wie das sichtbare Niveau (`acr`) aus dem Nachweis einer Sitzung entsteht, beschreibt
[Orchestrierung](../04-orchestrierung.md) Abschnitt 4. Dort trennt `DefaultAuthPolicy.resolveAcr`
zwei Fragen: Wie stark ist die Identifizierung (IAL), und wie stark ist die Anmeldung (AAL)? Für das
IAL zählt nur der Identitätsnachweis der **laufenden** Sitzung, weil die Identität in jeder Sitzung
neu bewiesen wird.

**Erwogene Alternative**: Das Niveau nur aus dem ableiten, was in der laufenden Sitzung geschehen
ist. Unter welchen Bedingungen ein Verfahren eingerichtet wurde, spielt dann keine Rolle.

**Begründung**: Ohne die Grenze beim Einrichten gäbe es einen Weg nach oben. Wer eine schwache
Sitzung übernimmt (etwa auf `loa1`), könnte darin ein eigenes Verfahren einrichten. Damit könnte er
dauerhaft ein höheres Niveau vortäuschen. Ebenso könnte eine schwach identifizierte Person mit
starken Anmeldeverfahren ein Niveau erreichen, das ihre Identifizierung nie hergab.

**Folgen und Kosten**: Ein Niveau kann an zwei Stellen sinken statt an einer. Welche Grenze gerade
wirkt, lässt sich nur nachvollziehen, wenn man zwei Dinge zusammen ansieht: den Nachweis der Sitzung
(`SessionEvidence`, siehe
[ADR-15](ADR-015-nachweise-und-ausgestellte-tokens-in-getrennten-tabellen.md)) und
`account.auth_method.enrolled_under_acr`. Die Tabelle `account.change_log` (IDENTIFIED) mit der
Spalte `acr` dient nur als Audit-Nachweis. Für keine Entscheidung wird sie gelesen.

**Geschichte**: Ursprünglich waren es drei Grenzen. Die erste sollte `account.change_log.acr`
(IDENTIFIED) sein und begrenzen, welches Niveau ein Konto überhaupt erreichen kann. Diese Grenze
wurde nie gelesen. Seit 2026-09-23 ist festgehalten, dass die Identifizierung nur noch über
`enrolledUnderAcr` wirkt. Der Dateiname enthält noch die alte Zahl.
