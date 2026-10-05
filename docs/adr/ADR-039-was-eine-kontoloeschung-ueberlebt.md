# ADR-39: Was eine Kontolöschung überlebt – das Änderungsprotokoll ohne Werte

**Status:** entschieden und umgesetzt (2026-09-26).

**Entscheidung**: Wird ein Konto gelöscht, verschwinden seine Daten. Später kann es aber Streit
geben, etwa wenn jemand behauptet, sein Konto sei übernommen und dann gelöscht worden. Dann braucht
es einen Nachweis, was mit dem Konto geschah. Diese ADR legt fest, was davon die Löschung überlebt.

Jedes Konto hat ein [Änderungsprotokoll](../glossar/glossar.md) (`account.change_log`). Es ist
append-only: Einträge kommen nur hinzu und werden nie geändert. Das Protokoll hält fest, *dass*
etwas geschah und *wie*, aber nie *was*, also keine Werte.

- **Feste Spalten** enthalten, was jedes Ereignis hat: Konto-Id, Ereignis, Verfahren bzw.
  Attributtyp, Niveau und Zeitpunkt.
- **Was nur einzelne Ereignisse haben**, steht als JSON in `details`. Jedes `details` nennt seinen
  eigenen `type` und seine `version`. So erklärt sich eine Zeile auch Jahre später selbst, ohne den
  Code, der sie geschrieben hat.
- **Welche Schlüssel ein Ereignis hat**, legt allein `ChangeLog` fest, mit einer Funktion je
  Ereignis. Sobald sich die Schlüssel eines Ereignisses ändern, gibt es eine neue Version
  (`ChangeType.detailsVersion`).
- **Keine Attributwerte, keine Stammdaten.**

Das Protokoll überlebt die Löschung des Kontos, weil es keinen Fremdschlüssel auf das Konto hat.
Es wird **10 Jahre nach der Löschung** abgeräumt (`account.change-log.retention-years`,
`ChangeLogRetention`).

Die Ereignisse sind `IDENTIFIED`, `ATTRIBUTE_RETRACTED`, `METHOD_ADDED`, `METHOD_DEACTIVATED`,
`ACCOUNT_DELETED` und `ACCOUNT_ABSORBED`. Jedes wird in derselben Transaktion geschrieben wie die
Änderung selbst (`ChangeLog`, `Propagation.MANDATORY`). Ein Ereignis gibt es also genau dann, wenn
es die Änderung gibt.

**Warum nicht einfach die Kaskaden entfernen** (so der Vorschlag im Review): Bisher löschte die
Datenbank beim Löschen eines Kontos alle zugehörigen Zeilen mit (Kaskade). Diese Tabellen enthielten
damals Werte:

- `claim` die Attributwerte,
- das inzwischen entfallene `identification.details` etwa Dokumentnummer und Ort,
- `auth_method.details` die Thumbprints von Schlüsseln.

Sie über die Löschung hinaus zu behalten, hieße genau das aufzubewahren, was die Datenminimierung
(Art. 5 DSGVO) verbietet. Sie verschwinden deshalb weiterhin mit dem Konto. Nur was als Nachweis
nötig ist, bleibt erhalten.

**Datenschutz**: Grundsätzlich werden bei einer Kontolöschung die Daten gelöscht (Art. 17 DSGVO).
Das Protokoll stützt sich auf zwei Ausnahmen davon:

- Art. 17 Abs. 3 lit. e: die Verteidigung von Rechtsansprüchen, etwa bei einer strittigen
  Kontoübernahme,
- lit. b, wo eine gesetzliche Aufbewahrungspflicht besteht.

Die 10 Jahre orientieren sich an der längsten Verjährung (§ 199 BGB). Ohne eine benannte
gesetzliche Pflicht wären 3 Jahre (§ 195 BGB) der vorsichtigere Wert. **Die Frist ist von der
Datenschutzbeauftragten zu bestätigen** und deshalb einstellbar.

**Zugleich entfallen und festgelegt**:

- **`orchestrator.session_event` entfällt.** Die Tabelle wurde nur geschrieben, nie gelesen. Was
  darin als Nachweis diente, steht jetzt im Änderungsprotokoll. Der Rest steht ausführlicher im
  Journey-Trace, dem Protokoll der einzelnen Schritte einer Journey zur Fehlersuche.
- **Der Journey-Trace bleibt Fehlersuche**, kein Nachweis. Er wird 14 statt 30 Tage aufbewahrt.
- **`account.identification` wird ins Protokoll übernommen**: Auch diese Tabelle wurde nur
  geschrieben, nie gelesen. Ihr Inhalt steht jetzt im Ereignis `IDENTIFIED`. Es enthält in `details`:
  - die Rolle (Identifizierung oder Zuordnung, ADR-18),
  - die Referenz beim Anbieter (`provider`, `providerTxId`, `procedure`, `methodVersion`),
  - einen Hash dessen, was das Verfahren gesehen hat (`evidenceHash`).

  Damit lassen sich nachträglich alle Konten ermitteln, die ein bestimmtes Verfahren in einer
  bestimmten Version identifiziert hat. Ein einzelner Fall lässt sich beim Anbieter nachprüfen.
  Übernimmt ein Konto ein [verwerfbares](../glossar/glossar.md) Konto (ADR-20), werden dessen
  Identifizierungen mit übernommen, mit einem Vermerk, woher sie stammen (`carriedFromAccountId`).
- **Eine Person ist über Name, Vorname und Geburtsdatum wiederzufinden**, auch nach der Löschung.
  Wessen Konto übernommen und gelöscht wurde, der kann oft nicht mehr angeben als das. Dafür enthält
  jedes `IDENTIFIED` einen Suchschlüssel (`lookup_key`):
  - Er ist ein HMAC über die drei **geprüften** Werte des Kontos, in der Schreibweise des Abgleichs
    (`passportForm`). Ein HMAC ist ein Hash, in den zusätzlich ein Geheimnis eingeht.
  - Selbst angegebene Werte zählen nicht. Sonst ließen sich Treffer unter fremdem Namen anlegen.
  - Dazu kommt die `person_id` des Personenverzeichnisses, wo es eine gibt.

  Beides sind indizierte Spalten, keine Schlüssel in `details`: Wonach unter Millionen Zeilen
  gesucht wird, gehört in eine Spalte. Ein einfacher Hash taugt nicht, weil sich Name und
  Geburtsdatum mit Namenslisten durchprobieren ließen. Das Geheimnis
  (`account.change-log.lookup-secret`) muss deshalb so lange bestehen wie das Protokoll. Jedes
  Identifizierungsverfahren muss alle drei Werte liefern. Das wird beim Start geprüft
  (`ToolHandlerRegistry`). `ident-fsc` meldet dafür jetzt auch das Geburtsdatum. Der Suchschlüssel
  ist ein pseudonymes Personendatum und stützt sich auf dieselbe Begründung wie das Protokoll.
- **Keine Dokument- oder Ausweisnummer**, weder im Protokoll noch beim Anbieter angefordert. Sie darf
  nicht zum Verknüpfen verwendet werden (§ 20 PAuswG), und ein echter eID-Dienst gibt sie gar nicht
  heraus. Übernommen werden nur die oben genannten Referenzfelder, ausgewählt nach ihrem Namen.
  `ChangeLog.identified` filtert dazu den Bericht des Tools selbst, gleich was das Tool liefert.
- **`auth_method.details` enthält nur noch, was das eigene Modul wieder liest**: die
  Schlüssel-Referenz des Geräts und die KOBIL-Gerätekennung. Wie ein Verfahren hinzukam, also die
  Nachweise der Sitzung und der Kanal, steht im Ereignis `METHOD_ADDED` (`amr`, `channel`).
  Folgendes entfällt, weil es nie gelesen wurde: der Thumbprint, die KOBIL-Nutzerkennung (sie liegt
  ohnehin im KOBIL-Modul) und die Angaben des SMS-Anbieters.
- **Protokolle sind am Namen zu unterscheiden**: Der Name sagt den Zweck. Die Endung sagt, ob es ein
  Nachweis ist: Nachweise enden auf `_log`, die Aufzeichnung zur Fehlersuche auf `_trace`.
  - `account.change_log`: Kontoänderungen, 10 Jahre nach der Löschung,
  - `account.sign_in_log`: Anmeldungen, 6 Monate, wird mit dem Konto gelöscht,
  - `orchestrator.journey_trace`: Fehlersuche, 14 Tage.
- **Anmeldungen gehören nicht hierher**, sie haben ein eigenes Protokoll (siehe Nachtrag unten).
  Anmeldungen sind um Größenordnungen häufiger. Eine Anmeldehistorie über Jahre nach der Löschung
  ließe sich mit dem Grundsatz der Speicherbegrenzung nicht begründen.

**Erwogene Alternativen**:

- **Kaskaden entfernen**: verworfen, siehe oben.
- **Eine Spalte je Feld** bzw. zusammengesetzte Texte (`ANKER:grund`, `provider=…;tx=…`):
  verworfen. Die Spalten wären je Ereignis anders zu lesen, und die Texte müsste jeder Leser selbst
  zerlegen. Die Suche „alle Konten eines Verfahrens in Version X“ ist eine seltene Abfrage des
  Betreibers. Auf PostgreSQL lässt sie sich bei Bedarf mit einem Ausdrucksindex auf `details`
  beschleunigen.
- **Nichts überlebt**: verworfen. Bei einer strittigen Übernahme gäbe es dann keinen Nachweis, wer
  wann womit identifiziert war.
- **Den Journey-Trace als Nachweis länger aufbewahren**: verworfen. Er ist für Jahre zu
  detailreich. Außerdem läuft nicht jede Kontoänderung über eine Journey, etwa Änderungen aus dem
  Personenverzeichnis, ein Passwortwechsel über Keycloak oder das Löschen durch den Betreiber.

**Nachtrag 2026-09-26: das Anmeldeprotokoll** (`account.sign_in_log`, früher hier als offen geführt).
Wer sich wann womit und auf welchem Niveau angemeldet hat, stand bisher nur 14 Tage im
Journey-Trace. Jetzt gibt es dafür ein eigenes Protokoll. Es ist genauso aufgebaut wie das
Änderungsprotokoll: feste Spalten, `details` mit `type` und `version` und eine Schreibfunktion je
Ereignis in `SignInLog`. Die Ereignisse und Regeln:

- **`SIGNED_IN`**: Eine Einstiegs-Journey (`AuthIntent.isEntryIntent`) hat den Kanal angemeldet.
  Festgehalten werden das Niveau, die Nachweise (`amr`), die beteiligten Tools in ihrer Fassung
  (`tools`, ADR-51) und der Intent.
  **`STEPPED_UP`**: ein Step-up, also ein nachträglich erhöhtes Niveau, auch als Unter-Journey.
- **`SIGN_IN_FAILED`**: ein falscher Nachweis für ein bekanntes Konto, mit Verfahren und Tool in
  seiner Fassung (`tool`; fehlt, wenn Keycloak selbst geprüft hat). Das gilt für die App wie für
  Keycloaks Passwortformular. **`LOCKED_OUT`**: der Fehlversuch, der die Sperre ausgelöst hat.
  Beides schreibt `AccountLockoutService`, durch den jeder solche Fehlversuch läuft.
- **`SIGNED_OUT`**: ein gewollter Logout. In der App läuft er über den Orchestrator. Im Web-Kanal
  meldet ihn Keycloak (Event-Listener `orchestrator-sign-in-log`,
  `POST …/kc/accounts/{accountId}/sign-outs`), und zwar nach dem Commit und ohne den Logout
  aufzuhalten. Läuft eine Sitzung einfach ab, ist das kein Ereignis.
- **Kein Nachweis über die Löschung hinaus**: Das Protokoll gehört zum Konto (Fremdschlüssel,
  `ON DELETE CASCADE`) und wird 6 Monate aufbewahrt (`account.sign-in-log.retention-months`,
  `SignInLogRetention`). Eine Anmeldehistorie sind Verhaltensdaten, kein Beleg.
- **Anders als beim Änderungsprotokoll ist die Klasse, die schreibt, öffentlich**, also auch für
  andere Module nutzbar: Anmeldungen geschehen im Orchestrator, also schreibt der Orchestrator
  hier. Das Änderungsprotokoll schreibt nur das Konto-Modul selbst.
- **Grenze**: Erledigt Keycloak eine Anmeldung allein über seine SSO-Sitzung, ohne den Orchestrator
  zu fragen, erscheint sie nicht als `SIGNED_IN`. Nur ihr Logout erscheint.
