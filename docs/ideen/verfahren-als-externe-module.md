# Idee: Verfahren als externe Module

**Worum es geht.** Ein Verfahren, also ein Tool wie `auth-sms` samt seinen Oberflächen, soll in
einem eigenen Repository entstehen können. Es soll als fertiges Artefakt in den Orchestrator, die
Keycloak-Erweiterung, das Login-Theme und die App eingebunden werden. Eingebunden wird zur
Build-Zeit: Eine Distribution setzt den Host und die gewählten Verfahren beim Bauen zusammen.
Plugins, die zur Laufzeit in ein Verzeichnis gelegt werden, sind nicht Ziel.

**Warum das wichtig ist.** Heute liegt jedes Verfahren in vier Bäumen dieses Repositorys:
- im Backend unter `tools/<modul>`,
- in der Erweiterung unter `webtool/<modul>`,
- im Theme unter `pages/Tool*.tsx`,
- im Frontend unter `tools/<modul>`.

Ein neues Verfahren eines anderen Teams oder Anbieters lässt sich nur durch Änderungen in all diesen
Bäumen ergänzen. Die Grenze, die [tool_api](../08-projektrahmen.md#fachkern-und-technik) im
Backend schon zieht, soll für alle Schichten gelten und als Artefakt greifbar werden.

**Stand: Konzept, nicht umgesetzt** (Stand 2026-10-10).

---

## 1) Ausgangslage: was heute schon offen ist

Vieles ist schon so gebaut, dass es Klassen und Dateien findet, statt eine Liste zu pflegen:

- **Backend**
  - `ToolHandlerRegistry` sammelt alle `ToolModule`-Beans. Eine zentrale Liste der Tools gibt es
    nicht.
  - `ToolContextResolver` ordnet eine Route `/tools/api/<toolId>/v<N>` über `ToolController.tool`
    ihrem Tool zu.
  - `ModuleId` und `ModuleApiGroups` durchsuchen den Klassenpfad nach `@ApplicationModule` und
    `@RestController`. Fremde Jars sind dabei eingeschlossen.
  - `ModuleMigrationLocations` findet `classpath*:db/migration/*/`, also auch Ordner in fremden
    Jars.
- **Keycloak-Erweiterung**
  - Das SPI `webToolRenderer` ist nicht intern.
  - `WebToolAvailability.renderableTools` meldet jede installierte `WebToolRendererFactory`, egal
    aus welchem Jar sie kommt.
- **Frontend:** `tools/registry.ts` lädt `./*/index.tsx` per `import.meta.glob`. Ein Tool muss
  nirgends sonst eingetragen werden.

## 2) Was ein externes Verfahren heute blockiert

**Backend**
- **Kein Vertragsartefakt:**
  - `contract/tool_api` und `contract/texts` sind Pakete im Root-Projekt, kein eigenes Modul.
  - Sie hängen an Spring, Jackson, JPA und Servlet.
  - `Text.onWire` wird aus den Root-Tests gesetzt.
- **Direkter Griff auf Simulationen:** `auth_sms` importiert `simulation.sms`, `auth_email`
  importiert `simulation.mail`, `auth_kobil` importiert `simulation.kobil`, `ident_nect` importiert
  `simulation.nect`.
- **Fester Paketstamm:** Spring-Scan, `ModuleId`, Spring Modulith und die Architekturtests setzen
  `com.example.identity.tools.<id>` voraus.
- **Eine Flyway-Historie:** Die Versionsnummern laufen über alle Module durch
  ([Konventionen](../../src/main/resources/db/migration/KONVENTIONEN.md)). Zwei Artefakte mit
  derselben Nummer verhindern den Start. `demo_views` und `demo_seed` lesen Tabellen der Tools.
- **Ein Textbundle:** Die Texte aller Tools landen im Bundle `app`. Der Textkatalog durchsucht nur
  dieses Repository.
- **Zentrale Vertragsstände:** `api/openapi.yaml`, `api/contract` und `api/published` enthalten
  jedes Tool. Daraus entstehen `frontend/src/generated` und die Modelle der Erweiterung.
- **Tests im Root-Projekt kennen Tool-Interna:** zum Beispiel `RateLimitArchitectureTest`, die
  Fixtures und `catalog.fixture.json`.

**Keycloak-Erweiterung**
- Die SPI-Typen liegen nur im Shadow-Jar, und dort ist Jackson eingebettet.
- Das Dockerfile kopiert zwei Jars mit festem Namen.
- Ein Tool ohne Renderer-Factory bietet die Erweiterung nicht an (`renderableTools`), und
  `versionOf` wirft dann eine Exception.

**Login-Theme**
- `KcPage.tsx` wählt die Seite über einen festen `switch`.
- Keycloakify erzeugt die `.ftl`-Seiten beim Build aus diesem Quellbaum.
- Ein Realm hat genau ein Login-Theme.
- Welche Texte eine Seite bekommt, steht als `orchestratorTexts.<pageId>` in den Einstellungen des
  aktiven Themes.
- Die allgemeine Seite `orchestrator-tool.ftl` zeigt nur rohe Textfelder, ohne Titel, Hinweis oder
  Feldarten.

**App (Frontend)**
- Der Glob findet nur Tools im eigenen Quellbaum.
- Die Host-Typen (`ToolModule`, `ToolRenderContext`, `CallerProof`) sind kein Paket.
- `AppChannelApp.tsx` behandelt Nect (Rückkehr per URL) und KOBIL (Aufräumen beim Start) eigens.
- Die Demo-Seiten sind eine geschlossene `Area`-Union mit festen Vite-Einstiegen.

## 3) Zielbild: ein Verfahren besteht aus vier Artefakten gegen vier SDKs

**Der Host stellt vier SDKs bereit:**

| SDK | Form | Inhalt |
|---|---|---|
| `identity-tool-api` | Maven | das heutige `contract/tool_api` und `contract/texts`. Die Regel „keine Beans“ (M-2) bleibt. |
| `identity-kc-webtool-api` | Maven, in Keycloak `compileOnly` | `WebToolRenderer`, `WebToolRendererFactory`, `WebToolRenderContext` und eine schmale Sicht auf die Einstellungen. Jackson stellt die Erweiterung bereit; das API-Jar bettet es nicht ein. |
| `@identity/tool-sdk` | npm | `ToolModule`, `ToolRenderContext`, `CallerProof`, die Typen des Umschlags, `t`/`Tx`/`resolveText`, `submitViaPatch` und die gemeinsamen Formulare aus `tools/shared`. React ist Peer-Abhängigkeit, damit `t()` dieselben Bundles sieht wie der Host. |
| `@identity/kc-theme-sdk` | npm | `ToolForm`, `Field` und die Seitentypen für Login-Seiten. |

**Ein externes Verfahren liefert:**
- ein Backend-Jar mit Paket `…tools.<id>`, eigenem Schema, eigenem Textbundle und seinem Vertrag
  `contract/tools/<toolId>/v<N>.yaml`,
- ein Provider-Jar mit seiner `WebToolRendererFactory`,
- ein npm-Paket mit der App-Oberfläche (`ToolModule`),
- optional ein npm-Paket mit eigenen Login-Seiten,
- optional seine Simulation, als Jar und Demo-Seite.

## 4) Die Schnitte im Einzelnen

- **Paketstamm.** Externe Verfahren behalten `com.example.identity.tools.<id>`. Scan, `ModuleId`,
  Spring Modulith und die Architekturtests funktionieren dann ohne Umbau. Ein einstellbarer Stamm
  wäre sauberer, kostet aber Änderungen an allen Stellen, die suchen. Das ist eine offene
  Entscheidung (Abschnitt 7).
- **Simulationen.** Ein Tool spricht ein Fremdsystem nur über einen Port in `tool_api` an, wie
  heute schon `PersonDirectory` oder `KeyService`. `SmsGateway` und `MailServer` werden solche
  Ports. Die Simulation implementiert den Port in einem eigenen Artefakt oder gehört zum
  Verfahren. Das schließt die heutigen Ausnahmen in M-3 (`auth_sms → sms` usw.).
- **Flyway.** Zwei Wege:
  - jedes Modul mit eigener Historie (eine Flyway-Instanz je Schema), oder
  - ein fester Nummernbereich je Modul.

  Der erste Weg ist robuster. Es gibt ohnehin keine Fremdschlüssel über Schemas hinweg (ADR-16).
  Die Demo-Sichten auf Tool-Tabellen (`demo_views`) wandern in das jeweilige Modul.
- **Texte.** Jedes Modul hat ein eigenes Bundle (`texts/<moduleId>/…`) und einen eigenen Pfad,
  über den es seine Texte ausliefert. Das gibt es schon für die Simulationen (`NECT_TEXTS`). Der
  Textkatalog und `/translate-texts` arbeiten je Artefakt. Im Frontend sucht `wordingOf` ohnehin
  über alle geladenen Bundles; der Host lädt dazu die Bundles der eingebundenen Module.
- **Vertrag.**
  - Jedes Verfahren veröffentlicht `contract/tools/<toolId>/v<N>.yaml` gegen den Umschlag
    (`envelope.yaml`).
  - Der Host erzeugt seine Typen aus dem Umschlag und den eigenen Tools, das Modul aus seinem
    eigenen Vertrag. Schrittdaten unbekannter Art kommen beim Host als `UnknownStepData` an. Das
    ist heute schon so vorgesehen.
  - `checkPublishedApiCompatibility` läuft je Artefakt.
- **Keycloak-Erweiterung.** Provider-Jars kommen über ein Verzeichnis ins Image, nicht mehr über
  feste Dateinamen.
- **Login-Seiten.**
  - **Standardweg:** eine echte allgemeine Tool-Seite. Der Renderer beschreibt seine Felder
    deklarativ: Art, Beschriftung, Hinweis, Auswahl, Demo-Wert. Die Seite zeichnet sie daraus. Die
    meisten Verfahren brauchen damit keine eigene React-Seite.
  - **Eigene Seiten:** Wer eine braucht, liefert sie als npm-Paket. Der Build des Themes nimmt sie
    über eine Seitentabelle auf, die aus den Abhängigkeiten entsteht.
  - **Verworfen: ein Child-Theme je Verfahren.** Ein Realm hat nur ein Login-Theme, mehrere
    Verfahren ließen sich so nicht kombinieren.
- **App.**
  - Die Registry entsteht aus einer Host-Liste: einem Vite-Plugin, das die eingebundenen Pakete
    aus `package.json` aufnimmt.
  - `ToolModule` bekommt Hooks für den Lebenszyklus, zum Beispiel `onBoot` und `handleReturn(url)`.
    Sie ersetzen die Sonderfälle für Nect und KOBIL in `AppChannelApp.tsx`.
  - Demo-Seiten einer Simulation melden sich über ein Manifest an (Bereich, Pfad, Bundle) statt
    über die geschlossene `Area`-Union.
- **Tests.**
  - Die Tests eines Verfahrens wandern mit ihm.
  - Der Host behält Tests, die nur den Vertrag nutzen. Am besten von außen, siehe
    [Black-Box-Contract-Tests](black-box-contract-tests.md).
  - Host-Tests importieren keine Tool-Interna mehr.
  - `catalog.fixture.json` entsteht aus dem Katalog, statt gepflegt zu werden.

## 5) Distribution

Ein eigenes Assembly-Projekt (Gradle und npm-Workspace) nennt den Host und die gewählten Verfahren
mit ihren Fassungen. Es baut daraus drei Teile:
- das Orchestrator-Jar mit allen Backend-Jars,
- das Keycloak-Image mit der Erweiterung, den Provider-Jars und dem Theme samt den Seiten aus den
  Paketen,
- das Frontend mit den Tool-Paketen.

Die Kompatibilität sichern die SDK-Fassungen und die veröffentlichten Tool-Verträge.

## 6) Schritte in sinnvoller Reihenfolge

Jeder Schritt lohnt sich für sich, auch ohne fremdes Repository:

1. `contract/tool_api` und `contract/texts` als eigenes Gradle-Modul `:tool-api`.
2. Die Zugriffe auf Simulationen hinter Ports in `tool_api`.
3. Ein internes Verfahren, etwa `auth_email`, als eigenes Gradle-Modul `:tools:auth-email`. Das
   beweist den Schnitt im Monorepo.
4. Ein Textbundle und eine Flyway-Historie je Modul.
5. Das Webtool-API-Jar und die allgemeine Tool-Seite im Theme.
6. `@identity/tool-sdk` und die Hooks für den Lebenszyklus in der App.
7. Das Assembly-Projekt; danach das erste Verfahren in ein eigenes Repository.

## 7) Offene Entscheidungen

- **Versionierung:** Wie werden die SDKs gegen den Host versioniert (SemVer,
  Kompatibilitätsmatrix)? Und wie lange unterstützt der Host eine alte SDK-Fassung (vgl.
  [Abschied von alten Fassungen](tool-versionen.md))?
- **Simulationen:** Gehören sie zum Verfahren oder zur Demo?
- **Paketstamm:** Bleibt er fest, oder wird er einstellbar?
- **Eigene Login-Seiten:** Braucht es sie überhaupt, oder reicht die allgemeine Tool-Seite?
  Kandidaten für eigene Seiten sind heute QR-Warten und Nect.
