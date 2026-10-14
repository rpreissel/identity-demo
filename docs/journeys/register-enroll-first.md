> Eine Journey aus dem Katalog. Wie die Diagramme zu lesen sind, erklärt
> [../04-orchestrierung.md](../04-orchestrierung.md), Abschnitt 3.

# `REGISTER`: Experiment „Erst Anmeldeverfahren einrichten“

Die normale Registrierung beschreibt [`REGISTER`](register.md).

Dies ist eine zweite, eigenständige Variante der Registrierung (`REGISTER`). Sie dreht die
Reihenfolge um: Der Nutzer richtet zuerst Anmeldeverfahren ein, und die Identifizierung wird erst
am Ende angeboten.

**Eigene Zustände, eigene Strategie.** Die Variante hat eigene Zustände (`RegisterEnrollFirstState`)
und eine eigene Strategie. Keiner dieser Zustände wird mit `RegisterState`, `AuthChoice` oder
`Enrolling` geteilt.

**Wie die Variante gewählt wird.** Unter `AuthIntent.REGISTER` ist nur ein einziges Spring-Bean
registriert, `RegisterDispatchStrategy`. Es wählt für jede **neue** Journey einmal zwischen beiden
Varianten. Welche Variante eine laufende Journey nutzt, ergibt sich danach allein aus ihrem
Zustandstyp (`is RegisterEnrollFirstState` oder `is RegisterState`). Der Schalter wird dafür nie
erneut gelesen.

**Der Schalter.** Eingeschaltet wird die Variante über einen Schalter, den der Betreiber zur
Laufzeit umstellen kann (`JourneyFeatureFlag.REGISTER_ENROLL_FIRST`, `"register-enroll-first"`).
Den Wert liefert `FeatureFlagService` (`@Service`, implementiert `FeatureFlagProvider`) aus der
Tabelle `orchestrator.feature_flag`. Dort steht eine Zeile je Schalter. Fehlt die Zeile, ist der
Schalter aus. Lesen und setzen lässt er sich über
`GET/PUT /orchestrator/admin/registration-order` (`RegistrationOrderController`).

**Grundidee:** Zum Start ist kein Konto nötig. Das Konto entsteht erst, wenn das erste Verfahren
fertig eingerichtet ist, und nicht schon bei der Identifizierung. Das erledigt die allgemeine Aktion
`Action.AdoptCredential` im `JourneyService`. Bis dahin fragt die Strategie die
Einrichtungs-Kandidaten ohne Konto ab (`CandidateTools.forEnrollment(account = null, …)`): Dann ist
noch kein Verfahren aktiv.

**Zuerst die E-Mail-Adresse.** Der erste Schritt ist fest: Der Nutzer bestätigt seine
E-Mail-Adresse (`EnrollFirstAttestingEmail`). Das ist ein `ATTESTATION`-Schritt, der eine Angabe
bestätigt, und kein Einrichten eines Verfahrens. Er lässt sich nicht überspringen; wer ablehnt
(`Abandoned`), bekommt ihn erneut angeboten. Hat der Betreiber das Tool gesperrt, entfällt der
Schritt, und die Journey bleibt nicht stehen.

**Danach freie Wahl.** Ein bestimmtes Verfahren schreibt die Variante nicht vor. Der Nutzer wählt
aus allen Einrichtungs-Tools (`EnrollFirstEnrolling`), wie in der normalen Variante. Welche
Verfahren das Konto am Ende hat, regeln die üblichen Pflichten (Orchestrierung, Abschnitt 5), in
dieser Reihenfolge:

- ein weiteres Verfahren, falls das Niveau nicht reicht,
- dann die E-Mail-Bestätigung,
- dann die Pflicht zu einem Verfahren einer zweiten Faktorart.

**Die Identifizierung am Ende.** Erst wenn alle Pflichten erfüllt sind, wird die Identifizierung
**einmal angeboten, aber nie erzwungen**. Das geschieht über die Sub-Journey `RE_IDENTIFY`
(`Transition.RequireSubJourney`), also über den gemeinsam genutzten Ablauf zur Identifizierung.

Lehnt der Nutzer ab oder gibt es nichts anzubieten, endet die Journey trotzdem erfolgreich
(`Transition.Authenticated`). Das Konto ist dann angemeldet, aber nicht identifiziert.

Gehört die identifizierte Person bereits zu einem anderen, echten Konto, lehnt der Executor die
Identifizierung mit `409` ab. Zwei echte Konten werden nämlich nie zusammengeführt. Gibt es für
diese Person dagegen nur ein verwerfbares Konto (etwa aus einem früher abgebrochenen eID-Lauf),
wird es übernommen (ADR-20). Verwerfbar ist ein Konto, das keiner Person zugeordnet ist und in dem
nie ein Anmeldeverfahren eingerichtet wurde.

**Rückfrage zum Gerät am Ende.** Das Smartphone kann schon mit einem anderen Konto verknüpft sein.
Dann fragt diese Variante erst am **Ende** der Journey, nach der freiwilligen Identifizierung, ob
es neu verknüpft werden soll (`EnrollFirstConfirmDeviceRebind`).

Der Grund für diesen späten Zeitpunkt: Die normale Variante identifiziert zuerst und kann direkt
danach fragen. Hier verknüpft die Journey dagegen beim ersten eingerichteten Verfahren. Zu diesem
Zeitpunkt ist das Konto gerade erst entstanden und hat noch keine Identität.

Lehnt der Nutzer ab, endet die Registrierung ohne Geräteverknüpfung. Das Konto bleibt über die
Anmeldung per E-Mail-Adresse voll nutzbar ([09-dpop.md](../09-dpop.md) Abschnitt 3).

```mermaid
stateDiagram-v2
  [*] --> EnrollFirstStart
  EnrollFirstStart --> EnrollFirstAttestingEmail: Start - die Adresse kommt zuerst
  EnrollFirstStart --> EnrollFirstEnrolling: Start, kein Bestätigungs-Tool verfügbar
  EnrollFirstAttestingEmail --> EnrollFirstAttestingEmail: abgelehnt - derselbe Schritt wird erneut angeboten
  EnrollFirstAttestingEmail --> EnrollFirstEnrolling: E-Mail bestätigt
  EnrollFirstEnrolling --> EnrollFirstEnrolling: abgelehnt - derselbe Schritt wird erneut angeboten, oder Verfahren eingerichtet, Niveau reicht noch nicht
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
