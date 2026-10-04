# Idee: Anmeldeverfahren ändern und frischer Nachweis in der Verwaltung

Status: **Konzept, nicht umgesetzt** (Stand 2026-10-04). Das Dokument beschreibt zwei
zusammengehörige Erweiterungen von `MANAGE_AUTH_METHODS`: ein eingerichtetes Verfahren ändern
(neues Passwort, neue Telefonnummer) und einen frischen Nachweis verlangen, wenn der letzte
Nachweis der Sitzung älter als fünf Minuten ist. Dieselbe Frist gilt für `DELETE_ACCOUNT`.

---

## 1) Ausgangslage

- `MANAGE_AUTH_METHODS` kennt drei Wünsche: Verfahren hinzufügen, Verfahren entfernen, Attribut
  zurücknehmen ([journeys/manage-auth-methods.md](../journeys/manage-auth-methods.md)). Ändern
  fehlt. Wer ein neues Passwort oder eine neue Nummer will, muss entfernen und neu hinzufügen.
- Das Konto kann das Ersetzen schon: Wird ein Verfahren mit nur einer Instanz erneut
  eingerichtet, ersetzt der neue Eintrag den alten (`AccountService.addAuthMethod`, eigenes
  Ereignis im `ChangeLog`). Es fehlt nur der Weg dorthin.
- Alle drei Wünsche verlangen `selfServiceAcrFloor`: `loa2` für ein identifiziertes Konto, `loa1`
  für ein nie identifiziertes. `loa2` altert (`identity.policy.loa2-max-age`, 30 Minuten), `loa1`
  trägt die ganze Sitzung.
- Daraus folgt eine Lücke: An einem entsperrten Gerät mit laufender Sitzung lassen sich Verfahren
  verwalten, ohne dass jemand gerade etwas nachgewiesen hat. Beim nie identifizierten Konto gilt
  das unbegrenzt, beim identifizierten bis zu 30 Minuten.
- `DELETE_ACCOUNT` verlangt deshalb zusätzlich einen frischen Nachweis über ein beliebiges
  aktives Verfahren (`DeleteAccountState.ConfirmationRequired`).

## 2) Grundidee

**Ändern ist erneutes Einrichten.** Es gibt keine neue Tool-Rolle und keine neue Kategorie. Ein
vierter Wunsch `ChangeRequested(methodInstanceId)` führt nach bestandener Vorbedingung in
`Enrolling`, fest auf das `enroll-*`-Tool des Verfahrens. Das Tool läuft unverändert: neue Nummer
samt TAN, neues Passwort. Beim Erfolg ersetzt der neue Eintrag den alten.

**Ändern stuft nicht herab.** Die Sitzung muss mindestens das Niveau haben, unter dem der alte
Eintrag eingerichtet wurde (`enrolledUnderAcr`). Reicht es nicht, folgt ein Step-up; ist das
Niveau so nicht erreichbar, wird der Wunsch abgelehnt und auf die Re-Identifizierung verwiesen.
Der neue Eintrag erbt das alte Niveau nicht, er bekommt das der Sitzung.

**Das alte Passwort fragt das Tool nicht ab.** Die übliche Abfrage soll beweisen, dass gerade
der Kontoinhaber handelt. Das ist hier Sache der Journey, nicht des Tools:

- Entfernen und neu Hinzufügen führt zum selben Ergebnis. Eine strengere Regel nur fürs Ändern
  wäre umgehbar.
- Wer das Passwort vergessen hat, aber ein anderes Verfahren besitzt, soll es ersetzen können.
  Mit Pflichtabfrage bräuchte es einen zweiten Weg für das Zurücksetzen.
- Andere Verfahren haben kein Gegenstück: Eine verlorene Telefonnummer kann niemand bestätigen.

**Frischer Nachweis nach fünf Minuten.** Für alle Wünsche der Verwaltung gilt zusätzlich zum
Floor: Der jüngste Nachweis der Sitzung darf höchstens fünf Minuten alt sein. Ist er älter,
bestätigt der Nutzer ein beliebiges aktives Verfahren neu. Auch das Verfahren, das ersetzt werden
soll, zählt. Wer nur ein Passwort hat, wird so von selbst nach dem alten Passwort gefragt. Wer es
vergessen hat, bestätigt mit SMS oder identifiziert sich neu.

Die Frist erspart die doppelte Abfrage direkt nach der Anmeldung oder einem Step-up und erlaubt
mehrere Änderungen hintereinander. Sie ist einstellbar (`identity.policy.self-service-max-age`,
Vorgabe `PT5M`).

**Eine Frische-Regel.** `DELETE_ACCOUNT` nutzt dieselbe Frist. Eine gemeinsame Funktion
entscheidet für Verwaltung und Löschen, ob ein Nachweis frisch genug ist.

## 3) Wo das technisch ansetzen würde

**Ändern**

- `ManageAuthMethodsState`: neuer Zustand `ChangeRequested(methodInstanceId)`, Wunsch und
  Wartezustand zugleich wie `RemoveRequested`.
- Start über einen neuen Endpunkt je Instanz. Form und Namen folgen bei der Umsetzung den
  vorhandenen Routen für Hinzufügen und Entfernen. Der Vertrag ändert sich, also OpenAPI-Snapshot
  und Frontend-Typen neu erzeugen.
- Änderbar ist ein Verfahren, wenn es nur eine Instanz erlaubt (`allowsMultipleInstances ==
  false`) und sein Modul ein Enroll-Tool hat. Bei Verfahren je Gerät (KOBIL, Geräteschlüssel)
  bleibt es bei Entfernen und Hinzufügen.
- Das Verfahren `email` ist nicht änderbar. Es hat kein eigenes Credential, seine Referenz ist der
  EMAIL-Anker (`EMAIL_ANCHOR_ENROLLMENT`), und den schreibt `confirm-email`. Ein erneutes
  `enroll-email` hätte nichts zu ersetzen.
- Vorbedingung ist das höhere von `selfServiceAcrFloor` und dem `enrolledUnderAcr` des alten
  Eintrags.
- Keine Aussperr-Prüfung: Der alte Eintrag fällt erst mit dem Erfolg weg.
- Passwort und SMS schreiben keinen Anker; ihre Angaben (`PASSWORD_EXISTS`, `PHONE_NUMBER`)
  gehören dem Modul und wechseln mit dem Eintrag. Schreibt ein Enroll-Tool künftig einen Anker,
  gelten dessen Regeln für das Ersetzen (`AnchorRegistry.bind`: Niveau, Eindeutigkeit über alle
  Konten). Wird der Anker abgewiesen, scheitert die Änderung als Ganzes, und der alte Eintrag
  bleibt samt seinem Anker bestehen.
- Bricht der Nutzer ab oder lehnt das Tool ab, endet die Journey; ein anderes Verfahren wird
  nicht angeboten.
- `ToolJourney` bekommt eine Abfrage nach der vorhandenen Instanz eines Moduls, die ohne Instanz
  leer antwortet. Die Enroll-Tools für Passwort und SMS nutzen sie für ihren Text (Abschnitt 4).

**Frischer Nachweis**

- Eine Funktion neben `selfServiceAcrFloor`, die aus den Nachweisen der Sitzung liest, ob der
  jüngste jünger als die Frist ist. Die Zeitstempel sind vorhanden, `DefaultAuthPolicy` nutzt sie
  für das Altern von `loa2`.
- Reihenfolge je Wunsch: erst der Floor (Step-up, falls nötig), dann die Frische. Ein Step-up, der
  gerade lief, erfüllt die Frische von selbst.
- Die erneute Bestätigung nutzt das Angebot von `DELETE_ACCOUNT`
  (`CandidateTools.forReconfirmation`). `ManageAuthMethodsState` bekommt dafür einen Zustand
  `ConfirmationRequired`, der den Wunsch mitführt.
- `DeleteAccountStrategy` fragt dieselbe Funktion, bevor sie `ConfirmationRequired` anbietet.
- Die Diagramme in `journeys/manage-auth-methods.md` und `journeys/delete-account.md` ändern sich
  mit (`JourneyDiagramsTest`).

## 4) Frontend

Verwaltet wird an zwei Stellen: in der App unter „Sicherheit“ → „Anmeldeverfahren“
(`AuthenticationCompletedView`, [Frontend](../10-frontend.md), FE-17) und im Web über die Required
Action `orchestrator-manage-methods` ([API](../05-api.md), „Anmeldeverfahren verwalten im
Web-Kanal“). Beide führen dieselbe Journey; was gezeigt wird, bestimmt `next`.

### App

**Einstieg.** Die Seite eines Verfahrens bekommt neben „Deaktivieren“ einen Knopf „Ändern“.

- Er erscheint nur, wenn der Server das Verfahren als änderbar kennzeichnet (ein Feld an
  `ActiveMethodView`). Das Frontend leitet das nicht selbst ab.
- Die Seite zeigt keinen bisherigen Wert (Telefonnummer). Die Liste der Verfahren enthält ihn
  heute nicht, und für das Ändern ist er nicht nötig.
- „Ändern“ startet die Journey wie „Weiteres Verfahren hinzufügen“ (`handleAddMethod`), nur mit
  der `methodInstanceId`. Was danach kommt, bestimmt `next`.

**Formular des Tools.** Es ist das vorhandene Formular (`PasswordEnrollForm`, `SmsEnrollForm`),
mit anderem Text: „Passwort ändern“ statt „Passwort einrichten“, „Neue Telefonnummer“, und ein
Satz, dass der bisherige Eintrag mit dem Abschluss ersetzt wird. Das Tool erkennt selbst, dass es
ersetzt:

- Es fragt beim Start, ob das Konto schon eine Instanz seines Verfahrens hat, und schreibt die
  Antwort in sein eigenes `stepData`. Den Wunsch der Journey muss es dafür nicht kennen; die
  Aussage stimmt auch, wenn ein erneutes Einrichten auf anderem Weg zustande kommt.
- In seinem eigenen Speicher sieht das Tool es nicht, die Einträge dort tragen kein Konto. Die
  Frage geht an den Orchestrator: `ToolJourney.requireEnrollment` löst die Instanz heute schon
  auf, antwortet ohne Instanz aber mit einem Fehler. `tool_api` bekommt daneben eine Abfrage, die
  ohne Instanz leer antwortet.
- `enroll-password` und `enroll-sms` nutzen heute die gemeinsame Form `MissingFields`. Sie
  bekommen je eine eigene Schritt-Form mit dem zusätzlichen Feld, deklariert im Modul.
- Die neuen Texte sind Vorlagen im Code und laufen durch `/translate-texts`.

**Frischer Nachweis.** Die erneute Bestätigung ist ein gewöhnliches Angebot von `auth-*`-Tools,
kein neuer Bildschirm. Der Hinweis darüber kommt vom Server: „Bitte bestätigen Sie zuerst ein
Anmeldeverfahren. Ihr letzter Nachweis liegt länger zurück.“

- Gibt es nur ein aktives Verfahren, beginnt dessen Tool sofort, ohne Auswahl. Wer nur ein
  Passwort hat, sieht also direkt die Eingabe des bisherigen Passworts und danach das Formular für
  das neue.
- Derselbe Hinweis erscheint beim Hinzufügen, Deaktivieren und Löschen des Kontos. Das
  Deaktivieren ist heute ein einzelner Aufruf mit sofortigem Ergebnis; künftig kann seine Antwort
  ein `next` mit der Bestätigung enthalten, wie es der Step-up schon tut.
- „Abbrechen“ führt zur Liste der Verfahren zurück (FE-16), nichts ist geändert.

**Ablehnung wegen des Niveaus.** Reicht das Niveau der Sitzung nicht und ist ein Step-up möglich,
läuft er wie beim Hinzufügen. Ist das Niveau nicht erreichbar, zeigt die Seite des Verfahrens die
Meldung des Servers („Dieses Verfahren wurde mit höherem Sicherheitsniveau eingerichtet. Bitte
identifizieren Sie sich zuerst erneut.“). Einen eigenen Knopf zur Re-Identifizierung gibt es dort
nicht; der Weg bleibt „Sicherheitsniveau erhöhen“ im Profil (FE-15).

**Nach dem Erfolg.** Die App kehrt zur Liste der Verfahren zurück und zeigt eine Meldung
(„Passwort geändert“). Die Liste wird neu gelesen: Der Eintrag hat eine neue Id, deshalb zeigt
die App die Liste und nicht die Seite des alten Eintrags.

### Web (Required Action)

**Einstieg.** Die Liste `orchestrator-manage-methods.ftl` bekommt je Zeile neben „Entfernen“
einen Knopf „Ändern“, nur bei änderbaren Verfahren.
`OrchestratorManageMethodsRequiredAction` kennt dafür neben `add` und `remove` eine dritte Aktion
`change`, ruft den neuen Endpunkt auf und reicht die Antwort wie bisher an `handleResponse`.
`OrchestratorClient.MethodView` trägt dazu dasselbe Kennzeichen wie `ActiveMethodView`.

**Formular des Tools.** `tool-password-enroll.ftl` und `tool-sms-enroll.ftl` zeigen den anderen
Text aus demselben Feld in `stepData`. Die Vorlagen stehen in der Keycloak-Erweiterung und laufen
ebenfalls durch `/translate-texts`.

**Frischer Nachweis.** Er braucht keine neue Seite: `OrchestratorNextDispatch` zeigt das Angebot
über die Auswahlseite oder startet das einzige Tool direkt, wie beim Step-up. Der Hinweis des
Servers steht über dem Formular (`page-notes.ftl`).

- Im Web greift die Frist öfter als in der App. Die Required Action nutzt das SSO-Cookie, und
  `OrchestratorResumeAuthenticator` stellt den Kanal aus älteren Nachweisen wieder her. Wer die
  Verwaltung aus einer länger laufenden Sitzung öffnet, bestätigt also in der Regel zuerst ein
  Verfahren.
- Maßgeblich ist der Zeitpunkt des ursprünglichen Nachweises, nicht der des Wiederherstellens.
  Sonst würde jeder Aufruf von `kc_action` die Frist zurücksetzen.
- Gefragt wird erst bei „Ändern“, „Entfernen“ oder „Hinzufügen“, nicht schon beim Öffnen der
  Liste. Die Liste nur zu lesen bleibt ohne frischen Nachweis möglich.

**Ablehnung und Erfolg.** Beides ist eine Statuszeile über der Liste, wie heute „Anmeldeverfahren
entfernt.“: nach dem Erfolg „Anmeldeverfahren geändert.“, bei zu niedrigem Niveau die Meldung des
Servers. Bei einem Abbruch der Bestätigung kehrt die Required Action zur Liste zurück.

**Abgrenzung zum Passwort in Keycloak.** Keycloak kann das Passwort auch selbst setzen
(`OrchestratorStorageProvider.updateCredential` über `MgmtPasswordController`), ohne Kanal und
ohne Journey. Dieser Weg kennt weder Floor noch Frist. Solange Keycloaks eigene Aktion zum
Ändern des Passworts nicht angeboten wird, bleibt „Ändern“ in der Required Action der einzige
Weg für Nutzer.

### Demo-Spalte

Das Diagramm zu `MANAGE_AUTH_METHODS` (`journeyDiagrams.ts`) bekommt den neuen Wunsch und die
Bestätigung, das zu `DELETE_ACCOUNT` die Frist. Die Begründung des Schritts (`DemoStepReason`)
nennt, dass der letzte Nachweis älter als fünf Minuten ist.

## 5) Was sich an bestehendem Verhalten ändern würde

- Hinzufügen, Entfernen und Attribut zurücknehmen verlangen künftig eine Bestätigung, wenn der
  letzte Nachweis älter als fünf Minuten ist. Das trifft vor allem Web-Sitzungen, die länger offen
  stehen.
- `DELETE_ACCOUNT` verlangt die Bestätigung nach derselben Frist statt nach seiner heutigen Regel.

## 6) Abgrenzung

- **E-Mail-Adresse ändern** gehört nicht dazu. Das ist ein Attributwechsel über `confirm-email`,
  kein erneutes Einrichten, und an der bestätigten Adresse hängen das Passwort und das
  E-Mail-Verfahren. Was mit ihnen geschieht, ist eine eigene Frage. An der Telefonnummer hängt
  kein anderes Verfahren.
- **Umbenennen** einer Instanz (Anzeigename eines Geräts) ist kein Einrichten und läuft nicht über
  diesen Weg.
