# ADR-22: Demo-Geheimnisse liegen im Klartext — benannt statt verschwiegen

**Status:** umgesetzt.

**Kontext**: Ein Geheimnis wie ein Passwort speichert man normalerweise nicht im Klartext. Man
speichert nur einen Hash, also einen daraus berechneten Wert, aus dem sich das Geheimnis nicht
zurückgewinnen lässt. Das geht aber nur, wenn man das Geheimnis später nie wieder im Original
braucht. Einige Geheimnisse in diesem Projekt muss der Server dagegen im Original herausgeben oder
selbst benutzen, etwa eine PIN, die er bei jeder Anmeldung an den Anbieter KOBIL weitergibt, oder
seinen eigenen Signaturschlüssel. Diese ADR hält fest, welche Geheimnisse das sind und warum die Demo
sie nicht verschlüsselt.

## Entscheidung

Einige Geheimnisse speichert die Demo bewusst im Klartext. Das ist ein Kompromiss für die Demo. Er
steht hier an **einer** Stelle, statt in jeder betroffenen ADR neu begründet zu werden:

| Was | Wo | Warum nicht gehasht |
|---|---|---|
| verwahrter KOBIL-PIN ([ADR-21](ADR-021-der-kobil-pin-liegt-im-backend-und-das.md)) | `auth_kobil.enrollment.pin`, seit [ADR-55](ADR-055-hauptschluessel-je-journey-verfahrensgeheimnisse-versiegelt.md) verschlüsselt, aber für den Server lesbar | muss für jede Anmeldung herausgegeben werden |
| PIN und Entsperrgeheimnis während einer KOBIL-Einrichtung | Arbeitsdaten von `enroll-kobil` in `orchestrator.tool_session.data` ([ADR-49](ADR-049-arbeitsdaten-der-tools-am-orchestrator.md)); seit [ADR-53](ADR-053-arbeitsdaten-und-app-tokens-verschluesselt.md) verschlüsselt, aber für den Server lesbar | ein Neuladen der Seite soll den Ablauf nicht abbrechen |
| Freischaltcode im Brief ([ADR-31](ADR-031-freischaltcode-liegt-im-fremdsystem.md)) | `personenverzeichnis.brief.code` | den Klartext gibt es auch in der echten Welt, auf Papier; geprüft wird nur gegen den Hash in `freischaltcode` |
| Signaturschlüssel des Orchestrators ([ADR-25](ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md)) | seit [ADR-54](ADR-054-schluesseldienst-simuliert.md) im Schlüsseldienst (`kms.transit_key_version`, Simulation); die Tabelle `orchestrator.node_signing_key` gibt es nicht mehr | der Dienst signiert, der Schlüssel verlässt ihn nie |
| Signaturschlüssel der Keycloak-Erweiterung (ADR-25) | Wert `peerAuthSigningKeyJwk` der Komponente `orchestrator`, in der Datenbank von Keycloak; als Geheimnis deklariert, Admin-API, Admin-Console und Realm-Export zeigen ihn nur maskiert | wie oben |

Verschlüsselt wird nichts davon.

## Begründung

Eine Verschlüsselung (etwa AES-256-GCM unter einem Schlüssel aus `identity.secrets`) schützt gegen
eine gestohlene Kopie der Datenbank. Sie schützt aber nicht gegen einen Zugriff auf den laufenden
Prozess, denn der muss den Schlüssel ja kennen. Beim KOBIL-PIN kommt hinzu: Der simulierte Anbieter
(`kobil`) hält denselben Wert ohnehin im Klartext, so wie es das echte KOBIL tun müsste. Eine
Verschlüsselung auf nur einer Seite sähe nach Schutz aus, ohne einer zu sein. Für eine Demo lohnt
sich dafür der Aufwand für die Verwaltung von Schlüsseln nicht.

**Erwogene Alternative beim PIN:** Den PIN für jede Anmeldung neu setzen (KOBIL kann das) und danach
verwerfen. Dann bliebe nichts dauerhaft gespeichert. Aber jede Anmeldung wäre dann auf die
Verwaltungsschnittstelle des Anbieters angewiesen. Außerdem entstünde ein Zeitfenster, in dem der
Wechsel des PINs und die Anmeldung über das SDK in der falschen Reihenfolge ablaufen können.

## Folgen

- Vertretbar ist das nur, solange es sichtbar bleibt. Die H2-Konsole ist im Projekt bewusst
  eingeschaltet. Ihr Kommentar in `application.yml` zählt auf, was dort lesbar wäre, wenn man sie
  über `web-allow-others` öffnete. Die Klartextfelder aus der Tabelle oben gehören auf diese Liste.
- Die Einrichtungsdaten von KOBIL bleiben nur so lange gespeichert wie jede Tool-Session, also
  höchstens 24 Stunden (`tool-session.retention`). Sie werden mit ihrer Zeile in
  `orchestrator.tool_session` gelöscht und nach der Aktivierung geleert.
- Ein echter Betrieb bräuchte für jede Zeile der Tabelle eine eigene Lösung, etwa einen
  Schlüsselspeicher, ein HSM oder verschlüsselte Spalten. Für das Log der Angaben (Claim-Log) gibt
  es sie: [ADR-52](ADR-052-umschlagverschluesselung-des-claim-logs.md) verschlüsselt die Werte mit
  eigenen Schlüsseln je Gruppe. An dieser Entscheidung ändert das nichts; die Zeilen der Tabelle oben
  bleiben im Klartext.

## Geschichte

Ursprünglich betraf diese ADR nur den verwahrten KOBIL-PIN. Dieselbe Abwägung stand danach zusätzlich
in ADR-9, ADR-25 und ADR-31. Sie ist jetzt hier zusammengefasst; die anderen ADRs verweisen nur noch
hierher.
