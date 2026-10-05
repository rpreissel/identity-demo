# ADR-34: Personenverzeichnis – Partnernummer, drei Rollen, Änderungen per Event ans Konto

**Status:** umgesetzt.

> **Nachtrag 2026-09-26:** Seit [ADR-38](ADR-038-keycloak-liest-konten.md) endet der Weg „per Event
> bis Keycloak“ am Konto. Keycloak hält keine Kopie der Konten mehr. Es liest die Werte bei Bedarf
> selbst beim Orchestrator nach. Eine Änderung im Verzeichnis ist dort deshalb spätestens nach einer
> Minute sichtbar. Punkt 6 (Abgleich nur, wenn kopierte Werte betroffen sind) ist damit entfallen.
> Titel und Text unten sind daran angepasst.

> **Nachtrag 2026-09-26:** Das Verzeichnis speichert je Person auch E-Mail-Adresse und Mobilnummer,
> als Kontaktdaten des Fremdsystems. Das Konto hat davon getrennt eigene Werte: Die Claims (also die
> im Konto gespeicherten Angaben) EMAIL und PHONE_NUMBER entstehen erst, wenn der Nutzer die Adresse
> per Code oder die Nummer per TAN bestätigt. Beide Angaben stammen aus verschiedenen Quellen und
> können voneinander abweichen. Eine Änderung im Verzeichnis wird deshalb nicht gemeldet. In der Demo
> füllen die Werte aus dem Verzeichnis nur die Formulare vor
> ([02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 6).

Die Versicherung führt ihre Stammdaten in einem eigenen System, unabhängig von den Konten dieser
Anwendung. Dort steht, welche Personen sie kennt, wie sie heißen und ob sie versichert sind. Dieses
System simuliert das Projekt als **Personenverzeichnis**. Ein Konto wird einer Person im Verzeichnis
zugeordnet, wenn der Nutzer sich identifiziert. Danach muss das Konto erfahren, wenn sich bei
dieser Person etwas ändert. Diese ADR legt fest, über welche Nummern die Zuordnung läuft, welche
Rollen ein Konto daraus bekommt und wie Änderungen beim Konto ankommen.

**Entscheidung**: Das simulierte Fremdsystem heißt **Personenverzeichnis** (Modul
`personenverzeichnis`). Es kennt drei Kennungen je Person und meldet Änderungen per Event an das
Konto. Die Kennungen und die drei Rollen im Einzelnen beschreibt
[02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 6. Hier ist Folgendes entschieden:

1. **Die Partnernummer ist der Schlüssel jeder Person.** Sie besteht aus `P` und neun Ziffern und
   wird vom Verzeichnis zufällig vergeben. Im Konto ist sie der Anker `PERSON_ID`. Ein
   [Anker](../glossar/glossar.md) ist eine Angabe, über die sich ein Konto eindeutig wiederfinden
   lässt. Die Partnernummer ist bewusst keine fortlaufende Zahl: Sie verrät weder Reihenfolge noch
   Anzahl der Personen. Außerdem liest ein Mensch sie auf einem Brief und tippt sie ein.
2. **Eine KVNR gibt es nur zusammen mit einer Mitgliedsnummer.** Die KVNR ist die
   Krankenversichertennummer auf der Gesundheitskarte. Die Mitgliedsnummer ist die Nummer, unter
   der jemand bei der Versicherung versichert ist. Die Regel steht im Datenbankschema
   (`ck_person_kvnr_nur_versichert`) und wird auch im Verzeichnis selbst geprüft. Umgekehrt darf die
   KVNR zeitweise fehlen, weil sie sich ab und zu ändert. Die Mitgliedsnummer ist im Konto ein
   ersetzbarer Anker (`MEMBER_NUMBER`), die KVNR ein Claim.
3. **Die Rolle wird abgeleitet, nicht gespeichert.** Es gibt drei Rollen:
   - **Versicherter:** Das Konto hat eine Mitgliedsnummer.
   - **Partner:** Das Konto hat nur eine Partnernummer.
   - **Interessent:** Das Konto ist keiner Person zugeordnet.

   Die ID-Claims enthalten dafür `personId` und `versnr`. Beide werden bei jeder Abfrage aus dem
   Verzeichnis gelesen, und die App leitet die Rolle daraus ab. Ein eigenes Rollenfeld gibt es im
   Backend nicht.
4. **Zuordnung: erst KVNR, sonst Partnernummer.** Zwei Tools ordnen ein Konto einer Person zu:
   `ident-kvnr` (nach einer Identifizierung mit eID oder Nect) und `ident-fsc` (Brief mit
   Freischaltcode). Beide nehmen jede der zwei Kennungen an. Es bleibt also ein Verfahren mit zwei
   möglichen Kennungen, ohne zweites Tool.

   Der Client fragt zuerst nach der KVNR. Erst wenn der Nutzer „Ich habe keine
   Versichertennummer“ wählt, fragt er nach der Partnernummer. Kommen beide an, entscheidet die
   KVNR. `ident-fsc` speichert immer nur eine der beiden.

   Die Prüfung dahinter ist in beiden Fällen dieselbe: Zuerst werden die Personalien mit dem
   Verzeichnis abgeglichen, dann wird der Anker geschrieben. Ein Konto, das über die Partnernummer
   zugeordnet wurde, bekommt keinen KVNR-Claim. Gibt es eine KVNR, kommt der Claim mit der nächsten
   Änderung aus dem Verzeichnis.
5. **Änderungen gehen per Event an das Konto.** Ändert das Verzeichnis eine Person, veröffentlicht
   es `tool_api.PersonChanged(personId, changed, kvnr, versnr)`. Das Modul `account` übernimmt die
   Änderung (`PersonChangeListener`). Das frühere Folgeereignis `AccountChanged` ist seit 2026-09-26
   entfallen, weil es niemand mehr empfangen hat (Punkt 6). Alles läuft über die Event Publication
   Registry ([ADR-29](ADR-029-event-publication-registry-statt-eigener-outbox.md)): Die Ereignisse
   werden gespeichert und bei einem Fehler erneut zugestellt.
6. **Keycloak liest selbst** (seit [ADR-38](ADR-038-keycloak-liest-konten.md)). Bis dahin übertrug
   ein Abgleich die Änderung nach Keycloak, aber nur, wenn sie Werte betraf, von denen Keycloak eine
   Kopie hatte. Heute liest Keycloak das Konto bei Bedarf nach. Für ein Konto, das einer Person
   zugeordnet ist, zeigt es nur die Werte des Verzeichnisses. Ein Feld, das dort geleert wurde,
   bleibt so leer und erscheint nicht aus alten Claims wieder
   ([05-api.md](../05-api.md) Abschnitt 3b).

Name, Vorname, Geburtsdatum und Adresse werden weiterhin **bei jeder Abfrage** aus dem Verzeichnis
gelesen (`PersonDirectory`). Das Konto speichert davon keinen aktuellen Wert. Ändert sich einer
dieser Werte, ist im Konto also kein Claim nötig.

**Warum das Event in `tool_api` liegt**: Absender (`personenverzeichnis`) und Empfänger (`account`)
kennen einander nicht. Beide sind aber schon von `tool_api` abhängig (`PersonDirectory`). Das Event
enthält, wie eine echte Änderungsmitteilung, nur dreierlei: die Person, die Arten der geänderten
Attribute und die neuen Kennungen (KVNR, Mitgliedsnummer). Genau diese Kennungen speichert das Konto
selbst. Andere Stammdaten enthält das Event nie.

**Ausdrücklich benannte Ausnahme: Anker ohne Sitzung.** Normalerweise darf ein Anker nur geschrieben
werden, wenn die Anmeldung ein Mindestniveau erreicht hat (`AnchorRule.acrFloor`). Das Niveau sagt,
wie sehr einer Anmeldung vertraut wird (siehe [Glossar](../glossar/glossar.md)). Eine Änderung im
Verzeichnis kommt aber ohne Sitzung an, also ohne angemeldeten Nutzer. Für seine eigenen Kennungen
ist das Verzeichnis selbst maßgeblich.

`AccountService.applyDirectoryChange` schreibt deshalb den KVNR-Claim und den `MEMBER_NUMBER`-Anker
mit dem Niveau des Verzeichnisses (loa2). Diese Ausnahme ist eng begrenzt:

- nur auf diesem einen Weg,
- nur für diese zwei Arten von Angaben,
- nur für das Konto, das über `PERSON_ID` mit genau dieser Person verbunden ist.

Wie der alte Wert dabei zurückgenommen wird, steht in der Liste der Auslöser in
[ADR-12](ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md). Eine entfernte Kennung wird nur
zurückgenommen: Eine fehlende KVNR ist ein Zwischenzustand, und ohne Mitgliedsnummer
ist die Person Partner. Beim Zuordnen entsteht der `MEMBER_NUMBER`-Anker dagegen auf dem normalen
Weg mit der Sitzung. Die Kennung liefert dann `PersonDirectory.memberNumberOf`.

**Folgen**:

- Ein Token, das vor der Änderung ausgestellt wurde, enthält die alten Werte, bis es erneuert wird.
- Wer die Mitgliedsnummer verliert, verliert den Anker, nicht das Konto (`PERSON_ID` bleibt). Er
  wird vom Versicherten zum Partner.
- `personId` ist überall ein String (`P…`), auch im Demo-Feld `demo.personId` des gemeinsamen
  Antwortformats. Das ist ein bewusster Bruch der Version v1. `api/published/v1.yaml` wurde dafür
  auf einen neuen Stand gebracht, wie schon beim Journey-Trace ([API](../05-api.md)).
- In unserem Code heißt das Verzeichnis englisch, so wie der Port: `ClaimSource.PERSON_DIRECTORY`
  (gespeichert als `person_directory`), `AttributeAuthority.PersonDirectory`,
  `RetractionSource.PERSON_DIRECTORY`. Deutsch heißt nur das Fremdsystem selbst
  (`personenverzeichnis`, `Personenverzeichnis`).

**Geschichte**: Das Fremdsystem hieß vorher „Personenregister“ (Modul `ext_stammdaten`). Dort war die
KVNR der unveränderliche Schlüssel und die Personen-ID eine fortlaufende Zahl. Die Mitgliedsnummer,
die Rolle Partner und die Zuordnung über die Partnernummer kamen am 2026-09-24 hinzu.
