# ADR-42: Ein Browser-Client, der Orchestrator schaltet die Anmeldung auf `loa1` um

**Status:** umgesetzt 2026-09.

**Entscheidung.** Der Web-Kanal hat genau einen Browser-Client. Was er auf `loa1` abfragt, schaltet
der Orchestrator zur Laufzeit um, nach demselben Verfahren wie das Login-Theme
([ADR-41](ADR-041-keycloakify-neben-freemarker.md)): entweder die Verfahrensauswahl des
Orchestrators (Standard), darunter die Anmeldung per QR-Code ohne bekanntes Konto
(`auth-qr-lookup`), oder Keycloaks eigenes Passwortformular. Der Schalter gilt realmweit.

**Warum die Verfahrensauswahl der Standard ist.** Sie zeigt schon auf der ersten Seite, was die
Demo ausmacht: alle Verfahren des Orchestrators und den Weg zum Registrieren. Das Passwortformular
ist die Ausnahme, die man zum Vergleich einschaltet.

**Erwogene Alternativen.**

- **Zwei Clients mit je eigenem Flow-Baum.** So war es vorher: ein zweiter Client nur für die Demo,
  gebunden an eine Kopie von `orchestrator-browser`, die sich nur im `loa1`-Zweig unterschied. Die
  Webseite wählte den Client und musste ihn sich für Step-up, Refresh und Abmelden merken. Kopie und
  zweiter Client mussten mit dem Haupt-Client Schritt halten (Redirect-URIs, Scopes, Flow-Aufbau).
- **Einen Client, aber zwischen zwei Flow-Bäumen umbinden** (`authenticationFlowBindingOverrides`).
  Das Umschalten wäre ein einziger, atomarer Schreibzugriff. Die Kopie des ganzen Flow-Baums
  (14 Migrationsschritte) bliebe aber im Realm. Gewählt ist die Variante mit weniger
  Realm-Konfiguration: ein zusätzlicher Schritt statt eines zweiten Baums.

**Preis.** Das Umschalten ist nicht atomar: Es ändert drei Executions nacheinander. Und es gilt für
alle Besucher gleichzeitig, auch für eine Anmeldung, die gerade läuft. Ein Tab kann nicht mehr den
einen und ein zweiter Tab den anderen Ablauf zeigen.

## 1) Ein Flow, zwei Belegungen von `loa1`

- **Der Subflow `orchestrator-loa-1`** in `orchestrator-browser` enthält beide Belegungen
  (keycloak-migrations, `V1__realm.kc.kts`, Abschnitt V2):
  - `orchestrator-authenticator` mit `targetAcr=loa1`, im Standard `REQUIRED`
  - `auth-username-password-form` und `orchestrator-update-authenticator` (meldet das Passwort an den
    Orchestrator), im Standard `DISABLED`
- **Die Migration legt den Standard an.** Ein frisch aufgebautes Realm steht damit schon so, wie es
  ohne Eintrag im Orchestrator stehen soll. `KeycloakLoa1LoginTest` prüft die Requirements der
  Migration gegen den Stand des Schalters ohne Flag.
- **Umgeschaltet wird über die Requirements** dieser drei Executions, mit der Admin-API von Keycloak
  (`PUT /admin/realms/{realm}/authentication/flows/orchestrator-loa-1/executions`).
- **Erst einschalten, dann ausschalten:** Zwischen den Schreibzugriffen laufen kurz beide Belegungen,
  nie keine. Ein `loa1` ohne Authenticator gibt es damit nicht.
- **Keine neuen Rechte, kein neuer Code in der Extension:** Der Orchestrator schreibt als
  Migrations-Client `orchestrator-migration`, wie beim Login-Theme.
- **Der Step-up braucht nichts:** Er läuft über denselben Client und `loa2`, gleich wie `loa1`
  belegt ist.

## 2) Der Schalter im Orchestrator

- **Quelle der Wahrheit** ist das Feature-Flag `FeatureFlags.KEYCLOAK_LOA1_PASSWORD`
  (`Loa1LoginSwitch`, Keycloak-Seite in `KeycloakLoa1Login`). Es ist nach der Ausnahme benannt:
  Ohne Zeile gilt die Verfahrensauswahl, derselbe Stand, den die Migration anlegt.
- **Endpunkte:** `$ADMIN_API/loa1-login` mit GET und PUT, hinter dem Admin-Login, und dieselben ohne
  Login unter `$DEMO_API/loa1-login` für die Demo-Spalte der Webseite (`@DemoSurface`, wie der
  Theme-Schalter). PUT setzt zuerst Keycloak und erst dann das Flag. Lehnt Keycloak ab, bleibt das
  Flag, wie es war.
- **Abgleich beim Start:** Nach den Keycloak-Migrationen setzt der Orchestrator den Subflow einmal auf
  den Stand des Flags. Ein neu aufgebautes Realm verliert so nicht still ein gewähltes Passwortformular.
- **Demo zurücksetzen** schaltet auf die Verfahrensauswahl zurück. Die Server-Info meldet die aktive
  Belegung (`keycloak.loa1Login`).

Welche Verfahren auf `loa1` zählen, entscheidet in beiden Belegungen die Policy des Orchestrators.
Der Schalter bestimmt nur, wer fragt.
