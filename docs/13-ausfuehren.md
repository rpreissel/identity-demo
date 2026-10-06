# Ausführen und bauen

Dieses Kapitel beschreibt, wie Sie die Demo auf dem eigenen Rechner starten, bauen und testen. Es
gibt dafür mehrere Wege: mit und ohne Keycloak, in Podman-Containern oder direkt auf dem Rechner.

Was für den Betrieb außerhalb der Demo gilt, steht in [07-betrieb.md](07-betrieb.md). Dazu gehören
der Fehlervertrag, die Aufbewahrung, die Pflichtwerte außerhalb des Demomodus und die Kennzahlen.
Die Modulstruktur und die Prüfungen im Build beschreibt [08-projektrahmen.md](08-projektrahmen.md)
in Abschnitt 7.

---

## 1) Voraussetzungen

- **JDK 21** und **Node.js 22 mit npm** auf dem Rechner. Das sind dieselben Versionen wie in der CI.
  Gradle ruft npm selbst auf, um das Frontend und das Keycloak-Theme zu bauen.
- **Podman** mit `podman compose`. Das brauchen Sie nur für die Varianten mit Keycloak. Auf macOS
  läuft Podman in einer virtuellen Maschine (Podman Machine). Diese VM bindet nur das Verzeichnis
  `/Users` ein. Bind-Mounts aus `/tmp` funktionieren dort deshalb nicht. `compose.yml` nutzt aus
  diesem Grund benannte Volumes (`keycloak-data`, `orchestrator-data`). Bleiben Sie auch bei eigenen
  Erweiterungen dabei.
- An einem Arbeitsplatz ohne Zugang zu `services.gradle.org` führen Sie einmal `./init-local` aus und
  geben das Passwort ein. Das Skript tut Folgendes:
  - Es entschlüsselt die Adressen des internen Spiegelservers (`gradle/local-mirror.enc`).
  - Es stellt die Gradle-Distribution in `gradle/wrapper/gradle-wrapper.properties` auf diesen
    Spiegel um und nimmt die Datei aus der Versionsverwaltung durch Git heraus.
  - Es legt den Maven-Spiegel als Init-Skript unter `~/.gradle/init.d/` ab.

  Der Build im Repository bleibt dabei unverändert. `./init-local remove` macht beides rückgängig.

---

## 2) Drei Arten zu starten

Welche Art Sie wählen, hängt davon ab, ob Sie den Web-Kanal brauchen. Der Web-Kanal ist die
Anmeldung im Browser über Keycloak. Der App-Kanal funktioniert auch ohne Keycloak.

### Ohne Keycloak (am schnellsten)

```bash
./gradlew bootRun
```

Dieser Befehl startet den Orchestrator im Standardprofil auf Port 8080. Podman ist nicht nötig. Es
gibt dann den App-Kanal. Im Demomodus (`DEMO_MODE`, Standard `true`) gibt es außerdem das
Personenverzeichnis, die Nect-Simulation und die Admin-Seite. Der Web-Kanal ist abgeschaltet: `/web/`
zeigt einen Hinweis, und die Kachel auf der Willkommensseite ist ausgeschaltet.

### Alles in Containern

```bash
./gradlew build
podman compose up --build
```

Das startet zwei Container:

- **`keycloak`**: ein echtes Keycloak mit der Erweiterung und beiden Login-Themes. Es ist nur über
  HTTPS erreichbar, auf Port 8543. Beim ersten Start erzeugt es ein selbstsigniertes Zertifikat im
  Volume `keycloak-data`. Das Zertifikat bleibt erhalten, auch wenn das Image neu gebaut wird. Sie
  müssen ihm im Browser also nur einmal vertrauen.
- **`orchestrator`**: Port 8080, mit dem Profil `keycloak` und der H2-Datenbankdatei im Volume
  `orchestrator-data`. Der Orchestrator wartet selbst, bis Keycloak antwortet. Dann wendet er alle
  Migrationen aus dem Modul `keycloak-migrations` an (`KeycloakMigrationRunnerStartup`). Dadurch
  entsteht das Realm, also der Bereich in Keycloak mit den Nutzern, Clients und Einstellungen dieser
  Anwendung. Keycloak erreicht der Orchestrator intern unter `https://keycloak:8443`.

`podman compose up` ist bewusst ein eigener Schritt. Gradle ruft Podman nie auf. So hängt ein
normaler Build nicht davon ab, dass eine Podman Machine läuft.

### Keycloak im Container, Orchestrator auf dem Rechner

Diese Variante ist beim Entwickeln schneller, weil nach einer Änderung nur der Orchestrator neu
startet:

```bash
podman compose up keycloak
./gradlew bootRunKc
```

`bootRunKc` ist `bootRun` mit dem Profil `keycloak` (`application-keycloak.yml`). Der Orchestrator
spricht Keycloak dann unter `https://localhost:8543` an. Ein einfaches `bootRun` bliebe im
Standardprofil und würde das Keycloak aus Compose gar nicht nutzen. Anders als im Container
funktioniert hier, wie bei `bootRun`, auch die H2-Konsole. Die Begründung steht in Abschnitt 3,
„H2-Konsole: nur beim Host-Start“.

### Frontend mit Neuladen beim Speichern

```bash
cd frontend
npm install
npm run dev
```

Das startet Vite auf Port 5173. Vite lädt die Seite im Browser neu, sobald Sie eine Datei speichern.
Anfragen an `/orchestrator`, `/mock-personenverzeichnis`, `/mock-nect`, `/mock-kobil`, `/mock-sms`
und `/mock-mail` leitet Vite an den Orchestrator auf Port 8080 weiter. Der Orchestrator muss also
gleichzeitig laufen.

---

## 3) Adressen und Zugänge

- **Willkommensseite**: <http://localhost:8080/>. Von dort führen Kacheln zu allen anderen Seiten.
- **App-Kanal**: <http://localhost:8080/app/>
- **Web-Kanal**: <http://localhost:8080/web/> (nur mit Keycloak)
- **Admin-Seite**: <http://localhost:8080/admin/>, Anmeldung mit `admin` / `admin` (`demo.admin` in
  `application.yml`)
- **Personenverzeichnis** (simuliertes Fremdsystem): <http://localhost:8080/personenverzeichnis/>
- **Briefkasten** (Briefe, SMS und E-Mails an Testpersonen, nur im Demomodus):
  <http://localhost:8080/briefkasten/>
- **Schlüsseldienst** (simuliertes KMS, nur im Demomodus, [ADR-54](adr/ADR-054-schluesseldienst-simuliert.md)):
  <http://localhost:8080/mock-kms/keys> zeigt Schlüssel und Versionen; rotieren mit
  `POST /mock-kms/keys/{name}/rotation`, zurückziehen mit `POST /mock-kms/keys/{name}/retirement?below=N`.
- **H2-Konsole**: <http://localhost:8080/h2-console>, nur beim Start direkt auf dem Rechner (siehe
  unten, „H2-Konsole: nur beim Host-Start“). Die Startseite verlinkt sie im Server-Status, im
  Abschnitt „Entwickler-Werkzeuge“. Dort stehen auch die Zugangsdaten.
- **API-Doku** (Swagger UI, nur im Demomodus): <http://localhost:8080/swagger-ui/index.html>, ebenfalls
  im Server-Status verlinkt. Sie dient nur zum Nachlesen. Aufrufe direkt aus der Swagger UI
  funktionieren nicht, weil sie einen DPoP-Nachweis brauchen.
- **Health und Kennzahlen**: eigener Port 9080 (`MANAGEMENT_PORT`), siehe
  [07-betrieb.md](07-betrieb.md) Abschnitt 7
- **Keycloak**: <https://localhost:8543>. Für die Admin-Konsole gilt `admin` / `admin`
  (`KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`). Der Orchestrator selbst braucht diesen Zugang
  nicht.

Beim Start auf dem Rechner liegt die Datenbank unter `./data/identitydb`, im Container im Volume
`orchestrator-data`. Testpersonen und gültige Freischaltcodes spielt eine Flyway-Migration beim
Start ein. Konten legt sie nicht an. Jede Testperson registriert sich selbst, in der App oder auf der
Website.

### H2-Konsole: nur beim Host-Start

Die H2-Konsole ist eine Weboberfläche, mit der man direkt in die Datenbank sehen und sie ändern kann.
Sie ist unter `/h2-console` bewusst eingeschaltet. Die Einstellung `web-allow-others` bleibt aber
`false`. Die Begründung steht als Kommentar in `application.yml`.

Spring Security schützt nur `/orchestrator/admin/**` (`AdminSecurityConfig`). Der Pfad der Konsole
bleibt offen. Ihn schützt deshalb allein die Prüfung von H2, ob die Anfrage vom eigenen Rechner
kommt. Hinter der Konsole liegen Passwort-Hashes, Geräteschlüssel und alle Sitzungen.

Diese Prüfung vergleicht die Absenderadresse der Anfrage. Bei `./gradlew bootRun` ist das
`127.0.0.1`, und die Konsole funktioniert. **Im Container (`compose.yml`) funktioniert sie nicht.**
Dort erreicht die Anfrage den Orchestrator über die Portweiterleitung `8080:8080`, und zwar mit der
Adresse des Container-Netzes. Für H2 ist das eine Verbindung von außen. H2 lehnt sie ab mit der
Meldung *„remote connections ('webAllowOthers') are disabled on this server“*. Der Schutz wirkt also
wie vorgesehen.

**Auf OpenShift ist die Konsole ausgeschaltet** (`SPRING_H2_CONSOLE_ENABLED=false` im Manifest). Dort
erkennt Spring Boot die Plattform und wertet den Header `X-Forwarded-For` aus. Tomcat vertraut dabei
jedem privaten Netz als Proxy. Ein Client aus einem privaten Netz könnte sich so als `127.0.0.1`
ausgeben, und die Prüfung von H2 würde ihn durchlassen.

Wer in die Datenbank sehen will, startet deshalb den Orchestrator direkt auf dem Rechner und lässt
nur Keycloak über Compose laufen. Die Variante `host` (Voreinstellung von `KEYCLOAK_SETUP_VARIANT`)
richtet Keycloak dafür bereits auf `host.containers.internal:8080` aus. Wer die Daten eines Laufs im
Container braucht, kopiert die Datei aus dem gestoppten Volume `orchestrator-data` heraus.

`web-allow-others` einzuschalten kommt nicht in Frage. Der Port ist auf dem Rechner nach außen
freigegeben. Jeder, der ihn erreicht, bekäme dann vollen Lese- und Schreibzugriff auf die Datenbank.

---

## 4) Wie der Container-Build funktioniert

Die Dockerfiles bauen nichts selbst. Stattdessen baut der Gradle-Task `stagePodmanArtifacts` auf dem
Rechner diese Teile:

- das Jar des Orchestrators,
- das Frontend,
- das Jar der Keycloak-Erweiterung,
- das Keycloak-Theme.

Der Task legt sie zusammen mit den Dockerfiles unter `build/podman/orchestrator` und
`build/podman/keycloak` ab. `compose.yml` nennt diese Verzeichnisse als Build-Kontext, nicht die
Wurzel des Repositorys. Podman liest also nur die fertigen Artefakte und nicht `.git`,
`node_modules` oder die Caches von Gradle.

Der Task ist eine Abhängigkeit von `assemble` und läuft deshalb bei jedem `./gradlew build` mit.
Einzeln brauchen Sie ihn nur, wenn Sie ausschließlich die Artefakte erzeugen wollen:

```bash
./gradlew stagePodmanArtifacts
```

Fehlen die Artefakte, bricht der `COPY`-Schritt im Image-Build mit „not found“ ab. Er verwendet also
nicht unbemerkt ein altes Artefakt.

Das Image des Orchestrators wechselt kurz zu `USER root` und legt einen eigenen Nutzer `identity`
an. Das Keycloak-Image bringt seinen Nutzer `keycloak` schon mit. Zum Anlegen nimmt das Dockerfile
`groupadd`/`useradd` (UBI) oder `addgroup`/`adduser` (Alpine), je nachdem, was das Basis-Image
mitbringt.

---

## 5) Basis-Images einstellen

Ein Basis-Image ist das Image, auf dem ein eigenes Image aufbaut. Der Grundsatz lautet: Images von
Red Hat, wo immer es geht, auch zu Hause. Standardmäßig kommen die Laufzeit-Images von
`registry.access.redhat.com` (UBI). Dafür braucht man weder eine Anmeldung noch ein Red-Hat-Konto.
Nur Keycloak selbst kommt vom öffentlichen `quay.io/keycloak/keycloak`, weil es dafür kein freies
Image von Red Hat gibt.

Beide Basis-Images lassen sich ändern, ohne die Dockerfiles anzufassen (`ARG` im Dockerfile,
`build.args` in `compose.yml`). Podman Compose liest die Werte aus einer Datei `.env` im
Projektverzeichnis. Diese Datei steht nicht im Repository. Für den Arbeitsplatz gibt es eine Vorlage:

```bash
cp .env.work.example .env
```

Podman Compose liest von sich aus nur `.env`, nicht `.env.local`. Eine andere Datei liest es nur mit
`--env-file`. Ohne `.env` gelten die Standardwerte. Zu Hause brauchen Sie also keine.

Die Variablen:

- **`KEYCLOAK_BASE_IMAGE`**: Laufzeit-Image von Keycloak in `keycloak-extension/Dockerfile`.
  Standard `quay.io/keycloak/keycloak:26.6.4`.
- **`ORCHESTRATOR_RUNTIME_BASE_IMAGE`**: Laufzeit-Image in `Dockerfile`. Standard
  `registry.access.redhat.com/ubi9/openjdk-21-runtime:latest`.
- **`KEYCLOAK_SETUP_VARIANT`**: welche Keycloak-Umgebung aufgebaut und angesprochen wird (Abschnitt
  6). Standard `host`. `compose.yml` setzt den Wert fest auf `compose`. Ein Wert in `.env` wirkt dort
  deshalb nicht, sondern nur als Umgebungsvariable beim Start auf dem Rechner.
- **`KEYCLOAK_ADMIN`** / **`KEYCLOAK_ADMIN_PASSWORD`**: der erste Admin von Keycloak, nur für die
  Admin-Konsole. Standard `admin` / `admin`.
- **`ORCHESTRATOR_CLIENT_JWKS_URL`**: wo Keycloak den öffentlichen Schlüssel des Orchestrators für
  den Client `orchestrator-migration` abholt. Standard
  `http://host.containers.internal:8080/orchestrator/api/v1/kc/client-jwks/orchestrator-migration/.well-known/jwks.json`.
  Derselbe Wert passt für beide Varianten mit Keycloak, weil der Orchestrator in beiden Fällen auf
  Port 8080 des Rechners erreichbar ist.

Beispiel einer `.env` für eine Firmenumgebung mit Red-Hat-Abonnement. Sie verwendet den Red Hat
build of Keycloak statt des Community-Keycloak:

```
KEYCLOAK_BASE_IMAGE=registry.redhat.io/rhbk/keycloak-rhel9:26.6
ORCHESTRATOR_RUNTIME_BASE_IMAGE=registry.redhat.io/ubi9/openjdk-21-runtime:latest
```

Dafür müssen Sie sich vorher mit `podman login registry.redhat.io` und gültigen Zugangsdaten
anmelden. Ohne Anmeldung scheitert der Download mit 403. Der Community-Keycloak des Projekts folgt
dem Strom des Red Hat build of Keycloak (derzeit 26.6): Upstream endet dieser Strom bei 26.6.4, Red
Hat zählt seine Patches darüber hinaus selbst (26.6.5 und höher).

Für den Gradle-Lauf auf dem Rechner gelten diese Variablen nicht. Dort hilft bei Bedarf
`./init-local` (Abschnitt 1).

---

## 6) Keycloak-Umgebung: eine Variante statt einzelner Werte

Orchestrator und Keycloak müssen gegenseitig ihre Adressen und weitere Einstellungen kennen. Damit
diese Werte immer zusammenpassen, stehen sie gemeinsam in einem benannten Satz (`KeycloakSetup`).
Dazu gehören Realm-Name, Client-IDs, Redirect-URIs und die Adressen beider Seiten. Aus diesem Satz
werden zwei Dinge abgeleitet:

- die Migration, die das Realm aufbaut,
- die Laufzeitwerte des Orchestrators: Abgleich der Konten, JWKS für die Peer-Auth und
  OIDC-Aussteller.

Client-Secrets gibt es keine. Der Orchestrator weist sich bei Keycloak mit einer signierten
Assertion aus (`private_key_jwt`), also mit einem Nachweis, den er mit seinem privaten Schlüssel
signiert. Das gilt auch für die Migration, bei der er als `orchestrator-migration` im Master-Realm
auftritt.

`KEYCLOAK_SETUP_VARIANT` wählt die Variante:

- **`host`**: Der Orchestrator läuft per `./gradlew bootRunKc` auf dem Rechner, nur Keycloak im
  Container.
- **`compose`**: Beide laufen im Compose-Netz. `compose.yml` setzt diesen Wert selbst.
- **`openshift`**: der Prototyp in `openshift/identity-demo.yaml`, ein Pod mit zwei Containern, die
  sich das Netz teilen. Aufbau und Ausrollen beschreibt [openshift/README.md](../openshift/README.md).

Alle Varianten stehen in `application-keycloak.yml` unter `keycloak-setup.variants`, die gemeinsamen
Werte unter `keycloak-setup.base`. Jede Variante nennt nur die Werte, in denen sie abweicht. Eine
weitere Umgebung ist dort ein neuer Eintrag und keine Codeänderung. Die Werte für die Keycloak-Seite
werden als Konfiguration der Komponente `orchestrator` im Realm gespeichert (in der Admin-Konsole
unter „User federation“). Dort liest die Erweiterung sie zur Laufzeit.

**Achtung:** Ändert sich ein Wert, den ein bereits angewendeter Migrationsschritt benutzt hat, baut
der `MigrationRunner` das Realm neu auf. Das ist genauso wie bei einer geänderten Migrationsdatei.
Die Nutzer in Keycloak entstehen beim nächsten Abgleich oder Login neu. Die Datenbank des
Orchestrators bleibt unberührt.

---

## 7) Tests

Backend und Keycloak-Erweiterung:

```bash
./gradlew test                        # Orchestrator: Unit-, Integrations- und Architekturtests
./gradlew quickTest                   # dasselbe ohne die Specs, die einen Spring-Kontext starten
./gradlew :keycloak-extension:test    # Keycloak-Erweiterung
./gradlew test -PstrictTexts          # zusätzlich: jede Sprache vollständig übersetzt (ADR-33)
./gradlew :test --tests '*ModelBasedJourneyTest' -PmodelSeeds=1000   # gründliche Zufallssuche
./gradlew build                       # alles bauen und alle Tests ausführen
```

`quickTest` ist für einen schnellen Zwischenstand beim Entwickeln gedacht. Jeder Spec mit
`@SpringBootTest` wird dabei als ignoriert gemeldet, auch wenn er die Annotation über
`IntegrationTestSupport` erbt. Alles andere läuft. Welche Specs ausgelassen werden, leitet der Build
selbst ab. Kein Spec hat dafür eine Markierung (`io.kotest.provided.TestTier`). Vor einem Commit
zählt der volle Lauf `test`.

`ModelBasedJourneyTest` erzeugt zufällige Folgen von Aktionen und prüft, ob das System sich dabei
korrekt verhält. Im vollen Lauf fährt er 50 Zufallsfolgen, immer dieselben. Dazu kommt jede Folge,
die einmal einen Fehler gefunden hat. Wer an Kanal, Journey oder Sitzung arbeitet, sucht mit
`-PmodelSeeds=1000` gründlicher. Eine dabei gefundene Folge wird danach zu den festen Folgen
hinzugefügt.

**Wofür die Laufzeit gebraucht wird.** Der volle Lauf dauert knapp zwei Minuten. Die meiste Zeit
brauchen die wenigen großen Specs (`OpenApiSnapshotTest`, `ModelBasedJourneyTest`), das Starten der
Spring-Kontexte und die Szenarien der Integrationstests.

- **Starten der Kontexte.** Spring startet einen eigenen Kontext je Konfiguration. Deshalb erben die
  Specs von `SharedSpringContext`, damit sie sich einen Kontext teilen. Beans, die ein Spec durch
  eine Testattrappe ersetzt, stehen dort als Spy. `SharedSpringContextTest` prüft, dass das so
  bleibt. Einen eigenen Kontext haben nur Specs, die eigene Beans importieren.
- **Szenarien.** Die Datenbank wird vor jedem `when` geleert. Jedes Szenario baut seinen
  Ausgangszustand also neu auf.
- **Parallele Test-JVMs** (`maxParallelForks`) helfen kaum, weil jede JVM ihre Kontexte selbst
  startet. Zwei parallele JVMs sparen wenige Sekunden, vier sind langsamer.

Welche Art von Test man schreibt, entscheidet deshalb nicht die Laufzeit, sondern was der Test
prüft. Eine Regel prüft ein Unit-Test, in `domain` oder am Handler. Ein Integrationstest prüft, was
nur mit Datenbank, Transaktion oder HTTP sichtbar wird. Und er prüft jeden Ablauf einmal, nicht jede
Variante.

Ohne `-PstrictTexts` ist eine fehlende Übersetzung nur eine Warnung. Die Oberfläche zeigt dann so
lange die deutsche Vorlage aus dem Code. Für einen Build außerhalb des Demomodus gehört die Option
dazu.

Frontend (im Verzeichnis `frontend`):

```bash
npm test                      # Komponententests mit Vitest
npm run lint                  # oxlint
npx tsc -b                    # Typprüfung (Vitest prüft keine Typen)
npm run test:e2e              # Playwright gegen das echte Backend
npm run test:e2e:keycloak     # Playwright gegen die Login-Seiten von Keycloak
```

### `test:e2e`

**`test:e2e`** startet selbst einen Orchestrator per `./gradlew bootRun` auf Port 8095 mit einer
frischen Datenbank im Arbeitsspeicher. Ein Orchestrator auf Port 8080 und der lokale OpenShift-Pod
auf 8090/8091 stören also nicht.

Die Suite prüft nur, was allein im Browser sichtbar wird. Die Logik dahinter decken die
Integrationstests ab. Geprüft werden:

- Registrierung, „Zurück“, Gerät zurücksetzen und Tokens,
- die Rückkehr von Nect in die App (`nect-return.spec.ts`),
- KOBIL mit dem im Browser abgelegten Entsperrgeheimnis (`kobil.spec.ts`),
- das Fortsetzen nach einem Neuladen ohne zweite SMS (`resume.spec.ts`),
- das Ändern der Telefonnummer (`change-method.spec.ts`),
- die erneute Bestätigung vor dem Ändern, wenn der letzte Nachweis zu alt ist
  (`fresh-proof.spec.ts`). Für diesen Spec läuft der Orchestrator der Suite mit einer Frist von zehn
  Sekunden (`identity.policy.self-service-max-age`, gesetzt in `playwright.config.ts`).

### `test:e2e:keycloak`

**`test:e2e:keycloak`** startet keinen Server. Vorher muss der ganze Stack laufen
(`podman compose up -d`). Andere Adressen lassen sich über `ORCHESTRATOR_URL`, `KEYCLOAK_URL`,
`ADMIN_USER` und `ADMIN_PASSWORD` setzen. Für den lokalen OpenShift-Pod sieht das zum Beispiel so
aus: `ORCHESTRATOR_URL=http://localhost:8090 KEYCLOAK_URL=http://localhost:8091`.

Diese Suite läuft nicht in der CI. Sie setzt die Demo zu Beginn zurück. Das Konto, mit dem sie sich
anmeldet, registriert sie selbst über die Website. Die einzelnen Specs:

- `vorgangszugang.spec.ts` prüft die Anmeldung mit Einmalkennwort in beiden Themes
  ([ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)):
  - eine Einladung beim Personenverzeichnis ausstellen,
  - sich anmelden,
  - der Vorgang ist im Token vermerkt,
  - „Vorgang beenden“ beendet die Sitzung,
  - das Kennwort ist danach verbraucht,
  - die Demo-Auswahl der Einladungen,
  - eine Einladung unter dem verlangten Niveau wird abgewiesen.
- `qr-login.spec.ts` folgt der QR-Anmeldung über beide Kanäle. Die Website zeigt den
  Kopplungscode. Die frisch angemeldete App nimmt den Code an und gibt die Anmeldung frei. Der
  Bestätigungscode der App schließt die Anmeldung auf der Website ab.
- `change-method.spec.ts` ändert das Passwort über die Required Action zur Verwaltung der
  Verfahren, in beiden Themes und mit einer einzigen Browser-Sitzung. Eine Required Action ist ein
  Schritt, den Keycloak nach der Anmeldung verlangt.
- `fresh-proof.spec.ts` prüft dort die erneute Bestätigung bei einem zu alten Nachweis. Dieser Spec
  läuft nur gegen einen Stack mit kurzer Frist und wird sonst übersprungen. So starten Sie ihn:
  `SELF_SERVICE_MAX_AGE=PT10S podman compose up -d`, dann
  `SELF_SERVICE_MAX_AGE_SECONDS=10 npm run test:e2e:keycloak`. Starten Sie den Stack danach ohne die
  Variable neu. Sonst bleibt die Frist kurz.

### Uhr der Podman Machine

Auf macOS bleibt die Uhr der VM stehen, während der Rechner schläft. Danach geht sie nach. Der
Orchestrator im Container lehnt dann jeden DPoP-Nachweis der App mit `401` ab, denn der Nachweis
liegt für ihn in der Zukunft. Erlaubt sind 30 Sekunden Abweichung. In der Keycloak-Suite schlägt
dann `qr-login.spec.ts` fehl, weil die App keinen Kanal anlegen kann. Die Login-Seiten selbst
funktionieren weiter. So prüfen Sie die Uhr und stellen sie:
`podman machine ssh date -u` und `podman machine ssh "sudo date -u -s @$(date -u +%s)"`.

Beim ersten Mal braucht Playwright seinen Browser: `npx playwright install chromium`.

## 8) Die Videos neu bauen

Es gibt zwei Videos mit getrennten Aufgaben:

- Das **Erklärvideo** erklärt Konzepte, Stand und Bewertung. Die Demo zeigt es nur in kurzen
  Ausschnitten.
- Das **Demo-Video** zeigt die Bedienung. Für das Warum verweist es auf das Erklärvideo.

Was das eine Video erklärt, wiederholt das andere nicht.

### Das Demo-Video

`docs/media/demo.mp4` (gespeichert mit Git LFS) entsteht aus `frontend/e2e-video/demo.record.ts`.
Das ist ein Playwright-Lauf, der die Aufgaben der Willkommensseite im Browser durchspielt. Titelkarten
und Untertitel blendet er als Teil der Seite ein.

So entsteht das Video:

1. Das Skript `frontend/record-demo-video.sh` setzt den Compose-Stack mit leeren Volumes neu auf und
   nimmt auf (`playwright.video.config.ts`, 1600×1100, verlangsamt).
2. `frontend/cut-demo-video.mjs` schneidet danach mit ffmpeg. Der Rekorder markiert jeden
   Seitenwechsel als verborgen, und diese Stellen werden herausgeschnitten. So ist nie eine halb
   geladene Seite zu sehen.

Der App-Tab erscheint als Bild im Bild über der Anmeldung auf der Website.

Jeder Untertitel wird auch gesprochen, mit derselben Stimme wie im Erklärvideo
(`frontend/e2e-video/narrator.ts`). Der Rekorder lässt die Seite stehen, solange der Satz dauert, und
notiert, wann der Satz gesprochen wurde. Das Schnittskript legt die Sätze dann an die Stellen, an
denen sie nach dem Schnitt liegen.

Die Stimme richtet das Skript beim ersten Lauf selbst ein (`frontend/erklaervideo/setup.sh`). Die
gesprochenen Sätze liegen danach im Cache unter `frontend/erklaervideo/out/narration`. Sie brauchen
Podman, den Browser von Playwright, ffmpeg und `uv`. Die Aufnahme dauert etwa 15 Minuten.

Das Skript baut die Images nicht neu (`podman compose up -d` ohne `--build`). Nach einer
Codeänderung bauen Sie deshalb vorher:

```bash
./gradlew build && podman compose build
cd frontend && ./record-demo-video.sh
```

Die Texte der Karten und Untertitel stehen im Spec. Sie beschreiben nur, was die Doku belegt
([01-ueberblick.md](01-ueberblick.md), [ADR-38](adr/ADR-038-keycloak-liest-konten.md),
[ADR-48](adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)). Die Aufgaben folgen der
Willkommensseite. Ändert sich eine Aufgabe, müssen Willkommensseite und Video beide angepasst werden.

Wie die Stimme Fachbegriffe ausspricht, regelt `frontend/erklaervideo/spoken.mjs` für beide Videos.
Was die Stimme aus einem Wort macht, können Sie an seiner Lautschrift ablesen, ohne hinhören zu
müssen:

```bash
cd frontend/erklaervideo
echo "Kieklohk" | out/piper-venv/bin/python tts_piper.py out/voices/de_DE-thorsten-high.onnx 1.05 --phonemes
```

Englische Wörter liest die Stimme deutsch. Ein `sp` oder `st` am Wortanfang liest sie immer als
„schp“ oder „scht“. Solche Wörter schreibt `spoken.mjs` lautlich um oder lässt sie im gesprochenen
Satz weg.

### Das Erklärvideo

`docs/media/erklaervideo.mp4` (gespeichert mit Git LFS) entsteht aus `frontend/erklaervideo/`. Es
besteht aus:

- animierten Folien als HTML, die Playwright Bild für Bild rendert,
- einer Sprecherstimme aus Piper (ein lokales Programm, das Text in Sprache umwandelt, Stimme
  „thorsten“),
- kurzen Ausschnitten aus `demo.mp4`.

Jedes Element einer Folie erscheint mit dem Satz, der es nennt. `scenes.mjs` enthält dafür je Szene
das Layout und die Sätze. Die Untertitel sind als eigene Untertitelspur im Video enthalten. Sie
brauchen `uv`, Playwright und ffmpeg. Der Bau dauert etwa fünf Minuten:

```bash
cd frontend/erklaervideo
./setup.sh                      # einmalig: Piper und die Stimme nach out/ (etwa 120 MB)
node build.mjs --preview        # ein Standbild je Szene in out/, zum Prüfen des Layouts
node build.mjs                  # das Video
```

Die Zeitmarken der Ausschnitte (`clip.from`, `clip.to`) beziehen sich auf `demo.mp4`. Nach einer
neuen Aufnahme des Demo-Videos müssen Sie sie anpassen. Die Inhalte folgen
[01-ueberblick.md](01-ueberblick.md) und
[14-stand-und-weg-zur-produktion.md](14-stand-und-weg-zur-produktion.md).

## 9) Die Doku als Website

Die Doku unter `docs/` ist auch als Website veröffentlicht: <https://rpreissel.github.io/identity-demo/>.
Die Website bietet ein Inhaltsverzeichnis, eine Suche, gezeichnete Diagramme und das Demo-Video zum
Abspielen. Sie liest die Markdown-Dateien unverändert. Alles, was nur die Website braucht, steht in
`site/` (VitePress). Deshalb darf die Doku weiterhin nur nutzen, was auch auf GitHub funktioniert:
relative `.md`-Links, keine Front Matter, Mermaid in ```` ```mermaid ````-Blöcken.

- Das Inhaltsverzeichnis links ist [README.md](README.md). Jede `## `-Überschrift wird eine Gruppe,
  jeder fett gesetzte Link darunter ein Eintrag. Eine neue Datei erscheint erst, wenn sie dort
  verlinkt ist.
- Jeder Unterordner hat eine `README.md`. GitHub zeigt sie beim Blättern im Ordner an, die Website als
  Startseite des Ordners. Der Build bricht ab, wenn eine Datei des Ordners darin nicht verlinkt ist.
  Für `adr/` gilt [12-entscheidungen.md](12-entscheidungen.md) als diese Liste. Der Build bricht auch
  bei toten Links ab.
- Links, die aus `docs/` herausführen, etwa in den Code, zeigen auf der Website auf die Datei auf
  GitHub. Dasselbe gilt für Links auf Dateien, die bewusst nicht in der Website stehen
  ([00-agent-quickstart.md](00-agent-quickstart.md); Liste `excluded` in
  `site/.vitepress/config.mts`).
- Ein Absatz, der nur aus einem Link auf eine `.mp4`-Datei besteht, wird auf der Website zu einem
  Videoplayer. Auf GitHub bleibt er ein Link.

So sehen Sie die Website lokal an (Node 22; für das Video vorher `git lfs pull`):

```bash
cd site && npm ci && npm run dev
```

Veröffentlicht wird bei jedem Push auf `main`, der `docs/` oder `site/` ändert
(`.github/workflows/docs.yml`). Ein Pull Request baut die Website nur, damit tote Links auffallen.
