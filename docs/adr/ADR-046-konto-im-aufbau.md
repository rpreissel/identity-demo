# ADR-46: Ein Konto ist im Aufbau, bis es ein Anmeldeverfahren hat

**Status:** umgesetzt 2026-09-27.

**Entscheidung.** Während einer Registrierung legt der Orchestrator schon beim ersten Nachweis ein
Konto an, etwa nach der Identifizierung. Ein Anmeldeverfahren (zum Beispiel Passwort, SMS oder ein
gebundenes Gerät) kommt oft erst später dazu. Diese ADR legt fest, wann ein solches Konto als fertig
angelegt gilt.

Ein Konto ist **eingerichtet**, sobald es mindestens ein Anmeldeverfahren hat. Vorher ist es **im
Aufbau** (`AccountProfile.isSetUp`). Dieser Zustand wird nicht gespeichert, sondern aus den
vorhandenen Verfahren abgeleitet. Der Gedanke dahinter: Ein Konto ist etwas, in das man sich anmelden
kann. Identität, bestätigte Adresse, zweiter Faktor und Niveau sind dagegen Pflichten der
[Journey](../glossar/glossar.md), also Aufgaben des geführten Ablaufs. Sie sind keine Bedingung
dafür, dass das Konto existiert.

Auch deaktivierte Verfahren zählen mit. Ein gesperrtes Gerät macht ein Konto deshalb nicht wieder zu
einem Konto im Aufbau. Eingerichtet heißt aber nicht fertig: Identität, zweiter Faktor und Niveau
können noch offen sein, und trotzdem kann man sich in das Konto anmelden. Ein eingerichtetes Konto
wird nie wieder zu einem Konto im Aufbau (I-28).

Für ein Konto im Aufbau gilt:

- **Die Anmeldung und die Keycloak-Suche finden es nicht.** Über `AccountService.resolveByAnchor`
  laufen die Lookup-Tools (Tools, die ein Konto über eine Angabe wie die E-Mail-Adresse suchen) und
  die Suche von Keycloak nach E-Mail oder Nutzername. Diese Methode liefert nur Konten mit Verfahren.
  Ohne Verfahren gäbe es ohnehin nichts, womit man sich anmelden könnte.

  Der Zugriff von Keycloak über die Konto-ID bleibt dagegen offen. Ihn nutzt nur der Orchestrator
  selbst: Während einer Registrierung nennt er der Anmeldeseite das Konto. Außerdem findet
  `AccountService.anchorHolder` ein Konto im Aufbau für interne Verwaltungsaufgaben, etwa wenn
  Änderungen aus dem Personenverzeichnis nachgezogen werden.
- **Ein Abbruch verwirft es ganz.** `AccountDeletionService` löscht das Konto samt Identität,
  Adresse und Gerätelink. Was ohne Abbruch liegen bleibt, räumt der `RetentionJob` ab, sobald kein
  offener Kanal mehr damit arbeitet.
- **Der Kanal zeigt es an.** Ein [Kanal](../glossar/glossar.md) ist die Verbindung des Nutzers zum
  Orchestrator, über die App oder die Website. Sein Zustand ist `ChannelState.REGISTERING` genau
  dann, wenn der Nutzer nicht angemeldet ist und das Konto im Aufbau ist. Der Orchestrator leitet
  diesen Zustand beim Antworten ab (`ChannelState.shownWith`) und speichert ihn nie. Die App fragt
  daran vor dem Verwerfen einer Registrierung nach, auch ohne Demomodus.

**Warum.** Anlass war die Rückfrage, die die App stellt, bevor sie eine Registrierung verwirft. Dafür
gab es zwei Probleme:

- Die App las aus dem Demo-Block der Antwort, ob gerade registriert wird. Der Demo-Block ist aber nur
  zum Vorführen gedacht.
- Der Orchestrator setzte `REGISTERING` schon beim Einstieg, sobald kein Konto bekannt war. Das traf
  auch auf eine Anmeldung per E-Mail-Adresse zu.

Dahinter lag ein tieferes Problem: Eine Registrierung legt beim ersten Nachweis ein Konto an und
speichert darin ihre Zwischenergebnisse. Das Datenmodell sah diese Verwendung des Kontos aber nicht vor. Ein
Abbruch löschte deshalb nur den leeren Platzhalter (`isDisposable`). Ein Konto, das schon
identifiziert war, aber noch kein Verfahren hatte, blieb liegen.

**Erwogene Alternativen.**

- *Ein gespeicherter Abschluss* (`registered_at`), gesetzt, sobald die Journey ihre Pflichten erfüllt
  sieht. Diese Lösung war kurz umgesetzt und wurde wieder zurückgenommen. Die Pflichten hängen von
  Variante, Kanal, Niveau und den verfügbaren Tools ab. „Eingerichtet“ hieß damit „die Journey ist am
  Ende“ und nicht „das Konto ist benutzbar“. Das brauchte eine eigene Aktion, eine zusätzliche
  Absicherung beim Anmelden und eine aufgeschobene Geräteverknüpfung. Außerdem wurde bei der Variante
  „Erst Anmeldeverfahren einrichten“ ein Konto mit erfüllten Pflichten erst eingerichtet, wenn der
  Nutzer die freiwillige Frage beantwortet hatte, ob er sich identifizieren möchte.
- *Ein eigenes Modell „Registrierung“*, das erst beim Abschluss zum Konto wird. Fachlich wäre das am
  saubersten. Heute sind aber Angaben (Claims) und Verfahren über Konto-IDs zugeordnet. Nicht
  umgesetzt.
- *`REGISTERING` aus `isDisposable` ableiten.* Dann fiele der Zustand nach der Identifizierung auf
  `ANONYMOUS` zurück, also genau dort, wo ein Abbruch etwas verwirft.

**Kosten.** Bricht jemand nach dem ersten Verfahren ab, bleibt ein Konto zurück, in das man sich
anmelden kann. Das gilt auch dann, wenn die Registrierung ihre übrigen Pflichten (zweiter Faktor,
Niveau) nicht erfüllt hat. Die Anmelde-Journeys gehen damit so um:

- `FAST_ACCESS` bietet fehlende Verfahren nach dem Nachweis an.
- Ein Step-up, also ein zusätzlicher Nachweis für ein höheres Niveau, verlangt sie, sobald ein Niveau
  sie braucht.
- Ein zweiter Registrierungsversuch mit derselben Adresse findet das bestehende Konto und bekommt den
  Fehler `409` aus ADR-20. Dessen Text verweist auf die Anmeldung.
- Ein Gerät kann ein Konto im Aufbau nur über die ausdrückliche Umbinde-Frage nach der
  Identifizierung wiedererkennen. `FAST_ACCESS` gibt ein Konto ohne Verfahren dann an `REGISTER` ab.

**Invarianten** (in [invarianten.md](../invarianten.md)), also Regeln, die immer gelten müssen:

- I-26: Ein angemeldeter Kanal arbeitet nie mit einem Konto im Aufbau. `REGISTERING` wird nie
  gespeichert.
- I-27: Anmeldung und Keycloak-Suche finden kein Konto im Aufbau.
- I-28: Ein eingerichtetes Konto fällt nie in den Aufbau zurück.
