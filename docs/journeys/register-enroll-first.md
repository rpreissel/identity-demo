> Eine Journey aus dem Katalog. Wie die Diagramme zu lesen sind, erklärt
> [../04-orchestrierung.md](../04-orchestrierung.md), Abschnitt 3.

# `REGISTER`: Experiment „Erst Anmeldeverfahren einrichten“

Die normale Registrierung beschreibt [`REGISTER`](register.md).

Dies ist eine zweite, eigenständige Variante von `REGISTER` mit eigenen Zuständen
(`RegisterEnrollFirstState`, nichts davon ist mit `RegisterState`, `AuthChoice` oder `Enrolling`
geteilt) und eigener Strategie. Unter `AuthIntent.REGISTER` ist nur ein einziges Spring-Bean
registriert, `RegisterDispatchStrategy`. Es wählt für jede **neue** Journey einmal zwischen beiden
Varianten. Welche Variante eine laufende Journey nutzt, ergibt sich danach allein aus ihrem
Zustandstyp (`is RegisterEnrollFirstState` oder `is RegisterState`). Der Schalter wird dafür nie
erneut gelesen.

Eingeschaltet wird die Variante über den Laufzeitschalter `JourneyFeatureFlag.REGISTER_ENROLL_FIRST`
(`"register-enroll-first"`). Den Wert liefert `FeatureFlagService` (`@Service`, implementiert
`FeatureFlagProvider`) aus der Tabelle `orchestrator.feature_flag`. Dort steht eine Zeile je
Schalter; fehlt die Zeile, ist der Schalter aus. Lesen und setzen lässt er sich über
`GET/PUT /orchestrator/admin/registration-order` (`RegistrationOrderController`).

**Grundidee:** Zum Start ist kein Konto nötig. Es entsteht erst, wenn das erste Verfahren
fertig eingerichtet ist (das allgemeine `Action.AdoptCredential` im `JourneyService`), und nicht
schon bei der Identifizierung. Bis dahin rechnet die Strategie mit einem Platzhalter-Konto, das nur
im Speicher existiert und nie gespeichert wird (`AccountProfile(accountId = -1, ...)`).

**Feste Reihenfolge: erst E-Mail, dann SMS.** In der normalen Variante (`RegisterState`,
`AuthEnrollCore`) wählt der Nutzer frei. Diese Variante verlangt dagegen zuerst die Bestätigung der
E-Mail-Adresse (`EnrollFirstAttestingEmail`, ein `ATTESTATION`-Schritt, kein Einrichten) und danach das
Einrichten von SMS (`EnrollFirstEnrollingSms`). Beide Schritte lassen sich nicht überspringen: Wer
ablehnt (`Abandoned`), bekommt denselben Schritt erneut angeboten. Hat der Betreiber eines der
beiden Tools gesperrt, entfällt nur dieser Schritt; die Journey wird dadurch nicht blockiert.

Danach gelten dieselben Pflichten wie in der normalen Variante (Orchestrierung, Abschnitt 5): ein
weiteres Verfahren, falls das Niveau nicht reicht, dann die E-Mail-Bestätigung, dann die
Pflicht zur zweiten Faktorart. `EnrollFirstEnrolling` fängt alles auf, was E-Mail und SMS nicht abdecken, etwa
ein höheres Sicherheitsniveau. Es ist außerdem der Startzustand, wenn beim Start weder E-Mail noch
SMS verfügbar waren.

Erst wenn alle Pflichten erfüllt sind, wird die Identifizierung **einmal angeboten, aber nie
erzwungen**. Das geschieht über die Sub-Journey `RE_IDENTIFY` (`Transition.RequireSubJourney`).
Lehnt der Nutzer ab oder gibt es nichts anzubieten, endet die Journey trotzdem erfolgreich
(`Transition.Authenticated`). Das Konto ist dann angemeldet, aber nicht identifiziert. Gehört die
identifizierte Person bereits zu einem anderen, echten Konto, lehnt der Executor die
Identifizierung mit `409` ab, denn zwei echte Konten werden nie zusammengeführt. Gibt es für diese
Person dagegen nur ein verwerfbares Konto (etwa aus einem früher abgebrochenen eID-Lauf), wird es
übernommen (ADR-20).

**Rückfrage zum Gerät am Ende.** Gehört das Gerät schon einem anderen Konto, fragt diese Variante
erst am **Ende** der Journey, ob es neu verknüpft werden soll (`EnrollFirstConfirmDeviceRebind`),
nach der freiwilligen Identifizierung. Die normale Variante identifiziert zuerst und kann direkt
danach fragen; hier verknüpft die Journey beim ersten eingerichteten Verfahren, und zu diesem
Zeitpunkt ist das Konto gerade erst entstanden und hat noch keine Identität. Lehnt der Nutzer ab,
endet die Registrierung ohne Geräteverknüpfung. Das Konto bleibt über die Anmeldung per
E-Mail-Adresse voll nutzbar ([09-dpop.md](../09-dpop.md) Abschnitt 3).

```mermaid
stateDiagram-v2
  [*] --> EnrollFirstStart
  EnrollFirstStart --> EnrollFirstAttestingEmail: Start - die Adresse kommt zuerst
  EnrollFirstStart --> EnrollFirstEnrollingSms: Start, kein Bestätigungs-Tool verfügbar
  EnrollFirstStart --> EnrollFirstEnrolling: Start, weder E-Mail- noch SMS-Tool verfügbar
  EnrollFirstAttestingEmail --> EnrollFirstAttestingEmail: abgelehnt - derselbe Schritt wird erneut angeboten
  EnrollFirstAttestingEmail --> EnrollFirstEnrollingSms: E-Mail bestätigt
  EnrollFirstEnrollingSms --> EnrollFirstEnrollingSms: abgelehnt - derselbe Schritt wird erneut angeboten
  EnrollFirstEnrollingSms --> EnrollFirstEnrolling: SMS eingerichtet, aber Niveau reicht noch nicht
  EnrollFirstEnrollingSms --> EnrollFirstConfirmingEmail: SMS eingerichtet, Niveau erreicht, E-Mail-Pflicht noch offen
  EnrollFirstEnrollingSms --> EnrollFirstSecondFactorKindObligation: SMS eingerichtet, Niveau erreicht, E-Mail bereits bestätigt, loa2 sonst nicht erreichbar, nur eine Faktorart aktiv
  EnrollFirstEnrollingSms --> RE_IDENTIFY: SMS eingerichtet, Niveau erreicht, keine Pflicht offen - Identifizierung wird angeboten
  EnrollFirstEnrolling --> EnrollFirstEnrolling: Verfahren eingerichtet, Niveau reicht noch nicht
  EnrollFirstEnrolling --> EnrollFirstConfirmingEmail: Niveau erreicht, E-Mail-Pflicht noch offen
  EnrollFirstEnrolling --> EnrollFirstSecondFactorKindObligation: Niveau erreicht, E-Mail bereits bestätigt, loa2 sonst nicht erreichbar, nur eine Faktorart aktiv
  EnrollFirstEnrolling --> RE_IDENTIFY: Niveau erreicht, keine Pflicht offen - Identifizierung wird angeboten
  EnrollFirstConfirmingEmail --> EnrollFirstSecondFactorKindObligation: E-Mail bestätigt, loa2 sonst nicht erreichbar, nur eine Faktorart aktiv
  EnrollFirstConfirmingEmail --> RE_IDENTIFY: E-Mail bestätigt, keine weitere Pflicht offen
  EnrollFirstSecondFactorKindObligation --> RE_IDENTIFY: Verfahren anderer Art eingerichtet

  RE_IDENTIFY --> EnrollFirstStart: zugestimmt und identifiziert, oder abgelehnt (SubJourneyFinished/Cancelled)
  RE_IDENTIFY --> [*]: Person gehört bereits zu einem anderen, echten Konto - 409
  EnrollFirstStart --> EnrollFirstConfirmDeviceRebind: fertig, aber dieses Gerät gehört einem ANDEREN Konto
  EnrollFirstStart --> Finished: fertig
  EnrollFirstConfirmDeviceRebind --> Finished: zugestimmt - Gerät neu verknüpft, gerätegebundene Credentials des alten Kontos widerrufen
  EnrollFirstConfirmDeviceRebind --> Finished: abgelehnt - angemeldet, aber ohne Geräteverknüpfung
  Finished --> [*]

  note right of EnrollFirstConfirmDeviceRebind
    Bewusst hier am ENDE und nicht
    dort, wo die Verknüpfung sonst
    entsteht: Beim ersten
    eingerichteten Verfahren ist das
    Konto gerade erst angelegt und
    hat noch keine Identität, nach
    der man fragen könnte. Ohne
    Zustimmung wird nie neu verknüpft.
  end note
  note right of RE_IDENTIFY
    Sub-Journey RE_IDENTIFY,
    freiwillig; gibt es nichts
    anzubieten, geht es direkt
    zum Ende. Lehnt der Nutzer
    ab, bleibt das Konto dauerhaft
    ohne Identifizierung. Der Text
    kommt aus ReIdentifyState
    (Abschnitt "RE_IDENTIFY") und
    sagt nie "erneut" oder "nicht
    erreichbar", denn das Konto
    wurde nie zuvor identifiziert.
  end note
```
