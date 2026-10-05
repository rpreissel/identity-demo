# Verfahren `kobil`

**Was es ist:** Der Nutzer bindet sein Smartphone über den externen Dienstleister **KOBIL** an sein
Konto. Bei jeder Anmeldung entsperrt er das Verfahren in der App, entweder mit Biometrie (etwa
Fingerabdruck) oder mit dem Passwort seines Kontos. Danach bestätigt KOBIL dem Server, dass die
Anmeldung von genau diesem Gerät kommt.

**Wozu es dient:** Es ist ein Anmeldeverfahren für die App mit **Gerätebindung**, also fest an ein
bestimmtes Smartphone gebunden. Es erreicht höchstens das Niveau `loa2`. Auf der Website gibt
es dieses Verfahren nicht.

KOBIL ist das erste Verfahren, dessen Nachweis nicht über den Client läuft. Der Client überbringt
nur eine Einmalkennung (OTP). Die Bestätigung des Geräts holt sich das Backend selbst beim Anbieter.
Ein manipulierter Client kann eine Kennung zurückhalten oder wiederholen, aber kein Ergebnis
vortäuschen. Der Besitz des Geräts ist damit stärker belegt als bei jedem anderen Tool. Für das
Entsperren gilt das nicht (siehe unten).

Begriffe wie Tool, Rolle, Fassung, Faktortyp und Niveau erklärt die
[Übersicht der Verfahren](README.md). Weitere Begriffe stehen im [Glossar](../glossar/glossar.md).

## Tools

Das Verfahren hat zwei Tools: eines zum Einrichten (`enroll-kobil`, beginnt mit dem Schritt
`activate`) und eines zum Anmelden eines bekannten Kontos (`auth-kobil`, beginnt mit dem Schritt
`unlock`).

| toolId | Rolle | Fassungen |
|---|---|---|
| `enroll-kobil` | `ENROLLMENT`, Startschritt `activate` | 1 |
| `auth-kobil` | `KNOWN_ACCOUNT_AUTH`, Startschritt `unlock` | 1 |

Das Verfahren kann drei Faktortypen erbringen: Besitz, Wissen und Inhärenz
(`{possession,knowledge,inherence}`). Es liefert höchstens das Niveau `loa2`. Es gilt
`onePerDevice`, also ein aktiver Eintrag je Gerät (`allowsMultipleInstances = true`). Deklariert ist
das Verfahren in `tools/auth_kobil/KobilToolModule.kt`. Diese Datei ist auch das Beispiel einer
Deklaration in [03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 2. Das Credential,
also das gespeicherte Merkmal für die Anmeldung, liegt in `auth_kobil.enrollment`.

## Grundentscheidungen

`enroll-kobil` und `auth-kobil` binden das Gerät nicht selbst, sondern über KOBIL.

Eine zweite Abweichung ist bewusst anders als der übliche Weg bei KOBIL: **Die PIN liegt im
Backend des Tools**, nicht beim Nutzer. Das Backend erzeugt sie beim Einrichten. Bei jeder
Anmeldung gibt es sie an den Client heraus, nachdem dieser sich lokal entsperrt hat. Der Client
entsperrt entweder per Gerätegeheimnis mit Biometrie-Schutz oder per Passwort des Kontos (ADR-21,
ADR-22).

Daraus folgen diese Punkte:

- Dieses Entsperren ist die `userVerification` des Verfahrens, kein zweiter Nachweis. Es nutzt die
  im Projekt üblichen Namen: `pin` für Wissen, `biometric` für Inhärenz. Das sind dieselben Werte
  wie bei `auth-device`.
  Ein `amr`-Eintrag `password` wäre nicht nur ein neuer Name, sondern falsch. `amr` ist die Liste
  der Verfahren, mit denen sich ein Nutzer in der Sitzung angemeldet hat. amr-Werte und Namen der
  Verfahren teilen sich einen Namensraum. `JourneyRecorder` würde dem Durchlauf deshalb das echte
  Passwort-Verfahren des Kontos zuschreiben.
- Die **Biometrie ist freiwillig**: Nur wenn der Nutzer zustimmt, entsteht überhaupt ein
  Gerätegeheimnis, und nur dann speichert der Server dessen Hash. Beide Wege zum Entsperren sind
  optional. Welche es für ein bestimmtes Credential gibt, **berechnet der Server** (unten, „Welche
  Wege zum Entsperren es gibt“).
- `kobil` deklariert dieselben `factorTypes` und dasselbe `maxAcr` wie `device`. Damit hat es
  **dieselbe Ausnahme** von der Regel „nur nachweisbare Faktoren melden"
  ([Orchestrierung](../04-orchestrierung.md) Abschnitt 4): In beiden Fällen gibt der Client selbst
  an, wie entsperrt wurde. Beides wird bewusst gleich behandelt, statt für dasselbe Entsperren eine
  zweite, strengere Regel einzuführen.
- `boundKeyRef` ist beim KOBIL-Verfahren der **DPoP-Schlüssel** des Kanals, nicht die Gerätekennung
  von KOBIL. Mit dem DPoP-Schlüssel belegt die App bei jeder Anfrage, dass sie von diesem Gerät
  kommt. Wenn das Verfahren angeboten wird, ist der Schlüssel das Einzige, was bekannt ist. Die
  Kennung erfährt der Server erst nach dem Einlösen. Sie steht deshalb getrennt als `reference`
  (für `device-link.boundCredentials`). Beide beantworten verschiedene Fragen zu verschiedenen
  Zeitpunkten. Die Kennung steht außerdem in `details` des Eintrags in `account.auth_method`, weil
  das Modul sie selbst wieder liest ([06-ablaeufe.md](../06-ablaeufe.md) Abschnitt 1).

### Vier Geheimnisse, vier verschiedene Aussagen

Am Verfahren sind vier Geheimnisse beteiligt. Jedes beweist dem Server etwas anderes:

| Was | Wo es liegt | Was es dem Server beweist |
|---|---|---|
| KOBIL-PIN | im Backend des Tools, je Durchlauf herausgegeben | **Nichts über den Nutzer** – er kennt sie nicht |
| Lokales Gerätegeheimnis mit Biometrie-Schutz | nur im Client | Den Weg zum Entsperren des Credentials |
| Passwort des Kontos | `auth_password.enrollment` | Einen zweiten Weg zum Entsperren |
| Bestätigung + Gerätekennung | bei KOBIL, vom Server per OTP eingelöst | **Echten Besitz** – der Server prüft selbst, statt zu glauben |

## Einrichtung (`enroll-kobil`, Schritt `activate`)

1. Start des Tools: Das Backend legt bei KOBIL einen Nutzer an und lässt einen Aktivierungscode
   ausstellen. Es erzeugt die PIN und setzt sie dort. `stepData` enthält `tenantId`, `kobilUserId`,
   `activationCode`, `pin` und ein frisch erzeugtes `unlockSecret`.
2. Der Client ruft damit direkt KOBIL auf (SDK-`ActivateEvent`). Dabei entsteht bei KOBIL die
   **Gerätekennung**. Das `unlockSecret` legt der Client nur bei Zustimmung lokal hinter seiner
   Biometrie ab. Andernfalls verwirft er es.
3. `PATCH {activated, biometricConsent, label}`: Das Backend fragt die Kennung bei KOBIL ab, nie
   beim Client. Denn mit ihr wird jede spätere Anmeldung verglichen. Dann schreibt es das
   Credential: Kennung, PIN, DPoP-`bindingKeyRef` und **nur bei Zustimmung** den Hash des
   `unlockSecret`. Das Ergebnis ist `Completed.Enrolled` mit `amr = [kobil, pin|biometric]`. Wie
   entsperrt wird, ergibt sich dabei aus der Zustimmung. Es ist keine zweite Eingabe.

`biometricConsent` hat keinen Standardwert. Denn eine Zustimmung, die der Nutzer nicht gegeben hat,
gibt es nicht. Ohne Zustimmung bleibt `unlock_secret_hash` NULL. „Biometrie erlaubt" ist damit kein
Schalter neben einem Geheimnis. Es bedeutet schlicht, dass es das Geheimnis gibt.

Ein `activated` ohne Gerät bei KOBIL ist **kein** Fehlschlag, sondern `Unchanged`. Wer die Seite neu
geladen hat, hat nichts geraten. Also wird auch kein Versuchsbudget verbraucht. Deshalb gibt der
Schritt seine Werte bei jedem Lesen erneut heraus: Solange die Einrichtung läuft, muss der Client
sie noch abholen können.

Sobald das Credential geschrieben ist, löscht das Backend die Aktivierungswerte aus der
Tool-Sitzung. Aktivierungscode, PIN und `unlockSecret` werden sofort geleert
(`EnrollKobilToolHandler.patch`), nicht erst beim Aufräumen der Tool-Sitzungen bis zu 24 Stunden
später. Die PIN ist danach nur noch im Credential gespeichert, das `unlockSecret` nur als Hash. Auch
KOBIL selbst nimmt einen Aktivierungscode nur einmal an und vergisst ihn danach
(`KobilSsms.activate`).

## Nutzung (`auth-kobil`, Schritte `unlock` und `otp`)

Bei der Anmeldung wechseln sich Client und Backend so ab:

| Schritt | Wer | Was |
|---|---|---|
| `unlock` | Client | Entsperrt lokal: `POST .../auth-kobil/pin-releases` mit dem Gerätegeheimnis **oder** dem Passwort des Kontos. Angeboten wird nur, was es wirklich gibt (`stepData.unlockOptions`) |
| – | Backend | Prüft und gibt die PIN heraus – nur in **dieser einen Antwort**; der Schritt wechselt auf `otp` |
| `otp` | Client | Meldet sich mit der PIN per SDK-`LoginEvent` bei KOBIL an und erhält ein OTP zurück |
| – | Client | `PATCH {otp}` |
| – | Backend | Löst das OTP bei KOBIL ein, vergleicht die Kennung und bewertet gemeldete Risiken |

Die Herausgabe der PIN ist eine **eigene Unterressource**,
`POST /tools/api/auth-kobil/v1/{toolSessionId}/pin-releases`, nicht Teil des `PATCH`. Es ist das
einzige Tool, das den eigenen URL-Bereich tatsächlich nutzt, den [05-api.md](../05-api.md)
Abschnitt 2 jedem Tool zusagt. Die Gründe dafür und die Form der Antwort stehen dort.

Der Anfrageinhalt ist ein echtes Entweder-oder (`sealed interface KobilUnlockCredential`). Eine
Anfrage mit beidem zugleich oder mit keinem von beiden lässt sich im Code gar nicht erzeugen. Damit
folgt der gemeldete Faktortyp aus dem Typ statt aus einem Schalter.

Das Passwort des Kontos ist auf diesem Weg der Schlüssel zum KOBIL-Credential, kein eigener
Anmeldeschritt. Geprüft wird es über `PasswordCredentialPort` ([Verfahren `password`](password.md)).
Gemeldet wird `pin`, nie `password`. Denn `password` würde dem Durchlauf das echte
Passwort-Verfahren zuschreiben und es doppelt zählen. Eine wiederholte Herausgabe ist erlaubt: Ist
das Zeitfenster abgelaufen, entsperrt der Nutzer einfach erneut.

### Welche Wege zum Entsperren es gibt, entscheidet nicht der Client

`unlockOptions` ist nicht fest, sondern wird abgeleitet:

- `biometric` gibt es genau dann, wenn es einen `unlock_secret_hash` gibt, wenn also der Nutzer
  zugestimmt hat.
- `password` gibt es genau dann, wenn das Konto noch ein Passwort hat.

`auth-kobil` nennt in `stepData` nur die Wege, die es tatsächlich gibt. Es bietet nicht beide an,
von denen dann einer scheitern muss. Ein Weg, den es nicht gibt, könnte nur zu einem führen: zu einem
Fehlversuch, der auf die Login-Sperre zählt.

Der Nachteil: Die Antwort verrät dem Aufrufer, ob das Konto ein Passwort hat. Das ist hier
vertretbar. Denn `auth-kobil` läuft überhaupt nur für einen Aufrufer, dessen Schlüssel schon zu einem
eingetragenen Credential **dieses** Kontos passt (`AuthMethodView.boundKeyRef`). Außerdem sieht
derselbe Aufrufer `activeMethods`, sobald er fertig ist.

Eine leere Liste ist möglich und wird auch so angezeigt. Ein Credential ohne Zustimmung zur
Biometrie auf einem Konto, das sein Passwort verloren hat, ist nicht mehr nutzbar. Der Client sagt
das, statt eine Schaltfläche anzubieten, die nicht funktionieren kann.

Verschwindet ein Credential, erkennt das KOBIL-Frontend an `GET /app/channels/device-link`, dass es
sein Gerätegeheimnis löschen muss ([05-api.md](../05-api.md) Abschnitt 3a,
[09-dpop.md](../09-dpop.md) Abschnitt 3).

## Was geprüft wird und was bei Abweichungen passiert

- Kein gültiges Zeitfenster für die Herausgabe -> `Failed("Entsperren erforderlich")`, zurück zu
  `unlock`.
- OTP unbekannt oder schon verbraucht -> `Failed("Bestaetigung nicht erkannt")`. Unbekannt,
  verbraucht und fremd ergeben bei KOBIL bewusst dieselbe Antwort.
- Die Kennung weicht ab -> `Failed("Geraet nicht erkannt")`. Das ist derselbe Wortlaut wie bei
  `auth-device` und verrät nicht, welches Gerät erwartet wurde.
- Ein gemeldetes Risiko steht auf der eingestellten Sperrliste (`identity.kobil.blocking-risks`) ->
  `Failed("Geraet als unsicher gemeldet")`. Das ist bewusst ein eigener Grund. Es ist kein
  Tippfehler des Nutzers, sondern eine Aussage über das Gerät. In einem „nicht erkannt" ginge diese
  echte Feststellung verloren. Geprüft wird gegen eine benannte Liste, nicht gegen einen Punktwert.
  Ein Punktwert wäre erfunden und läse sich trotzdem wie eine Messung. Beide Seiten sind
  abgeschlossene Enums. Ein unbekanntes Signal lässt sich deshalb gar nicht erzeugen, und es ist
  auch kein Fall, den man prüfen müsste.
- Falsches Gerätegeheimnis, falsches Passwort und gar kein Passwort-Credential -> immer derselbe
  Wortlaut (`Failed("Entsperren fehlgeschlagen")`). So lässt sich daraus nicht ablesen, ob das Konto
  ein Passwort hat.

Alle Fehlschläge sind gewöhnliche Fehlversuche (`200` mit `stepData.error`, Versuchsbudget der
Journey), kein Fehlerstatus. Über `chargeRateLimits` zählen sie auf den `AccountLockoutService`,
auch die Ablehnung wegen eines Risikos. Ein gerootetes Telefon kann seinen Besitzer also aussperren.
Das wird bewusst in Kauf genommen, statt eine Sonderbehandlung einzuführen.

## Was es nicht gibt

Zwei Dinge sind nicht gebaut (siehe [05-api.md](../05-api.md) Abschnitt 3b):

- eine `-lookup`-Variante, denn das Credential ist an einen Schlüssel gebunden;
- ein `WebToolRenderer` für den Web-Kanal, denn ein SDK für Telefone lässt sich aus einer
  Anmeldeseite im Browser nicht ansprechen.

## In der Demo

Der Anbieter ist simuliert (`demoOnly`). Das Modul `kobil` hat:

- ein eigenes Schema,
- eine eigene HTTP-Schnittstelle für die App (`/mock-kobil/*`, das Gegenstück zum MC SDK),
- die Schnittstelle `KobilSsms` für unser Backend.

Es gibt kein Spring-Profil und keine zweite Implementierung. In der Demo übernimmt die Simulation
vollständig die Rolle von KOBIL. Ihre Operationen heißen nach dem, was SSMS tut: Nutzer anlegen,
Aktivierungscode ausstellen, PIN setzen, Geräte eines Nutzers abfragen, OTP am Services-Knoten
prüfen. Sie heißen nicht nach unserem Ablauf. Die echten Nachrichtenformate spielen für die Demo
keine Rolle. Den Fremddienst ruft das Frontend direkt auf (`src/kobilSdk.ts`,
[10-frontend.md](../10-frontend.md)).
