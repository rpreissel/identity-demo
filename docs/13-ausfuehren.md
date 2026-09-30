# Ausführen und bauen

Dieses Kapitel beschreibt, wie man die Demo auf dem eigenen Rechner startet, baut und testet: mit
und ohne Keycloak, mit Podman-Containern und direkt auf dem Rechner. Was für den Betrieb außerhalb
der Demo gilt (Fehlervertrag, Aufbewahrung, Pflichtwerte außerhalb des Demomodus, Kennzahlen), steht
in [07-betrieb.md](07-betrieb.md). Die Modulstruktur und die Prüfungen im Build beschreibt
[08-projektrahmen.md](08-projektrahmen.md) Abschnitt 7.

---

## 1) Voraussetzungen

- **JDK 21** und **Node.js mit npm** auf dem Rechner. Gradle ruft npm selbst auf, um das Frontend und
  das Keycloak-Theme zu bauen.
- **Podman** mit `podman compose`, nur für die Varianten mit Keycloak. Auf macOS läuft Podman in
  einer VM (Podman Machine). Diese VM bindet nur `/Users` ein: Bind-Mounts aus `/tmp` funktionieren
  dort nicht. `compose.yml` nutzt deshalb benannte Volumes (`keycloak-data`, `orchestrator-data`),
  und so sollte es auch bei eigenen Erweiterungen bleiben.
- Am Arbeitsplatz ohne Zugang zu `services.gradle.org`: einmal `./init-local` ausführen und das
  Passwort eingeben. Das Skript entschlüsselt die Adressen des internen Spiegels
  (`gradle/local-mirror.enc`), stellt die Gradle-Distribution in
  `gradle/wrapper/gradle-wrapper.properties` um und nimmt die Datei aus der Verfolgung durch Git.
  Den Maven-Spiegel legt es als Init-Skript unter `~/.gradle/init.d/` ab; der Build im Repo bleibt
  unverändert. `./init-local remove` nimmt beides zurück.

---

## 2) Drei Arten zu starten

Welche man wählt, hängt davon ab, ob man den Web-Kanal (Anmeldung im Browser über Keycloak) braucht.

### Ohne Keycloak (am schnellsten)

```bash
./gradlew bootRun
```

Startet den Orchestrator im Standardprofil auf Port 8080. Podman ist nicht nötig. Es gibt dann den
App-Kanal und im Demomodus (`DEMO_MODE`, Standard `true`) das Personenverzeichnis, die
Nect-Simulation und die Admin-Seite. Der Web-Kanal ist abgeschaltet: `/web/` zeigt einen Hinweis,
und die Kachel auf der Willkommensseite ist ausgeschaltet.

### Alles in Containern

```bash
./gradlew build
podman compose up --build
```

Startet zwei Container:

- **`keycloak`**: ein echtes Keycloak mit der Erweiterung und beiden Login-Themes, nur HTTPS, auf
  Port 8543. Beim ersten Start erzeugt es ein selbstsigniertes Zertifikat im Volume
  `keycloak-data`. Es bleibt über Neubauten des Images hinweg erhalten, der Browser muss ihm also
  nur einmal vertrauen.
- **`orchestrator`**: Port 8080, mit dem Profil `keycloak` und der H2-Datenbankdatei im Volume
  `orchestrator-data`. Er wartet selbst, bis Keycloak antwortet, und wendet dann alle Migrationen aus
  dem Modul `keycloak-migrations` an (`KeycloakMigrationRunnerStartup`). Damit entsteht das Realm.
  Keycloak erreicht er intern unter `https://keycloak:8443`.

`podman compose up` ist bewusst ein eigener Schritt. Gradle ruft Podman nie auf, damit ein normaler
Build nicht von einer laufenden Podman Machine abhängt.

### Keycloak im Container, Orchestrator auf dem Rechner

Schneller beim Entwickeln, weil nur der Orchestrator neu startet:

```bash
podman compose up keycloak
./gradlew bootRunKc
```

`bootRunKc` ist `bootRun` mit dem Profil `keycloak` (`application-keycloak.yml`). Es spricht Keycloak
unter `https://localhost:8543` an. Ein einfaches `bootRun` bliebe im Standardprofil und würde das
Keycloak aus Compose gar nicht nutzen. Nur in dieser Variante funktioniert auch die H2-Konsole
(Begründung in [08-projektrahmen.md](08-projektrahmen.md), Abschnitt „H2-Konsole: nur beim
Host-Start“).

### Frontend mit Neuladen beim Speichern

```bash
cd frontend
npm install
npm run dev
```

Startet Vite auf Port 5173. Anfragen an `/orchestrator`, `/mock-personenverzeichnis`, `/mock-nect`
und `/mock-kobil` leitet Vite an den Orchestrator auf Port 8080 weiter; der muss also nebenher laufen.

---

## 3) Adressen und Zugänge

- **Willkommensseite**: <http://localhost:8080/>. Von dort führen Kacheln zu allen anderen Seiten.
- **App-Kanal**: <http://localhost:8080/app/>
- **Web-Kanal**: <http://localhost:8080/web/> (nur mit Keycloak)
- **Admin-Seite**: <http://localhost:8080/admin/>, Anmeldung `admin` / `admin` (`demo.admin` in
  `application.yml`)
- **Personenverzeichnis** (simuliertes Fremdsystem): <http://localhost:8080/personenverzeichnis/>
- **Briefkasten** (Briefe, SMS und E-Mails an Testpersonen, nur im Demomodus):
  <http://localhost:8080/briefkasten/>
- **H2-Konsole**: <http://localhost:8080/h2-console>, nur bei Start auf dem Rechner
- **Health und Kennzahlen**: eigener Port 9080 (`MANAGEMENT_PORT`), siehe
  [07-betrieb.md](07-betrieb.md) Abschnitt 7
- **Keycloak**: <https://localhost:8543>. Die Admin-Konsole nutzt `admin` / `admin`
  (`KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`). Der Orchestrator selbst braucht diesen Zugang
  nicht.

Die Datenbank liegt beim Start auf dem Rechner unter `./data/identitydb`, im Container im Volume
`orchestrator-data`. Testpersonen und gültige Freischaltcodes spielt eine Flyway-Migration beim
Start ein. Konten legt sie nicht an: Jede Testperson registriert sich selbst, in der App oder auf
der Website.

---

## 4) Wie der Container-Build funktioniert

Die Dockerfiles bauen nichts selbst. Der Gradle-Task `stagePodmanArtifacts` baut auf dem Rechner das
Orchestrator-Jar, das Frontend, das Jar der Keycloak-Erweiterung und das Keycloak-Theme und legt sie
zusammen mit den Dockerfiles unter `build/podman/orchestrator` und `build/podman/keycloak` ab.
`compose.yml` nennt diese Verzeichnisse als Build-Kontext, nicht die Wurzel des Repos. Podman liest
also nur die fertigen Artefakte und nicht `.git`, `node_modules` oder Gradle-Caches.

Der Task hängt an `assemble` und läuft damit bei jedem `./gradlew build` mit. Einzeln braucht man
ihn nur, wenn man ausschließlich die Artefakte erzeugen will:

```bash
./gradlew stagePodmanArtifacts
```

Fehlen die Artefakte, bricht der `COPY`-Schritt im Image-Build mit „not found“ ab, statt still ein
altes Artefakt zu verwenden.

Die Images legen kurz als `USER root` einen eigenen Nutzer an (`dpop` im Orchestrator-Image; das
Keycloak-Image bringt `keycloak` schon mit). Dafür nehmen sie `groupadd`/`useradd` (UBI) oder
`addgroup`/`adduser` (Alpine), je nachdem, was das Basis-Image mitbringt.

---

## 5) Basis-Images einstellen

Grundsatz: Red Hat, wo es geht, auch zu Hause. Standardmäßig kommen die Laufzeit-Images von
`registry.access.redhat.com` (UBI, ohne Anmeldung und ohne Red-Hat-Konto). Nur Keycloak selbst
kommt vom öffentlichen `quay.io/keycloak/keycloak`, weil es dafür kein freies Red-Hat-Image gibt.

Beide Basis-Images lassen sich überschreiben, ohne die Dockerfiles zu ändern (`ARG` im Dockerfile,
`build.args` in `compose.yml`). Die Werte liest Podman Compose aus einer Datei `.env` im
Projektverzeichnis. Sie steht nicht im Repo. Für den Arbeitsplatz gibt es eine Vorlage:

```bash
cp .env.work.example .env
```

Podman Compose liest von sich aus nur `.env`, nicht `.env.local`; eine andere Datei nur mit
`--env-file`. Ohne `.env` gelten die Standardwerte, zu Hause braucht es also keine.

Die Variablen:

- **`KEYCLOAK_BASE_IMAGE`**: Laufzeit-Image von Keycloak in `keycloak-extension/Dockerfile`.
  Standard `quay.io/keycloak/keycloak:26.6.4`.
- **`ORCHESTRATOR_RUNTIME_BASE_IMAGE`**: Laufzeit-Image in `Dockerfile`. Standard
  `registry.access.redhat.com/ubi9/openjdk-21-runtime:latest`.
- **`KEYCLOAK_SETUP_VARIANT`**: welche Keycloak-Umgebung aufgebaut und angesprochen wird (Abschnitt
  6). Standard `host`; `compose.yml` setzt `compose`.
- **`KEYCLOAK_ADMIN`** / **`KEYCLOAK_ADMIN_PASSWORD`**: erster Admin von Keycloak, nur für die
  Admin-Konsole. Standard `admin` / `admin`.
- **`ORCHESTRATOR_CLIENT_JWKS_URL`**: wo Keycloak den öffentlichen Schlüssel des Orchestrators für
  den Client `orchestrator-migration` abholt. Standard
  `http://host.containers.internal:8080/orchestrator/api/v1/kc/client-jwks/orchestrator-migration/.well-known/jwks.json`.
  Derselbe Wert passt für beide Varianten mit Keycloak, weil der Orchestrator in beiden Fällen auf
  Port 8080 des Rechners erreichbar ist.

Beispiel einer `.env` für eine Firmenumgebung mit Red-Hat-Abonnement (Red Hat build of Keycloak
statt Community-Keycloak):

```
KEYCLOAK_BASE_IMAGE=registry.redhat.io/rhbk/keycloak-rhel9:26.2
ORCHESTRATOR_RUNTIME_BASE_IMAGE=registry.redhat.io/ubi9/openjdk-21-runtime:latest
```

Dafür braucht es vorher `podman login registry.redhat.io` mit gültigen Zugangsdaten; anonym
scheitert der Download mit 403. `rhbk/keycloak-rhel9` zählt seine Versionen unabhängig vom
Community-Keycloak (derzeit etwa 26.2/26.4 statt 26.6.4).

Für den Gradle-Lauf auf dem Rechner gelten diese Variablen nicht; dort hilft bei Bedarf `./init-local`
(Abschnitt 1).

---

## 6) Keycloak-Umgebung: eine Variante statt einzelner Werte

Realm-Name, Client-IDs, Redirect-URIs und die Adressen beider Seiten stehen gemeinsam in einem
benannten Satz (`KeycloakSetup`). Aus ihm speisen sich die Migration, die das Realm aufbaut, und die
Laufzeitwerte des Orchestrators (Abgleich der Konten, JWKS für die Peer-Auth, OIDC-Aussteller).
Client-Secrets gibt es keine: Der Orchestrator weist sich bei Keycloak mit einer signierten Assertion
aus (`private_key_jwt`), auch für die Migration als `orchestrator-migration` im Master-Realm.

`KEYCLOAK_SETUP_VARIANT` wählt die Variante:

- **`host`**: Orchestrator per `./gradlew bootRunKc` auf dem Rechner, nur Keycloak im Container.
- **`compose`**: beide im Compose-Netz; `compose.yml` setzt das selbst.
- **`openshift`**: der Prototyp in `openshift/identity-demo.yaml`, ein Pod mit zwei Containern, die sich
  das Netz teilen. Aufbau und Ausrollen beschreibt [openshift/README.md](../openshift/README.md).

Alle Varianten stehen in `application-keycloak.yml` unter `keycloak-setup.variants`, die gemeinsamen
Werte unter `keycloak-setup.base`. Jede Variante nennt nur, worin sie abweicht. Eine weitere Umgebung
ist ein Eintrag dort, keine Codeänderung. Die Werte für die Keycloak-Seite landen als Konfiguration
der Komponente `orchestrator` im Realm (Admin-Konsole unter „User federation“); dort liest die
Erweiterung sie zur Laufzeit.

**Achtung:** Ändert sich ein Wert, den ein bereits angewendeter Migrationsschritt benutzt hat, baut
der `MigrationRunner` das Realm neu auf, genau wie bei einer geänderten Migrationsdatei. Die Nutzer
in Keycloak entstehen beim nächsten Abgleich oder Login neu; die Datenbank des Orchestrators bleibt
unberührt.

---

## 7) Tests

Backend und Keycloak-Erweiterung:

```bash
./gradlew test                        # Orchestrator: Unit-, Integrations- und Architekturtests
./gradlew quickTest                   # dasselbe ohne die Specs, die einen Spring-Kontext starten
./gradlew :keycloak-extension:test    # Keycloak-Erweiterung
./gradlew test -PstrictTexts          # zusätzlich: jede Sprache vollständig übersetzt (ADR-33)
./gradlew build                       # alles bauen und alle Tests ausführen
```

`quickTest` ist der Zwischenstand beim Entwickeln: Jeder Spec mit `@SpringBootTest` (auch geerbt
über `IntegrationTestSupport`) wird als ignoriert gemeldet, alles andere läuft. Das Kriterium ist
abgeleitet, kein Spec trägt eine Markierung (`io.kotest.provided.TestTier`). Vor einem Commit
zählt der volle Lauf `test`.

**Wo die Zeit hingeht.** Der volle Lauf dauert rund zwei Minuten. Die Integrationstests sind dabei
nicht der Treiber: Nach dem Start des Spring-Kontexts braucht eine Integrationstest-Klasse etwa eine
Sekunde. Die Zeit geht in die wenigen großen Specs (`ModelBasedJourneyTest`,
`IdentityApplicationTests` mit dem ersten Kontextstart, `OpenApiSnapshotTest`) und in die
Kontextstarts, einen je `@MockkBean`-Kombination. Parallele Test-JVMs (`maxParallelForks`) helfen
kaum, weil jede ihre Kontexte selbst startet: Zwei sparen wenige Sekunden, vier sind langsamer.

Welche Art von Test man schreibt, entscheidet deshalb nicht die Laufzeit, sondern was er prüft: Eine
Regel prüft ein Unit-Test, in `domain` oder am Handler. Ein Integrationstest prüft, was nur mit
Datenbank, Transaktion oder HTTP sichtbar wird, und je Ablauf einmal, nicht je Variante.

Ohne `-PstrictTexts` ist eine fehlende Übersetzung nur eine Warnung, und die Oberfläche zeigt so
lange die Vorlage aus dem Code. Für einen Build außerhalb des Demomodus gehört die Option dazu.

Frontend (im Verzeichnis `frontend`):

```bash
npm test                      # Komponententests mit Vitest
npm run lint                  # oxlint
npx tsc -b                    # Typprüfung (Vitest prüft keine Typen)
npm run test:e2e              # Playwright gegen das echte Backend
npm run test:e2e:keycloak     # Playwright gegen die Login-Seiten von Keycloak
```

- **`test:e2e`** startet selbst einen Orchestrator per `./gradlew bootRun` auf Port 8091 mit einer
  frischen In-Memory-Datenbank. Ein auf Port 8080 laufender Orchestrator stört also nicht.
- **`test:e2e:keycloak`** startet keinen Server. Vorher muss der ganze Stack laufen
  (`podman compose up -d`). Andere Adressen lassen sich über `ORCHESTRATOR_URL`, `KEYCLOAK_URL`,
  `ADMIN_USER` und `ADMIN_PASSWORD` setzen. Diese Suite läuft nicht in der CI. Sie setzt die Demo
  zu Beginn zurück und registriert das Konto, mit dem sie sich anmeldet, selbst über die Website.
  `vorgangszugang.spec.ts` prüft die Anmeldung mit Einmalkennwort in beiden Themes: Einladung beim
  Personenverzeichnis ausstellen, anmelden, Vorgangs-Marker im Token, „Vorgang beenden“ beendet die
  Sitzung, das Kennwort ist danach verbraucht, die Demo-Auswahl der Einladungen und die Abweisung
  einer Einladung unter dem verlangten Niveau
  ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)).

Beim ersten Mal braucht Playwright seinen Browser: `npx playwright install chromium`.

## 8) Das Demo-Video neu aufnehmen

`docs/media/demo.mp4` (Git LFS) entsteht aus `frontend/e2e-video/demo.spec.ts`: ein Playwright-Lauf,
der die Aufgaben der Willkommensseite im Browser durchspielt und Titelkarten und Untertitel als
Teil der Seite einblendet. Das Skript `frontend/record-demo-video.sh` setzt den Compose-Stack mit
leeren Volumes neu auf und nimmt auf (`playwright.video.config.ts`, 1600×1100, verlangsamt).
`frontend/cut-demo-video.mjs` schneidet danach mit ffmpeg: Der Spec markiert jeden Seitenwechsel
als verborgen, und diese Stellen fallen heraus, sodass nie eine halb geladene Seite zu sehen ist.
Der App-Tab erscheint als Bild im Bild über der Website-Anmeldung. Es braucht Podman, den
Playwright-Browser und ffmpeg und dauert etwa 15 Minuten.

```bash
cd frontend && ./record-demo-video.sh
```

Die Texte der Karten und Untertitel stehen im Spec. Sie beschreiben nur, was die Doku belegt
([01-ueberblick.md](01-ueberblick.md), [ADR-38](adr/ADR-038-keycloak-liest-konten.md)).

## 9) Die Doku als Website

Die Doku unter `docs/` ist auch als Website mit Inhaltsverzeichnis, Suche, gezeichneten Diagrammen
und abspielbarem Demo-Video veröffentlicht: <https://rpreissel.github.io/identity-demo/>. Die
Website liest die Markdown-Dateien unverändert; alles, was nur sie braucht, steht in `site/`
(VitePress). Deshalb gilt für die Doku weiter nur, was auch auf GitHub funktioniert: relative
`.md`-Links, keine Front Matter, Mermaid in ```` ```mermaid ````-Blöcken.

- Das Inhaltsverzeichnis links ist [README.md](README.md): Jede `## `-Überschrift wird eine Gruppe,
  jeder fett gesetzte Link darunter ein Eintrag. Eine neue Datei erscheint erst, wenn sie dort
  verlinkt ist.
- Jeder Unterordner hat eine `README.md`, die GitHub beim Blättern und die Website als Startseite
  des Ordners zeigt. Der Build bricht ab, wenn eine Datei darin nicht verlinkt ist (für `adr/`
  gilt [12-entscheidungen.md](12-entscheidungen.md) als Liste). Er bricht auch bei toten Links ab.
- Links, die aus `docs/` herausführen (etwa in den Code), zeigen auf der Website auf die Datei
  auf GitHub. Ebenso Links auf Dateien, die bewusst nicht im Buch stehen
  ([00-agent-quickstart.md](00-agent-quickstart.md); Liste `excluded` in
  `site/.vitepress/config.mts`).
- Ein Absatz, der nur aus einem Link auf eine `.mp4`-Datei besteht, wird auf der Website ein
  Player; auf GitHub bleibt er ein Link.

Lokal ansehen (Node 22; für das Video vorher `git lfs pull`):

```bash
cd site && npm ci && npm run dev
```

Veröffentlicht wird bei jedem Push auf `main`, der `docs/` oder `site/` ändert
(`.github/workflows/docs.yml`); ein Pull Request baut die Website nur, damit tote Links auffallen.
