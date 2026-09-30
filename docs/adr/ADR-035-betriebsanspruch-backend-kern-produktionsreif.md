# ADR-35: Betriebsanspruch – der Backend-Kern ist produktionsreif, Rand und Umgebung folgen später

**Status:** entschieden (2026-09-25).

**Entscheidung**: Das Projekt soll zeigen, dass der Ansatz trägt. Den Beleg liefert ein
**produktionsreifer Backend-Kern**, nicht eine Oberfläche. Frontends und Ausführungsumgebungen
werden später gehärtet; bis dahin gelten sie als Vorführrahmen.

Das Projekt zerfällt dafür in drei Bereiche mit verschiedenem Anspruch:

1. **Kern – produktionsreif.**
   - Module: die Gruppen `core/` (`orchestrator` samt `kc`-Anbindung, `account`), `contract/`
     (`tool_api`, `texts`) und `tools/` (alle Tool-Module, `auth_*` wie
     `ident_*`).
   - Die Keycloak-Extension (`keycloak-extension`) und die Realm-Migrationen (`keycloak-migrations`):
     Sie sind Backend-Code, der über Konten und Niveaus entscheidet.
   - Anspruch: Jede Sicherheitszusage gilt ohne Voraussetzung an die Umgebung, die nicht ausdrücklich
     als Annahme benannt ist. Invarianten sind per Typ, Constraint oder Test erzwungen, nicht per
     Kommentar. Kein unerklärtes „demo-only“ im Kern: Ein Verfahren, das mehr vergibt, als es
     beweisen kann, erklärt sich selbst und ist außerhalb des Demomodus aus
     ([ADR-36](ADR-036-niveaus-und-ihre-nachweise.md)).
2. **Simulierte Fremdsysteme – Vorführrahmen hinter Ports.**
   - Module: die Gruppe `simulation/` (`personenverzeichnis`, `kobil`, `nect`, `sms`, `mail`) und
     `demo/demo_seed`; dazu die
     simulierte eID-Kartenlesung in `ident_eid` und der Klartextversand von TAN/Code.
   - Anspruch: Sie ersetzen reale Systeme und dürfen deren Sicherheit nicht nachbauen – aber der Kern
     vertraut ihnen **nur über seinen Port** und nur mit dem, was der Port-Vertrag zusagt. Was ein
     reales System zusätzlich leisten muss (z. B. Einmaligkeit eines Freischaltcodes, Signatur einer
     KOBIL-Antwort), steht im Vertrag des Ports, nicht im Mock.
3. **Frontends und Ausführungsumgebung – später.**
   - `frontend/`, `keycloak-theme/`, `compose.yml`, `Dockerfile`, `openshift/`, Profil- und
     Datenbank-Konfiguration für den Betrieb (Admin-Zugang, H2-Konsole, Keycloak-Startmodus,
     Proxy-Header, TLS zwischen den Containern).
   - Anspruch: vorführfähig. Sie werden in einer eigenen Runde gehärtet; bis dahin darf keine
     Instanz mit echten Personendaten laufen.

**Warum so geschnitten**: Ob der Ansatz trägt, entscheidet sich am Kern: Orchestrierung, Niveaus,
Kontobindung, DPoP-Bindung. Eine gehärtete Oberfläche oder ein gehärtetes Deployment sagt nichts über
den Ansatz aus, kostet aber dieselbe Zeit. Umgekehrt hielte ein Kern, dessen Invarianten nur auf
Konvention beruhen, keiner genauen Prüfung stand.

**Erwogene Alternative**: Das ganze Projekt als Referenz deklarieren und nur die ausnutzbaren
Befunde schließen. Verworfen: Dann bleibt P-1 (Invarianten per Konvention) offen, und das Argument
„produktionsnah“ fällt beim ersten Review in sich zusammen.

**Folgen für die Review-Befunde 2026-09**:

- **Im Kern, jetzt:** S-1 bis S-3, S-6, S-7, M-1 bis M-11, M-13; die strukturellen Maßnahmen P-1
  (Typen, Constraints, modellbasierter Test, Invariantenregister), P-3 (eine Wahrheit für Keycloak)
  und P-4 (Niveaus nur mit Nachweis).
- **S-4 (Trust-all-TLS)** gehört in den Kern, obwohl es nach Umgebung aussieht: Die Klasse sitzt im
  Kern und wirkt JVM-weit auf jeden ausgehenden Aufruf. Der Kern bindet Trust-all an einen
  ausdrücklichen Schalter; wie die Umgebung Zertifikate bereitstellt, folgt später.
- **M-9 (Hop Keycloak → Orchestrator)**: Die Authentisierung der Antwort ist Kern; TLS auf dem Hop ist
  Umgebung.
- **Später (Umgebung/Frontend):** S-5 (Admin-Passwort auf OpenShift), M-12 (Keycloak `start-dev`,
  H2-Konsole im Pod), Compose-Ports, `forward-headers-strategy`, CSP-Header, Web-Kanal-Tokens im
  `sessionStorage`, Frontend-Thumbprint.
- **Fremdsystem-Simulation:** S-8 (Freischaltcode-Hash) liegt im simulierten Personenverzeichnis
  ([ADR-31](ADR-031-freischaltcode-liegt-im-fremdsystem.md)). Dass ein Code bis zum Ablauf
  wiederverwendbar bleibt, ist eine eigene, bewusste Entscheidung mit benanntem Restrisiko (ADR-31,
  Abschnitt „Der Code ist bis zum Ablauf wiederverwendbar“). Die unauthentifizierte Verwaltungs-API
  des Verzeichnisses bleibt Vorführrahmen.
- **`ident-eid`** bleibt eine Simulation des eID-Servers mit dessen Niveau (loa3); der Port-Vertrag
  benennt, dass ein reales Ergebnis serverseitig vom eID-Server kommt und nie aus Client-Angaben.

**Folgen allgemein**:

- „demo-only“ ist im Kern kein zulässiger Kommentar mehr. Findet sich einer, ist es ein Befund: Das
  Stück wird entweder produktionsreif oder wandert hinter einen Port in den Fremdsystem-Bereich.
- Die Grenze zwischen Kern und Fremdsystem-Simulation ist ein Port; ein ArchUnit-Test sichert, dass
  der Kern kein Mock-Modul direkt referenziert.
- Bevor Frontend und Umgebung gehärtet sind, läuft keine Instanz mit echten Personendaten. Das
  steht als Einschränkung in [07-betrieb.md](../07-betrieb.md).
