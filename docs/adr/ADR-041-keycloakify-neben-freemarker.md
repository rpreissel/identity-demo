# ADR-41: Keycloakify läuft neben FreeMarker, der Orchestrator schaltet realmweit um

**Status:** umgesetzt 2026-09.

**Entscheidung.** Die Seiten des Web-Kanals in Keycloak (Anmeldung, Registrierung, Step-up und die
Verwaltung der Anmeldeverfahren) gibt es in zwei Themes: `orchestrator` mit FreeMarker-Vorlagen und
`orchestrator-keycloakify` mit React-Komponenten. Beide liegen immer in Keycloak. Welches gilt,
entscheidet ein Schalter im Orchestrator, der das Login-Theme des Realms zur Laufzeit umstellt.
Beide Themes erfüllen denselben Seitenvertrag und sehen gleich aus.

**Erwogene Alternative.** FreeMarker ganz durch Keycloakify ersetzen. Dann hätte jede Seite auf
einmal umgestellt werden müssen, und ein Vergleich beider Darstellungen wäre nicht mehr möglich.

**Preis.** Zwei Darstellungen derselben Seiten, die denselben Vertrag erfüllen müssen, und ein
zweites npm-Paket mit eigenem Build.

## 1) Zwei Themes, realmweit umgeschaltet

- **Das FreeMarker-Theme ist das Eltern-Theme** des Keycloakify-Themes. Eine Seite ohne
  React-Komponente zeigt Keycloak deshalb mit der FreeMarker-Vorlage, im selben Aussehen.
- **Umgeschaltet wird das Login-Theme des Realms** über die Admin-API von Keycloak:
  `PUT /admin/realms/{realm}` mit nur `loginTheme`. Keycloak übernimmt nur die gesetzten Felder.
- **Alles wechselt mit:** alle Clients, der Step-up und die Required Action zur Verwaltung der
  Verfahren. Der OIDC-Ablauf im Frontend bleibt unverändert.
- **Die Wirkung ist sofort da,** auch mitten in einer laufenden Anmeldung. Das ist vertretbar, weil
  beide Themes denselben Seitenvertrag erfüllen (Abschnitt 3).
- **Keine neuen Rechte:** Der Orchestrator schaltet mit dem Migrations-Client
  `orchestrator-migration`, der das Realm ohnehin aufbaut. Der Service-Account `orchestrator-admin`
  behält sein einziges Recht.
- **Kein neuer Code in der Extension,** keine eigene SPI zur Wahl des Themes.

## 2) Der Schalter im Orchestrator

- **Der Orchestrator ist die Quelle der Wahrheit,** Keycloak wird nachgezogen. Der Schalter ist das
  Feature-Flag `FeatureFlags.KEYCLOAK_LOGIN_KEYCLOAKIFY`. Keine Zeile heißt FreeMarker.
- **Endpunkt:** `$ADMIN_API/login-theme` mit GET und PUT, hinter dem Admin-Login. PUT setzt zuerst
  das Theme im Realm und erst dann den Schalter. Lehnt Keycloak ab, bleibt der Schalter, wie er war.
- **Abgleich beim Start:** Nach den Keycloak-Migrationen setzt der Orchestrator das Theme einmal auf
  den Stand des Schalters. Ein neu aufgebautes Realm fällt so nicht still auf FreeMarker zurück.
- **Demo zurücksetzen** schaltet auf FreeMarker zurück. Die Server-Info meldet das aktive Theme.

## 3) Was beide Themes teilen

**Der Seitenrahmen zeigt den Seitentitel, nicht den technischen Nutzernamen.** Keycloaks Basisvorlage
ersetzt den Titel durch den „attempted username“ mit Neustart-Link, sobald ein Nutzer an der
Anmeldung hängt. Hier hängt er, sobald der Orchestrator ein Konto nennt, bei der Registrierung also
das Konto im Aufbau mit dem Namen `account-<id>` (ADR-46), das niemandem etwas sagt. Das
FreeMarker-Theme hat deshalb eine eigene `template.ftl` (Kopie der Basisvorlage mit dieser einen
Änderung): immer der Seitentitel, der Nutzername nur, wenn er eine E-Mail-Adresse ist. Das
Keycloakify-Theme (`Layout.tsx`) zeigt von sich aus nur den Titel.

**Texte.** `KcTexts` liest die Texte aus den Messages des aktiven Themes. Das Keycloakify-Theme erbt
sie vom FreeMarker-Theme, ohne Kopie. Im Browser kommt von den FreeMarker-Texten nichts an, deshalb
setzt `WebFormRenderer` neben `t` ein zweites Attribut `texts`: die Texte der Seite als einfache Map.

- **Nur die der Seite:** Der Theme-Build (`keycloak-theme/scripts/texts-per-page.mjs`) sammelt je
  Seite die `t("…")`-Vorlagen der Komponente und ihrer Importe und schreibt sie als
  `orchestratorTexts.<pageId>` in die `theme.properties`. `KcTexts.forBrowser` schickt nur diese.
- **Den Seitennamen vor dem Rendern** nennt jede Tool-Factory über `WebToolRendererFactory.template()`.
- **Eine geänderte Übersetzung** zeigt das Theme ohne neuen Build. Neu bauen muss man nur, wenn eine
  React-Seite eine neue Vorlage bekommt.
- **`/translate-texts`** sieht beide Themes: `npm run texts:export` schreibt die Vorlagen des Themes
  in einen Katalog, den `KcTextCatalog` neben den `.ftl`-Vorlagen liest.

**Seitenvertrag.** Jede Seite bekommt dieselben Attribute, gleich welches Theme sie zeigt. Neben `t`
und `texts`:

- `orchestrator-select`: `pageTitle`, `description`, `options`, `optionLabels`, `offerRegistration`
- `orchestrator-tool`: `toolId`, `fields` (aus `stepData.missingFields`)
- `orchestrator-confirm`: `pageTitle`, `confirmLabel`, `cancelLabel`
- `orchestrator-error`: nur die Fehlermeldung
- `orchestrator-manage-methods`: `methods` (je Eintrag `id`, `method`, `label`)
- jede `tool-*`-Seite: `toolId`, `pageTitle`, `hint`, dazu je Tool die Demo-Werte (`demoPassword`,
  `demoTan`, `demoPersonsJson`; Letzteres jede Seite, die Angaben einer Testperson abfragt, auch
  `tool-sms-enroll`), den Schritt (`step`, `personalienPage`), `addressAgain` (E-Mail bestätigen:
  „Zurück“ im Code zeigt die Adressmaske wieder, ohne Serveraufruf) und für
  `tool-qr-wait` `pairingCode`, `deepLink`, `qrDataUri`, `statusUrl`
  ([ADR-45](ADR-045-qr-warteseite-fragt-im-hintergrund.md))

Die Überschrift heißt `pageTitle`, nicht `title`: Keycloak setzt `title` auf jeder Seite selbst und
überschreibt ein gleichnamiges eigenes Attribut. Maßgeblich sind `WebFormRenderer` und die
`*RendererFactory`-Klassen. Was sich dort ändert, gilt für beide Themes.

## 4) Gemeinsames Aussehen

Kein Logo und kein Markenname, nur Farben, Formen und Schrift: warme Grautöne, eckige Kanten ohne
Schatten, Blau nur für den Fokusrahmen, die Systemschrift. Die Werte stehen als CSS-Variablen in
einer Datei, `theme/orchestrator/login/resources/css/tokens.css`. FreeMarker bindet sie über die
`theme.properties` ein, Keycloakify importiert dieselbe Datei.

## 5) Wo es gebaut wird

- **Eigenes npm-Paket `keycloak-theme/`,** nicht in `frontend/`: eine andere Zielgruppe und kein
  gemeinsamer npm-Workspace. Dieselben Werkzeuge: React, Vite, TypeScript, Vitest.
- **Ausgabe als JAR** über `keycloakify build`. Es landet in `/opt/keycloak/providers/`, Keycloak
  findet das Theme darin selbst. Gradle baut es mit eigenen Tasks nach dem Muster von `frontend/`.
- **Das FreeMarker-Theme bleibt im JAR der Extension,** das Keycloakify-Theme gehört nicht hinein.

## 6) Verwaltung der Verfahren

Die Verwaltung der Anmeldeverfahren im Web-Kanal nutzt denselben Mechanismus wie die Anmeldung, nur
in anderer Darstellung. Verworfen wurden:

- **Das Account-Theme von Keycloak mit dessen REST-API.** Sie kann Verfahren, die Keycloak nicht
  kennt, nicht anlegen oder ändern. Außerdem wanderte die Zuständigkeit für Journey und Policy
  teilweise zu Keycloak.
- **Eine eigene Oberfläche mit eigenem Backend.** Das wäre eine neue Serverkomponente mit eigenem
  Betrieb und eigener Sicherheit, nur für die Selbstverwaltung.
