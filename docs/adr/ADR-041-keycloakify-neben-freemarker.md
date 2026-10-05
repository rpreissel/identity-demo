# ADR-41: Keycloakify läuft neben FreeMarker, der Orchestrator schaltet realmweit um

**Status:** umgesetzt 2026-09.

**Entscheidung.** Auf der Website führt [Keycloak](../glossar/glossar.md) die Anmeldung und zeigt
dafür eigene Seiten. Wie diese Seiten aussehen, bestimmt ein Theme, also ein Paket aus Vorlagen,
Texten und Stilen. Keycloak bringt dafür FreeMarker mit, eine Sprache für HTML-Vorlagen.
Keycloakify ist ein Werkzeug, mit dem man solche Seiten stattdessen als React-Komponenten schreibt.

Die Seiten des Web-Kanals in Keycloak gibt es in zwei Themes. Das sind die Seiten für Anmeldung,
Registrierung, Step-up und die Verwaltung der Anmeldeverfahren.

- `orchestrator` arbeitet mit FreeMarker-Vorlagen.
- `orchestrator-keycloakify` arbeitet mit React-Komponenten.

Beide Themes liegen immer in Keycloak. Welches gilt, entscheidet ein Schalter im Orchestrator. Er
stellt das Login-Theme des Realms zur Laufzeit um. Ein [Realm](../glossar/glossar.md) ist ein
abgeschlossener Bereich in Keycloak mit eigenen Nutzern und Einstellungen. Beide Themes erfüllen
denselben Seitenvertrag und sehen gleich aus.

**Erwogene Alternative.** FreeMarker ganz durch Keycloakify ersetzen. Dann hätten alle Seiten auf
einmal umgestellt werden müssen. Und ein Vergleich beider Darstellungen wäre nicht mehr möglich.

**Preis.** Es gibt zwei Darstellungen derselben Seiten, die denselben Vertrag erfüllen müssen. Dazu
kommt ein zweites npm-Paket mit eigenem Build.

## 1) Zwei Themes, realmweit umgeschaltet

- **Das FreeMarker-Theme ist das Eltern-Theme** des Keycloakify-Themes. Hat eine Seite keine
  React-Komponente, zeigt Keycloak sie deshalb mit der FreeMarker-Vorlage, im selben Aussehen.
- **Umgeschaltet wird das Login-Theme des Realms**, und zwar über die Admin-API von Keycloak:
  `PUT /admin/realms/{realm}` mit nur dem Feld `loginTheme`. Keycloak übernimmt nur die gesetzten
  Felder.
- **Alles wechselt mit:** alle Clients, der Step-up und die Required Action zur Verwaltung der
  Verfahren. Der OIDC-Ablauf im Frontend bleibt unverändert.
- **Die Umstellung wirkt sofort,** auch mitten in einer laufenden Anmeldung. Das ist vertretbar,
  weil beide Themes denselben Seitenvertrag erfüllen (Abschnitt 3).
- **Keine neuen Rechte:** Der Orchestrator schaltet mit dem Migrations-Client
  `orchestrator-migration` um, der das Realm ohnehin aufbaut. Der Service-Account
  `orchestrator-admin` behält sein einziges Recht.
- **Kein neuer Code in der Extension,** keine eigene SPI zur Wahl des Themes.

## 2) Der Schalter im Orchestrator

- **Maßgeblich ist der Orchestrator.** Keycloak wird an seinen Stand angepasst. Der Schalter ist
  das Feature-Flag `KeycloakFeatureFlags.LOGIN_KEYCLOAKIFY`. Ein eigenes Flag für FreeMarker gibt es nicht.
- **Endpunkt:** `$ADMIN_API/login-theme` mit GET und PUT, nur nach Anmeldung als Administrator. PUT
  setzt zuerst das Theme im Realm und erst dann den Schalter. Lehnt Keycloak ab, bleibt der
  Schalter, wie er war.
- **Abgleich beim Start:** Nach den Keycloak-Migrationen setzt der Orchestrator das Theme einmal auf
  den Stand des Schalters. So wechselt ein neu aufgebautes Realm nicht unbemerkt auf FreeMarker
  zurück.
- **Demo zurücksetzen** schaltet auf FreeMarker zurück. Die Server-Info meldet das aktive Theme.

## 3) Was beide Themes teilen

**Der Seitenrahmen zeigt den Seitentitel, nicht den technischen Nutzernamen.** Keycloaks
Basisvorlage ersetzt den Titel durch den „attempted username“ mit einem Link zum Neustart, sobald
der Anmeldung ein Nutzer zugeordnet ist. Hier ist das der Fall, sobald der Orchestrator ein Konto
nennt. Bei der Registrierung ist das also das Konto im Aufbau mit dem Namen `account-<id>`
(ADR-46), der niemandem etwas sagt. Das FreeMarker-Theme hat deshalb eine eigene `template.ftl`.
Sie ist eine Kopie der Basisvorlage mit genau dieser einen Änderung: Sie zeigt immer den
Seitentitel und den Nutzernamen nur, wenn er eine E-Mail-Adresse ist. Das Keycloakify-Theme
(`Layout.tsx`) zeigt von sich aus nur den Titel.

**Texte.** `KcTexts` liest die Texte aus den Messages des aktiven Themes. Das Keycloakify-Theme
erbt sie vom FreeMarker-Theme, ohne Kopie. Im Browser kommen die FreeMarker-Texte aber nicht an.
Deshalb setzt `WebFormRenderer` neben `t` ein zweites Attribut `texts`: die Texte der Seite als
einfache Map.

- **Nur die Texte der jeweiligen Seite:** Der Build des Themes
  (`keycloak-theme/scripts/texts-per-page.mjs`) sammelt je Seite die `t("…")`-Vorlagen der
  Komponente und ihrer Importe. Er schreibt sie als `orchestratorTexts.<pageId>` in die
  `theme.properties`. `KcTexts.forBrowser` schickt nur diese Texte.
- **Den Seitennamen schon vor dem Rendern** nennt jede Tool-Factory über
  `WebToolRendererFactory.template()`.
- **Eine geänderte Übersetzung** zeigt das Theme ohne neuen Build. Neu bauen muss man nur, wenn eine
  React-Seite eine neue Vorlage bekommt.
- **`/translate-texts`** sieht beide Themes: `npm run texts:export` schreibt die Vorlagen des Themes
  in einen Katalog. Diesen liest `KcTextCatalog` neben den `.ftl`-Vorlagen.

**Seitenvertrag.** Jede Seite bekommt dieselben Attribute, gleich welches Theme sie zeigt. Neben `t`
und `texts` sind das:

- `orchestrator-select`: `pageTitle`, `description`, `options`, `optionLabels`, `offerRegistration`
- `orchestrator-tool`: `toolId`, `fields` (aus `stepData.missingFields`)
- `orchestrator-confirm`: `pageTitle`, `confirmLabel`, `cancelLabel`
- `orchestrator-error`: nur die Fehlermeldung
- `orchestrator-manage-methods`: `methods` (je Eintrag `id`, `method`, `label`)
- jede `tool-*`-Seite: `toolId`, `pageTitle`, `hint`, dazu je nach Tool:
  - die Demo-Werte (`demoPassword`, `demoTan`, `demoPersonsJson`). `demoPersonsJson` bekommt jede
    Seite, die Angaben einer Testperson abfragt, auch `tool-sms-enroll`.
  - den Schritt (`step`, `personalienPage`),
  - `addressAgain` beim Bestätigen der E-Mail-Adresse: „Zurück“ zeigt die Eingabemaske für die
    Adresse wieder, ohne Aufruf beim Server, nur im Code der Seite,
  - für `tool-qr-wait` die Werte `pairingCode`, `deepLink`, `qrDataUri` und `statusUrl`
    ([ADR-45](ADR-045-qr-warteseite-fragt-im-hintergrund.md)).

Die Überschrift heißt `pageTitle`, nicht `title`. Keycloak setzt `title` auf jeder Seite selbst und
überschreibt ein gleichnamiges eigenes Attribut. Maßgeblich sind `WebFormRenderer` und die
`*RendererFactory`-Klassen. Was sich dort ändert, gilt für beide Themes.

## 4) Gemeinsames Aussehen

Es gibt kein Logo und keinen Markennamen, nur Farben, Formen und Schrift:

- warme Grautöne,
- eckige Kanten ohne Schatten,
- Blau nur für den Fokusrahmen,
- die Systemschrift.

Die Werte stehen als CSS-Variablen in einer einzigen Datei,
`theme/orchestrator/login/resources/css/tokens.css`. FreeMarker bindet sie über die
`theme.properties` ein, Keycloakify importiert dieselbe Datei.

## 5) Wo es gebaut wird

- **Eigenes npm-Paket `keycloak-theme/`,** nicht in `frontend/`. Die Seiten haben eine andere
  Zielgruppe, und es gibt keinen gemeinsamen npm-Workspace. Die Werkzeuge sind dieselben: React,
  Vite, TypeScript, Vitest.
- **Ausgabe als JAR** über `keycloakify build`. Das JAR liegt in `/opt/keycloak/providers/`, und
  Keycloak findet das Theme darin selbst. Gradle baut es mit eigenen Tasks nach dem Muster von
  `frontend/`.
- **Das FreeMarker-Theme bleibt im JAR der Extension,** das Keycloakify-Theme gehört nicht hinein.

## 6) Verwaltung der Verfahren

Die Verwaltung der Anmeldeverfahren im Web-Kanal nutzt denselben Mechanismus wie die Anmeldung, nur
mit anderer Darstellung. Verworfen wurden:

- **Das Account-Theme von Keycloak mit dessen REST-API.** Diese API kann Verfahren, die Keycloak
  nicht kennt, weder anlegen noch ändern. Außerdem wäre die Zuständigkeit für Journey und Policy
  teilweise zu Keycloak gewandert.
- **Eine eigene Oberfläche mit eigenem Backend.** Das wäre eine neue Serverkomponente mit eigenem
  Betrieb und eigener Sicherheit, nur für die Selbstverwaltung.
