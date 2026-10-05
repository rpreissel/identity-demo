# ADR-37: Bei einem nie identifizierten Konto genügt das Postfach auch für destruktive Aktionen

**Status:** entschieden und umgesetzt (2026-09-25).

**Entscheidung**: Manche Aktionen im eigenen Konto verlangen ein bestimmtes
[Sicherheitsniveau](../glossar/glossar.md), also einen Nachweis, wie sehr der Anmeldung vertraut
wird. `loa1` heißt: ein Anmeldeverfahren, `loa2` heißt: zwei Verfahren verschiedener Art oder eine
Identifizierung. Diese ADR legt fest, welches Niveau für die Selbstbedienung an einem Konto
ohne Personenbindung reicht.

Ein Konto ohne Personenbindung (`personId == null`) ist keiner Person im Personenverzeichnis
zugeordnet. So ein Konto entsteht etwa bei der Registrierung „Enrollment zuerst“, bei der jemand
zuerst ein Anmeldeverfahren einrichtet und sich nicht identifiziert. Für ein solches Konto genügt
loa1 für die Selbstbedienung (`selfServiceAcrFloor`). Dazu gehören:

- das Konto löschen,
- ein Verfahren einrichten oder entfernen,
- die E-Mail-Adresse zurückziehen.

loa1 kann dabei allein der Code sein, der an die E-Mail-Adresse geht. Wer das Postfach übernimmt,
kann all das also ohne zweiten Faktor. Jedes identifizierte Konto verlangt weiterhin loa2.

**Warum**: Ein unidentifiziertes Konto hat keine Identität und keine Stammdaten. Bei einer
Übernahme ist also nichts davon zu erreichen. Seine E-Mail-Adresse ist sein
[Anker](../glossar/glossar.md), also die Angabe, über die das Konto eindeutig wiedergefunden wird
([ADR-19](ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md)). Wer das Postfach hat, ist
für dieses Konto deshalb faktisch der Inhaber. Auch ein zweiter Faktor ließe sich über dasselbe
Postfach zurücksetzen.

Mehr zu verlangen, als ein solches Konto erreichen kann, sperrt den Nutzer nur aus. Ein nie
identifiziertes Konto kommt ohne Identifizierung nie über loa1 hinaus. Diese Obergrenze setzt die
Richtlinie für Sicherheitsniveaus (`DefaultAuthPolicy`) über `enrolledUnderAcr`.

Das entspricht dem Grundsatz aus M-6: Wer mehr Sicherheit an seinem Konto will, identifiziert sich.
Ein höheres Niveau erreicht ein Konto durch die Identifizierung, nicht durch einen zweiten Faktor
an einem Konto ohne Identität.

**Erwogene Alternativen**:

- **Zwei verschiedene Faktorarten** (loa2 aus der Kombination zweier Verfahren) für destruktive
  Aktionen: verworfen. Konten, die nur die E-Mail-Adresse haben, könnten sich dann nicht mehr
  selbst löschen, ohne sich vorher zu identifizieren. Für ein Konto ohne Identität ist das eine
  unverhältnismäßige Hürde.
- **Ein frisch geprüfter Faktor, der nicht über die E-Mail läuft**: verworfen aus demselben Grund.

**Restrisiko**: Wer ein Postfach übernimmt, übernimmt damit jedes unidentifizierte Konto an dieser
Adresse und kann es auch löschen. Geschützt ist dabei aber nichts, was einer Person zugeordnet wäre.
Ein Konto mit Personenbindung lässt sich auf diesem Weg nicht erreichen.
