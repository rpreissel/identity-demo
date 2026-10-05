# ADR-35: Betriebsanspruch – der Backend-Kern ist produktionsreif, Rand und Umgebung folgen später

**Status:** entschieden (2026-09-25).

Ein Demo-Projekt kann nicht alles zugleich in Produktionsqualität liefern. Es muss entscheiden,
welche Teile so sorgfältig gebaut werden, als liefen sie im Betrieb mit echten Daten, und welche
Teile vorerst nur der Vorführung dienen. Ohne diese Grenze bleibt unklar, welche Schwächen ein
Fehler sind und welche bewusst in Kauf genommen werden. Ein Review im September 2026 hat eine Reihe
von Befunden ergeben, die nach dieser Grenze eingeordnet werden mussten.

**Entscheidung**: Das Projekt soll zeigen, dass der Ansatz funktioniert. Den Beleg liefert ein
**produktionsreifer Backend-Kern**, nicht eine Oberfläche. Frontends und Ausführungsumgebungen
werden später gehärtet, also gegen Angriffe und Fehlbedienung abgesichert. Bis dahin gelten sie als
Vorführrahmen.

Das Projekt ist dafür in drei Bereiche mit unterschiedlichem Anspruch aufgeteilt:

1. **Kern – produktionsreif.**
   - Module: die Gruppen `core/` (`orchestrator` samt `keycloak`-Anbindung, `account`), `contract/`
     (`tool_api`, `texts`) und `tools/` (alle Tool-Module, `auth_*` ebenso wie
     `ident_*`).
   - Die Keycloak-Extension (`keycloak-extension`) und die Realm-Migrationen (`keycloak-migrations`).
     Sie zählen zum Kern, weil sie Backend-Code sind, der über Konten und Niveaus entscheidet. Das
     Niveau gibt an, wie sehr einer Anmeldung vertraut wird
     (siehe [Glossar](../glossar/glossar.md)).
   - Anspruch: Jede Sicherheitszusage gilt, ohne dass die Umgebung etwas Bestimmtes leisten muss.
     Ausgenommen sind nur Voraussetzungen, die ausdrücklich als Annahme benannt sind. Invarianten,
     also Regeln, auf die sich der Kern jederzeit verlässt, sind per Typ, Datenbank-Constraint
     oder Test erzwungen, nicht per Kommentar. Im Kern gibt es kein unerklärtes „demo-only“. Ein
     Verfahren, das ein höheres Niveau vergibt, als es beweisen kann, sagt das selbst und ist
     außerhalb des Demomodus abgeschaltet ([ADR-36](ADR-036-niveaus-und-ihre-nachweise.md)).
2. **Simulierte Fremdsysteme – Vorführrahmen hinter Ports.** Ein Port ist eine fest definierte
   Schnittstelle, über die der Kern mit einem anderen System spricht.
   - Module: die Gruppe `simulation/` (`personenverzeichnis`, `kobil`, `nect`, `sms`, `mail`) und
     `demo/demo_seed`. Dazu kommen die simulierte eID-Kartenlesung in `ident_eid` und der Versand
     von TAN und Code im Klartext.
   - Anspruch: Diese Module ersetzen reale Systeme. Sie müssen deren Sicherheit nicht nachbauen.
     Der Kern vertraut ihnen aber **nur über seinen Port** und nur in dem, was der Vertrag des Ports
     zusagt. Was ein reales System zusätzlich leisten muss, steht im Vertrag des Ports, nicht im
     Mock. Beispiele sind die Einmaligkeit eines Freischaltcodes oder die Signatur einer
     KOBIL-Antwort.
3. **Frontends und Ausführungsumgebung – später.**
   - Dazu gehören `frontend/`, `keycloak-theme/`, `compose.yml`, `Dockerfile`, `openshift/` sowie
     die Profil- und Datenbank-Konfiguration für den Betrieb. Gemeint sind etwa Admin-Zugang,
     H2-Konsole, Startmodus von Keycloak, Proxy-Header und TLS zwischen den Containern.
   - Anspruch: vorführfähig. Diese Teile werden in einer eigenen Runde gehärtet. Bis dahin darf
     keine Instanz mit echten Personendaten laufen.

**Warum so geschnitten**: Ob der Ansatz funktioniert, entscheidet sich am Kern, also an
Orchestrierung, Niveaus, Kontobindung und DPoP-Bindung. DPoP bindet ein Token an einen Schlüssel auf
dem Gerät, sodass ein gestohlenes Token allein nichts nützt. Eine gehärtete Oberfläche oder ein
gehärtetes Deployment sagt nichts über den Ansatz aus, kostet aber genauso viel Zeit. Umgekehrt
würde ein Kern, dessen Invarianten nur auf Konvention beruhen, keiner genauen Prüfung standhalten.

**Erwogene Alternative**: Das ganze Projekt als Referenz erklären und nur die Befunde schließen, die
sich tatsächlich ausnutzen lassen. Verworfen: Dann bliebe P-1 (Invarianten nur per Konvention)
offen. Die Aussage, das Projekt sei „produktionsnah“, ließe sich dann schon beim ersten Review nicht
mehr halten.

**Folgen für die Review-Befunde 2026-09**:

Die Kürzel bezeichnen einzelne Befunde aus dem Review. Sie werden so den drei Bereichen zugeordnet:

- **Im Kern, jetzt:** S-1 bis S-3, S-6, S-7, M-1 bis M-11, M-13. Dazu die strukturellen Maßnahmen
  - P-1 (Typen, Constraints, modellbasierter Test, Invariantenregister),
  - P-3 (eine einzige maßgebliche Quelle für Keycloak) und
  - P-4 (Niveaus nur mit Nachweis).
- **S-4 (Trust-all-TLS)** gehört in den Kern, obwohl es nach Umgebung aussieht. Gemeint ist eine
  Einstellung, die jedem TLS-Zertifikat vertraut. Die Klasse dafür liegt im Kern und wirkt in der
  ganzen JVM auf jeden ausgehenden Aufruf. Der Kern erlaubt Trust-all deshalb nur über einen
  ausdrücklichen Schalter. Wie die Umgebung Zertifikate bereitstellt, folgt später.
- **M-9 (Verbindung Keycloak → Orchestrator)**: Dass die Antwort authentisiert ist, gehört zum
  Kern. TLS auf dieser Verbindung gehört zur Umgebung.
- **Später (Umgebung/Frontend):** S-5 (Admin-Passwort auf OpenShift), M-12 (Keycloak `start-dev`,
  H2-Konsole im Pod), Ports in Compose, `forward-headers-strategy`, CSP-Header, Tokens des
  Web-Kanals im `sessionStorage`, Thumbprint im Frontend.
- **Simulation der Fremdsysteme:** S-8 (Hash des Freischaltcodes) betrifft das simulierte
  Personenverzeichnis ([ADR-31](ADR-031-freischaltcode-liegt-im-fremdsystem.md)). Dass ein Code bis
  zum Ablauf wiederverwendbar bleibt, ist eine eigene, bewusste Entscheidung mit benanntem
  Restrisiko. Sie steht in ADR-31 im Abschnitt „Der Code ist bis zum Ablauf wiederverwendbar“. Die
  Verwaltungs-API des Verzeichnisses verlangt keine Authentisierung. Sie bleibt Vorführrahmen.
- **`ident-eid`** bleibt eine Simulation des eID-Servers und vergibt dessen Niveau (loa3). Der
  Vertrag des Ports sagt ausdrücklich, dass ein reales Ergebnis auf dem Server vom eID-Server kommt
  und nie aus Angaben des Clients.

**Folgen allgemein**:

- „demo-only“ ist im Kern kein zulässiger Kommentar mehr. Findet sich einer, ist das ein Befund. Das
  betroffene Stück wird dann entweder produktionsreif, oder es wird hinter einen Port in den
  Bereich der Fremdsysteme verlegt.
- Die Grenze zwischen Kern und Simulation der Fremdsysteme ist ein Port. Ein ArchUnit-Test stellt
  sicher, dass der Kern kein Mock-Modul direkt verwendet.
- Bevor Frontend und Umgebung gehärtet sind, läuft keine Instanz mit echten Personendaten. Das
  steht als Einschränkung in [07-betrieb.md](../07-betrieb.md).
