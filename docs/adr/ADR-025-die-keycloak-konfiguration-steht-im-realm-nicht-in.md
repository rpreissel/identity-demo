# ADR-25: Die Keycloak-Konfiguration steht im Realm, nicht in der Container-Umgebung

**Status:** umgesetzt.

**Kontext**: **Keycloak** führt auf der Website die Anmeldung und stellt die Tokens aus. Dieses
Projekt erweitert Keycloak um eigenen Code, die **Keycloak-Erweiterung**. Ein **Realm** ist ein
abgeschlossener Bereich in Keycloak mit eigenen Nutzern, Clients und Anmeldeabläufen (siehe
[Glossar](../glossar/glossar.md)). Der Orchestrator, also der Server dieses Projekts, legt das Realm
beim Start mit **Migrationen** an, also mit Schritten, die das Realm nacheinander aufbauen. Dieselben
Einstellungen, etwa der Name des Realms oder die Adresse des Orchestrators, brauchen drei Stellen:
die Migration, die Erweiterung in Keycloak und der laufende Orchestrator. Früher holte jede dieser
Stellen die Werte aus einer anderen Quelle. Außerdem meldete sich der Orchestrator bei Keycloak mit
gemeinsamen Geheimnissen an (Client-Secrets, Admin-Passwort). Diese ADR legt fest, woher die
Einstellungen kommen und wie sich die beiden Seiten ohne gemeinsames Geheimnis ausweisen.

**Entscheidung**: Die Keycloak-Erweiterung liest ihre Einstellungen zur Laufzeit aus den
Konfigurationswerten der Komponente `orchestrator` im Realm (`OrchestratorSettings`). Aus
Umgebungsvariablen des Keycloak-Containers liest sie sie nicht mehr. Fehlt die Komponente oder ein
Wert, scheitert der Aufruf sofort und sichtbar; einen Ersatzwert gibt es nicht.

Dahinter beschreibt **ein** Wertobjekt die ganze Umgebung (`KeycloakSetup`, ausgewählt über
`keycloak-setup.variant`). Aus ihm stammen sowohl die Werte, mit denen die Migration das Realm
aufbaut, als auch die Werte für den laufenden Betrieb des Orchestrators. Zum laufenden Betrieb gehören:

- bis [ADR-38](ADR-038-keycloak-liest-konten.md) der Abgleich der Konten,
- das JWKS (die veröffentlichten öffentlichen Schlüssel) für die Assertion zwischen den Servern,
- die Prüfung der OIDC-Tokens.

**Warum**: Aufbau und Betrieb meinen dasselbe Realm und dieselben Clients. Früher holten Migration
und laufender Betrieb ihre Werte aus getrennten Quellen: `System.getenv` im Migrationsskript,
Umgebungsvariablen am Keycloak-Container und Spring-Properties am Orchestrator. Dann genügte ein
Tippfehler, und die Migration lief gegen Realm A, der Abgleich aber gegen Realm B. Der Fehler zeigte
sich erst später im laufenden Betrieb, als Antwort 401. Zugleich waren die Einstellungen der
Erweiterung im laufenden Keycloak nirgends zu sehen, weder in der Admin-Console noch im Export des
Realms.

**Zwei Hälften, eine Folge**: Das Wertobjekt hat zwei Teile. `RealmSetup` ist das, was ins Realm
geschrieben wird. `KeycloakAccess` beschreibt nur, wie man das fertige Realm erreicht. Ändert sich ein
Wert aus `RealmSetup`, baut der `MigrationRunner` das Realm neu auf. Dasselbe geschieht bei einer
geänderten Migrationsdatei, und zwar aus demselben Grund: Die Schritte, die schon erledigt sind,
verwenden den Wert nie wieder. Ohne Neuaufbau würde das Realm unbemerkt mit dem alten Wert
weiterlaufen. Das Migrationsskript sieht deshalb ausschließlich `RealmSetup`. So kann ein Schritt gar
nicht erst einen Wert verwenden, dessen Änderung keinen Neuaufbau auslöst.

### Kein geteiltes Geheimnis mehr

Zwischen Orchestrator und Keycloak gibt es kein gemeinsames Geheimnis, weder in der Konfiguration noch
in der Compose-Datei noch im Realm. Jede Richtung weist sich mit einer Signatur aus:

- **Orchestrator → Keycloak (Clients im Realm).** Die Clients `orchestrator-admin` und
  `orchestrator-app-token` melden sich per **`private_key_jwt`** an (RFC 7523, die in ADR-9 erwogene
  Härtung). Dabei signiert der Orchestrator jede Anfrage nach einem Token mit dem Schlüssel des
  jeweiligen Clients. Keycloak holt den öffentlichen Teil unter der `jwks.url` des Clients ab
  (`.../kc/client-jwks/{clientId}/.well-known/jwks.json`). **Jeder Client hat seinen eigenen
  Schlüssel.** Bei einem gemeinsamen Schlüssel könnte sich jemand, der den Token-Client ohne Rechte
  fälscht, ebenso als Admin- oder Migrationsclient anmelden.
- **Orchestrator → Keycloak (Migration).** Auch die Migration meldet sich ohne Passwort an. Das
  funktioniert so:
  - Beim Start von Keycloak legt die Erweiterung selbst einen Client `orchestrator-migration` im
    Master-Realm an (`MigrationClientBootstrapFactory`). Das geschieht nach der eigenen
    Datenbankmigration von Keycloak und lässt sich beliebig oft wiederholen.
  - Dieser Client meldet sich mit `private_key_jwt` an. Den öffentlichen Schlüssel dazu holt Keycloak
    über ein eigenes JWKS beim Orchestrator.
  - Der Client hat im Master-Realm nur die Rolle **`create-realm`**, nicht `admin`. Wer ein Realm
    anlegt, bekommt von Keycloak die Verwaltungsrollen dieses Realms. Mit ihnen baut die Migration ihr
    Realm auf und löscht es bei einem Reset.
  - Diese Rechte stehen erst in einem Token, das danach ausgestellt wird. Deshalb holt die Migration
    nach dem Anlegen ein neues Token (`MigrationRunner.onRealmCreated`).
  - Das Master-Realm und fremde Realms kann die Migration nicht verwalten.
  - Beim Umstieg von `admin` trägt die Erweiterung die Verwaltungsrollen der schon vorhandenen Realms
    einmalig nach.
  - Der Orchestrator holt sein Token über `client_credentials` mit Assertion
    (`KeycloakMigrationToken`).
  - Der Admin mit Passwort, den Keycloak beim ersten Start anlegt, bleibt nur für Menschen an der
    Admin-Console.

  *Früher* hatte der Client die Rolle `admin`. Die Begründung war, dass `create-realm` wegen des schon
  ausgestellten Tokens nicht reiche. Dieses Problem löst das neue Token nach dem Anlegen.
- **Keycloak → Orchestrator.** Die Erweiterung weist sich mit einer signierten Assertion aus (siehe
  ADR-7). Das ist das Gegenstück zur ersten Richtung und folgt demselben Prinzip.

Beide Signaturschlüssel liegen **außerhalb des Arbeitsspeichers des Prozesses**. Der Schlüssel des
Orchestrators liegt im Schlüsseldienst ([ADR-54](ADR-054-schluesseldienst-simuliert.md)), der für
ihn signiert; `orchestrator.node_signing_key` gibt es seit ADR-54 nicht mehr. Der Schlüssel der Erweiterung
ist ein Wert der Komponente `orchestrator` und liegt damit in der Datenbank von Keycloak. Vorher
entstand auf jeder Seite bei jedem Start der JVM ein neues Schlüsselpaar. Das reichte nur, solange
genau eine Instanz lief. Eine zweite Instanz hätte mit einem Schlüssel signiert, den das JWKS der
ersten nie nennt. Dass die Schlüssel in der Datenbank im Klartext liegen, ist der Demo-Kompromiss aus
[ADR-22](ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md).

**Die eine Ausnahme vom Realm als Quelle:** Die `jwks.url` des Migrations-Clients steht nicht im
Realm, sondern in der SPI-Konfiguration des Keycloak-Containers
(`KC_SPI_ORCHESTRATOR_BOOTSTRAP__MIGRATION_CLIENT__JWKS_URL`). Denn beim Start gibt es noch kein
Realm, aus dem sie kommen könnte. Die Adresse des Orchestrators steht damit an zwei Stellen: dort und
als `orchestratorBaseUrl` der Variante. Das ist hingenommen, weil ein falscher Wert sofort auffällt.
Schon die erste Anfrage der Migration nach einem Token scheitert beim Start. Und fehlt die Option ganz,
startet Keycloak nicht.

**Kosten**, bewusst getragen:

- Ohne die Komponente `orchestrator` läuft die Erweiterung nicht. Das ist gewollt. Ein Standardwert,
  den das System stillschweigend annimmt, wäre genau die Fehlkonfiguration, die früher erst auffiel,
  wenn sich eine Anmeldung seltsam verhielt.
- Ein geänderter Wert verwirft alles auf der Seite von Keycloak und baut es neu auf. Das ist
  verkraftbar, weil die Datenbank des Orchestrators keine IDs von Keycloak speichert. Damals entstanden
  die Nutzer beim nächsten Abgleich über `orchestratorAccountId` neu. Seit
  [ADR-38](ADR-038-keycloak-liest-konten.md) liest Keycloak die Konten über eine feste Komponenten-Id;
  ein Neuaufbau ändert kein `sub`.
- Keycloak muss den Orchestrator erreichen können, um dessen JWKS zu holen. Diese Verbindung braucht
  die Erweiterung ohnehin für jeden Aufruf (`orchestratorBaseUrl`). Neu ist nur, dass sie an einer
  zweiten Stelle sichtbar wird.
- **Nur interne Adressen:** Wer unter der `jwks.url` des Migrations-Clients antwortet, kann sich Tokens
  als Admin des Master-Realms ausstellen. Diese Adresse darf deshalb nie über einen öffentlich
  erreichbaren Weg laufen.
- `keycloak-admin-client` 26.0.12 kann sich selbst nicht per Assertion anmelden. Deshalb setzt ein
  Filter bei jedem Aufruf das aktuelle Token ein (`buildAdminClient`).

## Geschichte

Zuerst entfielen nur die Client-Secrets im Realm. Die Migration meldete sich damals noch als Admin
des Master-Realms mit Benutzername und Passwort an (`KEYCLOAK_ADMIN`/`KEYCLOAK_ADMIN_PASSWORD` am
Orchestrator). Später ersetzte der Client `orchestrator-migration` auch dieses letzte gemeinsame
Geheimnis.
