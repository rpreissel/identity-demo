# ADR-44: Das Zählwerk liegt im Orchestrator, die Regeln in den Modulen

**Status:** umgesetzt 2026-09.

**Entscheidung.** Der Orchestrator stellt nur das Zählwerk: atomar zählen in einem gleitenden
Zeitfenster, zurücksetzen, aufbewahren (`RateLimitCounter`, Tabelle `orchestrator.rate_limit`).
Er leiht es den Tool-Modulen über einen Port in `tool_api` (`RateLimits`). Was gezählt wird,
unter welchem Schlüssel, mit welcher Grenze und wann der Zähler von vorn beginnt, entscheidet das
Modul, dem die Sache gehört. Ein Zähler bleibt nur dann im Orchestrator, wenn er absichtlich über
mehrere Tools hinweg gilt oder zu keinem Tool gehört.

Der erste Fall ist der Versand von Codes: `auth_sms` verschickt die SMS und ist deshalb auch dafür
verantwortlich, dass niemand über seine Tools eine fremde Nummer mit SMS überschwemmt; ebenso
`auth_email` für E-Mails.

**Warum.** Bisher lagen Grenzen und Schlüssel des Versands in `SendThrottleService` im Orchestrator,
und jeder Controller musste daran denken, vor dem Senden `isSendThrottled` oder
`isSendThrottledForContact` zu fragen. Der Orchestrator entschied damit über etwas, das er nicht
tut: Er verschickt keine SMS und weiß nicht, an welche Nummer ein Tool sendet. Die Frage stand im
Controller, der Versand im Handler; zwischen beiden konnte eine neue Stelle das Fragen vergessen.
Außerdem ließ sich eine Mengenbegrenzung nicht dort zurücksetzen, wo man weiß, dass der Code angekommen ist:
im Modul, das den Code prüft.

**Erwogene Alternativen.**

- **Alles im Orchestrator lassen und dort nach Erfolg zurücksetzen** (`ToolJourneyService`
  wertet `Completed` aus und leitet aus den Claims die Nummer ab). Verworfen: Der Orchestrator
  müsste dann für jedes Tool wissen, welcher Claim die Empfängeradresse ist und wie das Tool sie
  normalisiert. Das ist genau das Wissen, das ins Modul gehört.
- **Jedes Modul bringt seine eigene Zählertabelle mit.** Verworfen: Die atomare Erhöhung, die
  Behandlung einer fehlenden Zeile und die Aufbewahrung sind heikel
  ([07-betrieb.md](../07-betrieb.md) Abschnitt 4) und sollen genau einmal richtig implementiert
  sein.
- **Ein Port mit freiem Namensraum als Parameter** (`tryAttempt("auth_sms", key)`). Verworfen: Dann
  könnte ein Modul die Zähler eines anderen erhöhen oder zurücksetzen. Der Namensraum folgt deshalb
  aus der Klasse der Mengenbegrenzung (siehe unten), nicht aus einem Argument.

**Preis.** SMS und E-Mail haben getrennte Versandlimits. Wer Adresse und Nummer eines Kontos kennt, kann
in zehn Minuten drei SMS und drei E-Mails auslösen statt zusammen drei. Und weil das SMS-Limit an
der Nummer hängt, teilen sich Einrichten (`enroll-sms`) und Anmelden (`auth-sms`,
`auth-sms-lookup`) dasselbe Versandlimit je Nummer: Wer die Nummer eines anderen kennt, kann dessen
SMS-Anmeldung zehn Minuten lang aufhalten. Das ging vorher über `auth-sms-lookup` ebenso.

## 1) Der Port

- **`RateLimit`** (`tool_api`): abstrakte Klasse, von der ein Modul je Mengenbegrenzung eine Klasse
  ableitet (Grenze, Zeitfenster). Ihr Namensraum ist `<modul>.<Klasse>`, abgeleitet aus Paket und
  Name der Klasse, also zum Beispiel `auth_sms.SmsSendLimit`. Ein Modul kann keinen fremden
  Namensraum benennen: Dafür müsste es von einer Klasse eines anderen Moduls ableiten, und diese
  Abhängigkeit verbietet Spring Modulith.
- **`RateLimits`** (`tool_api`): zählt einen Versuch (`tryAttempt`) oder setzt zurück
  (`reset`), jeweils für eine Mengenbegrenzung und einen Schlüssel. Implementiert von `ModuleRateLimits`
  im Orchestrator. Der Schlüssel wird nur als HMAC mit dem OTP-Pepper gespeichert, nie im
  Klartext: Nummern und Adressen sind leicht zu erraten.
- **Tabelle:** Die Spalte `scope` trägt entweder den Namen eines Bereichs des Orchestrators
  (`RateLimitScope`) oder den Namensraum einer Mengenbegrenzung. Ein Namensraum enthält einen Punkt, ein
  Bereichsname nie; beide können sich also nicht überschneiden.
- **Geprüft** von `RateLimitArchitectureTest`: Mengenbegrenzungen liegen in einem Tool-Modul, ihre
  Namensräume sind verschieden und passen in die Spalte, nur `RateLimit` ruft den Port, und
  jede Klasse, die einen Code verschickt, fragt vorher das Versandlimit ihres Moduls.

## 2) Versandlimits in `auth_sms` und `auth_email`

- **`SmsSendLimit`** (`auth_sms`): drei TANs je Mobilnummer in zehn Minuten, über `enroll-sms`,
  `auth-sms` und `auth-sms-lookup` hinweg. Schlüssel ist die normalisierte Nummer
  (`PhoneNumber.normalize`), denn die Nummer ist es, die überschwemmt wird.
- **`EmailSendLimit`** (`auth_email`): drei Codes je Adresse in zehn Minuten, über
  `confirm-email`, `auth-email` und `auth-email-lookup`. Schlüssel ist die normalisierte Adresse.
- **Zurückgesetzt nach Erfolg.** Wird ein Code richtig eingegeben, beginnt das Versandlimit dieser
  Nummer oder Adresse von vorn. Wer die Codes angefordert hat, hat damit bewiesen, dass er sie
  bekommt. Wer eine fremde Nummer überschwemmt, kommt nie an diesen Punkt.
- **Antwort bei erschöpftem Versandlimit** folgt der Regel, dass die Antwort nichts verraten darf, was
  der Anfragende nicht schon weiß:
  - Beim Einrichten (`enroll-sms`, `confirm-email`) hat der Nutzer die Nummer oder Adresse selbst
    eingegeben, und bei der Anmeldung mit bekanntem Konto (`auth-sms`, `auth-email`) kennt der
    Kanal das Konto schon. Beide antworten mit `429` (`TooManyRequestsException` aus `tool_api`),
    ohne einen Versuch der Journey zu verbrauchen.
  - Bei der Anmeldung über die E-Mail-Adresse (`auth-sms-lookup`, `auth-email-lookup`) sieht ein
    erschöpftes Versandlimit aus wie eine unbekannte Adresse, dieselbe Regel wie bei einer Kontosperre.
    Es wird nichts gesendet, und kein Code passt.
- **Kontolöschung** löscht diese Zähler nicht: Sie hängen an Nummer oder Adresse, nicht am Konto,
  und liegen nur als HMAC vor. Sie verfallen mit der Aufbewahrungsfrist.

## 3) Welcher Zähler wohin gehört

- **Versand von SMS und E-Mail** (bisher `SendThrottleService`, Bereiche `ACCOUNT_SEND` und
  `CONTACT_SEND`): in die Module, siehe Abschnitt 2.
- **Fehlgeschlagene Anmeldungen je Konto** (`AccountLockoutService`, `ACCOUNT`): bleibt im
  Orchestrator. Die Sperre gilt absichtlich über alle Anmeldeverfahren hinweg; läge sie je Modul,
  hätte ein Angreifer fünf Versuche je Verfahren. Sie schreibt außerdem das Anmeldeprotokoll
  ([ADR-39](ADR-039-was-eine-kontoloeschung-ueberlebt.md)), und bei bekanntem Konto antwortet
  `beginActivation` mit `423`, bevor irgendein Modul läuft.
- **Fehlgeschlagene Identifizierungen je Person** (`PersonLockoutService`, `PERSON`): bleibt im
  Orchestrator. `ident-fsc` und `ident-kvnr` melden beide eine Person als Ziel eines Fehlversuchs
  (`Failed.Identification.attemptedPersonId`); die Sperre schützt die Person über beide Tools.
- **Eröffnete Kanäle je DPoP-Schlüssel** (`ChannelCreationRateLimitService`, `BINDING_KEY`) und
  **Admin-Anmeldung** (`ADMIN`): bleiben im Orchestrator. Beides gehört zu keinem Tool.
- **Versuchsbudget der Journey** (`AuthJourney.attemptBudget`): bleibt im Orchestrator; es gilt
  über alle Tools einer Journey.
- **Bestätigungscode des QR-Logins** (`auth_qr`, `countWrongConfirmation`): liegt schon im Modul,
  als Zähler an der Anfrage selbst. Er lebt nur so lange wie die Anfrage und braucht kein
  Zeitfenster über Anfragen hinweg, also auch den Port nicht. Die offene Mengenbegrenzung für die Suche nach
  einem `pairingCode` ([07-betrieb.md](../07-betrieb.md) Abschnitt 5) wäre eine Mengenbegrenzung von
  `auth_qr` auf diesem Port.
- **PIN und Freigabe bei KOBIL:** Ein Fehlversuch zählt als fehlgeschlagene Anmeldung am Konto
  ([ADR-21](ADR-021-der-kobil-pin-liegt-im-backend-und-das.md)), also bei `AccountLockoutService`.
  Eine eigene Mengenbegrenzung im Modul gibt es nicht.

**Nachtrag 2026-09-28.** Beim Einrichten antwortete ein erschöpftes Versandlimit zunächst mit
`Failed.NothingGuessed`. Das zog einen der drei Versuche der Journey ab, obwohl niemand etwas geraten
hatte, und dieselbe Ursache wurde je nach Tool anders verbucht. Jetzt antworten Einrichten und
Anmeldung mit bekanntem Konto gleich: `429`, kein Versuch.
