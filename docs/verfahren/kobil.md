# Verfahren `kobil`

Gerätebindung über den externen Dienstleister **KOBIL** – das erste Verfahren, dessen Nachweis
nicht über den Client läuft. Der Client überbringt nur eine Einmalkennung (OTP); die Bestätigung
des Geräts holt sich das Backend selbst beim Anbieter. Ein manipulierter Client kann eine Kennung
zurückhalten oder wiederholen, aber kein Ergebnis vortäuschen. Der Besitz des Geräts ist damit
stärker belegt als bei jedem anderen Tool; das Entsperren ist es nicht (unten).

## Tools

| toolId | Rolle | Fassungen |
|---|---|---|
| `enroll-kobil` | `ENROLLMENT`, Startschritt `activate` | 1 |
| `auth-kobil` | `KNOWN_ACCOUNT_AUTH`, Startschritt `unlock` | 1 |

Faktoren `{possession,knowledge,inherence}`, höchstens `loa2`; `onePerDevice`, also ein aktiver
Eintrag je Gerät (`allowsMultipleInstances = true`). Deklariert in
`tools/auth_kobil/KobilToolModule.kt` (das Beispiel einer Deklaration in
[03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 2). Das Credential liegt in
`auth_kobil.enrollment`.

## Grundentscheidungen

`enroll-kobil`/`auth-kobil` binden das Gerät nicht selbst, sondern über KOBIL. Eine zweite
Abweichung ist bewusst anders als der übliche Weg bei KOBIL: **Der PIN liegt im
Backend des Tools**, nicht beim Nutzer. Er wird beim Einrichten dort erzeugt und bei jeder
Anmeldung an den Client herausgegeben, nachdem dieser sich lokal entsperrt hat: per
Gerätegeheimnis mit Biometrie-Schutz oder per Passwort des Kontos (ADR-21, ADR-22).

- Dieses Entsperren ist die `userVerification` des Verfahrens, kein zweiter Nachweis. Es
  nutzt die im Projekt üblichen Namen: `pin` für Wissen, `biometric` für Inhärenz – dieselben
  Werte wie bei `auth-device`. (Ein amr-Eintrag `password` wäre nicht nur ein neuer Name, sondern
  falsch: amr-Werte und Namen der Verfahren teilen sich einen Namensraum, und `JourneyRecorder` würde
  dem Durchlauf das echte Passwort-Verfahren des Kontos anhängen.)
- Die **Biometrie ist freiwillig**: Nur wenn der Nutzer zustimmt, entsteht überhaupt ein
  Gerätegeheimnis, und nur dann speichert der Server dessen Hash. Beide Wege zum Entsperren sind
  optional; welche es für ein bestimmtes Credential gibt, **berechnet der Server** (unten, „Welche
  Wege zum Entsperren es gibt“).
- `kobil` deklariert dieselben `factorTypes` und dasselbe `maxAcr` wie `device` und hat damit
  **dieselbe Ausnahme** von der Regel „nur nachweisbare Faktoren melden"
  ([Orchestrierung](../04-orchestrierung.md) Abschnitt 4): Wie entsperrt wurde, gibt in beiden Fällen
  der Client selbst an. Beides wird bewusst gleich behandelt, statt für dasselbe Entsperren eine
  zweite, strengere Regel einzuführen.
- `boundKeyRef` ist beim KOBIL-Verfahren der **DPoP-Schlüssel** des Kanals, nicht die
  Gerätekennung von KOBIL. Wenn das Verfahren angeboten wird, ist der Schlüssel das Einzige, was
  bekannt ist; die Kennung erfährt der Server erst nach dem Einlösen. Sie steht deshalb getrennt
  als `reference` (für `device-link.boundCredentials`): Beide beantworten verschiedene Fragen zu
  verschiedenen Zeitpunkten. Die Kennung steht außerdem in `details` des Eintrags in
  `account.auth_method`, weil das Modul sie selbst wieder liest
  ([06-ablaeufe.md](../06-ablaeufe.md) Abschnitt 1).

### Vier Geheimnisse, vier verschiedene Aussagen

| Was | Wo es liegt | Was es dem Server beweist |
|---|---|---|
| KOBIL-PIN | im Backend des Tools, je Durchlauf herausgegeben | **Nichts über den Nutzer** – er kennt ihn nicht |
| Lokales Gerätegeheimnis mit Biometrie-Schutz | nur im Client | Den Weg zum Entsperren des Credentials |
| Passwort des Kontos | `auth_password.enrollment` | Einen zweiten Weg zum Entsperren |
| Bestätigung + Gerätekennung | bei KOBIL, vom Server per OTP eingelöst | **Echten Besitz** – der Server prüft selbst, statt zu glauben |

## Einrichtung (`enroll-kobil`, Schritt `activate`)

1. Start des Tools: Das Backend legt bei KOBIL einen Nutzer an, lässt einen Aktivierungscode
   ausstellen, erzeugt den PIN und setzt ihn dort. `stepData` enthält `tenantId`, `kobilUserId`,
   `activationCode`, `pin` und ein frisch erzeugtes `unlockSecret`.
2. Der Client ruft damit direkt KOBIL auf (SDK-`ActivateEvent`). Dabei entsteht bei KOBIL die
   **Gerätekennung**. Das `unlockSecret` legt der Client nur bei Zustimmung lokal hinter seiner
   Biometrie ab; andernfalls verwirft er es.
3. `PATCH {activated, biometricConsent, label}`: Das Backend fragt die Kennung bei KOBIL ab – nie
   beim Client, denn mit ihr wird jede spätere Anmeldung verglichen. Dann schreibt es das
   Credential: Kennung, PIN, DPoP-`bindingKeyRef` und **nur bei Zustimmung** den Hash des
   `unlockSecret`. Das Ergebnis ist `Completed.Enrolled` mit `amr = [kobil, pin|biometric]`. Der
   Weg zum Entsperren ergibt sich dabei aus der Zustimmung; es ist keine zweite Eingabe.

`biometricConsent` hat keinen Standardwert: Eine Zustimmung, die man nicht gegeben hat, gibt es
nicht. Ohne sie bleibt `unlock_secret_hash` NULL. „Biometrie erlaubt" ist damit kein Schalter neben
einem Geheimnis, sondern bedeutet schlicht, dass es das Geheimnis gibt.

Ein `activated` ohne Gerät bei KOBIL ist **kein** Fehlschlag, sondern `Unchanged`: Wer die Seite neu
geladen hat, hat nichts geraten, also wird auch kein Versuchsbudget verbraucht. Deshalb gibt der
Schritt seine Werte bei jedem Lesen erneut heraus: Solange die Einrichtung läuft, muss der Client
sie noch abholen können.

Sobald das Credential geschrieben ist, löscht das Backend die Aktivierungswerte aus der
Tool-Sitzung: Aktivierungscode, PIN und `unlockSecret` werden sofort geleert
(`EnrollKobilToolHandler.patch`), nicht erst beim Aufräumen der Tool-Sitzungen bis zu 24 Stunden
später. Der PIN lebt danach nur noch im Credential, das `unlockSecret` nur als Hash. Auch KOBIL
selbst nimmt einen Aktivierungscode nur einmal an und vergisst ihn danach (`KobilSsms.activate`).

## Nutzung (`auth-kobil`, Schritte `unlock` und `otp`)

| Schritt | Wer | Was |
|---|---|---|
| `unlock` | Client | Entsperrt lokal: `POST .../auth-kobil/pin-releases` mit dem Gerätegeheimnis **oder** dem Passwort des Kontos. Angeboten wird nur, was es wirklich gibt (`stepData.unlockOptions`) |
| – | Backend | Prüft und gibt den PIN heraus – nur in **dieser einen Antwort**; der Schritt wechselt auf `otp` |
| `otp` | Client | Meldet sich mit dem PIN per SDK-`LoginEvent` bei KOBIL an und erhält ein OTP zurück |
| – | Client | `PATCH {otp}` |
| – | Backend | Löst das OTP bei KOBIL ein, vergleicht die Kennung und bewertet gemeldete Risiken |

Die Herausgabe des PIN ist eine **eigene Unterressource**,
`POST /tools/api/auth-kobil/v1/{toolSessionId}/pin-releases`, nicht Teil des `PATCH`. Es ist das
einzige Tool, das den eigenen URL-Bereich tatsächlich nutzt, den [05-api.md](../05-api.md)
Abschnitt 2 jedem Tool zusagt; die Gründe dafür und die Form der Antwort stehen dort.

Der Anfrageinhalt ist ein echtes Entweder-oder (`sealed interface KobilUnlockCredential`): Beides
zugleich oder keines von beiden lässt sich gar nicht bilden. Damit folgt der gemeldete Faktortyp
aus dem Typ statt aus einem Schalter.

Das Passwort des Kontos ist auf diesem Weg der Schlüssel zum KOBIL-Credential, kein eigener
Anmeldeschritt. Geprüft wird es über `PasswordCredentialPort` ([Verfahren `password`](password.md)),
gemeldet wird `pin` – nie
`password`, denn das würde dem Durchlauf das echte Passwort-Verfahren anhängen und es doppelt
zählen. Eine wiederholte Herausgabe ist erlaubt: Wessen Zeitfenster abgelaufen ist, entsperrt
einfach erneut.

### Welche Wege zum Entsperren es gibt, entscheidet nicht der Client

`unlockOptions` wird abgeleitet, es ist nicht fest: `biometric` genau dann, wenn es einen
`unlock_secret_hash` gibt (also jemand zugestimmt hat), `password` genau dann, wenn das Konto noch
ein Passwort hat. `auth-kobil` nennt in `stepData` nur die Wege, die es tatsächlich gibt, statt
beide anzubieten und einen davon ins Leere laufen zu lassen. Einen Weg anzubieten, den es nicht
gibt, könnte nur zu einem führen: einem Fehlversuch, der die Login-Sperre belastet.

Der Preis dafür: Die Antwort verrät dem Aufrufer, ob das Konto ein Passwort hat. Das ist hier
vertretbar, weil `auth-kobil` überhaupt nur für einen Aufrufer läuft, dessen Schlüssel schon zu
einem eingetragenen Credential **dieses** Kontos passt (`AuthMethodView.boundKeyRef`). Außerdem sieht derselbe
Aufrufer `activeMethods`, sobald er fertig ist.

Eine leere Liste ist möglich und wird auch so angezeigt: Ein Credential ohne Zustimmung zur
Biometrie auf einem Konto, das sein Passwort verloren hat, ist nicht mehr nutzbar. Der Client sagt
das, statt eine Schaltfläche anzubieten, die nicht funktionieren kann.

Verschwindet ein Credential, erkennt das KOBIL-Frontend an `GET /app/channels/device-link`, dass es
sein Gerätegeheimnis löschen muss ([05-api.md](../05-api.md) Abschnitt 3a,
[09-dpop.md](../09-dpop.md) Abschnitt 3).

## Was geprüft wird und was bei Abweichungen passiert

- Kein gültiges Zeitfenster für die Herausgabe -> `Failed("Entsperren erforderlich")`, zurück zu
  `unlock`.
- OTP unbekannt oder schon verbraucht -> `Failed("Bestaetigung nicht erkannt")`. Unbekannt,
  verbraucht und fremd sind bei KOBIL bewusst dieselbe Antwort.
- Die Kennung weicht ab -> `Failed("Geraet nicht erkannt")`. Das ist derselbe Wortlaut wie bei
  `auth-device` und verrät nicht, welches Gerät erwartet wurde.
- Ein gemeldetes Risiko gehört zur eingestellten Sperrliste (`identity.kobil.blocking-risks`) ->
  `Failed("Geraet als unsicher gemeldet")`. Das ist bewusst ein eigener Grund: Es ist kein
  Tippfehler des Nutzers, sondern eine Aussage über das Gerät; in einem „nicht erkannt" ginge ein
  echter Befund verloren. Geprüft wird gegen eine benannte Liste, nicht gegen einen Punktwert: Ein
  Punktwert wäre erfunden und läse sich trotzdem wie eine Messung. Da beide Seiten abgeschlossene
  Enums sind, lässt sich ein unbekanntes Signal gar nicht bilden; es ist also auch kein Fall, den
  man prüfen müsste.
- Falsches Gerätegeheimnis, falsches Passwort und gar kein Passwort-Credential -> immer derselbe
  Wortlaut (`Failed("Entsperren fehlgeschlagen")`). So lässt sich daraus nicht ablesen, ob das Konto
  ein Passwort hat.

Alle Fehlschläge sind gewöhnliche Fehlversuche (`200` mit `stepData.error`, Versuchsbudget der
Journey), kein Fehlerstatus. Über `chargeRateLimits` belasten sie den
`AccountLockoutService`, auch die Ablehnung wegen eines Risikos. Ein gerootetes Telefon kann seinen
Besitzer also aussperren. Das wird bewusst in Kauf genommen, statt eine Sonderbehandlung
einzuführen.

## Was es nicht gibt

Nicht gebaut sind eine `-lookup`-Variante (das Credential ist an einen Schlüssel gebunden) und ein
`WebToolRenderer` für den Web-Kanal (ein SDK für Telefone lässt sich aus einer Anmeldeseite
im Browser nicht ansprechen) – siehe [05-api.md](../05-api.md) Abschnitt 3b.

## In der Demo

Der Anbieter ist simuliert (`demoOnly`): Das Modul `kobil` hat ein eigenes Schema, eine eigene
HTTP-Schnittstelle für die App (`/mock-kobil/*`, das Gegenstück zum MC SDK) und die Schnittstelle
`KobilSsms` für unser Backend. Es gibt kein Spring-Profil und keine zweite Implementierung: Die
Simulation *ist* KOBIL. Ihre Operationen heißen nach dem, was SSMS tut (Nutzer anlegen,
Aktivierungscode ausstellen, PIN setzen, Geräte eines Nutzers abfragen, OTP am Services-Knoten
prüfen), nicht nach unserem Ablauf. Die echten Nachrichtenformate spielen für die Demo keine
Rolle. Den Fremddienst ruft das Frontend direkt auf (`src/kobilSdk.ts`,
[10-frontend.md](../10-frontend.md)).
