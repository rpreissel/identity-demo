# ADR-46: Ein Konto ist im Aufbau, bis es ein Anmeldeverfahren hat

**Status:** umgesetzt 2026-09-27.

**Entscheidung.** Ein Konto ist **eingerichtet**, sobald es mindestens ein Anmeldeverfahren hat;
vorher ist es **im Aufbau** (`AccountProfile.isSetUp`). Das ist abgeleitet, nicht gespeichert: Ein
Konto ist etwas, in das man sich anmelden kann. Identität, bestätigte Adresse, zweiter Faktor und
Niveau sind Pflichten der Journey, keine Bedingung dafür, dass das Konto existiert. Deaktivierte
Verfahren zählen mit, damit ein gesperrtes Gerät ein Konto nicht zurück in den Aufbau schiebt.
Eingerichtet heißt nicht fertig: Identität, zweiter Faktor und Niveau können offen sein, das Konto
ist trotzdem anmeldefähig. Zurück in den Aufbau führt kein Weg (I-28).

Für ein Konto im Aufbau gilt:

- **Anmeldung und Keycloak-Suche finden es nicht.** `AccountService.resolveByAnchor`, über die die
  Lookup-Tools und die Keycloak-Suche nach E-Mail oder Nutzername laufen, liefert nur Konten mit
  Verfahren. Ohne Verfahren gäbe es ohnehin nichts zum Anmelden. Keycloaks Zugriff per Konto-ID
  bleibt offen: Den nutzt nur der Orchestrator selbst, der während einer Registrierung über die
  Anmeldeseite das Konto nennt. `AccountService.anchorHolder` findet es für Buchhaltung, etwa das
  Nachziehen des Personenverzeichnisses.
- **Ein Abbruch verwirft es ganz**, mit Identität, Adresse und Gerätelink, über
  `AccountDeletionService`. Was ohne Abbruch liegen bleibt, räumt der `RetentionJob` ab, sobald kein
  offener Kanal mehr damit arbeitet.
- **Der Kanal zeigt es an:** `ChannelState.REGISTERING` ⇔ nicht angemeldet und das Konto ist im
  Aufbau. Abgeleitet beim Antworten (`ChannelState.shownWith`), nie gespeichert. Die App fragt daran
  vor dem Verwerfen nach, auch ohne Demomodus.

**Warum.** Anlass war die Rückfrage vor dem Verwerfen einer Registrierung: Die App las aus dem
Demo-Block, ob gerade registriert wird, und `REGISTERING` wurde beim Einstieg gesetzt, sobald kein
Konto bekannt war, auch für eine Anmeldung per E-Mail-Adresse. Dahinter lag, dass eine Registrierung
beim ersten Nachweis ein Konto anlegt und als Ablage nutzt, ohne dass das Modell diese Ablage kennt.
Ein Abbruch löschte nur den leeren Platzhalter (`isDisposable`), ein identifiziertes Konto ohne
Verfahren blieb liegen.

**Erwogene Alternativen.**

- *Ein gespeicherter Abschluss* (`registered_at`), gesetzt, wenn die Journey ihre Pflichten erfüllt
  sieht. Kurz umgesetzt und wieder zurückgenommen: Die Pflichten hängen von Variante, Kanal, Niveau
  und verfügbaren Tools ab. „Eingerichtet“ hieß damit „die Journey ist am Ende“, nicht
  „das Konto ist benutzbar“. Das brauchte eine eigene Aktion, ein Sicherheitsnetz beim Anmelden und
  eine aufgeschobene Geräteverknüpfung, und bei „Erst Anmeldeverfahren einrichten“ hing ein Konto mit
  erfüllten Pflichten an der freiwilligen Identifizierungsfrage.
- *Ein eigenes Modell „Registrierung“*, das erst beim Abschluss zum Konto wird. Fachlich am
  saubersten, aber Claims und Verfahren hängen heute an Konto-IDs. Nicht umgesetzt.
- *`REGISTERING` an `isDisposable` hängen.* Fällt nach der Identifizierung auf `ANONYMOUS` zurück,
  gerade dort, wo ein Abbruch etwas verwirft.

**Kosten.** Ein Abbruch nach dem ersten Verfahren lässt ein anmeldefähiges Konto zurück, auch wenn
die Registrierung ihre übrigen Pflichten (zweiter Faktor, Niveau) nicht erfüllt hat. Die
Anmelde-Journeys gehen damit um: `FAST_ACCESS` bietet fehlende Verfahren nach dem Nachweis an, ein
Step-up verlangt sie, sobald ein Niveau sie braucht. Ein zweiter Registrierungsversuch mit derselben
Adresse trifft auf das Konto und bekommt den `409` aus ADR-20, dessen Text auf die Anmeldung
verweist. Ein Gerät kann ein Konto im Aufbau nur über die ausdrückliche Umbinde-Frage nach der
Identifizierung wiedererkennen; `FAST_ACCESS` gibt ein Konto ohne Verfahren dann an `REGISTER` ab.

**Invarianten** (in [invarianten.md](../invarianten.md)): I-26 Ein angemeldeter Kanal arbeitet nie
mit einem Konto im Aufbau; `REGISTERING` wird nie gespeichert. I-27 Anmeldung und Keycloak-Suche
finden kein Konto im Aufbau. I-28 Ein eingerichtetes Konto fällt nie in den Aufbau zurück.
