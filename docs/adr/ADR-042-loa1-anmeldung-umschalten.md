# ADR-42: Ein Browser-Client, der Orchestrator schaltet die Anmeldung auf `loa1` um

**Status:** umgesetzt 2026-09.

**Worum es geht.** Wer sich auf der Webseite anmeldet, wird zu Keycloak weitergeleitet, dem Anmeldeserver des
Projekts. Keycloak fragt dort zuerst nach einem ersten Nachweis. Damit erreicht der Nutzer die
unterste Stufe des [Sicherheitsniveaus](../glossar/glossar.md), `loa1` („ein Verfahren, etwa ein
Passwort“). Für diese erste Stufe gibt es in der Demo zwei Möglichkeiten:

- die **Verfahrensauswahl des Orchestrators**: Der Orchestrator, also der Server dieses Projekts,
  zeigt alle Anmeldeverfahren zur Auswahl, darunter die Anmeldung per QR-Code ohne bekanntes Konto
  (`auth-qr-lookup`) und den Weg zum Registrieren;
- **Keycloaks eigenes Passwortformular**, das man zum Vergleich zeigen möchte.

Die Frage war, wie die Demo zwischen beiden wechselt. Die Webseite meldet sich bei Keycloak als
sogenannter Client an. Welche Schritte Keycloak dabei abfragt, legt ein Ablaufbaum fest (in Keycloak
„Flow“ genannt). Ein Flow besteht aus einzelnen Schritten („Executions“). Jeder Schritt ist
eingeschaltet (`REQUIRED`) oder ausgeschaltet (`DISABLED`).

**Entscheidung.** Der Web-Kanal hat genau einen Browser-Client. Der Web-Kanal ist die Verbindung, über
die ein Nutzer im Browser mit dem Orchestrator arbeitet. Was dieser Client auf `loa1` abfragt, schaltet
der Orchestrator zur Laufzeit um. Er nutzt dafür dasselbe Verfahren wie beim Umschalten des
Login-Themes, also des Aussehens der Anmeldeseiten ([ADR-41](ADR-041-keycloakify-neben-freemarker.md)).
Zur Wahl stehen die Verfahrensauswahl des Orchestrators (der Standard) und Keycloaks eigenes
Passwortformular. Der Schalter gilt für das ganze Realm, also für alle Nutzer dieser
Keycloak-Installation.

**Warum die Verfahrensauswahl der Standard ist.** Sie zeigt schon auf der ersten Seite, was die
Demo ausmacht: alle Verfahren des Orchestrators und den Weg zum Registrieren. Das Passwortformular
ist die Ausnahme, die man zum Vergleich einschaltet.

**Erwogene Alternativen.**

- **Zwei Clients mit je eigenem Flow-Baum.** So war es vorher. Es gab einen zweiten Client nur für die
  Demo. Er war an eine Kopie von `orchestrator-browser` gebunden, die sich nur im `loa1`-Zweig
  unterschied. Die Webseite wählte den Client und musste ihn sich für Step-up, Refresh und Abmelden
  merken. (Ein Step-up hebt eine bestehende Anmeldung auf ein höheres Niveau; ein Refresh erneuert
  die Tokens.) Bei jeder Änderung am Haupt-Client mussten die Kopie und der zweite Client nachgezogen werden:
  bei Redirect-URIs, Scopes und Flow-Aufbau.
- **Einen Client, aber zwischen zwei Flow-Bäumen umbinden** (`authenticationFlowBindingOverrides`).
  Das Umschalten wäre dann ein einziger, atomarer Schreibzugriff, also ganz oder gar nicht. Die Kopie
  des ganzen Flow-Baums (14 Migrationsschritte) bliebe aber im Realm. Gewählt ist die Variante mit
  weniger Realm-Konfiguration: ein zusätzlicher Schritt statt eines zweiten Baums.

**Preis.** Das Umschalten ist nicht atomar: Es ändert drei Executions nacheinander. Außerdem gilt es
für alle Besucher gleichzeitig, auch für eine Anmeldung, die gerade läuft. Ein Tab kann also nicht
mehr den einen Ablauf zeigen und ein zweiter Tab den anderen.

## 1) Ein Flow, zwei Belegungen von `loa1`

Beide Möglichkeiten stehen fest im selben Flow. Umgeschaltet wird nur, welche davon eingeschaltet ist.

- **Der Subflow `orchestrator-loa-1`** in `orchestrator-browser` enthält beide Belegungen. Er wird
  in keycloak-migrations angelegt (`V1__realm.kc.kts`, Abschnitt V2):
  - `orchestrator-authenticator` mit `targetAcr=loa1`, im Standard `REQUIRED`;
  - `auth-username-password-form` und `orchestrator-update-authenticator`, im Standard `DISABLED`.
    Der zweite Schritt meldet das Passwort an den Orchestrator.
- **Die Migration legt den Standard an.** Ein frisch aufgebautes Realm steht damit schon so, wie es
  ohne Eintrag im Orchestrator stehen soll. `KeycloakLoa1LoginTest` prüft, dass die Einstellungen
  („Requirements“) aus der Migration zum Stand des Schalters ohne gesetztes Flag passen.
- **Umgeschaltet wird über die Requirements** dieser drei Executions. Der Orchestrator nutzt dafür
  die Admin-API von Keycloak
  (`PUT /admin/realms/{realm}/authentication/flows/orchestrator-loa-1/executions`).
- **Erst einschalten, dann ausschalten.** Zwischen den Schreibzugriffen sind kurz beide Belegungen
  aktiv, aber nie keine. Ein `loa1` ganz ohne Authenticator, also ohne prüfenden Schritt, kann so
  nicht entstehen.
- **Keine neuen Rechte, kein neuer Code in der Extension.** Der Orchestrator schreibt als
  Migrations-Client `orchestrator-migration`, genau wie beim Login-Theme. (Die Extension ist der
  Code des Projekts, der in Keycloak läuft.)
- **Der Step-up braucht nichts.** Er läuft über denselben Client und über `loa2`, egal wie `loa1`
  belegt ist.

## 2) Der Schalter im Orchestrator

Der Orchestrator merkt sich, welche Belegung gewählt ist, und sorgt dafür, dass Keycloak dazu passt.

- **Maßgeblich** ist das [Feature-Flag](../glossar/glossar.md)
  `KeycloakFeatureFlags.LOA1_PASSWORD`, also ein Schalter, den der Betreiber zur Laufzeit umstellt
  (`Loa1LoginSwitch`, die Keycloak-Seite steht in `KeycloakLoa1Login`). Das Flag ist nach der
  Ausnahme benannt: Gibt es keine Zeile dafür, gilt die Verfahrensauswahl. Das ist derselbe Stand,
  den die Migration anlegt.
- **Endpunkte.** `$ADMIN_API/loa1-login` gibt es mit GET und PUT hinter dem Admin-Login. Dieselben
  Endpunkte gibt es ohne Login unter `$DEMO_API/loa1-login` für die Demo-Spalte der Webseite
  (`@DemoSurface`, wie beim Theme-Schalter). PUT ändert zuerst Keycloak und erst danach das Flag.
  Lehnt Keycloak ab, bleibt das Flag, wie es war.
- **Abgleich beim Start.** Nach den Keycloak-Migrationen stellt der Orchestrator den Subflow einmal
  auf den Stand des Flags. So geht ein gewähltes Passwortformular nicht still verloren, wenn das
  Realm neu aufgebaut wird.
- **„Demo zurücksetzen“** schaltet auf die Verfahrensauswahl zurück. Die Server-Info meldet, welche
  Belegung gerade aktiv ist (`keycloak.loa1Login`).

Welche Verfahren auf `loa1` zählen, entscheidet in beiden Belegungen die Richtlinie (Policy) des
Orchestrators. Der Schalter bestimmt nur, wer fragt.
