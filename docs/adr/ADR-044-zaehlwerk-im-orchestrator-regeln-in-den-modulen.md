# ADR-44: Das Zählwerk liegt im Orchestrator, die Regeln in den Modulen

**Status:** umgesetzt 2026-09.

**Worum es geht.** An vielen Stellen muss das System zählen, wie oft etwas passiert, und ab einer
Grenze ablehnen. Ein Beispiel: An dieselbe Mobilnummer dürfen in zehn Minuten nur drei SMS-Codes
gehen. Eine solche Regel heißt [Mengenbegrenzung](../glossar/glossar.md), für den Versand von Codes
auch Versandlimit. Das System besteht aus dem Orchestrator, dem zentralen Server, und aus
Tool-Modulen. Ein Tool-Modul enthält die Tools eines Verfahrens, etwa alles rund um SMS. Ein Tool
ist ein einzelner Arbeitsschritt, den der Nutzer durchläuft, etwa „SMS-Code eingeben“. Die Frage
ist: Wer legt fest, was gezählt wird und wie viel erlaubt ist, und wer führt die Zähler?

**Entscheidung.** Der Orchestrator stellt nur das Zählwerk bereit, also die technische Grundlage
zum Zählen. Es kann:

- atomar in einem gleitenden Zeitfenster zählen (atomar heißt: auch bei gleichzeitigen Anfragen
  geht kein Zählschritt verloren),
- einen Zähler zurücksetzen,
- Zähler aufbewahren.

Im Code ist das `RateLimitCounter` mit der Tabelle `orchestrator.rate_limit`. Der Orchestrator stellt
das Zählwerk den Tool-Modulen über eine feste Schnittstelle (einen Port) in `tool_api` zur Verfügung
(`RateLimits`). Das Modul, dem die Sache gehört, entscheidet:

- was gezählt wird,
- unter welchem Schlüssel,
- mit welcher Grenze,
- wann der Zähler von vorn beginnt.

Ein Zähler bleibt nur dann im Orchestrator, wenn er absichtlich über mehrere Tools hinweg gilt oder
zu keinem Tool gehört.

Der erste Anwendungsfall ist der Versand von Codes. `auth_sms` verschickt die SMS. Deshalb ist
`auth_sms` auch dafür verantwortlich, dass niemand über seine Tools massenhaft SMS an eine fremde
Nummer schicken lässt. Für E-Mails gilt dasselbe bei `auth_email`.

**Warum.** Bisher lagen Grenzen und Schlüssel für den Versand in `SendThrottleService` im
Orchestrator. Jeder Controller musste daran denken, vor dem Senden `isSendThrottled` oder
`isSendThrottledForContact` aufzurufen. Damit entschied der Orchestrator über etwas, das er gar
nicht selbst tut: Er verschickt keine SMS und weiß nicht, an welche Nummer ein Tool sendet. Die
Prüfung stand im Controller, der Versand im Handler. Eine neue Stelle dazwischen konnte die Prüfung
vergessen. Außerdem ließ sich eine Mengenbegrenzung nicht dort zurücksetzen, wo man weiß, dass der
Code angekommen ist: im Modul, das den Code prüft.

**Erwogene Alternativen.**

- **Alles im Orchestrator lassen und dort nach Erfolg zurücksetzen.** Dazu würde
  `ToolJourneyService` das Ergebnis `Completed` auswerten und aus den Claims die Nummer ableiten.
  (Claims sind die Angaben, die ein Tool über den Nutzer zurückmeldet.) Verworfen: Der Orchestrator
  müsste dann für jedes Tool wissen, welcher Claim die Empfängeradresse ist und wie das Tool sie
  normalisiert. Genau dieses Wissen gehört ins Modul.
- **Jedes Modul bringt seine eigene Zählertabelle mit.** Verworfen: Die atomare Erhöhung, der
  Umgang mit einer fehlenden Zeile und die Aufbewahrung sind fehleranfällig
  ([07-betrieb.md](../07-betrieb.md) Abschnitt 4). Sie sollen genau einmal richtig umgesetzt sein.
- **Ein Port, dem man den Namensraum als freien Parameter übergibt**
  (`tryAttempt("auth_sms", key)`). Verworfen: Dann könnte ein Modul die Zähler eines anderen Moduls
  erhöhen oder zurücksetzen. Der Namensraum ergibt sich deshalb aus der Klasse der Mengenbegrenzung
  (siehe unten), nicht aus einem Argument.

**Preis.** SMS und E-Mail haben getrennte Versandlimits. Wer Adresse und Nummer eines Kontos kennt,
kann in zehn Minuten drei SMS und drei E-Mails auslösen, statt zusammen nur drei. Außerdem zählt
das SMS-Limit je Nummer. Deshalb teilen sich Einrichten (`enroll-sms`) und Anmelden (`auth-sms`,
`auth-sms-lookup`) dasselbe Versandlimit je Nummer. Wer die Nummer eines anderen kennt, kann dessen
SMS-Anmeldung also zehn Minuten lang blockieren. Über `auth-sms-lookup` ging das vorher ebenso.

## 1) Der Port

Der Port besteht aus zwei Teilen in `tool_api`: einer Klasse, mit der ein Modul eine
Mengenbegrenzung beschreibt, und einer Schnittstelle, die zählt.

- **`RateLimit`** (`tool_api`) ist eine abstrakte Klasse. Ein Modul leitet davon je
  Mengenbegrenzung eine eigene Klasse ab und legt dort Grenze und Zeitfenster fest. Der Namensraum
  dieser Mengenbegrenzung ist `<modul>.<Klasse>`. Er ergibt sich aus Paket und Name der Klasse, zum
  Beispiel `auth_sms.SmsSendLimit`. Ein Modul kann keinen fremden Namensraum benennen. Dafür müsste
  es von einer Klasse eines anderen Moduls ableiten, und diese Abhängigkeit verbietet Spring
  Modulith.
- **`RateLimits`** (`tool_api`) zählt einen Versuch (`tryAttempt`) oder setzt zurück (`reset`),
  jeweils für eine Mengenbegrenzung und einen Schlüssel. Umgesetzt wird das von `ModuleRateLimits`
  im Orchestrator. Der Schlüssel wird nie im Klartext gespeichert, sondern nur als HMAC. Ein
  HMAC ist ein Prüfwert, der mit einem geheimen Schlüssel berechnet wird, hier mit
  dem OTP-Pepper. Der Grund: Nummern und Adressen sind leicht zu erraten.
- **Tabelle.** Die Spalte `scope` enthält entweder den Namen eines Bereichs des Orchestrators
  (`RateLimitScope`) oder den Namensraum einer Mengenbegrenzung. Ein Namensraum enthält immer einen
  Punkt, ein Bereichsname nie. Beide können sich also nicht überschneiden.
- **Geprüft** wird das von `RateLimitArchitectureTest`:
  - Mengenbegrenzungen liegen in einem Tool-Modul.
  - Ihre Namensräume sind verschieden und passen in die Spalte.
  - Nur `RateLimit` ruft den Port auf.
  - Jede Klasse, die einen Code verschickt, prüft vorher das Versandlimit ihres Moduls.

## 2) Versandlimits in `auth_sms` und `auth_email`

Die beiden Module für SMS und E-Mail haben je ein eigenes Versandlimit.

- **`SmsSendLimit`** (`auth_sms`): drei TANs je Mobilnummer in zehn Minuten, gemeinsam über
  `enroll-sms`, `auth-sms` und `auth-sms-lookup`. Schlüssel ist die normalisierte Nummer
  (`PhoneNumber.normalize`), denn geschützt werden soll die Nummer, an die die SMS gehen.
- **`EmailSendLimit`** (`auth_email`): drei Codes je Adresse in zehn Minuten, gemeinsam über
  `confirm-email`, `auth-email` und `auth-email-lookup`. Schlüssel ist die normalisierte Adresse.
- **Zurückgesetzt nach Erfolg.** Gibt der Nutzer einen Code richtig ein, beginnt das Versandlimit
  dieser Nummer oder Adresse von vorn. Wer die Codes angefordert hat, hat damit bewiesen, dass er
  sie bekommt. Wer massenhaft SMS an eine fremde Nummer schicken lässt, kommt nie an diesen Punkt.
- **Antwort bei erschöpftem Versandlimit.** Hier gilt die Regel, dass eine Antwort nichts verraten
  darf, was der Anfragende nicht schon weiß:
  - Beim Einrichten (`enroll-sms`, `confirm-email`) hat der Nutzer die Nummer oder Adresse selbst
    eingegeben. Bei der Anmeldung mit bekanntem Konto (`auth-sms`, `auth-email`) kennt der Kanal das
    Konto schon. In beiden Fällen lautet die Antwort `429` (`TooManyRequestsException` aus
    `tool_api`). Dabei wird kein Versuch der Journey verbraucht. (Eine Journey ist der geführte
    Ablauf, den der Nutzer gerade durchläuft. Sie hat ein begrenztes Budget an Versuchen.)
  - Bei der Anmeldung über die E-Mail-Adresse (`auth-sms-lookup`, `auth-email-lookup`) sieht ein
    erschöpftes Versandlimit so aus wie eine unbekannte Adresse. Das ist dieselbe Regel wie bei
    einer Kontosperre. Es wird nichts gesendet, und kein Code passt.
- **Kontolöschung** löscht diese Zähler nicht. Sie gehören zu einer Nummer oder Adresse, nicht zum
  Konto, und liegen nur als HMAC vor. Sie verfallen mit der Aufbewahrungsfrist.

## 3) Welcher Zähler wohin gehört

Diese Übersicht zeigt für jeden bisherigen Zähler, ob er jetzt in einem Modul liegt oder im
Orchestrator bleibt, und warum.

- **Versand von SMS und E-Mail** (bisher `SendThrottleService`, Bereiche `ACCOUNT_SEND` und
  `CONTACT_SEND`): liegt jetzt in den Modulen, siehe Abschnitt 2.
- **Fehlgeschlagene Anmeldungen je Konto** (`AccountLockoutService`, `ACCOUNT`): bleibt im
  Orchestrator. Die Sperre gilt absichtlich über alle Anmeldeverfahren hinweg. Läge sie in jedem
  Modul einzeln, hätte ein Angreifer fünf Versuche je Verfahren. Außerdem schreibt sie das
  Anmeldeprotokoll ([ADR-39](ADR-039-was-eine-kontoloeschung-ueberlebt.md)). Und bei bekanntem Konto
  antwortet `beginActivation` mit `423`, bevor überhaupt ein Modul läuft.
- **Fehlgeschlagene Identifizierungen je Person** (`PersonLockoutService`, `PERSON`): bleibt im
  Orchestrator. `ident-fsc` und `ident-kvnr` melden beide bei einem Fehlversuch, welche Person das
  Ziel war (`Failed.Identification.attemptedPersonId`). Die Sperre schützt die Person über beide
  Tools hinweg.
- **Eröffnete Kanäle je DPoP-Schlüssel** (`ChannelCreationRateLimitService`, `BINDING_KEY`) und
  **Admin-Anmeldung** (`ADMIN`): bleiben im Orchestrator, weil beide zu keinem Tool gehören. (Ein
  Kanal ist die Verbindung eines Nutzers zum Orchestrator. Der DPoP-Schlüssel ist der Schlüssel,
  mit dem die App ihre Anfragen signiert.)
- **Versuchsbudget der Journey** (`AuthJourney.attemptBudget`): bleibt im Orchestrator, denn es gilt
  über alle Tools einer Journey.
- **Bestätigungscode des QR-Logins** (`auth_qr`, `countWrongConfirmation`): liegt schon im Modul,
  als Zähler an der Anfrage selbst. Er lebt nur so lange wie die Anfrage. Er braucht kein
  Zeitfenster über mehrere Anfragen hinweg und deshalb auch den Port nicht. Für die Suche nach einem
  `pairingCode` fehlt noch eine Mengenbegrenzung ([verfahren/qr.md](../verfahren/qr.md), „Sicherheit
  des Pairing-Codes“). Sie wäre eine Mengenbegrenzung von `auth_qr` über diesen Port.
- **PIN und Freigabe bei KOBIL:** Ein Fehlversuch zählt als fehlgeschlagene Anmeldung am Konto
  ([ADR-21](ADR-021-der-kobil-pin-liegt-im-backend-und-das.md)), also bei `AccountLockoutService`.
  Eine eigene Mengenbegrenzung im Modul gibt es nicht.

**Nachtrag 2026-09-28.** Beim Einrichten antwortete ein erschöpftes Versandlimit zunächst mit
`Failed.NothingGuessed`. Das zog einen der drei Versuche der Journey ab, obwohl niemand etwas
geraten hatte. Außerdem wurde dieselbe Ursache je nach Tool anders verbucht. Jetzt antworten
Einrichten und Anmeldung mit bekanntem Konto gleich: `429`, und es wird kein Versuch verbraucht.
