# ADR-29: Die Event Publication Registry von Spring Modulith statt einer eigenen Outbox-Tabelle

**Status:** umgesetzt; der Anlass, die Keycloak-Spiegelung, ist seit
[ADR-38](ADR-038-keycloak-liest-konten.md) entfallen (siehe Nachtrag).

> **Nachtrag 2026-09-26:** Diese ADR entstand für die Keycloak-Spiegelung. Diese Spiegelung gibt es
> seit [ADR-38](ADR-038-keycloak-liest-konten.md) nicht mehr: Keycloak liest die Konten selbst beim
> Orchestrator nach. Die Registry wird heute von zwei Listenern genutzt:
>
> - `KeycloakAccountRemovalListener` löscht nach dem Ereignis `AccountDeleted` die Daten, die
>   Keycloak selbst zu dem Konto speichert (Sitzungen, Zustimmungen).
> - `PersonChangeListener` übernimmt eine Änderung aus dem Personenverzeichnis ins Konto
>   ([ADR-34](ADR-034-personenverzeichnis-meldet-aenderungen.md)).
>
> Die Abschnitte „Das Problem“, „Erster Ansatz“ und „Was wir dafür aufgeben“ beschreiben den Stand
> der Spiegelung. Die Regeln unter „Was dadurch anders wird“ und „Kosten und Stolpersteine“ gelten
> weiter.

**Entscheidung.** Manche Ereignisse müssen sicher zugestellt werden, auch wenn ein erster Versuch
scheitert. Diese Ereignisse laufen über `@ApplicationModuleListener`. Anfangs war das die
Keycloak-Spiegelung. Heute sind es das Aufräumen der Keycloak-Daten eines gelöschten Kontos und die Übernahme
von Änderungen aus dem Personenverzeichnis. Spring Modulith trägt dann jede noch offene Zustellung in
die Tabelle `orchestrator.event_publication` ein. Eine eigene Outbox-Tabelle gibt es nicht.

Zum Hintergrund: Eine **Outbox** ist eine Tabelle, in die eine Anwendung ein Ereignis in derselben
Datenbank-Transaktion schreibt wie die eigentliche Änderung. So geht das Ereignis nicht verloren,
auch wenn die Zustellung erst später gelingt. Die **Event Publication Registry** ist die fertige
Umsetzung dieses Verfahrens in Spring Modulith. Ein **Listener** ist der Code, der auf ein Ereignis
reagiert.

## Das Problem

Der Orchestrator verwaltet die Konten. Früher übertrug er jede Kontoänderung zusätzlich an Keycloak,
den Anmeldeserver. Das war die Keycloak-Spiegelung.

Diese Spiegelung lief absichtlich ohne Garantie. Ein fehlgeschlagener Aufruf bei Keycloak durfte eine
Kontoänderung, die schon in der Datenbank festgeschrieben war, nicht nachträglich scheitern lassen.
Der Fehler wurde deshalb nur ins Log geschrieben, sonst geschah nichts. Ein neuer Versuch kam erst,
wenn sich das Konto irgendwann noch einmal änderte. Bei einem Konto, das sich nie wieder ändert, kam
also nie ein neuer Versuch.

Das Ergebnis: Das Konto existiert, aber der Nutzer fehlt in Keycloak. Eine Anmeldung ist unmöglich,
und nirgends ist vermerkt, dass das so ist.

## Erster Ansatz: eigene Tabelle

Zuerst hatte ich eine eigene Tabelle `orchestrator.kc_sync_outbox` gebaut. Der Eintrag entstand in
der Transaktion der Kontoänderung, mit dem Schlüssel `accountId`. Dazu kamen ein Service, der Erfolg
und Fehler verbucht, und ein geplanter Job für die Wiederholung. Das waren vier Klassen und eine
Migration.

Das war überflüssig. Spring Modulith 2.1.1 ist schon im Projekt und bringt genau dieses Verfahren
mit: die Event Publication Registry. Sie kann mehr als mein Nachbau:

| | eigene Tabelle | Registry |
|---|---|---|
| Eintrag vor dem Festschreiben | ja | ja |
| Wiederholung im laufenden Betrieb | eigener `@Scheduled`-Job | `spring.modulith.events.staleness.*` |
| Wiederholung nach einem Neustart | nein | `republish-outstanding-events-on-restart` |
| Zustellversuche, Status, Zeitpunkt der letzten Wiederholung | nur Versuche | alle drei |
| Wer den Code pflegt | wir | das Framework |

## Was wir dafür aufgeben

Meine Tabelle hatte `accountId` als Schlüssel. Zehn Änderungen an einem Konto ergaben deshalb nur
einen offenen Eintrag. Die Registry führt dagegen einen Eintrag je Ereignis und Listener, in diesem
Fall also zehn.

Das ist vertretbar. Die Spiegelung übertrug immer den aktuellen Stand des Kontos und ließ sich
deshalb beliebig oft wiederholen. Zehn Zustellungen schreiben zehnmal dasselbe und erzeugen keine
falschen Daten.

## Was dadurch anders wird

- Ein Fehler im Listener darf **nicht** mehr abgefangen werden. Die Exception ist das Signal „nicht
  erledigt“. Nur sie hält den Eintrag offen. Ein `catch`, das den Fehler nur ins Log schreibt, würde
  die Zustellung als erledigt markieren. Der Fall wäre dann endgültig verloren.
- `@ApplicationModuleListener` ist zusätzlich `@Async`. Der Listener läuft also nicht im Thread der
  Anfrage, sondern getrennt davon.

## Kosten und Stolpersteine

- Es gibt eine Abhängigkeit mehr (`spring-modulith-starter-jdbc`).
- Die Einstellung `spring.modulith.events.jdbc.schema: orchestrator` ist zwingend. Ohne sie sucht
  die Registry die Tabelle im Standardschema, findet sie nicht und schreibt nichts. Eine
  Fehlermeldung gibt es dabei nicht. Genau das ist beim ersten Versuch passiert. Deshalb prüft der
  `EventPublicationRegistryTest` diese Einstellung.
- Die Tabelle legt Flyway an (`orchestrator/V15__event_publication.sql`), nicht Modulith selbst.
  Das Projekt legt jedes Schema über eine Migration an (ADR-16). Die Datei ist unverändert aus dem
  `spring-modulith-events-jdbc`-Jar übernommen. Beim Wechsel auf eine neue Version muss man sie
  deshalb mit der neuen Fassung vergleichen.

## Eine Ausnahme

`KeycloakSessionLogoutListener` bleibt ein einfacher `@TransactionalEventListener` ohne Registry.
Der Grund: Eine Sitzung in Keycloak, die nicht beendet wurde, läuft nach wenigen Minuten von selbst
ab. Die Daten, die Keycloak zu einem gelöschten Konto speichert, blieben dagegen liegen, wenn niemand
sie löscht. Nur der zweite Fall braucht deshalb eine Wiederholung.

Mehr dazu in [07-betrieb.md](../07-betrieb.md), Abschnitt 3a.
