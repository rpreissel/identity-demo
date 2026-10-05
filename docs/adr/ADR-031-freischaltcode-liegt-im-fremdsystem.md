# ADR-31: Der Freischaltcode liegt im Personenverzeichnis, `ident_fsc` fragt es über einen Port

**Status:** umgesetzt; der direkte Aufruf ist durch den Nachtrag 2026-09-26 abgelöst (Port
`ActivationCodes`).

Worum es geht: Ein **Freischaltcode** ist ein Code, den eine Person per Brief bekommt. Mit diesem
Code kann sie sich identifizieren, also nachweisen, wer sie ist. Das Tool `ident_fsc` prüft den Code.
Das **Personenverzeichnis** ist ein simuliertes Fremdsystem, in dem die Personen geführt werden. Diese
ADR klärt, wo die Codes gespeichert sind und wie das Tool sie prüft.

> **Nachtrag 2026-09-26: `ident_fsc` fragt das Register jetzt über einen Port**
> (`tool_api.ActivationCodes` mit `digest` und `isValid`, implementiert von `Freischaltcodes`). Ein
> Port ist eine Schnittstelle, über die ein Modul ein Fremdsystem anspricht, ohne dessen Klassen
> direkt zu kennen.
>
> Der Grund für den direkten Aufruf hielt nicht stand. `ident_fsc` erreichte dasselbe Fremdsystem
> damit auf zwei Wegen: die Person über den Port `PersonDirectory`, den Code über die Klasse
> `Freischaltcodes`. Über die Klasse kamen zudem die Begriffe des Registers (`pruefe`,
> `juengsterGueltigerCode`) in unseren Code. Am Port werden sie übersetzt, wie bei `PersonDirectory`.
>
> Jetzt gilt: Jedes Fremdsystem wird auf genau einem Weg angesprochen. Das Personenverzeichnis nur
> über Ports. Die übrigen Mocks (`kobil`, `nect`, `sms`, `mail`) weiterhin direkt. Die
> Demo-Personen liest der Orchestrator über den eigenen Port `DemoPersonDirectory` statt über
> `Personenverzeichnis` und `Freischaltcodes`. Der Rest dieser ADR beschreibt den Stand vor dem
> Nachtrag.

**Ursprüngliche Entscheidung** (den direkten Aufruf hat der Nachtrag abgelöst): Die Freischaltcodes
liegen in `personenverzeichnis.freischaltcode`, nicht mehr in `ident_fsc.code`. Das Modul
`personenverzeichnis` stellt sie über die öffentliche Klasse `Freischaltcodes` aus, widerruft sie und
prüft sie. `ident_fsc` ruft `Freischaltcodes.pruefe` direkt auf. Dafür deklariert es
`personenverzeichnis` als erlaubte Abhängigkeit.

## Warum

Den Freischaltcode vergibt das Personenverzeichnis und verschickt ihn per Brief. Das Verfahren zur
Identifizierung prüft ihn nur. Solange die Codes im Schema von `ident_fsc` lagen, war das Tool
zugleich Aussteller und Prüfer. In der Demo ließ sich das Ausstellen deshalb nicht als Vorgang im
Fremdsystem zeigen, etwa auf der Oberfläche `/personenverzeichnis/`.

## Warum anfangs direkt statt über einen Port

Das Muster gab es im Projekt schon: `auth_kobil` fragt `kobil.KobilSsms` direkt
(`allowedDependencies` enthält `kobil`). So bildet es auch das echte System ab: Das Verfahren spricht
das Personenverzeichnis direkt an, nicht eine Abstraktionsschicht davor. Ein neuer Port in
`tool_api` wäre ein zweiter Weg für dieselbe Art von Abhängigkeit zu einem Fremdsystem gewesen.

`PersonDirectory` bleibt der Port für drei Aufgaben (ADR-34):

- die Person über KVNR oder Partnernummer finden,
- die Personalien abgleichen,
- die Mitgliedsnummer lesen.

Diese Fragen stellen alle Verfahren zur Identifizierung, nicht nur `ident_fsc`.

## Der Brief

Das Personenverzeichnis speichert den Code nur als SHA-256-Hash. Die einzige Definition dafür ist
`Freischaltcodes.digest`. `demo_seed/V16__testdata.sql` rechnet in SQL dasselbe. Den Klartext enthält
nur der simulierte Brief (`personenverzeichnis.brief`). Die Demo liest den Code aus diesem
Briefkasten, statt eine zweite, fest eingetragene Liste von Codes zu pflegen. Warum der Klartext dort
liegen darf, steht in [ADR-22](ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md).

## Der Code ist bis zum Ablauf wiederverwendbar

Entschieden am 2026-09-25: Eine erfolgreiche Identifizierung **verbraucht** den Freischaltcode
**nicht**. Er gilt, bis er abläuft oder widerrufen wird (`Freischaltcode.isValidAt`). In dieser Zeit
kann man ihn mehrfach verwenden.

Warum: `ident-fsc` ist nicht nur der Weg zur ersten Identifizierung. Es ist auch der Ausweg für den
[Intent](../glossar/glossar.md) MANAGE_METHODS, also das Anliegen, die eigenen Anmeldeverfahren zu
verwalten. Ein Konto mit nur einem Verfahren erreicht das
[Sicherheitsniveau](../glossar/glossar.md) loa2 über eine erneute Identifizierung
(`AuthPolicy.reIdentCandidates`). Wäre der Code nur einmal nutzbar, bräuchte jede erneute
Identifizierung einen neuen Brief. Für den Nutzer hieße das: ein Postweg für jeden Verwaltungsvorgang.

Erwogen und verworfen:

- **Einmalnutzung**: Sie schließt das unten beschriebene Restrisiko. Sie kostet aber einen Brief je
  erneuter Identifizierung.
- **Einmalig für Fremde, wiederverwendbar für dasselbe Konto**: Das ergäbe zwei Bedeutungen
  desselben Codes. Außerdem käme mehr Logik an die Stelle, die über die Übernahme eines Kontos
  entscheidet.

**Restrisiko, bewusst in Kauf genommen**: Wer den Brief nach der rechtmäßigen Nutzung findet (etwa im
Altpapier oder als Mitbewohner), kann sich bis zum Ablauf erneut als diese Person identifizieren.
Damit erreicht er loa2 und so das Konto der Person. Begrenzt wird das nur durch drei Dinge:

- die Gültigkeitsdauer,
- den Widerruf im Personenverzeichnis,
- die Mengenbegrenzung für Identifizierungen (5 Fehlversuche je Person in 15 Minuten). Sie schützt
  gegen Raten, nicht gegen einen bekannten Code.

Wie das Verzeichnis den Code speichert, ist nach
[ADR-35](ADR-035-betriebsanspruch-backend-kern-produktionsreif.md) Sache des Fremdsystems. Heute ist
es SHA-256 ohne Pepper. Das echte System muss den Code so ablegen, dass ein gelesener Hash den Code
nicht verrät.

## Kosten

- Die Migrationen `personenverzeichnis/V1`, `ident_fsc/V4` und `demo_seed/V16` wurden direkt
  geändert, statt neue hinzuzufügen. Nach
  [ADR-16](ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md) wird eine bestehende
  H2-Datei dadurch ungültig. `FlywayResetConfig` baut sie lokal neu auf.
- Tool-Module hängen damit nicht mehr nur von `tool_api` ab. Welche benannten Ausnahmen es für
  simulierte Fremdsysteme gibt, listet der [Projektrahmen](../08-projektrahmen.md) (M-3) an einer
  Stelle auf.
