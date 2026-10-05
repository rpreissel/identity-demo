# Idee: Anmeldeverfahren ändern und frischer Nachweis in der Verwaltung

**Worum es geht.** Ein Nutzer verwaltet seine Anmeldeverfahren in der Journey
`MANAGE_AUTH_METHODS`. Eine [Journey](../glossar/glossar.md) ist ein geführter Ablauf im
Orchestrator. Das Dokument beschreibt zwei Erweiterungen dieser Journey, die zusammengehören:

- Ein eingerichtetes Verfahren lässt sich **ändern**, etwa durch ein neues Passwort oder eine neue
  Telefonnummer.
- Die Verwaltung verlangt einen **frischen Nachweis**, wenn der letzte Nachweis der Sitzung älter
  als fünf Minuten ist. Dieselbe Frist gilt für das Löschen des Kontos (`DELETE_ACCOUNT`).

**Warum das wichtig ist.** Bisher musste man ein Verfahren entfernen und neu hinzufügen, um es zu
ändern. Außerdem konnte jemand an einem entsperrten Gerät mit laufender Sitzung Verfahren
verwalten, ohne gerade etwas nachgewiesen zu haben (Abschnitt 1).

**Stand: umgesetzt** (Stand 2026-10-04), in Backend, App und Web. Maßgeblich sind jetzt die
Beschreibungen von [`MANAGE_AUTH_METHODS`](../journeys/manage-auth-methods.md) und
[`DELETE_ACCOUNT`](../journeys/delete-account.md) sowie die [API](../05-api.md), Abschnitt
„Verfahren verwalten“. In einem Punkt weicht die Umsetzung vom Text unten ab: Ob ein Verfahren
änderbar ist, legt das Enroll-Tool selbst fest (`changeable`). Die Regel „nur eine Instanz und ein
Enroll-Tool“ aus Abschnitt 3 entscheidet darüber nicht.

---

## 1) Ausgangslage

- `MANAGE_AUTH_METHODS` kennt drei Wünsche: Verfahren hinzufügen, Verfahren entfernen und eine
  Angabe (Attribut) zurücknehmen ([journeys/manage-auth-methods.md](../journeys/manage-auth-methods.md)).
  Ändern fehlt. Wer ein neues Passwort oder eine neue Nummer will, muss das Verfahren entfernen und
  neu hinzufügen.
- Das Konto kann einen Eintrag schon ersetzen: Wird ein Verfahren, das nur eine Instanz haben
  darf, erneut eingerichtet, ersetzt der neue Eintrag den alten (`AccountService.addAuthMethod`).
  Dafür gibt es ein eigenes Ereignis im Änderungsprotokoll (`ChangeLog`). Es fehlt nur der Weg in
  der Verwaltung dorthin.
- Alle drei Wünsche verlangen ein Mindestniveau der Sitzung (`selfServiceAcrFloor`). Für ein
  identifiziertes Konto ist es `loa2`, für ein nie identifiziertes `loa1`. Ein Nachweis auf `loa2`
  verliert nach einer Weile seine Gültigkeit (`identity.policy.loa2-max-age`, 30 Minuten). Ein
  Nachweis auf `loa1` gilt dagegen für die ganze Sitzung.
- Daraus folgt eine Lücke: An einem entsperrten Gerät mit laufender Sitzung lassen sich Verfahren
  verwalten, ohne dass jemand gerade etwas nachgewiesen hat. Beim nie identifizierten Konto gilt
  das unbegrenzt, beim identifizierten bis zu 30 Minuten lang.
- `DELETE_ACCOUNT` verlangt deshalb schon zusätzlich einen frischen Nachweis über ein beliebiges
  aktives Verfahren (`DeleteAccountState.ConfirmationRequired`).

## 2) Grundidee

**Ändern ist erneutes Einrichten.** Es gibt keine neue Tool-Rolle und keine neue Kategorie. Ein
vierter Wunsch `ChangeRequested(methodInstanceId)` führt in den Zustand `Enrolling`, sobald die
Vorbedingung erfüllt ist. Dort läuft fest das `enroll-*`-Tool des Verfahrens. Das Tool läuft
unverändert: Es fragt die neue Nummer samt TAN ab oder das neue Passwort. Ist es erfolgreich,
ersetzt der neue Eintrag den alten.

**Ändern stuft nicht herab.** Die Sitzung muss mindestens das Niveau haben, unter dem der alte
Eintrag eingerichtet wurde (`enrolledUnderAcr`). Reicht das Niveau nicht, folgt ein Step-up, also
ein zusätzlicher Nachweis. Ist das Niveau auch so nicht erreichbar, lehnt der Orchestrator den
Wunsch ab und verweist auf die erneute Identifizierung. Der neue Eintrag übernimmt das alte Niveau
nicht. Er bekommt das Niveau der Sitzung, in der er eingerichtet wird.

**Das Tool fragt nicht nach dem alten Passwort.** Die übliche Abfrage des alten Passworts soll
beweisen, dass gerade der Kontoinhaber handelt. Das ist hier Aufgabe der Journey, nicht des Tools.
Dafür gibt es drei Gründe:

- Entfernen und neu Hinzufügen führt zum selben Ergebnis. Eine strengere Regel nur fürs Ändern
  ließe sich also umgehen.
- Wer das Passwort vergessen hat, aber ein anderes Verfahren besitzt, soll es ersetzen können.
  Mit einer Pflichtabfrage bräuchte es einen zweiten Weg zum Zurücksetzen.
- Andere Verfahren haben nichts Entsprechendes: Eine verlorene Telefonnummer kann niemand
  bestätigen.

**Frischer Nachweis nach fünf Minuten.** Für alle Wünsche der Verwaltung gilt zusätzlich zum
Mindestniveau: Der jüngste Nachweis der Sitzung darf höchstens fünf Minuten alt sein. Ist er
älter, bestätigt der Nutzer ein beliebiges aktives Verfahren neu. Auch das Verfahren, das ersetzt
werden soll, zählt dafür. Wer nur ein Passwort hat, wird so von selbst nach dem alten Passwort
gefragt. Wer es vergessen hat, bestätigt mit SMS oder identifiziert sich neu.

Die Frist erspart die doppelte Abfrage direkt nach der Anmeldung oder einem Step-up. Sie erlaubt
außerdem mehrere Änderungen hintereinander. Die Frist ist einstellbar
(`identity.policy.self-service-max-age`, Vorgabe `PT5M`).

**Eine Regel für die Frische.** `DELETE_ACCOUNT` nutzt dieselbe Frist. Eine gemeinsame Funktion
entscheidet für die Verwaltung und für das Löschen, ob ein Nachweis frisch genug ist.

## 3) Wo das technisch ansetzen würde

**Ändern**

- `ManageAuthMethodsState` bekommt den neuen Zustand `ChangeRequested(methodInstanceId)`. Er ist
  Wunsch und Wartezustand zugleich, wie `RemoveRequested`.
- Der Start läuft über einen neuen Endpunkt je Instanz. Form und Namen folgen bei der Umsetzung den
  vorhandenen Routen für Hinzufügen und Entfernen. Der Vertrag der API ändert sich. Deshalb müssen
  OpenAPI-Snapshot und Frontend-Typen neu erzeugt werden.
- Änderbar ist ein Verfahren, wenn es nur eine Instanz erlaubt (`allowsMultipleInstances ==
  false`) und sein Modul ein Enroll-Tool hat. Bei Verfahren, die es je Gerät gibt (KOBIL,
  Geräteschlüssel), bleibt es bei Entfernen und Hinzufügen.
- Das Verfahren `email` ist nicht änderbar. Es hat kein eigenes Credential, also kein eigenes
  gespeichertes Anmeldemerkmal. Seine Referenz ist der EMAIL-Anker (`EMAIL_ANCHOR_ENROLLMENT`). Ein
  [Anker](../glossar/glossar.md) ist eine Angabe, über die sich ein Konto eindeutig wiederfinden
  lässt. Diesen Anker schreibt `confirm-email`. Ein erneutes `enroll-email` hätte nichts zu
  ersetzen.
- Die Vorbedingung ist das höhere der beiden Niveaus `selfServiceAcrFloor` und `enrolledUnderAcr`
  des alten Eintrags.
- Es gibt keine Prüfung, ob sich der Nutzer aussperrt. Der alte Eintrag fällt erst weg, wenn der
  neue erfolgreich eingerichtet ist.
- Passwort und SMS schreiben keinen Anker. Ihre Angaben (`PASSWORD_EXISTS`, `PHONE_NUMBER`)
  gehören dem Modul und wechseln mit dem Eintrag. Schreibt ein Enroll-Tool künftig einen Anker,
  gelten für das Ersetzen die Regeln für Anker (`AnchorRegistry.bind`): das nötige Niveau und die
  Eindeutigkeit über alle Konten. Weist die Prüfung den Anker ab, scheitert die Änderung als
  Ganzes. Der alte Eintrag bleibt dann samt seinem Anker bestehen.
- Bricht der Nutzer ab oder lehnt das Tool ab, endet die Journey. Ein anderes Verfahren bietet sie
  dann nicht an.
- `ToolJourney` bekommt eine Abfrage nach der vorhandenen Instanz eines Moduls. Gibt es keine
  Instanz, ist die Antwort leer. Die Enroll-Tools für Passwort und SMS nutzen diese Abfrage für
  ihren Text (Abschnitt 4).

**Frischer Nachweis**

- Neben `selfServiceAcrFloor` gibt es eine Funktion, die aus den Nachweisen der Sitzung liest, ob
  der jüngste jünger als die Frist ist. Die Zeitstempel dafür sind vorhanden. `DefaultAuthPolicy`
  nutzt sie schon, um Nachweise auf `loa2` nach einer Weile ungültig werden zu lassen.
- Für jeden Wunsch gilt dieselbe Reihenfolge: zuerst das Mindestniveau (mit Step-up, falls nötig),
  dann die Frische. Ein Step-up, der gerade lief, erfüllt die Frische von selbst.
- Die erneute Bestätigung nutzt dasselbe Angebot an Tools wie `DELETE_ACCOUNT`
  (`CandidateTools.forReconfirmation`). `ManageAuthMethodsState` bekommt dafür einen Zustand
  `ConfirmationRequired`, der sich den ursprünglichen Wunsch merkt.
- `DeleteAccountStrategy` fragt dieselbe Funktion, bevor sie `ConfirmationRequired` anbietet.
- Die Diagramme in `journeys/manage-auth-methods.md` und `journeys/delete-account.md` ändern sich
  mit (`JourneyDiagramsTest`).

## 4) Frontend

Der Nutzer verwaltet seine Verfahren an zwei Stellen:

- in der App unter „Sicherheit“ → „Anmeldeverfahren“ (`AuthenticationCompletedView`,
  [Frontend](../10-frontend.md), FE-17),
- im Web über die Required Action `orchestrator-manage-methods` ([API](../05-api.md),
  „Anmeldeverfahren verwalten im Web-Kanal“). Eine Required Action ist ein Schritt, den Keycloak
  nach der Anmeldung verlangt.

Beide führen dieselbe Journey. Was angezeigt wird, bestimmt das Feld `next` in der Antwort des
Orchestrators.

### App

**Einstieg.** Die Seite eines Verfahrens bekommt neben „Deaktivieren“ einen Knopf „Ändern“.

- Der Knopf erscheint nur, wenn der Server das Verfahren als änderbar kennzeichnet. Dafür gibt es
  ein Feld an `ActiveMethodView`. Das Frontend leitet das nicht selbst ab.
- Die Seite zeigt keinen bisherigen Wert, etwa keine Telefonnummer. Die Liste der Verfahren
  enthält ihn heute nicht, und für das Ändern ist er nicht nötig.
- „Ändern“ startet die Journey wie „Weiteres Verfahren hinzufügen“ (`handleAddMethod`), nur mit
  der `methodInstanceId`. Was danach kommt, bestimmt `next`.

**Formular des Tools.** Die App nutzt das vorhandene Formular (`PasswordEnrollForm`,
`SmsEnrollForm`), nur mit anderem Text: „Passwort ändern“ statt „Passwort einrichten“, „Neue
Telefonnummer“ und ein Satz, dass der bisherige Eintrag mit dem Abschluss ersetzt wird. Das Tool
erkennt selbst, dass es einen Eintrag ersetzt:

- Es fragt beim Start, ob das Konto schon eine Instanz seines Verfahrens hat. Die Antwort schreibt
  es in sein eigenes `stepData`, also in die Daten, die es der App für die Anzeige mitgibt. Den
  Wunsch der Journey muss das Tool dafür nicht kennen. Die Aussage stimmt auch, wenn ein erneutes
  Einrichten auf anderem Weg zustande kommt.
- In seinem eigenen Speicher sieht das Tool das nicht, denn die Einträge dort sind keinem Konto
  zugeordnet. Die Frage geht deshalb an den Orchestrator. `ToolJourney.requireEnrollment` ermittelt
  die Instanz schon heute, antwortet aber mit einem Fehler, wenn es keine gibt. `tool_api` bekommt
  daneben eine Abfrage, die in diesem Fall leer antwortet.
- `enroll-password` und `enroll-sms` nutzen heute die gemeinsame Form `MissingFields`. Sie
  bekommen je eine eigene Form für ihren Schritt, mit dem zusätzlichen Feld. Diese Form wird im
  Modul deklariert.
- Die neuen Texte sind Vorlagen im Code und werden mit `/translate-texts` übersetzt.

**Frischer Nachweis.** Die erneute Bestätigung ist ein gewöhnliches Angebot von `auth-*`-Tools,
kein neuer Bildschirm. Den Hinweis darüber liefert der Server: „Bitte bestätigen Sie zuerst ein
Anmeldeverfahren. Ihr letzter Nachweis liegt länger zurück.“

- Gibt es nur ein aktives Verfahren, beginnt dessen Tool sofort, ohne Auswahl. Wer nur ein
  Passwort hat, sieht also direkt die Eingabe des bisherigen Passworts und danach das Formular für
  das neue.
- Derselbe Hinweis erscheint beim Hinzufügen, beim Deaktivieren und beim Löschen des Kontos. Das
  Deaktivieren ist heute ein einzelner Aufruf mit sofortigem Ergebnis. Künftig kann seine Antwort
  ein `next` mit der Bestätigung enthalten, so wie es der Step-up schon tut.
- „Abbrechen“ führt zur Liste der Verfahren zurück (FE-16). Es ist dann nichts geändert.

**Ablehnung wegen des Niveaus.** Reicht das Niveau der Sitzung nicht und ist ein Step-up möglich,
läuft er wie beim Hinzufügen. Ist das Niveau nicht erreichbar, zeigt die Seite des Verfahrens die
Meldung des Servers: „Dieses Verfahren wurde mit höherem Sicherheitsniveau eingerichtet. Bitte
identifizieren Sie sich zuerst erneut.“ Einen eigenen Knopf für die erneute Identifizierung gibt es
dort nicht. Der Weg dorthin bleibt „Sicherheitsniveau erhöhen“ im Profil (FE-15).

**Nach dem Erfolg.** Die App kehrt zur Liste der Verfahren zurück und zeigt eine Meldung, etwa
„Passwort geändert“. Sie liest die Liste neu. Der Eintrag hat eine neue Id. Deshalb zeigt die App
die Liste und nicht die Seite des alten Eintrags.

### Web (Required Action)

**Einstieg.** Die Liste `orchestrator-manage-methods.ftl` bekommt in jeder Zeile neben „Entfernen“
einen Knopf „Ändern“, aber nur bei änderbaren Verfahren.
`OrchestratorManageMethodsRequiredAction` kennt dafür neben `add` und `remove` eine dritte Aktion
`change`. Sie ruft den neuen Endpunkt auf und reicht die Antwort wie bisher an `handleResponse`
weiter. `OrchestratorClient.MethodView` bekommt dazu dasselbe Kennzeichen wie `ActiveMethodView`.

**Formular des Tools.** `tool-password-enroll.ftl` und `tool-sms-enroll.ftl` zeigen den anderen
Text aus demselben Feld in `stepData`. Die Vorlagen stehen in der Keycloak-Erweiterung und werden
ebenfalls mit `/translate-texts` übersetzt.

**Frischer Nachweis.** Er braucht keine neue Seite. `OrchestratorNextDispatch` zeigt das Angebot
auf der Auswahlseite oder startet das einzige Tool direkt, wie beim Step-up. Der Hinweis des
Servers steht über dem Formular (`page-notes.ftl`).

- Im Web wird die Frist öfter überschritten als in der App. Die Required Action nutzt das
  SSO-Cookie, und `OrchestratorResumeAuthenticator` stellt den Kanal aus älteren Nachweisen wieder
  her. Wer die Verwaltung aus einer länger laufenden Sitzung öffnet, bestätigt also in der Regel
  zuerst ein Verfahren.
- Maßgeblich ist der Zeitpunkt des ursprünglichen Nachweises, nicht der Zeitpunkt des
  Wiederherstellens. Sonst würde jeder Aufruf von `kc_action` die Frist zurücksetzen.
- Gefragt wird erst bei „Ändern“, „Entfernen“ oder „Hinzufügen“, nicht schon beim Öffnen der
  Liste. Die Liste nur anzusehen bleibt ohne frischen Nachweis möglich.

**Ablehnung und Erfolg.** Beides erscheint als Statuszeile über der Liste, wie heute
„Anmeldeverfahren entfernt.“. Nach dem Erfolg steht dort „Anmeldeverfahren geändert.“, bei zu
niedrigem Niveau die Meldung des Servers. Bricht der Nutzer die Bestätigung ab, kehrt die Required
Action zur Liste zurück.

**Abgrenzung zum Passwort in Keycloak.** Keycloak kann das Passwort auch selbst setzen
(`OrchestratorStorageProvider.updateCredential` über `MgmtPasswordController`), ohne Kanal und
ohne Journey. Dieser Weg prüft weder das Mindestniveau noch die Frist. Solange Keycloaks eigene
Aktion zum Ändern des Passworts nicht angeboten wird, bleibt „Ändern“ in der Required Action der
einzige Weg für Nutzer.

### Demo-Spalte

Das Diagramm zu `MANAGE_AUTH_METHODS` (`journeyDiagrams.ts`) bekommt den neuen Wunsch und die
Bestätigung. Das Diagramm zu `DELETE_ACCOUNT` bekommt die Frist. Die Begründung des Schritts
(`DemoStepReason`) nennt, dass der letzte Nachweis älter als fünf Minuten ist.

## 5) Was sich an bestehendem Verhalten ändern würde

- Hinzufügen, Entfernen und das Zurücknehmen einer Angabe verlangen künftig eine Bestätigung, wenn
  der letzte Nachweis älter als fünf Minuten ist. Das betrifft vor allem Web-Sitzungen, die länger
  offen sind.
- `DELETE_ACCOUNT` verlangt die Bestätigung nach derselben Frist statt nach seiner heutigen Regel.

## 6) Abgrenzung

- **Die E-Mail-Adresse ändern** gehört nicht dazu. Das ist der Wechsel einer Angabe über
  `confirm-email`, kein erneutes Einrichten. Außerdem sind das Passwort und das E-Mail-Verfahren an
  die bestätigte Adresse gebunden. Was mit ihnen geschieht, ist eine eigene Frage. An die
  Telefonnummer ist dagegen kein anderes Verfahren gebunden.
- **Eine Instanz umbenennen** (etwa den Anzeigenamen eines Geräts ändern) ist kein Einrichten und
  läuft nicht über diesen Weg.
