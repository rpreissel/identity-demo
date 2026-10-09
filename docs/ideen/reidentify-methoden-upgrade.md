# Idee: Verfahren aufwerten nach erneuter oder erstmaliger Identifizierung (RE_IDENTIFY)

**Worum es geht.** Jedes Anmeldeverfahren merkt sich, mit welchem Sicherheitsniveau die Sitzung
lief, in der es eingerichtet wurde (`enrolledUnderAcr`). Mehr als dieses Niveau kann das Verfahren
später nie liefern. Das bleibt auch so, wenn sich der Nutzer später mit einem starken Verfahren
wie dem Online-Ausweis identifiziert. Die Idee: Nach einer erfolgreichen Identifizierung darf der
Nutzer zustimmen, dass seine schwächer eingerichteten Verfahren künftig bis zum neu erreichten
Niveau zählen.

**Warum das wichtig ist.** Ohne diese Möglichkeit bleibt ein Nutzer, der sich anfangs nur mit einem
schwachen Verfahren registriert hat, für immer auf einem niedrigen Niveau. Das gilt auch dann,
wenn er seine Identität inzwischen stärker nachgewiesen hat.
Zugleich ist die Obergrenze eine Kernregel der Sicherheit. Eine Ausnahme davon muss deshalb eng
begrenzt und bewusst entschieden sein.

**Stand: Konzept, nicht umgesetzt, Entscheidung zu ADR-5 offen** (Stand 2026-09-24, Issue
`DPoP-demo-wyp3`). Das Dokument beschreibt, was es bedeuten würde, neben „Anmeldeverfahren
verwalten“ (in App und Web) einen eigenen Knopf „Identifizieren“ anzubieten. Nach jeder
erfolgreichen Sub-Journey `RE_IDENTIFY` würde an einer einzigen Stelle geprüft, ob bestehende
Anmeldeverfahren mit niedrigerem `enrolledUnderAcr` nach Zustimmung aufgewertet werden dürfen.
Eine [Sub-Journey](../glossar/glossar.md) ist ein Ablauf, der einen anderen Ablauf unterbricht und
danach zu ihm zurückkehrt. `RE_IDENTIFY` ist die Sub-Journey für die erneute Identifizierung.

---

## 1) Ausgangslage

Der Wert `authenticationMethods[].enrolledUnderAcr` ist in diesem Projekt bewusst **für immer
festgeschrieben**. Das ist die Obergrenze durch `enrolledUnderAcr` aus
[ADR-5](../adr/ADR-005-drei-obergrenzen-fuer-das-sicherheitsniveau.md). Sie wirkt beim Einrichten
und beim Anmelden ([06-ablaeufe.md](../06-ablaeufe.md) Abschnitt 1). Die Regel lautet: Ein
Verfahren darf bei der Anmeldung nie mehr Vertrauen erzeugen, als bei seiner Einrichtung vorhanden
war.

Die Regel hat einen guten Grund. Ohne sie könnte jemand eine schwach gesicherte Sitzung übernehmen
und dort ein eigenes Verfahren einrichten. Damit erreichte er dauerhaft ein höheres Niveau, als er
je nachgewiesen hat.

Für Nutzer entsteht daraus aber ein echtes Problem. Ein Beispiel:

- Jemand registriert sich im Experiment „Erst Anmeldeverfahren einrichten“
  (`RegisterEnrollFirstStrategy`) nur mit SMS. Das Verfahren bekommt `sms.enrolledUnderAcr = loa1`,
  und das Konto ist keiner Person zugeordnet (`personId == null`).
- Später weist er sich ganz regulär mit `ident-fsc`, `ident-eid` oder `ident-nect` aus.
- Seine SMS bleibt trotzdem für immer auf `loa1` begrenzt, obwohl er seine Identität inzwischen
  nachweislich stärker bestätigt hat.

Anders als in dem Angriff, vor dem die Regel schützt, handelt hier kein Angreifer. Es handelt der
Inhaber des Kontos selbst, und er hat gerade freiwillig einen stärkeren Nachweis erbracht.

## 2) Grundidee

1. **Ein eigener Knopf.** Neben „Anmeldeverfahren verwalten“ gibt es einen neuen Knopf
   „Identifizieren“. Er kommt zuerst in die App. Im Web fehlt er vorerst, das ist eine bekannte
   Lücke (siehe Abschnitt 6). Der Knopf startet jederzeit die schon vorhandene und mehrfach
   genutzte Sub-Journey `RE_IDENTIFY` (`ReIdentifyStrategy`). Das gilt auch, wenn `personId` schon
   gesetzt ist. Die sonst übliche Schwelle `loa2` gibt es dabei **nicht**. Die erneute
   Identifizierung ist ja gerade der Weg, auf dem das Niveau steigt. Eine Schwelle davor wäre
   widersinnig.
2. **Eine gemeinsame Prüfung für JEDEN Aufrufer.** Die Prüfung läuft nicht nur beim neuen Knopf,
   sondern auch bei allen heutigen Aufrufern von `RE_IDENTIFY`:
   - `STEP_UP`,
   - `LOOKUP_LOGIN`,
   - `AuthEnrollCore` bei `FAST_ACCESS` und `REGISTER`,
   - `RegisterEnrollFirstStrategy`.

   Nach einer erfolgreichen Identifizierung prüft der Orchestrator, ob es aktive Verfahren mit
   niedrigerem `enrolledUnderAcr` gibt. Wenn ja, fragt er den Nutzer, ob diese Verfahren künftig
   bis zum neu erreichten Niveau zählen dürfen. Dabei zählt er die Verfahren einzeln auf. Ist kein
   Verfahren betroffen, läuft alles unverändert weiter. Im Normalfall gibt es also keinen
   zusätzlichen Schritt und keine Änderung.
3. **Zustimmung führt zur Aufwertung.** Sagt der Nutzer „Ja“, hebt der Orchestrator
   `enrolledUnderAcr` der genannten Verfahren an. Die Grenze ist dabei das eigene `maxAcr` des
   jeweiligen Tools, denn ein Verfahren darf nie mehr können, als es technisch nachweisen kann.
   Das ist eine bewusste Ausnahme von ADR-5, die nur mit **Zustimmung** gilt. ADR-5 wird dadurch
   nicht aufgehoben. Auslöser ist immer ein neuer, erfolgreicher Nachweis der Identität durch den
   Inhaber des Kontos selbst. Es gibt nie eine automatische oder stillschweigende Änderung. Sagt
   der Nutzer „Nein“, bleibt alles, wie es ist.

## 3) Wo das technisch ansetzen würde

**Der neue Zustand.** `RE_IDENTIFY`
([`ReIdentifyStrategy.kt`](../../src/main/kotlin/com/example/identity/core/orchestrator/domain/journey/strategy/ReIdentifyStrategy.kt),
Zustände in `ReIdentifyState.kt`) ist nie der Einstieg einer Journey. Man erreicht die Sub-Journey
nur über `Transition.RequireSubJourney`. Heute läuft es so ab:

1. Im Zustand `Identifying` kommt ein erfolgreiches `Completed(Identified)` an.
2. Das führt direkt zu `Action.RecordIdentification`.
3. Danach folgt in jedem Fall `Transition.Authenticated`.

Genau zwischen Schritt 2 und 3 käme ein neuer, dritter Zustand hinzu: `OfferMethodUpgrade`. Er ist
ein `AnswerableState` wie das schon bestehende `OfferReIdent`, also ein Zustand, der dem Nutzer
eine Frage stellt und auf die Antwort wartet. Er zählt die betroffenen Verfahren auf und fragt.

**Wer davon betroffen ist.** Diese Stelle liegt **innerhalb** von `ReIdentifyStrategy`. Die
Prüfung gälte deshalb von selbst für alle fünf Aufrufer, ohne dass einer von ihnen geändert werden
müsste:

- `STEP_UP`,
- `LOOKUP_LOGIN`,
- `AuthEnrollCore`, das von `FAST_ACCESS` und `REGISTER` genutzt wird,
- `RegisterEnrollFirstStrategy`,
- der neue Knopf in der Verwaltung.

Neu hinzukommen müsste nur dieser Knopf als sechster Aufrufer. Dafür braucht es den Zustand
`IdentifyRequested` in `ManageAuthMethodsStrategy`/`ManageAuthMethodsState`, ohne Schwelle davor.

**Was es bei Zustimmung braucht.**

- Eine neue `Action` (`Action.UpgradeMethods(accountId, methodInstanceIds, newAcr)`), die der
  `JourneyActionExecutor` ausführt. Der Executor begrenzt dabei jedes Verfahren auf das `maxAcr`
  seines Tools. Das geschieht dort, weil `AccountService` bewusst nichts von `Tool` weiß.
- Eine neue Methode zum Speichern, `AccountService.upgradeMethods(...)`, nach dem Muster des
  bestehenden `deactivateAuthenticationMethod`. Sie findet die Verfahren über ihre `id` und nicht
  über den Namen des Verfahrens. So werden mehrere aktive Instanzen desselben Verfahrens (etwa
  mehrere Geräte) nicht verwechselt.

**Welches Niveau als „erreicht“ gilt.** Alle drei Handler für die Identifizierung setzen
`ToolOutcome.Completed.Identified.achievedAcr` zuverlässig:

- `ident-fsc` und `ident-eid` setzen `descriptor.maxAcr`, also `loa2` beziehungsweise `loa3`
  (`IdentFscToolHandler.kt:107`, `IdentEidToolHandler.kt:70`).
- `ident-nect` setzt das Niveau des gewählten Verfahrens: `loa3` beim Online-Ausweis und bei der
  EUDI-Wallet, aber nur `loa2` beim Reisepass (`IdentNectToolHandler.kt:141`).

Das ist also kein Hindernis. Es zeigt aber, dass man das erreichte Niveau nicht aus dem Descriptor
ablesen darf.

Die neue Prüfung sollte als „gerade erreichtes Niveau“ trotzdem nicht `state.targetAcr` verwenden.
Das ist nur die *Mindestanforderung*, mit der die Sub-Journey gestartet wurde. Beim neuen Knopf
ohne Schwelle wäre sie zum Beispiel bewusst `loa1`, damit `ident-fsc` als mögliches Tool erhalten
bleibt. Die Mindestanforderung kann also niedriger sein als das tatsächlich erreichte Niveau.

Zuverlässiger ist `ctx.currentAcr` (`JourneyContext.currentAcr = policy.resolveAcr(evidence,
account)`). Maßgeblich ist der Wert in dem Moment, in dem nach dem Ausführen von
`Action.RecordIdentification` das `ActionCompleted` in `Identifying` eintrifft. Zu diesem
Zeitpunkt hat `recordToolCompletion` die neue `MethodEvidence` (mit `loa = achievedAcr`) schon
übernommen, und der Kontext für den folgenden Aufruf wird neu aufgebaut. `ctx.currentAcr` zeigt
dann genau das erreichte Niveau.

## 4) Frontend

- **App-Kanal:** `AuthenticationCompletedView.tsx` zeigt „Anmeldeverfahren verwalten“ schon an,
  mit Knöpfen zum Hinzufügen und Entfernen und mit der Obergrenze `enrolledUnderAcr` je Verfahren.
  Ein Knopf „Identifizieren“ ließe sich dort ohne Weiteres ergänzen. Für die Frage selbst braucht
  das Frontend keine Änderung. Die App zeigt für jeden `AnswerableState` ohnehin eine allgemeine
  Bestätigungsseite (`Question.Confirm`). Diese Seite würde auch `OfferMethodUpgrade` richtig
  darstellen, denn sie zeigt nur den Text aus `stepData.prompt`.
- **Web-Kanal:** Dort läuft die Verwaltung der Verfahren inzwischen als Required Action von
  Keycloak mit eigener Journey ([05-api.md](../05-api.md) Abschnitt 3b). Eine
  Required Action ist ein Schritt, den Keycloak nach der Anmeldung verlangt. Der neue
  HTTP-Endpunkt wäre nicht an einen Kanal gebunden. Einen Knopf zum Identifizieren gibt es im Web
  aber noch nicht. Er müsste in der Seite der Required Action ergänzt werden. Für eine erste
  Umsetzung könnte man das als bekannte Lücke hinnehmen.

## 5) Was sich an bestehendem Verhalten und an der Doku ändern würde

[04-orchestrierung.md](../04-orchestrierung.md) beschreibt im Abschnitt „IAL und AAL“ eine Kette
von Obergrenzen. Diese Kette bekäme eine Ausnahme. Heute gilt uneingeschränkt: Die Identifizierung
beim Einrichten begrenzt, was ein Verfahren später liefern darf. Künftig gälte das mit dem Zusatz:
„es sei denn, der Inhaber des Kontos weist sich später auf einem höheren Niveau erneut aus und
stimmt der nachträglichen Aufwertung ausdrücklich zu“.

ADR-5 bräuchte einen Nachtrag. Er grenzt diese eine, eng umrissene Ausnahme von der sonst geltenden
Regel „nie nachträglich“ ab. Die Ausnahme ist:

- freiwillig,
- vom Inhaber des Kontos ausgelöst,
- ausdrücklich abgefragt,
- weiterhin durch das eigene `maxAcr` jedes Verfahrens begrenzt.

## 6) Offene Fragen und Risiken

1. **Ist die Ausnahme von ADR-5 grundsätzlich gewollt?** Sie weicht bewusst vom bisher streng
   eingehaltenen Grundsatz „nie nachträglich“ ab. Sie betrifft nur Verfahren des eigenen Kontos und
   wirkt nur nach ausdrücklicher, informierter Zustimmung. Trotzdem ändert sie eine Kernregel der
   Sicherheitsarchitektur. Das sollte nicht nebenbei entschieden werden.
2. **Lücke im Web-Kanal:** Solange die Seite der Required Action keinen Knopf zum Identifizieren
   hat, gibt es den neuen Einstieg nur in der App.
3. **Wie fein die Zustimmung ist:** Werden immer alle betroffenen Verfahren auf einmal aufgewertet,
   oder soll der Nutzer einzelne abwählen können? Für eine erste Version: alle auf einmal. So
   funktioniert auch das bestehende Ja/Nein bei `OfferReIdent` und `DeleteAccountStrategy`.
4. **Wiederholte Angebote:** Lehnt der Nutzer einmal ab, stellt der Orchestrator die Frage bei jeder
   späteren erneuten Identifizierung wieder. Einen Schalter „nie wieder fragen“ gibt es nicht. Das
   ist bewusst einfach gehalten. Bei häufigen Step-ups könnte es Nutzer aber stören. Ein
   [Step-up](../glossar/glossar.md) ist ein zusätzlicher Nachweis, um ein höheres Niveau zu
   erreichen.
5. **Aufwand für Tests:** Die Prüfung liegt an einer gemeinsamen Stelle in `ReIdentifyStrategy`.
   Deshalb betrifft sie möglicherweise jeden bestehenden Aufrufer. Alle Integrationstests müssten
   auf ungewollte Nebenwirkungen geprüft werden. Eine erste Durchsicht der vorhandenen Testdaten
   fand keinen Fall, in dem ein aktives Verfahren unter dem jeweils erreichten Niveau der
   Identifizierung liegt. Das müsste bei der Umsetzung aber bestätigt werden.

## 7) Nächste Schritte (falls die Umsetzung gewünscht ist)

1. Entscheiden lassen, ob die Ausnahme von ADR-5 (Punkt 6.1) so gewollt ist.
2. Den neuen Zustand `ReIdentifyState.OfferMethodUpgrade` anlegen und `ReIdentifyStrategy`
   anpassen (gemeinsame Prüfung nach `Action.RecordIdentification`).
3. Die neue `Action.UpgradeMethods` (`Action.kt`) anlegen, ihre Ausführung im
   `JourneyActionExecutor` bauen (Begrenzung je Verfahren auf dessen `maxAcr`) und
   `AccountService.upgradeMethods(...)` ergänzen.
4. Den neuen Zustand `ManageAuthMethodsState.IdentifyRequested` anlegen und in
   `ManageAuthMethodsStrategy` anbinden (ohne Schwelle `loa2`). Dazu kommen ein HTTP-Endpunkt und
   ein Knopf im Frontend (App-Kanal).
5. Tests schreiben: Unit-Tests (`ReIdentifyStrategyTest`, `ManageAuthMethodsStrategyTest`, Test für
   `AccountService`) und ein Integrationstest von Anfang bis Ende. Dieser soll nachweisen, dass das
   Niveau in einer folgenden Sitzung tatsächlich höher ist. Es genügt nicht, nur den Wert in der
   Datenbank zu prüfen.
6. Die Doku anpassen: die Diagramme in `journeys/re-identify.md` und
   `journeys/manage-auth-methods.md`, den Abschnitt „IAL und AAL“ in `04-orchestrierung.md` und
   ADR-5 (Nachtrag).
