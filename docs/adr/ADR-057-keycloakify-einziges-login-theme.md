# ADR-57: Keycloakify ist das einzige Login-Theme

**Status:** umgesetzt 2026-10-09 (Issue `DPoP-demo-8a33`). Löst
[ADR-41](ADR-041-keycloakify-neben-freemarker.md) ab.

**Entscheidung.** Die Seiten des Web-Kanals in Keycloak gibt es nur noch in einem Theme:
`orchestrator-keycloakify`, geschrieben mit Keycloakify als React-Komponenten. Das sind die Seiten
für Anmeldung, Registrierung, Step-up und die Verwaltung der Anmeldeverfahren. Das FreeMarker-Theme
`orchestrator` und der Schalter zwischen beiden Themes entfallen. Das Realm setzt das Theme beim
Aufbau (`keycloak-setup.base.loginTheme`), der Orchestrator stellt es zur Laufzeit nicht mehr um.

**Warum.** ADR-41 hatte beide Themes nebeneinander gestellt, um schrittweise umzustellen und beide
Darstellungen zu vergleichen. Inzwischen hat jede Seite der Extension eine React-Komponente. Zwei
Darstellungen derselben Seiten kosteten bei jeder Änderung doppelte Arbeit: jede neue Tool-Seite
zweimal, jeder Test in beiden Themes. Einen Vergleich braucht die Demo nicht mehr.

**Erwogene Alternative.** Beide Themes behalten, FreeMarker aber nicht mehr pflegen. Dann wären
veraltete Seiten über den Schalter weiter erreichbar gewesen. Verworfen.

**Preis.** Ohne Eltern-Theme `orchestrator` fällt eine Seite ohne eigene Komponente auf Keycloaks
Standarddarstellung zurück (Keycloakify), nicht mehr auf eine eigene FreeMarker-Vorlage. Eine neue
Seite der Extension braucht deshalb immer eine React-Komponente.

## 1) Texte

- Die eigenen Texte der Login-Seiten liegen in `keycloak-theme/messages/messages_<lang>.properties`.
  `/translate-texts` schreibt sie (Bundle `keycloak`, [ADR-33](ADR-033-texte-als-vorlage-im-code.md)).
- Beim Build hängt `scripts/append-messages.mjs` (Hook `postBuild` des Keycloakify-Plugins) sie an
  die Message-Bundles im Theme-JAR an. `KcTexts` liest sie dort über das aktive Theme, so wie
  vorher aus dem Eltern-Theme.
- Jede Seite bekommt das Attribut `texts`: nur die Texte, die ihre Komponente nutzt. Der Build
  (`scripts/texts-per-page.mjs`) schreibt sie je Seite als `orchestratorTexts.<pageId>` in die
  `theme.properties`, und `KcTexts.forBrowser` schickt nur diese. Das Attribut `t` für
  FreeMarker-Vorlagen entfällt.
- `KcTextCatalog` sammelt die Vorlagen aus dem Java-Code und aus dem Katalog des Themes
  (`npm run texts:export`).

## 2) Seitenvertrag

Maßgeblich sind `WebFormRenderer` und die `*RendererFactory`-Klassen der Extension. Die Seiten-IDs
behalten ihre Namen (`orchestrator-select.ftl`, `tool-sms-enroll.ftl` …), weil Keycloak Seiten so
benennt. Jede Seite bekommt `texts` und dazu:

- `orchestrator-select`: `pageTitle`, `description`, `options`, `optionLabels`, `offerRegistration`
- `orchestrator-tool`: `toolId`, `fields` (aus `stepData.missingFields`)
- `orchestrator-confirm`: `pageTitle`, `confirmLabel`, `cancelLabel`
- `orchestrator-error`: nur die Fehlermeldung
- `orchestrator-manage-methods`: `methods` (je Eintrag `id`, `method`, `label`)
- jede `tool-*`-Seite: `toolId`, `pageTitle`, `hint`, dazu je nach Tool die Demo-Werte
  (`demoPassword`, `demoTan`, `demoPersonsJson`), den Schritt (`step`, `personalienPage`),
  `addressAgain` beim Bestätigen der E-Mail-Adresse und für `tool-qr-wait` die Werte
  `pairingCode`, `deepLink`, `qrDataUri` und `statusUrl`
  ([ADR-45](ADR-045-qr-warteseite-fragt-im-hintergrund.md)).

Die Überschrift heißt `pageTitle`, nicht `title`: Keycloak setzt `title` auf jeder Seite selbst. Der
Seitenrahmen (`Layout.tsx`) zeigt immer den Seitentitel, nie den technischen Nutzernamen
`account-<id>` eines Kontos im Aufbau (ADR-46).

**Keycloaks eigene Seiten** (Passwortformular, Info, erneute Anmeldung …) zeichnet Keycloakify mit
seinen Standardseiten, aber im Rahmen `KcTemplate.tsx`, also in unserem Layout statt im
PatternFly-Template. Ihre Klassen bildet `KC_CLASSES` in `KcPage.tsx` auf die des Themes ab. Kennt die
Anmeldung den Nutzer schon, steht unter dem Titel ein Hinweis „Für <Adresse>. Nicht Sie?“, aber nur,
wenn der Name eine E-Mail-Adresse ist. Weil die Extension diesen Seiten keine `texts` schickt, nimmt
das Theme dort seine eigenen Bundles in der Sprache der Anmeldung (`bundledTexts.ts`).

**Demo-Hilfen** wie die Auswahl der Testperson stehen in einer eigenen Spalte neben der Karte (auf
schmalen Bildschirmen darüber), nie im Formular (`demoSlot.ts`, `DemoPersonPicker.tsx`).

## 3) Aussehen

Kein Logo, kein Markenname, nur Farben, Formen und Schrift: warme Grautöne, eckige Kanten ohne
Schatten, Blau nur für den Fokusrahmen, die Systemschrift. Die Werte stehen als CSS-Variablen in
`keycloak-theme/src/login/tokens.css`, `theme.css` importiert sie.

## 4) Wo es gebaut wird

- **Eigenes npm-Paket `keycloak-theme/`,** nicht in `frontend/`: andere Zielgruppe, kein gemeinsamer
  npm-Workspace. Die Werkzeuge sind dieselben: React, Vite, TypeScript, Vitest.
- **Ausgabe als JAR** über `keycloakify build` (Gradle-Task `keycloakThemeBuild`). Das JAR liegt in
  `/opt/keycloak/providers/`, Keycloak findet das Theme darin selbst. Das JAR der Extension enthält
  kein Theme mehr.

## 5) Verwaltung der Verfahren

Unverändert aus ADR-41 Abschnitt 6: Die Verwaltung der Anmeldeverfahren im Web-Kanal nutzt denselben
Mechanismus wie die Anmeldung (Required Action `orchestrator-manage-methods`), nur mit anderer Seite.
Verworfen bleiben das Account-Theme von Keycloak mit dessen REST-API und eine eigene Oberfläche mit
eigenem Backend.
